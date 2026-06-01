package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.TurnData;

import java.io.File;
import java.nio.file.Files;
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

    public static int[] defaultTimesMs() {
        return new int[]{200, 500, 1000};
    }

    public static void run(int numGames, int turnLimit, int[] timesMs, int[] ratios, int threads, String outDir) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.MAX_TURNS_CAPITALS = turnLimit;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 2 - Final Win Rates\n" +
                "mode=CAPITALS turnLimit=" + turnLimit + " gamesPerMatchup=" + numGames + " threads=" + threads + "\n" +
                "tribes=IMPERIUS vs IMPERIUS2\n" +
                "budget=TIME (STOP_TIME, shared per turn) timesMs=" + Arrays.toString(timesMs) + "\n" +
                "ratios(for Expansion/Combo)=" + Arrays.toString(ratios) + "\n" +
                "baselines=MCTS,OEP,EMCTS  experimental=CrossoverExpansion,CrossoverBB,CrossoverCombo\n");

        String[] baselineNames = {"MCTS", "OEP", "EMCTS"};
        int totalMatchups = timesMs.length * (1 + 2 * ratios.length) * baselineNames.length, matchupIdx = 0;

        for (int ms : timesMs) {
            // TIME budget shared across each turn's actions; agents self-limit via STOP_TIME
            // on the per-turn timer (the engine carries the remaining time across actions).
            Constants.TURN_TIME_MILLIS = ms;
            Constants.TURN_TIME_LIMITED = false;
            String levelName = ms + "ms";
            File gamesCsv = new File(dir, "Phase2_games_" + levelName + ".csv");

            // Experimental side: BB once (ratio-independent); Expansion & Combo per ratio.
            List<String[]> experimental = new ArrayList<>(); // {label, ratioLabel}
            experimental.add(new String[]{"CrossoverBB", "NA"});
            for (int r : ratios) {
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
                                    res.endTurn);
                        }
                    }
                    ExpCsv.append(gamesCsv,
                            "Level,Matchup,GameId,AgentName,Ratio,Won,Winner,AvgInitialSeq,AvgFinalSeq,AvgNodesVisited,EndTurn",
                            rows);
                    ExpCsv.markCompleted(ckpt, key);
                }
            }
        }
        System.out.println("[Phase2] Complete. Output in " + dir.getPath());
    }

    /** Per-game averages over an agent's turns: [avgInitial, avgFinal, avgNodes]. */
    private static double[] averages(GameResult.AgentRecord rec) {
        if (rec.turns.isEmpty()) return new double[]{0, 0, 0};
        double si = 0, sf = 0, sn = 0;
        for (TurnData t : rec.turns) {
            si += t.getInitialSequenceValue();
            sf += t.getFinalSequenceValue();
            sn += t.getNodesVisited();
        }
        int n = rec.turns.size();
        return new double[]{si / n, sf / n, sn / n};
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

    /** Reads BEST_RATIO from phase1_best_ratio.txt; if it is 20, also returns 5. Default {5}. */
    public static int[] ratiosFromPhase1(String phase1Dir) {
        File f = new File(phase1Dir, "phase1_best_ratio.txt");
        int best = 5;
        if (f.exists()) {
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (line.startsWith("BEST_RATIO=")) best = Integer.parseInt(line.substring("BEST_RATIO=".length()).trim());
                }
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            System.out.println("[Phase2] WARNING: " + f.getPath() + " not found; defaulting ratio to 5.");
        }
        return best == 20 ? new int[]{20, 5} : new int[]{best};
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        int[] ratios = ratiosFromPhase1("test_results/phase1");
        run(100, 50, defaultTimesMs(), ratios, threads, "test_results/phase2");
    }
}
