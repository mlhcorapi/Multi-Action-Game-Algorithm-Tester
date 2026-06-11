package core.data.exp;

import core.Constants;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;
import core.game.GameState;
import core.data.exp.IsolationPhaseRunner.Scenario;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v5 Isolation Phase runner. For ONE compute budget, runs every agent configuration over the full
 * 200-scenario portfolio × {@link #VISITS_PER_SCENARIO} visits — a single search turn per game — and
 * writes per-(agent, ratio, budget) CSVs in the v5 folder layout:
 *
 * <pre>
 *   {baseDir}/EMCTS/{cu}cu/results.csv
 *   {baseDir}/CrossoverBB/{cu}cu/results.csv
 *   {baseDir}/CrossoverExpansion/ratio_{2,5,10,20,50}/{cu}cu/results.csv
 *   {baseDir}/CrossoverCombo/ratio_{2,5,10,20,50}/{cu}cu/results.csv
 * </pre>
 *
 * <p>Difference from v4: the per-turn budget is the deterministic COMPUTE-UNIT budget (STOP_COMPUTE
 * via the thread-local {@link core.game.ComputeMeter}) rather than wall-clock (STOP_TIME). Compute
 * units count advance + copy + eval, so the budget is reproducible and independent of CPU contention /
 * multithreading — the v4 wall-clock budget made compute-per-turn vary with machine load.
 *
 * <p><b>Paired by (scenario, visit).</b> Every config faces the same 200 scenarios × 100 visits, and
 * within a (scenario, visit) every agent shares the SAME visit seed, so the random initial sequence is
 * identical across all 12 configs. Paired comparisons across agents are valid.
 *
 * <p><b>Thread-safety.</b> {@code Constants.TURN_TIME_MILLIS} is set once to a large constant (the
 * agents gate on compute, not time) and never varied, and {@link core.game.ComputeMeter} is
 * thread-local, so all configs and (scenario, visit) tasks can run in a shared pool safely.
 *
 * <p><b>Resumable.</b> Per-(agent, ratio, budget) checkpoint key in {@code _completed.txt}.
 */
public class IsolationPhaseRunnerV5 {

    /** Visits per scenario. 200 × 100 = 20000 turns per agent per compute budget. */
    public static final int VISITS_PER_SCENARIO = 100;

    /** CrossoverExpansion / Combo ratios swept at every compute budget. */
    public static final int[] RATIOS = {2, 5, 10, 20, 50};

    /** Trajectory CSV schema for EMCTS / CrossoverExpansion (no crossover-improvement columns). */
    public static final String TRAJECTORY_HEADER_BASE =
            "ScenarioId,ScenarioSource,ScenarioSourceGameId,ScenarioCapturedTick,VisitId,AgentName,Ratio,"
            + "BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,"
            + "ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences";

    /** Trajectory CSV schema for CrossoverBB / CrossoverCombo (adds the per-burn crossover delta). */
    public static final String TRAJECTORY_HEADER_BURN =
            TRAJECTORY_HEADER_BASE + ",RawImprovement,PercentageImprovement";

    /** Operator-telemetry CSV schema — ONE row per (scenario, visit) turn, written alongside each
     *  leaf's results.csv as {@code turn_telemetry.csv}. Collected to discriminate the v5 hypotheses
     *  for the single-vs-dual gap (see {@link core.data.dto.OperatorTelemetryData}): H1 blend
     *  coherence (XoChildVs*), H2 repair disruption (Mut/XoRepair*), H3 elite-pool collapse
     *  (XoParentSimilaritySum, *ChildDuplicates), H4 operator credit (Mut/XoBestGain*). */
    public static final String TELEMETRY_HEADER =
            "ScenarioId,ScenarioSource,VisitId,AgentName,Ratio,"
            + "MutEvents,MutBestGains,MutBestGainSum,MutChildDuplicates,MutRepairKept,MutRepairReplaced,"
            + "XoEvents,XoBestGains,XoBestGainSum,XoChildVsBestParentSum,XoChildVsMeanParentSum,"
            + "XoChildBeatsBestParent,XoChildWorseThanBothParents,XoParentSimilaritySum,XoChildDuplicates,"
            + "XoRepairKept,XoRepairReplaced";

    private IsolationPhaseRunnerV5() {}

    /**
     * Runs all 12 agent configs at the given compute budget over the full portfolio, parallelised across
     * {@code threads} workers. Writes into {@code baseDir/<agent>/[ratio_<r>/]<cu>cu/results.csv}.
     */
    public static void runForCompute(int computeUnits, int threads, List<Scenario> portfolio, File baseDir,
                                     File ckpt, Set<String> done) throws Exception {
        done = ExpCsv.readCompleted(ckpt);
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        // STOP_COMPUTE agents gate on compute units, not the clock; set a large time so the per-turn
        // ElapsedCpuTimer never cuts a turn short. Constant across budgets ⇒ no static-field race.
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        // 1 EMCTS + 1 CrossoverBB + 5 CrossoverExpansion + 5 CrossoverCombo = 12 configs.
        List<AgentConfig> configs = new ArrayList<>();
        configs.add(new AgentConfig("EMCTS", -1, ExpAgents.emctsV5(computeUnits), false));
        configs.add(new AgentConfig("CrossoverBB", -1, ExpAgents.crossoverBBV5(computeUnits), true));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverExpansion", r, ExpAgents.crossoverExpansionV5(r, computeUnits), false));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverCombo",     r, ExpAgents.crossoverComboV5(r, computeUnits),   true));

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < configs.size(); i++) {
            AgentConfig cfg = configs.get(i);
            String key = configKey(cfg, computeUnits);
            if (done.contains(key)) {
                System.out.println("[v5] (" + (i+1) + "/" + configs.size() + ") SKIP (done): " + key);
                continue;
            }
            File csv = leafCsv(baseDir, cfg, computeUnits);
            ExpCsv.ensureDir(csv.getParentFile());
            if (csv.exists()) csv.delete();
            File tele = new File(csv.getParentFile(), "turn_telemetry.csv");
            if (tele.exists()) tele.delete();

            System.out.println(String.format(Locale.ROOT,
                    "[v5] (%d/%d) RUN  %s (%d scenarios × %d visits = %d turns)",
                    i+1, configs.size(), key, portfolio.size(), VISITS_PER_SCENARIO,
                    portfolio.size() * VISITS_PER_SCENARIO));
            runOneConfig(cfg, portfolio, csv, threads);
            ExpCsv.markCompleted(ckpt, key);
            System.out.println(String.format(Locale.ROOT,
                    "[v5] (%d/%d) DONE %s   elapsed=%s",
                    i+1, configs.size(), key, elapsed(t0)));
        }
    }

    /** Runs all portfolio×visit tasks for ONE config, parallelised across {@code threads} workers. */
    private static void runOneConfig(AgentConfig cfg, List<Scenario> portfolio, File csv,
                                     int threads) throws Exception {
        String header = cfg.burnCrossover ? TRAJECTORY_HEADER_BURN : TRAJECTORY_HEADER_BASE;
        ExpCsv.append(csv, header, Collections.emptyList());   // write header up-front
        File tele = new File(csv.getParentFile(), "turn_telemetry.csv");
        ExpCsv.append(tele, TELEMETRY_HEADER, Collections.emptyList());

        int total = portfolio.size() * VISITS_PER_SCENARIO;
        AtomicInteger completed = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Object writeLock = new Object();
        List<Future<?>> futures = new ArrayList<>();

        for (Scenario sc : portfolio) {
            for (int v = 0; v < VISITS_PER_SCENARIO; v++) {
                final int visitId = v;
                final Scenario scenario = sc;
                futures.add(pool.submit(() -> {
                    try {
                        TurnResult res = runOneTurn(cfg, scenario, visitId);
                        synchronized (writeLock) {
                            ExpCsv.append(csv, header, res.burnRows);
                            if (res.telemetryRow != null) {
                                ExpCsv.append(tele, TELEMETRY_HEADER,
                                        Collections.singletonList(res.telemetryRow));
                            }
                        }
                    } catch (Throwable t) {
                        System.err.println("[v5] task failed scenario=" + scenario.id + " visit=" + visitId
                                + " agent=" + cfg.name + ": " + t);
                        t.printStackTrace();
                    }
                    int c = completed.incrementAndGet();
                    if (c % 200 == 0 || c == total) {
                        System.out.println(String.format(Locale.ROOT, "    %s: %d/%d", configShort(cfg), c, total));
                    }
                }));
            }
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
    }

    /** Output of one (scenario, visit) turn: per-burn trajectory rows + one operator-telemetry row. */
    private static final class TurnResult {
        final List<String> burnRows;
        final String telemetryRow;
        TurnResult(List<String> burnRows, String telemetryRow) {
            this.burnRows = burnRows; this.telemetryRow = telemetryRow;
        }
    }

    /** Runs ONE search turn from the captured scenario. Returns the per-burn CSV rows plus the
     *  one-per-turn operator-telemetry row. */
    private static TurnResult runOneTurn(AgentConfig cfg, Scenario scenario, int visitId) {
        long seed = visitSeed(scenario.id, visitId);
        Agent agent = cfg.spec.create(seed);
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        int activeTribeId = gs.getActiveTribeID();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(activeTribeId, allIds);

        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { agent.act(gs, ect); } catch (Throwable t) { /* logged below as 0-row output */ }

        List<TurnData> turns = agent.getTurnDataLog();
        List<BridgeBurnEventData> burns = agent.getBridgeBurnDataLog();
        List<CrossoverEventData> crossovers = cfg.burnCrossover ? agent.getCrossoverDataLog() : Collections.emptyList();
        if (turns.isEmpty() || burns.isEmpty()) {
            return new TurnResult(Collections.emptyList(), null);
        }
        TurnData t = turns.get(0);   // single-turn run: exactly one TurnData

        Map<Integer, CrossoverEventData> crossByPhase = new HashMap<>();
        for (CrossoverEventData c : crossovers) crossByPhase.put(c.getBridgeBurningPhaseNumber(), c);

        String ratioLabel = (cfg.ratio < 0) ? "NA" : String.valueOf(cfg.ratio);
        List<String> rows = new ArrayList<>(burns.size());
        for (BridgeBurnEventData b : burns) {
            StringBuilder row = new StringBuilder(256);
            row.append(scenario.id).append(',')
               .append(scenario.source).append(',')
               .append(scenario.sourceGameId).append(',')
               .append(scenario.capturedTick).append(',')
               .append(visitId).append(',')
               .append(cfg.name).append(',')
               .append(ratioLabel).append(',')
               .append(b.getBridgeBurningPhaseNumber()).append(',')
               .append(ExpCsv.round(b.getBestSequenceValue())).append(',')
               .append(b.getFmCallsElapsed()).append(',')
               .append(b.getExpansionPhaseTimestamp()).append(',')
               .append(t.getScoreAtTurnStart()).append(',')
               .append(ExpCsv.round(t.getInitialSequenceValue())).append(',')
               .append(ExpCsv.round(t.getFinalSequenceValue())).append(',')
               .append(ExpCsv.round(t.getAvgNodeValue())).append(',')
               .append(t.getFmCalls()).append(',')
               .append(t.getNodesEvaluated()).append(',')
               .append(t.getUniqueSequences());
            if (cfg.burnCrossover) {
                CrossoverEventData c = crossByPhase.get(b.getBridgeBurningPhaseNumber());
                if (c != null) {
                    row.append(',').append(ExpCsv.round(c.getRawImprovement()))
                       .append(',').append(ExpCsv.round(c.getPercentageImprovement()));
                } else {
                    row.append(',').append(',');
                }
            }
            rows.add(row.toString());
        }

        // One operator-telemetry row per turn (null for agent types that don't collect it).
        String teleRow = null;
        core.data.dto.OperatorTelemetryData ot = agent.getOperatorTelemetry();
        if (ot != null) {
            teleRow = scenario.id + "," + scenario.source + "," + visitId + ","
                    + cfg.name + "," + ratioLabel + ","
                    + ot.mutEvents + "," + ot.mutBestGains + "," + ExpCsv.round(ot.mutBestGainSum) + ","
                    + ot.mutChildDuplicates + "," + ot.mutRepairKept + "," + ot.mutRepairReplaced + ","
                    + ot.xoEvents + "," + ot.xoBestGains + "," + ExpCsv.round(ot.xoBestGainSum) + ","
                    + ExpCsv.round(ot.xoChildVsBestParentSum) + "," + ExpCsv.round(ot.xoChildVsMeanParentSum) + ","
                    + ot.xoChildBeatsBestParent + "," + ot.xoChildWorseThanBothParents + ","
                    + ExpCsv.round(ot.xoParentSimilaritySum) + "," + ot.xoChildDuplicates + ","
                    + ot.xoRepairKept + "," + ot.xoRepairReplaced;
        }
        return new TurnResult(rows, teleRow);
    }

    /** Shared per-(scenario, visit) seed — identical across all 12 configs (paired-comparison core). */
    public static long visitSeed(int scenarioId, int visitId) {
        return ((long) scenarioId) * 1_000_000L + visitId;
    }

    /** Compute-budget label used in the folder layout. */
    private static String budgetLabel(int computeUnits) { return computeUnits + "cu"; }

    /** Resolve the leaf CSV path for a config at a given compute budget. */
    private static File leafCsv(File baseDir, AgentConfig cfg, int computeUnits) {
        File dir;
        if (cfg.ratio < 0) {
            dir = new File(baseDir, cfg.name + "/" + budgetLabel(computeUnits));
        } else {
            dir = new File(baseDir, cfg.name + "/ratio_" + cfg.ratio + "/" + budgetLabel(computeUnits));
        }
        return new File(dir, "results.csv");
    }

    /** Stable checkpoint key per (agent, ratio, budget). */
    private static String configKey(AgentConfig cfg, int computeUnits) {
        if (cfg.ratio < 0) return "agent=" + cfg.name + ";cu=" + computeUnits;
        return "agent=" + cfg.name + ";ratio=" + cfg.ratio + ";cu=" + computeUnits;
    }

    private static String configShort(AgentConfig cfg) {
        return cfg.ratio < 0 ? cfg.name : (cfg.name + "@r" + cfg.ratio);
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Pairing of agent display name + ratio + factory, with a flag for the burn-crossover CSV schema. */
    private static final class AgentConfig {
        final String name;
        final int ratio;        // -1 means "no ratio in path (EMCTS / CrossoverBB)"
        final AgentSpec spec;
        final boolean burnCrossover;
        AgentConfig(String name, int ratio, AgentSpec spec, boolean burnCrossover) {
            this.name = name; this.ratio = ratio; this.spec = spec; this.burnCrossover = burnCrossover;
        }
    }
}
