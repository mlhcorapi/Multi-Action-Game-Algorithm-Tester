package magat.portfolio;

import magat.game.Capture;
import magat.game.Game;
import magat.game.ScenarioSource;
import magat.run.RunGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Builds (or resumes) a portfolio from a game's scenario sources: parallel, restart-safe, and
 * reproducible at any thread count.
 *
 * <p><b>What a portfolio is.</b> For each source, in the order the game declares them, "the first
 * {@code target} valid captures in (attempt, phase) order"; ids are assigned sequentially across
 * sources in that order. That is a property of ordered consumption, not of execution order: each
 * attempt is a self-contained game that depends on its attempt number alone, so which captures it
 * yields does not depend on what ran beside it or when.
 *
 * <p><b>Continuous parallelism.</b> Attempts are kept in flight with a bounded look-ahead and
 * consumed in attempt order, so no worker waits at a barrier while work remains. The look-ahead is
 * sized from the yield observed so far, so a build about to reach its target does not speculatively
 * play hundreds of games it will throw away.
 *
 * <p><b>Restart-safe.</b> Every finished attempt is journalled the moment it completes
 * ({@link CaptureJournal}); a restart replays only the attempts that were still running. The scenario
 * files and the manifest are assembled in one pass at the end, so a partial set of them is never
 * mistaken for a portfolio; the completion marker is written last, atomically.
 *
 * <p><b>Two refusals.</b> A frozen portfolio (one with split files) is never rebuilt in place, and an
 * exception escaping a source is never read as "this attempt yielded nothing": the build stops with
 * the journal intact.
 *
 * <p>The game-specific generation is behind {@link ScenarioSource}.
 */
public final class PortfolioBuilder {

    public static final String COMPLETE_MARKER = "_portfolio_complete.txt";
    /** In-flight attempts per worker: more than one so a slow game never starves the pool. */
    private static final int LOOKAHEAD_PER_THREAD = 2;
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private PortfolioBuilder() {}

    /** What a build did, for the caller's summary. */
    public static final class Result {
        public final boolean complete;
        public final boolean skipped;
        public final int scenarios;
        public final String identity;
        public final Map<String, String> perSource;

        Result(boolean complete, boolean skipped, int scenarios, String identity, Map<String, String> perSource) {
            this.complete = complete;
            this.skipped = skipped;
            this.scenarios = scenarios;
            this.identity = identity;
            this.perSource = perSource;
        }
    }

    /** Parses {@code single=200,dual=200}; sources not named take their default target. */
    public static <S> Map<String, Integer> targets(List<ScenarioSource<S>> sources, String spec) {
        Map<String, Integer> given = new LinkedHashMap<>();
        if (spec != null && !spec.trim().isEmpty()) {
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split("=");
                if (kv.length != 2) throw new IllegalArgumentException("bad target '" + part + "': expected source=count");
                int n = Integer.parseInt(kv[1].trim());
                if (n < 0) throw new IllegalArgumentException("negative target for " + kv[0]);
                given.put(kv[0].trim(), n);
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ScenarioSource<S> s : sources) {
            Integer n = given.remove(s.name());
            out.put(s.name(), n != null ? n : s.defaultTarget());
        }
        if (!given.isEmpty())
            throw new IllegalArgumentException("unknown source(s) " + given.keySet() + "; this game has " + out.keySet());
        return out;
    }

    /** {@code single=200 dual=200}: the form written into the manifest and the marker. */
    public static String targetsText(Map<String, Integer> targets) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** What a portfolio's sources make: the game, its configuration and each source's definition. */
    static <S> String journalKey(Game<S, ?> game, List<ScenarioSource<S>> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("game: ").append(game.id()).append('\n');
        for (Map.Entry<String, String> e : game.configuration().entrySet())
            sb.append("configuration.").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        for (ScenarioSource<S> s : sources)
            sb.append("source.").append(s.name()).append(": ").append(s.definition()).append('\n');
        return sb.toString();
    }

    public static <S, A> Result build(Game<S, A> game, Map<String, Integer> targets, Path outDir, int threads)
            throws IOException, InterruptedException {
        return build(game, game.scenarioSources(), targets, outDir, threads);
    }

    public static <S, A> Result build(Game<S, A> game, List<ScenarioSource<S>> sources, Map<String, Integer> targets,
                                      Path outDir, int threads) throws IOException, InterruptedException {
        threads = Math.max(1, threads);
        for (ScenarioSource<S> s : sources) {
            if (!SOURCE_NAME.matcher(s.name()).matches())
                throw new IllegalArgumentException("source name '" + s.name() + "' must be lower case letters, digits and dashes");
            if (!targets.containsKey(s.name()))
                throw new IllegalArgumentException("no target for source '" + s.name() + "'");
        }
        Path scenariosDir = outDir.resolve(Portfolio.SCENARIOS_DIR);
        Path manifestFile = outDir.resolve(Manifest.FILE);
        Path marker = outDir.resolve(COMPLETE_MARKER);
        String key = journalKey(game, sources);
        String targetsText = targetsText(targets);
        String wantMarker = key + "targets: " + targetsText + "\n";
        int total = 0;
        for (int n : targets.values()) total += n;

        // A complete portfolio for exactly these sources and targets: nothing to do.
        if (Files.isRegularFile(marker) && Files.isRegularFile(manifestFile)) {
            String have = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
            try {
                Manifest m = Manifest.read(outDir);
                if (have.equals(wantMarker) && m.size() == total) {
                    System.out.println("[portfolio] already complete (" + targetsText + ", " + m.size()
                            + " scenarios, identity " + Identity.shortForm(m.identity()) + ") - nothing to build. Delete "
                            + COMPLETE_MARKER + " to force a rebuild.");
                    return new Result(true, true, m.size(), m.identity(), Collections.emptyMap());
                }
            } catch (IOException e) {
                // unreadable: fall through to a rebuild
            }
        }
        if (Files.exists(outDir.resolve(Split.DEV_FILE)) || Files.exists(outDir.resolve(Split.HOLDOUT_FILE)))
            throw new IllegalStateException("portfolio " + outDir + " is frozen (it has split files) and does not match "
                    + "this build; a frozen portfolio is never rebuilt in place. Build into a new directory.");

        if (!RunGuard.acquireLock(outDir, "portfolio-build"))
            throw new IllegalStateException("another build is writing to " + outDir);
        try {
            RunGuard.deleteTree(scenariosDir);
            Files.deleteIfExists(manifestFile);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(outDir.resolve(Identity.FILE));
            CaptureJournal<S> journal = CaptureJournal.open(outDir.resolve(CaptureJournal.DIR), key, game);
            System.out.println("[portfolio] game " + game.id() + ", targets " + targetsText + ", " + threads + " thread(s), "
                    + (journal.attemptsOnDisk() > 0
                        ? "RESUMING (" + journal.attemptsOnDisk() + " source game(s) already played)"
                        : "fresh build"));
            for (ScenarioSource<S> s : sources)
                System.out.println("[portfolio]   source " + s.name() + ": " + s.definition());

            long t0 = System.currentTimeMillis();
            Map<String, List<Capture<S>>> captured = new LinkedHashMap<>();
            Map<String, String> perSource = new LinkedHashMap<>();
            for (ScenarioSource<S> s : sources) {
                long ts = System.currentTimeMillis();
                Collected<S> c = collect(s, targets.get(s.name()), threads, journal);
                captured.put(s.name(), c.captures);
                perSource.put(s.name(), String.format(Locale.ROOT, "%d/%d through attempt %d (played %d, replayed %d) in %.1fs",
                        c.captures.size(), targets.get(s.name()), c.lastAttempt, c.played, c.replayed,
                        (System.currentTimeMillis() - ts) / 1000.0));
                if (RunGuard.stopRequested()) {
                    System.out.println("[portfolio] stopped during source " + s.name() + " - journal kept; re-run to resume.");
                    return new Result(false, false, 0, null, perSource);
                }
            }

            // Assemble single-threaded in (source, attempt, phase) order: deterministic ids.
            Files.createDirectories(scenariosDir);
            List<Manifest.Row> rows = new ArrayList<>();
            int id = 0;
            for (ScenarioSource<S> s : sources) {
                for (Capture<S> c : captured.get(s.name())) {
                    id++;
                    // Serialize before describing: the bin is the state as captured.
                    RunGuard.atomicWrite(Portfolio.binPath(outDir, id), game.serialize(c.state));
                    rows.add(new Manifest.Row(id, s.name(), c.attempt, c.seed, c.phase,
                            game.branchingFactor(c.state), game.describe(c.state)));
                }
            }
            Map<String, String> definitions = new LinkedHashMap<>();
            for (ScenarioSource<S> s : sources) definitions.put(s.name(), s.definition());
            RunGuard.atomicWrite(manifestFile, Manifest.render(game.id(), game.configuration(), definitions, targetsText, rows));
            String identity = Identity.of(rows);

            boolean complete = id == total;
            if (complete) {
                RunGuard.atomicWrite(outDir.resolve(Identity.FILE), identity + "\n");
                RunGuard.atomicWrite(marker, wantMarker);
                journal.discard();
            } else {
                System.out.println("[portfolio] WARN: " + id + " scenarios built, " + total + " wanted: no completion marker "
                        + "written, journal kept. A source ran out of attempts; see its line above.");
            }
            for (Map.Entry<String, String> e : perSource.entrySet())
                System.out.println("[portfolio]   " + e.getKey() + ": " + e.getValue());
            System.out.println(String.format(Locale.ROOT, "[portfolio] %s: %d scenarios in %.1fs, identity %s -> %s",
                    complete ? "complete" : "INCOMPLETE", id, (System.currentTimeMillis() - t0) / 1000.0, identity, outDir));
            return new Result(complete, false, id, identity, perSource);
        } finally {
            RunGuard.releaseLock(outDir);
        }
    }

    private static final class Collected<S> {
        final List<Capture<S>> captures = new ArrayList<>();
        int played, replayed, lastAttempt;
    }

    /**
     * Plays attempts of one source until {@code target} valid captures are collected or its attempt
     * cap is reached, consuming results in attempt order. Journalled attempts are read from disk.
     */
    private static <S> Collected<S> collect(ScenarioSource<S> source, int target, int threads, CaptureJournal<S> journal)
            throws InterruptedException {
        Collected<S> out = new Collected<>();
        if (target <= 0) return out;
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "portfolio-" + source.name());
            t.setDaemon(true);
            return t;
        }));
        int hardLookahead = Math.max(threads, threads * LOOKAHEAD_PER_THREAD);
        Deque<Pending<S>> inFlight = new ArrayDeque<>();
        int next = 1, consumed = 0, capsSeen = 0, logged = 0;
        int phases = Math.max(1, source.phases().length);
        try {
            while (out.captures.size() < target && !RunGuard.stopRequested()) {
                // Attempts beyond the number still needed are played and thrown away. Before anything
                // is observed assume every phase captures; afterwards use the yield actually seen.
                double yield = consumed > 0 ? capsSeen / (double) consumed : phases;
                int needed = (int) Math.ceil(1.5 * (target - out.captures.size()) / Math.max(0.05, yield));
                int lookahead = Math.max(1, Math.min(hardLookahead, needed));

                while (inFlight.size() < lookahead && next <= source.maxAttempts()) {
                    final int attempt = next++;
                    List<Capture<S>> cached = journal.read(source.name(), attempt);
                    if (cached != null) {
                        inFlight.add(Pending.resolved(attempt, cached));
                        out.replayed++;
                    } else {
                        out.played++;
                        inFlight.add(Pending.submitted(attempt, pool.submit(() -> {
                            if (RunGuard.stopRequested()) return null;
                            List<Capture<S>> caps = source.play(attempt);
                            for (Capture<S> c : caps)
                                if (c.attempt != attempt)
                                    throw new IllegalStateException("attempt " + attempt + " returned a capture labelled attempt " + c.attempt);
                            journal.write(source.name(), attempt, caps);
                            return caps;
                        })));
                    }
                }
                if (inFlight.isEmpty()) break;              // attempt cap reached and pipeline drained

                Pending<S> head = inFlight.poll();
                List<Capture<S>> caps;
                try {
                    caps = head.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("source " + source.name() + " attempt " + head.attempt
                            + " failed: " + e.getCause() + ". A source must swallow its own engine failures; this one "
                            + "escaped, so the build stops rather than guess what the attempt would have yielded. "
                            + "The journal keeps every finished attempt.", e.getCause());
                }
                if (caps == null) break;                    // stop requested inside the task
                consumed++;
                out.lastAttempt = head.attempt;
                capsSeen += caps.size();
                for (Capture<S> c : caps) if (out.captures.size() < target) out.captures.add(c);

                if (consumed - logged >= 10 || out.captures.size() >= target) {
                    logged = consumed;
                    System.out.println(String.format(Locale.ROOT,
                            "[portfolio] %s: through attempt %d -> %d/%d valid  (played %d, replayed %d, in flight %d, yield %.2f/attempt)",
                            source.name(), head.attempt, out.captures.size(), target, out.played, out.replayed,
                            inFlight.size(), capsSeen / (double) consumed));
                }
            }
        } finally {
            // Speculative attempts past the target are irrelevant to the output. Unstarted ones are
            // dropped; running ones finish (and are journalled) before the next source starts, because
            // a source may set engine globals its games read.
            for (Pending<S> p : inFlight) p.cancel();
            RunGuard.shutdownAndWait(pool, 7, TimeUnit.DAYS);
        }
        if (out.captures.size() < target && !RunGuard.stopRequested())
            System.out.println("[portfolio] WARN: only " + out.captures.size() + "/" + target + " valid " + source.name()
                    + " captures after " + source.maxAttempts() + " attempts.");
        return out;
    }

    /** An attempt whose captures are either already known or still being computed. */
    private static final class Pending<S> {
        final int attempt;
        final Future<List<Capture<S>>> future;
        final List<Capture<S>> value;

        private Pending(int attempt, Future<List<Capture<S>>> future, List<Capture<S>> value) {
            this.attempt = attempt;
            this.future = future;
            this.value = value;
        }

        static <S> Pending<S> resolved(int attempt, List<Capture<S>> caps) { return new Pending<>(attempt, null, caps); }
        static <S> Pending<S> submitted(int attempt, Future<List<Capture<S>>> f) { return new Pending<>(attempt, f, null); }

        List<Capture<S>> get() throws ExecutionException, InterruptedException {
            return future != null ? future.get() : value;
        }

        void cancel() { if (future != null) future.cancel(false); }
    }
}
