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
 * 100 random-map games per matchup, at 3 fmcall budgets. CrossoverExpansion/Combo use the
 * best ratio from Phase 1 (and ratio 5 too if the best was 20).
 *
 * Output (per budget level): one game-level CSV, long format (one row per agent per game)
 * with the winner and per-game AVERAGES of initial/final sequence value and nodes visited.
 */
public class Phase2Runner {

    // Calibrated fmcall budgets ~200/500/1000ms on the target machine (~30.8 fmcalls/ms; see Calibrate).
    public static Budget[] defaultLevels() {
        return new Budget[]{ new Budget("200ms", 6000), new Budget("500ms", 15000), new Budget("1000ms", 30000) };
    }

    public static void run(int numGames, int turnLimit, Budget[] levels, int[] ratios, int threads, String outDir) {
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
                "ratios(for Expansion/Combo)=" + Arrays.toString(ratios) + "\n" +
                "budgets(fmcalls)=" + budgetsStr(levels) + "\n" +
                "baselines=MCTS,OEP,EMCTS  experimental=CrossoverExpansion,CrossoverBB,CrossoverCombo\n");

        String[] baselineNames = {"MCTS", "OEP", "EMCTS"};

        for (Budget level : levels) {
            File gamesCsv = new File(dir, "Phase2_games_" + level.name + ".csv");

            // Build the experimental side: BB once (ratio-independent); Expansion & Combo per ratio.
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
                    String key = "level=" + level.name + ";" + matchup;
                    if (done.contains(key)) {
                        System.out.println("[Phase2] SKIP (done): " + key);
                        continue;
                    }
                    System.out.println("[Phase2] RUN " + key + " (" + numGames + " games)");

                    AgentSpec expSpec = buildExperimental(expName, level.fmcalls, ratio);
                    AgentSpec baseSpec = buildBaseline(baseName, level.fmcalls);
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
                            rows.add(level.name + "," + matchup + "," + res.gameId + "," + rec.name + "," +
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

    private static AgentSpec buildExperimental(String name, int fmcalls, int ratio) {
        switch (name) {
            case "CrossoverExpansion": return ExpAgents.crossoverExpansion(fmcalls, ratio);
            case "CrossoverBB":        return ExpAgents.crossoverBB(fmcalls);
            case "CrossoverCombo":     return ExpAgents.crossoverCombo(fmcalls, ratio);
            default: throw new IllegalArgumentException("Unknown experimental agent: " + name);
        }
    }

    private static AgentSpec buildBaseline(String name, int fmcalls) {
        switch (name) {
            case "MCTS":  return ExpAgents.mcts(fmcalls);
            case "OEP":   return ExpAgents.oep(fmcalls);
            case "EMCTS": return ExpAgents.emcts(fmcalls);
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

    private static String budgetsStr(Budget[] levels) {
        StringBuilder sb = new StringBuilder();
        for (Budget b : levels) sb.append(b.name).append("=").append(b.fmcalls).append(" ");
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        int[] ratios = ratiosFromPhase1("test_results/phase1");
        run(100, 50, defaultLevels(), ratios, threads, "test_results/phase2");
    }
}
