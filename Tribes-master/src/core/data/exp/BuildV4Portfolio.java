package core.data.exp;

import core.Constants;
import core.Types;
import core.actors.City;
import core.actors.Tribe;
import core.game.FastGameStateIO;
import core.game.Game;
import core.game.GameState;
import players.Agent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * v4 Isolation Phase: builds a fixed 100-scenario portfolio used by every agent at every thinking
 * time. Scenarios are mid/late-game {@link GameState} snapshots with high branching factor, captured
 * once and reused across the experiment.
 *
 * <p>Portfolio composition:
 * <ul>
 *   <li><b>50 single-player scenarios</b> — for each of 5 distinct level seeds, play one EMCTS-vs-passive
 *       game (SCORE mode, 30-turn limit) and capture the start-of-turn state for ticks 21..30.</li>
 *   <li><b>50 two-player scenarios</b> — 50 distinct level seeds, each one EMCTS-vs-EMCTS game (CAPITALS,
 *       50-turn limit), captured at the start of tick 25.</li>
 * </ul>
 *
 * <p>Source agents always use the deterministic compute-unit budget (1000ms-equivalent = 26800 units) so
 * the portfolio is reproducible across machines/threads. The captured map is the standard 11x11 water-free
 * board (IMPERIUS + IMPERIUS2) hardcoded in {@link GameState#init}.
 *
 * <p>Output: {@code test_results/v4/_portfolio/}
 * <ul>
 *   <li>{@code scenarios/scenario_NNN.bin} — gzipped binary {@link GameState} (via {@link FastGameStateIO})</li>
 *   <li>{@code portfolio.csv} — manifest: ScenarioId, Source, SourceGameId, SourceLevelSeed,
 *       SourceGameSeed, CapturedTick, ActiveTribe, ActiveTribeNumCities, ActiveTribeNumUnits,
 *       ActiveTribeScore, BranchingFactor</li>
 * </ul>
 *
 * <p>Resumable: if {@code _completed.txt} marks a scenario done, it is skipped on re-run.
 */
public class BuildV4Portfolio {

    public static final int    NUM_SINGLE_GAMES = 5;
    public static final int    SINGLE_FIRST_TICK = 21;       // last 10 turn-start states (ticks 21..30)
    public static final int    SINGLE_LAST_TICK  = 30;
    public static final int    NUM_DUAL_GAMES   = 50;
    public static final int    DUAL_CAPTURE_TICK = 25;

    public static final int    SOURCE_BUDGET_UNITS = 26_800; // 1000ms-equivalent (reproducible, compute-unit)
    public static final long   SINGLE_LEVEL_SEED_BASE = 4_000_000L;
    public static final long   DUAL_LEVEL_SEED_BASE   = 5_000_000L;
    public static final long   FIXED_GAME_SEED        = 9_000_001L;
    public static final long   SOURCE_AGENT_SEED      = 100L;

    /** Header for the portfolio.csv manifest. Kept stable; downstream readers depend on it. */
    public static final String MANIFEST_HEADER =
            "ScenarioId,Source,SourceGameId,SourceLevelSeed,SourceGameSeed,CapturedTick,ActiveTribeId," +
            "ActiveTribeNumCities,ActiveTribeNumUnits,ActiveTribeScore,BranchingFactor";

    private static final Types.TRIBE[] TRIBES = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "test_results/v4/_portfolio";
        File dir = new File(outDir);
        File scenariosDir = new File(dir, "scenarios");
        ExpCsv.ensureDir(scenariosDir);
        File ckpt = new File(dir, "_completed.txt");
        java.util.Set<String> done = ExpCsv.readCompleted(ckpt);
        File manifest = new File(dir, "portfolio.csv");

        ExpCsv.writeText(new File(dir, "config.txt"),
                "v4 Isolation Phase - Portfolio Generator\n" +
                "singlePlayer=" + NUM_SINGLE_GAMES + " games of EMCTS vs DoNothing (SCORE/30); capture ticks "
                        + SINGLE_FIRST_TICK + ".." + SINGLE_LAST_TICK + " (50 scenarios)\n" +
                "twoPlayer=" + NUM_DUAL_GAMES + " games of EMCTS vs EMCTS (CAPITALS/50); capture tick "
                        + DUAL_CAPTURE_TICK + " (50 scenarios)\n" +
                "sourceBudget=" + SOURCE_BUDGET_UNITS + " compute-units (1000ms-equiv, reproducible)\n" +
                "tribes=IMPERIUS vs IMPERIUS2 on 11x11 water-free board\n");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;

        int scenarioId = 0;
        long t0 = System.currentTimeMillis();

        // ---- 50 single-player scenarios (5 games × last 10 ticks) ----
        for (int g = 1; g <= NUM_SINGLE_GAMES; g++) {
            long levelSeed = SINGLE_LEVEL_SEED_BASE + g;
            System.out.println("[Portfolio] SINGLE game " + g + "/" + NUM_SINGLE_GAMES
                    + " (levelSeed=" + levelSeed + ")");
            ArrayList<Agent> players = new ArrayList<>();
            players.add(buildEmctsSourceAgent());
            players.add(new players.DoNothingAgent(SOURCE_AGENT_SEED));
            Game game = new Game();
            Constants.MAX_TURNS_CAPITALS = 30;       // unused in SCORE but set anyway
            game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, Types.GAME_MODE.SCORE);

            // Step turn by turn from tick 0 to SINGLE_LAST_TICK; capture at the start of each tick in
            // [SINGLE_FIRST_TICK, SINGLE_LAST_TICK]. We use captureAtTickAsTurnStart for the snapshot —
            // captureAtTick alone leaves activeTribeID pointing at the LAST tribe to act in the previous
            // tick (the passive opponent in single-player source games), which is the wrong player to
            // resume. The "skip if done" path keeps the plain captureAtTick because it only needs to
            // advance the LIVE state by one tick (no snapshot consumed).
            for (int tick = SINGLE_FIRST_TICK; tick <= SINGLE_LAST_TICK; tick++) {
                scenarioId++;
                String key = "scenario=" + sid(scenarioId);
                if (done.contains(key)) {
                    System.out.println("  SKIP (done) " + key);
                    game.captureAtTick(tick + 1);    // still need to step forward to keep loop coherent
                    continue;
                }
                GameState gs = game.captureAtTickAsTurnStart(tick);
                if (gs.isGameOver()) {
                    System.out.println("  game ended early at tick " + gs.getTick() + " — stopping");
                    // pad remaining ticks for this game with later games (don't count this scenario)
                    scenarioId--;
                    break;
                }
                if (!validateScenario(gs, scenarioId)) {
                    scenarioId--;
                    continue;
                }
                persistAndManifest(scenarioId, "single", g, levelSeed, FIXED_GAME_SEED, tick, gs,
                        scenariosDir, manifest);
                ExpCsv.markCompleted(ckpt, key);
            }
        }

        // ---- 50 two-player scenarios (50 games × tick 25) ----
        Constants.MAX_TURNS_CAPITALS = 50;
        for (int g = 1; g <= NUM_DUAL_GAMES; g++) {
            scenarioId++;
            String key = "scenario=" + sid(scenarioId);
            long levelSeed = DUAL_LEVEL_SEED_BASE + g;
            if (done.contains(key)) {
                System.out.println("[Portfolio] DUAL game " + g + " SKIP (done) " + key);
                continue;
            }
            System.out.println("[Portfolio] DUAL game " + g + "/" + NUM_DUAL_GAMES
                    + " (levelSeed=" + levelSeed + ")");
            ArrayList<Agent> players = new ArrayList<>();
            players.add(buildEmctsSourceAgent());
            players.add(buildEmctsSourceAgent());
            Game game = new Game();
            game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, Types.GAME_MODE.CAPITALS);

            GameState gs = game.captureAtTickAsTurnStart(DUAL_CAPTURE_TICK);
            if (gs.isGameOver()) {
                System.out.println("  game ended early at tick " + gs.getTick() + " — dropping scenario "
                        + sid(scenarioId));
                scenarioId--;   // keep IDs contiguous
                continue;
            }
            if (!validateScenario(gs, scenarioId)) {
                scenarioId--;
                continue;
            }
            persistAndManifest(scenarioId, "dual", g, levelSeed, FIXED_GAME_SEED, DUAL_CAPTURE_TICK, gs,
                    scenariosDir, manifest);
            ExpCsv.markCompleted(ckpt, key);
        }

        System.out.println(String.format(Locale.ROOT,
                "[Portfolio] Complete. %d scenarios in %.1fs. Output: %s",
                scenarioId, (System.currentTimeMillis() - t0) / 1000.0, dir.getPath()));
    }

    private static Agent buildEmctsSourceAgent() {
        EMCTSParams p = new EMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = SOURCE_BUDGET_UNITS;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = ExpAgents.DEPTH;
        return new EMCTSAgent(SOURCE_AGENT_SEED, p);
    }

    private static void persistAndManifest(int scenarioId, String source, int sourceGameId, long levelSeed,
                                           long gameSeed, int capturedTick, GameState gs,
                                           File scenariosDir, File manifest) throws Exception {
        File bin = new File(scenariosDir, "scenario_" + sid(scenarioId) + ".bin");
        FastGameStateIO.save(gs, bin);

        int activeTribeId = gs.getActiveTribeID();
        Tribe at = gs.getTribes()[activeTribeId];
        int numCities = at.getNumCities();
        int numUnits = 0;
        for (Integer cId : at.getCitiesID()) {
            City c = (City) gs.getActor(cId);
            if (c != null) numUnits += c.getUnitsID().size();
        }
        int branchingFactor = gs.getAllAvailableActions().size();
        int score = at.getScore();

        String row = scenarioId + "," + source + "," + sourceGameId + "," + levelSeed + "," + gameSeed + ","
                + capturedTick + "," + activeTribeId + "," + numCities + "," + numUnits + "," + score + ","
                + branchingFactor;
        ExpCsv.append(manifest, MANIFEST_HEADER, java.util.Collections.singletonList(row));
        System.out.println(String.format(Locale.ROOT,
                "  scenario %s: tribe=%d cities=%d units=%d score=%d branching=%d  (%s, gameId=%d, tick=%d)",
                sid(scenarioId), activeTribeId, numCities, numUnits, score, branchingFactor,
                source, sourceGameId, capturedTick));
    }

    /**
     * Pre-persist validation: rejects scenarios where the active tribe has no cities or no units (the
     * captureAtTickAsTurnStart fix should keep this from happening for single-player sources where the
     * passive opponent used to end up active — this is the belt-and-suspenders check), and rejects
     * scenarios where a tiny EMCTS plan-turn throws (catches NPEs from any edge-case engine state
     * before a 600-task isolation-phase run wastes hours on it). Restores Constants.TURN_TIME_MILLIS.
     */
    public static boolean validateScenario(GameState gs, int scenarioId) {
        int aid = gs.getActiveTribeID();
        if (aid < 0 || aid >= gs.getTribes().length) {
            System.out.println("  REJECT " + sid(scenarioId) + ": invalid activeTribeID=" + aid);
            return false;
        }
        Tribe at = gs.getTribes()[aid];
        int numCities = at.getNumCities();
        int numUnits = 0;
        if (at.getCitiesID() != null) {
            for (Integer cId : at.getCitiesID()) {
                City c = (City) gs.getActor(cId);
                if (c != null && c.getUnitsID() != null) numUnits += c.getUnitsID().size();
            }
        }
        if (numCities == 0 || numUnits == 0) {
            System.out.println("  REJECT " + sid(scenarioId) + ": activeTribe " + aid
                    + " has cities=" + numCities + " units=" + numUnits + " (passive/empty position)");
            return false;
        }
        // Smoke-act with a tiny EMCTS: catches NPE / IllegalState from edge-case states.
        long savedTtm = Constants.TURN_TIME_MILLIS;
        try {
            Constants.TURN_TIME_MILLIS = 50;
            EMCTSParams p = new EMCTSParams();
            p.stop_type = p.STOP_TIME;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;
            p.randomInitialSequence = true;
            EMCTSAgent a = new EMCTSAgent(SOURCE_AGENT_SEED, p);
            ArrayList<Integer> allIds = new ArrayList<>();
            for (int k = 0; k < gs.getTribes().length; k++) allIds.add(k);
            a.setPlayerIDs(aid, allIds);
            ElapsedCpuTimer ect = new ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
            GameState copy = gs.copy();
            copy.setRandomGenerator(new java.util.Random(SOURCE_AGENT_SEED));
            a.act(copy, ect);
            return true;
        } catch (Throwable t) {
            System.out.println("  REJECT " + sid(scenarioId) + ": EMCTS act() threw " + t);
            return false;
        } finally {
            Constants.TURN_TIME_MILLIS = savedTtm;
        }
    }

    /** Zero-padded 3-digit scenario id (e.g. 007, 042, 100). */
    public static String sid(int id) { return String.format(Locale.ROOT, "%03d", id); }
}
