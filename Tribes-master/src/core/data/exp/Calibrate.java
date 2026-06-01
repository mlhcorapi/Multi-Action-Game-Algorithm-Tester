package core.data.exp;

import core.Constants;
import core.Types;
import core.game.Game;
import players.Agent;
import players.RandomAgent;
import players.emcts.EMCTSAgent;
import players.emcts.EMCTSParams;

import java.util.ArrayList;

/**
 * Calibrates the fmcall<->wall-clock mapping for the EMCTS baseline on this machine, so the
 * phase budgets can be set to roughly match the intended thinking times. Run single-threaded;
 * under the experiments' ~10 threads each game gets ~1 core, so single-core timing is
 * representative. Prints suggested fmcall budgets for the standard target times.
 */
public class Calibrate {

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.MAX_TURNS_CAPITALS = 12; // a handful of turns is enough to time

        int calibFmcalls = 20000;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        EMCTSParams p = new EMCTSParams();
        p.stop_type = p.STOP_FMCALLS;
        p.num_fmcalls = calibFmcalls;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = ExpAgents.DEPTH;

        ArrayList<Agent> players = new ArrayList<>();
        EMCTSAgent emcts = new EMCTSAgent(42, p);
        players.add(emcts);
        players.add(new RandomAgent(43));

        Game game = new Game();
        game.init(players, 555L, tribes, 666L, Types.GAME_MODE.CAPITALS);

        long t0 = System.currentTimeMillis();
        game.run(null, null);
        long elapsed = System.currentTimeMillis() - t0;

        int turns = Math.max(1, emcts.getGameDurationTurns());
        // EMCTS does ~calibFmcalls per turn; RandomAgent is ~instant, so elapsed ~ EMCTS think time.
        double msPerTurn = (double) elapsed / turns;
        double fmcallsPerMs = calibFmcalls / msPerTurn;

        System.out.println("=== Calibration (EMCTS, single-threaded) ===");
        System.out.printf("budget=%d fmcalls/turn, turns=%d, elapsed=%dms -> %.1f ms/turn, %.1f fmcalls/ms%n",
                calibFmcalls, turns, elapsed, msPerTurn, fmcallsPerMs);
        int[] targets = {200, 500, 1000, 2000, 5000};
        System.out.println("Suggested fmcall budgets (set these in the Phase*Runner.defaultLevels()):");
        for (int t : targets) {
            System.out.printf("  ~%5dms  ->  %d fmcalls%n", t, Math.max(1, (int) Math.round(t * fmcallsPerMs)));
        }
        System.out.println("NOTE: crossover agents do more work per fmcall, so for equal fmcalls they take");
        System.out.println("somewhat longer wall-clock - that overhead is itself a measured efficiency signal.");
    }
}
