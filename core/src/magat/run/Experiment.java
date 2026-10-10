package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.agent.TurnBudget;
import magat.portfolio.Identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An experiment spec: a JSON file, validated before anything runs ({@link Validator}), hashed into
 * provenance, and read by both the runner and the analyzer. Replaces the thesis tree's stage schedules,
 * which were Java constants.
 *
 * <pre>
 * {
 *   "name": "ab-emcts-holdout", "game": "tribes", "split": "holdout", "baseline": "emcts-comma",
 *   "levels": [ { "budget": 80000, "visits": 8 }, { "budget": 400000, "visits": 6, "arms": ["..."] } ],
 *   "arms": [ { "id": "emcts-comma" }, { "id": "comma-z0-coarse", "attribution": "comma-z0-random" } ],
 *   "sweeps": [ { "parameter": "repairKeys", "arms": ["..."], "null": "comma-z0-random" } ],
 *   "contrasts": [ { "a": "comma-z0-coarse", "b": "emcts-comma", "label": "headline" } ],
 *   "scenarios": 4,
 *   "preregistration": "docs/...", "frozen": "frozen_choices.txt"
 * }
 * </pre>
 *
 * Levels are run in the order written, which makes the order a priority list: leaves are checkpointed
 * independently, so a run stopped partway holds whatever came first. A level runs every arm unless it
 * lists its own {@code arms}. {@code scenarios} limits a run to an evenly spread subset of the split
 * (shakedowns and smoke runs only).
 */
public final class Experiment {

    private static final Set<String> KNOWN = new LinkedHashSet<>(Arrays.asList("name", "game", "portfolio", "split",
            "baseline", "levels", "arms", "sweeps", "contrasts", "scenarios", "preregistration", "frozen", "initialization",
            "wallclock_anchor", "description"));

    public static final class Level {
        public final long budget;
        public final int visits;
        /** Null when the level runs every arm. */
        public final List<String> arms;

        Level(long budget, int visits, List<String> arms) {
            this.budget = budget;
            this.visits = visits;
            this.arms = arms == null ? null : Collections.unmodifiableList(arms);
        }
    }

    public static final class Arm {
        public final String id;
        /** The arm with this arm's mechanism replaced by noise, when the spec names one. */
        public final String attribution;

        Arm(String id, String attribution) {
            this.id = id;
            this.attribution = attribution;
        }
    }

    public static final class Sweep {
        public final String parameter;
        public final List<String> arms;
        /** The arm at the parameter's null setting; a sweep without one is a design flaw (principle 5). */
        public final String nullArm;

        Sweep(String parameter, List<String> arms, String nullArm) {
            this.parameter = parameter;
            this.arms = Collections.unmodifiableList(arms);
            this.nullArm = nullArm;
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
    public final List<Sweep> sweeps;
    public final List<Contrast> contrasts;
    public final int scenarios;
    public final String preregistration;
    public final String frozen;
    /**
     * Whether each turn's initialization is paid from its budget ({@code "initialization": "in-budget"},
     * MAGAT's standard) or bounds only the search ({@code "outside-budget"}, the thesis's). A spec that does
     * not say keeps the meaning it was written with: outside the budget. Null when the value is not one of
     * the two (the validator reports it).
     */
    public final TurnBudget.Initialization initialization;
    public final boolean initializationDeclared;
    public final String initializationValue;
    public final List<String> unknownKeys;

    private Experiment(Path source, String sha256, JsonObject o) {
        this.source = source;
        this.sha256 = sha256;
        this.name = o.getString("name", null);
        this.game = o.getString("game", null);
        this.portfolio = o.getString("portfolio", null);
        this.split = o.getString("split", null);
        this.baseline = o.getString("baseline", null);
        List<Level> lv = new ArrayList<>();
        for (JsonValue v : array(o, "levels")) {
            JsonObject l = v.asObject();
            lv.add(new Level(l.getLong("budget", -1), l.getInt("visits", -1), l.get("arms") == null ? null : strings(l.get("arms").asArray())));
        }
        this.levels = Collections.unmodifiableList(lv);
        List<Arm> ar = new ArrayList<>();
        for (JsonValue v : array(o, "arms"))
            ar.add(v.isString() ? new Arm(v.asString(), null) : new Arm(v.asObject().getString("id", null), v.asObject().getString("attribution", null)));
        this.arms = Collections.unmodifiableList(ar);
        List<Sweep> sw = new ArrayList<>();
        for (JsonValue v : array(o, "sweeps")) {
            JsonObject s = v.asObject();
            sw.add(new Sweep(s.getString("parameter", null), s.get("arms") == null ? Collections.emptyList() : strings(s.get("arms").asArray()),
                    s.get("null") == null || s.get("null").isNull() ? null : s.get("null").asString()));
        }
        this.sweeps = Collections.unmodifiableList(sw);
        List<Contrast> ct = new ArrayList<>();
        for (JsonValue v : array(o, "contrasts")) {
            JsonObject c = v.asObject();
            ct.add(new Contrast(c.getString("a", null), c.getString("b", null), c.getString("label", "")));
        }
        this.contrasts = Collections.unmodifiableList(ct);
        this.scenarios = o.getInt("scenarios", 0);
        this.preregistration = o.getString("preregistration", null);
        this.frozen = o.getString("frozen", null);
        this.initializationValue = o.get("initialization") == null ? null
                : o.get("initialization").isString() ? o.get("initialization").asString() : o.get("initialization").toString();
        this.initializationDeclared = initializationValue != null;
        TurnBudget.Initialization init;
        try {
            init = initializationDeclared ? TurnBudget.Initialization.of(initializationValue) : TurnBudget.Initialization.OUTSIDE_BUDGET;
        } catch (IllegalArgumentException e) {
            init = null;
        }
        this.initialization = init;
        List<String> unknown = new ArrayList<>();
        for (String n : o.names()) if (!KNOWN.contains(n)) unknown.add(n);
        this.unknownKeys = Collections.unmodifiableList(unknown);
    }

    public static Experiment load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return parse(file, new String(bytes, StandardCharsets.UTF_8), Identity.sha256(bytes));
    }

    public static Experiment parse(Path source, String json, String sha256) {
        JsonValue v;
        try {
            v = Json.parse(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException((source == null ? "spec" : source) + " is not valid JSON: " + e.getMessage());
        }
        if (!v.isObject()) throw new IllegalArgumentException("an experiment spec is a JSON object");
        return new Experiment(source, sha256, v.asObject());
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

    private static JsonArray array(JsonObject o, String key) {
        JsonValue v = o.get(key);
        return v == null || v.isNull() ? new JsonArray() : v.asArray();
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonValue v : a) out.add(v.asString());
        return out;
    }
}
