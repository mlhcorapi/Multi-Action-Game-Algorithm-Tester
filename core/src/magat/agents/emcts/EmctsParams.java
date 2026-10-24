package magat.agents.emcts;

/**
 * Parameters of {@link Emcts}: 21 phases (20 bridge burns) and a greedy 1-ply initial plan of at most
 * 19 actions plus the end of turn. The budget is always the compute budget of the
 * {@link magat.agent.TurnBudget}.
 */
public class EmctsParams {

    /** Phases per turn; bridge burns = phases - 1. */
    public int phases = 21;

    /** Maximum plan length before the end-of-turn action is appended. */
    public int nodeSize = 20;

    public EmctsParams copy() {
        EmctsParams p = new EmctsParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        return p;
    }
}
