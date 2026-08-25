package core.data.exp;

import core.Constants;
import core.Types;
import core.actions.Action;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import core.data.exp.IsolationPhaseRunner.Scenario;
import players.Agent;
import players.emcts.EMCTSAgent;
import players.heuristics.StateHeuristic;
import players.heuristics.TribesSimpleHeuristic;
import players.emcts.EMCTSParams;
import players.tbets.TBETSAgent;
import players.tbets.TBETSParams;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TBETS vs EMCTS on a common scenario portfolio, in the isolation-phase style used by the rest of
 * this project: every agent plans ONE turn from the SAME captured mid-game position under the SAME
 * deterministic compute budget, and the comparison is PAIRED on (scenario, visit).
 *
 * <p><b>Why per-turn and not win rate.</b> Tribes final scores have a standard deviation on the
 * order of the effect being measured — in earlier probing, two runs of the same configuration that
 * differed by 0.3% of budget produced a 2x score swing. Whole-game win rates need hundreds of games
 * per arm before they resolve anything. Turn-level planning quality, measured paired on a fixed
 * position, removes the map, the opponent and the trajectory from the variance and answers the
 * question the port actually poses: given the same position and the same compute, which search finds
 * the better turn?
 *
 * <p><b>What is compared: {@code PostTurnValue}, and NOT {@code FinalSequenceValue}.</b> The runner
 * plays the agent's whole turn out and then scores the resulting state itself, with one heuristic it
 * owns. That extra step exists because {@code FinalSequenceValue} is each agent's OWN search value,
 * and those are not commensurable: with {@link TBETSParams#useRollouts} on, a TBETS node's value is
 * the heuristic TWO turns downstream, past a scripted opponent reply and a scripted reply of its own,
 * while an EMCTS node's value is the state one turn out. The two-turn state has had another round of
 * production and score accrue to it, so it reads systematically higher for reasons that have nothing
 * to do with the quality of the turn chosen. Comparing the two columns directly would have credited
 * the rollout arm with an advantage it may not have.
 *
 * <p>{@code PostTurnValue} is immune to that: it is {@code evaluateState(stateAsHandedOver,
 * stateAfterTheTurnTheAgentActuallyPLAYED)}, computed the same way for every arm by code the agents
 * do not participate in. It also measures the turn as PLAYED rather than as planned, so an agent
 * whose cached plan breaks against the real state is charged for it. {@code FinalSequenceValue} is
 * still logged, because the gap between the two is itself informative — a large one means the search
 * is optimising something other than what it delivers.
 *
 * <h2>Fairness</h2>
 * <ul>
 *   <li><b>Same position.</b> Both agents are handed {@code scenario.state.copy()} with the same
 *       seeded RNG, so the forward model resolves stochastic actions identically for both.</li>
 *   <li><b>Same seed.</b> Paired on {@link IsolationPhaseRunner#visitSeed}, the same pairing the v4-v6
 *       experiments use.</li>
 *   <li><b>Same search budget.</b> Both run at the same ceiling in the same currency — STOP_COMPUTE
 *       compute-units by default, or milliseconds of search under {@link #TIME_MODE} — and both
 *       EXCLUDE their initialization from it. EMCTS always does (its greedy 1-ply root is not
 *       charged), so TBETS is configured to match rather than being charged for a phase its opponent
 *       gets free. Initialization cost is measured separately for both and written to the
 *       {@code InitComputeUnits} column, and {@code TotalComputeUnits} carries init + search, so the
 *       asymmetry is auditable rather than hidden. Read both before drawing a conclusion: if the
 *       initialization costs diverge sharply the matched-search comparison flatters whichever agent
 *       initializes more expensively.</li>
 *   <li><b>Both currencies.</b> Compute-units are reproducible but price only advance/copy/eval;
 *       wall-clock prices everything but is machine- and load-dependent. The two arms disagreeing
 *       would itself be the finding, so run both — {@code --time} with one thread.</li>
 * </ul>
 *
 * <p><b>Known asymmetry, stated up front.</b> The portfolio's positions are captured from games
 * played BY EMCTS agents ({@link BuildV5Portfolio} source games), because that is the portfolio this
 * project already builds and reusing it keeps these results comparable with the v5/v6 numbers. The
 * positions are ordinary mid-game states rather than anything tuned to a search, but they are drawn
 * from an EMCTS-played distribution, and that is a real if modest advantage to EMCTS. Treat a narrow
 * EMCTS win as inconclusive on that ground alone.
 *
 * <p>Usage: {@code java -cp out core.data.exp.TbetsVsEmctsRunner [outDir] [threads] [visits] [cu...]}
 */
public class TbetsVsEmctsRunner {

    /**
     * Budget currency. false (default) = STOP_COMPUTE, the project's deterministic compute-unit
     * budget. true = STOP_TIME with an explicit per-turn search budget in milliseconds.
     *
     * <p><b>Why a wall-clock arm exists at all,</b> given that this project deliberately moved OFF
     * wall-clock budgets for reproducibility: compute-units price {@code advance}, {@code copy} and
     * {@code eval} and nothing else, and the two agents do different amounts of work that falls
     * outside those three. At a matched compute budget TBETS spent noticeably more wall-clock than
     * EMCTS, which means the compute-unit currency is undercharging it somewhere - candidates are
     * {@code allGoodActions} feasibility scans and the per-node sequence-key strings, neither of
     * which is metered. A compute-matched win therefore needs a wall-clock-matched check before it
     * can be believed. Run it single-threaded; with workers competing, this measures contention.
     */
    public static boolean TIME_MODE = false;

    public static final String BASE = "test_results/tbets_vs_emcts";
    public static final int[] DEFAULT_LEVELS = {40_000, 80_000};
    public static final int DEFAULT_VISITS = 5;
    /** Safety bound on replaying one turn; a Tribes turn plan is capped at 20 actions. */
    private static final int MAX_TURN_ACTIONS = 100;

    private static final String HEADER =
            "Budget,ScenarioId,Source,SourceGameId,CapturedTick,BranchingFactor,Visit,Agent,"
            + "InitialValue,FinalValue,PostTurnValue,TurnActions,Gain,AvgNodeValue,"
            + "NodesEvaluated,UniqueSequences,FmCalls,SearchFmCalls,"
            + "ComputeUnits,InitComputeUnits,TotalComputeUnits,PlanMillis,InitMillis,Failed";

    public static void main(String[] args) throws Exception {
        // --time anywhere in the args switches the budget currency from compute-units to
        // MILLISECONDS of search (STOP_TIME + num_timeMillis). See TIME_MODE_NOTE.
        List<String> a = new ArrayList<>();
        boolean timeMode = false;
        for (String x : args) {
            if ("--time".equals(x.trim())) timeMode = true; else a.add(x);
        }
        args = a.toArray(new String[0]);

        String outDir = args.length > 0 ? args[0] : BASE;
        int threads   = args.length > 1 ? Integer.parseInt(args[1].trim()) : 8;
        int visits    = args.length > 2 ? Integer.parseInt(args[2].trim()) : DEFAULT_VISITS;
        int[] levels  = args.length > 3 ? parseInts(args, 3) : DEFAULT_LEVELS;
        TIME_MODE = timeMode;

        if (timeMode && threads > 1) {
            System.out.println("[TvE] WARNING: --time with " + threads + " threads measures CPU "
                    + "contention as much as search. Use 1 thread for a wall-clock comparison.");
        }

        Constants.VERBOSE = false;

        File base = new File(outDir);
        File portfolioDir = new File(base, "_portfolio");
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(portfolioDir);
        System.out.println("[TvE] portfolio: " + portfolio.size() + " scenarios from " + portfolioDir);
        System.out.println("[TvE] visits=" + visits + "  threads=" + threads
                + "  levels=" + java.util.Arrays.toString(levels));

        Map<Integer, Integer> branching = readBranching(new File(portfolioDir, "portfolio.csv"));

        for (int cu : levels) {
            File csv = new File(base, (TIME_MODE ? "turns_ms" : "turns_cu") + cu + ".csv");
            if (csv.exists()) {
                System.out.println("[TvE] cu=" + cu + " already done (" + csv.getName() + ") - skipping");
                continue;
            }
            System.out.println("\n[TvE] ==== budget " + cu + (TIME_MODE ? " ms of search" : " compute-units") + " ====");
            List<String> rows = runLevel(cu, portfolio, branching, visits, threads);

            File tmp = new File(base, csv.getName() + ".part");
            try (PrintWriter w = new PrintWriter(tmp, "UTF-8")) {
                w.println(HEADER);
                for (String r : rows) w.println(r);
            }
            Files.move(tmp.toPath(), csv.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[TvE] wrote " + csv.getPath() + " (" + rows.size() + " rows)");
        }

        System.out.println();
        for (int cu : levels) {
            File csv = new File(base, (TIME_MODE ? "turns_ms" : "turns_cu") + cu + ".csv");
            if (csv.exists()) TbetsVsEmctsAnalysis.report(csv, cu);
        }
    }

    // ------------------------------------------------------------------ agents under test

    /** Name -> factory. Kept here so the arms are declared in one visible place. */
    private static Map<String, java.util.function.LongFunction<Agent>> arms(int cu) {
        Map<String, java.util.function.LongFunction<Agent>> m = new LinkedHashMap<>();

        m.put("EMCTS", seed -> {
            EMCTSParams p = new EMCTSParams();
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;               // 21 phases, 20 bridge burns - the v6 setting
            p.randomInitialSequence = false;         // greedy 1-ply init, as in v6
            if (TIME_MODE) { p.stop_type = p.STOP_TIME; p.num_timeMillis = cu; }
            else           { p.stop_type = p.STOP_COMPUTE; p.num_computeUnits = cu; }
            return new EMCTSAgent(seed, p);
        });

        m.put("TBETS", seed -> new TBETSAgent(seed, tbetsParams(cu, false)));

        m.put("TBETS-rollouts", seed -> {
            TBETSParams p = tbetsParams(cu, true);
            p.rolloutGreedy = false;   // the cheap script; the greedy one cannot search at these budgets
            return new TBETSAgent(seed, p);
        });

        return m;
    }

    private static TBETSParams tbetsParams(int cu, boolean rollouts) {
        TBETSParams p = new TBETSParams();
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        if (TIME_MODE) { p.stop_type = p.STOP_TIME; p.num_timeMillis = cu; }
        else           { p.stop_type = p.STOP_COMPUTE; p.num_computeUnits = cu; }
        // Match EMCTS, which never charges its greedy initialization to the budget. Both agents then
        // get `cu` units of SEARCH, and both initialization costs are logged for auditing.
        p.excludeSeedingFromBudget = true;
        p.useRollouts = rollouts;
        return p;
    }

    // ------------------------------------------------------------------ execution

    private static List<String> runLevel(int cu, List<Scenario> portfolio, Map<Integer, Integer> branching,
                                         int visits, int threads) throws Exception {
        Map<String, java.util.function.LongFunction<Agent>> arms = arms(cu);
        List<Callable<String>> tasks = new ArrayList<>();

        for (Scenario s : portfolio) {
            for (int v = 0; v < visits; v++) {
                for (Map.Entry<String, java.util.function.LongFunction<Agent>> e : arms.entrySet()) {
                    final int visit = v;
                    final String name = e.getKey();
                    final java.util.function.LongFunction<Agent> factory = e.getValue();
                    final int bf = branching.getOrDefault(s.id, -1);
                    tasks.add(() -> runOneTurn(cu, s, bf, visit, name, factory));
                }
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<String> rows = new ArrayList<>(tasks.size());
        try {
            // submit() rather than invokeAll(): invokeAll blocks until the LAST task finishes, so a
            // level that takes half an hour prints nothing at all until it is over. Submitting and
            // then draining the futures in order reports progress as the work actually lands.
            List<Future<String>> futures = new ArrayList<>(tasks.size());
            for (Callable<String> t : tasks) futures.add(pool.submit(t));

            long t0 = System.currentTimeMillis();
            int done = 0, failed = 0;
            for (Future<String> f : futures) {
                String r = f.get();
                rows.add(r);
                if (r.endsWith(",1")) failed++;
                if (++done % 100 == 0 || done == tasks.size()) {
                    long elapsed = System.currentTimeMillis() - t0;
                    long eta = done > 0 ? elapsed * (tasks.size() - done) / done : 0;
                    System.out.println("[TvE]   " + done + "/" + tasks.size() + " turns planned"
                            + "  (" + (elapsed / 1000) + "s elapsed, ~" + (eta / 1000) + "s left"
                            + (failed > 0 ? ", " + failed + " FAILED" : "") + ")");
                }
            }
        } finally {
            pool.shutdown();
        }
        return rows;
    }

    /**
     * One planning episode. The protocol is {@code IsolationPhaseRunnerV6.runOneTurn}'s: same visit
     * seed, same RNG installed into the state, one {@code act()} call, read {@code TurnData[0]}.
     */
    private static String runOneTurn(int cu, Scenario scenario, int branchingFactor, int visit,
                                     String agentName, java.util.function.LongFunction<Agent> factory) {
        long seed = IsolationPhaseRunner.visitSeed(scenario.id, visit);

        String provenance = cu + "," + scenario.id + "," + scenario.source + ","
                + scenario.sourceGameId + "," + scenario.capturedTick + "," + branchingFactor + ","
                + visit + "," + agentName + ",";

        try {
            ComputeMeter.reset();   // per-thread; the agent resets again in planTurn, this is hygiene
            Agent agent = factory.apply(seed);

            GameState gs = scenario.state.copy();
            gs.setRandomGenerator(new Random(seed));
            int activeTribeId = gs.getActiveTribeID();
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
            agent.setPlayerIDs(activeTribeId, allIds);

            // The state as handed over, kept as the heuristic's "old state" reference.
            GameState before = gs.copy();
            StateHeuristic yardstick = new TribesSimpleHeuristic(activeTribeId, allIds);

            ElapsedCpuTimer ect = new ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);

            // PLAY THE WHOLE TURN, do not just ask for one action. Only the first act() searches -
            // every plan-based agent here plans once per tick and replays the cached sequence - so
            // this costs a handful of advances and buys the only cross-agent-comparable number in
            // the file. See PostTurnValue in the class javadoc for why FinalSequenceValue is not it.
            int actions = 0;
            while (!gs.isGameOver() && gs.getActiveTribeID() == activeTribeId && actions < MAX_TURN_ACTIONS) {
                Action a = agent.act(gs, ect);
                if (a == null) break;
                gs.advance(a, true);
                actions++;
                if (a.getActionType() == Types.ACTION.END_TURN) break;
            }
            double postTurn = yardstick.evaluateState(before, gs);

            List<TurnData> turns = agent.getTurnDataLog();
            if (turns.isEmpty()) return provenance + failTail();

            TurnData t = turns.get(0);
            double total = t.getComputeUnits() + Math.max(0.0, t.getInitComputeUnits());
            return provenance
                    + ExpCsv.round(t.getInitialSequenceValue()) + ","
                    + ExpCsv.round(t.getFinalSequenceValue()) + ","
                    + ExpCsv.round(postTurn) + ","
                    + actions + ","
                    + ExpCsv.round(t.getFinalSequenceValue() - t.getInitialSequenceValue()) + ","
                    + ExpCsv.round(t.getAvgNodeValue()) + ","
                    + t.getNodesEvaluated() + "," + t.getUniqueSequences() + ","
                    + t.getFmCalls() + "," + t.getSearchFmCalls() + ","
                    + ExpCsv.round(t.getComputeUnits()) + ","
                    + ExpCsv.round(t.getInitComputeUnits()) + ","
                    + ExpCsv.round(total) + ","
                    + t.getPlanMillis() + "," + t.getInitMillis() + ",0";
        } catch (Throwable ex) {
            synchronized (System.err) {
                System.err.println("[TvE] FAILED " + agentName + " scenario=" + scenario.id
                        + " visit=" + visit + " -> " + ex);
            }
            return provenance + failTail();
        }
    }

    private static String failTail() {
        return "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1";
    }

    // ------------------------------------------------------------------ helpers

    /** BranchingFactor column of the manifest, keyed by scenario id; -1 when absent. */
    private static Map<Integer, Integer> readBranching(File manifest) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8);
            String[] head = lines.get(0).split(",");
            int col = -1;
            for (int i = 0; i < head.length; i++) {
                if (head[i].trim().equals("BranchingFactor")) { col = i; break; }
            }
            if (col < 0) return m;
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",");
                if (f.length <= col) continue;
                m.put(Integer.parseInt(f[0].trim()), Integer.parseInt(f[col].trim()));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static int[] parseInts(String[] args, int from) {
        List<Integer> v = new ArrayList<>();
        for (int i = from; i < args.length; i++) v.add(Integer.parseInt(args[i].trim()));
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    static String f2(double d) { return String.format(Locale.US, "%.2f", d); }
}
