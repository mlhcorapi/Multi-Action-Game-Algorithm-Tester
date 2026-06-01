package core.data.dto;

public class BridgeBurnEventData {
    private final int gameId;
    private final int turnNumber;
    private final long expansionPhaseTimestamp;
    private final int bridgeBurningPhaseNumber;
    private final double bestSequenceValue;
    private final int fmCallsElapsed; // forward-model calls done so far this turn (deterministic search-progress measure)

    public BridgeBurnEventData(int gameId, int turnNumber, long expansionPhaseTimestamp, int bridgeBurningPhaseNumber, double bestSequenceValue, int fmCallsElapsed) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.expansionPhaseTimestamp = expansionPhaseTimestamp;
        this.bridgeBurningPhaseNumber = bridgeBurningPhaseNumber;
        this.bestSequenceValue = bestSequenceValue;
        this.fmCallsElapsed = fmCallsElapsed;
    }

    public int getGameId() {
        return gameId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public long getExpansionPhaseTimestamp() {
        return expansionPhaseTimestamp;
    }

    public int getBridgeBurningPhaseNumber() {
        return bridgeBurningPhaseNumber;
    }

    public double getBestSequenceValue() {
        return bestSequenceValue;
    }

    public int getFmCallsElapsed() {
        return fmCallsElapsed;
    }

    @Override
    public String toString() {
        return "BridgeBurnEventData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", expansionPhaseTimestamp=" + expansionPhaseTimestamp +
                ", bridgeBurningPhaseNumber=" + bridgeBurningPhaseNumber +
                ", bestSequenceValue=" + bestSequenceValue +
                ", fmCallsElapsed=" + fmCallsElapsed +
                '}';
    }
}
