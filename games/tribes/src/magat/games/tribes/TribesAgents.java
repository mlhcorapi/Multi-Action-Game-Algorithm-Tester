package magat.games.tribes;

import core.actions.Action;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.games.tribes.oracle.ThesisEmctsOracle;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams.SelectionMode;

import java.util.LinkedHashMap;
import java.util.Map;

/** The arms registered for Tribes, and the oracles the ported ones are gated against. */
public final class TribesAgents {

    private TribesAgents() {}

    public static AgentRegistry<TribesState, Action> registry() {
        AgentRegistry<TribesState, Action> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(EmctsFactory.comma(5));
        return r;
    }

    public static Map<String, Oracle<TribesState>> oracles() {
        Map<String, Oracle<TribesState>> o = new LinkedHashMap<>();
        o.put("emcts", new ThesisEmctsOracle(SelectionMode.PLUS, 1));
        o.put("emcts-comma", new ThesisEmctsOracle(SelectionMode.COMMA, 5));
        return o;
    }
}
