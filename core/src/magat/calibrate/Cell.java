package magat.calibrate;

import magat.meter.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One measured {@code (arm, budget)} cell: what was counted, what it cost per repetition, the
 * fastest-repetition per-operation nanoseconds, and the weights relative to the session's reference
 * advance.
 */
public final class Cell {

    public final String arm;
    public final long budget;
    public final String profile;

    /** Operations timed, summed over repetitions (each repetition counts the same work). */
    public long advances, copies, evals;
    /** Distinct positions behind the cell. */
    public int positions;
    /** {tAdvance, tCopy, tEval} in ns per repetition. */
    public final List<double[]> perRep = new ArrayList<>();
    /** Fastest-repetition nanoseconds per operation. */
    public double tAdvance, tCopy, tEval;
    public double cvAdvance, cvCopy, cvEval;
    public double gapAdvance, gapCopy, gapEval;
    /** Weights relative to this session's reference advance (the reference cell's wAdvance is 1). */
    public double wAdvance, wCopy, wEval;
    public final List<String> failures = new ArrayList<>();

    public Cell(String arm, long budget) {
        this.arm = arm;
        this.budget = budget;
        this.profile = Profile.key(arm, budget);
    }

    /** Builds a cell from known nanoseconds (tests, and re-reading a profiles CSV). */
    public static Cell measured(String arm, long budget, double tAdvance, double tCopy, double tEval) {
        Cell c = new Cell(arm, budget);
        c.tAdvance = tAdvance;
        c.tCopy = tCopy;
        c.tEval = tEval;
        return c;
    }

    /** Applies the estimator and the per-cell gates to the repetitions recorded. */
    void summarise(long minOps, double gapLimit) {
        double[] a = column(0), c = column(1), e = column(2);
        tAdvance = Estimator.fastest(a); cvAdvance = Estimator.cv(a); gapAdvance = Estimator.twoFastestGap(a);
        tCopy = Estimator.fastest(c);    cvCopy = Estimator.cv(c);    gapCopy = Estimator.twoFastestGap(c);
        tEval = Estimator.fastest(e);    cvEval = Estimator.cv(e);    gapEval = Estimator.twoFastestGap(e);
        if (advances < minOps) failures.add("only " + advances + " advances (need " + minOps + ")");
        if (copies < minOps) failures.add("only " + copies + " copies (need " + minOps + ")");
        if (evals < minOps) failures.add("only " + evals + " evals (need " + minOps + ")");
        if (perRep.size() >= 2) {
            gap("advance", gapAdvance, gapLimit);
            gap("copy", gapCopy, gapLimit);
            gap("eval", gapEval, gapLimit);
        }
    }

    private void gap(String op, double gap, double limit) {
        if (gap > limit)
            failures.add(String.format(Locale.ROOT, "two fastest %s times differ by %.1f%% (limit %.0f%%)", op, 100 * gap, 100 * limit));
    }

    /** Expresses this cell's nanoseconds as weights relative to {@code referenceAdvanceNanos}. */
    void normalise(double referenceAdvanceNanos) {
        wAdvance = tAdvance / referenceAdvanceNanos;
        wCopy = tCopy / referenceAdvanceNanos;
        wEval = tEval / referenceAdvanceNanos;
    }

    public double[] weights() { return new double[]{wAdvance, wCopy, wEval}; }

    public boolean plausible() {
        for (double w : weights()) if (!(w > 0 && w < 100)) return false;
        return true;
    }

    private double[] column(int idx) {
        double[] out = new double[perRep.size()];
        for (int i = 0; i < out.length; i++) out[i] = perRep.get(i)[idx];
        return out;
    }
}
