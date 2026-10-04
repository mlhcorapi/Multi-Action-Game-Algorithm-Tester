package magat.portfolio;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.WriterConfig;
import magat.run.RunGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code _holdout_ledger.json}, beside the split files: every experiment that has read this
 * portfolio's holdout, with the hash of its spec and of the frozen-choices file it was run under.
 *
 * <p>"The holdout is read once" is a promise the split files cannot keep by themselves. The ledger
 * makes it auditable: {@code magat run --split holdout} prints the ledger first, refuses to run
 * without a frozen-choices file, and appends an entry; a second experiment on the same holdout gets
 * a warning it cannot miss, and the entry count travels into the result's provenance.
 */
public final class HoldoutLedger {

    public static final String FILE = "_holdout_ledger.json";
    public static final int FORMAT = 1;

    /** One read of the holdout. */
    public static final class Entry {
        public final String date;
        public final String experiment;
        public final String specHash;
        public final List<String> arms;
        public final String frozen;
        public final String frozenHash;
        public final String run;

        public Entry(String date, String experiment, String specHash, List<String> arms, String frozen,
                     String frozenHash, String run) {
            this.date = date;
            this.experiment = experiment;
            this.specHash = specHash;
            this.arms = Collections.unmodifiableList(new ArrayList<>(arms));
            this.frozen = frozen;
            this.frozenHash = frozenHash;
            this.run = run;
        }

        /** An entry dated now, hashing the spec and frozen-choices files as they are on disk. */
        public static Entry now(String experiment, Path spec, List<String> arms, Path frozen, Path run) throws IOException {
            return new Entry(Instant.now().toString(), experiment, Identity.sha256(spec), arms,
                    frozen.toString(), Identity.sha256(frozen), run.toString());
        }

        JsonObject toJson() {
            JsonArray a = new JsonArray();
            for (String s : arms) a.add(s);
            return new JsonObject().add("date", date).add("experiment", experiment).add("specSha256", specHash)
                    .add("arms", a).add("frozen", frozen).add("frozenSha256", frozenHash).add("run", run);
        }

        static Entry fromJson(JsonObject o) {
            List<String> arms = new ArrayList<>();
            for (JsonValue v : o.get("arms").asArray()) arms.add(v.asString());
            return new Entry(o.getString("date", ""), o.getString("experiment", ""), o.getString("specSha256", ""), arms,
                    o.getString("frozen", ""), o.getString("frozenSha256", ""), o.getString("run", ""));
        }

        @Override
        public String toString() {
            return date + "  " + experiment + "  arms " + arms + "  spec " + Identity.shortForm(specHash)
                    + "  frozen " + frozen + " (" + Identity.shortForm(frozenHash) + ")  -> " + run;
        }
    }

    private final Path file;
    private final String identity;
    private final List<Entry> entries;

    private HoldoutLedger(Path file, String identity, List<Entry> entries) {
        this.file = file;
        this.identity = identity;
        this.entries = entries;
    }

    /**
     * The ledger of a portfolio, empty when nothing has read its holdout. A ledger written for a
     * different portfolio identity is refused: it would vouch for a holdout it never saw.
     */
    public static HoldoutLedger load(Path portfolioDir) throws IOException {
        String identity = Manifest.read(portfolioDir).identity();
        Path f = portfolioDir.resolve(FILE);
        List<Entry> entries = new ArrayList<>();
        if (Files.isRegularFile(f)) {
            JsonObject o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).asObject();
            if (o.getInt("format", -1) != FORMAT)
                throw new IllegalStateException(f + ": unsupported ledger format " + o.get("format"));
            String own = o.getString("portfolio", "");
            if (!own.equals(identity))
                throw new IllegalStateException(f + " belongs to portfolio " + own + ", not to this one (" + identity + ")");
            for (JsonValue v : o.get("entries").asArray()) entries.add(Entry.fromJson(v.asObject()));
        }
        return new HoldoutLedger(f, identity, entries);
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }

    public boolean holdoutRead() { return !entries.isEmpty(); }

    /** Appends one read and rewrites the file atomically. */
    public void append(Entry e) throws IOException {
        entries.add(e);
        JsonArray a = new JsonArray();
        for (Entry x : entries) a.add(x.toJson());
        JsonObject o = new JsonObject()
                .add("format", FORMAT)
                .add("portfolio", identity)
                .add("note", "Every experiment that has read this portfolio's holdout. Appended by magat run --split "
                        + "holdout; never edit by hand.")
                .add("entries", a);
        RunGuard.atomicWrite(file, o.toString(WriterConfig.PRETTY_PRINT) + "\n");
    }

    /** The ledger, printable. */
    public String describe() {
        if (entries.isEmpty()) return "holdout ledger: the holdout of portfolio " + Identity.shortForm(identity) + " has never been read.";
        StringBuilder sb = new StringBuilder("holdout ledger: the holdout of portfolio " + Identity.shortForm(identity)
                + " has been read " + entries.size() + " time(s):\n");
        for (Entry e : entries) sb.append("  ").append(e).append('\n');
        return sb.toString().trim();
    }

    /** A banner for a run about to read a holdout that has been read before, or null. */
    public String warning() {
        if (entries.isEmpty()) return null;
        return "############################################################\n"
                + "# WARNING: this holdout has already been read " + entries.size() + " time(s).\n"
                + "# A holdout is evidence once. Results from a second read are labelled with the\n"
                + "# read count in their provenance and are not a clean replication.\n"
                + "############################################################\n" + describe();
    }
}
