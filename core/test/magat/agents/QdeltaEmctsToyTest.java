package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.QdeltaArms;
import magat.agents.emcts.QdeltaEmcts;
import magat.agents.emcts.QdeltaParams;
import magat.game.Granularity;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class QdeltaEmctsToyTest {

    private final ToyGame game = ToyGame.withActionClasses();
    private List<Scenario<ToyGame.State>> positions;

    /**
     * The most a comma turn passes its budget by (the stop rule in {@code Emcts}): one child's scoring, a copy,
     * the replay of a plan of five actions at most here and an evaluation, then the next child's two copies.
     */
    private static final double SCORING_COMMA = 0.16 + (ToyGame.MAX_ACTIONS_PER_TURN + 1) * 1.0 + 0.077 + 2 * 0.16 + 1e-9;

    @Before
    public void setUp() {
        Meter.install(null);
        Meter.allowUnpriced(true);
        positions = game.positions(6);
    }

    @After
    public void tearDown() {
        Meter.allowUnpriced(false);
        Meter.install(null);
        Meter.useProfile(null);
        Meter.reset();
    }

    private static double tel(TurnRecord r, String key) {
        Double v = r.telemetry.get(key);
        assertNotNull("telemetry " + key, v);
        return v;
    }

    private static long op(TurnRecord r, String op) {
        Long n = r.operations.get(op);
        return n == null ? 0 : n;
    }

    @Test
    public void theArmsAreCommaSearchesWhoseRepairEvaluatesNothing() {
        AgentFactory<ToyGame.State, ToyGame.Move> coarse = QdeltaArms.coarseFactory();
        AgentFactory<ToyGame.State, ToyGame.Move> random = QdeltaArms.randomFactory();
        assertEquals("comma-z0-coarse", coarse.id());
        assertEquals("comma-z0-random", random.id());
        long picks = 0;
        for (AgentFactory<ToyGame.State, ToyGame.Move> arm : java.util.Arrays.asList(coarse, random)) {
            for (Scenario<ToyGame.State> position : positions) {
                TurnRecord r = Episode.run(game, position, 0, arm, 2_000);
                double turn = r.units + r.initUnits;
                assertTrue(arm.id() + ": the turn reaches its budget (" + turn + ")", turn >= 2_000 * 20 / 21);
                assertTrue(arm.id() + ": and passes it by one child's scoring at most (" + turn + ")", turn < 2_000 + SCORING_COMMA);
                assertTrue(arm.id() + ": every mutation is credited", tel(r, "statUpdates") > 0);
                assertEquals(arm.id() + ": the table's updates are the operations recorded",
                        (long) tel(r, "statUpdates"), op(r, "qd.update"));
                // A child the budget ran out on was drawn and never scored: one at most, the turn's last.
                double abandoned = tel(r, "biasUniformPicks") - tel(r, "mutEvents");
                assertTrue(arm.id() + ": insertion is uniform, every mutation's (" + abandoned + ")", abandoned == 0 || abandoned == 1);
                assertEquals(arm.id() + ": the repair evaluates no candidate", 0.0, tel(r, "repairCandidateEvals"), 0.0);
                assertEquals(arm.id() + ": every replacement is one zero-evaluation pick",
                        (long) tel(r, "zeroEvalPicks"), op(r, "qd.zeropick"));
                assertTrue(arm.id() + ": the scored children's replacements among them",
                        tel(r, "mutRepairReplaced") <= tel(r, "zeroEvalPicks"));
                picks += (long) tel(r, "zeroEvalPicks");
                if (arm == random) assertEquals("the control reads no estimate", 0L, op(r, "qd.lookup"));
                else if (tel(r, "zeroEvalPicks") > 0) assertTrue("the champion reads one per candidate", op(r, "qd.lookup") > 0);
            }
        }
        assertTrue("actions go stale on these positions, so the repair is exercised", picks > 0);
    }

    @Test
    public void theRankingIsTheOnlyDifferenceBetweenTheChampionAndItsControl() {
        assertEquals("the control records no lookup",
                QdeltaEmcts.operations(QdeltaArms.coarse()).size() - 1, QdeltaEmcts.operations(QdeltaArms.random()).size());
        assertTrue(QdeltaEmcts.operations(QdeltaArms.coarse()).contains("qd.lookup"));
        QdeltaParams c = QdeltaArms.coarse(), r = QdeltaArms.random();
        assertEquals(c.selection, r.selection);
        assertEquals(c.childrenPerGeneration, r.childrenPerGeneration);
        assertEquals(c.removalCredit, r.removalCredit);
        assertEquals(c.shrinkageM, r.shrinkageM, 0.0);
        assertNotEquals(c.repairMode, r.repairMode);
    }

    @Test
    public void theSameVisitIsTheSameSearchAndTheTableChangesIt() {
        AgentFactory<ToyGame.State, ToyGame.Move> coarse = QdeltaArms.coarseFactory();
        for (int i = 0; i < positions.size(); i++) {
            Signature a = Signature.of(Episode.run(game, positions.get(i), 3, coarse, 1_500));
            Signature b = Signature.of(Episode.run(game, positions.get(i), 3, coarse, 1_500));
            assertTrue("deterministic", a.differences(b, 0).isEmpty());
        }
        // Without a single repair the arms are emcts-comma with a table beside it: the same plan, the same
        // forward-model calls. The table's own operations aside, their units differ only through the repair.
        int same = 0, repaired = 0;
        for (Scenario<ToyGame.State> position : positions) {
            TurnRecord q = Episode.run(game, position, 3, coarse, 1_500);
            TurnRecord e = Episode.run(game, position, 3, EmctsFactory.<ToyGame.State, ToyGame.Move>comma(5), 1_500);
            if (tel(q, "zeroEvalPicks") > 0 || tel(e, "mutRepairReplaced") > 0) { repaired++; continue; }
            same++;
            assertEquals("no repair: the same search", e.finalValue, q.finalValue, 0.0);
            assertEquals("no repair: the same advances", e.advances, q.advances);
        }
        assertTrue("both kinds of position occur (" + same + " without a repair, " + repaired + " with)", repaired > 0);
    }

    @Test
    public void aGameWithoutActionClassesAndAFineTableAreRefused() {
        try {
            Episode.run(new ToyGame(), positions.get(0), 0, QdeltaArms.<ToyGame.State, ToyGame.Move>coarseFactory(), 500);
            fail("the table learns over action classes");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("offers no action classes"));
        }
        QdeltaParams fine = QdeltaArms.coarse();
        fine.repairKeys = Granularity.FINE;
        try {
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1L, fine);
            fail("a fine table is archived, not ported");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("archived"));
        }
    }
}
