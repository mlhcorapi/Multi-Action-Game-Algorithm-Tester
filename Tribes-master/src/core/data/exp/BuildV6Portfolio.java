package core.data.exp;

import core.Constants;
import core.Types;
import core.data.exp.BuildV5Portfolio.Cap;
import core.game.FastGameStateIO;
import core.game.GameState;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * v6 portfolio builder: parallel and restart-safe.
 *
 * <p><b>The generation and selection rules are v5's, unchanged.</b> Every decision that determines
 * WHICH scenarios end up in the portfolio — the source-game setup, the level-seed bases, the capture
 * ticks, the validation predicate, the stuck-game guard, the attempt caps and the manifest schema —
 * lives in {@link BuildV5Portfolio} and is called from here rather than reimplemented. Two reasons
 * that is the right call rather than a shortcut:
 *
 * <ol>
 *   <li>The source games are already played by an EMCTS agent using <b>greedy</b> initialization
 *       ({@code BuildV5Portfolio.buildEmctsSourceAgent} never sets {@code randomInitialSequence},
 *       whose default is false). v5's random-init switch applied only to the agents under test in the
 *       isolation phase, never to portfolio construction — so nothing about the portfolio needed to
 *       change for v6's greedy-init switch.</li>
 *   <li>Building v6's portfolio by an identical procedure keeps it comparable with v5's, which is what
 *       lets the two experiments be read against each other.</li>
 * </ol>
 *
 * <p>What this class adds is <b>scheduling and durability</b>, neither of which can change the output:
 *
 * <p><b>Continuous parallelism.</b> v5 ran source games in barrier-synchronised waves of {@code
 * threads} attempts: every wave finished at the speed of its slowest game, and on a 90-worker server
 * with source games whose length varies several-fold, most workers sit idle most of the time. Here
 * attempts are kept continuously in flight with a bounded look-ahead and <i>consumed in attempt
 * order</i>, so no worker waits for a barrier while work remains.
 *
 * <p><b>Determinism is preserved exactly.</b> The portfolio is defined as "the first {@code target}
 * valid captures in (attempt, tick) order", which is a property of the ordered consumption, not of
 * the execution order. Each attempt is a self-contained game seeded by {@code seedBase + attempt},
 * so which captures an attempt yields does not depend on what ran beside it or when. Verified: this
 * builder and {@link BuildV5Portfolio} produce an identical {@code portfolio.csv} and scenarios that
 * a fixed-seed agent plans identically from (same greedy root, same sequence values, same node counts,
 * same realized compute units), at any thread count.
 *
 * <p><b>The scenario FILES are not byte-identical, and cannot be.</b> {@code Tribe} holds
 * enum-keyed {@link java.util.HashMap}s (monuments, tribesMet); enum {@code hashCode()} is the JVM
 * identity hash, so the map's serialization order varies between JVM runs and between thread counts.
 * The difference is confined to the order entries appear in the byte stream — contents, and everything
 * computed from them, are identical — and it is present in v5 exactly as it is here. Compare
 * portfolios by their manifest and by agent behaviour, never by {@code cmp} on the {@code .bin}s.
 *
 * <p><b>Restart-safe.</b> v5's builder was all-or-nothing: an interrupted build deleted its partial
 * output and replayed every source game from attempt 1. The dual category alone allows 1500 attempts
 * of real search, so that is hours thrown away on any restart. Here every finished attempt is
 * journalled to {@code _build_journal/} the moment it completes — its captures as {@code .bin} files
 * plus one index line — and a restart replays only the attempts that were still running. The journal
 * is deleted once the portfolio is complete.
 *
 * <p>Output (unchanged): {@code test_results/v6/_portfolio/} — {@code scenarios/scenario_NNN.bin} +
 * {@code portfolio.csv}.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.BuildV6Portfolio [outDir] [threads] [numSingle] [numDual]}
 */
public class BuildV6Portfolio {

    /**
     * Scenario counts: <b>50 single + 50 dual</b>, half of v5's 100 + 100.
     *
     * <p>The halving buys wall-clock, and it is taken out of the portfolio rather than out of the
     * visits because the two are not equivalent. Turns per cell fall from 10,000 to 5,000 either way,
     * but the independent replicate in the analysis is the SCENARIO â€” the 50 visits of a scenario
     * share a board, a position and a greedy root, so {@code v6_significance.py} averages them first
     * and tests on n scenarios. Cutting visits would leave n untouched and quietly make each
     * scenario's mean noisier; cutting scenarios states the cost where it is paid, n = 200 -> 100,
     * roughly a sqrt(2) widening of the paired test's resolution.
     *
     * <p>The single/dual split stays even so the branching-factor contrast the H5 confound check
     * rests on is untouched. v5's constants are deliberately NOT changed: v5's dataset is already on
     * disk and its analysis reads its own portfolio size.
     */
    public static final int NUM_SINGLE_SCENARIOS = 50;
    public static final int NUM_DUAL_SCENARIOS   = 50;

    /** In-flight attempts per worker. >1 so a slow game never starves the pool, bounded so a build
     *  that is about to hit its target does not speculatively run hundreds of useless games. */
    private static final int LOOKAHEAD_PER_THREAD = 2;

    private BuildV6Portfolio() {}

    /** Standalone entry point. {@link RunExperimentV6} calls {@link #build} instead, so that the
     *  portfolio finishing does not tell the shared {@link RunGuard} that the whole experiment has. */
    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        int threads   = args.length > 1 ? parseThreads(args[1]) : ServerProfile.get().maxWorkers();
        int numSingle = args.length > 2 ? Integer.parseInt(args[2].trim()) : NUM_SINGLE_SCENARIOS;
        int numDual   = args.length > 3 ? Integer.parseInt(args[3].trim()) : NUM_DUAL_SCENARIOS;

        RunGuard.install("v6 portfolio build",
                "re-run the same cell — finished source games are replayed from the journal, not re-searched");
        build(outDir, threads, numSingle, numDual);
        RunGuard.finished();
    }

    /** Builds (or resumes) the portfolio. Safe to call as a step inside a larger job. */
    public static void build(String outDir, int threads, int numSingle, int numDual) throws Exception {
        // Never more workers than the machine policy allows, whatever the caller passed.
        int ceiling = ServerProfile.get().maxWorkers();
        if (threads > ceiling) {
            System.out.println("[Portfolio] requested " + threads + " threads; capped to the machine "
                    + "ceiling of " + ceiling);
            threads = ceiling;
        }
        threads = Math.max(1, threads);

        File dir = new File(outDir);
        if (!RunGuard.acquireLock(dir, "BuildV6Portfolio")) RunGuard.abort(2);
        File scenariosDir = new File(dir, "scenarios");
        File manifest = new File(dir, "portfolio.csv");
        File doneMarker = new File(dir, "_portfolio_complete.txt");
        File journalDir = new File(dir, "_build_journal");

        // Checkpoint: if a COMPLETE portfolio for these exact targets already exists, skip the rebuild
        // (the server may restart during the long isolation phase, which re-enters this main).
        String wantTag = "single=" + numSingle + " dual=" + numDual;
        if (doneMarker.exists() && manifest.exists()) {
            try {
                String have = new String(Files.readAllBytes(doneMarker.toPath())).trim();
                long rows = Files.lines(manifest.toPath()).count() - 1; // minus header
                if (have.equals(wantTag) && rows == (numSingle + numDual)) {
                    System.out.println("[Portfolio] already complete (" + wantTag + ", " + rows
                            + " scenarios) — skipping regeneration. Delete " + doneMarker.getName()
                            + " to force a rebuild.");
                    return;
                }
            } catch (Exception ignore) { /* fall through to a (re)build */ }
        }

        // The scenario/manifest outputs are assembled in one pass at the end, so a partial set of them
        // is always discarded. The JOURNAL is what survives a restart, and it is keyed by target counts
        // so a changed target never reuses the wrong games.
        BuildV5Portfolio.deleteDir(scenariosDir);
        if (manifest.exists()) manifest.delete();
        if (doneMarker.exists()) doneMarker.delete();
        File ckpt = new File(dir, "_completed.txt");
        if (ckpt.exists()) ckpt.delete();
        ExpCsv.ensureDir(scenariosDir);
        Journal journal = Journal.open(journalDir, wantTag);

        ServerProfile.get().printBanner("v6 portfolio build");
        System.out.println("[Portfolio] threads=" + threads + "  targets: " + wantTag
                + "  journal=" + journalDir.getPath()
                + (journal.attemptsOnDisk() > 0
                        ? "  (RESUMING: " + journal.attemptsOnDisk() + " source game(s) already played)"
                        : "  (fresh build)"));

        ExpCsv.writeText(new File(dir, "config.txt"),
                "v6 Isolation Phase - Portfolio Generator (parallel, " + threads + " threads, journalled)\n" +
                "rules=IDENTICAL to v5 (BuildV5Portfolio): same seeds, capture ticks, validation and manifest\n" +
                "singlePlayer=" + numSingle + " scenarios from EMCTS vs DoNothing (SCORE); capture turns "
                        + Arrays.toString(BuildV5Portfolio.SINGLE_TICKS) + "; level-seed attempts capped at "
                        + BuildV5Portfolio.MAX_SINGLE_GAME_ATTEMPTS + "\n" +
                "twoPlayer=" + numDual + " scenarios from EMCTS vs EMCTS (CAPITALS); capture turns "
                        + Arrays.toString(BuildV5Portfolio.DUAL_TICKS) + "; level-seed attempts capped at "
                        + BuildV5Portfolio.MAX_DUAL_GAME_ATTEMPTS + "\n" +
                "sourceBudget=" + BuildV5Portfolio.SOURCE_BUDGET_UNITS + " compute-units (1000ms-equiv, reproducible)\n" +
                "parallel=source games run continuously (look-ahead " + LOOKAHEAD_PER_THREAD
                        + "x threads); captures consumed + IDs assigned in (gameAttempt, tick) order, "
                        + "so the portfolio is thread-count- and thread-order-independent\n" +
                "restart=every finished source game is journalled; a restart replays only games that "
                        + "were still running\n" +
                "validation=activeTribe cities>=1 AND units>=1 AND score>=" + BuildV5Portfolio.MIN_VALID_SCORE
                        + " AND a tiny EMCTS planTurn does not throw\n" +
                "stuck-game guard=if FIRST captured turn of a source game fails validation the whole game is aborted\n" +
                "tribes=IMPERIUS vs IMPERIUS2 on 11x11 water-free board\n");

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.REMOVE_FISHING = true;

        long t0 = System.currentTimeMillis();

        // SINGLE-source: EMCTS vs DoNothing, SCORE mode. MAX_TURNS_CAPITALS must cover the last capture
        // turn. It is a global, so the two categories must not overlap in time — they are sequential.
        Constants.MAX_TURNS_CAPITALS = BuildV5Portfolio.SINGLE_TICKS[BuildV5Portfolio.SINGLE_TICKS.length - 1];
        List<Cap> singles = collectCategory("single", BuildV5Portfolio.SINGLE_LEVEL_SEED_BASE,
                Types.GAME_MODE.SCORE, BuildV5Portfolio.SINGLE_TICKS, numSingle,
                BuildV5Portfolio.MAX_SINGLE_GAME_ATTEMPTS, false, threads, journal);

        if (RunGuard.stopRequested()) { System.out.println("[Portfolio] stopped during the single category — journal kept."); return; }

        // DUAL-source: EMCTS vs EMCTS, CAPITALS mode.
        Constants.MAX_TURNS_CAPITALS = 50;
        List<Cap> duals = collectCategory("dual", BuildV5Portfolio.DUAL_LEVEL_SEED_BASE,
                Types.GAME_MODE.CAPITALS, BuildV5Portfolio.DUAL_TICKS, numDual,
                BuildV5Portfolio.MAX_DUAL_GAME_ATTEMPTS, true, threads, journal);

        if (RunGuard.stopRequested()) { System.out.println("[Portfolio] stopped during the dual category — journal kept."); return; }

        // Persist single-threaded in (category, gameAttempt, tick) order — deterministic IDs, no write
        // contention, and identical to what v5 produces.
        int scenarioId = 0;
        for (Cap c : singles) BuildV5Portfolio.persistAndManifest(++scenarioId, "single", c.gameAttempt,
                c.levelSeed, BuildV5Portfolio.FIXED_GAME_SEED, c.tick, c.gs, scenariosDir, manifest);
        for (Cap c : duals)   BuildV5Portfolio.persistAndManifest(++scenarioId, "dual", c.gameAttempt,
                c.levelSeed, BuildV5Portfolio.FIXED_GAME_SEED, c.tick, c.gs, scenariosDir, manifest);

        // Completion marker — read on re-entry to skip rebuilding a finished portfolio. Written
        // atomically so a kill mid-write cannot leave a marker that parses but lies.
        if (scenarioId == numSingle + numDual) {
            RunGuard.atomicWrite(doneMarker, wantTag + "\n");
            BuildV5Portfolio.deleteDir(journalDir);   // the journal has done its job
            System.out.println("[Portfolio] journal discarded (portfolio complete).");
        } else {
            System.out.println("[Portfolio] WARN: " + scenarioId + " scenarios built, " + (numSingle + numDual)
                    + " wanted — no completion marker written, journal kept.");
        }
        System.out.println(String.format(Locale.ROOT,
                "[Portfolio] Complete. %d scenarios (%d single + %d dual) in %.1fs. Output: %s",
                scenarioId, singles.size(), duals.size(), (System.currentTimeMillis() - t0) / 1000.0, dir.getPath()));
    }

    /**
     * Runs source games continuously across {@code threads} workers until {@code target} valid
     * captures are collected or the attempt cap is hit, consuming results in attempt order.
     *
     * <p>Attempts already present in the journal are taken from disk instead of being replayed, so a
     * restart pays only for the games that were mid-flight.
     */
    private static List<Cap> collectCategory(String source, long seedBase, Types.GAME_MODE mode,
                                             int[] ticks, int target, int maxAttempts,
                                             boolean twoPlayer, int threads, Journal journal)
            throws Exception {
        List<Cap> collected = new ArrayList<>();
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(Math.max(1, threads)));
        int hardLookahead = Math.max(threads, threads * LOOKAHEAD_PER_THREAD);
        Deque<Pending> inFlight = new ArrayDeque<>();
        int next = 1, replayed = 0, played = 0, consumed = 0, capsSeen = 0, logged = 0;
        try {
            while (collected.size() < target && !RunGuard.stopRequested()) {
                // How many attempts could still be needed? Captures are consumed in attempt order, so
                // any attempt beyond that count is pure waste — its game is played and thrown away.
                // Before anything has been observed, assume the best case (every capture tick valid);
                // afterwards use the yield actually seen, which for the dual category is far below 1
                // capture per attempt and correctly opens the pipeline up.
                double yield = consumed > 0 ? capsSeen / (double) consumed : ticks.length;
                int needed = (int) Math.ceil(1.5 * (target - collected.size()) / Math.max(0.05, yield));
                int lookahead = Math.max(1, Math.min(hardLookahead, needed));

                // Top the pipeline up. Journalled attempts resolve immediately and never enter the pool.
                while (inFlight.size() < lookahead && next <= maxAttempts) {
                    final int attempt = next++;
                    final long levelSeed = seedBase + attempt;
                    List<Cap> cached = journal.read(source, attempt, levelSeed);
                    if (cached != null) {
                        inFlight.add(Pending.resolved(attempt, cached));
                        replayed++;
                    } else {
                        played++;
                        inFlight.add(Pending.submitted(attempt, pool.submit(() -> {
                            if (RunGuard.stopRequested()) return java.util.Collections.<Cap>emptyList();
                            List<Cap> caps = BuildV5Portfolio.runOneSourceGame(
                                    attempt, levelSeed, mode, ticks, twoPlayer);
                            journal.write(source, attempt, levelSeed, caps);
                            return caps;
                        })));
                    }
                }
                if (inFlight.isEmpty()) break;               // attempt cap reached and pipeline drained

                Pending head = inFlight.poll();
                List<Cap> caps;
                try { caps = head.get(); } catch (Exception e) { caps = java.util.Collections.emptyList(); }
                consumed++;
                capsSeen += caps.size();
                for (Cap c : caps) if (collected.size() < target) collected.add(c);

                if (consumed - logged >= 10 || collected.size() >= target) {
                    logged = consumed;
                    System.out.println(String.format(Locale.ROOT,
                            "[Portfolio] %s: through attempt %d -> %d/%d valid  "
                            + "(played %d, replayed %d, in flight %d, yield %.2f caps/attempt)",
                            source, head.attempt, collected.size(), target, played, replayed,
                            inFlight.size(), capsSeen / (double) consumed));
                }
            }
        } finally {
            // Speculative attempts past the target are irrelevant to the output; drop them promptly.
            for (Pending p : inFlight) p.cancel();
            RunGuard.shutdownAndWait(pool, 2);
        }
        if (collected.size() < target && !RunGuard.stopRequested()) {
            System.out.println("  WARN: only " + collected.size() + "/" + target + " valid " + source
                    + " scenarios after " + maxAttempts + " attempts.");
        }
        return collected;
    }

    /** An attempt whose captures are either already known or still being computed. */
    private static final class Pending {
        final int attempt;
        final Future<List<Cap>> future;
        final List<Cap> value;
        private Pending(int attempt, Future<List<Cap>> future, List<Cap> value) {
            this.attempt = attempt; this.future = future; this.value = value;
        }
        static Pending resolved(int attempt, List<Cap> caps) { return new Pending(attempt, null, caps); }
        static Pending submitted(int attempt, Future<List<Cap>> f) { return new Pending(attempt, f, null); }
        List<Cap> get() throws Exception { return future != null ? future.get() : value; }
        void cancel() { if (future != null) future.cancel(false); }
    }

    /**
     * Append-only record of finished source games, so a restart does not re-search them.
     *
     * <p>Layout under {@code _build_journal/}: one {@code <source>_<attempt>_t<tick>.bin} per capture,
     * and ONE {@code index.csv} line per finished attempt:
     * {@code source,attempt,levelSeed,<tick|tick|...  or  ->,END}.
     *
     * <p><b>Why the whole attempt is one line, and why it ends in {@code END}.</b> An attempt's record
     * has to be all-or-nothing. Recording one line per capture looks equivalent and is not: a kill
     * between the third and fourth line of an eleven-capture game leaves a journal that claims the game
     * yielded three captures. The resume would believe it, and the portfolio would silently be built
     * from the wrong scenarios — the one failure mode here that produces bad data rather than lost
     * time. One line per attempt reduces the window to a single append, and the {@code END} sentinel
     * closes it: a line that does not parse to five fields ending in {@code END} is a torn write and
     * the attempt is replayed.
     *
     * <p>Bins are written BEFORE the index line, so a kill between the two leaves an orphan {@code .bin}
     * that the replay simply overwrites — never a claim of a capture whose state is missing. A bin
     * truncated by the kill itself fails to load, which also triggers a replay of that attempt.
     *
     * <p>The journal is tagged with the target counts and discarded if they change, so a build for a
     * different portfolio size can never inherit the wrong games.
     */
    private static final class Journal {
        private final File dir;
        private final File index;
        /** source -> attempt -> ticks recorded (empty list = attempt produced no captures). */
        private final Map<String, Map<Integer, List<Integer>>> known = new LinkedHashMap<>();

        private Journal(File dir) {
            this.dir = dir;
            this.index = new File(dir, "index.csv");
        }

        static Journal open(File dir, String wantTag) {
            Journal j = new Journal(dir);
            File tagFile = new File(dir, "targets.txt");
            if (dir.exists()) {
                String have = "";
                try {
                    if (tagFile.exists()) have = new String(Files.readAllBytes(tagFile.toPath())).trim();
                } catch (Exception ignore) { }
                if (!have.equals(wantTag)) {
                    System.out.println("[Portfolio] journal was built for a different target (" + have
                            + " != " + wantTag + ") — discarding it.");
                    BuildV5Portfolio.deleteDir(dir);
                }
            }
            ExpCsv.ensureDir(dir);
            RunGuard.atomicWrite(tagFile, wantTag + "\n");
            j.load();
            return j;
        }

        private void load() {
            if (!index.exists()) return;
            int torn = 0;
            try {
                for (String line : Files.readAllLines(index.toPath())) {
                    String[] f = line.split(",", -1);
                    if (f.length != 5 || !"END".equals(f[4].trim())) { torn++; continue; }
                    try {
                        String src = f[0].trim();
                        int attempt = Integer.parseInt(f[1].trim());
                        List<Integer> ticks = new ArrayList<>();
                        String tickList = f[3].trim();
                        if (!tickList.isEmpty() && !tickList.equals("-")) {
                            for (String t : tickList.split("\\|")) ticks.add(Integer.parseInt(t.trim()));
                        }
                        known.computeIfAbsent(src, k -> new LinkedHashMap<>()).put(attempt, ticks);
                    } catch (NumberFormatException e) { torn++; }
                }
            } catch (Exception e) {
                System.out.println("[Portfolio] journal index unreadable (" + e + ") — rebuilding from scratch.");
                known.clear();
                return;
            }
            if (torn > 0) {
                System.out.println("[Portfolio] journal: " + torn + " incomplete record(s) ignored "
                        + "(interrupted mid-write) — those attempts will be replayed.");
            }
        }

        int attemptsOnDisk() {
            int n = 0;
            for (Map<Integer, List<Integer>> m : known.values()) n += m.size();
            return n;
        }

        /** Captures for a journalled attempt, or null if this attempt still has to be played. */
        List<Cap> read(String source, int attempt, long levelSeed) {
            Map<Integer, List<Integer>> bySrc = known.get(source);
            if (bySrc == null) return null;
            List<Integer> ticks = bySrc.get(attempt);
            if (ticks == null) return null;
            List<Cap> out = new ArrayList<>(ticks.size());
            for (int tick : ticks) {
                File bin = binFile(source, attempt, tick);
                try {
                    GameState gs = FastGameStateIO.load(bin);
                    out.add(new Cap(attempt, levelSeed, tick, gs));
                } catch (Exception e) {
                    // A capture the index claims but cannot be loaded: distrust the whole attempt and
                    // replay it, rather than silently building a short portfolio.
                    System.out.println("[Portfolio] journal entry " + bin.getName() + " unreadable ("
                            + e + ") — replaying attempt " + attempt);
                    bySrc.remove(attempt);
                    return null;
                }
            }
            return out;
        }

        /** Record a finished attempt: every bin first, then ONE terminated index line. */
        void write(String source, int attempt, long levelSeed, List<Cap> caps) {
            try {
                for (Cap c : caps) FastGameStateIO.save(c.gs, binFile(source, attempt, c.tick));
            } catch (Exception e) {
                System.err.println("[Portfolio] journal write failed for attempt " + attempt + ": " + e);
                return;   // no index line => the attempt is replayed rather than half-trusted
            }
            StringBuilder ticks = new StringBuilder();
            for (Cap c : caps) {
                if (ticks.length() > 0) ticks.append('|');
                ticks.append(c.tick);
            }
            RunGuard.appendLine(index, source + "," + attempt + "," + levelSeed + ","
                    + (caps.isEmpty() ? "-" : ticks.toString()) + ",END");
        }

        private File binFile(String source, int attempt, int tick) {
            return new File(dir, String.format(Locale.ROOT, "%s_%05d_t%03d.bin", source, attempt, tick));
        }
    }

    private static int parseThreads(String s) {
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("auto")) return ServerProfile.get().maxWorkers();
        return Integer.parseInt(t);
    }

    /** Zero-padded 3-digit scenario id (e.g. 007, 042, 200). */
    public static String sid(int id) { return BuildV5Portfolio.sid(id); }
}
