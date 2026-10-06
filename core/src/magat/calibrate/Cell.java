package magat.calibrate;

import magat.meter.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One measured {@code (arm, budget)} cell: what was counted, what it cost per repetition, the
 * fastest-repetition per-operation nanoseconds, and the weights relative to the session's reference
 * advance. The three forward-model operations have fields of their own; the arm's declared algorithm
 * operations ({@link #ops}) are measured the same way, one {@link OpStat} each.
 *
 * <p>The audit: per repetition, the wall-clock of the cell's turns against the nanoseconds its timed
 * operations account for. {@link #unpriced} is the share of the fastest repetition's turn time that
 * the operations the currency prices do not account for; {@link #unaccounted}, the share no timed
 * operation accounts for (the same under a version-2 currency, which prices them all).
 */
public final class Cell {

    /** Below this share of the cell's turn time an operation's stability is reported, not gated: an error of half in it moves the turn's price by under half a percent. */
    public static final double MATERIAL_SHARE = 0.01;

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
    /** Observations that do not fail the cell (an operation declared but never performed in these turns). */
    public final List<String> notes = new ArrayList<>();
    /** "reference", "bracket" (the reference cell measured again at the end of the session), or empty. */
    public String role = "";

    /** One algorithm operation of the arm, measured like the forward model's. */
    public static final class OpStat {
        /** Occurrences, summed over repetitions; those inside timed spans (an untimed {@code count()} adds none). */
        public long count, timed;
        /** Nanoseconds per timed occurrence, per repetition. */
        public final List<Double> perRep = new ArrayList<>();
        public double t, cv, gap, w;
        /** The operation's share of the cell's turn time (fastest repetition). */
        public double share;
    }

    /** The arm's declared algorithm operations, in declaration order. */
    public final Map<String, OpStat> ops = new LinkedHashMap<>();
    /** Per repetition: wall-clock ns of the turns, ns the forward model accounts for, ns the algorithm operations account for. */
    public final List<double[]> wallPerRep = new ArrayList<>();
    /** Audit shares of the fastest repetition (NaN until measured). */
    public double unpriced = Double.NaN, unaccounted = Double.NaN;
    /** The fastest repetition's turn time, and the shares of it the forward model and the arm's own operations account for. */
    public double turnMillis = Double.NaN, forwardModelShare = Double.NaN, operationsShare = Double.NaN;
    /** Wall-clock seconds the cell took to measure (its discarded turn and every repetition). */
    public double seconds;
    /** Turns per repetition. */
    public int turnsPerRep;

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

    /** Adds an algorithm operation measured at {@code t} ns per occurrence, {@code count} times (tests). */
    public Cell withOp(String op, double t, long count) {
        OpStat s = new OpStat();
        s.t = t;
        s.count = count;
        s.timed = count;
        ops.put(op, s);
        return this;
    }

    /**
     * Applies the estimator and the per-cell gates to the repetitions recorded.
     *
     * @param pricesOps whether the currency being written prices the algorithm operations (version 2):
     *                  the audit's {@link #unpriced} share counts them as priced only then
     */
    void summarise(long minOps, double gapLimit, boolean pricesOps) {
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

        // The audit, on the fastest repetition (interference only ever slows one down).
        int best = -1;
        for (int i = 0; i < wallPerRep.size(); i++)
            if (wallPerRep.get(i)[0] > 0 && (best < 0 || wallPerRep.get(i)[0] < wallPerRep.get(best)[0])) best = i;
        double wall = best < 0 ? Double.NaN : wallPerRep.get(best)[0];
        if (best >= 0) {
            double[] r = wallPerRep.get(best);
            unaccounted = 1 - (r[1] + r[2]) / wall;
            unpriced = 1 - (r[1] + (pricesOps ? r[2] : 0)) / wall;
            forwardModelShare = r[1] / wall;
            operationsShare = r[2] / wall;
            turnMillis = wall / Math.max(1, turnsPerRep) / 1e6;
        }

        int reps = Math.max(1, perRep.size());
        for (Map.Entry<String, OpStat> en : ops.entrySet()) {
            OpStat s = en.getValue();
            double[] v = new double[s.perRep.size()];
            for (int i = 0; i < v.length; i++) v[i] = s.perRep.get(i);
            s.t = Estimator.fastest(v);
            s.cv = Estimator.cv(v);
            s.gap = Estimator.twoFastestGap(v);
            s.share = wall > 0 ? s.t * (s.count / (double) reps) / wall : Double.NaN;
            if (s.count == 0) {
                notes.add(en.getKey() + " was declared but never performed in these turns; it is priced 0");
            } else if (s.timed == 0) {
                failures.add(en.getKey() + " was counted but never timed: record at least its batches in a span");
            } else if (v.length >= 2 && s.gap > gapLimit) {
                String msg = String.format(Locale.ROOT, "two fastest %s times differ by %.1f%% (limit %.0f%%)", en.getKey(), 100 * s.gap, 100 * gapLimit);
                if (s.share >= MATERIAL_SHARE) failures.add(msg);
                else notes.add(msg + String.format(Locale.ROOT, "; it is %.2f%% of the turn, so reported, not gated", 100 * s.share));
            }
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
        for (OpStat s : ops.values()) s.w = s.t / referenceAdvanceNanos;
    }

    public double[] weights() { return new double[]{wAdvance, wCopy, wEval}; }

    /** Every weight of the cell: the forward model's three, then the arm's operations. */
    public Map<String, Double> prices() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("advance", wAdvance);
        m.put("copy", wCopy);
        m.put("eval", wEval);
        for (Map.Entry<String, OpStat> e : ops.entrySet()) m.put(e.getKey(), e.getValue().w);
        return m;
    }

    /** Every operation's nanoseconds per occurrence. */
    public Map<String, Double> nanos() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("advance", tAdvance);
        m.put("copy", tCopy);
        m.put("eval", tEval);
        for (Map.Entry<String, OpStat> e : ops.entrySet()) m.put(e.getKey(), e.getValue().t);
        return m;
    }

    /** Every operation's count, summed over the repetitions. */
    public Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("advance", advances);
        m.put("copy", copies);
        m.put("eval", evals);
        for (Map.Entry<String, OpStat> e : ops.entrySet()) m.put(e.getKey(), e.getValue().count);
        return m;
    }

    /**
     * The forward model's weights within (0, 100) of the reference advance, the thesis calibrator's check
     * for a broken or mis-attributed timer. An algorithm operation has no such bound (one occurrence may
     * cost many advances: a generation's bookkeeping, a legal-action query, a toy game's whole turn), only
     * a finite, non-negative price.
     */
    public boolean plausible() {
        for (double w : weights()) if (!(w > 0 && w < 100)) return false;
        for (OpStat s : ops.values()) if (!(s.w >= 0 && !Double.isInfinite(s.w))) return false;
        return true;
    }

    private double[] column(int idx) {
        double[] out = new double[perRep.size()];
        for (int i = 0; i < out.length; i++) out[i] = perRep.get(i)[idx];
        return out;
    }
}
