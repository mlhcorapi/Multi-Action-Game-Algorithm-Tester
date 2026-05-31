package players.ExperimentalEMCTS;

public class CrossoverBBAgent extends ExperimentalEMCTSAgent {

    public CrossoverBBAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.heuristic_method = params.DIFF_HEURISTIC;
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = false;
        return params;
    }

    @Override
    public CrossoverBBAgent copy() {
        return new CrossoverBBAgent(getSeed());
    }
}

