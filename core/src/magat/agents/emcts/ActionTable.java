package magat.agents.emcts;

import magat.game.Granularity;

import java.util.HashMap;
import java.util.Map;

/**
 * The learned statistics of {@link QdeltaEmcts} at every granularity at once: the running sum and
 * count of paired deltas per action class, and the global mean the estimates shrink toward.
 *
 * <p>One observation is recorded against the action's coarse, mid and (when any setting reads it)
 * fine class: three aggregations of one credit stream, which is what lets insertion read one
 * granularity while repair reads another. The thesis tree indexed arrays by Tribes ordinals; classes
 * here are the game's {@code actionClass} strings, which partition actions identically, and sums are
 * accumulated in the same order, so every estimate is the same double.
 */
final class ActionTable {

    private final Map<String, double[]> coarse = new HashMap<>();
    private final Map<String, double[]> mid = new HashMap<>();
    private final Map<String, double[]> fine;
    double globalSum;
    long globalN;

    ActionTable(boolean withFine) {
        this.fine = withFine ? new HashMap<>(4096) : null;
    }

    void reset() {
        coarse.clear();
        mid.clear();
        if (fine != null) fine.clear();
        globalSum = 0.0;
        globalN = 0;
    }

    void add(String coarseKey, String midKey, String fineKey, double delta) {
        bump(coarse, coarseKey, delta);
        bump(mid, midKey, delta);
        if (fine != null && fineKey != null) bump(fine, fineKey, delta);
        globalSum += delta;
        globalN++;
    }

    private static void bump(Map<String, double[]> m, String key, double delta) {
        double[] e = m.computeIfAbsent(key, k -> new double[2]);
        e[0] += delta;
        e[1] += 1.0;
    }

    /** {sum, n} of one class at one granularity; {0, 0} when unobserved. */
    double[] entry(Granularity g, String key) {
        Map<String, double[]> m = g == Granularity.COARSE ? coarse : g == Granularity.MID ? mid : fine;
        double[] e = m == null ? null : m.get(key);
        return e == null ? new double[]{0.0, 0.0} : e;
    }

    double globalMean() {
        return globalN > 0 ? globalSum / globalN : 0.0;
    }

    /** Observation counts of every observed class at one granularity. */
    int[] counts(Granularity g) {
        Map<String, double[]> m = g == Granularity.COARSE ? coarse : g == Granularity.MID ? mid : fine;
        if (m == null) return new int[0];
        int[] out = new int[m.size()];
        int i = 0;
        for (double[] e : m.values()) out[i++] = (int) e[1];
        return out;
    }

    void copyFrom(ActionTable o) {
        copy(o.coarse, coarse);
        copy(o.mid, mid);
        if (fine != null && o.fine != null) copy(o.fine, fine);
        globalSum = o.globalSum;
        globalN = o.globalN;
    }

    private static void copy(Map<String, double[]> from, Map<String, double[]> to) {
        to.clear();
        for (Map.Entry<String, double[]> e : from.entrySet()) to.put(e.getKey(), new double[]{e.getValue()[0], e.getValue()[1]});
    }
}
