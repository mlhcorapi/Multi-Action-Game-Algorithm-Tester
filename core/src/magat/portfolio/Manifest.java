package magat.portfolio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code portfolio.csv}, version 1, and the thesis tree's manifest, which is read as it is.
 *
 * <p>Version 1 is a block of {@code # key: value} header lines followed by the CSV:
 * <pre>
 *   # magat-portfolio: 1
 *   # game: tribes
 *   # configuration.engine.commit: b9d27af...
 *   # source.single: seeds 4000000+attempt, ...
 *   # targets: single=200 dual=200
 *   ScenarioId,Source,SourceAttempt,SourceSeed,Phase,Branching,&lt;describe() columns in the game's order&gt;
 * </pre>
 * Nothing in it depends on the machine, the thread count or the time, so a rebuild reproduces it
 * byte for byte wherever the game, its configuration and its sources are the same.
 *
 * <p>The thesis manifest ({@code ScenarioId,Source,SourceGameId,SourceLevelSeed,SourceGameSeed,
 * CapturedTick,...,BranchingFactor,...}) has no header block; its rows map onto the same fields.
 */
public final class Manifest {

    public static final String FILE = "portfolio.csv";
    public static final int FORMAT = 1;
    public static final List<String> CORE_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "ScenarioId", "Source", "SourceAttempt", "SourceSeed", "Phase", "Branching"));
    /** Columns of the thesis manifest that are fields here or constants of the source, not facts. */
    private static final List<String> LEGACY_NON_FACTS = Arrays.asList(
            "ScenarioId", "Source", "SourceGameId", "SourceLevelSeed", "SourceGameSeed", "CapturedTick", "BranchingFactor");

    /** One scenario's line. */
    public static final class Row {
        public final int id;
        public final String source;
        public final int attempt;
        public final long seed;
        public final int phase;
        public final int branching;
        /** The game's {@code describe()} facts, in the game's column order. */
        public final Map<String, String> facts;

        public Row(int id, String source, int attempt, long seed, int phase, int branching, Map<String, String> facts) {
            this.id = id;
            this.source = source;
            this.attempt = attempt;
            this.seed = seed;
            this.phase = phase;
            this.branching = branching;
            this.facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        }

        /** The line the identity hash is computed over. */
        public String identityLine() { return id + "," + source + "," + phase; }

        /** What a scenario is independently of the id it was given: source, attempt and phase. */
        public String key() { return source + "#" + attempt + "@" + phase; }
    }

    private final Map<String, String> meta;
    private final List<Row> rows;
    private final boolean legacy;

    private Manifest(Map<String, String> meta, List<Row> rows, boolean legacy) {
        this.meta = Collections.unmodifiableMap(meta);
        this.rows = Collections.unmodifiableList(rows);
        this.legacy = legacy;
    }

    public static Manifest read(Path portfolioDir) throws IOException {
        Path f = portfolioDir.resolve(FILE);
        if (!Files.isRegularFile(f)) throw new IOException("portfolio manifest not found: " + f);
        return parse(Files.readAllLines(f, StandardCharsets.UTF_8));
    }

    public static Manifest parse(List<String> lines) throws IOException {
        Map<String, String> meta = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        String[] header = null;
        boolean legacy = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (header == null) {
                    String body = line.substring(1).trim();
                    int colon = body.indexOf(": ");
                    if (colon > 0) meta.put(body.substring(0, colon).trim(), body.substring(colon + 2).trim());
                }
                continue;
            }
            if (header == null) {
                header = line.split(",");
                for (int i = 0; i < header.length; i++) header[i] = header[i].trim();
                legacy = Arrays.asList(header).contains("CapturedTick");
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != header.length)
                throw new IOException("manifest row has " + f.length + " fields, header has " + header.length + ": " + line);
            Map<String, String> cols = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) cols.put(header[i], f[i].trim());
            Map<String, String> facts = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : cols.entrySet()) {
                boolean core = legacy ? LEGACY_NON_FACTS.contains(e.getKey()) : CORE_COLUMNS.contains(e.getKey());
                if (!core) facts.put(e.getKey(), e.getValue());
            }
            rows.add(new Row(
                    Integer.parseInt(need(cols, "ScenarioId")),
                    need(cols, "Source"),
                    Integer.parseInt(need(cols, legacy ? "SourceGameId" : "SourceAttempt")),
                    Long.parseLong(need(cols, legacy ? "SourceLevelSeed" : "SourceSeed")),
                    Integer.parseInt(need(cols, legacy ? "CapturedTick" : "Phase")),
                    Integer.parseInt(need(cols, legacy ? "BranchingFactor" : "Branching")),
                    facts));
        }
        if (header == null) throw new IOException("manifest has no header line");
        if (rows.isEmpty()) throw new IOException("manifest has no rows");
        if (!legacy) {
            String fmt = meta.get("magat-portfolio");
            if (fmt != null && !String.valueOf(FORMAT).equals(fmt))
                throw new IOException("manifest format " + fmt + " is not supported by this version (expects " + FORMAT + ")");
        }
        return new Manifest(meta, rows, legacy);
    }

    private static String need(Map<String, String> cols, String name) throws IOException {
        String v = cols.get(name);
        if (v == null) throw new IOException("manifest has no column '" + name + "'");
        return v;
    }

    public List<Row> rows() { return rows; }
    public int size() { return rows.size(); }
    public boolean legacy() { return legacy; }
    public Map<String, String> meta() { return meta; }

    /** The game the manifest was built for, or null for a thesis manifest (which does not say). */
    public String game() { return meta.get("game"); }

    /** {@code configuration.*} header entries, with the prefix removed. */
    public Map<String, String> configuration() { return prefixed("configuration."); }

    /** {@code source.*} header entries: source name to its definition. */
    public Map<String, String> sources() { return prefixed("source."); }

    private Map<String, String> prefixed(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : meta.entrySet())
            if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        return out;
    }

    public String identity() { return Identity.of(rows); }

    /**
     * Renders a version-1 manifest. Every value must be free of commas and line breaks, because the
     * file is read with a plain split; a game whose facts violate that fails here, at build time.
     */
    public static String render(String game, Map<String, String> configuration, Map<String, String> sources,
                                String targets, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# magat-portfolio: ").append(FORMAT).append('\n');
        sb.append("# game: ").append(clean(game)).append('\n');
        for (Map.Entry<String, String> e : configuration.entrySet())
            sb.append("# configuration.").append(clean(e.getKey())).append(": ").append(clean(e.getValue())).append('\n');
        for (Map.Entry<String, String> e : sources.entrySet())
            sb.append("# source.").append(clean(e.getKey())).append(": ").append(clean(e.getValue())).append('\n');
        sb.append("# targets: ").append(clean(targets)).append('\n');
        List<String> factColumns = rows.isEmpty() ? Collections.emptyList() : new ArrayList<>(rows.get(0).facts.keySet());
        List<String> header = new ArrayList<>(CORE_COLUMNS);
        header.addAll(factColumns);
        sb.append(String.join(",", header)).append('\n');
        for (Row r : rows) {
            if (!new ArrayList<>(r.facts.keySet()).equals(factColumns))
                throw new IllegalStateException("scenario " + r.id + " describes itself with columns " + r.facts.keySet()
                        + ", the first scenario with " + factColumns + "; describe() must return the same keys in the same order");
            sb.append(r.id).append(',').append(cell(r.source)).append(',').append(r.attempt).append(',').append(r.seed)
              .append(',').append(r.phase).append(',').append(r.branching);
            for (String c : factColumns) sb.append(',').append(cell(r.facts.get(c)));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String clean(String s) {
        if (s == null) return "";
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            throw new IllegalStateException("manifest header value contains a line break: " + s);
        return s.trim();
    }

    private static String cell(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            throw new IllegalStateException("manifest value contains a comma or line break: '" + s + "'");
        return s;
    }
}
