package core.data.exp;

import core.Constants;
import core.Types;

import java.util.Arrays;
import java.util.List;

/**
 * Bounded benchmark to find a safe thread count. Runs a few heavy head-to-head games
 * (EMCTS vs CrossoverCombo) at a given budget/thread count while sampling peak JVM heap.
 * ALWAYS run with a capped -Xmx (e.g. -Xmx2g) so it cannot exhaust system RAM.
 *
 * args: [threads] [budgetFmcalls] [games] [turnLimit]
 */
public class ThreadBenchmark {

    public static void main(String[] args) {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int budget = args.length > 1 ? Integer.parseInt(args[1]) : 30000;
        int games = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int turnLimit = args.length > 3 ? Integer.parseInt(args[3]) : 12;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.MAX_TURNS_CAPITALS = turnLimit;
        Types.TRIBE[] tribes = {Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};

        Runtime rt = Runtime.getRuntime();
        final long[] peak = {0};
        Thread sampler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long used = rt.totalMemory() - rt.freeMemory();
                    synchronized (peak) { if (used > peak[0]) peak[0] = used; }
                    Thread.sleep(40);
                }
            } catch (InterruptedException ignored) {}
        });
        sampler.setDaemon(true);
        sampler.start();

        List<AgentSpec> specs = Arrays.asList(
                ExpAgents.emcts(budget),
                ExpAgents.crossoverCombo(budget, 5));

        long t0 = System.currentTimeMillis();
        ExperimentEngine.runMatchup(specs, tribes, Types.GAME_MODE.CAPITALS, games, 1,
                g -> 1000L + g, g -> 2000L + g, g -> 3000L + g, threads, null);
        long elapsed = System.currentTimeMillis() - t0;

        sampler.interrupt();
        long peakMB = peak[0] / (1024 * 1024);
        double perThreadMB = (double) peakMB / threads;

        System.out.println("=== ThreadBenchmark ===");
        System.out.printf("threads=%d budget=%d games=%d turnLimit=%d%n", threads, budget, games, turnLimit);
        System.out.printf("wallClock=%.1fs  (%.2f s/game)%n", elapsed / 1000.0, elapsed / 1000.0 / games);
        System.out.printf("maxHeap(-Xmx)=%dMB  peakUsedHeap=%dMB  ~perConcurrentGame=%.0fMB%n",
                rt.maxMemory() / (1024 * 1024), peakMB, perThreadMB);
    }
}
