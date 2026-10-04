package magat.portfolio;

import magat.game.Capture;
import magat.game.ScenarioSource;
import magat.testgame.ToyGame;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PortfolioBuilderTest {

    /**
     * A source whose attempts take different times (so they finish out of order on several threads)
     * and sometimes yield nothing, wrapping the toy game's own source. {@code failAt} makes one
     * attempt throw, as a bug escaping a source would.
     */
    static final class ScrambledSource implements ScenarioSource<ToyGame.State> {
        final String name;
        final int salt;
        final ScenarioSource<ToyGame.State> inner;
        volatile int failAt = -1;
        final List<Integer> played = Collections.synchronizedList(new ArrayList<>());

        ScrambledSource(ToyGame game, String name, int salt) {
            this.name = name;
            this.salt = salt;
            this.inner = game.scenarioSources().get(0);
        }

        @Override public String name() { return name; }
        @Override public int[] phases() { return inner.phases(); }
        @Override public int maxAttempts() { return 60; }
        @Override public int defaultTarget() { return 5; }
        @Override public String definition() { return "scrambled toy, salt " + salt; }

        @Override
        public List<Capture<ToyGame.State>> play(int attempt) {
            played.add(attempt);
            if (attempt == failAt) throw new IllegalStateException("bug in attempt " + attempt);
            try { Thread.sleep((attempt * 37L + salt) % 11); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if ((attempt + salt) % 4 == 0) return Collections.emptyList();
            List<Capture<ToyGame.State>> out = new ArrayList<>();
            for (Capture<ToyGame.State> c : inner.play(attempt * 7 + salt))
                out.add(new Capture<>(attempt, c.seed, c.phase, c.state));
            return out;
        }
    }

    private static Map<String, Integer> targets(int a, int b) {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("alpha", a);
        t.put("beta", b);
        return t;
    }

    private static List<ScenarioSource<ToyGame.State>> sources(ToyGame game) {
        return Arrays.asList(new ScrambledSource(game, "alpha", 1), new ScrambledSource(game, "beta", 2));
    }

    private static String manifest(Path dir) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(Manifest.FILE)), StandardCharsets.UTF_8);
    }

    @Test
    public void theSamePortfolioAtOneAndFourThreads() throws Exception {
        ToyGame game = new ToyGame();
        Path one = Files.createTempDirectory("magat-build1"), four = Files.createTempDirectory("magat-build4");
        PortfolioBuilder.Result r1 = PortfolioBuilder.build(game, sources(game), targets(7, 5), one, 1);
        PortfolioBuilder.Result r4 = PortfolioBuilder.build(game, sources(game), targets(7, 5), four, 4);
        assertTrue(r1.complete);
        assertTrue(r4.complete);
        assertEquals(12, r1.scenarios);
        assertEquals(r1.identity, r4.identity);
        assertEquals(manifest(one), manifest(four));
        assertFalse("the journal is discarded once complete", Files.exists(one.resolve(CaptureJournal.DIR)));
        assertEquals(r1.identity, Identity.read(one));

        Manifest m = Manifest.read(one);
        assertEquals("toy", m.game());
        for (int i = 0; i < m.size(); i++) {
            Manifest.Row row = m.rows().get(i);
            assertEquals("ids are sequential across sources", i + 1, row.id);
            assertEquals(i < 7 ? "alpha" : "beta", row.source);
            if (i > 0 && m.rows().get(i - 1).source.equals(row.source)) {
                Manifest.Row prev = m.rows().get(i - 1);
                assertTrue("(attempt, phase) order within a source",
                        prev.attempt < row.attempt || (prev.attempt == row.attempt && prev.phase < row.phase));
            }
        }
        // The bins hold the captured states: the loader reproduces the manifest's facts from them.
        magat.portfolio.Portfolio<ToyGame.State> p = magat.portfolio.Portfolio.load(game, four);
        for (magat.game.Scenario<ToyGame.State> sc : p.scenarios()) {
            assertEquals(sc.branching, game.branchingFactor(sc.state));
            assertEquals(sc.facts, game.describe(sc.state));
        }
    }

    @Test
    public void aCompleteBuildIsNotRedone() throws Exception {
        ToyGame game = new ToyGame();
        Path dir = Files.createTempDirectory("magat-build");
        PortfolioBuilder.build(game, sources(game), targets(4, 2), dir, 2);
        List<ScenarioSource<ToyGame.State>> again = sources(game);
        PortfolioBuilder.Result r = PortfolioBuilder.build(game, again, targets(4, 2), dir, 2);
        assertTrue(r.skipped);
        assertTrue(((ScrambledSource) again.get(0)).played.isEmpty());
    }

    @Test
    public void anEscapingExceptionStopsTheBuildAndTheJournalResumesIt() throws Exception {
        ToyGame game = new ToyGame();
        Path fresh = Files.createTempDirectory("magat-fresh"), dir = Files.createTempDirectory("magat-resume");
        PortfolioBuilder.build(game, sources(game), targets(9, 3), fresh, 3);

        List<ScenarioSource<ToyGame.State>> broken = sources(game);
        ((ScrambledSource) broken.get(0)).failAt = 4;
        try {
            PortfolioBuilder.build(game, broken, targets(9, 3), dir, 3);
            fail("an exception escaping a source must stop the build");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("attempt 4"));
        }
        assertFalse(Files.exists(dir.resolve(Manifest.FILE)));
        assertFalse(Files.exists(dir.resolve(PortfolioBuilder.COMPLETE_MARKER)));
        assertTrue(Files.exists(dir.resolve(CaptureJournal.DIR).resolve("index.csv")));

        // A torn index line (a kill mid-append) is ignored and its attempt replayed.
        Files.write(dir.resolve(CaptureJournal.DIR).resolve("index.csv"),
                "alpha,5,1@36\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        List<ScenarioSource<ToyGame.State>> fixed = sources(game);
        PortfolioBuilder.Result r = PortfolioBuilder.build(game, fixed, targets(9, 3), dir, 3);
        assertTrue(r.complete);
        assertEquals(manifest(fresh), manifest(dir));
        List<Integer> replayedAlpha = ((ScrambledSource) fixed.get(0)).played;
        assertTrue("attempts 1-3 were journalled, not replayed: " + replayedAlpha,
                !replayedAlpha.contains(1) && !replayedAlpha.contains(2) && !replayedAlpha.contains(3));
        assertTrue("the failed attempt is played again", replayedAlpha.contains(4));
    }

    @Test
    public void theJournalSurvivesATargetChangeButNotASourceChange() throws Exception {
        ToyGame game = new ToyGame();
        Path dir = Files.createTempDirectory("magat-grow");
        List<ScenarioSource<ToyGame.State>> broken = sources(game);
        ((ScrambledSource) broken.get(1)).failAt = 1;      // alpha completes, beta's first attempt fails
        try { PortfolioBuilder.build(game, broken, targets(3, 2), dir, 1); fail(); } catch (IllegalStateException expected) { }

        List<ScenarioSource<ToyGame.State>> bigger = sources(game);
        PortfolioBuilder.build(game, bigger, targets(5, 2), dir, 1);
        List<Integer> alpha = ((ScrambledSource) bigger.get(0)).played;
        assertFalse("alpha's journalled attempts are reused for a larger target: " + alpha, alpha.contains(1));

        Path dir2 = Files.createTempDirectory("magat-changed");
        List<ScenarioSource<ToyGame.State>> first = sources(game);
        ((ScrambledSource) first.get(1)).failAt = 1;
        try { PortfolioBuilder.build(game, first, targets(3, 2), dir2, 1); fail(); } catch (IllegalStateException expected) { }
        List<ScenarioSource<ToyGame.State>> changed = Arrays.asList(new ScrambledSource(game, "alpha", 5), new ScrambledSource(game, "beta", 2));
        PortfolioBuilder.build(game, changed, targets(3, 2), dir2, 1);
        assertTrue("a changed source definition discards the journal", ((ScrambledSource) changed.get(0)).played.contains(1));
    }

    @Test
    public void aFrozenPortfolioIsNeverRebuiltInPlace() throws Exception {
        ToyGame game = new ToyGame();
        Path dir = Files.createTempDirectory("magat-frozen");
        PortfolioBuilder.build(game, sources(game), targets(4, 2), dir, 2);
        Split.freeze(dir);
        try {
            PortfolioBuilder.build(game, sources(game), targets(5, 2), dir, 2);
            fail("rebuilding a frozen portfolio must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("frozen"));
        }
        assertEquals(6, Manifest.read(dir).size());
    }

    @Test
    public void targetsDefaultPerSourceAndRejectUnknownNames() {
        ToyGame game = new ToyGame();
        Map<String, Integer> t = PortfolioBuilder.targets(sources(game), "beta=3");
        assertEquals(Integer.valueOf(5), t.get("alpha"));
        assertEquals(Integer.valueOf(3), t.get("beta"));
        assertEquals("alpha=5 beta=3", PortfolioBuilder.targetsText(t));
        try {
            PortfolioBuilder.targets(sources(game), "gamma=1");
            fail();
        } catch (IllegalArgumentException expected) { }
    }
}
