package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.TurnData;
import players.oep.OEPAgent;
import players.oep.OEPParams;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * OEP parameter tuning. Sweeps starting population x tournament size at the given time budgets, in the
 * isolated fixed-seed setup (OEP vs a passive opponent, SCORE mode, 11x11 water-free map), and reports
 * the combo with the best AVERAGE final heuristic value per budget. Kill rate fixed at 0.5 (per the
 * experiment plan). The chosen params are written to oep_best_params.txt and applied to ExpAgents.oepTime().
 *
 * Resumable: each (ms,pop,tour) combo is checkpointed; re-running skips finished combos, and the best is
 * recomputed from the CSV (so skipped combos still count).
 *
 * Args: [threads] [times(csv)] [pops(csv)] [tours(csv)] [games] [outDir]
 *   e.g. retune ONLY 5000ms:  4  5000  20,50,100  3,5,10  10  test_results/v2/oep_tuning
 *
 * NOTE: keep POP_SIZE &lt;= 100. Much larger populations (e.g. 200) can stall OEP when its population
 * converges to fewer distinct individuals than TOURNAMENT_SIZE in the early game. This is also where the
 * optimum lives - tuning has found SMALLER populations better at larger budgets (500ms->100, 1000ms->50) -
 * so pops {20,50,100} cover the useful range.
 *
 * Output: <outDir>/OepTuning.csv (per-combo averages) + <outDir>/oep_best_params.txt.
 */
public class OepTuning {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    public static void main(String[] args) {
        int threads   = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int[] times   = args.length > 1 ? parseInts(args[1]) : new int[]{500, 1000};
        int[] pops    = args.length > 2 ? parseInts(args[2]) : new int[]{20, 50, 100};
        int[] tours   = args.length > 3 ? parseInts(args[3]) : new int[]{3, 5, 10};
        int games     = args.length > 4 ? Integer.parseInt(args[4]) : 10;
        String outDir = args.length > 5 ? args[5] : "test_results/oep_tuning";

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);
        File csv = new File(dir, "OepTuning.csv");

        int total = times.length * pops.length * tours.length, idx = 0;
        for (int ms : times) {
            Constants.TURN_TIME_MILLIS = ms;
            Constants.TURN_TIME_LIMITED = false;
            for (int pop : pops) {
                for (int tour : tours) {
                    idx++;
                    String key = "ms=" + ms + ";pop=" + pop + ";tour=" + tour;
                    if (done.contains(key)) {
                        System.out.println("[OEPTuning] (" + idx + "/" + total + ") SKIP (done): " + key);
                        continue;
                    }
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
                }
            }
        }

        // Best per budget, recomputed from the full CSV so skipped/resumed combos still count.
        String best = bestParamsFromCsv(csv, times, games);
        ExpCsv.writeText(new File(dir, "oep_best_params.txt"), best);
        System.out.println("[OEPTuning] Complete.\n" + best);
    }

    /** Reads OepTuning.csv and returns, per time budget, the (pop, tour) with the highest AvgFinalValue. */
    private static String bestParamsFromCsv(File csv, int[] times, int games) {
        Map<Integer, double[]> bestByMs = new TreeMap<>(); // ms -> [bestVal, pop, tour]
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) { // skip header
                String[] f = lines.get(i).split(",");
                if (f.length < 4) continue;
                int ms = Integer.parseInt(f[0].trim());
                int pop = Integer.parseInt(f[1].trim());
                int tour = Integer.parseInt(f[2].trim());
                double v = Double.parseDouble(f[3].trim());
                double[] cur = bestByMs.get(ms);
                if (cur == null || v > cur[0]) bestByMs.put(ms, new double[]{v, pop, tour});
            }
        } catch (Exception e) { e.printStackTrace(); }

        StringBuilder sb = new StringBuilder();
        sb.append("OEP tuning - best (population, tournament) per time budget, by average final heuristic\n");
        sb.append("value (kill rate fixed 0.5; isolated SCORE setup vs passive opponent, ").append(games).append(" games/combo).\n\n");
        for (int ms : times) {
            double[] b = bestByMs.get(ms);
            if (b == null) { sb.append(ms).append("ms: (no data)\n"); continue; }
            sb.append(ms).append("ms: BEST_POP=").append((int) b[1]).append(" BEST_TOURNAMENT=").append((int) b[2])
              .append("  (avgFinalValue=").append(String.format("%.2f", b[0])).append(")\n");
        }
        return sb.toString();
    }

    private static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }

    private static GameResult.AgentRecord recordFor(GameResult res, String name) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(name)) return rec;
        return res.agents.get(0);
    }
}
