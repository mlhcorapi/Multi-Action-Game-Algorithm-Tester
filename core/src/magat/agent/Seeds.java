package magat.agent;

/**
 * The pairing convention: every arm planning the same (scenario, visit) gets the same seed, so the
 * agent's own randomness and the state's reseeded stochasticity are identical across arms and the
 * comparison is paired. Unchanged from the thesis tree's {@code visitSeed}, so archived rows replay.
 */
public final class Seeds {

    private Seeds() {}

    public static long visit(int scenarioId, int visit) {
        return ((long) scenarioId) * 1_000_000L + visit;
    }
}
