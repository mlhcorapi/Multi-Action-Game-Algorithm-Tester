package core.data.exp;

import java.io.File;
import java.util.Map;

/**
 * Single entry point for the full experiment suite. Runs in dependency order:
 *   1. Phase 3 (low-variance isolated setup): sweeps the crossover ratio and records trajectories,
 *      then selects the best ratio by average FinalSequenceValue.
 *   2. Phase 2 (win rates): uses that ratio for the experimental-vs-baseline matchups.
 * Output goes to test_results/full/. Prints a banner + elapsed time per phase; the per-runner
 * output shows per-matchup (M/TOTAL) and per-game progress.
 *
 * (There is no separate Phase 1 any more: the old ratio-analysis phase was folded into Phase 3's
 * controlled environment, where the ratio is chosen on search effectiveness with no opponent
 * interference and without tuning on an agent that Phase 2 later tests against.)
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

        // Phase 3 runs FIRST: in the low-variance isolated setup it sweeps the crossover ratio
        // (selecting the best by average FinalSequenceValue) and records the per-turn trajectories.
        banner("PHASE 3 - Ratio selection + trajectory", "4 EMCTS-family agents + CrossoverExpansion ratio sweep vs fixed passive opponent, SCORE/30, 50 games", t0);
        Phase3Runner.run(50, Phase3Runner.defaultLevels(), Phase3Runner.DEFAULT_RATIOS, threads, base + "/phase3");

        Map<String, Integer> bestByLevel = Phase3Runner.bestRatiosByLevel(base + "/phase3");
        System.out.println(">> Best crossover ratio per budget level (highest avg FinalSequenceValue): " + bestByLevel);

        banner("PHASE 2 - Win rates", "3 experimental vs 3 baselines, Capitals/50, time budgets, 100 games", t0);
        Phase2Runner.run(100, 50, Phase2Runner.defaultTimesMs(), bestByLevel, threads, base + "/phase2");

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
