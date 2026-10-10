package magat.games.tribes;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import magat.agent.TurnBudget;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Manifest;
import magat.run.Experiment;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The published levels (games/tribes/levels.json, measured on the R7615 by calibrate-time) are 500, 1000 and
 * 5000 ms of a whole emcts turn within the tolerance, and the reference ladder runs at exactly those levels.
 */
public class PublishedLevelsTest {

    private static final Path LEVELS = Paths.get("games", "tribes", "levels.json");
    private static final Path LADDER = Paths.get("experiments", "tribes", "reference-ladder.json");
    private static final Path RECORD = Paths.get("games", "tribes", "levels-record");
    private static final long[] UNITS = {9_500L, 20_000L, 101_500L};
    private static final int[] TIMES = {500, 1000, 5000};

    private static JsonObject levels() throws Exception {
        return Json.parse(new String(Files.readAllBytes(LEVELS), StandardCharsets.UTF_8)).asObject();
    }

    @Test
    public void areFiveHundredOneThousandAndFiveThousandMillisecondsOnTheR7615() throws Exception {
        JsonObject doc = levels();
        assertEquals("tribes", doc.getString("game", null));
        assertEquals("emcts", doc.getString("reference_arm", null));
        assertEquals("in-budget", doc.getString("initialization", null));
        assertTrue(doc.getString("machine", "").startsWith("AMD EPYC 9534"));
        assertTrue("the engine the levels were timed on", doc.getString("engine", "").startsWith("828b130"));
        assertTrue(doc.getBoolean("converged", false));
        assertEquals(64, doc.getInt("positions", 0));
        assertEquals(500, doc.getInt("rounding", 0));
        assertNull("not a toy", doc.get("toy"));
        double tolerance = doc.getDouble("tolerance", 0);
        assertEquals(0.05, tolerance, 1e-12);
        List<JsonValue> rows = doc.get("levels").asArray().values();
        assertEquals(UNITS.length, rows.size());
        for (int i = 0; i < UNITS.length; i++) {
            JsonObject lv = rows.get(i).asObject();
            assertEquals(TIMES[i], lv.getInt("time_ms", 0));
            assertEquals(UNITS[i], lv.getLong("units", 0));
            assertEquals("rounded to 500 units", 0, lv.getLong("units", 0) % 500);
            double mean = lv.get("measured").asObject().getDouble("mean_ms", 0);
            assertEquals(TIMES[i] + " ms", 1.0, mean / TIMES[i], tolerance);
            assertEquals(mean / TIMES[i], lv.getDouble("ratio", 0), 1e-4);
        }
    }

    @Test
    public void theRecordsLastWorkingCurrencyPricesTheReferenceArmAtThem() throws Exception {
        Weights w = Weights.load(RECORD.resolve("iter-4").resolve("compute_weights.properties"));
        assertEquals("tribes", w.game());
        assertEquals("emcts@9500", w.reference());
        for (long u : UNITS) assertTrue("emcts@" + u, w.has(Profile.key("emcts", u)));
        assertEquals("16,16,16", w.turnsPerRep());
        assertEquals("in-budget", w.header().get("initialization"));
        assertEquals("measured on the published portfolio", Manifest.read(Paths.get("games", "tribes", "portfolio", "v1")).identity(),
                w.portfolioIdentity());
    }

    @Test
    public void theReferenceLadderRunsAtThePublishedLevels() throws Exception {
        Experiment x = Experiment.load(LADDER);
        assertEquals(TurnBudget.Initialization.IN_BUDGET, x.initialization);
        assertTrue(x.initializationDeclared);
        List<Long> budgets = new ArrayList<>();
        for (Experiment.Level l : x.levels) budgets.add(l.budget);
        budgets.sort(null);
        List<Long> published = new ArrayList<>();
        for (JsonValue v : levels().get("levels").asArray()) published.add(v.asObject().getLong("units", 0));
        assertEquals(published, budgets);
    }
}
