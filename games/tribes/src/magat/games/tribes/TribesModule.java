package magat.games.tribes;

import core.actions.Action;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.game.Game;
import magat.game.GameModule;

import java.util.Map;

/** Loaded by the CLI as {@code --game tribes}. */
public final class TribesModule implements GameModule<TribesState, Action> {

    private final TribesGame game = new TribesGame();
    private final AgentRegistry<TribesState, Action> agents = TribesAgents.registry();
    private final Map<String, Oracle<TribesState>> oracles = TribesAgents.oracles();

    public TribesModule() {}

    @Override public Game<TribesState, Action> game() { return game; }
    @Override public AgentRegistry<TribesState, Action> agents() { return agents; }
    @Override public Map<String, Oracle<TribesState>> oracles() { return oracles; }
}
