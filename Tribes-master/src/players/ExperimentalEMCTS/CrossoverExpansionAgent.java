package players.ExperimentalEMCTS;

public class CrossoverExpansionAgent extends ExperimentalEMCTSAgent {

    public CrossoverExpansionAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.heuristic_method = params.DIFF_HEURISTIC;
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

