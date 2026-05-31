package players.ExperimentalEMCTS;

import players.heuristics.AlgParams;

public class ExperimentalEMCTSParams extends AlgParams {
    public float bias = 0f;
    public int depth = 21; // phases per turn; bridge burns = depth - 1 = 20
    public int NODE_SIZE = 20;
    public boolean useCrossoverBridgeBurning = false;
    public boolean useCrossoverExpansion = false;
    public int crossoverExpansionRatio = 1;
    public int mutationExpansionRatio = 5;
}
