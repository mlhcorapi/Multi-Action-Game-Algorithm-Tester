package core.data.exp;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime throttle for the isolation phase: caps how many turns may be <i>in flight</i> at once and
 * lowers that cap by itself when the JVM shows memory pressure.
 *
 * <p><b>Why a calibrated thread count is not enough.</b> {@link ThreadCalibrationV6} picks a thread
 * count from a short sweep on a sample of the portfolio. The real run is neither short nor a sample:
 * it crosses the whole portfolio, whose branching factors differ several-fold, and search-tree size tracks
 * branching factor. A thread count that is comfortable on the average scenario can be over-committed
 * on a run of wide ones — and the failure mode is not a graceful slowdown, it is the whole run
 * degrading into back-to-back full GCs, or dying with an {@code OutOfMemoryError} after two days.
 * A run that must survive unattended needs a mechanism that reacts to the machine it is actually on,
 * not only to a measurement taken before it started.
 *
 * <p><b>What it measures.</b> Two signals, both from the JVM rather than from the OS, so they work the
 * same in a container:
 * <ul>
 *   <li><b>Post-collection old-gen occupancy.</b> {@link MemoryPoolMXBean#getCollectionUsage()} is what
 *       is still live after the last collection of that pool — the honest "how full are we" number.
 *       Instantaneous used-heap is not: it sawtooths with allocation and says nothing.</li>
 *   <li><b>GC time share.</b> The fraction of recent wall-clock the collectors consumed. Above roughly
 *       a fifth, added threads are buying GC work rather than search.</li>
 * </ul>
 *
 * <p><b>What it does.</b> Workers take a permit before running a turn and return it after. On
 * pressure the governor withdraws permits — in-flight turns finish untouched, but fewer new ones
 * start, so live heap falls. On recovery it hands the permits back, up to the calibrated ceiling and
 * never above it. Permits are withdrawn quickly and restored slowly (one per interval), because
 * oscillating between over- and under-commitment is worse than sitting slightly under.
 *
 * <p><b>Results are unaffected.</b> Throttling changes only how many turns run concurrently. The v6
 * budget is a deterministic compute-unit count, so a turn does identical work at any concurrency; and
 * every turn's output depends only on its {@code (scenario, visit)} seed. Wall-clock is the only thing
 * this can change, which is precisely why it is safe to let it act automatically.
 */
public final class LoadGovernor {

    /** Post-GC old-gen occupancy above which permits are withdrawn. */
    public static final double HIGH_WATER = 0.78;
    /** Post-GC old-gen occupancy below which permits are handed back. */
    public static final double LOW_WATER  = 0.60;
    /** Recent-window GC time share above which permits are withdrawn regardless of occupancy. */
    public static final double GC_SHARE_HIGH = 0.20;
    /** Recent-window GC time share below which permits are handed back. */
    public static final double GC_SHARE_LOW  = 0.08;
    /** Never throttle below this fraction of the calibrated ceiling — past that, stop and report. */
    public static final double MIN_FRACTION = 0.25;

    private final int ceiling;
    private final long intervalMs;
    private final Semaphore permits;
    /** Permits currently withheld from the pool (0 == running at the ceiling). */
    private final AtomicInteger withheld = new AtomicInteger(0);
    private final AtomicInteger lowest;
    private final AtomicLong throttleEvents = new AtomicLong(0);
    private final List<MemoryPoolMXBean> oldGens = new ArrayList<>();
    private final List<GarbageCollectorMXBean> collectors;
    private volatile boolean running = true;
    private Thread monitor;

    private long lastGcMillis = 0;
    private long lastSampleNanos = 0;
    private double lastOccupancy = 0;
    private double lastGcShare = 0;

    /**
     * @param ceiling    the calibrated maximum number of concurrent turns
     * @param intervalMs how often to sample; 2000–5000 ms is plenty, the signals are slow
     */
    public LoadGovernor(int ceiling, long intervalMs) {
        this.ceiling = Math.max(1, ceiling);
        this.intervalMs = Math.max(500, intervalMs);
        this.permits = new Semaphore(this.ceiling, true);
        this.lowest = new AtomicInteger(this.ceiling);
        this.collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            // Old / tenured / "G1 Old Gen" — the pool whose post-collection usage is the live set.
            if (p.getType() == MemoryType.HEAP && p.getCollectionUsage() != null
                    && p.getName().toLowerCase(Locale.ROOT).contains("old")) {
                oldGens.add(p);
            }
        }
    }

    /** Start the background sampler. Idempotent. */
    public synchronized void start() {
        if (monitor != null) return;
        lastGcMillis = totalGcMillis();
        lastSampleNanos = System.nanoTime();
        monitor = new Thread(this::loop, "v6-load-governor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /** Stop the sampler. In-flight permits are untouched. */
    public synchronized void stop() {
        running = false;
        if (monitor != null) monitor.interrupt();
        monitor = null;
    }

    /** Block until a turn may start. */
    public void acquire() throws InterruptedException { permits.acquire(); }

    /** Release the permit taken by {@link #acquire()}. Must run in a {@code finally}. */
    public void release() { permits.release(); }

    /** Concurrency currently allowed (ceiling minus whatever the governor has withheld). */
    public int allowed() { return ceiling - withheld.get(); }

    /** Smallest concurrency the governor ever fell to during this phase. */
    public int lowestAllowed() { return lowest.get(); }

    /** How many times the governor withdrew permits during this phase. */
    public long throttleEvents() { return throttleEvents.get(); }

    /** One-line state, for the phase summary in the run log. */
    public String status() {
        return String.format(Locale.ROOT,
                "concurrency %d/%d (low-water %d, %d throttle event(s)); oldgen %.0f%% after GC, "
                + "GC share %.1f%%",
                allowed(), ceiling, lowestAllowed(), throttleEvents(), lastOccupancy * 100, lastGcShare * 100);
    }

    // ---------------------------------------------------------------------------------------------

    private void loop() {
        while (running) {
            try { Thread.sleep(intervalMs); } catch (InterruptedException e) { return; }
            if (!running) return;
            try { sampleAndAdjust(); } catch (Throwable t) {
                System.err.println("[governor] sample failed: " + t);
            }
        }
    }

    private void sampleAndAdjust() {
        double occ = occupancy();
        double gcShare = gcShare();
        lastOccupancy = occ;
        lastGcShare = gcShare;

        boolean pressured = occ >= HIGH_WATER || gcShare >= GC_SHARE_HIGH;
        boolean relaxed   = occ <= LOW_WATER && gcShare <= GC_SHARE_LOW;
        int floor = Math.max(1, (int) Math.ceil(ceiling * MIN_FRACTION));

        if (pressured && allowed() > floor) {
            // Withdraw a quarter of the current concurrency at once. Memory pressure compounds, so a
            // slow taper can be overtaken by the very allocation it is trying to stop.
            int step = Math.max(1, (allowed() - floor) / 4);
            int got = 0;
            for (int i = 0; i < step; i++) {
                if (!permits.tryAcquire()) break;   // in use; it will come back to us on release
                got++;
            }
            if (got > 0) {
                withheld.addAndGet(got);
                lowest.getAndUpdate(v -> Math.min(v, allowed()));
                throttleEvents.incrementAndGet();
                System.out.println(String.format(Locale.ROOT,
                        "[governor] THROTTLE -%d -> concurrency %d/%d  (oldgen %.0f%% after GC, GC share %.1f%%)",
                        got, allowed(), ceiling, occ * 100, gcShare * 100));
            }
        } else if (relaxed && withheld.get() > 0) {
            // Give permits back one at a time: the pressure signal lags the allocation that caused it,
            // so restoring in bulk just re-triggers the throttle a few seconds later.
            withheld.decrementAndGet();
            permits.release();
            System.out.println(String.format(Locale.ROOT,
                    "[governor] release +1 -> concurrency %d/%d  (oldgen %.0f%% after GC, GC share %.1f%%)",
                    allowed(), ceiling, occ * 100, gcShare * 100));
        }
    }

    /** Live-set occupancy of the old generation after its last collection, as a fraction of its max. */
    private double occupancy() {
        double worst = 0;
        for (MemoryPoolMXBean p : oldGens) {
            MemoryUsage u = p.getCollectionUsage();
            if (u == null) continue;
            long max = u.getMax() > 0 ? u.getMax() : Runtime.getRuntime().maxMemory();
            if (max <= 0) continue;
            worst = Math.max(worst, u.getUsed() / (double) max);
        }
        if (worst > 0) return worst;
        // No old-gen pool exposes collection usage (some collectors): fall back to instantaneous heap.
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory();
    }

    /** Share of the interval just elapsed that the collectors spent running, summed over collectors. */
    private double gcShare() {
        long now = System.nanoTime();
        long gc = totalGcMillis();
        long wallMs = Math.max(1, (now - lastSampleNanos) / 1_000_000L);
        long gcDelta = Math.max(0, gc - lastGcMillis);
        lastSampleNanos = now;
        lastGcMillis = gc;
        // GC is parallel, so gcDelta can exceed wall time; normalise by the GC thread count the JVM
        // would use, approximated by the worker ceiling, and clamp.
        return Math.min(1.0, gcDelta / (double) (wallMs * Math.max(1, ceiling / 4)));
    }

    private long totalGcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean b : collectors) {
            long c = b.getCollectionTime();
            if (c > 0) t += c;
        }
        return t;
    }
}
