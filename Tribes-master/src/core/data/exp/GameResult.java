package core.data.exp;

import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;

import java.util.List;

/** Immutable-ish container for everything one finished game produced, per agent. */
public class GameResult {

    public int gameId;
    public int endTurn;          // game tick the match ended on
    public String winnerName;    // agent that won (RESULT.WIN), else highest-score agent

    public List<AgentRecord> agents;

    public static class AgentRecord {
        public String name;
        public int seat;          // resolved seat index in the finished game
        public int finalScore;
        public boolean won;
        public List<TurnData> turns;
        public List<BridgeBurnEventData> bridgeBurns;
        public List<CrossoverEventData> crossovers;
    }
}
