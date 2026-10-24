package magat.agents.oep;

/**
 * Parameters of {@link Oep}, the defaults of the Tribes engine's {@code OEPParams}: a population of 50 whole
 * turns, half of it killed each generation, one offspring in ten mutated, and turns of at most 20 actions
 * before the end of turn. The budget is always the compute budget of the {@link magat.agent.TurnBudget}.
 */
public class OepParams {

    /** Whole turns in the population. */
    public int populationSize = 50;

    /** The share of the population removed each generation, the worst first. */
    public double killRate = 0.5;

    /** The chance that an offspring has one action, chosen at random, replaced by a random legal one. */
    public double mutationRate = 0.1;

    /** The longest plan, in actions before the end of turn. */
    public int maxActions = 20;

    public OepParams copy() {
        OepParams p = new OepParams();
        p.populationSize = populationSize;
        p.killRate = killRate;
        p.mutationRate = mutationRate;
        p.maxActions = maxActions;
        return p;
    }
}
