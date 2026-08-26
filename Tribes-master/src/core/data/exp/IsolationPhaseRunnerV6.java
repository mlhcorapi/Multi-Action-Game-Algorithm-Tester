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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v6 Isolation Phase runner. For ONE budget level, runs every agent configuration over the full
 * 100-scenario portfolio × {@link #VISITS_PER_SCENARIO} visits — a single search turn per game — and
 * writes per-(agent, ratio, budget) CSVs in the v6 folder layout:
 *
 * <pre>
 *   {baseDir}/EMCTS/{cu}cu/results.csv
 *   {baseDir}/CrossoverBB/{cu}cu/results.csv
 *   {baseDir}/CrossoverExpansion/ratio_{2,5,10,20,50}/{cu}cu/results.csv
 *   {baseDir}/CrossoverCombo/ratio_{2,5,10,20,50}/{cu}cu/results.csv
 * </pre>
 *
 * <p><b>Differences from v5</b> (see {@code analysis/EXPERIMENT_V6_PLAN.md}):
 * <ol>
 *   <li><b>Greedy initial sequence.</b> Every config starts from the greedy 1-ply root that is standard
 *       EMCTS's default, rather than v5's deliberately-weak uniformly-random root. All configs run
 *       byte-identical initialization code before any operator-specific work, so a shared (scenario,
 *       visit) seed still produces an identical root across all 12 — the paired guarantee holds, and
 *       {@code RunExperimentV6.verifyGreedyInit} asserts it before the run starts.</li>
 *   <li><b>The budget bounds SEARCH only.</b> The greedy initialization is measured and then excluded
 *       (the agents reset the ComputeMeter after building the root). Its cost scales with branching
 *       factor, which differs systematically by scenario source, so charging it would bias the primary
 *       contrast. Bridge burning — including the crossover tournament — stays INSIDE the budget.</li>
 *   <li><b>50 visits per scenario</b> (v5: 100). Greedy init removes the starting-point variance
 *       component, so the paired residual is smaller.</li>
 *   <li><b>Realized spend is logged per turn</b> (units, advances, copies, evals, initialization cost,
 *       wall-clock), plus per-phase spend on every bridge-burn row. Budget adherence becomes a reported
 *       result instead of an assumption.</li>
 * </ol>
 *
 * <p><b>Thread-safety.</b> {@code Constants.TURN_TIME_MILLIS} is set once to a large constant (the
 * agents gate on compute, not time) and never varied, and {@link core.game.ComputeMeter} is
 * thread-local, so all configs and (scenario, visit) tasks can run in a shared pool safely.
 *
 * <p><b>Concurrency is bounded twice.</b> The pool size comes from {@link ThreadCalibrationV6} (itself
 * capped at physical cores by {@link ServerProfile}), and a {@link LoadGovernor} sits inside it and
 * lowers the number of concurrently-running turns whenever the JVM shows memory pressure. Neither can
 * change a result — the budget is a deterministic compute-unit count and each turn's output depends
 * only on its {@code (scenario, visit)} seed — so both are free to act automatically.
 *
 * <p><b>Resumable.</b> Per-(agent, ratio, budget) checkpoint key in {@code _completed.txt}, plus
 * per-(scenario, visit) recovery inside a partially-finished leaf. A SIGTERM/SIGINT stops the
 * submission of new turns and lets the running ones finish and write (see {@link RunGuard}), so an
 * orderly stop loses nothing at all and a hard kill loses at most one turn per worker.
 */
public class IsolationPhaseRunnerV6 {

    /** Visits per scenario. 100 scenarios × 50 = 5000 turns per agent per budget level. */
    public static final int VISITS_PER_SCENARIO = 50;

    /** CrossoverExpansion / Combo ratios swept at every budget level. */
    public static final int[] RATIOS = {2, 5, 10, 20, 50};

    /** Number of fields in {@link #TELEMETRY_HEADER}; the resume logic uses it as a completeness guard. */
    private static final int TELEMETRY_FIELDS = 22;

    /** How often the {@link LoadGovernor} samples memory pressure during a leaf. */
    private static final long GOVERNOR_INTERVAL_MS = 3_000L;

    /** Trajectory CSV schema for EMCTS / CrossoverExpansion (no crossover-improvement columns). */
    public static final String TRAJECTORY_HEADER_BASE =
            "ScenarioId,ScenarioSource,ScenarioSourceGameId,ScenarioCapturedTick,VisitId,AgentName,Ratio,"
            + "BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,ComputeUnitsElapsed,Timestamp,"
            + "ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences,"
            // v6 realized-spend block (constant across a turn's burn rows; one turn = one value)
            + "SearchUnits,SearchAdvances,SearchCopies,SearchEvals,SearchFmCalls,"
            + "InitUnits,InitAdvances,InitCopies,InitEvals,InitFmCalls,PlanMillis,InitMillis";

    /** Trajectory CSV schema for CrossoverBB / CrossoverCombo (adds the per-burn crossover delta). */
    public static final String TRAJECTORY_HEADER_BURN =
            TRAJECTORY_HEADER_BASE + ",RawImprovement,PercentageImprovement";

    /** Operator-telemetry CSV schema — ONE row per (scenario, visit) turn, written alongside each
     *  leaf's results.csv as {@code turn_telemetry.csv}. Unchanged from v5 by design: v6 re-asks the
     *  same hypothesis battery (H1 blend coherence, H2 repair disruption, H3 elite-pool collapse,
     *  H4 operator credit) on the corrected budget and the greedy start. See
     *  {@link core.data.dto.OperatorTelemetryData} and {@code analysis/V5_HYPOTHESES.md}. */
    public static final String TELEMETRY_HEADER =
            "ScenarioId,ScenarioSource,VisitId,AgentName,Ratio,"
            + "MutEvents,MutBestGains,MutBestGainSum,MutChildDuplicates,MutRepairKept,MutRepairReplaced,"
            + "XoEvents,XoBestGains,XoBestGainSum,XoChildVsBestParentSum,XoChildVsMeanParentSum,"
            + "XoChildBeatsBestParent,XoChildWorseThanBothParents,XoParentSimilaritySum,XoChildDuplicates,"
            + "XoRepairKept,XoRepairReplaced";

    private IsolationPhaseRunnerV6() {}

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
        // Agents gate on compute units, not the clock; a large constant stops the per-turn
        // ElapsedCpuTimer cutting a turn short. Constant across budgets => no static-field race.
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        List<AgentConfig> configs = configsFor(computeUnits);

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < configs.size(); i++) {
            if (RunGuard.stopRequested()) {
                System.out.println("[v6] stop requested — leaving the remaining configs at cu="
                        + computeUnits + " for the next resume");
                return;
            }
            AgentConfig cfg = configs.get(i);
            String key = configKey(cfg, computeUnits);
            if (done.contains(key)) {
                System.out.println("[v6] (" + (i+1) + "/" + configs.size() + ") SKIP (done): " + key);
                continue;
            }
            File csv = leafCsv(baseDir, cfg, computeUnits);
            ExpCsv.ensureDir(csv.getParentFile());
            File tele = new File(csv.getParentFile(), "turn_telemetry.csv");
            String header = cfg.burnCrossover ? TRAJECTORY_HEADER_BURN : TRAJECTORY_HEADER_BASE;
            int total = portfolio.size() * VISITS_PER_SCENARIO;

            Set<String> doneTurns;
            if (csv.exists() || tele.exists()) {
                doneTurns = compactPartialLeaf(csv, tele, header);
                System.out.println(String.format(Locale.ROOT,
                        "[v6] (%d/%d) RESUME %s — %d/%d turns already done", i+1, configs.size(), key,
                        doneTurns.size(), total));
            } else {
                doneTurns = Collections.emptySet();
                System.out.println(String.format(Locale.ROOT,
                        "[v6] (%d/%d) RUN  %s (%d scenarios × %d visits = %d turns)",
                        i+1, configs.size(), key, portfolio.size(), VISITS_PER_SCENARIO, total));
            }
            boolean complete = runOneConfig(cfg, portfolio, csv, threads, doneTurns);
            if (!complete) {
                System.out.println(String.format(Locale.ROOT,
                        "[v6] (%d/%d) INTERRUPTED %s — NOT marked complete; resume continues per "
                        + "(scenario, visit)", i+1, configs.size(), key));
                return;
            }
            ExpCsv.markCompleted(ckpt, key);
            System.out.println(String.format(Locale.ROOT,
                    "[v6] (%d/%d) DONE %s   elapsed=%s",
                    i+1, configs.size(), key, elapsed(t0)));
        }
    }

    /** The 12 v6 configurations: 1 EMCTS + 1 CrossoverBB + 5 CrossoverExpansion + 5 CrossoverCombo. */
    private static List<AgentConfig> configsFor(int computeUnits) {
        List<AgentConfig> configs = new ArrayList<>();
        configs.add(new AgentConfig("EMCTS", -1, ExpAgents.emctsV6(computeUnits), false));
        configs.add(new AgentConfig("CrossoverBB", -1, ExpAgents.crossoverBBV6(computeUnits), true));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverExpansion", r, ExpAgents.crossoverExpansionV6(r, computeUnits), false));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverCombo",     r, ExpAgents.crossoverComboV6(r, computeUnits),   true));
        return configs;
    }

    /**
     * Runs all NOT-yet-done portfolio×visit tasks for ONE config, parallelised across {@code threads}
     * workers and gated by a {@link LoadGovernor}. Headers are written lazily by the first
     * {@link ExpCsv#append}; on resume the files already exist (compacted) so appends never duplicate
     * the header.
     *
     * @return true if every outstanding turn ran; false if a stop was requested part-way, in which
     *         case the caller must NOT mark the config complete
     */
    private static boolean runOneConfig(AgentConfig cfg, List<Scenario> portfolio, File csv,
                                        int threads, Set<String> doneTurns) throws Exception {
        String header = cfg.burnCrossover ? TRAJECTORY_HEADER_BURN : TRAJECTORY_HEADER_BASE;
        File tele = new File(csv.getParentFile(), "turn_telemetry.csv");

        int total = portfolio.size() * VISITS_PER_SCENARIO;
        AtomicInteger completed = new AtomicInteger(doneTurns.size());
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger empty = new AtomicInteger(0);
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, GOVERNOR_INTERVAL_MS);
        governor.start();
        Object writeLock = new Object();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Scenario sc : portfolio) {
                for (int v = 0; v < VISITS_PER_SCENARIO; v++) {
                    if (doneTurns.contains(sc.id + ":" + v)) continue;
                    final int visitId = v;
                    final Scenario scenario = sc;
                    futures.add(pool.submit(() -> {
                        // A stop turns every queued-but-unstarted task into a no-op, so the pool drains
                        // in seconds instead of grinding through thousands of pending turns.
                        if (RunGuard.stopRequested()) { skipped.incrementAndGet(); return; }
                        try {
                            governor.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            skipped.incrementAndGet();
                            return;
                        }
                        try {
                            TurnResult res = runOneTurn(cfg, scenario, visitId);
                            if (res.telemetryRow == null && res.burnRows.isEmpty()) empty.incrementAndGet();
                            synchronized (writeLock) {
                                // Results first, telemetry last: the resume compaction treats the
                                // telemetry row as the proof a turn finished, so it must never be the
                                // earlier of the two writes.
                                ExpCsv.append(csv, header, res.burnRows);
                                if (res.telemetryRow != null) {
                                    ExpCsv.append(tele, TELEMETRY_HEADER,
                                            Collections.singletonList(res.telemetryRow));
                                }
                            }
                        } catch (Throwable t) {
                            System.err.println("[v6] task failed scenario=" + scenario.id + " visit=" + visitId
                                    + " agent=" + cfg.name + ": " + t);
                            t.printStackTrace();
                        } finally {
                            governor.release();
                        }
                        int c = completed.incrementAndGet();
                        if (c % 200 == 0 || c == total) {
                            System.out.println(String.format(Locale.ROOT, "    %s: %d/%d   [%s]",
                                    configShort(cfg), c, total, governor.status()));
                        }
                    }));
                }
            }
            for (Future<?> f : futures) f.get();
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }

        if (empty.get() > 0) {
            System.out.println("[v6] WARN " + configShort(cfg) + ": " + empty.get()
                    + " turn(s) produced no rows (agent threw or logged nothing); they are re-attempted "
                    + "on the next resume of this leaf");
        }
        if (governor.throttleEvents() > 0) {
            System.out.println("[v6] governor summary for " + configShort(cfg) + ": "
                    + governor.throttleEvents() + " throttle event(s), concurrency fell to "
                    + governor.lowestAllowed() + "/" + threads);
        }
        return skipped.get() == 0 && !RunGuard.stopRequested();
    }

    /**
     * Resume support: rewrite a leaf's results.csv + turn_telemetry.csv keeping only fully-completed
     * (scenario, visit) turns, and return that done-set. The telemetry row is written LAST in each
     * turn's synchronized block, so a telemetry row is the authority that a turn finished; results
     * rows not backed by a telemetry row are dropped so the recompute does not duplicate them.
     */
    private static Set<String> compactPartialLeaf(File csv, File tele, String resultsHeader) throws Exception {
        Set<String> done = new LinkedHashSet<>();
        // 1) telemetry is the completion authority (ScenarioId@0, VisitId@2).
        List<String> teleKept = new ArrayList<>();
        if (tele.exists()) {
            List<String> lines = Files.readAllLines(tele.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < TELEMETRY_FIELDS) continue;
                try {
                    String tup = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[2].trim());
                    if (done.add(tup)) teleKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
        }
        writeLeaf(tele, TELEMETRY_HEADER, teleKept);
        // 2) results: keep only rows whose (scenario, visit) finished (ScenarioId@0, VisitId@4).
        int baseFields = resultsHeader.split(",").length;
        List<String> resKept = new ArrayList<>();
        if (csv.exists()) {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < baseFields) continue;
                try {
                    String tup = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[4].trim());
                    if (done.contains(tup)) resKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
        }
        writeLeaf(csv, resultsHeader, resKept);
        return done;
    }

    /** Overwrite a CSV with {@code header} + {@code rows} (used by resume compaction). */
    private static void writeLeaf(File f, String header, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
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

        // Per-turn spend block, identical on every burn row of this turn.
        String spend = ExpCsv.round(t.getComputeUnits()) + "," + t.getAdvances() + ","
                + t.getCopies() + "," + t.getEvals() + "," + t.getSearchFmCalls() + ","
                + ExpCsv.round(t.getInitComputeUnits()) + "," + t.getInitAdvances() + ","
                + t.getInitCopies() + "," + t.getInitEvals() + "," + t.getInitFmCalls() + ","
                + t.getPlanMillis() + "," + t.getInitMillis();

        String ratioLabel = (cfg.ratio < 0) ? "NA" : String.valueOf(cfg.ratio);
        List<String> rows = new ArrayList<>(burns.size());
        for (BridgeBurnEventData b : burns) {
            StringBuilder row = new StringBuilder(320);
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
               .append(ExpCsv.round(b.getComputeUnitsElapsed())).append(',')
               .append(b.getExpansionPhaseTimestamp()).append(',')
               .append(t.getScoreAtTurnStart()).append(',')
               .append(ExpCsv.round(t.getInitialSequenceValue())).append(',')
               .append(ExpCsv.round(t.getFinalSequenceValue())).append(',')
               .append(ExpCsv.round(t.getAvgNodeValue())).append(',')
               .append(t.getFmCalls()).append(',')
               .append(t.getNodesEvaluated()).append(',')
               .append(t.getUniqueSequences()).append(',')
               .append(spend);
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
    /**
     * Times running EMCTS over the given (scenario × visits) subset at {@code computeUnits} with
     * {@code threads} workers, WITHOUT persisting anything — used by the thread-count auto-tune.
     * Returns wall-clock milliseconds (lower = faster for that thread count).
     */
    public static long benchmarkThroughput(int computeUnits, int threads, List<Scenario> subset, int visits) {
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        AgentConfig cfg = new AgentConfig("EMCTS", -1, ExpAgents.emctsV6(computeUnits), false);
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(Math.max(1, threads)));
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (Scenario sc : subset) {
            for (int v = 0; v < visits; v++) {
                final int vv = v; final Scenario s = sc;
                fs.add(pool.submit(() -> {
                    if (RunGuard.stopRequested()) return;
                    try { runOneTurn(cfg, s, vv); } catch (Throwable ignore) {}
                }));
            }
        }
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignore) {} }
        RunGuard.shutdownAndWait(pool, 1);
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /** The root sequence snapshot hook, which lives on the concrete EMCTS-family classes. */
    private static List<String> initialRootSequenceOf(Agent a) {
        if (a instanceof players.emcts.EMCTSAgent)
            return ((players.emcts.EMCTSAgent) a).getInitialRootSequenceForV4();
        if (a instanceof players.ExperimentalEMCTS.ExperimentalEMCTSAgent)
            return ((players.ExperimentalEMCTS.ExperimentalEMCTSAgent) a).getInitialRootSequenceForV4();
        return Collections.emptyList();
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
