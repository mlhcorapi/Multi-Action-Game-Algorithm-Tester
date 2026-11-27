package magat.agents.emcts;

/**
 * What a {@link QdeltaEmcts} arm learns within one turn when it is not a table of independent classes: an
 * estimate of the change in a plan's value an action brings, from the action's coarse, mid and fine classes,
 * its position in the plan and the turn's progress. Credited as the table is, by every evaluated child: the
 * change goes to the action inserted and, negated, to the one removed. Starts empty every turn.
 *
 * <p>Every implementation is deterministic: the same examples in the same order give the same estimates on any
 * JVM ({@code StrictMath} for every transcendental), so a turn re-plays field for field.
 */
interface InTurnLearner {

    /** Forgets everything: the turn begins. */
    void reset();

    /**
     * One example.
     *
     * @param classes  the action's coarse, mid and fine classes
     * @param position its position in the plan, 0 (first) to 1 (last)
     * @param progress how much of the turn's budget is spent, 0 to 1
     * @param delta    the change in value it is credited with
     */
    void credit(String[] classes, double position, double progress, double delta);

    /** The estimated change the action brings; only the order of estimates matters to the repair. */
    double estimate(String[] classes, double position, double progress);

    /** A one-line summary of what was learned this turn, for telemetry: the squared error of its last predictions. */
    double meanSquaredError();
}
