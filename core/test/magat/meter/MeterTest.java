package magat.meter;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class MeterTest {

    @After
    public void tearDown() {
        Meter.install(null);
        Meter.allowUnpriced(false);
        Meter.useProfile(null);
        Meter.reset();
    }

    private static Weights weights(String... lines) {
        return Weights.parse(null, Arrays.asList(lines));
    }

    @Test
    public void countsAndDefaultPricing() {
        Meter.useProfile(null);
        Meter.reset();
        Meter.onAdvance(); Meter.onAdvance(); Meter.onCopy(); Meter.onEval();
        assertEquals(2, Meter.advances());
        assertEquals(1, Meter.copies());
        assertEquals(1, Meter.evals());
        assertEquals(2 * 1.0 + 1 * 0.16 + 1 * 0.077, Meter.units(), 1e-12);
        Meter.reset();
        assertEquals(0.0, Meter.units(), 0.0);
    }

    @Test
    public void profilePricing() {
        Meter.install(weights("reference = emcts@40000",
                "profile.emcts@40000.wAdvance = 1.5", "profile.emcts@40000.wCopy = 0.2", "profile.emcts@40000.wEval = 0.05"));
        Meter.useProfile("emcts@40000");
        Meter.reset();
        Meter.onAdvance(); Meter.onCopy(); Meter.onEval();
        assertEquals("emcts@40000", Meter.activeProfile());
        assertEquals(1.5 + 0.2 + 0.05, Meter.units(), 1e-12);
        Meter.useProfile(null);
        assertEquals("global", Meter.activeProfile());
    }

    @Test(expected = IllegalStateException.class)
    public void unpricedProfileIsFatal() {
        Meter.install(weights("reference = emcts@40000"));
        Meter.useProfile("nope@1");
    }

    @Test
    public void unpricedProfileAllowedFallsBackToGlobal() {
        Meter.install(weights("global.wAdvance = 1", "global.wCopy = 0.5", "global.wEval = 0.25"));
        Meter.allowUnpriced(true);
        Meter.useProfile("nope@1");
        assertArrayEquals(new double[]{1, 0.5, 0.25}, Meter.activeWeights(), 0.0);
        assertEquals("global", Meter.activeProfile());
    }

    @Test
    public void countsAreThreadLocal() throws Exception {
        Meter.reset();
        Meter.onAdvance();
        final long[] other = new long[1];
        Thread t = new Thread(() -> { Meter.reset(); Meter.onAdvance(); Meter.onAdvance(); other[0] = Meter.advances(); });
        t.start(); t.join();
        assertEquals(2, other[0]);
        assertEquals(1, Meter.advances());
    }
}
