package magat.probe;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.WriterConfig;
import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.game.Scenario;
import magat.machine.MachineProfile;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.run.RunGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How long a turn takes, single-threaded, under a currency: what a budget level means in thinking time.
 *
 * <p>A level's units make arms comparable, not positions: a turn's time per unit depends on its position
 * several-fold (the engine's legal-action generation grows with the units on the board). So a level's time
 * is a mean over many positions spread across the portfolio, one turn each, and the spread is reported
 * beside it. Each turn is timed whole, initialization included, without the meter's profiling (as a run
 * plays it). A turn spends its level, its initialization included, so the mean is the level's thinking
 * time on this machine. A cell whose turns spend more than {@link TurnBudget#OVERSPEND} times their level on
 * average is flagged: its time is not the level's.
 *
 * <p>{@code magat calibrate-time} uses it to find the units that make a level a round number of
 * milliseconds on the reference machine; on its own it reports any arms at any levels.
 */
public final class TimeCheck {

    public static final int DEFAULT_POSITIONS = 64;
    public static final int DEFAULT_WARMUP = 6;
    public static final double DEFAULT_TOLERANCE = 0.05;

    private TimeCheck() {}

    /** One arm at one level: every turn's milliseconds, in position order, and what they spent. */
    public static final class Cell {
        public final String arm;
        public final long budget;
        public final double[] ms;
        public final double meanMs, medianMs, q1Ms, q3Ms, minMs, maxMs, meanUnits, meanInitUnits, meanInitMs;
        public double targetMs = Double.NaN;

        Cell(String arm, long budget, double[] ms, double[] units, double[] initUnits, double[] initMs) {
            this.arm = arm;
            this.budget = budget;
            this.ms = ms;
            double[] s = ms.clone();
            Arrays.sort(s);
            meanMs = mean(ms);
            medianMs = quantile(s, 0.5);
            q1Ms = quantile(s, 0.25);
            q3Ms = quantile(s, 0.75);
            minMs = s.length == 0 ? Double.NaN : s[0];
            maxMs = s.length == 0 ? Double.NaN : s[s.length - 1];
            meanUnits = mean(units);
            meanInitUnits = mean(initUnits);
            meanInitMs = mean(initMs);
        }

        /** The mean as a share of the target (1.0 on target); NaN without one. */
        public double ratio() {
            return targetMs > 0 ? meanMs / targetMs : Double.NaN;
        }

        public boolean within(double tolerance) {
            return !(targetMs > 0) || Math.abs(ratio() - 1) <= tolerance;
        }

        /** Milliseconds per thousand units the turns actually spent. */
        public double msPerThousandUnits() {
            return meanUnits > 0 ? 1000 * meanMs / meanUnits : Double.NaN;
        }

        /** The units a turn spent on average, initialization included, as a multiple of the level. */
        public double spend() {
            return budget > 0 ? meanUnits / budget : Double.NaN;
        }

        public boolean overspends() {
            return spend() > TurnBudget.OVERSPEND;
        }
    }

    /**
     * @param targets      milliseconds per level (aligned with {@code levels}), or null
     * @param fixedWeights price every turn by {@link Weights#DEFAULT} (a first guess, no currency needed);
     *                     otherwise by the arm's profile at the level in the installed weights
     */
    public static <S, A> List<Cell> run(Game<S, A> game, List<AgentFactory<S, A>> arms, List<Scenario<S>> portfolio, long[] levels,
                                        double[] targets, int positions, int warmup, boolean fixedWeights) {
        List<Scenario<S>> spread = spread(portfolio, positions);
        List<Cell> out = new ArrayList<>();
        boolean wasProfiling = Meter.PROFILE;
        Meter.PROFILE = false;                        // a run times nothing; neither does this
        try {
            for (int li = 0; li < levels.length; li++) {
                long level = levels[li];
                AgentFactory<S, A> first = arms.get(0);
                for (int i = 0; i < warmup && !RunGuard.stopRequested(); i++)
                    turn(game, portfolio.get((int) ((long) (i * 61 + 7) % portfolio.size())), 9_000 + i, first, level, fixedWeights);
                System.gc();
                for (AgentFactory<S, A> arm : arms) {
                    double[] ms = new double[spread.size()], units = new double[spread.size()];
                    double[] initUnits = new double[spread.size()], initMs = new double[spread.size()];
                    int n = 0;
                    for (Scenario<S> sc : spread) {
                        if (RunGuard.stopRequested()) break;
                        long t0 = System.nanoTime();
                        TurnRecord r = turn(game, sc, 0, arm, level, fixedWeights);
                        ms[n] = (System.nanoTime() - t0) / 1e6;
                        units[n] = r.units + r.initUnits;
                        initUnits[n] = r.initUnits;
                        initMs[n] = r.initMillis;
                        n++;
                    }
                    Cell c = new Cell(arm.id(), level, Arrays.copyOf(ms, n), Arrays.copyOf(units, n), Arrays.copyOf(initUnits, n),
                            Arrays.copyOf(initMs, n));
                    if (targets != null && li < targets.length) c.targetMs = targets[li];
                    out.add(c);
                    System.out.println(row(c));
                }
            }
        } finally {
            Meter.PROFILE = wasProfiling;
        }
        return out;
    }

    private static <S, A> TurnRecord turn(Game<S, A> game, Scenario<S> sc, int visit, AgentFactory<S, A> arm, long level, boolean fixed) {
        return fixed ? Episode.run(game, sc, visit, arm, level, Weights.DEFAULT, "fixed") : Episode.run(game, sc, visit, arm, level);
    }

    /** {@code n} positions spread evenly over the portfolio (all of it when n is larger). */
    static <S> List<Scenario<S>> spread(List<Scenario<S>> portfolio, int n) {
        n = Math.max(1, Math.min(n, portfolio.size()));
        List<Scenario<S>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(portfolio.get((int) ((long) i * portfolio.size() / n)));
        return out;
    }

    public static String header() {
        return String.format(Locale.ROOT, "  %-28s %8s %6s %6s %9s %9s %17s %9s %9s %10s %8s", "cell", "units", "spend", "turns", "mean ms",
                "median", "quartiles", "min-max", "init ms", "ms/1000u", "target");
    }

    static String row(Cell c) {
        return String.format(Locale.ROOT, "  %-28s %8.0f %5.2fx %6d %9.1f %9.1f %8.0f-%-8.0f %4.0f-%-5.0f %8.1f %10.2f %8s%s",
                c.arm + "@" + c.budget, c.meanUnits, c.spend(), c.ms.length, c.meanMs, c.medianMs, c.q1Ms, c.q3Ms, c.minMs, c.maxMs,
                c.meanInitMs, c.msPerThousandUnits(),
                c.targetMs > 0 ? String.format(Locale.ROOT, "%+.1f%%", 100 * (c.ratio() - 1)) : "",
                c.overspends() ? "   <-- spends over its level" : "");
    }

    /** The cells that spend over their level, as {@code arm@budget 1.25x}. */
    public static List<String> overspent(List<Cell> cells) {
        List<String> out = new ArrayList<>();
        for (Cell c : cells)
            if (c.overspends()) out.add(String.format(Locale.ROOT, "%s@%d %.2fx", c.arm, c.budget, c.spend()));
        return out;
    }

    public static JsonObject json(List<Cell> cells, String weights, int positions, String game) {
        JsonArray arr = new JsonArray();
        for (Cell c : cells) {
            JsonObject o = new JsonObject().add("arm", c.arm).add("budget", c.budget).add("turns", c.ms.length)
                    .add("mean_ms", c.meanMs).add("median_ms", c.medianMs).add("q1_ms", c.q1Ms).add("q3_ms", c.q3Ms)
                    .add("min_ms", c.minMs).add("max_ms", c.maxMs).add("mean_units", c.meanUnits)
                    .add("mean_init_units", c.meanInitUnits).add("mean_init_ms", c.meanInitMs)
                    .add("ms_per_1000_units", c.msPerThousandUnits()).add("spend", c.spend());
            if (c.targetMs > 0) o.add("target_ms", c.targetMs).add("ratio", c.ratio());
            arr.add(o);
        }
        JsonObject o = new JsonObject().add("format", 1).add("game", game).add("weights", weights)
                .add("positions", positions)
                .add("machine", MachineProfile.get().summary())
                .add("engine", System.getProperty("magat.engine.commit", "unknown"))
                .add("magat", System.getProperty("magat.git.commit", "unknown"));
        if (System.getProperty("magat.git.notebook") != null) o.add("notebook", System.getProperty("magat.git.notebook"));
        return o.add("measured", new java.util.Date().toString()).add("cells", arr);
    }

    public static void write(Path file, JsonObject o) throws IOException {
        RunGuard.atomicWrite(file, o.toString(WriterConfig.PRETTY_PRINT) + "\n");
    }

    private static double mean(double[] v) {
        if (v.length == 0) return Double.NaN;
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }
}
