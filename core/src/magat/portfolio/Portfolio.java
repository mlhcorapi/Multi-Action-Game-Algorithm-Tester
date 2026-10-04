package magat.portfolio;

import magat.game.Game;
import magat.game.Scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A portfolio on disk, loaded: the manifest ({@code portfolio.csv}) and one serialized state per
 * scenario ({@code scenarios/scenario_NNN.bin}).
 *
 * <p>Two manifest headers are accepted. Version 1 begins
 * {@code ScenarioId,Source,SourceAttempt,SourceSeed,Phase,Branching} and continues with the game's
 * {@code describe()} columns. The thesis tree's manifest
 * ({@code ScenarioId,Source,SourceGameId,SourceLevelSeed,SourceGameSeed,CapturedTick,...,BranchingFactor,...})
 * is read as it is, so the published Tribes portfolio needs no conversion. The identity hash is the
 * same under both: SHA-256 over the ordered {@code id,source,phase} lines.
 */
public final class Portfolio<S> {

    public static final String MANIFEST = "portfolio.csv";
    public static final String SCENARIOS_DIR = "scenarios";

    private final Path dir;
    private final List<Scenario<S>> scenarios;
    private final Map<Integer, Scenario<S>> byId;
    private final String identity;

    private Portfolio(Path dir, List<Scenario<S>> scenarios, String identity) {
        this.dir = dir;
        this.scenarios = Collections.unmodifiableList(scenarios);
        Map<Integer, Scenario<S>> m = new LinkedHashMap<>();
        for (Scenario<S> s : scenarios) m.put(s.id, s);
        this.byId = Collections.unmodifiableMap(m);
        this.identity = identity;
    }

    public static <S, A> Portfolio<S> load(Game<S, A> game, Path dir) throws IOException {
        return load(game, dir, null);
    }

    /** Loads only the listed ids (in manifest order) when {@code onlyIds} is non-null. */
    public static <S, A> Portfolio<S> load(Game<S, A> game, Path dir, java.util.Set<Integer> onlyIds) throws IOException {
        Path manifest = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest))
            throw new IOException("portfolio manifest not found: " + manifest);
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        List<Row> rows = parseManifest(lines);
        List<Scenario<S>> out = new ArrayList<>();
        for (Row r : rows) {
            if (onlyIds != null && !onlyIds.contains(r.id)) continue;
            Path bin = dir.resolve(SCENARIOS_DIR).resolve(binName(r.id));
            if (!Files.isRegularFile(bin)) throw new IOException("scenario file missing: " + bin);
            S state = game.deserialize(Files.readAllBytes(bin));
            out.add(new Scenario<>(r.id, r.source, r.attempt, r.seed, r.phase, r.branching, state, r.facts));
        }
        return new Portfolio<>(dir, out, identityOf(rows));
    }

    /** Reads the manifest alone (no states): for identity checks and listings. */
    public static String identity(Path dir) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve(MANIFEST), StandardCharsets.UTF_8);
        return identityOf(parseManifest(lines));
    }

    public static String binName(int id) {
        return String.format(Locale.ROOT, "scenario_%03d.bin", id);
    }

    public Path dir() { return dir; }
    public List<Scenario<S>> scenarios() { return scenarios; }
    public Scenario<S> byId(int id) { return byId.get(id); }
    public int size() { return scenarios.size(); }

    /** SHA-256 over the ordered {@code id,source,phase} lines of the manifest. */
    public String identity() { return identity; }

    /** {@code n} scenarios spread evenly across the portfolio, so a sample spans both sources. */
    public List<Scenario<S>> spread(int n) {
        n = Math.max(1, Math.min(n, scenarios.size()));
        List<Scenario<S>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(scenarios.get((int) ((long) i * scenarios.size() / n)));
        return out;
    }

    // ------------------------------------------------------------------ manifest

    static final class Row {
        int id; String source; int attempt; long seed; int phase; int branching;
        Map<String, String> facts = new LinkedHashMap<>();
    }

    static List<Row> parseManifest(List<String> lines) throws IOException {
        List<Row> rows = new ArrayList<>();
        String[] header = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (header == null) { header = line.split(","); continue; }
            String[] f = line.split(",", -1);
            if (f.length < 6) continue;
            Map<String, String> cols = new LinkedHashMap<>();
            for (int i = 0; i < header.length && i < f.length; i++) cols.put(header[i].trim(), f[i].trim());
            Row r = new Row();
            boolean legacy = cols.containsKey("CapturedTick");
            r.id = Integer.parseInt(cols.get("ScenarioId"));
            r.source = cols.get("Source");
            r.attempt = Integer.parseInt(cols.get(legacy ? "SourceGameId" : "SourceAttempt"));
            r.seed = Long.parseLong(cols.get(legacy ? "SourceLevelSeed" : "SourceSeed"));
            r.phase = Integer.parseInt(cols.get(legacy ? "CapturedTick" : "Phase"));
            r.branching = Integer.parseInt(cols.get(legacy ? "BranchingFactor" : "Branching"));
            List<String> core = Arrays.asList("ScenarioId", "Source", "SourceGameId", "SourceAttempt",
                    "SourceLevelSeed", "SourceSeed", "SourceGameSeed", "CapturedTick", "Phase",
                    "BranchingFactor", "Branching");
            for (Map.Entry<String, String> e : cols.entrySet())
                if (!core.contains(e.getKey())) r.facts.put(e.getKey(), e.getValue());
            rows.add(r);
        }
        if (header == null) throw new IOException("manifest has no header line");
        if (rows.isEmpty()) throw new IOException("manifest has no rows");
        return rows;
    }

    static String identityOf(List<Row> rows) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Row r : rows) sb.append(r.id).append(',').append(r.source).append(',').append(r.phase).append('\n');
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
