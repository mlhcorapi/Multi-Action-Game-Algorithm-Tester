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
 * Time budgets (not fmcalls) are used here so every agent gets an EQUAL per-turn budget:
 * the engine shares each turn's time across all of a turn's actions, so the per-action
 * baselines (MCTS/OEP) and the plan-once experimental agents are budgeted fairly. (Phase 1
 * and Phase 3 use fmcalls, where all participants are plan-once and fmcalls is already fair.)
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
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
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
        StringBuilder ratioDesc = new StringBuilder();
        for (int ms : timesMs) {
            int best = bestRatioByLevel.getOrDefault(ms + "ms", 5);
            int[] rs = (best == 5) ? new int[]{5} : new int[]{5, best};
            ratiosByMs.put(ms, rs);
            ratioDesc.append(ms).append("ms->").append(Arrays.toString(rs)).append(" ");
        }

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 2 - Final Win Rates\n" +
                "mode=CAPITALS turnLimit=" + turnLimit + " gamesPerMatchup=" + numGames + " threads=" + threads + "\n" +
                "tribes=IMPERIUS vs IMPERIUS2\n" +
                "budget=TIME (STOP_TIME, shared per turn) timesMs=" + Arrays.toString(timesMs) + "\n" +
                "ratios(Expansion/Combo) per level {5, best-from-Phase3}: " + ratioDesc.toString().trim() + "\n" +
                "baselines=MCTS,OEP,EMCTS  experimental=CrossoverExpansion,CrossoverBB,CrossoverCombo\n");

        String[] baselineNames = {"MCTS", "OEP", "EMCTS"};
        int totalMatchups = 0;
        for (int ms : timesMs) totalMatchups += (1 + 2 * ratiosByMs.get(ms).length) * baselineNames.length;
        int matchupIdx = 0;

        for (int ms : timesMs) {
            // TIME budget shared across each turn's actions; agents self-limit via STOP_TIME
            // on the per-turn timer (the engine carries the remaining time across actions).
            Constants.TURN_TIME_MILLIS = ms;
            Constants.TURN_TIME_LIMITED = false;
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
                    System.out.println("[Phase2] (" + matchupIdx + "/" + totalMatchups + ") RUN " + key + " (" + numGames + " games, " + ms + "ms/turn)");

                    AgentSpec expSpec = buildExperimentalTime(expName, ratio);
                    AgentSpec baseSpec = buildBaselineTime(baseName);
                    List<AgentSpec> specs = Arrays.asList(expSpec, baseSpec);

                    List<GameResult> results = ExperimentEngine.runMatchup(
                            specs, tribes, Types.GAME_MODE.CAPITALS, numGames, 1,
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
                                    ExpCsv.round(avg[3]) + "," + res.endTurn);
                        }
                    }
                    ExpCsv.append(gamesCsv,
                            "Level,Matchup,GameId,AgentName,Ratio,Won,Winner,AvgInitialSeq,AvgFinalSeq,AvgNodesVisited,AvgSequencesEvaluated,EndTurn",
                            rows);
                    ExpCsv.markCompleted(ckpt, key);
                }
            }
        }
        System.out.println("[Phase2] Complete. Output in " + dir.getPath());
    }

    /** Per-game averages over an agent's turns: [avgInitial, avgFinal, avgNodes, avgSequences]. */
    private static double[] averages(GameResult.AgentRecord rec) {
        if (rec.turns.isEmpty()) return new double[]{0, 0, 0, 0};
        double si = 0, sf = 0, sn = 0, sq = 0;
        for (TurnData t : rec.turns) {
            si += t.getInitialSequenceValue();
            sf += t.getFinalSequenceValue();
            sn += t.getNodesVisited();
            sq += t.getSequencesEvaluated();
        }
        int n = rec.turns.size();
        return new double[]{si / n, sf / n, sn / n, sq / n};
    }

    private static AgentSpec buildExperimentalTime(String name, int ratio) {
        switch (name) {
            case "CrossoverExpansion": return ExpAgents.crossoverExpansionTime(ratio);
            case "CrossoverBB":        return ExpAgents.crossoverBBTime();
            case "CrossoverCombo":     return ExpAgents.crossoverComboTime(ratio);
            default: throw new IllegalArgumentException("Unknown experimental agent: " + name);
        }
    }

    private static AgentSpec buildBaselineTime(String name) {
        switch (name) {
            case "MCTS":  return ExpAgents.mctsTime();
            case "OEP":   return ExpAgents.oepTime();
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
