package magat.meter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class WeightsTest {

    @Test
    public void parsesTheHeaderAndTheProfiles() {
        Weights w = Weights.parse(null, Arrays.asList(
                "# a comment", "magat.weights.version = 2", "game = tribes", "reference = emcts@9500",
                "referenceAdvanceNanos = 57696.049", "turnsPerRep = 16,16,16", "portfolio.identity = abc",
                "global.w.advance = 1.0", "global.w.copy = 0.147774", "global.w.eval = 0.047417",
                "profile.emcts@9500.w.advance = 1.0", "profile.emcts@9500.w.copy = 0.14",
                "profile.emcts@9500.w.eval = 0.06", "profile.emcts@9500.ns.advance = 57696.049",
                "profile.half@1.w.advance = 1.0"));
        assertEquals("tribes", w.game());
        assertEquals("emcts@9500", w.reference());
        assertEquals(57696.049, w.referenceAdvanceNanos(), 1e-9);
        assertEquals("16,16,16", w.turnsPerRep());
        assertEquals("abc", w.portfolioIdentity());
        assertArrayEquals(new double[]{1.0, 0.147774, 0.047417}, w.global(), 1e-12);
        assertTrue(w.has("emcts@9500"));
        assertArrayEquals(new double[]{1.0, 0.14, 0.06}, w.profile("emcts@9500"), 1e-12);
        assertEquals(57696.049, w.nanos("emcts@9500")[0], 0.0);
        assertTrue(Double.isNaN(w.nanos("emcts@9500")[1]));
        assertFalse("an incomplete profile is not usable", w.has("half@1"));
        assertEquals(Arrays.asList("half@1", "x@2"), w.missing(Arrays.asList("emcts@9500", "half@1", "x@2")));
    }

    @Test
    public void parsesEveryOperationAProfilePrices() {
        Weights w = Weights.parse(null, Arrays.asList(
                "magat.weights.version = 2", "game = toy", "reference = emcts@300", "referenceAdvanceNanos = 1000",
                "operations = advance,copy,eval,emcts.legal,x.update",
                "global.w.advance = 1", "global.w.copy = 0.2", "global.w.eval = 0.1", "global.w.emcts.legal = 0.05",
                "profile.emcts@300.w.advance = 1", "profile.emcts@300.w.copy = 0.2", "profile.emcts@300.w.eval = 0.1",
                "profile.emcts@300.w.emcts.legal = 0.05", "profile.emcts@300.ns.advance = 1000",
                "profile.emcts@300.ns.emcts.legal = 50", "profile.emcts@300.unpriced = 0.031",
                "profile.x@300.w.advance = 1.2", "profile.x@300.w.copy = 0.2", "profile.x@300.w.eval = 0.1",
                "profile.x@300.w.emcts.legal = 0.06", "profile.x@300.w.x.update = 0",
                "profile.broken@300.w.advance = 1", "profile.broken@300.w.copy = 0.2", "profile.broken@300.w.emcts.legal = 0.06"));
        assertEquals(2, w.version());
        assertArrayEquals(new double[]{1, 0.2, 0.1}, w.profile("emcts@300"), 0);
        assertEquals(Double.valueOf(0.05), w.prices("emcts@300").get("emcts.legal"));
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("emcts.legal", "x.update")), w.operations("x@300"));
        assertEquals("an operation declared but never seen is priced at zero, which is a price", Double.valueOf(0.0),
                w.prices("x@300").get("x.update"));
        assertEquals(1000.0, w.nanos("emcts@300")[0], 0);
        assertEquals(Double.valueOf(50.0), w.nanosOf("emcts@300").get("emcts.legal"));
        assertEquals(0.031, w.unpricedShare("emcts@300"), 0);
        assertTrue(Double.isNaN(w.unpricedShare("x@300")));
        assertArrayEquals(new double[]{1, 0.2, 0.1}, w.global(), 0);
        assertEquals(Double.valueOf(0.05), w.globalOperations().get("emcts.legal"));
        assertFalse("a profile without its three forward-model weights is not usable", w.has("broken@300"));
        assertEquals(Arrays.asList("x.update"), w.unpricedOperations("emcts@300", Arrays.asList("emcts.legal", "x.update")));
    }

    @Test
    public void aFileOfAnyOtherVersionIsRefused() {
        for (String first : new String[]{"magat.weights.version = 1", "compute.reference=EMCTS@40000", "game = toy"}) {
            try {
                Weights.parse(null, Arrays.asList(first, "reference = emcts@300",
                        "profile.emcts@300.wAdvance = 1", "profile.emcts@300.wCopy = 0.2", "profile.emcts@300.wEval = 0.1"));
                fail(first + " must be refused");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("MAGAT reads version 2"));
            }
        }
    }

    @Test
    public void profileKeys() {
        assertEquals("tbets@101500", Profile.key("tbets", 101500));
        assertEquals("tbets", Profile.arm("tbets@101500"));
        assertEquals(101500L, Profile.budget("tbets@101500"));
    }
}
