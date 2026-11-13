package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.WriterConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code _run.json} at the root of one split's tree: what the results in it were produced from, and the
 * locks a resume is checked against.
 *
 * <p>Recorded once, on the first run into the tree, and then enforced: the game, its configuration
 * (engine commit included), the portfolio identity, the split and the positions the spec selects of it. A resume that differs in any of them
 * aborts: its turns would not be comparable with the ones already on disk. The weights file's hash is
 * recorded too, with the currency lock in {@link GridRunner}. Every invocation and every retired leaf
 * is appended, so the tree carries its own history. The MAGAT commit is recorded once, as the tree's
 * first invocation had it; each invocation records its own, and each leaf the commit that played it.
 */
final class Provenance {

    static final String FILE = "_run.json";

    private final Path file;
    final JsonObject json;
    final boolean fresh;

    private Provenance(Path file, JsonObject json, boolean fresh) {
        this.file = file;
        this.json = json;
        this.fresh = fresh;
    }

    static Provenance open(Path root) throws IOException {
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return new Provenance(f, new JsonObject().add("format", 1), true);
        return new Provenance(f, Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).asObject(), false);
    }

    /** The locks: identical or abort. Returns the differences, empty when the tree may be extended. */
    List<String> mismatches(String game, Map<String, String> configuration, String portfolioIdentity, String split,
                            String selection) {
        List<String> out = new ArrayList<>();
        if (fresh) return out;
        compare(out, "game", str(json, "game", "id"), game);
        compare(out, "split", json.getString("split", null), split);
        compare(out, "selection", json.getString("selection", null), selection);
        compare(out, "portfolio identity", str(json, "portfolio", "identity"), portfolioIdentity);
        JsonValue cfg = json.get("game") == null ? null : json.get("game").asObject().get("configuration");
        if (cfg != null)
            for (Map.Entry<String, String> e : configuration.entrySet()) {
                JsonValue had = cfg.asObject().get(e.getKey());
                compare(out, "configuration." + e.getKey(), had == null ? null : had.asString(), e.getValue());
            }
        return out;
    }

    private static void compare(List<String> out, String what, String had, String now) {
        if (had != null && !had.equals(now)) out.add(what + ": this tree holds '" + had + "', this run has '" + now + "'");
    }

    String weightsSha() {
        return str(json, "weights", "sha256");
    }

    void set(String key, JsonValue v) {
        json.set(key, v);
    }

    void append(String list, JsonObject entry) {
        JsonValue v = json.get(list);
        JsonArray a = v == null ? new JsonArray() : v.asArray();
        a.add(entry);
        json.set(list, a);
    }

    void save() throws IOException {
        RunGuard.atomicWrite(file, json.toString(WriterConfig.PRETTY_PRINT) + "\n");
    }

    private static String str(JsonObject o, String obj, String key) {
        JsonValue v = o.get(obj);
        return v == null || !v.isObject() ? null : v.asObject().getString(key, null);
    }
}
