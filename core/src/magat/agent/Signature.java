package magat.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything an equivalence gate compares between a ported arm and its oracle: the two values, the
 * search and initialization spend as raw counts, the node and unique-sequence counts, and the priced
 * units. Values and units are compared to four decimals, as every turns file writes them; counts
 * exactly. An oracle may also carry telemetry; every key it carries that the candidate also reports is
 * compared, to four decimals.
 */
public final class Signature {

    public final double initial, finalValue;
    public final long initAdvances, initCopies, initEvals;
    public final long advances, copies, evals;
    public final long nodes, unique;
    public final double units;
    public final Map<String, Double> telemetry;

    public Signature(double initial, double finalValue,
                     long initAdvances, long initCopies, long initEvals,
                     long advances, long copies, long evals,
                     long nodes, long unique, double units) {
        this(initial, finalValue, initAdvances, initCopies, initEvals, advances, copies, evals, nodes, unique, units,
                Collections.emptyMap());
    }

    public Signature(double initial, double finalValue,
                     long initAdvances, long initCopies, long initEvals,
                     long advances, long copies, long evals,
                     long nodes, long unique, double units, Map<String, Double> telemetry) {
        this.initial = initial; this.finalValue = finalValue;
        this.initAdvances = initAdvances; this.initCopies = initCopies; this.initEvals = initEvals;
        this.advances = advances; this.copies = copies; this.evals = evals;
        this.nodes = nodes; this.unique = unique; this.units = units;
        this.telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
    }

    public static Signature of(TurnRecord r) {
        return new Signature(r.initial, r.finalValue, r.initAdvances, r.initCopies, r.initEvals,
                r.advances, r.copies, r.evals, r.nodes, r.unique, r.units, r.telemetry);
    }

    /**
     * Differences between this signature (the candidate) and {@code oracle}, as human-readable lines;
     * empty when they agree. {@code oracleDeadInitCopies} is the number of initialization copies the
     * oracle makes that have no effect and the port does not make (see the oracle's documentation).
     */
    public List<String> differences(Signature oracle, int oracleDeadInitCopies) {
        List<String> out = new ArrayList<>();
        cmp(out, "initial", TurnRecord.round4(initial), TurnRecord.round4(oracle.initial));
        cmp(out, "final", TurnRecord.round4(finalValue), TurnRecord.round4(oracle.finalValue));
        cmp(out, "initAdvances", initAdvances, oracle.initAdvances);
        cmp(out, "initCopies(+dead " + oracleDeadInitCopies + ")", initCopies + oracleDeadInitCopies, oracle.initCopies);
        cmp(out, "initEvals", initEvals, oracle.initEvals);
        cmp(out, "advances", advances, oracle.advances);
        cmp(out, "copies", copies, oracle.copies);
        cmp(out, "evals", evals, oracle.evals);
        cmp(out, "nodes", nodes, oracle.nodes);
        cmp(out, "unique", unique, oracle.unique);
        cmp(out, "units", TurnRecord.round4(units), TurnRecord.round4(oracle.units));
        for (Map.Entry<String, Double> e : oracle.telemetry.entrySet()) {
            Double mine = telemetry.get(e.getKey());
            if (mine == null) out.add("telemetry " + e.getKey() + ": the candidate does not report it");
            else cmp(out, "telemetry " + e.getKey(), TurnRecord.round4(mine), TurnRecord.round4(e.getValue()));
        }
        return out;
    }

    private static void cmp(List<String> out, String what, double a, double b) {
        if (Double.compare(a, b) != 0) out.add(what + ": candidate " + a + " vs oracle " + b);
    }

    private static void cmp(List<String> out, String what, long a, long b) {
        if (a != b) out.add(what + ": candidate " + a + " vs oracle " + b);
    }

    /** One line for the gate's report. */
    public String line() {
        return String.format(Locale.ROOT, "init=%s final=%s fm=%d nodes=%d unique=%d units=%s",
                TurnRecord.round4(initial), TurnRecord.round4(finalValue), initAdvances + advances,
                nodes, unique, TurnRecord.round4(units));
    }

    @Override
    public String toString() {
        return line() + String.format(Locale.ROOT, " [search a/c/e=%d/%d/%d init a/c/e=%d/%d/%d]",
                advances, copies, evals, initAdvances, initCopies, initEvals);
    }
}
