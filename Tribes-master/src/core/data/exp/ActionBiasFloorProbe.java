package core.data.exp;

import core.Constants;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.stage1.Stage1BiasedAgent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Does the action bias actually ENGAGE at each budget level, or is it Baseline with extra bookkeeping?
 *
 * <h2>The failure this exists to catch</h2>
 *
 * <p>The action-bias line fails quietly from <b>below</b>. {@code qhat} shrinks toward the running
 * global mean, so a key with no observations returns exactly that mean; when every feasible
 * candidate's key is unseen they all tie, and the tie-break is uniform. An arm at too small a budget
 * therefore behaves like the baseline while still reporting a small noisy effect — it does not
 * announce that the mechanism never fired. Every downstream contrast would then be measuring nothing,
 * and nothing in any CSV would say so.
 *
 * <p>{@link BudgetFloorProbe} does not answer this. It asks a crossover question — is the tournament
 * firing? — and says nothing about whether a learned table had evidence in it.
 *
 * <h2>What is actually measured</h2>
 *
 * <p><b>Not</b> the number of mutations, which is the intuitive quantity and the wrong one. The
 * search concentrates on a few promising actions, so a table that is sparse overall can still be
 * well-supported exactly where it is read. What decides the question is how much evidence stood
 * behind the key each biased insertion <i>actually chose</i>:
 *
 * <blockquote><b>Criterion:</b> at least {@value #CRITERION_PCT}% of biased insertions made from burn
 * {@value Stage1BiasedAgent#WARMUP_BURNS} of 20 onward chose a key already carrying
 * {@value Stage1BiasedAgent#MIN_SUPPORT} or more observations.</blockquote>
 *
 * <p>The warm-up burns are skipped because the table is empty at turn start by construction, so
 * counting the cold start would measure the reset rather than the budget.
 *
 * <p>Only a biased insertion has a chosen key to measure, so the probe runs {@code Bias_e25}; at
 * {@code epsilon = 1} nothing is ever chosen by Q and the question is not defined.
 *
 * <p><b>A level that fails is a reporting level, not a contrast level.</b> It can still carry a budget
 * trend or an anytime curve; it cannot carry a claim that the bias helped, because at that budget the
 * bias was not operating.
 *
 * <h2>Run it AFTER calibration</h2>
 *
 * <p>A budget is a number of compute units and a unit is defined by the weights, so the same nominal
 * budget buys a different number of mutations under different weights. This probe's answer is
 * therefore weight-dependent: re-run it whenever the weights change, which includes moving machines.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.ActionBiasFloorProbe [portfolioDir] [outDir]
 * [budgetsCsv] [numScenarios] [threads]}
 */
public final class ActionBiasFloorProbe {

    private ActionBiasFloorProbe() {}

    /** Supported-pick share below which the mechanism is not reliably engaged. */
    public static final int CRITERION_PCT = 80;
    public static final String DEFAULT_BUDGETS = "40000,80000,400000";
    public static final int DEFAULT_SCENARIOS = 10;
    public static final String CSV_NAME = "action_bias_floor.csv";

    /** The biased arm being probed; set in {@link #main} (default {@code Bias_e25}). */
    private static Stage1Runner.Config arm;

    /** The default arm keeps the historical file name; any other arm gets its own file. */
    private static String csvName() {
        return arm.name.equals(Stage1Runner.BIAS_E25.name)
                ? CSV_NAME : "action_bias_floor_" + arm.name + ".csv";
    }

    private static final class Row {
        final int budget;
        int turns;
        long mutations, picks, supported;
        final List<Integer> distinctKeys = new ArrayList<>();
        final List<Integer> medianObs = new ArrayList<>();
        Row(int budget) { this.budget = budget; }
        double supportedFraction() { return picks > 0 ? supported / (double) picks : Double.NaN; }
        boolean pass() { return picks > 0 && 100.0 * supportedFraction() >= CRITERION_PCT; }
    }

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : Stage1Runner.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : Stage1Runner.BASE + "/_calibration";
        int[] budgets       = GameLengthProbeV6.parseInts(args.length > 2 ? args[2] : DEFAULT_BUDGETS);
        int numScenarios    = args.length > 3 ? Integer.parseInt(args[3].trim()) : DEFAULT_SCENARIOS;
        int threads         = args.length > 4 ? Integer.parseInt(args[4].trim())
                                              : Math.max(1, ServerProfile.get().maxWorkers());
        // Optional 6th arg: which biased arm to probe. The question is per-substrate — comma visits
        // fresher targets, so its table fills differently — hence Comma_On after the Gate B re-pin.
        arm = Stage1Runner.armByName(args.length > 5 ? args[5].trim() : Stage1Runner.BIAS_E25.name);
        if (arm.eps >= 1.0)
            throw new IllegalArgumentException("arm '" + arm.name + "' has eps=" + arm.eps
                    + " - an unbiased arm never CHOOSES a key, so it has no floor to probe");

        Stage1Runner.applyEngineConstants();
        Constants.TURN_TIME_LIMITED = false;

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));
        if (portfolio.isEmpty()) {
            System.err.println("No portfolio at " + portfolioDir + " — build it first.");
            System.exit(1);
        }
        int n = Math.min(numScenarios, portfolio.size());

        System.out.println("############################################################");
        System.out.println("# ACTION-BIAS FLOOR PROBE   arm=" + arm.name
                + "  scenarios=" + n + "  threads=" + threads);
        System.out.println("# Does the bias have evidence to act on, or is it Baseline with bookkeeping?");
        System.out.println("# Criterion: >= " + CRITERION_PCT + "% of biased insertions from burn "
                + Stage1BiasedAgent.WARMUP_BURNS + " onward chose a key with >= "
                + Stage1BiasedAgent.MIN_SUPPORT + " observations.");
        System.out.println("# Weight-dependent: re-run whenever the compute weights change.");
        System.out.println("############################################################");
        System.out.println("# " + core.game.ComputeMeter.weightsSummary());

        List<Row> rows = new ArrayList<>();
        for (int budget : budgets) {
            Row row = new Row(budget);
            java.util.concurrent.ExecutorService pool =
                    RunGuard.register(java.util.concurrent.Executors.newFixedThreadPool(threads));
            List<java.util.concurrent.Future<Stage1BiasedAgent>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < n; i++) {
                    final Scenario sc = portfolio.get(i);
                    final int b = budget;
                    futures.add(pool.submit(() -> planOneTurn(sc, b)));
                }
                for (java.util.concurrent.Future<Stage1BiasedAgent> f : futures) {
                    Stage1BiasedAgent a = f.get();
                    if (a == null) continue;
                    row.turns++;
                    row.mutations += a.getOperatorTelemetry().mutEvents;
                    row.picks += a.getBiasPicksAfterWarmup();
                    row.supported += a.getBiasSupportedAfterWarmup();
                    int[] obs = a.getInsertKeyObservationCounts();
                    row.distinctKeys.add(obs.length);
                    row.medianObs.add((int) Math.round(median(obs)));
                }
            } finally {
                RunGuard.shutdownAndWait(pool, 1);
            }
            rows.add(row);
            System.out.printf(Locale.ROOT,
                    "  %7d cu  turns=%-3d  mut/turn=%-7.0f  keys=%-5.0f  medianObs/key=%-5.0f  "
                    + "picks=%-7d  supported=%5.1f%%  %s%n",
                    row.budget, row.turns, mean(row.mutations, row.turns),
                    meanOf(row.distinctKeys), meanOf(row.medianObs), row.picks,
                    100 * row.supportedFraction(), row.pass() ? "PASS" : "FAIL");
        }

        // ---- report ------------------------------------------------------------------------------
        StringBuilder csv = new StringBuilder(
                "Budget,Turns,MutationsPerTurn,DistinctKeys,MedianObsPerKey,PicksAfterWarmup,"
                + "SupportedPicks,SupportedFraction,Verdict\n");
        for (Row r : rows) {
            csv.append(String.format(Locale.ROOT, "%d,%d,%.1f,%.1f,%.1f,%d,%d,%.4f,%s%n",
                    r.budget, r.turns, mean(r.mutations, r.turns), meanOf(r.distinctKeys),
                    meanOf(r.medianObs), r.picks, r.supported, r.supportedFraction(),
                    r.pass() ? "PASS" : "FAIL"));
        }
        ExpCsv.ensureDir(new File(outDir));
        File out = new File(outDir, csvName());
        RunGuard.atomicWrite(out, csv.toString());

        System.out.println("\n=== VERDICT ===");
        int failed = 0;
        for (Row r : rows) {
            if (r.pass()) {
                System.out.printf(Locale.ROOT, "  [ok]   %d cu: the bias acts on evidence (%.1f%%)%n",
                        r.budget, 100 * r.supportedFraction());
            } else {
                failed++;
                System.out.printf(Locale.ROOT,
                        "  [FAIL] %d cu: only %.1f%% of biased picks had %d+ observations behind them.%n",
                        r.budget, 100 * r.supportedFraction(), Stage1BiasedAgent.MIN_SUPPORT);
                System.out.println("         At this budget the arm is Baseline with extra bookkeeping:");
                System.out.println("         it is a REPORTING level (budget trend, anytime curves),");
                System.out.println("         not a level any 'the bias helped' claim may rest on.");
            }
        }
        System.out.println("  measurements -> " + out.getPath());
        RunGuard.finished();
        System.exit(failed == 0 ? 0 : 1);
    }

    /** One planning turn with the biased arm; returns the agent so its counters can be read. */
    private static Stage1BiasedAgent planOneTurn(Scenario sc, int budget) {
        try {
            long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
            Stage1BiasedAgent agent = (Stage1BiasedAgent) arm.create(seed, budget);
            GameState gs = sc.state.copy();
            gs.setRandomGenerator(new java.util.Random(seed));
            ArrayList<Integer> ids = new ArrayList<>();
            for (int i = 0; i < gs.getTribes().length; i++) ids.add(i);
            agent.setPlayerIDs(gs.getActiveTribeID(), ids);
            ElapsedCpuTimer ect = new ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
            agent.act(gs, ect);
            return agent;
        } catch (Throwable t) {
            System.err.println("[floor] scenario " + sc.id + " @" + budget + " failed: " + t);
            return null;
        }
    }

    private static double median(int[] v) {
        if (v.length == 0) return 0;
        int[] c = v.clone();
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : 0.5 * (c[m - 1] + c[m]);
    }

    private static double mean(long total, int count) { return count > 0 ? total / (double) count : 0; }

    private static double meanOf(List<Integer> v) {
        if (v.isEmpty()) return 0;
        long s = 0;
        for (int x : v) s += x;
        return s / (double) v.size();
    }
}
