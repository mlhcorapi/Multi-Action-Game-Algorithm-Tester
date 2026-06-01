package core.data.exp;

import core.Types;
import core.game.Game;
import players.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntToLongFunction;

/**
 * Runs one matchup (a set of agents over N games) in parallel and returns per-game results.
 * Each game builds its own fresh agents, so games are independent and thread-safe.
 * There is NO per-game timeout: real games run to completion.
 */
public class ExperimentEngine {

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
            List<AgentSpec> specs,
            Types.TRIBE[] tribes,
            Types.GAME_MODE mode,
            int numGames,
            int startGameId,
            IntToLongFunction levelSeedFn,
            IntToLongFunction gameSeedFn,
            IntToLongFunction agentSeedFn,
            int threads,
            Progress progress) {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<GameResult>> futures = new ArrayList<>();

        for (int i = 0; i < numGames; i++) {
            final int gameIndex = i;
            final int gameId = startGameId + i;
            final long levelSeed = levelSeedFn.applyAsLong(gameIndex);
            final long gameSeed = gameSeedFn.applyAsLong(gameIndex);
            final long agentSeed = agentSeedFn.applyAsLong(gameIndex);
            futures.add(executor.submit(() ->
                    runOneGame(specs, tribes, mode, gameId, levelSeed, gameSeed, agentSeed)));
        }
        executor.shutdown();

        List<GameResult> results = new ArrayList<>();
        int completed = 0;
        for (Future<GameResult> f : futures) {
            try {
                GameResult r = f.get(); // no timeout
                results.add(r);
            } catch (Throwable e) {
                // Catch Throwable (incl. OutOfMemoryError) so one bad game is dropped and
                // logged rather than killing the whole multi-hour run.
                System.err.println("Game simulation failed (dropped): " + e);
            }
            completed++;
            if (progress != null && !results.isEmpty()) {
                progress.onGame(completed, numGames, results.get(results.size() - 1));
            }
        }
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        return results;
    }

    private static GameResult runOneGame(List<AgentSpec> specs, Types.TRIBE[] tribes,
                                         Types.GAME_MODE mode, int gameId,
                                         long levelSeed, long gameSeed, long agentSeed) {
        ArrayList<Agent> players = new ArrayList<>();
        for (AgentSpec spec : specs) {
            players.add(spec.create(agentSeed));
        }

        Game game = new Game();
        game.init(players, levelSeed, tribes, gameSeed, mode);
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
