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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class EmctsToyTest {

    private final ToyGame game = new ToyGame();
    private List<Scenario<ToyGame.State>> positions;

    /**
     * The most a turn passes its budget by (the stop rule in {@code Emcts}): one child's scoring, a copy, the
     * replay of a plan of five actions at most here and an evaluation, at the measurement weights.
     */
    private static final double SCORING = 0.16 + (ToyGame.MAX_ACTIONS_PER_TURN + 1) * 1.0 + 0.077 + 1e-9;
    /** And in a comma generation, the two copies the next child makes before its first check. */
    private static final double SCORING_COMMA = SCORING + 2 * 0.16;

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

    /** A turn by the engine's accounting, which only the equivalence gate uses. */
    private TurnRecord byTheEnginesRule(long budget) {
        magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.OUTSIDE_BUDGET);
        try {
            return Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), budget);
        } finally {
            magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.IN_BUDGET);
        }
    }

    @Test
    public void aTurnStopsWhenItsBudgetIsSpentWhereTheEnginesRuleGoesOn() {
        // Here the greedy initialization takes 5 steps, about 37 units, and a generation about 13. A turn may pass
        // its budget by one child's scoring, no more.
        final double over = SCORING;
        TurnRecord whole = byTheEnginesRule(2_000);
        for (long budget : new long[]{5, 20, 40, 66, 136}) {
            TurnRecord in = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), budget);
            double turn = in.units + in.initUnits;
            assertTrue(budget + ": the turn stops once its budget is spent (" + turn + ")", turn < budget + over);
            TurnRecord engine = byTheEnginesRule(budget);
            assertEquals("the engine's rule never cuts its initialization short", whole.initUnits, engine.initUnits, 1e-9);
            assertEquals("and gives each of its 20 burns a child, past the budget", 20, engine.curve.size());
        }
        TurnRecord small = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 10);
        assertTrue("a budget below the initialization cuts it short", small.initUnits < whole.initUnits);
        assertEquals("and leaves the search nothing: no generation", 0.0, small.units, 0.0);
        assertTrue("and no burn", small.curve.isEmpty());
        assertTrue("the plan it has is played, its end of turn included", small.sequenceLength >= 2);
    }

    @Test
    public void commaSelectionMakesGenerationsOfKChildrenAndStopsInsideOne() {
        EmctsFactory<ToyGame.State, ToyGame.Move> comma = EmctsFactory.comma(5);
        assertEquals("emcts-comma", comma.id());
        TurnRecord r = Episode.run(game, positions.get(0), 0, comma, 2_000);
        double turn = r.units + r.initUnits;
        assertTrue("the turn reaches its budget (" + turn + ")", turn >= 2_000 * 20 / 21);
        assertTrue("and passes it by one child's scoring at most (" + turn + ")", turn < 2_000 + SCORING_COMMA);
        TurnRecord plain = Episode.run(game, positions.get(0), 0, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 2_000);
        assertNotEquals("comma selection is another search", plain.finalValue + ":" + plain.nodes, r.finalValue + ":" + r.nodes);
        assertTrue("deterministic", Signature.of(r).differences(Signature.of(Episode.run(game, positions.get(0), 0, comma, 2_000)), 0).isEmpty());
        // Budgets smaller than a generation of five children: the turn stops inside it.
        for (long budget : new long[]{40, 50, 66, 90}) {
            TurnRecord in = Episode.run(game, positions.get(0), 0, comma, budget);
            assertTrue(budget + ": stops inside a generation (" + (in.units + in.initUnits) + ")", in.units + in.initUnits < budget + SCORING_COMMA);
        }
        // Under the engine's rule, which only the equivalence gate plays, every generation is whole.
        magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.OUTSIDE_BUDGET);
        try {
            TurnRecord engine = Episode.run(game, positions.get(0), 0, comma, 2_000);
            assertEquals("whole generations of five", 0, (int) engine.telemetry.get("iterations").doubleValue() % 5);
        } finally {
            magat.agent.TurnBudget.setInitialization(magat.agent.TurnBudget.Initialization.IN_BUDGET);
        }
    }

    @Test
    public void aTurnPassesItsBudgetByOneChildsScoringAtMost() {
        // The stop rule's bound, swept over positions and budgets. A child built within the budget is scored
        // whole, so its scoring is the term that binds, not a greedy step's trial (1.24 units here): some turns
        // pass the budget by more than a trial, and none by more than a scoring.
        List<Scenario<ToyGame.State>> many = game.positions(8);
        for (EmctsFactory<ToyGame.State, ToyGame.Move> arm : Arrays.asList(EmctsFactory.<ToyGame.State, ToyGame.Move>plain(),
                EmctsFactory.<ToyGame.State, ToyGame.Move>comma(5))) {
            double bound = arm.id().equals("emcts") ? SCORING : SCORING_COMMA;
            int pastATrial = 0;
            for (Scenario<ToyGame.State> position : many) {
                for (long budget = 30; budget <= 1_500; budget += 37) {
                    TurnRecord r = Episode.run(game, position, 0, arm, budget);
                    double past = r.units + r.initUnits - budget;
                    assertTrue(arm.id() + " at " + budget + ": past its budget by " + past, past < bound);
                    if (past > 1.24 + 1e-9) pastATrial++;
                }
            }
            assertTrue(arm.id() + ": a child's scoring, not a trial, is the bound that binds", pastATrial > 0);
        }
    }

    @Test
    public void plusSelectionMakesOneChildPerGeneration() {
        magat.agents.emcts.EmctsParams p = new magat.agents.emcts.EmctsParams();
        p.childrenPerGeneration = 2;
        try {
            new magat.agents.emcts.Emcts<ToyGame.State, ToyGame.Move>(1L, p);
            fail("(1+1) with two children is not a search MAGAT has gated");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("one child per generation"));
        }
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
