package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.TurnData;
import players.oep.OEPAgent;
import players.oep.OEPParams;

import java.io.File;
import java.util.*;

/**
 * OEP parameter tuning. Sweeps starting population x tournament size at the Phase-2 time budgets,
 * in the isolated fixed-seed setup (OEP vs a passive opponent, SCORE mode, 11x11 water-free map),
 * and reports the combo with the best AVERAGE final heuristic value per budget. Kill rate fixed at
 * 0.5 (per the experiment plan). The chosen params are written to oep_best_params.txt and should be
 * applied to ExpAgents.oepTime() for Phase 2.
 *
 * Output: test_results/oep_tuning/OepTuning.csv (per-combo averages) + oep_best_params.txt.
 */
public class OepTuning {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        int[] times = {500, 1000};
        int[] pops = {20, 50, 100};
        int[] tours = {3, 5, 10};
        int games = 10;

        File dir = new File("test_results/oep_tuning");
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);
        File csv = new File(dir, "OepTuning.csv");

        StringBuilder best = new StringBuilder();
        best.append("OEP tuning - best (population, tournament) per time budget, by average final heuristic\n");
        best.append("value (kill rate fixed 0.5; isolated SCORE setup vs passive opponent, ").append(games).append(" games/combo).\n\n");

        int total = times.length * pops.length * tours.length, idx = 0;
        for (int ms : times) {
            Constants.TURN_TIME_MILLIS = ms;
            Constants.TURN_TIME_LIMITED = false;
            double bestVal = Double.NEGATIVE_INFINITY;
            int bestPop = pops[0], bestTour = tours[0];

            for (int pop : pops) {
                for (int tour : tours) {
                    idx++;
                    String key = "ms=" + ms + ";pop=" + pop + ";tour=" + tour;
                    final int fpop = pop, ftour = tour;
                    AgentSpec oep = new AgentSpec("OEP", seed -> {
                        OEPParams p = new OEPParams();
                        p.stop_type = p.STOP_TIME;
                        p.heuristic_method = p.SIMPLE_HEURISTIC;
                        p.POP_SIZE = fpop;
                        p.TOURNAMENT_SIZE = ftour;
                        p.KILL_RATE = 0.5;
                        return new OEPAgent(seed, p);
                    });

                    System.out.println("[OEPTuning] (" + idx + "/" + total + ") " + key + " (" + games + " games)");
                    List<GameResult> results = ExperimentEngine.runMatchup(
                            Arrays.asList(oep, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, games, 1,
                            g -> FIXED_LEVEL_SEED, g -> FIXED_GAME_SEED, g -> AGENT_SEED_BASE + g, threads, (c, t, r) -> {});

                    double sumFinal = 0, sumScore = 0;
                    int turns = 0, gamesDone = 0;
                    for (GameResult res : results) {
                        GameResult.AgentRecord rec = recordFor(res, "OEP");
                        for (TurnData td : rec.turns) { sumFinal += td.getFinalSequenceValue(); turns++; }
                        sumScore += rec.finalScore;
                        gamesDone++;
                    }
                    double avgFinal = turns > 0 ? sumFinal / turns : 0;
                    double avgScore = gamesDone > 0 ? sumScore / gamesDone : 0;
                    ExpCsv.append(csv, "TimeMs,PopSize,TournamentSize,AvgFinalValue,AvgFinalScore,Turns,Games",
                            Collections.singletonList(ms + "," + pop + "," + tour + "," + ExpCsv.round(avgFinal) + "," +
                                    ExpCsv.round(avgScore) + "," + turns + "," + gamesDone));
                    ExpCsv.markCompleted(ckpt, key);
                    if (avgFinal > bestVal) { bestVal = avgFinal; bestPop = pop; bestTour = tour; }
                }
            }
            best.append(ms).append("ms: BEST_POP=").append(bestPop).append(" BEST_TOURNAMENT=").append(bestTour)
                .append("  (avgFinalValue=").append(String.format("%.2f", bestVal)).append(")\n");
        }

        ExpCsv.writeText(new File(dir, "oep_best_params.txt"), best.toString());
        System.out.println("[OEPTuning] Complete.\n" + best);
    }

    private static GameResult.AgentRecord recordFor(GameResult res, String name) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(name)) return rec;
        return res.agents.get(0);
    }
}
