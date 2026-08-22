package core.data.exp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pilot for the v6 full-games tournament: measures how long a game actually lasts at each budget
 * level, so the turn cap is set from data rather than inherited.
 *
 * <h2>Why this has to be measured</h2>
 *
 * <p>A CAPITALS game ends one of two ways: a tribe captures every capital (a conquest), or the tick
 * count passes {@code Constants.MAX_TURNS_CAPITALS} and {@code GameState.computeGameRanking} awards
 * the win to whoever is ahead on score. Those are different questions. A win rate built mostly of
 * conquests measures playing strength; one built mostly of score-at-the-cap measures who accumulated
 * more points in a fixed window — which in Tribes is dominated by technology count, not territory.
 *
 * <p>The old Phase 2 tournament capped at 50 and only <b>1.3%</b> of its 900 games reached the cap
 * (mean length 22.4 ticks), so there the cap was effectively inert. But those agents planned on a
 * 500–1000 ms clock. The v6 agents get 40,000–400,000 compute units per turn and are much stronger,
 * and stronger play can push length either way: better attack shortens a game, better defence
 * lengthens it. Assuming 50 still holds is the kind of assumption this probe exists to remove.
 *
 * <h2>What it does</h2>
 *
 * <p>Plays real games — same maps, same orientation swap, same seed grid, same agent factories as
 * {@link FullGameRunnerV6} — under a deliberately loose safety cap, and reports the distribution of
 * end ticks per budget level. Games that reach the safety cap are <b>censored, not discarded</b>:
 * their share is exactly the statistic that matters, and it is reported as such.
 *
 * <p>Three opponents are sampled per level rather than one, because game length is a property of the
 * MATCHUP, not of a single agent: EMCTS vs EMCTS (the control, where neither side has an edge),
 * CrossoverExpansion@5 and CrossoverCombo@5.
 *
 * <h2>Reading the output</h2>
 *
 * <p>The recommendation is the smallest cap from a small candidate ladder at which at most
 * {@link #TARGET_BIND_RATE} of games would have been decided by the cap. It is printed, not applied:
 * {@link RunV6FullGames#TURN_LIMIT} is a compile-time constant so that the tournament's rules are
 * fixed and visible in the source rather than dependent on whatever a probe last wrote to disk.
 *
 * <p>Usage: {@code java -Xmx<heap> -cp "out:lib/*" core.data.exp.GameLengthProbeV6
 * [threads|auto] [safetyCap] [gamesPerLevelCsv] [cuCsv]}
 */
public final class GameLengthProbeV6 {

    private GameLengthProbeV6() {}

    /** Loose cap the probe runs under. High enough to be non-binding for almost every game. */
    public static final int DEFAULT_SAFETY_CAP = 150;

    /** Games per budget level, smallest level first. Fewer at 400k, where a game costs ~27x a 40k one. */
    public static final int[] DEFAULT_GAMES_PER_LEVEL = {60, 60, 24};

    /** Cap candidates considered when recommending a turn limit. */
    public static final int[] CAP_LADDER = {40, 50, 60, 75, 100, 125, 150};

    /** The recommendation is the smallest cap at which no more than this share of games would bind. */
    public static final double TARGET_BIND_RATE = 0.02;

    private static final String HEADER =
            "Level,ComputeUnits,Opponent,Ratio,GameIndex,MapIndex,Orientation,EndTurn,DecidedBy,Censored";

    /** The matchups sampled at each level. Ratio -1 means the config takes no ratio. */
    private static List<FullGameRunnerV6.Matchup> probeMatchups(int cu) {
        return Arrays.asList(
                new FullGameRunnerV6.Matchup("EMCTS", -1, cu),
                new FullGameRunnerV6.Matchup("CrossoverExpansion", 5, cu),
                new FullGameRunnerV6.Matchup("CrossoverCombo", 5, cu));
    }

    public static void main(String[] args) throws Exception {
        RunGuard.install("v6 FULL-GAMES length probe", "re-run the same command (it overwrites)");

        String threadsArg = args.length > 0 ? args[0] : "auto";
        int safetyCap = args.length > 1 ? Integer.parseInt(args[1].trim()) : DEFAULT_SAFETY_CAP;
        int[] gamesPerLevel = args.length > 2 ? parseInts(args[2]) : DEFAULT_GAMES_PER_LEVEL;
        int[] cuLevels = args.length > 3 ? parseInts(args[3]) : RunV6FullGames.COMPUTE_LEVELS;

        ServerProfile profile = ServerProfile.get();
        int threads = threadsArg.equalsIgnoreCase("auto")
                ? profile.maxWorkers() : Integer.parseInt(threadsArg.trim());
        profile.printBanner("v6 full-games length probe");

        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();
        FullGameRunnerV6.applyEngineConstants(safetyCap);

        File outDir = new File(RunV6FullGames.BASE + "/_calibration");
        ExpCsv.ensureDir(outDir);
        File csv = new File(outDir, "game_length_probe.csv");
        if (csv.exists() && !csv.delete())
            System.err.println("[probe] could not delete stale " + csv.getPath() + "; rows will append");

        System.out.println("############################################################");
        System.out.println("# v6 FULL GAMES — game-length probe");
        System.out.println("# safety cap = " + safetyCap + " ticks (games reaching it are CENSORED, not dropped)");
        System.out.println("# maps       = " + FullGameMapSetV6.summary(maps));
        System.out.println("# threads    = " + threads);
        System.out.println("############################################################");
        System.out.println();

        Map<Integer, List<Row>> byLevel = new LinkedHashMap<>();
        for (int li = 0; li < cuLevels.length; li++) {
            int cu = cuLevels[li];
            int games = gamesPerLevel[Math.min(li, gamesPerLevel.length - 1)];
            System.out.println("--- " + cu + " units, " + games + " games x "
                    + probeMatchups(cu).size() + " matchups ---");
            List<Row> rows = runLevel(cu, games, maps, threads, safetyCap);
            byLevel.put(cu, rows);
            ExpCsv.append(csv, HEADER, toLines(rows));
            report(cu, rows, safetyCap);
            if (RunGuard.stopRequested()) { System.out.println("[probe] stop requested; ending early"); break; }
        }

        System.out.println();
        System.out.println("############################################################");
        System.out.println("# RECOMMENDATION");
        System.out.println("############################################################");
        recommend(byLevel, safetyCap);
        System.out.println();
        System.out.println("[probe] rows written to " + csv.getPath());
        RunGuard.finished();
    }

    private static final class Row {
        final int cu, gameIndex, mapIndex, orientation, endTurn;
        final String opponent, ratio, decidedBy;
        final boolean censored;
        Row(int cu, String opponent, String ratio, int gameIndex, int mapIndex, int orientation,
            int endTurn, String decidedBy, boolean censored) {
            this.cu = cu; this.opponent = opponent; this.ratio = ratio; this.gameIndex = gameIndex;
            this.mapIndex = mapIndex; this.orientation = orientation; this.endTurn = endTurn;
            this.decidedBy = decidedBy; this.censored = censored;
        }
    }

    /**
     * Plays {@code gamesPerMatchup} games for each sampled matchup at one budget level.
     *
     * <p>Game indices are spread across the full 200-game grid ({@code stride}) rather than taken from
     * its front, so the sample covers all {@value FullGameMapSetV6#NUM_MAPS} maps and both
     * orientations instead of the first few maps only.
     */
    private static List<Row> runLevel(int cu, int gamesPerMatchup, List<FullGameMapSetV6.MapInfo> maps,
                                      int threads, int safetyCap) throws Exception {
        List<FullGameRunnerV6.Matchup> ms = probeMatchups(cu);
        int stride = Math.max(1, FullGameRunnerV6.GAMES_PER_MATCHUP / gamesPerMatchup);

        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, 15_000L);
        governor.start();
        List<Future<Row>> futures = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        int total = ms.size() * gamesPerMatchup;
        long t0 = System.currentTimeMillis();

        try {
            for (FullGameRunnerV6.Matchup m : ms) {
                for (int i = 0; i < gamesPerMatchup; i++) {
                    final int gameId = (i * stride) % FullGameRunnerV6.GAMES_PER_MATCHUP;
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        if (RunGuard.stopRequested()) return null;
                        governor.acquire();
                        try {
                            FullGameMapSetV6.MapInfo map = maps.get(FullGameRunnerV6.mapIndexOf(gameId));
                            String row = FullGameRunnerV6.playOne(m, map, gameId, 0);
                            String[] f = row.split(",", -1);
                            int endTurn = Integer.parseInt(f[14]);
                            String decidedBy = f[15];
                            int c = done.incrementAndGet();
                            if (c % 20 == 0 || c == total) {
                                double perGame = (System.currentTimeMillis() - t0) / (double) c;
                                System.out.println(String.format(Locale.ROOT,
                                        "    %d/%d  (%.1f s/game)  [%s]",
                                        c, total, perGame / 1000.0, governor.status()));
                            }
                            return new Row(cu, m.opponent, m.ratio > 0 ? String.valueOf(m.ratio) : "NA",
                                    idx, map.index, FullGameRunnerV6.orientationOf(gameId),
                                    endTurn, decidedBy, endTurn >= safetyCap);
                        } catch (Throwable t) {
                            System.err.println("[probe] game failed (" + m.key() + ", id=" + gameId + "): " + t);
                            return null;
                        } finally {
                            governor.release();
                        }
                    }));
                }
            }
            List<Row> rows = new ArrayList<>();
            for (Future<Row> f : futures) {
                Row r = f.get();
                if (r != null) rows.add(r);
            }
            return rows;
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }
    }

    private static List<String> toLines(List<Row> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (Row r : rows)
            out.add(String.format(Locale.ROOT, "%dcu,%d,%s,%s,%d,%d,%d,%d,%s,%d",
                    r.cu, r.cu, r.opponent, r.ratio, r.gameIndex, r.mapIndex, r.orientation,
                    r.endTurn, r.decidedBy, r.censored ? 1 : 0));
        return out;
    }

    private static void report(int cu, List<Row> rows, int safetyCap) {
        if (rows.isEmpty()) { System.out.println("  (no games completed)"); return; }
        List<Integer> ends = new ArrayList<>();
        int conquest = 0, censored = 0;
        for (Row r : rows) {
            ends.add(r.endTurn);
            if ("CAPITAL".equals(r.decidedBy)) conquest++;
            if (r.censored) censored++;
        }
        Collections.sort(ends);
        System.out.println(String.format(Locale.ROOT,
                "  n=%d  endTurn min/p50/p90/p95/p99/max = %d/%d/%d/%d/%d/%d   "
              + "conquest=%.0f%%  censored@%d=%.1f%%",
                ends.size(), ends.get(0), pct(ends, 50), pct(ends, 90), pct(ends, 95),
                pct(ends, 99), ends.get(ends.size() - 1),
                100.0 * conquest / rows.size(), safetyCap, 100.0 * censored / rows.size()));
        System.out.println();
    }

    private static int pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /**
     * Prints, per level and overall, the share of games that each candidate cap would have decided,
     * and names the smallest cap meeting {@link #TARGET_BIND_RATE} across ALL levels — the cap has to
     * be one number for the whole tournament, so the binding level is the one that sets it.
     */
    private static void recommend(Map<Integer, List<Row>> byLevel, int safetyCap) {
        System.out.printf(Locale.ROOT, "%-10s", "cap");
        for (int cu : byLevel.keySet()) System.out.printf(Locale.ROOT, "%12s", cu + "cu");
        System.out.printf(Locale.ROOT, "%12s%n", "worst");

        int chosen = -1;
        for (int cap : CAP_LADDER) {
            if (cap > safetyCap) continue;
            System.out.printf(Locale.ROOT, "%-10d", cap);
            double worst = 0;
            for (List<Row> rows : byLevel.values()) {
                double bind = bindRate(rows, cap);
                worst = Math.max(worst, bind);
                System.out.printf(Locale.ROOT, "%11.1f%%", 100 * bind);
            }
            System.out.printf(Locale.ROOT, "%11.1f%%%n", 100 * worst);
            if (chosen < 0 && worst <= TARGET_BIND_RATE) chosen = cap;
        }

        System.out.println();
        if (chosen < 0) {
            System.out.println("No cap on the ladder holds the bind rate at or below "
                    + (int) (100 * TARGET_BIND_RATE) + "% at every level.");
            System.out.println("Either raise the safety cap and re-probe, or accept a higher bind rate "
                    + "and report the conquest/score split alongside the win rates.");
        } else {
            System.out.println("RECOMMENDED TURN CAP: " + chosen
                    + "   (<= " + (int) (100 * TARGET_BIND_RATE) + "% of games decided by the cap at every level)");
            System.out.println("Set RunV6FullGames.TURN_LIMIT = " + chosen
                    + " if it differs from the compiled value (" + RunV6FullGames.TURN_LIMIT + "), rebuild, then run.");
        }
        System.out.println();
        System.out.println("A censored game (one that reached the probe's safety cap of " + safetyCap
                + ") counts as binding at every candidate cap, which is the conservative reading.");
    }

    /** Share of games that a cap of {@code cap} would have decided rather than letting them finish. */
    private static double bindRate(List<Row> rows, int cap) {
        if (rows.isEmpty()) return 0;
        int bind = 0;
        for (Row r : rows) if (r.censored || r.endTurn >= cap) bind++;
        return bind / (double) rows.size();
    }

    static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }
}
