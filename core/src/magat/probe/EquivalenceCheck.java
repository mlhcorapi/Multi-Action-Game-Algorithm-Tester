package magat.probe;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Oracle;
import magat.agent.Seeds;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Profile;
import magat.meter.Weights;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The equivalence gate: a ported arm must reproduce its oracle's signature on every (scenario,
 * visit) at every budget, both sides priced by the same weights. This is the thesis tree's
 * {@code Stage1Runner verify}, widened from one turn to a grid, and it is what says the seam is in
 * the right place.
 */
public final class EquivalenceCheck {

    public static final class Cell {
        public final int scenarioId, visit;
        public final long budget;
        public final Signature candidate, oracle;
        public final List<String> differences;

        Cell(int scenarioId, int visit, long budget, Signature candidate, Signature oracle, List<String> differences) {
            this.scenarioId = scenarioId; this.visit = visit; this.budget = budget;
            this.candidate = candidate; this.oracle = oracle; this.differences = differences;
        }

        public boolean passed() { return differences.isEmpty(); }
    }

    public static final class Result {
        public final String arm;
        public final List<Cell> cells = new ArrayList<>();

        Result(String arm) { this.arm = arm; }

        public int failures() {
            int n = 0;
            for (Cell c : cells) if (!c.passed()) n++;
            return n;
        }

        public boolean passed() { return !cells.isEmpty() && failures() == 0; }
    }

    private EquivalenceCheck() {}

    public static <S, A> Result run(Game<S, A> game, AgentFactory<S, A> arm, Oracle<S> oracle,
                                    List<Scenario<S>> scenarios, int visits, long[] budgets,
                                    Weights weights, int threads) throws Exception {
        Result result = new Result(arm.id());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            for (long budget : budgets) {
                // Both sides are priced by the arm's profile at this budget; at a budget no file
                // prices (a toy gate), by the arm's profile at its smallest priced level.
                String profile = Profile.key(arm.id(), budget);
                double[] w = weights.profile(profile);
                if (w == null) {
                    String nearest = smallestPricedLevel(weights, arm.id());
                    if (nearest == null)
                        throw new IllegalStateException("arm " + arm.id() + " has no priced profile in " + weights.source());
                    w = weights.profile(nearest);
                    profile = nearest + " (used for " + budget + ")";
                    System.out.println("  note: " + Profile.key(arm.id(), budget) + " is not priced; both sides use "
                            + nearest + "'s weights");
                }
                final double[] priced = w;
                final String label = profile;

                // Candidate turns, in parallel: the meter is thread-local.
                List<Future<Signature>> cand = new ArrayList<>();
                for (Scenario<S> sc : scenarios) {
                    for (int v = 0; v < visits; v++) {
                        final int visit = v;
                        cand.add(pool.submit(() -> Signature.of(Episode.run(game, sc, visit, arm, budget, priced, label))));
                    }
                }
                // Oracle turns, in parallel under the same three numbers.
                List<Future<Signature>> orc = new ArrayList<>();
                for (Scenario<S> sc : scenarios) {
                    for (int v = 0; v < visits; v++) {
                        final long seed = Seeds.visit(sc.id, v);
                        orc.add(pool.submit(() -> oracle.plan(sc, seed, budget, priced)));
                    }
                }
                int i = 0;
                for (Scenario<S> sc : scenarios) {
                    for (int v = 0; v < visits; v++, i++) {
                        Signature c = cand.get(i).get();
                        Signature o = orc.get(i).get();
                        result.cells.add(new Cell(sc.id, v, budget, c, o, c.differences(o, oracle.deadInitCopies())));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /** The arm's priced profile with the smallest budget, or {@code null}. */
    static String smallestPricedLevel(Weights weights, String arm) {
        String best = null;
        long bestBudget = Long.MAX_VALUE;
        for (String p : weights.profiles()) {
            if (!Profile.arm(p).equals(arm)) continue;
            long b = Profile.budget(p);
            if (b < bestBudget) { bestBudget = b; best = p; }
        }
        return best;
    }

    public static void print(Result r, Oracle<?> oracle, boolean verbose) {
        System.out.println("# equivalence: " + r.arm + " vs " + oracle.describe());
        for (Cell c : r.cells) {
            String tag = c.passed() ? "ok  " : "FAIL";
            if (verbose || !c.passed())
                System.out.println(String.format(Locale.ROOT, "  [%s] scenario %3d visit %d @%d  %s",
                        tag, c.scenarioId, c.visit, c.budget, c.candidate.thesisLine()));
            for (String d : c.differences) System.out.println("         " + d);
        }
        System.out.println(String.format(Locale.ROOT, "  %d cells, %d failed", r.cells.size(), r.failures()));
    }
}
