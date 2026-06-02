package core.game;

/**
 * Deterministic per-thread compute accounting, used as a hardware-independent search budget that
 * captures (close to) ALL of an agent's planning compute - not just forward-model calls.
 *
 * It counts the three dominant operations of an EMCTS-family search:
 *   - advance() : forward-model step
 *   - copy()    : full game-state clone
 *   - eval()    : heuristic state evaluation
 * and combines them into "compute units" via calibrated relative weights (advance = 1.0), so that
 * 1 unit ~= one advance's worth of wall-clock time. Because it is a deterministic COUNT (not a clock),
 * a fixed compute-unit budget yields the same search on every run/machine (reproducible), while still
 * reflecting total compute (forward model is only ~79% of it; the rest is copies + eval).
 *
 * Thread-local so parallel games never interfere. Weights are machine-calibrated by ComputeCalibrate;
 * re-run it on a new machine and update W_COPY / W_EVAL below.
 */
public final class ComputeMeter {
    private ComputeMeter() {}

    // Calibrated relative op costs (advance = 1.0). Measured on the dev machine by ComputeCalibrate
    // (per-op ~37us advance / ~6us copy / ~3us eval => compute share 85% / 11% / 4%). Re-run
    // ComputeCalibrate on a new machine and update these.
    public static volatile double W_ADVANCE = 1.0;
    public static volatile double W_COPY = 0.16;
    public static volatile double W_EVAL = 0.077;

    // Opt-in nanosecond profiling for calibration only (default OFF => just cheap counting).
    public static volatile boolean PROFILE = false;

    private static final ThreadLocal<long[]> COUNTS = ThreadLocal.withInitial(() -> new long[3]); // [adv, copy, eval]
    private static final ThreadLocal<long[]> NANOS  = ThreadLocal.withInitial(() -> new long[3]); // [adv, copy, eval]

    public static void onAdvance() { COUNTS.get()[0]++; }
    public static void onCopy()    { COUNTS.get()[1]++; }
    public static void onEval()    { COUNTS.get()[2]++; }

    public static void addAdvanceNanos(long n) { NANOS.get()[0] += n; }
    public static void addCopyNanos(long n)    { NANOS.get()[1] += n; }
    public static void addEvalNanos(long n)    { NANOS.get()[2] += n; }

    /** Reset the per-turn counts (call at the start of each planning episode). */
    public static void reset() { long[] c = COUNTS.get(); c[0] = c[1] = c[2] = 0; }
    public static void resetNanos() { long[] n = NANOS.get(); n[0] = n[1] = n[2] = 0; }

    public static long advances() { return COUNTS.get()[0]; }
    public static long copies()   { return COUNTS.get()[1]; }
    public static long evals()    { return COUNTS.get()[2]; }
    public static long advanceNanos() { return NANOS.get()[0]; }
    public static long copyNanos()    { return NANOS.get()[1]; }
    public static long evalNanos()    { return NANOS.get()[2]; }

    /** Weighted compute units used on the current thread since the last reset(). */
    public static double computeUnits() {
        long[] c = COUNTS.get();
        return c[0] * W_ADVANCE + c[1] * W_COPY + c[2] * W_EVAL;
    }
}
