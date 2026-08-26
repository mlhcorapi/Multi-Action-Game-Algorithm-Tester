package core.data.exp;

import core.Constants;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the SMALLEST compute budget at which the v6 configurations are actually running the
 * algorithms they are named after.
 *
 * <p><b>The problem this answers.</b> A budget level is only evidence about crossover if crossover
 * happens at it. Two mechanisms fail quietly from below:
 *
 * <ul>
 *   <li><b>Crossover bridge burning</b> is a 3-level bracket over the phase's top EIGHT elites
 *       (8 &rarr; 4 &rarr; 2 &rarr; 1, seven children evaluated). The code runs it whenever the pool
 *       holds at least <i>two</i>. At a small budget a phase produces only a handful of children, so
 *       the tournament either does not fire at all — the burn silently falls back to promoting the
 *       best child, which is what plain EMCTS does — or fires on a pool of 2 or 3 and reduces to a
 *       single pairing. Both are structurally a different operator from the one being studied, and
 *       neither announces itself in the results.</li>
 *   <li><b>Crossover expansion</b> fires once every {@code mutationExpansionRatio} iterations, so the
 *       top of the ratio sweep needs enough iterations per turn to fire a meaningful number of times.</li>
 * </ul>
 *
 * <p><b>What "large enough" means here</b>, per criterion and why:
 * <ol>
 *   <li>{@code tournamentRate} &ge; {@value #MIN_TOURNAMENT_RATE} — nearly every one of the 20 burns
 *       runs a tournament, so the crossover agent differs from EMCTS at essentially every burn rather
 *       than at half of them;</li>
 *   <li>{@code meanPoolSize} &ge; {@value #MIN_MEAN_POOL} of 8 — the bracket is close to the full
 *       8-node one the operator is defined as, so the measured effect is the real operator's;</li>
 *   <li>{@code childrenPerPhase} &ge; {@value #MIN_CHILDREN_PER_PHASE} — enough new nodes are added
 *       between consecutive burns to refill the elite pool, rather than the search burning bridges
 *       faster than it can build them;</li>
 *   <li>{@code thinnestExpansionXo} &ge; {@value #MIN_EXPANSION_XO} events/turn — the sparsest cell of
 *       the ratio sweep (the largest ratio) still fires often enough to carry information.</li>
 * </ol>
 *
 * <p>The probe sweeps candidate budgets, reports every criterion per budget, and names the smallest
 * that satisfies all four. It is a diagnostic that decides a design parameter, so it prints its
 * reasoning rather than only its answer.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.BudgetFloorProbe [portfolioDir] [outDir]
 * [budgetsCsv] [scenarios]}
 */
public final class BudgetFloorProbe {

    /**
     * Fraction of the 20 burns at which the tournament actually runs.
     *
     * <p><b>There is a ceiling, and it is not 1.0.</b> Measured on this portfolio, the rate rises
     * 0.46 → 0.63 → 0.74 → 0.84 → 0.89 across 13.4k → 26.8k → 40k → 54k → 70k units and then stops:
     * 90k also gives 0.89. Roughly one burn in nine never gets a pool of four, and no amount of extra
     * budget changes that — the branching factor varies several-fold across the portfolio, and in the
     * widest scenarios a phase's iterations are expensive enough that few children are produced no
     * matter how large the phase's slice is. A threshold of 0.95 is therefore unreachable rather than
     * demanding, and setting one would only push the budget up until the sweep ran out of patience.
     *
     * <p>0.70 asks for at least ~80% of what the design can ever achieve, which is the point at which
     * the crossover configurations differ from EMCTS at three burns in four rather than at half.
     */
    public static final double MIN_TOURNAMENT_RATE = 0.70;
    /** Mean elites available to a tournament, out of the 8 the bracket is designed for. */
    public static final double MIN_MEAN_POOL = 6.0;
    /**
     * New children evaluated per phase — the pool refill rate between consecutive burns.
     *
     * <p>Set at twice the 8-slot elite pool: the phase between two burns should be able to turn the
     * pool over completely, so the tournament breeds from nodes this phase actually found rather than
     * from whatever survived the last one. Eight would only mean "could just barely refill".
     */
    public static final double MIN_CHILDREN_PER_PHASE = 16.0;
    /**
     * Expansion-crossover events per turn in the SPARSEST ratio cell (the largest ratio).
     *
     * <p>Deliberately modest. At ratio 50 the operator is <i>designed</i> to be rare — rarity is the
     * treatment being tested, not a defect — so the bar here is "fires reliably within every turn",
     * not "fires often". With 5,000 turns behind each cell, five events per turn is twenty-five
     * thousand crossovers behind that cell's estimate, which is ample. The failure worth excluding is a cell
     * where the operator never fires and the configuration is silently plain EMCTS.
     */
    public static final double MIN_EXPANSION_XO = 5.0;

    /** Candidate budgets swept when none are given. */
    private static final int[] DEFAULT_CANDIDATES =
            {13_400, 20_000, 26_800, 33_500, 40_000, 47_000, 54_000, 67_000};

    public static final String HEADER =
            "BudgetUnits,Scenarios,BB_Burns,BB_Tournaments,BB_TournamentRate,BB_MeanPoolSize,"
            + "BB_FullBracketRate,BB_EnterRate,BB_ChildrenPerPhase,BB_NodesPerTurn,BB_UniqueSeqPerTurn,"
            + "Combo_TournamentRate,Combo_MeanPoolSize,Combo_ChildrenPerPhase,"
            + "ThinnestExpansionXo,RealizedUnits,Verdict";

    private BudgetFloorProbe() {}

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int[] budgets       = args.length > 2 ? parseInts(args[2]) : DEFAULT_CANDIDATES;
        int numScenarios    = args.length > 3 ? Integer.parseInt(args[3].trim()) : 10;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);
        File csv = new File(out, "budget_floor.csv");

        System.out.println("############################################################");
        System.out.println("# v6 budget-floor probe   (output=" + csv.getPath() + ")");
        System.out.println("# Finds the smallest budget at which the crossover operators actually run");
        System.out.println("# as specified, rather than degenerating quietly into plain EMCTS.");
        System.out.println("############################################################");
        System.out.println("# criteria: tournamentRate >= " + MIN_TOURNAMENT_RATE
                + ", meanPool >= " + MIN_MEAN_POOL + "/8, childrenPerPhase >= " + MIN_CHILDREN_PER_PHASE
                + ", thinnest expansionXo >= " + MIN_EXPANSION_XO + "/turn");

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));
        int n = Math.min(numScenarios, portfolio.size());
        List<Scenario> sample = new ArrayList<>();
        for (int i = 0; i < n; i++) sample.add(portfolio.get((int) ((long) i * portfolio.size() / n)));
        System.out.println("[floor] " + sample.size() + " scenarios spanning the portfolio; budgets "
                + java.util.Arrays.toString(budgets));

        List<Row> rows = new ArrayList<>();
        System.out.printf(Locale.ROOT, "%n  %9s %8s %7s %9s %9s %9s %11s %10s %8s   %s%n",
                "budget", "tourn/", "pool", "full", "children", "nodes", "uniqueSeq", "thinnest", "spend",
                "verdict");
        System.out.printf(Locale.ROOT, "  %9s %8s %7s %9s %9s %9s %11s %10s %8s%n",
                "", "burn", "/8", "bracket", "/phase", "/turn", "/turn", "expXo", "vs target");

        for (int budget : budgets) {
            Row r = measure(budget, sample);
            rows.add(r);
            System.out.printf(Locale.ROOT,
                    "  %9d %8.2f %7.2f %8.0f%% %9.1f %9.1f %11.1f %10.1f %7.1f%%   %s%n",
                    budget, r.tournamentRate(), r.meanPool(), 100 * r.fullBracketRate(),
                    r.childrenPerPhase(), r.nodesPerTurn(), r.uniqueSeqPerTurn(),
                    r.thinnestExpansionXo(),
                    100.0 * (r.realizedUnits() - budget) / budget, r.verdict());
        }

        List<String> lines = new ArrayList<>();
        for (Row r : rows) lines.add(r.toCsv());
        ExpCsv.append(csv, HEADER, lines);

        recommend(rows);
        System.out.println("\nWrote " + csv.getPath());
        RunGuard.finished();
    }

    // ---------------------------------------------------------------------------------------------

    private static Row measure(int budget, List<Scenario> sample) {
        Row r = new Row(budget, sample.size());

        // BOTH bridge-burning configurations are measured, and the criteria bind on the WORSE of the
        // two. CrossoverBB is the demanding case: Combo also runs expansion crossover, and every
        // expansion child is offered to the same elite pool the tournament draws from, so Combo's pool
        // refills faster at the same budget. Judging the floor on Combo alone sets it comfortably too
        // low for the configuration that has nothing else feeding its pool.
        for (Scenario sc : sample) {
            ExperimentalEMCTSAgent bb = (ExperimentalEMCTSAgent) ExpAgents.crossoverBBV6(budget)
                    .create(IsolationPhaseRunnerV6.visitSeed(sc.id, 0));
            TurnData t = runOne(bb, sc);
            if (t != null) r.bb.add(bb, t);
        }
        for (Scenario sc : sample) {
            ExperimentalEMCTSAgent combo = (ExperimentalEMCTSAgent) ExpAgents.crossoverComboV6(5, budget)
                    .create(IsolationPhaseRunnerV6.visitSeed(sc.id, 0));
            TurnData t = runOne(combo, sc);
            if (t != null) r.combo.add(combo, t);
        }

        // The sparsest cell of the ratio sweep: expansion crossover at the LARGEST ratio.
        int largestRatio = IsolationPhaseRunnerV6.RATIOS[IsolationPhaseRunnerV6.RATIOS.length - 1];
        for (Scenario sc : sample) {
            Agent a = ExpAgents.crossoverExpansionV6(largestRatio, budget)
                    .create(IsolationPhaseRunnerV6.visitSeed(sc.id, 0));
            if (runOne(a, sc) == null) continue;
            core.data.dto.OperatorTelemetryData ot = a.getOperatorTelemetry();
            if (ot != null) r.thinnestXoSum += ot.xoEvents;
            r.thinnestTurns++;
        }
        return r;
    }

    private static TurnData runOne(Agent a, Scenario sc) {
        long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
        ArrayList<Integer> ids = new ArrayList<>();
        for (int k = 0; k < sc.state.getTribes().length; k++) ids.add(k);
        a.setPlayerIDs(sc.state.getActiveTribeID(), ids);
        GameState gs = sc.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { a.act(gs, ect); } catch (Throwable t) { return null; }
        List<TurnData> log = a.getTurnDataLog();
        return log.isEmpty() ? null : log.get(0);
    }

    private static void recommend(List<Row> rows) {
        Row floor = null;
        for (Row r : rows) if (r.passes() && floor == null) floor = r;

        System.out.println("\n=== RECOMMENDATION ===");
        if (floor == null) {
            System.out.println("  No candidate budget satisfied all four criteria. Widen the sweep upward:");
            System.out.println("    java -cp \"out:lib/*\" core.data.exp.BudgetFloorProbe <pf> <out> 67000,100000,134000");
            return;
        }
        int min = floor.budget;
        System.out.printf(Locale.ROOT,
                "  Smallest budget meeting every criterion: %,d compute-units.%n", min);
        System.out.printf(Locale.ROOT,
                "    tournaments fire on %.0f%% of burns, mean bracket %.1f/8 (%.0f%% full),%n"
                + "    %.0f new children per phase between burns, sparsest ratio cell %.0f xo/turn.%n",
                100 * floor.tournamentRate(), floor.meanPool(), 100 * floor.fullBracketRate(),
                floor.childrenPerPhase(), floor.thinnestExpansionXo());
        System.out.println();
        System.out.printf(Locale.ROOT, "  Suggested v6 levels:  %,d  /  %,d (2x)  /  %,d (10x)%n",
                min, 2 * min, 10 * min);
        System.out.println("  Set them in RunExperimentV6.DEFAULT_COMPUTE_LEVELS, or pass explicitly:");
        System.out.printf(Locale.ROOT,
                "    java ... core.data.exp.RunExperimentV6 auto %d,%d,%d%n", min, 2 * min, 10 * min);
    }

    /** Accumulators for one bridge-burning configuration at one budget. */
    private static final class Cfg {
        int turns, burns, entered, completed, fullBrackets;
        double poolSum, nodes, uniqueSeq, units;

        void add(ExperimentalEMCTSAgent a, TurnData t) {
            burns += a.getBridgeBurnDataLog().size();
            entered += a.getBbTournaments();
            // A tournament that ENTERS is not a tournament that RUNS. performCrossoverBridgeBurning
            // is called whenever the elite pool holds 2, but its level-1 round pairs the pool two at a
            // time, so a pool of 2 or 3 yields a single child, fails the `level1Children.size() < 2`
            // guard and returns findBestChild(root) — the plain-EMCTS promotion — WITHOUT logging a
            // crossover event. The bracket therefore needs a pool of at least FOUR to run at all.
            // The crossover event log counts exactly the tournaments that completed, so it is the
            // honest rate, and it is the one the budget floor is judged on.
            completed += a.getCrossoverDataLog() != null ? a.getCrossoverDataLog().size() : 0;
            poolSum += a.getBbMeanPoolSize() * a.getBbTournaments();
            fullBrackets += a.getBbFullBrackets();
            nodes += t.getNodesEvaluated();
            uniqueSeq += t.getUniqueSequences();
            units += t.getComputeUnits();
            turns++;
        }
        /** Burns at which the bracket actually ran, over all burns. The binding criterion. */
        double tournamentRate()   { return burns > 0 ? completed / (double) burns : 0; }
        /** Burns at which it was attempted — above the rate above by the bail-out cases. */
        double enterRate()        { return burns > 0 ? entered / (double) burns : 0; }
        double meanPool()         { return entered > 0 ? poolSum / entered : 0; }
        double fullBracketRate()  { return entered > 0 ? fullBrackets / (double) entered : 0; }
        // Children created between consecutive burns. The turn is split into params.depth phases.
        double childrenPerPhase() { return turns > 0 ? nodes / turns / ExpAgents.DEPTH : 0; }
        double nodesPerTurn()     { return turns > 0 ? nodes / turns : 0; }
        double uniqueSeqPerTurn() { return turns > 0 ? uniqueSeq / turns : 0; }
        double realizedUnits()    { return turns > 0 ? units / turns : 0; }
    }

    /** Accumulated measurements for one candidate budget, across both configurations. */
    private static final class Row {
        final int budget, scenarios;
        final Cfg bb = new Cfg();
        final Cfg combo = new Cfg();
        int thinnestTurns;
        double thinnestXoSum;

        Row(int budget, int scenarios) { this.budget = budget; this.scenarios = scenarios; }

        // Every criterion binds on the WORSE of the two bridge-burning configurations.
        double tournamentRate()   { return Math.min(bb.tournamentRate(), combo.tournamentRate()); }
        double meanPool()         { return Math.min(bb.meanPool(), combo.meanPool()); }
        double fullBracketRate()  { return Math.min(bb.fullBracketRate(), combo.fullBracketRate()); }
        double childrenPerPhase() { return Math.min(bb.childrenPerPhase(), combo.childrenPerPhase()); }
        double nodesPerTurn()     { return Math.min(bb.nodesPerTurn(), combo.nodesPerTurn()); }
        double uniqueSeqPerTurn() { return Math.min(bb.uniqueSeqPerTurn(), combo.uniqueSeqPerTurn()); }
        double realizedUnits()    { return Math.max(bb.realizedUnits(), combo.realizedUnits()); }
        double thinnestExpansionXo() { return thinnestTurns > 0 ? thinnestXoSum / thinnestTurns : 0; }

        boolean passes() {
            return tournamentRate() >= MIN_TOURNAMENT_RATE
                    && meanPool() >= MIN_MEAN_POOL
                    && childrenPerPhase() >= MIN_CHILDREN_PER_PHASE
                    && thinnestExpansionXo() >= MIN_EXPANSION_XO;
        }

        String verdict() {
            if (passes()) return "OK";
            List<String> why = new ArrayList<>();
            if (tournamentRate() < MIN_TOURNAMENT_RATE)
                why.add(String.format(Locale.ROOT, "tournaments on %.0f%% of burns", 100 * tournamentRate()));
            if (meanPool() < MIN_MEAN_POOL)
                why.add(String.format(Locale.ROOT, "bracket %.1f/8", meanPool()));
            if (childrenPerPhase() < MIN_CHILDREN_PER_PHASE)
                why.add(String.format(Locale.ROOT, "%.1f children/phase", childrenPerPhase()));
            if (thinnestExpansionXo() < MIN_EXPANSION_XO)
                why.add(String.format(Locale.ROOT, "sparsest cell %.1f xo/turn", thinnestExpansionXo()));
            return String.join(", ", why);
        }

        String toCsv() {
            return String.join(",", String.valueOf(budget), String.valueOf(scenarios),
                    String.valueOf(bb.burns), String.valueOf(bb.completed),
                    f(bb.tournamentRate()), f(bb.meanPool()), f(bb.fullBracketRate()),
                    f(bb.enterRate()), f(bb.childrenPerPhase()), f(bb.nodesPerTurn()), f(bb.uniqueSeqPerTurn()),
                    f(combo.tournamentRate()), f(combo.meanPool()), f(combo.childrenPerPhase()),
                    f(thinnestExpansionXo()), f(realizedUnits()),
                    passes() ? "OK" : "too_small");
        }

        private static String f(double v) { return String.format(Locale.ROOT, "%.4f", v); }
    }

    private static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
}
