package magat.run;

import magat.agent.AgentRegistry;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.meter.Weights;
import magat.portfolio.Split;
import magat.testgame.ToyArms;
import magat.testgame.ToyGame;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ValidatorTest {

    private static AgentRegistry<ToyGame.State, ToyGame.Move> registry() {
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(ToyArms.emctsAlt());
        return r;
    }

    /** A currency pricing the given arms at 300 and 600: the forward model and every EMCTS operation. */
    private static Weights priced(String... arms) {
        StringBuilder sb = new StringBuilder("magat.weights.version = 2\nreference = emcts@300\n");
        for (String a : arms) for (int b : new int[]{300, 600}) {
            String p = "profile." + a + "@" + b + ".";
            for (String op : new String[]{"advance", "copy", "eval"}) sb.append(p).append("w.").append(op).append(" = 0.5\n");
            for (String op : magat.agents.emcts.Emcts.OPERATIONS) sb.append(p).append("w.").append(op).append(" = 0.1\n");
        }
        return Weights.parse(null, Arrays.asList(sb.toString().split("\n")));
    }

    private static Experiment spec(String arms, String extra) {
        String json = "{ \"name\": \"t\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1}, {\"budget\": 600, \"visits\": 1} ],"
                + " \"arms\": [" + arms + "]" + extra + " }";
        return Experiment.parse(null, json, "x");
    }

    private static Validator.Report check(Experiment x, Split.Half half, Weights w) {
        return Validator.check(x, "toy", registry(), half, w, false);
    }

    private static boolean says(Validator.Report r, String needle, boolean error) {
        for (String s : error ? r.errors : r.warnings) if (s.contains(needle)) return true;
        return false;
    }

    @Test
    public void theCurrencyMustPriceEveryOperationAnArmDeclares() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-alt\"}", "");
        StringBuilder sb = new StringBuilder("magat.weights.version = 2\nreference = emcts@300\n");
        for (String a : new String[]{"emcts", "emcts-alt"})
            for (int b : new int[]{300, 600}) {
                String p = "profile." + a + "@" + b + ".";
                sb.append(p).append("w.advance = 0.5\n").append(p).append("w.copy = 0.5\n").append(p).append("w.eval = 0.5\n");
                for (String op : magat.agents.emcts.Emcts.OPERATIONS)
                    if (!(a.equals("emcts-alt") && b == 600 && op.equals("emcts.burn"))) sb.append(p).append("w.").append(op).append(" = 0.1\n");
            }
        Validator.Report r = check(x, Split.Half.DEV, Weights.parse(null, Arrays.asList(sb.toString().split("\n"))));
        assertFalse(r.ok());
        assertTrue(r.errors.toString(), says(r, "emcts-alt@600.emcts.burn", true));
        assertTrue(check(x, Split.Half.DEV, priced("emcts", "emcts-alt")).ok());
    }

    @Test
    public void aBaselineAtOneLevelIsWarnedAbout() {
        Experiment oneLevel = Experiment.parse(null, "{ \"name\": \"t\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1} ], \"arms\": [\"emcts\"] }", "x");
        for (Split.Half half : Split.Half.values()) {
            Validator.Report r = check(oneLevel, half, priced("emcts"));
            assertTrue(r.ok());
            assertTrue(says(r, "budget multipliers", false));
        }
    }

    @Test
    public void everyArmIsRegisteredAndPricedButTheFloorNeedsNoPrice() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"random\"},{\"id\":\"nope\"}", "");
        Validator.Report r = check(x, Split.Half.DEV, priced("emcts"));
        assertTrue(says(r, "nope is not registered", true));
        assertFalse(says(r, "random@", true));
        Experiment unpriced = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-alt\"}", "");
        assertTrue(says(check(unpriced, Split.Half.DEV, priced("emcts")), "emcts-alt@300", true));
    }

    @Test
    public void typosAndDuplicatesAreCaught() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts\"}", ", \"sweep\": []");
        Validator.Report r = check(x, Split.Half.DEV, priced("emcts"));
        assertTrue(says(r, "listed twice", true));
        assertTrue(says(r, "unknown key 'sweep'", false));
    }
}
