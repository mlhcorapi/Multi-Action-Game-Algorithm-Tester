package magat.probe;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.EmctsParams;
import magat.agents.emcts.QdeltaParams;
import magat.agents.emcts.Selection;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Weights;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code magat check structure}: asserts what arms ARE, rather than arguing it in a comment.
 * Equivalence claims are executable: two configurations that are the same policy produce identical
 * turns (values, every count, nodes, unique sequences, the mechanism's counters) from the same seeds;
 * two that must differ do not; and the tree has the shape the policy implies.
 *
 * <p>The spec ({@code experiments/<game>/structure.json}) names registered arms and <i>variants</i>,
 * which re-run a registered EMCTS-family arm under another selection rule and offspring count, or as
 * {@code QdeltaEmcts} with every mechanism at its do-nothing default:
 * <pre>
 * { "budget": 8000, "scenarios": 4, "visits": 1,
 *   "variants": { "legacy-k5": { "base": "emcts", "selection": "LEGACY", "k": 5 },
 *                 "qdelta-defaults": { "base": "emcts", "qdelta": true } },
 *   "same":   [ ["legacy-k5", "comma-k5"] ],
 *   "differ": [ ["comma-z0-coarse", "comma-z0-random"] ],
 *   "branching": [ { "arm": "legacy-k5", "exactly": 5 }, { "arm": "plus-k5", "moreThan": 5 } ] }
 * </pre>
 * Every turn is priced with the fixed capture weights, identical for both sides of every comparison,
 * so variants need no calibration. From the thesis tree's {@code SelectionStructureCheck} and
 * {@code SelectionFixCheck}, on portfolio turns instead of complete games.
 */
public final class StructureCheck {

    private StructureCheck() {}

    public static final class Result {
        public final List<String> passes = new ArrayList<>(), failures = new ArrayList<>();
        public boolean ok() { return failures.isEmpty(); }
    }

    public static <S, A> Result run(Game<S, A> game, AgentRegistry<S, A> registry, List<Scenario<S>> portfolio, Path spec, int threads)
            throws Exception {
        JsonObject o = Json.parse(new String(Files.readAllBytes(spec), StandardCharsets.UTF_8)).asObject();
        long budget = o.getLong("budget", 8000);
        int visits = o.getInt("visits", 1);
        int n = Math.min(o.getInt("scenarios", 4), portfolio.size());
        List<Scenario<S>> positions = new ArrayList<>();
        for (int i = 0; i < n; i++) positions.add(portfolio.get((int) ((long) i * portfolio.size() / n)));

        Map<String, AgentFactory<S, A>> arms = new LinkedHashMap<>();
        JsonValue vs = o.get("variants");
        if (vs != null)
            for (JsonObject.Member m : vs.asObject()) arms.put(m.getName(), variant(registry, m.getName(), m.getValue().asObject()));

        Map<String, List<TurnRecord>> runs = new TreeMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        Result r = new Result();
        try {
            for (String name : referenced(o)) {
                AgentFactory<S, A> arm = arms.containsKey(name) ? arms.get(name) : registry.get(name);
                List<Future<TurnRecord>> fs = new ArrayList<>();
                for (Scenario<S> sc : positions)
                    for (int v = 0; v < visits; v++) {
                        final int visit = v;
                        fs.add(pool.submit(() -> Episode.run(game, sc, visit, arm, budget, Weights.DEFAULT, "structure")));
                    }
                List<TurnRecord> recs = new ArrayList<>();
                for (Future<TurnRecord> f : fs) recs.add(f.get());
                runs.put(name, recs);
            }
        } finally {
            pool.shutdownNow();
        }

        for (JsonValue p : list(o, "same")) {
            String a = p.asArray().get(0).asString(), b = p.asArray().get(1).asString();
            Set<String> keys = commonKeys(runs.get(a), runs.get(b));
            String why = signature(runs.get(a), keys).equals(signature(runs.get(b), keys)) ? null : firstDifference(runs.get(a), runs.get(b), keys);
            (why == null ? r.passes : r.failures).add(a + " == " + b + (why == null ? "" : "   first difference: " + why));
        }
        for (JsonValue p : list(o, "differ")) {
            String a = p.asArray().get(0).asString(), b = p.asArray().get(1).asString();
            Set<String> keys = commonKeys(runs.get(a), runs.get(b));
            boolean differ = !signature(runs.get(a), keys).equals(signature(runs.get(b), keys));
            (differ ? r.passes : r.failures).add(a + " != " + b + (differ ? "" : "   IDENTICAL on every turn: one is not wired up, or both are the same arm"));
        }
        for (JsonValue p : list(o, "branching")) {
            JsonObject b = p.asObject();
            String name = b.getString("arm", "");
            int got = 0;
            for (TurnRecord t : runs.get(name)) got = Math.max(got, t.telemetry.getOrDefault("maxBranching", 0.0).intValue());
            boolean ok;
            String want;
            if (b.get("exactly") != null) { ok = got == b.getInt("exactly", -1); want = "exactly " + b.getInt("exactly", -1); }
            else if (b.get("moreThan") != null) { ok = got > b.getInt("moreThan", 0); want = "more than " + b.getInt("moreThan", 0); }
            else { ok = got >= b.getInt("atLeast", 2); want = "at least " + b.getInt("atLeast", 2); }
            (ok ? r.passes : r.failures).add(name + " maxBranching " + got + " (expect " + want + ")");
        }
        return r;
    }

    private static <S, A> AgentFactory<S, A> variant(AgentRegistry<S, A> registry, String name, JsonObject v) {
        AgentFactory<S, A> base = registry.get(v.getString("base", ""));
        if (!(base instanceof EmctsFactory))
            throw new IllegalArgumentException("variant " + name + ": its base " + base.id() + " is not an EMCTS-family arm");
        EmctsFactory<S, A> f = (EmctsFactory<S, A>) base;
        EmctsParams p = f.params();
        Selection sel = v.get("selection") == null ? p.selection : Selection.valueOf(v.getString("selection", ""));
        int k = v.getInt("k", p.childrenPerGeneration);
        if (v.getBoolean("qdelta", false)) {
            QdeltaParams q = new QdeltaParams();
            q.phases = p.phases; q.nodeSize = p.nodeSize; q.bias = p.bias; q.randomInitialSequence = p.randomInitialSequence;
            q.selection = sel;
            q.childrenPerGeneration = k;
            return new EmctsFactory<>(name, "QdeltaEmcts with every mechanism at its default, on " + base.id(), q::copy);
        }
        return f.variant(name, sel, k);
    }

    private static List<String> referenced(JsonObject o) {
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"same", "differ"})
            for (JsonValue p : list(o, key))
                for (JsonValue a : p.asArray()) if (!out.contains(a.asString())) out.add(a.asString());
        for (JsonValue p : list(o, "branching")) {
            String a = p.asObject().getString("arm", "");
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    private static Iterable<JsonValue> list(JsonObject o, String key) {
        JsonValue v = o.get(key);
        return v == null ? new ArrayList<>() : v.asArray();
    }

    /** The telemetry keys both runs report: a mechanism's own counters cannot tell two arms apart if only one has them. */
    private static Set<String> commonKeys(List<TurnRecord> a, List<TurnRecord> b) {
        Set<String> keys = new TreeSet<>(a.get(0).telemetry.keySet());
        keys.retainAll(b.get(0).telemetry.keySet());
        return keys;
    }

    /** Everything observable about the turns, wall-clock aside: the signature, duplicates, length, the shared counters. */
    static String signature(List<TurnRecord> turns, Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (TurnRecord t : turns) {
            sb.append(Signature.of(t)).append(" dup=").append(t.duplicates).append(" len=").append(t.sequenceLength);
            for (String k : keys) sb.append(' ').append(k).append('=').append(t.telemetry.get(k));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String firstDifference(List<TurnRecord> a, List<TurnRecord> b, Set<String> keys) {
        String[] x = signature(a, keys).split("\n"), y = signature(b, keys).split("\n");
        for (int i = 0; i < Math.min(x.length, y.length); i++)
            if (!x[i].equals(y[i])) return "turn " + i + ": " + x[i] + "  vs  " + y[i];
        return "different turn counts";
    }
}
