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
 * v5 Isolation Phase: builds a fixed 200-scenario portfolio used by every agent at every compute
 * budget. Identical methodology to {@link BuildV4Portfolio} (same level-seed bases, capture ticks,
 * game modes and validation), scaled up to 200 scenarios — so the first 100 captures are the SAME
 * game states from the same turns as v4, with 100 additional captures appended.
 *
 * <p>Portfolio composition:
 * <ul>
 *   <li><b>100 single-player scenarios</b> — EMCTS-vs-passive games (SCORE mode, 30-turn limit),
 *       capturing the start-of-turn state for ticks 21..30 (10 per game ⇒ 10 source games).</li>
 *   <li><b>100 two-player scenarios</b> — EMCTS-vs-EMCTS games (CAPITALS, 50-turn limit), captured at
 *       the start of tick 25 (one per game ⇒ 100 source games).</li>
 * </ul>
 *
 * <p>Source agents use the deterministic compute-unit budget (1000ms-equivalent = 26800 units) so the
 * portfolio is reproducible across machines/threads. Map is the standard 11x11 water-free board.
 *
 * <p>Output: {@code test_results/v5/_portfolio/} — {@code scenarios/scenario_NNN.bin} +
 * {@code portfolio.csv} (manifest, same header as v4).
 */
public class BuildV5Portfolio {

    /** Target number of single-source scenarios (turn-start states across some 1-tribe games). */
    public static final int    NUM_SINGLE_SCENARIOS    = 100;
    /** Target number of dual-source scenarios (tick 25 of 2-tribe games). */
    public static final int    NUM_DUAL_SCENARIOS      = 100;
    /** Cap on level-seed attempts before giving up on a category. ~3× the games we expect to need
     *  (10 single games × 10 ticks = 100; 100 dual games × 1 = 100). */
    public static final int    MAX_SINGLE_GAME_ATTEMPTS = 30;
    public static final int    MAX_DUAL_GAME_ATTEMPTS   = 300;

    public static final int    SINGLE_FIRST_TICK = 21;       // last 10 turn-start states (ticks 21..30)
    public static final int    SINGLE_LAST_TICK  = 30;
    public static final int    DUAL_CAPTURE_TICK = 25;

    /** Minimum active-tribe score for validateScenario to accept the position (stuck-game guard). */
    public static final int    MIN_VALID_SCORE = 500;

    public static final int    SOURCE_BUDGET_UNITS = 26_800; // 1000ms-equivalent (reproducible, compute-unit)
    public static final long   SINGLE_LEVEL_SEED_BASE = 4_000_000L;
    public static final long   DUAL_LEVEL_SEED_BASE   = 5_000_000L;
    public static final long   FIXED_GAME_SEED        = 9_000_001L;
    public static final long   SOURCE_AGENT_SEED      = 100L;

    /** Header for the portfolio.csv manifest. The v4 columns come first (downstream readers parse the
     *  leading columns by index), followed by the v5 action-type composition of the captured state
     *  (H5: does the single/dual gap track combat-action availability rather than the source label?).
     *  Categories: Attack | Move | other unit actions (Capture/Convert/Heal/Upgrade/...) | city
     *  actions | tribe actions (Research/EndTurn/...). */
    public static final String MANIFEST_HEADER =
            "ScenarioId,Source,SourceGameId,SourceLevelSeed,SourceGameSeed,CapturedTick,ActiveTribeId," +
            "ActiveTribeNumCities,ActiveTribeNumUnits,ActiveTribeScore,BranchingFactor," +
            "NumAttackActions,NumMoveActions,NumOtherUnitActions,NumCityActions,NumTribeActions";

    private static final Types.TRIBE[] TRIBES = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "test_results/v5/_portfolio";
        File dir = new File(outDir);
        File scenariosDir = new File(dir, "scenarios");
        // Always start fresh: a partial portfolio is a corruption hazard.
        deleteDir(scenariosDir);
        File manifest = new File(dir, "portfolio.csv");
        if (manifest.exists()) manifest.delete();
        File ckpt = new File(dir, "_completed.txt");
        if (ckpt.exists()) ckpt.delete();
        ExpCsv.ensureDir(scenariosDir);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "v5 Isolation Phase - Portfolio Generator\n" +
                "singlePlayer=" + NUM_SINGLE_SCENARIOS + " scenarios from EMCTS vs DoNothing (SCORE/30); capture ticks "
                        + SINGLE_FIRST_TICK + ".." + SINGLE_LAST_TICK + "; level-seed attempts capped at "
                        + MAX_SINGLE_GAME_ATTEMPTS + "\n" +
                "twoPlayer=" + NUM_DUAL_SCENARIOS + " scenarios from EMCTS vs EMCTS (CAPITALS/50); capture tick "
                        + DUAL_CAPTURE_TICK + "; level-seed attempts capped at " + MAX_DUAL_GAME_ATTEMPTS + "\n" +
                "sourceBudget=" + SOURCE_BUDGET_UNITS + " compute-units (1000ms-equiv, reproducible)\n" +
                "selection=same level-seed bases / capture ticks as v4, so the first 100 captures are the\n" +
                "          SAME game states from the same turns as v4, with 100 more appended\n" +
                "validation=activeTribe cities>=1 AND units>=1 AND score>=" + MIN_VALID_SCORE
                        + " AND a tiny EMCTS planTurn does not throw\n" +
                "stuck-game guard=if FIRST captured tick of a source game fails validation the whole game is aborted\n" +
                "tribes=IMPERIUS vs IMPERIUS2 on 11x11 water-free board\n");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;

        int scenarioId = 0;
        long t0 = System.currentTimeMillis();

        // ---- SINGLE-source scenarios — extend the level-seed pool until NUM_SINGLE_SCENARIOS valid
        // captures are collected (or the safety cap is reached).
        Constants.MAX_TURNS_CAPITALS = 30;
        int validSingles = 0;
        int singleGamesTried = 0;
        for (int gameAttempt = 1;
             validSingles < NUM_SINGLE_SCENARIOS && gameAttempt <= MAX_SINGLE_GAME_ATTEMPTS;
             gameAttempt++) {
            long levelSeed = SINGLE_LEVEL_SEED_BASE + gameAttempt;
            singleGamesTried = gameAttempt;
            System.out.println("[Portfolio] SINGLE attempt " + gameAttempt + " (levelSeed=" + levelSeed
                    + "); valid so far " + validSingles + "/" + NUM_SINGLE_SCENARIOS);
            ArrayList<Agent> players = new ArrayList<>();
            players.add(buildEmctsSourceAgent());
            players.add(new players.DoNothingAgent(SOURCE_AGENT_SEED));
            Game game = new Game();
            int collectedThisGame = 0;
            try {
                game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, Types.GAME_MODE.SCORE);

                for (int tick = SINGLE_FIRST_TICK; tick <= SINGLE_LAST_TICK; tick++) {
                    if (validSingles >= NUM_SINGLE_SCENARIOS) break;
                    GameState gs = game.captureAtTickAsTurnStart(tick);
                    if (gs.isGameOver()) {
                        System.out.println("  game ended early at tick " + gs.getTick());
                        break;
                    }
                    if (!validateScenario(gs, scenarioId + 1)) {
                        if (tick == SINGLE_FIRST_TICK) {
                            System.out.println("  ABORT game " + gameAttempt
                                    + " (stuck position at first captured tick — next seed)");
                            break;
                        }
                        continue;
                    }
                    scenarioId++;
                    persistAndManifest(scenarioId, "single", gameAttempt, levelSeed, FIXED_GAME_SEED, tick, gs,
                            scenariosDir, manifest);
                    validSingles++;
                    collectedThisGame++;
                }
            } catch (Throwable t) {
                System.out.println("  ABORT game " + gameAttempt + ": engine threw "
                        + t.getClass().getSimpleName() + " — next seed");
            }
            System.out.println("  collected " + collectedThisGame + " valid scenarios from game " + gameAttempt);
        }
        if (validSingles < NUM_SINGLE_SCENARIOS) {
            System.out.println("  WARN: only " + validSingles + "/" + NUM_SINGLE_SCENARIOS
                    + " valid single scenarios after " + singleGamesTried + " attempts (cap "
                    + MAX_SINGLE_GAME_ATTEMPTS + ").");
        }

        // ---- DUAL-source scenarios — same extension model, one capture per source game at tick 25.
        Constants.MAX_TURNS_CAPITALS = 50;
        int validDuals = 0;
        int dualGamesTried = 0;
        for (int gameAttempt = 1;
             validDuals < NUM_DUAL_SCENARIOS && gameAttempt <= MAX_DUAL_GAME_ATTEMPTS;
             gameAttempt++) {
            long levelSeed = DUAL_LEVEL_SEED_BASE + gameAttempt;
            dualGamesTried = gameAttempt;
            System.out.println("[Portfolio] DUAL attempt " + gameAttempt + " (levelSeed=" + levelSeed
                    + "); valid so far " + validDuals + "/" + NUM_DUAL_SCENARIOS);
            ArrayList<Agent> players = new ArrayList<>();
            players.add(buildEmctsSourceAgent());
            players.add(buildEmctsSourceAgent());
            Game game = new Game();
            GameState gs;
            try {
                game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, Types.GAME_MODE.CAPITALS);
                gs = game.captureAtTickAsTurnStart(DUAL_CAPTURE_TICK);
            } catch (Throwable t) {
                System.out.println("  ABORT game " + gameAttempt + ": engine threw "
                        + t.getClass().getSimpleName() + " — next seed");
                continue;
            }
            if (gs.isGameOver()) {
                System.out.println("  game ended early at tick " + gs.getTick() + " — skipping");
                continue;
            }
            if (!validateScenario(gs, scenarioId + 1)) {
                continue;
            }
            scenarioId++;
            persistAndManifest(scenarioId, "dual", gameAttempt, levelSeed, FIXED_GAME_SEED, DUAL_CAPTURE_TICK, gs,
                    scenariosDir, manifest);
            validDuals++;
        }
        if (validDuals < NUM_DUAL_SCENARIOS) {
            System.out.println("  WARN: only " + validDuals + "/" + NUM_DUAL_SCENARIOS
                    + " valid dual scenarios after " + dualGamesTried + " attempts (cap "
                    + MAX_DUAL_GAME_ATTEMPTS + ").");
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
        java.util.ArrayList<core.actions.Action> allActions = gs.getAllAvailableActions();
        int branchingFactor = allActions.size();
        int score = at.getScore();
        // v5 action-type composition (H5): how combat- vs economy-oriented is this state's action space?
        int nAttack = 0, nMove = 0, nOtherUnit = 0, nCity = 0, nTribe = 0;
        for (core.actions.Action a : allActions) {
            if (a instanceof core.actions.unitactions.Attack) nAttack++;
            else if (a instanceof core.actions.unitactions.Move) nMove++;
            else if (a instanceof core.actions.unitactions.UnitAction) nOtherUnit++;
            else if (a instanceof core.actions.cityactions.CityAction) nCity++;
            else if (a instanceof core.actions.tribeactions.TribeAction) nTribe++;
        }

        String row = scenarioId + "," + source + "," + sourceGameId + "," + levelSeed + "," + gameSeed + ","
                + capturedTick + "," + activeTribeId + "," + numCities + "," + numUnits + "," + score + ","
                + branchingFactor + ","
                + nAttack + "," + nMove + "," + nOtherUnit + "," + nCity + "," + nTribe;
        ExpCsv.append(manifest, MANIFEST_HEADER, java.util.Collections.singletonList(row));
        System.out.println(String.format(Locale.ROOT,
                "  scenario %s: tribe=%d cities=%d units=%d score=%d branching=%d  (%s, gameId=%d, tick=%d)",
                sid(scenarioId), activeTribeId, numCities, numUnits, score, branchingFactor,
                source, sourceGameId, capturedTick));
    }

    /** Pre-persist validation: rejects empty/stuck positions and states where a tiny EMCTS plan-turn
     *  throws. Restores Constants.TURN_TIME_MILLIS. */
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
        int score = at.getScore();
        if (numCities == 0 || numUnits == 0) {
            System.out.println("  REJECT " + sid(scenarioId) + ": activeTribe " + aid
                    + " has cities=" + numCities + " units=" + numUnits + " (passive/empty position)");
            return false;
        }
        if (score < MIN_VALID_SCORE) {
            System.out.println("  REJECT " + sid(scenarioId) + ": activeTribe " + aid
                    + " has cities=" + numCities + " units=" + numUnits + " score=" + score
                    + " (< " + MIN_VALID_SCORE + " — stuck game)");
            return false;
        }
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

    /** Zero-padded 3-digit scenario id (e.g. 007, 042, 200). */
    public static String sid(int id) { return String.format(Locale.ROOT, "%03d", id); }

    /** Recursively remove a directory (cleanup before fresh portfolio generation). */
    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
