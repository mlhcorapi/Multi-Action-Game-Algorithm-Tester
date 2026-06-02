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
 * Factories that build each agent type configured for the experiments. The EMCTS family in Phase 1/3
 * uses the deterministic COMPUTE-UNIT budget (STOP_COMPUTE, see ComputeMeter); Phase 2 uses STOP_TIME.
 * All use the SIMPLE heuristic and (for the EMCTS family) depth = DEPTH. A fresh params object is built
 * per agent so per-game state never leaks between games.
 */
public class ExpAgents {

    public static final int DEPTH = 21; // bridge burns = depth - 1 = 20

    private static ExperimentalEMCTSParams expParams(int budget, int ratio) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;          // deterministic compute-unit budget (Phase 1/3)
        p.num_computeUnits = budget;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.mutationExpansionRatio = ratio;
        return p;
    }

    public static AgentSpec emcts(int budget) {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_COMPUTE;          // deterministic compute-unit budget (Phase 1/3)
            p.num_computeUnits = budget;
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

    public static AgentSpec crossoverExpansion(int budget, int ratio) {
        return new AgentSpec("CrossoverExpansion",
                seed -> new CrossoverExpansionAgent(seed, expParams(budget, ratio)));
    }

    public static AgentSpec crossoverBB(int budget) {
        // Bridge-burning variant; the expansion ratio is irrelevant (no crossover expansion).
        return new AgentSpec("CrossoverBB",
                seed -> new CrossoverBBAgent(seed, expParams(budget, 5)));
    }

    public static AgentSpec crossoverCombo(int budget, int ratio) {
        return new AgentSpec("CrossoverCombo",
                seed -> new CrossoverComboAgent(seed, expParams(budget, ratio)));
    }

    /**
     * A fixed PASSIVE opponent (always ends its turn). Used as the 2nd tribe in Phase 3 so
     * the map has expansion opportunities (a 1-tribe map is degenerate) while leaving the
     * villages/development to the tracked experimental agent.
     */
    public static AgentSpec passiveOpponent() {
        return new AgentSpec("Opponent", seed -> new players.DoNothingAgent(seed));
    }

    // ---- STOP_TIME variants (Phase 2) ----
    // Under STOP_TIME the per-turn budget comes from the engine's ElapsedCpuTimer
    // (Constants.TURN_TIME_MILLIS, shared across a turn's actions), so per-action agents
    // (MCTS/OEP) and plan-once agents (EMCTS family) get an EQUAL per-turn budget.

    private static ExperimentalEMCTSParams expParamsTime(int ratio) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_TIME;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.mutationExpansionRatio = ratio;
        return p;
    }

    public static AgentSpec emctsTime() {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = DEPTH;
            return new EMCTSAgent(seed, p);
        });
    }

    public static AgentSpec mctsTime() {
        return new AgentSpec("MCTS", seed -> {
            MCTSParams p = new MCTSParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            return new MCTSPlayer(seed, p);
        });
    }

    public static AgentSpec oepTime(int ms) {
        return new AgentSpec("OEP", seed -> {
            OEPParams p = new OEPParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.KILL_RATE = 0.5;
            // Population + tournament size tuned per time budget (OepTuning sweep): kill rate fixed 0.5.
            if (ms <= 500) { p.POP_SIZE = 100; p.TOURNAMENT_SIZE = 3; }   // 500ms-equivalent
            else           { p.POP_SIZE = 50;  p.TOURNAMENT_SIZE = 10; }  // 1000ms+ (best at 1000ms)
            return new OEPAgent(seed, p);
        });
    }

    public static AgentSpec crossoverExpansionTime(int ratio) {
        return new AgentSpec("CrossoverExpansion", seed -> new CrossoverExpansionAgent(seed, expParamsTime(ratio)));
    }

    public static AgentSpec crossoverBBTime() {
        return new AgentSpec("CrossoverBB", seed -> new CrossoverBBAgent(seed, expParamsTime(5)));
    }

    public static AgentSpec crossoverComboTime(int ratio) {
        return new AgentSpec("CrossoverCombo", seed -> new CrossoverComboAgent(seed, expParamsTime(ratio)));
    }
}
