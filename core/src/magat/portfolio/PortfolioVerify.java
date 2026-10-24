package magat.portfolio;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.Signature;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Weights;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code magat portfolio verify}: everything that can be checked about a portfolio before anything
 * runs on it, and, given the published portfolio, whether this one is the same.
 *
 * <ol>
 *   <li>The manifest parses and names this game (the original format names none and is accepted).</li>
 *   <li>Every scenario file loads, and the branching factor and {@code describe()} facts recomputed
 *       from it equal the manifest's: the bins and the manifest describe the same positions.</li>
 *   <li>{@code identity.txt}, if present, equals the manifest's identity.</li>
 *   <li>Split files, if present, carry that identity, and the split rule applied to the manifest
 *       reproduces their membership exactly (an audit of the files, never a replacement for them).</li>
 *   <li>Against a published portfolio: the same identity and every row equal (attempt, seed, phase,
 *       branching, facts); with {@code subset}, every row of this (toy) portfolio equals the published
 *       row of the same source, attempt and phase, ids aside.</li>
 *   <li>Optionally, {@code plan} scenarios are planned from both portfolios' bins with the same arm,
 *       seed and budget, and the plans must agree field for field. Scenario files are not
 *       byte-identical across JVM runs (serialized hash maps iterate in identity-hash order), so this
 *       is how "the same positions" is checked beyond the manifest.</li>
 * </ol>
 * The game's configuration recorded in a version-1 manifest is compared with the current one; a
 * difference is reported as a warning, because an engine commit may change nothing about positions.
 */
public final class PortfolioVerify {

    private PortfolioVerify() {}

    public static final class Report {
        public final List<String> passes = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();

        void pass(String s) { passes.add(s); }
        void warn(String s) { warnings.add(s); }
        void fail(String s) { failures.add(s); }

        public boolean ok() { return failures.isEmpty(); }

        public void print() {
            for (String s : passes) System.out.println("  [ok]    " + s);
            for (String s : warnings) System.out.println("  [warn]  " + s);
            for (String s : failures) System.out.println("  [FAIL]  " + s);
        }
    }

    /** Options for the comparison with a published portfolio. */
    public static final class Against<S, A> {
        public Path published;
        public boolean subset;
        public int plan;
        public AgentFactory<S, A> arm;
        public long budget = 8_000L;
    }

    public static <S, A> Report run(Game<S, A> game, Path dir, Against<S, A> against) throws IOException {
        Report r = new Report();
        Manifest m = Manifest.read(dir);
        String identity = m.identity();
        if (m.legacy()) r.pass("manifest parses (original format, " + m.size() + " rows)");
        else if (game.id().equals(m.game())) r.pass("manifest parses (version 1, game " + m.game() + ", " + m.size() + " rows)");
        else r.fail("manifest names game '" + m.game() + "', not '" + game.id() + "'");
        if (!m.legacy()) {
            Map<String, String> now = game.configuration();
            for (Map.Entry<String, String> e : m.configuration().entrySet()) {
                String cur = now.get(e.getKey());
                if (!Objects.equals(cur, e.getValue()))
                    r.warn("configuration." + e.getKey() + " was '" + e.getValue() + "' at build time, is '" + cur + "' now");
            }
        }

        // 2. bins load and agree with the manifest
        Portfolio<S> p;
        try {
            p = Portfolio.load(game, dir);
        } catch (RuntimeException | IOException e) {
            r.fail("scenario files do not load: " + e.getMessage());
            return r;
        }
        int mismatched = 0;
        List<String> examples = new ArrayList<>();
        for (Scenario<S> sc : p.scenarios()) {
            int b = game.branchingFactor(sc.state);
            Map<String, String> facts = game.describe(sc.state);
            List<String> diffs = new ArrayList<>();
            if (b != sc.branching) diffs.add("branching " + b + " vs manifest " + sc.branching);
            for (Map.Entry<String, String> e : sc.facts.entrySet())
                if (facts.containsKey(e.getKey()) && !facts.get(e.getKey()).equals(e.getValue()))
                    diffs.add(e.getKey() + " " + facts.get(e.getKey()) + " vs manifest " + e.getValue());
            if (!diffs.isEmpty()) {
                mismatched++;
                if (examples.size() < 5) examples.add("scenario " + sc.id + ": " + String.join(", ", diffs));
            }
        }
        if (mismatched == 0) r.pass("all " + p.size() + " scenario files load and match the manifest's branching and facts");
        else r.fail(mismatched + " scenario file(s) disagree with the manifest, e.g. " + examples);

        // 3. identity file
        String file = Identity.read(dir);
        if (file == null) r.warn("no " + Identity.FILE + " (written by a complete build or by freeze)");
        else if (file.equals(identity)) r.pass(Identity.FILE + " matches the manifest: " + identity);
        else r.fail(Identity.FILE + " says " + file + ", the manifest's identity is " + identity);

        // 4. split files
        Path dev = dir.resolve(Split.DEV_FILE), hold = dir.resolve(Split.HOLDOUT_FILE);
        if (Files.exists(dev) && Files.exists(hold)) {
            String fd = Split.frozenIdentity(dev), fh = Split.frozenIdentity(hold);
            if (identity.equals(fd) && identity.equals(fh)) r.pass("split files carry the manifest's identity");
            else r.fail("split files were frozen against " + fd + " / " + fh + ", not " + identity);
            Split.Deal deal = Split.deal(m.rows());
            Set<Integer> frozenDev = Split.ids(dir, Split.Half.DEV), frozenHold = Split.ids(dir, Split.Half.HOLDOUT);
            if (deal.ids(Split.Half.DEV).equals(frozenDev) && deal.ids(Split.Half.HOLDOUT).equals(frozenHold))
                r.pass("the split rule reproduces the frozen membership (" + frozenDev.size() + " dev / " + frozenHold.size() + " holdout)");
            else r.fail("the split rule applied to this manifest does not reproduce the frozen split files");
        } else if (Files.exists(dev) || Files.exists(hold)) {
            r.fail("only one split file exists");
        } else {
            r.warn("not frozen yet (no split files); run `magat portfolio freeze` before anything runs on it");
        }

        // 5 and 6. against the published portfolio
        if (against != null && against.published != null) compare(game, p, m, against, r);
        return r;
    }

    private static <S, A> void compare(Game<S, A> game, Portfolio<S> p, Manifest m, Against<S, A> a, Report r) throws IOException {
        Manifest pub = Manifest.read(a.published);
        Map<String, Manifest.Row> pubByKey = new LinkedHashMap<>();
        for (Manifest.Row row : pub.rows()) pubByKey.put(row.key(), row);
        if (!a.subset) {
            if (m.identity().equals(pub.identity()))
                r.pass("identity equals the published portfolio's: " + pub.identity());
            else r.fail("identity " + m.identity() + " differs from the published " + pub.identity());
        }
        int unmatched = 0, differing = 0;
        List<String> examples = new ArrayList<>();
        Map<Integer, Manifest.Row> counterpart = new LinkedHashMap<>();
        for (Manifest.Row row : m.rows()) {
            Manifest.Row q = pubByKey.get(row.key());
            if (q == null) {
                unmatched++;
                if (examples.size() < 5) examples.add("scenario " + row.id + " (" + row.key() + ") is not in the published portfolio");
                continue;
            }
            counterpart.put(row.id, q);
            List<String> d = new ArrayList<>();
            if (!a.subset && q.id != row.id) d.add("id " + row.id + " vs published " + q.id);
            if (q.seed != row.seed) d.add("seed " + row.seed + " vs " + q.seed);
            if (q.branching != row.branching) d.add("branching " + row.branching + " vs " + q.branching);
            for (Map.Entry<String, String> e : row.facts.entrySet()) {
                String v = q.facts.get(e.getKey());
                if (v == null) d.add("published has no " + e.getKey());
                else if (!v.equals(e.getValue())) d.add(e.getKey() + " " + e.getValue() + " vs " + v);
            }
            if (!d.isEmpty()) {
                differing++;
                if (examples.size() < 5) examples.add("scenario " + row.id + " (" + row.key() + "): " + String.join(", ", d));
            }
        }
        String what = a.subset ? "every row matches the published row of the same source, attempt and phase"
                               : "every row equals the published row (id, attempt, seed, phase, branching, facts)";
        if (!a.subset && m.size() != pub.size()) r.fail(m.size() + " rows, the published portfolio has " + pub.size());
        if (unmatched == 0 && differing == 0) r.pass(what + " (" + m.size() + " rows)");
        else r.fail(unmatched + " row(s) missing from and " + differing + " row(s) different from the published portfolio, e.g. " + examples);

        if (a.plan > 0 && a.arm != null) {
            List<Scenario<S>> sample = p.spread(a.plan);
            int agree = 0;
            for (Scenario<S> sc : sample) {
                Manifest.Row q = counterpart.get(sc.id);
                if (q == null) continue;
                Scenario<S> published = Portfolio.load(game, a.published, java.util.Collections.singleton(q.id)).byId(q.id);
                // Same id on both sides, so both plan under the same visit seed.
                Scenario<S> mine = new Scenario<>(q.id, sc.source, sc.attempt, sc.seed, sc.phase, sc.branching, sc.state, sc.facts);
                Signature x = Signature.of(Episode.run(game, mine, 1, a.arm, a.budget, Weights.DEFAULT, "capture"));
                Signature y = Signature.of(Episode.run(game, published, 1, a.arm, a.budget, Weights.DEFAULT, "capture"));
                List<String> d = x.differences(y, 0);
                if (d.isEmpty()) agree++;
                else r.fail("scenario " + sc.id + " plans differently from published " + q.id + ": " + d);
            }
            if (agree == sample.size())
                r.pass(a.arm.id() + " at " + a.budget + " units plans identically from " + agree + " rebuilt and published scenario file(s)");
        }
    }
}
