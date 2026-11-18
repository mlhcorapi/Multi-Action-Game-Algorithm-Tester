package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.game.Scenario;
import magat.portfolio.Identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An experiment spec: a JSON file, validated before anything runs ({@link Validator}), hashed into
 * provenance, and read by both the runner and the analyzer. docs/EXPERIMENTS.md describes every key.
 *
 * <pre>
 * {
 *   "name": "reference", "game": "tribes", "split": "all", "baseline": "emcts",
 *   "levels": [ { "budget": 20000, "visits": 8 }, { "budget": 101500, "visits": 6, "arms": ["..."] } ],
 *   "arms": [ { "id": "emcts" }, { "id": "random" } ],
 *   "contrasts": [ { "a": "emcts", "b": "random", "label": "search over the floor" } ],
 *   "scenarios": 4
 * }
 * </pre>
 *
 * The short forms say the same with less:
 *
 * <pre>
 * {
 *   "name": "oep-vs-tbets", "game": "tribes", "baseline": "emcts",
 *   "arms": ["emcts", "oep", "tbets"],
 *   "levels": ["500ms", 20000], "repetitions": 4,
 *   "split": "dev", "sources": ["single"], "phases": { "min": 10, "max": 20 }, "scenarios": 40,
 *   "weights": "r7615-time"
 * }
 * </pre>
 *
 * <ul>
 *   <li>A level is a number of units, a published level's name ({@code "500ms"}: the game's
 *       {@code levels.json}), or an object with a {@code budget} that is either. {@code repetitions} is
 *       the old {@code visits}; the spec's own {@code repetitions} is the default for every level that
 *       gives neither.</li>
 *   <li>Without {@code contrasts}, every arm is compared with every arm written before it, in the order
 *       written; an empty list asks for none.</li>
 *   <li>{@code sources}, {@code phases} and {@code ids} select positions of the split; {@code scenarios}
 *       then spreads that many evenly over what they selected. A filter never reaches across the split.</li>
 *   <li>{@code weights} names the currency (a published one's name, or a file); the launcher resolves it,
 *       and a {@code --weights} given on the command line wins.</li>
 * </ul>
 *
 * Levels are run in the order written, which makes the order a priority list: leaves are checkpointed
 * independently, so a run stopped partway holds whatever came first. A level runs every arm unless it
 * lists its own {@code arms}.
 */
public final class Experiment {

    private static final Set<String> KNOWN = new LinkedHashSet<>(Arrays.asList("name", "game", "portfolio", "split",
            "baseline", "levels", "arms", "contrasts", "scenarios", "description", "repetitions", "weights", "sources",
            "phases", "ids", "reuse"));

    public static final class Level {
        public final long budget;
        public final int visits;
        /** Null when the level runs every arm. */
        public final List<String> arms;
        /** The level as the spec wrote it: "500ms", or the units. */
        public final String written;

        Level(long budget, int visits, List<String> arms, String written) {
            this.budget = budget;
            this.visits = visits;
            this.arms = arms == null ? null : Collections.unmodifiableList(arms);
            this.written = written;
        }
    }

    public static final class Arm {
        public final String id;

        Arm(String id) {
            this.id = id;
        }
    }

    public static final class Contrast {
        public final String a, b, label;

        Contrast(String a, String b, String label) {
            this.a = a;
            this.b = b;
            this.label = label;
        }
    }

    public final Path source;
    public final String sha256;
    public final String name;
    public final String game;
    public final String portfolio;
    public final String split;
    public final String baseline;
    public final List<Level> levels;
    public final List<Arm> arms;
    public final List<Contrast> contrasts;
    /** Whether the contrasts are the default (no {@code contrasts} key), not written out. */
    public final boolean contrastsByDefault;
    public final int scenarios;
    /** The spec's own default repetitions per level; 0 when it gives none. */
    public final int repetitions;
    /** The currency as the spec names it, or null; the launcher resolves it. */
    public final String weights;
    /**
     * Whether a run may take leaves other runs played ({@code --reuse}); {@code "reuse": false} plays every leaf,
     * for an experiment whose wall-clock comparison needs every turn timed in the same run.
     */
    public final boolean reuse;
    /** The sources to keep, or null for every source. */
    public final List<String> sources;
    /** The capture phases to keep, exactly, or null. */
    public final Set<Integer> phases;
    /** The inclusive range of capture phases to keep, or null. */
    public final Integer phaseMin, phaseMax;
    /** The scenario ids to keep, or null for every id. */
    public final List<Integer> ids;
    public final List<String> unknownKeys;
    /** What could not be read: each one an error the {@link Validator} reports. */
    public final List<String> problems;

    private Experiment(Path source, String sha256, JsonObject o, Map<String, Long> named) {
        this.source = source;
        this.sha256 = sha256;
        List<String> problems = new ArrayList<>();
        this.name = string(o, "name", problems);
        this.game = string(o, "game", problems);
        this.portfolio = string(o, "portfolio", problems);
        this.split = string(o, "split", problems);
        this.baseline = string(o, "baseline", problems);
        this.weights = string(o, "weights", problems);
        this.reuse = bool(o, "reuse", true, problems);
        this.repetitions = count(o.get("repetitions"), "repetitions", problems);
        this.scenarios = count(o.get("scenarios"), "scenarios", problems);
        List<Level> lv = new ArrayList<>();
        for (JsonValue v : array(o, "levels", problems)) lv.add(level(v, named, repetitions, problems));
        this.levels = Collections.unmodifiableList(lv);
        List<Arm> ar = new ArrayList<>();
        for (JsonValue v : array(o, "arms", problems)) {
            if (v.isString()) ar.add(new Arm(v.asString()));
            else if (v.isObject() && (v.asObject().get("id") == null || v.asObject().get("id").isString()))
                ar.add(new Arm(v.asObject().getString("id", null)));
            else problems.add("an arm is " + v + ": write its id, \"oep\", or {\"id\": \"oep\"}");
        }
        this.arms = Collections.unmodifiableList(ar);
        List<Contrast> ct = new ArrayList<>();
        this.contrastsByDefault = o.get("contrasts") == null;
        if (contrastsByDefault) {
            ct.addAll(everyPair(armIds()));
        } else {
            for (JsonValue v : array(o, "contrasts", problems)) {
                if (!v.isObject()) { problems.add("a contrast is " + v + ": write {\"a\": ..., \"b\": ..., \"label\": ...}"); continue; }
                JsonObject c = v.asObject();
                ct.add(new Contrast(string(c, "a", problems), string(c, "b", problems), c.getString("label", "")));
            }
        }
        this.contrasts = Collections.unmodifiableList(ct);
        JsonValue src = o.get("sources");
        this.sources = src == null ? null : Collections.unmodifiableList(strings(src, "sources", problems));
        Set<Integer> ph = null;
        Integer lo = null, hi = null;
        JsonValue p = o.get("phases");
        if (p != null && p.isArray()) {
            ph = new TreeSet<>(ints(p, "phases", problems));
        } else if (p != null && p.isObject()) {
            for (String k : p.asObject().names())
                if (!k.equals("min") && !k.equals("max")) problems.add("phases takes \"min\" and \"max\", not '" + k + "'");
            JsonValue mn = p.asObject().get("min"), mx = p.asObject().get("max");
            lo = mn == null ? null : integer(mn, "phases.min", problems);
            hi = mx == null ? null : integer(mx, "phases.max", problems);
            if (lo != null && hi != null && lo > hi) problems.add("phases: min " + lo + " is above max " + hi);
        } else if (p != null) {
            problems.add("phases is a list of phases, [10, 12], or a range, {\"min\": 10, \"max\": 20}; not " + p);
        }
        this.phases = ph == null ? null : Collections.unmodifiableSet(ph);
        this.phaseMin = lo;
        this.phaseMax = hi;
        JsonValue id = o.get("ids");
        this.ids = id == null ? null : Collections.unmodifiableList(ints(id, "ids", problems));
        List<String> unknown = new ArrayList<>();
        for (String n : o.names()) if (!KNOWN.contains(n)) unknown.add(n);
        this.unknownKeys = Collections.unmodifiableList(unknown);
        this.problems = Collections.unmodifiableList(problems);
    }

    public static Experiment load(Path file) throws IOException {
        return load(file, Collections.emptyMap());
    }

    /** @param named the game's published level names ({@link #levelNames}), for levels written as "500ms" */
    public static Experiment load(Path file, Map<String, Long> named) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return parse(file, new String(bytes, StandardCharsets.UTF_8), Identity.sha256(bytes), named);
    }

    public static Experiment parse(Path source, String json, String sha256) {
        return parse(source, json, sha256, Collections.emptyMap());
    }

    public static Experiment parse(Path source, String json, String sha256, Map<String, Long> named) {
        JsonValue v;
        try {
            v = Json.parse(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException((source == null ? "spec" : source) + " is not valid JSON: " + e.getMessage());
        }
        if (!v.isObject()) throw new IllegalArgumentException("an experiment spec is a JSON object");
        return new Experiment(source, sha256, v.asObject(), named == null ? Collections.emptyMap() : named);
    }

    /**
     * A game's published levels by name, from its {@code levels.json}: {@code "500ms"} for the level that is
     * 500 ms of thinking on the reference machine. Empty when the file is null or absent.
     */
    public static Map<String, Long> levelNames(Path levelsJson) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        if (levelsJson == null || !Files.isRegularFile(levelsJson)) return out;
        JsonValue v = Json.parse(new String(Files.readAllBytes(levelsJson), StandardCharsets.UTF_8));
        JsonValue lv = v.isObject() ? v.asObject().get("levels") : null;
        if (lv == null || !lv.isArray()) return out;
        for (JsonValue l : lv.asArray()) {
            if (!l.isObject() || l.asObject().get("time_ms") == null || l.asObject().get("units") == null) continue;
            out.put(levelKey(l.asObject().get("time_ms").toString() + "ms"), l.asObject().get("units").asLong());
        }
        return out;
    }

    /** The arm ids, in the order written. */
    public List<String> armIds() {
        List<String> out = new ArrayList<>();
        for (Arm a : arms) out.add(a.id);
        return out;
    }

    /** The arms a level runs. */
    public List<String> armsAt(Level l) {
        return l.arms != null ? l.arms : armIds();
    }

    /** Every level an arm runs at. */
    public List<Long> levelsOf(String arm) {
        List<Long> out = new ArrayList<>();
        for (Level l : levels) if (armsAt(l).contains(arm)) out.add(l.budget);
        return out;
    }

    /**
     * The default contrasts: every arm against every arm written before it, grouped by the earlier one, in
     * the order written. For random, mcts, emcts that is mcts - random, emcts - random, emcts - mcts.
     */
    static List<Contrast> everyPair(List<String> ids) {
        List<Contrast> out = new ArrayList<>();
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        distinct.remove(null);
        for (int i = 0; i < distinct.size(); i++)
            for (int j = i + 1; j < distinct.size(); j++)
                out.add(new Contrast(distinct.get(j), distinct.get(i), distinct.get(j) + " against " + distinct.get(i)));
        return out;
    }

    // ------------------------------------------------------------------ which positions run

    /** Whether the spec narrows the split at all: a filter or a count. */
    public boolean selects() {
        return sources != null || phases != null || phaseMin != null || phaseMax != null || ids != null || scenarios > 0;
    }

    /** The scenarios of a split the run plays: the filters, then {@code scenarios} spread evenly over what is left. */
    public <S> List<Scenario<S>> select(List<Scenario<S>> split) {
        List<Scenario<S>> kept = new ArrayList<>();
        for (Scenario<S> s : split) if (keeps(s)) kept.add(s);
        if (scenarios > 0 && scenarios < kept.size()) return spread(kept, scenarios);
        return kept;
    }

    /** Whether the filters keep a scenario (the count aside). */
    <S> boolean keeps(Scenario<S> s) {
        if (sources != null && !sources.contains(s.source)) return false;
        if (phases != null && !phases.contains(s.phase)) return false;
        if (phaseMin != null && s.phase < phaseMin) return false;
        if (phaseMax != null && s.phase > phaseMax) return false;
        return ids == null || ids.contains(s.id);
    }

    private static <S> List<Scenario<S>> spread(List<Scenario<S>> list, int n) {
        List<Scenario<S>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(list.get((int) ((long) i * list.size() / n)));
        return out;
    }

    /** The selection in one line, as provenance records it and a resume is checked against: "all" when there is none. */
    public String selection() {
        if (!selects()) return "all";
        List<String> parts = new ArrayList<>();
        if (sources != null) parts.add("sources=" + String.join(",", sources));
        if (phases != null) parts.add("phases=" + join(phases));
        if (phaseMin != null || phaseMax != null)
            parts.add("phases=" + (phaseMin == null ? "" : phaseMin) + ".." + (phaseMax == null ? "" : phaseMax));
        if (ids != null) parts.add("ids=" + join(ids));
        if (scenarios > 0) parts.add("scenarios=" + scenarios);
        return String.join(";", parts);
    }

    private static String join(Iterable<Integer> xs) {
        StringBuilder sb = new StringBuilder();
        for (Integer x : xs) sb.append(sb.length() == 0 ? "" : ",").append(x);
        return sb.toString();
    }

    // ------------------------------------------------------------------ reading

    private static Level level(JsonValue v, Map<String, Long> named, int repetitions, List<String> problems) {
        JsonValue budget = v;
        JsonValue visits = null, reps = null;
        List<String> arms = null;
        if (v.isObject()) {
            JsonObject l = v.asObject();
            budget = l.get("budget");
            visits = l.get("visits");
            reps = l.get("repetitions");
            for (String k : l.names())
                if (!Arrays.asList("budget", "visits", "repetitions", "arms").contains(k))
                    problems.add("a level has the unknown key '" + k + "' (a level takes budget, repetitions and arms)");
            if (l.get("arms") != null) arms = strings(l.get("arms"), "a level's arms", problems);
        }
        String written = budget == null ? "?" : budget.isString() ? budget.asString() : budget.toString();
        long b = budget(budget, named, problems);
        if (visits != null && reps != null) problems.add("level " + written + " gives both visits and repetitions: they are the same thing");
        int n = reps != null ? count(reps, "level " + written + "'s repetitions", problems)
                : visits != null ? count(visits, "level " + written + "'s visits", problems) : repetitions;
        return new Level(b, n > 0 ? n : -1, arms, written);
    }

    private static long budget(JsonValue v, Map<String, Long> named, List<String> problems) {
        if (v == null || v.isNull()) {
            problems.add("a level has no budget");
            return -1;
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d != Math.rint(d) || d <= 0) {
                problems.add("level " + v + " is not a positive whole number of units");
                return -1;
            }
            return (long) d;
        }
        if (v.isString()) {
            String s = v.asString().trim();
            if (s.matches("[0-9]{1,15}") && Long.parseLong(s) > 0) return Long.parseLong(s);
            if (s.matches("[0-9]+")) {
                problems.add("level '" + s + "' is not a positive whole number of units");
                return -1;
            }
            Long units = named.get(levelKey(s));
            if (units != null) return units;
            problems.add("level '" + s + "' is " + (named.isEmpty()
                    ? "not a number, and no published levels were given to name it (the launcher passes the game's levels.json)"
                    : "not a published level; the published ones are " + String.join(", ", named.keySet())));
            return -1;
        }
        problems.add("a level is " + v + ": write units, 20000, a published level, \"1000ms\", or {\"budget\": ..., \"repetitions\": ...}");
        return -1;
    }

    private static String levelKey(String s) {
        return s.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String string(JsonObject o, String key, List<String> problems) {
        JsonValue v = o.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isString()) return v.asString();
        problems.add(key + " is " + v + ", not a string");
        return null;
    }

    private static boolean bool(JsonObject o, String key, boolean otherwise, List<String> problems) {
        JsonValue v = o.get(key);
        if (v == null || v.isNull()) return otherwise;
        if (v.isBoolean()) return v.asBoolean();
        problems.add(key + " is " + v + ", not true or false");
        return otherwise;
    }

    private static int count(JsonValue v, String what, List<String> problems) {
        if (v == null || v.isNull()) return 0;
        Integer n = integer(v, what, problems);
        if (n == null) return 0;
        if (n < 1) {
            problems.add(what + " is " + n + ", not a positive number");
            return 0;
        }
        return n;
    }

    private static Integer integer(JsonValue v, String what, List<String> problems) {
        if (v.isNumber() && v.asDouble() == Math.rint(v.asDouble()) && Math.abs(v.asDouble()) < Integer.MAX_VALUE)
            return (int) v.asDouble();
        problems.add(what + " is " + v + ", not a whole number");
        return null;
    }

    private static JsonArray array(JsonObject o, String key, List<String> problems) {
        JsonValue v = o.get(key);
        if (v == null || v.isNull()) return new JsonArray();
        if (v.isArray()) return v.asArray();
        problems.add(key + " is " + v + ", not a list");
        return new JsonArray();
    }

    private static List<String> strings(JsonValue a, String what, List<String> problems) {
        List<String> out = new ArrayList<>();
        if (!a.isArray()) {
            problems.add(what + " is " + a + ", not a list");
            return out;
        }
        for (JsonValue v : a.asArray())
            if (v.isString()) out.add(v.asString());
            else problems.add(what + " holds " + v + ", not a string");
        return out;
    }

    private static List<Integer> ints(JsonValue a, String what, List<String> problems) {
        List<Integer> out = new ArrayList<>();
        if (!a.isArray()) {
            problems.add(what + " is " + a + ", not a list");
            return out;
        }
        for (JsonValue v : a.asArray()) {
            Integer n = integer(v, what, problems);
            if (n != null) out.add(n);
        }
        return out;
    }
}
