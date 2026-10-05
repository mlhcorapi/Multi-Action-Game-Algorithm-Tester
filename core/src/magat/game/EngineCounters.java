package magat.game;

/**
 * Optional: a game whose engine keeps its own forward-model counters exposes them here, so
 * {@link ContractCheck} can assert that no forward-model work bypasses the contract during a
 * planning turn (law L10), and the overhead check can compare the engine's own timing with the
 * decorator's. Purely diagnostic; nothing in a run reads it.
 */
public interface EngineCounters {

    /** The engine's own {@code {advances, copies, evals}} on the calling thread since the last reset. */
    long[] engineCounts();

    /** Zero the engine's own counters on the calling thread. */
    void resetEngineCounts();

    /**
     * Switches the engine's own per-operation timing on or off (a JVM-wide mode, like
     * {@code Meter.PROFILE}). Returns false when the engine has none. For the overhead check only.
     */
    default boolean engineTiming(boolean on) {
        return false;
    }

    /** The engine's own {@code {advanceNanos, copyNanos, evalNanos}} on the calling thread, or null. */
    default long[] engineNanos() {
        return null;
    }

    default void resetEngineNanos() {
    }

    /**
     * Nanoseconds the engine's counting hook takes for {@code calls} increments on the calling thread:
     * the cost its dormant counters add to every operation while MAGAT meters through the contract.
     * Negative when the engine has no hook.
     */
    default long dormantCounterNanos(long calls) {
        return -1;
    }
}
