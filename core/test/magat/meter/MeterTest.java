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
        java.util.List<String> all = new java.util.ArrayList<>(Arrays.asList(lines));
        all.add(0, "magat.weights.version = 2");
        return Weights.parse(null, all);
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
                "profile.emcts@40000.w.advance = 1.5", "profile.emcts@40000.w.copy = 0.2", "profile.emcts@40000.w.eval = 0.05"));
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
        Meter.install(weights("global.w.advance = 1", "global.w.copy = 0.5", "global.w.eval = 0.25"));
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

    // ------------------------------------------------------------------ algorithm operations

    private static final Meter.Op WORK = Meter.register("metertest.work");
    private static final Meter.Op INNER = Meter.register("metertest.inner");

    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) { /* busy */ }
    }

    @Test
    public void algorithmOperationsArePricedAndAnUnpricedOneIsNamed() {
        Meter.install(weights("reference = a@1",
                "profile.a@1.w.advance = 1.5", "profile.a@1.w.copy = 0.2", "profile.a@1.w.eval = 0.05",
                "profile.a@1.w.metertest.work = 0.25"));
        Meter.useProfile("a@1");
        Meter.reset();
        Meter.onAdvance(); Meter.onCopy(); Meter.onEval();
        WORK.count(7);
        assertEquals(1.5 + 0.2 + 0.05 + 7 * 0.25, Meter.units(), 1e-12);
        assertEquals(0.25, Meter.activeWeight("metertest.work"), 0);
        assertTrue(Meter.unpricedCounted(null).isEmpty());
        INNER.count();
        assertEquals("an operation the profile does not price is named", java.util.Collections.singletonList("metertest.inner"),
                Meter.unpricedCounted(null));
        // A probe's explicit triple prices the forward model only.
        Meter.useWeights(new double[]{1, 0.16, 0.077}, "probe");
        assertEquals(0.0, Meter.activeWeight("metertest.work"), 0);
        assertTrue(Meter.unpricedCounted(null).isEmpty());
    }

    @Test
    public void aSpanIsTimedWithoutWhatIsNestedInIt() {
        Meter.resetNanos();
        Meter.reset();
        Meter.PROFILE = true;
        try {
            try (Meter.Span outer = WORK.span()) {
                spin(2_000_000);
                try (Meter.Span inner = INNER.span()) {
                    spin(3_000_000);
                }
                Meter.addAdvanceNanos(1_000_000);    // a forward-model call timed by MeteredGame inside the span
                spin(1_000_000);
            }
        } finally {
            Meter.PROFILE = false;
        }
        int w = Meter.index("metertest.work"), in = Meter.index("metertest.inner");
        assertEquals(1, Meter.count(w));
        assertEquals(1, Meter.timed(w));
        assertTrue("the inner span's own time: " + Meter.nanos(in), Meter.nanos(in) >= 3_000_000 && Meter.nanos(in) < 6_000_000);
        // The outer span spun 3 ms of its own; the inner span's 3 ms and the advance's 1 ms are subtracted.
        assertTrue("the outer span's own time: " + Meter.nanos(w), Meter.nanos(w) >= 1_900_000 && Meter.nanos(w) < 5_000_000);
        assertEquals(1, Meter.timed(Meter.ADVANCE));
    }

    @Test
    public void aBatchSpanCountsItsOccurrencesAndAnUntimedCountIsNotTimed() {
        Meter.resetNanos();
        Meter.reset();
        Meter.PROFILE = true;
        try (Meter.Span s = WORK.span(5)) {
            spin(100_000);
        } finally {
            Meter.PROFILE = false;
        }
        WORK.count(3);
        int w = Meter.index("metertest.work");
        assertEquals(8, Meter.count(w));
        assertEquals("five occurrences timed, three only counted", 5, Meter.timed(w));
        assertTrue(Meter.nanos(w) >= 100_000);
        // Not profiling, a span only counts.
        Meter.resetNanos();
        try (Meter.Span s = WORK.span()) {
            spin(10_000);
        }
        assertEquals(9, Meter.count(w));
        assertEquals(0, Meter.nanos(w));
        assertEquals(0, Meter.timed(w));
    }

    @Test
    public void spansCloseInnermostFirst() {
        Meter.PROFILE = true;
        Meter.Span a = WORK.span();
        Meter.Span b = INNER.span();
        try {
            a.close();
            fail("closing the outer span first must be refused");
        } catch (IllegalStateException expected) {
        } finally {
            b.close();
            a.close();
            Meter.PROFILE = false;
        }
    }

    @Test
    public void namesFollowTheRuleAndRegistrationIsIdempotent() {
        assertSame(WORK, Meter.register("metertest.work"));
        assertSame(WORK, Meter.operation("metertest.work"));
        for (String bad : new String[]{"nodot", "Qd.update", "qd.", "qd..x", "advance", "qd.up-date", "1qd.x"}) {
            try {
                Meter.register(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) { }
        }
        try {
            Meter.op("metertest.never.registered");
            fail();
        } catch (IllegalStateException expected) { }
        assertEquals(0, Meter.index("advance"));
        assertEquals(-1, Meter.index("metertest.never.registered"));
        assertTrue(Meter.operations().contains("metertest.work"));
    }
}
