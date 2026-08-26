package core.data.exp;

import core.game.ComputeMeter;
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
            if (ms <= 500)       { p.POP_SIZE = 100; p.TOURNAMENT_SIZE = 3; }   // 500ms  (OepTuning)
            else if (ms <= 1000) { p.POP_SIZE = 50;  p.TOURNAMENT_SIZE = 10; }  // 1000ms (OepTuning)
            else                 { p.POP_SIZE = 100; p.TOURNAMENT_SIZE = 10; }  // 5000ms PLACEHOLDER - update from the v2 5000ms sweep (test_results/v2/oep_tuning)
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

    // ---- v4 Isolation Phase variants ----
    // STOP_TIME plus randomInitialSequence=true: every agent's root starts from the same uniformly-random
    // valid sequence (seeded shared across all agents per scenario × visit), so the search has more room
    // to improve over its initial. The greedy in-search REPAIR mechanism is unchanged.

    private static ExperimentalEMCTSParams expParamsV4(int ratio) {
        ExperimentalEMCTSParams p = expParamsTime(ratio);
        p.randomInitialSequence = true;
        return p;
    }

    public static AgentSpec emctsV4() {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = DEPTH;
            p.randomInitialSequence = true;
            return new EMCTSAgent(seed, p);
        });
    }

    public static AgentSpec crossoverExpansionV4(int ratio) {
        return new AgentSpec("CrossoverExpansion", seed -> new CrossoverExpansionAgent(seed, expParamsV4(ratio)));
    }

    public static AgentSpec crossoverBBV4() {
        return new AgentSpec("CrossoverBB", seed -> new CrossoverBBAgent(seed, expParamsV4(5)));
    }

    public static AgentSpec crossoverComboV4(int ratio) {
        return new AgentSpec("CrossoverCombo", seed -> new CrossoverComboAgent(seed, expParamsV4(ratio)));
    }

    // ---- v5 Isolation Phase variants ----
    // Identical to v4 (randomInitialSequence=true, shared root per scenario × visit) EXCEPT the per-turn
    // budget is the deterministic COMPUTE-UNIT budget (STOP_COMPUTE via ComputeMeter) instead of
    // wall-clock (STOP_TIME). This makes every turn's search reproducible and independent of CPU
    // contention / multithreading — the v4 wall-clock budget made compute-per-turn vary with machine load.

    private static ExperimentalEMCTSParams expParamsV5(int ratio, int computeUnits) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = computeUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.mutationExpansionRatio = ratio;
        p.randomInitialSequence = true;
        return p;
    }

    public static AgentSpec emctsV5(int computeUnits) {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_COMPUTE;
            p.num_computeUnits = computeUnits;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = DEPTH;
            p.randomInitialSequence = true;
            return new EMCTSAgent(seed, p);
        });
    }

    public static AgentSpec crossoverExpansionV5(int ratio, int computeUnits) {
        return new AgentSpec("CrossoverExpansion", seed -> new CrossoverExpansionAgent(seed, expParamsV5(ratio, computeUnits)));
    }

    public static AgentSpec crossoverBBV5(int computeUnits) {
        return new AgentSpec("CrossoverBB", seed -> new CrossoverBBAgent(seed, expParamsV5(5, computeUnits)));
    }

    public static AgentSpec crossoverComboV5(int ratio, int computeUnits) {
        return new AgentSpec("CrossoverCombo", seed -> new CrossoverComboAgent(seed, expParamsV5(ratio, computeUnits)));
    }

    // ---- v6 Isolation Phase variants ----------------------------------------------------------
    // Two differences from v5:
    //   1. GREEDY initial sequence (randomInitialSequence left at its false default), matching standard
    //      EMCTS. All 12 configs run byte-identical initialization code before any operator-specific
    //      work, so a shared (scenario, visit) seed still yields an identical root in every config —
    //      the paired-comparison guarantee, now on a strong rather than a deliberately weak start.
    //   2. The budget bounds SEARCH only: the agents measure the greedy initialization and reset the
    //      ComputeMeter before the phase loop (see ExperimentalEMCTSAgent.planTurn). Bridge burning,
    //      including the crossover tournament, is INSIDE the budget.
    // Both budget flavours are provided: STOP_COMPUTE for the main run, STOP_TIME + num_timeMillis for
    // the C0 calibration (which needs exactly T ms of search regardless of initialization cost).

    /**
     * Params for one v6 configuration, including its ComputeMeter weight PROFILE.
     *
     * <p>{@code keyRatio} is the ratio as it appears in the profile name and is not always
     * {@code mutationRatio}: CrossoverBB carries a ratio field that its search never reads, so its
     * profile is {@code CrossoverBB@<units>} rather than {@code CrossoverBB_r5@<units>}. Getting this
     * wrong would not fail loudly - the agent would simply fall back to the global weights - so the
     * key is built in one place from {@link ComputeMeter#profileKey}.
     */
    private static ExperimentalEMCTSParams expParamsV6(String config, int keyRatio, int mutationRatio,
                                                       int computeUnits) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = computeUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.mutationExpansionRatio = mutationRatio;
        p.randomInitialSequence = false;   // greedy 1-ply init (explicit for the reader; false is the default)
        p.computeProfile = ComputeMeter.profileKey(config, keyRatio, computeUnits);
        return p;
    }

    /**
     * The STOP_TIME flavour used by the C0 calibration. The budget is a clock, so the weights do not
     * bound the search here - but they still price the {@code SearchUnits} C0 reports, and C0's whole
     * question is "how many units per millisecond does this configuration do". It therefore has to
     * state which profile priced them, which is what {@code profileUnits} is: the budget level whose
     * weights to use.
     */
    private static ExperimentalEMCTSParams expParamsV6Time(String config, int keyRatio, int mutationRatio,
                                                           int searchMillis, int profileUnits) {
        ExperimentalEMCTSParams p = expParamsV6(config, keyRatio, mutationRatio, profileUnits);
        p.stop_type = p.STOP_TIME;
        p.num_computeUnits = 0;
        p.num_timeMillis = searchMillis;
        return p;
    }

    public static AgentSpec emctsV6(int computeUnits) {
        return new AgentSpec("EMCTS", seed -> new EMCTSAgent(seed, emctsParamsV6(computeUnits)));
    }

    public static AgentSpec crossoverExpansionV6(int ratio, int computeUnits) {
        return new AgentSpec("CrossoverExpansion", seed -> new CrossoverExpansionAgent(
                seed, expParamsV6("CrossoverExpansion", ratio, ratio, computeUnits)));
    }

    public static AgentSpec crossoverBBV6(int computeUnits) {
        return new AgentSpec("CrossoverBB", seed -> new CrossoverBBAgent(
                seed, expParamsV6("CrossoverBB", -1, 5, computeUnits)));
    }

    public static AgentSpec crossoverComboV6(int ratio, int computeUnits) {
        return new AgentSpec("CrossoverCombo", seed -> new CrossoverComboAgent(
                seed, expParamsV6("CrossoverCombo", ratio, ratio, computeUnits)));
    }

    /** The budget level whose weights price a STOP_TIME run when the caller does not say. */
    public static final int DEFAULT_PROFILE_UNITS = RunExperimentV6.DEFAULT_COMPUTE_LEVELS[0];

    public static AgentSpec emctsV6Time(int searchMillis) {
        return emctsV6Time(searchMillis, DEFAULT_PROFILE_UNITS);
    }

    public static AgentSpec emctsV6Time(int searchMillis, int profileUnits) {
        return new AgentSpec("EMCTS", seed -> {
            EMCTSParams p = emctsParamsV6(profileUnits);
            p.stop_type = p.STOP_TIME;
            p.num_computeUnits = 0;
            p.num_timeMillis = searchMillis;
            return new EMCTSAgent(seed, p);
        });
    }

    public static AgentSpec crossoverExpansionV6Time(int ratio, int searchMillis) {
        return crossoverExpansionV6Time(ratio, searchMillis, DEFAULT_PROFILE_UNITS);
    }

    public static AgentSpec crossoverExpansionV6Time(int ratio, int searchMillis, int profileUnits) {
        return new AgentSpec("CrossoverExpansion", seed -> new CrossoverExpansionAgent(
                seed, expParamsV6Time("CrossoverExpansion", ratio, ratio, searchMillis, profileUnits)));
    }

    public static AgentSpec crossoverBBV6Time(int searchMillis) {
        return crossoverBBV6Time(searchMillis, DEFAULT_PROFILE_UNITS);
    }

    public static AgentSpec crossoverBBV6Time(int searchMillis, int profileUnits) {
        return new AgentSpec("CrossoverBB", seed -> new CrossoverBBAgent(
                seed, expParamsV6Time("CrossoverBB", -1, 5, searchMillis, profileUnits)));
    }

    public static AgentSpec crossoverComboV6Time(int ratio, int searchMillis) {
        return crossoverComboV6Time(ratio, searchMillis, DEFAULT_PROFILE_UNITS);
    }

    public static AgentSpec crossoverComboV6Time(int ratio, int searchMillis, int profileUnits) {
        return new AgentSpec("CrossoverCombo", seed -> new CrossoverComboAgent(
                seed, expParamsV6Time("CrossoverCombo", ratio, ratio, searchMillis, profileUnits)));
    }

    private static EMCTSParams emctsParamsV6(int computeUnits) {
        EMCTSParams p = new EMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = computeUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = DEPTH;
        p.randomInitialSequence = false;   // greedy 1-ply init
        p.computeProfile = ComputeMeter.profileKey("EMCTS", -1, computeUnits);
        return p;
    }
}
