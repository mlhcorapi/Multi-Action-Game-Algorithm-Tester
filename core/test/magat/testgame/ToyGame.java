package magat.testgame;

import magat.game.Capture;
import magat.game.Game;
import magat.game.Granularity;
import magat.game.Scenario;
import magat.game.ScenarioSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A tiny two-player multi-action game for the core's own tests: each turn a player adds 1 or 2 to
 * one of three counters up to four times, then ends the turn; a counter may not exceed 12, so
 * actions go stale as counters fill; one advance in ten grants a bonus from the state's own random
 * generator, so reseeding matters. The value is a mildly non-linear function of the counters.
 */
public final class ToyGame implements Game<ToyGame.State, ToyGame.Move> {

    public static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        int[] pos = new int[3];
        int player;
        int turn;
        int actionsThisTurn;
        Random rng = new Random(0);

        State copy() {
            State s = new State();
            s.pos = pos.clone();
            s.player = player;
            s.turn = turn;
            s.actionsThisTurn = actionsThisTurn;
            // The deterministic cascade: the copy's generator is seeded from the parent's next draw.
            s.rng = new Random(rng.nextLong());
            return s;
        }
    }

    public static final class Move {
        public final int idx, amount;    // idx -1 = end turn
        Move(int idx, int amount) { this.idx = idx; this.amount = amount; }
        public boolean isEnd() { return idx < 0; }
        @Override public String toString() { return isEnd() ? "END" : "ADD" + idx + "+" + amount; }
    }

    private static final Move END = new Move(-1, 0);
    public static final int MAX_ACTIONS_PER_TURN = 4;
    public static final int CAP = 12;
    public static final int TURNS = 3;

    @Override public String id() { return "toy"; }
    @Override public Map<String, String> configuration() { return Collections.singletonMap("cap", String.valueOf(CAP)); }
    @Override public State copy(State s) { return s.copy(); }

    @Override
    public void advance(State s, Move m) {
        if (m.isEnd()) {
            s.player = 1 - s.player;
            s.actionsThisTurn = 0;
            if (s.player == 0) s.turn++;
            return;
        }
        // A stale action is a no-op, as the Tribes engine treats one: a repaired plan replayed on a
        // fresh copy may meet a different stochastic outcome, and the search relies on the replay
        // completing rather than throwing.
        if (!isLegal(s, m)) return;
        s.pos[m.idx] += m.amount;
        s.actionsThisTurn++;
        if (s.rng.nextInt(10) == 0 && s.pos[m.idx] < CAP) s.pos[m.idx]++;
    }

    @Override
    public List<Move> legalActions(State s) {
        List<Move> out = new ArrayList<>();
        if (isTerminal(s)) return out;
        if (s.actionsThisTurn < MAX_ACTIONS_PER_TURN) {
            for (int i = 0; i < 3; i++)
                for (int k = 1; k <= 2; k++)
                    if (s.pos[i] + k <= CAP) out.add(new Move(i, k));
        }
        out.add(END);
        return out;
    }

    @Override
    public boolean isLegal(State s, Move m) {
        if (isTerminal(s)) return false;
        if (m.isEnd()) return true;
        return s.actionsThisTurn < MAX_ACTIONS_PER_TURN && s.pos[m.idx] + m.amount <= CAP;
    }

    @Override
    public double evaluate(State s, int player) {
        double v = s.pos[0] * 1.0 + s.pos[1] * 1.5 + s.pos[2] * 0.5
                - 0.3 * Math.max(s.pos[0], Math.max(s.pos[1], s.pos[2]));
        return player == 0 ? v : -v;
    }

    @Override public boolean isTerminal(State s) { return s.turn >= TURNS; }
    @Override public int activePlayer(State s) { return s.player; }
    @Override public int playerCount(State s) { return 2; }
    @Override public boolean endsTurn(Move m) { return m.isEnd(); }
    @Override public Move endTurnAction(State s) { return isTerminal(s) ? null : END; }
    @Override public String actionKey(Move m) { return m.toString(); }

    @Override
    public String actionClass(State s, Move m, Granularity level) {
        if (m.isEnd()) return "END";
        switch (level) {
            case COARSE: return "ADD";
            case MID: return "ADD:" + m.idx;
            default: return m.toString();
        }
    }

    @Override public int branchingFactor(State s) { return legalActions(s).size(); }
    @Override public void reseed(State s, long seed) { s.rng = new Random(seed); }

    @Override
    public byte[] serialize(State s) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (ObjectOutputStream o = new ObjectOutputStream(b)) { o.writeObject(s); }
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Override
    public State deserialize(byte[] bytes) {
        try (ObjectInputStream i = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (State) i.readObject();
        } catch (IOException | ClassNotFoundException e) { throw new IllegalStateException(e); }
    }

    @Override
    public Map<String, String> describe(State s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Pos0", String.valueOf(s.pos[0]));
        m.put("Pos1", String.valueOf(s.pos[1]));
        m.put("Pos2", String.valueOf(s.pos[2]));
        return m;
    }

    @Override
    public List<ScenarioSource<State>> scenarioSources() {
        return Collections.singletonList(new ScenarioSource<State>() {
            @Override public String name() { return "toy"; }
            @Override public int[] phases() { return new int[]{1, 2}; }
            @Override public int maxAttempts() { return 100; }
            @Override public int defaultTarget() { return 6; }
            @Override public String definition() { return "toy: seed attempt, random play seeded attempt*31, captures at turns 1 and 2"; }
            @Override
            public List<Capture<State>> play(int attempt) {
                State s = new State();
                s.rng = new Random(attempt);
                Random play = new Random(attempt * 31L);
                List<Capture<State>> out = new ArrayList<>();
                int nextPhase = 0;
                while (!isTerminal(s) && nextPhase < 2) {
                    if (s.turn == phases()[nextPhase] && s.player == 0 && s.actionsThisTurn == 0) {
                        out.add(new Capture<>(attempt, attempt, s.turn, s.copy()));
                        nextPhase++;
                    }
                    List<Move> legal = legalActions(s);
                    advance(s, legal.get(play.nextInt(legal.size())));
                }
                return out;
            }
        });
    }

    /** A few captured positions for tests. */
    public List<Scenario<State>> positions(int n) {
        List<Scenario<State>> out = new ArrayList<>();
        ScenarioSource<State> src = scenarioSources().get(0);
        int id = 1;
        for (int attempt = 1; out.size() < n && attempt < 50; attempt++) {
            for (Capture<State> c : src.play(attempt)) {
                if (out.size() >= n) break;
                out.add(new Scenario<>(id++, src.name(), c.attempt, c.seed, c.phase, branchingFactor(c.state), c.state, describe(c.state)));
            }
        }
        return out;
    }
}
