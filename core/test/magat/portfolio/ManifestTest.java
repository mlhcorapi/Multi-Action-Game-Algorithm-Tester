package magat.portfolio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ManifestTest {

    private static List<Manifest.Row> rows() {
        List<Manifest.Row> out = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("Cities", String.valueOf(id));
            facts.put("Score", String.valueOf(100 * id));
            out.add(new Manifest.Row(id, id <= 2 ? "single" : "dual", id, 4_000_000L + id, 10 + id, 50 * id, facts));
        }
        return out;
    }

    @Test
    public void versionOneRoundTripsAndNamesItsGame() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("engine.commit", "abc123");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("single", "seeds 4000000+attempt, captures at 10..30");
        String text = Manifest.render("tribes", config, sources, "single=2 dual=2", rows());
        Manifest m = Manifest.parse(Arrays.asList(text.split("\n")));
        assertFalse(m.legacy());
        assertEquals("tribes", m.game());
        assertEquals("abc123", m.configuration().get("engine.commit"));
        assertEquals("seeds 4000000+attempt, captures at 10..30", m.sources().get("single"));
        assertEquals(Identity.of(rows()), m.identity());
        assertEquals(4, m.size());
        assertEquals(Arrays.asList("Cities", "Score"), new ArrayList<>(m.rows().get(2).facts.keySet()));
        assertEquals("render(parse(x)) == x", text, Manifest.render("tribes", m.configuration(), m.sources(), "single=2 dual=2", m.rows()));
    }

    @Test
    public void theIdentityIsTheThesisFormula() {
        // sha256("1,single,11\n2,single,12\n3,dual,13\n4,dual,14\n")
        assertEquals(Identity.sha256("1,single,11\n2,single,12\n3,dual,13\n4,dual,14\n"), Identity.of(rows()));
    }

    @Test
    public void valuesThatWouldBreakTheCsvAreRejectedAtBuildTime() {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("Name", "a,b");
        try {
            Manifest.render("toy", Collections.emptyMap(), Collections.emptyMap(), "",
                    Collections.singletonList(new Manifest.Row(1, "s", 1, 1, 1, 1, facts)));
            fail();
        } catch (IllegalStateException expected) { }
    }
}
