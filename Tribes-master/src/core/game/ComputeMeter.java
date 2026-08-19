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
 * reflecting total compute rather than the forward model alone (advance() is well short of 100% of
 * planning cost, and its share varies by agent - see FmComputeProbe).
 *
 * Thread-local so parallel games never interfere. Weights are machine-calibrated by ComputeCalibrate;
 * re-run it on a new machine and update W_COPY / W_EVAL below.
 */
public final class ComputeMeter {
    private ComputeMeter() {}

    // Calibrated relative op costs (advance = 1.0). Measured on the dev machine by ComputeCalibrate
    // (per-op wall-clock ratios, order ~37us advance / ~6us copy / ~3us eval; the exact weights below
    // are the values ComputeCalibrate printed, not these rounded figures). The resulting shares are
    // shares of the COUNTED ops only and are not comparable with FmComputeProbe's share of total
    // planning wall-clock. Re-run ComputeCalibrate on a new machine and update these.
    /**
     * Where {@code ComputeCalibrateV6} writes this machine's measured weights. Declared BEFORE the
     * weight fields on purpose: static initialisers run in declaration order, and the weights read
     * this path while they are being initialised. Point elsewhere with
     * {@code -Dcompute.weightsFile=<path>}; delete the file to fall back to the compiled defaults.
     */
    private static final String WEIGHTS_FILE = System.getProperty(
            "compute.weightsFile", "test_results/v6/_calibration/compute_weights.properties");

    /** Lazily-read contents of {@link #WEIGHTS_FILE}; empty when there is no such file. */
    private static java.util.Properties fileWeights;

    public static volatile double W_ADVANCE = 1.0;
    public static volatile double W_COPY = weight("compute.wCopy", "COMPUTE_W_COPY", 0.16);
    public static volatile double W_EVAL = weight("compute.wEval", "COMPUTE_W_EVAL", 0.077);

    /**
     * The weights are wall-clock ratios and therefore a property of the MACHINE, not of the code, but
     * the ones compiled into this file came from whichever machine last ran a calibration. Resolution
     * order, most specific first:
     *
     * <ol>
     *   <li>{@code -Dcompute.wCopy} / {@code -Dcompute.wEval} (or {@code COMPUTE_W_COPY} /
     *       {@code COMPUTE_W_EVAL} in the environment) — an explicit override for one run;</li>
     *   <li>{@link #WEIGHTS_FILE}, written by {@code ComputeCalibrateV6} after a calibration that
     *       passed its own validity checks — this is the normal path, and it needs no action from the
     *       operator beyond running the calibration once;</li>
     *   <li>the compiled defaults.</li>
     * </ol>
     *
     * <p>Whichever wins is printed once and reported in the run banner, because a compute-unit is only
     * defined relative to these numbers: a dataset is not interpretable without knowing which weights
     * priced it.
     *
     * <p>Changing them changes what a compute-unit IS, so any budget expressed in units has to be
     * re-derived afterwards. Never change them mid-experiment: all configs must be priced in the same
     * currency for the comparison to hold.
     */
    private static double weight(String sysProp, String envVar, double dflt) {
        String v = System.getProperty(sysProp);
        String source = "-D" + sysProp;
        if (v == null || v.trim().isEmpty()) { v = System.getenv(envVar); source = "$" + envVar; }
        if (v == null || v.trim().isEmpty()) { v = fromFile(sysProp); source = WEIGHTS_FILE; }
        if (v == null || v.trim().isEmpty()) return dflt;
        try {
            double d = Double.parseDouble(v.trim());
            if (d > 0 && d < 100) {
                System.out.println("[ComputeMeter] " + sysProp + " = " + d + " (from " + source
                        + "; compiled default was " + dflt + ")");
                return d;
            }
            System.err.println("[ComputeMeter] ignoring implausible " + sysProp + "=" + v
                    + " from " + source);
        } catch (NumberFormatException e) {
            System.err.println("[ComputeMeter] ignoring unparseable " + sysProp + "=" + v
                    + " from " + source);
        }
        return dflt;
    }

    /** One property from the calibration file, or null if the file is absent or unreadable. */
    private static synchronized String fromFile(String key) {
        if (fileWeights == null) {
            fileWeights = new java.util.Properties();
            java.io.File f = new java.io.File(WEIGHTS_FILE);
            if (f.isFile()) {
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    fileWeights.load(in);
                } catch (Exception e) {
                    System.err.println("[ComputeMeter] could not read " + WEIGHTS_FILE + ": " + e);
                }
            }
        }
        return fileWeights.getProperty(key);
    }

    /** One-line provenance string for run logs and output headers. */
    public static String weightsSummary() {
        return String.format(java.util.Locale.ROOT, "advance=%.4f copy=%.4f eval=%.4f",
                W_ADVANCE, W_COPY, W_EVAL);
    }

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
