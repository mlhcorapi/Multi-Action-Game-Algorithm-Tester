package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.agents.emcts.Emcts;
import magat.agents.emcts.EmctsFactory;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** EMCTS declares its own operations, records exactly those, and is priced by them under a version-2 currency only. */
public class OperationsToyTest {

    private final ToyGame game = new ToyGame();
    private List<Scenario<ToyGame.State>> positions;

    @Before
    public void setUp() {
        Meter.install(null);
        positions = game.positions(3);
    }

    @After
    public void tearDown() {
        Meter.install(null);
        Meter.allowUnpriced(false);
        Meter.useProfile(null);
    }

    @Test
    public void emctsDeclaresItsOperations() {
        List<String> ops = EmctsFactory.<ToyGame.State, ToyGame.Move>plain().operations();
        assertEquals(Emcts.OPERATIONS, ops);
        for (String op : ops) assertTrue(op, Meter.OP_NAME.matcher(op).matches());
    }

    @Test
    public void aTurnRecordsExactlyItsDeclaredOperations() {
        AgentFactory<ToyGame.State, ToyGame.Move> f = EmctsFactory.plain();
        TurnRecord r = Episode.run(game, positions.get(0), 0, f, 1_500, Weights.DEFAULT, "toy");
        assertEquals(f.operations(), new ArrayList<>(r.operations.keySet()));
        assertTrue(r.operations.get("emcts.generation") > 0);
        assertTrue(r.operations.get("emcts.legal") > 0);
        assertTrue(r.operations.get("emcts.seqkey") > 0);
        assertTrue("the greedy initial plan queries legal actions in its initialization", r.initOperations.get("emcts.legal") > 0);
    }

    @Test
    public void underAVersionOneCurrencyTheOperationsChangeNothing() {
        // The same arm, the same turn, priced by the same forward-model weights from a version-1 file and
        // from a probe's triple: identical in every count and unit, because the operations weigh nothing.
        Meter.install(Weights.parse(null, Arrays.asList("magat.weights.version = 1", "reference = emcts@1500",
                "profile.emcts@1500.wAdvance = 1", "profile.emcts@1500.wCopy = 0.16", "profile.emcts@1500.wEval = 0.077")));
        TurnRecord v1 = Episode.run(game, positions.get(1), 0, EmctsFactory.plain(), 1_500);
        TurnRecord triple = Episode.run(game, positions.get(1), 0, EmctsFactory.plain(), 1_500, Weights.DEFAULT, "toy");
        assertEquals(triple.units, v1.units, 0);
        assertEquals(triple.advances, v1.advances);
        assertEquals(triple.finalValue, v1.finalValue, 0);
        assertEquals(triple.operations, v1.operations);
    }

    @Test
    public void underAVersionTwoCurrencyTheOperationsAreSpentFromTheBudget() {
        List<String> lines = new ArrayList<>(Arrays.asList("magat.weights.version = 2", "reference = emcts@1500",
                "profile.emcts@1500.w.advance = 1", "profile.emcts@1500.w.copy = 0.16", "profile.emcts@1500.w.eval = 0.077"));
        for (String op : Emcts.OPERATIONS) lines.add("profile.emcts@1500.w." + op + " = 0.5");
        Meter.install(Weights.parse(null, lines));
        TurnRecord r = Episode.run(game, positions.get(1), 0, EmctsFactory.plain(), 1_500);
        double ops = 0;
        for (long c : r.operations.values()) ops += 0.5 * c;
        assertEquals(r.advances + 0.16 * r.copies + 0.077 * r.evals + ops, r.units, 1e-6);
        TurnRecord free = Episode.run(game, positions.get(1), 0, EmctsFactory.plain(), 1_500, Weights.DEFAULT, "toy");
        assertTrue("a budget that pays for the algorithm's own work buys fewer forward-model calls",
                r.advances < free.advances);

        // A version-2 profile that leaves a declared operation unpriced is refused at the turn.
        lines.remove("profile.emcts@1500.w.emcts.burn = 0.5");
        Meter.install(Weights.parse(null, lines));
        try {
            Episode.run(game, positions.get(1), 0, EmctsFactory.plain(), 1_500);
            fail("an operation counted but not priced in a version-2 profile must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("emcts.burn"));
        }
    }

    @Test
    public void anOperationTheFactoryDoesNotDeclareIsRefused() {
        AgentFactory<ToyGame.State, ToyGame.Move> plain = EmctsFactory.plain();
        AgentFactory<ToyGame.State, ToyGame.Move> silent = new AgentFactory<ToyGame.State, ToyGame.Move>() {
            @Override public String id() { return "silent"; }
            @Override public SearchAgent<ToyGame.State, ToyGame.Move> create(long seed, TurnBudget budget) { return plain.create(seed, budget); }
            @Override public List<String> operations() { return Collections.emptyList(); }
        };
        try {
            Episode.run(game, positions.get(0), 0, silent, 1_500, Weights.DEFAULT, "toy");
            fail("a turn that records an undeclared operation must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("does not declare"));
        }
    }
}
