package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v6 thread-count calibration: finds how many worker threads the isolation phase should use, per
 * budget level, on this machine.
 *
 * <p><b>Why this is a throughput question and not a validity question.</b> v6 budgets on
 * deterministic compute-units, so the thread count cannot change any result — each turn performs the
 * same counted work whether it runs alone or alongside 90 others. Threads only change how long the
 * run takes. That is the whole payoff of a count-based budget, and it is why this calibration is
 * allowed to optimise freely. (The C0 timing calibration, {@link CalibrationRunnerV6}, is the
 * opposite case and must stay single-threaded on a quiesced machine.)
 *
 * <p><b>Why more threads is not simply better.</b> Four effects bend the curve down, and this sweep is
 * built to see all four rather than assume them:
 * <ul>
 *   <li><i>Frequency scaling.</i> A many-core server drops all-core clocks well below its single-core
 *       boost, so per-thread throughput falls as threads are added. Measured directly: the sweep
 *       samples the mean core clock before and after each point.</li>
 *   <li><i>Sustained-power throttling.</i> The clock a server holds for ten seconds is not the clock it
 *       holds for ten hours. Every sweep point is therefore run as TWO timed segments and reported with
 *       the second-half slowdown ({@code ThrottlePct}); a point whose second half is materially slower
 *       than its first is not a throughput number the real run will ever see.</li>
 *   <li><i>SMT.</i> Not swept at all — {@link ServerProfile} caps the ladder at physical cores. This
 *       workload is heavy on state cloning and therefore on memory bandwidth, which SMT does not
 *       multiply.</li>
 *   <li><i>Memory.</i> Every in-flight turn holds a search tree — hundreds of MB at the larger budgets.
 *       Enough concurrent turns and the run degrades into GC thrash or dies outright.</li>
 * </ul>
 *
 * <p><b>What it does.</b> Sweeps thread counts upward on a fixed workload, measures turns/minute in two
 * halves, tracks peak heap to derive a per-task memory footprint, and stops early once throughput has
 * clearly turned over or the next candidate would not fit in the heap. It then picks the <b>knee</b>:
 * the SMALLEST thread count within {@link #KNEE_TOLERANCE} of the best <i>sustained</i> throughput,
 * rather than the peak itself. Paying 50% more threads (and 50% more memory and heat) for 2% more
 * throughput is a bad trade on a run this long.
 *
 * <p><b>Per budget level.</b> Tree size grows with the budget, so the memory ceiling — and therefore
 * the best thread count — differs between the smallest and largest level. {@link RunExperimentV6}
 * calibrates each level separately and checkpoints the result per level.
 *
 * <p><b>Interrupt-safe.</b> Rows are appended to {@code thread_calibration.csv} as each sweep point
 * finishes, and the sweep aborts cleanly on {@link RunGuard#stopRequested()}, so a stop during
 * calibration loses at most the point in progress.
 *
 * <p>Usage: {@code java -Xmx<heap> -cp "out:lib/*" core.data.exp.ThreadCalibrationV6 [portfolioDir]
 * [outDir] [cuCsv]}
 */
public class ThreadCalibrationV6 {

    /** Accept the smallest thread count within this fraction of the best sustained throughput. */
    public static final double KNEE_TOLERANCE = 0.03;   // 3%

    /** Fraction of max heap the run is allowed to project into. The rest is headroom for GC and for
     *  the crossover configs, whose trees are larger than the EMCTS probe used here. */
    public static final double HEAP_SAFETY = 0.60;

    /** Turns per thread at the FIRST sweep point, before the cost of a turn at this budget is known. */
    public static final int TURNS_PER_THREAD = 16;

    /**
     * Target wall-clock for one timed segment. After the first point the sweep knows how long a turn
     * costs at this budget and sizes every later point to land near this, instead of scaling the work
     * with the thread count.
     *
     * <p>This is the difference between a calibration that pays for itself and one that does not. A
     * fixed turns-per-thread means a 93-thread point runs 23x the work of a 4-thread point, and a turn
     * at 134,000 units costs ~10x one at 13,400 — so the largest level's sweep would run for over an
     * hour before the experiment it is tuning had started. Twenty-five seconds is comfortably longer
     * than the JIT and GC transients this workload shows, which is all the measurement needs.
     */
    public static final long TARGET_SEGMENT_MILLIS = 25_000L;

    /** Hard ceiling on one budget level's whole sweep. Past this the ladder stops and the knee is
     *  taken from the points measured so far — a slightly worse thread count costs far less than an
     *  open-ended tuning phase. */
    public static final long MAX_SWEEP_MILLIS = 12 * 60_000L;

    /** Never measure a point on fewer turns than this per thread, however fast the machine is. */
    private static final int MIN_TURNS_PER_THREAD = 3;

    /** Scenarios in the sweep's fixed workload. Enough to span the portfolio's branching-factor
     *  range, few enough that a point is sized by its visit count rather than by the portfolio. */
    private static final int SWEEP_SCENARIOS = 64;

    /** Second-half slowdown (fraction) above which a point is reported as thermally/power throttled. */
    public static final double THROTTLE_FLAG = 0.05;   // 5%

    /** Stop the sweep after this many consecutive points below the running best. */
    private static final int DECLINE_PATIENCE = 2;

    public static final String HEADER =
            "BudgetUnits,Threads,Turns,ElapsedMs,TurnsPerMin,SustainedTurnsPerMin,ThrottlePct,"
            + "SpeedupVs1,EfficiencyPct,ClockStartMHz,ClockEndMHz,ClockDropPct,"
            + "PeakHeapMB,PerTaskHeapMB,Chosen";

    private ThreadCalibrationV6() {}

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int[] levels        = args.length > 2 ? parseInts(args[2]) : RunExperimentV6.DEFAULT_COMPUTE_LEVELS;

        RunGuard.install("v6 thread calibration",
                "re-run this cell; finished budget levels are re-measured (each sweep is minutes, not hours)");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);
        File csv = new File(out, "thread_calibration.csv");

        System.out.println("############################################################");
        System.out.println("# v6 thread calibration   (output=" + csv.getPath() + ")");
        System.out.println("# levels=" + java.util.Arrays.toString(levels));
        System.out.println("############################################################");
        ServerProfile.get().printBanner("thread calibration");

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));

        for (int cu : levels) {
            if (RunGuard.stopRequested()) break;
            System.out.println("\n=== budget " + cu + " cu ===");
            Result r = calibrate(portfolio, cu, null, csv);
            System.out.println("  -> chosen threads = " + r.chosen + " for " + cu + " cu");
        }
        System.out.println("\nWrote " + csv.getPath());
        RunGuard.finished();
    }

    /** Outcome of one budget level's sweep. */
    public static final class Result {
        public final int chosen;
        public final List<String> rows;   // CSV fragments, without the leading budget column
        Result(int chosen, List<String> rows) { this.chosen = chosen; this.rows = rows; }
    }

    /** Convenience overload with no incremental CSV sink. */
    public static Result calibrate(List<Scenario> portfolio, int budget, int[] candidates) {
        return calibrate(portfolio, budget, candidates, null);
    }

    /**
     * Sweeps thread counts for one budget level and returns the knee.
     *
     * @param candidates explicit ladder, or null to derive one from {@link ServerProfile}
     * @param csvSink    file to append each finished point to as it is measured (interrupt safety),
     *                   or null to accumulate in memory only
     */
    public static Result calibrate(List<Scenario> portfolio, int budget, int[] candidates, File csvSink) {
        ServerProfile machine = ServerProfile.get();
        int[] ladder = candidates != null ? candidates : machine.threadLadder();

        System.out.println("  ladder: " + java.util.Arrays.toString(ladder)
                + "   (ceiling " + machine.maxWorkers() + " = " + machine.physicalCores()
                + " physical cores - " + machine.reserveCores() + " reserved; SMT siblings never used)");

        // Warm the JIT so the first timed point is not penalised, and settle the heap baseline.
        IsolationPhaseRunnerV6.benchmarkThroughput(
                budget, Math.min(4, machine.maxWorkers()), portfolio.subList(0, Math.min(4, portfolio.size())), 2);
        System.gc();
        long baselineHeap = usedHeap();
        long maxHeap = Runtime.getRuntime().maxMemory();

        List<String> rows = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        double best = 0; int declines = 0;
        double perTaskMB = 0;
        // Throughput per thread at the FIRST (smallest) sweep point, used as the scaling reference.
        // Scaling is reported against this rather than against a true 1-thread run, which would cost
        // a disproportionate share of the calibration's wall-clock at these budgets.
        double basePerThread = 0;

        System.out.printf(Locale.ROOT, "  %8s %7s %8s %10s %12s %12s %9s %10s %11s %12s%n",
                "threads", "t/thr", "turns", "elapsedMs", "turns/min", "sustained", "2ndHalf",
                "speedup", "efficiency", "peakHeapMB");

        long sweepStart = System.currentTimeMillis();

        // ONE fixed scenario subset for every point in this sweep, spread across the portfolio.
        //
        // Sizing a point by taking `portfolio.subList(0, numScen)` made each thread count measure a
        // DIFFERENT workload: a small point ran the first 16 scenarios, a large one the first 100, and
        // the portfolio is ordered single-source first, so bigger points also drew a different mix of
        // branching factors. Comparing throughput across those points is comparing two things at once.
        // Here the mix is held constant and only the number of visits varies, so the only thing that
        // changes between points is the thread count — which is the whole question.
        int subsetSize = Math.min(portfolio.size(), SWEEP_SCENARIOS);
        List<Scenario> subset = new ArrayList<>(subsetSize);
        for (int i = 0; i < subsetSize; i++) {
            subset.add(portfolio.get((int) ((long) i * portfolio.size() / subsetSize)));
        }

        // Pilot: learn what a turn costs at this budget BEFORE the first measured point, so that point
        // is sized like the rest rather than running a fixed turns-per-thread that is far too short at
        // a large budget — and it is the point the whole efficiency column is normalised against. The
        // pilot uses the SAME subset, so its estimate is not biased by an unrepresentative sample.
        int pilotThreads = Math.max(1, ladder[0]);
        long pilotMs = IsolationPhaseRunnerV6.benchmarkThroughput(budget, pilotThreads, subset, 1);
        double millisPerTurnPerThread = Math.max(1e-3, pilotMs / (subsetSize / (double) pilotThreads));
        int lastPerThread = Math.max(2, TURNS_PER_THREAD / 2);   // growth anchor for the first point
        System.out.printf(Locale.ROOT,
                "  workload: %d scenarios (fixed for every point);  pilot: %d turns on %d threads in "
                + "%.1fs => sizing each point for ~%ds per segment%n",
                subsetSize, subsetSize, pilotThreads, pilotMs / 1000.0, TARGET_SEGMENT_MILLIS / 1000);

        for (int threads : ladder) {
            if (RunGuard.stopRequested()) {
                System.out.println("  (stop requested - ending the sweep early)");
                break;
            }
            long elapsed = System.currentTimeMillis() - sweepStart;
            if (elapsed > MAX_SWEEP_MILLIS) {
                System.out.printf(Locale.ROOT,
                        "  (sweep budget of %ds reached after %ds - taking the knee from the points "
                        + "measured so far)%n", MAX_SWEEP_MILLIS / 1000, elapsed / 1000);
                break;
            }
            // Memory gate: would this point plausibly fit? perTaskMB is 0 until the first point.
            if (perTaskMB > 0) {
                double projectedMB = (baselineHeap >> 20) + perTaskMB * threads;
                double allowedMB = (maxHeap >> 20) * HEAP_SAFETY;
                if (projectedMB > allowedMB) {
                    System.out.printf(Locale.ROOT,
                            "  %8d  SKIPPED - projected %.0f MB exceeds %.0f MB of usable heap%n",
                            threads, projectedMB, allowedMB);
                    break;
                }
            }

            // Size this point so a segment lands near TARGET_SEGMENT_MILLIS. The per-step growth is
            // clamped: a single optimistic estimate (the pilot runs on a warm cache and can be several
            // times fast) would otherwise produce one enormous point that dominates the sweep's cost.
            int wanted = (int) Math.round(TARGET_SEGMENT_MILLIS / millisPerTurnPerThread);
            int perThread = Math.max(MIN_TURNS_PER_THREAD,
                    Math.min(wanted, Math.min(3 * lastPerThread, 4 * TURNS_PER_THREAD)));
            lastPerThread = perThread;
            int perSegment = perThread * threads;
            int visits = Math.max(1, (int) Math.ceil(perSegment / (double) subsetSize));
            int segTurns = subsetSize * visits;

            double clockStart = ServerProfile.currentClockMHz();
            HeapSampler sampler = new HeapSampler();
            sampler.start();
            // Two segments: the first pays the ramp-up, the second shows what the machine SUSTAINS.
            long msA = IsolationPhaseRunnerV6.benchmarkThroughput(budget, threads, subset, visits);
            long msB = IsolationPhaseRunnerV6.benchmarkThroughput(budget, threads, subset, visits);
            long peak = sampler.stopAndPeak();
            double clockEnd = ServerProfile.currentClockMHz();

            long ms = msA + msB;
            int turns = segTurns * 2;
            // Wall-clock a segment costs per turn-per-thread, used to size the remaining points.
            if (segTurns > 0) millisPerTurnPerThread = msB / (double) (segTurns / (double) threads);
            double rateA = msA > 0 ? segTurns * 60000.0 / msA : 0;
            double rateB = msB > 0 ? segTurns * 60000.0 / msB : 0;
            double rate = ms > 0 ? turns * 60000.0 / ms : 0;
            // Sustained = the second segment. The knee is chosen on this, not on the optimistic mean.
            double sustained = rateB;
            double throttlePct = rateA > 0 ? 100.0 * (1.0 - rateB / rateA) : 0;

            double throughputPerThread = sustained / threads;
            if (basePerThread <= 0) basePerThread = throughputPerThread;
            double speedup = basePerThread > 0 ? sustained / basePerThread : threads;
            double effPct = basePerThread > 0 ? 100.0 * throughputPerThread / basePerThread : 100.0;
            double clockDropPct = (clockStart > 0 && clockEnd > 0)
                    ? 100.0 * (1.0 - clockEnd / clockStart) : Double.NaN;
            double peakMB = peak / (double) (1 << 20);
            // Per-task footprint = everything above the baseline, divided by the tasks that could be
            // in flight. Always the LATEST (largest-thread) point, never the running maximum: peak heap
            // contains a fixed component the workers do not own — JIT arenas, the portfolio's own
            // churn, whatever the collector had not yet reclaimed — and dividing that by two threads
            // charges it as if it were per-task. That overestimate compounds, and it showed up as a
            // sweep aborting at 9 threads on a machine that comfortably ran 15.
            double thisPerTask = Math.max(0.0, (peak - baselineHeap) / (double) (1 << 20) / threads);
            perTaskMB = thisPerTask;

            System.out.printf(Locale.ROOT,
                    "  %8d %7d %8d %10d %12.1f %12.1f %8.1f%% %10.2f %10.0f%% %12.0f%s%n",
                    threads, perThread, turns, ms, rate, sustained, throttlePct, speedup, effPct,
                    peakMB, throttlePct >= THROTTLE_FLAG * 100 ? "   (2nd half slower)" : "");

            rows.add(String.format(Locale.ROOT, "%d,%d,%d,%.2f,%.2f,%.2f,%.3f,%.1f,%s,%s,%s,%.0f,%.2f,",
                    threads, turns, ms, rate, sustained, throttlePct, speedup, effPct,
                    fmt(clockStart), fmt(clockEnd), fmt(clockDropPct), peakMB, thisPerTask));
            points.add(new Point(threads, sustained, throttlePct));

            if (sustained > best) { best = sustained; declines = 0; }
            else if (++declines >= DECLINE_PATIENCE) {
                System.out.println("  (sustained throughput has turned over - stopping the sweep)");
                break;
            }
        }

        int chosen = pickKnee(points, best, machine);
        // Mark the chosen row, then persist the whole level at once.
        for (int i = 0; i < rows.size(); i++) {
            String[] f = rows.get(i).split(",", -1);
            boolean isChosen = Integer.parseInt(f[0]) == chosen;
            rows.set(i, rows.get(i) + (isChosen ? "yes" : "no"));
        }
        if (csvSink != null) {
            List<String> withBudget = new ArrayList<>(rows.size());
            for (String r : rows) withBudget.add(budget + "," + r);
            ExpCsv.append(csvSink, HEADER, withBudget);
        }

        System.out.printf(Locale.ROOT,
                "  knee: %d threads (smallest within %.0f%% of the %.1f turns/min SUSTAINED peak)%n",
                chosen, KNEE_TOLERANCE * 100, best);
        if (perTaskMB > 0) {
            double heapMB = Runtime.getRuntime().maxMemory() / (double) (1 << 20);
            double projectedMB = baselineHeap / (double) (1 << 20) + perTaskMB * chosen;
            int memCap = machine.heapCap(perTaskMB, baselineHeap / (double) (1 << 20), HEAP_SAFETY);
            System.out.printf(Locale.ROOT,
                    "  per-task heap ~%.0f MB => %d threads projects to ~%.0f MB of a %.0f MB heap "
                    + "(%.0f%%; heap alone would allow %d). Probe is EMCTS; the crossover configs hold "
                    + "larger trees, so LoadGovernor watches this live during the run.%n",
                    perTaskMB, chosen, projectedMB, heapMB, 100.0 * projectedMB / heapMB, memCap);
        }
        return new Result(chosen, rows);
    }

    /**
     * Smallest thread count whose SUSTAINED throughput is within {@link #KNEE_TOLERANCE} of the
     * observed peak.
     *
     * <p>The second-half slowdown is reported but deliberately does NOT enter this choice. An earlier
     * version preferred points that showed no slowdown, on the reasoning that a count reaching peak
     * only on boost clocks will not hold it for a multi-day run. In practice the two-segment
     * difference at these durations is dominated by ordinary run-to-run variance — the same sweep
     * produced +7%, +15% and −5% on adjacent points of a monotone curve — so acting on it meant
     * letting noise pick the thread count. It stays in the table, where a human can see a consistent
     * pattern across a whole sweep, which is the only level at which the signal is real.
     */
    private static int pickKnee(List<Point> points, double best, ServerProfile machine) {
        if (points.isEmpty()) return Math.max(1, machine.maxWorkers() / 2);
        double floor = best * (1.0 - KNEE_TOLERANCE);
        for (Point p : points) {                       // ascending thread order
            if (p.sustained >= floor) return p.threads;
        }
        return points.get(points.size() - 1).threads;
    }

    /** One measured sweep point. */
    private static final class Point {
        final int threads; final double sustained; final double throttlePct;
        Point(int threads, double sustained, double throttlePct) {
            this.threads = threads; this.sustained = sustained; this.throttlePct = throttlePct;
        }
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
        /** Stop sampling and return the peak seen.
         *
         *  <p>Not called {@code stop()}: {@link Thread} declares a final {@code stop()} up to JDK 22,
         *  so a subclass method of that name is a compile error there. It was removed outright in
         *  JDK 23, which is why the clash is invisible on a newer compiler and fatal on an older one. */
        long stopAndPeak() {
            running = false;
            try { join(500); } catch (InterruptedException ignore) { }
            return peak;
        }
    }

    private static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
}
