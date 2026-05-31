package players.ExperimentalEMCTS;

/**
 * CrossoverCombo: uses crossover in BOTH expansion and bridge burning. All search and
 * telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this class only
 * flips the params so the experiment isolates the combined impact of crossover.
 */
public class CrossoverComboAgent extends ExperimentalEMCTSAgent {

    public CrossoverComboAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 8000; // enough budget for ~20 bridge-burn phases (depth-1)
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = true;
        return params;
    }

    @Override
    public CrossoverComboAgent copy() {
        return new CrossoverComboAgent(getSeed());
    }
}
