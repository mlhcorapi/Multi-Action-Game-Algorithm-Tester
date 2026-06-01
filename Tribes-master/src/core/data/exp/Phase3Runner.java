package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Phase 3 - Low-variance isolated analysis: RATIO SELECTION + TRAJECTORY, in one controlled
 * environment. Each EMCTS-family configuration plays {numGames} games against a fixed PASSIVE
 * opponent (DoNothing) on a FIXED map seed, SCORE mode, 30-turn limit; only the tracked agent's
 * RNG seed varies per game (game k uses the same seed across configs), so every configuration
 * faces identical conditions and variance is low.
 *
 * This phase does two jobs:
 *   1. RATIO SELECTION. CrossoverExpansion is swept over several mutation:crossover-expansion
 *      ratios. The "best" ratio is the one with the highest AVERAGE FinalSequenceValue - the best
 *      heuristic value the search reaches. That is exactly what the tree search optimises (it has
 *      no control over in-game score or combat; it only finds the highest-scoring sequence it can),
 *      so search effectiveness, not game outcome, is the right basis for choosing the ratio. To
 *      avoid the budget-scale problem (larger budgets reach higher absolute values), ratios are
 *      RANKED within each budget level and the best average rank wins. Written to
 *      phase3_best_ratio.txt and consumed by Phase 2. (Done in the isolated setup, with no opponent
 *      interference, and without tuning on any agent that Phase 2 later tests against.)
 *   2. TRAJECTORY. Per-bridge-burn curves for the agent families: EMCTS and CrossoverExpansion
 *      (standard burners) and CrossoverBB / CrossoverCombo@best (crossover burners).
 *
 * Output (per budget level):
 *   - Phase3_turns_<level>.csv      : one row per agent per turn, for every config run (incl. all
 *                                     swept ratios). Carries InitialSequenceValue, FinalSequenceValue,
 *                                     NodesVisited, SequencesEvaluated, FinalScore. Drives selection.
 *   - Phase3_standardBB_<level>.csv : per-bridge-burn rows for the standard burners (EMCTS,
 *                                     CrossoverExpansion at every swept ratio).
 *   - Phase3_crossoverBB_<level>.csv: per-bridge-burn rows for the crossover burners (CrossoverBB,
 *                                     CrossoverCombo@best); also carries Raw/PercentageImprovement.
 *   - phase3_best_ratio.txt         : per-ratio average FinalSequenceValue per level + BEST_RATIO.
 */
public class Phase3Runner {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    /** Mutation:crossover-expansion ratios swept for CrossoverExpansion (mutations per crossover). */
    public static final int[] DEFAULT_RATIOS = {2, 5, 10, 15, 20};

    // Calibrated fmcall budgets ~500/1000/5000ms on the target machine (~30.8 fmcalls/ms; see Calibrate).
    // NB: the smallest level is 500ms (not 200ms): with bridge burns fixed at 20, the 20 mandatory
    // crossover tournaments alone cost ~7000 fmcalls, so a 200ms (~6000) budget cannot fit them plus
    // any search - the crossover agents would overshoot it and barely search. At 15000+ the 20 burns
    // are a modest fraction of the budget, so every agent spends the same total fmcalls (see BurnBudgetCheck).
    public static Budget[] defaultLevels() {
        return new Budget[]{ new Budget("500ms", 15000), new Budget("1000ms", 30000), new Budget("5000ms", 154000) };
    }

    public static void run(int numGames, Budget[] levels, int[] ratios, int threads, String outDir) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        // fmcall-budgeted phase: ensure the engine does not impose any time cutoff.
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        // SCORE mode uses Constants.MAX_TURNS (= 30, package-private; referenced as a literal below).
        final int scoreTurnLimit = 30;
        // A 1-tribe map is degenerate (no expansion -> every agent stalls), so we use a 2-tribe map
        // with a fixed PASSIVE opponent: the map then has villages to capture, and the tracked agent
        // develops while the opponent never interferes.
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 3 - Ratio selection + trajectory (tracked agent vs fixed passive opponent)\n" +
                "mode=SCORE turnLimit=" + scoreTurnLimit + " gamesPerConfig=" + numGames + " threads=" + threads + "\n" +
                "tribes=IMPERIUS (tracked agent) vs IMPERIUS2 (DoNothing/passive opponent)\n" +
                "fixedLevelSeed=" + FIXED_LEVEL_SEED + " fixedGameSeed=" + FIXED_GAME_SEED + " agentSeed=" + AGENT_SEED_BASE + "+gameIndex\n" +
                "ratiosSwept(CrossoverExpansion)=" + Arrays.toString(ratios) + "\n" +
                "bestRatio=argmax avg FinalSequenceValue (rank-aggregated across budget levels)\n" +
                "budgets(fmcalls)=" + budgetsStr(levels) + "\n");

        int totalMatchups = levels.length * (3 + ratios.length); // EMCTS + BB + (#ratios)Expansion + Combo
        int[] idx = {0};

        // ---- Pass 1: ratio-independent baselines + the CrossoverExpansion ratio sweep ----
        // (CrossoverCombo needs the selected ratio, so it runs in pass 2.)
        for (Budget level : levels) {
            runConfig(dir, ckpt, done, level, tribes, numGames, threads, idx, totalMatchups,
                    ExpAgents.emcts(level.fmcalls), "EMCTS", -1, false);
            runConfig(dir, ckpt, done, level, tribes, numGames, threads, idx, totalMatchups,
                    ExpAgents.crossoverBB(level.fmcalls), "CrossoverBB", -1, true);
            for (int r : ratios) {
                runConfig(dir, ckpt, done, level, tribes, numGames, threads, idx, totalMatchups,
                        ExpAgents.crossoverExpansion(level.fmcalls, r), "CrossoverExpansion", r, false);
            }
        }

        // ---- Select the best ratio by average FinalSequenceValue (read back from the turns CSVs) ----
        int bestRatio = computeBestRatio(dir, levels, ratios);

        // ---- Pass 2: CrossoverCombo at the selected ratio (crossover burner) ----
        for (Budget level : levels) {
            runConfig(dir, ckpt, done, level, tribes, numGames, threads, idx, totalMatchups,
                    ExpAgents.crossoverCombo(level.fmcalls, bestRatio), "CrossoverCombo", bestRatio, true);
        }

        System.out.println("[Phase3] Complete (best ratio=" + bestRatio + "). Output in " + dir.getPath());
    }

    /** Runs one configuration (tracked agent vs the passive opponent) and appends its per-turn and
     *  per-bridge-burn rows. Resumable via the per-config checkpoint key. */
    private static void runConfig(File dir, File ckpt, Set<String> done, Budget level, Types.TRIBE[] tribes,
                                  int numGames, int threads, int[] idx, int totalMatchups,
                                  AgentSpec spec, String name, int ratio, boolean isCrossoverBB) {
        idx[0]++;
        String ratioLabel = (ratio < 0) ? "NA" : String.valueOf(ratio);
        String label = name + (ratio < 0 ? "" : "@r" + ratio);
        String key = "level=" + level.name + ";agent=" + name + ";ratio=" + ratioLabel;
        if (done.contains(key)) {
            System.out.println("[Phase3] (" + idx[0] + "/" + totalMatchups + ") SKIP (done): " + key);
            return;
        }
        System.out.println("[Phase3] (" + idx[0] + "/" + totalMatchups + ") RUN " + key + " (" + numGames + " games)");

        List<GameResult> results = ExperimentEngine.runMatchup(
                Arrays.asList(spec, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, numGames, 1,
                g -> FIXED_LEVEL_SEED, g -> FIXED_GAME_SEED, g -> AGENT_SEED_BASE + g,
                threads,
                (completed, total, r) -> {
                    if (completed % 20 == 0 || completed == total)
                        System.out.println("    " + label + ": " + completed + "/" + total);
                });

        List<String> turnRows = new ArrayList<>();
        List<String> bbRows = new ArrayList<>();
        for (GameResult res : results) {
            GameResult.AgentRecord rec = recordFor(res, spec.name); // the tracked experimental agent

            // Per-turn rows: drive ratio selection (FinalSequenceValue) + the efficiency story.
            for (TurnData t : rec.turns) {
                turnRows.add(name + "," + ratioLabel + "," + res.gameId + "," + t.getTurnNumber() + "," +
                        t.getScoreAtTurnStart() + "," + ExpCsv.round(t.getInitialSequenceValue()) + "," +
                        ExpCsv.round(t.getFinalSequenceValue()) + "," + t.getNodesVisited() + "," +
                        t.getSequencesEvaluated() + "," + rec.finalScore);
            }

            // Per-bridge-burn rows: the trajectory.
            Map<Integer, Integer> scoreAtTurn = new HashMap<>();
            Map<Integer, Integer> nodesAtTurn = new HashMap<>();
            Map<Integer, Integer> seqAtTurn = new HashMap<>();
            for (TurnData t : rec.turns) {
                scoreAtTurn.put(t.getTurnNumber(), t.getScoreAtTurnStart());
                nodesAtTurn.put(t.getTurnNumber(), t.getNodesVisited());
                seqAtTurn.put(t.getTurnNumber(), t.getSequencesEvaluated());
            }
            Map<String, CrossoverEventData> crossByTurnPhase = new HashMap<>();
            if (isCrossoverBB)
                for (CrossoverEventData c : rec.crossovers)
                    crossByTurnPhase.put(c.getTurnNumber() + ":" + c.getBridgeBurningPhaseNumber(), c);

            for (BridgeBurnEventData b : rec.bridgeBurns) {
                int startScore = scoreAtTurn.getOrDefault(b.getTurnNumber(), -1);
                int nodesThisTurn = nodesAtTurn.getOrDefault(b.getTurnNumber(), -1);
                int seqThisTurn = seqAtTurn.getOrDefault(b.getTurnNumber(), -1);
                StringBuilder row = new StringBuilder();
                row.append(name).append(",").append(ratioLabel).append(",").append(res.gameId).append(",")
                   .append(b.getTurnNumber()).append(",").append(b.getBridgeBurningPhaseNumber()).append(",")
                   .append(ExpCsv.round(b.getBestSequenceValue())).append(",")
                   .append(b.getFmCallsElapsed()).append(",")
                   .append(b.getExpansionPhaseTimestamp()).append(",")
                   .append(startScore).append(",").append(nodesThisTurn).append(",")
                   .append(seqThisTurn).append(",").append(rec.finalScore);
                if (isCrossoverBB) {
                    CrossoverEventData c = crossByTurnPhase.get(b.getTurnNumber() + ":" + b.getBridgeBurningPhaseNumber());
                    double raw = (c != null) ? c.getRawImprovement() : 0.0;
                    double pct = (c != null) ? c.getPercentageImprovement() : 0.0;
                    row.append(",").append(ExpCsv.round(raw)).append(",").append(ExpCsv.round(pct));
                }
                bbRows.add(row.toString());
            }
        }

        ExpCsv.append(new File(dir, "Phase3_turns_" + level.name + ".csv"),
                "AgentName,Ratio,GameId,TurnNumber,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,NodesVisited,SequencesEvaluated,FinalScore",
                turnRows);
        if (isCrossoverBB) {
            ExpCsv.append(new File(dir, "Phase3_crossoverBB_" + level.name + ".csv"),
                    "AgentName,Ratio,GameId,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,NodesVisited,SequencesEvaluated,FinalScore,RawImprovement,PercentageImprovement",
                    bbRows);
        } else {
            ExpCsv.append(new File(dir, "Phase3_standardBB_" + level.name + ".csv"),
                    "AgentName,Ratio,GameId,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,NodesVisited,SequencesEvaluated,FinalScore",
                    bbRows);
        }
        ExpCsv.markCompleted(ckpt, key);
    }

    /**
     * Reads the per-turn CSVs and picks the CrossoverExpansion ratio with the best AVERAGE
     * FinalSequenceValue. Ratios are ranked WITHIN each budget level (rank 1 = highest average)
     * and the best average rank across levels wins - this avoids letting the larger budgets
     * (which reach higher absolute values) dominate the comparison. Writes phase3_best_ratio.txt.
     */
    private static int computeBestRatio(File dir, Budget[] levels, int[] ratios) {
        // level -> ratio -> [sumFinalSeq, count]
        Map<String, Map<Integer, double[]>> agg = new LinkedHashMap<>();
        for (Budget level : levels) {
            Map<Integer, double[]> perRatio = new TreeMap<>();
            agg.put(level.name, perRatio);
            File turns = new File(dir, "Phase3_turns_" + level.name + ".csv");
            if (!turns.exists()) continue;
            try {
                List<String> lines = Files.readAllLines(turns.toPath());
                // header: AgentName,Ratio,GameId,TurnNumber,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,...
                for (int i = 1; i < lines.size(); i++) {
                    String[] f = lines.get(i).split(",");
                    if (f.length < 7) continue;
                    if (!f[0].trim().equals("CrossoverExpansion")) continue;
                    String rl = f[1].trim();
                    if (rl.equals("NA")) continue;
                    int ratio = Integer.parseInt(rl);
                    double finalSeq = Double.parseDouble(f[6].trim());
                    double[] sc = perRatio.computeIfAbsent(ratio, k -> new double[2]);
                    sc[0] += finalSeq;
                    sc[1] += 1;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Map<Integer, Integer> rankSum = new TreeMap<>();
        Map<Integer, Integer> rankCount = new TreeMap<>();
        for (int r : ratios) { rankSum.put(r, 0); rankCount.put(r, 0); }

        StringBuilder sb = new StringBuilder();
        sb.append("Phase 3 - CrossoverExpansion average FinalSequenceValue by ratio (search effectiveness)\n");
        sb.append("Best ratio = best average rank across budget levels (rank 1 = highest avg sequence value).\n\n");
        for (Budget level : levels) {
            Map<Integer, double[]> perRatio = agg.get(level.name);
            List<double[]> avgs = new ArrayList<>(); // {ratio, avgFinalSeq}
            for (int r : ratios) {
                double[] sc = perRatio.get(r);
                double avg = (sc != null && sc[1] > 0) ? sc[0] / sc[1] : Double.NEGATIVE_INFINITY;
                avgs.add(new double[]{r, avg});
            }
            avgs.sort((a, b) -> Double.compare(b[1], a[1])); // descending by avg
            sb.append("  level ").append(level.name).append(":\n");
            int rank = 1;
            for (double[] e : avgs) {
                int r = (int) e[0];
                String avgStr = (e[1] == Double.NEGATIVE_INFINITY) ? "n/a" : String.format("%.2f", e[1]);
                sb.append(String.format("    rank %d : ratio %2d  avgFinalSeq=%s%n", rank, r, avgStr));
                rankSum.merge(r, rank, Integer::sum);
                rankCount.merge(r, 1, Integer::sum);
                rank++;
            }
            sb.append("\n");
        }

        int bestRatio = ratios.length > 0 ? ratios[0] : 5;
        double bestAvgRank = Double.POSITIVE_INFINITY;
        sb.append("Average rank across levels (lower is better):\n");
        for (int r : ratios) {
            double avgRank = rankCount.get(r) > 0 ? (double) rankSum.get(r) / rankCount.get(r) : Double.POSITIVE_INFINITY;
            sb.append(String.format("  ratio %2d : avgRank=%.2f%n", r, avgRank));
            if (avgRank < bestAvgRank) { bestAvgRank = avgRank; bestRatio = r; }
        }
        sb.append("BEST_RATIO=").append(bestRatio).append("\n");
        ExpCsv.writeText(new File(dir, "phase3_best_ratio.txt"), sb.toString());
        System.out.println("[Phase3] " + sb);
        return bestRatio;
    }

    /** Reads BEST_RATIO from phase3_best_ratio.txt; defaults to 5 if absent. Used by Phase 2. */
    public static int bestRatioFromPhase3(String phase3Dir) {
        File f = new File(phase3Dir, "phase3_best_ratio.txt");
        int best = 5;
        if (f.exists()) {
            try {
                for (String line : Files.readAllLines(f.toPath()))
                    if (line.startsWith("BEST_RATIO=")) best = Integer.parseInt(line.substring("BEST_RATIO=".length()).trim());
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            System.out.println("[Phase3] WARNING: " + f.getPath() + " not found; defaulting ratio to 5.");
        }
        return best;
    }

    private static GameResult.AgentRecord recordFor(GameResult res, String name) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(name)) return rec;
        return res.agents.get(0);
    }

    private static String budgetsStr(Budget[] levels) {
        StringBuilder sb = new StringBuilder();
        for (Budget b : levels) sb.append(b.name).append("=").append(b.fmcalls).append(" ");
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        run(50, defaultLevels(), DEFAULT_RATIOS, threads, "test_results/phase3");
    }
}
