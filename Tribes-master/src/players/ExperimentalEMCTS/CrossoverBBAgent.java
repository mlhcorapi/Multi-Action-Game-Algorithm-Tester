package players.ExperimentalEMCTS;

/**
 * CrossoverBB: standard EMCTS search plus Crossover Bridge Burning at phase transitions.
 * All search/telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this
 * class only flips the params so the experiment isolates the impact of crossover.
 */
public class CrossoverBBAgent extends ExperimentalEMCTSAgent {

    public CrossoverBBAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 8000; // enough budget for ~20 bridge-burn phases (depth-1)
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = false;
        return params;
    }

    @Override
    public CrossoverBBAgent copy() {
        return new CrossoverBBAgent(getSeed());
    }
}
