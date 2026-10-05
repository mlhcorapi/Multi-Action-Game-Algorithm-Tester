package magat.games.tribes;

import core.actions.Action;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.games.tribes.oracle.ThesisEmctsOracle;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams.SelectionMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The arms registered for Tribes, and the oracles the ported ones are gated against. */
public final class TribesAgents {

    private TribesAgents() {}

    /**
     * Every arm of the worked result (the AB-EMCTS line), by its MAGAT id: the arms the published R7615
     * currency prices (see {@code calibration/r7615/ORIGIN.md}), less the engine EMCTS that anchors the
     * unit and is never run. Arms not registered yet are ported by milestone 5.
     */
    public static final List<String> WORKED_RESULT = Collections.unmodifiableList(Arrays.asList(
            "emcts",
            "emcts-bias-e75",
            "emcts-bias-e50",
            "emcts-bias-e25",
            "emcts-bias-e10",
            "emcts-bias-coarse-e25",
            "emcts-bias-norem-e25",
            "emcts-bias-perburn-e25",
            "emcts-short15",
            "emcts-short15-bias-e25",
            "emcts-short5-bias-e25",
            "emcts-z0-coarse",
            "emcts-z0-mid",
            "emcts-z0-fine",
            "emcts-z0-fine-insfine",
            "emcts-comma",
            "comma-bias-e25",
            "comma-bias-e75",
            "comma-bias-e50",
            "comma-bias-e10",
            "comma-bias-coarse-e25",
            "comma-bias-fine-e25",
            "comma-bias-norem-e25",
            "comma-bias-perburn-e25",
            "comma-short15",
            "comma-short15-coarse",
            "comma-short15-bias-e25",
            "comma-short5-bias-e25",
            "comma-rand15",
            "comma-z0-coarse",
            "comma-z0-mid",
            "comma-z0-fine",
            "comma-z0-fine-insfine",
            "comma-z0-random"));

    /** The reference agents of the brief: plain EMCTS, its comma variant, and the worked result's champion. */
    public static final List<String> REFERENCE = Collections.unmodifiableList(Arrays.asList("emcts", "emcts-comma", "comma-z0-coarse"));

    /** The named arm sets, written {@code @worked-result} and {@code @reference}. */
    public static Map<String, List<String>> armSets() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("worked-result", WORKED_RESULT);
        m.put("reference", REFERENCE);
        return m;
    }

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
