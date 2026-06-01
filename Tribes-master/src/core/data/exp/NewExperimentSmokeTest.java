package core.data.exp;

import java.io.File;
import java.nio.file.Files;

/**
 * Fast validation of the Phase 1/2/3 runners at tiny scale (few games, small budgets) into
 * test_results/smoke/. Exercises the exact same code paths as the full runs, so if this
 * passes the real runs should work. Also verifies that solo (1-tribe) SCORE mode runs.
 */
public class NewExperimentSmokeTest {

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke";
        deleteDir(new File(base));

        int games = 2;
        int turnLimit = 8;   // short Capitals games so the smoke is fast
        int threads = 4;
        Budget[] tiny = { new Budget("tiny", 400) };

        System.out.println("==================== PHASE 1 (smoke) ====================");
        Phase1Runner.run(games, turnLimit, tiny, new int[]{5, 20}, threads, base + "/phase1");

        System.out.println("==================== PHASE 2 (smoke) ====================");
        int[] ratios = Phase2Runner.ratiosFromPhase1(base + "/phase1");
        System.out.println("  (ratios from Phase 1: " + java.util.Arrays.toString(ratios) + ")");
        Phase2Runner.run(games, turnLimit, tiny, ratios, threads, base + "/phase2");

        System.out.println("==================== PHASE 3 (smoke, solo 1-tribe) ====================");
        Phase3Runner.run(games, tiny, ratios[0], threads, base + "/phase3");

        System.out.println("\n==================== VERIFICATION ====================");
        verifyDir(new File(base + "/phase1"));
        verifyDir(new File(base + "/phase2"));
        verifyDir(new File(base + "/phase3"));

        File bestRatio = new File(base + "/phase1/phase1_best_ratio.txt");
        System.out.println("\nphase1_best_ratio.txt present: " + bestRatio.exists());
        System.out.println("Total smoke time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println("If all CSVs above have >1 line (header + data) and Phase 3 produced rows,");
        System.out.println("the solo SCORE mode works and the pipeline is healthy.");
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
