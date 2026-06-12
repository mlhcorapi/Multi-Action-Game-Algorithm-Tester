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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * v5 Isolation Phase: builds a fixed 200-scenario portfolio used by every agent at every compute
 * budget. Same methodology as {@link BuildV4Portfolio} (same level-seed bases, game modes and
 * validation), but:
 * <ul>
 *   <li>100 single-player + 100 two-player scenarios;</li>
 *   <li>single captures at turns 10,12,14,16,18,20,22,24,26,28,30 (11/game); dual captures at turns
 *       15,20,25 (3/game);</li>
 *   <li>source games are generated <b>in parallel</b> across worker threads — each game is independent
 *       and the per-thread {@link core.game.ComputeMeter} keeps the deterministic compute budget
 *       thread-safe. Captures are collected and IDs assigned in (gameAttempt, tick) order, so the
 *       portfolio is reproducible regardless of thread-completion order;</li>
 *   <li>level-seed attempt caps raised 5× (failing seeds are common at the earlier capture turns).</li>
 * </ul>
 *
 * <p>Output: {@code test_results/v5/_portfolio/} — {@code scenarios/scenario_NNN.bin} +
 * {@code portfolio.csv} (manifest, with the v5 action-composition columns).
 *
 * <p>Usage: {@code BuildV5Portfolio [outDir] [threads] [numSingle] [numDual]}. The last two override
 * the scenario targets (for quick test builds); production runs pass only outDir + threads.
 */
public class BuildV5Portfolio {

    /** Target number of single-source scenarios (turn-start states across some 1-tribe games). */
    public static final int    NUM_SINGLE_SCENARIOS    = 100;
    /** Target number of dual-source scenarios. */
    public static final int    NUM_DUAL_SCENARIOS      = 100;
    /** Cap on level-seed attempts before giving up on a category (raised 5× from the v4-style caps —
     *  the earlier capture turns make more source seeds fail validation). */
    public static final int    MAX_SINGLE_GAME_ATTEMPTS = 150;
    public static final int    MAX_DUAL_GAME_ATTEMPTS   = 1500;

    /** Single-player capture turns (11 turn-start states per source game). */
    public static final int[]  SINGLE_TICKS = {10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30};
    /** Two-player capture turns (3 turn-start states per source game). */
    public static final int[]  DUAL_TICKS   = {15, 20, 25};

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

    /** One captured scenario before ID assignment: provenance + the snapshot. */
    private static final class Cap {
        final int gameAttempt; final long levelSeed; final int tick; final GameState gs;
        Cap(int gameAttempt, long levelSeed, int tick, GameState gs) {
            this.gameAttempt = gameAttempt; this.levelSeed = levelSeed; this.tick = tick; this.gs = gs;
        }
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "test_results/v5/_portfolio";
        int threads   = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        int numSingle = args.length > 2 ? Integer.parseInt(args[2]) : NUM_SINGLE_SCENARIOS;
        int numDual   = args.length > 3 ? Integer.parseInt(args[3]) : NUM_DUAL_SCENARIOS;

        File dir = new File(outDir);
        File scenariosDir = new File(dir, "scenarios");
        File manifest = new File(dir, "portfolio.csv");
        File doneMarker = new File(dir, "_portfolio_complete.txt");

        // Checkpoint: if a COMPLETE portfolio for these exact targets already exists, skip the ~hour
        // rebuild (the server may restart during the long isolation phase, which re-enters this main).
        String wantTag = "single=" + numSingle + " dual=" + numDual;
        if (doneMarker.exists() && manifest.exists()) {
            try {
                String have = new String(java.nio.file.Files.readAllBytes(doneMarker.toPath())).trim();
                long rows = java.nio.file.Files.lines(manifest.toPath()).count() - 1; // minus header
                if (have.equals(wantTag) && rows == (numSingle + numDual)) {
                    System.out.println("[Portfolio] already complete (" + wantTag + ", " + rows
                            + " scenarios) — skipping regeneration. Delete " + doneMarker.getName()
                            + " to force a rebuild.");
                    return;
                }
            } catch (Exception ignore) { /* fall through to a fresh build */ }
        }

        // Not complete (fresh, partial, or target changed): always start clean — a partial portfolio
        // is a corruption hazard, and the build itself is short relative to the isolation phase.
        deleteDir(scenariosDir);
        if (manifest.exists()) manifest.delete();
        if (doneMarker.exists()) doneMarker.delete();
        File ckpt = new File(dir, "_completed.txt");
        if (ckpt.exists()) ckpt.delete();
        ExpCsv.ensureDir(scenariosDir);

        ExpCsv.writeText(new File(dir, "config.txt"),
                "v5 Isolation Phase - Portfolio Generator (parallel, " + threads + " threads)\n" +
                "singlePlayer=" + numSingle + " scenarios from EMCTS vs DoNothing (SCORE); capture turns "
                        + java.util.Arrays.toString(SINGLE_TICKS) + "; level-seed attempts capped at "
                        + MAX_SINGLE_GAME_ATTEMPTS + "\n" +
                "twoPlayer=" + numDual + " scenarios from EMCTS vs EMCTS (CAPITALS); capture turns "
                        + java.util.Arrays.toString(DUAL_TICKS) + "; level-seed attempts capped at "
                        + MAX_DUAL_GAME_ATTEMPTS + "\n" +
                "sourceBudget=" + SOURCE_BUDGET_UNITS + " compute-units (1000ms-equiv, reproducible)\n" +
                "parallel=source games run concurrently; captures collected + IDs assigned in "
                        + "(gameAttempt, tick) order so the portfolio is thread-order-independent\n" +
                "validation=activeTribe cities>=1 AND units>=1 AND score>=" + MIN_VALID_SCORE
                        + " AND a tiny EMCTS planTurn does not throw\n" +
                "stuck-game guard=if FIRST captured turn of a source game fails validation the whole game is aborted\n" +
                "tribes=IMPERIUS vs IMPERIUS2 on 11x11 water-free board\n");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;

        long t0 = System.currentTimeMillis();

        // SINGLE-source: EMCTS vs DoNothing, SCORE mode. MAX_TURNS_CAPITALS must cover the last capture turn.
        Constants.MAX_TURNS_CAPITALS = SINGLE_TICKS[SINGLE_TICKS.length - 1];
        List<Cap> singles = collectCategory("single", SINGLE_LEVEL_SEED_BASE, Types.GAME_MODE.SCORE,
                SINGLE_TICKS, numSingle, MAX_SINGLE_GAME_ATTEMPTS, false, threads);

        // DUAL-source: EMCTS vs EMCTS, CAPITALS mode.
        Constants.MAX_TURNS_CAPITALS = 50;
        List<Cap> duals = collectCategory("dual", DUAL_LEVEL_SEED_BASE, Types.GAME_MODE.CAPITALS,
                DUAL_TICKS, numDual, MAX_DUAL_GAME_ATTEMPTS, true, threads);

        // Persist single-threaded in (category, gameAttempt, tick) order — deterministic IDs, no write contention.
        int scenarioId = 0;
        for (Cap c : singles) persistAndManifest(++scenarioId, "single", c.gameAttempt, c.levelSeed,
                FIXED_GAME_SEED, c.tick, c.gs, scenariosDir, manifest);
        for (Cap c : duals)   persistAndManifest(++scenarioId, "dual", c.gameAttempt, c.levelSeed,
                FIXED_GAME_SEED, c.tick, c.gs, scenariosDir, manifest);

        // Completion marker — read on re-entry to skip rebuilding a finished portfolio.
        if (scenarioId == numSingle + numDual) {
            ExpCsv.writeText(doneMarker, wantTag + "\n");
        }
        System.out.println(String.format(Locale.ROOT,
                "[Portfolio] Complete. %d scenarios (%d single + %d dual) in %.1fs. Output: %s",
                scenarioId, singles.size(), duals.size(), (System.currentTimeMillis() - t0) / 1000.0, dir.getPath()));
    }

    /**
     * Runs source games concurrently (in attempt-ordered waves of {@code threads}) until {@code target}
     * valid scenarios are collected or the attempt cap is hit. Captures are returned in
     * (gameAttempt, tick) order, so downstream ID assignment is independent of thread timing.
     */
    private static List<Cap> collectCategory(String source, long seedBase, Types.GAME_MODE mode,
                                             int[] ticks, int target, int maxAttempts,
                                             boolean twoPlayer, int threads) throws Exception {
        List<Cap> collected = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            int next = 1;
            while (collected.size() < target && next <= maxAttempts) {
                int start = next, end = Math.min(maxAttempts, start + Math.max(1, threads) - 1);
                List<Future<List<Cap>>> futures = new ArrayList<>();
                for (int a = start; a <= end; a++) {
                    final int attempt = a;
                    final long levelSeed = seedBase + a;
                    futures.add(pool.submit(() -> runOneSourceGame(attempt, levelSeed, mode, ticks, twoPlayer)));
                }
                for (Future<List<Cap>> f : futures) {     // drained in submission (= attempt) order
                    List<Cap> caps;
                    try { caps = f.get(); } catch (Exception e) { caps = Collections.emptyList(); }
                    for (Cap c : caps) if (collected.size() < target) collected.add(c);
                }
                System.out.println(String.format(Locale.ROOT,
                        "[Portfolio] %s: attempts %d..%d -> %d/%d valid", source, start, end,
                        collected.size(), target));
                next = end + 1;
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.HOURS);
        }
        if (collected.size() < target) {
            System.out.println("  WARN: only " + collected.size() + "/" + target + " valid " + source
                    + " scenarios after " + maxAttempts + " attempts.");
        }
        return collected;
    }

    /** Plays one source game and returns its valid captures (in tick order). Applies the stuck-game
     *  guard: if the FIRST captured turn fails validation the whole game is abandoned. Swallows engine
     *  throws (returns whatever was captured before the throw) so one bad seed never aborts the build. */
    private static List<Cap> runOneSourceGame(int attempt, long levelSeed, Types.GAME_MODE mode,
                                              int[] ticks, boolean twoPlayer) {
        List<Cap> out = new ArrayList<>();
        ArrayList<Agent> players = new ArrayList<>();
        players.add(buildEmctsSourceAgent());
        players.add(twoPlayer ? buildEmctsSourceAgent() : new players.DoNothingAgent(SOURCE_AGENT_SEED));
        Game game = new Game();
        try {
            game.init(players, levelSeed, TRIBES, FIXED_GAME_SEED, mode);
            for (int ti = 0; ti < ticks.length; ti++) {
                GameState gs = game.captureAtTickAsTurnStart(ticks[ti]);
                if (gs.isGameOver()) break;
                if (!validateScenario(gs, attempt)) {
                    if (ti == 0) break;   // stuck position at first captured turn — abandon this seed
                    continue;
                }
                out.add(new Cap(attempt, levelSeed, ticks[ti], gs));
            }
        } catch (Throwable t) {
            System.out.println("  game attempt " + attempt + " (seed " + levelSeed + "): engine threw "
                    + t.getClass().getSimpleName() + " — keeping " + out.size() + " captures, next seed");
        }
        return out;
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
     *  throws. Thread-safe — the validation budget is set on a local timer rather than by mutating the
     *  global {@code Constants.TURN_TIME_MILLIS}, so it can run on many source games concurrently. */
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
            return false;   // passive/empty position (common at first captured turn — quiet)
        }
        if (score < MIN_VALID_SCORE) {
            return false;   // stuck game (spawn-pinned tribe stays at the starting score)
        }
        // Smoke-act with a tiny EMCTS to catch NPE / IllegalState from edge-case states. The 50ms budget
        // is set on this local timer only — no global Constants mutation, so this is thread-safe.
        try {
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
            ect.setMaxTimeMillis(50);
            GameState copy = gs.copy();
            copy.setRandomGenerator(new java.util.Random(SOURCE_AGENT_SEED));
            a.act(copy, ect);
            return true;
        } catch (Throwable t) {
            System.out.println("  REJECT " + sid(scenarioId) + ": EMCTS act() threw " + t);
            return false;
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
