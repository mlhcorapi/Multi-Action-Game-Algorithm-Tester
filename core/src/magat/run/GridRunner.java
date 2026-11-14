package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.calibrate.ThreadSweep;
import magat.game.Game;
import magat.game.GameModule;
import magat.game.Scenario;
import magat.machine.MachineProfile;
import magat.meter.Meter;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Identity;
import magat.portfolio.Portfolio;
import magat.portfolio.Split;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs an experiment: every {@code (arm, budget)} leaf over the split's scenarios and visits, levels in
 * the order the spec lists them (a priority order: leaves are checkpointed independently, so a stopped
 * run keeps whatever came first).
 *
 * <p>A per-leaf checkpoint key
 * {@code arm=<id>;budget=<units>;split=<split>}; resume by re-running the same command (finished leaves
 * are skipped, a partial leaf continues per scenario and visit after {@link Leaf#compact}); per-level
 * thread counts; the {@link LoadGovernor}; one lock per output tree; a leaf marked complete only when
 * every turn produced a row.
 *
 * <p>Three guards:
 * <ul>
 *   <li><b>Currency lock.</b> {@code _run.json} records the weights file's hash and every leaf records
 *       the weights it ran under. A resume under another file is allowed only if every leaf's own price
 *       is unchanged, or the leaves whose price changed are named in {@code --retire-leaf} (their
 *       checkpoint lines and directories are removed, and the retirement is logged). A leaf can never
 *       hold turns priced in two currencies.</li>
 *   <li><b>Identical-output assertion.</b> After every level, two arms whose turns agree on value, counts,
 *       nodes and unique sequences on every turn are reported as a bug and the run stops.</li>
 *   <li><b>Portfolio and configuration lock</b> ({@link Provenance}).</li>
 * </ul>
 */
public final class GridRunner {

    public static final String CHECKPOINT = "_completed.txt";

    /** Exit codes: 0 complete; 1 stopped or a turn failed; 2 invalid spec; 3 lock held; 4 tree mismatch; 5 identical arms. */
    public static final int OK = 0, INCOMPLETE = 1, INVALID = 2, LOCKED = 3, MISMATCH = 4, IDENTICAL = 5;

    private GridRunner() {}

    public static final class Options {
        public Split.Half split;
        /** The run root; the split's tree is {@code <out>/<split>/}. */
        public Path out;
        public int threads;
        public Map<Long, Integer> threadsAt = new LinkedHashMap<>();
        public Path threadsFile;
        public double machineFactor = 1.0;
        public Set<String> retire = new LinkedHashSet<>();
        public boolean toy;
        public List<String> args = new ArrayList<>();
        /** The audit's warning, on one thread: a leaf whose priced work accounts for less than (1 - this) of its plan time. */
        public double auditWarn = 0.10;
        /** A run's split tree whose leaves this run takes instead of playing them, where it may ({@link Reuse}); null plays every leaf. */
        public Path reuse;
        /** How many of a leaf's turns this machine re-plays, field for field, before taking it. */
        public int reuseReplay = 2;
    }

    public static <S, A> int run(GameModule<S, A> module, Experiment x, Portfolio<S> portfolio, Weights weights, Options o)
            throws IOException, InterruptedException {
        Game<S, A> game = module.game();
        Split.Half half = o.split != null ? o.split : Split.Half.parse(x.split == null ? "all" : x.split);

        // ---- the spec, before anything is touched
        System.out.println("# experiment " + x.name + " (" + (x.source == null ? "inline" : x.source) + ", sha256 " + Identity.shortForm(x.sha256) + ")");
        Validator.Report v = Validator.check(x, game.id(), module.agents(), half, weights, o.toy);
        if (v.ok()) Validator.checkSelection(x, portfolio.scenarios(), portfolio.dir(), half, v);
        v.print();
        if (!v.ok()) {
            System.out.println("VALIDATION: FAIL (" + v.errors.size() + " error(s)); nothing was run");
            return INVALID;
        }
        if (weights.portfolioIdentity() != null && !weights.portfolioIdentity().equals(portfolio.identity()))
            System.out.println("  [warn]  the weights were measured on portfolio " + Identity.shortForm(weights.portfolioIdentity())
                    + "; this run reads " + Identity.shortForm(portfolio.identity()));

        return runTree(module, x, portfolio, weights, o, game, half);
    }

    private static <S, A> int runTree(GameModule<S, A> module, Experiment x, Portfolio<S> portfolio, Weights weights, Options o,
                                      Game<S, A> game, Split.Half half) throws IOException, InterruptedException {
        // ---- the tree
        Path root = o.out.resolve(half.key()).toAbsolutePath();
        Files.createDirectories(root);
        if (!RunGuard.acquireLock(root, "run " + x.name)) return LOCKED;
        try {
            Split.claimOutputRoot(root, half);
            Provenance prov = Provenance.open(root);
            List<String> mismatch = prov.mismatches(game.id(), game.configuration(), portfolio.identity(), half.key(), x.selection());
            if (!mismatch.isEmpty()) {
                System.out.println("ABORT: " + root + " was produced under different conditions:");
                for (String m : mismatch) System.out.println("  " + m);
                System.out.println("Results written here would not be comparable with the ones already in it. Use a new --out.");
                return MISMATCH;
            }
            Path checkpoint = root.resolve(CHECKPOINT);
            String weightsSha = Identity.sha256(weights.source());
            if (!prov.fresh && prov.weightsSha() != null && !prov.weightsSha().equals(weightsSha)) {
                List<String> changed = repriced(root, weights, module);
                List<String> notRetired = new ArrayList<>();
                for (String leaf : changed) if (!o.retire.contains(leaf)) notRetired.add(leaf);
                System.out.println("# the weights file differs from the one this tree was started with ("
                        + Identity.shortForm(prov.weightsSha()) + " -> " + Identity.shortForm(weightsSha) + "); "
                        + changed.size() + " leaf/leaves would change price: " + changed);
                if (!notRetired.isEmpty()) {
                    System.out.println("ABORT (currency lock): these leaves hold turns priced in the old currency and would continue in "
                            + "the new one: " + notRetired + ". Re-run with --retire-leaf " + String.join(",", notRetired)
                            + " to discard and re-run them, or use the weights the tree was started with.");
                    return MISMATCH;
                }
            }
            for (String leaf : o.retire) retire(root, checkpoint, leaf, half, prov, weightsSha);

            recordProvenance(prov, x, game, portfolio, weights, weightsSha, half, o);
            prov.save();

            // ---- the scenarios
            List<Scenario<S>> scenarios = x.select(Split.apply(portfolio.scenarios(), portfolio.dir(), half));

            Weights previous = Meter.installed();
            Meter.install(weights);
            Meter.allowUnpriced(false);
            try {
                Map<Long, Integer> sweep = o.threadsFile != null && Files.isRegularFile(o.threadsFile) ? ThreadSweep.read(o.threadsFile) : null;
                banner(x, half, root, scenarios.size(), weights, o, sweep);
                Reuse reuse = null;
                if (o.reuse != null) {
                    List<String> why = new ArrayList<>();
                    reuse = Reuse.open(o.reuse, portfolio.identity(), game.configuration(), why);
                    System.out.println(reuse != null
                            ? "[reuse] from " + reuse.source() + ", played on " + MachineProfile.modelOf(reuse.machine()) + ": each leaf it holds "
                              + "whole, at this run's prices, is taken after " + o.reuseReplay + " of its turns re-play here field for field"
                            : "[reuse] not from " + o.reuse + ": " + String.join("; ", why) + ". Every leaf is played");
                }
                Set<String> done = readCheckpoint(checkpoint);
                for (Experiment.Level level : x.levels) {
                    int threads = threadsFor(level.budget, o, sweep);
                    for (String id : x.armsAt(level)) {
                        if (RunGuard.stopRequested()) return finish(INCOMPLETE, "stopped");
                        String key = checkpointKey(id, level.budget, half);
                        if (done.contains(key)) {
                            System.out.println("[run] SKIP (done) " + key);
                            continue;
                        }
                        if (reuse != null) {
                            AgentFactory<S, A> arm = module.agents().get(id);
                            String not = reuse.take(game, portfolio, arm, level, scenarios, Leaf.dir(root, id, level.budget),
                                    baseMeta(arm, level.budget, weights), o.reuseReplay, threads);
                            if (not == null) {
                                RunGuard.appendLine(checkpoint, key);
                                done.add(key);
                                System.out.println("[run] REUSED " + key + " from " + Leaf.dir(reuse.source(), id, level.budget));
                                continue;
                            }
                            System.out.println("[reuse] " + id + "@" + level.budget + " is played: " + not);
                        }
                        boolean complete = runLeaf(game, module.agents().get(id), level, scenarios, root, threads, weights, o.auditWarn);
                        if (!complete) return finish(INCOMPLETE, "leaf " + id + "@" + level.budget + " incomplete; re-run the same command to continue it");
                        RunGuard.appendLine(checkpoint, key);
                        done.add(key);
                        System.out.println("[run] DONE " + key);
                    }
                    String twins = identicalArms(root, x.armsAt(level), level.budget);
                    if (twins != null) {
                        System.out.println("BUG: " + twins);
                        System.out.println("Two arms producing identical output is a bug, not thin data (principle 12): one is a renamed "
                                + "duplicate of the other, or a mechanism is not wired in. The run stops here.");
                        return finish(IDENTICAL, "identical arms");
                    }
                }
            } finally {
                Meter.install(previous);
            }
            return finish(OK, null);
        } finally {
            RunGuard.releaseLock(root);
        }
    }

    private static int finish(int code, String why) {
        System.out.println(code == OK ? "RUN: COMPLETE" : "RUN: " + (code == IDENTICAL ? "STOPPED (identical arms)" : "INCOMPLETE") + (why == null ? "" : " - " + why));
        return code;
    }

    public static String checkpointKey(String arm, long budget, Split.Half half) {
        return "arm=" + arm + ";budget=" + budget + ";split=" + half.key();
    }

    // ------------------------------------------------------------------ one leaf

    private static <S, A> boolean runLeaf(Game<S, A> game, AgentFactory<S, A> arm, Experiment.Level level, List<Scenario<S>> scenarios,
                                          Path root, int threads, Weights weights, double auditWarn) throws IOException, InterruptedException {
        Path dir = Leaf.dir(root, arm.id(), level.budget);
        Set<String> done = new LinkedHashSet<>();
        Leaf leaf = Leaf.open(dir, done);
        int total = scenarios.size() * level.visits;
        String profile = Profile.key(arm.id(), level.budget);
        double[] w = arm.priced() ? weights.profile(profile) : weights.global();
        // The commit that plays this leaf's turns; a leaf continued from another commit names the earlier ones.
        JsonObject magat = thisMagat();
        String commit = magat.getString("commit", "unknown");
        List<String> earlier = earlierCommits(dir, done.size(), commit);
        JsonObject meta = baseMeta(arm, level.budget, weights).add("threads", threads).add("host", host()).add("commit", commit);
        if (magat.get("notebook") != null) meta.add("notebook", magat.get("notebook"));
        if (!earlier.isEmpty()) meta.add("earlierCommits", Json.array(earlier.toArray(new String[0])));
        meta.add("started", Instant.now().toString()).add("turnsTotal", total).add("turnsDoneAtStart", done.size()).add("status", "running");
        writeMeta(dir, meta);
        System.out.println(String.format(Locale.ROOT, "[run] RUN %s @ %d: %d turns (%d already done) on %d thread(s)",
                arm.id(), level.budget, total - done.size(), done.size(), threads));
        if (!earlier.isEmpty())
            System.out.println("  [warn]  " + arm.id() + "@" + level.budget + ": its " + done.size() + " turn(s) so far were played by MAGAT "
                    + String.join(", ", earlier) + "; this invocation, " + commit + ", plays the rest");

        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "run-" + arm.id());
            t.setDaemon(true);
            return t;
        }));
        LoadGovernor governor = new LoadGovernor(threads, 5_000L);
        governor.start();
        AtomicInteger finished = new AtomicInteger(done.size()), failures = new AtomicInteger(), skipped = new AtomicInteger();
        java.util.concurrent.atomic.DoubleAdder pricedUnits = new java.util.concurrent.atomic.DoubleAdder();
        java.util.concurrent.atomic.LongAdder planMillis = new java.util.concurrent.atomic.LongAdder();
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            for (Scenario<S> sc : scenarios)
                for (int visit = 0; visit < level.visits; visit++) {
                    if (done.contains(Leaf.key(sc.id, visit))) continue;
                    final int vv = visit;
                    futures.add(pool.submit(() -> {
                        if (RunGuard.stopRequested()) { skipped.incrementAndGet(); return; }
                        try {
                            governor.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            skipped.incrementAndGet();
                            return;
                        }
                        try {
                            TurnRecord r = arm.priced() ? Episode.run(game, sc, vv, arm, level.budget)
                                    : Episode.run(game, sc, vv, arm, level.budget, weights.global(), "global");
                            leaf.append(sc, vv, arm.id(), level.budget, r);
                            pricedUnits.add(r.units + r.initUnits);
                            planMillis.add(r.planMillis);
                            int c = finished.incrementAndGet();
                            if (c % 100 == 0 || c == total)
                                System.out.println(String.format(Locale.ROOT, "    %s@%d: %d/%d   [%s]", arm.id(), level.budget, c, total, governor.status()));
                        } catch (Throwable t) {
                            if (failures.incrementAndGet() <= 5)
                                System.err.println("[run] turn failed " + arm.id() + "@" + level.budget + " scenario " + sc.id + " visit " + vv + ": " + t);
                        } finally {
                            governor.release();
                        }
                    }));
                }
            for (Future<?> f : futures) {
                try { f.get(); } catch (java.util.concurrent.ExecutionException e) { failures.incrementAndGet(); }
            }
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1, TimeUnit.HOURS);
        }
        int rows = Leaf.rows(dir);
        boolean complete = failures.get() == 0 && skipped.get() == 0 && !RunGuard.stopRequested() && rows == total;
        meta.set("finished", Instant.now().toString()).set("turnsDone", rows).set("failures", failures.get())
            .set("seconds", (System.currentTimeMillis() - t0) / 1000.0).set("governor", governor.status())
            .set("status", complete ? "complete" : "incomplete");
        // An arm that is not priced spends nothing its units account for, so it has nothing to audit.
        JsonObject audit = arm.priced() ? audit(weights, pricedUnits.sum(), planMillis.sum(), threads) : null;
        if (audit != null) {
            meta.set("audit", audit);
            String line = auditLine(arm.id() + "@" + level.budget, audit, auditWarn);
            if (line != null) System.out.println(line);
        }
        writeMeta(dir, meta);
        if (failures.get() > 0) System.out.println("[run] " + failures.get() + " turn(s) of " + arm.id() + "@" + level.budget + " failed; the leaf is not complete");
        return complete;
    }

    /**
     * The run's side of the audit: the plan time the priced work predicts (units times the currency's
     * reference-advance nanoseconds, the definition of a unit) against the plan time measured, over the
     * turns this invocation ran. Under a version-2 currency what is left is undeclared work; under version
     * 1 the arms' own operations too. On another machine, or many threads, it also holds the speed
     * difference, which the record says.
     */
    static JsonObject audit(Weights weights, double units, long measuredMillis, int threads) {
        double refNs = weights.referenceAdvanceNanos();
        if (!(refNs > 0) || measuredMillis <= 0) return null;
        double predictedMs = units * refNs / 1e6;
        // By CPU model: a calibration pins its JVMs to a few cores, so the file's description counts those.
        String machine = weights.header().get("machine");
        return new JsonObject().add("unpricedShare", 1 - predictedMs / measuredMillis).add("predictedMs", predictedMs)
                .add("measuredMs", measuredMillis).add("threads", threads)
                .add("sameMachine", machine != null && MachineProfile.modelOf(machine).equals(MachineProfile.get().cpuModel()))
                .add("basis", "(units + initialization units) x referenceAdvanceNanos " + refNs + " ns, against the turns' plan milliseconds");
    }

    /**
     * What the run says of a leaf's audit, or null. On one thread a share above {@code warn} is a warning: work the
     * currency does not price. On many threads the turns share the machine's caches, memory and clock, which
     * slows each one, so the share holds that contention too: one line of information, the time the turns took
     * against the time their priced work predicts.
     */
    static String auditLine(String cell, JsonObject audit, double warn) {
        double share = audit.getDouble("unpricedShare", 0);
        int threads = audit.getInt("threads", 1);
        boolean same = audit.getBoolean("sameMachine", false);
        String elsewhere = same ? "" : "; the currency was calibrated on another machine, so this holds the speed difference too";
        if (threads > 1)
            return String.format(Locale.ROOT, "[run] audit: %s took %.2fx the time its priced work predicts, on %d threads: the turns "
                    + "running at once slow each other, so the calibration's idle audit is the clean measure%s", cell,
                    audit.getDouble("measuredMs", 0) / audit.getDouble("predictedMs", 1), threads, elsewhere);
        if (share <= warn) return null;
        return String.format(Locale.ROOT, "[run] audit: %.1f%% of %s's plan time is not accounted for by the work its currency prices "
                + "(threshold %.0f%%)%s", 100 * share, cell, 100 * warn, elsewhere);
    }

    /** The prices a leaf runs under: its profile's every operation, or the global ones for an arm that is not priced. */
    static Map<String, Double> pricesOf(Weights weights, String profile) {
        if (profile != null && weights.prices(profile) != null) return weights.prices(profile);
        Map<String, Double> g = new LinkedHashMap<>();
        double[] w = weights.global();
        for (int i = 0; i < 3; i++) g.put(Meter.FORWARD_MODEL_OPS.get(i), w[i]);
        g.putAll(weights.globalOperations());
        return g;
    }

    /** This MAGAT as the launcher names it. The commit is -dirty for code changes only; a notebook saved with its outputs is named apart. */
    static JsonObject thisMagat() {
        JsonObject m = new JsonObject().add("version", System.getProperty("magat.version", "unknown"))
                .add("commit", System.getProperty("magat.git.commit", "unknown"));
        if (System.getProperty("magat.git.notebook") != null) m.add("notebook", System.getProperty("magat.git.notebook"));
        return m;
    }

    /** The commits other than {@code commit} that played the turns a leaf already holds, from its previous {@code _leaf.json}. */
    static List<String> earlierCommits(Path dir, int turnsHeld, String commit) throws IOException {
        Path f = dir.resolve(Leaf.META);
        if (turnsHeld == 0 || !Files.isRegularFile(f)) return new ArrayList<>();
        JsonObject was = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).asObject();
        Set<String> seen = new LinkedHashSet<>();
        JsonValue before = was.get("earlierCommits");
        if (before != null && before.isArray()) for (JsonValue v : before.asArray()) seen.add(v.asString());
        seen.add(was.getString("commit", "unknown"));      // a leaf from before commits were recorded
        seen.remove(commit);
        return new ArrayList<>(seen);
    }

    /** What a leaf's {@code _leaf.json} says of its arm and prices, whoever played it. */
    static JsonObject baseMeta(AgentFactory<?, ?> arm, long budget, Weights weights) {
        String profile = Profile.key(arm.id(), budget);
        double[] w = arm.priced() ? weights.profile(profile) : weights.global();
        JsonObject prices = new JsonObject();
        for (Map.Entry<String, Double> e : pricesOf(weights, arm.priced() ? profile : null).entrySet()) prices.add(e.getKey(), e.getValue());
        return new JsonObject().add("arm", arm.id()).add("budget", budget)
                .add("profile", arm.priced() ? profile : "global (arm is not priced)")
                .add("weights", new JsonArray().add(w[0]).add(w[1]).add(w[2]))
                .add("currencyVersion", weights.version()).add("prices", prices);
    }

    static void writeMeta(Path dir, JsonObject meta) throws IOException {
        RunGuard.atomicWrite(dir.resolve(Leaf.META), meta.toString(com.eclipsesource.json.WriterConfig.PRETTY_PRINT) + "\n");
    }

    // ------------------------------------------------------------------ the guards

    /** Arms of one level whose complete leaves agree on every turn; null when there are none. */
    static String identicalArms(Path root, List<String> arms, long budget) throws IOException {
        Map<String, Map<String, String>> sig = new LinkedHashMap<>();
        for (String a : arms) {
            Map<String, String> s = Leaf.signatures(Leaf.dir(root, a, budget));
            if (!s.isEmpty()) sig.put(a, s);
        }
        List<String> ids = new ArrayList<>(sig.keySet());
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++)
                if (sig.get(ids.get(i)).equals(sig.get(ids.get(j))))
                    return "arms " + ids.get(i) + " and " + ids.get(j) + " produced identical output (value, counts, nodes, unique) on every one of "
                            + sig.get(ids.get(i)).size() + " turns at " + budget;
        return null;
    }

    /** Leaves on disk whose recorded weights differ from what {@code weights} prices them at now. */
    private static <S, A> List<String> repriced(Path root, Weights weights, GameModule<S, A> module) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            for (Path armDir : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
                try (java.util.stream.Stream<Path> t = Files.list(armDir)) {
                    for (Path leafDir : (Iterable<Path>) t.filter(Files::isDirectory)::iterator) {
                        Path meta = leafDir.resolve(Leaf.META);
                        if (!Files.isRegularFile(meta)) continue;
                        JsonObject m = Json.parse(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8)).asObject();
                        String arm = m.getString("arm", ""), profile = Profile.key(arm, m.getLong("budget", 0));
                        boolean priced = module.agents().has(arm) && module.agents().get(arm).priced();
                        if (!samePrices(m, priced ? profile : null, weights)) out.add(arm + "@" + m.getLong("budget", 0));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether a leaf's recorded prices are what {@code weights} prices it at now: the forward model's three,
     * and every operation either side prices.
     */
    static boolean samePrices(JsonObject meta, String profile, Weights weights) {
        double[] now = profile != null ? weights.profile(profile) : weights.global();
        if (now == null) return false;
        JsonArray was = meta.get("weights").asArray();
        for (int i = 0; i < 3; i++) if (Math.abs(was.get(i).asDouble() - now[i]) >= 1e-12) return false;
        Map<String, Double> nowAll = pricesOf(weights, profile);
        JsonValue recorded = meta.get("prices");
        Map<String, Double> wasAll = new LinkedHashMap<>();
        if (recorded != null && recorded.isObject())
            for (JsonObject.Member mm : recorded.asObject()) wasAll.put(mm.getName(), mm.getValue().asDouble());
        Set<String> ops = new LinkedHashSet<>(nowAll.keySet());
        ops.addAll(wasAll.keySet());
        ops.removeAll(Meter.FORWARD_MODEL_OPS);
        for (String op : ops) {
            Double a = wasAll.get(op), b = nowAll.get(op);
            if (a == null || b == null || Math.abs(a - b) >= 1e-12) return false;
        }
        return true;
    }

    private static void retire(Path root, Path checkpoint, String leaf, Split.Half half, Provenance prov, String weightsSha) throws IOException {
        int at = leaf.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("--retire-leaf takes arm@budget, not '" + leaf + "'");
        String arm = leaf.substring(0, at);
        long budget = Long.parseLong(leaf.substring(at + 1));
        Path dir = Leaf.dir(root, arm, budget);
        String key = checkpointKey(arm, budget, half);
        int rows = Files.isDirectory(dir) ? Leaf.rows(dir) : 0;
        if (Files.isRegularFile(checkpoint)) {
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(checkpoint, StandardCharsets.UTF_8)) if (!line.trim().equals(key)) kept.add(line);
            RunGuard.atomicWrite(checkpoint, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
        }
        RunGuard.deleteTree(dir);
        prov.append("retired", new JsonObject().add("date", Instant.now().toString()).add("leaf", leaf).add("turnsDiscarded", rows)
                .add("weightsSha256", weightsSha));
        System.out.println("[run] RETIRED " + leaf + ": " + rows + " turn(s) discarded; it will be re-run");
    }

    // ------------------------------------------------------------------ provenance, threads, banner

    private static <S, A> void recordProvenance(Provenance prov, Experiment x, Game<S, A> game, Portfolio<S> portfolio, Weights weights,
                                                String weightsSha, Split.Half half, Options o) throws IOException {
        JsonObject cfg = new JsonObject();
        for (Map.Entry<String, String> e : game.configuration().entrySet()) cfg.add(e.getKey(), e.getValue());
        // The tree's MAGAT is the one that started it; every invocation, and every leaf, records its own.
        JsonObject magat = thisMagat();
        String commit = magat.getString("commit", "unknown");
        JsonValue had = prov.json.get("magat");
        if (prov.fresh || had == null || !had.isObject()) prov.set("magat", magat);
        else if (!commit.equals(had.asObject().getString("commit", "unknown")))
            System.out.println("  [warn]  this tree was started by MAGAT " + had.asObject().getString("commit", "unknown")
                    + "; this invocation is " + commit + ". The turns it plays come from this code: every invocation and every "
                    + "leaf records the commit that played it");
        if (prov.fresh) {
            prov.set("game", new JsonObject().add("id", game.id()).add("configuration", cfg));
            prov.set("portfolio", new JsonObject().add("dir", portfolio.dir().toAbsolutePath().toString()).add("identity", portfolio.identity()));
            prov.set("split", Json.value(half.key()));
            prov.set("selection", Json.value(x.selection()));
        }
        JsonObject header = new JsonObject();
        for (Map.Entry<String, String> e : weights.header().entrySet()) header.add(e.getKey(), e.getValue());
        prov.set("weights", new JsonObject().add("path", weights.source().toAbsolutePath().toString()).add("sha256", weightsSha)
                .add("reference", String.valueOf(weights.reference())).add("header", header));
        prov.set("experiment", new JsonObject().add("name", x.name).add("path", x.source == null ? "" : x.source.toAbsolutePath().toString())
                .add("sha256", x.sha256));
        if (o.toy) prov.set("toy", Json.value(true));
        JsonObject threads = new JsonObject();
        Map<Long, Integer> sweep = o.threadsFile != null && Files.isRegularFile(o.threadsFile) ? ThreadSweep.read(o.threadsFile) : null;
        for (Experiment.Level l : x.levels) threads.add(String.valueOf(l.budget), threadsFor(l.budget, o, sweep));
        JsonObject invocation = new JsonObject().add("date", Instant.now().toString()).add("commit", commit);
        if (magat.get("notebook") != null) invocation.add("notebook", magat.get("notebook"));
        prov.append("invocations", invocation.add("host", host())
                .add("machine", MachineProfile.get().summary()).add("java", System.getProperty("java.version"))
                .add("experimentSha256", x.sha256).add("weightsSha256", weightsSha).add("threads", threads)
                .add("reuse", o.reuse == null ? "" : o.reuse.toAbsolutePath().toString())
                .add("args", String.join(" ", o.args)));
    }

    static int threadsFor(long budget, Options o, Map<Long, Integer> sweep) {
        Integer at = o.threadsAt.get(budget);
        if (at != null) return Math.max(1, at);
        if (o.threads > 0) return o.threads;
        if (sweep != null && sweep.get(budget) != null) return Math.max(1, sweep.get(budget));
        return MachineProfile.get().maxWorkers();
    }

    private static void banner(Experiment x, Split.Half half, Path root, int scenarios, Weights weights, Options o, Map<Long, Integer> sweep) {
        System.out.println("############################################################");
        System.out.println("# run " + x.name + "   split " + half.key() + "   " + scenarios + " scenarios"
                + (x.selects() ? " (" + x.selection() + ")" : "") + "   -> " + root);
        System.out.println("# weights " + weights.summary());
        for (String line : estimate(x, scenarios, weights, o, sweep)) System.out.println("#" + line);
        MachineProfile.get().printBanner("run " + x.name);
    }

    /**
     * The grid's size and how long it should take: one line per level, then the total. A unit is calibrated to
     * wall-clock, so a turn of B units costs about B reference advances of the machine the currency was measured
     * on; on another machine the hours are that machine's, which the last line says. Nothing decides on it.
     */
    public static List<String> estimate(Experiment x, int scenarios, Weights weights, Options o, Map<Long, Integer> sweep) {
        List<String> out = new ArrayList<>();
        double refNs = weights.referenceAdvanceNanos(), hours = 0;
        long turns = 0;
        for (Experiment.Level l : x.levels) {
            if (l.budget <= 0 || l.visits < 1) continue;
            long t = (long) x.armsAt(l).size() * scenarios * l.visits;
            turns += t;
            hours += t * l.budget * refNs * 1e-9 * o.machineFactor / 3600.0 / threadsFor(l.budget, o, sweep);
            out.add(String.format(Locale.ROOT, "   level %7d: %2d arm(s) x %d scenarios x %d visit(s) on %d thread(s)",
                    l.budget, x.armsAt(l).size(), scenarios, l.visits, threadsFor(l.budget, o, sweep)));
        }
        out.add(String.format(Locale.ROOT, " %,d turns; estimate %s (budget x %.0f ns per reference advance x machine factor %.2f; "
                + "nothing decides on it)", turns, hours < 1 ? String.format(Locale.ROOT, "%.0f min", Math.ceil(hours * 60))
                : String.format(Locale.ROOT, "%.1f h", hours), refNs, o.machineFactor));
        String machine = weights.header().get("machine");
        if (machine != null && !MachineProfile.modelOf(machine).equals(MachineProfile.get().cpuModel()))
            out.add(" the currency was measured on " + MachineProfile.modelOf(machine) + ": those are its hours; this machine's differ");
        return out;
    }

    private static Set<String> readCheckpoint(Path f) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.trim().isEmpty()) out.add(line.trim());
        return out;
    }

    private static String host() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown-host"; }
    }
}
