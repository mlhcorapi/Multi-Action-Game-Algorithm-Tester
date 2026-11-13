package magat.calibrate;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.WriterConfig;
import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.game.Game;
import magat.game.Scenario;
import magat.machine.MachineProfile;
import magat.meter.Meter;
import magat.run.RunGuard;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * How many workers a run should use per budget level on this machine.
 *
 * <p>A throughput question, not a validity one: budgets are deterministic counts, so the thread count
 * cannot change any result, only how long the run takes. Four effects bend the curve down, and the
 * sweep is built to see them rather than assume them: all-core frequency scaling (the clock is sampled
 * before and after each point where the OS exposes it), sustained-power throttling (every point is two
 * timed segments and the knee is chosen on the second), SMT (never swept: the ladder stops at physical
 * cores) and memory (the heap still held after collections gives a per-task footprint, and a point that
 * would not fit is skipped; a point that runs out of memory anyway ends the sweep, and the knee is chosen
 * from the points before it).
 *
 * <p>The choice is the knee: the SMALLEST thread count within {@link #KNEE_TOLERANCE} of the best
 * sustained throughput. A sweep that ran out of time with fewer than three points does not report its
 * bottom rung as a knee; it falls back to a memory-aware estimate and says so.
 *
 * <p>Writes {@code thread_sweep.csv} (appended per level) and {@code threads.json}
 * ({@code {"levels": {"<budget>": {"threads": n, "estimate": false}}}}), which runs read by default.
 *
 * <p>Pilot-sized points, two segments, the knee rule, the memory gate after two points and the
 * truncated-sweep fallback. The gate's footprint is the heap held after collections, not the peak heap in
 * use: the peak also counts garbage not yet collected, which grows with the heap rather than with the
 * workers, so it projected the R7615's mid and upper thread counts past the heap and skipped them.
 */
public final class ThreadSweep {

    public static final double KNEE_TOLERANCE = 0.03;
    public static final double HEAP_SAFETY = 0.60;
    public static final int TURNS_PER_THREAD = 16;
    public static final long MIN_SWEEP_MILLIS = 8 * 60_000L;
    public static final long MAX_SWEEP_MILLIS = 90 * 60_000L;
    public static final double THROTTLE_FLAG = 0.05;
    public static final String CSV = "thread_sweep.csv";
    public static final String JSON = "threads.json";
    private static final int MIN_TURNS_PER_THREAD = 3;
    private static final int SWEEP_SCENARIOS = 64;
    private static final int DECLINE_PATIENCE = 2;
    public static final String HEADER = "Budget,Threads,Turns,ElapsedMs,TurnsPerMin,SustainedTurnsPerMin,ThrottlePct,"
            + "SpeedupVsFirst,EfficiencyPct,ClockStartMHz,ClockEndMHz,ClockDropPct,PeakHeapMB,PerTaskHeapMB,Chosen";

    private ThreadSweep() {}

    public static final class Options {
        public long[] levels = {40_000L, 80_000L, 400_000L};
        /** Explicit ladder; null derives one from the machine profile. */
        public int[] ladder;
        /** Target wall-clock of one timed segment. */
        public long segmentMillis = 25_000L;
    }

    /** Outcome of one level's sweep. */
    public static final class Level {
        public final long budget;
        public final int chosen;
        public final boolean estimate;
        public final List<String> rows;

        Level(long budget, int chosen, boolean estimate, List<String> rows) {
            this.budget = budget;
            this.chosen = chosen;
            this.estimate = estimate;
            this.rows = rows;
        }
    }

    public static <S, A> List<Level> run(Game<S, A> game, AgentFactory<S, A> arm, List<Scenario<S>> portfolio,
                                         String portfolioIdentity, Path out, Options o) throws IOException {
        MachineProfile machine = MachineProfile.get();
        machine.printBanner("thread sweep");
        Path csv = out.resolve(CSV);
        List<Level> levels = new ArrayList<>();
        Meter.allowUnpriced(true);          // throughput only: the pricing in force does not matter here
        try {
            for (long budget : o.levels) {
                if (RunGuard.stopRequested()) break;
                System.out.println("\n=== budget " + budget + " ===");
                Level l = sweep(game, arm, portfolio, budget, o.ladder != null ? o.ladder : machine.threadLadder(), o.segmentMillis, machine);
                levels.add(l);
                List<String> withBudget = new ArrayList<>();
                for (String r : l.rows) withBudget.add(budget + "," + r);
                appendCsv(csv, withBudget);
                writeJson(out.resolve(JSON), game.id(), arm.id(), portfolioIdentity, machine, l);
                System.out.println("  -> " + l.chosen + " threads at " + budget + (l.estimate ? " (ESTIMATE)" : "") + "   (" + out.resolve(JSON) + ")");
            }
        } finally {
            Meter.allowUnpriced(false);
        }
        return levels;
    }

    private static <S, A> Level sweep(Game<S, A> game, AgentFactory<S, A> arm, List<Scenario<S>> portfolio, long budget,
                                      int[] ladder, long segmentMillis, MachineProfile machine) {
        System.out.println("  ladder " + Arrays.toString(ladder) + "   (ceiling " + machine.maxWorkers() + " = " + machine.physicalCores()
                + " physical cores - " + machine.reserveCores() + " reserved; SMT siblings never used)");
        benchmark(game, arm, budget, Math.min(4, machine.maxWorkers()), portfolio.subList(0, Math.min(4, portfolio.size())), 2);
        System.gc();
        long baselineHeap = liveHeap(), maxHeap = Runtime.getRuntime().maxMemory();

        List<String> rows = new ArrayList<>();
        List<double[]> points = new ArrayList<>();      // {threads, sustained}
        double best = 0, perTaskMB = 0, basePerThread = 0;
        int declines = 0;
        long start = System.currentTimeMillis();
        boolean truncated = false;

        int pilotThreads = Math.max(1, ladder[0]);
        int pilotScen = Math.min(portfolio.size(), Math.max(8, pilotThreads));
        long pilotMs = benchmark(game, arm, budget, pilotThreads, spread(portfolio, pilotScen), 1);
        double msPerTurnPerThread = Math.max(1e-3, pilotMs / (pilotScen / (double) pilotThreads));
        int perThreadPlan = (int) Math.round(segmentMillis / msPerTurnPerThread);
        perThreadPlan = Math.max(MIN_TURNS_PER_THREAD, Math.min(perThreadPlan, 4 * TURNS_PER_THREAD));
        // One fixed subset for every point, sized from the pilot, so only the thread count varies.
        int subsetSize = Math.max(8, Math.min(Math.min(SWEEP_SCENARIOS, portfolio.size()), perThreadPlan * pilotThreads));
        List<Scenario<S>> subset = spread(portfolio, subsetSize);
        long perPointMs = (long) (2 * perThreadPlan * msPerTurnPerThread);
        long sweepBudget = Math.max(MIN_SWEEP_MILLIS, Math.min(MAX_SWEEP_MILLIS, (long) (ladder.length * perPointMs * 1.3)));
        int lastPerThread = perThreadPlan;
        System.out.println(String.format(Locale.ROOT, "  pilot: %d turns on %d threads in %.1fs => %.2fs per turn per thread",
                pilotScen, pilotThreads, pilotMs / 1000.0, msPerTurnPerThread / 1000.0));
        System.out.println(String.format(Locale.ROOT, "  workload: %d positions (fixed), ~%d turns/thread/segment => ~%.0fs per point; budget %.0f min",
                subsetSize, perThreadPlan, perPointMs / 1000.0, sweepBudget / 60000.0));
        System.out.println(String.format(Locale.ROOT, "  %8s %7s %8s %10s %12s %12s %9s %10s %11s %12s",
                "threads", "t/thr", "turns", "elapsedMs", "turns/min", "sustained", "2ndHalf", "speedup", "efficiency", "peakHeapMB"));

        for (int threads : ladder) {
            if (RunGuard.stopRequested()) break;
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > sweepBudget) {
                System.out.println(String.format(Locale.ROOT, "  (sweep budget of %.0f min reached with %d point(s))", sweepBudget / 60000.0, points.size()));
                truncated = true;
                break;
            }
            // Memory gate, only from the second point: the first point's per-task estimate is the most inflated.
            if (perTaskMB > 0 && points.size() >= 2) {
                double projected = (baselineHeap >> 20) + perTaskMB * threads, allowed = (maxHeap >> 20) * HEAP_SAFETY;
                if (projected > allowed) {
                    System.out.println(String.format(Locale.ROOT, "  %8d  SKIPPED - projected %.0f MB held after collections exceeds %.0f MB "
                            + "(%.0f%% of the %.0f MB heap; more heap: MAGAT_HEAP)", threads, projected, allowed, 100 * HEAP_SAFETY,
                            maxHeap / (double) (1 << 20)));
                    break;
                }
            }
            int wanted = (int) Math.round(segmentMillis / msPerTurnPerThread);
            int perThread = Math.max(MIN_TURNS_PER_THREAD, Math.min(wanted, Math.min(3 * lastPerThread, 4 * TURNS_PER_THREAD)));
            lastPerThread = perThread;
            int visits = Math.max(1, (int) Math.ceil(perThread * threads / (double) subsetSize));
            int segTurns = subsetSize * visits;

            double clockStart = MachineProfile.currentClockMHz();
            HeapSampler sampler = new HeapSampler();
            sampler.start();
            long msA, msB;
            try {
                msA = benchmark(game, arm, budget, threads, subset, visits);
                msB = benchmark(game, arm, budget, threads, subset, visits);
            } catch (OutOfMemory e) {
                sampler.stopAndPeak();
                System.gc();
                System.out.println(String.format(Locale.ROOT, "  %8d  OUT OF MEMORY in a %.0f MB heap - stopping the sweep; the knee comes "
                        + "from the points before it (more heap: MAGAT_HEAP)", threads, maxHeap / (double) (1 << 20)));
                break;
            }
            long peak = sampler.stopAndPeak(), held = sampler.peakHeld();
            double clockEnd = MachineProfile.currentClockMHz();

            long ms = msA + msB;
            if (segTurns > 0) msPerTurnPerThread = msB / (segTurns / (double) threads);
            double rateA = msA > 0 ? segTurns * 60000.0 / msA : 0, rateB = msB > 0 ? segTurns * 60000.0 / msB : 0;
            double rate = ms > 0 ? 2 * segTurns * 60000.0 / ms : 0, sustained = rateB;
            double throttlePct = rateA > 0 ? 100.0 * (1.0 - rateB / rateA) : 0;
            double perThreadRate = sustained / threads;
            if (basePerThread <= 0) basePerThread = perThreadRate;
            double speedup = basePerThread > 0 ? sustained / basePerThread : threads;
            double effPct = basePerThread > 0 ? 100.0 * perThreadRate / basePerThread : 100.0;
            double clockDrop = clockStart > 0 && clockEnd > 0 ? 100.0 * (1.0 - clockEnd / clockStart) : Double.NaN;
            double peakMB = peak / (double) (1 << 20);
            // The LATEST point's footprint, never the running maximum: the heap holds a fixed part the workers do not own.
            // What collections could not free, not the peak in use: the peak also counts garbage awaiting collection.
            perTaskMB = Math.max(0.0, (held - baselineHeap) / (double) (1 << 20) / threads);

            System.out.println(String.format(Locale.ROOT, "  %8d %7d %8d %10d %12.1f %12.1f %8.1f%% %10.2f %10.0f%% %12.0f%s",
                    threads, perThread, 2 * segTurns, ms, rate, sustained, throttlePct, speedup, effPct, peakMB,
                    throttlePct >= THROTTLE_FLAG * 100 ? "   (2nd half slower)" : ""));
            rows.add(String.format(Locale.ROOT, "%d,%d,%d,%.2f,%.2f,%.2f,%.3f,%.1f,%s,%s,%s,%.0f,%.2f,",
                    threads, 2 * segTurns, ms, rate, sustained, throttlePct, speedup, effPct, fmt(clockStart), fmt(clockEnd), fmt(clockDrop), peakMB, perTaskMB));
            points.add(new double[]{threads, sustained});
            if (sustained > best) { best = sustained; declines = 0; }
            else if (++declines >= DECLINE_PATIENCE) {
                System.out.println("  (sustained throughput has turned over - stopping the sweep)");
                break;
            }
        }

        int chosen = knee(points, best, machine.maxWorkers());
        boolean estimate = false;
        if (truncated && points.size() < 3) {
            int memCap = machine.heapCap(perTaskMB, baselineHeap / (double) (1 << 20), HEAP_SAFETY);
            chosen = Math.max(chosen, Math.min(memCap, (int) Math.round(0.75 * machine.maxWorkers())));
            estimate = true;
            System.out.println("  *** SWEEP TRUNCATED: only " + points.size() + " point(s); the smallest count tried is not a knee.");
            System.out.println("  Falling back to " + chosen + " threads (75% of the ceiling, capped by the heap). An ESTIMATE: re-run when the machine is free.");
        }
        for (int i = 0; i < rows.size(); i++) {
            int threads = Integer.parseInt(rows.get(i).split(",", -1)[0]);
            rows.set(i, rows.get(i) + (threads == chosen ? "yes" : "no"));
        }
        System.out.println(String.format(Locale.ROOT, "  knee: %d threads (smallest within %.0f%% of the %.1f turns/min sustained peak)",
                chosen, 100 * KNEE_TOLERANCE, best));
        return new Level(budget, chosen, estimate, rows);
    }

    /** Smallest thread count whose sustained throughput is within the tolerance of the best. Points ascend in threads. */
    static int knee(List<double[]> points, double best, int maxWorkers) {
        if (points.isEmpty()) return Math.max(1, maxWorkers / 2);
        double floor = best * (1.0 - KNEE_TOLERANCE);
        for (double[] p : points) if (p[1] >= floor) return (int) p[0];
        return (int) points.get(points.size() - 1)[0];
    }

    /** A sweep point's workers ran out of heap. */
    static final class OutOfMemory extends RuntimeException {
        OutOfMemory(Throwable cause) { super(cause); }
    }

    /** Plans every (position, visit) pair on {@code threads} workers; returns the wall-clock in ms. */
    static <S, A> long benchmark(Game<S, A> game, AgentFactory<S, A> arm, long budget, int threads, List<Scenario<S>> subset, int visits) {
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(Math.max(1, threads)));
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        boolean failed = true;
        try {
            for (Scenario<S> sc : subset)
                for (int v = 0; v < visits; v++) {
                    final int visit = v;
                    fs.add(pool.submit(() -> {
                        if (!RunGuard.stopRequested()) Episode.run(game, sc, visit, arm, budget);
                    }));
                }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof OutOfMemoryError) throw new OutOfMemory(e.getCause());
                    throw new IllegalStateException("a sweep turn failed: " + e.getCause(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            failed = false;
        } finally {
            if (failed) pool.shutdownNow();    // a failed point's trees are released before anything else runs
            RunGuard.shutdownAndWait(pool, 1, TimeUnit.HOURS);
        }
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static <S> List<Scenario<S>> spread(List<Scenario<S>> portfolio, int n) {
        n = Math.max(1, Math.min(n, portfolio.size()));
        List<Scenario<S>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(portfolio.get((int) ((long) i * portfolio.size() / n)));
        return out;
    }

    private static void appendCsv(Path csv, List<String> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(csv)) sb.append(HEADER).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.createDirectories(csv.toAbsolutePath().getParent());
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Adds one level to {@code threads.json}, keeping the levels already there. */
    private static void writeJson(Path file, String game, String arm, String portfolioIdentity, MachineProfile machine, Level l) throws IOException {
        JsonObject root = Files.isRegularFile(file)
                ? Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).asObject() : new JsonObject();
        root.set("format", 1).set("game", game).set("arm", arm).set("portfolio", portfolioIdentity)
            .set("machine", machine.summary()).set("measured", new java.util.Date().toString());
        JsonObject levels = root.get("levels") != null ? root.get("levels").asObject() : new JsonObject();
        levels.set(String.valueOf(l.budget), new JsonObject().add("threads", l.chosen).add("estimate", l.estimate));
        root.set("levels", levels);
        RunGuard.atomicWrite(file, root.toString(WriterConfig.PRETTY_PRINT) + "\n");
    }

    /** {@code budget -> threads} from a {@code threads.json}, for runs. */
    public static java.util.Map<Long, Integer> read(Path file) throws IOException {
        java.util.Map<Long, Integer> out = new java.util.TreeMap<>();
        JsonObject levels = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).asObject().get("levels").asObject();
        for (JsonObject.Member m : levels) out.put(Long.parseLong(m.getName()), m.getValue().asObject().getInt("threads", 1));
        return out;
    }

    /** The CPU model a {@code threads.json} was swept on, or null when it does not say. */
    public static String machineModel(Path file) throws IOException {
        JsonValue m = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).asObject().get("machine");
        return m == null || !m.isString() ? null : MachineProfile.modelOf(m.asString());
    }

    private static String fmt(double v) {
        return Double.isNaN(v) || v < 0 ? "" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * The heap held after each pool's most recent collection, summed over the heap's pools: the live set,
     * plus what those collections did not reclaim. The {@link magat.run.LoadGovernor} reads the same signal.
     * Falls back to the heap in use where the JVM reports no collection usage.
     */
    static long liveHeap() {
        long sum = 0;
        boolean any = false;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() != MemoryType.HEAP) continue;
            MemoryUsage u = p.getCollectionUsage();
            if (u == null) continue;
            sum += u.getUsed();
            any = true;
        }
        return any ? sum : usedHeap();
    }

    /** Samples the heap in the background: its peak in use (reported) and its peak held after collections (the gate's). */
    private static final class HeapSampler extends Thread {
        private volatile boolean running = true;
        private volatile long peak = 0, held = 0;

        HeapSampler() { setDaemon(true); }

        @Override public void run() {
            while (running) {
                long u = usedHeap(), h = liveHeap();
                if (u > peak) peak = u;
                if (h > held) held = h;
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }

        long peakHeld() { return held; }

        /** Not {@code stop()}: Thread declares a final stop() up to JDK 22, a compile error on an older compiler. */
        long stopAndPeak() {
            running = false;
            try { join(500); } catch (InterruptedException ignore) { }
            return peak;
        }
    }
}
