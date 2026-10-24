package magat.meter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A calibrated weights file, loaded: the currency of one game on one machine.
 *
 * <p>One unit is one {@code advance} by the reference agent at the reference budget on the game's
 * reference portfolio positions, on the calibrated machine. Every weight is a ratio to that advance's
 * nanoseconds, per {@code <arm>@<budget>} profile.
 *
 * <p>The file format is version 2 ({@code magat.weights.version = 2}): it prices every operation,
 * {@code profile.<p>.w.<op>} and {@code profile.<p>.ns.<op>} for {@code advance}, {@code copy}, {@code eval}
 * and each operation the arm declares, the calibration audit's {@code profile.<p>.unpriced} share,
 * {@code global.w.<op>}, and the header line {@code operations}. Any other version is refused.
 */
public final class Weights {

    /**
     * The fixed measurement weights, used when no file is installed and by every portfolio capture and
     * calibration session: {@code advance 1, copy 0.16, eval 0.077}. A portfolio must not depend on a
     * calibration, or a recalibration would silently rebuild it.
     */
    public static final double[] DEFAULT = {1.0, 0.16, 0.077};

    private final Path source;
    private final Map<String, String> header;                  // every non-profile key, verbatim
    private final int version;
    private final double[] global;                             // forward-model global weights
    private final Map<String, Double> globalOps;               // version 2: the reference cell's algorithm operations
    private final Map<String, Map<String, Double>> prices;     // profile -> op -> weight (advance/copy/eval and declared ops)
    private final Map<String, Map<String, Double>> nanos;      // profile -> op -> nanoseconds
    private final Map<String, Double> unpriced;                // profile -> the calibration audit's unpriced share

    private Weights(Path source, Map<String, String> header, int version, double[] global, Map<String, Double> globalOps,
                    Map<String, Map<String, Double>> prices, Map<String, Map<String, Double>> nanos, Map<String, Double> unpriced) {
        this.source = source;
        this.header = Collections.unmodifiableMap(header);
        this.version = version;
        this.global = global;
        this.globalOps = Collections.unmodifiableMap(globalOps);
        this.prices = Collections.unmodifiableMap(prices);
        this.nanos = Collections.unmodifiableMap(nanos);
        this.unpriced = Collections.unmodifiableMap(unpriced);
    }

    public static Weights load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return parse(file, lines);
    }

    /** Parses the file's lines; exposed so tests can build a file without touching disk. */
    public static Weights parse(Path source, List<String> lines) {
        String name = source == null ? "weights" : source.toString();
        Map<String, String> raw = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            int colon = t.indexOf(':');
            int sep = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            if (sep <= 0) continue;
            raw.put(t.substring(0, sep).trim(), t.substring(sep + 1).trim());
        }

        Map<String, String> header = new LinkedHashMap<>();
        Map<String, Map<String, Double>> prices = new TreeMap<>();
        Map<String, Map<String, Double>> nanos = new TreeMap<>();
        Map<String, Double> unpriced = new TreeMap<>();
        Map<String, Double> globalOps = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("profile.")) {
                if (k.startsWith("global.w.")) globalOps.put(k.substring("global.w.".length()), parseDouble(e.getValue()));
                else header.put(k, e.getValue());
                continue;
            }
            // <arm>@<budget>.<field>: arm ids hold no dots, budgets are digits, so the name ends at the
            // first dot after the '@'.
            String rest = k.substring("profile.".length());
            int at = rest.indexOf('@');
            int dot = at < 0 ? -1 : rest.indexOf('.', at);
            if (dot <= 0) continue;
            String profile = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            double v = parseDouble(e.getValue());
            if (field.startsWith("w.")) { prices.computeIfAbsent(profile, n -> new LinkedHashMap<>()).put(field.substring(2), v); continue; }
            if (field.startsWith("ns.")) { nanos.computeIfAbsent(profile, n -> new LinkedHashMap<>()).put(field.substring(3), v); continue; }
            if (field.equals("unpriced")) unpriced.put(profile, v);
        }
        // A profile is only usable when its three forward-model weights are present and plausible (0, 1000),
        // and every algorithm operation it prices has a finite, non-negative weight (zero: declared, never
        // seen; large: one occurrence may cost many advances).
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : prices.entrySet()) {
            Map<String, Double> w = e.getValue();
            boolean ok = true;
            for (String fm : Meter.FORWARD_MODEL_OPS) {
                Double x = w.get(fm);
                if (x == null || !(x > 0 && x < 1000)) ok = false;
            }
            for (Map.Entry<String, Double> op : w.entrySet())
                if (!Meter.FORWARD_MODEL_OPS.contains(op.getKey()) && !(op.getValue() >= 0 && !op.getValue().isInfinite())) ok = false;
            if (!ok) incomplete.add(e.getKey());
        }
        for (String n : incomplete) prices.remove(n);

        String v = header.get("magat.weights.version");
        if (!"2".equals(v == null ? null : v.trim()))
            throw new IllegalArgumentException(name + " is " + (v == null ? "not a MAGAT weights file (no magat.weights.version)"
                    : "a version-" + v.trim() + " weights file") + "; MAGAT reads version 2 (calibrate this machine with magat calibrate)");
        double[] global = new double[]{
                globalWeight(globalOps, "advance", DEFAULT[0]),
                globalWeight(globalOps, "copy", DEFAULT[1]),
                globalWeight(globalOps, "eval", DEFAULT[2])};
        for (String fm : Meter.FORWARD_MODEL_OPS) globalOps.remove(fm);
        return new Weights(source, header, 2, global, globalOps, prices, nanos, unpriced);
    }

    /** A global forward-model weight, else the default; plausible only in (0, 1000). */
    private static double globalWeight(Map<String, Double> global, String op, double dflt) {
        Double d = global.get(op);
        return d != null && d > 0 && d < 1000 ? d : dflt;
    }

    /** A header number with no plausibility bound (nanoseconds). */
    private static double headerNumber(Map<String, String> h, String key, double dflt) {
        String v = h.get(key);
        if (v == null) return dflt;
        double d = parseDouble(v);
        return Double.isNaN(d) ? dflt : d;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ queries

    public Path source() { return source; }
    public Map<String, String> header() { return header; }
    public String game() { return header.get("game"); }
    public String reference() { return header.get("reference"); }
    public double referenceAdvanceNanos() { return headerNumber(header, "referenceAdvanceNanos", Double.NaN); }
    public String turnsPerRep() { return header.get("turnsPerRep"); }
    public String portfolioIdentity() { return header.get("portfolio.identity"); }

    /** The file format's version: always 2. */
    public int version() { return version; }

    /** The global fallback weights: the reference cell's, or {@link #DEFAULT} if the file has none. */
    public double[] global() { return global.clone(); }

    public boolean has(String profile) { return prices.containsKey(profile); }

    /** The three forward-model weights of a profile, as {@code {wAdvance, wCopy, wEval}}, or {@code null}. */
    public double[] profile(String name) {
        Map<String, Double> w = prices.get(name);
        if (w == null) return null;
        return new double[]{w.get("advance"), w.get("copy"), w.get("eval")};
    }

    /** Every weight of a profile, forward-model operations first, or {@code null} when it is not priced. */
    public Map<String, Double> prices(String name) {
        Map<String, Double> w = prices.get(name);
        return w == null ? null : Collections.unmodifiableMap(w);
    }

    /** The algorithm operations a profile prices, in the file's order. */
    public Set<String> operations(String name) {
        Map<String, Double> w = prices.get(name);
        Set<String> out = new LinkedHashSet<>();
        if (w != null) for (String op : w.keySet()) if (!Meter.FORWARD_MODEL_OPS.contains(op)) out.add(op);
        return out;
    }

    /** Per-operation nanoseconds of a profile's forward model ({@code NaN} where the file does not carry them). */
    public double[] nanos(String name) {
        Map<String, Double> n = nanos.get(name);
        double[] out = {Double.NaN, Double.NaN, Double.NaN};
        if (n != null) for (int i = 0; i < 3; i++) { Double x = n.get(Meter.FORWARD_MODEL_OPS.get(i)); if (x != null) out[i] = x; }
        return out;
    }

    /** Nanoseconds of every operation the file measured for a profile. */
    public Map<String, Double> nanosOf(String name) {
        Map<String, Double> n = nanos.get(name);
        return n == null ? Collections.emptyMap() : Collections.unmodifiableMap(n);
    }

    /** The calibration audit's share of the cell's turn time that no priced operation accounts for; NaN when not recorded. */
    public double unpricedShare(String name) {
        Double d = unpriced.get(name);
        return d == null ? Double.NaN : d;
    }

    /** The global weights of the reference cell's own algorithm operations. */
    public Map<String, Double> globalOperations() { return globalOps; }

    public Set<String> profiles() { return prices.keySet(); }

    /** The profiles among {@code wanted} that this file does not price, in the given order. */
    public List<String> missing(Collection<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) if (!has(w)) out.add(w);
        return out;
    }

    /** The operations among {@code declared} that a profile does not price. */
    public List<String> unpricedOperations(String profile, Collection<String> declared) {
        List<String> out = new ArrayList<>();
        Map<String, Double> w = prices.get(profile);
        for (String op : declared) if (w == null || !w.containsKey(op)) out.add(op);
        return out;
    }

    // ------------------------------------------------------------------ for the meter

    /** Writes a profile's weights onto the meter's dense operation indices; operations the file does not price stay 0. */
    void fill(String profile, double[] dense, boolean[] priced) {
        Map<String, Double> w = prices.get(profile);
        if (w == null) return;
        int n = Meter.operationCount();
        for (int i = 0; i < n; i++) {
            Double x = w.get(Meter.name(i));
            if (x != null) { dense[i] = x; priced[i] = true; }
        }
    }

    /** The global weights on the meter's dense indices: the forward model, and the reference cell's operations. */
    void fillGlobal(double[] dense, boolean[] priced) {
        for (int i = 0; i < Meter.FORWARD_MODEL; i++) { dense[i] = global[i]; priced[i] = true; }
        int n = Meter.operationCount();
        for (int i = Meter.FORWARD_MODEL; i < n; i++) {
            Double x = globalOps.get(Meter.name(i));
            if (x != null) { dense[i] = x; priced[i] = true; }
        }
    }

    /** One line for banners. */
    public String summary() {
        int ops = 0;
        for (String p : prices.keySet()) ops = Math.max(ops, operations(p).size());
        return String.format(Locale.ROOT, "%s: %d profiles, up to %d algorithm operation(s) each, reference %s (%.1f ns/advance), global %.4f/%.4f/%.4f%s",
                source == null ? "(in-memory)" : source.getFileName(), prices.size(), ops,
                reference(), referenceAdvanceNanos(), global[0], global[1], global[2],
                "true".equals(header.get("toy")) ? "   TOY CALIBRATION, not a currency" : "");
    }
}
