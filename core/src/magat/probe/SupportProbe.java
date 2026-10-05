package magat.probe;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.game.Game;
import magat.game.Scenario;
import magat.run.RunGuard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code magat probe support}: does an arm's learned mechanism actually engage at each budget, or is
 * it the baseline with extra bookkeeping?
 *
 * <p>A learned-bias line fails quietly from below: an unobserved class's estimate is exactly the
 * prior, unseen candidates tie, and ties break uniformly, so at too small a budget the arm behaves
 * like its baseline while still reporting a small noisy effect. What decides it is not how many
 * mutations ran but how much evidence stood behind the class each biased insertion actually chose.
 * Criterion (default): at least 80% of the biased insertions made after the warm-up burns chose a
 * class already carrying at least 3 observations. A level that fails is a <i>reporting</i> level: it
 * can carry a trend, not a claim that the mechanism helped.
 *
 * <p>Weight-dependent (a budget buys a different number of mutations under different weights), so it
 * is re-run whenever the currency changes. Extracted from the thesis tree's {@code ActionBiasFloorProbe}.
 */
public final class SupportProbe {

    public static final double CRITERION = 0.80;
    public static final String HEADER = "Budget,Turns,MutationsPerTurn,DistinctKeys,MedianObsPerKey,PicksAfterWarmup,SupportedPicks,SupportedFraction,Verdict";

    private SupportProbe() {}

    public static final class Level {
        public final long budget;
        public int turns;
        public long mutations, picks, supported;
        double keys, medianObs;

        Level(long budget) { this.budget = budget; }

        public double fraction() { return picks > 0 ? supported / (double) picks : Double.NaN; }

        public boolean pass(double criterion) { return picks > 0 && fraction() >= criterion; }

        String row(double criterion) {
            return String.format(Locale.ROOT, "%d,%d,%.1f,%.1f,%.1f,%d,%d,%.4f,%s", budget, turns, turns > 0 ? mutations / (double) turns : 0,
                    turns > 0 ? keys / turns : 0, turns > 0 ? medianObs / turns : 0, picks, supported, fraction(), pass(criterion) ? "PASS" : "FAIL");
        }
    }

    public static <S, A> List<Level> run(Game<S, A> game, AgentFactory<S, A> arm, List<Scenario<S>> scenarios, long[] budgets,
                                         int threads, double criterion, Path csv) throws Exception {
        List<Level> out = new ArrayList<>();
        for (long budget : budgets) {
            Level level = new Level(budget);
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            try {
                List<Future<Map<String, Double>>> fs = new ArrayList<>();
                for (Scenario<S> sc : scenarios)
                    fs.add(pool.submit(() -> Episode.planned(game, sc, 0, arm, budget).agent.support()));
                for (Future<Map<String, Double>> f : fs) {
                    Map<String, Double> s = f.get();
                    if (s.isEmpty() || s.getOrDefault("biased", 0.0) < 1.0)
                        throw new IllegalArgumentException("arm " + arm.id() + " has no biased insertion: it never chooses a class, "
                                + "so it has no support to probe");
                    level.turns++;
                    level.mutations += s.get("mutations").longValue();
                    level.picks += s.get("picksAfterWarmup").longValue();
                    level.supported += s.get("supportedAfterWarmup").longValue();
                    level.keys += s.get("distinctInsertKeys");
                    level.medianObs += s.get("medianInsertKeyObservations");
                }
            } finally {
                pool.shutdownNow();
            }
            out.add(level);
            System.out.println("  " + level.row(criterion).replace(",", "  "));
        }
        if (csv != null) {
            StringBuilder sb = new StringBuilder(HEADER).append('\n');
            for (Level l : out) sb.append(l.row(criterion)).append('\n');
            RunGuard.atomicWrite(csv, sb.toString());
        }
        return out;
    }

    public static void printHeader() {
        System.out.println("  " + HEADER.replace(",", "  "));
    }

    /** Writes nothing; prints the verdict per level and returns whether every level passed. */
    public static boolean report(List<Level> levels, double criterion) {
        boolean all = true;
        for (Level l : levels) {
            boolean ok = l.pass(criterion);
            all &= ok;
            System.out.println(String.format(Locale.ROOT, "  %8d: %.1f%% of %d biased picks after the warm-up rested on supported classes -> %s",
                    l.budget, 100 * l.fraction(), l.picks, ok ? "a CONTRAST level" : "a REPORTING level (the mechanism is not reliably engaged)"));
        }
        return all;
    }
}
