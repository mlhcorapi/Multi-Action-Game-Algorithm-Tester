package core.data.dto;

public class TurnData {
    private final int gameId;
    private final int turnNumber;
    private final double initialSequenceValue;
    private final double finalSequenceValue;
    private final int nodesVisited;
    private final int scoreAtTurnStart;

    public TurnData(int gameId, int turnNumber, double initialSequenceValue, double finalSequenceValue, int nodesVisited, int scoreAtTurnStart) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.initialSequenceValue = initialSequenceValue;
        this.finalSequenceValue = finalSequenceValue;
        this.nodesVisited = nodesVisited;
        this.scoreAtTurnStart = scoreAtTurnStart;
    }

    public int getGameId() {
        return gameId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public double getInitialSequenceValue() {
        return initialSequenceValue;
    }

    public double getFinalSequenceValue() {
        return finalSequenceValue;
    }

    public int getNodesVisited() {
        return nodesVisited;
    }

    public int getScoreAtTurnStart() {
        return scoreAtTurnStart;
    }

    @Override
    public String toString() {
        return "TurnData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", initialSequenceValue=" + initialSequenceValue +
                ", finalSequenceValue=" + finalSequenceValue +
                ", nodesVisited=" + nodesVisited +
                ", scoreAtTurnStart=" + scoreAtTurnStart +
                '}';
    }
}
