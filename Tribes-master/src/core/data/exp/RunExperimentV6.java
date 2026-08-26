package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v6 Isolation Phase — single entry point. Greedy-initialization rebuild of v5 (100-scenario
 * portfolio, 50 visits, ratios {2,5,10,20,50}, deterministic STOP_COMPUTE budget bounding SEARCH only).
 *
 * <p>See {@code analysis/EXPERIMENT_V6_PLAN.md}. The C0 calibration
 * ({@link CalibrationRunnerV6}) is a SEPARATE job that should be run and read FIRST — it decides the
 * budget rule and the budget levels this run uses.
 *
 * <p><b>Built for long, restart-prone runs.</b> The whole pipeline is resumable so a server restart
 * loses almost nothing:
 * <ol>
 *   <li>portfolio build is skipped if a complete one already exists ({@code _portfolio_complete.txt});</li>
 *   <li>a greedy-init invariance check runs once and is checkpointed ({@code _greedy_init_ok.txt});</li>
 *   <li>the thread count is auto-tuned PER BUDGET LEVEL and checkpointed per level
 *       ({@code _tuned_threads_<cu>cu.txt}) — see {@link ThreadCalibrationV6};</li>
 *   <li>the isolation phase resumes per (agent, ratio, budget) AND per (scenario, visit).</li>
 * </ol>
 *
 * <p><b>Multithreading does not affect results.</b> The budget is a deterministic compute-unit count,
 * so a turn performs identical work whether it runs alone or alongside 100 others; the thread count
 * changes only wall-clock. Contrast the C0 timing calibration ({@link CalibrationRunnerV6}), which
 * measures time and therefore must run single-threaded on a quiesced machine.
 *
 * <p><b>How many threads.</b> {@link ServerProfile} sets the ceiling from the machine's PHYSICAL core
 * count (SMT siblings are never given to workers), {@link ThreadCalibrationV6} measures the sustained
 * throughput curve below that ceiling and picks its knee, and a {@link LoadGovernor} lowers
 * concurrency during the run itself if the heap comes under pressure. Override the ceiling with
 * {@code -Dv6.maxThreads=N} when the machine is shared.
 *
 * <p><b>Stopping is safe.</b> {@link RunGuard} turns a Ctrl-C / SIGTERM into a cooperative stop:
 * queued turns are abandoned, running turns finish and write, no config is marked complete unless it
 * really is, and re-running the same command resumes.
 *
 * <p>Output: {@code test_results/v6/<Agent>/[ratio_<r>/]<cu>cu/results.csv} + {@code turn_telemetry.csv}.
 *
 * <p>Usage: {@code java -Xmx750g -cp "out:lib/*" core.data.exp.RunExperimentV6 [threads|auto] [cuCsv]}
 */
public class RunExperimentV6 {

    public static final String BASE = "test_results/v6";

    /**
     * v6 budget levels, in compute-units of SEARCH (greedy initialization excluded):
     * <b>the floor, twice the floor, ten times the floor.</b>
     *
     * <p><b>Where 40,000 comes from.</b> Measured, not carried over — see {@link BudgetFloorProbe},
     * which is the notebook cell that produces this number. v5's 13,400 was far too small once the
     * budget bounded search only: the crossover bridge-burn tournament is a 3-level bracket over the
     * phase's top eight elites, and its level-1 round pairs them two at a time, so a pool below FOUR
     * produces a single child, fails an internal guard and falls back to promoting the best child —
     * which is exactly what plain EMCTS does. At 13,400 that happened at more than half of the 20
     * burns, so half the "crossover bridge burning" condition was not running crossover bridge
     * burning at all.
     *
     * <p>Measured tournament-completion rate against budget on the v6 portfolio:
     * <pre>
     *   13,400 -> 46%     40,000 -> 74%     70,000 -> 89%
     *   26,800 -> 63%     54,000 -> 84%     90,000 -> 89%   (ceiling)
     * </pre>
     * The rate saturates near 0.89: in the widest-branching scenarios a phase produces few children
     * however large its slice is, so roughly one burn in nine can never assemble a pool of four. 40,000
     * reaches ~83% of that ceiling and is the smallest level at which the phase between two burns
     * evaluates <b>17 new children</b> — twice the 8-slot elite pool, so the pool is fully turned over
     * between consecutive burns rather than merely topped up.
     *
     * <p>The other two levels follow from it by construction (2x, 10x). Both sit at the coverage
     * ceiling, so the design spans a deliberately-constrained condition and two unconstrained ones.
     *
     * <p>These are in units of the CURRENT {@link core.game.ComputeMeter} weights. Re-running
     * {@code ComputeCalibrateV6} changes what a unit is, so re-run {@link BudgetFloorProbe} after it
     * and update these if the floor moves.
     */
    public static final int[] DEFAULT_COMPUTE_LEVELS = {40_000, 80_000, 400_000};

    public static void main(String[] args) throws Exception {
        RunGuard.install("EXPERIMENT v6",
                "re-run the SAME command — finished configs are skipped and a partial one continues "
                + "per (scenario, visit)");
        String threadsArg = args.length > 0 ? args[0] : "auto";
        int[] cuLevels = args.length > 1 ? parseInts(args[1]) : DEFAULT_COMPUTE_LEVELS;
        int numSingle = args.length > 2 ? Integer.parseInt(args[2].trim()) : BuildV6Portfolio.NUM_SINGLE_SCENARIOS;
        int numDual   = args.length > 3 ? Integer.parseInt(args[3].trim()) : BuildV6Portfolio.NUM_DUAL_SCENARIOS;
        long t0 = System.currentTimeMillis();

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        // "Re-run the same cell to resume" is only safe if the previous run really has stopped.
        if (!RunGuard.acquireLock(baseDir, "RunExperimentV6")) RunGuard.abort(2);
        File portfolioDir = new File(BASE + "/_portfolio");
        File ckpt = new File(baseDir, "_completed.txt");

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v6 — Isolation Phase   (output=" + BASE + ")");
        System.out.println("# " + (numSingle + numDual) + " scenarios × "
                + IsolationPhaseRunnerV6.VISITS_PER_SCENARIO + " visits = "
                + ((numSingle + numDual) * IsolationPhaseRunnerV6.VISITS_PER_SCENARIO)
                + " turns / config / budget");
        System.out.println("# Budgets: " + java.util.Arrays.toString(cuLevels)
                + " compute-units of SEARCH (STOP_COMPUTE; greedy init excluded)");
        System.out.println("# Init: GREEDY 1-ply, shared across all configs per (scenario, visit)");
        System.out.println("# Ratios: " + java.util.Arrays.toString(IsolationPhaseRunnerV6.RATIOS)
                + " ; threads=" + threadsArg);
        System.out.println("############################################################");
        // Provenance: a compute-unit is only defined relative to these weights, so the run log must
        // record which ones priced this dataset.
        System.out.println("# ComputeMeter weights: " + core.game.ComputeMeter.weightsSummary()
                + "   (override with -Dcompute.wCopy / -Dcompute.wEval; see BudgetFairnessProbe)");
        System.out.println("# " + ComputeCalibrateV6.coverageBanner(cuLevels));
        System.out.println("############################################################");
        ServerProfile machine = ServerProfile.get();
        machine.printBanner("EXPERIMENT v6");

        int provisional = "auto".equalsIgnoreCase(threadsArg)
                ? machine.maxWorkers()
                : Math.min(machine.maxWorkers(), Integer.parseInt(threadsArg.trim()));

        // 1. Portfolio: built once, then skipped on every later (re)entry.
        banner("PORTFOLIO build", t0);
        BuildV6Portfolio.build(portfolioDir.getPath(), provisional, numSingle, numDual);

        banner("PORTFOLIO verify (startup)", t0);
        VerifyV4Portfolio.Report rep = VerifyV4Portfolio.verify(portfolioDir);
        if (!rep.allPass()) {
            System.err.println("[v6] ABORT: " + rep.failed.size() + " scenario(s) failed verification: " + rep.failed);
            System.exit(1);
        }

        banner("PORTFOLIO load", t0);
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(portfolioDir);
        System.out.println("  loaded " + portfolio.size() + " scenarios into memory");

        // 1b. Greedy-init invariance: every config at every budget MUST share the initial sequence for
        // a given (scenario, visit). Checkpointed so a restart skips it.
        File initMarker = new File(baseDir, "_greedy_init_ok.txt");
        if (initMarker.exists()) {
            System.out.println("[v6] greedy-init invariance already verified (" + initMarker.getName() + ") — skipping");
        } else {
            banner("GREEDY-INIT verify", t0);
            if (!verifyGreedyInit(portfolio)) {
                System.err.println("[v6] ABORT: greedy initial sequence is NOT shared across configs/budgets.");
                System.exit(1);
            }
            RunGuard.atomicWrite(initMarker, "ok\n");
        }

        // 2. Isolation phase — resumable per (agent, ratio, budget) AND per (scenario, visit).
        // Threads are tuned PER BUDGET LEVEL: search-tree size grows with the budget, so the memory
        // ceiling (and therefore the best thread count) at 134k units is not the one at 13.4k. Each
        // level's pick is checkpointed separately so a restart reuses it.
        boolean autoThreads = "auto".equalsIgnoreCase(threadsArg);
        int fixedThreads = autoThreads ? -1 : Integer.parseInt(threadsArg.trim());

        Set<String> done = ExpCsv.readCompleted(ckpt);
        for (int cu : cuLevels) {
            if (RunGuard.stopRequested()) break;
            int threads = resolveThreads(autoThreads, fixedThreads, baseDir, portfolio, cu, t0);
            banner("ISOLATION cu=" + cu + "  (threads=" + threads + ")", t0);
            IsolationPhaseRunnerV6.runForCompute(cu, threads, portfolio, baseDir, ckpt, done);
        }

        System.out.println("\n############################################################");
        if (RunGuard.stopRequested()) {
            System.out.println("# v6 STOPPED after " + elapsed(t0) + " — progress is on disk in "
                    + baseDir.getAbsolutePath());
            System.out.println("# Re-run the same command to resume where it left off.");
        } else {
            System.out.println("# v6 COMPLETE in " + elapsed(t0) + ".  Output in " + baseDir.getAbsolutePath());
        }
        System.out.println("############################################################");
        RunGuard.finished();
    }

    /**
     * Asserts that for sampled (scenario, visit) pairs, ALL 12 configs at MULTIPLE budgets produce the
     * byte-identical greedy initial sequence — the paired-comparison guarantee.
     *
     * <p>This matters MORE under greedy init than it did under random init, not less. Greedy
     * initialization breaks score ties by drawing from the visit-seeded RNG, so the shared root is not
     * a pure function of the game state: it holds only because every config runs byte-identical
     * initialization code, consuming the same draws in the same order, before any operator-specific
     * work touches the RNG. That is an invariant worth checking rather than assuming, and it is cheap.
     */
    private static boolean verifyGreedyInit(List<Scenario> portfolio) {
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        int[] verifyBudgets = {1_000, 4_000};   // small => fast; init is budget-independent by construction
        int n = portfolio.size();
        int[] idx = {0, n / 4, n / 2, (3 * n) / 4, n - 1};   // span single (low ids) and dual (high ids)
        int checked = 0, mismatches = 0;
        for (int si : idx) {
            if (si < 0 || si >= n) continue;
            Scenario sc = portfolio.get(si);
            for (int visit = 0; visit <= 1; visit++) {
                long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, visit);
                String reference = null, refLabel = null;
                for (int cu : verifyBudgets) {
                    for (Object[] spec : configSpecs(cu)) {
                        String label = spec[0] + "/" + cu + "cu";
                        String packed = initialSequenceOf((AgentSpec) spec[1], sc, seed);
                        if (packed == null) continue;   // act() threw (logged) — ignore for the invariant
                        checked++;
                        if (reference == null) { reference = packed; refLabel = label; }
                        else if (!reference.equals(packed)) {
                            mismatches++;
                            System.out.println("  MISMATCH scenario " + sc.id + " visit " + visit
                                    + ": [" + label + "] != [" + refLabel + "]");
                        }
                    }
                }
                System.out.println("  scenario " + sc.id + " (" + sc.source + ") visit " + visit
                        + ": " + (mismatches == 0 ? "all identical" : "MISMATCH"));
            }
        }
        System.out.println("  greedy-init invariance: " + (checked - mismatches) + "/" + checked
                + " agent×budget instances matched the per-(scenario,visit) reference");
        return mismatches == 0 && checked > 0;
    }

    /** Builds an agent, plans the turn, and returns its initial root sequence packed as a string (or
     *  null if act() threw). */
    private static String initialSequenceOf(AgentSpec spec, Scenario sc, long seed) {
        Agent a = spec.create(seed);
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int k = 0; k < sc.state.getTribes().length; k++) allIds.add(k);
        a.setPlayerIDs(sc.state.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        GameState gs = sc.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        try { a.act(gs, ect); } catch (Throwable t) { return null; }
        List<String> seq;
        if (a instanceof players.emcts.EMCTSAgent)
            seq = ((players.emcts.EMCTSAgent) a).getInitialRootSequenceForV4();
        else if (a instanceof players.ExperimentalEMCTS.ExperimentalEMCTSAgent)
            seq = ((players.ExperimentalEMCTS.ExperimentalEMCTSAgent) a).getInitialRootSequenceForV4();
        else return null;
        return String.join("|", seq);
    }

    /** The 12 (label, spec) v6 configs at a given compute budget. */
    private static List<Object[]> configSpecs(int cu) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"EMCTS", ExpAgents.emctsV6(cu)});
        out.add(new Object[]{"CrossoverBB", ExpAgents.crossoverBBV6(cu)});
        for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Object[]{"CrossoverExpansion@" + r, ExpAgents.crossoverExpansionV6(r, cu)});
        for (int r : IsolationPhaseRunnerV6.RATIOS) out.add(new Object[]{"CrossoverCombo@" + r, ExpAgents.crossoverComboV6(r, cu)});
        return out;
    }

    /**
     * Resolves the worker-thread count for one budget level: CLI override, previously-checkpointed
     * pick, or a fresh {@link ThreadCalibrationV6} sweep. The sweep's rows are appended to
     * {@code _calibration/thread_calibration.csv} so the scaling curve is reportable, not just used.
     */
    private static int resolveThreads(boolean autoThreads, int fixedThreads, File baseDir,
                                      List<Scenario> portfolio, int cu, long t0) throws Exception {
        if (!autoThreads) {
            int capped = Math.min(fixedThreads, ServerProfile.get().maxWorkers());
            if (capped != fixedThreads) {
                System.out.println("[v6] threads=" + fixedThreads + " requested; capped to the machine "
                        + "ceiling of " + capped + " (raise it with -Dv6.maxThreads / -Dv6.reserveCores)");
            }
            System.out.println("[v6] threads=" + capped + " (fixed by CLI) for " + cu + " cu");
            return capped;
        }
        File tunedFile = new File(baseDir, "_tuned_threads_" + cu + "cu.txt");
        if (tunedFile.exists()) {
            int t = Integer.parseInt(new String(
                    java.nio.file.Files.readAllBytes(tunedFile.toPath())).trim());
            System.out.println("[v6] threads=" + t + " (reused from " + tunedFile.getName() + ")");
            return t;
        }
        banner("THREAD auto-tune  (" + cu + " cu)", t0);
        File calibDir = new File(baseDir, "_calibration");
        ExpCsv.ensureDir(calibDir);
        // The sweep appends each point to the shared CSV as it finishes, so the scaling curve is
        // reportable — and survives a stop mid-sweep — rather than only being used.
        ThreadCalibrationV6.Result r = ThreadCalibrationV6.calibrate(
                portfolio, cu, null, new File(calibDir, "thread_calibration.csv"));
        if (RunGuard.stopRequested()) return r.chosen;   // never checkpoint a half-measured sweep
        RunGuard.atomicWrite(tunedFile, r.chosen + "\n");

        System.out.println("[v6] threads=" + r.chosen + " (auto-tuned, saved to " + tunedFile.getName() + ")");
        return r.chosen;
    }

    private static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }

    private static void banner(String phase, long t0) {
        System.out.println("\n============================================================");
        System.out.println("== " + phase + "   [elapsed " + elapsed(t0) + "]");
        System.out.println("============================================================");
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
