package core.data.exp;

import core.Constants;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.stage1.Stage1BiasedAgent;
import players.stage1.Stage1Params;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * STAGE 1 of the action-bias line (see {@code analysis/STAGE1_PLAN.md}): does within-turn learned
 * action bias IMPROVE the search, in the isolation-phase instrument (single planning turns from a
 * fixed scenario portfolio, paired seeds across configurations — the same design and the same
 * {@code visitSeed} convention as v5/v6)?
 *
 * <p><b>Configurations</b> (all Stage-0-informed; every experimental knob has its baseline-recovering
 * setting, and the Baseline config IS the recovered baseline, verified bit-identical to plain
 * {@code ExperimentalEMCTSAgent} before the run starts):
 * <ul>
 *   <li>epsilon dose-response: 0.75 / 0.50 / 0.25 / 0.10 (Mid keys, removal credit, continuous);</li>
 *   <li>granularity contrast at eps=0.25: Coarse vs Mid;</li>
 *   <li>removal-credit contrast at eps=0.25: off vs on;</li>
 *   <li>update-schedule contrast at eps=0.25: per-burn snapshot vs continuous;</li>
 *   <li>repair shortlisting: K=15 alone (eps=1), K=15 and K=5 combined with eps=0.25;</li>
 *   <li>Baseline additionally logs per-child sequence composition (Stage-2 groundwork:
 *       co-occurrence / interaction analysis on UNBIASED search data).</li>
 * </ul>
 *
 * <p><b>Schedule</b> (sized for ~12 h on 4 laptop workers; wall-clock per turn from the Stage-0
 * calibration ~45 microseconds per compute unit + ~1 s greedy init on mid-game scenarios):
 * <pre>
 *   160k cu : all 11 configs x 60 scenarios x 10 visits  (~6,600 turns, ~4.5 h)   [main grid]
 *   640k cu : Baseline, Bias_e25, Short15_e25 x 6 visits (~1,080 turns, ~3 h)     [confirmation]
 *    40k cu : Baseline, Bias_e25, Short15_e25 x 10 visits (~1,800 turns, ~0.5 h)  [budget trend]
 * </pre>
 * The portfolio (30 single + 30 dual scenarios — the deterministic first-N subset of the v5/v6
 * 100+100 build) is built on first run (~0.5-1 h, journaled + resumable).
 *
 * <p>Resumable per (config, budget) and per (scenario, visit) with the v6 telemetry-row-is-authority
 * compaction; Ctrl-C is a cooperative stop. Output:
 * {@code test_results/stage1/<Config>/<cu>cu/results.csv + turn_telemetry.csv [+ seqlog_*.csv.gz]}.
 *
 * <p>Usage:
 * <pre>
 *   java -Xmx12g -cp "out;lib/*" core.data.exp.Stage1Runner [threads] [outDir]   (default 4)
 *   java -cp "out;lib/*" core.data.exp.Stage1Runner smoke
 *   java -cp "out;lib/*" core.data.exp.Stage1Runner verify
 * </pre>
 */
public final class Stage1Runner {

    private Stage1Runner() {}

    public static final String BASE = "test_results/stage1";
    public static final int NUM_SINGLE = 30, NUM_DUAL = 30;
    public static final int VERIFY_BUDGET = 20_000;

    public static final String RESULTS_HEADER =
            "ScenarioId,ScenarioSource,ScenarioSourceGameId,ScenarioCapturedTick,VisitId,Config,"
            + "BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,ComputeUnitsElapsed,"
            + "ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences,SearchUnits,PlanMillis,InitMillis";
    private static final int RESULTS_FIELDS = RESULTS_HEADER.split(",").length;

    public static final String TELEMETRY_HEADER =
            "ScenarioId,ScenarioSource,VisitId,Config,InitialValue,FinalValue,"
            + "NodesEvaluated,UniqueSequences,PlanMillis,"
            + "MutEvents,MutBestGains,MutChildDuplicates,MutRepairKept,MutRepairReplaced,"
            + "BiasGreedyPicks,BiasUniformPicks,ShortlistCalls,ShortlistSkipped,StatUpdates";
    private static final int TELEMETRY_FIELDS = TELEMETRY_HEADER.split(",").length;

    // ------------------------------------------------------------------ configurations

    /** One agent configuration: a name and the Stage1Params it runs with. */
    static final class Config {
        final String name;
        final double eps;
        final boolean coarse, removal, perBurn;
        final int shortlistK;
        final boolean logSeq;
        Config(String name, double eps, boolean coarse, boolean removal, boolean perBurn,
               int shortlistK, boolean logSeq) {
            this.name = name; this.eps = eps; this.coarse = coarse; this.removal = removal;
            this.perBurn = perBurn; this.shortlistK = shortlistK; this.logSeq = logSeq;
        }
        Agent create(long seed, int cu) {
            Stage1Params p = new Stage1Params();
            p.stop_type = p.STOP_COMPUTE;
            p.num_computeUnits = cu;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;
            p.epsilon = eps;
            p.coarseKeys = coarse;
            p.removalCredit = removal;
            p.perBurnUpdates = perBurn;
            p.shortlistK = shortlistK;
            p.logSeqComposition = logSeq;
            return new Stage1BiasedAgent(seed, p);
        }
    }

    //                                        name           eps   coarse removal perBurn  K   logSeq
    static final Config BASELINE   = new Config("Baseline",     1.00, false, true, false,  0, true);
    static final Config BIAS_E75   = new Config("Bias_e75",     0.75, false, true, false,  0, false);
    static final Config BIAS_E50   = new Config("Bias_e50",     0.50, false, true, false,  0, false);
    static final Config BIAS_E25   = new Config("Bias_e25",     0.25, false, true, false,  0, false);
    static final Config BIAS_E10   = new Config("Bias_e10",     0.10, false, true, false,  0, false);
    static final Config COARSE_E25 = new Config("Coarse_e25",   0.25, true,  true, false,  0, false);
    static final Config NOREM_E25  = new Config("NoRemoval_e25",0.25, false, false,false,  0, false);
    static final Config PERBURN_E25= new Config("PerBurn_e25",  0.25, false, true, true,   0, false);
    static final Config SHORT15    = new Config("Shortlist15",  1.00, false, true, false, 15, false);
    static final Config SHORT15_E25= new Config("Short15_e25",  0.25, false, true, false, 15, false);
    static final Config SHORT5_E25 = new Config("Short5_e25",   0.25, false, true, false,  5, false);

    static final List<Config> ALL = List.of(BASELINE, BIAS_E75, BIAS_E50, BIAS_E25, BIAS_E10,
            COARSE_E25, NOREM_E25, PERBURN_E25, SHORT15, SHORT15_E25, SHORT5_E25);
    static final List<Config> CORE = List.of(BASELINE, BIAS_E25, SHORT15_E25);

    /** One budget level of the schedule. */
    static final class Level {
        final int cu; final int visits; final List<Config> configs;
        Level(int cu, int visits, List<Config> configs) {
            this.cu = cu; this.visits = visits; this.configs = configs;
        }
    }

    static List<Level> schedule() {
        return List.of(
                new Level(160_000, 10, ALL),
                new Level(640_000, 6, CORE),
                new Level(40_000, 10, CORE));
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("smoke")) { smoke(); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("verify")) {
            List<Scenario> p = smokePortfolio();
            System.exit(verify(p.get(0)) ? 0 : 1);
        }
        int threads = args.length > 0 ? Integer.parseInt(args[0].trim()) : 4;
        File outDir = new File(args.length > 1 ? args[1].trim() : BASE);
        run(threads, outDir, NUM_SINGLE, NUM_DUAL, schedule());
    }

    static void run(int threads, File outDir, int numSingle, int numDual, List<Level> schedule)
            throws Exception {
        RunGuard.install("STAGE 1 BIAS",
                "re-run the SAME command — finished leaves are skipped, partial leaves continue "
                + "per (scenario, visit)");
        ExpCsv.ensureDir(outDir);
        if (!RunGuard.acquireLock(outDir, "Stage1Runner")) RunGuard.abort(2);
        applyEngineConstants();

        long t0 = System.currentTimeMillis();
        banner(schedule, threads, outDir);

        // Portfolio: deterministic first-N subset of the v5/v6 build procedure; journaled/resumable.
        File pDir = new File(outDir, "_portfolio");
        if (!new File(pDir, "_portfolio_complete.txt").exists()) {
            System.out.println("[stage1] building portfolio (" + numSingle + " single + " + numDual
                    + " dual) — one-time cost, journaled; expect ~0.5-1 h on 4 workers");
            BuildV6Portfolio.build(pDir.getPath(), threads, numSingle, numDual);
            applyEngineConstants();   // the builder sets its own engine constants; restore ours
        }
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(pDir);
        System.out.println("[stage1] portfolio loaded: " + portfolio.size() + " scenarios");

        // Equivalence gate: Baseline params must reproduce plain EMCTS bit-for-bit.
        if (!verify(portfolio.get(0))) {
            System.err.println("[stage1] ABORT: Baseline is not bit-identical to plain EMCTS");
            RunGuard.abort(3);
        }

        File ckpt = new File(outDir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        for (Level level : schedule) {
            for (Config cfg : level.configs) {
                if (RunGuard.stopRequested()) { finish(t0); return; }
                String key = "config=" + cfg.name + ";cu=" + level.cu;
                if (done.contains(key)) {
                    System.out.println("[stage1] SKIP (done): " + key);
                    continue;
                }
                boolean complete = runLeaf(level, cfg, portfolio, outDir, threads);
                if (complete) {
                    ExpCsv.markCompleted(ckpt, key);
                    System.out.println("[stage1] DONE " + key + "   elapsed=" + elapsed(t0));
                } else {
                    System.out.println("[stage1] INTERRUPTED " + key + " — resume continues "
                            + "per (scenario, visit)");
                    finish(t0);
                    return;
                }
            }
        }
        finish(t0);
    }

    private static void finish(long t0) {
        System.out.println("[stage1] finished in " + elapsed(t0)
                + ". Analyse with: python analysis/analyze_stage1.py");
        RunGuard.finished();
    }

    private static void banner(List<Level> schedule, int threads, File outDir) {
        System.out.println("############################################################");
        System.out.println("# STAGE 1 — within-turn action bias, isolation phase   (output=" + outDir + ")");
        long totalTurns = 0; double estSec = 0;
        for (Level l : schedule) {
            long t = (long) l.configs.size() * 60 * l.visits;
            totalTurns += t;
            estSec += t * (l.cu * 4.5e-5 + 1.2);
        }
        System.out.println(String.format(Locale.ROOT,
                "# %d turns planned; rough estimate %.1f h on %d workers (+ portfolio build on first run)",
                totalTurns, estSec / 3600.0 / threads, threads));
        System.out.println("# ComputeMeter weights: " + core.game.ComputeMeter.weightsSummary());
        System.out.println("############################################################");
    }

    static void applyEngineConstants() {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.VERBOSE = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;   // agents gate on compute units, not the clock
    }

    // ------------------------------------------------------------------ one leaf

    /** Runs all outstanding (scenario, visit) turns of one (config, budget) leaf. */
    private static boolean runLeaf(Level level, Config cfg, List<Scenario> portfolio, File outDir,
                                   int threads) throws Exception {
        File dir = new File(outDir, cfg.name + "/" + level.cu + "cu");
        ExpCsv.ensureDir(dir);
        File csv = new File(dir, "results.csv");
        File tele = new File(dir, "turn_telemetry.csv");
        Set<String> doneTurns = compactPartialLeaf(csv, tele);

        int total = portfolio.size() * level.visits;
        System.out.println(String.format(Locale.ROOT,
                "[stage1] RUN %s @ %dcu: %d turns (%d already done), est %.0f min",
                cfg.name, level.cu, total - doneTurns.size(), doneTurns.size(),
                (total - doneTurns.size()) * (level.cu * 4.5e-5 + 1.2) / 60.0 / threads));

        AtomicInteger completed = new AtomicInteger(doneTurns.size());
        AtomicInteger skipped = new AtomicInteger();
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, 5_000L);
        governor.start();
        Object writeLock = new Object();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Scenario sc : portfolio) {
                for (int v = 0; v < level.visits; v++) {
                    if (doneTurns.contains(sc.id + ":" + v)) continue;
                    final Scenario scenario = sc;
                    final int visit = v;
                    futures.add(pool.submit(() -> {
                        if (RunGuard.stopRequested()) { skipped.incrementAndGet(); return; }
                        try {
                            governor.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            skipped.incrementAndGet();
                            return;
                        }
                        try {
                            TurnOut out = runOneTurn(cfg, level.cu, scenario, visit, dir);
                            synchronized (writeLock) {
                                // Telemetry LAST: it is the completion authority on resume.
                                ExpCsv.append(csv, RESULTS_HEADER, out.burnRows);
                                ExpCsv.append(tele, TELEMETRY_HEADER,
                                        Collections.singletonList(out.telemetryRow));
                            }
                        } catch (Throwable t) {
                            System.err.println("[stage1] task failed " + cfg.name + "@" + level.cu
                                    + " scenario=" + scenario.id + " visit=" + visit + ": " + t);
                        } finally {
                            governor.release();
                        }
                        int c = completed.incrementAndGet();
                        if (c % 100 == 0 || c == total) {
                            System.out.println(String.format(Locale.ROOT,
                                    "    %s@%dcu: %d/%d   [%s]", cfg.name, level.cu, c, total,
                                    governor.status()));
                        }
                    }));
                }
            }
            for (Future<?> f : futures) f.get();
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }
        return skipped.get() == 0 && !RunGuard.stopRequested();
    }

    // ------------------------------------------------------------------ one turn

    private static final class TurnOut {
        final List<String> burnRows;
        final String telemetryRow;
        TurnOut(List<String> burnRows, String telemetryRow) {
            this.burnRows = burnRows; this.telemetryRow = telemetryRow;
        }
    }

    /** One planning turn from a captured scenario — the v6 isolation pattern verbatim. */
    private static TurnOut runOneTurn(Config cfg, int cu, Scenario scenario, int visitId, File leafDir)
            throws Exception {
        long seed = IsolationPhaseRunnerV6.visitSeed(scenario.id, visitId);
        Agent agent = cfg.create(seed, cu);
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        int activeTribeId = gs.getActiveTribeID();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(activeTribeId, allIds);

        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        agent.act(gs, ect);

        List<TurnData> turns = agent.getTurnDataLog();
        if (turns.isEmpty()) throw new IllegalStateException("agent produced no TurnData");
        TurnData t = turns.get(0);

        String turnCols = t.getScoreAtTurnStart() + ","
                + ExpCsv.round(t.getInitialSequenceValue()) + ","
                + ExpCsv.round(t.getFinalSequenceValue()) + ","
                + ExpCsv.round(t.getAvgNodeValue()) + ","
                + t.getFmCalls() + "," + t.getNodesEvaluated() + "," + t.getUniqueSequences() + ","
                + ExpCsv.round(t.getComputeUnits()) + "," + t.getPlanMillis() + "," + t.getInitMillis();

        List<String> rows = new ArrayList<>();
        for (BridgeBurnEventData b : agent.getBridgeBurnDataLog()) {
            rows.add(scenario.id + "," + scenario.source + "," + scenario.sourceGameId + ","
                    + scenario.capturedTick + "," + visitId + "," + cfg.name + ","
                    + b.getBridgeBurningPhaseNumber() + ","
                    + ExpCsv.round(b.getBestSequenceValue()) + ","
                    + b.getFmCallsElapsed() + "," + ExpCsv.round(b.getComputeUnitsElapsed()) + ","
                    + turnCols);
        }

        core.data.dto.OperatorTelemetryData ot = agent.getOperatorTelemetry();
        Stage1BiasedAgent s1 = (agent instanceof Stage1BiasedAgent) ? (Stage1BiasedAgent) agent : null;
        String teleRow = scenario.id + "," + scenario.source + "," + visitId + "," + cfg.name + ","
                + ExpCsv.round(t.getInitialSequenceValue()) + ","
                + ExpCsv.round(t.getFinalSequenceValue()) + ","
                + t.getNodesEvaluated() + "," + t.getUniqueSequences() + "," + t.getPlanMillis() + ","
                + (ot == null ? "0,0,0,0,0" : ot.mutEvents + "," + ot.mutBestGains + ","
                    + ot.mutChildDuplicates + "," + ot.mutRepairKept + "," + ot.mutRepairReplaced) + ","
                + (s1 == null ? "0,0,0,0,0" : s1.getBiasGreedyPicks() + "," + s1.getBiasUniformPicks()
                    + "," + s1.getShortlistCalls() + "," + s1.getShortlistSkipped() + ","
                    + s1.getStatUpdates());

        // Stage-2 groundwork: per-child sequence composition (Baseline only).
        if (cfg.logSeq && s1 != null && !s1.getSeqLogRows().isEmpty()) {
            File fin = new File(leafDir, "seqlog_s" + scenario.id + "_v" + visitId + ".csv.gz");
            File tmp = new File(leafDir, fin.getName() + ".tmp");
            String prefix = scenario.id + "," + visitId + ",";
            try (Writer w = new OutputStreamWriter(
                    new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16),
                    StandardCharsets.UTF_8)) {
                w.write("ScenarioId,VisitId," + Stage1BiasedAgent.SEQLOG_FIELDS);
                w.write('\n');
                for (String r : s1.getSeqLogRows()) {
                    w.write(prefix);
                    w.write(r);
                    w.write('\n');
                }
            }
            if (fin.exists() && !fin.delete()) throw new IllegalStateException("cannot replace " + fin);
            if (!tmp.renameTo(fin)) throw new IllegalStateException("cannot rename " + tmp);
        }
        return new TurnOut(rows, teleRow);
    }

    // ------------------------------------------------------------------ verify

    /**
     * The equivalence gate: Stage1BiasedAgent with all-default knobs must plan a scenario turn
     * bit-identically to plain ExperimentalEMCTSAgent (same values, same forward-model calls, same
     * node and unique-sequence counts). Guards the copied mutate()/repair() against drift.
     */
    static boolean verify(Scenario scenario) {
        applyEngineConstants();
        String a = verifyRun(scenario, false);
        String b = verifyRun(scenario, true);
        boolean ok = a.equals(b);
        System.out.println("[stage1-verify] plain:    " + a);
        System.out.println("[stage1-verify] baseline: " + b);
        System.out.println(ok ? "[stage1-verify] PASS — Baseline is bit-identical to plain EMCTS"
                              : "[stage1-verify] FAIL — copied operators have drifted from the base class");
        return ok;
    }

    private static String verifyRun(Scenario scenario, boolean stage1) {
        long seed = IsolationPhaseRunnerV6.visitSeed(scenario.id, 0);
        Agent agent;
        if (stage1) {
            agent = BASELINE.create(seed, VERIFY_BUDGET);
        } else {
            players.ExperimentalEMCTS.ExperimentalEMCTSParams p =
                    new players.ExperimentalEMCTS.ExperimentalEMCTSParams();
            p.stop_type = p.STOP_COMPUTE;
            p.num_computeUnits = VERIFY_BUDGET;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;
            agent = new ExperimentalEMCTSAgent(seed, p);
        }
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(gs.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try {
            agent.act(gs, ect);
        } catch (Throwable t) {
            return "THREW: " + t;
        }
        TurnData t = agent.getTurnDataLog().get(0);
        return "init=" + ExpCsv.round(t.getInitialSequenceValue())
                + " final=" + ExpCsv.round(t.getFinalSequenceValue())
                + " fm=" + t.getFmCalls() + " nodes=" + t.getNodesEvaluated()
                + " unique=" + t.getUniqueSequences()
                + " units=" + ExpCsv.round(t.getComputeUnits());
    }

    // ------------------------------------------------------------------ smoke

    private static List<Scenario> smokePortfolio() throws Exception {
        File pDir = new File("test_results/stage1_smoke/_portfolio");
        if (!new File(pDir, "_portfolio_complete.txt").exists()) {
            System.out.println("[stage1-smoke] building tiny portfolio (4 single + 2 dual)...");
            BuildV6Portfolio.build(pDir.getPath(), 4, 4, 2);
        }
        applyEngineConstants();
        return IsolationPhaseRunner.loadPortfolio(pDir);
    }

    /** Tiny end-to-end: tiny portfolio, 8k budget, 1 visit, three configs. A few minutes. */
    private static void smoke() throws Exception {
        RunGuard.install("STAGE 1 SMOKE", "re-run to resume");
        List<Scenario> portfolio = smokePortfolio();
        if (!verify(portfolio.get(0))) { RunGuard.abort(3); }
        File outDir = new File("test_results/stage1_smoke");
        List<Level> sched = List.of(new Level(8_000, 1, CORE));
        for (Level l : sched)
            for (Config c : l.configs)
                runLeaf(l, c, portfolio, outDir, 2);
        System.out.println("[stage1-smoke] done. Analyse with: "
                + "python analysis/analyze_stage1.py test_results/stage1_smoke "
                + "--outdir analysis/stage1_smoke_results");
        RunGuard.finished();
    }

    // ------------------------------------------------------------------ resume compaction

    /** v6-style: a telemetry row proves its turn finished; results rows without one are dropped. */
    private static Set<String> compactPartialLeaf(File csv, File tele) throws Exception {
        Set<String> done = new LinkedHashSet<>();
        List<String> teleKept = new ArrayList<>();
        if (tele.exists()) {
            List<String> lines = Files.readAllLines(tele.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < TELEMETRY_FIELDS) continue;
                try {
                    String key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[2].trim());
                    if (done.add(key)) teleKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
        }
        if (tele.exists() || !teleKept.isEmpty()) writeAll(tele, TELEMETRY_HEADER, teleKept);
        List<String> resKept = new ArrayList<>();
        if (csv.exists()) {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < RESULTS_FIELDS) continue;
                try {
                    String key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[4].trim());
                    if (done.contains(key)) resKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
            writeAll(csv, RESULTS_HEADER, resKept);
        }
        return done;
    }

    private static void writeAll(File f, String header, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
