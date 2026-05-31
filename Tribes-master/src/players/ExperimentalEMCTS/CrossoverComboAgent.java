package players.ExperimentalEMCTS;

public class CrossoverComboAgent extends ExperimentalEMCTSAgent {

    public CrossoverComboAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.heuristic_method = params.DIFF_HEURISTIC;
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = true;
        return params;
    }

    @Override
    public CrossoverComboAgent copy() {
        return new CrossoverComboAgent(getSeed());
    }
}

