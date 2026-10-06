package magat.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one planned turn produced: the values, the spend (raw counts always, units as priced by the
 * profile in force), the diversity counters, the anytime curve, and any agent-defined telemetry.
 * This is one row of {@code turns.csv} plus the rows of {@code curve.csv}.
 *
 * <p>The forward model's counts are split into the search ({@code advances}, ...) and the
 * initialization ({@code initAdvances}, ...); so are the arm's declared algorithm operations
 * ({@link #operations}, {@link #initOperations}), which {@link Episode} attaches after the turn.
 */
public final class TurnRecord {

    /** One anytime checkpoint: the best value so far and the spend at that point. */
    public static final class Checkpoint {
        public final int index;
        public final double best;
        public final long advances;
        public final double units;
        public final long millis;

        public Checkpoint(int index, double best, long advances, double units, long millis) {
            this.index = index; this.best = best; this.advances = advances; this.units = units; this.millis = millis;
        }
    }

    public final double initial;
    public final double finalValue;
    public final boolean terminal;
    public final double avgNode;
    public final long advances, copies, evals;
    public final double units;
    public final long initAdvances, initCopies, initEvals;
    public final double initUnits;
    public final long planMillis, initMillis;
    public final long nodes, unique, duplicates;
    public final int sequenceLength;
    public final List<Checkpoint> curve;
    public final Map<String, Double> telemetry;
    /** The arm's declared algorithm operations, counted over the search, in declaration order. */
    public final Map<String, Long> operations;
    /** The same operations, counted over the initialization (outside the budget, inside {@code initUnits}). */
    public final Map<String, Long> initOperations;

    private TurnRecord(Builder b) {
        this.initial = b.initial; this.finalValue = b.finalValue; this.terminal = b.terminal;
        this.avgNode = b.avgNode;
        this.advances = b.advances; this.copies = b.copies; this.evals = b.evals; this.units = b.units;
        this.initAdvances = b.initAdvances; this.initCopies = b.initCopies; this.initEvals = b.initEvals;
        this.initUnits = b.initUnits;
        this.planMillis = b.planMillis; this.initMillis = b.initMillis;
        this.nodes = b.nodes; this.unique = b.unique; this.duplicates = b.duplicates;
        this.sequenceLength = b.sequenceLength;
        this.curve = Collections.unmodifiableList(new ArrayList<>(b.curve));
        this.telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(b.telemetry));
        this.operations = Collections.emptyMap();
        this.initOperations = Collections.emptyMap();
    }

    private TurnRecord(TurnRecord r, Map<String, Long> operations, Map<String, Long> initOperations) {
        this.initial = r.initial; this.finalValue = r.finalValue; this.terminal = r.terminal; this.avgNode = r.avgNode;
        this.advances = r.advances; this.copies = r.copies; this.evals = r.evals; this.units = r.units;
        this.initAdvances = r.initAdvances; this.initCopies = r.initCopies; this.initEvals = r.initEvals;
        this.initUnits = r.initUnits;
        this.planMillis = r.planMillis; this.initMillis = r.initMillis;
        this.nodes = r.nodes; this.unique = r.unique; this.duplicates = r.duplicates;
        this.sequenceLength = r.sequenceLength;
        this.curve = r.curve;
        this.telemetry = r.telemetry;
        this.operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
        this.initOperations = Collections.unmodifiableMap(new LinkedHashMap<>(initOperations));
    }

    /** This record with the arm's algorithm-operation counts attached ({@link Episode} does it after the turn). */
    public TurnRecord withOperations(Map<String, Long> operations, Map<String, Long> initOperations) {
        return new TurnRecord(this, operations, initOperations);
    }

    /** Search gain: what the search added over the starting plan. */
    public double gain() {
        return finalValue - initial;
    }

    /** Total forward-model advances, initialization included (the thesis tree's {@code FmCalls}). */
    public long fmCalls() {
        return initAdvances + advances;
    }

    /** Rounded to four decimals, as every thesis-tree CSV was written. */
    public static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** The thesis tree's verify-gate line, for eyeballing against archived logs. */
    public String thesisSignature() {
        return String.format(Locale.ROOT, "init=%s final=%s fm=%d nodes=%d unique=%d units=%s",
                round4(initial), round4(finalValue), fmCalls(), nodes, unique, round4(units));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double initial, finalValue, avgNode, units, initUnits;
        private boolean terminal;
        private long advances, copies, evals, initAdvances, initCopies, initEvals, planMillis, initMillis;
        private long nodes, unique, duplicates;
        private int sequenceLength;
        private final List<Checkpoint> curve = new ArrayList<>();
        private final Map<String, Double> telemetry = new LinkedHashMap<>();

        public Builder values(double initial, double finalValue, boolean terminal, double avgNode) {
            this.initial = initial; this.finalValue = finalValue; this.terminal = terminal; this.avgNode = avgNode;
            return this;
        }

        public Builder search(long advances, long copies, long evals, double units) {
            this.advances = advances; this.copies = copies; this.evals = evals; this.units = units;
            return this;
        }

        public Builder init(long advances, long copies, long evals, double units, long initMillis) {
            this.initAdvances = advances; this.initCopies = copies; this.initEvals = evals;
            this.initUnits = units; this.initMillis = initMillis;
            return this;
        }

        /** Reads the initialization spend from the budget's snapshot, if {@code beginSearch} was called. */
        public Builder init(TurnBudget budget, long initMillis) {
            if (budget.searchBegun())
                return init(budget.initAdvances(), budget.initCopies(), budget.initEvals(), budget.initUnits(), initMillis);
            return init(0, 0, 0, 0.0, initMillis);
        }

        public Builder planMillis(long planMillis) { this.planMillis = planMillis; return this; }

        public Builder diversity(long nodes, long unique, long duplicates) {
            this.nodes = nodes; this.unique = unique; this.duplicates = duplicates;
            return this;
        }

        public Builder sequenceLength(int n) { this.sequenceLength = n; return this; }

        public Builder checkpoint(int index, double best, long advances, double units, long millis) {
            curve.add(new Checkpoint(index, best, advances, units, millis));
            return this;
        }

        public Builder telemetry(String key, double value) { telemetry.put(key, value); return this; }

        public TurnRecord build() {
            return new TurnRecord(this);
        }
    }
}
