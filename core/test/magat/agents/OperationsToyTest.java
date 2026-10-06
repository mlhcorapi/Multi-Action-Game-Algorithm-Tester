package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.agents.emcts.Emcts;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.QdeltaArms;
import magat.agents.emcts.QdeltaEmcts;
import magat.agents.emcts.QdeltaParams;
import magat.game.Granularity;
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

/** The EMCTS family declares its own operations, records exactly those, and is priced by them under a version-2 currency only. */
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

    private static QdeltaParams params(double eps, QdeltaParams.RepairMode mode, int k) {
        QdeltaParams p = new QdeltaParams();
        p.epsilon = eps;
        p.repairMode = mode;
        p.shortlistK = k;
        p.repairKeys = Granularity.COARSE;
        return p;
    }

    private static EmctsFactory<ToyGame.State, ToyGame.Move> arm(String id, QdeltaParams p) {
        return new EmctsFactory<>(id, "", p::copy);
    }

    @Test
    public void everyArmDeclaresTheOperationsItsParametersMakeItPerform() {
        assertEquals(Emcts.OPERATIONS, EmctsFactory.<ToyGame.State, ToyGame.Move>plain().operations());
        assertEquals(Arrays.asList("emcts.generation", "emcts.legal", "emcts.check", "emcts.seqkey", "emcts.burn",
                "qd.classify", "qd.update", "qd.lookup", "qd.zeropick"),
                QdeltaEmcts.operations(params(1.0, QdeltaParams.RepairMode.ZERO_EVAL, 0)));
        assertEquals("a random shortlist reads no estimate", Arrays.asList("emcts.generation", "emcts.legal", "emcts.check",
                "emcts.seqkey", "emcts.burn", "qd.classify", "qd.update", "qd.shortlist"),
                QdeltaEmcts.operations(params(1.0, QdeltaParams.RepairMode.SHORTLIST_RANDOM, 2)));
        assertTrue(QdeltaEmcts.operations(params(0.25, QdeltaParams.RepairMode.FULL, 0)).contains("qd.lookup"));
        for (QdeltaArms.Arm a : QdeltaArms.all()) {
            List<String> ops = a.<ToyGame.State, ToyGame.Move>factory().operations();
            for (String op : ops) assertTrue(a.id + ": " + op, Meter.OP_NAME.matcher(op).matches());
            assertEquals(a.id, Emcts.OPERATIONS, ops.subList(0, Emcts.OPERATIONS.size()));
        }
    }

    @Test
    public void aTurnRecordsExactlyItsDeclaredOperations() {
        for (AgentFactory<ToyGame.State, ToyGame.Move> f : Arrays.asList(EmctsFactory.<ToyGame.State, ToyGame.Move>plain(),
                arm("z", params(1.0, QdeltaParams.RepairMode.ZERO_EVAL, 0)),
                arm("s", params(0.25, QdeltaParams.RepairMode.SHORTLIST_K, 2)))) {
            TurnRecord r = Episode.run(game, positions.get(0), 0, f, 1_500, Weights.DEFAULT, "toy");
            assertEquals(f.id(), f.operations(), new ArrayList<>(r.operations.keySet()));
            assertTrue(f.id(), r.operations.get("emcts.generation") > 0);
            assertTrue(f.id(), r.operations.get("emcts.legal") > 0);
            assertTrue(f.id(), r.operations.get("emcts.seqkey") > 0);
            assertTrue(f.id() + ": the greedy initial plan queries legal actions outside the budget",
                    r.initOperations.get("emcts.legal") > 0);
        }
        // Every zero-evaluation pick is one qd.zeropick; a call that found no legal candidate is an operation but no pick.
        TurnRecord z = Episode.run(game, positions.get(0), 0, arm("z", params(1.0, QdeltaParams.RepairMode.ZERO_EVAL, 0)), 1_500,
                Weights.DEFAULT, "toy");
        // (On the toy game a repair rarely replaces anything, so there may be none; on Tribes there are thousands.)
        long picks = z.telemetry.get("zeroEvalPicks").longValue();
        assertEquals("every replacement is one zero-evaluation pick", z.telemetry.get("mutRepairReplaced").longValue(), picks);
        assertTrue(z.operations.get("qd.zeropick") >= picks);
        // Each ranked pick reads one estimate per candidate, so lookups outnumber picks.
        assertTrue(z.operations.get("qd.lookup") >= picks);
        // Credit: one update per child (the insertion), plus one more for the removed action where there is one.
        long children = z.telemetry.get("mutEvents").longValue();
        assertTrue(z.operations.get("qd.update") >= children && z.operations.get("qd.update") <= 2 * children);
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
