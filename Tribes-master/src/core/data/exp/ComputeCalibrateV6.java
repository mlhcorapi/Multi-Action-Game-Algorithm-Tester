package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.ComputeMeter;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calibrates the {@link ComputeMeter} weights for this machine <b>per configuration and per budget
 * level</b>, and says whether the measurement is trustworthy.
 *
 * <h2>Why the weights cannot be one global set</h2>
 *
 * <p>A compute-unit is {@code W_ADVANCE*advances + W_COPY*copies + W_EVAL*evals}, and those weights are
 * the relative wall-clock costs of the three operations. The original v6 calibration measured them
 * once, on one configuration at one budget, and applied the result to all twelve configurations at all
 * three levels. That is only correct if an advance, a copy and an eval cost the same everywhere, and
 * they measurably do not:
 *
 * <ul>
 *   <li><b>Across configurations.</b> A crossover agent splices two sequences and repairs the result,
 *       so the states it advances and evaluates are not the states a mutation-only agent reaches. The
 *       operations have the same names and different costs.</li>
 *   <li><b>Across budget levels.</b> At ten times the budget the tree is an order of magnitude larger
 *       and stops fitting in cache. The v6 full-games plan measured the 400,000-unit level at
 *       <b>26.7x</b> the wall-clock of the 40,000-unit level rather than 10x - a fact about per-op
 *       cost, recorded there as a curiosity and priced here.</li>
 * </ul>
 *
 * <p>Under one global weight set, a nominal budget of N units therefore buys a different amount of
 * real compute for each agent and at each level, which is the exact confound the budget exists to
 * remove. This class measures every {@code (config, level)} cell and writes a weight set for each.
 *
 * <h2>What a unit means afterwards</h2>
 *
 * <p>Every weight is expressed relative to ONE operation: an advance in the reference cell, EMCTS at
 * the smallest budget level. So {@code W_ADVANCE} is 1.0 only for the reference; a configuration whose
 * advances genuinely take 1.4x as long is charged 1.4 units for each of them. Consequences:
 *
 * <ol>
 *   <li>an equal unit budget is equal wall-clock across configurations - the fairness property the
 *       budget was introduced for, now true rather than assumed;</li>
 *   <li>the budget levels become proportional in time: 400,000 units costs ~10x 40,000 units instead
 *       of 26.7x, because the extra per-op cost at that level is now charged rather than absorbed;</li>
 *   <li>a unit has a stated wall-clock value on the reference machine, written into the file.</li>
 * </ol>
 *
 * <h2>Method</h2>
 *
 * <ol>
 *   <li>measures on <b>portfolio scenarios</b>, one turn at a time, with the <b>same scenarios and the
 *       same seeds for every cell</b>, so cells differ by configuration and budget and by nothing
 *       else;</li>
 *   <li><b>warms up at every budget level and then repeats identical work</b>, estimating each
 *       per-operation time from the <b>fastest</b> repetition. Because the work is identical, all
 *       variation between repetitions is machine interference, and interference can only make a
 *       repetition slower - so the mean is contaminated by whichever repetition was disturbed while
 *       the fastest is the least-contended observation. Weights are ratios between cells, so a
 *       consistent estimator matters more than an unbiased one;</li>
 *   <li><b>checks its own validity</b> per cell and prints a PASS/FAIL verdict: enough operations to
 *       divide by, the two fastest repetitions agreeing within tolerance, plausible weights, and an
 *       idle machine. Gating on the full spread instead would reject a cell for the behaviour of its
 *       WORST repetition, a number carrying no information about what the operation costs;</li>
 *   <li><b>writes weights the pipeline loads automatically</b>, so applying them is not a manual step
 *       that can be forgotten, plus a CSV of everything measured for the write-up.</li>
 * </ol>
 *
 * <p><b>Why it must be single-threaded on an idle machine.</b> This is a timing measurement. Another
 * load inflates the per-operation times unevenly - copies and evals are memory-bound and suffer most,
 * advances least - which is precisely the ratio being measured. The verdict fails if the load average
 * says the machine was busy.
 *
 * <p><b>One round is enough.</b> The measurement is mildly self-referential: per-op costs depend on how
 * big the tree grew, which depends on the weights in force while measuring. The dependence is
 * second-order (it moves the tree size, not the shape of the work), and re-running this command is the
 * cheap way to confirm it - a converged calibration reproduces its own weights within the spread it
 * reports.
 *
 * <p><b>Changing the weights changes what a unit IS.</b> Run this BEFORE C0 and before the production
 * run, and never mid-experiment. Because the floor level was derived in the old currency, re-run
 * {@link BudgetFloorProbe} afterwards.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.ComputeCalibrateV6 [portfolioDir] [outDir]
 * [reps] [turnsPerRepCsv] [warmupTurns] [levelsCsv]}
 */
public final class ComputeCalibrateV6 {

    /** Repetitions of each cell; the spread between them is the stability evidence. */
    public static final int DEFAULT_REPS = 3;
    /**
     * Turns per repetition, one entry per budget level - equivalently, how many distinct positions
     * each cell is measured on.
     *
     * <p>The same count at every level by default, because a weight only means anything relative to
     * the reference cell's, which sits at the smallest level: measuring the levels on different
     * positions would put a position difference into the cross-level ratio that anchors the unit.
     * That costs wall-clock at the top level, where one turn is ~19 s - roughly an hour for the whole
     * calibration on a server.
     *
     * <p>Pass e.g. {@code 4,4,2} to halve the 400,000-unit level's cost. The within-level comparisons
     * stay exact; the cross-level ratio then rests on a spread subset of the same positions
     * (see {@link #scenarioFor}) rather than on all of them.
     */
    public static final String DEFAULT_TURNS_PER_REP = "4,4,4";

    /**
     * Turns per repetition for the action-bias suite, which needs more of them at the smallest level.
     *
     * <p>{@link #MIN_OPS} is a floor on each counter, and the zero-evaluation repair arms make roughly
     * four times fewer COPIES than every other configuration — not by accident but by construction,
     * since not copying candidates is the whole point of the mode. Measured at 40,000 units: a Z0 arm
     * produces ~14,700 copies over four turns against a floor of 20,000, so the default would fail
     * those four cells and, the gate being all-or-nothing, reject the entire calibration.
     *
     * <p>Eight turns at the smallest level clears it with margin. The floor is left alone: it is a
     * sanity check on sample size, and the honest way past it is more samples, not a lower bar.
     * Higher levels do far more work per turn, so their counts clear it easily and they stay cheap.
     */
    public static final String AB_TURNS_PER_REP = "8,4,2";
    /** Turns run and discarded before the first measurement, so JIT compilation is not timed. */
    public static final int DEFAULT_WARMUP = 6;
    /**
     * Size of the scenario pool a cell's turns are drawn from. Levels that run fewer turns take a
     * spread subset of this same pool, so every cell at every level sees the same positions.
     */
    public static final int SCENARIO_POOL = 8;

    /**
     * Tolerance for the stability gate: the two fastest repetitions of a cell must agree within this.
     *
     * <p>Also the threshold gate A3' uses when comparing idle against loaded weight ratios, so the
     * two questions - "is this cell stable?" and "does its price survive contention?" - are asked at
     * the same resolution. The full coefficient of variation is still computed and printed beside it
     * as a disturbance diagnostic; it is reported, not gated on.
     */
    public static final double MAX_CV = 0.10;

    /**
     * Two-fastest-repetition gap a cell may show before it fails its own check. Doubled under
     * {@code --underLoad}: a loaded run measures ratios in a deliberately contended regime, and
     * rejecting its cells at the idle threshold throws away exactly the measurement the loaded run
     * exists to take (the R7615 run lost 1–2 of 12 cells this way, all in the noisy eval timings).
     */
    private static double gapLimit = MAX_CV;
    /**
     * Minimum count of each operation in a cell before dividing by it means anything.
     *
     * <p>A sanity floor, not the quality bar. The three operations occur at different rates, so a
     * single threshold high enough to be a real sample-size requirement for advances would silently
     * demand a much longer run for the rarest counter. Stability is measured directly by
     * {@link #MAX_CV}; this only excludes a degenerate near-empty sample.
     */
    public static final long MIN_OPS = 20_000;
    /** 1-minute load average per core above which the machine was not idle enough to time on. */
    public static final double MAX_LOAD_PER_CORE = 0.35;

    /** File the pipeline reads its weights from. */
    public static final String WEIGHTS_FILE = "compute_weights.properties";
    /** Everything measured, one row per cell, for the analysis and the write-up. */
    public static final String PROFILES_CSV = "compute_profiles.csv";

    /** The configuration that defines the unit: its advance is 1.0 units at the smallest level. */
    public static final String REFERENCE_CONFIG = "EMCTS";

    private ComputeCalibrateV6() {}

    // ------------------------------------------------------------------ one measured cell

    /** One (config, level) cell: what was counted, what it cost, and the weights that follow. */
    private static final class Cell {
        final String config;
        final int ratio;            // -1 for the configurations without one
        final int units;            // budget level
        final String profile;
        long adv, cop, ev;          // total operations timed, summed over repetitions
        int scenarios;              // distinct positions behind this cell
        final List<double[]> perRep = new ArrayList<>();   // {tAdv, tCopy, tEval} ns per rep
        double tAdv, tCopy, tEval;  // FASTEST repetition (see fastest())
        double cvAdv, cvCopy, cvEval;              // full spread, reported as the disturbance diagnostic
        double gapAdv, gapCopy, gapEval;           // two-fastest agreement, the gate
        double wAdvance, wCopy, wEval;
        final List<String> failures = new ArrayList<>();

        Cell(String config, int ratio, int units) {
            this.config = config; this.ratio = ratio; this.units = units;
            this.profile = ComputeMeter.profileKey(config, ratio, units);
        }

        AgentSpec spec() {
            switch (config) {
                case "EMCTS":              return ExpAgents.emctsV6(units);
                case "CrossoverBB":        return ExpAgents.crossoverBBV6(units);
                case "CrossoverExpansion": return ExpAgents.crossoverExpansionV6(ratio, units);
                case "CrossoverCombo":     return ExpAgents.crossoverComboV6(ratio, units);
                default:
                    // Action-bias arms carry the AB_ prefix and are looked up in the runner's own
                    // registry, so an arm can never be calibrated under one definition and run under
                    // another.
                    if (config.startsWith(ExpAgents.AB_PREFIX))
                        return Stage1Runner.specFor(config.substring(ExpAgents.AB_PREFIX.length()), units);
                    throw new IllegalArgumentException("unknown config " + config);
            }
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        // --suite and --underLoad are flags, not positionals, and are stripped here.
        //
        // The suite HAS to be a flag. As a trailing positional it could only be given by supplying
        // every argument before it, which would defeat its own suite-aware defaults: you cannot ask
        // for the ab suite's turn counts while also being forced to state the turn counts. The
        // trailing positional is still honoured so existing v6 invocations keep working.
        int underLoad = 0;
        String suiteFlag = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i].trim();
            if (a.equals("--underLoad") && i + 1 < args.length) underLoad = Integer.parseInt(args[++i].trim());
            else if (a.startsWith("--underLoad=")) underLoad = Integer.parseInt(a.substring(12).trim());
            else if (a.equals("--suite") && i + 1 < args.length) suiteFlag = args[++i].trim();
            else if (a.startsWith("--suite=")) suiteFlag = a.substring(8).trim();
            else pos.add(a);
        }
        args = pos.toArray(new String[0]);

        if (underLoad > 1) gapLimit = 2 * MAX_CV;   // contended timings are legitimately noisier

        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int reps            = args.length > 2 ? Integer.parseInt(args[2].trim()) : DEFAULT_REPS;
        String suiteArg     = suiteFlag != null ? suiteFlag.toLowerCase(Locale.ROOT)
                            : args.length > 6 ? args[6].trim().toLowerCase(Locale.ROOT) : SUITE_V6;
        // "auto" keeps the suite's own default while still allowing the later positionals (warmup,
        // levels) to be given — otherwise choosing a level would force you to restate the turn counts.
        String turnsArg     = args.length > 3 ? args[3].trim() : "auto";
        int[] turnsPerRep   = GameLengthProbeV6.parseInts(
                                    turnsArg.isEmpty() || turnsArg.equalsIgnoreCase("auto")
                                    ? (suiteArg.equals(SUITE_V6) ? DEFAULT_TURNS_PER_REP
                                                                 : AB_TURNS_PER_REP)
                                    : turnsArg);
        int warmup          = args.length > 4 ? Integer.parseInt(args[4].trim()) : DEFAULT_WARMUP;
        int[] levels        = args.length > 5 ? GameLengthProbeV6.parseInts(args[5])
                                              : RunExperimentV6.DEFAULT_COMPUTE_LEVELS;
        String suite        = suiteArg;
        if (!suite.equals(SUITE_V6) && !suite.equals(SUITE_AB) && !suite.equals(SUITE_ALL)
                && !suite.equals(SUITE_A3)) {
            System.err.println("unknown suite '" + suite + "' - expected one of: "
                    + SUITE_V6 + ", " + SUITE_AB + ", " + SUITE_ALL + ", " + SUITE_A3);
            System.exit(2);
        }

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);

        List<Cell> cells = new ArrayList<>();
        for (int level : levels) cells.addAll(cellsForLevel(level, suite));

        System.out.println("############################################################");
        System.out.println("# v6 compute-unit weight calibration (per config, per budget level)");
        System.out.println("# Measures what an advance, a copy and an eval cost FOR EACH CONFIGURATION");
        System.out.println("# AT EACH BUDGET LEVEL, relative to one reference operation, and writes them");
        System.out.println("# where the rest of the pipeline picks them up.");
        System.out.println("############################################################");
        if (underLoad > 1) {
            System.out.println("# UNDER LOAD: each cell is measured on one thread while " + (underLoad - 1)
                    + " others run the SAME configuration.");
            System.out.println("# This is gate A3' — the idle weights are ratios measured in a regime the");
            System.out.println("# experiment never occupies. Arms differ several-fold in allocation rate, and");
            System.out.println("# GC is nearly free on one thread while it competes for cores, L3 and memory");
            System.out.println("# bandwidth at " + underLoad + ". If the ratios move, the idle weights mis-price");
            System.out.println("# exactly the arms this line is trying to measure.");
        } else {
            System.out.println("# SINGLE-THREADED and requires an idle machine: this is a timing measurement.");
        }
        System.out.println("# currently in force: " + ComputeMeter.weightsSummary());
        System.out.println("# suite=" + suite + "  underLoad=" + underLoad
                + "  levels=" + java.util.Arrays.toString(levels)
                + "  configs=" + (cells.size() / Math.max(1, levels.length))
                + "  cells=" + cells.size());
        System.out.println("# reps=" + reps + "  turns(=positions)/rep per level="
                + java.util.Arrays.toString(turnsPerRep) + "  warmup=" + warmup);
        System.out.println("# A repetition repeats the SAME positions with the SAME seeds, so variation"
                + " across reps measures the machine, not the portfolio.");
        System.out.println("# Estimator: the FASTEST repetition (interference can only slow one down).");
        System.out.println("# Gate: the two fastest agree within " + (int) (100 * MAX_CV)
                + "%. Full CV is printed beside it as the disturbance diagnostic.");
        System.out.println("# reference cell = " + REFERENCE_CONFIG + "@" + levels[0]
                + "  (its advance is 1.000 units by definition)");
        ServerProfile.get().printBanner("compute-unit weight calibration");

        double loadBefore = loadPerCore();
        if (loadBefore >= 0) {
            System.out.printf(Locale.ROOT, "# load average before starting: %.2f per core%s%n",
                    loadBefore, loadBefore > MAX_LOAD_PER_CORE ? "   <-- MACHINE IS BUSY" : "   (idle)");
        }

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));
        if (portfolio.isEmpty()) {
            System.out.println("\nNo portfolio at " + portfolioDir + " - build it first (notebook 0.5).");
            System.exit(1);
        }

        long t0 = System.currentTimeMillis();
        int done = 0;
        int warmedLevel = -1;
        for (Cell c : cells) {
            int levelIdx = indexOf(levels, c.units);
            // Warm up ONCE PER BUDGET LEVEL, not once per run.
            //
            // The hot code (GameState.advance/copy, the heuristic) is shared by every configuration,
            // so JIT state carries across cells within a level. Heap state does not: a 400,000-unit
            // turn builds a far larger tree than a 40,000-unit one, so warming only at the smallest
            // level leaves the first cells of every larger level measured while the heap is still
            // growing. Full collections then land inside their early repetitions and not their later
            // ones — which is exactly the repetition-to-repetition spread the gate rejects, and it is
            // reproducible: the first cells of the largest level failed while their neighbours passed.
            //
            // Each cell still discards its own first turn as well, which is what touches that
            // agent's own code paths.
            if (c.units != warmedLevel) {
                warmedLevel = c.units;
                System.out.println(String.format(Locale.ROOT,
                        "%n[weights] warmup for level %d: %d turns (discarded)", c.units, warmup));
                Cell warm = new Cell(REFERENCE_CONFIG, -1, c.units);
                ComputeMeter.PROFILE = true;   // warm the TIMED code path, not the untimed one
                for (int i = 0; i < warmup; i++)
                    runTurn(warm, scenarioFor(portfolio, i % SCENARIO_POOL, SCENARIO_POOL), 9_000 + i);
                ComputeMeter.PROFILE = false;
                // Collect between levels so each starts from a comparable heap rather than carrying
                // the previous level's garbage into its first cell's repetitions.
                System.gc();
            }
            int turns = turnsPerRep[Math.min(levelIdx, turnsPerRep.length - 1)];
            LoadHarness load = underLoad > 1 ? LoadHarness.start(c, portfolio, underLoad - 1) : null;
            try {
                measure(c, portfolio, reps, turns);
            } finally {
                if (load != null) load.stop();
            }
            done++;
            System.out.printf(Locale.ROOT,
                    "  [%2d/%2d] %-30s advance %7.1f ns  copy %7.1f ns  eval %7.1f ns   "
                    + "(gap %.1f/%.1f/%.1f%% | CV %.1f/%.1f/%.1f%%)%s%n",
                    done, cells.size(), c.profile, c.tAdv, c.tCopy, c.tEval,
                    100 * c.gapAdv, 100 * c.gapCopy, 100 * c.gapEval,
                    100 * c.cvAdv, 100 * c.cvCopy, 100 * c.cvEval,
                    c.failures.isEmpty() ? "" : "   <-- " + c.failures.get(0));
        }
        System.out.println("  measured in " + ((System.currentTimeMillis() - t0) / 60000) + " min");

        // ---- normalise to the reference cell ------------------------------------------------------
        Cell ref = null;
        for (Cell c : cells)
            if (c.config.equals(REFERENCE_CONFIG) && c.units == levels[0]) ref = c;
        if (ref == null || ref.tAdv <= 0) {
            System.out.println("\nVERDICT: FAIL - the reference cell " + REFERENCE_CONFIG + "@" + levels[0]
                    + " produced no usable timing; nothing written.");
            RunGuard.finished();
            return;
        }
        for (Cell c : cells) {
            c.wAdvance = c.tAdv / ref.tAdv;
            c.wCopy    = c.tCopy / ref.tAdv;
            c.wEval    = c.tEval / ref.tAdv;
        }

        report(cells, levels, ref);

        // ---- validity ----------------------------------------------------------------------------
        List<String> failures = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        int badCells = 0;
        for (Cell c : cells) if (!c.failures.isEmpty()) badCells++;
        check(badCells == 0, "every cell measured cleanly (" + cells.size() + " cells)",
                badCells + " of " + cells.size() + " cells failed their own checks (listed above)",
                passes, failures);
        boolean plausible = true;
        for (Cell c : cells)
            if (!(c.wAdvance > 0 && c.wAdvance < 100 && c.wCopy > 0 && c.wCopy < 100
                    && c.wEval > 0 && c.wEval < 100)) plausible = false;
        check(plausible, "weights are plausible in every cell",
                "at least one cell produced an implausible weight - a broken or mis-attributed timer",
                passes, failures);

        double loadAfter = loadPerCore();
        if (underLoad > 1) {
            // Gate on the harness's OWN completed work, not on the OS load average. Inside a
            // container the load average can read near zero while the machine is saturated (the
            // R7615's JupyterHub read 0.11/core during a run whose per-op costs were 40-80% above
            // idle), and that false negative once discarded a measurement that had succeeded.
            long loadTurns = LoadHarness.WORK_TURNS.get();
            long minTurns = (long) cells.size() * (underLoad - 1);
            check(loadTurns >= minTurns,
                    String.format(Locale.ROOT, "load harness completed %,d turns beside the measured "
                            + "cells (OS load average %.2f per core, informational only)",
                            loadTurns, loadAfter),
                    String.format(Locale.ROOT, "load harness completed only %,d turns (wanted >= %,d) - "
                            + "the machine was not reliably loaded while measuring", loadTurns, minTurns),
                    passes, failures);
        } else if (loadAfter >= 0) {
            check(loadAfter <= MAX_LOAD_PER_CORE + 1.0 / Math.max(1, ServerProfile.get().logicalCpus()),
                    String.format(Locale.ROOT, "machine was idle (load %.2f per core, this job included)",
                            loadAfter),
                    String.format(Locale.ROOT, "load average %.2f per core - something else was running; "
                            + "timings are inflated unevenly", loadAfter), passes, failures);
        } else {
            passes.add("load average not exposed by this OS (not checked)");
        }

        System.out.println("\n=== IS THIS A FAIR BENCHMARK? ===");
        for (String s : passes) System.out.println("  [ok]   " + s);
        for (String s : failures) System.out.println("  [FAIL] " + s);

        // A loaded run never clobbers the idle weights: they are two measurements of the same thing
        // in two regimes, and the comparison between them is the whole point of gate A3'.
        File idleWeights = new File(out, WEIGHTS_FILE);
        File weightsFile = underLoad > 1 ? new File(out, "compute_weights_load" + underLoad + ".properties")
                                         : idleWeights;
        File profilesCsv = underLoad > 1 ? new File(out, "compute_profiles_load" + underLoad + ".csv")
                                         : new File(out, PROFILES_CSV);
        if (underLoad > 1) reportLoadVsIdle(cells, idleWeights, underLoad);
        if (failures.isEmpty()) {
            writeWeights(weightsFile, cells, ref, reps, levels);
            writeCsv(profilesCsv, cells, ref);
            System.out.println("\n  VERDICT: PASS - calibration is trustworthy.");
            System.out.println("  Weights  -> " + weightsFile.getPath() + "  ("
                    + cells.size() + " profiles; loaded automatically by every later v6 job)");
            System.out.println("  Measured -> " + profilesCsv.getPath());
            System.out.println("  NOTE: a compute-unit now means something different, so the budget levels");
            System.out.println("        must be re-derived (BudgetFloorProbe) before the real run, and C0");
            System.out.println("        should now show units/ms FLAT across configurations - that is the");
            System.out.println("        check that this calibration did its job.");
        } else {
            writeCsv(profilesCsv, cells, ref);      // keep the measurements even when they are rejected
            System.out.println("\n  VERDICT: FAIL - weights NOT written; the pipeline keeps its current ones.");
            System.out.println("  The measurements are in " + profilesCsv.getPath() + " for inspection.");
            System.out.println("  Fix the failures above and re-run. The usual cause is other load on the");
            System.out.println("  machine: stop it and try again.");
        }
        RunGuard.finished();
    }

    // ------------------------------------------------------------------ gate A3': under load

    /**
     * Keeps {@code workers} threads busy running the SAME configuration as the cell being measured,
     * so that cell is timed in the regime the experiment actually occupies.
     *
     * <p><b>Why the same configuration.</b> A leaf of the isolation phase runs one arm across every
     * worker, so "many threads all running arm X" is arm X's operating condition. Loading with a
     * mixture would measure a machine state that never occurs.
     *
     * <p><b>Known distortion, and why it is tolerable.</b> {@code ComputeMeter.PROFILE} is a global
     * flag, not a thread-local one, so while the measured thread is timing, the load threads also
     * take the {@code nanoTime} path they would not take in a real run. The overhead is small and it
     * applies to every cell equally — and A3' compares <i>ratios between cells</i>, where a uniform
     * term cancels. Per-thread counters are thread-local, so the load threads never pollute the
     * measurement itself.
     */
    private static final class LoadHarness {
        /**
         * Completed load turns across the whole run — proof the harness actually loaded the machine.
         * The verdict gates on this rather than on the OS load average, because inside a container
         * the load average can read near zero while the machine is saturated: the R7615's JupyterHub
         * reported 0.11 per core during a run whose per-op costs were 40–80% above idle.
         */
        static final java.util.concurrent.atomic.AtomicLong WORK_TURNS =
                new java.util.concurrent.atomic.AtomicLong();

        private final java.util.concurrent.ExecutorService pool;
        private final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();

        private LoadHarness(java.util.concurrent.ExecutorService pool) { this.pool = pool; }

        static LoadHarness start(Cell cell, List<Scenario> portfolio, int workers) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
            LoadHarness h = new LoadHarness(pool);
            java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(workers);
            for (int w = 0; w < workers; w++) {
                final int id = w;
                pool.submit(() -> {
                    boolean announced = false;
                    for (long i = 0; !h.stop.get(); i++) {
                        if (!announced) { running.countDown(); announced = true; }
                        try {
                            // Offset by worker so the threads are not lock-stepped on one position.
                            Scenario sc = portfolio.get((int) (((id * 7L + i * 13L) % portfolio.size())));
                            runTurn(cell, sc, 8_000_000L + id * 10_000L + i);
                            WORK_TURNS.incrementAndGet();
                        } catch (Throwable t) {
                            // A load thread failing must not take the measurement down with it.
                        }
                    }
                });
            }
            try {
                // Wait until every worker is inside a turn, then settle: the measured cell should see
                // a machine already at load, not one ramping into it.
                running.await(2, java.util.concurrent.TimeUnit.MINUTES);
                Thread.sleep(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return h;
        }

        void stop() {
            stop.set(true);
            pool.shutdownNow();
            try { pool.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Gate A3': does the per-configuration price hold in the regime it will be spent in?
     *
     * <p>Compares each cell's loaded weights against the idle ones and reports the largest move. The
     * weights are ratios to the reference cell, so a uniform slowdown cancels: what shows up here is
     * a configuration whose cost changes <i>relative to the others</i> under contention — which is
     * exactly what an allocation-heavy arm is expected to do, and exactly what would otherwise
     * mis-price it silently.
     */
    private static void reportLoadVsIdle(List<Cell> cells, File idleWeights, int underLoad) {
        System.out.println("\n=== GATE A3': PER-CONFIG WEIGHT RATIOS, IDLE vs " + underLoad + " THREADS ===");
        if (!idleWeights.exists()) {
            System.out.println("  No idle calibration at " + idleWeights.getPath()
                    + " to compare against - run the calibration without --underLoad first.");
            return;
        }
        java.util.Properties idle = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(idleWeights)) {
            idle.load(in);
        } catch (Exception e) {
            System.out.println("  Could not read " + idleWeights.getPath() + ": " + e);
            return;
        }
        System.out.printf(Locale.ROOT, "  %-34s %10s %10s %9s%n", "profile", "idle", "loaded", "change");
        double worst = 0; String worstWhere = "";
        int compared = 0;
        for (Cell c : cells) {
            String key = "compute.profile." + c.profile + ".wAdvance";
            String v = idle.getProperty(key);
            if (v == null) continue;
            double before = Double.parseDouble(v);
            if (before <= 0) continue;
            double change = (c.wAdvance - before) / before;
            compared++;
            if (Math.abs(change) > Math.abs(worst)) { worst = change; worstWhere = c.profile; }
            System.out.printf(Locale.ROOT, "  %-34s %10.4f %10.4f %8.1f%%%n",
                    c.profile, before, c.wAdvance, 100 * change);
        }
        if (compared == 0) {
            System.out.println("  No overlapping profiles - the idle run calibrated a different suite.");
            return;
        }
        System.out.printf(Locale.ROOT, "%n  Largest move: %.1f%% (%s), over %d profiles.%n",
                100 * worst, worstWhere, compared);
        if (Math.abs(worst) <= MAX_CV) {
            System.out.println("  VERDICT: ratios HOLD under load (within the calibrator's own "
                    + (int) (100 * MAX_CV) + "% gate).");
            System.out.println("  The idle weights are the operating currency; wall-clock fairness (W7)");
            System.out.println("  stays a secondary check.");
        } else {
            System.out.println("  VERDICT: ratios MOVE under load.");
            System.out.println("  The LOADED weights are the operating currency: re-measure every cell with");
            System.out.println("  --underLoad and use " + WEIGHTS_FILE.replace(".properties",
                    "_load" + underLoad + ".properties") + " for the run.");
            System.out.println("  Wall-clock fairness (W7) is promoted to CO-PRIMARY: report every headline");
            System.out.println("  claim in both compute units and seconds, and treat an arm that wins in one");
            System.out.println("  and loses in the other as unresolved, not as a win.");
        }
    }

    // ------------------------------------------------------------------ measuring

    /**
     * Every profile the v6 pipeline expects to find calibrated at these budget levels.
     *
     * <p>Used by the run banners: a run that starts with no profiles is not wrong, but every
     * configuration silently falls back to the global weights and the budget stops being equal
     * compute - so it has to be visible at the top of the log rather than inferred afterwards.
     */
    public static List<String> expectedProfiles(int[] levels) {
        List<String> out = new ArrayList<>();
        for (int level : levels) for (Cell c : cellsForLevel(level)) out.add(c.profile);
        return out;
    }

    /** One banner line: how many of the expected profiles this JVM actually loaded. */
    public static String coverageBanner(int[] levels) {
        List<String> want = expectedProfiles(levels);
        int have = 0;
        for (String p : want) if (ComputeMeter.hasProfile(p)) have++;
        if (have == want.size())
            return "Compute profiles: " + have + "/" + want.size()
                    + " calibrated (per-config, per-level weights in force)";
        if (have == 0)
            return "Compute profiles: NONE of " + want.size() + " calibrated -- every config falls back"
                    + " to the global weights, so the budget is NOT equal compute across configs."
                    + " Run ComputeCalibrateV6.";
        return "Compute profiles: only " + have + "/" + want.size() + " calibrated -- the rest fall back"
                + " to the global weights. Re-run ComputeCalibrateV6 for the missing levels.";
    }

    /** Suites of configurations this calibrator knows how to price. */
    public static final String SUITE_V6 = "v6", SUITE_AB = "ab", SUITE_ALL = "all", SUITE_A3 = "a3";

    /**
     * The A3' subset: the reference plus the three most allocation-divergent action-bias arms.
     *
     * <p>A3' asks whether per-configuration cost RATIOS survive the move from an idle machine to a
     * loaded one, and the arms that can answer it are the ones that differ most in allocation rate —
     * full greedy repair, a shortlist, and zero-eval, which runs several times more mutations per turn.
     * Measuring the whole suite under load would cost hours to answer a question four configurations
     * settle, and a loaded measurement is the expensive kind.
     */
    private static final String[] A3_ARMS = {"Baseline", "Short5_e25", "Z0_Fine"};

    /** The 12 v6 isolation-phase configurations at one budget level, in the canonical order. */
    private static List<Cell> cellsForLevel(int units) {
        return cellsForLevel(units, SUITE_V6);
    }

    /**
     * The configurations of one suite at one budget level.
     *
     * <p>The reference cell ({@code EMCTS}) is in every suite, because it is what defines the unit:
     * a suite calibrated without it would have nothing to be a ratio of.
     */
    private static List<Cell> cellsForLevel(int units, String suite) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell("EMCTS", -1, units));
        if (suite.equals(SUITE_V6) || suite.equals(SUITE_ALL)) {
            out.add(new Cell("CrossoverBB", -1, units));
            for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Cell("CrossoverExpansion", r, units));
            for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Cell("CrossoverCombo", r, units));
        }
        if (suite.equals(SUITE_AB) || suite.equals(SUITE_ALL)) {
            for (String arm : Stage1Runner.armNames())
                out.add(new Cell(ExpAgents.AB_PREFIX + arm, -1, units));
        }
        if (suite.equals(SUITE_A3)) {
            for (String arm : A3_ARMS) out.add(new Cell(ExpAgents.AB_PREFIX + arm, -1, units));
        }
        return out;
    }

    /**
     * Measures one cell: {@code reps} repetitions of the same {@code turns} turns.
     *
     * <p><b>Counts and nanoseconds must cover the same span, and getting that wrong is silent.</b> An
     * agent calls {@link ComputeMeter#reset()} twice per planning episode - once at the start, once
     * after greedy initialization, so the phase ceilings bound search only - which clears the COUNTS
     * but not the nanosecond accumulators. Reading {@code ComputeMeter.advances()} after a loop of
     * turns therefore returns the LAST turn's search count, while {@code advanceNanos()} holds every
     * turn's. Dividing one by the other inflated every per-operation time by roughly the number of
     * turns in the repetition, and made the repetition-to-repetition spread a measure of which
     * scenario happened to land last rather than of anything about the machine.
     *
     * <p>So the accumulation is per TURN: nanoseconds are reset before each turn, and the operation
     * counts come from that turn's own telemetry ({@code TurnData}), summing the initialization and
     * search phases because the nanoseconds cover both. Both sides then describe exactly one planning
     * episode.
     *
     * <p><b>A repetition is a repetition.</b> Every rep runs the same scenarios with the same seeds,
     * so the work is byte-identical and the counts come out identical; only the timings differ. The CV
     * across reps is then a pure machine-quietness statistic - which is what the gate is for - rather
     * than a mixture of that and real position-to-position cost variation.
     *
     * <p>The scenarios depend on the turn index only - never on the configuration, the repetition or
     * the budget level - so every one of the 36 cells is measured on identical work. Without that, a
     * difference between two cells' per-op costs could be a difference between the positions they
     * happened to draw, and the cross-level ratios that anchor the unit would be meaningless.
     */
    private static void measure(Cell c, List<Scenario> portfolio, int reps, int turns) {
        // One discarded turn, profiled, so this agent's own code paths are JIT-compiled in the shape
        // they will be measured in - the timed branch inside advance()/copy()/eval() included.
        ComputeMeter.PROFILE = true;
        runTurn(c, scenarioFor(portfolio, 0, turns), 9_500);
        ComputeMeter.PROFILE = false;

        for (int rep = 0; rep < reps; rep++) {
            long adv = 0, cop = 0, ev = 0;
            long nsAdv = 0, nsCop = 0, nsEv = 0;
            for (int i = 0; i < turns; i++) {
                Scenario sc = scenarioFor(portfolio, i, turns);
                ComputeMeter.resetNanos();
                ComputeMeter.PROFILE = true;
                // visitId 0 for every rep: a repetition must repeat, or the CV below stops being a
                // statement about the machine.
                long[] ops = runTurn(c, sc, IsolationPhaseRunnerV6.visitSeed(sc.id, 0));
                ComputeMeter.PROFILE = false;
                adv += ops[0]; cop += ops[1]; ev += ops[2];
                nsAdv += ComputeMeter.advanceNanos();
                nsCop += ComputeMeter.copyNanos();
                nsEv += ComputeMeter.evalNanos();
            }
            c.adv += adv; c.cop += cop; c.ev += ev;
            c.perRep.add(new double[]{
                    adv > 0 ? nsAdv / (double) adv : 0,
                    cop > 0 ? nsCop / (double) cop : 0,
                    ev > 0 ? nsEv / (double) ev : 0});
        }
        c.scenarios = turns;

        double[] a = column(c.perRep, 0), cp = column(c.perRep, 1), e = column(c.perRep, 2);
        c.tAdv = fastest(a);   c.cvAdv = cv(a);   c.gapAdv = twoFastestGap(a);
        c.tCopy = fastest(cp); c.cvCopy = cv(cp); c.gapCopy = twoFastestGap(cp);
        c.tEval = fastest(e);  c.cvEval = cv(e);  c.gapEval = twoFastestGap(e);

        if (c.adv < MIN_OPS) c.failures.add("only " + c.adv + " advances (need " + MIN_OPS + ")");
        if (c.cop < MIN_OPS) c.failures.add("only " + c.cop + " copies (need " + MIN_OPS + ")");
        if (c.ev  < MIN_OPS) c.failures.add("only " + c.ev + " evals (need " + MIN_OPS + ")");
        // Gate on the two fastest repetitions agreeing, not on the full spread. A cell whose worst
        // repetition was disturbed is not a badly-measured cell; it is a cell that was measured while
        // something else ran, and its fastest repetitions still say what the operation costs.
        if (c.perRep.size() >= 2) {
            if (c.gapAdv > gapLimit) c.failures.add(String.format(Locale.ROOT,
                    "two fastest advance times differ by %.1f%% (limit %.0f%%)",
                    100 * c.gapAdv, 100 * gapLimit));
            if (c.gapCopy > gapLimit) c.failures.add(String.format(Locale.ROOT,
                    "two fastest copy times differ by %.1f%% (limit %.0f%%)",
                    100 * c.gapCopy, 100 * gapLimit));
            if (c.gapEval > gapLimit) c.failures.add(String.format(Locale.ROOT,
                    "two fastest eval times differ by %.1f%% (limit %.0f%%)",
                    100 * c.gapEval, 100 * gapLimit));
        }
    }

    /**
     * Scenario for turn {@code i} of {@code turns}, drawn from a fixed pool that does not depend on
     * the budget level.
     *
     * <p>Levels may run different numbers of turns (a 400,000-unit turn costs ~19 s), but they must
     * still measure the same POSITIONS, because a weight's whole meaning is its ratio to the reference
     * cell's - which sits at another level. A level running fewer turns therefore takes a spread
     * subset of the same pool rather than a different stretch of the portfolio.
     *
     * <p>The stride is coprime with most portfolio sizes so consecutive slots land far apart and the
     * sample covers both scenario sources rather than the first few scenarios.
     */
    private static Scenario scenarioFor(List<Scenario> portfolio, int i, int turns) {
        int slot = (int) ((long) i * SCENARIO_POOL / Math.max(1, turns));
        return portfolio.get((int) (((long) slot * 37) % portfolio.size()));
    }

    /**
     * Plans one turn on the calling thread and returns its operation counts as
     * {@code {advances, copies, evals}} for the WHOLE planning episode - initialization plus search,
     * because that is the span the nanosecond accumulators cover.
     *
     * <p>The counts come from the turn's own telemetry rather than from the live meter: the agent
     * resets the meter's counters twice inside this call, so by the time it returns they describe the
     * search phase only. Returns zeros if the turn produced no telemetry.
     */
    private static long[] runTurn(Cell c, Scenario sc, long seed) {
        Agent a = c.spec().create(seed);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int k = 0; k < sc.state.getTribes().length; k++) ids.add(k);
        a.setPlayerIDs(sc.state.getActiveTribeID(), ids);
        GameState gs = sc.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { a.act(gs, ect); } catch (Throwable ignore) { }

        java.util.List<core.data.dto.TurnData> log = a.getTurnDataLog();
        if (log == null || log.isEmpty()) return new long[3];
        core.data.dto.TurnData t = log.get(0);
        return new long[]{
                t.getAdvances() + t.getInitAdvances(),
                t.getCopies() + t.getInitCopies(),
                t.getEvals() + t.getInitEvals()};
    }

    // ------------------------------------------------------------------ reporting

    /**
     * The result, and the evidence that per-cell weights were needed at all: how far apart the
     * configurations are within a level, and how much wall-clock a single global weight set would have
     * mis-allocated between them.
     */
    private static void report(List<Cell> cells, int[] levels, Cell ref) {
        System.out.println("\n=== RESULT: weights per profile (relative to one advance in "
                + ref.profile + " = " + String.format(Locale.ROOT, "%.1f ns", ref.tAdv) + ") ===");
        System.out.printf(Locale.ROOT, "  %-26s %10s %10s %10s %12s%n",
                "profile", "W_ADVANCE", "W_COPY", "W_EVAL", "ns/advance");
        for (Cell c : cells)
            System.out.printf(Locale.ROOT, "  %-26s %10.4f %10.4f %10.4f %12.1f%n",
                    c.profile, c.wAdvance, c.wCopy, c.wEval, c.tAdv);

        int scen = cells.isEmpty() ? 0 : cells.get(0).scenarios;
        System.out.println("\n  Each cell above: " + scen + " distinct position(s), each replanned once"
                + " per repetition. Identical positions and seeds in every cell, so the ratios between"
                + " cells are paired.");

        System.out.println("\n=== DOES AN OPERATION COST THE SAME FOR EVERY CONFIGURATION? ===");
        System.out.println("  (this is the question the old single-weight calibration assumed the answer to)");
        System.out.printf(Locale.ROOT, "  %10s %26s %26s %14s%n",
                "level", "cheapest advance", "dearest advance", "spread");
        for (int level : levels) {
            Cell lo = null, hi = null;
            for (Cell c : cells) {
                if (c.units != level || c.tAdv <= 0) continue;
                if (lo == null || c.tAdv < lo.tAdv) lo = c;
                if (hi == null || c.tAdv > hi.tAdv) hi = c;
            }
            if (lo == null) continue;
            System.out.printf(Locale.ROOT, "  %10d %18s %6.1f %18s %6.1f %13.1f%%%n",
                    level, lo.config + ratioTag(lo.ratio), lo.tAdv, hi.config + ratioTag(hi.ratio),
                    hi.tAdv, 100 * (hi.tAdv / lo.tAdv - 1));
        }

        System.out.println("\n  Same question across levels, for the reference configuration:");
        for (int level : levels)
            for (Cell c : cells)
                if (c.units == level && c.config.equals(REFERENCE_CONFIG) && c.ratio < 0)
                    System.out.printf(Locale.ROOT,
                            "    %8d units: %7.1f ns/advance  (%.2fx the floor level)%n",
                            level, c.tAdv, ref.tAdv > 0 ? c.tAdv / ref.tAdv : 0);

        System.out.println("\n=== WHAT THE OLD GLOBAL WEIGHTS WOULD HAVE MIS-ALLOCATED ===");
        System.out.println("  Wall-clock a nominal 1,000-unit budget would really have bought, priced with");
        System.out.println("  one global weight set (advance=1, copy=0.16, eval=0.077) against each cell's");
        System.out.println("  own realized operation mix. Equal columns = the old scheme was already fair.");
        System.out.printf(Locale.ROOT, "  %10s %12s %12s %14s%n",
                "level", "min (ms)", "max (ms)", "unfairness");
        for (int level : levels) {
            double lo = Double.MAX_VALUE, hi = 0;
            String loName = "", hiName = "";
            for (Cell c : cells) {
                if (c.units != level || c.adv == 0) continue;
                double oldUnits = c.adv + 0.16 * c.cop + 0.077 * c.ev;
                double nanos = c.adv * c.tAdv + c.cop * c.tCopy + c.ev * c.tEval;
                double msPerKUnit = oldUnits > 0 ? (1000.0 * nanos / oldUnits) / 1e6 : 0;
                if (msPerKUnit < lo) { lo = msPerKUnit; loName = c.profile; }
                if (msPerKUnit > hi) { hi = msPerKUnit; hiName = c.profile; }
            }
            if (hi > 0)
                System.out.printf(Locale.ROOT, "  %10d %12.3f %12.3f %13.1f%%   (%s vs %s)%n",
                        level, lo, hi, 100 * (hi / lo - 1), loName, hiName);
        }
        System.out.println("  A large percentage here is the bias the per-profile weights remove: the same");
        System.out.println("  nominal budget was buying that much more compute for one agent than another.");
    }

    private static String ratioTag(int ratio) { return ratio > 0 ? "@" + ratio : ""; }

    // ------------------------------------------------------------------ output

    private static void writeWeights(File f, List<Cell> cells, Cell ref, int reps, int[] levels) {
        StringBuilder sb = new StringBuilder();
        sb.append("# v6 ComputeMeter weights, measured on this machine, PER CONFIGURATION PER BUDGET LEVEL.\n");
        sb.append("# Loaded automatically by core.game.ComputeMeter. Delete this file to fall back to the\n");
        sb.append("# compiled defaults; -Dcompute.wCopy / -Dcompute.wEval still override the globals.\n");
        sb.append("#\n");
        sb.append("# One compute-unit = one advance in ").append(ref.profile).append(" = ")
          .append(String.format(Locale.ROOT, "%.1f", ref.tAdv)).append(" ns here. Every weight below is\n");
        sb.append("# relative to THAT, so W_ADVANCE is 1.0 only for the reference cell: an advance that\n");
        sb.append("# really takes 1.4x as long is charged 1.4 units. Equal unit budgets are therefore\n");
        sb.append("# equal wall-clock across configurations and proportional across levels.\n");
        sb.append("#\n");
        sb.append("# machine=").append(ServerProfile.get().cpuModel().replace('\n', ' ')).append('\n');
        sb.append("# cores=").append(ServerProfile.get().physicalCores()).append(" physical / ")
          .append(ServerProfile.get().logicalCpus()).append(" logical\n");
        sb.append("# java=").append(System.getProperty("java.version")).append('\n');
        sb.append("# measured=").append(new java.util.Date()).append('\n');
        sb.append("# reps=").append(reps).append(" levels=")
          .append(java.util.Arrays.toString(levels)).append('\n');
        sb.append("compute.reference=").append(ref.profile).append('\n');
        sb.append(String.format(Locale.ROOT, "compute.referenceAdvanceNanos=%.3f%n", ref.tAdv));
        sb.append("\n# Global fallback: the reference cell's weights. Used by anything with no profile\n");
        sb.append("# of its own (other agent families, probes, uncalibrated budgets).\n");
        sb.append(String.format(Locale.ROOT, "compute.wAdvance=%.6f%n", ref.wAdvance));
        sb.append(String.format(Locale.ROOT, "compute.wCopy=%.6f%n", ref.wCopy));
        sb.append(String.format(Locale.ROOT, "compute.wEval=%.6f%n", ref.wEval));
        sb.append("\n# Per-profile weights. Key: compute.profile.<config>[_r<ratio>]@<budgetUnits>.<op>\n");
        for (Cell c : cells) {
            String p = "compute.profile." + c.profile + ".";
            sb.append(String.format(Locale.ROOT, "%swAdvance=%.6f%n", p, c.wAdvance));
            sb.append(String.format(Locale.ROOT, "%swCopy=%.6f%n", p, c.wCopy));
            sb.append(String.format(Locale.ROOT, "%swEval=%.6f%n", p, c.wEval));
            sb.append(String.format(Locale.ROOT, "%sadvanceNanos=%.3f%n", p, c.tAdv));
        }
        RunGuard.atomicWrite(f, sb.toString());
    }

    /**
     * One row per cell.
     *
     * <p>{@code MsPerKiloUnitOld} is what 1,000 nominal units really cost this cell under the OLD
     * global weights, given its own realized operation mix: the column whose spread across cells is
     * the unfairness the per-profile weights remove. Under the new weights that same quantity is
     * {@code 1000 x referenceAdvanceNanos} for every cell by construction - the constancy IS the fix -
     * so it is reported once as {@code MsPerKiloUnitNew} rather than varying per row.
     */
    private static void writeCsv(File f, List<Cell> cells, Cell ref) {
        StringBuilder sb = new StringBuilder(
                "Profile,Config,Ratio,BudgetUnits,Scenarios,Advances,Copies,Evals,"
                + "AdvanceNanos,CopyNanos,EvalNanos,CvAdvance,CvCopy,CvEval,"
                + "W_ADVANCE,W_COPY,W_EVAL,MsPerKiloUnitNew,MsPerKiloUnitOld,Status\n");
        for (Cell c : cells) {
            double oldUnits = c.adv + 0.16 * c.cop + 0.077 * c.ev;
            double nanos = c.adv * c.tAdv + c.cop * c.tCopy + c.ev * c.tEval;
            double msPerKiloOld = oldUnits > 0 ? (1000.0 * nanos / oldUnits) / 1e6 : 0;
            sb.append(String.format(Locale.ROOT,
                    "%s,%s,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.6f,%.6f,%.6f,%.5f,%.5f,%s%n",
                    c.profile, c.config, c.ratio > 0 ? String.valueOf(c.ratio) : "NA", c.units,
                    c.scenarios, c.adv, c.cop, c.ev, c.tAdv, c.tCopy, c.tEval,
                    c.cvAdv, c.cvCopy, c.cvEval, c.wAdvance, c.wCopy, c.wEval,
                    1000.0 * ref.tAdv / 1e6, msPerKiloOld,
                    c.failures.isEmpty() ? "ok" : "PROBLEM: " + String.join("; ", c.failures)
                            .replace(',', ';')));
        }
        RunGuard.atomicWrite(f, sb.toString());
    }

    // ------------------------------------------------------------------ helpers

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

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private static double[] column(List<double[]> rows, int idx) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i)[idx];
        return out;
    }

    private static double mean(double[] v) {
        double s = 0; for (double d : v) s += d; return v.length > 0 ? s / v.length : 0;
    }

    /**
     * The fastest repetition — the estimator.
     *
     * <p>Every repetition runs byte-identical work, so all variation between them is machine
     * interference, and interference can only make a repetition SLOWER. The mean is therefore
     * contaminated by whichever repetition happened to be disturbed, while the fastest is the
     * least-contended observation and the better estimate of what the operation actually costs.
     *
     * <p>Weights are RATIOS between cells, so a consistent estimator matters more than an unbiased
     * one: any systematic difference between "fastest" and "mean" applies to every cell and cancels.
     *
     * <p>Zero entries mean the operation never occurred in that repetition; since all repetitions run
     * identical work that is either true of all of them or none, but it is filtered defensively so a
     * degenerate counter cannot pull the minimum to zero.
     */
    private static double fastest(double[] v) {
        double best = Double.POSITIVE_INFINITY;
        for (double d : v) if (d > 0 && d < best) best = d;
        return Double.isInfinite(best) ? 0 : best;
    }

    /**
     * Relative gap between the two fastest repetitions — the stability gate.
     *
     * <p>The question worth gating on is whether the least-contended measurements agree, not whether
     * every repetition agreed: a cell should not be rejected for the behaviour of its WORST
     * repetition, a number that carries no information about what the operation costs. Returns 0
     * when there are fewer than two usable repetitions, which the caller reports rather than gates.
     */
    private static double twoFastestGap(double[] v) {
        double a = Double.POSITIVE_INFINITY, b = Double.POSITIVE_INFINITY;
        for (double d : v) {
            if (d <= 0) continue;
            if (d < a) { b = a; a = d; }
            else if (d < b) b = d;
        }
        if (Double.isInfinite(a) || Double.isInfinite(b)) return 0;
        return (b - a) / a;
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
