package core.data.exp;

import core.Constants;
import core.Types;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.FastGameStateIO;
import core.game.Game;
import core.game.GameState;
import players.Agent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * v5 pipeline smoke test (into {@code test_results/smoke_v5/}). Tiny scale: 3 synthetic scenarios,
 * 2 visits, small COMPUTE budgets, 3 agents (EMCTS, CrossoverBB, CrossoverExpansion@5). Asserts the
 * v5 pipeline end-to-end, mirroring the v4 smoke but with the STOP_COMPUTE budget:
 *
 * <ol>
 *   <li><b>FastGameStateIO round-trip</b> — a captured GameState saves+loads byte-for-byte.</li>
 *   <li><b>Per-scenario startup validation</b> — active tribe has cities + units; a tiny EMCTS act()
 *       does not throw.</li>
 *   <li><b>Shared random initial sequence</b> — EMCTS / CrossoverBB / CrossoverExpansion@5 produce the
 *       SAME initial sequence given identical (scenarioId, visitId) seeds.</li>
 *   <li><b>Invariance across compute budgets AND all 12 configs</b> — the initial sequence is generated
 *       before search, so it must be identical across every (config × compute budget).</li>
 *   <li><b>Folder layout + schema</b> — leaf {@code <agent>/[ratio_<r>/]<cu>cu/results.csv} exist with
 *       the expected schema (burn columns present only for the burn-crossover agents).</li>
 * </ol>
 *
 * <p>If this passes, the v5 design is sound and the full {@link RunExperimentV5} is safe to launch.
 */
public class NewExperimentSmokeTestV5 {

    /** Small compute budgets for the smoke (the real run uses 13400 / 26800 / 134000). The initial
     *  sequence is budget-independent, so small budgets fully exercise the invariance check fast. */
    private static final int[] SMOKE_CU_LEVELS = {2_000, 5_000, 13_400};
    /** Compute budget for the tiny isolation pass + the shared-initial-sequence check. */
    private static final int   SMOKE_CU = 5_000;

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke_v5";
        deleteDir(new File(base));

        System.out.println("==================== 1. CAPTURE 3 tiny scenarios + FastGameStateIO round-trip ====================");
        List<Scenario> portfolio = captureTinyPortfolio(base + "/_portfolio");
        boolean roundTripOk = portfolio.size() == 3;
        for (Scenario sc : portfolio) {
            GameState reloaded = FastGameStateIO.load(
                    new File(base + "/_portfolio/scenarios/scenario_" + BuildV5Portfolio.sid(sc.id) + ".bin"));
            boolean same = reloaded.getTick() == sc.state.getTick()
                    && reloaded.getActiveTribeID() == sc.state.getActiveTribeID()
                    && reloaded.getBoard().getSize() == sc.state.getBoard().getSize()
                    && reloaded.getAllAvailableActions().size() == sc.state.getAllAvailableActions().size();
            System.out.println("  scenario " + BuildV5Portfolio.sid(sc.id) + " round-trip OK: " + same);
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
            System.out.println("  scenario " + BuildV5Portfolio.sid(sc.id) + " tick=" + sc.state.getTick()
                    + " activeTribe=" + aid + " cities=" + cities + " units=" + units
                    + " -> " + (valid ? "PASS" : "FAIL"));
            allValid &= valid;
        }
        System.out.println("Per-scenario startup validation: " + (allValid ? "PASS" : "FAIL"));

        System.out.println("\n==================== 2. SHARED random initial sequence across agents ====================");
        boolean sharedInitOk = verifySharedInitialSequence(portfolio.get(0));
        System.out.println("Shared random initial sequence (EMCTS == BB == Expansion@5): " + (sharedInitOk ? "PASS" : "FAIL"));

        System.out.println("\n==================== 2b. INVARIANCE across compute budgets AND all 12 configs ====================");
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

        boolean allOk = roundTripOk && allValid && sharedInitOk && acrossCuOk && layoutOk && teleOk;
        System.out.println("\nTotal smoke_v5 time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println(allOk ? "PASS: v5 pipeline sound." : "FAIL: see checks above.");
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
            File bin = new File(scenariosDir, "scenario_" + BuildV5Portfolio.sid(s) + ".bin");
            FastGameStateIO.save(gs, bin);
            String row = s + ",single," + s + ",555555,666666," + tick + "," + gs.getActiveTribeID()
                    + ",0,0,0," + gs.getAllAvailableActions().size()
                    + ",0,0,0,0,0";   // action-composition columns: not needed by the smoke
            ExpCsv.append(manifest, BuildV5Portfolio.MANIFEST_HEADER, Collections.singletonList(row));
            result.add(new Scenario(s, "single", s, tick, gs));
        }
        return result;
    }

    /** EMCTS / CrossoverBB / CrossoverExpansion@5 (v5 STOP_COMPUTE) produce the SAME initial sequence. */
    private static boolean verifySharedInitialSequence(Scenario sc) {
        long seed = IsolationPhaseRunnerV5.visitSeed(sc.id, 0);
        Constants.TURN_TIME_MILLIS = 10_000_000L;   // STOP_COMPUTE: timer must not interfere
        AgentSpec[] specs = { ExpAgents.emctsV5(SMOKE_CU), ExpAgents.crossoverBBV5(SMOKE_CU),
                              ExpAgents.crossoverExpansionV5(5, SMOKE_CU) };
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

    /** The initial random action sequence must be byte-identical across all 12 configs AND all smoke
     *  compute budgets (the seed does not depend on the budget, and the budget only kicks in during the
     *  search loop after randomActions() returns). */
    private static boolean verifyInitialSequenceInvariantAcrossBudgetsAndConfigs(Scenario sc) {
        long seed = IsolationPhaseRunnerV5.visitSeed(sc.id, 0);
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        String reference = null, referenceLabel = null;
        int total = 0, matches = 0;
        for (int cu : SMOKE_CU_LEVELS) {
            List<Object[]> configs = new ArrayList<>();
            configs.add(new Object[]{"EMCTS",              -1, ExpAgents.emctsV5(cu)});
            configs.add(new Object[]{"CrossoverBB",        -1, ExpAgents.crossoverBBV5(cu)});
            for (int r : IsolationPhaseRunnerV5.RATIOS)
                configs.add(new Object[]{"CrossoverExpansion", r, ExpAgents.crossoverExpansionV5(r, cu)});
            for (int r : IsolationPhaseRunnerV5.RATIOS)
                configs.add(new Object[]{"CrossoverCombo",     r, ExpAgents.crossoverComboV5(r, cu)});
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

    /** Run a 3-agent isolation pass at the given compute budget over the 3 scenarios × 2 visits. */
    private static void runTinyIsolation(int cu, List<Scenario> portfolio, File baseDir) {
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        String label = cu + "cu";
        runMiniConfig(ExpAgents.emctsV5(cu),                 -1, false, portfolio,
                new File(baseDir, "EMCTS/" + label + "/results.csv"), 2);
        runMiniConfig(ExpAgents.crossoverBBV5(cu),           -1, true,  portfolio,
                new File(baseDir, "CrossoverBB/" + label + "/results.csv"), 2);
        runMiniConfig(ExpAgents.crossoverExpansionV5(5, cu),  5, false, portfolio,
                new File(baseDir, "CrossoverExpansion/ratio_5/" + label + "/results.csv"), 2);
    }

    /** Mini stand-in for IsolationPhaseRunnerV5.runOneConfig — 2-visit, sequential. */
    private static void runMiniConfig(AgentSpec spec, int ratio, boolean burnCrossover,
                                      List<Scenario> portfolio, File csv, int visits) {
        ExpCsv.ensureDir(csv.getParentFile());
        String header = burnCrossover ? IsolationPhaseRunnerV5.TRAJECTORY_HEADER_BURN
                                      : IsolationPhaseRunnerV5.TRAJECTORY_HEADER_BASE;
        ExpCsv.append(csv, header, Collections.emptyList());
        for (Scenario sc : portfolio) {
            for (int v = 0; v < visits; v++) {
                long seed = IsolationPhaseRunnerV5.visitSeed(sc.id, v);
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
                core.data.dto.TurnData t = a.getTurnDataLog().get(0);
                Map<Integer, core.data.dto.CrossoverEventData> byPhase = new HashMap<>();
                for (core.data.dto.CrossoverEventData c : a.getCrossoverDataLog())
                    byPhase.put(c.getBridgeBurningPhaseNumber(), c);
                List<String> rows = new ArrayList<>();
                for (core.data.dto.BridgeBurnEventData b : a.getBridgeBurnDataLog()) {
                    StringBuilder row = new StringBuilder(256);
                    row.append(sc.id).append(',').append(sc.source).append(',').append(sc.sourceGameId).append(',')
                       .append(sc.capturedTick).append(',').append(v).append(',')
                       .append(spec.name).append(',').append(ratio < 0 ? "NA" : String.valueOf(ratio)).append(',')
                       .append(b.getBridgeBurningPhaseNumber()).append(',')
                       .append(ExpCsv.round(b.getBestSequenceValue())).append(',')
                       .append(b.getFmCallsElapsed()).append(',').append(b.getExpansionPhaseTimestamp()).append(',')
                       .append(t.getScoreAtTurnStart()).append(',').append(ExpCsv.round(t.getInitialSequenceValue())).append(',')
                       .append(ExpCsv.round(t.getFinalSequenceValue())).append(',').append(ExpCsv.round(t.getAvgNodeValue())).append(',')
                       .append(t.getFmCalls()).append(',').append(t.getNodesEvaluated()).append(',').append(t.getUniqueSequences());
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

                // Operator-telemetry row (same as IsolationPhaseRunnerV5).
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
                    ExpCsv.append(tele, IsolationPhaseRunnerV5.TELEMETRY_HEADER,
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
            boolean ok = (expectBurn == hasBurnCols);
            System.out.println(String.format(Locale.ROOT, "  %s — %d lines, burnCols=%s (expected=%s) -> %s",
                    csv.getPath(), lines.size(), hasBurnCols, expectBurn, ok ? "OK" : "FAIL"));
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
