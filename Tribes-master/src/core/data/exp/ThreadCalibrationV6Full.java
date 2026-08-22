package core.data.exp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thread-count calibration for the v6 FULL-GAMES tournament, per budget level.
 *
 * <p>Same reasoning and the same knee rule as {@link ThreadCalibrationV6}; a separate class because
 * the unit of work is different and the difference matters.
 *
 * <h2>Why the isolation phase's calibration does not transfer</h2>
 *
 * <ul>
 *   <li><b>The work item is a game, not a turn.</b> A turn at 400,000 units costs tens of seconds; a
 *       full game is ~45 of them. A scheduler tuned on turns is being tuned on an item two orders of
 *       magnitude smaller than the one it will actually queue, and queue granularity is exactly what a
 *       thread count has to suit.</li>
 *   <li><b>Two agents, alternating.</b> Only the agent to move is searching, so an in-flight game
 *       holds one live tree at a time — the same live-tree count per worker as the isolation phase,
 *       but reached through a different allocation pattern, with a whole {@code Game} and its board
 *       resident between turns.</li>
 *   <li><b>States get bigger as a game runs.</b> The isolation portfolio is captured early; a
 *       tournament game reaches states with many more units and cities, where {@code copy()} and
 *       {@code advance()} both cost more and the tree is larger.</li>
 * </ul>
 *
 * <h2>What it measures, and the one thing it cannot</h2>
 *
 * <p>Sweeps thread counts on real games — same maps, same orientation swap, same agent factories as
 * {@link FullGameRunnerV6} — timed in two segments, and picks the smallest thread count within
 * {@link ThreadCalibrationV6#KNEE_TOLERANCE} of the best <i>sustained</i> throughput. The second
 * segment is the one the knee is chosen on: the clock a server holds for a minute is not the clock it
 * holds for a day, and {@code ThrottlePct} makes the difference visible.
 *
 * <p>The games it times are <b>short</b> — the tick cap is derived from a pilot so that one game
 * lands near {@link #TARGET_GAME_MILLIS} — which means the sweep sees early-game states and therefore
 * <i>under</i>-estimates the per-task heap a late-game turn will hold. That is why the per-task figure
 * is multiplied by {@link #HEAP_DERATE} before it gates anything, and why the live
 * {@link LoadGovernor} during the run, not this sweep, is the real protection against heap pressure.
 * {@link ThreadCalibrationV6} carries the same caveat for the same reason (it probes with EMCTS while
 * the crossover configs hold larger trees).
 *
 * <p><b>None of this can change a result.</b> The budget is a deterministic compute-unit count, so a
 * turn performs identical work whether it runs alone or alongside ninety others. The thread count
 * changes wall-clock and nothing else.
 *
 * <p>Usage: {@code java -Xmx<heap> -cp "out:lib/*" core.data.exp.ThreadCalibrationV6Full
 * [outDir] [cuCsv] [threadLadderCsv]}
 */
public final class ThreadCalibrationV6Full {

    private ThreadCalibrationV6Full() {}

    /** Target wall-clock for ONE calibration game; the tick cap is derived from the pilot to hit it. */
    public static final long TARGET_GAME_MILLIS = 60_000L;

    /**
     * Target wall-clock for one timed segment. Longer than the isolation phase's 25 s because the work
     * item is coarser: a segment has to fit at least one game per thread, and at 400,000 units a game
     * of even two ticks costs well over a minute.
     */
    public static final long TARGET_SEGMENT_MILLIS = 90_000L;

    /** Bounds on the derived tick cap. Below 2 a "game" is barely a game; above 12 it is not short. */
    public static final int MIN_TICK_CAP = 2, MAX_TICK_CAP = 12;

    /** Games per thread per segment, after sizing. At least one — a thread cannot run half a game. */
    private static final int MIN_GAMES_PER_THREAD = 1, MAX_GAMES_PER_THREAD = 6;

    /**
     * The calibration games are early-game, so their trees are smaller than a real tournament turn's.
     * The measured per-task heap is multiplied by this before it gates the ladder.
     */
    public static final double HEAP_DERATE = 2.0;

    /** Fraction of max heap the run may project into; the rest is GC headroom. */
    public static final double HEAP_SAFETY = ThreadCalibrationV6.HEAP_SAFETY;

    /** Stop the sweep after this many consecutive points fail to beat the best sustained throughput. */
    private static final int DECLINE_PATIENCE = 2;

    /** Whole-sweep bounds per budget level, clamped around the derived estimate. */
    private static final long MIN_SWEEP_MILLIS = 10 * 60_000L, MAX_SWEEP_MILLIS = 75 * 60_000L;

    public static final String HEADER =
            "ComputeUnits,Threads,TickCap,Games,ElapsedMs,GamesPerMin,SustainedGamesPerMin,ThrottlePct,"
          + "Speedup,EfficiencyPct,ClockStartMHz,ClockEndMHz,ClockDropPct,PeakHeapMB,PerTaskMB,Chosen";

    public static final class Result {
        public final int chosen;
        public final List<String> rows;
        Result(int chosen, List<String> rows) { this.chosen = chosen; this.rows = rows; }
    }

    /**
     * The matchup mix each calibration game is drawn from: the cheapest config and the most expensive
     * one, so the measured throughput is not a property of whichever agent happened to be probed.
     */
    private static List<FullGameRunnerV6.Matchup> calibMatchups(int cu) {
        return Arrays.asList(
                new FullGameRunnerV6.Matchup("EMCTS", -1, cu),
                new FullGameRunnerV6.Matchup("CrossoverCombo", 5, cu));
    }

    /**
     * Plays {@code games} short games across {@code threads} workers and returns the elapsed millis.
     * Failures are counted, not thrown: a crashed calibration game costs a data point, never the sweep.
     */
    private static long benchmark(int cu, int tickCap, int threads, int games,
                                  List<FullGameMapSetV6.MapInfo> maps, int gameIdOffset) {
        FullGameRunnerV6.applyEngineConstants(tickCap);
        List<FullGameRunnerV6.Matchup> ms = calibMatchups(cu);
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < games; i++) {
                // Spread across maps and orientations, and alternate the matchup, so a point's mix is
                // fixed by construction rather than by which games happened to land first.
                final FullGameRunnerV6.Matchup m = ms.get(i % ms.size());
                final int gameId = (gameIdOffset + i * 7) % FullGameRunnerV6.GAMES_PER_MATCHUP;
                futures.add(pool.submit(() -> {
                    if (RunGuard.stopRequested()) return;
                    try {
                        FullGameRunnerV6.playOne(m, maps.get(FullGameRunnerV6.mapIndexOf(gameId)), gameId, 0);
                    } catch (Throwable t) {
                        System.err.println("[calib-full] game failed: " + t);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } catch (Exception e) {
            System.err.println("[calib-full] benchmark interrupted: " + e);
        } finally {
            RunGuard.shutdownAndWait(pool, 1);
        }
        return System.currentTimeMillis() - t0;
    }

    /** Sweeps thread counts for one budget level and returns the knee. */
    public static Result calibrate(int cu, List<FullGameMapSetV6.MapInfo> maps, int[] candidates,
                                   File csvSink) {
        ServerProfile machine = ServerProfile.get();
        int[] ladder = candidates != null ? candidates : machine.threadLadder();
        System.out.println("  ladder: " + Arrays.toString(ladder)
                + "   (ceiling " + machine.maxWorkers() + " = " + machine.physicalCores()
                + " physical cores - " + machine.reserveCores() + " reserved; SMT siblings never used)");

        int pilotThreads = Math.max(1, ladder[0]);

        // Pilot 1: what does ONE planning episode cost at this budget? Two ticks = ~4 episodes.
        // pilotThreads games on pilotThreads workers run concurrently, so the elapsed time IS one
        // game's time; a game of MIN_TICK_CAP ticks is ~2*MIN_TICK_CAP planning episodes.
        long pilotMs = benchmark(cu, MIN_TICK_CAP, pilotThreads, pilotThreads, maps, 0);
        double msPerEpisode = Math.max(1.0, pilotMs / (2.0 * MIN_TICK_CAP));

        // Size the tick cap so one calibration game lands near TARGET_GAME_MILLIS.
        int tickCap = (int) Math.round(TARGET_GAME_MILLIS / (2.0 * msPerEpisode));
        tickCap = Math.max(MIN_TICK_CAP, Math.min(MAX_TICK_CAP, tickCap));
        double msPerGame = 2.0 * tickCap * msPerEpisode;

        int gamesPerThread = (int) Math.round(TARGET_SEGMENT_MILLIS / msPerGame);
        gamesPerThread = Math.max(MIN_GAMES_PER_THREAD, Math.min(MAX_GAMES_PER_THREAD, gamesPerThread));

        long perPointMs = (long) (2 * gamesPerThread * msPerGame);
        long sweepBudgetMs = Math.max(MIN_SWEEP_MILLIS,
                Math.min(MAX_SWEEP_MILLIS, (long) (ladder.length * perPointMs * 1.3)));

        System.out.printf(Locale.ROOT,
                "  pilot: %d games (cap %d) on %d threads in %.1fs => ~%.1fs per planning episode%n",
                pilotThreads, MIN_TICK_CAP, pilotThreads, pilotMs / 1000.0, msPerEpisode / 1000.0);
        System.out.printf(Locale.ROOT,
                "  workload: tick cap %d => ~%.0fs per game, %d game(s)/thread/segment "
                + "=> ~%.0fs per point; sweep budget %.0f min for %d points%n",
                tickCap, msPerGame / 1000.0, gamesPerThread, perPointMs / 1000.0,
                sweepBudgetMs / 60000.0, ladder.length);

        System.gc();
        long baselineHeap = usedHeap();
        long maxHeap = Runtime.getRuntime().maxMemory();

        List<String> rows = new ArrayList<>();
        List<double[]> points = new ArrayList<>();    // {threads, sustainedGamesPerMin}
        double best = 0, perTaskMB = 0, basePerThread = 0;
        int declines = 0;
        boolean truncated = false;
        long sweepStart = System.currentTimeMillis();

        System.out.printf(Locale.ROOT, "  %8s %7s %8s %10s %12s %12s %9s %10s %11s %12s%n",
                "threads", "g/thr", "games", "elapsedMs", "games/min", "sustained", "2ndHalf",
                "speedup", "efficiency", "peakHeapMB");

        for (int threads : ladder) {
            if (RunGuard.stopRequested()) {
                System.out.println("  (stop requested - ending the sweep early)");
                break;
            }
            long elapsed = System.currentTimeMillis() - sweepStart;
            if (elapsed > sweepBudgetMs) {
                System.out.printf(Locale.ROOT,
                        "  (sweep budget of %.0f min reached after %.0f min - stopping with %d point(s))%n",
                        sweepBudgetMs / 60000.0, elapsed / 60000.0, points.size());
                truncated = true;
                break;
            }
            // Memory gate, never applied on fewer than two measured points: the per-task estimate is
            // (peak - baseline) / threads, and peak carries a fixed component the workers do not own,
            // so the estimate from the smallest point is the most inflated one there is. Gating on it
            // can end a sweep at its second rung and hand back the bottom of the ladder.
            if (perTaskMB > 0 && points.size() >= 2) {
                double projectedMB = (baselineHeap >> 20) + perTaskMB * HEAP_DERATE * threads;
                double allowedMB = (maxHeap >> 20) * HEAP_SAFETY;
                if (projectedMB > allowedMB) {
                    System.out.printf(Locale.ROOT,
                            "  %8d  SKIPPED - projected %.0f MB (per-task x%.1f late-game derate) "
                            + "exceeds %.0f MB of usable heap%n",
                            threads, projectedMB, HEAP_DERATE, allowedMB);
                    break;
                }
            }

            int segGames = gamesPerThread * threads;
            double clockStart = ServerProfile.currentClockMHz();
            HeapSampler sampler = new HeapSampler();
            sampler.start();
            // Two segments: the first pays ramp-up, the second is what the machine SUSTAINS. Distinct
            // gameId offsets so the second segment is not simply the first one replayed from cache.
            long msA = benchmark(cu, tickCap, threads, segGames, maps, 0);
            long msB = benchmark(cu, tickCap, threads, segGames, maps, 3);
            long peak = sampler.stopAndPeak();
            double clockEnd = ServerProfile.currentClockMHz();

            long ms = msA + msB;
            double rateA = msA > 0 ? segGames * 60000.0 / msA : 0;
            double rateB = msB > 0 ? segGames * 60000.0 / msB : 0;
            double rate = ms > 0 ? 2 * segGames * 60000.0 / ms : 0;
            double sustained = rateB;
            double throttlePct = rateA > 0 ? 100.0 * (1.0 - rateB / rateA) : 0;
            if (segGames > 0 && msB > 0) msPerGame = msB / (double) (segGames / (double) threads);

            double perThreadRate = sustained / threads;
            if (basePerThread <= 0) basePerThread = perThreadRate;
            double speedup = basePerThread > 0 ? sustained / basePerThread : threads;
            double effPct = basePerThread > 0 ? 100.0 * perThreadRate / basePerThread : 100.0;
            double clockDropPct = (clockStart > 0 && clockEnd > 0)
                    ? 100.0 * (1.0 - clockEnd / clockStart) : Double.NaN;
            double peakMB = peak / (double) (1 << 20);
            // Always the latest (largest-thread) point, never a running maximum across points.
            perTaskMB = Math.max(0.0, (peak - baselineHeap) / (double) (1 << 20) / threads);

            System.out.printf(Locale.ROOT,
                    "  %8d %7d %8d %10d %12.2f %12.2f %8.1f%% %10.2f %10.0f%% %12.0f%s%n",
                    threads, gamesPerThread, 2 * segGames, ms, rate, sustained, throttlePct,
                    speedup, effPct, peakMB,
                    throttlePct >= 5.0 ? "   (2nd half slower)" : "");

            rows.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.3f,%.3f,%.2f,%.3f,%.1f,%s,%s,%s,%.0f,%.2f,",
                    cu, threads, tickCap, 2 * segGames, ms, rate, sustained, throttlePct, speedup,
                    effPct, fmt(clockStart), fmt(clockEnd), fmt(clockDropPct), peakMB, perTaskMB));
            points.add(new double[]{threads, sustained});

            if (sustained > best) { best = sustained; declines = 0; }
            else if (++declines >= DECLINE_PATIENCE) {
                System.out.println("  (sustained throughput has turned over - stopping the sweep)");
                break;
            }
        }

        int chosen = pickKnee(points, best, machine);

        // A truncated sweep is not a measurement: with one or two points the "knee" is just the
        // smallest thread count tried, and returning it silently is how a 96-core machine ends up
        // running a multi-day level on 8 threads.
        if (truncated && points.size() < 3) {
            int memCap = machine.heapCap(perTaskMB * HEAP_DERATE,
                    baselineHeap / (double) (1 << 20), HEAP_SAFETY);
            int estimate = Math.max(chosen, Math.min(memCap, (int) Math.round(0.75 * machine.maxWorkers())));
            System.out.println();
            System.out.println("  *** SWEEP TRUNCATED — only " + points.size() + " point(s) measured. ***");
            System.out.println("  The smallest thread count tried is NOT a knee, so it is not used.");
            System.out.printf(Locale.ROOT,
                    "  Falling back to %d threads: 75%% of the %d-worker ceiling, capped by the %d the%n"
                    + "  measured per-task heap (%.0f MB x%.1f derate) allows. This is an ESTIMATE.%n",
                    estimate, machine.maxWorkers(), memCap, perTaskMB, HEAP_DERATE);
            chosen = estimate;
        }

        for (int i = 0; i < rows.size(); i++) {
            String[] f = rows.get(i).split(",", -1);
            rows.set(i, rows.get(i) + (Integer.parseInt(f[1]) == chosen ? "yes" : "no"));
        }
        if (csvSink != null) ExpCsv.append(csvSink, HEADER, rows);

        System.out.printf(Locale.ROOT,
                "  knee: %d threads (smallest within %.0f%% of the %.2f games/min SUSTAINED peak)%n",
                chosen, ThreadCalibrationV6.KNEE_TOLERANCE * 100, best);
        if (perTaskMB > 0) {
            double heapMB = maxHeap / (double) (1 << 20);
            double projectedMB = baselineHeap / (double) (1 << 20) + perTaskMB * HEAP_DERATE * chosen;
            System.out.printf(Locale.ROOT,
                    "  per-task heap ~%.0f MB measured on SHORT games; x%.1f derate for late-game trees "
                    + "=> %d threads projects to ~%.0f MB of a %.0f MB heap (%.0f%%). LoadGovernor "
                    + "watches this live during the run.%n",
                    perTaskMB, HEAP_DERATE, chosen, projectedMB, heapMB, 100.0 * projectedMB / heapMB);
        }
        return new Result(chosen, rows);
    }

    /** Smallest thread count whose sustained throughput is within the knee tolerance of the peak. */
    private static int pickKnee(List<double[]> points, double best, ServerProfile machine) {
        if (points.isEmpty()) return Math.max(1, machine.maxWorkers() / 2);
        double floor = best * (1.0 - ThreadCalibrationV6.KNEE_TOLERANCE);
        for (double[] p : points) if (p[1] >= floor) return (int) p[0];
        return (int) points.get(points.size() - 1)[0];
    }

    private static String fmt(double v) {
        return (Double.isNaN(v) || v < 0) ? "" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Samples used heap in the background so a sweep point can report its peak. */
    private static final class HeapSampler extends Thread {
        private volatile boolean running = true;
        private volatile long peak = 0;
        HeapSampler() { setDaemon(true); }
        @Override public void run() {
            while (running) {
                long u = usedHeap();
                if (u > peak) peak = u;
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }
        /** Not named {@code stop()}: {@link Thread#stop()} is final up to JDK 22, so a subclass
         *  method of that name fails to compile there — invisible on a newer compiler, fatal on the
         *  server's JDK 11 target. */
        long stopAndPeak() {
            running = false;
            try { join(500); } catch (InterruptedException ignore) { }
            return peak;
        }
    }

    public static void main(String[] args) throws Exception {
        RunGuard.install("v6 FULL-GAMES thread calibration", "re-run the same command");
        File outDir = new File(args.length > 0 ? args[0] : RunV6FullGames.BASE + "/_calibration");
        int[] cuLevels = args.length > 1 ? GameLengthProbeV6.parseInts(args[1]) : RunV6FullGames.COMPUTE_LEVELS;
        int[] ladder = args.length > 2 ? GameLengthProbeV6.parseInts(args[2]) : null;

        ServerProfile.get().printBanner("v6 full-games thread calibration");
        ExpCsv.ensureDir(outDir);
        File csv = new File(outDir, "thread_calibration_full.csv");
        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();

        for (int cu : cuLevels) {
            System.out.println();
            System.out.println("--- calibrating " + cu + " units ---");
            Result r = calibrate(cu, maps, ladder, csv);
            RunGuard.atomicWrite(new File(outDir, "_tuned_threads_full_" + cu + "cu.txt"),
                    String.valueOf(r.chosen));
            System.out.println("  -> " + cu + "cu uses " + r.chosen + " threads");
            if (RunGuard.stopRequested()) break;
        }
        System.out.println();
        System.out.println("[calib-full] curve written to " + csv.getPath());
        RunGuard.finished();
    }
}
