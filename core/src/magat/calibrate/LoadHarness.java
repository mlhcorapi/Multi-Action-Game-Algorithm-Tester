package magat.calibrate;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.game.Game;
import magat.game.Scenario;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps {@code workers} threads planning with the SAME arm and budget as the cell being measured, so
 * the cell is timed in the regime a run actually occupies (every worker on one arm).
 *
 * <p>The verdict of a loaded calibration gates on {@link #WORK_TURNS}, the harness's own completed
 * work, never on the OS load average: inside a container the load average can read near zero while
 * the machine is saturated (the R7615's JupyterHub read 0.11 per core during a run whose per-op costs
 * were 40-80% above idle), and that false negative once discarded a measurement that had succeeded.
 *
 * <p>Known distortion: {@code Meter.PROFILE} is global, so load threads also take the timed path. The
 * overhead applies to every cell alike and cancels in the ratios being compared. Counts are
 * thread-local, so load threads never pollute the measurement.
 */
final class LoadHarness {

    static final AtomicLong WORK_TURNS = new AtomicLong();

    private final ExecutorService pool;
    private final AtomicBoolean stop = new AtomicBoolean();

    private LoadHarness(ExecutorService pool) {
        this.pool = pool;
    }

    static <S, A> LoadHarness start(Game<S, A> game, AgentFactory<S, A> arm, long budget, List<Scenario<S>> positions, int workers) {
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "calibrate-load");
            t.setDaemon(true);
            return t;
        });
        LoadHarness h = new LoadHarness(pool);
        CountDownLatch running = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            final int id = w;
            pool.submit(() -> {
                running.countDown();
                for (long i = 0; !h.stop.get(); i++) {
                    try {
                        // Offset by worker so the threads are not lock-stepped on one position.
                        Scenario<S> sc = positions.get((int) ((id * 7L + i * 13L) % positions.size()));
                        Episode.run(game, sc, (int) (8_000 + id * 1_000 + (i % 1_000)), arm, budget);
                        WORK_TURNS.incrementAndGet();
                    } catch (Throwable t) {
                        // a load thread failing must not take the measurement down with it
                    }
                }
            });
        }
        try {
            // Every worker inside a turn, then settle: the measured cell sees a machine already loaded.
            running.await(2, TimeUnit.MINUTES);
            Thread.sleep(5_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return h;
    }

    void stop() {
        stop.set(true);
        pool.shutdownNow();
        try { pool.awaitTermination(2, TimeUnit.MINUTES); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
