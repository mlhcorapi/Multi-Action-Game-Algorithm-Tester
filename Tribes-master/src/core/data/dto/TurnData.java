package core.data.dto;

/**
 * Per-turn telemetry for one planning episode. The three workload metrics are kept distinct so
 * algorithm efficiency can be compared properly:
 *   - fmCalls         : forward-model advance() calls this turn (the COMPUTE-demand currency / budget).
 *   - nodesEvaluated  : candidate nodes scored this turn (every eval()), DUPLICATES INCLUDED.
 *   - uniqueSequences : DISTINCT action sequences among those evaluated (de-duplicated).
 * Efficiency ratios (computed in analysis): nodesEvaluated/fmCalls, uniqueSequences/fmCalls,
 * uniqueSequences/nodesEvaluated, value-per-node, etc.
 *
 * Value metrics:
 *   - initialSequenceValue : greedy-root (pre-search) heuristic value of the turn's plan.
 *   - finalSequenceValue   : best-node (post-search) heuristic value of the turn's plan.
 *   - avgNodeValue         : mean heuristic value over ALL nodes evaluated this turn.
 *
 * A metric is logged as -1 (ints) when not applicable to an agent (e.g. uniqueSequences/avgNodeValue
 * for the per-action tree-search baseline MCTS).
 */
public class TurnData {
    private final int gameId;
    private final int turnNumber;
    private final int scoreAtTurnStart;
    private final double initialSequenceValue;
    private final double finalSequenceValue;
    private final double avgNodeValue;
    private final int fmCalls;
    private final int nodesEvaluated;
    private final int uniqueSequences;

    public TurnData(int gameId, int turnNumber, int scoreAtTurnStart,
                    double initialSequenceValue, double finalSequenceValue, double avgNodeValue,
                    int fmCalls, int nodesEvaluated, int uniqueSequences) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.scoreAtTurnStart = scoreAtTurnStart;
        this.initialSequenceValue = initialSequenceValue;
        this.finalSequenceValue = finalSequenceValue;
        this.avgNodeValue = avgNodeValue;
        this.fmCalls = fmCalls;
        this.nodesEvaluated = nodesEvaluated;
        this.uniqueSequences = uniqueSequences;
    }

    public int getGameId() {
        return gameId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public int getScoreAtTurnStart() {
        return scoreAtTurnStart;
    }

    public double getInitialSequenceValue() {
        return initialSequenceValue;
    }

    public double getFinalSequenceValue() {
        return finalSequenceValue;
    }

    public double getAvgNodeValue() {
        return avgNodeValue;
    }

    public int getFmCalls() {
        return fmCalls;
    }

    public int getNodesEvaluated() {
        return nodesEvaluated;
    }

    public int getUniqueSequences() {
        return uniqueSequences;
    }

    @Override
    public String toString() {
        return "TurnData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", scoreAtTurnStart=" + scoreAtTurnStart +
                ", initialSequenceValue=" + initialSequenceValue +
                ", finalSequenceValue=" + finalSequenceValue +
                ", avgNodeValue=" + avgNodeValue +
                ", fmCalls=" + fmCalls +
                ", nodesEvaluated=" + nodesEvaluated +
                ", uniqueSequences=" + uniqueSequences +
                '}';
    }
}
