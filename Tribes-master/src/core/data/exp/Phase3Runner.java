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
 * The output is split by experiment into four CSVs per budget level:
 *
 *   RATIO TEST (CrossoverExpansion only, swept over {2,5,10,15,20}; the per-level best ratio - the
 *   one with the highest AVERAGE FinalSequenceValue at that level - is what the search optimises and
 *   is written to phase3_best_ratio.txt for Phase 2 and for this phase's Combo trajectory):
 *     - Phase3_ratio_turns_<level>.csv      : one row per turn  (AgentName,Ratio,GameId,TurnNumber,
 *           ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,NodesVisited,SequencesEvaluated,FinalScore)
 *     - Phase3_ratio_trajectory_<level>.csv : one row per bridge-burn (AgentName,Ratio,GameId,TurnNumber,
 *           BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,
 *           NodesVisited,SequencesEvaluated,FinalScore)
 *
 *   FAMILY TRAJECTORY (per-bridge-burn comparison of the bridge-burning mechanism):
 *     - Phase3_standardBB_<level>.csv  : the normal bridge burner, EMCTS (Ratio=NA).
 *     - Phase3_crossoverBB_<level>.csv : the crossover bridge burners, CrossoverBB (Ratio=NA) and
 *           CrossoverCombo@{5,best} - same columns plus RawImprovement,PercentageImprovement.
 */
public class Phase3Runner {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    /** Mutation:crossover-expansion ratios swept for CrossoverExpansion (mutations per crossover). */
    public static final int[] DEFAULT_RATIOS = {2, 5, 10, 15, 20};

    /** Fixed "standard" ratio always evaluated downstream alongside the per-level best. */
    public static final int STANDARD_RATIO = 5;

    /** Which experiment a configuration belongs to (decides which CSV(s) it is written to). */
    private enum Group { RATIO, STANDARD_BB, CROSSOVER_BB }

    // Calibrated COMPUTE-UNIT budgets ~500/1000/5000ms on the target machine (~26.8 units/ms; see
    // ComputeCalibrate). A compute-unit is a weighted advance+copy+eval count (see ComputeMeter), so the
    // budget bounds total planning compute deterministically (machine-/thread-independent), not just fmcalls.
    // NB: the smallest level is 500ms (not 200ms): with bridge burns fixed at 20, the 20 mandatory
    // crossover tournaments alone cost a large share of a small budget, so a 200ms-equivalent budget cannot
    // fit them plus any search - the crossover agents would overshoot it and barely search. At the 500ms+
    // levels the 20 burns are a modest fraction of the budget, so every agent spends the same total
    // compute-units (see BurnBudgetCheck).
    public static Budget[] defaultLevels() {
        // The 5000ms level costs ~5x per game of 1000ms, so it runs fewer games (25 vs 50) to keep
        // total runtime manageable; its ratio choice only feeds this phase's own deep trajectory
        // (Phase 2 has no 5000ms level), so halving its sample does not affect the win-rate phase.
        return new Budget[]{ new Budget("500ms", 13400, 50), new Budget("1000ms", 26800, 50), new Budget("5000ms", 134000, 25) };
    }

    public static void run(int numGames, Budget[] levels, int[] ratios, int threads, String outDir) {
        run(numGames, levels, ratios, threads, outDir, null);
    }

    /**
     * @param extraRatioDir optional path to a PRIOR Phase-3 output dir (e.g. test_results/full/phase3) whose
     *     CrossoverExpansion ratio-test rows are MERGED with this run's when selecting the best ratio per
     *     level. Use it for additive follow-up runs so the ratio is chosen over the combined sample
     *     (e.g. 25 prior + 75 new = 100 games) rather than only this run's delta. Null = this run only.
     */
    public static void run(int numGames, Budget[] levels, int[] ratios, int threads, String outDir, String extraRatioDir) {
        run(numGames, levels, ratios, threads, outDir, extraRatioDir, null);
    }

    /**
     * @param startState optional fixed start position (e.g. a captured turn-3 board, see
     *     {@link core.game.Game#captureAtTick}). When non-null, every Phase-3 game begins from a fresh
     *     copy of it instead of from turn 0, skipping the degenerate opening turns; the tracked agent's
     *     logged turns then start at the captured tick. Null = the usual from-turn-0 run.
     */
    public static void run(int numGames, Budget[] levels, int[] ratios, int threads, String outDir,
                           String extraRatioDir, core.game.GameState startState) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        // Maps are water-free (LevelGenerator includeWater=false), so penalize the now-useless naval
        // techs (FISHING/SAILING/WHALING/NAVIGATION/AQUATISM) in the SIMPLE heuristic.
        Constants.REMOVE_FISHING = true;
        // compute-unit-budgeted phase: ensure the engine does not impose any time cutoff.
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

        StringBuilder gamesPerLevel = new StringBuilder();
        for (Budget b : levels) gamesPerLevel.append(b.name).append("=").append(b.games > 0 ? b.games : numGames).append("g ");

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 3 - Ratio selection + trajectory (tracked agent vs fixed passive opponent)\n" +
                "mode=SCORE turnLimit=" + scoreTurnLimit + " gamesPerConfig(per level)=" + gamesPerLevel.toString().trim() + " threads=" + threads + "\n" +
                "tribes=IMPERIUS (tracked agent) vs IMPERIUS2 (DoNothing/passive opponent)\n" +
                "fixedLevelSeed=" + FIXED_LEVEL_SEED + " fixedGameSeed=" + FIXED_GAME_SEED + " agentSeed=" + AGENT_SEED_BASE + "+gameIndex\n" +
                "ratioTest=CrossoverExpansion swept over " + Arrays.toString(ratios) + " (-> Phase3_ratio_turns/_trajectory)\n" +
                "familyTrajectory=EMCTS (standardBB) + CrossoverBB,CrossoverCombo@{" + STANDARD_RATIO + ",best} (crossoverBB)\n" +
                "bestRatioPerLevel=argmax avg FinalSequenceValue; downstream uses {" + STANDARD_RATIO + ", best} per level\n" +
                "budgets(compute-units)=" + budgetsStr(levels) + "\n" +
                (startState != null ? "startState=FIXED captured board at turn " + startState.getTick()
                        + " (degenerate opening skipped; logged turns begin here)\n" : ""));

        // pass 1 = EMCTS + CrossoverBB + (#ratios) Expansion; pass 2 = Combo at {5,best} per level (<=2 each).
        int totalMatchups = levels.length * (2 + ratios.length) + levels.length * 2;
        int[] idx = {0};

        // ---- Pass 1: family-trajectory baselines + the CrossoverExpansion ratio sweep ----
        // (CrossoverCombo needs the per-level selected ratio, so it runs in pass 2.)
        for (Budget level : levels) {
            int levelGames = (level.games > 0) ? level.games : numGames;
            runConfig(dir, ckpt, done, level, tribes, levelGames, threads, idx, totalMatchups,
                    ExpAgents.emcts(level.budget), "EMCTS", -1, Group.STANDARD_BB, startState);
            runConfig(dir, ckpt, done, level, tribes, levelGames, threads, idx, totalMatchups,
                    ExpAgents.crossoverBB(level.budget), "CrossoverBB", -1, Group.CROSSOVER_BB, startState);
            for (int r : ratios) {
                runConfig(dir, ckpt, done, level, tribes, levelGames, threads, idx, totalMatchups,
                        ExpAgents.crossoverExpansion(level.budget, r), "CrossoverExpansion", r, Group.RATIO, startState);
            }
        }

        // ---- Select the best ratio PER budget level by average FinalSequenceValue (ratio test) ----
        // Merge an optional prior dir so an additive run chooses over the COMBINED sample.
        File extraDir = (extraRatioDir == null) ? null : new File(extraRatioDir);
        Map<String, Integer> bestByLevel = computeBestRatioPerLevel(dir, levels, ratios, extraDir);

        // ---- Pass 2: CrossoverCombo at {5 (standard), best-for-this-level} per level ----
        for (Budget level : levels) {
            int levelGames = (level.games > 0) ? level.games : numGames;
            for (int r : comboRatiosFor(bestByLevel.get(level.name))) {
                runConfig(dir, ckpt, done, level, tribes, levelGames, threads, idx, totalMatchups,
                        ExpAgents.crossoverCombo(level.budget, r), "CrossoverCombo", r, Group.CROSSOVER_BB, startState);
            }
        }

        System.out.println("[Phase3] Complete (best ratios by level=" + bestByLevel + "). Output in " + dir.getPath());
    }

    /** The ratio set used downstream at a given level: the standard ratio, plus the per-level best
     *  (deduplicated, so just {STANDARD_RATIO} when the best is itself the standard). */
    static int[] comboRatiosFor(Integer best) {
        int b = (best == null) ? STANDARD_RATIO : best;
        return (b == STANDARD_RATIO) ? new int[]{STANDARD_RATIO} : new int[]{STANDARD_RATIO, b};
    }

    /** Runs one configuration (tracked agent vs the passive opponent) and appends its rows to the
     *  CSV(s) for its experiment group. Resumable via the per-config checkpoint key. */
    private static void runConfig(File dir, File ckpt, Set<String> done, Budget level, Types.TRIBE[] tribes,
                                  int numGames, int threads, int[] idx, int totalMatchups,
                                  AgentSpec spec, String name, int ratio, Group group, core.game.GameState startState) {
        idx[0]++;
        String ratioLabel = (ratio < 0) ? "NA" : String.valueOf(ratio);
        String label = name + (ratio < 0 ? "" : "@r" + ratio);
        String key = "level=" + level.name + ";agent=" + name + ";ratio=" + ratioLabel;
        if (done.contains(key)) {
            System.out.println("[Phase3] (" + idx[0] + "/" + totalMatchups + ") SKIP (done): " + key);
            return;
        }
        // Offset seeds/gameIds by firstGameIndex so a follow-up (additive) run yields fresh, independent
        // samples that combine with the earlier batch (same fixed map) rather than duplicating it.
        final int off = level.firstGameIndex;
        System.out.println("[Phase3] (" + idx[0] + "/" + totalMatchups + ") RUN " + key
                + " (" + numGames + " games" + (off > 0 ? ", gameIds " + (off + 1) + ".." + (off + numGames) : "") + ")");

        List<GameResult> results = ExperimentEngine.runMatchup(
                Arrays.asList(spec, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, numGames, off + 1,
                g -> FIXED_LEVEL_SEED, g -> FIXED_GAME_SEED, g -> AGENT_SEED_BASE + off + g,
                threads,
                (completed, total, r) -> {
                    if (completed % 20 == 0 || completed == total)
                        System.out.println("    " + label + ": " + completed + "/" + total);
                },
                startState);

        boolean isCrossoverBB = (group == Group.CROSSOVER_BB);
        List<String> turnRows = new ArrayList<>();
        List<String> bbRows = new ArrayList<>();
        for (GameResult res : results) {
            GameResult.AgentRecord rec = recordFor(res, spec.name); // the tracked experimental agent

            // Per-turn rows: only for the ratio test (drives ratio selection + the efficiency story).
            if (group == Group.RATIO) {
                for (TurnData t : rec.turns) {
                    turnRows.add(name + "," + ratioLabel + "," + res.gameId + "," + t.getTurnNumber() + "," +
                            t.getScoreAtTurnStart() + "," + ExpCsv.round(t.getInitialSequenceValue()) + "," +
                            ExpCsv.round(t.getFinalSequenceValue()) + "," + ExpCsv.round(t.getAvgNodeValue()) + "," +
                            t.getFmCalls() + "," + t.getNodesEvaluated() + "," + t.getUniqueSequences() + "," + rec.finalScore);
                }
            }

            // Per-bridge-burn rows: the trajectory (all groups).
            Map<Integer, Integer> scoreAtTurn = new HashMap<>();
            Map<Integer, Double> initialAtTurn = new HashMap<>();
            Map<Integer, Double> finalAtTurn = new HashMap<>();
            Map<Integer, Integer> fmAtTurn = new HashMap<>();
            Map<Integer, Integer> nodesEvalAtTurn = new HashMap<>();
            Map<Integer, Integer> uniqueAtTurn = new HashMap<>();
            Map<Integer, Double> avgValAtTurn = new HashMap<>();
            for (TurnData t : rec.turns) {
                scoreAtTurn.put(t.getTurnNumber(), t.getScoreAtTurnStart());
                initialAtTurn.put(t.getTurnNumber(), t.getInitialSequenceValue());
                finalAtTurn.put(t.getTurnNumber(), t.getFinalSequenceValue());
                fmAtTurn.put(t.getTurnNumber(), t.getFmCalls());
                nodesEvalAtTurn.put(t.getTurnNumber(), t.getNodesEvaluated());
                uniqueAtTurn.put(t.getTurnNumber(), t.getUniqueSequences());
                avgValAtTurn.put(t.getTurnNumber(), t.getAvgNodeValue());
            }
            Map<String, CrossoverEventData> crossByTurnPhase = new HashMap<>();
            if (isCrossoverBB)
                for (CrossoverEventData c : rec.crossovers)
                    crossByTurnPhase.put(c.getTurnNumber() + ":" + c.getBridgeBurningPhaseNumber(), c);

            for (BridgeBurnEventData b : rec.bridgeBurns) {
                int startScore = scoreAtTurn.getOrDefault(b.getTurnNumber(), -1);
                double initialThisTurn = initialAtTurn.getOrDefault(b.getTurnNumber(), 0.0);
                double finalThisTurn = finalAtTurn.getOrDefault(b.getTurnNumber(), 0.0);
                int fmThisTurn = fmAtTurn.getOrDefault(b.getTurnNumber(), -1);
                int nodesEvalThisTurn = nodesEvalAtTurn.getOrDefault(b.getTurnNumber(), -1);
                int uniqueThisTurn = uniqueAtTurn.getOrDefault(b.getTurnNumber(), -1);
                double avgValThisTurn = avgValAtTurn.getOrDefault(b.getTurnNumber(), -1.0);
                StringBuilder row = new StringBuilder();
                // Column order: the ratio-test CSVs put Ratio after AgentName; the family-trajectory
                // CSVs put it after GameId (per the agreed schemas).
                row.append(name).append(",");
                if (group == Group.RATIO) {
                    row.append(ratioLabel).append(",").append(res.gameId).append(",");
                } else {
                    row.append(res.gameId).append(",").append(ratioLabel).append(",");
                }
                row.append(b.getTurnNumber()).append(",").append(b.getBridgeBurningPhaseNumber()).append(",")
                   .append(ExpCsv.round(b.getBestSequenceValue())).append(",")
                   .append(b.getFmCallsElapsed()).append(",")
                   .append(b.getExpansionPhaseTimestamp()).append(",")
                   .append(startScore).append(",")
                   .append(ExpCsv.round(initialThisTurn)).append(",").append(ExpCsv.round(finalThisTurn)).append(",")
                   .append(ExpCsv.round(avgValThisTurn)).append(",")
                   .append(fmThisTurn).append(",").append(nodesEvalThisTurn).append(",")
                   .append(uniqueThisTurn).append(",").append(rec.finalScore);
                if (isCrossoverBB) {
                    CrossoverEventData c = crossByTurnPhase.get(b.getTurnNumber() + ":" + b.getBridgeBurningPhaseNumber());
                    double raw = (c != null) ? c.getRawImprovement() : 0.0;
                    double pct = (c != null) ? c.getPercentageImprovement() : 0.0;
                    row.append(",").append(ExpCsv.round(raw)).append(",").append(ExpCsv.round(pct));
                }
                bbRows.add(row.toString());
            }
        }

        switch (group) {
            case RATIO:
                ExpCsv.append(new File(dir, "Phase3_ratio_turns_" + level.name + ".csv"),
                        "AgentName,Ratio,GameId,TurnNumber,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,FmCalls,NodesEvaluated,UniqueSequences,FinalScore",
                        turnRows);
                ExpCsv.append(new File(dir, "Phase3_ratio_trajectory_" + level.name + ".csv"),
                        "AgentName,Ratio,GameId,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,FmCalls,NodesEvaluated,UniqueSequences,FinalScore",
                        bbRows);
                break;
            case STANDARD_BB:
                ExpCsv.append(new File(dir, "Phase3_standardBB_" + level.name + ".csv"),
                        "AgentName,GameId,Ratio,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,FmCalls,NodesEvaluated,UniqueSequences,FinalScore",
                        bbRows);
                break;
            case CROSSOVER_BB:
                ExpCsv.append(new File(dir, "Phase3_crossoverBB_" + level.name + ".csv"),
                        "AgentName,GameId,Ratio,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,FmCalls,NodesEvaluated,UniqueSequences,FinalScore,RawImprovement,PercentageImprovement",
                        bbRows);
                break;
        }
        ExpCsv.markCompleted(ckpt, key);
    }

    /**
     * Reads the ratio-test per-turn CSVs and picks, FOR EACH budget level, the CrossoverExpansion
     * ratio with the best AVERAGE FinalSequenceValue at that level. Writes phase3_best_ratio.txt
     * with the per-ratio averages and a machine-readable BEST_RATIO_<level>=<n> line per level.
     */
    private static Map<String, Integer> computeBestRatioPerLevel(File dir, Budget[] levels, int[] ratios, File extraDir) {
        Map<String, Integer> bestByLevel = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("Phase 3 - CrossoverExpansion average FinalSequenceValue by ratio, per budget level.\n");
        sb.append("Best ratio per level = highest average FinalSequenceValue (search effectiveness).\n");
        sb.append("Phase 2 uses {" + STANDARD_RATIO + " (standard), best-for-that-level} at each shared budget level.\n\n");

        for (Budget level : levels) {
            Map<Integer, double[]> perRatio = new TreeMap<>(); // ratio -> [sumFinalSeq, count]
            // Merge this run's ratio-test rows with the optional prior dir's, so the best ratio is
            // chosen over the COMBINED sample (additive follow-up runs).
            for (File d : (extraDir == null) ? new File[]{dir} : new File[]{dir, extraDir}) {
                File turns = new File(d, "Phase3_ratio_turns_" + level.name + ".csv");
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

            List<double[]> avgs = new ArrayList<>(); // {ratio, avgFinalSeq}
            for (int r : ratios) {
                double[] sc = perRatio.get(r);
                double avg = (sc != null && sc[1] > 0) ? sc[0] / sc[1] : Double.NEGATIVE_INFINITY;
                avgs.add(new double[]{r, avg});
            }
            avgs.sort((a, b) -> Double.compare(b[1], a[1])); // descending by avg

            int best = ratios.length > 0 ? ratios[0] : STANDARD_RATIO;
            if (!avgs.isEmpty() && avgs.get(0)[1] != Double.NEGATIVE_INFINITY) best = (int) avgs.get(0)[0];
            bestByLevel.put(level.name, best);

            sb.append("  level ").append(level.name).append(":\n");
            for (double[] e : avgs) {
                String avgStr = (e[1] == Double.NEGATIVE_INFINITY) ? "n/a" : String.format("%.2f", e[1]);
                sb.append(String.format("    ratio %2d  avgFinalSeq=%s%n", (int) e[0], avgStr));
            }
            sb.append("\n");
        }

        sb.append("Selected best ratio per level (Phase 2 also always includes ratio ").append(STANDARD_RATIO).append("):\n");
        for (Budget level : levels)
            sb.append("BEST_RATIO_").append(level.name).append("=").append(bestByLevel.get(level.name)).append("\n");
        ExpCsv.writeText(new File(dir, "phase3_best_ratio.txt"), sb.toString());
        System.out.println("[Phase3] " + sb);
        return bestByLevel;
    }

    /** Reads the per-level BEST_RATIO_<level>=<n> lines from phase3_best_ratio.txt. Used by Phase 2. */
    public static Map<String, Integer> bestRatiosByLevel(String phase3Dir) {
        Map<String, Integer> map = new LinkedHashMap<>();
        File f = new File(phase3Dir, "phase3_best_ratio.txt");
        if (f.exists()) {
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (line.startsWith("BEST_RATIO_")) {
                        String rest = line.substring("BEST_RATIO_".length());
                        int eq = rest.indexOf('=');
                        if (eq > 0)
                            map.put(rest.substring(0, eq).trim(), Integer.parseInt(rest.substring(eq + 1).trim()));
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            System.out.println("[Phase3] WARNING: " + f.getPath() + " not found; Phase 2 will default to ratio " + STANDARD_RATIO + ".");
        }
        return map;
    }

    private static GameResult.AgentRecord recordFor(GameResult res, String name) {
        for (GameResult.AgentRecord rec : res.agents)
            if (rec.name.equals(name)) return rec;
        return res.agents.get(0);
    }

    private static String budgetsStr(Budget[] levels) {
        StringBuilder sb = new StringBuilder();
        for (Budget b : levels) sb.append(b.name).append("=").append(b.budget).append(" ");
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4; // keep low: memory-bound (see ThreadBenchmark)
        run(50, defaultLevels(), DEFAULT_RATIOS, threads, "test_results/phase3");
    }
}
