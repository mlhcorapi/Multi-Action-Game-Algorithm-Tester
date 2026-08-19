package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.ComputeMeter;
import core.game.GameState;
import players.Agent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calibrates the {@link ComputeMeter} weights for THIS machine, and says whether the measurement is
 * trustworthy.
 *
 * <p>A v6 compute-unit is {@code advances + W_COPY·copies + W_EVAL·evals}. Those weights are the
 * relative wall-clock costs of the three operations, so they are a property of the machine — its
 * memory subsystem, its caches, its JIT — not of the code. The values compiled into
 * {@link ComputeMeter} came from whichever machine last ran a calibration. Running the experiment on a
 * different machine without re-deriving them means the budget is priced in someone else's currency.
 *
 * <p><b>How this differs from {@link ComputeCalibrate}.</b> The original plays three full games once
 * and prints numbers for a human to copy into source. That is fine as a one-off but it is neither
 * bounded in time, nor repeated, nor checkable, nor applied. This version:
 * <ol>
 *   <li>measures on <b>portfolio scenarios</b>, one turn at a time, so the cost is predictable;</li>
 *   <li><b>warms up and then repeats</b>, so it can report how much the weights move between
 *       repetitions — the difference between a measurement and a single sample;</li>
 *   <li><b>checks its own validity</b> and prints a PASS/FAIL verdict: enough operations of each kind
 *       to divide by, repetition spread inside tolerance, and a machine that was actually idle;</li>
 *   <li><b>writes the weights to a file that the pipeline loads automatically</b>, so applying them is
 *       not a manual step that can be forgotten.</li>
 * </ol>
 *
 * <p><b>Why it must be single-threaded on an idle machine.</b> This is a timing measurement, and it
 * accumulates into process-wide counters ({@code ExperimentalEMCTSAgent.FM_NANOS}) that are not
 * thread-safe. Another load on the machine inflates the per-operation times unevenly — copies and
 * evals are memory-bound and suffer most from a busy machine, advances least — which is precisely the
 * ratio being measured. The verdict below fails if the load average says the machine was busy.
 *
 * <p><b>Applying the result.</b> The weights are written to
 * {@code <outDir>/compute_weights.properties} and {@link ComputeMeter} reads that file on startup, so
 * every later job in the pipeline is priced in this machine's units with no further action. An
 * explicit {@code -Dcompute.wCopy=… -Dcompute.wEval=…} still overrides it, and deleting the file
 * restores the compiled defaults.
 *
 * <p><b>Changing the weights changes what a unit IS.</b> Run this BEFORE the C0 calibration and before
 * the production run, and do not change it mid-experiment: every configuration must be priced in the
 * same currency for the comparison to hold. The written file records the timestamp and machine so a
 * dataset can be traced to the weights that priced it.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.ComputeCalibrateV6 [portfolioDir] [outDir]
 * [reps] [scenariosPerRep] [warmupTurns]}
 */
public final class ComputeCalibrateV6 {

    /** Repetitions of the whole measurement; the spread between them is the stability evidence. */
    public static final int DEFAULT_REPS = 5;
    /** Scenarios measured per repetition. */
    public static final int DEFAULT_SCENARIOS = 6;
    /** Turns run and discarded first, so JIT compilation is not charged to the timings. */
    public static final int DEFAULT_WARMUP = 12;
    /** Budget per measured turn. Large enough that per-op timing is not dominated by turn setup. */
    public static final int MEASURE_CU = 26_800;

    /** Coefficient of variation across repetitions above which the calibration is not trustworthy. */
    public static final double MAX_CV = 0.10;
    /**
     * Minimum count of each operation before dividing by it means anything.
     *
     * <p>A sanity floor, not the quality bar. The three operations occur at different rates — evals
     * run at roughly half the frequency of advances — so a single absolute threshold set high enough
     * to be a real sample-size requirement for advances silently demands twice the run length for
     * evals, and fails an otherwise good calibration on the rarest counter. Stability is measured
     * directly by {@link #MAX_CV} across repetitions, which is the check that actually establishes
     * whether the numbers are trustworthy; this one only excludes a degenerate near-empty sample.
     */
    public static final long MIN_OPS = 20_000;
    /** 1-minute load average per core above which the machine was not idle enough to time on. */
    public static final double MAX_LOAD_PER_CORE = 0.35;

    /** File the pipeline reads its weights from. */
    public static final String WEIGHTS_FILE = "compute_weights.properties";

    private ComputeCalibrateV6() {}

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int reps            = args.length > 2 ? Integer.parseInt(args[2].trim()) : DEFAULT_REPS;
        int scenarios       = args.length > 3 ? Integer.parseInt(args[3].trim()) : DEFAULT_SCENARIOS;
        int warmup          = args.length > 4 ? Integer.parseInt(args[4].trim()) : DEFAULT_WARMUP;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);
        File weightsFile = new File(out, WEIGHTS_FILE);

        System.out.println("############################################################");
        System.out.println("# v6 compute-unit weight calibration");
        System.out.println("# Measures what a copy and an eval cost RELATIVE TO AN ADVANCE on this");
        System.out.println("# machine, and writes them where the rest of the pipeline will pick them up.");
        System.out.println("############################################################");
        System.out.println("# SINGLE-THREADED and requires an idle machine: this is a timing measurement.");
        System.out.println("# currently in force: " + ComputeMeter.weightsSummary());
        System.out.println("# reps=" + reps + "  scenarios/rep=" + scenarios + "  warmup=" + warmup
                + "  budget/turn=" + MEASURE_CU + " cu");
        ServerProfile.get().printBanner("compute-unit weight calibration");

        double loadBefore = loadPerCore();
        if (loadBefore >= 0) {
            System.out.printf(Locale.ROOT, "# load average before starting: %.2f per core%s%n",
                    loadBefore, loadBefore > MAX_LOAD_PER_CORE ? "   <-- MACHINE IS BUSY" : "   (idle)");
        }

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));
        int n = Math.min(scenarios, portfolio.size());
        List<Scenario> sample = new ArrayList<>();
        for (int i = 0; i < n; i++) sample.add(portfolio.get((int) ((long) i * portfolio.size() / n)));

        // Warm up: JIT-compile the hot paths on the same code the measurement will time, then discard.
        System.out.println("\n[weights] warmup: " + warmup + " turns (discarded)");
        for (int i = 0; i < warmup; i++) {
            timeOneTurn(sample.get(i % sample.size()), 9_000 + i, false);
        }

        System.out.printf(Locale.ROOT, "%n  %4s %14s %14s %14s %12s %12s%n",
                "rep", "advance(ns)", "copy(ns)", "eval(ns)", "W_COPY", "W_EVAL");
        List<double[]> perRep = new ArrayList<>();   // [tAdv, tCopy, tEval, wCopy, wEval]
        long totalAdv = 0, totalCop = 0, totalEv = 0;

        for (int rep = 0; rep < reps; rep++) {
            ExperimentalEMCTSAgent.FM_NANOS = 0;
            ComputeMeter.PROFILE = true;
            ExperimentalEMCTSAgent.PROFILE_FM = true;
            ComputeMeter.reset();
            ComputeMeter.resetNanos();

            for (int i = 0; i < sample.size(); i++) {
                timeOneTurn(sample.get(i), 4_000 + rep * 1_000 + i, true);
            }

            ComputeMeter.PROFILE = false;
            ExperimentalEMCTSAgent.PROFILE_FM = false;

            long adv = ComputeMeter.advances(), cop = ComputeMeter.copies(), ev = ComputeMeter.evals();
            totalAdv += adv; totalCop += cop; totalEv += ev;
            double tAdv = adv > 0 ? ExperimentalEMCTSAgent.FM_NANOS / (double) adv : 0;
            double tCopy = cop > 0 ? ComputeMeter.copyNanos() / (double) cop : 0;
            double tEval = ev > 0 ? ComputeMeter.evalNanos() / (double) ev : 0;
            double wCopy = tAdv > 0 ? tCopy / tAdv : 0;
            double wEval = tAdv > 0 ? tEval / tAdv : 0;
            perRep.add(new double[]{tAdv, tCopy, tEval, wCopy, wEval});
            System.out.printf(Locale.ROOT, "  %4d %14.1f %14.1f %14.1f %12.4f %12.4f%n",
                    rep + 1, tAdv, tCopy, tEval, wCopy, wEval);
        }

        double[] wCopyAll = column(perRep, 3), wEvalAll = column(perRep, 4);
        double wCopy = mean(wCopyAll), wEval = mean(wEvalAll);
        double cvCopy = cv(wCopyAll), cvEval = cv(wEvalAll);
        double tAdvMean = mean(column(perRep, 0));
        double loadAfter = loadPerCore();

        System.out.println("\n=== RESULT ===");
        System.out.printf(Locale.ROOT, "  W_ADVANCE = 1.0 (by definition)%n");
        System.out.printf(Locale.ROOT, "  W_COPY    = %.4f   (CV across reps %.1f%%)%n", wCopy, 100 * cvCopy);
        System.out.printf(Locale.ROOT, "  W_EVAL    = %.4f   (CV across reps %.1f%%)%n", wEval, 100 * cvEval);
        System.out.printf(Locale.ROOT, "  one compute-unit = one advance = %.1f ns on this machine%n", tAdvMean);
        System.out.printf(Locale.ROOT, "  compiled default was W_COPY=%.4f W_EVAL=%.4f -> %s%n",
                0.16, 0.077, describeShift(wCopy, wEval));

        // ---- validity ----------------------------------------------------------------------------
        List<String> failures = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        check(totalAdv >= MIN_OPS, "sample size: " + totalAdv + " advances (>= " + MIN_OPS + ")",
                "only " + totalAdv + " advances measured, need >= " + MIN_OPS, passes, failures);
        check(totalCop >= MIN_OPS, "sample size: " + totalCop + " copies",
                "only " + totalCop + " copies measured, need >= " + MIN_OPS, passes, failures);
        check(totalEv >= MIN_OPS, "sample size: " + totalEv + " evals",
                "only " + totalEv + " evals measured, need >= " + MIN_OPS, passes, failures);
        check(cvCopy <= MAX_CV, String.format(Locale.ROOT, "W_COPY stable across reps (CV %.1f%%)", 100 * cvCopy),
                String.format(Locale.ROOT, "W_COPY varies %.1f%% between reps (max %.0f%%) - machine not quiet?",
                        100 * cvCopy, 100 * MAX_CV), passes, failures);
        check(cvEval <= MAX_CV, String.format(Locale.ROOT, "W_EVAL stable across reps (CV %.1f%%)", 100 * cvEval),
                String.format(Locale.ROOT, "W_EVAL varies %.1f%% between reps (max %.0f%%) - machine not quiet?",
                        100 * cvEval, 100 * MAX_CV), passes, failures);
        check(wCopy > 0 && wCopy < 1.0 && wEval > 0 && wEval < 1.0,
                "weights are plausible (a copy and an eval each cost less than an advance)",
                String.format(Locale.ROOT, "implausible weights W_COPY=%.3f W_EVAL=%.3f", wCopy, wEval),
                passes, failures);
        if (loadAfter >= 0) {
            check(loadAfter <= MAX_LOAD_PER_CORE + 1.0 / Math.max(1, ServerProfile.get().logicalCpus()),
                    String.format(Locale.ROOT, "machine was idle (load %.2f per core, this job included)", loadAfter),
                    String.format(Locale.ROOT, "load average %.2f per core - something else was running; "
                            + "timings are inflated unevenly", loadAfter), passes, failures);
        } else {
            passes.add("load average not exposed by this OS (not checked)");
        }

        System.out.println("\n=== IS THIS A FAIR BENCHMARK? ===");
        for (String s : passes) System.out.println("  [ok]   " + s);
        for (String s : failures) System.out.println("  [FAIL] " + s);

        if (failures.isEmpty()) {
            writeWeights(weightsFile, wCopy, wEval, tAdvMean, cvCopy, cvEval, reps, sample.size());
            System.out.println("\n  VERDICT: PASS - calibration is trustworthy.");
            System.out.println("  Written to " + weightsFile.getPath() + " and picked up automatically by");
            System.out.println("  every later v6 job. Re-run the smoke test to see the new weights in force.");
            System.out.println("  NOTE: a compute-unit now means something slightly different, so the budget");
            System.out.println("        levels must be re-derived (C0 / BudgetFloorProbe) before the real run.");
        } else {
            System.out.println("\n  VERDICT: FAIL - weights NOT written; the pipeline keeps its current ones.");
            System.out.println("  Fix the failures above and re-run. The usual cause is other load on the");
            System.out.println("  machine: stop it and try again.");
        }
        RunGuard.finished();
    }

    // ---------------------------------------------------------------------------------------------

    /** Plans one turn on the calling thread (the profiling counters are thread-local / process-wide). */
    private static void timeOneTurn(Scenario sc, long seed, boolean measured) {
        Agent a = ExpAgents.crossoverComboV6(5, MEASURE_CU).create(seed);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int k = 0; k < sc.state.getTribes().length; k++) ids.add(k);
        a.setPlayerIDs(sc.state.getActiveTribeID(), ids);
        GameState gs = sc.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { a.act(gs, ect); } catch (Throwable ignore) { }
    }

    private static void writeWeights(File f, double wCopy, double wEval, double tAdvNs,
                                     double cvCopy, double cvEval, int reps, int scenarios) {
        String body = "# v6 ComputeMeter weights, measured on this machine.\n"
                + "# Loaded automatically by core.game.ComputeMeter. Delete this file to fall back to\n"
                + "# the compiled defaults; -Dcompute.wCopy / -Dcompute.wEval still override it.\n"
                + "# A compute-unit = one advance = " + String.format(Locale.ROOT, "%.1f", tAdvNs) + " ns here.\n"
                + "# machine=" + ServerProfile.get().cpuModel().replace('\n', ' ') + "\n"
                + "# cores=" + ServerProfile.get().physicalCores() + " physical / "
                + ServerProfile.get().logicalCpus() + " logical\n"
                + "# java=" + System.getProperty("java.version") + "\n"
                + "# measured=" + new java.util.Date() + "\n"
                + "# reps=" + reps + " scenarios/rep=" + scenarios
                + String.format(Locale.ROOT, " cvCopy=%.4f cvEval=%.4f%n", cvCopy, cvEval)
                + String.format(Locale.ROOT, "compute.wCopy=%.6f%n", wCopy)
                + String.format(Locale.ROOT, "compute.wEval=%.6f%n", wEval);
        RunGuard.atomicWrite(f, body);
    }

    private static String describeShift(double wCopy, double wEval) {
        double dc = 100 * (wCopy / 0.16 - 1), de = 100 * (wEval / 0.077 - 1);
        return String.format(Locale.ROOT, "copy %+.0f%%, eval %+.0f%% versus the compiled values", dc, de);
    }

    private static void check(boolean ok, String okMsg, String failMsg,
                              List<String> passes, List<String> failures) {
        if (ok) passes.add(okMsg); else failures.add(failMsg);
    }

    /** 1-minute load average divided by CPU count, or -1 where the OS does not expose it. */
    private static double loadPerCore() {
        try {
            double la = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean().getSystemLoadAverage();
            if (la < 0) return -1;
            return la / Math.max(1, Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            return -1;
        }
    }

    private static double[] column(List<double[]> rows, int idx) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i)[idx];
        return out;
    }

    private static double mean(double[] v) {
        double s = 0; for (double d : v) s += d; return v.length > 0 ? s / v.length : 0;
    }

    /** Coefficient of variation: SD / mean. The stability statistic. */
    private static double cv(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double d : v) s += (d - m) * (d - m);
        double sd = Math.sqrt(s / (v.length - 1));
        return m != 0 ? sd / m : 0;
    }
}
