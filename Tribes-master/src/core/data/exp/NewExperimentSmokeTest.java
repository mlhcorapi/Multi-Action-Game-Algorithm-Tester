package core.data.exp;

import java.io.File;
import java.nio.file.Files;

/**
 * Fast validation of the Phase 3 (ratio sweep + trajectory) and Phase 2 (win rates) runners at
 * tiny scale (few games, small budgets) into test_results/smoke/. Exercises the exact same code
 * paths as the full runs - including selecting the crossover ratio in Phase 3 and feeding it to
 * Phase 2 - so if this passes the real runs should work.
 */
public class NewExperimentSmokeTest {

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke";
        deleteDir(new File(base));

        int games = 2;
        int turnLimit = 10;  // short Capitals games so the smoke is fast
        int threads = 4;
        // Use a representative (not micro) budget so node counts / fmcalls are meaningful.
        Budget[] tiny = { new Budget("b2000", 2000) };

        // Phase 3 runs first: it selects the crossover ratio (tiny sweep of 2 ratios) and records
        // trajectories in the isolated setup; Phase 2 then uses that ratio.
        System.out.println("==================== PHASE 3 (smoke: ratio sweep + trajectory) ====================");
        Phase3Runner.run(games, tiny, new int[]{5, 20}, threads, base + "/phase3");

        int best = Phase3Runner.bestRatioFromPhase3(base + "/phase3");
        System.out.println("  (best ratio from Phase 3: " + best + ")");

        System.out.println("==================== PHASE 2 (smoke, TIME budget) ====================");
        Phase2Runner.run(games, turnLimit, new int[]{40}, new int[]{best}, threads, base + "/phase2"); // 40ms/turn for a fast smoke

        System.out.println("\n==================== VERIFICATION ====================");
        verifyDir(new File(base + "/phase3"));
        verifyDir(new File(base + "/phase2"));

        File bestRatio = new File(base + "/phase3/phase3_best_ratio.txt");
        System.out.println("\nphase3_best_ratio.txt present: " + bestRatio.exists());
        System.out.println("Total smoke time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println("If all CSVs above have >1 line (header + data) and Phase 3 produced rows,");
        System.out.println("the SCORE-mode (vs passive opponent) games work and the pipeline is healthy.");
    }

    private static void verifyDir(File dir) {
        System.out.println("--- " + dir.getPath() + " ---");
        File[] files = dir.listFiles();
        if (files == null) { System.out.println("  (no output!)"); return; }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".csv")) continue;
            long lines = 0;
            try { lines = Files.lines(f.toPath()).count(); } catch (Exception ignored) {}
            System.out.println(String.format("  %-40s %d lines", f.getName(), lines));
        }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
