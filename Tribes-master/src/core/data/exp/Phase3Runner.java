package core.data.exp;

import core.Constants;
import core.Types;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;

import java.io.File;
import java.util.*;

/**
 * Phase 3 - Solo trajectory analysis. One player per game, SCORE mode, 30-turn limit,
 * tribe IMPERIUS, on a FIXED map seed. Each of the 4 EMCTS-family agents plays 100 games;
 * game k uses the same agent RNG seed for every agent (so RNG is the only variable, and the
 * map is identical). 3 fmcall budgets.
 *
 * Output (per budget level): two bridge-burn-level CSVs - one for the standard bridge
 * burners (EMCTS, CrossoverExpansion) and one for the crossover bridge burners
 * (CrossoverBB, CrossoverCombo); the latter also carries raw/percentage improvement.
 * Each row also carries the turn's start score and the game's final score.
 */
public class Phase3Runner {

    private static final long FIXED_LEVEL_SEED = 777_777L;
    private static final long FIXED_GAME_SEED = 888_888L;
    private static final long AGENT_SEED_BASE = 100L;

    // Calibrated fmcall budgets ~200/1000/5000ms on the target machine (~30.8 fmcalls/ms; see Calibrate).
    public static Budget[] defaultLevels() {
        return new Budget[]{ new Budget("200ms", 6000), new Budget("1000ms", 30000), new Budget("5000ms", 154000) };
    }

    public static void run(int numGames, Budget[] levels, int ratio, int threads, String outDir) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        // fmcall-budgeted phase: ensure the engine does not impose any time cutoff.
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        // SCORE mode uses Constants.MAX_TURNS (= 30, package-private; referenced as a literal below).
        final int scoreTurnLimit = 30;
        // A 1-tribe map is degenerate (no expansion -> every agent stalls), so we use a
        // 2-tribe map with a fixed PASSIVE opponent: the map then has villages to capture,
        // and the tracked experimental agent develops while the opponent never interferes.
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        File dir = new File(outDir);
        ExpCsv.ensureDir(dir);
        File ckpt = new File(dir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "Phase 3 - Trajectory Analysis (tracked agent vs fixed passive opponent)\n" +
                "mode=SCORE turnLimit=" + scoreTurnLimit + " gamesPerAgent=" + numGames + " threads=" + threads + "\n" +
                "tribes=IMPERIUS (tracked agent) vs IMPERIUS2 (DoNothing/passive opponent)\n" +
                "fixedLevelSeed=" + FIXED_LEVEL_SEED + " fixedGameSeed=" + FIXED_GAME_SEED + " agentSeed=" + AGENT_SEED_BASE + "+gameIndex\n" +
                "ratio(Expansion/Combo)=" + ratio + "\n" +
                "budgets(fmcalls)=" + budgetsStr(levels) + "\n");

        int totalMatchups = levels.length * 4, matchupIdx = 0;
        for (Budget level : levels) {
            File standardCsv = new File(dir, "Phase3_standardBB_" + level.name + ".csv");
            File crossoverCsv = new File(dir, "Phase3_crossoverBB_" + level.name + ".csv");

            // (spec, isCrossoverBB)
            List<Object[]> agents = Arrays.asList(
                    new Object[]{ExpAgents.emcts(level.fmcalls), false},
                    new Object[]{ExpAgents.crossoverExpansion(level.fmcalls, ratio), false},
                    new Object[]{ExpAgents.crossoverBB(level.fmcalls), true},
                    new Object[]{ExpAgents.crossoverCombo(level.fmcalls, ratio), true});

            for (Object[] entry : agents) {
                AgentSpec spec = (AgentSpec) entry[0];
                boolean isCrossoverBB = (Boolean) entry[1];
                matchupIdx++;
                String key = "level=" + level.name + ";agent=" + spec.name;
                if (done.contains(key)) {
                    System.out.println("[Phase3] (" + matchupIdx + "/" + totalMatchups + ") SKIP (done): " + key);
                    continue;
                }
                System.out.println("[Phase3] (" + matchupIdx + "/" + totalMatchups + ") RUN " + key + " (" + numGames + " games)");

                List<GameResult> results = ExperimentEngine.runMatchup(
                        Arrays.asList(spec, ExpAgents.passiveOpponent()), tribes, Types.GAME_MODE.SCORE, numGames, 1,
                        g -> FIXED_LEVEL_SEED, g -> FIXED_GAME_SEED, g -> AGENT_SEED_BASE + g,
                        threads,
                        (completed, total, r) -> {
                            if (completed % 20 == 0 || completed == total)
                                System.out.println("    " + spec.name + ": " + completed + "/" + total);
                        });

                List<String> rows = new ArrayList<>();
                for (GameResult res : results) {
                    GameResult.AgentRecord rec = recordFor(res, spec.name); // the tracked experimental agent
                    Map<Integer, Integer> scoreAtTurn = new HashMap<>();
                    Map<Integer, Integer> nodesAtTurn = new HashMap<>();
                    Map<Integer, Integer> seqAtTurn = new HashMap<>();
                    for (TurnData t : rec.turns) {
                        scoreAtTurn.put(t.getTurnNumber(), t.getScoreAtTurnStart());
                        nodesAtTurn.put(t.getTurnNumber(), t.getNodesVisited());
                        seqAtTurn.put(t.getTurnNumber(), t.getSequencesEvaluated());
                    }

                    Map<String, CrossoverEventData> crossByTurnPhase = new HashMap<>();
                    if (isCrossoverBB) {
                        for (CrossoverEventData c : rec.crossovers)
                            crossByTurnPhase.put(c.getTurnNumber() + ":" + c.getBridgeBurningPhaseNumber(), c);
                    }

                    for (BridgeBurnEventData b : rec.bridgeBurns) {
                        int startScore = scoreAtTurn.getOrDefault(b.getTurnNumber(), -1);
                        int nodesThisTurn = nodesAtTurn.getOrDefault(b.getTurnNumber(), -1);
                        int seqThisTurn = seqAtTurn.getOrDefault(b.getTurnNumber(), -1);
                        StringBuilder row = new StringBuilder();
                        row.append(spec.name).append(",").append(res.gameId).append(",")
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
                        rows.add(row.toString());
                    }
                }

                if (isCrossoverBB) {
                    ExpCsv.append(crossoverCsv,
                            "AgentName,GameId,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,NodesVisited,SequencesEvaluated,FinalScore,RawImprovement,PercentageImprovement",
                            rows);
                } else {
                    ExpCsv.append(standardCsv,
                            "AgentName,GameId,TurnNumber,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,Timestamp,ScoreAtTurnStart,NodesVisited,SequencesEvaluated,FinalScore",
                            rows);
                }
                ExpCsv.markCompleted(ckpt, key);
            }
        }
        System.out.println("[Phase3] Complete. Output in " + dir.getPath());
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
        int[] ratios = Phase2Runner.ratiosFromPhase1("test_results/phase1");
        run(100, defaultLevels(), ratios[0], threads, "test_results/phase3");
    }
}
