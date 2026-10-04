package magat.game;

import magat.agent.AgentRegistry;
import magat.agent.Oracle;

import java.util.Map;

/**
 * What a game adapter contributes to the CLI: the game, the arms registered for it, and any oracles
 * (reference implementations a ported arm is gated against). Loaded reflectively by name, so the
 * core never imports a game package: {@code --game tribes} resolves to
 * {@code magat.games.tribes.TribesModule}.
 */
public interface GameModule<S, A> {

    Game<S, A> game();

    AgentRegistry<S, A> agents();

    /** Arm id to the oracle it must reproduce; empty when the adapter ships none. */
    Map<String, Oracle<S>> oracles();
}
