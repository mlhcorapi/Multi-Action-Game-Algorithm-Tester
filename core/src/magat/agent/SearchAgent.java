package magat.agent;

import magat.game.Game;

/**
 * A search algorithm, written against the {@link Game} contract. A fresh instance is created for
 * every planned turn, so an agent may keep per-turn state freely and must keep no state across turns.
 *
 * <p>What an agent promises: all forward-model work goes through the game it was given; its only
 * randomness is the seed it was created with, consumed in a fixed order; {@link TurnBudget#beginSearch}
 * is called at most once, at the boundary the agent documents.
 */
public interface SearchAgent<S, A> {

    /**
     * Plans one turn from {@code state} for {@code player} and returns what happened. The state is
     * the agent's own copy and may be mutated; the budget bounds the search that follows
     * {@link TurnBudget#beginSearch}.
     */
    TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget);
}
