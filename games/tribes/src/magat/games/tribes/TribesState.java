package magat.games.tribes;

import core.game.GameState;

/**
 * A Tribes position as MAGAT sees it. The engine's {@code GameState} is held privately so that an
 * agent written against the contract cannot reach the engine by accident; {@link #raw()} is the one
 * escape hatch, used by the adapter itself and by nothing an agent should call. Its uses are
 * greppable and the layering check lists them.
 */
public final class TribesState {

    private final GameState raw;

    TribesState(GameState raw) {
        this.raw = raw;
    }

    /** The engine state. Adapter and oracle use only. */
    public GameState raw() {
        return raw;
    }
}
