package core.data;

import core.Constants;
import core.Types;
import core.game.Game;
import players.Agent;
import players.ExperimentalEMCTS.CrossoverBBAgent;
import players.ExperimentalEMCTS.CrossoverComboAgent;
import players.ExperimentalEMCTS.CrossoverExpansionAgent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;
import players.mcts.MCTSParams;
import players.mcts.MCTSPlayer;
import players.oep.OEPAgent;
import players.oep.OEPParams;

import java.util.ArrayList;
import java.util.List;

public class Phase0SmokeTest {

    public static void main(String[] args) {
        System.out.println("Starting Phase 0 Smoke Test...");

        Constants.MAX_TURNS_CAPITALS = 5;
        Types.GAME_MODE gameMode = Types.GAME_MODE.CAPITALS;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;

        Types.TRIBE[] tribes = new Types.TRIBE[]{Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        List<ExperimentRunner.GameSimulationWorker> workers = new ArrayList<>();
        int gameIdCounter = 1;

        String[] agentNames = {"MCTSPlayer", "OEPAgent", "EMCTSAgent", "CrossoverExpansionAgent", "CrossoverBBAgent", "CrossoverComboAgent"};

        for (String agentName : agentNames) {
            System.out.println("Queuing 3 games for agent: " + agentName);
            for (int i = 0; i < 3; i++) {
                ArrayList<Agent> matchPlayers = new ArrayList<>();
                Agent agent = null;

                if (agentName.equals("MCTSPlayer")) {
                    MCTSParams mctsParams = new MCTSParams();
                    mctsParams.stop_type = mctsParams.STOP_ITERATIONS;
                    mctsParams.num_iterations = 10;
                    mctsParams.ROLLOUT_LENGTH = 5;
                    agent = new MCTSPlayer(42, mctsParams);
                } else if (agentName.equals("OEPAgent")) {
                    OEPParams oepParams = new OEPParams();
                    oepParams.stop_type = oepParams.STOP_ITERATIONS;
                    oepParams.num_iterations = 5;
                    oepParams.POP_SIZE = 10;
                    agent = new OEPAgent(42, oepParams);
                } else if (agentName.equals("EMCTSAgent")) {
                    EMCTSParams emctsParams = new EMCTSParams();
                    emctsParams.stop_type = emctsParams.STOP_ITERATIONS;
                    emctsParams.num_iterations = 10;
                    emctsParams.depth = 2;
                    agent = new EMCTSAgent(42, emctsParams);
                } else if (agentName.equals("CrossoverExpansionAgent")) {
                    agent = new CrossoverExpansionAgent(42);
                } else if (agentName.equals("CrossoverBBAgent")) {
                    agent = new CrossoverBBAgent(42);
                } else if (agentName.equals("CrossoverComboAgent")) {
                    agent = new CrossoverComboAgent(42);
                }

                matchPlayers.add(agent);
                matchPlayers.add(new players.RandomAgent(42));

                Game game = new Game();
                game.init(matchPlayers, 100 + i, tribes, 200 + i, gameMode);

                // Track agent at index 0
                workers.add(new ExperimentRunner.GameSimulationWorker(game, gameIdCounter++, 0));
            }
        }

        System.out.println("Total simulations queued: " + workers.size());
        System.out.println("Executing ExperimentRunner runBatch with phase 0 (will trigger CsvDataHarvester)...");

        List<ExperimentRunner.SimulationResult> results = ExperimentRunner.runBatch(0, workers);

        System.out.println("\n--- CONSOLE VERIFICATION LOGGING ---");

        for (ExperimentRunner.SimulationResult result : results) {
            String agentName = result.summary.getAgentName();
            int gameId = result.summary.getGameId();
            int turns = result.turns.size();
            int bridgeBurns = result.bridgeBurns.size();
            int crossovers = result.crossovers.size();

            System.out.println(String.format("Agent: %-25s | Game ID: %3d | TurnData rows: %3d | BridgeBurn rows: %3d | Crossover rows: %3d",
                    agentName, gameId, turns, bridgeBurns, crossovers));
        }

        System.out.println("------------------------------------");
        System.out.println("Phase 0 Execution Complete. Check /test_results/ for generated CSVs.");
    }
}
