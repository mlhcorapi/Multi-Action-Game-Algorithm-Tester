package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.agents.mcts.Mcts;
import magat.agents.mcts.MctsParams;
import magat.agents.random.RandomAgent;
import magat.game.MeteredGame;
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

/** MCTS on the toy game: deterministic in its seed, stops at its budget, and beats the random floor. */
public class MctsToyTest {

    private final ToyGame game = new ToyGame();
    private final AgentFactory<ToyGame.State, ToyGame.Move> mcts = Mcts.factory();
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
        TurnRecord r = turn(mcts, 0, 0, 1_500);
        double spent = r.units + r.initUnits;
        assertTrue("the turn spends its budget (" + spent + ")", spent >= 1_500);
        assertTrue("and overshoots by one iteration and the replay of its plan at most", spent < 1_500 + 10);
        assertEquals("the root's evaluation is the initialization", 1, r.initEvals);
        assertEquals(Mcts.OPERATIONS, new ArrayList<>(r.operations.keySet()));
        assertEquals(r.telemetry.get("iterations").longValue(), r.operations.get("mcts.iteration").longValue());
        assertTrue(r.operations.get("mcts.legal") > 0);
        assertTrue("one node per iteration at most: a node whose turn has no other action is found a leaf when it is expanded",
                r.nodes <= r.telemetry.get("iterations") && r.nodes > r.telemetry.get("iterations") / 2);
        assertTrue("it holds a position only for the nodes it has expanded", r.telemetry.get("expanded") < r.nodes / 2.0);
    }

    @Test
    public void stopsOnceTheWholeTurnHasBeenSearched() {
        TurnRecord r = turn(mcts, 0, 0, 1_000_000);
        assertTrue("a toy turn has a few thousand partial plans (" + r.nodes + ")", r.nodes < 10_000);
        assertTrue(r.units + r.initUnits < 50_000);
        assertTrue(r.finalValue >= turn(mcts, 0, 0, 1_500).finalValue);
    }

    @Test
    public void isDeterministicInItsSeedAndPairedAcrossVisits() {
        Signature a = Signature.of(turn(mcts, 1, 3, 1_500));
        Signature b = Signature.of(turn(mcts, 1, 3, 1_500));
        assertTrue(a.differences(b, 0).isEmpty());
        assertFalse("a different visit is a different search", a.differences(Signature.of(turn(mcts, 1, 4, 1_500)), 0).isEmpty());
    }

    @Test
    public void beatsTheRandomFloorAndDoesNotGetWorseWithMoreBudget() {
        double small = 0, large = 0, random = 0;
        for (int p = 0; p < positions.size(); p++) {
            for (int v = 0; v < 4; v++) {
                small += turn(mcts, p, v, 300).finalValue;
                large += turn(mcts, p, v, 1_500).finalValue;
                random += turn(RandomAgent.factory(), p, v, 1_500).finalValue;
            }
        }
        assertTrue("mcts " + large + " against random " + random, large > random);
        assertTrue("at 1,500 units " + large + ", at 300 " + small, large >= small);
    }

    @Test
    public void playsTheMostVisitedPathThenEndsTheTurn() {
        Mcts<ToyGame.State, ToyGame.Move> agent = new Mcts<>(7, new MctsParams());
        MeteredGame<ToyGame.State, ToyGame.Move> metered = MeteredGame.of(game);
        ToyGame.State s = game.copy(positions.get(2).state);
        game.reseed(s, 7);
        Meter.declare(Mcts.OPERATIONS);
        Meter.reset();
        Meter.useWeights(Weights.DEFAULT, "toy");
        TurnRecord r = agent.plan(metered, s, game.activePlayer(s), TurnBudget.units(1_500));
        List<ToyGame.Move> plan = agent.bestSequence();
        assertEquals(r.sequenceLength, plan.size());
        assertTrue(plan.toString(), plan.get(plan.size() - 1).isEnd());
        for (int i = 0; i < plan.size() - 1; i++) assertFalse("the end of turn is not one of the tree's actions", plan.get(i).isEnd());
        assertTrue(plan.toString(), plan.size() <= ToyGame.MAX_ACTIONS_PER_TURN + 1);
    }
}
