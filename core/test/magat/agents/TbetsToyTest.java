package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.SearchAgent;
import magat.agent.Signature;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.agents.random.RandomAgent;
import magat.agents.tbets.Tbets;
import magat.agents.tbets.TbetsParams;
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

/** The TBETS port on the toy game: deterministic in its seed, stops at its budget, seeding included, and beats the random floor. */
public class TbetsToyTest {

    private final ToyGame game = new ToyGame();
    private final AgentFactory<ToyGame.State, ToyGame.Move> tbets = Tbets.factory();
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
    public void spendsItsBudgetSeedingIncludedAndRecordsItsOperations() {
        TurnRecord r = turn(tbets, 0, 0, 1_500);
        double spent = r.units + r.initUnits;
        assertTrue("the turn spends its budget (" + spent + ")", spent >= 1_500);
        assertTrue("and overshoots by one candidate turn at most", spent < 1_500 + 10);
        assertEquals("the seeding is the initialization: the two copies before it, and one per seeded turn", 22, r.initCopies);
        assertEquals(Tbets.OPERATIONS, new ArrayList<>(r.operations.keySet()));
        assertEquals(r.telemetry.get("iterations").longValue(), r.operations.get("tbets.select").longValue());
        assertEquals("at one ply every node evaluated is a turn of the root's", r.nodes, r.telemetry.get("rootChildren").longValue());
        assertEquals("and a duplicate is rejected before it is evaluated", r.nodes, r.unique);
        assertTrue(r.finalValue >= r.initial);
    }

    @Test
    public void itsSeedingStopsWhenTheBudgetRunsOut() {
        TurnRecord r = turn(tbets, 0, 0, 40);
        assertTrue("a budget of 40 units buys a few of the 20 seeded turns (" + r.initEvals + ")", r.initEvals > 1 && r.initEvals < 20);
        assertTrue("and no search", r.evals == 0 && r.telemetry.get("iterations") == 0);
        assertTrue(r.units + r.initUnits < 40 + 10);
    }

    @Test
    public void isDeterministicInItsSeedAndPairedAcrossVisits() {
        Signature a = Signature.of(turn(tbets, 1, 3, 1_500));
        Signature b = Signature.of(turn(tbets, 1, 3, 1_500));
        assertTrue(a.differences(b, 0).isEmpty());
        assertFalse("a different visit is a different search", a.differences(Signature.of(turn(tbets, 1, 4, 1_500)), 0).isEmpty());
    }

    @Test
    public void beatsTheRandomFloorAndDoesNotGetWorseWithMoreBudget() {
        double small = 0, large = 0, random = 0;
        for (int p = 0; p < positions.size(); p++) {
            for (int v = 0; v < 4; v++) {
                small += turn(tbets, p, v, 300).finalValue;
                large += turn(tbets, p, v, 1_500).finalValue;
                random += turn(RandomAgent.factory(), p, v, 1_500).finalValue;
            }
        }
        assertTrue("tbets " + large + " against random " + random, large > random);
        assertTrue("at 1,500 units " + large + ", at 300 " + small, large >= small);
    }

    @Test
    public void atTwoPliesTheTreeAlsoGrowsTheOpponentsReplies() {
        AgentFactory<ToyGame.State, ToyGame.Move> twoPly = new AgentFactory<ToyGame.State, ToyGame.Move>() {
            @Override public String id() { return "tbets-two-ply"; }
            @Override public SearchAgent<ToyGame.State, ToyGame.Move> create(long seed, TurnBudget budget) {
                TbetsParams p = new TbetsParams();
                p.maxPlies = 2;
                return new Tbets<>(seed, p);
            }
            @Override public List<String> operations() { return Tbets.OPERATIONS; }
        };
        TurnRecord r = turn(twoPly, 0, 0, 3_000);
        assertTrue("some nodes evaluated are replies to the root's turns", r.telemetry.get("rootChildren") < r.nodes);
        assertTrue(r.units + r.initUnits >= 3_000);
        assertTrue(Signature.of(r).differences(Signature.of(turn(twoPly, 0, 0, 3_000)), 0).isEmpty());
    }
}
