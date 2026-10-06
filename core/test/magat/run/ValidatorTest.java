package magat.run;

import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.meter.Weights;
import magat.portfolio.Split;
import magat.testgame.ToyGame;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ValidatorTest {

    private static AgentRegistry<ToyGame.State, ToyGame.Move> registry() {
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(EmctsFactory.comma(3));
        AgentFactory<ToyGame.State, ToyGame.Move> plain = EmctsFactory.plain();
        // An arm with a mechanism whose attribution control is emcts-comma.
        r.register(new AgentFactory<ToyGame.State, ToyGame.Move>() {
            @Override public String id() { return "emcts-mech"; }
            @Override public SearchAgent<ToyGame.State, ToyGame.Move> create(long seed, TurnBudget b) { return plain.create(seed, b); }
            @Override public String attributionControl() { return "emcts-comma"; }
        });
        return r;
    }

    private static Weights priced(String... arms) {
        StringBuilder sb = new StringBuilder("magat.weights.version = 1\nreference = emcts@300\n");
        for (String a : arms) for (int b : new int[]{300, 600})
            for (String op : new String[]{"wAdvance", "wCopy", "wEval"})
                sb.append("profile.").append(a).append('@').append(b).append('.').append(op).append(" = 0.5\n");
        return Weights.parse(null, Arrays.asList(sb.toString().split("\n")));
    }

    private static Experiment spec(String arms, String sweeps, String extra) {
        String json = "{ \"name\": \"t\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1}, {\"budget\": 600, \"visits\": 1} ],"
                + " \"arms\": [" + arms + "], \"sweeps\": [" + sweeps + "]" + extra + " }";
        return Experiment.parse(null, json, "x");
    }

    private static Validator.Report check(Experiment x, Split.Half half, Weights w, String frozen) {
        return Validator.check(x, "toy", registry(), half, w, frozen, false);
    }

    private static boolean says(Validator.Report r, String needle, boolean error) {
        for (String s : error ? r.errors : r.warnings) if (s.contains(needle)) return true;
        return false;
    }

    @Test
    public void aSweepWithoutItsNullIsRejected() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-comma\"}", "{\"parameter\":\"k\",\"arms\":[\"emcts-comma\"]}", "");
        Validator.Report r = check(x, Split.Half.DEV, priced("emcts", "emcts-comma"), null);
        assertFalse(r.ok());
        assertTrue(r.errors.toString(), says(r, "has no null", true));

        Experiment outside = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-comma\"}",
                "{\"parameter\":\"k\",\"arms\":[\"emcts-comma\"],\"null\":\"random\"}", "");
        assertTrue(says(check(outside, Split.Half.DEV, priced("emcts", "emcts-comma"), null), "is not in the grid", true));

        Experiment good = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-comma\"}",
                "{\"parameter\":\"k\",\"arms\":[\"emcts-comma\"],\"null\":\"emcts\"}", "");
        assertTrue(check(good, Split.Half.DEV, priced("emcts", "emcts-comma"), null).ok());
    }

    @Test
    public void aVersionTwoCurrencyMustPriceEveryOperationAnArmDeclares() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-comma\"}", "{\"parameter\":\"k\",\"arms\":[\"emcts-comma\"],\"null\":\"emcts\"}", "");
        StringBuilder sb = new StringBuilder("magat.weights.version = 2\nreference = emcts@300\n");
        for (String a : new String[]{"emcts", "emcts-comma"})
            for (int b : new int[]{300, 600}) {
                String p = "profile." + a + "@" + b + ".";
                sb.append(p).append("w.advance = 0.5\n").append(p).append("w.copy = 0.5\n").append(p).append("w.eval = 0.5\n");
                for (String op : magat.agents.emcts.Emcts.OPERATIONS)
                    if (!(a.equals("emcts-comma") && b == 600 && op.equals("emcts.burn"))) sb.append(p).append("w.").append(op).append(" = 0.1\n");
            }
        Validator.Report r = check(x, Split.Half.DEV, Weights.parse(null, Arrays.asList(sb.toString().split("\n"))), null);
        assertFalse(r.ok());
        assertTrue(r.errors.toString(), says(r, "emcts-comma@600.emcts.burn", true));
        // A version-1 currency prices the forward model only: the operations run free, which is said, not refused.
        Validator.Report v1 = check(x, Split.Half.DEV, priced("emcts", "emcts-comma"), null);
        assertTrue(v1.ok());
        assertTrue(v1.warnings.toString(), says(v1, "run free", false));
    }

    @Test
    public void aMechanismNeedsItsAttributionControlInTheGrid() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-mech\"}", "", "");
        Weights w = priced("emcts", "emcts-mech");
        Validator.Report dev = check(x, Split.Half.DEV, w, null);
        assertTrue(dev.ok());
        assertTrue(says(dev, "attribution control emcts-comma", false));
        assertTrue(says(check(x, Split.Half.HOLDOUT, w, "frozen.txt"), "attribution control emcts-comma", true));
    }

    @Test
    public void theHoldoutNeedsFrozenChoicesAndABaselineCurve() {
        Experiment x = spec("{\"id\":\"emcts\"}", "", "");
        assertTrue(says(check(x, Split.Half.HOLDOUT, priced("emcts"), null), "frozen-choices", true));
        assertTrue(check(x, Split.Half.HOLDOUT, priced("emcts"), "frozen.txt").ok());

        Experiment oneLevel = Experiment.parse(null, "{ \"name\": \"t\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1} ], \"arms\": [\"emcts\"] }", "x");
        assertTrue(says(check(oneLevel, Split.Half.HOLDOUT, priced("emcts"), "f"), "budget multipliers", true));
        assertTrue(check(oneLevel, Split.Half.DEV, priced("emcts"), null).ok());
        assertTrue(says(check(oneLevel, Split.Half.DEV, priced("emcts"), null), "budget multipliers", false));
    }

    @Test
    public void everyArmIsRegisteredAndPricedButTheFloorNeedsNoPrice() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"random\"},{\"id\":\"nope\"}", "", "");
        Validator.Report r = check(x, Split.Half.DEV, priced("emcts"), null);
        assertTrue(says(r, "nope is not registered", true));
        assertFalse(says(r, "random@", true));
        Experiment unpriced = spec("{\"id\":\"emcts\"},{\"id\":\"emcts-comma\"}", "", "");
        assertTrue(says(check(unpriced, Split.Half.DEV, priced("emcts"), null), "emcts-comma@300", true));
    }

    @Test
    public void typosAndDuplicatesAreCaught() {
        Experiment x = spec("{\"id\":\"emcts\"},{\"id\":\"emcts\"}", "", ", \"sweep\": []");
        Validator.Report r = check(x, Split.Half.DEV, priced("emcts"), null);
        assertTrue(says(r, "listed twice", true));
        assertTrue(says(r, "unknown key 'sweep'", false));
    }
}
