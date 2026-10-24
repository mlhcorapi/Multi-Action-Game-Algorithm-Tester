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
        TurnBudget.setInitialization(TurnBudget.Initialization.IN_BUDGET);
    }

    @Test
    public void visitSeedIsTheFixedConvention() {
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

    @Test
    public void inTheBudgetTheSearchGetsWhatTheInitializationLeft() {
        Meter.useProfile(null);
        Meter.reset();
        Meter.onAdvance(); Meter.onAdvance(); Meter.onCopy(); Meter.onEval();     // 2.237 units of initialization
        TurnBudget b = TurnBudget.units(100);
        assertEquals(TurnBudget.Initialization.IN_BUDGET, b.initializationPolicy());
        b.beginSearch();
        assertEquals(97, b.searchUnits());
        assertEquals(97.0, b.ceiling(0, 1), 0.0);
        for (int i = 0; i < 97; i++) Meter.onAdvance();
        assertFalse("97 + 2.237 is short of 100", b.exhausted());
        Meter.onAdvance();
        assertTrue("the turn has spent its budget", b.exhausted());
    }

    @Test
    public void outsideTheBudgetTheSearchGetsAllOfIt() {
        TurnBudget.setInitialization(TurnBudget.Initialization.OUTSIDE_BUDGET);
        Meter.useProfile(null);
        Meter.reset();
        Meter.onAdvance(); Meter.onAdvance();
        TurnBudget b = TurnBudget.units(100);
        TurnBudget.setInitialization(TurnBudget.Initialization.IN_BUDGET);
        assertEquals("a budget keeps the policy it was made under", TurnBudget.Initialization.OUTSIDE_BUDGET, b.initializationPolicy());
        b.beginSearch();
        assertEquals(100, b.searchUnits());
        assertEquals(1904.0, TurnBudget.units(40_000).ceiling(0, 21), 0.0);
        for (int i = 0; i < 99; i++) Meter.onAdvance();
        assertFalse(b.exhausted());
        Meter.onAdvance();
        assertTrue(b.exhausted());
    }

    @Test
    public void anInitializationLargerThanTheBudgetLeavesTheSearchNothing() {
        Meter.useProfile(null);
        Meter.reset();
        for (int i = 0; i < 30; i++) Meter.onAdvance();
        TurnBudget b = TurnBudget.units(20);
        b.beginSearch();
        assertEquals(0, b.searchUnits());
        assertTrue(b.exhausted());
        assertEquals("a phase still has a ceiling to reach", 1.0, b.ceiling(0, 3), 0.0);
    }

    @Test(expected = IllegalStateException.class)
    public void beginSearchTwiceIsAnError() {
        TurnBudget b = TurnBudget.units(10);
        b.beginSearch();
        b.beginSearch();
    }
}
