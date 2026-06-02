package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;

import java.util.*;

/**
 * Diagnostic (not part of the experiment): verifies that after fixing bridge burns at 20, every agent
 * performs ~20 burns per turn at the REAL compute-unit budget levels (STOP_COMPUTE; see ComputeMeter).
 * The equalised quantity is now COMPUTE-UNITS, not fmcalls, so the per-turn `FmCalls` it prints is
 * expected to DIFFER across agents (the crossover variants spend their units with a different
 * advance/copy/eval mix) - that demand gap is the efficiency cost under study, not a fairness bug.
 * Prints per-agent per-turn averages of FmCalls, UniqueSequences, and max bridge-burn phase number.
 */
public class BurnBudgetCheck {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        int[] budgets = {13400, 26800};   // real Phase-3 compute-unit budgets (500ms, 1000ms)
        int numGames = 2;
        int ratio = 5;

        for (int budget : budgets) {
            System.out.println("==================== budget = " + budget + " compute-units (target) ====================");
            List<Object[]> agents = Arrays.asList(
                    new Object[]{"EMCTS",              ExpAgents.emcts(budget)},
                    new Object[]{"CrossoverExpansion", ExpAgents.crossoverExpansion(budget, ratio)},
                    new Object[]{"CrossoverBB",         ExpAgents.crossoverBB(budget)},
                    new Object[]{"CrossoverCombo",      ExpAgents.crossoverCombo(budget, ratio)});

            for (Object[] entry : agents) {
                String name = (String) entry[0];
                AgentSpec spec = (AgentSpec) entry[1];

                List<GameResult> results = ExperimentEngine.runMatchup(
                        Arrays.asList(spec, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, numGames, 1,
                        g -> FIXED_LEVEL_SEED, g -> FIXED_GAME_SEED, g -> AGENT_SEED_BASE + g,
                        1, (c, t, r) -> {});

                double sumNodes = 0, sumSeqs = 0, sumMaxBurn = 0;
                int turnCount = 0;
                for (GameResult res : results) {
                    GameResult.AgentRecord rec = recordFor(res, spec.name);
                    Map<Integer, Integer> maxBurnByTurn = new HashMap<>();
                    for (BridgeBurnEventData b : rec.bridgeBurns)
                        maxBurnByTurn.merge(b.getTurnNumber(), b.getBridgeBurningPhaseNumber(), Math::max);
                    for (TurnData td : rec.turns) {
                        sumNodes += td.getFmCalls();
                        sumSeqs += td.getUniqueSequences();
                        sumMaxBurn += maxBurnByTurn.getOrDefault(td.getTurnNumber(), 0);
                        turnCount++;
                    }
                }
                if (turnCount == 0) { System.out.printf("  %-20s (no turns)%n", name); continue; }
                System.out.printf("  %-20s avgFmCalls=%8.0f  avgUniqueSeq=%6.1f  avgMaxBurnPhase=%5.1f  (budget=%d units)%n",
                        name, sumNodes / turnCount, sumSeqs / turnCount, sumMaxBurn / turnCount, budget);
            }
            System.out.println();
        }
    }

    private static GameResult.AgentRecord recordFor(GameResult res, String name) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(name)) return rec;
        return res.agents.get(0);
    }
}
