package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.FastGameStateIO;
import core.game.Game;
import core.game.GameState;
import players.Agent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * v6 pipeline smoke test (into {@code test_results/smoke_v6/}). Tiny scale: 3 synthetic scenarios,
 * 2 visits, small budgets, 3 agents for the isolation pass. Asserts the v6 pipeline end-to-end.
 *
 * <p>Sections 1–5 mirror the v5 smoke (round-trip, startup validation, shared initial sequence,
 * invariance across configs × budgets, folder/schema, operator telemetry). Sections 6 and 7 are new,
 * and check the two properties that define the v6 budget:
 *
 * <ol start="6">
 *   <li><b>Greedy initialization is OUTSIDE the budget.</b> Every agent must report a non-zero
 *       initialization cost AND a search spend that still reaches the full budget. If initialization
 *       were being charged, search spend would fall short by exactly the initialization cost, so
 *       "InitUnits &gt; 0 and SearchUnits ≈ budget" is the discriminating observation.</li>
 *   <li><b>Bridge burning is INSIDE the budget.</b> All agents must perform the full 20 burns, and the
 *       crossover-bridge-burning agents must pay for their tournaments out of the same budget rather
 *       than on top of it: their search spend stays at the budget while they evaluate strictly fewer
 *       distinct sequences than EMCTS. That gap IS the efficiency cost under study. Also checks the
 *       per-phase spend trajectory is monotone and lands on the per-turn total.</li>
 * </ol>
 *
 * <p>If this passes, the v6 design is sound and the full {@link RunExperimentV6} is safe to launch.
 * Note the C0 calibration ({@link CalibrationRunnerV6}) is a separate job that should be run first.
 */
public class NewExperimentSmokeTestV6 {

    /** Small budgets for the invariance sweep. The property being checked — that all 12 configs share
     *  the greedy initial sequence — is budget-independent by construction, so tiny budgets prove it
     *  just as well as the real levels ({@link RunExperimentV6#DEFAULT_COMPUTE_LEVELS}) and keep the
     *  smoke test to seconds. */
    private static final int[] SMOKE_CU_LEVELS = {2_000, 5_000, 13_400};
    /** Budget for the tiny isolation pass + the shared-initial-sequence check. */
    private static final int   SMOKE_CU = 5_000;
    /**
     * Budget for the budget-boundary checks (sections 6/7). Deliberately larger than SMOKE_CU: the 20
     * mandatory crossover tournaments cost a large share of a small budget, so at SMOKE_CU the
     * crossover agents would overshoot and the "burns come out of the budget" check could not
     * distinguish overshoot from correct accounting.
     */
    private static final int   BOUNDARY_CU = 26_800;

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke_v6";
        deleteDir(new File(base));

        System.out.println("==================== 1. CAPTURE 3 tiny scenarios + FastGameStateIO round-trip ====================");
        List<Scenario> portfolio = captureTinyPortfolio(base + "/_portfolio");
        boolean roundTripOk = portfolio.size() == 3;
        for (Scenario sc : portfolio) {
            GameState reloaded = FastGameStateIO.load(
                    new File(base + "/_portfolio/scenarios/scenario_" + BuildV6Portfolio.sid(sc.id) + ".bin"));
            boolean same = reloaded.getTick() == sc.state.getTick()
                    && reloaded.getActiveTribeID() == sc.state.getActiveTribeID()
                    && reloaded.getBoard().getSize() == sc.state.getBoard().getSize()
                    && reloaded.getAllAvailableActions().size() == sc.state.getAllAvailableActions().size();
            System.out.println("  scenario " + BuildV6Portfolio.sid(sc.id) + " round-trip OK: " + same);
            roundTripOk &= same;
        }
        System.out.println("FastGameStateIO round-trip: " + (roundTripOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 1b. Per-scenario startup validation ====================");
        boolean allValid = true;
        for (Scenario sc : portfolio) {
            int aid = sc.state.getActiveTribeID();
            int cities = sc.state.getTribes()[aid].getNumCities();
            int units = 0;
            if (sc.state.getTribes()[aid].getCitiesID() != null) {
                for (Integer cId : sc.state.getTribes()[aid].getCitiesID()) {
                    core.actors.City c = (core.actors.City) sc.state.getActor(cId);
                    if (c != null && c.getUnitsID() != null) units += c.getUnitsID().size();
                }
            }
            boolean valid = BuildV5Portfolio.validateScenario(sc.state, sc.id);
            System.out.println("  scenario " + BuildV6Portfolio.sid(sc.id) + " tick=" + sc.state.getTick()
                    + " activeTribe=" + aid + " cities=" + cities + " units=" + units
                    + " -> " + (valid ? "PASS" : "FAIL"));
            allValid &= valid;
        }
        System.out.println("Per-scenario startup validation: " + (allValid ? "PASS" : "FAIL"));

        System.out.println("\n==================== 2. SHARED GREEDY initial sequence across agents ====================");
        boolean sharedInitOk = verifySharedInitialSequence(portfolio.get(0));
        System.out.println("Shared greedy initial sequence (EMCTS == BB == Expansion@5): " + (sharedInitOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 2b. INVARIANCE across budgets AND all 12 configs ====================");
        boolean acrossCuOk = verifyInitialSequenceInvariantAcrossBudgetsAndConfigs(portfolio.get(0));
        System.out.println("Invariance across " + Arrays.toString(SMOKE_CU_LEVELS) + " cu × all 12 configs: "
                + (acrossCuOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 3. Tiny isolation pass (3 agents, " + SMOKE_CU + " cu, 2 visits) ====================");
        File baseDir = new File(base);
        runTinyIsolation(SMOKE_CU, portfolio, baseDir);

        System.out.println("\n==================== 4. Folder + schema verification ====================");
        String cu = SMOKE_CU + "cu";
        boolean layoutOk = checkLeaf(new File(base + "/EMCTS/" + cu + "/results.csv"), false)
                && checkLeaf(new File(base + "/CrossoverBB/" + cu + "/results.csv"),    true)
                && checkLeaf(new File(base + "/CrossoverExpansion/ratio_5/" + cu + "/results.csv"), false);
        System.out.println("Folder layout + schema: " + (layoutOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 5. Operator-telemetry verification ====================");
        int expectedTurns = portfolio.size() * 2;   // 3 scenarios × 2 visits
        boolean teleOk = checkTelemetry(new File(base + "/EMCTS/" + cu + "/turn_telemetry.csv"), false, expectedTurns)
                && checkTelemetry(new File(base + "/CrossoverBB/" + cu + "/turn_telemetry.csv"), false, expectedTurns)
                && checkTelemetry(new File(base + "/CrossoverExpansion/ratio_5/" + cu + "/turn_telemetry.csv"), true, expectedTurns);
        System.out.println("Operator telemetry (EMCTS/BB xo=0; Expansion xo>0; counters sane): "
                + (teleOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 6+7. Budget boundaries (init OUT, bridge burning IN) ====================");
        boolean budgetOk = verifyBudgetBoundaries(portfolio.get(0));

        System.out.println("\n==================== 8. Thread-count invariance (1 thread == N threads) ====================");
        boolean threadOk = verifyThreadInvariance(portfolio);

        boolean allOk = roundTripOk && allValid && sharedInitOk && acrossCuOk && layoutOk && teleOk
                && budgetOk && threadOk;
        System.out.println("\nTotal smoke_v6 time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println(allOk ? "PASS: v6 pipeline sound." : "FAIL: see checks above.");
    }

    /**
     * Section 8. The property the whole compute-unit budget exists to buy: <b>the thread count cannot
     * change a result.</b>
     *
     * <p>Runs the same set of (config, scenario, visit) turns twice — once strictly single-threaded,
     * once across a pool — and requires every turn's outcome to match exactly: the greedy root, the
     * final sequence value, the realized compute units, the nodes evaluated, the distinct sequences,
     * and the bridge-burn count. Any mismatch means shared mutable state has leaked between workers,
     * and the experiment's results would then depend on how busy the machine was when they were
     * produced.
     *
     * <p>This is the check {@code EXPERIMENT_V6_PLAN.md} §8.5 left open, in the form that is actually
     * meaningful. It deliberately compares <i>agent behaviour</i>, not files: the serialized scenario
     * bytes are not stable across JVM runs (enum-keyed {@code HashMap}s serialize in identity-hash
     * order), so a checksum comparison would report a failure that has no bearing on any result. What
     * matters is that the same seed produces the same search, and that is what this asserts.
     */
    private static boolean verifyThreadInvariance(List<Scenario> portfolio) {
        final int cu = SMOKE_CU;
        final int visits = 2;
        List<Object[]> configs = new ArrayList<>();
        configs.add(new Object[]{"EMCTS", ExpAgents.emctsV6(cu)});
        configs.add(new Object[]{"CrossoverBB", ExpAgents.crossoverBBV6(cu)});
        configs.add(new Object[]{"CrossoverCombo@5", ExpAgents.crossoverComboV6(5, cu)});

        List<Callable<String>> tasks = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Object[] cfg : configs) {
            for (Scenario sc : portfolio) {
                for (int v = 0; v < visits; v++) {
                    final AgentSpec spec = (AgentSpec) cfg[1];
                    final Scenario scenario = sc;
                    final int visit = v;
                    tasks.add(() -> fingerprint(spec, scenario, visit));
                    labels.add(cfg[0] + "/scenario " + scenario.id + "/visit " + visit);
                }
            }
        }

        List<String> serial = new ArrayList<>();
        for (Callable<String> t : tasks) {
            try { serial.add(t.call()); } catch (Exception e) { serial.add("EXCEPTION " + e); }
        }

        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        List<String> parallel = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (Callable<String> t : tasks) fs.add(pool.submit(t));
            for (Future<String> f : fs) {
                try { parallel.add(f.get()); } catch (Exception e) { parallel.add("EXCEPTION " + e); }
            }
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignore) { }
        }

        int mismatches = 0;
        for (int i = 0; i < serial.size(); i++) {
            if (!serial.get(i).equals(parallel.get(i))) {
                mismatches++;
                if (mismatches <= 3) {
                    System.out.println("  MISMATCH " + labels.get(i));
                    System.out.println("    1 thread  : " + serial.get(i));
                    System.out.println("    " + threads + " threads: " + parallel.get(i));
                }
            }
        }
        System.out.println("  " + (serial.size() - mismatches) + "/" + serial.size()
                + " turns identical between 1 thread and " + threads + " threads"
                + "  (root sequence, final value, compute units, nodes, unique sequences, burns)");
        boolean ok = mismatches == 0 && !serial.isEmpty();
        System.out.println("Thread-count invariance (results independent of concurrency): "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Everything about one turn's outcome that a concurrency bug could disturb, as one string. */
    private static String fingerprint(AgentSpec spec, Scenario sc, int visitId) {
        long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, visitId);
        Agent a = spec.create(seed);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int k = 0; k < sc.state.getTribes().length; k++) ids.add(k);
        a.setPlayerIDs(sc.state.getActiveTribeID(), ids);
        GameState gs = sc.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { a.act(gs, ect); } catch (Throwable t) { return "THREW " + t.getClass().getSimpleName(); }
        List<core.data.dto.TurnData> log = a.getTurnDataLog();
        if (log.isEmpty()) return "NO_TURN_DATA";
        core.data.dto.TurnData t = log.get(0);
        List<String> root =
                a instanceof players.emcts.EMCTSAgent
                        ? ((players.emcts.EMCTSAgent) a).getInitialRootSequenceForV4()
                        : a instanceof players.ExperimentalEMCTS.ExperimentalEMCTSAgent
                            ? ((players.ExperimentalEMCTS.ExperimentalEMCTSAgent) a).getInitialRootSequenceForV4()
                            : java.util.Collections.emptyList();
        return "root=" + String.join("|", root).hashCode()
                + " final=" + ExpCsv.round(t.getFinalSequenceValue())
                + " units=" + ExpCsv.round(t.getComputeUnits())
                + " adv=" + t.getAdvances() + " cop=" + t.getCopies() + " ev=" + t.getEvals()
                + " nodes=" + t.getNodesEvaluated() + " uniq=" + t.getUniqueSequences()
                + " burns=" + a.getBridgeBurnDataLog().size();
    }

    /**
     * Sections 6 and 7. Runs one turn per representative config at {@link #BOUNDARY_CU} and checks:
     * initialization is measured but not charged; the search spend reaches the budget; all 20 burns
     * happen; crossover tournaments are paid out of the budget (so those agents evaluate fewer
     * distinct sequences than EMCTS); and the per-phase spend trajectory is monotone and consistent
     * with the per-turn total.
     */
    private static boolean verifyBudgetBoundaries(Scenario sc) {
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Object[][] configs = {
                {"EMCTS",                ExpAgents.emctsV6(BOUNDARY_CU)},
                {"CrossoverBB",          ExpAgents.crossoverBBV6(BOUNDARY_CU)},
                {"CrossoverExpansion@5", ExpAgents.crossoverExpansionV6(5, BOUNDARY_CU)},
                {"CrossoverCombo@5",     ExpAgents.crossoverComboV6(5, BOUNDARY_CU)},
        };

        boolean ok = true;
        Map<String, Integer> uniqueByAgent = new LinkedHashMap<>();
        System.out.printf(Locale.ROOT, "  %-22s %10s %12s %10s %7s %8s%n",
                "config", "initUnits", "searchUnits", "search/bud", "burns", "uniqSeq");

        for (Object[] c : configs) {
            String name = (String) c[0];
            AgentSpec spec = (AgentSpec) c[1];
            long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
            Agent a = spec.create(seed);
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int k = 0; k < sc.state.getTribes().length; k++) allIds.add(k);
            a.setPlayerIDs(sc.state.getActiveTribeID(), allIds);
            utils.ElapsedCpuTimer ect = new utils.ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
            GameState gs = sc.state.copy();
            gs.setRandomGenerator(new java.util.Random(seed));
            try { a.act(gs, ect); } catch (Throwable t) {
                System.out.println("  " + name + " threw " + t + " -> FAIL"); ok = false; continue;
            }
            if (a.getTurnDataLog().isEmpty()) {
                System.out.println("  " + name + " produced no TurnData -> FAIL"); ok = false; continue;
            }
            TurnData t = a.getTurnDataLog().get(0);
            List<BridgeBurnEventData> burns = a.getBridgeBurnDataLog();
            double ratio = t.getComputeUnits() / (double) BOUNDARY_CU;
            uniqueByAgent.put(name, t.getUniqueSequences());

            System.out.printf(Locale.ROOT, "  %-22s %10.0f %12.0f %10.2f %7d %8d%n",
                    name, t.getInitComputeUnits(), t.getComputeUnits(), ratio,
                    burns == null ? 0 : burns.size(), t.getUniqueSequences());

            // 6a. Initialization happened and was measured.
            if (!(t.getInitComputeUnits() > 0) || t.getInitFmCalls() <= 0) {
                System.out.println("    FAIL: initialization cost not measured (InitUnits/InitFmCalls must be > 0)");
                ok = false;
            }
            // 6b. Initialization was NOT charged: search still reaches the budget. Had it been charged,
            // search spend would fall short by exactly InitUnits.
            if (ratio < 0.90) {
                System.out.println("    FAIL: search spend is only " + String.format(Locale.ROOT, "%.2f", ratio)
                        + "x the budget — initialization looks like it is being charged to it");
                ok = false;
            }
            // 7a. All 20 burns happened, for every agent alike.
            int expectedBurns = ExpAgents.DEPTH - 1;
            if (burns == null || burns.size() != expectedBurns) {
                System.out.println("    FAIL: expected " + expectedBurns + " bridge burns, got "
                        + (burns == null ? 0 : burns.size()));
                ok = false;
            }
            // 7b. Tournaments are paid out of the budget, not on top of it. Overshoot is bounded by one
            // phase's worth of work plus one tournament, so a small excess is expected and fine.
            if (ratio > 1.60) {
                System.out.println("    FAIL: search spend is " + String.format(Locale.ROOT, "%.2f", ratio)
                        + "x the budget — bridge-burn cost appears to be escaping the budget");
                ok = false;
            }
            // 7c. Per-phase spend trajectory: monotone non-decreasing, ending at the per-turn total.
            if (burns != null && !burns.isEmpty()) {
                double prev = -1; boolean monotone = true;
                for (BridgeBurnEventData b : burns) {
                    if (b.getComputeUnitsElapsed() < prev - 1e-6) { monotone = false; break; }
                    prev = b.getComputeUnitsElapsed();
                }
                if (!monotone) {
                    System.out.println("    FAIL: per-phase ComputeUnitsElapsed is not monotone");
                    ok = false;
                }
                if (prev > t.getComputeUnits() + 1e-6) {
                    System.out.println("    FAIL: last per-phase spend (" + prev
                            + ") exceeds the per-turn total (" + t.getComputeUnits() + ")");
                    ok = false;
                }
            }
        }

        // 7d. The substantive signature that burns are charged: at an equal budget the
        // crossover-bridge-burning agents must buy fewer distinct sequences than EMCTS, because their
        // 20 mandatory tournaments come out of the same pot.
        Integer emcts = uniqueByAgent.get("EMCTS");
        for (String name : new String[]{"CrossoverBB", "CrossoverCombo@5"}) {
            Integer v = uniqueByAgent.get(name);
            if (emcts != null && v != null) {
                boolean fewer = v < emcts;
                System.out.println("  " + name + " uniqueSeq=" + v + " vs EMCTS=" + emcts
                        + " -> " + (fewer ? "OK (tournaments charged to the budget)"
                                          : "FAIL (tournaments look free)"));
                if (!fewer) ok = false;
            }
        }

        System.out.println("Budget boundaries (init OUT of budget, bridge burning IN): " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Build 3 scenarios from a single short EMCTS game (ticks 10/11/12) into a tiny portfolio. */
    private static List<Scenario> captureTinyPortfolio(String portfolioDir) throws Exception {
        File dir = new File(portfolioDir);
        File scenariosDir = new File(dir, "scenarios");
        ExpCsv.ensureDir(scenariosDir);
        File manifest = new File(dir, "portfolio.csv");

        Constants.VISUALS = false; Constants.LOG_STATS = false; Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L; Constants.REMOVE_FISHING = true;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        ArrayList<Agent> players = new ArrayList<>();
        EMCTSParams p = new EMCTSParams();
        p.stop_type = p.STOP_COMPUTE; p.num_computeUnits = 13_400; p.heuristic_method = p.SIMPLE_HEURISTIC; p.depth = 21;
        players.add(new EMCTSAgent(100L, p));
        players.add(new players.DoNothingAgent(100L));
        Game game = new Game();
        game.init(players, 555_555L, tribes, 666_666L, Types.GAME_MODE.SCORE);

        List<Scenario> result = new ArrayList<>();
        for (int s = 1; s <= 3; s++) {
            int tick = 9 + s;   // capture ticks 10/11/12
            GameState gs = game.captureAtTickAsTurnStart(tick);
            File bin = new File(scenariosDir, "scenario_" + BuildV6Portfolio.sid(s) + ".bin");
            FastGameStateIO.save(gs, bin);
            String row = s + ",single," + s + ",555555,666666," + tick + "," + gs.getActiveTribeID()
                    + ",0,0,0," + gs.getAllAvailableActions().size()
                    + ",0,0,0,0,0";   // action-composition columns: not needed by the smoke
            ExpCsv.append(manifest, BuildV5Portfolio.MANIFEST_HEADER, Collections.singletonList(row));
            result.add(new Scenario(s, "single", s, tick, gs));
        }
        return result;
    }

    /** EMCTS / CrossoverBB / CrossoverExpansion@5 (v6) produce the SAME greedy initial sequence. */
    private static boolean verifySharedInitialSequence(Scenario sc) {
        long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        AgentSpec[] specs = { ExpAgents.emctsV6(SMOKE_CU), ExpAgents.crossoverBBV6(SMOKE_CU),
                              ExpAgents.crossoverExpansionV6(5, SMOKE_CU) };
        String[] seqStrings = new String[specs.length];
        double[] initialValues = new double[specs.length];
        for (int i = 0; i < specs.length; i++) {
            Agent a = specs[i].create(seed);
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int k = 0; k < sc.state.getTribes().length; k++) allIds.add(k);
            a.setPlayerIDs(sc.state.getActiveTribeID(), allIds);
            utils.ElapsedCpuTimer ect = new utils.ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
            GameState gs = sc.state.copy();
            gs.setRandomGenerator(new java.util.Random(seed));
            try { a.act(gs, ect); } catch (Throwable ignored) {}
            seqStrings[i] = String.join("|", initialSeqOf(a));
            initialValues[i] = a.getTurnDataLog().isEmpty()
                    ? Double.NaN : a.getTurnDataLog().get(0).getInitialSequenceValue();
            System.out.println("  " + a.getClass().getSimpleName() + " initialValue=" + initialValues[i]
                    + " seqLen=" + initialSeqOf(a).size() + " seq=" + truncate(seqStrings[i], 90));
        }
        boolean valuesEqual = true;
        for (int i = 1; i < initialValues.length; i++)
            if (Math.abs(initialValues[i] - initialValues[0]) > 1e-9) { valuesEqual = false; break; }
        boolean seqsEqual = true;
        for (int i = 1; i < seqStrings.length; i++)
            if (!seqStrings[i].equals(seqStrings[0])) { seqsEqual = false; break; }
        System.out.println("  values-equal=" + valuesEqual + "  sequences-equal=" + seqsEqual);
        return valuesEqual && seqsEqual;
    }

    /** The greedy initial sequence must be byte-identical across all 12 configs AND all smoke budgets:
     *  it is built before the search loop, and every config runs identical init code, so neither the
     *  algorithm nor the budget can affect it. */
    private static boolean verifyInitialSequenceInvariantAcrossBudgetsAndConfigs(Scenario sc) {
        long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        String reference = null, referenceLabel = null;
        int total = 0, matches = 0;
        for (int cu : SMOKE_CU_LEVELS) {
            List<Object[]> configs = new ArrayList<>();
            configs.add(new Object[]{"EMCTS",              -1, ExpAgents.emctsV6(cu)});
            configs.add(new Object[]{"CrossoverBB",        -1, ExpAgents.crossoverBBV6(cu)});
            for (int r : IsolationPhaseRunnerV6.RATIOS)
                configs.add(new Object[]{"CrossoverExpansion", r, ExpAgents.crossoverExpansionV6(r, cu)});
            for (int r : IsolationPhaseRunnerV6.RATIOS)
                configs.add(new Object[]{"CrossoverCombo",     r, ExpAgents.crossoverComboV6(r, cu)});
            for (Object[] cfg : configs) {
                String agentName = (String) cfg[0];
                int ratio = (Integer) cfg[1];
                AgentSpec spec = (AgentSpec) cfg[2];
                String label = agentName + (ratio < 0 ? "" : "@r" + ratio) + "/" + cu + "cu";
                Agent a = spec.create(seed);
                ArrayList<Integer> allIds = new ArrayList<>();
                for (int k = 0; k < sc.state.getTribes().length; k++) allIds.add(k);
                a.setPlayerIDs(sc.state.getActiveTribeID(), allIds);
                utils.ElapsedCpuTimer ect = new utils.ElapsedCpuTimer();
                ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
                GameState gs = sc.state.copy();
                gs.setRandomGenerator(new java.util.Random(seed));
                try { a.act(gs, ect); } catch (Throwable t) {
                    System.out.println("    " + label + " threw " + t + " — skipping"); continue;
                }
                String packed = String.join("|", initialSeqOf(a));
                total++;
                if (reference == null) {
                    reference = packed; referenceLabel = label;
                    System.out.println("  reference [" + label + "] seq=" + truncate(packed, 100));
                    matches++;
                } else if (reference.equals(packed)) {
                    matches++;
                } else {
                    System.out.println("  MISMATCH [" + label + "] vs reference [" + referenceLabel + "]:");
                    System.out.println("    ref: " + truncate(reference, 120));
                    System.out.println("    got: " + truncate(packed, 120));
                }
            }
        }
        System.out.println("  matched " + matches + "/" + total + " (config × budget) initial sequences");
        return matches == total && total == 12 * SMOKE_CU_LEVELS.length;
    }

    private static List<String> initialSeqOf(Agent a) {
        if (a instanceof players.emcts.EMCTSAgent)
            return ((players.emcts.EMCTSAgent) a).getInitialRootSequenceForV4();
        if (a instanceof players.ExperimentalEMCTS.ExperimentalEMCTSAgent)
            return ((players.ExperimentalEMCTS.ExperimentalEMCTSAgent) a).getInitialRootSequenceForV4();
        return Collections.emptyList();
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }

    /** Run a 3-agent isolation pass at the given budget over the 3 scenarios × 2 visits. */
    private static void runTinyIsolation(int cu, List<Scenario> portfolio, File baseDir) {
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        String label = cu + "cu";
        runMiniConfig(ExpAgents.emctsV6(cu),                 -1, false, portfolio,
                new File(baseDir, "EMCTS/" + label + "/results.csv"), 2);
        runMiniConfig(ExpAgents.crossoverBBV6(cu),           -1, true,  portfolio,
                new File(baseDir, "CrossoverBB/" + label + "/results.csv"), 2);
        runMiniConfig(ExpAgents.crossoverExpansionV6(5, cu),  5, false, portfolio,
                new File(baseDir, "CrossoverExpansion/ratio_5/" + label + "/results.csv"), 2);
    }

    /** Mini stand-in for IsolationPhaseRunnerV6.runOneConfig — 2-visit, sequential. */
    private static void runMiniConfig(AgentSpec spec, int ratio, boolean burnCrossover,
                                      List<Scenario> portfolio, File csv, int visits) {
        ExpCsv.ensureDir(csv.getParentFile());
        String header = burnCrossover ? IsolationPhaseRunnerV6.TRAJECTORY_HEADER_BURN
                                      : IsolationPhaseRunnerV6.TRAJECTORY_HEADER_BASE;
        ExpCsv.append(csv, header, Collections.emptyList());
        for (Scenario sc : portfolio) {
            for (int v = 0; v < visits; v++) {
                long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, v);
                Agent a = spec.create(seed);
                ArrayList<Integer> allIds = new ArrayList<>();
                for (int k = 0; k < sc.state.getTribes().length; k++) allIds.add(k);
                a.setPlayerIDs(sc.state.getActiveTribeID(), allIds);
                utils.ElapsedCpuTimer ect = new utils.ElapsedCpuTimer();
                ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
                GameState gs = sc.state.copy();
                gs.setRandomGenerator(new java.util.Random(seed));
                try { a.act(gs, ect); } catch (Throwable ignored) {}
                if (a.getTurnDataLog().isEmpty() || a.getBridgeBurnDataLog().isEmpty()) continue;
                TurnData t = a.getTurnDataLog().get(0);
                Map<Integer, core.data.dto.CrossoverEventData> byPhase = new HashMap<>();
                for (core.data.dto.CrossoverEventData c : a.getCrossoverDataLog())
                    byPhase.put(c.getBridgeBurningPhaseNumber(), c);

                String spend = ExpCsv.round(t.getComputeUnits()) + "," + t.getAdvances() + ","
                        + t.getCopies() + "," + t.getEvals() + "," + t.getSearchFmCalls() + ","
                        + ExpCsv.round(t.getInitComputeUnits()) + "," + t.getInitAdvances() + ","
                        + t.getInitCopies() + "," + t.getInitEvals() + "," + t.getInitFmCalls() + ","
                        + t.getPlanMillis() + "," + t.getInitMillis();

                List<String> rows = new ArrayList<>();
                for (BridgeBurnEventData b : a.getBridgeBurnDataLog()) {
                    StringBuilder row = new StringBuilder(320);
                    row.append(sc.id).append(',').append(sc.source).append(',').append(sc.sourceGameId).append(',')
                       .append(sc.capturedTick).append(',').append(v).append(',')
                       .append(spec.name).append(',').append(ratio < 0 ? "NA" : String.valueOf(ratio)).append(',')
                       .append(b.getBridgeBurningPhaseNumber()).append(',')
                       .append(ExpCsv.round(b.getBestSequenceValue())).append(',')
                       .append(b.getFmCallsElapsed()).append(',')
                       .append(ExpCsv.round(b.getComputeUnitsElapsed())).append(',')
                       .append(b.getExpansionPhaseTimestamp()).append(',')
                       .append(t.getScoreAtTurnStart()).append(',').append(ExpCsv.round(t.getInitialSequenceValue())).append(',')
                       .append(ExpCsv.round(t.getFinalSequenceValue())).append(',').append(ExpCsv.round(t.getAvgNodeValue())).append(',')
                       .append(t.getFmCalls()).append(',').append(t.getNodesEvaluated()).append(',').append(t.getUniqueSequences()).append(',')
                       .append(spend);
                    if (burnCrossover) {
                        core.data.dto.CrossoverEventData c = byPhase.get(b.getBridgeBurningPhaseNumber());
                        if (c != null) {
                            row.append(',').append(ExpCsv.round(c.getRawImprovement()))
                               .append(',').append(ExpCsv.round(c.getPercentageImprovement()));
                        } else {
                            row.append(',').append(',');
                        }
                    }
                    rows.add(row.toString());
                }
                ExpCsv.append(csv, header, rows);

                core.data.dto.OperatorTelemetryData ot = a.getOperatorTelemetry();
                if (ot != null) {
                    File tele = new File(csv.getParentFile(), "turn_telemetry.csv");
                    String teleRow = sc.id + "," + sc.source + "," + v + ","
                            + spec.name + "," + (ratio < 0 ? "NA" : String.valueOf(ratio)) + ","
                            + ot.mutEvents + "," + ot.mutBestGains + "," + ExpCsv.round(ot.mutBestGainSum) + ","
                            + ot.mutChildDuplicates + "," + ot.mutRepairKept + "," + ot.mutRepairReplaced + ","
                            + ot.xoEvents + "," + ot.xoBestGains + "," + ExpCsv.round(ot.xoBestGainSum) + ","
                            + ExpCsv.round(ot.xoChildVsBestParentSum) + "," + ExpCsv.round(ot.xoChildVsMeanParentSum) + ","
                            + ot.xoChildBeatsBestParent + "," + ot.xoChildWorseThanBothParents + ","
                            + ExpCsv.round(ot.xoParentSimilaritySum) + "," + ot.xoChildDuplicates + ","
                            + ot.xoRepairKept + "," + ot.xoRepairReplaced;
                    ExpCsv.append(tele, IsolationPhaseRunnerV6.TELEMETRY_HEADER,
                            Collections.singletonList(teleRow));
                }
            }
        }
    }

    /**
     * Telemetry sanity per leaf: file exists with one row per (scenario, visit); EMCTS must report
     * ZERO crossover events (mutation-only baseline); the crossover-expansion agent must report
     * xoEvents > 0 at the smoke budget; every agent must report mutEvents > 0 and repair counters
     * that actually accumulated (kept + replaced > 0).
     */
    private static boolean checkTelemetry(File tele, boolean expectXo, int expectedRows) {
        if (!tele.exists()) { System.out.println("  MISSING: " + tele); return false; }
        try {
            List<String> lines = Files.readAllLines(tele.toPath());
            if (lines.size() != expectedRows + 1) {
                System.out.println("  " + tele.getPath() + " — expected " + (expectedRows + 1)
                        + " lines (header + " + expectedRows + " turns), got " + lines.size());
                return false;
            }
            List<String> cols = Arrays.asList(lines.get(0).split(","));
            int iMutEvents = cols.indexOf("MutEvents"), iMutKept = cols.indexOf("MutRepairKept"),
                iMutRepl = cols.indexOf("MutRepairReplaced"), iXoEvents = cols.indexOf("XoEvents"),
                iXoSim = cols.indexOf("XoParentSimilaritySum");
            if (iMutEvents < 0 || iXoEvents < 0 || iXoSim < 0) {
                System.out.println("  " + tele.getPath() + " — header missing expected columns");
                return false;
            }
            long mutEvents = 0, xoEvents = 0, repairTotal = 0;
            boolean simInRange = true;
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",");
                mutEvents += Long.parseLong(f[iMutEvents]);
                xoEvents += Long.parseLong(f[iXoEvents]);
                repairTotal += Long.parseLong(f[iMutKept]) + Long.parseLong(f[iMutRepl]);
                double sim = Double.parseDouble(f[iXoSim]);
                long xo = Long.parseLong(f[iXoEvents]);
                if (sim < 0 || sim > xo + 1e-9) simInRange = false;   // similarity ∈ [0,1] per event
            }
            boolean xoOk = expectXo ? (xoEvents > 0) : (xoEvents == 0);
            boolean ok = mutEvents > 0 && repairTotal > 0 && xoOk && simInRange;
            System.out.println(String.format(Locale.ROOT,
                    "  %s — turns=%d mutEvents=%d xoEvents=%d (expectXo=%s) repairCounts=%d simRange=%s -> %s",
                    tele.getPath(), lines.size() - 1, mutEvents, xoEvents, expectXo, repairTotal,
                    simInRange, ok ? "OK" : "FAIL"));
            return ok;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    private static boolean checkLeaf(File csv, boolean expectBurn) {
        if (!csv.exists()) { System.out.println("  MISSING: " + csv); return false; }
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            if (lines.size() < 2) { System.out.println("  EMPTY: " + csv); return false; }
            String header = lines.get(0);
            boolean hasBurnCols = header.contains("RawImprovement") && header.contains("PercentageImprovement");
            boolean hasSpendCols = header.contains("SearchUnits") && header.contains("InitUnits")
                    && header.contains("ComputeUnitsElapsed");
            boolean ok = (expectBurn == hasBurnCols) && hasSpendCols;
            System.out.println(String.format(Locale.ROOT,
                    "  %s — %d lines, burnCols=%s (expected=%s), spendCols=%s -> %s",
                    csv.getPath(), lines.size(), hasBurnCols, expectBurn, hasSpendCols, ok ? "OK" : "FAIL"));
            return ok;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }
}
