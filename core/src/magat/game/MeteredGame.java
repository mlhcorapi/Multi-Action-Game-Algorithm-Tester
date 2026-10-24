package magat.game;

import magat.meter.Meter;

import java.util.List;
import java.util.Map;

/**
 * The meter, as a decorator over the contract. Every runner, calibrator and probe wraps the game
 * with this before handing it to an agent, so every forward-model call is counted (and, when
 * {@link Meter#PROFILE} is on, timed) on the calling thread. {@code advance}, {@code copy} and
 * {@code evaluate} are the three operations a unit is made of; everything else delegates.
 *
 * <p>Counting happens before the operation, so an operation that throws is still counted.
 */
public final class MeteredGame<S, A> implements Game<S, A> {

    private final Game<S, A> inner;

    private MeteredGame(Game<S, A> inner) {
        this.inner = inner;
    }

    public static <S, A> MeteredGame<S, A> of(Game<S, A> game) {
        if (game instanceof MeteredGame) return (MeteredGame<S, A>) game;
        return new MeteredGame<>(game);
    }

    /** The game being metered. For probes only; agents never see it. */
    public Game<S, A> inner() {
        return inner;
    }

    @Override public String id() { return inner.id(); }
    @Override public Map<String, String> configuration() { return inner.configuration(); }

    @Override
    public S copy(S state) {
        Meter.onCopy();
        if (Meter.PROFILE) {
            long t0 = System.nanoTime();
            S s = inner.copy(state);
            Meter.addCopyNanos(System.nanoTime() - t0);
            return s;
        }
        return inner.copy(state);
    }

    @Override
    public void advance(S state, A action) {
        Meter.onAdvance();
        if (Meter.PROFILE) {
            long t0 = System.nanoTime();
            inner.advance(state, action);
            Meter.addAdvanceNanos(System.nanoTime() - t0);
            return;
        }
        inner.advance(state, action);
    }

    @Override
    public double evaluate(S state, int player) {
        Meter.onEval();
        if (Meter.PROFILE) {
            long t0 = System.nanoTime();
            double v = inner.evaluate(state, player);
            Meter.addEvalNanos(System.nanoTime() - t0);
            return v;
        }
        return inner.evaluate(state, player);
    }

    @Override public List<A> legalActions(S state) { return inner.legalActions(state); }
    @Override public boolean isLegal(S state, A action) { return inner.isLegal(state, action); }
    @Override public boolean isTerminal(S state) { return inner.isTerminal(state); }
    @Override public int activePlayer(S state) { return inner.activePlayer(state); }
    @Override public int playerCount(S state) { return inner.playerCount(state); }
    @Override public boolean endsTurn(A action) { return inner.endsTurn(action); }
    @Override public A endTurnAction(S state) { return inner.endTurnAction(state); }
    @Override public String actionKey(A action) { return inner.actionKey(action); }
    @Override public int branchingFactor(S state) { return inner.branchingFactor(state); }
    @Override public void reseed(S state, long seed) { inner.reseed(state, seed); }
    @Override public byte[] serialize(S state) { return inner.serialize(state); }
    @Override public S deserialize(byte[] bytes) { return inner.deserialize(bytes); }
    @Override public Map<String, String> describe(S state) { return inner.describe(state); }
    @Override public List<ScenarioSource<S>> scenarioSources() { return inner.scenarioSources(); }
}
