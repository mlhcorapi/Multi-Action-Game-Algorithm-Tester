package magat.meter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class WeightsTest {

    @Test
    public void parsesVersionOneKeys() {
        Weights w = Weights.parse(null, Arrays.asList(
                "# a comment", "magat.weights.version = 1", "game = tribes", "reference = emcts@40000",
                "referenceAdvanceNanos = 62870.569", "turnsPerRep = 8,4,2", "portfolio.identity = abc",
                "global.wAdvance = 1.0", "global.wCopy = 0.137451", "global.wEval = 0.056738",
                "profile.emcts@40000.wAdvance = 1.000659", "profile.emcts@40000.wCopy = 0.14",
                "profile.emcts@40000.wEval = 0.06", "profile.emcts@40000.advanceNanos = 62912.0",
                "profile.half@1.wAdvance = 1.0"));
        assertEquals("tribes", w.game());
        assertEquals("emcts@40000", w.reference());
        assertEquals(62870.569, w.referenceAdvanceNanos(), 1e-9);
        assertEquals("8,4,2", w.turnsPerRep());
        assertEquals("abc", w.portfolioIdentity());
        assertArrayEquals(new double[]{1.0, 0.137451, 0.056738}, w.global(), 1e-12);
        assertTrue(w.has("emcts@40000"));
        assertArrayEquals(new double[]{1.000659, 0.14, 0.06}, w.profile("emcts@40000"), 1e-12);
        assertEquals(62912.0, w.nanos("emcts@40000")[0], 0.0);
        assertTrue(Double.isNaN(w.nanos("emcts@40000")[1]));
        assertFalse("an incomplete profile is not usable", w.has("half@1"));
        assertEquals(Arrays.asList("half@1", "x@2"), w.missing(Arrays.asList("emcts@40000", "half@1", "x@2")));
    }

    @Test
    public void parsesTheThesisTreeFormat() {
        Weights w = Weights.parse(null, Arrays.asList(
                "compute.reference=EMCTS@40000", "compute.referenceAdvanceNanos=62870.569",
                "compute.wAdvance=1.000000", "compute.wCopy=0.137451", "compute.wEval=0.056738",
                "compute.profile.AB_Baseline@40000.wAdvance=1.000659",
                "compute.profile.AB_Baseline@40000.wCopy=0.139",
                "compute.profile.AB_Baseline@40000.wEval=0.057",
                "compute.profile.AB_Baseline@40000.advanceNanos=62912.005"));
        assertEquals("EMCTS@40000", w.reference());
        assertArrayEquals(new double[]{1.0, 0.137451, 0.056738}, w.global(), 1e-12);
        assertArrayEquals(new double[]{1.000659, 0.139, 0.057}, w.profile("AB_Baseline@40000"), 1e-12);
    }

    @Test
    public void parsesVersionTwo() {
        Weights w = Weights.parse(null, Arrays.asList(
                "magat.weights.version = 2", "game = toy", "reference = emcts@300", "referenceAdvanceNanos = 1000",
                "operations = advance,copy,eval,emcts.legal,qd.update",
                "global.w.advance = 1", "global.w.copy = 0.2", "global.w.eval = 0.1", "global.w.emcts.legal = 0.05",
                "profile.emcts@300.w.advance = 1", "profile.emcts@300.w.copy = 0.2", "profile.emcts@300.w.eval = 0.1",
                "profile.emcts@300.w.emcts.legal = 0.05", "profile.emcts@300.ns.advance = 1000",
                "profile.emcts@300.ns.emcts.legal = 50", "profile.emcts@300.unpriced = 0.031",
                "profile.qd@300.w.advance = 1.2", "profile.qd@300.w.copy = 0.2", "profile.qd@300.w.eval = 0.1",
                "profile.qd@300.w.emcts.legal = 0.06", "profile.qd@300.w.qd.update = 0",
                "profile.broken@300.w.advance = 1", "profile.broken@300.w.copy = 0.2", "profile.broken@300.w.emcts.legal = 0.06"));
        assertEquals(2, w.version());
        assertArrayEquals(new double[]{1, 0.2, 0.1}, w.profile("emcts@300"), 0);
        assertEquals(Double.valueOf(0.05), w.prices("emcts@300").get("emcts.legal"));
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("emcts.legal", "qd.update")), w.operations("qd@300"));
        assertEquals("an operation declared but never seen is priced at zero, which is a price", Double.valueOf(0.0),
                w.prices("qd@300").get("qd.update"));
        assertEquals(1000.0, w.nanos("emcts@300")[0], 0);
        assertEquals(Double.valueOf(50.0), w.nanosOf("emcts@300").get("emcts.legal"));
        assertEquals(0.031, w.unpricedShare("emcts@300"), 0);
        assertTrue(Double.isNaN(w.unpricedShare("qd@300")));
        assertArrayEquals(new double[]{1, 0.2, 0.1}, w.global(), 0);
        assertEquals(Double.valueOf(0.05), w.globalOperations().get("emcts.legal"));
        assertFalse("a profile without its three forward-model weights is not usable", w.has("broken@300"));
        assertEquals(Arrays.asList("qd.update"), w.unpricedOperations("emcts@300", Arrays.asList("emcts.legal", "qd.update")));
    }

    @Test
    public void aVersionOneFilePricesTheForwardModelOnly() {
        Weights w = Weights.parse(null, Arrays.asList("magat.weights.version = 1", "reference = emcts@300",
                "profile.emcts@300.wAdvance = 1", "profile.emcts@300.wCopy = 0.2", "profile.emcts@300.wEval = 0.1"));
        assertEquals(1, w.version());
        assertTrue(w.operations("emcts@300").isEmpty());
        assertTrue("nothing beyond the forward model is unpriced in a version-1 currency",
                w.unpricedOperations("emcts@300", Arrays.asList("emcts.legal")).isEmpty());
        assertEquals(Arrays.asList("advance", "copy", "eval"), new java.util.ArrayList<>(w.prices("emcts@300").keySet()));
        assertEquals(1, Weights.parse(null, Arrays.asList("compute.reference=EMCTS@40000")).version());
    }

    @Test
    public void profileKeys() {
        assertEquals("comma-z0-coarse@80000", Profile.key("comma-z0-coarse", 80000));
        assertEquals("comma-z0-coarse", Profile.arm("comma-z0-coarse@80000"));
        assertEquals(80000L, Profile.budget("comma-z0-coarse@80000"));
    }
}
