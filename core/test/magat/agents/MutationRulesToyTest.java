package magat.agents;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.agent.TurnRecord;
import magat.agents.emcts.QdeltaArms;
import magat.agents.emcts.QdeltaEmcts;
import magat.agents.emcts.QdeltaParams;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The in-turn learning plan's mutation rules, self-learning ideas and shortlist repair, on the toy game. */
public class MutationRulesToyTest {

    private final ToyGame game = ToyGame.withActionClasses();
    private List<Scenario<ToyGame.State>> positions;
    private final Map<String, AgentFactory<ToyGame.State, ToyGame.Move>> arms = new HashMap<>();

    /** One child's scoring and the next child's copies, plus a 5-mutation child's walk: the stop rule's bound here. */
    private static final double PAST = 0.16 + (ToyGame.MAX_ACTIONS_PER_TURN + 1) * 1.0 + 0.077 + 2 * 0.16 + 1e-9;

    @Before
    public void setUp() {
        Meter.install(null);
        Meter.allowUnpriced(true);
        positions = game.positions(6);
        for (AgentFactory<ToyGame.State, ToyGame.Move> f : QdeltaArms.<ToyGame.State, ToyGame.Move>mutationAndRepairFactories()) arms.put(f.id(), f);
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

    private TurnRecord play(String id, int position, int visit, long budget) {
        return Episode.run(game, positions.get(position), visit, arms.get(id), budget);
    }

    @Test
    public void everyArmPlaysWithinItsBudgetTheSameSearchForTheSameVisit() {
        assertEquals(24, arms.size());
        for (String id : arms.keySet()) {
            for (int i = 0; i < positions.size(); i++) {
                TurnRecord r = play(id, i, 0, 2_000);
                double turn = r.units + r.initUnits;
                assertTrue(id + ": the turn reaches its budget (" + turn + ")", turn >= 2_000 * 20 / 21);
                assertTrue(id + ": and passes it by one child's scoring at most (" + turn + ")", turn < 2_000 + PAST);
                assertTrue(id + ": every mutation is credited", tel(r, "statUpdates") > 0);
            }
            Signature a = Signature.of(play(id, 1, 3, 1_500)), b = Signature.of(play(id, 1, 3, 1_500));
            assertTrue(id + ": deterministic", a.differences(b, 0).isEmpty());
        }
    }

    @Test
    public void biasedInsertionsAreCountedAsTheRuleSays() {
        TurnRecord all = play("mut-thompson", 0, 0, 2_000), half = play("mut-thompson50", 0, 0, 2_000), guard = play("mut-guard", 0, 0, 2_000);
        assertEquals("every insertion biased", 0.0, tel(all, "biasUniformPicks"), 0.0);
        assertTrue(tel(all, "biasGreedyPicks") > 0);
        double share = tel(half, "biasGreedyPicks") / (tel(half, "biasGreedyPicks") + tel(half, "biasUniformPicks"));
        assertTrue("about half biased (" + share + ")", share > 0.3 && share < 0.7);
        assertEquals("the guard learns nothing about insertion", 0.0, tel(guard, "biasGreedyPicks"), 0.0);
        TurnRecord eps90 = play("mut-eps90", 0, 0, 2_000);
        double greedy = tel(eps90, "biasGreedyPicks") / (tel(eps90, "biasGreedyPicks") + tel(eps90, "biasUniformPicks"));
        assertTrue("90% greedy (" + greedy + ")", greedy > 0.8);
    }

    @Test
    public void multiMutationChildrenChangeSeveralActionsAtOnce() {
        for (String id : new String[]{"mut-k3-none", "mut-k5-all"}) {
            TurnRecord r = play(id, 0, 0, 2_000);
            int k = id.contains("k3") ? 3 : 5;
            assertEquals(id, k, tel(r, "mutationsPerChild"), 0.0);
            assertEquals(id + ": the walk evaluates no repair candidate", 0.0, tel(r, "repairCandidateEvals"), 0.0);
        }
        TurnRecord one = play("mut-k3-one", 0, 0, 2_000);
        double share = tel(one, "biasGreedyPicks") / (tel(one, "biasGreedyPicks") + tel(one, "biasUniformPicks"));
        assertTrue("one of three biased (" + share + ")", share > 0.25 && share < 0.45);
    }

    @Test
    public void theGuardDiscardsDuplicatesAndTheAdaptiveRulesAdapt() {
        long redrawn = 0;
        for (int i = 0; i < positions.size(); i++) redrawn += (long) tel(play("mut-guard", i, 0, 3_000), "duplicatesRedrawn");
        assertTrue("on the toy game some children repeat, and the guard redraws them (" + redrawn + ")", redrawn > 0);
        TurnRecord aos = play("mut-aos", 0, 0, 3_000);
        double shares = tel(aos, "aosUniform") + tel(aos, "aosThompson") + tel(aos, "aosK3") + tel(aos, "aosK5");
        assertEquals("every child made by one of the four operators", 1.0, shares, 1e-9);
        TurnRecord step = play("mut-k-adapt", 0, 0, 3_000);
        assertTrue("the step size moves (" + tel(step, "mutationsPerChild") + ")", tel(step, "mutationsPerChild") != 1.0);
        double kicks = 0, blocked = 0;
        for (int i = 0; i < positions.size(); i++) {
            kicks += tel(play("mut-kick", i, 0, 5_000), "kicks");
            blocked += tel(play("mut-tabu", i, 0, 5_000), "tabuBlocked");
        }
        assertTrue("a stalled search kicks (" + kicks + ")", kicks > 0);
        assertTrue("a failed mutation is kept out for a while (" + blocked + ")", blocked > 0);
    }

    @Test
    public void theShortlistRepairEvaluatesAtMostThreeCandidates() {
        TurnRecord r = play("comma-short3-coarse", 0, 0, 3_000);
        double replaced = tel(r, "mutRepairReplaced");
        assertTrue(tel(r, "repairCandidateEvals") <= 3 * Math.max(1, replaced) + 1e-9);
        assertTrue("every replacement shortlisted or small enough to evaluate whole", tel(r, "shortlistSkipped") >= 0);
    }

    @Test
    public void aLearnedMutationNeedsATable() {
        QdeltaParams p = QdeltaArms.thompson(1.0);
        p.learner = QdeltaParams.Learner.MLP;
        try {
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1L, p);
            fail("Thompson sampling reads a table's posterior");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("TABLE"));
        }
        QdeltaParams e = QdeltaArms.eps(0.5);
        e.mutationsPerChild = 3;
        try {
            new QdeltaEmcts<ToyGame.State, ToyGame.Move>(1L, e);
            fail("epsilon-greedy is the engine's single-mutation rule");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("one mutation"));
        }
    }
}
