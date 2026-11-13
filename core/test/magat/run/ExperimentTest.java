package magat.run;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The spec's short forms read as the long ones, and what cannot be read is a problem the validator reports, not a crash. */
public class ExperimentTest {

    private static final Map<String, Long> NAMED = new LinkedHashMap<>();
    static {
        NAMED.put("500ms", 9500L);
        NAMED.put("1000ms", 20000L);
    }

    private static Experiment spec(String body) {
        return Experiment.parse(null, "{ \"name\": \"t\", \"game\": \"toy\", \"baseline\": \"emcts\", " + body + " }", "x", NAMED);
    }

    private static List<String> pairs(Experiment x) {
        List<String> out = new ArrayList<>();
        for (Experiment.Contrast c : x.contrasts) out.add(c.a + "-" + c.b);
        return out;
    }

    @Test
    public void theShortFormsReadAsTheLongOnes() {
        Experiment shortForm = spec("\"arms\": [\"emcts\", \"random\"], \"levels\": [300, \"600\", \"500ms\", {\"budget\": \"1000 ms\", "
                + "\"repetitions\": 3}], \"repetitions\": 2");
        assertTrue(shortForm.problems.toString(), shortForm.problems.isEmpty());
        assertTrue(shortForm.unknownKeys.isEmpty());
        assertEquals(Arrays.asList("emcts", "random"), shortForm.armIds());
        long[] budgets = new long[4];
        int[] visits = new int[4];
        for (int i = 0; i < 4; i++) {
            budgets[i] = shortForm.levels.get(i).budget;
            visits[i] = shortForm.levels.get(i).visits;
        }
        assertArrayEquals(new long[]{300, 600, 9500, 20000}, budgets);
        assertArrayEquals("a level's own repetitions win over the spec's", new int[]{2, 2, 2, 3}, visits);
        assertEquals("500ms", shortForm.levels.get(2).written);

        Experiment longForm = spec("\"arms\": [{\"id\": \"emcts\"}, {\"id\": \"random\"}], "
                + "\"levels\": [{\"budget\": 300, \"visits\": 2}, {\"budget\": 600, \"visits\": 2}]");
        assertTrue(longForm.problems.isEmpty());
        assertEquals(2, longForm.levels.get(1).visits);
        assertFalse("no filter, no count: the whole split", longForm.selects());
        assertEquals("all", longForm.selection());
    }

    @Test
    public void withoutContrastsEveryArmMeetsEveryArmWrittenBeforeIt() {
        // The reference experiment's own ten contrasts, in its order: the default reproduces them.
        Experiment x = spec("\"arms\": [\"random\", \"mcts\", \"emcts\", \"oep\", \"tbets\"], \"levels\": [300], \"repetitions\": 1");
        assertTrue(x.contrastsByDefault);
        assertEquals(Arrays.asList("mcts-random", "emcts-random", "oep-random", "tbets-random", "emcts-mcts", "oep-mcts",
                "tbets-mcts", "oep-emcts", "tbets-emcts", "tbets-oep"), pairs(x));
        assertEquals("tbets against oep", x.contrasts.get(9).label);
        Experiment none = spec("\"arms\": [\"random\", \"emcts\"], \"levels\": [300], \"repetitions\": 1, \"contrasts\": []");
        assertFalse(none.contrastsByDefault);
        assertTrue("an empty list asks for none", none.contrasts.isEmpty());
    }

    @Test
    public void aNamedLevelMustBeAPublishedOne() {
        Experiment unknown = spec("\"arms\": [\"emcts\"], \"levels\": [\"750ms\"], \"repetitions\": 1");
        assertTrue(unknown.problems.toString(), unknown.problems.get(0).contains("500ms, 1000ms"));
        Experiment unnamed = Experiment.parse(null, "{ \"name\": \"t\", \"game\": \"toy\", \"arms\": [\"emcts\"], \"levels\": [\"500ms\"] }", "x");
        assertTrue(unnamed.problems.toString(), unnamed.problems.get(0).contains("no published levels"));
    }

    @Test
    public void whatCannotBeReadIsAProblemNotACrash() {
        Experiment x = spec("\"arms\": [\"emcts\", 7], \"levels\": [{\"budget\": 300, \"visits\": 1, \"repetitions\": 2}, 2.5, true],"
                + " \"repetitions\": \"many\", \"phases\": {\"from\": 1}, \"sources\": \"single\", \"ids\": [1, \"two\"]");
        String all = x.problems.toString();
        for (String needle : new String[]{"an arm is 7", "both visits and repetitions", "2.5 is not a positive whole number",
                "a level is true", "repetitions is \"many\"", "not 'from'", "sources is \"single\", not a list", "ids is \"two\""})
            assertTrue(needle + " in " + all, all.contains(needle));
        Experiment noReps = spec("\"arms\": [\"emcts\"], \"levels\": [300]");
        assertEquals(-1, noReps.levels.get(0).visits);
    }

    @Test
    public void theSelectionIsRecordedInOneLine() {
        Experiment x = spec("\"arms\": [\"emcts\"], \"levels\": [300], \"repetitions\": 1, \"sources\": [\"single\"],"
                + " \"phases\": {\"min\": 10, \"max\": 20}, \"ids\": [3, 1], \"scenarios\": 2");
        assertTrue(x.problems.toString(), x.problems.isEmpty());
        assertTrue(x.selects());
        assertEquals("sources=single;phases=10..20;ids=3,1;scenarios=2", x.selection());
        assertEquals("phases=12,14", spec("\"arms\": [\"emcts\"], \"phases\": [14, 12]").selection());
        assertTrue(spec("\"arms\": [\"emcts\"], \"phases\": {\"min\": 20, \"max\": 10}").problems.toString().contains("above max"));
    }

    @Test
    public void thePublishedLevelsAreNamedByTheirMilliseconds() throws Exception {
        Path f = Files.createTempFile("levels", ".json");
        Files.write(f, ("{ \"levels\": [ { \"time_ms\": 500, \"units\": 9500 }, { \"time_ms\": 5000, \"units\": 101500 } ] }")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Long> named = Experiment.levelNames(f);
        assertEquals(Long.valueOf(9500), named.get("500ms"));
        assertEquals(Long.valueOf(101500), named.get("5000ms"));
        assertTrue(Experiment.levelNames(null).isEmpty());
        assertTrue(Experiment.levelNames(f.resolveSibling("no-such-levels.json")).isEmpty());
    }
}
