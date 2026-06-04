package core.data.exp;

import java.io.File;
import java.util.Map;

/**
 * Follow-up experiment session (v2). Two improvements over the first run:
 *   1. Phase 3 (solo, low-variance) is topped up to 100 games at EVERY budget level. The first run had
 *      50/50/25 games (500/1000/5000ms) and showed game-to-game variance; v2 runs only the DELTA with
 *      fresh, independent seeds (Budget.firstGameIndex offset) so the new games COMBINE with the prior
 *      run instead of duplicating it: 500ms +50, 1000ms +50, 5000ms +75 -> 100 each when merged.
 *   2. Phase 2 (vs baselines) adds the 5000ms time level (100 games). OEP must be re-tuned for 5000ms
 *      first (see OepTuning) and ExpAgents.oepTime()'s 5000ms branch updated.
 *
 * The best CrossoverExpansion ratio per level is chosen over the COMBINED (prior + new) sample by passing
 * the prior Phase-3 dir as the merge source. The analysis (analysis/analyze.py) reads both
 * test_results/full and test_results/v2 and concatenates (gameIds do not collide).
 *
 * Output: test_results/v2/. Resumable (per-config / per-matchup checkpoints).
 *
 * Args: [threads] [phase2TimesCsv]   (phase2TimesCsv default "5000"; pass "500,1000,5000" to also
 *       re-run the lower Phase-2 levels, e.g. if the 100-game Phase 3 changed their best ratio.)
 *
 * Usage:  java -Xmx8g -cp out;lib/*  core.data.exp.RunExperimentV2 [threads] [phase2TimesCsv]
 */
public class RunExperimentV2 {

    static final String PRIOR = "test_results/full"; // v1 results to combine with / top up
    static final String BASE  = "test_results/v2";

    /** Phase-3 DELTA: games to ADD per level (with the prior run's game count as the seed/gameId offset). */
    static Budget[] phase3Delta() {
        return new Budget[]{
                new Budget("500ms",  13400,  50, 50),  // 50 prior + 50 new  (gameIds 51..100)
                new Budget("1000ms", 26800,  50, 50),  // 50 prior + 50 new
                new Budget("5000ms", 134000, 75, 25),  // 25 prior + 75 new  (gameIds 26..100)
        };
    }

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int[] phase2Times = args.length > 1 ? parseInts(args[1]) : new int[]{5000};
        long t0 = System.currentTimeMillis();

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v2  (threads=" + threads + ", output=" + BASE + ", prior=" + PRIOR + ")");
        System.out.println("# Phase 3 -> 100 games/level (additive); Phase 2 -> add " + java.util.Arrays.toString(phase2Times) + "ms");
        System.out.println("# Resumable: re-run to continue after an interruption.");
        System.out.println("############################################################");

        // Phase 3 delta: best ratio per level chosen over the COMBINED (prior + new) sample.
        banner("PHASE 3 v2 - top up to 100 games/level (additive delta)", t0);
        Phase3Runner.run(100, phase3Delta(), Phase3Runner.DEFAULT_RATIOS, threads, BASE + "/phase3", PRIOR + "/phase3");
        Map<String, Integer> bestByLevel = Phase3Runner.bestRatiosByLevel(BASE + "/phase3");
        System.out.println(">> Best crossover ratio per level over the COMBINED 100-game sample: " + bestByLevel);

        // Phase 2: add the 5000ms level (OEP must already be tuned for 5000ms; see ExpAgents.oepTime).
        banner("PHASE 2 v2 - win rates at " + java.util.Arrays.toString(phase2Times) + "ms", t0);
        Phase2Runner.run(100, 50, phase2Times, bestByLevel, threads, BASE + "/phase2");

        System.out.println("\n############################################################");
        System.out.println("# v2 COMPLETE in " + elapsed(t0) + ".  Output in " + new File(BASE).getAbsolutePath());
        System.out.println("# Combine with v1 via analysis/analyze.py (reads test_results/full + test_results/v2).");
        System.out.println("############################################################");
    }

    private static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }

    private static void banner(String phase, long t0) {
        System.out.println("\n============================================================");
        System.out.println("== " + phase + "   [elapsed " + elapsed(t0) + "]");
        System.out.println("============================================================");
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format("%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
