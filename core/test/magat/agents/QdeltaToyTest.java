package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.QdeltaArms;
import magat.agents.emcts.QdeltaEmcts;
import magat.agents.emcts.QdeltaParams;
import magat.agents.emcts.Selection;
import magat.game.Granularity;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class QdeltaToyTest {

    private final ToyGame game = new ToyGame();
    private List<Scenario<ToyGame.State>> positions;

    @Before
    public void setUp() {
        Meter.install(null);
        Meter.allowUnpriced(true);
        positions = game.positions(4);
    }

    @After
    public void tearDown() {
        Meter.allowUnpriced(false);
        Meter.install(null);
        Meter.useProfile(null);
    }

    private TurnRecord run(AgentFactory<ToyGame.State, ToyGame.Move> arm, int scenario) {
        return Episode.run(game, positions.get(scenario), 0, arm, 1_500, Weights.DEFAULT, "toy");
    }

    private static EmctsFactory<ToyGame.State, ToyGame.Move> arm(String id, QdeltaParams p) {
        return new EmctsFactory<>(id, "", p::copy);
    }

    private static QdeltaParams params(double eps, QdeltaParams.RepairMode mode, int k, Granularity repairKeys) {
        QdeltaParams p = new QdeltaParams();
        p.epsilon = eps;
        p.repairMode = mode;
        p.shortlistK = k;
        p.repairKeys = repairKeys;
        return p;
    }

    @Test
    public void everyDefaultIsTheBaseline() {
        for (int s = 0; s < positions.size(); s++) {
            TurnRecord plain = run(EmctsFactory.plain(), s);
            TurnRecord q = run(arm("q", new QdeltaParams()), s);
            assertEquals(Signature.of(plain).toString(), Signature.of(q).toString());
            assertEquals(plain.duplicates, q.duplicates);
            for (String k : plain.telemetry.keySet()) assertEquals(k, plain.telemetry.get(k), q.telemetry.get(k));
            assertEquals("no biased pick at epsilon 1", 0.0, q.telemetry.get("biasGreedyPicks"), 0);
            assertEquals(q.telemetry.get("mutEvents"), q.telemetry.get("biasUniformPicks"));
        }
    }

    @Test
    public void zeroEvaluationRepairEvaluatesNoCandidate() {
        for (int s = 0; s < positions.size(); s++) {
            TurnRecord z = run(arm("z", params(1.0, QdeltaParams.RepairMode.ZERO_EVAL, 0, Granularity.COARSE)), s);
            assertEquals(0.0, z.telemetry.get("repairCandidateEvals"), 0);
            TurnRecord full = run(EmctsFactory.plain(), s);
            assertTrue(full.evals > 0);
        }
    }

    /**
     * The random control makes the same shortlist calls as the ranked mode. (Whether the two then
     * differ depends on how often repairs replace an action, which on the toy game is rarely; on Tribes
     * {@code check structure} asserts that they do.)
     */
    @Test
    public void theRandomControlShortlistsExactlyAsOftenAsTheRankedMode() {
        for (int s = 0; s < positions.size(); s++) {
            TurnRecord ranked = run(arm("r", params(1.0, QdeltaParams.RepairMode.SHORTLIST_K, 2, Granularity.MID)), s);
            TurnRecord random = run(arm("n", params(1.0, QdeltaParams.RepairMode.SHORTLIST_RANDOM, 2, Granularity.MID)), s);
            assertEquals("the same shortlist calls", ranked.telemetry.get("shortlistCalls"), random.telemetry.get("shortlistCalls"));
            assertEquals(ranked.telemetry.get("shortlistSkipped"), random.telemetry.get("shortlistSkipped"));
        }
    }

    @Test
    public void aModeAndAShortlistSizeThatDisagreeAreRefused() {
        try {
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1, params(1.0, QdeltaParams.RepairMode.SHORTLIST_K, 0, Granularity.MID));
            fail();
        } catch (IllegalArgumentException expected) { }
        try {
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1, params(1.0, QdeltaParams.RepairMode.ZERO_EVAL, 5, Granularity.MID));
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void aBiasedArmReportsItsSupport() {
        Map<String, Double> s = Episode.planned(game, positions.get(0), 0, arm("b", params(0.25, QdeltaParams.RepairMode.FULL, 0, Granularity.MID)),
                1_500).agent.support();
        assertEquals(1.0, s.get("biased"), 0);
        assertTrue(s.get("mutations") > 0);
        assertTrue(s.get("distinctInsertKeys") > 0);
        assertTrue(s.get("supportedAfterWarmup") <= s.get("picksAfterWarmup"));
    }

    @Test
    public void theWorkedResultArmsAreThirtyOneDistinctDefinitions() {
        List<QdeltaArms.Arm> arms = QdeltaArms.all();
        assertEquals(31, arms.size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (QdeltaArms.Arm a : arms) {
            assertTrue(a.id, ids.add(a.id));
            assertTrue(a.id, a.id.matches("[a-z0-9-]+"));
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1, a.params);        // each constructs: mode and K agree
        }
        assertTrue("comma-z0-fine-insfine was dropped by the thesis", !ids.contains("comma-z0-fine-insfine"));
        for (QdeltaArms.Arm a : arms)
            if (a.attribution != null) assertTrue(a.id + " -> " + a.attribution, ids.contains(a.attribution));
        assertEquals(Selection.COMMA, arms.get(arms.size() - 1).params.selection);
    }
}
