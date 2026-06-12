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
 * v5 Isolation Phase — single entry point. Compute-budget rebuild of v4 (200-scenario portfolio,
 * 100 visits, ratios {2,5,10,20,50}, deterministic STOP_COMPUTE budget).
 *
 * <p><b>Built for long, restart-prone runs.</b> The whole pipeline is resumable so a server restart
 * during the 12+ hour job loses almost nothing:
 * <ol>
 *   <li>portfolio build is skipped if a complete one already exists ({@code _portfolio_complete.txt});</li>
 *   <li>a random-init invariance check + a thread-count auto-tune run once and are checkpointed
 *       ({@code _random_init_ok.txt}, {@code _tuned_threads.txt});</li>
 *   <li>the isolation phase resumes per (agent, ratio, budget) AND per (scenario, visit) — only the
 *       turns that never finished are recomputed.</li>
 * </ol>
 *
 * <p>Output: {@code test_results/v5/<Agent>/[ratio_<r>/]<cu>cu/results.csv} + {@code turn_telemetry.csv}.
 *
 * <p>Usage: {@code java -Xmx750g -cp "out:lib/*" core.data.exp.RunExperimentV5 [threads|auto] [cuCsv]}<br>
 * Server example (dedicated, 750 GB): {@code java -Xmx750g -cp "out:lib/*" core.data.exp.RunExperimentV5 auto}
 */
public class RunExperimentV5 {

    public static final String BASE = "test_results/v5";

    /** v5 compute-unit budgets (0.5× / 1× / 5× of the calibrated 1000ms-equiv 26800 units). */
    public static final int[] DEFAULT_COMPUTE_LEVELS = {13_400, 26_800, 134_000};

    public static void main(String[] args) throws Exception {
        String threadsArg = args.length > 0 ? args[0] : "auto";   // "auto" => auto-tune the thread count
        int[] cuLevels = args.length > 1 ? parseInts(args[1]) : DEFAULT_COMPUTE_LEVELS;
        // args[2],[3] override the portfolio size (for reduced test runs); production uses 100 + 100.
        int numSingle = args.length > 2 ? Integer.parseInt(args[2].trim()) : BuildV5Portfolio.NUM_SINGLE_SCENARIOS;
        int numDual   = args.length > 3 ? Integer.parseInt(args[3].trim()) : BuildV5Portfolio.NUM_DUAL_SCENARIOS;
        long t0 = System.currentTimeMillis();

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        File portfolioDir = new File(BASE + "/_portfolio");
        File ckpt = new File(baseDir, "_completed.txt");

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v5 — Isolation Phase   (output=" + BASE + ")");
        System.out.println("# 200 scenarios × " + IsolationPhaseRunnerV5.VISITS_PER_SCENARIO + " visits = "
                + (200 * IsolationPhaseRunnerV5.VISITS_PER_SCENARIO) + " turns / config / budget");
        System.out.println("# Compute budgets: " + java.util.Arrays.toString(cuLevels) + " (STOP_COMPUTE)");
        System.out.println("# Ratios: " + java.util.Arrays.toString(IsolationPhaseRunnerV5.RATIOS)
                + " ; threads=" + threadsArg + " ; cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println("############################################################");

        // A provisional thread count for the build/tune phases (real isolation threads decided below).
        int provisional = "auto".equalsIgnoreCase(threadsArg)
                ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(threadsArg.trim());

        // 1. Portfolio: built once, then skipped on every later (re)entry.
        banner("PORTFOLIO build", t0);
        BuildV5Portfolio.main(new String[]{ portfolioDir.getPath(), String.valueOf(provisional),
                String.valueOf(numSingle), String.valueOf(numDual) });

        banner("PORTFOLIO verify (startup)", t0);
        VerifyV4Portfolio.Report rep = VerifyV4Portfolio.verify(portfolioDir);
        if (!rep.allPass()) {
            System.err.println("[v5] ABORT: " + rep.failed.size() + " scenario(s) failed verification: " + rep.failed);
            System.exit(1);
        }

        banner("PORTFOLIO load", t0);
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(portfolioDir);
        System.out.println("  loaded " + portfolio.size() + " scenarios into memory");

        // 1b. Random-init invariance: every config at every budget MUST share the initial sequence for a
        // given (scenario, visit). Checkpointed so a restart skips it.
        File initMarker = new File(baseDir, "_random_init_ok.txt");
        if (initMarker.exists()) {
            System.out.println("[v5] random-init invariance already verified (" + initMarker.getName() + ") — skipping");
        } else {
            banner("RANDOM-INIT verify", t0);
            if (!verifyRandomInit(portfolio)) {
                System.err.println("[v5] ABORT: random initial sequence is NOT shared across configs/budgets.");
                System.exit(1);
            }
            ExpCsv.writeText(initMarker, "ok\n");
        }

        // 1c. Thread-count auto-tune (only when threads=auto). Checkpointed so a restart reuses the pick.
        int threads;
        File tunedFile = new File(baseDir, "_tuned_threads.txt");
        if (!"auto".equalsIgnoreCase(threadsArg)) {
            threads = Integer.parseInt(threadsArg.trim());
            System.out.println("[v5] threads=" + threads + " (fixed by CLI)");
        } else if (tunedFile.exists()) {
            threads = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(tunedFile.toPath())).trim());
            System.out.println("[v5] threads=" + threads + " (reused from " + tunedFile.getName() + ")");
        } else {
            banner("THREAD auto-tune", t0);
            threads = tuneThreads(portfolio, cuLevels[0]);
            ExpCsv.writeText(tunedFile, threads + "\n");
            System.out.println("[v5] threads=" + threads + " (auto-tuned, saved to " + tunedFile.getName() + ")");
        }

        // 2. Isolation phase — resumable per (agent, ratio, budget) AND per (scenario, visit).
        Set<String> done = ExpCsv.readCompleted(ckpt);
        for (int cu : cuLevels) {
            banner("ISOLATION cu=" + cu + "  (threads=" + threads + ")", t0);
            IsolationPhaseRunnerV5.runForCompute(cu, threads, portfolio, baseDir, ckpt, done);
        }

        System.out.println("\n############################################################");
        System.out.println("# v5 COMPLETE in " + elapsed(t0) + ".  Output in " + baseDir.getAbsolutePath());
        System.out.println("############################################################");
    }

    /**
     * Asserts that for sampled (scenario, visit) pairs, ALL 12 configs at MULTIPLE compute budgets
     * produce the byte-identical initial action sequence. The initial sequence is built by
     * randomActions() from the visit-seeded RNG before the search loop, so it must not depend on the
     * algorithm or the budget. Uses small budgets (fast) and samples scenarios spanning single+dual.
     */
    private static boolean verifyRandomInit(List<Scenario> portfolio) {
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
                long seed = IsolationPhaseRunnerV5.visitSeed(sc.id, visit);
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
        System.out.println("  random-init invariance: " + (checked - mismatches) + "/" + checked
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

    /** The 12 (label, spec) configs at a given compute budget. */
    private static List<Object[]> configSpecs(int cu) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"EMCTS", ExpAgents.emctsV5(cu)});
        out.add(new Object[]{"CrossoverBB", ExpAgents.crossoverBBV5(cu)});
        for (int r : IsolationPhaseRunnerV5.RATIOS) out.add(new Object[]{"CrossoverExpansion@" + r, ExpAgents.crossoverExpansionV5(r, cu)});
        for (int r : IsolationPhaseRunnerV5.RATIOS) out.add(new Object[]{"CrossoverCombo@" + r, ExpAgents.crossoverComboV5(r, cu)});
        return out;
    }

    /**
     * Picks the thread count with the best throughput on a small fixed EMCTS workload at the smallest
     * compute budget (no persistence). Sweeps a few multiples of the core count and returns the fastest.
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
        IsolationPhaseRunnerV5.benchmarkThroughput(budget, cores, portfolio.subList(0, Math.min(4, portfolio.size())), 2);

        System.out.println("  tuning on " + turns + " turns (" + numScen + " scenarios × " + visits
                + " visits) at " + budget + " cu:");
        int best = cores; long bestMs = Long.MAX_VALUE;
        for (int c : cand) {
            long ms = IsolationPhaseRunnerV5.benchmarkThroughput(budget, c, subset, visits);
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
