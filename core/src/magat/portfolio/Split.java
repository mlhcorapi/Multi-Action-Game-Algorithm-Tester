package magat.portfolio;

import magat.game.Scenario;
import magat.run.RunGuard;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The frozen development / holdout partition of a portfolio.
 *
 * <p><b>Why.</b> Every judgement call in a study (the winning parameter, the champion arm) is a
 * maximum over arms, and that maximum is biased upward. The only clean fix is a set of scenarios no
 * choice was made on: the <b>holdout</b>, read once, after the choices are written down.
 *
 * <p><b>The rule.</b> Stratify by {@code (source, phase)}; within a stratum order by
 * {@code (branching, id)} and deal matched PAIRS, alternating which half gets the lower member; the
 * pair parity and the odd-member carry both run globally across strata. Each detail was forced by
 * measuring the simpler version: per-stratum carries gave every odd leftover to development (a
 * 33/27 split); plain alternate dealing of a sorted stratum gave one half the lower member of every
 * pair (22 branching points apart). Dealing within strata balances source and game phase by
 * construction, which is what makes the holdout a test of replication rather than of
 * generalisation across game phase.
 *
 * <p><b>The files are authoritative.</b> {@link #freeze} writes {@code _split_dev.txt} and
 * {@code _split_holdout.txt}, each carrying the portfolio identity. Readers never recompute the
 * partition: recomputing is what would let the split silently follow a rebuilt portfolio.
 * {@link #apply} checks both directions (the portfolio lost scenarios / the portfolio grew).
 *
 * <p>Extracted from the thesis tree's split, rule and guards unchanged.
 */
public final class Split {

    public static final String DEV_FILE = "_split_dev.txt";
    public static final String HOLDOUT_FILE = "_split_holdout.txt";
    public static final String REPORT_FILE = "split_report.txt";
    /** Written into an output root the first time a split runs there; guards against mixing. */
    public static final String ROOT_MARKER = "_split.txt";
    private static final String HASH_PREFIX = "# identity-sha256: ";

    private Split() {}

    /** Which part of the portfolio a run may see. */
    public enum Half {
        /** The whole portfolio: the only choice that mixes the two halves. */
        ALL,
        /** Development: every design choice is made here. */
        DEV,
        /** Holdout: read once, after the choices are frozen. */
        HOLDOUT;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        public boolean isSubset() { return this != ALL; }

        public static Half parse(String raw) {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            switch (s) {
                case "all": return ALL;
                case "dev": case "development": return DEV;
                case "holdout": return HOLDOUT;
                default: throw new IllegalArgumentException("unknown split '" + raw + "': expected dev, holdout or all");
            }
        }
    }

    /** The partition the rule produces for a manifest, with its balance report. */
    public static final class Deal {
        public final List<Manifest.Row> dev;
        public final List<Manifest.Row> holdout;
        public final List<String> strata;

        Deal(List<Manifest.Row> dev, List<Manifest.Row> holdout, List<String> strata) {
            this.dev = Collections.unmodifiableList(dev);
            this.holdout = Collections.unmodifiableList(holdout);
            this.strata = Collections.unmodifiableList(strata);
        }

        public Set<Integer> ids(Half half) {
            Set<Integer> out = new LinkedHashSet<>();
            for (Manifest.Row r : half == Half.DEV ? dev : holdout) out.add(r.id);
            return out;
        }
    }

    /** Applies the rule. Pure: the same rows always deal the same way. */
    public static Deal deal(List<Manifest.Row> rows) {
        Map<String, List<Manifest.Row>> strata = new LinkedHashMap<>();
        for (Manifest.Row r : rows) strata.computeIfAbsent(r.source + "@" + r.phase, k -> new ArrayList<>()).add(r);
        List<Manifest.Row> dev = new ArrayList<>(), hold = new ArrayList<>();
        List<String> report = new ArrayList<>();
        int pairToggle = 0, oddCarry = 0;
        for (Map.Entry<String, List<Manifest.Row>> e : strata.entrySet()) {
            List<Manifest.Row> members = new ArrayList<>(e.getValue());
            members.sort((a, b) -> a.branching != b.branching ? Integer.compare(a.branching, b.branching) : Integer.compare(a.id, b.id));
            int d = 0, i = 0;
            for (; i + 1 < members.size(); i += 2) {
                Manifest.Row lo = members.get(i), hi = members.get(i + 1);
                if (pairToggle++ % 2 == 0) { dev.add(lo); hold.add(hi); }
                else                       { dev.add(hi); hold.add(lo); }
                d++;
            }
            if (i < members.size()) {
                if (oddCarry++ % 2 == 0) { dev.add(members.get(i)); d++; }
                else hold.add(members.get(i));
            }
            report.add(e.getKey() + "=" + d + "/" + (members.size() - d));
        }
        dev.sort((a, b) -> Integer.compare(a.id, b.id));
        hold.sort((a, b) -> Integer.compare(a.id, b.id));
        return new Deal(dev, hold, report);
    }

    /** What {@link #freeze} found or did. */
    public static final class Frozen {
        public final String identity;
        public final boolean written;
        public final String report;

        Frozen(String identity, boolean written, String report) {
            this.identity = identity;
            this.written = written;
            this.report = report;
        }
    }

    /**
     * Writes the two split files if they do not exist, and verifies them against the manifest if
     * they do; writes {@code identity.txt} and {@code split_report.txt} if they are missing.
     * Idempotent and safe to call on every run.
     *
     * @throws IllegalStateException if the identity changed since the split was frozen, or only one
     *                               of the two files exists
     */
    public static synchronized Frozen freeze(Path portfolioDir) throws IOException {
        Manifest manifest = Manifest.read(portfolioDir);
        String identity = manifest.identity();
        Path dev = portfolioDir.resolve(DEV_FILE), hold = portfolioDir.resolve(HOLDOUT_FILE);
        String existingIdentity = Identity.read(portfolioDir);
        if (existingIdentity != null && !existingIdentity.equals(identity))
            throw new IllegalStateException(Identity.FILE + " in " + portfolioDir + " says " + existingIdentity
                    + " but the manifest's identity is " + identity + "; the manifest changed after it was built.");

        boolean written = false;
        if (Files.exists(dev) && Files.exists(hold)) {
            checkHash(dev, identity);
            checkHash(hold, identity);
        } else if (Files.exists(dev) || Files.exists(hold)) {
            throw new IllegalStateException("the split is half-written: exactly one of " + DEV_FILE + " / " + HOLDOUT_FILE
                    + " exists in " + portfolioDir + ". Delete both and re-freeze, or restore the missing one; a partial "
                    + "split cannot be trusted.");
        } else {
            Deal d = deal(manifest.rows());
            String frozenAt = Instant.now().toString();
            write(dev, Half.DEV, d.dev, identity, d.strata, frozenAt);
            write(hold, Half.HOLDOUT, d.holdout, identity, d.strata, frozenAt);
            written = true;
        }
        if (existingIdentity == null) RunGuard.atomicWrite(portfolioDir.resolve(Identity.FILE), identity + "\n");

        // The report describes the authoritative files, not a recomputation.
        Map<Integer, Manifest.Row> byId = new LinkedHashMap<>();
        for (Manifest.Row r : manifest.rows()) byId.put(r.id, r);
        String report = summary(Half.DEV, rowsOf(ids(portfolioDir, Half.DEV), byId)) + "\n"
                + summary(Half.HOLDOUT, rowsOf(ids(portfolioDir, Half.HOLDOUT), byId)) + "\n"
                + "strata (dev/holdout): " + strataLine(dev) + "\n"
                + "identity-sha256: " + identity + "\n";
        Path reportFile = portfolioDir.resolve(REPORT_FILE);
        if (written || !Files.exists(reportFile)) RunGuard.atomicWrite(reportFile, report);
        return new Frozen(identity, written, report);
    }

    private static List<Manifest.Row> rowsOf(Set<Integer> ids, Map<Integer, Manifest.Row> byId) {
        List<Manifest.Row> out = new ArrayList<>();
        for (int id : ids) {
            Manifest.Row r = byId.get(id);
            if (r == null) throw new IllegalStateException("the split names scenario " + id + ", which the manifest does not contain");
            out.add(r);
        }
        return out;
    }

    private static String strataLine(Path splitFile) throws IOException {
        for (String line : Files.readAllLines(splitFile, StandardCharsets.UTF_8))
            if (line.startsWith("# strata (dev/holdout): ")) return line.substring("# strata (dev/holdout): ".length()).trim();
        return "(not recorded)";
    }

    private static void write(Path f, Half half, List<Manifest.Row> rows, String identity, List<String> strata,
                              String frozenAt) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MAGAT portfolio split - ").append(half.name()).append('\n');
        sb.append("# frozen: ").append(frozenAt).append('\n');
        sb.append(HASH_PREFIX).append(identity).append('\n');
        sb.append("# rule: stratified by (Source, Phase); within a stratum ordered by (Branching, ScenarioId) and dealt in "
                + "matched pairs, with the pair parity and the odd-member carry running across strata\n");
        sb.append("# ").append(summary(half, rows)).append('\n');
        sb.append("# strata (dev/holdout): ").append(String.join(" ", strata)).append('\n');
        sb.append("# One scenario id per line. These files are AUTHORITATIVE: readers never recompute the split.\n");
        for (Manifest.Row r : rows) sb.append(r.id).append('\n');
        RunGuard.atomicWrite(f, sb.toString());
    }

    public static String summary(Half half, List<Manifest.Row> rows) {
        Map<String, Integer> bySource = new LinkedHashMap<>();
        double bf = 0;
        for (Manifest.Row r : rows) {
            bySource.merge(r.source, 1, Integer::sum);
            bf += r.branching;
        }
        StringBuilder src = new StringBuilder();
        for (Map.Entry<String, Integer> e : bySource.entrySet()) {
            if (src.length() > 0) src.append(", ");
            src.append(e.getKey()).append('=').append(e.getValue());
        }
        return String.format(Locale.ROOT, "%s: %d scenarios (%s), mean branching %.1f",
                half.name(), rows.size(), src, rows.isEmpty() ? 0.0 : bf / rows.size());
    }

    /** The identity a split file was frozen against, or null if it carries none. */
    public static String frozenIdentity(Path splitFile) throws IOException {
        for (String line : Files.readAllLines(splitFile, StandardCharsets.UTF_8))
            if (line.startsWith(HASH_PREFIX)) return line.substring(HASH_PREFIX.length()).trim();
        return null;
    }

    private static void checkHash(Path splitFile, String identity) throws IOException {
        String found = frozenIdentity(splitFile);
        if (found == null)
            throw new IllegalStateException(splitFile + " has no '" + HASH_PREFIX.trim() + "' line; refusing to trust it.");
        if (!found.equals(identity))
            throw new IllegalStateException("the portfolio changed after its split was frozen.\n"
                    + "  " + splitFile + "\n"
                    + "    frozen identity : " + found + "\n"
                    + "    current identity: " + identity + "\n"
                    + "  The frozen split no longer describes this portfolio. Restore the original portfolio, or delete "
                    + "both split files and re-freeze, and treat every result measured against the old split as "
                    + "belonging to a different experiment.");
    }

    /** The ids of one half, read from its frozen file; null for {@link Half#ALL}, meaning no filter. */
    public static Set<Integer> ids(Path portfolioDir, Half half) throws IOException {
        if (!half.isSubset()) return null;
        Path f = portfolioDir.resolve(half == Half.DEV ? DEV_FILE : HOLDOUT_FILE);
        if (!Files.exists(f)) throw new FileNotFoundException("split file not found: " + f + "; run `magat portfolio freeze` first.");
        Set<Integer> out = new LinkedHashSet<>();
        for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(Integer.parseInt(line));
        }
        if (out.isEmpty()) throw new IllegalStateException(f + " lists no scenario ids.");
        return out;
    }

    /**
     * Filters a loaded portfolio down to one half, freezing first if needed. Both directions are
     * checked on every run, because both failures are silent otherwise: a frozen id the portfolio no
     * longer contains (it shrank), and a portfolio id neither file lists (it grew, and the new
     * scenarios would be dropped without a word).
     */
    public static <S> List<Scenario<S>> apply(List<Scenario<S>> portfolio, Path portfolioDir, Half half) throws IOException {
        if (!half.isSubset()) return portfolio;
        freeze(portfolioDir);
        Set<Integer> wanted = ids(portfolioDir, half);
        Set<Integer> expected = new LinkedHashSet<>(ids(portfolioDir, Half.DEV));
        expected.addAll(ids(portfolioDir, Half.HOLDOUT));
        Set<Integer> present = new LinkedHashSet<>();
        for (Scenario<S> s : portfolio) present.add(s.id);

        List<Integer> missing = new ArrayList<>();
        for (int id : expected) if (!present.contains(id)) missing.add(id);
        if (!missing.isEmpty())
            throw new IllegalStateException("the frozen split names " + missing.size() + " scenario(s) the portfolio does not "
                    + "contain (first few: " + missing.subList(0, Math.min(5, missing.size())) + "). The portfolio is not the "
                    + "one the split was frozen against.");
        List<Integer> uncovered = new ArrayList<>();
        for (int id : present) if (!expected.contains(id)) uncovered.add(id);
        if (!uncovered.isEmpty())
            throw new IllegalStateException("the portfolio contains " + uncovered.size() + " scenario(s) that neither split "
                    + "file lists (first few: " + uncovered.subList(0, Math.min(5, uncovered.size())) + "). It grew after its "
                    + "split was frozen; those scenarios belong to neither half.");

        List<Scenario<S>> out = new ArrayList<>();
        for (Scenario<S> s : portfolio) if (wanted.contains(s.id)) out.add(s);
        return out;
    }

    /**
     * Records which half owns an output root and refuses to let another write there: two halves in
     * one tree merge in every results file and the holdout stops being separable.
     */
    public static void claimOutputRoot(Path outRoot, Half half) throws IOException {
        Path marker = outRoot.resolve(ROOT_MARKER);
        if (Files.exists(marker)) {
            String held = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            if (!held.equals(half.key()))
                throw new IllegalStateException("output directory " + outRoot + " already holds the '" + held + "' split; "
                        + "refusing to write '" + half.key() + "' into it. Use a separate output directory.");
            return;
        }
        RunGuard.atomicWrite(marker, half.key() + "\n");
    }
}
