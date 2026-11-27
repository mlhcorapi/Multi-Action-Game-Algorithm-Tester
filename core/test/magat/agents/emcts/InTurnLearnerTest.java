package magat.agents.emcts;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** The in-turn learners on their own: a planted signal learned, a cold start that ties, determinism, robustness. */
public class InTurnLearnerTest {

    private static final String[] GOOD = {"ATTACK", "ATTACK:RIDER", "Attack 3 -> 7"};
    private static final String[] BAD = {"MOVE", "MOVE:WARRIOR", "Move 3 -> (4,5)"};
    private static final String[] UNSEEN = {"BUILD", "BUILD:FARM", "Build FARM at (2,2)"};

    private static List<InTurnLearner> all() {
        return Arrays.asList(new HierTable(5.0), new LinearLearner(), new MlpLearner(42L));
    }

    /** Credits GOOD with about +10 and BAD with about -10, interleaved, with noise. */
    private static void plant(InTurnLearner l, int n, long seed) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            l.credit(GOOD, 0.3, i / (double) n, 10 + 4 * r.nextGaussian());
            l.credit(BAD, 0.6, i / (double) n, -10 + 4 * r.nextGaussian());
        }
    }

    @Test
    public void everyLearnerRanksAPlantedSignal() {
        for (InTurnLearner l : all()) {
            l.reset();
            plant(l, 150, 1);
            assertTrue(l.getClass().getSimpleName() + " ranks the good action above the bad",
                    l.estimate(GOOD, 0.3, 1) > l.estimate(BAD, 0.6, 1));
            assertTrue(l.getClass().getSimpleName() + " reports its error", l.meanSquaredError() >= 0);
        }
    }

    @Test
    public void anEmptyLearnerTiesEveryAction() {
        for (InTurnLearner l : all()) {
            l.reset();
            double a = l.estimate(GOOD, 0.1, 0.0), b = l.estimate(BAD, 0.9, 0.5), c = l.estimate(UNSEEN, 0.5, 0.2);
            assertEquals(l.getClass().getSimpleName(), a, b, 0.0);
            assertEquals(l.getClass().getSimpleName(), a, c, 0.0);
        }
    }

    @Test
    public void theSameExamplesGiveTheSameEstimatesAndAResetForgetsThem() {
        for (int k = 0; k < 3; k++) {
            InTurnLearner x = all().get(k), y = all().get(k);
            x.reset();
            y.reset();
            plant(x, 60, 7);
            plant(y, 60, 7);
            assertEquals(x.estimate(UNSEEN, 0.4, 0.4), y.estimate(UNSEEN, 0.4, 0.4), 0.0);
            assertEquals(x.estimate(GOOD, 0.4, 0.4), y.estimate(GOOD, 0.4, 0.4), 0.0);
            x.reset();
            assertEquals("a reset forgets", x.estimate(GOOD, 0.4, 0.4), x.estimate(BAD, 0.4, 0.4), 0.0);
        }
    }

    @Test
    public void aGameEndingOutlierDoesNotDerailTheModels() {
        // A plan that ends the game is worth about 2e9 in Tribes: the models' targets are standardized and clipped.
        for (InTurnLearner l : Arrays.asList(new LinearLearner(), new MlpLearner(3L))) {
            l.reset();
            plant(l, 20, 2);
            l.credit(UNSEEN, 0.5, 0.5, 2.0e9);
            plant(l, 150, 3);
            double g = l.estimate(GOOD, 0.3, 1), b = l.estimate(BAD, 0.6, 1);
            assertTrue(l.getClass().getSimpleName() + " stays finite", Double.isFinite(g) && Double.isFinite(b));
            assertTrue(l.getClass().getSimpleName() + " still ranks the signal", g > b);
        }
    }

    @Test
    public void theHierarchicalTableBacksOffToTheCoarserClass() {
        HierTable t = new HierTable(5.0);
        t.reset();
        for (int i = 0; i < 40; i++) t.credit(GOOD, 0, 0, 10.0);
        // A fine class never seen, under a mid class that has been: its estimate is the mid class's.
        String[] sibling = {"ATTACK", "ATTACK:RIDER", "Attack 3 -> 9"};
        assertEquals(t.estimate(new String[]{"ATTACK", "ATTACK:RIDER", null}, 0, 0), t.estimate(sibling, 0, 0), 1e-12);
        assertTrue("it inherits the mid class's good estimate", t.estimate(sibling, 0, 0) > 5.0);
        // A fine class seen often comes to its own mean: shrinkage (sum + m * parent) / (n + m).
        assertTrue(t.estimate(GOOD, 0, 0) > t.estimate(sibling, 0, 0) - 1e-12);
    }

    @Test
    public void theEncodingIsTheSameOnEveryJvm() {
        // String.hashCode is fixed by the language: these indices are part of the arm's definition.
        assertArrayEquals(new int[]{
                Math.floorMod("ATTACK".hashCode(), 64),
                64 + Math.floorMod("ATTACK:RIDER".hashCode(), 64),
                128 + Math.floorMod("Attack 3 -> 7".hashCode(), 256)}, ClassFeatures.active(GOOD));
        assertEquals(386, ClassFeatures.SIZE);
    }
}
