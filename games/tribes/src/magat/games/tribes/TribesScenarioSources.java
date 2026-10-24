package magat.games.tribes;

import core.Constants;
import core.Types;
import core.actors.City;
import core.actors.Tribe;
import core.game.ComputeMeter;
import core.game.Game;
import core.game.GameState;
import magat.game.Capture;
import magat.game.ScenarioSource;
import magat.meter.Weights;
import players.Agent;
import players.DoNothingAgent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;
import utils.ElapsedCpuTimer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The two capture sources of the published Tribes portfolio, exactly as {@code BuildV5Portfolio}
 * generated them: {@code single} (EMCTS vs DoNothing, SCORE mode, captures at ticks 10..30) and
 * {@code dual} (EMCTS vs EMCTS, CAPITALS mode, captures at ticks 15/20/25). Source games are played
 * by the engine's own {@code EMCTSAgent} at 26,800 units priced by the fixed capture weights
 * ({@link Weights#DEFAULT}), never by a calibration.
 *
 * <p>The two sources set the engine's turn cap differently and the cap is a global, so a builder
 * plays one source at a time.
 */
public final class TribesScenarioSources implements ScenarioSource<TribesState> {

    public static final int MIN_VALID_SCORE = 500;
    public static final int SOURCE_BUDGET_UNITS = 26_800;
    public static final long SINGLE_LEVEL_SEED_BASE = 4_000_000L;
    public static final long DUAL_LEVEL_SEED_BASE = 5_000_000L;
    public static final long FIXED_GAME_SEED = 9_000_001L;
    public static final long SOURCE_AGENT_SEED = 100L;
    public static final int[] SINGLE_TICKS = {10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30};
    public static final int[] DUAL_TICKS = {15, 20, 25};
    public static final int MAX_SINGLE_ATTEMPTS = 150;
    public static final int MAX_DUAL_ATTEMPTS = 1500;
    /** Captures per source in the published portfolio (200 + 200). */
    public static final int DEFAULT_TARGET = 200;
    private static final Types.TRIBE[] TRIBES = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

    private final String name;
    private final long seedBase;
    private final Types.GAME_MODE mode;
    private final int[] ticks;
    private final int maxAttempts;
    private final boolean twoPlayer;
    private final int turnCap;

    private TribesScenarioSources(String name, long seedBase, Types.GAME_MODE mode, int[] ticks,
                                  int maxAttempts, boolean twoPlayer, int turnCap) {
        this.name = name; this.seedBase = seedBase; this.mode = mode; this.ticks = ticks;
        this.maxAttempts = maxAttempts; this.twoPlayer = twoPlayer; this.turnCap = turnCap;
    }

    static List<ScenarioSource<TribesState>> all(TribesGame game) {
        List<ScenarioSource<TribesState>> out = new ArrayList<>();
        out.add(new TribesScenarioSources("single", SINGLE_LEVEL_SEED_BASE, Types.GAME_MODE.SCORE,
                SINGLE_TICKS, MAX_SINGLE_ATTEMPTS, false, SINGLE_TICKS[SINGLE_TICKS.length - 1]));
        out.add(new TribesScenarioSources("dual", DUAL_LEVEL_SEED_BASE, Types.GAME_MODE.CAPITALS,
                DUAL_TICKS, MAX_DUAL_ATTEMPTS, true, 50));
        return out;
    }

    @Override public String name() { return name; }
    @Override public int[] phases() { return ticks.clone(); }
    @Override public int maxAttempts() { return maxAttempts; }
    @Override public int defaultTarget() { return DEFAULT_TARGET; }

    @Override
    public String definition() {
        return String.format(Locale.ROOT,
                "level seed %d+attempt, game seed %d, %s, %s vs %s, %s, captures at ticks %s (turn cap %d), source agent "
                        + "EMCTSAgent seed %d depth 21 SIMPLE heuristic %d units at weights %s/%s/%s, valid when the active "
                        + "tribe has >=1 city and >=1 unit and score >=%d and a 50 ms EMCTS plan does not throw, "
                        + "abandoned when the first tick is invalid, at most %d attempts",
                seedBase, FIXED_GAME_SEED, mode, TRIBES[0], twoPlayer ? TRIBES[1] + " (same agent)" : TRIBES[1] + " (DoNothing)",
                "11x11 water-free board", Arrays.toString(ticks).replace(", ", " "), turnCap, SOURCE_AGENT_SEED,
                SOURCE_BUDGET_UNITS, fmt(Weights.DEFAULT[0]), fmt(Weights.DEFAULT[1]), fmt(Weights.DEFAULT[2]),
                MIN_VALID_SCORE, maxAttempts);
    }

    private static String fmt(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    @Override
    public List<Capture<TribesState>> play(int attempt) {
        TribesGame.applyEngineConstants();
        Constants.MAX_TURNS_CAPITALS = turnCap;
        // The engine's own meter prices the source agent's budget; pin it to the capture weights so a
        // portfolio never depends on whatever weights an earlier gate in this JVM installed there.
        ComputeMeter.W_ADVANCE = Weights.DEFAULT[0];
        ComputeMeter.W_COPY = Weights.DEFAULT[1];
        ComputeMeter.W_EVAL = Weights.DEFAULT[2];

        long levelSeed = seedBase + attempt;
        List<Capture<TribesState>> out = new ArrayList<>();
        ArrayList<Agent> players = new ArrayList<>();
        players.add(sourceAgent());
        players.add(twoPlayer ? sourceAgent() : new DoNothingAgent(SOURCE_AGENT_SEED));
        Game game = new Game();
        try {
            game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, mode);
            for (int ti = 0; ti < ticks.length; ti++) {
                GameState gs = game.captureAtTickAsTurnStart(ticks[ti]);
                if (gs.isGameOver()) break;
                if (!validate(gs)) {
                    if (ti == 0) break;      // stuck position at the first capture: abandon this seed
                    continue;
                }
                out.add(new Capture<>(attempt, levelSeed, ticks[ti], TribesGame.wrap(gs)));
            }
        } catch (Throwable t) {
            System.out.println("  [" + name + "] attempt " + attempt + " (seed " + levelSeed + "): engine threw "
                    + t.getClass().getSimpleName() + " - keeping " + out.size() + " captures");
        }
        return out;
    }

    private static Agent sourceAgent() {
        EMCTSParams p = new EMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = SOURCE_BUDGET_UNITS;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = 21;
        return new EMCTSAgent(SOURCE_AGENT_SEED, p);
    }

    /** {@code BuildV5Portfolio.validateScenario}: a live position on which a tiny plan does not throw. */
    static boolean validate(GameState gs) {
        int aid = gs.getActiveTribeID();
        if (aid < 0 || aid >= gs.getTribes().length) return false;
        Tribe at = gs.getTribes()[aid];
        int numUnits = 0;
        if (at.getCitiesID() != null) {
            for (Integer cId : at.getCitiesID()) {
                City c = (City) gs.getActor(cId);
                if (c != null && c.getUnitsID() != null) numUnits += c.getUnitsID().size();
            }
        }
        if (at.getNumCities() == 0 || numUnits == 0) return false;
        if (at.getScore() < MIN_VALID_SCORE) return false;
        try {
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = 21;
            p.randomInitialSequence = true;
            EMCTSAgent a = new EMCTSAgent(SOURCE_AGENT_SEED, p);
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int k = 0; k < gs.getTribes().length; k++) allIds.add(k);
            a.setPlayerIDs(aid, allIds);
            ElapsedCpuTimer ect = new ElapsedCpuTimer();
            ect.setMaxTimeMillis(50);
            GameState copy = gs.copy();
            copy.setRandomGenerator(new java.util.Random(SOURCE_AGENT_SEED));
            a.act(copy, ect);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String toString() {
        return name + " (seeds from " + seedBase + ", " + mode + ", phases " + Arrays.toString(ticks) + ")";
    }
}
