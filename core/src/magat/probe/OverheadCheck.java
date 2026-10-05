package magat.probe;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.calibrate.Estimator;
import magat.game.EngineCounters;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Meter;

import java.util.ArrayList;
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
