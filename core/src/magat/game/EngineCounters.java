package magat.game;

/**
 * Optional: a game whose engine keeps its own forward-model counters exposes them here, so
 * {@link ContractCheck} can assert that no forward-model work bypasses the contract during a
 * planning turn (law L9). Purely diagnostic; nothing in a run reads it.
 */
public interface EngineCounters {

    /** The engine's own {@code {advances, copies, evals}} on the calling thread since the last reset. */
    long[] engineCounts();

    /** Zero the engine's own counters on the calling thread. */
    void resetEngineCounts();
}
