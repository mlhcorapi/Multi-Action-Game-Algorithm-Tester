package magat.agents;

import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class EmctsToyTest {

    private final ToyGame game = new ToyGame();
    private List<Scenario<ToyGame.State>> positions;

    @Before
    public void setUp() {
        Meter.install(null);
        Meter.allowUnpriced(true);
        positions = game.positions(3);
    }

    @After
    public void tearDown() {
        Meter.allowUnpriced(false);
        Meter.install(null);
        Meter.useProfile(null);
        Meter.reset();
    }

    @Test
    public void inTheBudgetTheWholeTurnSpendsItOutsideTheSearchAloneDoes() {
        TurnRecord in = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 2_000);
        magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.OUTSIDE_BUDGET);
        TurnRecord out;
        try {
            out = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 2_000);
        } finally {
            magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.IN_BUDGET);
        }
        assertTrue(in.initUnits > 0);
        assertEquals("the same greedy initialization either way", in.initUnits, out.initUnits, 1e-9);
        double turn = in.units + in.initUnits;
        assertTrue("in the budget, the turn reaches it (" + turn + ")", turn >= 2_000 * 20 / 21);
        assertTrue("and overshoots by one iteration at most", turn < 2_000 + 60);
        assertTrue("outside, the search alone reaches it (" + out.units + ")", out.units >= 2_000 * 20 / 21);
        assertTrue("so the search in the budget is the shorter by the initialization", in.units < out.units);
    }

    @Test
    public void searchRunsWithinBudgetAndImproves() {
        TurnRecord r = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 2_000);
        assertTrue("final >= initial", r.finalValue >= r.initial);
        assertTrue("budget reached (" + r.units + ")", r.units >= 2_000 * 20 / 21);
        assertTrue("one iteration of overshoot at most", r.units < 2_000 + 60);
        assertEquals(r.units, r.advances * 1.0 + r.copies * 0.16 + r.evals * 0.077, 1e-9);
        assertTrue(r.nodes > 1);
        assertTrue(r.unique <= r.nodes);
        assertTrue(r.initAdvances > 0);
        assertTrue(r.initEvals > 0);
        assertTrue("20 burns at most", r.curve.size() <= 20);
        assertTrue(r.curve.size() >= 1);
        assertTrue(r.sequenceLength >= 1);
        assertTrue(r.telemetry.containsKey("mutEvents"));
    }

    @Test
    public void sameSeedSameSignature() {
        Signature a = Signature.of(Episode.run(game, positions.get(1), 3, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 1_500));
        Signature b = Signature.of(Episode.run(game, positions.get(1), 3, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 1_500));
        assertTrue(a.differences(b, 0).isEmpty());
        Signature c = Signature.of(Episode.run(game, positions.get(1), 4, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 1_500));
        assertFalse("a different visit is a different search", a.differences(c, 0).isEmpty());
    }

    @Test
    public void randomAgentProducesARecord() {
        TurnRecord r = Episode.run(game, positions.get(0), 0, RandomAgent.<ToyGame.State, ToyGame.Move>factory(), 1_000);
        assertEquals(1, r.nodes);
        assertTrue(r.sequenceLength >= 1);
        assertTrue(r.evals >= 1);
    }
}
