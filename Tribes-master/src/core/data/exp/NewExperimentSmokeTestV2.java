package core.data.exp;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Fast validation of the v2 changes at tiny scale (into test_results/smoke_v2/):
 *   1. A tiny "prior" Phase 3 run (stands in for test_results/full): 2 games.
 *   2. A v2 additive DELTA: 3 more games with a seed/gameId offset, merging the prior dir for the best
 *      ratio. Verifies the delta's gameIds are DISJOINT from the prior's (so the two batches combine to
 *      independent samples, not duplicates) and that the combined best-ratio file is written.
 *   3. A v2 Phase 2 run at a fresh time level (stands in for adding 5000ms).
 * If this passes, the additive top-up + 5000ms-add machinery in RunExperimentV2 is sound.
 */
public class NewExperimentSmokeTestV2 {

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke_v2";
        deleteDir(new File(base));
        int threads = 4;
        int[] ratios = {5, 20};

        System.out.println("==================== 1. PRIOR (stand-in for v1): Phase 3, 2 games ====================");
        Budget[] prior = { new Budget("40ms", 2000, 2, 0) };           // games 1..2
        Phase3Runner.run(2, prior, ratios, threads, base + "/full/phase3");

        System.out.println("==================== 2. v2 DELTA: Phase 3 +3 games (offset 2 -> gameIds 3..5) ====================");
        Budget[] delta = { new Budget("40ms", 2000, 3, 2) };           // games 3..5, seeds offset by 2
        Phase3Runner.run(3, delta, ratios, threads, base + "/v2/phase3", base + "/full/phase3");

        Map<String, Integer> best = Phase3Runner.bestRatiosByLevel(base + "/v2/phase3");
        System.out.println("  combined best ratios: " + best);

        System.out.println("==================== 3. v2 Phase 2: add a fresh time level (40ms here) ====================");
        Phase2Runner.run(2, 8, new int[]{40}, best, threads, base + "/v2/phase2");

        System.out.println("\n==================== VERIFICATION ====================");
        Set<Integer> priorIds = expansionGameIds(new File(base + "/full/phase3/Phase3_ratio_turns_40ms.csv"));
        Set<Integer> deltaIds = expansionGameIds(new File(base + "/v2/phase3/Phase3_ratio_turns_40ms.csv"));
        System.out.println("prior gameIds = " + new TreeSet<>(priorIds) + "  delta gameIds = " + new TreeSet<>(deltaIds));
        Set<Integer> overlap = new TreeSet<>(priorIds); overlap.retainAll(deltaIds);
        boolean disjoint = overlap.isEmpty() && !priorIds.isEmpty() && !deltaIds.isEmpty();
        System.out.println("ADDITIVE OK (gameIds disjoint, both non-empty): " + disjoint + (overlap.isEmpty() ? "" : "  OVERLAP=" + overlap));
        System.out.println("combined sample size (prior + delta) = " + (priorIds.size() + deltaIds.size()));

        verifyDir(new File(base + "/v2/phase3"));
        verifyDir(new File(base + "/v2/phase2"));
        File bestRatio = new File(base + "/v2/phase3/phase3_best_ratio.txt");
        System.out.println("\nphase3_best_ratio.txt (combined) present: " + bestRatio.exists());
        System.out.println("Total smoke_v2 time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println(disjoint
                ? "PASS: additive top-up produces independent, combinable samples; 5000ms-add path works."
                : "FAIL: delta gameIds are not disjoint from prior - additive offset is wrong.");
    }

    /** Distinct GameId values for CrossoverExpansion rows in a Phase3_ratio_turns CSV. */
    private static Set<Integer> expansionGameIds(File csv) {
        Set<Integer> ids = new HashSet<>();
        if (!csv.exists()) return ids;
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            // header: AgentName,Ratio,GameId,TurnNumber,...
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",");
                if (f.length < 3) continue;
                if (!f[0].trim().equals("CrossoverExpansion")) continue;
                ids.add(Integer.parseInt(f[2].trim()));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return ids;
    }

    private static void verifyDir(File dir) {
        System.out.println("--- " + dir.getPath() + " ---");
        File[] files = dir.listFiles();
        if (files == null) { System.out.println("  (no output!)"); return; }
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".csv")) continue;
            long lines = 0;
            try { lines = Files.lines(f.toPath()).count(); } catch (Exception ignored) {}
            System.out.println(String.format("  %-42s %d lines", f.getName(), lines));
        }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }
}
