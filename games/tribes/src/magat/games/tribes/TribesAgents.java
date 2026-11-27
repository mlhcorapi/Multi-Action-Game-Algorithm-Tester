package magat.games.tribes;

import core.actions.Action;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.QdeltaArms;
import magat.agents.mcts.Mcts;
import magat.agents.oep.Oep;
import magat.agents.random.RandomAgent;
import magat.agents.tbets.Tbets;
import magat.games.tribes.oracle.EngineEmctsOracle;
import magat.games.tribes.oracle.EngineQdeltaOracle;
import magat.games.tribes.oracle.EngineTbetsOracle;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arms registered for Tribes, and the oracles the ported ones are gated against. The reference
 * algorithms are the ones a calibration prices when it names no arms. The AB-EMCTS experiment's arms
 * ({@code @ab-emcts}: {@code emcts-comma}, its baseline, {@code comma-z0-coarse} and {@code comma-z0-random};
 * {@code docs/plans/ab-emcts-vs-oep.md}) are priced when named.
 */
public final class TribesAgents {

    private TribesAgents() {}

    /** The reference algorithms, written {@code @reference}. */
    public static final List<String> REFERENCE = Collections.unmodifiableList(Arrays.asList("mcts", "emcts", "oep", "tbets"));

    /** The AB-EMCTS experiment's arms, written {@code @ab-emcts}. */
    public static final List<String> AB_EMCTS = Collections.unmodifiableList(Arrays.asList("emcts-comma", "comma-z0-coarse", "comma-z0-random"));

    /** The in-turn learners (docs/plans/in-turn-learning.md), written {@code @in-turn}: tables at three granularities, a hierarchical one, a linear model and a network. */
    public static final List<String> IN_TURN = Collections.unmodifiableList(Arrays.asList(
            "comma-z0-coarse", "comma-z0-mid", "comma-z0-fine", "comma-z0-hier", "comma-z0-linear", "comma-z0-mlp"));

    /** The named arm sets, written {@code @<name>}. */
    public static Map<String, List<String>> armSets() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("reference", REFERENCE);
        m.put("ab-emcts", AB_EMCTS);
        m.put("in-turn", IN_TURN);
        return m;
    }

    public static AgentRegistry<TribesState, Action> registry() {
        AgentRegistry<TribesState, Action> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(Mcts.factory());
        r.register(EmctsFactory.plain());
        r.register(Oep.factory());
        r.register(Tbets.factory());
        r.register(EmctsFactory.comma(5));
        r.register(QdeltaArms.coarseFactory());
        r.register(QdeltaArms.randomFactory());
        r.register(QdeltaArms.midFactory());
        r.register(QdeltaArms.fineFactory());
        r.register(QdeltaArms.hierFactory());
        r.register(QdeltaArms.linearFactory());
        r.register(QdeltaArms.mlpFactory());
        return r;
    }

    public static Map<String, Oracle<TribesState>> oracles() {
        Map<String, Oracle<TribesState>> o = new LinkedHashMap<>();
        o.put("emcts", new EngineEmctsOracle());
        o.put("tbets", new EngineTbetsOracle());
        o.put("emcts-comma", new EngineEmctsOracle(ExperimentalEMCTSParams.SelectionMode.COMMA, 5));
        o.put("comma-z0-coarse", new EngineQdeltaOracle(QdeltaArms.coarse()));
        o.put("comma-z0-random", new EngineQdeltaOracle(QdeltaArms.random()));
        o.put("comma-z0-mid", new EngineQdeltaOracle(QdeltaArms.mid()));
        o.put("comma-z0-fine", new EngineQdeltaOracle(QdeltaArms.fine()));
        return o;
    }
}
