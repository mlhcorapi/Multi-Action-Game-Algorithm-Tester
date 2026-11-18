package magat.game;

import magat.testgame.ToyGame;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ContractCheckTest {

    @Test
    public void toyGameObeysEveryLaw() {
        ToyGame game = new ToyGame();
        List<Scenario<ToyGame.State>> positions = game.positions(6);
        assertEquals(6, positions.size());
        ContractCheck.Report r = ContractCheck.run(game, positions);
        r.print();
        assertTrue("laws failed: " + r.failures(), r.allPassed());
        assertEquals(9, r.verdicts.size());
    }

    @Test
    public void actionClassesAreAnOptionalCapabilityWithALawOfTheirOwn() {
        ToyGame plain = new ToyGame();
        assertFalse(plain.hasActionClasses());
        try {
            plain.actionClass(plain.positions(1).get(0).state, plain.legalActions(plain.positions(1).get(0).state).get(0), Granularity.MID);
            fail("a game without action classes says so");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("offers no action classes"));
        }

        ToyGame nested = ToyGame.withActionClasses();
        ContractCheck.Report ok = ContractCheck.run(nested, nested.positions(6));
        assertTrue("laws failed: " + ok.failures(), ok.allPassed());
        assertEquals("L10 joins the nine laws", 10, ok.verdicts.size());
        MeteredGame<ToyGame.State, ToyGame.Move> m = MeteredGame.of(nested);
        ToyGame.State s = nested.positions(1).get(0).state;
        ToyGame.Move a = nested.legalActions(s).get(0);
        assertTrue("the meter passes the capability through", m.hasActionClasses());
        assertEquals(nested.actionClass(s, a, Granularity.MID), m.actionClass(s, a, Granularity.MID));

        ToyGame broken = ToyGame.withBrokenActionClasses();
        ContractCheck.Report bad = ContractCheck.run(broken, broken.positions(6));
        assertEquals("only L10 fails", 1, bad.failures());
        ContractCheck.Verdict l10 = bad.verdicts.get(bad.verdicts.size() - 1);
        assertEquals("L10", l10.law);
        assertFalse(l10.passed);
        assertTrue(l10.detail, l10.detail.contains("maps to two COARSE classes"));
    }

    @Test
    public void meteredGameCountsEveryOperation() {
        ToyGame game = new ToyGame();
        MeteredGame<ToyGame.State, ToyGame.Move> m = MeteredGame.of(game);
        assertSame(m, MeteredGame.of(m));
        magat.meter.Meter.reset();
        ToyGame.State s = m.copy(game.positions(1).get(0).state);
        m.advance(s, m.legalActions(s).get(0));
        m.evaluate(s, 0);
        m.evaluate(s, 1);
        assertEquals(1, magat.meter.Meter.copies());
        assertEquals(1, magat.meter.Meter.advances());
        assertEquals(2, magat.meter.Meter.evals());
    }
}
