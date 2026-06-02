package core.data.exp;

import core.Constants;
import core.Types;
import core.game.ComputeMeter;
import core.game.Game;
import players.Agent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;

import java.util.ArrayList;

/**
 * Calibrates the ComputeMeter weights by timing a real EMCTS-family search. Measures per-op wall-clock
 * (advance / copy / eval) and prints the relative weights (advance = 1.0) plus the suggested
 * compute-unit budgets for the 500/1000/5000 ms brackets. Runs single-threaded ON THE MAIN THREAD so
 * the thread-local meter accumulates here.
 *
 * After running: bake the printed W_COPY / W_EVAL into ComputeMeter, and the printed budgets into
 * Phase3Runner.defaultLevels(). Re-run on a new machine (weights/budgets are hardware-specific, like
 * the old fmcalls-per-ms calibration).
 */
public class ComputeCalibrate {

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};
        int budget = 30000;

        // Representative EMCTS-family configs (cover standard + crossover + tournament work).
        AgentSpec[] specs = {
                ExpAgents.crossoverExpansion(budget, 5),
                ExpAgents.crossoverBB(budget),
                ExpAgents.crossoverCombo(budget, 5),
        };

        ExperimentalEMCTSAgent.PROFILE_FM = true; // advance time (agent's gs.advance() wrapper)
        ComputeMeter.PROFILE = true;              // copy + eval time
        ExperimentalEMCTSAgent.FM_NANOS = 0;
        ExperimentalEMCTSAgent.PLAN_NANOS = 0;
        ComputeMeter.reset();
        ComputeMeter.resetNanos();

        for (AgentSpec spec : specs) {
            ArrayList<Agent> players = new ArrayList<>();
            players.add(spec.create(100L));
            players.add(ExpAgents.passiveOpponent().create(100L));
            Game game = new Game();
            game.init(players, 777_777L, tribes, 888_888L, Types.GAME_MODE.SCORE);
            game.run(null, null);
        }

        ComputeMeter.PROFILE = false;
        ExperimentalEMCTSAgent.PROFILE_FM = false;

        long adv = ComputeMeter.advances(), cop = ComputeMeter.copies(), ev = ComputeMeter.evals();
        double tAdv = adv > 0 ? (double) ExperimentalEMCTSAgent.FM_NANOS / adv : 0;
        double tCopy = cop > 0 ? (double) ComputeMeter.copyNanos() / cop : 0;
        double tEval = ev > 0 ? (double) ComputeMeter.evalNanos() / ev : 0;
        double wCopy = tAdv > 0 ? tCopy / tAdv : 0;
        double wEval = tAdv > 0 ? tEval / tAdv : 0;

        System.out.println("==================== ComputeMeter calibration (EMCTS family) ====================");
        System.out.printf("counts        : advances=%d  copies=%d  evals=%d%n", adv, cop, ev);
        System.out.printf("per-op (ns)   : advance=%.1f  copy=%.1f  eval=%.1f%n", tAdv, tCopy, tEval);
        System.out.printf("WEIGHTS       : W_ADVANCE=1.0  W_COPY=%.4f  W_EVAL=%.4f%n", wCopy, wEval);
        double uAdv = adv, uCopy = cop * wCopy, uEval = ev * wEval, uTot = uAdv + uCopy + uEval;
        if (uTot > 0)
            System.out.printf("compute share : advance=%.0f%%  copy=%.0f%%  eval=%.0f%%%n",
                    100 * uAdv / uTot, 100 * uCopy / uTot, 100 * uEval / uTot);
        System.out.println("suggested compute-unit budgets (1 unit = one advance's time):");
        for (int ms : new int[]{500, 1000, 5000}) {
            double units = tAdv > 0 ? ms * 1e6 / tAdv : 0;
            System.out.printf("  %5dms -> %.0f units%n", ms, units);
        }
    }
}
