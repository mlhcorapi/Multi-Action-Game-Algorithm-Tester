package players.emcts;

import players.heuristics.AlgParams;

public class EMCTSParams extends AlgParams {
    public float bias = 0f;
    public int depth = 21; // phases per turn; bridge burns = depth - 1 = 20
    public int NODE_SIZE = 20;
}
