package core.data.exp;

import core.Constants;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;
import core.game.FastGameStateIO;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v4 Isolation Phase runner. For ONE thinking time, runs every agent configuration over the full
 * portfolio of 100 scenarios × {@link #VISITS_PER_SCENARIO} visits — a single search turn per game,
 * never a full game — and writes per-(agent, ratio, time) CSVs in the v4 folder layout:
 *
 * <pre>
 *   {baseDir}/EMCTS/{50,500,5000}ms/results.csv
 *   {baseDir}/CrossoverBB/{50,500,5000}ms/results.csv
 *   {baseDir}/CrossoverExpansion/ratio_{2,5,10,15,20}/{50,500,5000}ms/results.csv
 *   {baseDir}/CrossoverCombo/ratio_{2,5,10,15,20}/{50,500,5000}ms/results.csv
 * </pre>
 *
 * <p><b>Paired by (scenario, visit).</b> Every config faces the same 100 scenarios × 50 visits, and
 * within a (scenario, visit) every agent shares the SAME m_rnd seed — so {@code randomActions()}'s
 * random-init mode produces the SAME starting sequence across all 12 configs (greedy in-search repair
 * runs as usual). Paired comparisons across agents are therefore valid.
 *
 * <p><b>Race-condition guard.</b> {@code Constants.TURN_TIME_MILLIS} is a static field, so this class
 * runs ONE thinking-time pass at a time. Within a pass all configs and all (scenario, visit) tasks
 * are run in a single thread pool — they all share the same global thinking time, so the static is safe.
 *
 * <p><b>Resumable.</b> Per-(agent, ratio, time) checkpoint key in {@code _completed.txt}: once a leaf
 * CSV is fully written it is never re-run. Interrupting mid-config restarts that config from scratch.
 */
public class IsolationPhaseRunner {

    /** Visits per scenario (each scenario faced this many times by every agent). 100 × 50 = 5000 turns
     *  per agent per thinking time. */
    public static final int VISITS_PER_SCENARIO = 50;

    /** Standard CrossoverExpansion / Combo ratios swept at every thinking time. */
    public static final int[] RATIOS = {2, 5, 10, 15, 20};

    /** Trajectory CSV schema for EMCTS / CrossoverExpansion (no crossover-improvement columns). */
    public static final String TRAJECTORY_HEADER_BASE =
            "ScenarioId,ScenarioSource,ScenarioSourceGameId,ScenarioCapturedTick,VisitId,AgentName,Ratio,"
            + "BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,"
            + "ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences";

    /** Trajectory CSV schema for CrossoverBB / CrossoverCombo (adds the per-burn crossover delta). */
    public static final String TRAJECTORY_HEADER_BURN =
            TRAJECTORY_HEADER_BASE + ",RawImprovement,PercentageImprovement";

    private IsolationPhaseRunner() {}

    /**
     * Runs all 12 agent configs at the given thinking time over the full portfolio, parallelised across
     * {@code threads} workers. Writes into {@code baseDir/<agent>/[ratio_<r>/]<ms>ms/results.csv}.
     */
    public static void runForTime(int thinkingMs, int threads, List<Scenario> portfolio, File baseDir,
                                  File ckpt, Set<String> done) throws Exception {
        // Refresh the checkpoint set in case earlier ms passes (or earlier processes) wrote new keys.
        // The orchestrator passes the same `done` across ms passes; this keeps each pass current.
        done = ExpCsv.readCompleted(ckpt);
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = thinkingMs;     // static; safe because we run ms levels sequentially

        // 1 EMCTS + 1 CrossoverBB + 5 CrossoverExpansion + 5 CrossoverCombo = 12 configs.
        List<AgentConfig> configs = new ArrayList<>();
        configs.add(new AgentConfig("EMCTS", -1, ExpAgents.emctsV4(), false));
        configs.add(new AgentConfig("CrossoverBB", -1, ExpAgents.crossoverBBV4(), true));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverExpansion", r, ExpAgents.crossoverExpansionV4(r), false));
        for (int r : RATIOS) configs.add(new AgentConfig("CrossoverCombo",     r, ExpAgents.crossoverComboV4(r),   true));

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < configs.size(); i++) {
            AgentConfig cfg = configs.get(i);
            String key = configKey(cfg, thinkingMs);
            if (done.contains(key)) {
                System.out.println("[v4] (" + (i+1) + "/" + configs.size() + ") SKIP (done): " + key);
                continue;
            }
            File csv = leafCsv(baseDir, cfg, thinkingMs);
            ExpCsv.ensureDir(csv.getParentFile());
            // Wipe partial output from any prior interrupted run of THIS config so the resume re-runs
            // it cleanly. (Whole-config checkpoint: per-row resume is not supported.)
            if (csv.exists()) csv.delete();

            System.out.println(String.format(Locale.ROOT,
                    "[v4] (%d/%d) RUN  %s (%d scenarios × %d visits = %d turns)",
                    i+1, configs.size(), key, portfolio.size(), VISITS_PER_SCENARIO,
                    portfolio.size() * VISITS_PER_SCENARIO));
            runOneConfig(cfg, thinkingMs, portfolio, csv, threads);
            ExpCsv.markCompleted(ckpt, key);
            System.out.println(String.format(Locale.ROOT,
                    "[v4] (%d/%d) DONE %s   elapsed=%s",
                    i+1, configs.size(), key, elapsed(t0)));
        }
    }

    /**
     * Runs all 100×50 (scenario, visit) tasks for ONE config, parallelised across {@code threads}
     * workers. Each task: deep-copy the scenario state, construct a fresh agent with seed = f(scenario,
     * visit), call act() ONCE to plan the turn, collect TurnData + BridgeBurnEventData (+
     * CrossoverEventData for burn-crossover agents), convert to CSV rows, write under a synchronized
     * append.
     */
    private static void runOneConfig(AgentConfig cfg, int thinkingMs, List<Scenario> portfolio, File csv,
                                     int threads) throws Exception {
        String header = cfg.burnCrossover ? TRAJECTORY_HEADER_BURN : TRAJECTORY_HEADER_BASE;
        ExpCsv.append(csv, header, Collections.emptyList());   // write header up-front

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
                        List<String> rows = runOneTurn(cfg, scenario, visitId);
                        synchronized (writeLock) {
                            ExpCsv.append(csv, header, rows);
                        }
                    } catch (Throwable t) {
                        System.err.println("[v4] task failed scenario=" + scenario.id + " visit=" + visitId
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

    /**
     * Runs ONE search turn from the captured scenario. Returns CSV rows (one per bridge-burn phase) using
     * the schema chosen by {@link AgentConfig#burnCrossover}. The agent is freshly constructed with the
     * SHARED v4 visit seed {@link #visitSeed} so the random-initial-sequence path produces an identical
     * starting sequence across every config.
     */
    private static List<String> runOneTurn(AgentConfig cfg, Scenario scenario, int visitId) {
        long seed = visitSeed(scenario.id, visitId);
        Agent agent = cfg.spec.create(seed);
        // scenario.state is shared across all parallel workers; MUST deep-copy before mutating. The
        // deterministic-cascade in GameState.copy() then makes every sub-copy made inside planTurn
        // reproducible from gs.rnd alone, so we pin gs.rnd to the visit seed: every agent at the same
        // (scenario, visit) sees the SAME rnd state → identical Examine/LevelUp(EXPLORER) outcomes
        // → identical random initial sequence across all 12 configs (the paired guarantee).
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        int activeTribeId = gs.getActiveTribeID();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(activeTribeId, allIds);

        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        // Single act() call: triggers planTurn(), records the full bridge-burn trajectory in the agent's
        // internal logs. Subsequent rolls of the planned sequence would just replay it — not needed here.
        try { agent.act(gs, ect); } catch (Throwable t) { /* logged below as 0-row output */ }

        List<TurnData> turns = agent.getTurnDataLog();
        List<BridgeBurnEventData> burns = agent.getBridgeBurnDataLog();
        List<CrossoverEventData> crossovers = cfg.burnCrossover ? agent.getCrossoverDataLog() : Collections.emptyList();
        if (turns.isEmpty() || burns.isEmpty()) {
            // Defensive: should not happen for the EMCTS family at any positive thinking time.
            return Collections.emptyList();
        }
        TurnData t = turns.get(0);   // single-turn run: exactly one TurnData

        // Map crossover events by burn phase for the burn-crossover agents.
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
                // Write empty cells (-> NaN in pandas) when NO crossover event fired at this burn
                // phase, so the analyser can distinguish "no event" from "event ran and produced no
                // gain". (Previously: defaulted to 0.0 — collapsed both into the 'unchanged' bucket.)
                if (c != null) {
                    row.append(',').append(ExpCsv.round(c.getRawImprovement()))
                       .append(',').append(ExpCsv.round(c.getPercentageImprovement()));
                } else {
                    row.append(',').append(',');
                }
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /**
     * Shared per-(scenario, visit) seed. Every agent constructed for this pair gets the SAME seed, so the
     * uniformly-random initial sequence (when {@code params.randomInitialSequence == true}) is identical
     * across all 12 configs — the paired-comparison core of the isolation-phase methodology.
     */
    public static long visitSeed(int scenarioId, int visitId) {
        return ((long) scenarioId) * 1_000_000L + visitId;
    }

    /** Resolve the leaf CSV path for a config at a given thinking time. */
    private static File leafCsv(File baseDir, AgentConfig cfg, int thinkingMs) {
        File dir;
        if (cfg.ratio < 0) {
            dir = new File(baseDir, cfg.name + "/" + thinkingMs + "ms");
        } else {
            dir = new File(baseDir, cfg.name + "/ratio_" + cfg.ratio + "/" + thinkingMs + "ms");
        }
        return new File(dir, "results.csv");
    }

    /** Stable checkpoint key per (agent, ratio, ms). */
    private static String configKey(AgentConfig cfg, int ms) {
        if (cfg.ratio < 0) return "agent=" + cfg.name + ";ms=" + ms;
        return "agent=" + cfg.name + ";ratio=" + cfg.ratio + ";ms=" + ms;
    }

    private static String configShort(AgentConfig cfg) {
        return cfg.ratio < 0 ? cfg.name : (cfg.name + "@r" + cfg.ratio);
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Loads the full portfolio from a {@code _portfolio} directory into memory.
     *  Manifest header: see {@link BuildV4Portfolio#MANIFEST_HEADER}. */
    public static List<Scenario> loadPortfolio(File portfolioDir) throws Exception {
        File manifest = new File(portfolioDir, "portfolio.csv");
        File scenariosDir = new File(portfolioDir, "scenarios");
        if (!manifest.exists()) throw new java.io.FileNotFoundException(
                "Portfolio manifest not found: " + manifest.getPath() + " — run BuildV4Portfolio first.");
        List<Scenario> portfolio = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifest.toPath());
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (f.length < 11) continue;
            int id = Integer.parseInt(f[0].trim());
            String source = f[1].trim();
            int sourceGameId = Integer.parseInt(f[2].trim());
            int capturedTick = Integer.parseInt(f[5].trim());
            File bin = new File(scenariosDir, "scenario_" + BuildV4Portfolio.sid(id) + ".bin");
            GameState gs = FastGameStateIO.load(bin);
            portfolio.add(new Scenario(id, source, sourceGameId, capturedTick, gs));
        }
        return portfolio;
    }

    /** In-memory scenario: holds the captured {@link GameState} plus the provenance fields needed in
     *  each output row. */
    public static final class Scenario {
        public final int id;
        public final String source;
        public final int sourceGameId;
        public final int capturedTick;
        public final GameState state;
        public Scenario(int id, String source, int sourceGameId, int capturedTick, GameState state) {
            this.id = id; this.source = source; this.sourceGameId = sourceGameId;
            this.capturedTick = capturedTick; this.state = state;
        }
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
