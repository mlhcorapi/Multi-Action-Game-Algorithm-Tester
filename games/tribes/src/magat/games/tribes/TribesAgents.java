package magat.games.tribes;

import core.actions.Action;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.games.tribes.oracle.EngineEmctsOracle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The arms registered for Tribes, and the oracles the ported ones are gated against. */
public final class TribesAgents {

    private TribesAgents() {}

    /** The reference algorithms, written {@code @reference}. */
    public static final List<String> REFERENCE = Collections.singletonList("emcts");

    /** The named arm sets, written {@code @<name>}. */
    public static Map<String, List<String>> armSets() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("reference", REFERENCE);
        return m;
    }

    public static AgentRegistry<TribesState, Action> registry() {
        AgentRegistry<TribesState, Action> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        return r;
    }

    public static Map<String, Oracle<TribesState>> oracles() {
        Map<String, Oracle<TribesState>> o = new LinkedHashMap<>();
        o.put("emcts", new EngineEmctsOracle());
        return o;
    }
}
