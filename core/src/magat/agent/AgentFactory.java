package magat.agent;

/**
 * A named arm: builds a fresh {@link SearchAgent} for one turn. The id is the profile key's left
 * half and the results directory name, so it is lowercase, kebab-case, and permanent once a result
 * has been published under it.
 */
public interface AgentFactory<S, A> {

    String id();

    SearchAgent<S, A> create(long seed, TurnBudget budget);

    /**
     * Whether the budget buys this arm search, so that it has a price worth calibrating. False for an
     * arm whose work does not depend on the budget (the random floor); a calibration of it would only
     * fail its own sample-size gate.
     */
    default boolean priced() {
        return true;
    }

    /**
     * The algorithm operations this arm records through the meter, beyond the forward model's three:
     * lowercase dot-separated names led by the algorithm family ({@code emcts.legal}). An
     * arm records exactly these: a version-2 currency prices each per {@code (arm, budget)}, the
     * validator refuses a run whose currency leaves one unpriced, and a turn that records an operation
     * its factory did not declare is refused. A factory registers its names with
     * {@link magat.meter.Meter#declare} when it is constructed. Empty for an arm whose only work is the
     * forward model.
     */
    default java.util.List<String> operations() {
        return java.util.Collections.emptyList();
    }

    /** One line for banners and the registry listing. */
    default String description() {
        return "";
    }
}
