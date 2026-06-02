package core.data.exp;

import core.Constants;
import core.Types;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;

import java.util.Arrays;

/**
 * One-off measurement: what fraction of EMCTS-family planning wall-clock is the forward model
 * (gs.advance()) versus everything else (state copies, heuristic evaluation, bookkeeping)? This
 * justifies using forward-model calls as the budget currency. Uses the opt-in profiler in
 * ExperimentalEMCTSAgent (PROFILE_FM) and runs a few SCORE turns vs the passive opponent at a real
 * budget. EMCTS (standard) is structurally identical (same advance/eval/copy loop), so this is
 * representative of the whole family.
 */
public class FmComputeProbe {

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        int budget = 30000; // a real Phase-3 budget (1000ms-equivalent)
        Object[][] agents = {
                {"CrossoverExpansion@5", ExpAgents.crossoverExpansion(budget, 5)},
                {"CrossoverBB", ExpAgents.crossoverBB(budget)},
                {"CrossoverCombo@5", ExpAgents.crossoverCombo(budget, 5)},
        };

        ExperimentalEMCTSAgent.PROFILE_FM = true;
        System.out.println("==================== Forward-model share of compute (budget=" + budget + " fmcalls) ====================");
        double totFm = 0, totPlan = 0;
        for (Object[] a : agents) {
            String name = (String) a[0];
            AgentSpec spec = (AgentSpec) a[1];
            ExperimentalEMCTSAgent.FM_NANOS = 0;
            ExperimentalEMCTSAgent.PLAN_NANOS = 0;
            ExperimentEngine.runMatchup(
                    Arrays.asList(spec, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, 2, 1,
                    g -> 777_777L, g -> 888_888L, g -> 100L + g, 1, (c, t, r) -> {});
            double fm = ExperimentalEMCTSAgent.FM_NANOS, plan = ExperimentalEMCTSAgent.PLAN_NANOS;
            totFm += fm; totPlan += plan;
            double pct = plan > 0 ? 100.0 * fm / plan : 0;
            System.out.printf("  %-22s forward-model = %5.1f%% of planning compute  (FM %6.2fs / plan %6.2fs)%n",
                    name, pct, fm / 1e9, plan / 1e9);
        }
        ExperimentalEMCTSAgent.PROFILE_FM = false;
        double overall = totPlan > 0 ? 100.0 * totFm / totPlan : 0;
        System.out.printf("  %-22s forward-model = %5.1f%% of planning compute  (overall)%n", "EMCTS family", overall);
        System.out.println("Remainder is state copies (gs.copy()), heuristic evaluation, and search bookkeeping.");
    }
}
