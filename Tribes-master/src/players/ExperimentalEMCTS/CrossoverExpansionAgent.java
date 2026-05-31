package players.ExperimentalEMCTS;

/**
 * CrossoverExpansion: replaces standard mutation with Crossover Expansion on a set ratio
 * during tree growth; uses standard (non-crossover) bridge burning. All search and
 * telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this class only
 * flips the params so the experiment isolates the impact of crossover expansion.
 */
public class CrossoverExpansionAgent extends ExperimentalEMCTSAgent {

    public CrossoverExpansionAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 8000; // enough budget for ~20 bridge-burn phases (depth-1)
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        params.useCrossoverBridgeBurning = false;
        params.useCrossoverExpansion = true;
        params.crossoverExpansionRatio = 1;
        params.mutationExpansionRatio = 5;
        return params;
    }

    @Override
    public CrossoverExpansionAgent copy() {
        return new CrossoverExpansionAgent(getSeed());
    }
}
