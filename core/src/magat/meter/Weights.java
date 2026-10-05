package magat.meter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A calibrated weights file, loaded: the currency of one game on one machine.
 *
 * <p>One unit is one {@code advance} by the reference agent at the reference budget on the game's
 * reference portfolio positions, on the calibrated machine. Every weight, {@code wAdvance} included,
 * is a ratio to that operation's nanoseconds, per {@code <arm>@<budget>} profile.
 *
 * <p>Reads the MAGAT format (version 1) and, so that the published R7615 file can be used before it
 * is converted, the thesis tree's {@code compute.*} format. Keys are otherwise identical: a profile's
 * three weights are {@code profile.<arm>@<budget>.{wAdvance,wCopy,wEval}}.
 */
public final class Weights {

    /**
     * The fixed weights used when no file is installed, and the weights every portfolio capture is
     * priced with: {@code advance 1, copy 0.16, eval 0.077}, the thesis tree's compiled defaults.
     * A portfolio must not depend on a calibration, or a recalibration would silently rebuild it.
     */
    public static final double[] DEFAULT = {1.0, 0.16, 0.077};

    public static final String[] OPS = {"wAdvance", "wCopy", "wEval"};
    public static final String[] NANO_KEYS = {"advanceNanos", "copyNanos", "evalNanos"};

    private final Path source;
    private final Map<String, String> header;          // every non-profile key, verbatim
    private final double[] global;
    private final Map<String, double[]> profiles;      // name -> {wAdvance, wCopy, wEval}
    private final Map<String, double[]> nanos;         // name -> {advanceNanos, copyNanos, evalNanos} (NaN when absent)

    private Weights(Path source, Map<String, String> header, double[] global,
                    Map<String, double[]> profiles, Map<String, double[]> nanos) {
        this.source = source;
        this.header = Collections.unmodifiableMap(header);
        this.global = global;
        this.profiles = Collections.unmodifiableMap(profiles);
        this.nanos = Collections.unmodifiableMap(nanos);
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
        Map<String, double[]> profiles = new TreeMap<>();
        Map<String, double[]> nanos = new TreeMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey();
            String profilePrefix = k.startsWith("profile.") ? "profile."
                    : k.startsWith("compute.profile.") ? "compute.profile." : null;
            if (profilePrefix == null) {
                header.put(normaliseHeaderKey(k), e.getValue());
                continue;
            }
            String rest = k.substring(profilePrefix.length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) continue;
            String name = rest.substring(0, dot);
            String op = rest.substring(dot + 1);
            double v = parseDouble(e.getValue());
            int opIdx = indexOf(OPS, op);
            if (opIdx >= 0) {
                profiles.computeIfAbsent(name, n -> new double[]{Double.NaN, Double.NaN, Double.NaN})[opIdx] = v;
                continue;
            }
            int nanoIdx = indexOf(NANO_KEYS, op);
            if (nanoIdx >= 0) {
                nanos.computeIfAbsent(name, n -> new double[]{Double.NaN, Double.NaN, Double.NaN})[nanoIdx] = v;
            }
        }
        // A profile is only usable when all three weights are present and plausible.
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, double[]> e : profiles.entrySet()) {
            double[] w = e.getValue();
            for (double x : w) if (!(x > 0 && x < 1000)) { incomplete.add(e.getKey()); break; }
        }
        for (String n : incomplete) profiles.remove(n);

        double[] global = new double[]{
                headerDouble(header, "global.wAdvance", DEFAULT[0]),
                headerDouble(header, "global.wCopy", DEFAULT[1]),
                headerDouble(header, "global.wEval", DEFAULT[2])};
        return new Weights(source, header, global, profiles, nanos);
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

    /** A header weight: plausible only in (0, 1000), like a profile weight. */
    private static double headerDouble(Map<String, String> h, String key, double dflt) {
        String v = h.get(key);
        if (v == null) return dflt;
        double d = parseDouble(v);
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

    /** The global fallback weights: the reference cell's, or {@link #DEFAULT} if the file has none. */
    public double[] global() { return global.clone(); }

    public boolean has(String profile) { return profiles.containsKey(profile); }

    /** The three weights of a profile, as {@code {wAdvance, wCopy, wEval}}, or {@code null}. */
    public double[] profile(String name) {
        double[] w = profiles.get(name);
        return w == null ? null : w.clone();
    }

    /** Per-operation nanoseconds of a profile ({@code NaN} where the file does not carry them). */
    public double[] nanos(String name) {
        double[] n = nanos.get(name);
        return n == null ? new double[]{Double.NaN, Double.NaN, Double.NaN} : n.clone();
    }

    public Set<String> profiles() { return profiles.keySet(); }

    /** The profiles among {@code wanted} that this file does not price, in the given order. */
    public List<String> missing(Collection<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) if (!has(w)) out.add(w);
        return out;
    }

    /** One line for banners. */
    public String summary() {
        return String.format(Locale.ROOT, "%s: %d profiles, reference %s (%.1f ns/advance), global %.4f/%.4f/%.4f%s",
                source == null ? "(in-memory)" : source.getFileName(), profiles.size(),
                reference(), referenceAdvanceNanos(), global[0], global[1], global[2],
                "true".equals(header.get("toy")) ? "   TOY CALIBRATION, not a currency" : "");
    }
}
