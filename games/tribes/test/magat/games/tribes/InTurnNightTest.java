package magat.games.tribes;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Manifest;
import magat.portfolio.Split;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * The in-turn learning night's files (docs/plans/in-turn-learning-run.md) say what the plan says
 * (docs/plans/in-turn-learning.md, sections 5 and 6), and agree with each other: the 29 new arms at the published
 * levels, 13 chosen arms and the baseline at 30 s on 100 positions of the development half chosen by the split's own
 * rule, and seven calibration sittings of one currency that price exactly what the two specs play and the published
 * runs do not hold.
 */
public class InTurnNightTest {

    private static final Path PORTFOLIO = Paths.get("games", "tribes", "portfolio", "v1");
    private static final Path SCREEN = Paths.get("experiments", "tribes", "in-turn-screen.json");
    private static final Path LONG = Paths.get("experiments", "tribes", "in-turn-30s.json");
    private static final Path SITTINGS = Paths.get("calibrations", "tribes", "in-turn");
    private static final Path FROM = Paths.get("games", "tribes", "calibration", "r7615-ab-emcts", "compute_weights.properties");
    private static final long LEVEL = 610_000L;
    private static final long[] PUBLISHED = {9_500L, 20_000L, 101_500L};
    /** The plan's 13 arms at 30 s (section 6), and emcts, the baseline. */
    private static final List<String> AT_30S = Arrays.asList("comma-z0-coarse", "comma-z0-random", "emcts-comma", "oep",
            "comma-z0-mid", "comma-z0-fine", "comma-z0-hier", "comma-z0-mlp", "comma-short3-coarse", "mut-thompson",
            "mut-k3-one", "mut-aos", "mut-kick", "emcts");

    private static JsonObject read(Path f) throws Exception {
        return Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).asObject();
    }

    private static List<String> strings(JsonValue v) {
        List<String> out = new ArrayList<>();
        for (JsonValue x : v.asArray()) out.add(x.isString() ? x.asString() : x.asObject().getString("id", null));
        return out;
    }

    /** The 29 new arms: every learner but the champion, the shortlist repair, the mutation rules and the self-learning ideas. */
    private static Set<String> newArms() {
        Set<String> out = new LinkedHashSet<>(TribesAgents.IN_TURN);
        out.remove("comma-z0-coarse");
        out.add("comma-short3-coarse");
        out.addAll(TribesAgents.MUTATION);
        out.addAll(TribesAgents.SELF_LEARNING);
        return out;
    }

    @Test
    public void theThirtySecondPositionsAreTheSplitRulesOwnHalfOfTheDevelopmentHalf() throws Exception {
        Set<Integer> dev = Split.ids(PORTFOLIO, Split.Half.DEV);
        List<Manifest.Row> rows = new ArrayList<>();
        for (Manifest.Row r : Manifest.read(PORTFOLIO).rows()) if (dev.contains(r.id)) rows.add(r);
        Split.Deal deal = Split.deal(rows);
        JsonObject x = read(LONG);
        assertEquals("dev", x.getString("split", null));
        Set<Integer> ids = new TreeSet<>();
        for (JsonValue v : x.get("ids").asArray()) ids.add(v.asInt());
        assertEquals(100, x.get("ids").asArray().size());
        assertEquals("Split.deal of the development half, its first half", new TreeSet<>(deal.ids(Split.Half.DEV)), ids);
        int single = 0;
        for (Manifest.Row r : deal.dev) if (r.source.equals("single")) single++;
        assertEquals("single-player and dual positions alike", 50, single);
    }

    @Test
    public void theScreenPlaysTheTwentyNineNewArmsAndTakesTheRestFromThePublishedRuns() throws Exception {
        JsonObject x = read(SCREEN);
        assertEquals("dev", x.getString("split", null));
        assertEquals("r7615-in-turn", x.getString("weights", null));
        assertNull("the whole development half", x.get("ids"));
        List<String> arms = strings(x.get("arms"));
        assertEquals(new LinkedHashSet<>(arms).size(), arms.size());
        Set<String> played = new LinkedHashSet<>(arms);
        played.removeAll(newArms());
        assertEquals(29, newArms().size());
        assertTrue("every new arm is played", arms.containsAll(newArms()));
        Weights w = Weights.load(FROM);
        Path reference = Paths.get("games", "tribes", "results", "reference", "r7615", "all");
        Path abEmcts = Paths.get("games", "tribes", "results", "ab-emcts-vs-oep", "r7615", "dev");
        for (String reused : played)
            for (long l : PUBLISHED)
                assertTrue(reused + "@" + l + ": a published R7615 run holds it, to be taken",
                        Files.isRegularFile(reference.resolve(reused).resolve(l + "cu").resolve("turns.csv"))
                                || Files.isRegularFile(abEmcts.resolve(reused).resolve(l + "cu").resolve("turns.csv")));
        for (String fresh : newArms()) assertFalse(fresh + " is new", w.has(Profile.key(fresh, PUBLISHED[0])));
        List<String> levels = new ArrayList<>();
        for (JsonValue l : x.get("levels").asArray()) levels.add(l.asObject().getString("budget", null) + "x" + l.asObject().getInt("repetitions", 0));
        assertEquals(Arrays.asList("500msx4", "1000msx4", "5000msx3"), levels);
        for (JsonValue c : x.get("contrasts").asArray()) {
            assertTrue(arms.contains(c.asObject().getString("a", null)));
            assertTrue(arms.contains(c.asObject().getString("b", null)));
        }
    }

    @Test
    public void thirtySecondsIsTheThirteenChosenArmsAndTheBaselineWhoseLadderReachesIt() throws Exception {
        JsonObject x = read(LONG);
        assertEquals("r7615-in-turn", x.getString("weights", null));
        assertEquals("emcts", x.getString("baseline", null));
        List<JsonValue> levels = x.get("levels").asArray().values();
        JsonObject first = levels.get(0).asObject();
        assertEquals(LEVEL, first.getLong("budget", 0));
        assertEquals(2, first.getInt("repetitions", 0));
        assertNull("every arm at 30 s", first.get("arms"));
        assertEquals(new TreeSet<>(AT_30S), new TreeSet<>(strings(x.get("arms"))));
        assertEquals("the baseline at the published levels, from the published reference run", 4, levels.size());
        for (int i = 1; i < 4; i++) assertEquals(Arrays.asList("emcts"), strings(levels.get(i).asObject().get("arms")));
    }

    @Test
    public void theSevenSittingsPriceExactlyWhatTheSpecsPlayAndThePublishedRunsDoNotHold() throws Exception {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(SITTINGS)) { s.sorted().forEach(files::add); }
        assertEquals(7, files.size());
        Set<String> published = new LinkedHashSet<>(), long30 = new LinkedHashSet<>();
        int nPublished = 0, nLong = 0;
        for (Path f : files) {
            JsonObject c = read(f);
            assertEquals(f + ": one currency", "r7615-in-turn", c.getString("name", null));
            assertEquals(f + ": a copy of the published AB-EMCTS currency", "r7615-ab-emcts", c.getString("from", null));
            assertEquals(0, c.getInt("soak-minutes", -1));
            List<String> arms = strings(c.get("arms"));
            List<String> levels = new ArrayList<>();
            for (JsonValue l : c.get("levels").asArray()) levels.add(l.isString() ? l.asString() : String.valueOf(l.asLong()));
            if (f.getFileName().toString().startsWith("published-")) {
                nPublished++;
                assertEquals(Arrays.asList("500ms", "1000ms", "5000ms"), levels);
                assertNull(c.get("gain-levels"));
                for (String a : arms) assertTrue(a + " in one sitting", published.add(a));
            } else {
                nLong++;
                assertEquals(Arrays.asList(String.valueOf(LEVEL)), levels);
                assertTrue("a level the currency gains, on purpose", c.getBoolean("gain-levels", false));
                for (String a : arms) assertTrue(a + " in one sitting", long30.add(a));
            }
        }
        assertEquals(3, nPublished);
        assertEquals(4, nLong);
        assertEquals(newArms(), published);
        assertEquals(new TreeSet<>(AT_30S), new TreeSet<>(long30));
    }
}
