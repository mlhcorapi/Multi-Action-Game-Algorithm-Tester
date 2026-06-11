package core.data.exp;

import core.data.exp.IsolationPhaseRunner.Scenario;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v5 Isolation Phase — single entry point. Same design as v4 except:
 * <ul>
 *   <li>200-scenario portfolio (100 single-player + 100 two-player) — see {@link BuildV5Portfolio};
 *       the first 100 captures are the SAME game states/turns as v4.</li>
 *   <li>{@link IsolationPhaseRunnerV5#VISITS_PER_SCENARIO} = 100 visits per scenario.</li>
 *   <li>Ratios swept: {@code {2, 5, 10, 20, 50}}.</li>
 *   <li>Per-turn budget is the deterministic COMPUTE-UNIT budget (STOP_COMPUTE) instead of wall-clock,
 *       so results are reproducible and independent of CPU contention / multithreading.</li>
 * </ul>
 *
 * <p>Output: {@code test_results/v5/<Agent>/[ratio_<r>/]<cu>cu/results.csv} (36 leaf CSVs).
 *
 * <p>Usage: {@code java -Xmx<big>g -cp "out:lib/*" core.data.exp.RunExperimentV5 [threads] [cuCsv]}<br>
 * Server example: {@code java -Xmx700g -cp "out:lib/*" core.data.exp.RunExperimentV5 120}
 */
public class RunExperimentV5 {

    public static final String BASE = "test_results/v5";

    /** v5 compute-unit budgets. Mirror v4's 500/1000/5000 ms spacing using the portfolio's calibrated
     *  "1000ms-equivalent = 26800 units": 0.5× / 1× / 5×. Override via the 2nd CLI arg (comma-separated). */
    public static final int[] DEFAULT_COMPUTE_LEVELS = {13_400, 26_800, 134_000};

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int[] cuLevels = args.length > 1 ? parseInts(args[1]) : DEFAULT_COMPUTE_LEVELS;
        long t0 = System.currentTimeMillis();

        System.out.println("############################################################");
        System.out.println("# EXPERIMENT v5 — Isolation Phase   (threads=" + threads + ", output=" + BASE + ")");
        System.out.println("# Single-turn search from 200 captured scenarios × " + IsolationPhaseRunnerV5.VISITS_PER_SCENARIO
                + " visits = " + (200 * IsolationPhaseRunnerV5.VISITS_PER_SCENARIO) + " turns / config / budget");
        System.out.println("# Compute budgets: " + java.util.Arrays.toString(cuLevels) + " compute-units (STOP_COMPUTE)");
        System.out.println("# Ratios: " + java.util.Arrays.toString(IsolationPhaseRunnerV5.RATIOS));
        System.out.println("# Random-init mode (shared across agents per scenario × visit)");
        System.out.println("############################################################");

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        File ckpt = new File(baseDir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);

        // 1. Build the 200-scenario portfolio (always regenerates a fresh portfolio; parallel across threads).
        banner("PORTFOLIO build", t0);
        BuildV5Portfolio.main(new String[]{ BASE + "/_portfolio", String.valueOf(threads) });

        // 1b. Verify every persisted scenario starts up properly before any long isolation pass.
        banner("PORTFOLIO verify", t0);
        VerifyV4Portfolio.Report rep = VerifyV4Portfolio.verify(new File(BASE + "/_portfolio"));
        if (!rep.allPass()) {
            System.err.println("[v5] ABORT: " + rep.failed.size() + " scenario(s) failed verification: "
                    + rep.failed);
            System.err.println("Delete the failing scenario_NNN.bin + their _completed.txt entries, then re-run.");
            System.exit(1);
        }

        // 2. Load portfolio into memory ONCE (same .bin/manifest format as v4).
        banner("PORTFOLIO load", t0);
        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(BASE + "/_portfolio"));
        System.out.println("  loaded " + portfolio.size() + " scenarios into memory");

        // 3. Run each compute-budget pass.
        for (int cu : cuLevels) {
            banner("ISOLATION cu=" + cu, t0);
            IsolationPhaseRunnerV5.runForCompute(cu, threads, portfolio, baseDir, ckpt, done);
        }

        System.out.println("\n############################################################");
        System.out.println("# v5 COMPLETE in " + elapsed(t0) + ".  Output in " + baseDir.getAbsolutePath());
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
