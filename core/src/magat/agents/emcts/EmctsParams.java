package magat.agents.emcts;

/**
 * Parameters of {@link Emcts}: 21 phases (20 bridge burns), a greedy 1-ply initial plan of at most
 * 19 actions plus the end of turn, and (1+1) selection unless set otherwise. The budget is always the
 * compute budget of the {@link magat.agent.TurnBudget}.
 */
public class EmctsParams {

    /** Phases per turn; bridge burns = phases - 1. */
    public int phases = 21;

    /** Maximum plan length before the end-of-turn action is appended. */
    public int nodeSize = 20;

    /** How a generation's children replace the incumbent. */
    public Selection selection = Selection.PLUS;

    /** Children per generation: 1 under {@link Selection#PLUS}, k under {@link Selection#COMMA}. */
    public int childrenPerGeneration = 1;

    public EmctsParams copy() {
        EmctsParams p = new EmctsParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        p.selection = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        return p;
    }
}
