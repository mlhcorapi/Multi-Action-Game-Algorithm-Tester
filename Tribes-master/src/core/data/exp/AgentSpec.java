package core.data.exp;

import players.Agent;

/**
 * A named factory that builds a FRESH agent for each game (agents hold per-game state and
 * are not reusable). The factory receives the agent's RNG seed for that game.
 */
public class AgentSpec {

    public interface Factory {
        Agent create(long seed);
    }

    public final String name;
    private final Factory factory;

    public AgentSpec(String name, Factory factory) {
        this.name = name;
        this.factory = factory;
    }

    public Agent create(long seed) {
        return factory.create(seed);
    }
}
