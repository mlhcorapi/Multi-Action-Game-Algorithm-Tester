package players.ExperimentalEMCTS;

public class CrossoverExpansionAgent extends ExperimentalEMCTSAgent {

    public CrossoverExpansionAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.useCrossoverBridgeBurning = false;
        params.useCrossoverExpansion = true;
        params.crossoverExpansionRatio = 1;
        params.mutationExpansionRatio = 5;
        return params;
    }
}

