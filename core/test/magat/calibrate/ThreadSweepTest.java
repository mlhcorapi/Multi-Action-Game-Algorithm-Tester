package magat.calibrate;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
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

    @Test
    public void theHeapHeldAfterCollectionsCountsWhatIsLiveNotGarbage() {
        System.gc();
        long before = ThreadSweep.liveHeap();
        byte[][] kept = new byte[64][];
        for (int i = 0; i < kept.length; i++) kept[i] = new byte[1 << 20];
        System.gc();
        long held = ThreadSweep.liveHeap();
        assertTrue("64 MB kept: " + ((held - before) >> 20) + " MB", held - before >= 60L << 20);
        assertEquals(1 << 20, kept[63].length);
        kept = null;
        System.gc();
        assertTrue("released", ThreadSweep.liveHeap() < held - (32L << 20));
    }

    @Test
    public void aPointThatRunsOutOfMemoryIsReportedAsSuch() throws Exception {
        ToyGame game = new ToyGame();
        Path pdir = Files.createTempDirectory("magat-sweep-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 4), pdir, 1);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        AgentFactory<ToyGame.State, ToyGame.Move> greedy = new AgentFactory<ToyGame.State, ToyGame.Move>() {
            @Override public String id() { return "greedy"; }
            @Override public SearchAgent<ToyGame.State, ToyGame.Move> create(long seed, TurnBudget budget) {
                throw new OutOfMemoryError("Java heap space (simulated)");
            }
        };
        Meter.allowUnpriced(true);      // as ThreadSweep.run does
        try {
            ThreadSweep.benchmark(game, greedy, 200, 2, p.scenarios(), 1);
            fail("an out-of-memory point must be reported");
        } catch (ThreadSweep.OutOfMemory expected) {
            assertTrue(expected.getCause() instanceof OutOfMemoryError);
        } finally {
            Meter.allowUnpriced(false);
        }
    }
}
