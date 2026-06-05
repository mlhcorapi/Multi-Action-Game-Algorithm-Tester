package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.TurnData;

import java.io.File;
import java.util.*;

/**
 * Phase 2 - Final Win Rates. Round-robin of the 3 experimental agents vs the 3 baselines
 * (9 matchups; experimental agents do NOT play each other), Capitals, 50-turn limit,
 * 100 random-map games per matchup, at 3 thinking-TIME budgets.
 *
 * Time budgets (not compute-units) are used here so every agent gets an EQUAL per-turn budget:
 * the engine shares each turn's time across all of a turn's actions, so the per-action
 * baselines (MCTS/OEP) and the plan-once experimental agents are budgeted fairly. (Phase 3 uses a
 * deterministic compute-unit budget, where all participants are plan-once and the budget is already fair.)
 *
 * Output (per time level): one game-level CSV, long format (one row per agent per game)
 * with the winner and per-game AVERAGES of initial/final sequence value and nodes visited.
 */
public class Phase2Runner {

    // Smallest time level is 500ms (not 200ms): with bridge burns fixed at 20, the 20 mandatory
    // crossover tournaments dominate a ~200ms turn, so the crossover agents could not fit them plus
    // meaningful search and would overrun the shared per-turn time. At 500ms+ the burns are a modest
    // share of the turn, so every agent gets a fair, equal per-turn budget. (Add a third level such
    // as 2000 here if a wider time sweep is wanted - it roughly doubles the heaviest matchups' time.)
    public static int[] defaultTimesMs() {
        return new int[]{500, 1000};
    }

    public static void run(int numGames, int turnLimit, int[] timesMs, Map<String, Integer> bestRatioByLevel, int threads, String outDir) {
        run(numGames, turnLimit, timesMs, bestRatioByLevel, threads, outDir,
                new String[]{"MCTS", "OEP", "EMCTS"}, null, null);
    }

    /**
     * @param baselines      baseline opponents to test against (e.g. {"OEP","EMCTS"} to drop the weak MCTS).
     * @param ratiosOverride optional ms -> ratio set for Expansion/Combo (e.g. ALL ratios at 1000ms);
     *                       null or a missing key falls back to the default {5, best-from-Phase3}.
     * @param gamesOverride  optional ms -> games-per-matchup (e.g. 300 at 1000ms); null/missing = numGames.
     */
    public static void run(int numGames, int turnLimit, int[] timesMs, Map<String, Integer> bestRatioByLevel,
                           int threads, String outDir, String[] baselines,
                           Map<Integer, int[]> ratiosOverride, Map<Integer, Integer> gamesOverride) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        // Maps are water-free (LevelGenerator includeWater=false), so penalize the now-useless naval
        // techs (FISHING/SAILING/WHALING/NAVIGATION/AQUATISM) in the SIMPLE heuristic.
        Constants.REMOVE_FISHING = true;
        Constants.MAX_TURNS_CAPITALS = turnLimit;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        // Per-level ratio set for Expansion/Combo: the fixed standard ratio 5, plus the per-budget
        // best ratio chosen in Phase 3 (deduplicated). The best ratio can differ by budget level;
        // a level maps by its name (e.g. "500ms") to the Phase 3 budget level of the same name.
        Map<Integer, int[]> ratiosByMs = new LinkedHashMap<>();
        StringBuilder ratioDesc = new StringBuilder(), gamesDesc = new StringBuilder();
        for (int ms : timesMs) {
            int[] rs;
            if (ratiosOverride != null && ratiosOverride.containsKey(ms)) {
                rs = ratiosOverride.get(ms);                       // e.g. all ratios at 1000ms
            } else {
                int best = bestRatioByLevel.getOrDefault(ms + "ms", 5);
                rs = (best == 5) ? new int[]{5} : new int[]{5, best};
            }
            ratiosByMs.put(ms, rs);
            ratioDesc.append(ms).append("ms->").append(Arrays.toString(rs)).append(" ");
            int g = (gamesOverride != null && gamesOverride.containsKey(ms)) ? gamesOverride.get(ms) : numGames;
            gamesDesc.append(ms).append("ms->").append(g).append("g ");
        }

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 2 - Final Win Rates\n" +
                "mode=CAPITALS turnLimit=" + turnLimit + " gamesPerMatchup=" + gamesDesc.toString().trim() + " threads=" + threads + "\n" +
                "tribes=IMPERIUS vs IMPERIUS2\n" +
                "budget=TIME (STOP_TIME, shared per turn) timesMs=" + Arrays.toString(timesMs) + "\n" +
                "ratios(Expansion/Combo) per level: " + ratioDesc.toString().trim() + "\n" +
                "baselines=" + String.join(",", baselines) + "  experimental=CrossoverExpansion,CrossoverBB,CrossoverCombo\n");

        String[] baselineNames = baselines;
        int totalMatchups = 0;
        for (int ms : timesMs) totalMatchups += (1 + 2 * ratiosByMs.get(ms).length) * baselineNames.length;
        int matchupIdx = 0;

        for (int ms : timesMs) {
            // TIME budget shared across each turn's actions; agents self-limit via STOP_TIME
            // on the per-turn timer (the engine carries the remaining time across actions).
            Constants.TURN_TIME_MILLIS = ms;
            Constants.TURN_TIME_LIMITED = false;
            int levelGames = (gamesOverride != null && gamesOverride.containsKey(ms)) ? gamesOverride.get(ms) : numGames;
            String levelName = ms + "ms";
            File gamesCsv = new File(dir, "Phase2_games_" + levelName + ".csv");

            // Experimental side: BB once (ratio-independent); Expansion & Combo at each ratio for this level.
            List<String[]> experimental = new ArrayList<>(); // {label, ratioLabel}
            experimental.add(new String[]{"CrossoverBB", "NA"});
            for (int r : ratiosByMs.get(ms)) {
                experimental.add(new String[]{"CrossoverExpansion", String.valueOf(r)});
                experimental.add(new String[]{"CrossoverCombo", String.valueOf(r)});
            }

            for (String[] exp : experimental) {
                String expName = exp[0];
                String ratioLabel = exp[1];
                int ratio = ratioLabel.equals("NA") ? 5 : Integer.parseInt(ratioLabel);

                for (String baseName : baselineNames) {
                    String matchup = expName + (ratioLabel.equals("NA") ? "" : "_r" + ratioLabel) + "_vs_" + baseName;
                    matchupIdx++;
                    String key = "level=" + levelName + ";" + matchup;
                    if (done.contains(key)) {
                        System.out.println("[Phase2] (" + matchupIdx + "/" + totalMatchups + ") SKIP (done): " + key);
                        continue;
                    }
                    System.out.println("[Phase2] (" + matchupIdx + "/" + totalMatchups + ") RUN " + key + " (" + levelGames + " games, " + ms + "ms/turn)");

                    AgentSpec expSpec = buildExperimentalTime(expName, ratio);
                    AgentSpec baseSpec = buildBaselineTime(baseName, ms);
                    List<AgentSpec> specs = Arrays.asList(expSpec, baseSpec);

                    List<GameResult> results = ExperimentEngine.runMatchup(
                            specs, tribes, Types.GAME_MODE.CAPITALS, levelGames, 1,
                            g -> 1_000_000L + g, g -> 2_000_000L + g, g -> 3_000_000L + g,
                            threads,
                            (completed, total, r) -> {
                                if (completed % 20 == 0 || completed == total)
                                    System.out.println("    " + matchup + ": " + completed + "/" + total);
                            });

                    List<String> rows = new ArrayList<>();
                    for (GameResult res : results) {
                        for (GameResult.AgentRecord rec : res.agents) {
                            double[] avg = averages(rec);
                            rows.add(levelName + "," + matchup + "," + res.gameId + "," + rec.name + "," +
                                    ratioLabel + "," + (rec.name.equals(res.winnerName) ? 1 : 0) + "," +
                                    res.winnerName + "," +
                                    ExpCsv.round(avg[0]) + "," + ExpCsv.round(avg[1]) + "," + ExpCsv.round(avg[2]) + "," +
                                    ExpCsv.round(avg[3]) + "," + ExpCsv.round(avg[4]) + "," + ExpCsv.round(avg[5]) + "," + res.endTurn);
                        }
                    }
                    ExpCsv.append(gamesCsv,
                            "Level,Matchup,GameId,AgentName,Ratio,Won,Winner,AvgInitialSeq,AvgFinalSeq,AvgNodeValue,AvgFmCalls,AvgNodesEvaluated,AvgUniqueSequences,EndTurn",
                            rows);
                    ExpCsv.markCompleted(ckpt, key);
                }
            }
        }
        System.out.println("[Phase2] Complete. Output in " + dir.getPath());
    }

    /** Per-game averages over an agent's turns:
     *  [avgInitial, avgFinal, avgNodeValue, avgFmCalls, avgNodesEvaluated, avgUniqueSequences]. */
    private static double[] averages(GameResult.AgentRecord rec) {
        if (rec.turns.isEmpty()) return new double[]{0, 0, 0, 0, 0, 0};
        double si = 0, sf = 0, sav = 0, sfm = 0, sne = 0, suq = 0;
        for (TurnData t : rec.turns) {
            si += t.getInitialSequenceValue();
            sf += t.getFinalSequenceValue();
            sav += t.getAvgNodeValue();
            sfm += t.getFmCalls();
            sne += t.getNodesEvaluated();
            suq += t.getUniqueSequences();
        }
        int n = rec.turns.size();
        return new double[]{si / n, sf / n, sav / n, sfm / n, sne / n, suq / n};
    }

    private static AgentSpec buildExperimentalTime(String name, int ratio) {
        switch (name) {
            case "CrossoverExpansion": return ExpAgents.crossoverExpansionTime(ratio);
            case "CrossoverBB":        return ExpAgents.crossoverBBTime();
            case "CrossoverCombo":     return ExpAgents.crossoverComboTime(ratio);
            default: throw new IllegalArgumentException("Unknown experimental agent: " + name);
        }
    }

    private static AgentSpec buildBaselineTime(String name, int ms) {
        switch (name) {
            case "MCTS":  return ExpAgents.mctsTime();
            case "OEP":   return ExpAgents.oepTime(ms); // population/tournament tuned per time budget
            case "EMCTS": return ExpAgents.emctsTime();
            default: throw new IllegalArgumentException("Unknown baseline agent: " + name);
        }
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        // The crossover ratio is selected per budget level in Phase 3 (low-variance isolated setup,
        // by average FinalSequenceValue), so Phase 3 must run before Phase 2.
        Map<String, Integer> bestByLevel = Phase3Runner.bestRatiosByLevel("test_results/phase3");
        run(100, 50, defaultTimesMs(), bestByLevel, threads, "test_results/phase2");
    }
}
