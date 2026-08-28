package core.data.exp;

import core.data.exp.IsolationPhaseRunner.Scenario;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The frozen development / holdout partition of a scenario portfolio.
 *
 * <p><b>Why this exists.</b> Every judgement call in the action-bias line — the winning epsilon, the
 * champion arm, the repair granularity — is made by taking a maximum over arms. With eleven arms and
 * a per-arm CI of roughly &plusmn;19, that maximum is biased upward by a meaningful fraction of the
 * effect being claimed. The only clean fix is a set of scenarios that no choice was made on: the
 * <b>holdout</b>, read exactly once, after those choices are written down.
 *
 * <p><b>The partition rule.</b> Scenarios are stratified by {@code (Source, CapturedTick)} and the
 * members of each stratum are dealt alternately, in manifest order, starting with DEV. A contiguous
 * first-half/second-half cut would have been simpler and wrong: capture ticks cycle through a fixed
 * list as the portfolio is built ({@code {10,12,...,30}} for single-source, {@code {15,20,25}} for
 * dual), so a contiguous cut whose boundary does not align with the cycle hands the two halves
 * different game-phase distributions. A holdout that differs from development in game phase is no
 * longer testing replication; it is testing generalisation across game phase, and the difference is
 * invisible in the results. Dealing within strata makes source and tick balanced by construction,
 * and alternating (rather than splitting each stratum contiguously) also neutralises any monotone
 * drift along generation order.
 *
 * <p><b>The partition is frozen to disk, and the files are authoritative.</b> {@link #freeze} writes
 * {@code _split_dev.txt} and {@code _split_holdout.txt} beside the manifest, each carrying a hash of
 * the portfolio's <i>identity</i> — the ordered {@code (id, source, capturedTick)} tuples. Later runs
 * <b>read</b> those files; they never recompute the partition. Recomputing is what would let the
 * split silently follow a rebuilt portfolio, and a holdout that quietly changed membership between
 * two stages is worth nothing. The identity hash deliberately excludes branching factor and the
 * source seeds: those may legitimately be recomputed, while a changed scenario identity means the
 * frozen split no longer describes this portfolio and the run must stop.
 *
 * <p>Usage — freeze and report, running no experiment work:
 * <pre>
 *   java -cp "out:lib/*" core.data.exp.PortfolioSplit test_results/stage1/_portfolio
 * </pre>
 *
 * @see Stage1Runner
 */
public final class PortfolioSplit {

    private PortfolioSplit() {}

    public static final String MANIFEST      = "portfolio.csv";
    public static final String DEV_FILE      = "_split_dev.txt";
    public static final String HOLDOUT_FILE  = "_split_holdout.txt";
    /** Written into an output root the first time a split runs there; guards against mixing. */
    public static final String ROOT_MARKER   = "_split.txt";

    /** Manifest column indices (see {@code BuildV5Portfolio.MANIFEST_HEADER}). */
    private static final int COL_ID = 0, COL_SOURCE = 1, COL_TICK = 5, COL_BRANCHING = 10;
    /** Same minimum-field guard {@code IsolationPhaseRunner.loadPortfolio} uses. */
    private static final int MIN_FIELDS = 11;

    private static final String HASH_PREFIX = "# identity-sha256: ";

    /** Which half of the portfolio a run may see. */
    public enum Split {
        /** The whole portfolio — the legacy behaviour, and the only one that mixes the two halves. */
        ALL,
        /** Development: every design choice is made here. */
        DEV,
        /** Holdout: read once, after the choices are frozen. */
        HOLDOUT;

        public String key() { return name().toLowerCase(Locale.ROOT); }
        public boolean isSubset() { return this != ALL; }

        public static Split parse(String raw) {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            switch (s) {
                case "":
                case "all":      return ALL;
                case "dev":
                case "development": return DEV;
                case "holdout":
                case "hold":     return HOLDOUT;
                default:
                    throw new IllegalArgumentException(
                            "unknown split '" + raw + "' — expected one of: all, dev, holdout");
            }
        }
    }

    // ---------------------------------------------------------------------------------- freezing

    /**
     * Writes the two split files if they do not exist, and verifies them against the manifest if
     * they do. Idempotent and safe to call on every run.
     *
     * @throws IllegalStateException if the portfolio's identity has changed since the split was
     *                               frozen, or if only one of the two files is present
     */
    public static synchronized void freeze(File portfolioDir) throws Exception {
        List<Row> rows = readManifest(portfolioDir);
        String identity = identityHash(rows);
        File dev = new File(portfolioDir, DEV_FILE);
        File hold = new File(portfolioDir, HOLDOUT_FILE);

        if (dev.exists() && hold.exists()) {
            checkHash(dev, identity);
            checkHash(hold, identity);
            return;
        }
        if (dev.exists() || hold.exists()) {
            throw new IllegalStateException(
                    "portfolio split is half-written: exactly one of " + DEV_FILE + " / " + HOLDOUT_FILE
                    + " exists in " + portfolioDir + ". Delete both and re-freeze, or restore the "
                    + "missing one — a partial split cannot be trusted.");
        }

        // Stratify by (Source, CapturedTick); within a stratum sort by branching factor and deal in
        // matched PAIRS, alternating which side gets the lower member. Three details matter, and
        // each was forced by measuring the simpler version on the 60-scenario portfolio:
        //
        //  - Splitting each stratum contiguously, or dealing without carrying state between strata,
        //    gives every odd stratum's leftover to DEV. Those biases accumulate in one direction:
        //    the tick lists leave three singleton strata, and the result was a 33/27 split rather
        //    than 30/30. Both carries below are global for that reason, so leftovers alternate and
        //    the totals differ by at most one however ragged the strata are.
        //  - Branching factor spans several-fold across the portfolio and tracks how much work the
        //    search has to do, so it is the one covariate worth balancing. Plain alternate dealing
        //    of a sorted stratum does not: it hands one side the lower member of every adjacent
        //    pair, which left the two halves 22 branching points apart (266.0 vs 288.4).
        //  - Dealing matched pairs and flipping the direction each pair cancels that to second
        //    order — DEV takes the lower of one pair and the upper of the next.
        //
        // None of this biases either half's estimate: the analysis is paired within scenario. It
        // makes the two halves COMPARABLE, which is the whole point of a holdout that has to
        // replicate a development-set effect rather than merely produce one of its own.
        Map<String, List<Row>> strata = new LinkedHashMap<>();
        for (Row r : rows) strata.computeIfAbsent(r.source + "@" + r.tick, k -> new ArrayList<>()).add(r);

        List<Row> devRows = new ArrayList<>(), holdRows = new ArrayList<>();
        List<String> strataReport = new ArrayList<>();
        int pairToggle = 0, oddCarry = 0;
        for (Map.Entry<String, List<Row>> e : strata.entrySet()) {
            List<Row> members = new ArrayList<>(e.getValue());
            members.sort((a, b) -> a.branching != b.branching
                    ? Integer.compare(a.branching, b.branching)
                    : Integer.compare(a.id, b.id));
            int d = 0;
            int i = 0;
            for (; i + 1 < members.size(); i += 2) {
                Row lo = members.get(i), hi = members.get(i + 1);
                if (pairToggle++ % 2 == 0) { devRows.add(lo); holdRows.add(hi); }
                else                       { devRows.add(hi); holdRows.add(lo); }
                d++;
            }
            if (i < members.size()) {           // odd member out
                if (oddCarry++ % 2 == 0) { devRows.add(members.get(i)); d++; }
                else holdRows.add(members.get(i));
            }
            strataReport.add(e.getKey() + "=" + d + "/" + (members.size() - d));
        }
        devRows.sort((a, b) -> Integer.compare(a.id, b.id));
        holdRows.sort((a, b) -> Integer.compare(a.id, b.id));

        write(dev,  Split.DEV,     devRows,  identity, strataReport);
        write(hold, Split.HOLDOUT, holdRows, identity, strataReport);

        System.out.println("[split] froze portfolio split in " + portfolioDir);
        System.out.println("[split]   " + summary(Split.DEV, devRows));
        System.out.println("[split]   " + summary(Split.HOLDOUT, holdRows));
    }

    private static void write(File f, Split split, List<Row> rows, String identity,
                              List<String> strataReport) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# AB-EMCTS portfolio split — ").append(split.name()).append('\n');
        sb.append("# frozen: ").append(Instant.now()).append('\n');
        sb.append(HASH_PREFIX).append(identity).append('\n');
        sb.append("# rule: stratified by (Source, CapturedTick); within a stratum ordered by"
                + " (BranchingFactor, ScenarioId) and dealt alternately, with the deal parity"
                + " carried across strata so leftovers alternate\n");
        sb.append("# ").append(summary(split, rows)).append('\n');
        sb.append("# strata (dev/holdout): ").append(String.join(" ", strataReport)).append('\n');
        sb.append("# One scenario id per line. These files are AUTHORITATIVE: readers never"
                + " recompute the split.\n");
        for (Row r : rows) sb.append(r.id).append('\n');
        RunGuard.atomicWrite(f, sb.toString());
    }

    private static String summary(Split split, List<Row> rows) {
        Map<String, Integer> bySource = new LinkedHashMap<>();
        double bf = 0;
        for (Row r : rows) {
            bySource.merge(r.source, 1, Integer::sum);
            bf += r.branching;
        }
        StringBuilder src = new StringBuilder();
        for (Map.Entry<String, Integer> e : bySource.entrySet()) {
            if (src.length() > 0) src.append(", ");
            src.append(e.getKey()).append('=').append(e.getValue());
        }
        return String.format(Locale.ROOT, "%s: %d scenarios (%s), mean branching %.1f",
                split.name(), rows.size(), src, rows.isEmpty() ? 0.0 : bf / rows.size());
    }

    private static void checkHash(File splitFile, String identity) throws Exception {
        String found = null;
        for (String line : Files.readAllLines(splitFile.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith(HASH_PREFIX)) { found = line.substring(HASH_PREFIX.length()).trim(); break; }
        }
        if (found == null) {
            throw new IllegalStateException(splitFile + " has no '" + HASH_PREFIX.trim()
                    + "' line — it was not written by PortfolioSplit. Refusing to trust it.");
        }
        if (!found.equals(identity)) {
            throw new IllegalStateException(
                    "the portfolio changed after its split was frozen.\n"
                    + "  " + splitFile + "\n"
                    + "    frozen identity : " + found + "\n"
                    + "    current identity: " + identity + "\n"
                    + "  The frozen split no longer describes this portfolio, so development and "
                    + "holdout membership is not what earlier stages used. Either restore the "
                    + "original portfolio, or delete both split files and re-freeze — and treat "
                    + "every result measured against the old split as belonging to a different "
                    + "experiment.");
        }
    }

    // ----------------------------------------------------------------------------------- reading

    /**
     * The scenario ids belonging to one split, read from the frozen file. Returns {@code null} for
     * {@link Split#ALL}, meaning "no filter".
     */
    public static Set<Integer> ids(File portfolioDir, Split split) throws Exception {
        if (!split.isSubset()) return null;
        File f = new File(portfolioDir, split == Split.DEV ? DEV_FILE : HOLDOUT_FILE);
        if (!f.exists()) throw new FileNotFoundException(
                "split file not found: " + f + " — run PortfolioSplit.freeze first.");
        Set<Integer> out = new LinkedHashSet<>();
        for (String raw : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(Integer.parseInt(line));
        }
        if (out.isEmpty()) throw new IllegalStateException(f + " lists no scenario ids.");
        return out;
    }

    /**
     * Filters a loaded portfolio down to one split, freezing the partition first if needed.
     *
     * <p>Both directions are checked, because both failures are silent otherwise: a frozen id that
     * the portfolio no longer contains means the portfolio shrank, and a portfolio id that neither
     * split file lists means it grew after freezing — in which case the new scenarios belong to
     * neither half and would be dropped without a word.
     */
    public static List<Scenario> apply(List<Scenario> portfolio, File portfolioDir, Split split)
            throws Exception {
        if (!split.isSubset()) return portfolio;
        freeze(portfolioDir);

        Set<Integer> wanted = ids(portfolioDir, split);
        Set<Integer> devIds = ids(portfolioDir, Split.DEV);
        Set<Integer> holdIds = ids(portfolioDir, Split.HOLDOUT);

        Set<Integer> present = new LinkedHashSet<>();
        for (Scenario s : portfolio) present.add(s.id);

        // The portfolio must match the frozen split EXACTLY — not merely cover the half being run.
        // Checking only the requested half lets a portfolio that has lost holdout scenarios sail
        // through a dev run, and the dev results are then attributed to a portfolio that no longer
        // exists whole. Both halves are checked on every run for that reason.
        Set<Integer> expected = new LinkedHashSet<>(devIds);
        expected.addAll(holdIds);

        List<Integer> missing = new ArrayList<>();
        for (int id : expected) if (!present.contains(id)) missing.add(id);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "the frozen split names " + missing.size() + " scenario(s) the portfolio does not "
                    + "contain (first few: " + missing.subList(0, Math.min(5, missing.size()))
                    + "). The portfolio is not the one the split was frozen against — it has lost "
                    + "scenarios, so results measured here would be attributed to a portfolio that no "
                    + "longer exists whole.");
        }
        List<Integer> uncovered = new ArrayList<>();
        for (int id : present) if (!expected.contains(id)) uncovered.add(id);
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException(
                    "the portfolio contains " + uncovered.size() + " scenario(s) that neither split "
                    + "file lists (first few: " + uncovered.subList(0, Math.min(5, uncovered.size()))
                    + "). The portfolio grew after its split was frozen, so those scenarios belong to "
                    + "neither half and would be silently dropped. Re-freeze deliberately, or use the "
                    + "portfolio the split was frozen against.");
        }

        List<Scenario> out = new ArrayList<>();
        for (Scenario s : portfolio) if (wanted.contains(s.id)) out.add(s);
        return out;
    }

    /**
     * Records which split owns an output root, and refuses to let a second one write there.
     *
     * <p>Runs normally get their own root per split, so this only fires when an explicit output
     * directory is reused — but that is exactly the case where the two halves would land in one
     * {@code results.csv} and stop being separable at all.
     */
    public static void claimOutputRoot(File outRoot, Split split) throws Exception {
        File marker = new File(outRoot, ROOT_MARKER);
        if (marker.exists()) {
            String held = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            if (!held.equals(split.key())) {
                throw new IllegalStateException(
                        "output directory " + outRoot + " already holds the '" + held + "' split; "
                        + "refusing to write '" + split.key() + "' into it. Mixing two splits in one "
                        + "output tree merges them in every results.csv and makes the holdout "
                        + "unrecoverable. Use a separate output directory.");
            }
            return;
        }
        ExpCsv.ensureDir(outRoot);
        RunGuard.atomicWrite(marker, split.key() + "\n");
    }

    // ------------------------------------------------------------------------------------ manifest

    private static final class Row {
        final int id; final String source; final int tick; final int branching;
        Row(int id, String source, int tick, int branching) {
            this.id = id; this.source = source; this.tick = tick; this.branching = branching;
        }
    }

    private static List<Row> readManifest(File portfolioDir) throws Exception {
        File manifest = new File(portfolioDir, MANIFEST);
        if (!manifest.exists()) throw new FileNotFoundException(
                "portfolio manifest not found: " + manifest + " — build the portfolio first.");
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (f.length < MIN_FIELDS) continue;
            rows.add(new Row(Integer.parseInt(f[COL_ID].trim()),
                             f[COL_SOURCE].trim(),
                             Integer.parseInt(f[COL_TICK].trim()),
                             Integer.parseInt(f[COL_BRANCHING].trim())));
        }
        if (rows.isEmpty()) throw new IllegalStateException("portfolio manifest has no rows: " + manifest);
        return rows;
    }

    /** Hash of the ordered (id, source, capturedTick) tuples — the portfolio's identity for splitting. */
    private static String identityHash(List<Row> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) sb.append(r.id).append(',').append(r.source).append(',').append(r.tick).append('\n');
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    // ---------------------------------------------------------------------------------------- main

    /** Freezes the split for a portfolio and prints it, running no experiment work. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -cp \"out:lib/*\" core.data.exp.PortfolioSplit <portfolioDir>");
            System.exit(2);
        }
        File dir = new File(args[0].trim());
        List<Row> rows = readManifest(dir);
        System.out.println("[split] portfolio: " + dir + "  (" + rows.size() + " scenarios)");
        System.out.println("[split] identity-sha256: " + identityHash(rows));
        freeze(dir);
        for (Split s : new Split[]{Split.DEV, Split.HOLDOUT}) {
            Set<Integer> ids = ids(dir, s);
            System.out.println("[split] " + s.name() + ": " + ids.size() + " ids -> "
                    + new File(dir, s == Split.DEV ? DEV_FILE : HOLDOUT_FILE));
        }
    }
}
