package core.data.exp;

import java.io.File;
import java.util.Arrays;

/**
 * Single entry point for the full experiment suite: runs Phase 1 (ratio analysis), then
 * Phase 2 (win rates, using Phase 1's best ratio), then Phase 3 (trajectory analysis), in
 * sequence, into test_results/full/. Prints a banner + elapsed time for each phase, and the
 * per-runner output shows per-matchup (M/TOTAL) and per-game progress.
 *
 * Resumable: each phase checkpoints completed matchups, so re-running this skips finished
 * work and continues where it stopped.
 *
 * Usage:  java -Xmx8g -cp out;lib/*  core.data.exp.RunAllExperiments [threads]
 *   threads defaults to 4 (memory-bound; see test-run-guide.md).
 */
public class RunAllExperiments {

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        String base = "test_results/full";
        long t0 = System.currentTimeMillis();

        System.out.println("############################################################");
        System.out.println("# FULL EXPERIMENT SUITE  (threads=" + threads + ", output=" + base + ")");
        System.out.println("# Resumable: re-run to continue after an interruption.");
        System.out.println("############################################################");

        banner("PHASE 1 / 3", "Ratio analysis: EMCTS vs CrossoverExpansion, Capitals/50, 100 games", t0);
        Phase1Runner.run(100, 50, Phase1Runner.defaultLevels(), Phase1Runner.DEFAULT_RATIOS, threads, base + "/phase1");

        int[] ratios = Phase2Runner.ratiosFromPhase1(base + "/phase1");
        System.out.println(">> Best crossover ratio(s) from Phase 1: " + Arrays.toString(ratios));

        banner("PHASE 2 / 3", "Win rates: 3 experimental vs 3 baselines, Capitals/50, time budgets, 100 games", t0);
        Phase2Runner.run(100, 50, Phase2Runner.defaultTimesMs(), ratios, threads, base + "/phase2");

        banner("PHASE 3 / 3", "Trajectory analysis: 4 EMCTS-family agents vs fixed passive opponent, SCORE/30, 100 games", t0);
        Phase3Runner.run(100, Phase3Runner.defaultLevels(), ratios[0], threads, base + "/phase3");

        System.out.println("\n############################################################");
        System.out.println("# ALL PHASES COMPLETE in " + elapsed(t0) + ".  Output in " + new File(base).getAbsolutePath());
        System.out.println("############################################################");
    }

    private static void banner(String phase, String desc, long t0) {
        System.out.println("\n============================================================");
        System.out.println("== " + phase + "   [elapsed " + elapsed(t0) + "]");
        System.out.println("== " + desc);
        System.out.println("============================================================");
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format("%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
