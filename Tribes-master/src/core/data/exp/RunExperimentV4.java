package core.data.exp;

import core.data.exp.IsolationPhaseRunner.Scenario;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v4 Isolation Phase — single entry point. Chains:
 * <ol>
 *   <li>Build the 100-scenario portfolio (50 single-player + 50 two-player), persisted under
 *       {@code test_results/v4/_portfolio/} with the {@code portfolio.csv} manifest.</li>
 *   <li>Load the portfolio into memory.</li>
 *   <li>For each thinking time in {@link #DEFAULT_THINKING_MS_LEVELS}, run all 12 agent configs
 *       (EMCTS, CrossoverBB, CrossoverExpansion × 5 ratios, CrossoverCombo × 5 ratios) over the full
 *       portfolio × {@link IsolationPhaseRunner#VISITS_PER_SCENARIO} visits.</li>
 * </ol>
 *
 * <p>Output: {@code test_results/v4/<Agent>/[ratio_<r>/]<ms>ms/results.csv} (36 leaf CSVs in total).
 *
 * <p>Resumable: portfolio generation skips already-saved scenarios; per-(agent, ratio, ms) checkpoint
 * skips already-finished leaf CSVs.
 *
 * <p>Usage: {@code java -Xmx<big>g -cp out;lib/* core.data.exp.RunExperimentV4 [threads] [msCsv]}<br>
 * Server example (1 TB / 128 cores): {@code java -Xmx700g -cp "out:lib/*" core.data.exp.RunExperimentV4 120}
 */
public class RunExperimentV4 {

    public static final String BASE              = "test_results/v4";
    public static final int[]  DEFAULT_THINKING_MS_LEVELS = {50, 500, 5000};

    public static void main(String[] args) throws Exception {
        int threads  = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int[] msLevels = args.length > 1 ? parseInts(args[1]) : DEFAULT_THINKING_MS_LEVELS;
        long t0 = System.currentTimeMillis();

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v4 — Isolation Phase   (threads=" + threads + ", output=" + BASE + ")");
        System.out.println("# Single-turn search from 100 captured scenarios × " + IsolationPhaseRunner.VISITS_PER_SCENARIO
                + " visits = "  + (100 * IsolationPhaseRunner.VISITS_PER_SCENARIO) + " turns / config / ms");
        System.out.println("# Thinking times: " + java.util.Arrays.toString(msLevels) + " ms");
        System.out.println("# Random-init mode (shared across agents per scenario × visit)");
        System.out.println("############################################################");

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        File ckpt = new File(baseDir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        // 1. Build portfolio (idempotent — skips already-saved scenarios).
        banner("PORTFOLIO build", t0);
        BuildV4Portfolio.main(new String[]{ BASE + "/_portfolio" });

        // 1b. Walk the entire persisted portfolio and verify each scenario starts up properly. Failing
        // EARLY here (before any 60k-task isolation pass) saves hours of silently-bad output if a
        // scenario was persisted with the passive opponent as the active tribe or any other engine-edge
        // state. Throws + exits if any scenario fails validation.
        banner("PORTFOLIO verify", t0);
        VerifyV4Portfolio.Report rep = VerifyV4Portfolio.verify(new File(BASE + "/_portfolio"));
        if (!rep.allPass()) {
            System.err.println("[v4] ABORT: " + rep.failed.size() + " scenario(s) failed verification: "
                    + rep.failed);
            System.err.println("Delete the failing scenario_NNN.bin + remove their _completed.txt entries");
            System.err.println("then re-run RunExperimentV4 to regenerate them.");
            System.exit(1);
        }

        // 2. Load portfolio into memory ONCE.
        banner("PORTFOLIO load", t0);
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(BASE + "/_portfolio"));
        System.out.println("  loaded " + portfolio.size() + " scenarios into memory");

        // 3. Run each thinking-time pass (sequential across ms levels — Constants.TURN_TIME_MILLIS is static).
        for (int ms : msLevels) {
            banner("ISOLATION ms=" + ms, t0);
            IsolationPhaseRunner.runForTime(ms, threads, portfolio, baseDir, ckpt, done);
        }

        System.out.println("\n############################################################");
        System.out.println("# v4 COMPLETE in " + elapsed(t0) + ".  Output in " + baseDir.getAbsolutePath());
        System.out.println("# Analyse with: python analysis/analyze_v4.py " + BASE);
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
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
