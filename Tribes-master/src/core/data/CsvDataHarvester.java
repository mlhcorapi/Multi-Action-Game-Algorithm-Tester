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
                pw.println("AgentName,GameId,GameDurationTurns,GameOutcomeScore,TurnNumber,InitialSequenceValue,FinalSequenceValue,NodesVisited");
            }

            if (turns == null || turns.isEmpty()) {
                pw.println(summary.getAgentName() + "," +
                        summary.getGameId() + "," +
                        summary.getGameDurationTurns() + "," +
                        summary.getGameOutcomeScore() + "," +
                        "-1,0.0,0.0,0");
            } else {
                for (TurnData turn : turns) {
                    pw.println(summary.getAgentName() + "," +
                            summary.getGameId() + "," +
                            summary.getGameDurationTurns() + "," +
                            summary.getGameOutcomeScore() + "," +
                            turn.getTurnNumber() + "," +
                            turn.getInitialSequenceValue() + "," +
                            turn.getFinalSequenceValue() + "," +
                            turn.getNodesVisited());
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
                pw.println("GameId,TurnNumber,ExpansionPhaseTimestamp,BridgeBurningPhaseNumber,NewRootNodeValue");
            }

            for (BridgeBurnEventData event : events) {
                pw.println(event.getGameId() + "," +
                        event.getTurnNumber() + "," +
                        event.getExpansionPhaseTimestamp() + "," +
                        event.getBridgeBurningPhaseNumber() + "," +
                        event.getNewRootNodeValue());
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
                pw.println("GameId,TurnNumber,ExpansionPhaseTimestamp,BridgeBurningPhaseNumber,BestSequenceBefore,BestSequenceAfter,RawImprovement,PercentageImprovement");
            }

            for (CrossoverEventData event : events) {
                pw.println(event.getGameId() + "," +
                        event.getTurnNumber() + "," +
                        event.getExpansionPhaseTimestamp() + "," +
                        event.getBridgeBurningPhaseNumber() + "," +
                        event.getBestSequenceBefore() + "," +
                        event.getBestSequenceAfter() + "," +
                        event.getRawImprovement() + "," +
                        event.getPercentageImprovement());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
