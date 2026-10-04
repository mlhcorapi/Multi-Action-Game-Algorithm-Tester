package magat.agents.emcts;

/**
 * Parameters of {@link Emcts}. Defaults are the plain algorithm: 21 phases (20 bridge burns), a
 * greedy 1-ply initial plan of at most 19 actions plus the end of turn, (1+1) incumbent selection.
 * The budget is always the compute budget of the {@link magat.agent.TurnBudget}; the thesis tree's
 * time, iteration and forward-model-call stop rules are not carried over.
 */
public class EmctsParams {

    /** Phases per turn; bridge burns = phases - 1. */
    public int phases = 21;

    /** Maximum plan length before the end-of-turn action is appended (the thesis's {@code NODE_SIZE}). */
    public int nodeSize = 20;

    /** Unused exploration bias, kept so {@code LEGACY} scores refresh exactly as before. */
    public float bias = 0f;

    /** When true the initial plan is sampled uniformly instead of greedily (the v4 mode). */
    public boolean randomInitialSequence = false;

    public Selection selection = Selection.PLUS;

    /** Children per generation (k). 1 reduces the generational loop to the one-child search exactly. */
    public int childrenPerGeneration = 1;

    public EmctsParams copy() {
        EmctsParams p = new EmctsParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        p.bias = bias;
        p.randomInitialSequence = randomInitialSequence;
        p.selection = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        return p;
    }
}
