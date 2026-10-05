package magat.calibrate;

import magat.agents.emcts.EmctsFactory;
import magat.meter.Meter;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.testgame.ToyGame;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ThreadSweepTest {

    @Test
    public void theKneeIsTheSmallestCountWithinThreePercentOfTheBest() {
        List<double[]> pts = Arrays.asList(new double[]{2, 100}, new double[]{4, 180}, new double[]{8, 196},
                new double[]{12, 200}, new double[]{16, 199});
        assertEquals(8, ThreadSweep.knee(pts, 200, 16));
        assertEquals(8, ThreadSweep.knee(Collections.emptyList(), 0, 16));
    }

    @Test
    public void aToySweepWritesItsChoice() throws Exception {
        ToyGame game = new ToyGame();
        Path pdir = Files.createTempDirectory("magat-sweep-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 8), pdir, 1);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        Path out = Files.createTempDirectory("magat-sweep");
        ThreadSweep.Options o = new ThreadSweep.Options();
        o.levels = new long[]{200};
        o.ladder = new int[]{1, 2};
        o.segmentMillis = 50;
        List<ThreadSweep.Level> levels = ThreadSweep.run(game, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), p.scenarios(), p.identity(), out, o);
        assertEquals(1, levels.size());
        Map<Long, Integer> chosen = ThreadSweep.read(out.resolve(ThreadSweep.JSON));
        assertEquals(Integer.valueOf(levels.get(0).chosen), chosen.get(200L));
        assertTrue(Files.readAllLines(out.resolve(ThreadSweep.CSV)).get(0).startsWith("Budget,Threads"));
        assertNull(Meter.installed());
    }
}
