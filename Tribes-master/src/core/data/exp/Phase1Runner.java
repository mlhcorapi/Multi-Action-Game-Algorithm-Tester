package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Phase 1 - Ratio Analysis. EMCTS vs CrossoverExpansion, Capitals, 50-turn limit,
 * 100 random-map games per (ratio, budget) matchup. Ratios = mutations per crossover.
 * Picks the ratio with the best CrossoverExpansion win-rate vs EMCTS and writes it to
 * phase1_best_ratio.txt.
 *
 * Output (per budget level): turn-level, bridge-burn-level, and game-level CSVs, keyed by
 * Ratio / GameId / AgentName.
 */
public class Phase1Runner {

    public static final int[] DEFAULT_RATIOS = {2, 5, 10, 15, 20};

    // Calibrated fmcall budgets ~500ms / ~2000ms on the target machine (~30.8 fmcalls/ms; see Calibrate).
    public static Budget[] defaultLevels() {
        return new Budget[]{ new Budget("500ms", 15000), new Budget("2000ms", 61000) };
    }

    public static void run(int numGames, int turnLimit, Budget[] levels, int[] ratios, int threads, String outDir) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.MAX_TURNS_CAPITALS = turnLimit;
        // fmcall-budgeted phase: ensure the engine does not impose any time cutoff.
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 1 - Ratio Analysis\n" +
                "mode=CAPITALS turnLimit=" + turnLimit + " gamesPerMatchup=" + numGames + " threads=" + threads + "\n" +
                "tribes=IMPERIUS vs IMPERIUS2\n" +
                "ratios=" + Arrays.toString(ratios) + "\n" +
                "budgets(fmcalls)=" + budgetsStr(levels) + "\n" +
                "agents=EMCTS vs CrossoverExpansion\n");

        for (Budget level : levels) {
            File turnsCsv = new File(dir, "Phase1_turns_" + level.name + ".csv");
            File bbCsv = new File(dir, "Phase1_bridgeburns_" + level.name + ".csv");
            File gamesCsv = new File(dir, "Phase1_games_" + level.name + ".csv");

            for (int ratio : ratios) {
                String key = "level=" + level.name + ";ratio=" + ratio;
                if (done.contains(key)) {
                    System.out.println("[Phase1] SKIP (done): " + key);
                    continue;
                }
                System.out.println("[Phase1] RUN " + key + " (" + numGames + " games)");

                List<AgentSpec> specs = Arrays.asList(
                        ExpAgents.emcts(level.fmcalls),
                        ExpAgents.crossoverExpansion(level.fmcalls, ratio));

                final int fRatio = ratio;
                List<GameResult> results = ExperimentEngine.runMatchup(
                        specs, tribes, Types.GAME_MODE.CAPITALS, numGames, 1,
                        g -> 1_000_000L + g, g -> 2_000_000L + g, g -> 3_000_000L + g,
                        threads,
                        (completed, total, r) -> {
                            if (completed % 10 == 0 || completed == total)
                                System.out.println("    ratio " + fRatio + ": " + completed + "/" + total);
                        });

                List<String> turnRows = new ArrayList<>();
                List<String> bbRows = new ArrayList<>();
                List<String> gameRows = new ArrayList<>();
                for (GameResult res : results) {
                    for (GameResult.AgentRecord rec : res.agents) {
                        for (TurnData t : rec.turns) {
                            turnRows.add(ratio + "," + res.gameId + "," + rec.name + "," +
                                    t.getTurnNumber() + "," + t.getScoreAtTurnStart() + "," +
                                    ExpCsv.round(t.getInitialSequenceValue()) + "," +
                                    ExpCsv.round(t.getFinalSequenceValue()) + "," + t.getNodesVisited());
                        }
                        for (BridgeBurnEventData b : rec.bridgeBurns) {
                            bbRows.add(ratio + "," + res.gameId + "," + rec.name + "," +
                                    b.getTurnNumber() + "," + b.getBridgeBurningPhaseNumber() + "," +
                                    ExpCsv.round(b.getBestSequenceValue()) + "," +
                                    b.getFmCallsElapsed() + "," + b.getExpansionPhaseTimestamp());
                        }
                    }
                    gameRows.add(ratio + "," + res.gameId + "," + res.winnerName + "," +
                            scoreOf(res, "EMCTS") + "," + scoreOf(res, "CrossoverExpansion") + "," + res.endTurn);
                }
                ExpCsv.append(turnsCsv, "Ratio,GameId,AgentName,TurnNumber,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,NodesVisited", turnRows);
                ExpCsv.append(bbCsv, "Ratio,GameId,AgentName,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp", bbRows);
                ExpCsv.append(gamesCsv, "Ratio,GameId,Winner,EMCTSScore,CrossoverExpansionScore,EndTurn", gameRows);

                ExpCsv.markCompleted(ckpt, key);
            }
        }

        computeBestRatio(dir, levels, ratios);
        System.out.println("[Phase1] Complete. Output in " + dir.getPath());
    }

    /** Reads the game-level CSVs (resume-safe) and writes phase1_best_ratio.txt. */
    private static void computeBestRatio(File dir, Budget[] levels, int[] ratios) {
        Map<Integer, int[]> tally = new TreeMap<>(); // ratio -> [expWins, totalGames]
        for (int r : ratios) tally.put(r, new int[]{0, 0});

        for (Budget level : levels) {
            File gamesCsv = new File(dir, "Phase1_games_" + level.name + ".csv");
            if (!gamesCsv.exists()) continue;
            try {
                List<String> lines = Files.readAllLines(gamesCsv.toPath());
                for (int i = 1; i < lines.size(); i++) {
                    String[] f = lines.get(i).split(",");
                    if (f.length < 3) continue;
                    int ratio = Integer.parseInt(f[0].trim());
                    String winner = f[2].trim();
                    int[] t = tally.get(ratio);
                    if (t == null) continue;
                    t[1]++;
                    if (winner.equals("CrossoverExpansion")) t[0]++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Phase 1 - CrossoverExpansion win-rate vs EMCTS (aggregated across budgets)\n");
        int bestRatio = ratios.length > 0 ? ratios[0] : 5;
        double bestRate = -1;
        for (Map.Entry<Integer, int[]> e : tally.entrySet()) {
            int wins = e.getValue()[0], total = e.getValue()[1];
            double rate = total > 0 ? (double) wins / total : 0;
            sb.append(String.format("  ratio %2d : %d/%d = %.1f%%%n", e.getKey(), wins, total, rate * 100));
            if (rate > bestRate) { bestRate = rate; bestRatio = e.getKey(); }
        }
        sb.append("BEST_RATIO=").append(bestRatio).append("\n");
        // If 20 wins, crossover is essentially unhelpful; Phase 2 should also test 5 (see runner).
        sb.append("NOTE=if BEST_RATIO==20, Phase 2 will also evaluate ratio 5 as a sanity baseline.\n");
        ExpCsv.writeText(new File(dir, "phase1_best_ratio.txt"), sb.toString());
        System.out.println("[Phase1] " + sb);
    }

    private static int scoreOf(GameResult res, String agentName) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(agentName)) return rec.finalScore;
        return -1;
    }

    private static String budgetsStr(Budget[] levels) {
        StringBuilder sb = new StringBuilder();
        for (Budget b : levels) sb.append(b.name).append("=").append(b.fmcalls).append(" ");
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        run(100, 50, defaultLevels(), DEFAULT_RATIOS, threads, "test_results/phase1");
    }
}
