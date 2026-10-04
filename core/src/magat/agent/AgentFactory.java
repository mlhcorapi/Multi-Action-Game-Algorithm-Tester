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
     * The arm that is this arm with its mechanism replaced by noise, or {@code null} for an arm
     * without a mechanism. The experiment validator requires it in the grid.
     */
    default String attributionControl() {
        return null;
    }

    /** One line for banners and the registry listing. */
    default String description() {
        return "";
    }
}
