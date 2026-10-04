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
        assertEquals(10, r.verdicts.size());
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
