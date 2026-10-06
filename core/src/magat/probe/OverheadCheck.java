package magat.probe;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.calibrate.Estimator;
import magat.game.EngineCounters;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * {@code magat check overhead}: the two costs the metering design adds, measured rather than assumed
 * (DESIGN §8.4).
 *
 * <ol>
 *   <li><b>The decorator.</b> The same turns, timed once through {@code MeteredGame} (the meter's own
 *       timing around each call) and once by the engine's own hooks inside the engine, interleaved
 *       turn by turn with the order alternating, single-threaded. Per-operation nanoseconds by the fastest repetition; the
 *       difference is what wrapping costs per operation (dispatch, the count, the timing call).</li>
 *   <li><b>The dormant engine counters.</b> The engine still counts every operation into its own meter
 *       while MAGAT meters through the contract. That hook alone, timed over many calls, as a share of
 *       an advance.</li>
 * </ol>
 * Both are expected to be within noise; the point is that they are measured. Writes nothing.
 */
public final class OverheadCheck {

    private OverheadCheck() {}

    public static final class Result {
        public final double[] decorator = new double[3], engine = new double[3];
        public double hookNanos;
        public boolean supported;
    }

    public static <S, A> Result run(Game<S, A> game, AgentFactory<S, A> arm, long budget, List<Scenario<S>> positions, int reps, int warmup) {
        Result out = new Result();
        if (!(game instanceof EngineCounters) || !((EngineCounters) game).engineTiming(false)) {
            System.out.println("  the game's engine exposes no timing of its own; nothing to compare");
            return out;
        }
        out.supported = true;
        EngineCounters engine = (EngineCounters) game;
        try {
            for (int i = 0; i < warmup; i++) {           // both timed paths, so neither is measured cold
                Meter.PROFILE = true;
                Episode.run(game, positions.get(i % positions.size()), 9_000 + i, arm, budget);
                Meter.PROFILE = false;
                engine.engineTiming(true);
                Episode.run(game, positions.get(i % positions.size()), 9_000 + i, arm, budget);
                engine.engineTiming(false);
            }
            List<double[]> dec = new ArrayList<>(), eng = new ArrayList<>();
            for (int rep = 0; rep < reps; rep++) {
                long[] counts = new long[3], nanos = new long[3], eCounts = new long[3], eNanos = new long[3];
                for (int t = 0; t < positions.size(); t++) {
                    // Turn by turn, alternating which timer goes first, so both see the same machine:
                    // timing all of one and then all of the other measures a laptop warming up.
                    boolean decoratorFirst = (rep + t) % 2 == 0;
                    for (int pass = 0; pass < 2; pass++) {
                        Scenario<S> sc = positions.get(t);
                        if ((pass == 0) == decoratorFirst) {
                            Meter.resetNanos();
                            Meter.PROFILE = true;
                            TurnRecord r = Episode.run(game, sc, 0, arm, budget);
                            Meter.PROFILE = false;
                            counts[0] += r.initAdvances + r.advances;
                            counts[1] += r.initCopies + r.copies;
                            counts[2] += r.initEvals + r.evals;
                            nanos[0] += Meter.advanceNanos();
                            nanos[1] += Meter.copyNanos();
                            nanos[2] += Meter.evalNanos();
                        } else {
                            engine.resetEngineNanos();
                            engine.resetEngineCounts();
                            engine.engineTiming(true);
                            Episode.run(game, sc, 0, arm, budget);
                            engine.engineTiming(false);
                            long[] c = engine.engineCounts(), n = engine.engineNanos();
                            for (int i = 0; i < 3; i++) { eCounts[i] += c[i]; eNanos[i] += n[i]; }
                        }
                    }
                }
                dec.add(perOp(nanos, counts));
                eng.add(perOp(eNanos, eCounts));
            }
            for (int i = 0; i < 3; i++) {
                out.decorator[i] = Estimator.fastest(column(dec, i));
                out.engine[i] = Estimator.fastest(column(eng, i));
            }
            long calls = 20_000_000L;
            engine.dormantCounterNanos(calls / 10);          // warm the loop
            out.hookNanos = engine.dormantCounterNanos(calls) / (double) calls;
        } finally {
            Meter.PROFILE = false;
            engine.engineTiming(false);
        }
        String[] ops = {"advance", "copy", "eval"};
        System.out.println(String.format(Locale.ROOT, "  %-8s %14s %14s %10s", "op", "decorator ns", "engine ns", "overhead"));
        for (int i = 0; i < 3; i++)
            System.out.println(String.format(Locale.ROOT, "  %-8s %14.1f %14.1f %9.2f%%", ops[i], out.decorator[i], out.engine[i],
                    100 * (out.decorator[i] / out.engine[i] - 1)));
        // Weights are ratios to the advance, so what the timing method does to a currency is the
        // change in those ratios, not the absolute difference.
        System.out.println(String.format(Locale.ROOT, "  effect on a currency calibrated through the decorator rather than the engine's hooks: "
                        + "wCopy %+.2f%%, wEval %+.2f%%",
                100 * ((out.decorator[1] / out.decorator[0]) / (out.engine[1] / out.engine[0]) - 1),
                100 * ((out.decorator[2] / out.decorator[0]) / (out.engine[2] / out.engine[0]) - 1)));
        System.out.println(String.format(Locale.ROOT, "  dormant engine counter: %.2f ns per operation = %.4f%% of an advance",
                out.hookNanos, 100 * out.hookNanos / out.decorator[0]));
        return out;
    }

    // ------------------------------------------------------------------ the audit

    /** {@code --audit}: warn above this share of a turn that no timed operation accounts for. */
    public static final double AUDIT_WARN = 0.10;
    /** {@code --audit}: fail above this share. */
    public static final double AUDIT_FAIL = 0.25;

    /** One arm's audit: its fastest repetition's turn time and the shares of it each kind of operation accounts for. */
    public static final class Audit {
        public final String arm;
        public double turnMillis, forwardModel, operations, unaccounted;
        public final Map<String, Double> shares = new LinkedHashMap<>();

        Audit(String arm) { this.arm = arm; }
    }

    /**
     * Times each arm's turns with every operation profiled and attributes the turn time: to the forward
     * model, to each operation the arm declares, and to nothing (what the arm does without declaring it).
     * Single-threaded, so the shares describe the arm and not the machine's contention; priced with the
     * fixed capture weights, since a share does not depend on a currency. The fastest repetition counts.
     */
    public static <S, A> List<Audit> audit(Game<S, A> game, List<AgentFactory<S, A>> arms, long budget, List<Scenario<S>> positions,
                                           int reps, int warmup) {
        List<Audit> out = new ArrayList<>();
        try {
            for (AgentFactory<S, A> arm : arms) {
                List<String> ops = arm.operations();
                Meter.declare(ops);
                Meter.PROFILE = true;
                for (int i = 0; i < warmup; i++)
                    Episode.run(game, positions.get(i % positions.size()), 9_000 + i, arm, budget, Weights.DEFAULT, "audit");
                Audit best = null;
                double bestWall = Double.POSITIVE_INFINITY;
                for (int rep = 0; rep < reps; rep++) {
                    long wall = 0, fm = 0;
                    long[] opNs = new long[ops.size()];
                    for (Scenario<S> sc : positions) {
                        Meter.resetNanos();
                        long t0 = System.nanoTime();
                        Episode.run(game, sc, 0, arm, budget, Weights.DEFAULT, "audit");
                        wall += System.nanoTime() - t0;
                        fm += Meter.advanceNanos() + Meter.copyNanos() + Meter.evalNanos();
                        for (int j = 0; j < opNs.length; j++) opNs[j] += Meter.nanos(Meter.index(ops.get(j)));
                    }
                    if (wall < bestWall) {
                        bestWall = wall;
                        best = new Audit(arm.id());
                        best.turnMillis = wall / 1e6 / positions.size();
                        best.forwardModel = fm / (double) wall;
                        long all = 0;
                        for (int j = 0; j < opNs.length; j++) {
                            best.shares.put(ops.get(j), opNs[j] / (double) wall);
                            all += opNs[j];
                        }
                        best.operations = all / (double) wall;
                        best.unaccounted = 1 - (fm + all) / (double) wall;
                    }
                }
                out.add(best);
            }
        } finally {
            Meter.PROFILE = false;
        }
        return out;
    }

    /** Prints the audit and says whether every arm is within {@code fail}. */
    public static boolean report(List<Audit> audits, double warn, double fail) {
        boolean ok = true;
        System.out.println(String.format(Locale.ROOT, "  %-28s %10s %10s %10s %12s   %s", "arm", "turn ms", "fwd model", "own ops",
                "unaccounted", "largest own operations"));
        for (Audit a : audits) {
            List<Map.Entry<String, Double>> top = new ArrayList<>(a.shares.entrySet());
            top.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, top.size()); i++)
                sb.append(String.format(Locale.ROOT, "%s%s %.1f%%", i > 0 ? ", " : "", top.get(i).getKey(), 100 * top.get(i).getValue()));
            String verdict = a.unaccounted > fail ? "   <-- FAIL" : a.unaccounted > warn ? "   <-- warn" : "";
            if (a.unaccounted > fail) ok = false;
            System.out.println(String.format(Locale.ROOT, "  %-28s %10.1f %9.1f%% %9.1f%% %11.1f%%   %s%s", a.arm, a.turnMillis,
                    100 * a.forwardModel, 100 * a.operations, 100 * a.unaccounted, sb.length() == 0 ? "(declares none)" : sb, verdict));
        }
        return ok;
    }

    private static double[] perOp(long[] nanos, long[] counts) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = counts[i] > 0 ? nanos[i] / (double) counts[i] : 0;
        return out;
    }

    private static double[] column(List<double[]> rows, int i) {
        double[] out = new double[rows.size()];
        for (int r = 0; r < out.length; r++) out[r] = rows.get(r)[i];
        return out;
    }
}
