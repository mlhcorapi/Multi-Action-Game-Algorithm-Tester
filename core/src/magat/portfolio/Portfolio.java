package magat.portfolio;

import magat.game.Game;
import magat.game.Scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A portfolio on disk, loaded: the manifest ({@code portfolio.csv}, see {@link Manifest}) and one
 * serialized state per scenario ({@code scenarios/scenario_NNN.bin}).
 *
 * <p>A version-1 manifest names its game, and loading it with a different game is refused. The
 * original format does not name one and is accepted as it is, so the published Tribes portfolio needs no
 * conversion.
 */
public final class Portfolio<S> {

    public static final String MANIFEST = Manifest.FILE;
    public static final String SCENARIOS_DIR = "scenarios";

    private final Path dir;
    private final Manifest manifest;
    private final List<Scenario<S>> scenarios;
    private final Map<Integer, Scenario<S>> byId;

    private Portfolio(Path dir, Manifest manifest, List<Scenario<S>> scenarios) {
        this.dir = dir;
        this.manifest = manifest;
        this.scenarios = Collections.unmodifiableList(scenarios);
        Map<Integer, Scenario<S>> m = new LinkedHashMap<>();
        for (Scenario<S> s : scenarios) m.put(s.id, s);
        this.byId = Collections.unmodifiableMap(m);
    }

    public static <S, A> Portfolio<S> load(Game<S, A> game, Path dir) throws IOException {
        return load(game, dir, null);
    }

    /** Loads only the listed ids (in manifest order) when {@code onlyIds} is non-null. */
    public static <S, A> Portfolio<S> load(Game<S, A> game, Path dir, Set<Integer> onlyIds) throws IOException {
        Manifest manifest = Manifest.read(dir);
        if (manifest.game() != null && !manifest.game().equals(game.id()))
            throw new IllegalStateException("portfolio " + dir + " was built for game '" + manifest.game()
                    + "', not '" + game.id() + "'");
        List<Scenario<S>> out = new ArrayList<>();
        for (Manifest.Row r : manifest.rows()) {
            if (onlyIds != null && !onlyIds.contains(r.id)) continue;
            Path bin = binPath(dir, r.id);
            if (!Files.isRegularFile(bin)) throw new IOException("scenario file missing: " + bin);
            S state = game.deserialize(Files.readAllBytes(bin));
            out.add(new Scenario<>(r.id, r.source, r.attempt, r.seed, r.phase, r.branching, state, r.facts));
        }
        return new Portfolio<>(dir, manifest, out);
    }

    /** Reads the manifest alone (no states): for identity checks and listings. */
    public static String identity(Path dir) throws IOException {
        return Manifest.read(dir).identity();
    }

    public static String binName(int id) {
        return String.format(Locale.ROOT, "scenario_%03d.bin", id);
    }

    public static Path binPath(Path dir, int id) {
        return dir.resolve(SCENARIOS_DIR).resolve(binName(id));
    }

    public Path dir() { return dir; }
    public Manifest manifest() { return manifest; }
    public List<Scenario<S>> scenarios() { return scenarios; }
    public Scenario<S> byId(int id) { return byId.get(id); }
    public int size() { return scenarios.size(); }

    /** The identity of the whole manifest (see {@link Identity}), whatever subset was loaded. */
    public String identity() { return manifest.identity(); }

    /** {@code n} scenarios spread evenly across the portfolio, so a sample spans every source. */
    public List<Scenario<S>> spread(int n) {
        n = Math.max(1, Math.min(n, scenarios.size()));
        List<Scenario<S>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(scenarios.get((int) ((long) i * scenarios.size() / n)));
        return out;
    }
}
