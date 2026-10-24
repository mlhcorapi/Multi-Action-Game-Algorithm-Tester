package magat.meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Deterministic per-thread compute accounting: the count of every priced operation and, when
 * {@link #PROFILE} is on, its nanoseconds. Everything is thread-local, so one JVM can run many
 * differently-priced turns at once.
 *
 * <p><b>Two kinds of operation.</b> The three forward-model operations ({@code advance}, {@code copy},
 * {@code evaluate}) are counted and timed by {@link magat.game.MeteredGame}, never by an engine and never
 * by an algorithm: they are the anchor nobody can under-report. An algorithm's own work (table updates,
 * lookups, ranking, tree bookkeeping) is declared by its {@link magat.agent.AgentFactory} as named
 * operations and recorded by the algorithm through an {@link Op} handle:
 * <pre>
 *   static final Meter.Op UPDATE = Meter.register("qd.update");
 *   UPDATE.count();                                          // one occurrence, untimed
 *   try (Meter.Span s = RANK.span()) { ... }                 // one occurrence, timed while profiling
 *   try (Meter.Span s = LOOKUP.span(n)) { ...n lookups... }  // n occurrences timed as one batch
 * </pre>
 * Names are lowercase dot-separated segments, the first naming the algorithm family. A handle maps its
 * name to a dense index once, at registration, so the hot path is an array increment and allocates
 * nothing. A span's time is its own: forward-model calls and spans nested inside it are subtracted, so
 * no nanosecond is priced twice.
 *
 * <p><b>Pricing.</b> A unit is {@code sum over operations of count * weight}, priced by the profile
 * installed on the calling thread with {@link #useProfile}. Profiles come from a {@link Weights} file
 * installed once per JVM with {@link #install}; there is deliberately no default file path. A version-1
 * file prices the three forward-model operations only, so an algorithm's own operations weigh exactly
 * 0.0 under it and every unit is the double it always was; a version-2 file prices every operation.
 *
 * <p>Extracted from the thesis tree's {@code core/game/ComputeMeter.java}; the forward-model counting
 * and pricing semantics are unchanged.
 */
public final class Meter {

    private Meter() {}

    /** Opt-in nanosecond profiling, for calibration only. Global, because it is a mode of the JVM. */
    public static volatile boolean PROFILE = false;

    /** The most operations one JVM can register: the three forward-model ones and the algorithms' own. */
    public static final int CAPACITY = 64;
    public static final int ADVANCE = 0, COPY = 1, EVAL = 2, FORWARD_MODEL = 3;
    /** The forward-model operations' names in index order: what a version-2 weights file calls them. */
    public static final List<String> FORWARD_MODEL_OPS = Collections.unmodifiableList(Arrays.asList("advance", "copy", "eval"));
    /** An algorithm operation's name: lowercase dot-separated segments, the first naming the algorithm family. */
    public static final Pattern OP_NAME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+");
    private static final int MAX_DEPTH = 32;

    private static final String[] NAMES = new String[CAPACITY];
    private static final Map<String, Op> REGISTERED = new ConcurrentHashMap<>();
    private static volatile int opCount = FORWARD_MODEL;

    static {
        for (int i = 0; i < FORWARD_MODEL; i++) NAMES[i] = FORWARD_MODEL_OPS.get(i);
    }

    /** The weights of a JVM that has installed no file: the fixed capture weights of {@link Weights#DEFAULT}. */
    private static volatile Weights weights = null;
    private static volatile boolean allowUnpriced = false;

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    // ------------------------------------------------------------------ operations

    /**
     * An algorithm's named operation. Obtained once, with {@link #register}, and kept in a static
     * field: recording through it is an array increment on the calling thread's counters.
     */
    public static final class Op {
        public final String name;
        final int index;

        private Op(String name, int index) {
            this.name = name;
            this.index = index;
        }

        /** One occurrence, untimed: priced at the per-occurrence cost learned from this operation's spans. */
        public void count() {
            STATE.get().counts[index]++;
        }

        /** {@code n} occurrences, untimed. */
        public void count(long n) {
            STATE.get().counts[index] += n;
        }

        /** One occurrence, timed while profiling: {@code try (Meter.Span s = OP.span()) { ... }}. */
        public Span span() {
            return span(1);
        }

        /**
         * {@code n} occurrences timed as one batch: for an operation too cheap to time one call at a time
         * (a lookup of tens of nanoseconds would be mostly timer), wrap the loop that performs it n times.
         */
        public Span span(long n) {
            State s = STATE.get();
            s.counts[index] += n;
            if (!PROFILE) return Span.NONE;
            if (s.depth >= MAX_DEPTH) throw new IllegalStateException("operation spans nested deeper than " + MAX_DEPTH);
            Span sp = s.stack[s.depth++];
            sp.op = index;
            sp.n = n;
            sp.child = 0;
            sp.start = System.nanoTime();
            return sp;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A timed stretch of one operation, closed by try-with-resources. Its nanoseconds are its own: the
     * forward-model calls and the spans nested inside it are subtracted. Pre-allocated per thread, so
     * timing allocates nothing; spans close innermost first, as try-with-resources guarantees.
     */
    public static final class Span implements AutoCloseable {
        static final Span NONE = new Span(null);
        private final State owner;
        int op;
        long n, start, child;

        private Span(State owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner == null) return;
            long end = System.nanoTime();
            State s = owner;
            if (s.depth == 0 || s.stack[s.depth - 1] != this)
                throw new IllegalStateException("operation spans must close innermost first");
            s.depth--;
            long elapsed = end - start;
            s.nanos[op] += elapsed - child;
            s.timed[op] += n;
            if (s.depth > 0) s.stack[s.depth - 1].child += elapsed;
        }
    }

    /**
     * Registers an algorithm operation and returns its handle; idempotent, so the factory that declares
     * an operation and the agent that records it may both register it.
     *
     * @throws IllegalArgumentException if the name breaks the naming rule
     */
    public static Op register(String name) {
        Op o = REGISTERED.get(name == null ? "" : name);
        if (o != null) return o;
        synchronized (REGISTERED) {
            o = REGISTERED.get(name == null ? "" : name);
            if (o != null) return o;
            if (name == null || !OP_NAME.matcher(name).matches())
                throw new IllegalArgumentException("operation name '" + name + "': lowercase dot-separated segments, the first "
                        + "naming the algorithm family (qd.update, emcts.legal); the forward-model operations are metered by MeteredGame");
            int idx = opCount;
            if (idx >= CAPACITY) throw new IllegalStateException("more than " + (CAPACITY - FORWARD_MODEL) + " algorithm operations registered");
            NAMES[idx] = name;
            o = new Op(name, idx);
            REGISTERED.put(name, o);
            opCount = idx + 1;          // the volatile write publishes NAMES[idx]
            return o;
        }
    }

    /** Registers every name of a declaration (an {@code AgentFactory}'s operations). */
    public static void declare(List<String> names) {
        for (String n : names) register(n);
    }

    /** A registered operation's handle. @throws IllegalStateException if nothing registered it */
    public static Op operation(String name) {
        Op o = REGISTERED.get(name);
        if (o == null) throw new IllegalStateException("operation '" + name + "' is not registered; its AgentFactory declares it");
        return o;
    }

    /** One occurrence of a registered operation, untimed (the spelled-out form of {@code OP.count()}). */
    public static void op(String name) {
        operation(name).count();
    }

    /** A timed span of a registered operation (the spelled-out form of {@code OP.span()}). */
    public static Span span(String name) {
        return operation(name).span();
    }

    /** Every registered algorithm operation, in registration order. */
    public static List<String> operations() {
        int n = opCount;
        List<String> out = new ArrayList<>(n - FORWARD_MODEL);
        for (int i = FORWARD_MODEL; i < n; i++) out.add(NAMES[i]);
        return out;
    }

    /** Operations registered so far, the three forward-model ones included (dense indices 0..n-1). */
    public static int operationCount() {
        return opCount;
    }

    /** The name of the operation at a dense index. */
    public static String name(int index) {
        return NAMES[index];
    }

    /** The dense index of an operation (forward-model or registered), or -1. */
    public static int index(String name) {
        int fm = FORWARD_MODEL_OPS.indexOf(name);
        if (fm >= 0) return fm;
        Op o = REGISTERED.get(name);
        return o == null ? -1 : o.index;
    }

    // ------------------------------------------------------------------ the per-thread state

    private static final class State {
        final long[] counts = new long[CAPACITY];
        final long[] timed = new long[CAPACITY];
        final long[] nanos = new long[CAPACITY];
        final double[] weights = new double[CAPACITY];
        final boolean[] priced = new boolean[CAPACITY];
        String profile;           // null: the global weights
        boolean strict;           // a file's profile: every counted operation must be priced
        final Span[] stack = new Span[MAX_DEPTH];
        int depth;

        State() {
            for (int i = 0; i < MAX_DEPTH; i++) stack[i] = new Span(this);
            useGlobal();
        }

        void useGlobal() {
            Arrays.fill(weights, 0.0);
            Arrays.fill(priced, false);
            Weights w = Meter.weights;
            if (w == null) {
                for (int i = 0; i < FORWARD_MODEL; i++) weights[i] = Weights.DEFAULT[i];
            } else {
                w.fillGlobal(weights, priced);
            }
            for (int i = 0; i < FORWARD_MODEL; i++) priced[i] = true;
            profile = null;
            strict = false;
        }
    }

    // ------------------------------------------------------------------ weights

    /** Installs the weights every later {@link #useProfile} resolves against. {@code null} uninstalls. */
    public static synchronized void install(Weights w) {
        weights = w;
    }

    /** The installed weights, or {@code null}. */
    public static Weights installed() {
        return weights;
    }

    /**
     * Lets {@link #useProfile} fall back to the global weights for a profile that is not priced,
     * instead of failing. For smoke runs and probes at odd budgets only; a production runner never
     * sets it, because an unpriced arm spends a different currency and nothing in a CSV says so.
     */
    public static void allowUnpriced(boolean allow) {
        allowUnpriced = allow;
    }

    /**
     * Installs a profile's weights on the calling thread for the planning episode about to start.
     * {@code null} installs the global weights (the reference cell's, or {@link Weights#DEFAULT} when
     * no file is installed).
     *
     * @throws IllegalStateException if the profile is not priced and {@link #allowUnpriced} is off
     */
    public static void useProfile(String profile) {
        State s = STATE.get();
        if (profile == null) {
            s.useGlobal();
            return;
        }
        Weights file = weights;
        if (file == null || !file.has(profile)) {
            if (!allowUnpriced) {
                throw new IllegalStateException("profile '" + profile + "' is not priced"
                        + (file == null ? " (no weights file installed)" : " in " + file.source())
                        + "; calibrate it, or pass --allow-unpriced for a smoke run");
            }
            s.useGlobal();
            return;
        }
        Arrays.fill(s.weights, 0.0);
        Arrays.fill(s.priced, false);
        file.fill(profile, s.weights, s.priced);
        s.profile = profile;
        s.strict = true;
    }

    /**
     * Installs an explicit forward-model weight triple on the calling thread under a label; every
     * algorithm operation weighs 0 under it. For probes that must price both sides of a comparison
     * identically at a budget no file prices (the oracle gate, the structure check); a run never uses it.
     */
    public static void useWeights(double[] w, String label) {
        State s = STATE.get();
        Arrays.fill(s.weights, 0.0);
        Arrays.fill(s.priced, false);
        for (int i = 0; i < FORWARD_MODEL; i++) {
            s.weights[i] = w[i];
            s.priced[i] = true;
        }
        s.profile = label;
        s.strict = false;
    }

    /** Profile in force on this thread, or {@code "global"}. */
    public static String activeProfile() {
        String n = STATE.get().profile;
        return n == null ? "global" : n;
    }

    /** The forward-model weights in force on this thread, as {@code {advance, copy, eval}}. */
    public static double[] activeWeights() {
        return Arrays.copyOf(STATE.get().weights, FORWARD_MODEL);
    }

    /** The weight in force for one operation on this thread (0 for an operation the profile does not price). */
    public static double activeWeight(String op) {
        int i = index(op);
        return i < 0 ? 0.0 : STATE.get().weights[i];
    }

    /**
     * Operations counted on this thread since the last {@link #reset} (or among {@code extra}) that the
     * profile in force does not price; empty under the global weights or a probe's triple, which price the
     * forward model only by definition, and empty while
     * {@link #allowUnpriced} is on (the calibrator measures arms whose operations are not priced yet).
     */
    public static List<String> unpricedCounted(long[] extra) {
        State s = STATE.get();
        List<String> out = new ArrayList<>();
        if (!s.strict || allowUnpriced) return out;
        int n = opCount;
        for (int i = FORWARD_MODEL; i < n; i++)
            if (!s.priced[i] && (s.counts[i] > 0 || (extra != null && i < extra.length && extra[i] > 0))) out.add(NAMES[i]);
        return out;
    }

    // ------------------------------------------------------------------ counting (the forward model, via MeteredGame)

    public static void onAdvance() { STATE.get().counts[ADVANCE]++; }
    public static void onCopy()    { STATE.get().counts[COPY]++; }
    public static void onEval()    { STATE.get().counts[EVAL]++; }

    public static void addAdvanceNanos(long n) { addNanos(ADVANCE, n); }
    public static void addCopyNanos(long n)    { addNanos(COPY, n); }
    public static void addEvalNanos(long n)    { addNanos(EVAL, n); }

    /** A timed forward-model call: its own nanoseconds, and none of them in the span around it. */
    private static void addNanos(int op, long n) {
        State s = STATE.get();
        s.nanos[op] += n;
        s.timed[op]++;
        if (s.depth > 0) s.stack[s.depth - 1].child += n;
    }

    /** Zero the counts (not the nanoseconds) on this thread. */
    public static void reset() {
        Arrays.fill(STATE.get().counts, 0, opCount, 0L);
    }

    /** Zero the nanosecond accumulators, and the occurrences they cover, on this thread. */
    public static void resetNanos() {
        State s = STATE.get();
        int n = opCount;
        Arrays.fill(s.nanos, 0, n, 0L);
        Arrays.fill(s.timed, 0, n, 0L);
    }

    public static long advances() { return STATE.get().counts[ADVANCE]; }
    public static long copies()   { return STATE.get().counts[COPY]; }
    public static long evals()    { return STATE.get().counts[EVAL]; }
    public static long advanceNanos() { return STATE.get().nanos[ADVANCE]; }
    public static long copyNanos()    { return STATE.get().nanos[COPY]; }
    public static long evalNanos()    { return STATE.get().nanos[EVAL]; }

    /** Occurrences of an operation (by dense index) on this thread since the last {@link #reset}. */
    public static long count(int index) { return STATE.get().counts[index]; }
    /** Nanoseconds timed for an operation since the last {@link #resetNanos}. */
    public static long nanos(int index) { return STATE.get().nanos[index]; }
    /** Occurrences of an operation those nanoseconds cover (an untimed {@code count()} adds none). */
    public static long timed(int index) { return STATE.get().timed[index]; }

    /** Every operation's count on this thread, dense, length {@link #operationCount}. */
    public static long[] counts() {
        return Arrays.copyOf(STATE.get().counts, opCount);
    }

    /** Units spent on this thread since the last {@link #reset}, priced by the profile in force. */
    public static double units() {
        State s = STATE.get();
        long[] c = s.counts;
        double[] w = s.weights;
        double u = c[ADVANCE] * w[ADVANCE] + c[COPY] * w[COPY] + c[EVAL] * w[EVAL];
        int n = opCount;
        for (int i = FORWARD_MODEL; i < n; i++) u += c[i] * w[i];
        return u;
    }

    /** What {@code advances}, {@code copies} and {@code evals} would cost under the weights in force. */
    public static double price(long advances, long copies, long evals) {
        double[] w = STATE.get().weights;
        return advances * w[ADVANCE] + copies * w[COPY] + evals * w[EVAL];
    }
}
