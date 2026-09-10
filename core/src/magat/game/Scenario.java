package magat.game;

import java.util.Collections;
import java.util.Map;

/**
 * One portfolio position: the captured state plus the provenance every result row carries.
 * The state is shared by every worker and must be copied before use.
 */
public final class Scenario<S> {
    public final int id;
    public final String source;
    public final int attempt;
    public final long seed;
    public final int phase;
    public final int branching;
    public final S state;
    /** The game's {@link Game#describe} facts as recorded in the manifest. */
    public final Map<String, String> facts;

    public Scenario(int id, String source, int attempt, long seed, int phase, int branching,
                    S state, Map<String, String> facts) {
        this.id = id;
        this.source = source;
        this.attempt = attempt;
        this.seed = seed;
        this.phase = phase;
        this.branching = branching;
        this.state = state;
        this.facts = facts == null ? Collections.emptyMap() : Collections.unmodifiableMap(facts);
    }

    /** The identity line the portfolio hash is computed over. */
    public String identityLine() {
        return id + "," + source + "," + phase;
    }

    @Override
    public String toString() {
        return "scenario " + id + " (" + source + "@" + phase + ", branching " + branching + ")";
    }
}
