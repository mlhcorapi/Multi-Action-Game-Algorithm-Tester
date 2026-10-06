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
 * <p><b>Version 1</b> prices the three forward-model operations: {@code profile.<p>.{wAdvance,wCopy,wEval}},
 * with {@code advanceNanos}, {@code copyNanos} and {@code evalNanos} where measured. Under it an algorithm's
 * own operations cost nothing, exactly as they did when the published R7615 currency was measured.
 * <b>Version 2</b> prices every operation: {@code profile.<p>.w.<op>} and {@code profile.<p>.ns.<op>} for
 * {@code advance}, {@code copy}, {@code eval} and each operation the arm declares, the calibration audit's
 * {@code profile.<p>.unpriced} share, {@code global.w.<op>}, and the header line {@code operations}.
 * The thesis tree's {@code compute.*} format is read as version 1, so the published R7615 file can be used
 * before it is converted.
 */
public final class Weights {

    /**
     * The fixed weights used when no file is installed, and the weights every portfolio capture is
     * priced with: {@code advance 1, copy 0.16, eval 0.077}, the thesis tree's compiled defaults.
     * A portfolio must not depend on a calibration, or a recalibration would silently rebuild it.
     */
    public static final double[] DEFAULT = {1.0, 0.16, 0.077};

    /** Version-1 keys of the forward-model weights and nanoseconds, in {@link Meter} index order. */
    public static final String[] OPS = {"wAdvance", "wCopy", "wEval"};
    public static final String[] NANO_KEYS = {"advanceNanos", "copyNanos", "evalNanos"};

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
            String profilePrefix = k.startsWith("profile.") ? "profile."
                    : k.startsWith("compute.profile.") ? "compute.profile." : null;
            if (profilePrefix == null) {
                String hk = normaliseHeaderKey(k);
                if (hk.startsWith("global.w.")) globalOps.put(hk.substring("global.w.".length()), parseDouble(e.getValue()));
                else header.put(hk, e.getValue());
                continue;
            }
            // <arm>@<budget>.<field>: arm ids hold no dots, budgets are digits, so the name ends at the
            // first dot after the '@'.
            String rest = k.substring(profilePrefix.length());
            int at = rest.indexOf('@');
            int dot = at < 0 ? -1 : rest.indexOf('.', at);
            if (dot <= 0) continue;
            String name = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            double v = parseDouble(e.getValue());
            int fm = indexOf(OPS, field);
            if (fm >= 0) { prices.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(Meter.FORWARD_MODEL_OPS.get(fm), v); continue; }
            int ns = indexOf(NANO_KEYS, field);
            if (ns >= 0) { nanos.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(Meter.FORWARD_MODEL_OPS.get(ns), v); continue; }
            if (field.startsWith("w.")) { prices.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(field.substring(2), v); continue; }
            if (field.startsWith("ns.")) { nanos.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(field.substring(3), v); continue; }
            if (field.equals("unpriced")) unpriced.put(name, v);
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

        double[] global = new double[]{
                globalWeight(header, globalOps, "wAdvance", "advance", DEFAULT[0]),
                globalWeight(header, globalOps, "wCopy", "copy", DEFAULT[1]),
                globalWeight(header, globalOps, "wEval", "eval", DEFAULT[2])};
        for (String fm : Meter.FORWARD_MODEL_OPS) globalOps.remove(fm);
        int version = 1;
        try {
            version = Integer.parseInt(header.getOrDefault("magat.weights.version", "1").trim());
        } catch (NumberFormatException ignore) { }
        if (version < 2) globalOps.clear();     // a version-1 file prices the forward model only
        return new Weights(source, header, version, global, globalOps, prices, nanos, unpriced);
    }

    /** Maps the thesis tree's {@code compute.*} header keys onto the version-1 names. */
    private static String normaliseHeaderKey(String k) {
        switch (k) {
            case "compute.reference":            return "reference";
            case "compute.referenceAdvanceNanos": return "referenceAdvanceNanos";
            case "compute.turnsPerRep":          return "turnsPerRep";
            case "compute.wAdvance":             return "global.wAdvance";
            case "compute.wCopy":                return "global.wCopy";
            case "compute.wEval":                return "global.wEval";
            default:                             return k;
        }
    }

    /** A global forward-model weight: the version-2 key, else the version-1 key, else the default; plausible only in (0, 1000). */
    private static double globalWeight(Map<String, String> h, Map<String, Double> v2, String v1Key, String op, double dflt) {
        Double d = v2.get(op);
        if (d == null) {
            String v = h.get("global." + v1Key);
            if (v == null) return dflt;
            d = parseDouble(v);
        }
        return (d > 0 && d < 1000) ? d : dflt;
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

    private static int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ queries

    public Path source() { return source; }
    public Map<String, String> header() { return header; }
    public String game() { return header.get("game"); }
    public String reference() { return header.get("reference"); }
    public double referenceAdvanceNanos() { return headerNumber(header, "referenceAdvanceNanos", Double.NaN); }
    public String turnsPerRep() { return header.get("turnsPerRep"); }
    public String portfolioIdentity() { return header.get("portfolio.identity"); }

    /** 1: the forward model priced only; 2: every operation priced. */
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

    /** The algorithm operations a profile prices (empty in a version-1 file), in the file's order. */
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

    /** Version 2: the global weights of the reference cell's own algorithm operations. */
    public Map<String, Double> globalOperations() { return globalOps; }

    public Set<String> profiles() { return prices.keySet(); }

    /** The profiles among {@code wanted} that this file does not price, in the given order. */
    public List<String> missing(Collection<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) if (!has(w)) out.add(w);
        return out;
    }

    /** The operations among {@code declared} that a version-2 profile does not price (none in version 1, which prices the forward model only). */
    public List<String> unpricedOperations(String profile, Collection<String> declared) {
        List<String> out = new ArrayList<>();
        if (version < 2) return out;
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

    /** The global weights on the meter's dense indices: the forward model always, the reference cell's operations in version 2. */
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
        return String.format(Locale.ROOT, "%s: version %d, %d profiles%s, reference %s (%.1f ns/advance), global %.4f/%.4f/%.4f%s",
                source == null ? "(in-memory)" : source.getFileName(), version, prices.size(),
                version >= 2 ? ", up to " + ops + " algorithm operation(s) each" : " (forward model only)",
                reference(), referenceAdvanceNanos(), global[0], global[1], global[2],
                "true".equals(header.get("toy")) ? "   TOY CALIBRATION, not a currency" : "");
    }
}
