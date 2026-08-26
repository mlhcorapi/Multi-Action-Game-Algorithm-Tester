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
 *   <li><b>warms up and then repeats identical work</b>, and reports how far each per-operation time
 *       moves between repetitions - which, because the work is identical, is a statement about the
 *       machine rather than about the portfolio;</li>
 *   <li><b>checks its own validity</b> per cell and prints a PASS/FAIL verdict: enough operations to
 *       divide by, repetition spread inside tolerance, plausible weights, and an idle machine;</li>
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
 * cheap way to confirm it - a converged calibration reproduces its own weights within the CV it
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
    /** Turns run and discarded before the first measurement, so JIT compilation is not timed. */
    public static final int DEFAULT_WARMUP = 6;
    /**
     * Size of the scenario pool a cell's turns are drawn from. Levels that run fewer turns take a
     * spread subset of this same pool, so every cell at every level sees the same positions.
     */
    public static final int SCENARIO_POOL = 8;

    /** Coefficient of variation across repetitions above which a cell is not trustworthy. */
    public static final double MAX_CV = 0.10;
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
        double tAdv, tCopy, tEval;  // means across reps
        double cvAdv, cvCopy, cvEval;
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
                default: throw new IllegalArgumentException("unknown config " + config);
            }
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int reps            = args.length > 2 ? Integer.parseInt(args[2].trim()) : DEFAULT_REPS;
        int[] turnsPerRep   = GameLengthProbeV6.parseInts(
                                    args.length > 3 ? args[3] : DEFAULT_TURNS_PER_REP);
        int warmup          = args.length > 4 ? Integer.parseInt(args[4].trim()) : DEFAULT_WARMUP;
        int[] levels        = args.length > 5 ? GameLengthProbeV6.parseInts(args[5])
                                              : RunExperimentV6.DEFAULT_COMPUTE_LEVELS;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);

        List<Cell> cells = new ArrayList<>();
        for (int level : levels) cells.addAll(cellsForLevel(level));

        System.out.println("############################################################");
        System.out.println("# v6 compute-unit weight calibration (per config, per budget level)");
        System.out.println("# Measures what an advance, a copy and an eval cost FOR EACH CONFIGURATION");
        System.out.println("# AT EACH BUDGET LEVEL, relative to one reference operation, and writes them");
        System.out.println("# where the rest of the pipeline picks them up.");
        System.out.println("############################################################");
        System.out.println("# SINGLE-THREADED and requires an idle machine: this is a timing measurement.");
        System.out.println("# currently in force: " + ComputeMeter.weightsSummary());
        System.out.println("# levels=" + java.util.Arrays.toString(levels)
                + "  configs=" + (cells.size() / Math.max(1, levels.length))
                + "  cells=" + cells.size());
        System.out.println("# reps=" + reps + "  turns(=positions)/rep per level="
                + java.util.Arrays.toString(turnsPerRep) + "  warmup=" + warmup);
        System.out.println("# A repetition repeats the SAME positions with the SAME seeds, so the CV"
                + " across reps measures the machine, not the portfolio.");
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

        // Warm up once on the reference cell: the hot code (GameState.advance/copy, the heuristic) is
        // shared by every configuration, so JIT state carries across cells. Each cell still discards
        // its own first turn, which is what touches that agent's own code paths.
        System.out.println("\n[weights] warmup: " + warmup + " turns (discarded)");
        Cell warm = new Cell(REFERENCE_CONFIG, -1, levels[0]);
        ComputeMeter.PROFILE = true;          // warm the TIMED code path, not the untimed one
        for (int i = 0; i < warmup; i++)
            runTurn(warm, scenarioFor(portfolio, i % SCENARIO_POOL, SCENARIO_POOL), 9_000 + i);
        ComputeMeter.PROFILE = false;

        long t0 = System.currentTimeMillis();
        int done = 0;
        for (Cell c : cells) {
            int levelIdx = indexOf(levels, c.units);
            int turns = turnsPerRep[Math.min(levelIdx, turnsPerRep.length - 1)];
            measure(c, portfolio, reps, turns);
            done++;
            System.out.printf(Locale.ROOT,
                    "  [%2d/%2d] %-30s advance %7.1f ns  copy %7.1f ns  eval %7.1f ns   "
                    + "(CV %.1f/%.1f/%.1f%%)%s%n",
                    done, cells.size(), c.profile, c.tAdv, c.tCopy, c.tEval,
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
        if (loadAfter >= 0) {
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

        File weightsFile = new File(out, WEIGHTS_FILE);
        File profilesCsv = new File(out, PROFILES_CSV);
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

    /** The 12 isolation-phase configurations at one budget level, in the canonical order. */
    private static List<Cell> cellsForLevel(int units) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell("EMCTS", -1, units));
        out.add(new Cell("CrossoverBB", -1, units));
        for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Cell("CrossoverExpansion", r, units));
        for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Cell("CrossoverCombo", r, units));
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
        c.tAdv = mean(a);   c.cvAdv = cv(a);
        c.tCopy = mean(cp); c.cvCopy = cv(cp);
        c.tEval = mean(e);  c.cvEval = cv(e);

        if (c.adv < MIN_OPS) c.failures.add("only " + c.adv + " advances (need " + MIN_OPS + ")");
        if (c.cop < MIN_OPS) c.failures.add("only " + c.cop + " copies (need " + MIN_OPS + ")");
        if (c.ev  < MIN_OPS) c.failures.add("only " + c.ev + " evals (need " + MIN_OPS + ")");
        if (c.cvAdv > MAX_CV) c.failures.add(String.format(Locale.ROOT,
                "advance time varies %.1f%% between reps", 100 * c.cvAdv));
        if (c.cvCopy > MAX_CV) c.failures.add(String.format(Locale.ROOT,
                "copy time varies %.1f%% between reps", 100 * c.cvCopy));
        if (c.cvEval > MAX_CV) c.failures.add(String.format(Locale.ROOT,
                "eval time varies %.1f%% between reps", 100 * c.cvEval));
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

    /** Coefficient of variation: SD / mean. The stability statistic. */
    private static double cv(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double d : v) s += (d - m) * (d - m);
        double sd = Math.sqrt(s / (v.length - 1));
        return m != 0 ? sd / m : 0;
    }
}
