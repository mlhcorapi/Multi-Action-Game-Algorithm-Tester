package core.data.exp;

import core.Types;
import core.actors.City;
import core.actors.Tribe;
import core.game.Board;
import core.game.Game;
import players.Agent;
import players.DoNothingAgent;
import utils.Vector2d;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fast correctness gate for the v6 FULL-GAMES tournament — the analogue of
 * {@link NewExperimentSmokeTestV6} for the tournament phase. Runs in well under a minute at a tiny
 * budget, and checks the properties the whole design rests on.
 *
 * <p>Every check here corresponds to a claim the write-up will make. In particular §2 is the claim
 * that the orientation swap is a real exchange of starting positions rather than a relabelling, and
 * §1c is the check that caught a live bug: with consecutive level seeds, {@code java.util.Random}'s
 * cross-seed correlation put one capital on the identical tile on all 20 maps.
 *
 * <p>Usage: {@code java -Xmx4g -cp "out:lib/*" core.data.exp.NewExperimentSmokeTestV6Full}
 */
public final class NewExperimentSmokeTestV6Full {

    /** Budget for the games this test plays. Tiny — it is checking plumbing, not playing strength. */
    private static final int SMOKE_UNITS = 2_000;
    /** Tick cap for the games this test plays. */
    private static final int SMOKE_TICKS = 4;

    private static int checks = 0, failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("############################################################");
        System.out.println("# v6 FULL GAMES — smoke test");
        System.out.println("############################################################");

        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();
        section1_mapSet(maps);
        section2_orientationSwap(maps);
        section3_schedule();
        section4_mirrorPairs(maps);
        section5_oneGame(maps);
        section6_threadInvariance(maps);
        section7_resume();
        section8_engineConstants(maps);

        System.out.println();
        System.out.println("############################################################");
        if (failures == 0) {
            System.out.println("# ALL " + checks + " CHECKS PASSED");
            System.out.println("############################################################");
        } else {
            System.out.println("# " + failures + " of " + checks + " CHECKS FAILED");
            System.out.println("############################################################");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- §1 map set

    private static void section1_mapSet(List<FullGameMapSetV6.MapInfo> maps) {
        System.out.println("\n--- §1 map set ---");
        check("20 maps built", maps.size() == FullGameMapSetV6.NUM_MAPS,
                "got " + maps.size());

        Set<Long> seeds = new HashSet<>();
        for (FullGameMapSetV6.MapInfo m : maps) seeds.add(m.levelSeed);
        check("§1a level seeds are distinct", seeds.size() == maps.size(),
                seeds.size() + " distinct of " + maps.size());

        boolean twoCapitals = true, sameBoardSize = true;
        int size0 = maps.get(0).boardSize;
        for (FullGameMapSetV6.MapInfo m : maps) {
            if (m.capitalA.equals(m.capitalB)) twoCapitals = false;
            if (m.boardSize != size0) sameBoardSize = false;
        }
        check("§1b every map has two distinct capitals", twoCapitals, "");
        check("§1b all maps are the same size (" + size0 + ")", sameBoardSize, "");

        // The bug this exists to catch: consecutive level seeds gave every map the SAME capital tile
        // and the SAME capital distance, because java.util.Random is correlated across nearby seeds.
        // FullGameMapSetV6.scramble() is the fix; this asserts the fix is in force.
        Set<String> capA = new HashSet<>(), capB = new HashSet<>();
        Set<Integer> distances = new HashSet<>();
        for (FullGameMapSetV6.MapInfo m : maps) {
            capA.add(m.capitalA.x + "," + m.capitalA.y);
            capB.add(m.capitalB.x + "," + m.capitalB.y);
            distances.add(m.capitalDistance);
        }
        check("§1c capital positions vary across maps (seed scrambling works)",
                capA.size() >= 5 && capB.size() >= 5,
                "distinct capA=" + capA.size() + " capB=" + capB.size()
                + " — if these are 1, the level seeds are correlated");
        check("§1c capital distance varies across maps", distances.size() >= 2,
                "distinct distances=" + distances);

        // The seat an orientation lands on is a property of the map (row-major scan order of the
        // capitals). It does not need to be balanced across maps - the orientation swap balances it
        // WITHIN each map - but if it were constant the swap would be doing less than it appears to.
        Set<Integer> seatsA = new HashSet<>();
        for (FullGameMapSetV6.MapInfo m : maps) seatsA.add(m.seatA);
        System.out.println("      (orientation-0 seat takes " + seatsA.size()
                + " distinct value(s) across the map set; balanced within each map by the swap)");
    }

    // ---------------------------------------------------------------- §2 orientation swap

    /**
     * The core claim: reversing the player list, with the tribes array held fixed, yields the SAME
     * board with the two agents' capitals and seats exchanged. Checked on every map, not a sample.
     */
    private static void section2_orientationSwap(List<FullGameMapSetV6.MapInfo> maps) {
        System.out.println("\n--- §2 orientation swap is an exact exchange ---");
        int sameBoard = 0, exchanged = 0, seatSwapped = 0;

        for (FullGameMapSetV6.MapInfo map : maps) {
            Agent a0 = new DoNothingAgent(1), b0 = new DoNothingAgent(2);
            Game g0 = new Game();
            g0.init(new ArrayList<>(Arrays.asList(a0, b0)), map.levelSeed,
                    FullGameMapSetV6.TRIBES, 42L, Types.GAME_MODE.CAPITALS);

            Agent a1 = new DoNothingAgent(1), b1 = new DoNothingAgent(2);
            Game g1 = new Game();
            g1.init(new ArrayList<>(Arrays.asList(b1, a1)), map.levelSeed,
                    FullGameMapSetV6.TRIBES, 42L, Types.GAME_MODE.CAPITALS);

            if (fingerprint(g0.getBoard()).equals(fingerprint(g1.getBoard()))) sameBoard++;

            int seatA0 = seatOf(g0, a0), seatB0 = seatOf(g0, b0);
            int seatA1 = seatOf(g1, a1), seatB1 = seatOf(g1, b1);
            Vector2d capA0 = capitalOf(g0, seatA0), capB0 = capitalOf(g0, seatB0);
            Vector2d capA1 = capitalOf(g1, seatA1), capB1 = capitalOf(g1, seatB1);

            // Agent A held B's old capital and vice versa.
            if (capA1.equals(capB0) && capB1.equals(capA0)) exchanged++;
            // Seat, and therefore turn order, swapped with the capital.
            if (seatA1 == seatB0 && seatB1 == seatA0 && seatA0 != seatB0) seatSwapped++;
        }

        int n = maps.size();
        check("§2a the board is identical in both orientations", sameBoard == n,
                sameBoard + "/" + n);
        check("§2b the two agents exchange capitals", exchanged == n, exchanged + "/" + n);
        check("§2c the two agents exchange seats (so turn order swaps too)", seatSwapped == n,
                seatSwapped + "/" + n);
    }

    // ---------------------------------------------------------------- §3 schedule

    /** Over a matchup's 200 game ids, every map and every (map, orientation) must appear equally. */
    private static void section3_schedule() {
        System.out.println("\n--- §3 the 200-game schedule is balanced ---");
        Map<Integer, Integer> perMap = new HashMap<>();
        Map<String, Integer> perMapOrientation = new HashMap<>();
        Set<String> triples = new HashSet<>();

        for (int g = 0; g < FullGameRunnerV6.GAMES_PER_MATCHUP; g++) {
            int m = FullGameRunnerV6.mapIndexOf(g), o = FullGameRunnerV6.orientationOf(g);
            int p = FullGameRunnerV6.pairIndexOf(g);
            perMap.merge(m, 1, Integer::sum);
            perMapOrientation.merge(m + "/" + o, 1, Integer::sum);
            triples.add(m + "/" + o + "/" + p);
        }

        boolean mapsEqual = perMap.size() == FullGameMapSetV6.NUM_MAPS;
        for (int c : perMap.values()) if (c != FullGameRunnerV6.GAMES_PER_MAP) mapsEqual = false;
        check("§3a each of the " + FullGameMapSetV6.NUM_MAPS + " maps is played "
                + FullGameRunnerV6.GAMES_PER_MAP + " times", mapsEqual, perMap.toString());

        boolean orientationsEqual = perMapOrientation.size() == 2 * FullGameMapSetV6.NUM_MAPS;
        for (int c : perMapOrientation.values())
            if (c != FullGameRunnerV6.GAMES_PER_ORIENTATION) orientationsEqual = false;
        check("§3b each map is played " + FullGameRunnerV6.GAMES_PER_ORIENTATION
                + " times in EACH orientation", orientationsEqual, "");

        check("§3c (map, orientation, pair) is unique per game",
                triples.size() == FullGameRunnerV6.GAMES_PER_MATCHUP,
                triples.size() + " of " + FullGameRunnerV6.GAMES_PER_MATCHUP);
    }

    // ---------------------------------------------------------------- §4 mirror pairs

    /** The two games of a mirror pair must agree on every seed; only the seating may differ. */
    private static void section4_mirrorPairs(List<FullGameMapSetV6.MapInfo> maps) {
        System.out.println("\n--- §4 mirror pairs share every seed ---");
        int matched = 0, pairs = 0;
        for (int m = 0; m < FullGameMapSetV6.NUM_MAPS; m++) {
            for (int p = 0; p < FullGameRunnerV6.GAMES_PER_ORIENTATION; p++) {
                int g0 = m * FullGameRunnerV6.GAMES_PER_MAP + p;
                int g1 = g0 + FullGameRunnerV6.GAMES_PER_ORIENTATION;
                pairs++;
                boolean ok = FullGameRunnerV6.orientationOf(g0) == 0
                        && FullGameRunnerV6.orientationOf(g1) == 1
                        && FullGameRunnerV6.mapIndexOf(g0) == FullGameRunnerV6.mapIndexOf(g1)
                        && FullGameRunnerV6.pairIndexOf(g0) == FullGameRunnerV6.pairIndexOf(g1)
                        && FullGameRunnerV6.gameSeed(g0, 0) == FullGameRunnerV6.gameSeed(g1, 0)
                        && FullGameRunnerV6.agentSeedForSeat(g0, 0, 0) == FullGameRunnerV6.agentSeedForSeat(g1, 0, 0)
                        && FullGameRunnerV6.agentSeedForSeat(g0, 1, 0) == FullGameRunnerV6.agentSeedForSeat(g1, 1, 0);
                if (ok) matched++;
            }
        }
        check("§4a every (map, pair) forms a mirror pair with identical seeds", matched == pairs,
                matched + "/" + pairs);

        // The two seats must NOT share an RNG stream, or the mirror would be degenerate.
        boolean distinct = true;
        for (int g = 0; g < 20; g++)
            if (FullGameRunnerV6.agentSeedForSeat(g, 0, 0) == FullGameRunnerV6.agentSeedForSeat(g, 1, 0))
                distinct = false;
        check("§4b the two seats get different agent seeds", distinct, "");

        // Scrambling must actually separate neighbouring keys.
        Set<Long> seeds = new HashSet<>();
        for (int g = 0; g < FullGameRunnerV6.GAMES_PER_MATCHUP; g++)
            seeds.add(FullGameRunnerV6.gameSeed(g, 0));
        check("§4c game seeds are well separated across the grid",
                seeds.size() == FullGameMapSetV6.NUM_MAPS * FullGameRunnerV6.GAMES_PER_ORIENTATION,
                seeds.size() + " distinct (expected one per (map,pair))");
    }

    // ---------------------------------------------------------------- §5 one real game

    private static void section5_oneGame(List<FullGameMapSetV6.MapInfo> maps) {
        System.out.println("\n--- §5 a real game produces a well-formed row ---");
        FullGameRunnerV6.applyEngineConstants(SMOKE_TICKS);
        FullGameRunnerV6.Matchup m =
                new FullGameRunnerV6.Matchup("CrossoverCombo", 5, SMOKE_UNITS);

        String row = FullGameRunnerV6.playOne(m, maps.get(0), 0, 0);
        String[] f = row.split(",", -1);
        int expected = FullGameRunnerV6.GAMES_HEADER.split(",").length;
        check("§5a the row has " + expected + " fields", f.length == expected,
                "got " + f.length + ": " + row);
        if (f.length != expected) return;

        System.out.println("      " + FullGameRunnerV6.GAMES_HEADER);
        System.out.println("      " + row);

        String winner = f[10];
        check("§5b Winner is a role, EMCTS or OPPONENT",
                winner.equals("EMCTS") || winner.equals("OPPONENT"), winner);
        check("§5c EmctsWon agrees with Winner",
                f[11].equals(winner.equals("EMCTS") ? "1" : "0"), f[11] + " vs " + winner);
        check("§5d the two agents are in different seats", !f[8].equals(f[9]), f[8] + "/" + f[9]);
        check("§5e DecidedBy is CAPITAL or SCORE",
                f[15].equals("CAPITAL") || f[15].equals("SCORE"), f[15]);
        check("§5f EndTurn respects the tick cap",
                Integer.parseInt(f[14]) <= SMOKE_TICKS + 1,
                f[14] + " > cap " + SMOKE_TICKS);

        // Orientation must actually place EMCTS on the other side.
        String rowO1 = FullGameRunnerV6.playOne(m, maps.get(0), FullGameRunnerV6.GAMES_PER_ORIENTATION, 0);
        String[] f1 = rowO1.split(",", -1);
        check("§5g orientation 1 puts EMCTS in the other seat",
                !f[8].equals(f1[8]), "seat " + f[8] + " in both orientations");

        // The control arm is the one place a name-based winner silently breaks: both sides are called
        // "EMCTS", so a name would make EmctsWon identically 1 in the very matchup whose purpose is to
        // come out at 50%. Play enough control games that a stuck column cannot pass by chance.
        FullGameRunnerV6.Matchup control = new FullGameRunnerV6.Matchup("EMCTS", -1, SMOKE_UNITS);
        int wins = 0, n = 12;
        for (int g = 0; g < n; g++) {
            String[] cf = FullGameRunnerV6.playOne(
                    control, maps.get(FullGameRunnerV6.mapIndexOf(g)), g, 0).split(",", -1);
            if (cf[11].equals("1")) wins++;
        }
        check("§5h the EMCTS-vs-EMCTS control can report losses", wins > 0 && wins < n,
                "EMCTS won " + wins + "/" + n + " — 0 or " + n
                + " means the winner is being read from the agent NAME, which is identical on both sides");
    }

    // ---------------------------------------------------------------- §6 thread invariance

    /**
     * A game's outcome must not depend on how many threads were running beside it. This is the
     * tournament-phase form of the isolation phase's rerun-identity check, and it is what makes the
     * thread calibration free to optimise: the budget is a deterministic compute-unit count, so
     * concurrency changes wall-clock and nothing else.
     */
    private static void section6_threadInvariance(List<FullGameMapSetV6.MapInfo> maps) throws Exception {
        System.out.println("\n--- §6 results do not depend on the thread count ---");
        FullGameRunnerV6.applyEngineConstants(SMOKE_TICKS);
        FullGameRunnerV6.Matchup m =
                new FullGameRunnerV6.Matchup("CrossoverExpansion", 5, SMOKE_UNITS);
        int[] ids = {0, 5, 13, 27};

        String[] serial = new String[ids.length];
        for (int i = 0; i < ids.length; i++)
            serial[i] = FullGameRunnerV6.playOne(m, maps.get(FullGameRunnerV6.mapIndexOf(ids[i])), ids[i], 0);

        ExecutorService pool = Executors.newFixedThreadPool(ids.length);
        List<Future<String>> futures = new ArrayList<>();
        for (int id : ids) {
            final int gameId = id;
            futures.add(pool.submit(() ->
                    FullGameRunnerV6.playOne(m, maps.get(FullGameRunnerV6.mapIndexOf(gameId)), gameId, 0)));
        }
        int same = 0;
        for (int i = 0; i < ids.length; i++) if (serial[i].equals(futures.get(i).get())) same++;
        pool.shutdown();

        check("§6a the same games replay identically across a pool", same == ids.length,
                same + "/" + ids.length + " identical");

        // And a repeat of the same call must be identical too - no hidden state between games.
        String again = FullGameRunnerV6.playOne(m, maps.get(0), 0, 0);
        check("§6b replaying one game gives a byte-identical row", again.equals(serial[0]),
                "\n        first: " + serial[0] + "\n        again: " + again);
    }

    // ---------------------------------------------------------------- §7 resume

    /** A torn or doubled {@code games.csv} must be repaired into exactly the set of finished games. */
    private static void section7_resume() throws Exception {
        System.out.println("\n--- §7 resume repairs a damaged games.csv ---");
        File dir = new File(System.getProperty("java.io.tmpdir"), "v6full_smoke_" + System.nanoTime());
        ExpCsv.ensureDir(dir);
        File csv = new File(dir, "games.csv");
        try {
            String good0 = "40000cu,CrossoverBB,NA,0,0,123,0,0,1,0,EMCTS,1,100,90,12,CAPITAL,1";
            String good1 = "40000cu,CrossoverBB,NA,1,0,123,0,1,0,1,CrossoverBB,0,80,110,20,SCORE,1";
            ExpCsv.append(csv, FullGameRunnerV6.GAMES_HEADER, Arrays.asList(
                    good0,
                    good1,
                    good0,                                   // a doubled run's duplicate
                    "40000cu,CrossoverBB,NA,2,0,123,0,2"));  // a torn final write

            Set<Integer> done = FullGameRunnerV6.compactPartialMatchup(csv);
            check("§7a only the complete, unique games are counted",
                    done.size() == 2 && done.contains(0) && done.contains(1), done.toString());

            List<String> lines = java.nio.file.Files.readAllLines(csv.toPath());
            check("§7b the file is rewritten with the header plus 2 rows", lines.size() == 3,
                    lines.size() + " lines");
            check("§7c the torn row is gone",
                    lines.size() == 3 && !lines.get(2).endsWith(",0,2"), "");

            // Repairing an already-clean file must be a no-op.
            Set<Integer> again = FullGameRunnerV6.compactPartialMatchup(csv);
            check("§7d repairing a clean file changes nothing", again.equals(done), "");
        } finally {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) { if (!f.delete()) f.deleteOnExit(); }
            if (!dir.delete()) dir.deleteOnExit();
        }
    }

    // ---------------------------------------------------------------- §8 engine constants

    /**
     * The engine's rules live in JVM-wide statics, and more than one job in this package writes them.
     * {@link ThreadCalibrationV6Full} lowers {@code MAX_TURNS_CAPITALS} to its own 2–12 tick cap while
     * sweeping, and it runs immediately before the tournament for any level that is not yet
     * calibrated. If {@code runLevel} did not restore the constants itself, the real games would be
     * played to the calibration's cap and nothing in the output would say so — the games would simply
     * all be short.
     *
     * <p>So: stamp the constants to a wrong value, enter the runner's setup path, and require it to
     * have put them back.
     */
    private static void section8_engineConstants(List<FullGameMapSetV6.MapInfo> maps) throws Exception {
        System.out.println("\n--- §8 the runner restores the engine constants it depends on ---");

        core.Constants.MAX_TURNS_CAPITALS = 3;        // as a thread calibration would leave it
        core.Constants.REMOVE_FISHING = false;
        core.Constants.VERBOSE = true;

        // A level whose matchups are all checkpointed does no work but still runs the setup path.
        Set<String> allDone = new HashSet<>();
        for (FullGameRunnerV6.Matchup m : FullGameRunnerV6.matchups(SMOKE_UNITS)) allDone.add(m.key());
        File tmp = new File(System.getProperty("java.io.tmpdir"), "v6full_const_" + System.nanoTime());
        ExpCsv.ensureDir(tmp);
        try {
            FullGameRunnerV6.runLevel(SMOKE_UNITS, maps, 1, tmp, allDone);
        } finally {
            File[] fs = tmp.listFiles();
            if (fs != null) for (File f : fs) { if (!f.delete()) f.deleteOnExit(); }
            if (!tmp.delete()) tmp.deleteOnExit();
        }

        check("§8a MAX_TURNS_CAPITALS is restored to TURN_LIMIT",
                core.Constants.MAX_TURNS_CAPITALS == RunV6FullGames.TURN_LIMIT,
                "got " + core.Constants.MAX_TURNS_CAPITALS
                + ", expected " + RunV6FullGames.TURN_LIMIT
                + " — a preceding thread calibration would leak its tick cap into the real games");
        check("§8b REMOVE_FISHING is set (maps are water-free)",
                core.Constants.REMOVE_FISHING, "");
        check("§8c VERBOSE is off (7,200 games would otherwise flood the log)",
                !core.Constants.VERBOSE, "");
    }

    // ---------------------------------------------------------------- helpers

    private static String fingerprint(Board b) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < b.getSize(); x++)
            for (int y = 0; y < b.getSize(); y++)
                sb.append(b.getTerrainAt(x, y)).append('/').append(b.getResourceAt(x, y)).append(',');
        return sb.toString();
    }

    private static int seatOf(Game g, Agent a) {
        Agent[] seated = g.getPlayers();
        for (int i = 0; i < seated.length; i++) if (seated[i] == a) return i;
        throw new IllegalStateException("agent not seated");
    }

    private static Vector2d capitalOf(Game g, int seat) {
        Board b = g.getBoard();
        Tribe t = b.getTribe(seat);
        for (int cid : b.getCapitalIDs())
            if (t.getCitiesID().contains(cid)) return ((City) b.getActor(cid)).getPosition();
        throw new IllegalStateException("no capital for seat " + seat);
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  [ok]   " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what + (detail.isEmpty() ? "" : "  -> " + detail));
        }
    }
}
