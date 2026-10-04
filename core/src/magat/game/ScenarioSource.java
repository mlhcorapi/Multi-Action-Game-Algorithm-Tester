package magat.game;

import java.util.List;

/**
 * A named generator of positions from played games. The portfolio builder owns scheduling,
 * journaling, target counts, id assignment and the manifest; a source only plays one game per
 * attempt and validates what it captures.
 *
 * <p>Two promises make a portfolio reproducible on any machine and at any thread count:
 * <ol>
 *   <li>{@link #play} is a function of the attempt number alone: what attempt {@code n} yields does
 *       not depend on what ran before it, beside it, or on which thread.</li>
 *   <li>{@link #definition} names everything that determines what {@link #play} yields. It is written
 *       into the manifest and keys the build journal, so a changed source never resumes from games
 *       played under the old one.</li>
 * </ol>
 *
 * <p>The builder plays one source at a time, in the order the game declares them, and calls
 * {@link #play} for different attempts of that source concurrently. A source may therefore set
 * engine globals its games need, provided they are the same for every attempt of that source.
 */
public interface ScenarioSource<S> {

    /** Short name used in the manifest and for stratification: lower case, digits and dashes, e.g. {@code "single"}. */
    String name();

    /** Capture points within a source game (for Tribes: ticks), in the order they are reached. */
    int[] phases();

    /** Attempts the builder may spend on this source before giving up. */
    int maxAttempts();

    /** How many captures the game's published portfolio takes from this source. */
    int defaultTarget();

    /**
     * One line naming everything that determines what {@link #play} yields: seeds, opponents, the
     * source agent and its budget and pricing, the validity predicate. It may contain commas but not
     * line breaks.
     */
    String definition();

    /**
     * Plays source game number {@code attempt} (deterministic in the attempt number) and returns
     * its validated captures in phase order. Applies the source's own validity predicate and
     * stuck-game rule. Engine failures are swallowed: a bad seed yields fewer captures. Anything that
     * escapes this method is a bug, and the builder stops rather than guess what the attempt would
     * have yielded.
     */
    List<Capture<S>> play(int attempt);
}
