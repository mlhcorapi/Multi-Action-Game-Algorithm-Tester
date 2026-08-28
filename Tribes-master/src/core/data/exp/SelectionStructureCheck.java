package core.data.exp;

import core.Constants;
import core.Types;
import core.game.Game;
import players.Agent;
import players.DoNothingAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams.SelectionMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asserts what the selection policies ARE, rather than arguing it in a comment.
 *
 * <p>The pre-2026-08-25 {@code nodeToExpand()} descended best-child-to-leaf with no early stop. That
 * was found and fixed as a bug, and the fix is right — but the reframing that followed is a claim
 * about equivalence, and equivalence claims should be executable:
 *
 * <ul>
 *   <li><b>LEGACY == COMMA for k &gt; 1.</b> When every expanded node has exactly k children, a
 *       descent that always takes the best child returns the best of the newest generation, and takes
 *       it whether or not it beat its parent. That is (1,k) comma selection, arrived at by accident.</li>
 *   <li><b>LEGACY == DRIFT at k = 1.</b> With one child per node the tree is a chain, the descent is
 *       forced, and it returns the node just created: no selection at all, a neutral random walk.</li>
 *   <li><b>LEGACY branches exactly k; PLUS branches far wider.</b> The structural signature. Plus
 *       leaves the incumbent in place whenever a generation fails to improve, so children pile up on
 *       one node; legacy relocates every generation, so no node ever gets a second one.</li>
 * </ul>
 *
 * <p>The first two are checked by playing complete games and comparing the <i>whole outcome</i> —
 * scores, telemetry, branching — not a summary statistic. Two policies that are the same policy
 * produce byte-identical games from the same seed; anything less is not equivalence.
 *
 * <p><b>Why this matters for the experiment.</b> If LEGACY is COMMA, then "the old code beat the
 * fixed code" is not a bug report, it is a result: comma selection beating plus selection. It makes
 * the substrate an open experimental variable rather than a settled one, which is what Stage B of
 * {@code analysis/AB_EMCTS_R7615_PLAN.md} exists to close.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.SelectionStructureCheck [budgetUnits] [turnLimit]}
 */
public final class SelectionStructureCheck {

    private SelectionStructureCheck() {}

    private static final int DEFAULT_UNITS = 8_000;
    private static final int DEFAULT_TURN_LIMIT = 8;
    private static final long LEVEL_SEED = 100L;
    private static final long AGENT_SEED = 42L;

    private static int failures = 0;

    public static void main(String[] args) {
        int units = args.length > 0 ? Integer.parseInt(args[0].trim()) : DEFAULT_UNITS;
        int turnLimit = args.length > 1 ? Integer.parseInt(args[1].trim()) : DEFAULT_TURN_LIMIT;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.VERBOSE = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.MAX_TURNS_CAPITALS = turnLimit;

        System.out.println("############################################################");
        System.out.println("# SELECTION STRUCTURE CHECK   budget=" + units + "cu  turnLimit=" + turnLimit);
        System.out.println("# Equivalences are asserted on COMPLETE GAMES, not on summary statistics.");
        System.out.println("############################################################");

        Map<String, String> sig = new LinkedHashMap<>();
        for (int k : new int[]{1, 3, 5, 10}) {
            for (SelectionMode m : new SelectionMode[]{
                    SelectionMode.LEGACY, SelectionMode.COMMA, SelectionMode.PLUS, SelectionMode.DRIFT}) {
                String name = m + "_k" + k;
                sig.put(name, signature(m, k, units));
                System.out.printf(Locale.ROOT, "  %-14s %s%n", name, sig.get(name));
            }
        }

        System.out.println("\n=== EQUIVALENCES ===");
        // 1. The reframing: with k > 1 the legacy descent IS comma selection.
        for (int k : new int[]{3, 5, 10}) {
            same("LEGACY_k" + k + " == COMMA_k" + k + "  (the legacy descent is (1,k) comma)",
                    sig.get("LEGACY_k" + k), sig.get("COMMA_k" + k));
        }
        // 2. At k = 1 the descent is forced and there is no selection left to do.
        same("LEGACY_k1 == DRIFT_k1  (one child per node leaves a neutral random walk)",
                sig.get("LEGACY_k1"), sig.get("DRIFT_k1"));

        System.out.println("\n=== NON-EQUIVALENCES (these MUST differ, or an arm is not wired up) ===");
        for (int k : new int[]{3, 5, 10}) {
            differ("PLUS_k" + k + " != COMMA_k" + k, sig.get("PLUS_k" + k), sig.get("COMMA_k" + k));
        }

        System.out.println("\n=== STRUCTURE: children per node ===");
        System.out.println("  Legacy relocates every generation, so no node collects a second"
                + " generation's children: its branching is exactly k. Plus leaves the incumbent in"
                + " place whenever a generation fails to improve, so children pile up on it.");
        for (int k : new int[]{3, 5, 10}) {
            int legacyBranch = branching(SelectionMode.LEGACY, k, units);
            int plusBranch = branching(SelectionMode.PLUS, k, units);
            boolean ok = legacyBranch == k && plusBranch > k;
            System.out.printf(Locale.ROOT, "  k=%-3d legacy maxBranching=%-4d (expect %d)   "
                            + "plus maxBranching=%-5d (expect > %d)   %s%n",
                    k, legacyBranch, k, plusBranch, k, ok ? "ok" : "FAIL");
            if (!ok) failures++;
        }

        System.out.println();
        System.out.println(failures == 0
                ? "VERDICT: PASS - the policies are what the design says they are."
                : "VERDICT: FAIL - " + failures + " assertion(s) broken.");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------

    private static void same(String what, String a, String b) {
        boolean ok = a != null && a.equals(b);
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + what);
        if (!ok) {
            System.out.println("           a = " + a);
            System.out.println("           b = " + b);
            failures++;
        }
    }

    private static void differ(String what, String a, String b) {
        boolean ok = a != null && !a.equals(b);
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + what);
        if (!ok) {
            System.out.println("           both = " + a);
            System.out.println("           Two arms producing identical output is a bug, not thin data.");
            failures++;
        }
    }

    private static ExperimentalEMCTSParams params(SelectionMode mode, int k, int units) {
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = units;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = ExpAgents.DEPTH;
        p.selectionMode = mode;
        p.childrenPerGeneration = k;
        return p;
    }

    /** Plays one complete game and returns everything observable about it. */
    private static String signature(SelectionMode mode, int k, int units) {
        ExperimentalEMCTSAgent agent = new ExperimentalEMCTSAgent(AGENT_SEED, params(mode, k, units));
        int[] scores = play(agent);
        StringBuilder sb = new StringBuilder();
        for (int sc : scores) sb.append(sc).append('|');
        sb.append("branch=").append(agent.getMaxBranching());
        sb.append(" mut=").append(agent.getOperatorTelemetry().mutEvents);
        sb.append(" nodes=").append(agent.getOperatorTelemetry().mutRepairKept
                + agent.getOperatorTelemetry().mutRepairReplaced);
        return sb.toString();
    }

    private static int branching(SelectionMode mode, int k, int units) {
        ExperimentalEMCTSAgent agent = new ExperimentalEMCTSAgent(AGENT_SEED, params(mode, k, units));
        play(agent);
        return agent.getMaxBranching();
    }

    private static int[] play(Agent agent) {
        ArrayList<Agent> players = new ArrayList<>();
        players.add(agent);
        players.add(new DoNothingAgent(7));
        Game g = new Game();
        g.init(players, LEVEL_SEED, new Types.TRIBE[]{Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2},
                AGENT_SEED, Types.GAME_MODE.CAPITALS);
        g.run(null, null);
        return g.getScores();
    }
}
