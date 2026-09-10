package magat.game;

import java.util.List;
import java.util.Map;

/**
 * The contract a multi-action game implements to be evaluated by MAGAT.
 *
 * <p>Generic in the state type {@code S} and the action type {@code A}. Every method is total (no
 * exceptions for ordinary inputs) and deterministic. The laws the methods must obey are listed in
 * {@code docs/CONTRACT.md} and asserted by {@link ContractCheck}.
 *
 * <p>Nothing here is a game loop: the isolation instrument replans one turn from a captured
 * position. The engine's own turn loop is used only inside {@link ScenarioSource#play}.
 *
 * @param <S> a mutable game state
 * @param <A> an action; must have a canonical string identity via {@link #actionKey}
 */
public interface Game<S, A> {

    // ---- identity and provenance ------------------------------------------------------------

    /** Short stable id, e.g. {@code "tribes"}. Names the currency, the portfolio and the results tree. */
    String id();

    /**
     * Every ruleset/engine setting that changes what a position or a forward-model call means
     * (engine commit, game mode, rule flags). Frozen into the portfolio manifest and every run's
     * provenance; a run refuses a portfolio whose configuration differs.
     */
    Map<String, String> configuration();

    // ---- the forward model (every call metered by MeteredGame) --------------------------------

    S copy(S state);

    /**
     * Applies one action in place. The action came from {@link #legalActions} of this state or from
     * an earlier state of the same turn (a plan being repaired or replayed), so it may be stale. A
     * stale action is a no-op, never a crash: a repaired plan replayed on a fresh copy may meet a
     * different stochastic outcome, and the search relies on the replay completing. An engine
     * failure that does propagate is treated by repairing searches as "replace this action".
     */
    void advance(S state, A action);

    /**
     * The actions the active player may take now, filtered to the ones a search should consider.
     * Empty only at a terminal state or a turn boundary. The order is deterministic and part of the
     * game's identity: searches index into it with seeded random draws.
     */
    List<A> legalActions(S state);

    /** Cheap, side-effect-free, never throws: is this (possibly stale) action applicable here? */
    boolean isLegal(S state, A action);

    /**
     * Heuristic value of the state from one player's point of view. Finite; a terminal state may
     * carry a large sentinel, which {@link magat.agent.TurnRecord#terminal} reports separately.
     */
    double evaluate(S state, int player);

    boolean isTerminal(S state);

    // ---- turn structure -----------------------------------------------------------------------

    int activePlayer(S state);

    int playerCount(S state);

    /** True if applying this action ends the active player's turn. */
    boolean endsTurn(A action);

    /** The explicit end-of-turn action in this state, or {@code null} if the game ends turns implicitly. */
    A endTurnAction(S state);

    // ---- identity and abstraction of actions ---------------------------------------------------

    /**
     * Canonical identity of an action within a state; sequences are de-duplicated on it. Two
     * distinct legal actions of one state never share a key.
     */
    String actionKey(A action);

    /**
     * The action abstracted to a granularity, for learned tables: {@code COARSE} ("MOVE"),
     * {@code MID} ("MOVE:RIDER"), {@code FINE} (the full identity). Coarser levels partition finer ones.
     */
    String actionClass(S state, A action, Granularity level);

    /** A descriptive count for stratification and reporting; the game documents what it counts. */
    int branchingFactor(S state);

    // ---- determinism and persistence --------------------------------------------------------------

    /**
     * Pins the state's own stochasticity so two copies reseeded alike behave alike under the same
     * actions.
     */
    void reseed(S state, long seed);

    byte[] serialize(S state);

    S deserialize(byte[] bytes);

    /**
     * Small descriptive facts about a position for the manifest. Deterministic; never used for
     * pairing.
     */
    Map<String, String> describe(S state);

    // ---- capture --------------------------------------------------------------------------------

    /**
     * Named generators of positions from played games. The portfolio builder schedules, journals
     * and stratifies; a source only plays and validates.
     */
    List<ScenarioSource<S>> scenarioSources();
}
