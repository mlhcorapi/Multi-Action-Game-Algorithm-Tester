package core.data.exp;

import core.Types;
import core.data.dto.TurnData;
import core.game.Game;
import players.Agent;
import players.DoNothingAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.stage0.Stage0MutationLogAgent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * STAGE 0 of the action-bias line of work (see {@code analysis/STAGE0_SIGNAL_PLAN.md}):
 * instrumentation-only data collection. Plays full Tribes games with a
 * {@link Stage0MutationLogAgent} — behaviourally identical to plain EMCTS — at several
 * compute budgets ("thinking times"), and logs EVERY mutation event of every planning turn.
 * The offline analysis ({@code analysis/analyze_stage0.py}) then asks whether per-action value
 * estimates are stable enough within a turn to be worth exploiting, at which action
 * granularity, and from which budget upward. No biased sampling happens here.
 *
 * <p><b>Design notes.</b>
 * <ul>
 *   <li><b>Budgets are deterministic compute units</b> ({@code STOP_COMPUTE}, thread-local
 *       {@link core.game.ComputeMeter}), so multithreading cannot change any turn's search —
 *       the laptop constraint that motivated compute budgets in the first place. Per-budget
 *       wall-clock is REPORTED (PlanMillis in the turns CSVs) rather than assumed.</li>
 *   <li><b>Maps</b> come from {@link FullGameMapSetV6#build()} — the fixed v6 tournament set —
 *       so the states the search visits are the same distribution the thesis's other phases
 *       use. Game {@code g} plays map {@code g % 20}; all budgets share the same
 *       (map, gameSeed, agentSeed) grid so budget contrasts are position-matched.</li>
 *   <li><b>Opponent</b> is a plain {@code ExperimentalEMCTSAgent} at the same budget by default
 *       (realistic two-sided games; costs half the run), or {@code passive}
 *       ({@link DoNothingAgent}) for cheap smoke runs.</li>
 *   <li><b>Resumable per (budget, game).</b> The event log is written to a temp file and
 *       renamed into place last, so its existence is the completion authority; finished games
 *       are skipped on re-run. Ctrl-C is a cooperative stop via {@link RunGuard}.</li>
 * </ul>
 *
 * <p>Output, under {@code test_results/stage0/}:
 * <pre>
 *   mutlog_{cu}cu_g{k}.csv.gz   one row per mutation event (the analysis input)
 *   turns_{cu}cu_g{k}.csv       one row per planning turn (budget adherence + wall-clock report)
 *   games_summary.csv           one row per game (appended; analysis de-dups keep-last)
 *   _meta.txt                   ComputeMeter weights + run configuration (provenance)
 * </pre>
 *
 * <p>Usage:
 * <pre>
 *   java -cp "out;lib/*" core.data.exp.Stage0SignalRunner [threads] [cuCsv] [gamesPerBudget]
 *                                                         [opponent] [turnLimit] [outDir]
 *   java -cp "out;lib/*" core.data.exp.Stage0SignalRunner verify [cu] [turnLimit]
 *   java -cp "out;lib/*" core.data.exp.Stage0SignalRunner smoke
 * </pre>
 * Defaults: threads=4, cuCsv=10000,40000,160000,640000, gamesPerBudget=4, opponent=emcts,
 * turnLimit=50, outDir=test_results/stage0.
 */
public final class Stage0SignalRunner {

    private Stage0SignalRunner() {}

    public static final String DEFAULT_OUT = "test_results/stage0";
    public static final int[] DEFAULT_CU = {10_000, 40_000, 160_000, 640_000};
    public static final int DEFAULT_GAMES_PER_BUDGET = 4;
    public static final int DEFAULT_TURN_LIMIT = 50;   // = the v6 full-games tournament cap

    /** Seed bases, disjoint from every other job in this repo (v6 full games use 6-7M). */
    private static final long GAME_SEED_BASE  = 8_100_000L;
    private static final long AGENT_SEED_BASE = 8_200_000L;
    private static final long OPP_SEED_OFFSET = 8_887L;

    public static final String MUTLOG_HEADER =
            "Budget,GameId,AgentSeed," + Stage0MutationLogAgent.EVENT_FIELDS;

    public static final String TURNS_HEADER =
            "Budget,GameId,Tick,ScoreAtTurnStart,InitialValue,FinalValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences,"
            + "SearchUnits,SearchAdvances,SearchCopies,SearchEvals,SearchFmCalls,"
            + "InitUnits,InitAdvances,InitCopies,InitEvals,InitFmCalls,PlanMillis,InitMillis";

    public static final String SUMMARY_HEADER =
            "Budget,GameId,MapIndex,LevelSeed,AgentSeed,Opponent,TrackedWon,TrackedScore,OppScore,"
            + "EndTick,Ticks,Events,DroppedEvents,OrphanEvents,GameMillis";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("verify")) {
            int cu = args.length > 1 ? Integer.parseInt(args[1].trim()) : 8_000;
            int tl = args.length > 2 ? Integer.parseInt(args[2].trim()) : 10;
            System.exit(verify(cu, tl) ? 0 : 1);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("smoke")) {
            run(1, new int[]{6_000}, 1, "passive", 6, new File("test_results/stage0_smoke"));
            return;
        }
        int threads = args.length > 0 ? Integer.parseInt(args[0].trim()) : 4;
        int[] cu = args.length > 1 ? parseInts(args[1]) : DEFAULT_CU;
        int games = args.length > 2 ? Integer.parseInt(args[2].trim()) : DEFAULT_GAMES_PER_BUDGET;
        String opponent = args.length > 3 ? args[3].trim().toLowerCase(Locale.ROOT) : "emcts";
        int turnLimit = args.length > 4 ? Integer.parseInt(args[4].trim()) : DEFAULT_TURN_LIMIT;
        File outDir = new File(args.length > 5 ? args[5].trim() : DEFAULT_OUT);
        run(threads, cu, games, opponent, turnLimit, outDir);
    }

    // ------------------------------------------------------------------ the run

    public static void run(int threads, int[] cuLevels, int gamesPerBudget, String opponent,
                           int turnLimit, File outDir) throws Exception {
        RunGuard.install("STAGE 0 SIGNAL",
                "re-run the SAME command — finished (budget, game) cells are skipped");
        ExpCsv.ensureDir(outDir);
        if (!RunGuard.acquireLock(outDir, "Stage0SignalRunner")) RunGuard.abort(2);
        FullGameRunnerV6.applyEngineConstants(turnLimit);

        System.out.println("############################################################");
        System.out.println("# STAGE 0 — mutation-signal data collection   (output=" + outDir + ")");
        System.out.println("# Budgets: " + Arrays.toString(cuLevels) + " compute-units of SEARCH");
        System.out.println("# Games/budget: " + gamesPerBudget + "   opponent: " + opponent
                + "   turnLimit: " + turnLimit + "   threads: " + threads);
        System.out.println("# ComputeMeter weights: " + core.game.ComputeMeter.weightsSummary());
        System.out.println("############################################################");

        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();
        writeMeta(outDir, cuLevels, gamesPerBudget, opponent, turnLimit, threads);

        // Tasks, largest budgets first so the long games start early and the tail stays short.
        List<int[]> tasks = new ArrayList<>();   // {cu, gameId}
        int skipped = 0;
        int[] sorted = cuLevels.clone();
        Arrays.sort(sorted);
        for (int i = sorted.length - 1; i >= 0; i--) {
            for (int g = 0; g < gamesPerBudget; g++) {
                if (mutlogFile(outDir, sorted[i], g).exists()) { skipped++; continue; }
                tasks.add(new int[]{sorted[i], g});
            }
        }
        if (skipped > 0)
            System.out.println("[stage0] resume: " + skipped + " finished game(s) skipped");
        if (tasks.isEmpty()) { System.out.println("[stage0] nothing outstanding — all done."); return; }
        System.out.println("[stage0] " + tasks.size() + " game(s) to run");

        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, 5_000L);
        governor.start();
        AtomicInteger done = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        List<Future<?>> futures = new ArrayList<>();
        final int total = tasks.size();

        try {
            for (int[] task : tasks) {
                final int cu = task[0], g = task[1];
                futures.add(pool.submit(() -> {
                    if (RunGuard.stopRequested()) return;
                    try {
                        governor.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        playAndLog(cu, g, opponent, maps, outDir);
                    } catch (Throwable t) {
                        System.err.println("[stage0] FAILED cu=" + cu + " game=" + g + ": " + t);
                        t.printStackTrace();
                    } finally {
                        governor.release();
                    }
                    int c = done.incrementAndGet();
                    long el = System.currentTimeMillis() - t0;
                    System.out.println(String.format(Locale.ROOT,
                            "[stage0] %d/%d done (%.1f min elapsed)  [%s]",
                            c, total, el / 60000.0, governor.status()));
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }
        System.out.println("[stage0] finished in "
                + String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 60000.0)
                + " min. Analyse with: python analysis/analyze_stage0.py " + outDir);
    }

    // ------------------------------------------------------------------ one game

    private static void playAndLog(int cu, int gameId, String opponent,
                                   List<FullGameMapSetV6.MapInfo> maps, File outDir)
            throws Exception {
        FullGameMapSetV6.MapInfo map = maps.get(gameId % FullGameMapSetV6.NUM_MAPS);
        long gameSeed = FullGameMapSetV6.scramble(GAME_SEED_BASE + gameId);
        long agentSeed = FullGameMapSetV6.scramble(AGENT_SEED_BASE + gameId);
        long oppSeed = FullGameMapSetV6.scramble(AGENT_SEED_BASE + gameId + OPP_SEED_OFFSET);

        long t0 = System.currentTimeMillis();
        Stage0MutationLogAgent tracked = new Stage0MutationLogAgent(agentSeed, params(cu));
        Agent opp = makeOpponent(opponent, oppSeed, cu);

        ArrayList<Agent> players = new ArrayList<>(Arrays.asList(tracked, opp));
        Game game = new Game();
        game.init(players, map.levelSeed, FullGameMapSetV6.TRIBES, gameSeed,
                Types.GAME_MODE.CAPITALS);
        game.run(null, null);
        long gameMillis = System.currentTimeMillis() - t0;

        int seatT = seatOf(game, tracked), seatO = seatOf(game, opp);
        int[] scores = game.getScores();
        boolean trackedWon = game.getWinnerStatus()[seatT] == Types.RESULT.WIN;

        // 1) per-turn CSV (small; plain overwrite so a re-run leaves no stale rows)
        List<TurnData> turns = tracked.getTurnDataLog();
        StringBuilder tsb = new StringBuilder(TURNS_HEADER).append('\n');
        for (TurnData t : turns) {
            tsb.append(cu).append(',').append(gameId).append(',')
               .append(t.getTurnNumber()).append(',').append(t.getScoreAtTurnStart()).append(',')
               .append(ExpCsv.round(t.getInitialSequenceValue())).append(',')
               .append(ExpCsv.round(t.getFinalSequenceValue())).append(',')
               .append(ExpCsv.round(t.getAvgNodeValue())).append(',')
               .append(t.getFmCalls()).append(',').append(t.getNodesEvaluated()).append(',')
               .append(t.getUniqueSequences()).append(',')
               .append(ExpCsv.round(t.getComputeUnits())).append(',').append(t.getAdvances()).append(',')
               .append(t.getCopies()).append(',').append(t.getEvals()).append(',')
               .append(t.getSearchFmCalls()).append(',')
               .append(ExpCsv.round(t.getInitComputeUnits())).append(',').append(t.getInitAdvances()).append(',')
               .append(t.getInitCopies()).append(',').append(t.getInitEvals()).append(',')
               .append(t.getInitFmCalls()).append(',')
               .append(t.getPlanMillis()).append(',').append(t.getInitMillis()).append('\n');
        }
        ExpCsv.writeText(turnsFile(outDir, cu, gameId), tsb.toString());

        // 2) summary row (appended; the analysis de-dups on (Budget, GameId) keep-last)
        int endTick = Math.max(tracked.getGameDurationTurns(), opp.getGameDurationTurns());
        String summary = cu + "," + gameId + "," + map.index + "," + map.levelSeed + ","
                + agentSeed + "," + opponent + "," + (trackedWon ? 1 : 0) + ","
                + scores[seatT] + "," + scores[seatO] + "," + endTick + "," + turns.size() + ","
                + tracked.getEventRows().size() + "," + tracked.getDroppedEvents() + ","
                + tracked.getOrphanEvents() + "," + gameMillis;
        ExpCsv.append(new File(outDir, "games_summary.csv"), SUMMARY_HEADER,
                Collections.singletonList(summary));

        // 3) the event log, gzipped; renamed into place LAST = completion authority for resume
        File fin = mutlogFile(outDir, cu, gameId);
        File tmp = new File(fin.getParentFile(), fin.getName() + ".tmp");
        String prefix = cu + "," + gameId + "," + agentSeed + ",";
        try (Writer w = new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8)) {
            w.write(MUTLOG_HEADER);
            w.write('\n');
            for (String row : tracked.getEventRows()) {
                w.write(prefix);
                w.write(row);
                w.write('\n');
            }
        }
        if (fin.exists() && !fin.delete())
            throw new IllegalStateException("cannot replace " + fin);
        if (!tmp.renameTo(fin))
            throw new IllegalStateException("cannot rename " + tmp + " -> " + fin);

        System.out.println(String.format(Locale.ROOT,
                "[stage0]   cu=%d game=%d: %d ticks, %d events, won=%s, %.1f s"
                        + (tracked.getOrphanEvents() > 0 ? "  WARN orphans=" + tracked.getOrphanEvents() : ""),
                cu, gameId, turns.size(), tracked.getEventRows().size(), trackedWon,
                gameMillis / 1000.0));
    }

    // ------------------------------------------------------------------ verify

    /**
     * The equivalence guard: plays the SAME game (map, seeds, opponent) once with the
     * instrumented agent and once with plain {@code ExperimentalEMCTSAgent}, and requires the
     * two runs to match turn-for-turn (ticks, values, forward-model calls, final score). This is
     * what licenses reading Stage-0 logs as "what plain EMCTS does", and it guards the verbatim
     * copy of {@code mutate()} against silent drift from the base class.
     */
    static boolean verify(int cu, int turnLimit) {
        System.out.println("[stage0-verify] cu=" + cu + " turnLimit=" + turnLimit
                + " — instrumented vs plain ExperimentalEMCTSAgent on one identical game");
        FullGameRunnerV6.applyEngineConstants(turnLimit);
        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();

        Outcome a = playForVerify(true, cu, maps);
        Outcome b = playForVerify(false, cu, maps);

        boolean ok = a.equals(b);
        System.out.println("[stage0-verify] instrumented: " + a);
        System.out.println("[stage0-verify] plain:        " + b);
        if (ok) {
            System.out.println("[stage0-verify] PASS — instrumentation is behaviourally inert");
        } else {
            System.out.println("[stage0-verify] FAIL — the instrumented mutate() has drifted from "
                    + "the base implementation; fix before collecting data");
        }
        return ok;
    }

    private static final class Outcome {
        final List<String> perTurn = new ArrayList<>();
        int score, oppScore, ticks;
        @Override public boolean equals(Object o) {
            if (!(o instanceof Outcome)) return false;
            Outcome x = (Outcome) o;
            return score == x.score && oppScore == x.oppScore && ticks == x.ticks
                    && perTurn.equals(x.perTurn);
        }
        @Override public int hashCode() { return perTurn.hashCode(); }
        @Override public String toString() {
            return "score=" + score + " oppScore=" + oppScore + " ticks=" + ticks
                    + " turns=" + perTurn.size()
                    + (perTurn.isEmpty() ? "" : " firstTurn={" + perTurn.get(0) + "}");
        }
    }

    private static Outcome playForVerify(boolean instrumented, int cu,
                                         List<FullGameMapSetV6.MapInfo> maps) {
        FullGameMapSetV6.MapInfo map = maps.get(0);
        long gameSeed = FullGameMapSetV6.scramble(GAME_SEED_BASE + 999_331L);
        long agentSeed = FullGameMapSetV6.scramble(AGENT_SEED_BASE + 999_331L);
        long oppSeed = FullGameMapSetV6.scramble(AGENT_SEED_BASE + 999_331L + OPP_SEED_OFFSET);

        Agent tracked = instrumented
                ? new Stage0MutationLogAgent(agentSeed, params(cu))
                : new ExperimentalEMCTSAgent(agentSeed, params(cu));
        Agent opp = makeOpponent("emcts", oppSeed, cu);
        ArrayList<Agent> players = new ArrayList<>(Arrays.asList(tracked, opp));
        Game game = new Game();
        game.init(players, map.levelSeed, FullGameMapSetV6.TRIBES, gameSeed,
                Types.GAME_MODE.CAPITALS);
        game.run(null, null);

        Outcome out = new Outcome();
        int seatT = seatOf(game, tracked), seatO = seatOf(game, opp);
        out.score = game.getScores()[seatT];
        out.oppScore = game.getScores()[seatO];
        out.ticks = Math.max(tracked.getGameDurationTurns(), opp.getGameDurationTurns());
        for (TurnData t : tracked.getTurnDataLog()) {
            out.perTurn.add(t.getTurnNumber() + ":" + ExpCsv.round(t.getInitialSequenceValue())
                    + ":" + ExpCsv.round(t.getFinalSequenceValue()) + ":" + t.getFmCalls()
                    + ":" + t.getNodesEvaluated() + ":" + t.getUniqueSequences());
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Same configuration as the v6 EMCTS reference: STOP_COMPUTE, SIMPLE heuristic, depth 21. */
    private static ExperimentalEMCTSParams params(int cu) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = cu;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = ExpAgents.DEPTH;
        return p;
    }

    private static Agent makeOpponent(String kind, long seed, int cu) {
        if (kind.startsWith("passive")) return new DoNothingAgent(seed);
        if (kind.startsWith("emcts")) return new ExperimentalEMCTSAgent(seed, params(cu));
        throw new IllegalArgumentException("Unknown opponent '" + kind + "' (emcts|passive)");
    }

    /** {@code Game.init} reseats players by tribe placement, so locate by object identity. */
    private static int seatOf(Game game, Agent agent) {
        Agent[] seated = game.getPlayers();
        for (int i = 0; i < seated.length; i++) if (seated[i] == agent) return i;
        throw new IllegalStateException("agent not seated");
    }

    private static File mutlogFile(File dir, int cu, int g) {
        return new File(dir, "mutlog_" + cu + "cu_g" + g + ".csv.gz");
    }

    private static File turnsFile(File dir, int cu, int g) {
        return new File(dir, "turns_" + cu + "cu_g" + g + ".csv");
    }

    private static void writeMeta(File outDir, int[] cu, int games, String opponent,
                                  int turnLimit, int threads) {
        StringBuilder sb = new StringBuilder();
        sb.append("stage0 signal run\n")
          .append("date=").append(new java.util.Date()).append('\n')
          .append("budgets=").append(Arrays.toString(cu)).append('\n')
          .append("gamesPerBudget=").append(games).append('\n')
          .append("opponent=").append(opponent).append('\n')
          .append("turnLimit=").append(turnLimit).append('\n')
          .append("threads=").append(threads).append('\n')
          .append("computeMeterWeights=").append(core.game.ComputeMeter.weightsSummary()).append('\n')
          .append("java=").append(System.getProperty("java.version")).append('\n')
          .append("os=").append(System.getProperty("os.name")).append('\n');
        ExpCsv.writeText(new File(outDir, "_meta.txt"), sb.toString());
    }

    private static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }
}
