package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.agent.AgentFactory;
import magat.game.Game;
import magat.game.Scenario;
import magat.portfolio.Portfolio;
import magat.probe.ReplayCheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns another run already played, taken instead of played again: a published run's leaves, for a run that
 * prices them alike. The determinism the replay check tests is what makes this sound: at the same prices, on the
 * same portfolio and engine, a turn is a function of its arm, budget, scenario and visit alone, so the published
 * row is the row this machine would write.
 *
 * <p>The run as a whole is checked once ({@link #open}): the same portfolio identity and the same game
 * configuration, engine commit included. A leaf ({@link #take}) is then taken only whole, into a leaf this run has
 * not started, when the published leaf is complete, was played at the prices this run would play it at (the arm's
 * profile, or the global weights for an arm that is not priced), and holds every {@code (scenario, visit)} this run
 * needs; and only after this machine, at this commit, re-plays a sample of those turns field for field (wall-clock
 * aside). Anything less and the leaf is played, and the run says why. Prices are compared leaf by leaf, not by the
 * currency's hash, because a currency extended with a new arm (calibrated into it, merged by ratio) keeps every
 * price it had: an algorithm priced into the published currency can then be run against the published leaves.
 * A taken leaf keeps the commit and host that played it and records where it came from; its times are another
 * machine's, which the analysis leaves out of every time measure.
 */
final class Reuse {

    /** The key in {@code _leaf.json} that marks a leaf as taken from another run. */
    static final String KEY = "reused";

    private final Path source;
    private final String machine;

    private Reuse(Path source, String machine) {
        this.source = source;
        this.machine = machine;
    }

    /**
     * The published split tree, when this run may take turns from it; null otherwise, with the reasons added to
     * {@code why}.
     */
    static Reuse open(Path source, String portfolioIdentity, Map<String, String> configuration, List<String> why)
            throws IOException {
        Path runFile = source.resolve(Provenance.FILE);
        if (!Files.isRegularFile(runFile)) {
            why.add(source + " holds no " + Provenance.FILE + ": it is not a run's split tree");
            return null;
        }
        JsonObject run = Json.parse(new String(Files.readAllBytes(runFile), StandardCharsets.UTF_8)).asObject();
        String identity = str(run, "portfolio", "identity");
        if (!portfolioIdentity.equals(identity))
            why.add("it was played on another portfolio (" + shortSha(identity) + "; this run reads " + shortSha(portfolioIdentity) + ")");
        JsonValue game = run.get("game");
        JsonValue cfg = game == null || !game.isObject() ? null : game.asObject().get("configuration");
        for (Map.Entry<String, String> e : configuration.entrySet()) {
            JsonValue had = cfg == null || !cfg.isObject() ? null : cfg.asObject().get(e.getKey());
            String was = had == null || !had.isString() ? null : had.asString();
            if (!e.getValue().equals(was)) why.add("its game configuration differs: " + e.getKey() + " '" + was + "', here '" + e.getValue() + "'");
        }
        if (!why.isEmpty()) return null;
        JsonValue inv = run.get("invocations");
        String machine = inv != null && inv.isArray() && !inv.asArray().isEmpty() && inv.asArray().get(0).isObject()
                ? inv.asArray().get(0).asObject().getString("machine", "unknown") : "unknown";
        return new Reuse(source.toAbsolutePath(), machine);
    }

    String machine() {
        return machine;
    }

    Path source() {
        return source;
    }

    /**
     * Takes the published leaf of an arm at a level into {@code dir}, the rows of these scenarios and visits only,
     * after re-playing {@code replay} of them here. Returns null when the leaf was taken, else why it was not.
     */
    <S, A> String take(Game<S, A> game, Portfolio<S> portfolio, AgentFactory<S, A> arm, Experiment.Level level,
                       List<Scenario<S>> scenarios, Path dir, JsonObject meta, int replay, int threads) throws IOException {
        if (Files.isRegularFile(dir.resolve(Leaf.TURNS)) && Leaf.rows(dir) > 0) return "this run has started the leaf already";
        Path from = Leaf.dir(source, arm.id(), level.budget);
        JsonObject published = readJson(from.resolve(Leaf.META));
        if (published == null || !Files.isRegularFile(from.resolve(Leaf.TURNS))) return "the published run has no such leaf";
        if (!"complete".equals(published.getString("status", ""))) return "the published leaf is not complete";
        String differs = pricesDiffer(published, meta);
        if (differs != null) return "it was played at other prices (" + differs + ")";

        List<String> lines = Files.readAllLines(from.resolve(Leaf.TURNS), StandardCharsets.UTF_8);
        Set<String> needed = new LinkedHashSet<>();
        for (Scenario<S> sc : scenarios) for (int v = 0; v < level.visits; v++) needed.add(Leaf.key(sc.id, v));
        List<String> rows = new ArrayList<>();
        Set<String> found = new LinkedHashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", 5);
            if (f.length < 5) continue;
            String key = Leaf.key(Integer.parseInt(f[0]), Integer.parseInt(f[3]));
            if (needed.contains(key) && found.add(key)) rows.add(line);
        }
        if (found.size() < needed.size())
            return "the published leaf holds " + found.size() + " of the " + needed.size() + " turns this run needs";

        ReplayCheck.Result r;
        try {
            r = ReplayCheck.run(game, portfolio, arm, level.budget, ReplayCheck.read(from).only(needed), replay, threads);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            return "re-playing its turns here failed: " + e;
        }
        if (!r.pass())
            return "this machine did not re-play " + (r.replayed - r.reproduced) + " of " + r.replayed + " sampled turns field for field ("
                    + (r.failures.isEmpty() ? "nothing re-played" : r.failures.get(0)) + ")";

        Files.createDirectories(dir);
        List<String> out = new ArrayList<>();
        out.add(lines.get(0));
        out.addAll(rows);
        Files.write(dir.resolve(Leaf.TURNS), out, StandardCharsets.UTF_8);
        if (Files.isRegularFile(from.resolve(Leaf.CURVE))) {
            List<String> curve = Files.readAllLines(from.resolve(Leaf.CURVE), StandardCharsets.UTF_8), kept = new ArrayList<>();
            kept.add(curve.isEmpty() ? Leaf.CURVE_HEADER : curve.get(0));
            for (String line : curve.subList(Math.min(1, curve.size()), curve.size())) {
                String[] f = line.split(",", 3);
                if (f.length == 3 && needed.contains(f[0] + ":" + f[1])) kept.add(line);
            }
            Files.write(dir.resolve(Leaf.CURVE), kept, StandardCharsets.UTF_8);
        }
        JsonObject magat = GridRunner.thisMagat();
        meta.set("threads", published.get("threads") == null ? Json.value(0) : published.get("threads"))
            .set("host", published.getString("host", "unknown-host"))
            .set("commit", published.getString("commit", "unknown"))
            .set(KEY, new JsonObject().add("from", from.toString()).add("machine", machine)
                    .add("takenBy", magat.getString("commit", "unknown")).add("date", Instant.now().toString())
                    .add("replayed", r.replayed).add("reproduced", r.reproduced))
            .set("turnsTotal", needed.size()).set("turnsDone", rows.size()).set("status", "complete");
        GridRunner.writeMeta(dir, meta);
        return null;
    }

    /**
     * Where a published leaf's prices differ from the ones this run would play it at, or null when they agree: the
     * profile, the forward model's weights and every operation's price, to nine significant digits (a currency
     * file's precision).
     */
    static String pricesDiffer(JsonObject published, JsonObject mine) {
        if (!mine.getString("profile", "").equals(published.getString("profile", null)))
            return "profile " + published.getString("profile", "none") + " there, " + mine.getString("profile", "") + " here";
        JsonValue pw = published.get("weights"), mw = mine.get("weights");
        if (pw == null || !pw.isArray() || mw == null || pw.asArray().size() != mw.asArray().size()) return "its weights are not recorded";
        String[] names = {"advance", "copy", "eval"};
        for (int i = 0; i < mw.asArray().size(); i++)
            if (!same(pw.asArray().get(i).asDouble(), mw.asArray().get(i).asDouble()))
                return (i < names.length ? names[i] : "weight " + i) + " " + fmt(pw.asArray().get(i).asDouble()) + " there, "
                        + fmt(mw.asArray().get(i).asDouble()) + " here";
        JsonValue pp = published.get("prices"), mp = mine.get("prices");
        if (pp == null || !pp.isObject() || mp == null) return "its prices are not recorded";
        for (JsonObject.Member m : mp.asObject()) {
            JsonValue there = pp.asObject().get(m.getName());
            if (there == null || !same(there.asDouble(), m.getValue().asDouble()))
                return m.getName() + " " + (there == null ? "unpriced" : fmt(there.asDouble())) + " there, " + fmt(m.getValue().asDouble()) + " here";
        }
        for (JsonObject.Member m : pp.asObject())
            if (mp.asObject().get(m.getName()) == null) return m.getName() + " priced there, not here";
        return null;
    }

    private static String fmt(double v) {
        return new java.math.BigDecimal(v).round(new java.math.MathContext(9)).stripTrailingZeros().toPlainString();
    }

    private static boolean same(double a, double b) {
        return a == b || Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    private static JsonObject readJson(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return null;
        JsonValue v = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        return v.isObject() ? v.asObject() : null;
    }

    private static String str(JsonObject o, String obj, String key) {
        JsonValue v = o.get(obj);
        return v == null || !v.isObject() ? null : v.asObject().getString(key, null);
    }

    private static String shortSha(String sha) {
        return sha == null ? "none recorded" : sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
