package magat.games.tribes;

import core.Constants;
import core.Types;
import core.actions.Action;
import core.actions.cityactions.CityAction;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.City;
import core.actors.Tribe;
import core.actors.units.Unit;
import core.game.ComputeMeter;
import core.game.GameState;
import magat.game.EngineCounters;
import magat.game.Game;
import magat.game.ScenarioSource;
import players.heuristics.TribesSimpleHeuristic;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tribes behind the contract. Every method is the thesis tree's behaviour, moved: the "good
 * actions" filter of {@code Agent.allGoodActions}, the feasibility test of
 * {@code EMCTSAgent.checkActionFeasibility}, the SIMPLE heuristic, the deterministic-cascade copy,
 * the reseeding of {@code Stage1Runner.runOneTurn}, the serialization of {@code FastGameStateIO},
 * and the manifest facts of {@code BuildV5Portfolio.persistAndManifest}.
 *
 * <p>Engine globals ({@code core.Constants}) are set once, here, and are what {@link #configuration}
 * reports. No runner touches them.
 */
public final class TribesGame implements Game<TribesState, Action>, EngineCounters {

    /** Per-turn plan cap the engine's own agents use; only informational here. */
    static final int HEURISTIC_SIMPLE = 1;

    private final Map<Integer, TribesSimpleHeuristic> heuristics = new ConcurrentHashMap<>();
    private final List<ScenarioSource<TribesState>> sources;

    public TribesGame() {
        applyEngineConstants();
        this.sources = Collections.unmodifiableList(TribesScenarioSources.all(this));
    }

    /** The engine settings every MAGAT run of Tribes uses; unchanged from {@code Stage1Runner.applyEngineConstants}. */
    static void applyEngineConstants() {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.VERBOSE = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;   // agents gate on compute units, never the clock
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String id() {
        return "tribes";
    }

    @Override
    public Map<String, String> configuration() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("engine", "GAIGResearch/Tribes fork");
        c.put("engine.commit", System.getProperty("magat.engine.commit", "unknown"));
        c.put("heuristic", "SIMPLE");
        c.put("removeFishing", String.valueOf(Constants.REMOVE_FISHING));
        c.put("turnTimeLimited", String.valueOf(Constants.TURN_TIME_LIMITED));
        c.put("fullObservability", String.valueOf(Constants.PLAY_WITH_FULL_OBS));
        return c;
    }

    // ------------------------------------------------------------------ forward model

    @Override
    public TribesState copy(TribesState state) {
        return new TribesState(state.raw().copy());
    }

    @Override
    public void advance(TribesState state, Action action) {
        state.raw().advance(action, true);
    }

    /** City actions without DESTROY, unit actions without DISBAND, tribe actions; each feasible. */
    @Override
    public List<Action> legalActions(TribesState state) {
        GameState gs = state.raw();
        ArrayList<Action> out = new ArrayList<>();
        for (Action act : gs.getAllCityActions())
            if (act.getActionType() != Types.ACTION.DESTROY && act.isFeasible(gs)) out.add(act);
        for (Action act : gs.getAllUnitActions())
            if (act.getActionType() != Types.ACTION.DISBAND && act.isFeasible(gs)) out.add(act);
        for (Action act : gs.getTribeActions())
            if (act.isFeasible(gs)) out.add(act);
        return out;
    }

    @Override
    public boolean isLegal(TribesState state, Action a) {
        GameState gs = state.raw();
        if (gs.isGameOver()) return false;
        if (a instanceof UnitAction) {
            Actor act = gs.getActor(((UnitAction) a).getUnitId());
            if (!(act instanceof Unit) || act.getTribeId() != gs.getActiveTribeID()) return false;
        } else if (a instanceof CityAction) {
            Actor act = gs.getActor(((CityAction) a).getCityId());
            if (!(act instanceof City) || act.getTribeId() != gs.getActiveTribeID()) return false;
        }
        try {
            return a.isFeasible(gs);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public double evaluate(TribesState state, int player) {
        GameState gs = state.raw();
        TribesSimpleHeuristic h = heuristics.computeIfAbsent(player, p -> {
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
            return new TribesSimpleHeuristic(p, allIds);
        });
        // The two-argument form is the one the engine's own agents call; SIMPLE ignores the first
        // argument, and calling it keeps the engine's own counters comparable with the meter's.
        return h.evaluateState(gs, gs);
    }

    @Override
    public boolean isTerminal(TribesState state) {
        return state.raw().isGameOver();
    }

    // ------------------------------------------------------------------ turn structure

    @Override
    public int activePlayer(TribesState state) {
        return state.raw().getActiveTribeID();
    }

    @Override
    public int playerCount(TribesState state) {
        return state.raw().getTribes().length;
    }

    @Override
    public boolean endsTurn(Action action) {
        return action.getActionType() == Types.ACTION.END_TURN;
    }

    /** The first END_TURN among the legal actions, as every thesis-tree agent found it. */
    @Override
    public Action endTurnAction(TribesState state) {
        for (Action a : legalActions(state))
            if (a.getActionType() == Types.ACTION.END_TURN) return a;
        return null;
    }

    // ------------------------------------------------------------------ action identity

    @Override
    public String actionKey(Action action) {
        return action.toString();
    }

    /** The engine's own unfiltered action count, as the published manifest defines it. */
    @Override
    public int branchingFactor(TribesState state) {
        return state.raw().getAllAvailableActions().size();
    }

    // ------------------------------------------------------------------ determinism and persistence

    @Override
    public void reseed(TribesState state, long seed) {
        state.raw().setRandomGenerator(new Random(seed));
    }

    @Override
    public byte[] serialize(TribesState state) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 14);
            try (ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(bytes)))) {
                out.writeObject(state.raw());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize Tribes state", e);
        }
    }

    @Override
    public TribesState deserialize(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes))))) {
            return new TribesState((GameState) in.readObject());
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("cannot deserialize Tribes state (engine class shape changed?)", e);
        }
    }

    @Override
    public Map<String, String> describe(TribesState state) {
        GameState gs = state.raw();
        Map<String, String> m = new LinkedHashMap<>();
        int aid = gs.getActiveTribeID();
        Tribe at = gs.getTribes()[aid];
        int numUnits = 0;
        if (at.getCitiesID() != null) {
            for (Integer cId : at.getCitiesID()) {
                City c = (City) gs.getActor(cId);
                if (c != null && c.getUnitsID() != null) numUnits += c.getUnitsID().size();
            }
        }
        int nAttack = 0, nMove = 0, nOtherUnit = 0, nCity = 0, nTribe = 0;
        for (Action a : gs.getAllAvailableActions()) {
            if (a instanceof core.actions.unitactions.Attack) nAttack++;
            else if (a instanceof core.actions.unitactions.Move) nMove++;
            else if (a instanceof UnitAction) nOtherUnit++;
            else if (a instanceof CityAction) nCity++;
            else if (a instanceof core.actions.tribeactions.TribeAction) nTribe++;
        }
        m.put("ActiveTribeId", String.valueOf(aid));
        m.put("ActiveTribeNumCities", String.valueOf(at.getNumCities()));
        m.put("ActiveTribeNumUnits", String.valueOf(numUnits));
        m.put("ActiveTribeScore", String.valueOf(at.getScore()));
        m.put("NumAttackActions", String.valueOf(nAttack));
        m.put("NumMoveActions", String.valueOf(nMove));
        m.put("NumOtherUnitActions", String.valueOf(nOtherUnit));
        m.put("NumCityActions", String.valueOf(nCity));
        m.put("NumTribeActions", String.valueOf(nTribe));
        return m;
    }

    @Override
    public List<ScenarioSource<TribesState>> scenarioSources() {
        return sources;
    }

    // ------------------------------------------------------------------ engine counters (diagnostic)

    @Override
    public long[] engineCounts() {
        return new long[]{ComputeMeter.advances(), ComputeMeter.copies(), ComputeMeter.evals()};
    }

    @Override
    public void resetEngineCounts() {
        ComputeMeter.reset();
    }

    @Override
    public boolean engineTiming(boolean on) {
        ComputeMeter.PROFILE = on;
        return true;
    }

    @Override
    public long[] engineNanos() {
        return new long[]{ComputeMeter.advanceNanos(), ComputeMeter.copyNanos(), ComputeMeter.evalNanos()};
    }

    @Override
    public void resetEngineNanos() {
        ComputeMeter.resetNanos();
    }

    /** The engine's hook is a thread-local array increment; time it alone, then put the counts back. */
    @Override
    public long dormantCounterNanos(long calls) {
        long[] before = engineCounts();
        long t0 = System.nanoTime();
        for (long i = 0; i < calls; i++) ComputeMeter.onAdvance();
        long ns = System.nanoTime() - t0;
        ComputeMeter.reset();
        for (long i = 0; i < before[0]; i++) ComputeMeter.onAdvance();
        for (long i = 0; i < before[1]; i++) ComputeMeter.onCopy();
        for (long i = 0; i < before[2]; i++) ComputeMeter.onEval();
        return ns;
    }

    /** Wraps an engine state; for the scenario sources and the oracle. */
    static TribesState wrap(GameState gs) {
        return new TribesState(gs);
    }
}
