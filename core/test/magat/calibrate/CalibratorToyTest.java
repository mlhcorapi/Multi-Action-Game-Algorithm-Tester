package magat.calibrate;

import magat.agent.AgentRegistry;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.machine.MachineProfileTest;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.*;

/** The calibrator end to end on the toy game, with the gates relaxed (toy): writes, merges, refuses, checks. */
public class CalibratorToyTest {

    private final ToyGame game = new ToyGame();

    @After
    public void tearDown() {
        Meter.install(null);
        Meter.allowUnpriced(false);
    }

    private AgentRegistry<ToyGame.State, ToyGame.Move> registry() {
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(EmctsFactory.comma(3));
        return r;
    }

    private Portfolio<ToyGame.State> portfolio() throws Exception {
        Path dir = Files.createTempDirectory("magat-calib-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 10), dir, 2);
        return Portfolio.load(game, dir);
    }

    private static Calibrator.Options toy(String... arms) {
        Calibrator.Options o = new Calibrator.Options();
        o.levels = new long[]{300, 600};
        o.arms = arms.length == 0 ? null : Arrays.asList(arms);
        o.reps = 2;
        o.warmup = 1;
        o.turns = null;
        o.toy = true;
        return o;
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void writesAFreshCurrencyThenMergesIntoIt() throws Exception {
        Portfolio<ToyGame.State> p = portfolio();
        Path out = Files.createTempDirectory("magat-calib");
        Calibrator.Options first = toy("emcts");
        first.turns = "2,2";
        Calibrator.Result r1 = Calibrator.run(game, registry(), "emcts", p, out, null, first);
        assertTrue(r1.pass);
        Path file = out.resolve(Currency.WEIGHTS_FILE);
        Weights w1 = Weights.load(file);
        assertEquals("emcts@300", w1.reference());
        assertEquals("toy", w1.game());
        assertEquals("true", w1.header().get("toy"));
        assertEquals("2,2", w1.turnsPerRep());
        assertEquals("300,600", w1.header().get("levels"));
        assertEquals(p.identity(), w1.portfolioIdentity());
        assertEquals(1.0, w1.profile("emcts@300")[0], 1e-9);
        assertTrue(w1.has("emcts@600"));
        assertFalse("random is not priced", w1.has("random@300"));
        assertTrue(Files.exists(out.resolve(Currency.PROFILES_CSV)));

        // A second arm merges into the same currency; turns come from the file.
        Calibrator.Result r2 = Calibrator.run(game, registry(), "emcts", p, out, null, toy("emcts-comma"));
        assertTrue(r2.pass);
        Weights w2 = Weights.load(file);
        assertTrue(w2.has("emcts-comma@300") && w2.has("emcts-comma@600"));
        assertEquals(w1.referenceAdvanceNanos(), w2.referenceAdvanceNanos(), 0);
        assertArrayEquals("profiles already priced are untouched", w1.profile("emcts@600"), w2.profile("emcts@600"), 0);
        assertTrue(read(file).contains("# merged "));

        // Pricing it again without --replace is refused before anything is measured.
        String before = read(file);
        try {
            Calibrator.run(game, registry(), "emcts", p, out, null, toy("emcts-comma"));
            fail("re-pricing a priced arm must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("--replace"));
        }
        assertEquals(before, read(file));
        Calibrator.Options replace = toy("emcts-comma");
        replace.replace = new LinkedHashSet<>(Collections.singletonList("emcts-comma"));
        assertTrue(Calibrator.run(game, registry(), "emcts", p, out, null, replace).pass);
        assertEquals(1, read(file).split("profile\\.emcts-comma@300\\.wAdvance", -1).length - 1);

        // Turns that differ from the file's are refused: they would measure other positions.
        Calibrator.Options wrongTurns = toy("emcts-comma");
        wrongTurns.turns = "4,4";
        wrongTurns.replace = replace.replace;
        try {
            Calibrator.run(game, registry(), "emcts", p, out, null, wrongTurns);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("turns"));
        }

        // A check measures and compares, and writes nothing.
        String beforeCheck = read(file);
        Calibrator.Options check = toy("emcts-comma");
        check.check = true;
        Calibrator.Result c = Calibrator.run(game, registry(), "emcts", p, null, file, check);
        assertNotNull(c.drift);
        assertFalse(c.drift.rows.isEmpty());
        assertEquals(beforeCheck, read(file));
    }

    @Test
    public void aCalibrationOnAnotherPortfolioIsRefused() throws Exception {
        Path out = Files.createTempDirectory("magat-calib");
        Calibrator.Options o = toy("emcts");
        o.turns = "2,2";
        Calibrator.run(game, registry(), "emcts", portfolio(), out, null, o);
        Path otherDir = Files.createTempDirectory("magat-calib-portfolio2");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), otherDir, 1);
        try {
            Calibrator.run(game, registry(), "emcts", Portfolio.load(game, otherDir), out, null, toy("emcts-comma"));
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("portfolio"));
        }
    }

    @Test
    public void positionsDependOnTheTurnIndexAloneAndLevelsShareThePool() throws Exception {
        List<magat.game.Scenario<ToyGame.State>> s = portfolio().scenarios();
        // Eight turns at the bottom level and two at the top: the top level's positions are a subset.
        for (int i = 0; i < 2; i++)
            assertSame(Calibrator.positionFor(s, i, 2), Calibrator.positionFor(s, i * 4, 8));
        assertEquals(8, Calibrator.turnsFor(40_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        assertEquals(2, Calibrator.turnsFor(400_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        assertEquals("an unlisted level takes the nearest listed level below it", 4,
                Calibrator.turnsFor(160_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        MachineProfileTest.ladderIsAscendingAndCapped();
    }
}
