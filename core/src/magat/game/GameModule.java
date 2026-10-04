package magat.game;

import magat.agent.AgentRegistry;
import magat.agent.Oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * The arm whose advance at the smallest budget level defines this game's unit, when a new
     * currency is calibrated (an existing weights file names its own reference).
     */
    default String referenceArm() {
        return "emcts";
    }

    /**
     * Named sets of arm ids, written {@code @name} wherever a list of arms is accepted. A set may name
     * arms that are not registered yet (a published result's arms, before they are ported); commands
     * that run arms refuse those, commands that only look them up in a weights file accept them.
     */
    default Map<String, List<String>> armSets() {
        return Collections.emptyMap();
    }

    /** Expands {@code @set} entries of a list of arm ids, keeping order and dropping repeats. */
    default List<String> expandArms(List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id.startsWith("@")) {
                List<String> set = armSets().get(id.substring(1));
                if (set == null) throw new IllegalArgumentException("unknown arm set '" + id + "'; this game has " + armSets().keySet());
                out.addAll(set);
            } else {
                out.add(id);
            }
        }
        return new ArrayList<>(out);
    }
}
