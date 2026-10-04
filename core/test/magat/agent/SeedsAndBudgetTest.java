package magat.agent;

import magat.meter.Meter;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class SeedsAndBudgetTest {

    @After
    public void tearDown() {
        Meter.useProfile(null);
        Meter.reset();
    }

    @Test
    public void visitSeedIsTheThesisConvention() {
        assertEquals(1_000_000L, Seeds.visit(1, 0));
        assertEquals(1_000_005L, Seeds.visit(1, 5));
        assertEquals(400_000_007L, Seeds.visit(400, 7));
    }

    @Test
    public void phaseCeilingUsesIntegerDivision() {
        TurnBudget b = TurnBudget.units(40_000);
        assertEquals(1904.0, b.ceiling(0, 21), 0.0);
        assertEquals(39_984.0, b.ceiling(20, 21), 0.0);   // below the budget: the last iteration overshoots
        assertEquals(2.0 * 380, TurnBudget.units(8_000).ceiling(1, 21), 0.0);
    }

    @Test
    public void beginSearchSnapshotsAndResets() {
        Meter.useProfile(null);
        Meter.reset();
        Meter.onAdvance(); Meter.onAdvance(); Meter.onCopy(); Meter.onEval();
        TurnBudget b = TurnBudget.units(100);
        assertFalse(b.searchBegun());
        b.beginSearch();
        assertTrue(b.searchBegun());
        assertEquals(2, b.initAdvances());
        assertEquals(1, b.initCopies());
        assertEquals(1, b.initEvals());
        assertEquals(2 + 0.16 + 0.077, b.initUnits(), 1e-12);
        assertEquals(0, Meter.advances());
        assertFalse(b.exhausted());
        for (int i = 0; i < 100; i++) Meter.onAdvance();
        assertTrue(b.exhausted());
    }

    @Test(expected = IllegalStateException.class)
    public void beginSearchTwiceIsAnError() {
        TurnBudget b = TurnBudget.units(10);
        b.beginSearch();
        b.beginSearch();
    }
}
