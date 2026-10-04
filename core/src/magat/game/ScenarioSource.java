package magat.game;

import java.util.List;

/**
 * A named generator of positions from played games. The portfolio builder owns scheduling,
 * journaling, target counts and stratification; a source only plays one game per attempt and
 * validates what it captures.
 */
public interface ScenarioSource<S> {

    /** Short name used in the manifest and for stratification, e.g. {@code "single"}. */
    String name();

    /** Capture points within a source game (for Tribes: ticks), in the order they are reached. */
    int[] phases();

    /** Attempts the builder may spend on this source before giving up. */
    int maxAttempts();

    /**
     * Plays source game number {@code attempt} (deterministic in the attempt number) and returns
     * its validated captures in phase order. Applies the source's own validity predicate and
     * stuck-game rule. Engine failures are swallowed: a bad seed yields fewer captures, never an
     * aborted build.
     */
    List<Capture<S>> play(int attempt);
}
