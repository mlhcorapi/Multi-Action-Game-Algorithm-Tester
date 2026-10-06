package magat.calibrate;

import magat.meter.Weights;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CurrencyTest {

    /** A version-1 file whose reference weight is far from 1, so multiplying and dividing by it differ visibly. */
    private static final List<String> FILE = Arrays.asList(
            "magat.weights.version = 1",
            "game = toy",
            "reference = ref@100",
            "referenceAdvanceNanos = 500.000",
            "turnsPerRep = 2,2",
            "portfolio.identity = abc",
            "global.wAdvance = 2.000000",
            "global.wCopy = 0.400000",
            "global.wEval = 0.200000",
            "profile.ref@100.wAdvance = 2.000000",
            "profile.ref@100.wCopy = 0.400000",
            "profile.ref@100.wEval = 0.200000",
            "profile.ref@100.advanceNanos = 1000.000",
            "profile.x@100.wAdvance = 3.000000",
            "profile.x@100.wCopy = 0.600000",
            "profile.x@100.wEval = 0.300000",
            "profile.x@100.advanceNanos = 1500.000",
            "profile.keep@100.wAdvance = 1.500000",
            "profile.keep@100.wCopy = 0.100000",
            "profile.keep@100.wEval = 0.050000");

    private static Weights file() {
        return Weights.parse(null, FILE);
    }

    /** A session measuring {@code ref} and {@code x}: absolute costs scaled by {@code speed}, x's copy and eval by {@code ratio}. */
    private static List<Cell> session(double speed, double ratio) {
        // On the file's machine: ref advance 1000 ns (wAdvance 2 against the 500 ns unit), copy 200, eval 100;
        // x advance 1500, copy 300, eval 150.
        Cell ref = Cell.measured("ref", 100, 1000 * speed, 200 * speed, 100 * speed);
        Cell x = Cell.measured("x", 100, 1500 * speed, 300 * speed * ratio, 150 * speed * ratio);
        Cell fresh = Cell.measured("new", 100, 2000 * speed, 250 * speed, 120 * speed);
        List<Cell> cells = Arrays.asList(ref, x, fresh);
        for (Cell c : cells) c.normalise(ref.tAdvance);
        return cells;
    }

    private static Currency.Session s() {
        Currency.Session s = new Currency.Session();
        s.game = "toy";
        s.turnsPerRep = "2,2";
        s.measured = "now";
        return s;
    }

    @Test
    public void theFactorIsTheFilesReferenceWeightAndItMultiplies() {
        assertEquals(2.0, Currency.factor(file()), 0);
        for (double speed : new double[]{1.0, 1.58, 0.6}) {
            List<Cell> cells = session(speed, 1.0);
            // A machine uniformly faster or slower prices x exactly as the file does: no drift at all.
            Currency.Drift d = Currency.drift(file(), cells, cells.get(0), Currency.DRIFT_GATE);
            assertEquals(5, d.rows.size());          // ref's copy and eval, x's three operations
            for (Currency.DriftRow r : d.rows) assertEquals(r.what, 0.0, r.drift, 1e-12);
            assertTrue(d.pass());
            assertEquals(speed, d.machineFactor, 1e-12);
            assertEquals(Collections.singletonList("new@100"), d.notPriced);

            // The merge writes the new arm in the file's unit: 2000 ns over the reference advance's 1000, times 2.
            String merged = Currency.merge(FILE, file(), s(), Collections.singletonList(cells.get(2)), cells.get(0));
            Weights w = Weights.parse(null, Arrays.asList(merged.split("\n")));
            assertEquals(4.0, w.profile("new@100")[0], 1e-6);
            assertEquals(0.5, w.profile("new@100")[1], 1e-6);
            assertEquals(0.24, w.profile("new@100")[2], 1e-6);
            // Dividing by the factor (the bug the thesis caught in a unit test) would have written 1.0.
            assertNotEquals(cells.get(2).wAdvance / Currency.factor(file()), w.profile("new@100")[0], 1e-3);
            // The thesis's absolute re-denomination would have charged a slower machine's arm more.
            assertEquals(4.0 * speed, cells.get(2).tAdvance / file().referenceAdvanceNanos(), 1e-9);
        }
    }

    @Test
    public void aChangeInRatiosIsReportedPerOperationAndGatedOnTheTurnPrice() {
        List<Cell> cells = session(1.58, 0.81);      // copies and evals 19% cheaper relative to advances
        // A cell whose turns are mostly copies and evaluations: its turn price moves almost as far.
        Cell x = cells.get(1);
        x.advances = 100; x.copies = 4000; x.evals = 4000;
        Currency.Drift d = Currency.drift(file(), cells, cells.get(0), Currency.DRIFT_GATE);
        assertEquals(-0.19, d.worstOp.drift, 1e-9);
        assertTrue(d.worstOp.what.startsWith("x@100.w"));
        // stored 100*3 + 4000*0.6 + 4000*0.3 = 3900; now 300 + 4000*0.486 + 4000*0.243 = 3216
        assertEquals(3216.0 / 3900.0 - 1, d.turnPrice.get("x@100"), 1e-12);
        assertEquals("x@100", d.worstProfile);
        assertFalse(d.pass());
    }

    @Test
    public void theTurnPriceWeighsEachOperationByHowOftenTheCellPerformsIt() {
        List<Cell> cells = session(1.0, 0.6);        // x's copies and evals 40% cheaper relative to advances
        Cell x = cells.get(1);
        x.advances = 1000; x.copies = 500; x.evals = 500;
        Currency.Drift d = Currency.drift(file(), cells, cells.get(0), Currency.DRIFT_GATE);
        // stored price 1000*3 + 500*0.6 + 500*0.3 = 3450; now 3000 + 500*0.36 + 500*0.18 = 3270
        assertEquals(3270.0 / 3450.0 - 1, d.turnPrice.get("x@100"), 1e-12);
        assertEquals(-0.4, d.worstOp.drift, 1e-9);
        assertEquals(3270.0 / 3450.0 - 1, d.worstTurnPrice, 1e-12);
        assertTrue("the gate is the turn price: operations 40% cheaper that make up a tenth of the turn move it 5%", d.pass());
    }

    @Test
    public void theLaptopsRecordedCheckPassesUnderTheTurnPriceGate() {
        // ORIGIN.md, 2026-10-04: evaluations a third cheaper relative to advances, copies a few percent,
        // advances within the gate; the turn price within 4%. A Tribes turn of emcts@40000 is roughly
        // 40,000 advances, 1,200 copies and 1,100 evaluations of the units budget (search plus repair).
        Cell ref = Cell.measured("ref", 100, 1000, 200, 100);
        Cell x = Cell.measured("x", 100, 1500 * 1.03, 300 * 0.93, 150 * 0.622);
        x.advances = 36_000; x.copies = 1_200; x.evals = 1_100;
        List<Cell> cells = Arrays.asList(ref, x);
        for (Cell c : cells) c.normalise(ref.tAdvance);
        Currency.Drift d = Currency.drift(file(), cells, ref, Currency.DRIFT_GATE);
        assertEquals(-0.378, d.worstOp.drift, 1e-3);
        assertTrue(Math.abs(d.turnPrice.get("x@100")) < 0.10);
        assertTrue(d.pass());
    }

    @Test
    public void theMergeKeepsTheCurrencyAndReplacesOnlyWhatItWrites() {
        List<Cell> cells = session(1.0, 1.0);
        Cell x = cells.get(1);
        String merged = Currency.merge(FILE, file(), s(), Arrays.asList(x, cells.get(2)), cells.get(0));
        Weights w = Weights.parse(null, Arrays.asList(merged.split("\n")));
        assertEquals("500.000", w.header().get("referenceAdvanceNanos"));
        assertEquals("ref@100", w.reference());
        assertEquals(2.0, w.global()[0], 0);
        assertArrayEquals(new double[]{1.5, 0.1, 0.05}, w.profile("keep@100"), 0);
        assertArrayEquals(new double[]{3.0, 0.6, 0.3}, w.profile("x@100"), 1e-6);
        assertEquals("x is written once, not twice", 1, count(merged, "profile.x@100.wAdvance"));
        assertEquals(1, count(merged, "# merged now: 2 profile(s)"));
        assertTrue(merged.contains("re-denominated x2.000000"));
    }

    @Test
    public void mergingIsRefusedIntoAThesisFileOrAgainstAnotherReference() {
        List<Cell> cells = session(1.0, 1.0);
        List<String> thesis = new ArrayList<>(FILE.subList(1, FILE.size()));
        try {
            Currency.merge(thesis, Weights.parse(null, thesis), s(), Collections.singletonList(cells.get(2)), cells.get(0));
            fail();
        } catch (IllegalStateException expected) { }
        try {
            Currency.merge(FILE, file(), s(), Collections.singletonList(cells.get(2)), cells.get(1));
            fail();
        } catch (IllegalStateException expected) { }
    }

    @Test
    public void aFreshFileRoundTripsThroughTheLoader() {
        List<Cell> cells = session(1.0, 1.0);
        Currency.Session s = s();
        s.levels = "100";
        s.portfolioIdentity = "abc";
        s.machine = "test";
        s.reps = 3;
        String text = Currency.fresh(s, cells, cells.get(0));
        Weights w = Weights.parse(null, Arrays.asList(text.split("\n")));
        assertEquals("ref@100", w.reference());
        assertEquals(1000.0, w.referenceAdvanceNanos(), 1e-9);
        assertEquals("2,2", w.turnsPerRep());
        assertEquals("abc", w.portfolioIdentity());
        assertArrayEquals(new double[]{1.0, 0.2, 0.1}, w.profile("ref@100"), 1e-6);
        assertArrayEquals(new double[]{1.5, 0.3, 0.15}, w.profile("x@100"), 1e-6);
        assertArrayEquals(new double[]{1.0, 0.2, 0.1}, w.global(), 1e-6);
        assertEquals(300.0, w.nanos("x@100")[1], 1e-9);
        assertEquals(1.0, Currency.factor(w), 1e-9);
    }

    @Test
    public void versionTwoPricesTheArmsOwnOperationsAndMergesThemByTheSameFactor() {
        // A fresh version-2 file: the reference's operations become the global ones; every cell's are priced.
        Cell ref = Cell.measured("ref", 100, 1000, 200, 100).withOp("x.step", 500, 40);
        Cell x = Cell.measured("x", 100, 1500, 300, 150).withOp("x.step", 250, 40).withOp("x.rank", 50_000, 4);
        List<Cell> cells = Arrays.asList(ref, x);
        for (Cell c : cells) c.normalise(ref.tAdvance);
        ref.unpriced = 0.02;
        Currency.Session s = s();
        s.levels = "100";
        s.portfolioIdentity = "abc";
        s.machine = "test";
        String text = Currency.fresh(s, cells, ref);
        Weights w = Weights.parse(null, Arrays.asList(text.split("\n")));
        assertEquals(2, w.version());
        assertEquals("advance,copy,eval,x.step,x.rank", w.header().get("operations"));
        assertEquals(0.5, w.globalOperations().get("x.step"), 1e-9);
        assertEquals(0.25, w.prices("x@100").get("x.step"), 1e-9);
        assertEquals("an operation may cost many advances", 50.0, w.prices("x@100").get("x.rank"), 1e-9);
        assertEquals(50_000.0, w.nanosOf("x@100").get("x.rank"), 1e-9);
        assertEquals(0.02, w.unpricedShare("ref@100"), 1e-9);

        // Merged into a version-2 file whose reference weighs 2: every weight, the operations' included, times 2.
        List<String> lines = Arrays.asList(text.replace("profile.ref@100.w.advance = 1.000000", "profile.ref@100.w.advance = 2.000000").split("\n"));
        Weights file = Weights.parse(null, lines);
        Cell ref2 = Cell.measured("ref", 100, 800, 160, 80).withOp("x.step", 400, 40);
        Cell y = Cell.measured("y", 100, 1200, 240, 120).withOp("y.pick", 80, 10);
        for (Cell c : Arrays.asList(ref2, y)) c.normalise(ref2.tAdvance);
        String merged = Currency.merge(lines, file, s(), Collections.singletonList(y), ref2);
        Weights m = Weights.parse(null, Arrays.asList(merged.split("\n")));
        assertEquals(2 * 1.5, m.prices("y@100").get("advance"), 1e-6);
        assertEquals(2 * 0.1, m.prices("y@100").get("y.pick"), 1e-9);
        assertEquals("the operations line grows by what the merge adds", "advance,copy,eval,x.step,x.rank,y.pick", m.header().get("operations"));

        // Drift prices the operations both sides price into the turn: here x.step got 40% dearer.
        Cell x2 = Cell.measured("x", 100, 1500, 300, 150).withOp("x.step", 350, 40).withOp("x.rank", 50_000, 4);
        x2.advances = 100; x2.copies = 0; x2.evals = 0;
        for (Cell c : Arrays.asList(ref, x2)) c.normalise(ref.tAdvance);
        Currency.Drift d = Currency.drift(w, Arrays.asList(ref, x2), ref, Currency.DRIFT_GATE);
        // stored 100*1.5 + 40*0.25 + 4*50 = 360; now 100*1.5 + 40*0.35 + 4*50 = 364
        assertEquals(364.0 / 360.0 - 1, d.turnPrice.get("x@100"), 1e-9);
        assertEquals(0.4, d.worstOp.drift, 1e-9);
        assertEquals("x@100.w.x.step", d.worstOp.what);
        assertTrue(d.pass());
    }

    @Test
    public void theEstimatorTakesTheFastestAndGatesOnTheTwoFastest() {
        double[] reps = {110, 100, 140};
        assertEquals(100, Estimator.fastest(reps), 0);
        assertEquals(0.10, Estimator.twoFastestGap(reps), 1e-12);
        assertEquals(0, Estimator.fastest(new double[]{0, 0}), 0);
        assertEquals(0, Estimator.twoFastestGap(new double[]{100}), 0);
        assertEquals(Math.sqrt(((110 - 350 / 3.0) * (110 - 350 / 3.0) + (100 - 350 / 3.0) * (100 - 350 / 3.0)
                + (140 - 350 / 3.0) * (140 - 350 / 3.0)) / 2) / (350 / 3.0), Estimator.cv(reps), 1e-12);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
