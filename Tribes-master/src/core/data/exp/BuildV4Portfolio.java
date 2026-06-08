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
 * <p>Not resumable — always regenerates a fresh portfolio. The scenarios dir, manifest, and
 * {@code _completed.txt} are wiped on entry. Re-running is cheap (~10 minutes).
 *
 * <p>The generator EXTENDS its source-game pool dynamically: it keeps trying additional level seeds
 * until {@link #NUM_SINGLE_SCENARIOS} valid singles and {@link #NUM_DUAL_SCENARIOS} valid duals are
 * collected, capped at {@link #MAX_SINGLE_GAME_ATTEMPTS} and {@link #MAX_DUAL_GAME_ATTEMPTS}. Some
 * level seeds produce "stuck" games (e.g. an EMCTS confined to its spawn tile that never develops);
 * those are detected by {@link #validateScenario} and the whole game is aborted on first-tick fail.
 */
public class BuildV4Portfolio {

    /** Target number of single-source scenarios (last 10 turn-start states across some 1-tribe games). */
    public static final int    NUM_SINGLE_SCENARIOS    = 50;
    /** Target number of dual-source scenarios (tick 25 of 2-tribe games). */
    public static final int    NUM_DUAL_SCENARIOS      = 50;
    /** Cap on level-seed attempts before giving up on a category. Roughly 3× the base 5/50 we'd expect
     *  to succeed first try; well-developed source games are the common case so the cap is rarely hit. */
    public static final int    MAX_SINGLE_GAME_ATTEMPTS = 15;
    public static final int    MAX_DUAL_GAME_ATTEMPTS   = 150;

    public static final int    SINGLE_FIRST_TICK = 21;       // last 10 turn-start states (ticks 21..30)
    public static final int    SINGLE_LAST_TICK  = 30;
    public static final int    DUAL_CAPTURE_TICK = 25;

    /** Minimum active-tribe score for validateScenario to accept the position. A "stuck" EMCTS confined
     *  to its spawn tile stays at the starting score (~390) across all ticks — well below 500. A
     *  healthy game by tick 21 has scored thousands. */
    public static final int    MIN_VALID_SCORE = 500;

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
        // Always start fresh: a prior run may have persisted scenarios under the old (broken
        // active-tribe / loose validation) behaviour, and a partial portfolio is a corruption hazard.
        deleteDir(scenariosDir);
        File manifest = new File(dir, "portfolio.csv");
        if (manifest.exists()) manifest.delete();
        File ckpt = new File(dir, "_completed.txt");
        if (ckpt.exists()) ckpt.delete();
        ExpCsv.ensureDir(scenariosDir);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "v4 Isolation Phase - Portfolio Generator\n" +
                "singlePlayer=" + NUM_SINGLE_SCENARIOS + " scenarios from EMCTS vs DoNothing (SCORE/30); capture ticks "
                        + SINGLE_FIRST_TICK + ".." + SINGLE_LAST_TICK + "; level-seed attempts capped at "
                        + MAX_SINGLE_GAME_ATTEMPTS + "\n" +
                "twoPlayer=" + NUM_DUAL_SCENARIOS + " scenarios from EMCTS vs EMCTS (CAPITALS/50); capture tick "
                        + DUAL_CAPTURE_TICK + "; level-seed attempts capped at " + MAX_DUAL_GAME_ATTEMPTS + "\n" +
                "sourceBudget=" + SOURCE_BUDGET_UNITS + " compute-units (1000ms-equiv, reproducible)\n" +
                "validation=activeTribe cities>=1 AND units>=1 AND score>=" + MIN_VALID_SCORE
                        + " AND a tiny EMCTS planTurn does not throw\n" +
                "stuck-game guard=if FIRST captured tick of a source game fails validation the whole game is aborted (no more ticks tried)\n" +
                "tribes=IMPERIUS vs IMPERIUS2 on 11x11 water-free board\n");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;

        int scenarioId = 0;
        long t0 = System.currentTimeMillis();

        // ---- SINGLE-source scenarios — extend the level-seed pool until NUM_SINGLE_SCENARIOS valid
        // captures are collected (or the safety cap is reached). Some level seeds produce stuck games
        // (EMCTS pinned to its spawn tile, score never rising above ~390) — those are detected on the
        // first captured tick and the whole game is aborted so the next seed is tried.
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
                        // Stuck-game guard: a healthy EMCTS has DEFINITELY developed by tick 21 (cities>=2,
                        // hundreds of stars earned). If the very first captured tick is degenerate the
                        // remaining ticks of the same game will be too — abort and try the next seed.
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
                // Tribes engine has a known ClassCastException (and occasional other throws) under
                // deep search — same root cause that ExperimentEngine.runMatchup retries with perturbed
                // seeds for the full-game phases. For the portfolio build the cheap fix is: skip this
                // level seed entirely; the dynamic-extension loop just tries the next one.
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
                // Engine ClassCastException (or similar) during one of the source players' planning —
                // same root cause that ExperimentEngine handles via perturbed-seed retries elsewhere.
                // For the portfolio: skip this seed; the next attempt's distinct level/agent state is
                // a different deep-search trajectory and most likely won't trigger it again.
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
        int score = at.getScore();
        if (numCities == 0 || numUnits == 0) {
            System.out.println("  REJECT " + sid(scenarioId) + ": activeTribe " + aid
                    + " has cities=" + numCities + " units=" + numUnits + " (passive/empty position)");
            return false;
        }
        // "Stuck game" guard: a tribe pinned to its spawn tile (no expansion / no tech / no income)
        // stays at the starting score (~390 IIRC) across all ticks. A healthy EMCTS source at the
        // first captured tick (21+) has scored thousands. The 500 threshold separates clearly.
        if (score < MIN_VALID_SCORE) {
            System.out.println("  REJECT " + sid(scenarioId) + ": activeTribe " + aid
                    + " has cities=" + numCities + " units=" + numUnits + " score=" + score
                    + " (< " + MIN_VALID_SCORE + " — stuck game)");
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
