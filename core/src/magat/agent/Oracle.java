package magat.agent;

import magat.game.Scenario;

/**
 * A reference implementation a ported arm is gated against: typically the engine agent the port was
 * made from, driven through the engine directly. Runs on the calling thread.
 */
public interface Oracle<S> {

    /** Human-readable name of what the oracle runs, for the gate's report. */
    String describe();

    /**
     * Plans one turn exactly as the reference implementation does, under the given weights
     * {@code {wAdvance, wCopy, wEval}} for its budget stop, and reports its signature.
     */
    Signature plan(Scenario<S> scenario, long seed, long budgetUnits, double[] weights);

    /**
     * Whether the reference implementation pays its initialization from the budget: the gate plays the port by
     * the same rule, so the two stop at the same point. The engine's EMCTS bounds its search alone and pays its
     * greedy initialization outside the budget; its TBETS charges its seeding to the budget.
     */
    TurnBudget.Initialization initialization();

    /**
     * Initialization copies the reference implementation makes that have no effect on anything it
     * computes, and which the port therefore does not make. The gate expects exactly this many more
     * initialization copies on the oracle's side. A copy that draws from the state's random generator,
     * as every Tribes copy does, is never dead: the copies after it are seeded from what is left, so a
     * port makes it.
     */
    default int deadInitCopies() {
        return 0;
    }
}
