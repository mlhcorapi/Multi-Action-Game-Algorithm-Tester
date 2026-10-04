package magat.calibrate;

/**
 * The calibrator's statistics over repetitions of identical work.
 *
 * <p>Every repetition of a cell runs the same positions with the same seeds, so the work is
 * byte-identical and only the timings differ. All variation between repetitions is therefore machine
 * interference, and interference can only make a repetition slower. Hence the estimator is the
 * {@link #fastest} repetition, and the stability gate is whether the two fastest agree
 * ({@link #twoFastestGap}); the full spread ({@link #cv}) is reported as a disturbance diagnostic,
 * never gated on, because it describes the worst repetition, which says nothing about the operation.
 * Weights are ratios between cells, so a consistent estimator matters more than an unbiased one.
 */
public final class Estimator {

    private Estimator() {}

    /** The smallest positive value; 0 when there is none (an operation that never occurred). */
    public static double fastest(double[] v) {
        double best = Double.POSITIVE_INFINITY;
        for (double d : v) if (d > 0 && d < best) best = d;
        return Double.isInfinite(best) ? 0 : best;
    }

    /** Relative gap between the two smallest positive values; 0 with fewer than two. */
    public static double twoFastestGap(double[] v) {
        double a = Double.POSITIVE_INFINITY, b = Double.POSITIVE_INFINITY;
        for (double d : v) {
            if (d <= 0) continue;
            if (d < a) { b = a; a = d; }
            else if (d < b) b = d;
        }
        if (Double.isInfinite(a) || Double.isInfinite(b)) return 0;
        return (b - a) / a;
    }

    /** Coefficient of variation (sample SD over mean); 0 with fewer than two values. */
    public static double cv(double[] v) {
        if (v.length < 2) return 0;
        double m = 0;
        for (double d : v) m += d;
        m /= v.length;
        double s = 0;
        for (double d : v) s += (d - m) * (d - m);
        return m != 0 ? Math.sqrt(s / (v.length - 1)) / m : 0;
    }
}
