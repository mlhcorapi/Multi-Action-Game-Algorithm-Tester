package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.OperatorTelemetryData;
import core.data.dto.TurnData;
import core.game.Game;
import players.Agent;
import players.ExperimentalEMCTS.CrossoverBBAgent;
import players.ExperimentalEMCTS.CrossoverComboAgent;
import players.ExperimentalEMCTS.CrossoverExpansionAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the 2026-08 selection fix on live games, one short game per EMCTS-family agent.
 *
 * <p>The pre-fix {@code nodeToExpand()} descended best-child-to-leaf and so could never stop
 * at an interior node: every new child, better or worse, became the next expansion target,
 * degenerating the search tree into a 1-child-per-node mutation chain (an unguided random
 * walk). The fix expands the INCUMBENT — the highest-value node in the live tree — so failed
 * children now pile up as siblings under it.
 *
 * <p>Checks, per agent (EMCTS, CrossoverExpansion, CrossoverBB, CrossoverCombo):
 * <ol>
 *   <li><b>Branching:</b> {@code getMaxBranching() >= 2} — some node holds two or more
 *       children. The pre-fix baseline is exactly 1 (crossover grafts aside), so this is the
 *       direct disproof of the chain shape.</li>
 *   <li><b>Value sanity:</b> FinalSequenceValue >= InitialSequenceValue on every planned turn
 *       (final is the best-ever node of a search seeded with the initial root, so a violation
 *       would mean bestNode tracking broke).</li>
 *   <li><b>Operator liveness:</b> the crossover variants actually ran their operators
 *       (xoEvents > 0 for the expansion variants; tournament crossover events for BB/Combo).</li>
 * </ol>
 *
 * <p>Run: {@code java -cp "out;lib/*" core.data.exp.SelectionFixCheck}
 */
public class SelectionFixCheck {

    private static final int FM_BUDGET = 6000;
    private static final int MAX_TURNS = 8;

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.MAX_TURNS_CAPITALS = MAX_TURNS;
        Types.GAME_MODE gameMode = Types.GAME_MODE.CAPITALS;
        Types.TRIBE[] tribes = new Types.TRIBE[]{Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        boolean allPass = true;
        String[] names = {"EMCTS", "CrossoverExpansion", "CrossoverBB", "CrossoverCombo"};

        for (String name : names) {
            Agent agent = buildAgent(name, 42);

            ArrayList<Agent> matchPlayers = new ArrayList<>();
            matchPlayers.add(agent);
            matchPlayers.add(new players.DoNothingAgent(7));

            Game game = new Game();
            game.init(matchPlayers, 100, tribes, 200, gameMode);
            game.run(null, null);

            int maxBranching;
            OperatorTelemetryData ops;
            List<TurnData> turns;
            if (agent instanceof EMCTSAgent) {
                maxBranching = ((EMCTSAgent) agent).getMaxBranching();
                ops = agent.getOperatorTelemetry();
                turns = agent.getTurnDataLog();
            } else {
                ExperimentalEMCTSAgent e = (ExperimentalEMCTSAgent) agent;
                maxBranching = e.getMaxBranching();
                ops = e.getOperatorTelemetry();
                turns = e.getTurnDataLog();
            }

            int violations = 0;
            double sumImprovement = 0.0;
            for (TurnData t : turns) {
                double d = t.getFinalSequenceValue() - t.getInitialSequenceValue();
                if (d < 0) violations++;
                sumImprovement += d;
            }
            double meanImprovement = turns.isEmpty() ? 0.0 : sumImprovement / turns.size();

            int crossoverEvents = (agent instanceof ExperimentalEMCTSAgent)
                    ? ((ExperimentalEMCTSAgent) agent).getCrossoverDataLog().size() : 0;

            boolean branchingOk = maxBranching >= 2;
            boolean valuesOk = violations == 0;
            boolean opsOk = true;
            if (name.equals("CrossoverExpansion") || name.equals("CrossoverCombo")) {
                opsOk &= ops.xoEvents > 0;
            }
            if (name.equals("CrossoverBB") || name.equals("CrossoverCombo")) {
                opsOk &= crossoverEvents > 0;
            }

            boolean pass = branchingOk && valuesOk && opsOk;
            allPass &= pass;

            System.out.printf(
                    "%-19s | turns=%2d maxBranching=%4d mutEvents=%5d mutGains=%4d xoEvents=%4d bbXo=%3d "
                            + "meanImprove=%8.4f finalLTinitial=%d -> %s%n",
                    name, turns.size(), maxBranching, ops.mutEvents, ops.mutBestGains,
                    ops.xoEvents, crossoverEvents, meanImprovement, violations,
                    pass ? "PASS" : "FAIL");
            if (!branchingOk) System.out.println("    FAIL: tree never branched (maxBranching < 2) — chain shape persists");
            if (!valuesOk)    System.out.println("    FAIL: some turn's final value < initial value — bestNode tracking broke");
            if (!opsOk)       System.out.println("    FAIL: a crossover operator never fired — variant flags not exercised");
        }

        System.out.println(allPass ? "\nSelectionFixCheck: ALL PASS" : "\nSelectionFixCheck: FAILURES (see above)");
        if (!allPass) System.exit(1);
    }

    private static Agent buildAgent(String name, long seed) {
        if (name.equals("EMCTS")) {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_FMCALLS;
            p.num_fmcalls = FM_BUDGET;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;
            return new EMCTSAgent(seed, p);
        }
        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_FMCALLS;
        p.num_fmcalls = FM_BUDGET;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = ExpAgents.DEPTH;
        p.mutationExpansionRatio = 5;
        switch (name) {
            case "CrossoverExpansion": return new CrossoverExpansionAgent(seed, p);
            case "CrossoverBB":        return new CrossoverBBAgent(seed, p);
            case "CrossoverCombo":     return new CrossoverComboAgent(seed, p);
            default: throw new IllegalArgumentException(name);
        }
    }
}
