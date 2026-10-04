package magat.portfolio;

import magat.game.Scenario;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class SplitTest {

    /** A ragged synthetic manifest: two sources, uneven strata, branching drawn at random. */
    private static List<Manifest.Row> rows(int n, long seed) {
        Random r = new Random(seed);
        List<Manifest.Row> out = new ArrayList<>();
        int[] phases = {10, 12, 14, 16};
        for (int id = 1; id <= n; id++) {
            String source = id <= n * 0.6 ? "single" : "dual";
            int phase = phases[r.nextInt(source.equals("single") ? 4 : 2)];
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("Score", String.valueOf(r.nextInt(5000)));
            out.add(new Manifest.Row(id, source, id, 1000 + id, phase, 50 + r.nextInt(500), facts));
        }
        return out;
    }

    private static Path portfolio(List<Manifest.Row> rows) throws Exception {
        Path dir = Files.createTempDirectory("magat-split");
        Files.write(dir.resolve(Manifest.FILE), Manifest.render("toy", Collections.emptyMap(), Collections.emptyMap(),
                "x=" + rows.size(), rows).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void halvesDifferByAtMostOneAndBalanceEveryStratum() {
        for (long seed = 1; seed <= 20; seed++) {
            List<Manifest.Row> rows = rows(37 + (int) seed, seed);
            Split.Deal d = Split.deal(rows);
            assertEquals(rows.size(), d.dev.size() + d.holdout.size());
            assertTrue(Math.abs(d.dev.size() - d.holdout.size()) <= 1);
            for (String s : d.strata) {
                String[] counts = s.substring(s.indexOf('=') + 1).split("/");
                assertTrue(s, Math.abs(Integer.parseInt(counts[0]) - Integer.parseInt(counts[1])) <= 1);
            }
            assertEquals("the rule is a pure function", d.ids(Split.Half.DEV), Split.deal(rows).ids(Split.Half.DEV));
        }
    }

    @Test
    public void freezeIsIdempotentAndGuardsTheIdentity() throws Exception {
        List<Manifest.Row> rows = rows(30, 7);
        Path dir = portfolio(rows);
        Split.Frozen first = Split.freeze(dir);
        assertTrue(first.written);
        String dev = new String(Files.readAllBytes(dir.resolve(Split.DEV_FILE)), StandardCharsets.UTF_8);
        Split.Frozen second = Split.freeze(dir);
        assertFalse(second.written);
        assertEquals("a second freeze rewrites nothing", dev, new String(Files.readAllBytes(dir.resolve(Split.DEV_FILE)), StandardCharsets.UTF_8));
        assertEquals(first.identity, Identity.read(dir));
        assertTrue(Files.exists(dir.resolve(Split.REPORT_FILE)));

        // The manifest changes after the freeze: refused.
        List<Manifest.Row> changed = new ArrayList<>(rows);
        Manifest.Row last = changed.remove(changed.size() - 1);
        changed.add(new Manifest.Row(last.id, last.source, last.attempt, last.seed, last.phase + 1, last.branching, last.facts));
        Files.write(dir.resolve(Manifest.FILE), Manifest.render("toy", Collections.emptyMap(), Collections.emptyMap(),
                "x=30", changed).getBytes(StandardCharsets.UTF_8));
        Files.delete(dir.resolve(Identity.FILE));
        try {
            Split.freeze(dir);
            fail("a changed identity must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("changed after its split was frozen"));
        }
    }

    @Test
    public void aHalfWrittenSplitIsRefused() throws Exception {
        Path dir = portfolio(rows(20, 3));
        Split.freeze(dir);
        Files.delete(dir.resolve(Split.HOLDOUT_FILE));
        try {
            Split.freeze(dir);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("half-written"));
        }
    }

    @Test
    public void applyChecksBothDirections() throws Exception {
        List<Manifest.Row> rows = rows(20, 5);
        Path dir = portfolio(rows);
        List<Scenario<String>> all = new ArrayList<>();
        for (Manifest.Row r : rows) all.add(new Scenario<>(r.id, r.source, r.attempt, r.seed, r.phase, r.branching, "s" + r.id, r.facts));
        List<Scenario<String>> dev = Split.apply(all, dir, Split.Half.DEV);
        List<Scenario<String>> hold = Split.apply(all, dir, Split.Half.HOLDOUT);
        assertEquals(all.size(), dev.size() + hold.size());
        assertSame(all, Split.apply(all, dir, Split.Half.ALL));

        try {
            Split.apply(all.subList(0, all.size() - 1), dir, Split.Half.DEV);
            fail("a portfolio that lost a scenario must be refused");
        } catch (IllegalStateException e) { assertTrue(e.getMessage().contains("does not contain")); }

        List<Scenario<String>> grown = new ArrayList<>(all);
        grown.add(new Scenario<>(999, "single", 999, 0, 10, 1, "extra", Collections.emptyMap()));
        try {
            Split.apply(grown, dir, Split.Half.HOLDOUT);
            fail("a portfolio that grew must be refused");
        } catch (IllegalStateException e) { assertTrue(e.getMessage().contains("neither split")); }
    }

    @Test
    public void anOutputRootHoldsOneHalf() throws Exception {
        Path out = Files.createTempDirectory("magat-root");
        Split.claimOutputRoot(out, Split.Half.DEV);
        Split.claimOutputRoot(out, Split.Half.DEV);
        try {
            Split.claimOutputRoot(out, Split.Half.HOLDOUT);
            fail();
        } catch (IllegalStateException expected) { }
    }

    /** The rule, applied to the published Tribes manifest, reproduces the published split files. */
    @Test
    public void reproducesThePublishedTribesSplit() throws Exception {
        Path pub = Paths.get("games", "tribes", "portfolio", "v1");
        assumeTrue(Files.isRegularFile(pub.resolve(Manifest.FILE)));
        Manifest m = Manifest.read(pub);
        assertTrue(m.legacy());
        assertEquals("ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7", m.identity());
        Split.Deal d = Split.deal(m.rows());
        Set<Integer> dev = Split.ids(pub, Split.Half.DEV), hold = Split.ids(pub, Split.Half.HOLDOUT);
        assertEquals(dev, d.ids(Split.Half.DEV));
        assertEquals(hold, d.ids(Split.Half.HOLDOUT));
        assertEquals(m.identity(), Split.frozenIdentity(pub.resolve(Split.DEV_FILE)));
    }
}
