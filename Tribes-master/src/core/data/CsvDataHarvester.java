package core.data;

import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.GameSummaryData;
import core.data.dto.TurnData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class CsvDataHarvester {

    private static final String DIRECTORY = "test_results";

    /**
     * Rounds a heuristic value to 4 decimals before logging, so floating-point
     * representation noise (e.g. 0.1 * 3 = 0.30000000000000004) does not pollute the
     * CSV. Search/decision logic always uses the full-precision values; only the
     * recorded telemetry is rounded.
     */
    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static void ensureDirectoryExists() {
        File dir = new File(DIRECTORY);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static void writeSummaryAndTurns(int phase, GameSummaryData summary, List<TurnData> turns) {
        ensureDirectoryExists();
        File file = new File(DIRECTORY + File.separator + "Phase_" + phase + "_Summary.csv");
        boolean isNew = !file.exists();

        try (FileWriter fw = new FileWriter(file, true);
             PrintWriter pw = new PrintWriter(fw)) {

            if (isNew) {
                pw.println("AgentName,GameId,GameDurationTurns,GameOutcomeScore,TurnNumber,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,FmCalls,NodesEvaluated,UniqueSequences,ScoreAtTurnStart");
            }

            if (turns == null || turns.isEmpty()) {
                pw.println(summary.getAgentName() + "," +
                        summary.getGameId() + "," +
                        summary.getGameDurationTurns() + "," +
                        summary.getGameOutcomeScore() + "," +
                        "-1,0.0,0.0,-1.0,0,0,-1,-1");
            } else {
                for (TurnData turn : turns) {
                    pw.println(summary.getAgentName() + "," +
                            summary.getGameId() + "," +
                            summary.getGameDurationTurns() + "," +
                            summary.getGameOutcomeScore() + "," +
                            turn.getTurnNumber() + "," +
                            round(turn.getInitialSequenceValue()) + "," +
                            round(turn.getFinalSequenceValue()) + "," +
                            round(turn.getAvgNodeValue()) + "," +
                            turn.getFmCalls() + "," +
                            turn.getNodesEvaluated() + "," +
                            turn.getUniqueSequences() + "," +
                            turn.getScoreAtTurnStart());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeBridgeBurns(int phase, List<BridgeBurnEventData> events) {
        if (events == null || events.isEmpty()) return;
        ensureDirectoryExists();
        File file = new File(DIRECTORY + File.separator + "Phase_" + phase + "_Bridge_Burns.csv");
        boolean isNew = !file.exists();

        try (FileWriter fw = new FileWriter(file, true);
             PrintWriter pw = new PrintWriter(fw)) {

            if (isNew) {
                pw.println("GameId,TurnNumber,ExpansionPhaseTimestamp,BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed");
            }

            for (BridgeBurnEventData event : events) {
                pw.println(event.getGameId() + "," +
                        event.getTurnNumber() + "," +
                        event.getExpansionPhaseTimestamp() + "," +
                        event.getBridgeBurningPhaseNumber() + "," +
                        round(event.getBestSequenceValue()) + "," +
                        event.getFmCallsElapsed());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeCrossovers(int phase, List<CrossoverEventData> events) {
        if (events == null || events.isEmpty()) return;
        ensureDirectoryExists();
        File file = new File(DIRECTORY + File.separator + "Phase_" + phase + "_Crossovers.csv");
        boolean isNew = !file.exists();

        try (FileWriter fw = new FileWriter(file, true);
             PrintWriter pw = new PrintWriter(fw)) {

            if (isNew) {
                pw.println("GameId,TurnNumber,ExpansionPhaseTimestamp,BridgeBurningPhaseNumber,BestSequenceBefore,BestSequenceAfter,RawImprovement,PercentageImprovement,FmCallsElapsed");
            }

            for (CrossoverEventData event : events) {
                pw.println(event.getGameId() + "," +
                        event.getTurnNumber() + "," +
                        event.getExpansionPhaseTimestamp() + "," +
                        event.getBridgeBurningPhaseNumber() + "," +
                        round(event.getBestSequenceBefore()) + "," +
                        round(event.getBestSequenceAfter()) + "," +
                        round(event.getRawImprovement()) + "," +
                        round(event.getPercentageImprovement()) + "," +
                        event.getFmCallsElapsed());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
