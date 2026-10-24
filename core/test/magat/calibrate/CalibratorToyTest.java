package magat.calibrate;

import magat.agent.AgentRegistry;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.machine.MachineProfileTest;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.testgame.BusyAgent;
import magat.testgame.Stdout;
import magat.testgame.ToyArms;
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
        r.register(ToyArms.emctsAlt());
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

    /** A run's provenance hashes the weights file's bytes: they must not depend on the platform that wrote them. */
    private static void assertLineFeedsOnly(Path out) throws Exception {
        for (String f : new String[]{Currency.WEIGHTS_FILE, Currency.PROFILES_CSV, Currency.OPERATIONS_CSV})
            assertFalse(f + " has a carriage return", read(out.resolve(f)).contains("\r"));
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
        // A new file is version 2: every operation the arm declares is priced, with its nanoseconds and the audit.
        assertEquals(2, w1.version());
        assertEquals(new java.util.LinkedHashSet<>(magat.agents.emcts.Emcts.OPERATIONS), w1.operations("emcts@300"));
        assertTrue(w1.prices("emcts@300").get("emcts.generation") > 0);
        assertTrue(w1.nanosOf("emcts@300").get("emcts.legal") > 0);
        assertFalse(Double.isNaN(w1.unpricedShare("emcts@300")));
        assertTrue(w1.header().get("operations").startsWith("advance,copy,eval,emcts.generation"));
        assertTrue(read(out.resolve(Currency.OPERATIONS_CSV)).contains("emcts@300,emcts,300,emcts.burn,"));
        assertLineFeedsOnly(out);

        // A second arm merges into the same currency; turns come from the file.
        Calibrator.Result r2 = Calibrator.run(game, registry(), "emcts", p, out, null, toy("emcts-alt"));
        assertTrue(r2.pass);
        assertFalse("a session of more than one cell brackets its reference", Double.isNaN(r2.bracketMove));
        assertTrue(read(out.resolve(Currency.PROFILES_CSV)).contains(",bracket,"));
        Weights w2 = Weights.load(file);
        assertTrue(w2.has("emcts-alt@300") && w2.has("emcts-alt@600"));
        assertEquals(w1.referenceAdvanceNanos(), w2.referenceAdvanceNanos(), 0);
        assertArrayEquals("profiles already priced are untouched", w1.profile("emcts@600"), w2.profile("emcts@600"), 0);
        assertEquals(w1.prices("emcts@600"), w2.prices("emcts@600"));
        assertTrue(read(file).contains("# merged "));
        assertTrue("the merged arm's operations are priced too", w2.prices("emcts-alt@300").containsKey("emcts.seqkey"));
        assertLineFeedsOnly(out);

        // Pricing it again without --replace is refused before anything is measured.
        String before = read(file);
        try {
            Calibrator.run(game, registry(), "emcts", p, out, null, toy("emcts-alt"));
            fail("re-pricing a priced arm must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("--replace"));
        }
        assertEquals(before, read(file));
        Calibrator.Options replace = toy("emcts-alt");
        replace.replace = new LinkedHashSet<>(Collections.singletonList("emcts-alt"));
        assertTrue(Calibrator.run(game, registry(), "emcts", p, out, null, replace).pass);
        assertEquals(1, read(file).split("profile\\.emcts-alt@300\\.w\\.advance =", -1).length - 1);

        // Turns that differ from the file's are refused: they would measure other positions.
        Calibrator.Options wrongTurns = toy("emcts-alt");
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
        Calibrator.Options check = toy("emcts-alt");
        check.check = true;
        // A toy drift may land either side of the gate, and the check says which: keep what it prints.
        Stdout.Captured<Calibrator.Result> checked = Stdout.capture(() -> Calibrator.run(game, registry(), "emcts", p, null, file, check));
        Calibrator.Result c = checked.value;
        assertTrue(checked.out, checked.out.contains("VERDICT: " + (c.pass ? "PASS" : "FAIL")));
        assertNotNull(c.drift);
        assertFalse(c.drift.rows.isEmpty());
        assertEquals(beforeCheck, read(file));
    }

    @Test
    public void everySessionMeasuresTheSameTurnsWhateverTheFilePrices() throws Exception {
        // The stop rule decides which turn is played. Measured under the prices of the file it extends, a
        // merge session's reference cell played a different turn from the session that wrote the file (on
        // the development laptop, 6.6% slower per advance under version 2), and the merge's ratio compared
        // unlike things. Every session, merge and check alike, measures under the fixed measurement weights.
        Portfolio<ToyGame.State> p = portfolio();
        Path out = Files.createTempDirectory("magat-calib-turns");
        Calibrator.Options first = toy("emcts");
        first.turns = "2,2";
        Calibrator.Result fresh = Calibrator.run(game, registry(), "emcts", p, out, null, first);
        Calibrator.Result merge = Calibrator.run(game, registry(), "emcts", p, out, null, toy("emcts-alt"));
        Calibrator.Options check = toy("emcts");
        check.check = true;
        Calibrator.Result checked = Stdout.capture(() ->
                Calibrator.run(game, registry(), "emcts", p, null, out.resolve(Currency.WEIGHTS_FILE), check)).value;
        for (String profile : Arrays.asList("emcts@300", "emcts@600")) {
            Cell a = measured(fresh, profile), b = measured(merge, profile), c = measured(checked, profile);
            List<Long> turns = Arrays.asList(a.advances, a.copies, a.evals);
            assertEquals(profile + " in the merge session", turns, Arrays.asList(b.advances, b.copies, b.evals));
            assertEquals(profile + " in the check", turns, Arrays.asList(c.advances, c.copies, c.evals));
        }
    }

    private static Cell measured(Calibrator.Result r, String profile) {
        for (Cell c : r.cells) if (c.profile.equals(profile) && !"bracket".equals(c.role)) return c;
        fail("no cell " + profile);
        return null;
    }

    @Test
    public void theAuditReportsWorkAnArmDoesNotDeclare() throws Exception {
        Portfolio<ToyGame.State> p = portfolio();
        Path out = Files.createTempDirectory("magat-calib-audit");
        AgentRegistry<ToyGame.State, ToyGame.Move> reg = registry();
        reg.register(BusyAgent.factory("busy-declared", true));
        reg.register(BusyAgent.factory("busy-undeclared", false));
        Calibrator.Options o = toy("emcts", "busy-declared", "busy-undeclared");
        o.levels = new long[]{300};
        o.turns = "2";
        Stdout.Captured<Calibrator.Result> calibrated = Stdout.capture(() -> Calibrator.run(game, reg, "emcts", p, out, null, o));
        Calibrator.Result r = calibrated.value;
        String log = calibrated.out;
        Cell declared = null, undeclared = null;
        for (Cell c : r.cells) {
            if (c.arm.equals("busy-declared")) declared = c;
            if (c.arm.equals("busy-undeclared")) undeclared = c;
        }
        assertTrue("the declared loop is priced: " + declared.unpriced, declared.unpriced < Calibrator.AUDIT_WARN);
        assertTrue("the undeclared loop is most of the turn: " + undeclared.unpriced, undeclared.unpriced > 0.5);
        assertTrue(log, log.contains("[warn] busy-undeclared@300"));
        assertFalse(log, log.contains("[warn] busy-declared@300"));
        assertTrue(Weights.load(out.resolve(Currency.WEIGHTS_FILE)).prices("busy-declared@300").get(BusyAgent.WORK) > 0);
        assertTrue(Weights.load(out.resolve(Currency.WEIGHTS_FILE)).unpricedShare("busy-undeclared@300") > 0.5);
    }

    @Test
    public void anEstimateTimesAFewTurnsAndWritesNothing() throws Exception {
        Path out = Files.createTempDirectory("magat-calib-estimate");
        Calibrator.Options o = toy("emcts", "emcts-alt");
        o.turns = "2,2";
        o.estimate = true;
        Calibrator.Result r = Calibrator.run(game, registry(), "emcts", portfolio(), out, null, o);
        assertTrue(r.pass);
        assertTrue(r.estimateSeconds > 0);
        assertFalse(Files.exists(out.resolve(Currency.WEIGHTS_FILE)));
        assertFalse(Files.exists(out.resolve(Currency.PROFILES_CSV)));
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
            Calibrator.run(game, registry(), "emcts", Portfolio.load(game, otherDir), out, null, toy("emcts-alt"));
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
            assertSame(Calibrator.positionFor(s, i, 2, 8), Calibrator.positionFor(s, i * 4, 8, 8));
        // The pool is the most turns any level runs: 8 for every 8,4,2 file (its positions unchanged), 16 for 16,8,4.
        assertEquals(8, Calibrator.pool(new int[]{8, 4, 2}));
        assertEquals(16, Calibrator.pool(new int[]{16, 8, 4}));
        for (int i = 0; i < 8; i++)
            assertSame("a pool of eight picks the positions it always did", s.get((int) (((long) i * 37) % s.size())),
                    Calibrator.positionFor(s, i, 8, 8));
        for (int i = 0; i < 4; i++)
            assertSame(Calibrator.positionFor(s, i, 4, 16), Calibrator.positionFor(s, i * 4, 16, 16));
        assertEquals(8, Calibrator.turnsFor(40_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        assertEquals(2, Calibrator.turnsFor(400_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        assertEquals("an unlisted level takes the nearest listed level below it", 4,
                Calibrator.turnsFor(160_000, new long[]{40_000, 80_000, 400_000}, new int[]{8, 4, 2}));
        MachineProfileTest.ladderIsAscendingAndCapped();
    }
}
