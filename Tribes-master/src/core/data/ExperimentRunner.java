package core.data;

import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.GameSummaryData;
import core.data.dto.TurnData;
import core.game.Game;
import core.game.TribeResult;
import players.Agent;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.*;

public class ExperimentRunner {

    // Thread pool restricted to 10 to avoid OS lockup as requested
    private static final int MAX_THREADS = 10;

    // Timeout of 30 seconds per game.
    private static final long TIMEOUT_SECONDS = 30;

    public static class SimulationResult {
        public GameSummaryData summary;
        public List<TurnData> turns;
        public List<BridgeBurnEventData> bridgeBurns;
        public List<CrossoverEventData> crossovers;

        public SimulationResult(GameSummaryData summary, List<TurnData> turns, List<BridgeBurnEventData> bridgeBurns, List<CrossoverEventData> crossovers) {
            this.summary = summary;
            this.turns = turns;
            this.bridgeBurns = bridgeBurns;
            this.crossovers = crossovers;
        }
    }

    public static class GameSimulationWorker implements Callable<SimulationResult> {
        private final Game game;
        private final int gameId;
        private final int agentIndex;

        public GameSimulationWorker(Game game, int gameId, int agentIndex) {
            this.game = game;
            this.gameId = gameId;
            this.agentIndex = agentIndex;
        }

        @Override
        public SimulationResult call() throws Exception {
            // Run the game synchronously on this thread
            game.run(null, null);

            // Fetch players
            Agent[] players = game.getPlayers();
            if (agentIndex >= players.length) {
                throw new IllegalStateException("Agent index out of bounds.");
            }
            Agent targetAgent = players[agentIndex];

            // Reconstruct Agent name (could be enhanced, basic here)
            String[] agentChunks = targetAgent.getClass().toString().split("\\.");
            String agentName = agentChunks[agentChunks.length - 1];

            // Extract performance metrics
            int score = game.getScores()[agentIndex];

            // Note: Since Game.getTick() isn't available, using a placeholder for now
            // or we could reflect gs.getTick(). We use a workaround via game.getGameState() if possible.
            // Let's use getGameState(-1) if it exists, or just use 0 as a placeholder since we only want to collect the data
            // Looking at the code Game has `gs`, but it's private.
            // We can iterate the results or use GameState directly.
            // However, GameSummaryData only requires durationTurns. Agent result() method provides duration/score.
            // It tracks gameDurationTurns and gameOutcomeScore natively.
            // Let's rely on the agent's tracking or just use 0 for duration as a fallback.
            // In session 1 it mentions Agent.java tracks gameDurationTurns and gameOutcomeScore natively within its result() method.
            // But we need to get it from there. Wait, Agent has no public getters for those yet? Or maybe it does?
            // "The base Agent.java class provides default stub getters for the EMCTS experiment metrics"
            // Let's use reflection to get gs.getTick() for now.
            int durationTurns = 0;
            try {
                java.lang.reflect.Field gsField = game.getClass().getDeclaredField("gs");
                gsField.setAccessible(true);
                Object gsObj = gsField.get(game);
                java.lang.reflect.Method tickMethod = gsObj.getClass().getMethod("getTick");
                durationTurns = (int) tickMethod.invoke(gsObj);
            } catch (Exception e) {
                System.err.println("Could not get tick: " + e);
            }

            // Construct Summary
            GameSummaryData summary = new GameSummaryData(agentName, gameId, durationTurns, score);

            // Fetch DTO Logs from Agent hooks
            List<TurnData> turns = new ArrayList<>(targetAgent.getTurnDataLog());
            List<BridgeBurnEventData> bridgeBurns = new ArrayList<>(targetAgent.getBridgeBurnDataLog());
            List<CrossoverEventData> crossovers = new ArrayList<>(targetAgent.getCrossoverDataLog());

            return new SimulationResult(summary, turns, bridgeBurns, crossovers);
        }
    }

    public static void runBatch(int phase, List<GameSimulationWorker> workers) {
        ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
        List<Future<SimulationResult>> futures = new ArrayList<>();

        for (GameSimulationWorker worker : workers) {
            futures.add(executor.submit(worker));
        }

        executor.shutdown(); // No more tasks will be submitted

        for (Future<SimulationResult> future : futures) {
            try {
                // Wait for the result with timeout
                SimulationResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Write results via CsvDataHarvester using ONLY the main thread
                CsvDataHarvester.writeSummaryAndTurns(phase, result.summary, result.turns);
                CsvDataHarvester.writeBridgeBurns(phase, result.bridgeBurns);
                CsvDataHarvester.writeCrossovers(phase, result.crossovers);

            } catch (TimeoutException e) {
                System.err.println("Game simulation timed out after " + TIMEOUT_SECONDS + " seconds.");
                future.cancel(true); // Attempt to interrupt runaway thread
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Game simulation failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("Executor termination interrupted");
            executor.shutdownNow();
        }
    }
}
