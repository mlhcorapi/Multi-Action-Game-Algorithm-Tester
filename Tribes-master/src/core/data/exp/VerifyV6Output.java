package core.data.exp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Integrity check (and repair) for the v6 output tree.
 *
 * <p><b>Why a separate tool.</b> The resume logic protects a leaf it is about to continue: it compacts
 * the partial CSVs and recomputes what is missing. It does nothing for a leaf that was already marked
 * complete, and that is exactly where the dangerous corruption lands. Two writers on one tree — a
 * backgrounded run that outlived its notebook kernel, plus a "resume" started beside it — append to
 * the same files and each mark the same configs done. The result is a dataset with every row twice.
 * It does not crash, it does not warn, and a duplicated row silently doubles that
 * {@code (scenario, visit)}'s weight in every average and every paired test downstream.
 * {@link RunGuard#acquireLock} now prevents the cause; this verifies the effect, including on data
 * produced before that lock existed.
 *
 * <p><b>What it checks</b>, per leaf ({@code <Agent>/[ratio_<r>/]<cu>cu/}):
 * <ol>
 *   <li><b>Duplicate turns</b> — the same {@code (ScenarioId, VisitId)} appearing more than once in
 *       {@code turn_telemetry.csv}, or more than {@code VISITS_PER_SCENARIO} burn rows' worth in
 *       {@code results.csv}. This is the two-writer signature.</li>
 *   <li><b>Orphan result rows</b> — burn rows for a turn with no telemetry row, i.e. a turn that was
 *       interrupted between its two writes.</li>
 *   <li><b>Torn rows</b> — lines with the wrong field count, the shape a kill mid-append leaves.</li>
 *   <li><b>Wrong burn count</b> — a turn whose results rows do not number exactly the expected burns,
 *       which means rows were lost or mixed.</li>
 *   <li><b>Completeness</b> — leaves marked done in {@code _completed.txt} that do not actually hold
 *       a full portfolio × visits of turns.</li>
 * </ol>
 *
 * <p><b>Repair</b> ({@code --repair}) rewrites each affected leaf keeping, for every
 * {@code (ScenarioId, VisitId)}, the FIRST complete copy of its telemetry row and the results rows
 * that belong to it, and drops everything else. First rather than last because the first copy is the
 * one written by the run that legitimately owned the leaf. A {@code .bak} of every rewritten file is
 * kept alongside it. A leaf that turns out to be incomplete after repair has its key removed from
 * {@code _completed.txt}, so the next run finishes it properly instead of trusting the marker.
 *
 * <p>Read-only by default — run it before analysis, and after any interrupted run.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.VerifyV6Output [baseDir] [--repair]}
 */
public final class VerifyV6Output {

    /** Burn rows expected per turn (the fixed bridge-burn count every agent performs). */
    public static final int EXPECTED_BURNS_PER_TURN = 20;

    private static final int TELEMETRY_FIELDS = 22;

    private VerifyV6Output() {}

    public static void main(String[] args) throws Exception {
        String base = RunExperimentV6.BASE;
        boolean repair = false;
        for (String a : args) {
            if (a.equals("--repair")) repair = true;
            else if (!a.startsWith("--")) base = a;
        }
        File baseDir = new File(base);
        if (!baseDir.isDirectory()) {
            System.err.println("[verify] no such directory: " + baseDir.getPath());
            System.exit(2);
        }

        System.out.println("############################################################");
        System.out.println("# v6 output integrity   (" + baseDir.getPath() + ")");
        System.out.println("# mode: " + (repair ? "REPAIR (files rewritten, .bak kept)" : "CHECK ONLY"));
        System.out.println("############################################################");

        List<File> leaves = findLeaves(baseDir);
        System.out.println("[verify] " + leaves.size() + " leaf director" + (leaves.size() == 1 ? "y" : "ies"));

        int expectedTurns = -1;
        File manifest = new File(baseDir, "_portfolio/portfolio.csv");
        if (manifest.exists()) {
            long scenarios = Files.lines(manifest.toPath()).count() - 1;
            expectedTurns = (int) scenarios * IsolationPhaseRunnerV6.VISITS_PER_SCENARIO;
            System.out.println("[verify] portfolio has " + scenarios + " scenarios => a complete leaf is "
                    + expectedTurns + " turns");
        }

        List<Report> reports = new ArrayList<>();
        for (File leaf : leaves) reports.add(check(leaf, baseDir, expectedTurns, repair));

        System.out.println();
        System.out.printf(Locale.ROOT, "%-46s %8s %8s %8s %8s %8s%n",
                "leaf", "turns", "dupTurn", "orphan", "torn", "badBurn");
        int problems = 0;
        for (Report r : reports) {
            boolean bad = r.duplicateTurns > 0 || r.orphanResultRows > 0 || r.tornRows > 0
                    || r.turnsWithWrongBurnCount > 0;
            if (bad) problems++;
            System.out.printf(Locale.ROOT, "%-46s %8d %8d %8d %8d %8d %s%n",
                    r.name, r.turns, r.duplicateTurns, r.orphanResultRows, r.tornRows,
                    r.turnsWithWrongBurnCount, bad ? "  <-- PROBLEM" : "");
        }

        // Leaves claimed complete in the checkpoint that are not.
        File ckpt = new File(baseDir, "_completed.txt");
        List<String> falseComplete = new ArrayList<>();
        if (ckpt.exists() && expectedTurns > 0) {
            Set<String> done = ExpCsv.readCompleted(ckpt);
            for (Report r : reports) {
                if (r.checkpointKey != null && done.contains(r.checkpointKey) && r.turns < expectedTurns) {
                    falseComplete.add(r.checkpointKey + "  (" + r.turns + "/" + expectedTurns + " turns)");
                }
            }
        }

        System.out.println();
        if (problems == 0 && falseComplete.isEmpty()) {
            System.out.println("[verify] PASS — no duplicated, orphaned or torn rows; no leaf claims to be");
            System.out.println("         complete without being complete.");
        } else {
            System.out.println("[verify] " + problems + " leaf/leaves with row-level problems"
                    + (falseComplete.isEmpty() ? "" : ", " + falseComplete.size()
                       + " falsely marked complete:"));
            for (String s : falseComplete) System.out.println("           " + s);
            if (repair) {
                if (!falseComplete.isEmpty()) {
                    rewriteCheckpoint(ckpt, reports, expectedTurns);
                    System.out.println("[verify] _completed.txt rewritten — the incomplete leaves will be");
                    System.out.println("         finished by the next run instead of being skipped.");
                }
                System.out.println("[verify] REPAIRED. Re-run without --repair to confirm a clean tree,");
                System.out.println("         then re-run the experiment to fill anything that was dropped.");
            } else {
                System.out.println("[verify] Re-run with --repair to fix (a .bak of each file is kept).");
                System.exit(1);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Per-leaf findings. */
    public static final class Report {
        String name, checkpointKey;
        int turns, duplicateTurns, orphanResultRows, tornRows, turnsWithWrongBurnCount;
    }

    private static Report check(File leaf, File baseDir, int expectedTurns, boolean repair) throws Exception {
        Report rep = new Report();
        rep.name = relativise(baseDir, leaf);
        rep.checkpointKey = checkpointKeyFor(baseDir, leaf);

        File tele = new File(leaf, "turn_telemetry.csv");
        File res = new File(leaf, "results.csv");

        // --- telemetry: one row per completed turn, keyed (ScenarioId@0, VisitId@2) ---
        List<String> teleLines = tele.exists() ? Files.readAllLines(tele.toPath()) : new ArrayList<>();
        String teleHeader = teleLines.isEmpty() ? IsolationPhaseRunnerV6.TELEMETRY_HEADER : teleLines.get(0);
        Map<String, String> firstTele = new LinkedHashMap<>();
        for (int i = 1; i < teleLines.size(); i++) {
            String[] f = teleLines.get(i).split(",", -1);
            if (f.length < TELEMETRY_FIELDS) { rep.tornRows++; continue; }
            String key;
            try { key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[2].trim()); }
            catch (NumberFormatException e) { rep.tornRows++; continue; }
            if (firstTele.containsKey(key)) rep.duplicateTurns++;
            else firstTele.put(key, teleLines.get(i));
        }
        rep.turns = firstTele.size();

        // --- results: burn rows keyed (ScenarioId@0, VisitId@4) ---
        List<String> resLines = res.exists() ? Files.readAllLines(res.toPath()) : new ArrayList<>();
        String resHeader = resLines.isEmpty() ? null : resLines.get(0);
        int resFields = resHeader == null ? -1 : resHeader.split(",", -1).length;
        Map<String, List<String>> resByTurn = new LinkedHashMap<>();
        for (int i = 1; i < resLines.size(); i++) {
            String[] f = resLines.get(i).split(",", -1);
            if (resFields > 0 && f.length < resFields) { rep.tornRows++; continue; }
            String key;
            try { key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[4].trim()); }
            catch (NumberFormatException e) { rep.tornRows++; continue; }
            resByTurn.computeIfAbsent(key, k -> new ArrayList<>()).add(resLines.get(i));
        }

        Map<String, List<String>> keptRes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : resByTurn.entrySet()) {
            List<String> rows = e.getValue();
            if (!firstTele.containsKey(e.getKey())) {
                rep.orphanResultRows += rows.size();      // no telemetry => the turn never finished
                continue;
            }
            if (rows.size() == EXPECTED_BURNS_PER_TURN) {
                keptRes.put(e.getKey(), rows);
            } else if (rows.size() > EXPECTED_BURNS_PER_TURN
                    && rows.size() % EXPECTED_BURNS_PER_TURN == 0) {
                // An exact multiple is the two-writer signature: keep the first complete block.
                rep.duplicateTurns++;
                keptRes.put(e.getKey(), new ArrayList<>(rows.subList(0, EXPECTED_BURNS_PER_TURN)));
            } else {
                rep.turnsWithWrongBurnCount++;
                keptRes.put(e.getKey(), rows);            // preserved; flagged, not silently trimmed
            }
        }
        // Turns whose telemetry exists but whose results rows are missing entirely.
        for (String key : firstTele.keySet()) {
            if (!resByTurn.containsKey(key)) rep.turnsWithWrongBurnCount++;
        }

        boolean dirty = rep.duplicateTurns > 0 || rep.orphanResultRows > 0 || rep.tornRows > 0;
        if (repair && dirty) {
            if (tele.exists()) {
                backup(tele);
                writeCsv(tele, teleHeader, new ArrayList<>(firstTele.values()));
            }
            if (res.exists() && resHeader != null) {
                backup(res);
                List<String> flat = new ArrayList<>();
                for (List<String> rows : keptRes.values()) flat.addAll(rows);
                writeCsv(res, resHeader, flat);
            }
            System.out.println("  repaired " + rep.name + ": kept " + firstTele.size() + " turns, dropped "
                    + rep.duplicateTurns + " duplicate(s), " + rep.orphanResultRows + " orphan row(s), "
                    + rep.tornRows + " torn row(s)");
        }
        return rep;
    }

    /** Rewrite _completed.txt without the keys of leaves that are not actually complete. */
    private static void rewriteCheckpoint(File ckpt, List<Report> reports, int expectedTurns) {
        Set<String> keep = new LinkedHashSet<>(ExpCsv.readCompleted(ckpt));
        Map<String, Integer> turnsByKey = new HashMap<>();
        for (Report r : reports) if (r.checkpointKey != null) turnsByKey.put(r.checkpointKey, r.turns);
        keep.removeIf(k -> turnsByKey.containsKey(k) && turnsByKey.get(k) < expectedTurns);
        backup(ckpt);
        StringBuilder sb = new StringBuilder();
        for (String k : keep) sb.append(k).append('\n');
        RunGuard.atomicWrite(ckpt, sb.toString());
    }

    private static void backup(File f) {
        try {
            File bak = new File(f.getParentFile(), f.getName() + ".bak");
            Files.copy(f.toPath(), bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[verify] could not back up " + f + ": " + e);
        }
    }

    private static void writeCsv(File f, String header, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Every directory holding a results.csv or turn_telemetry.csv, excluding the internal dirs. */
    private static List<File> findLeaves(File baseDir) {
        List<File> out = new ArrayList<>();
        collect(baseDir, out);
        out.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        boolean isLeaf = false;
        for (File f : kids) {
            if (f.isFile() && (f.getName().equals("results.csv") || f.getName().equals("turn_telemetry.csv")))
                isLeaf = true;
        }
        if (isLeaf) { out.add(dir); return; }
        for (File f : kids) {
            if (f.isDirectory() && !f.getName().startsWith("_")) collect(f, out);
        }
    }

    /** Rebuild the checkpoint key IsolationPhaseRunnerV6 would have written for this leaf. */
    private static String checkpointKeyFor(File baseDir, File leaf) {
        String rel = relativise(baseDir, leaf);
        String[] parts = rel.split("/");
        if (parts.length < 2) return null;
        String cuPart = parts[parts.length - 1];
        if (!cuPart.endsWith("cu")) return null;
        String cu = cuPart.substring(0, cuPart.length() - 2);
        String agent = parts[0];
        if (parts.length >= 3 && parts[1].startsWith("ratio_")) {
            return "agent=" + agent + ";ratio=" + parts[1].substring("ratio_".length()) + ";cu=" + cu;
        }
        return "agent=" + agent + ";cu=" + cu;
    }

    private static String relativise(File base, File f) {
        String b = base.getAbsolutePath().replace('\\', '/');
        String p = f.getAbsolutePath().replace('\\', '/');
        return p.startsWith(b) ? p.substring(Math.min(p.length(), b.length() + 1)) : p;
    }
}
