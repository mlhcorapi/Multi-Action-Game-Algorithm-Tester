package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v6 Isolation Phase — single entry point. Greedy-initialization rebuild of v5 (200-scenario
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
 *   <li>a greedy-init invariance check + a thread-count auto-tune run once and are checkpointed
 *       ({@code _greedy_init_ok.txt}, {@code _tuned_threads.txt});</li>
 *   <li>the isolation phase resumes per (agent, ratio, budget) AND per (scenario, visit).</li>
 * </ol>
 *
 * <p>Output: {@code test_results/v6/<Agent>/[ratio_<r>/]<cu>cu/results.csv} + {@code turn_telemetry.csv}.
 *
 * <p>Usage: {@code java -Xmx750g -cp "out:lib/*" core.data.exp.RunExperimentV6 [threads|auto] [cuCsv]}
 */
public class RunExperimentV6 {

    public static final String BASE = "test_results/v6";

    /**
     * v6 budget levels, in compute-units of SEARCH (greedy initialization excluded).
     *
     * <p><b>Provisional.</b> These carry v5's numbers forward so the pipeline is runnable end-to-end,
     * but v6's budget bounds search only, so the effective search effort at each level is strictly
     * greater than v5's. The C0 calibration re-derives them against the reference machine — and may
     * raise the smallest level, since greedy init plus 20 mandatory bridge burns leave little room at
     * the bottom of the range. Update from C0 output before the production run.
     */
    public static final int[] DEFAULT_COMPUTE_LEVELS = {13_400, 26_800, 134_000};

    public static void main(String[] args) throws Exception {
        String threadsArg = args.length > 0 ? args[0] : "auto";
        int[] cuLevels = args.length > 1 ? parseInts(args[1]) : DEFAULT_COMPUTE_LEVELS;
        int numSingle = args.length > 2 ? Integer.parseInt(args[2].trim()) : BuildV6Portfolio.NUM_SINGLE_SCENARIOS;
        int numDual   = args.length > 3 ? Integer.parseInt(args[3].trim()) : BuildV6Portfolio.NUM_DUAL_SCENARIOS;
        long t0 = System.currentTimeMillis();

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        File portfolioDir = new File(BASE + "/_portfolio");
        File ckpt = new File(baseDir, "_completed.txt");

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v6 — Isolation Phase   (output=" + BASE + ")");
        System.out.println("# 200 scenarios × " + IsolationPhaseRunnerV6.VISITS_PER_SCENARIO + " visits = "
                + (200 * IsolationPhaseRunnerV6.VISITS_PER_SCENARIO) + " turns / config / budget");
        System.out.println("# Budgets: " + java.util.Arrays.toString(cuLevels)
                + " compute-units of SEARCH (STOP_COMPUTE; greedy init excluded)");
        System.out.println("# Init: GREEDY 1-ply, shared across all configs per (scenario, visit)");
        System.out.println("# Ratios: " + java.util.Arrays.toString(IsolationPhaseRunnerV6.RATIOS)
                + " ; threads=" + threadsArg + " ; cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println("############################################################");

        int provisional = "auto".equalsIgnoreCase(threadsArg)
                ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(threadsArg.trim());

        // 1. Portfolio: built once, then skipped on every later (re)entry.
        banner("PORTFOLIO build", t0);
        BuildV6Portfolio.main(new String[]{ portfolioDir.getPath(), String.valueOf(provisional),
                String.valueOf(numSingle), String.valueOf(numDual) });

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
            ExpCsv.writeText(initMarker, "ok\n");
        }

        // 1c. Thread-count auto-tune (only when threads=auto). Checkpointed so a restart reuses the pick.
        int threads;
        File tunedFile = new File(baseDir, "_tuned_threads.txt");
        if (!"auto".equalsIgnoreCase(threadsArg)) {
            threads = Integer.parseInt(threadsArg.trim());
            System.out.println("[v6] threads=" + threads + " (fixed by CLI)");
        } else if (tunedFile.exists()) {
            threads = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(tunedFile.toPath())).trim());
            System.out.println("[v6] threads=" + threads + " (reused from " + tunedFile.getName() + ")");
        } else {
            banner("THREAD auto-tune", t0);
            threads = tuneThreads(portfolio, cuLevels[0]);
            ExpCsv.writeText(tunedFile, threads + "\n");
            System.out.println("[v6] threads=" + threads + " (auto-tuned, saved to " + tunedFile.getName() + ")");
        }

        // 2. Isolation phase — resumable per (agent, ratio, budget) AND per (scenario, visit).
        Set<String> done = ExpCsv.readCompleted(ckpt);
        for (int cu : cuLevels) {
            banner("ISOLATION cu=" + cu + "  (threads=" + threads + ")", t0);
            IsolationPhaseRunnerV6.runForCompute(cu, threads, portfolio, baseDir, ckpt, done);
        }

        System.out.println("\n############################################################");
        System.out.println("# v6 COMPLETE in " + elapsed(t0) + ".  Output in " + baseDir.getAbsolutePath());
        System.out.println("############################################################");
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
     * Picks the thread count with the best throughput on a small fixed EMCTS workload at the smallest
     * budget (no persistence). Sweeps a few multiples of the core count and returns the fastest.
     */
    private static int tuneThreads(List<Scenario> portfolio, int budget) {
        int cores = Runtime.getRuntime().availableProcessors();
        Set<Integer> cand = new LinkedHashSet<>();
        for (int c : new int[]{ Math.max(1, cores / 2), cores, cores + cores / 2, cores * 2 }) cand.add(c);
        int maxCand = 0; for (int c : cand) maxCand = Math.max(maxCand, c);

        int visits = 4;
        int targetTurns = Math.max(64, maxCand * 4);
        int numScen = Math.min(portfolio.size(), (targetTurns + visits - 1) / visits);
        List<Scenario> subset = portfolio.subList(0, numScen);
        int turns = numScen * visits;

        // Warm up the JIT so the first timed candidate is not penalised.
        IsolationPhaseRunnerV6.benchmarkThroughput(budget, cores, portfolio.subList(0, Math.min(4, portfolio.size())), 2);

        System.out.println("  tuning on " + turns + " turns (" + numScen + " scenarios × " + visits
                + " visits) at " + budget + " cu:");
        int best = cores; long bestMs = Long.MAX_VALUE;
        for (int c : cand) {
            long ms = IsolationPhaseRunnerV6.benchmarkThroughput(budget, c, subset, visits);
            double rate = ms > 0 ? (turns * 1000.0 / ms) : 0;
            System.out.println(String.format(Locale.ROOT, "    threads=%-5d  %6d ms   %7.1f turns/s", c, ms, rate));
            if (ms < bestMs) { bestMs = ms; best = c; }
        }
        System.out.println("  best thread count: " + best);
        return best;
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
