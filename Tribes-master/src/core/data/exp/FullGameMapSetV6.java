package core.data.exp;

import core.Constants;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fixed map set for the v6 FULL-GAMES tournament: {@link #NUM_MAPS} generated levels, shared by
 * every matchup and every budget level.
 *
 * <p><b>Why a fixed shared set.</b> Two reasons, and they are different reasons.
 *
 * <ol>
 *   <li><i>Within a matchup</i>, a map is a large source of variance: a start with three fruit inside
 *       the city bound and a mountain chokepoint is not the same game as an open plain. Playing a
 *       known set of maps a known number of times each removes map sampling from the comparison.</li>
 *   <li><i>Between matchups</i>, using the SAME maps (and the same seed grid, see
 *       {@link FullGameRunnerV6}) makes the 36 matchups paired with one another. A difference between
 *       CrossoverExpansion@5 and CrossoverExpansion@20 is then a difference between the agents, not
 *       between the maps each happened to draw. This is the full-game analogue of v6's shared
 *       {@code visitSeed(scenario, visit)} across all 12 configs.</li>
 * </ol>
 *
 * <p><b>Map asymmetry is handled by swapping, not by filtering.</b> The generator places capitals by
 * maximin distance and then grows territory outward, so the two starting areas are never exactly
 * equal. This class MEASURES that asymmetry and writes it to the manifest, but it does not reject
 * maps on it, for two reasons: {@link FullGameRunnerV6} plays every map an equal number of times in
 * each orientation, so a map bias of any size cancels exactly within the map; and filtering would
 * quietly narrow the map distribution the result generalises over. The diagnostics exist so the
 * write-up can state what the maps looked like, not to gate them.
 *
 * <p><b>What a "map" is here.</b> A level seed. Board generation is a pure function of
 * (levelSeed, tribes array) — {@link core.game.GameState#init(long, Types.TRIBE[])} — and the tribes
 * array is fixed at {@code {IMPERIUS2, IMPERIUS}} for the whole experiment, so a seed reproduces its
 * board exactly on any machine and at any thread count. Nothing needs to be cached on disk.
 *
 * <p><b>Why those two tribes.</b> They are a mirror pair: same starting technology (ORGANIZATION),
 * same starting unit (WARRIOR), and identical per-terrain generation multipliers in
 * {@code terrainProbs.json} (all 1.0, i.e. BASE). Tribe identity therefore contributes nothing to the
 * result, which is what makes an agent-vs-agent win rate readable. {@code Phase2Runner} used the same
 * pairing.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.FullGameMapSetV6 [outDir]}
 */
public final class FullGameMapSetV6 {

    private FullGameMapSetV6() {}

    /** Maps in the set. Each is played 10 times per matchup (5 per orientation). */
    public static final int NUM_MAPS = 20;

    /**
     * Base of the level-seed sequence. Seed {@code i} is {@code scramble(BASE + i)}, filtered to those
     * that generate a valid 2-capital board; the accepted seeds are recorded in the manifest, so the
     * set is reproducible even if the generator's validity behaviour ever changes.
     */
    public static final long LEVEL_SEED_BASE = 6_000_000L;

    /**
     * Scatters a counter into a level seed, because {@code java.util.Random} is badly correlated
     * across CONSECUTIVE seeds and the level generator is driven entirely by one such {@code Random}.
     *
     * <p>This is not a theoretical concern; it was measured on this exact seed range. With seeds
     * {@code 6000000, 6000001, ...}:
     * <ul>
     *   <li>the first {@code nextDouble()} fell on the same side of 0.5 for <b>twelve consecutive
     *       seeds</b> — and the generator's terrain placement is a long sequence of
     *       {@code nextDouble() < probability} tests;</li>
     *   <li>the first {@code nextInt(121)} walked in near-arithmetic steps (38, 54, 7, 23, 101, …)
     *       rather than scattering.</li>
     * </ul>
     *
     * <p>The visible symptom was that all 20 maps put one capital on the identical tile and produced
     * an identical capital-to-capital distance: the maps varied in terrain but not in geometry, so
     * "20 maps" sampled far less of the map space than the count suggests. Passing the counter through
     * a SplitMix64 finalizer first — full avalanche, so adjacent inputs give unrelated outputs — makes
     * consecutive indices behave like independent draws.
     *
     * <p>Deterministic and self-contained, so the seed set is reproducible from the index alone.
     */
    public static long scramble(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z & Long.MAX_VALUE;   // keep it non-negative so seeds read sensibly in the manifest
    }

    /** Seeds tried before giving up. Invalid seeds are rare; this is a guard, not a budget. */
    private static final int MAX_SEED_ATTEMPTS = 20 * NUM_MAPS;

    /**
     * The tribes array, fixed for the whole experiment. Order matters and must never change: it binds
     * {@code capitalCells[i]} to {@code tribes[i]} in the generator, which is the mechanism the
     * orientation swap rides on ({@link FullGameRunnerV6}).
     */
    public static final Types.TRIBE[] TRIBES = {Types.TRIBE.IMPERIUS2, Types.TRIBE.IMPERIUS};

    /** One map: its seed and the diagnostics measured on the generated board. */
    public static final class MapInfo {
        public final int index;
        public final long levelSeed;
        public final int boardSize;
        /** Capital of {@code TRIBES[0]} — the position an agent occupies in orientation 0. */
        public final Vector2d capitalA;
        /** Capital of {@code TRIBES[1]} — the position an agent occupies in orientation 1. */
        public final Vector2d capitalB;
        /** Seat index (= turn order; 0 moves first) that {@code TRIBES[0]}'s capital was given. */
        public final int seatA, seatB;
        /** Chebyshev distance between the two capitals. */
        public final int capitalDistance;
        /** Counts within the starting city bound (radius 1) and the first expansion ring (radius 2). */
        public final StartArea areaA, areaB;

        MapInfo(int index, long levelSeed, int boardSize, Vector2d capitalA, Vector2d capitalB,
                int seatA, int seatB, StartArea areaA, StartArea areaB) {
            this.index = index;
            this.levelSeed = levelSeed;
            this.boardSize = boardSize;
            this.capitalA = capitalA;
            this.capitalB = capitalB;
            this.seatA = seatA;
            this.seatB = seatB;
            this.areaA = areaA;
            this.areaB = areaB;
            this.capitalDistance = Math.max(Math.abs(capitalA.x - capitalB.x),
                                            Math.abs(capitalA.y - capitalB.y));
        }

        /**
         * A single scalar for how unequal the two starts are: the summed absolute difference in
         * resource counts inside the city bound, where the resources are the ones that actually drive
         * an opening (fruit and crops feed population, animals feed it after HUNTING, ore after
         * MINING). Reported, never used to filter — see the class comment.
         */
        public int startAsymmetry() {
            return Math.abs(areaA.fruit - areaB.fruit) + Math.abs(areaA.crops - areaB.crops)
                 + Math.abs(areaA.animal - areaB.animal) + Math.abs(areaA.ore - areaB.ore);
        }
    }

    /** Terrain and resource counts in a square ring around a capital. */
    public static final class StartArea {
        public final int fruit, crops, animal, ore, ruins;
        public final int forest, mountain, village, water;
        StartArea(int fruit, int crops, int animal, int ore, int ruins,
                  int forest, int mountain, int village, int water) {
            this.fruit = fruit; this.crops = crops; this.animal = animal; this.ore = ore;
            this.ruins = ruins; this.forest = forest; this.mountain = mountain;
            this.village = village; this.water = water;
        }
    }

    /**
     * Builds the map set: generates boards from consecutive seeds and keeps the first
     * {@link #NUM_MAPS} that are valid. Deterministic and side-effect free — safe to call from the
     * runner, the verifier and the notebook alike, and every caller gets the identical set.
     */
    public static List<MapInfo> build() {
        // The generator is a pure function of (seed, tribes); the constants below only affect the
        // ENGINE, never generation, but are set anyway so a caller that goes on to run a game from
        // this class's process does not inherit GUI defaults.
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;

        List<MapInfo> maps = new ArrayList<>(NUM_MAPS);
        int attempts = 0;
        while (maps.size() < NUM_MAPS && attempts < MAX_SEED_ATTEMPTS) {
            MapInfo info = inspect(maps.size(), scramble(LEVEL_SEED_BASE + attempts));
            attempts++;
            if (info != null) maps.add(info);
        }
        if (maps.size() < NUM_MAPS)
            throw new IllegalStateException("Only " + maps.size() + "/" + NUM_MAPS
                    + " valid maps in " + attempts + " seeds from " + LEVEL_SEED_BASE);
        return maps;
    }

    /**
     * Generates one board and measures it, or returns null if the seed does not produce a usable
     * 2-capital level. Uses DoNothing agents: the board exists the moment {@code Game.init} returns,
     * and no turn is ever played.
     */
    private static MapInfo inspect(int index, long levelSeed) {
        try {
            ArrayList<Agent> players = new ArrayList<>(Arrays.asList(
                    new DoNothingAgent(0), new DoNothingAgent(1)));
            Game game = new Game();
            game.init(players, levelSeed, TRIBES, /*gameSeed*/ 0L, Types.GAME_MODE.CAPITALS);
            Board board = game.getBoard();

            int[] capitalIds = board.getCapitalIDs();
            if (capitalIds.length != TRIBES.length) return null;

            // Locate each tribe TYPE's seat and capital. Seat order is the board's row-major scan
            // order of the capital tiles, which is why it is recorded rather than assumed.
            Map<Types.TRIBE, Integer> seatOf = new LinkedHashMap<>();
            Map<Types.TRIBE, Vector2d> capitalOf = new LinkedHashMap<>();
            Tribe[] tribes = board.getTribes();
            for (int seat = 0; seat < tribes.length; seat++) {
                Tribe t = tribes[seat];
                for (int cid : capitalIds) {
                    if (t.getCitiesID().contains(cid)) {
                        capitalOf.put(t.getType(), ((City) board.getActor(cid)).getPosition());
                        seatOf.put(t.getType(), seat);
                        break;
                    }
                }
            }
            if (capitalOf.size() != TRIBES.length) return null;

            Vector2d capA = capitalOf.get(TRIBES[0]), capB = capitalOf.get(TRIBES[1]);
            if (capA.equals(capB)) return null;

            return new MapInfo(index, levelSeed, board.getSize(), capA, capB,
                    seatOf.get(TRIBES[0]), seatOf.get(TRIBES[1]),
                    measure(board, capA), measure(board, capB));
        } catch (Throwable t) {
            System.err.println("[maps] seed " + levelSeed + " rejected: " + t);
            return null;
        }
    }

    /** Counts terrain and resources in the 5x5 block centred on a capital (the bound + first ring). */
    private static StartArea measure(Board board, Vector2d capital) {
        int fruit = 0, crops = 0, animal = 0, ore = 0, ruins = 0;
        int forest = 0, mountain = 0, village = 0, water = 0;
        int n = board.getSize();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int x = capital.x + dx, y = capital.y + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) continue;
                Types.RESOURCE r = board.getResourceAt(x, y);
                if (r == Types.RESOURCE.FRUIT) fruit++;
                else if (r == Types.RESOURCE.CROPS) crops++;
                else if (r == Types.RESOURCE.ANIMAL) animal++;
                else if (r == Types.RESOURCE.ORE) ore++;
                else if (r == Types.RESOURCE.RUINS) ruins++;
                Types.TERRAIN t = board.getTerrainAt(x, y);
                if (t == Types.TERRAIN.FOREST) forest++;
                else if (t == Types.TERRAIN.MOUNTAIN) mountain++;
                else if (t == Types.TERRAIN.VILLAGE) village++;
                else if (t == Types.TERRAIN.SHALLOW_WATER || t == Types.TERRAIN.DEEP_WATER) water++;
            }
        }
        return new StartArea(fruit, crops, animal, ore, ruins, forest, mountain, village, water);
    }

    public static final String MANIFEST_HEADER =
            "MapIndex,LevelSeed,BoardSize,SeatA,CapAX,CapAY,SeatB,CapBX,CapBY,CapitalDistance,"
          + "A_Fruit,A_Crops,A_Animal,A_Ore,A_Ruins,A_Forest,A_Mountain,A_Village,A_Water,"
          + "B_Fruit,B_Crops,B_Animal,B_Ore,B_Ruins,B_Forest,B_Mountain,B_Village,B_Water,"
          + "StartAsymmetry";

    private static String row(MapInfo m) {
        return String.format(Locale.ROOT,
                "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,"
              + "%d,%d,%d,%d,%d,%d,%d,%d,%d,"
              + "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                m.index, m.levelSeed, m.boardSize,
                m.seatA, m.capitalA.x, m.capitalA.y, m.seatB, m.capitalB.x, m.capitalB.y,
                m.capitalDistance,
                m.areaA.fruit, m.areaA.crops, m.areaA.animal, m.areaA.ore, m.areaA.ruins,
                m.areaA.forest, m.areaA.mountain, m.areaA.village, m.areaA.water,
                m.areaB.fruit, m.areaB.crops, m.areaB.animal, m.areaB.ore, m.areaB.ruins,
                m.areaB.forest, m.areaB.mountain, m.areaB.village, m.areaB.water,
                m.startAsymmetry());
    }

    /** Writes {@code maps.csv} into {@code dir}, overwriting any previous copy. */
    public static void writeManifest(File dir, List<MapInfo> maps) {
        ExpCsv.ensureDir(dir);
        StringBuilder sb = new StringBuilder(MANIFEST_HEADER).append('\n');
        for (MapInfo m : maps) sb.append(row(m)).append('\n');
        ExpCsv.writeText(new File(dir, "maps.csv"), sb.toString());
    }

    /** Human-readable summary for the run banner and the notebook. */
    public static String summary(List<MapInfo> maps) {
        int minD = Integer.MAX_VALUE, maxD = 0, sumD = 0, maxAsym = 0, sumAsym = 0;
        for (MapInfo m : maps) {
            minD = Math.min(minD, m.capitalDistance);
            maxD = Math.max(maxD, m.capitalDistance);
            sumD += m.capitalDistance;
            maxAsym = Math.max(maxAsym, m.startAsymmetry());
            sumAsym += m.startAsymmetry();
        }
        return String.format(Locale.ROOT,
                "%d maps (seeds = scramble(%d + i), listed in maps.csv), board %dx%d, "
              + "capital distance min/mean/max = %d/%.1f/%d, start asymmetry mean/max = %.1f/%d",
                maps.size(), LEVEL_SEED_BASE,
                maps.get(0).boardSize, maps.get(0).boardSize,
                minD, sumD / (double) maps.size(), maxD,
                sumAsym / (double) maps.size(), maxAsym);
    }

    public static void main(String[] args) {
        String outDir = args.length > 0 ? args[0] : RunV6FullGames.BASE + "/_maps";
        List<MapInfo> maps = build();
        writeManifest(new File(outDir), maps);
        System.out.println("[maps] " + summary(maps));
        System.out.println("[maps] tribes = " + TRIBES[0] + " (orientation 0) vs " + TRIBES[1]
                + " (orientation 1) — mirror pair, identical start tech/unit/terrain weights");
        System.out.println("[maps] manifest written to " + new File(outDir, "maps.csv").getPath());
        System.out.println();
        System.out.printf(Locale.ROOT, "%5s %10s  %-9s %-9s %5s  %5s%n",
                "map", "seed", "capA(seat)", "capB(seat)", "dist", "asym");
        for (MapInfo m : maps) {
            System.out.printf(Locale.ROOT, "%5d %10d  (%d,%d)@%d    (%d,%d)@%d    %5d  %5d%n",
                    m.index, m.levelSeed, m.capitalA.x, m.capitalA.y, m.seatA,
                    m.capitalB.x, m.capitalB.y, m.seatB, m.capitalDistance, m.startAsymmetry());
        }
    }
}
