package core.data.exp;

import core.Types;
import core.game.Game;
import core.game.GameState;
import players.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntToLongFunction;

/**
 * Runs one matchup (a set of agents over N games) in parallel and returns per-game results.
 * Each game builds its own fresh agents, so games are independent and thread-safe.
 * There is NO per-game timeout: real games run to completion.
 *
 * Robustness: the underlying Tribes engine can throw (e.g. an occasional ClassCastException of a
 * unit to City under deep search). Rather than silently losing that game, a crashed game is
 * re-run with PERTURBED game/agent RNG seeds (the level seed is kept fixed, so e.g. Phase 3's map
 * is unchanged), up to {@link #MAX_GAME_ATTEMPTS} attempts, so each matchup still yields its full
 * N valid results. Games that still fail after all attempts are logged and dropped.
 */
public class ExperimentEngine {

    /** Initial run + up to (MAX_GAME_ATTEMPTS-1) perturbed retries per game that crashes. */
    public static final int MAX_GAME_ATTEMPTS = 4;
    /** Seed perturbation per retry attempt (large, to move well away from a deterministic crash). */
    private static final long RETRY_SEED_OFFSET = 9_000_000L;

    public interface Progress {
        void onGame(int completed, int total, GameResult result);
    }

    /**
     * @param specs        agents in intended seat order (1 for solo, 2 for head-to-head)
     * @param tribes       tribe types (length must equal specs.size())
     * @param mode         CAPITALS or SCORE
     * @param numGames     games to run
     * @param startGameId  gameId of the first game (1-based per matchup)
     * @param levelSeedFn  gameIndex (0-based) -> level-generation seed (fixed for same-map phases)
     * @param gameSeedFn   gameIndex -> game RNG seed
     * @param agentSeedFn  gameIndex -> agent RNG seed (shared by all agents in that game)
     * @param threads      worker threads
     * @param progress     per-game callback (called on the main thread), may be null
     */
    public static List<GameResult> runMatchup(
            List<AgentSpec> specs, Types.TRIBE[] tribes, Types.GAME_MODE mode,
            int numGames, int startGameId,
            IntToLongFunction levelSeedFn, IntToLongFunction gameSeedFn, IntToLongFunction agentSeedFn,
            int threads, Progress progress) {
        return runMatchup(specs, tribes, mode, numGames, startGameId,
                levelSeedFn, gameSeedFn, agentSeedFn, threads, progress, null);
    }

    /**
     * @param startState optional fixed "start position". When non-null, every game begins from a fresh
     *   copy of this state (via {@link Game#initFromState}) instead of generating a fresh board from
     *   levelSeed - so all games share an identical non-trivial starting board (e.g. the turn-3 state),
     *   and the only variation across games is the agent RNG (agentSeedFn). levelSeed/gameSeed are then
     *   unused for board generation. Null = generate a fresh board from seeds (the normal path).
     */
    public static List<GameResult> runMatchup(
            List<AgentSpec> specs,
            Types.TRIBE[] tribes,
            Types.GAME_MODE mode,
            int numGames,
            int startGameId,
            IntToLongFunction levelSeedFn,
            IntToLongFunction gameSeedFn,
            IntToLongFunction agentSeedFn,
            int threads,
            Progress progress,
            GameState startState) {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        GameResult[] slots = new GameResult[numGames]; // slots[i] = result for game index i (null until a run succeeds)
        int completed = 0;

        // Attempt 0 uses the intended seeds; each later attempt re-runs only the games that crashed,
        // perturbing the game/agent RNG seeds (level seed kept fixed) so a deterministic engine crash
        // is not simply repeated. This refills the matchup to its full numGames of valid results.
        for (int attempt = 0; attempt < MAX_GAME_ATTEMPTS; attempt++) {
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < numGames; i++) if (slots[i] == null) pending.add(i);
            if (pending.isEmpty()) break;
            if (attempt > 0)
                System.out.println("    [retry " + attempt + "] re-running " + pending.size()
                        + " crashed game(s) with perturbed seeds");

            List<Future<GameResult>> futures = new ArrayList<>();
            for (int gameIndex : pending) {
                final int gameId = startGameId + gameIndex;
                final long levelSeed = levelSeedFn.applyAsLong(gameIndex); // fixed map (unchanged across attempts)
                final long gameSeed = gameSeedFn.applyAsLong(gameIndex) + (long) attempt * RETRY_SEED_OFFSET;
                final long agentSeed = agentSeedFn.applyAsLong(gameIndex) + (long) attempt * RETRY_SEED_OFFSET;
                futures.add(executor.submit(() ->
                        runOneGame(specs, tribes, mode, gameId, levelSeed, gameSeed, agentSeed, startState)));
            }

            for (int p = 0; p < pending.size(); p++) {
                try {
                    GameResult r = futures.get(p).get(); // no timeout
                    slots[pending.get(p)] = r;
                    completed++;
                    if (progress != null) progress.onGame(completed, numGames, r);
                } catch (Throwable e) {
                    // Catch Throwable (incl. OutOfMemoryError) so one bad game is retried/dropped
                    // rather than killing the whole multi-hour run.
                    System.err.println("Game simulation failed (gameIndex " + pending.get(p)
                            + ", attempt " + attempt + "): " + e);
                }
            }
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        List<GameResult> results = new ArrayList<>();
        for (GameResult r : slots) if (r != null) results.add(r);
        int lost = numGames - results.size();
        if (lost > 0)
            System.err.println("    [warn] " + lost + " game(s) still failed after " + MAX_GAME_ATTEMPTS
                    + " attempts; dropped (matchup has " + results.size() + "/" + numGames + ").");
        return results;
    }

    private static GameResult runOneGame(List<AgentSpec> specs, Types.TRIBE[] tribes,
                                         Types.GAME_MODE mode, int gameId,
                                         long levelSeed, long gameSeed, long agentSeed,
                                         GameState startState) {
        ArrayList<Agent> players = new ArrayList<>();
        for (AgentSpec spec : specs) {
            players.add(spec.create(agentSeed));
        }

        Game game = new Game();
        if (startState != null) {
            // Begin from a fixed captured position (e.g. turn 3); each game gets its own deep copy.
            game.initFromState(players, startState.copy());
        } else {
            game.init(players, levelSeed, tribes, gameSeed, mode);
        }
        game.run(null, null);

        Agent[] seated = game.getPlayers();
        int[] scores = game.getScores();
        Types.RESULT[] status = game.getWinnerStatus();

        GameResult result = new GameResult();
        result.gameId = gameId;
        result.agents = new ArrayList<>();

        int bestScore = Integer.MIN_VALUE;
        String winnerName = "NONE";
        int endTurn = -1;

        for (int s = 0; s < specs.size(); s++) {
            Agent agent = players.get(s);
            // Game.init() reseats players by tribe placement; locate by identity.
            int seat = -1;
            for (int j = 0; j < seated.length; j++) {
                if (seated[j] == agent) { seat = j; break; }
            }
            if (seat < 0) throw new IllegalStateException("Tracked agent not found among players.");

            GameResult.AgentRecord rec = new GameResult.AgentRecord();
            rec.name = specs.get(s).name;
            rec.seat = seat;
            rec.finalScore = scores[seat];
            rec.won = (status[seat] == Types.RESULT.WIN);
            rec.turns = new ArrayList<>(agent.getTurnDataLog());
            rec.bridgeBurns = new ArrayList<>(agent.getBridgeBurnDataLog());
            rec.crossovers = new ArrayList<>(agent.getCrossoverDataLog());
            result.agents.add(rec);

            endTurn = agent.getGameDurationTurns();
            if (rec.won) winnerName = rec.name;
            if (rec.finalScore > bestScore) bestScore = rec.finalScore;
        }

        // No explicit WIN (e.g. SCORE mode or incomplete): winner = highest final score.
        if (winnerName.equals("NONE")) {
            for (GameResult.AgentRecord rec : result.agents) {
                if (rec.finalScore == bestScore) { winnerName = rec.name; break; }
            }
        }
        result.endTurn = endTurn;
        result.winnerName = winnerName;
        return result;
    }
}
