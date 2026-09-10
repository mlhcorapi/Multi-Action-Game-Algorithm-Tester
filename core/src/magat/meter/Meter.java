package magat.meter;

/**
 * Deterministic per-thread compute accounting: the count of the three forward-model operations
 * ({@code advance}, {@code copy}, {@code evaluate}) and, when {@link #PROFILE} is on, their
 * nanoseconds. Everything is thread-local, so one JVM can run many differently-priced turns at once.
 *
 * <p>The meter is driven by {@link magat.game.MeteredGame}, never by a game engine: a game is
 * metered by being wrapped, and an agent that only holds the wrapped game cannot bypass it.
 *
 * <p>A unit is the weighted total {@code advances*wAdvance + copies*wCopy + evals*wEval}, priced by
 * the profile installed on the calling thread with {@link #useProfile}. Profiles come from a
 * {@link Weights} file installed once per JVM with {@link #install}; there is deliberately no
 * default file path, so a run can never silently price itself with a file nobody pointed it at.
 *
 * <p>Extracted from the thesis tree's {@code core/game/ComputeMeter.java}; the counting and pricing
 * semantics are unchanged.
 */
public final class Meter {

    private Meter() {}

    /** Opt-in nanosecond profiling, for calibration only. Global, because it is a mode of the JVM. */
    public static volatile boolean PROFILE = false;

    /** The weights of a JVM that has installed no file: the fixed capture weights of {@link Weights#DEFAULT}. */
    private static volatile Weights weights = null;
    private static volatile boolean allowUnpriced = false;

    private static final ThreadLocal<long[]> COUNTS = ThreadLocal.withInitial(() -> new long[3]);
    private static final ThreadLocal<long[]> NANOS = ThreadLocal.withInitial(() -> new long[3]);
    private static final ThreadLocal<double[]> ACTIVE =
            ThreadLocal.withInitial(() -> globalWeights().clone());
    private static final ThreadLocal<String> ACTIVE_NAME = new ThreadLocal<>();

    // ------------------------------------------------------------------ weights

    /** Installs the weights every later {@link #useProfile} resolves against. {@code null} uninstalls. */
    public static synchronized void install(Weights w) {
        weights = w;
    }

    /** The installed weights, or {@code null}. */
    public static Weights installed() {
        return weights;
    }

    /**
     * Lets {@link #useProfile} fall back to the global weights for a profile that is not priced,
     * instead of failing. For smoke runs and probes at odd budgets only; a production runner never
     * sets it, because an unpriced arm spends a different currency and nothing in a CSV says so.
     */
    public static void allowUnpriced(boolean allow) {
        allowUnpriced = allow;
    }

    private static double[] globalWeights() {
        Weights w = weights;
        return w == null ? Weights.DEFAULT.clone() : w.global().clone();
    }

    /**
     * Installs a profile's weights on the calling thread for the planning episode about to start.
     * {@code null} installs the global weights (the reference cell's, or {@link Weights#DEFAULT} when
     * no file is installed).
     *
     * @throws IllegalStateException if the profile is not priced and {@link #allowUnpriced} is off
     */
    public static void useProfile(String profile) {
        double[] w = ACTIVE.get();
        if (profile == null) {
            double[] g = globalWeights();
            w[0] = g[0]; w[1] = g[1]; w[2] = g[2];
            ACTIVE_NAME.set(null);
            return;
        }
        Weights file = weights;
        double[] p = file == null ? null : file.profile(profile);
        if (p == null) {
            if (!allowUnpriced) {
                throw new IllegalStateException("profile '" + profile + "' is not priced"
                        + (file == null ? " (no weights file installed)"
                                        : " in " + file.source())
                        + "; calibrate it, or pass --allow-unpriced for a smoke run");
            }
            double[] g = globalWeights();
            w[0] = g[0]; w[1] = g[1]; w[2] = g[2];
            ACTIVE_NAME.set(null);
            return;
        }
        w[0] = p[0]; w[1] = p[1]; w[2] = p[2];
        ACTIVE_NAME.set(profile);
    }

    /**
     * Installs an explicit weight triple on the calling thread under a label. For probes that must
     * price both sides of a comparison identically at a budget no file prices; a run never uses it.
     */
    public static void useWeights(double[] w, String label) {
        double[] a = ACTIVE.get();
        a[0] = w[0]; a[1] = w[1]; a[2] = w[2];
        ACTIVE_NAME.set(label);
    }

    /** Profile in force on this thread, or {@code "global"}. */
    public static String activeProfile() {
        String n = ACTIVE_NAME.get();
        return n == null ? "global" : n;
    }

    /** The weights in force on this thread, as {@code {advance, copy, eval}}. */
    public static double[] activeWeights() {
        return ACTIVE.get().clone();
    }

    // ------------------------------------------------------------------ counting

    public static void onAdvance() { COUNTS.get()[0]++; }
    public static void onCopy()    { COUNTS.get()[1]++; }
    public static void onEval()    { COUNTS.get()[2]++; }

    public static void addAdvanceNanos(long n) { NANOS.get()[0] += n; }
    public static void addCopyNanos(long n)    { NANOS.get()[1] += n; }
    public static void addEvalNanos(long n)    { NANOS.get()[2] += n; }

    /** Zero the counts (not the nanoseconds) on this thread. */
    public static void reset() { long[] c = COUNTS.get(); c[0] = c[1] = c[2] = 0; }

    /** Zero the nanosecond accumulators on this thread. */
    public static void resetNanos() { long[] n = NANOS.get(); n[0] = n[1] = n[2] = 0; }

    public static long advances() { return COUNTS.get()[0]; }
    public static long copies()   { return COUNTS.get()[1]; }
    public static long evals()    { return COUNTS.get()[2]; }
    public static long advanceNanos() { return NANOS.get()[0]; }
    public static long copyNanos()    { return NANOS.get()[1]; }
    public static long evalNanos()    { return NANOS.get()[2]; }

    /** Units spent on this thread since the last {@link #reset}, priced by the profile in force. */
    public static double units() {
        long[] c = COUNTS.get();
        double[] w = ACTIVE.get();
        return c[0] * w[0] + c[1] * w[1] + c[2] * w[2];
    }

    /** What {@code advances}, {@code copies} and {@code evals} would cost under the weights in force. */
    public static double price(long advances, long copies, long evals) {
        double[] w = ACTIVE.get();
        return advances * w[0] + copies * w[1] + evals * w[2];
    }
}
