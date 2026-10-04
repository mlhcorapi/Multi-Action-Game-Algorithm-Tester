package magat.game;

/** One position captured from a source game, before it is given a scenario id. */
public final class Capture<S> {
    public final int attempt;
    public final long seed;
    public final int phase;
    public final S state;

    public Capture(int attempt, long seed, int phase, S state) {
        this.attempt = attempt;
        this.seed = seed;
        this.phase = phase;
        this.state = state;
    }
}
