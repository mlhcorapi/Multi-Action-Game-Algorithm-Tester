package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.oep.Oep;
import magat.agents.random.RandomAgent;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** OEP on the toy game: deterministic in its seed, stops at its budget, initialization included, and beats the random floor. */
public class OepToyTest {

    private final ToyGame game = new ToyGame();
    private final AgentFactory<ToyGame.State, ToyGame.Move> oep = Oep.factory();
    private List<Scenario<ToyGame.State>> positions;

    @Before
    public void setUp() {
        Meter.install(null);
        positions = game.positions(6);
    }

    @After
    public void tearDown() {
        Meter.install(null);
        Meter.useProfile(null);
    }

    private TurnRecord turn(AgentFactory<ToyGame.State, ToyGame.Move> arm, int position, int visit, long budget) {
        return Episode.run(game, positions.get(position), visit, arm, budget, Weights.DEFAULT, "toy");
    }

    @Test
    public void spendsItsBudgetAndRecordsItsOperations() {
        TurnRecord r = turn(oep, 0, 0, 1_500);
        double spent = r.units + r.initUnits;
        assertTrue("the turn spends its budget (" + spent + ")", spent >= 1_500);
        assertTrue("and overshoots by one turn played at most", spent < 1_500 + 10);
        assertEquals("the initial population is the initialization", 50, r.initEvals);
        assertEquals(Oep.OPERATIONS, new ArrayList<>(r.operations.keySet()));
        assertEquals("one evaluation per turn played", r.nodes, r.initEvals + r.evals);
        assertEquals(r.nodes, r.initOperations.get("oep.genome") + r.operations.get("oep.genome"));
        assertEquals(r.telemetry.get("generations").longValue(), r.operations.get("oep.generation").longValue());
        assertTrue(r.telemetry.get("generations") > 5);
        assertEquals(r.nodes, r.unique + r.duplicates);
        assertTrue("it keeps the fittest turn, so it never ends below its initial population's best", r.finalValue >= r.initial);
    }

    @Test
    public void itsInitializationStopsWhenTheBudgetRunsOut() {
        TurnRecord r = turn(oep, 0, 0, 40);
        assertTrue("a budget of 40 units buys a few random turns of the 50 (" + r.initEvals + ")", r.initEvals > 1 && r.initEvals < 50);
        assertTrue("and no search", r.evals == 0 && r.telemetry.get("generations") == 0);
        assertTrue("the turn stops one turn played past its budget at most", r.units + r.initUnits < 40 + 10);
    }

    @Test
    public void isDeterministicInItsSeedAndPairedAcrossVisits() {
        Signature a = Signature.of(turn(oep, 1, 3, 1_500));
        Signature b = Signature.of(turn(oep, 1, 3, 1_500));
        assertTrue(a.differences(b, 0).isEmpty());
        assertFalse("a different visit is a different search", a.differences(Signature.of(turn(oep, 1, 4, 1_500)), 0).isEmpty());
    }

    @Test
    public void beatsTheRandomFloorAndDoesNotGetWorseWithMoreBudget() {
        double small = 0, large = 0, random = 0;
        for (int p = 0; p < positions.size(); p++) {
            for (int v = 0; v < 4; v++) {
                small += turn(oep, p, v, 400).finalValue;
                large += turn(oep, p, v, 1_500).finalValue;
                random += turn(RandomAgent.factory(), p, v, 1_500).finalValue;
            }
        }
        assertTrue("oep " + large + " against random " + random, large > random);
        assertTrue("at 1,500 units " + large + ", at 400 " + small, large >= small);
    }
}
