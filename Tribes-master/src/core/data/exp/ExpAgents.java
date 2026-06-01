package core.data.exp;

import players.ExperimentalEMCTS.CrossoverBBAgent;
import players.ExperimentalEMCTS.CrossoverComboAgent;
import players.ExperimentalEMCTS.CrossoverExpansionAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;
import players.mcts.MCTSParams;
import players.mcts.MCTSPlayer;
import players.oep.OEPAgent;
import players.oep.OEPParams;

/**
 * Factories that build each agent type configured for the experiments: STOP_FMCALLS with
 * the given budget, SIMPLE heuristic, and (for the EMCTS family) depth = DEPTH. A fresh
 * params object is built per agent so per-game state never leaks between games.
 */
public class ExpAgents {

    public static final int DEPTH = 21; // bridge burns = depth - 1 = 20

    private static ExperimentalEMCTSParams expParams(int fmcalls, int ratio) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_FMCALLS;
        p.num_fmcalls = fmcalls;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.mutationExpansionRatio = ratio;
        return p;
    }

    public static AgentSpec emcts(int fmcalls) {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_FMCALLS;
            p.num_fmcalls = fmcalls;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = DEPTH;
            return new EMCTSAgent(seed, p);
        });
    }

    public static AgentSpec mcts(int fmcalls) {
        return new AgentSpec("MCTS", seed -> {
            MCTSParams p = new MCTSParams();
            p.stop_type = p.STOP_FMCALLS;
            p.num_fmcalls = fmcalls;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            return new MCTSPlayer(seed, p);
        });
    }

    public static AgentSpec oep(int fmcalls) {
        return new AgentSpec("OEP", seed -> {
            OEPParams p = new OEPParams();
            p.stop_type = p.STOP_FMCALLS;
            p.num_fmcalls = fmcalls;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            return new OEPAgent(seed, p);
        });
    }

    public static AgentSpec crossoverExpansion(int fmcalls, int ratio) {
        return new AgentSpec("CrossoverExpansion",
                seed -> new CrossoverExpansionAgent(seed, expParams(fmcalls, ratio)));
    }

    public static AgentSpec crossoverBB(int fmcalls) {
        // Bridge-burning variant; the expansion ratio is irrelevant (no crossover expansion).
        return new AgentSpec("CrossoverBB",
                seed -> new CrossoverBBAgent(seed, expParams(fmcalls, 5)));
    }

    public static AgentSpec crossoverCombo(int fmcalls, int ratio) {
        return new AgentSpec("CrossoverCombo",
                seed -> new CrossoverComboAgent(seed, expParams(fmcalls, ratio)));
    }
}
