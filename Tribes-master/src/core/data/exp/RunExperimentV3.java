package core.data.exp;

import core.Constants;
import core.Types;
import core.game.Game;
import core.game.GameState;
import players.Agent;

import java.io.File;
import java.util.*;

/**
 * Third experiment session (v3). Changes vs v2:
 *   1. 500 games for EVERY trial (Phase 3 all levels; Phase 2 standard levels). Re-run from scratch -
 *      no additive combining with v1/v2 (the conditions differ - see below).
 *   2. Phase 3 starts from a FIXED turn-3 position. Turns 0-2 are degenerate (every agent plays them
 *      identically), so this captures one EMCTS game's board at the start of turn 3 and begins ALL
 *      Phase-3 games from that single position - removing the degenerate opening and giving a clean,
 *      common, non-trivial start. Logged turns therefore begin at turn 3.
 *   3. Phase 2 drops MCTS (it loses ~100% and does not differentiate the algorithms); baselines are
 *      EMCTS + OEP only.
 *   4. Phase 2 at 1000ms tests ALL crossover ratios (a competitive ratio sweep) at 300 games/matchup;
 *      500ms and 5000ms keep {5, best-from-Phase3} at 500 games/matchup.
 *
 * Output: test_results/v3/. Analyse separately (different conditions): python analysis/analyze.py test_results/v3
 *
 * Usage:  java -Xmx<big>g -cp out;lib/*  core.data.exp.RunExperimentV3 [threads]
 *   On a 1TB / 128-core server, e.g.:  java -Xmx700g -cp "out:lib/*" core.data.exp.RunExperimentV3 120
 */
public class RunExperimentV3 {

    static final String BASE = "test_results/v3";
    static final long FIXED_LEVEL_SEED = 777_777L;   // matches the established Phase-3 map seed
    static final long FIXED_GAME_SEED  = 888_888L;
    static final int  START_TURN    = 3;             // skip the degenerate opening turns 0-2
    static final int  WARMUP_BUDGET = 26_800;        // EMCTS compute-units for the capture (1000ms-equiv;
                                                     // turns 0-2 are degenerate so the budget is immaterial)
    static final long WARMUP_SEED   = 100L;
    static final int  GAMES         = 500;

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        long t0 = System.currentTimeMillis();

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v3  (threads=" + threads + ", output=" + BASE + ")");
        System.out.println("# Phase 3: " + GAMES + " games/level from a FIXED turn-" + START_TURN + " start");
        System.out.println("# Phase 2: baselines EMCTS+OEP (no MCTS); 1000ms ALL ratios @300; 500/5000ms @" + GAMES);
        System.out.println("# Resumable: re-run to continue after an interruption.");
        System.out.println("############################################################");

        // ---- Capture the fixed start position once (EMCTS warmup through the degenerate opening) ----
        banner("CAPTURE fixed start @ turn " + START_TURN, t0);
        GameState start = captureStartState();

        // ---- Phase 3: 500 games/level, fresh, all beginning from the captured turn-3 board ----
        banner("PHASE 3 v3 - " + GAMES + " games/level from turn " + START_TURN, t0);
        Budget[] levels = {
                new Budget("500ms",  13_400,  GAMES),
                new Budget("1000ms", 26_800,  GAMES),
                new Budget("5000ms", 134_000, GAMES),
        };
        Phase3Runner.run(GAMES, levels, Phase3Runner.DEFAULT_RATIOS, threads, BASE + "/phase3", null, start);
        Map<String, Integer> bestByLevel = Phase3Runner.bestRatiosByLevel(BASE + "/phase3");
        System.out.println(">> Best crossover ratio per level (" + GAMES + " games): " + bestByLevel);

        // ---- Phase 2: EMCTS+OEP only; all ratios @1000ms (300 games); {5,best} @500/5000ms (500 games) ----
        banner("PHASE 2 v3 - win rates (no MCTS; ALL ratios @1000ms)", t0);
        String[] baselines = {"OEP", "EMCTS"};
        Map<Integer, int[]> ratiosOverride = new HashMap<>();
        ratiosOverride.put(1000, Phase3Runner.DEFAULT_RATIOS);   // all ratios {2,5,10,15,20} at 1000ms
        Map<Integer, Integer> gamesOverride = new HashMap<>();
        gamesOverride.put(1000, 300);                            // 300 games for the many 1000ms matchups
        Phase2Runner.run(GAMES, 50, new int[]{500, 1000, 5000}, bestByLevel, threads, BASE + "/phase2",
                baselines, ratiosOverride, gamesOverride);

        System.out.println("\n############################################################");
        System.out.println("# v3 COMPLETE in " + elapsed(t0) + ".  Output in " + new File(BASE).getAbsolutePath());
        System.out.println("# Analyse with: python analysis/analyze.py " + BASE);
        System.out.println("############################################################");
    }

    /**
     * Runs ONE EMCTS game (vs a passive opponent, fixed seeds, SCORE mode, 11x11 water-free map) and
     * captures the full board at the START of turn {@link #START_TURN}. Because turns 0..START_TURN-1 are
     * degenerate (every agent plays them identically), this single captured position is the universal,
     * non-trivial starting point reused by every Phase-3 game.
     */
    static GameState captureStartState() {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};
        ArrayList<Agent> players = new ArrayList<>();
        players.add(ExpAgents.emcts(WARMUP_BUDGET).create(WARMUP_SEED));
        players.add(ExpAgents.passiveOpponent().create(WARMUP_SEED));
        Game game = new Game();
        game.init(players, FIXED_LEVEL_SEED, tribes, FIXED_GAME_SEED, Types.GAME_MODE.SCORE);
        GameState s = game.captureAtTick(START_TURN);
        System.out.println("[v3] Captured fixed start: tick=" + s.getTick() + " (degenerate turns 0.." + (START_TURN - 1) + " skipped)");
        return s;
    }

    private static void banner(String phase, long t0) {
        System.out.println("\n============================================================");
        System.out.println("== " + phase + "   [elapsed " + elapsed(t0) + "]");
        System.out.println("============================================================");
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format("%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
