package magat.agents.mcts;

/**
 * Parameters of {@link Mcts}: UCB1's exploration constant and the longest plan. The budget is always the
 * compute budget of the {@link magat.agent.TurnBudget}.
 */
public class MctsParams {

    /** UCB1's exploration constant, on means normalised to the range of values seen. */
    public double c = Math.sqrt(2);

    /** The longest plan, in actions before the end of turn: a node this deep is a leaf of the tree. */
    public int maxActions = 20;

    public MctsParams copy() {
        MctsParams p = new MctsParams();
        p.c = c;
        p.maxActions = maxActions;
        return p;
    }
}
