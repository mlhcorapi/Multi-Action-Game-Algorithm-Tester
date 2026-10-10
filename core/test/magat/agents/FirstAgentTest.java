package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.agents.first.FirstAgent;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** The example of docs/ADDING_AN_ALGORITHM.md keeps every promise the guide makes for it. */
public class FirstAgentTest {

    private final ToyGame game = new ToyGame();
    private final AgentFactory<ToyGame.State, ToyGame.Move> first = FirstAgent.factory();
    private List<Scenario<ToyGame.State>> positions;

    @Before
    public void setUp() {
        Meter.install(null);
        positions = game.positions(3);
    }

    @After
    public void tearDown() {
        Meter.install(null);
        Meter.useProfile(null);
    }

    @Test
    public void spendsItsBudgetAndRecordsExactlyTheOperationItDeclares() {
        TurnRecord r = Episode.run(game, positions.get(0), 0, first, 1_500, Weights.DEFAULT, "toy");
        assertEquals(FirstAgent.OPERATIONS, new ArrayList<>(r.operations.keySet()));
        assertEquals("one sample per node", r.nodes, r.operations.get("first.sample").longValue());
        assertTrue("the turn spends its budget, its initialization included", r.units + r.initUnits >= 1_500);
        assertTrue("it keeps the best plan, so it never ends below where it started", r.finalValue >= r.initial);
        assertEquals(r.nodes, r.unique + r.duplicates);
    }

    @Test
    public void isDeterministicInItsSeedAndPairedAcrossVisits() {
        TurnRecord a = Episode.run(game, positions.get(1), 0, first, 1_500, Weights.DEFAULT, "toy");
        TurnRecord b = Episode.run(game, positions.get(1), 0, first, 1_500, Weights.DEFAULT, "toy");
        assertEquals(a.finalValue, b.finalValue, 0);
        assertEquals(Arrays.asList(a.advances, a.copies, a.evals, a.nodes), Arrays.asList(b.advances, b.copies, b.evals, b.nodes));
    }

    @Test
    public void underAVersionTwoCurrencyItsOwnWorkIsPaidFromTheBudget() {
        List<String> lines = new ArrayList<>(Arrays.asList("magat.weights.version = 2", "reference = first@1500",
                "profile.first@1500.w.advance = 1", "profile.first@1500.w.copy = 0.16", "profile.first@1500.w.eval = 0.077",
                "profile.first@1500.w.first.sample = 2"));
        Meter.install(Weights.parse(null, lines));
        TurnRecord priced = Episode.run(game, positions.get(2), 0, first, 1_500);
        assertEquals(priced.advances + 0.16 * priced.copies + 0.077 * priced.evals + 2.0 * priced.operations.get("first.sample"),
                priced.units, 1e-6);
        TurnRecord free = Episode.run(game, positions.get(2), 0, first, 1_500, Weights.DEFAULT, "toy");
        assertTrue("paying for its own work buys fewer samples", priced.nodes < free.nodes);
    }
}
