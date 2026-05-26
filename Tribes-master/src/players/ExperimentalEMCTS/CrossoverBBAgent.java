package players.ExperimentalEMCTS;

public class CrossoverBBAgent extends ExperimentalEMCTSAgent {

    public CrossoverBBAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = false;
        return params;
    }

    @Override
    public CrossoverBBAgent copy() {
        return new CrossoverBBAgent(getSeed());
    }
}

