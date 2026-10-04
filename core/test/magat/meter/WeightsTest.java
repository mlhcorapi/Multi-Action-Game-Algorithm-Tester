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
    public void profileKeys() {
        assertEquals("comma-z0-coarse@80000", Profile.key("comma-z0-coarse", 80000));
        assertEquals("comma-z0-coarse", Profile.arm("comma-z0-coarse@80000"));
        assertEquals(80000L, Profile.budget("comma-z0-coarse@80000"));
    }
}
