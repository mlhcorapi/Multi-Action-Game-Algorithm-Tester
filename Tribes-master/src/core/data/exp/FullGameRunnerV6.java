package core.data.exp;

import core.Constants;
import core.Types;
import core.actors.Tribe;
import core.game.Board;
import core.game.Game;
import players.Agent;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v6 FULL GAMES — the tournament phase. EMCTS plays complete Tribes games against every experimental
 * configuration, at every crossover ratio and every budget level, and the only thing measured is who
 * won.
 *
 * <p>This is the companion to the v6 isolation phase and answers the question the isolation phase
 * deliberately does not: the isolation phase measures the value of the best sequence found in ONE
 * turn from a captured position, which is search quality; a win rate is playing strength. See
 * {@code analysis/EXPERIMENT_V6_FULL_GAMES_PLAN.md}.
 *
 * <h2>The design, and what each part of it removes</h2>
 *
 * <p><b>Maps ({@link FullGameMapSetV6}).</b> {@value FullGameMapSetV6#NUM_MAPS} fixed maps, the same
 * ones in every matchup and at every budget level, each played {@value #GAMES_PER_MAP} times.
 *
 * <p><b>Orientation swap.</b> Each map is played {@value #GAMES_PER_ORIENTATION} times in each of two
 * orientations. The swap is exact and it is a property of the engine, not something simulated here:
 * the level generator binds {@code capitalCells[i]} to {@code tribes[i]}, and
 * {@code Game.initGameStructures} seats {@code players.get(i)} on {@code tribes[i]}. So holding the
 * tribes array fixed and REVERSING the player list produces a byte-identical board on which the two
 * agents have exchanged capitals. Because the seat index comes from the row-major scan order of the
 * capital tiles, exchanging capitals also exchanges seats — so turn order swaps with position, and
 * first-move advantage is balanced by the same mechanism. Verified by
 * {@code NewExperimentSmokeTestV6Full}.
 *
 * <p><b>Mirror pairs.</b> Game {@code (map m, pair p, orientation 0)} and {@code (map m, pair p,
 * orientation 1)} use the SAME level seed, the SAME game seed and the SAME per-seat agent seeds. The
 * two games are therefore identical in every respect except which agent sits in which seat. Agent
 * seeds attach to the SEAT, not to the agent, which is what makes that true. A matchup is 100 such
 * pairs, and pairing them in the analysis is materially tighter than treating 200 games as
 * independent.
 *
 * <p><b>Shared seed grid.</b> The (map, gameSeed, agentSeed) grid does not depend on the matchup or
 * the budget level, so all 36 matchups are played over an identical set of 200 starting conditions.
 * A difference between two ratios is then a difference between the agents rather than between the
 * positions they happened to draw — the full-game analogue of the isolation phase's shared
 * {@code visitSeed} across configs.
 *
 * <p><b>Mirror tribes.</b> IMPERIUS2 and IMPERIUS have the same starting technology and unit and
 * identical terrain-generation weights, so tribe identity contributes nothing.
 *
 * <h2>Scheduling</h2>
 *
 * <p>Every game of a budget level goes into ONE pool rather than a pool per matchup. A matchup is
 * only 200 games, so a per-matchup pool on a 93-worker machine spends its last wave mostly idle and
 * its tail waiting on the single longest game; flattening the level's 2,400 games into one queue
 * removes both. Concurrency is gated by a {@link LoadGovernor} because an in-flight game holds a
 * search tree whose size grows with the budget.
 *
 * <h2>Resumability</h2>
 *
 * <p>Each finished game appends one row to its matchup's {@code games.csv} immediately, and a resume
 * reads those files back to find which {@code GameId}s already exist. So an interrupted run loses at
 * most the games that were in flight. A matchup is written to {@code _completed.txt} only once all
 * {@value #GAMES_PER_MATCHUP} of its rows exist.
 */
public final class FullGameRunnerV6 {

    private FullGameRunnerV6() {}

    /** Games per matchup = {@value FullGameMapSetV6#NUM_MAPS} maps x {@value #GAMES_PER_MAP}. */
    public static final int GAMES_PER_MAP = 10;
    /** Games per map in EACH orientation. Half of {@link #GAMES_PER_MAP}, by construction. */
    public static final int GAMES_PER_ORIENTATION = GAMES_PER_MAP / 2;
    public static final int GAMES_PER_MATCHUP = FullGameMapSetV6.NUM_MAPS * GAMES_PER_MAP;

    /** Crossover ratios, identical to the isolation phase so the two phases line up config-for-config. */
    public static final int[] RATIOS = IsolationPhaseRunnerV6.RATIOS;

    /** Seed bases. Disjoint from every other v6 job's, so no run can accidentally replay another's. */
    public static final long GAME_SEED_BASE  = 7_100_000L;
    public static final long AGENT_SEED_BASE = 7_200_000L;
    /** Separates the two seats' agent RNGs within a game. Attached to the seat, never to the agent. */
    private static final long SEAT1_SEED_OFFSET = 7_777L;

    /** Initial run plus up to (MAX_GAME_ATTEMPTS-1) perturbed retries when the engine throws. */
    public static final int MAX_GAME_ATTEMPTS = 4;
    /** Seed perturbation per retry. Large, to move well away from a deterministic engine crash. */
    private static final long RETRY_SEED_OFFSET = 9_000_000L;

    private static final long GOVERNOR_INTERVAL_MS = 15_000L;

    public static final String GAMES_HEADER =
            "Level,Opponent,Ratio,GameId,MapIndex,LevelSeed,Orientation,PairIndex,"
          + "EmctsSeat,OppSeat,Winner,EmctsWon,EmctsScore,OppScore,EndTurn,DecidedBy,Attempts";
    /** Field count of {@link #GAMES_HEADER}; a short row is a torn write and is dropped on resume. */
    private static final int GAMES_FIELDS = GAMES_HEADER.split(",").length;

    // ------------------------------------------------------------------ matchups

    /** One opponent for EMCTS at one budget level. */
    public static final class Matchup {
        public final String opponent;   // "EMCTS" (control), "CrossoverBB", "CrossoverExpansion", "CrossoverCombo"
        public final int ratio;         // -1 when the config has no ratio (EMCTS, CrossoverBB)
        public final int computeUnits;
        public final String levelName;  // e.g. "40000cu"

        public Matchup(String opponent, int ratio, int computeUnits) {
            this.opponent = opponent;
            this.ratio = ratio;
            this.computeUnits = computeUnits;
            this.levelName = computeUnits + "cu";
        }

        /** {@code test_results/v6_full/<Opponent>/[ratio_<r>/]<cu>cu}. Mirrors the isolation tree. */
        public File dir(File base) {
            File d = new File(base, opponent);
            if (ratio > 0) d = new File(d, "ratio_" + ratio);
            return new File(d, levelName);
        }

        /** Checkpoint key and log label. */
        public String key() {
            return levelName + ";EMCTS_vs_" + opponent + (ratio > 0 ? "_r" + ratio : "");
        }

        public AgentSpec spec() {
            switch (opponent) {
                case "EMCTS":              return ExpAgents.emctsV6(computeUnits);
                case "CrossoverBB":        return ExpAgents.crossoverBBV6(computeUnits);
                case "CrossoverExpansion": return ExpAgents.crossoverExpansionV6(ratio, computeUnits);
                case "CrossoverCombo":     return ExpAgents.crossoverComboV6(ratio, computeUnits);
                default: throw new IllegalArgumentException("Unknown opponent: " + opponent);
            }
        }
    }

    /**
     * The 12 matchups at one budget level: the EMCTS-vs-EMCTS control first, then the 11 experimental
     * configurations.
     *
     * <p>The control runs first on purpose. It should return 50% within noise; if it does not, some
     * seat or spawn asymmetry survived the orientation swap and every other number at this level is
     * suspect. Cheapest config, most informative failure — so it is the one that runs while there is
     * still time to react.
     */
    public static List<Matchup> matchups(int computeUnits) {
        List<Matchup> ms = new ArrayList<>();
        ms.add(new Matchup("EMCTS", -1, computeUnits));           // control: expect 50%
        ms.add(new Matchup("CrossoverBB", -1, computeUnits));
        for (int r : RATIOS) ms.add(new Matchup("CrossoverExpansion", r, computeUnits));
        for (int r : RATIOS) ms.add(new Matchup("CrossoverCombo", r, computeUnits));
        return ms;
    }

    // ------------------------------------------------------------------ seeds

    public static int mapIndexOf(int gameId)    { return gameId / GAMES_PER_MAP; }
    public static int repOf(int gameId)         { return gameId % GAMES_PER_MAP; }
    public static int orientationOf(int gameId) { return repOf(gameId) / GAMES_PER_ORIENTATION; }
    public static int pairIndexOf(int gameId)   { return repOf(gameId) % GAMES_PER_ORIENTATION; }

    /**
     * Game RNG seed. Depends on (map, pair, attempt) only, so the two games of a mirror pair share it.
     *
     * <p>Every seed in this class is passed through {@link FullGameMapSetV6#scramble} before use.
     * {@code java.util.Random} is strongly correlated across nearby seeds — measured on this project's
     * seed range, twelve consecutive seeds put their first {@code nextDouble()} on the same side of
     * 0.5 — so a structured key like {@code base + map*1000 + pair} would hand the five repetitions of
     * a position five closely-related RNG streams instead of five independent ones. The key stays
     * structured and readable; the scramble turns it into a well-separated seed.
     */
    public static long gameSeed(int gameId, int attempt) {
        return FullGameMapSetV6.scramble(GAME_SEED_BASE + mapIndexOf(gameId) * 1000L
                + pairIndexOf(gameId) + attempt * RETRY_SEED_OFFSET);
    }

    /**
     * Agent RNG seed for a SEAT (not for an agent). Depends on (map, pair, seat, attempt) only, so the
     * two games of a mirror pair hand identical seeds to identical seats while the agents swap over.
     * Scrambled for the reason given on {@link #gameSeed}.
     */
    public static long agentSeedForSeat(int gameId, int seat, int attempt) {
        return FullGameMapSetV6.scramble(AGENT_SEED_BASE + mapIndexOf(gameId) * 1000L
                + pairIndexOf(gameId) + (seat == 0 ? 0L : SEAT1_SEED_OFFSET)
                + attempt * RETRY_SEED_OFFSET);
    }

    // ------------------------------------------------------------------ running a level

    /**
     * Runs every outstanding game of one budget level, flattening all matchups into a single pool.
     *
     * @param maps      the shared map set
     * @param threads   worker ceiling (the governor may lower it during the run)
     * @param base      output root, e.g. {@code test_results/v6_full}
     * @param doneMatchups matchup keys already in {@code _completed.txt}; those are skipped whole
     * @return the matchups that finished completely in THIS call, for the caller to checkpoint. A
     *         matchup interrupted part-way is absent, so it is never marked complete.
     */
    public static List<Matchup> runLevel(int computeUnits, List<FullGameMapSetV6.MapInfo> maps,
                                         int threads, File base, Set<String> doneMatchups)
            throws Exception {

        // Set here, not only in the caller, because the engine constants are JVM-wide statics and
        // something else in this process may have moved them. In particular ThreadCalibrationV6Full
        // lowers MAX_TURNS_CAPITALS to its own 2-12 tick cap while sweeping, and it runs immediately
        // before this method for any level that is not yet calibrated — so without this line the real
        // tournament games would silently be played to the calibration's cap.
        applyEngineConstants(RunV6FullGames.TURN_LIMIT);

        List<Matchup> all = matchups(computeUnits);
        List<Matchup> pending = new ArrayList<>();
        List<GameTask> tasks = new ArrayList<>();

        for (Matchup m : all) {
            if (doneMatchups.contains(m.key())) {
                System.out.println("  SKIP (done): " + m.key());
                continue;
            }
            File dir = m.dir(base);
            ExpCsv.ensureDir(dir);
            File csv = new File(dir, "games.csv");
            Set<Integer> already = compactPartialMatchup(csv);
            if (already.size() >= GAMES_PER_MATCHUP) {
                // Complete on disk but not checkpointed - a stop between the last write and the mark.
                pending.add(m);
                System.out.println("  RECOVER (all " + GAMES_PER_MATCHUP + " rows present): " + m.key());
                continue;
            }
            pending.add(m);
            for (int g = 0; g < GAMES_PER_MATCHUP; g++)
                if (!already.contains(g)) tasks.add(new GameTask(m, csv, g));
            if (!already.isEmpty())
                System.out.println("  RESUME " + m.key() + ": " + already.size() + "/"
                        + GAMES_PER_MATCHUP + " games already done");
        }

        // Nothing outstanding: every pending matchup was already complete on disk. Still route the
        // answer through the same row-count gate the normal path uses, so "may be checkpointed" has
        // exactly one definition and cannot drift between the two exits.
        if (tasks.isEmpty()) return completeOnDisk(pending, base);

        System.out.println(String.format(Locale.ROOT,
                "  %d game(s) outstanding across %d matchup(s) at %d units, %d worker(s)",
                tasks.size(), pending.size(), computeUnits, threads));

        // Interleave matchups so a stop leaves every matchup at a similar completion fraction rather
        // than a few finished and the rest untouched, and so thermal drift over the level cannot line
        // up with one agent's identity.
        tasks.sort((a, b) -> a.gameId != b.gameId ? Integer.compare(a.gameId, b.gameId)
                                                  : a.matchup.key().compareTo(b.matchup.key()));

        final int total = tasks.size();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, GOVERNOR_INTERVAL_MS);
        governor.start();
        long t0 = System.currentTimeMillis();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (GameTask task : tasks) {
                futures.add(pool.submit(() -> {
                    if (RunGuard.stopRequested()) { skipped.incrementAndGet(); return; }
                    try {
                        governor.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        skipped.incrementAndGet();
                        return;
                    }
                    try {
                        String row = playWithRetries(task, maps);
                        if (row == null) { failed.incrementAndGet(); return; }
                        ExpCsv.append(task.csv, GAMES_HEADER, Collections.singletonList(row));
                    } catch (Throwable t) {
                        failed.incrementAndGet();
                        System.err.println("[v6full] task failed " + task.matchup.key()
                                + " game=" + task.gameId + ": " + t);
                    } finally {
                        governor.release();
                    }
                    int c = completed.incrementAndGet();
                    if (c % 50 == 0 || c == total) {
                        long elapsed = System.currentTimeMillis() - t0;
                        double perGame = elapsed / (double) c;
                        long etaMin = Math.round((total - c) * perGame / 60000.0);
                        System.out.println(String.format(Locale.ROOT,
                                "    %d/%d games  (%.1f s/game, ETA %d min)  [%s]",
                                c, total, perGame / 1000.0, etaMin, governor.status()));
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }

        if (failed.get() > 0)
            System.out.println("[v6full] WARN " + failed.get() + " game(s) produced no row after "
                    + MAX_GAME_ATTEMPTS + " attempts; they are re-attempted on the next resume");
        if (governor.throttleEvents() > 0)
            System.out.println("[v6full] governor: " + governor.throttleEvents()
                    + " throttle event(s), concurrency fell to " + governor.lowestAllowed() + "/" + threads);

        // Only matchups that are now complete on disk may be checkpointed by the caller, and only if
        // the level ran to the end: a stop part-way must not mark anything complete.
        if (skipped.get() > 0 || RunGuard.stopRequested()) return new ArrayList<>();
        return completeOnDisk(pending, base);
    }

    /** The subset of {@code candidates} whose games.csv holds all {@value #GAMES_PER_MATCHUP} games. */
    private static List<Matchup> completeOnDisk(List<Matchup> candidates, File base) {
        List<Matchup> finished = new ArrayList<>();
        for (Matchup m : candidates)
            if (countRows(new File(m.dir(base), "games.csv")) >= GAMES_PER_MATCHUP) finished.add(m);
        return finished;
    }

    private static final class GameTask {
        final Matchup matchup; final File csv; final int gameId;
        GameTask(Matchup matchup, File csv, int gameId) {
            this.matchup = matchup; this.csv = csv; this.gameId = gameId;
        }
    }

    // ------------------------------------------------------------------ one game

    /**
     * Plays one game, retrying with perturbed game/agent seeds if the engine throws. The LEVEL seed is
     * never perturbed, so a retried game stays on its assigned map and the map/orientation balance
     * holds exactly. Returns the CSV row, or null if every attempt failed.
     */
    private static String playWithRetries(GameTask task, List<FullGameMapSetV6.MapInfo> maps) {
        FullGameMapSetV6.MapInfo map = maps.get(mapIndexOf(task.gameId));
        for (int attempt = 0; attempt < MAX_GAME_ATTEMPTS; attempt++) {
            if (RunGuard.stopRequested()) return null;
            try {
                return playOne(task.matchup, map, task.gameId, attempt);
            } catch (Throwable t) {
                // Catch Throwable (incl. OutOfMemoryError) so one bad game is retried or dropped
                // rather than killing a multi-day run.
                if (attempt == MAX_GAME_ATTEMPTS - 1)
                    System.err.println("[v6full] " + task.matchup.key() + " game=" + task.gameId
                            + " failed on all " + MAX_GAME_ATTEMPTS + " attempts: " + t);
            }
        }
        return null;
    }

    /**
     * One complete game.
     *
     * <p>The orientation swap is the reversal of the specs list and nothing else: the tribes array
     * stays {@code {IMPERIUS2, IMPERIUS}} in both orientations, so both games of a mirror pair are
     * generated from the identical board.
     */
    static String playOne(Matchup m, FullGameMapSetV6.MapInfo map, int gameId, int attempt) {
        int orientation = orientationOf(gameId);

        AgentSpec emctsSpec = ExpAgents.emctsV6(m.computeUnits);
        AgentSpec oppSpec = m.spec();
        // List index i plays TRIBES[i]; TRIBES[0] sits at map.seatA and TRIBES[1] at map.seatB.
        AgentSpec spec0 = (orientation == 0) ? emctsSpec : oppSpec;
        AgentSpec spec1 = (orientation == 0) ? oppSpec : emctsSpec;
        Agent agent0 = spec0.create(agentSeedForSeat(gameId, map.seatA, attempt));
        Agent agent1 = spec1.create(agentSeedForSeat(gameId, map.seatB, attempt));

        ArrayList<Agent> players = new ArrayList<>(Arrays.asList(agent0, agent1));
        Game game = new Game();
        game.init(players, map.levelSeed, FullGameMapSetV6.TRIBES,
                gameSeed(gameId, attempt), Types.GAME_MODE.CAPITALS);
        game.run(null, null);

        Agent emcts = (orientation == 0) ? agent0 : agent1;
        Agent opp = (orientation == 0) ? agent1 : agent0;
        int emctsSeat = seatOf(game, emcts), oppSeat = seatOf(game, opp);

        int[] scores = game.getScores();
        Types.RESULT[] status = game.getWinnerStatus();
        boolean emctsWon = status[emctsSeat] == Types.RESULT.WIN;
        boolean oppWon = status[oppSeat] == Types.RESULT.WIN;
        // The engine always resolves exactly one WIN (GameState.gameOver ranks the tribes and sets the
        // ranking's first entry to WIN even when the turn cap decided it), so there are no draws. The
        // score fallback only fires if that invariant is ever violated.
        if (emctsWon == oppWon) emctsWon = scores[emctsSeat] >= scores[oppSeat];

        // Winner is a ROLE, not an agent name. In the EMCTS-vs-EMCTS control arm both sides are called
        // "EMCTS", so a name here would make that matchup read as a 100% EMCTS win rate — in the one
        // arm whose entire purpose is to come out at 50%. Which config the opponent was is already
        // fully determined by the Opponent and Ratio columns.
        return String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%s,%d",
                m.levelName, m.opponent, m.ratio > 0 ? String.valueOf(m.ratio) : "NA",
                gameId, map.index, map.levelSeed, orientation, pairIndexOf(gameId),
                emctsSeat, oppSeat, emctsWon ? "EMCTS" : "OPPONENT", emctsWon ? 1 : 0,
                scores[emctsSeat], scores[oppSeat],
                Math.max(emcts.getGameDurationTurns(), opp.getGameDurationTurns()),
                decidedBy(game), attempt + 1);
    }

    /** {@code Game.init} reseats players by tribe placement, so locate by object identity. */
    private static int seatOf(Game game, Agent agent) {
        Agent[] seated = game.getPlayers();
        for (int i = 0; i < seated.length; i++) if (seated[i] == agent) return i;
        throw new IllegalStateException("Tracked agent not found among seated players");
    }

    /**
     * How the game ended: {@code CAPITAL} if one tribe finished holding every capital (a conquest
     * win), {@code SCORE} otherwise (the turn cap was reached and
     * {@code GameState.computeGameRanking} decided it).
     *
     * <p>Worth recording rather than inferring from {@code EndTurn}: a win rate made mostly of
     * score-at-the-cap decisions means something different from one made of conquests, and the share
     * of each is the evidence that the turn cap is not doing the deciding.
     */
    private static String decidedBy(Game game) {
        Board board = game.getBoard();
        int[] capitals = board.getCapitalIDs();
        for (Tribe t : board.getTribes()) {
            boolean holdsAll = true;
            for (int cid : capitals) if (!t.getCitiesID().contains(cid)) { holdsAll = false; break; }
            if (holdsAll) return "CAPITAL";
        }
        return "SCORE";
    }

    // ------------------------------------------------------------------ resume

    /**
     * Resume support: rewrite a matchup's {@code games.csv} keeping only well-formed, unique rows, and
     * return the set of {@code GameId}s that are present.
     *
     * <p>A row is the whole record of a game and is appended in one write, so unlike the isolation
     * phase there is no two-file ordering to reason about — the only damage an interruption can leave
     * is a torn final line, which a field-count check removes. Duplicate ids can only come from a
     * doubled run; the first occurrence is kept.
     */
    static Set<Integer> compactPartialMatchup(File csv) throws Exception {
        Set<Integer> ids = new LinkedHashSet<>();
        if (!csv.exists()) return ids;
        List<String> lines = Files.readAllLines(csv.toPath());
        List<String> kept = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] f = line.split(",", -1);
            if (f.length != GAMES_FIELDS) continue;              // torn write
            try {
                int id = Integer.parseInt(f[3].trim());          // GameId
                if (id < 0 || id >= GAMES_PER_MATCHUP) continue;
                if (ids.add(id)) kept.add(line);
            } catch (NumberFormatException ignore) { }
        }
        if (kept.size() != lines.size() - 1) {                   // only rewrite when something changed
            StringBuilder sb = new StringBuilder(GAMES_HEADER).append('\n');
            for (String k : kept) sb.append(k).append('\n');
            ExpCsv.writeText(csv, sb.toString());
        }
        return ids;
    }

    /**
     * How many distinct, well-formed games a matchup's CSV holds. Read-only, unlike
     * {@link #compactPartialMatchup}: this is called on every leaf once a level ends, purely to decide
     * what may be checkpointed, and a counting call has no business rewriting files.
     */
    static int countRows(File csv) {
        if (!csv.exists()) return 0;
        try {
            Set<Integer> ids = new HashSet<>();
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length != GAMES_FIELDS) continue;
                try {
                    int id = Integer.parseInt(f[3].trim());
                    if (id >= 0 && id < GAMES_PER_MATCHUP) ids.add(id);
                } catch (NumberFormatException ignore) { }
            }
            return ids.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Sets the engine globals every full game in this experiment runs under. */
    public static void applyEngineConstants(int turnLimit) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        // Suppresses Game's per-game result block. Thousands of games across ~90 threads would
        // otherwise interleave five lines each into the run log and bury the progress output.
        Constants.VERBOSE = false;
        // Maps are generated water-free (LevelGenerator includeWater=false), so the naval techs are
        // dead weight; the SIMPLE heuristic penalises them when this is set. Matches every other
        // v6 job, and both agents are affected identically.
        Constants.REMOVE_FISHING = true;
        Constants.MAX_TURNS_CAPITALS = turnLimit;
        // The budget is STOP_COMPUTE, so the engine's per-turn clock must not also bind.
        Constants.TURN_TIME_LIMITED = false;
    }
}
