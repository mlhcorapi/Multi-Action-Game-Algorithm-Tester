package magat.run;

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
 * Caps how many turns are in flight and lowers the cap by itself under memory pressure.
 *
 * <p>A thread count picked by a short sweep on a sample is not enough: a real run crosses the whole
 * portfolio, whose branching factors differ several-fold, and a count comfortable on the average
 * position can be over-committed on a run of wide ones. The failure is not a graceful slowdown but
 * back-to-back full collections, or an out-of-memory error two days in.
 *
 * <p>Two JVM signals, so it behaves the same in a container: old-generation occupancy after its last
 * collection (the honest live set), and the share of recent wall-clock spent collecting. Workers take a
 * permit per turn; under pressure permits are withdrawn quickly (a quarter at a time), and handed back
 * slowly (one per interval), never above the ceiling and never below a quarter of it.
 *
 * <p>Results are unaffected: budgets are deterministic counts and every turn depends only on its
 * (scenario, visit) seed, so concurrency changes wall-clock and nothing else.
 *
 * <p>Extracted from the thesis tree's {@code LoadGovernor}, unchanged.
 */
public final class LoadGovernor {

    public static final double HIGH_WATER = 0.78;
    public static final double LOW_WATER = 0.60;
    public static final double GC_SHARE_HIGH = 0.20;
    public static final double GC_SHARE_LOW = 0.08;
    public static final double MIN_FRACTION = 0.25;

    private final int ceiling;
    private final long intervalMs;
    private final Semaphore permits;
    private final AtomicInteger withheld = new AtomicInteger(0);
    private final AtomicInteger lowest;
    private final AtomicLong throttleEvents = new AtomicLong(0);
    private final List<MemoryPoolMXBean> oldGens = new ArrayList<>();
    private final List<GarbageCollectorMXBean> collectors;
    private volatile boolean running = true;
    private Thread monitor;
    private long lastGcMillis, lastSampleNanos;
    private volatile double lastOccupancy, lastGcShare;

    public LoadGovernor(int ceiling, long intervalMs) {
        this.ceiling = Math.max(1, ceiling);
        this.intervalMs = Math.max(500, intervalMs);
        this.permits = new Semaphore(this.ceiling, true);
        this.lowest = new AtomicInteger(this.ceiling);
        this.collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getCollectionUsage() != null && p.getName().toLowerCase(Locale.ROOT).contains("old"))
                oldGens.add(p);
    }

    public synchronized void start() {
        if (monitor != null) return;
        lastGcMillis = totalGcMillis();
        lastSampleNanos = System.nanoTime();
        monitor = new Thread(this::loop, "magat-load-governor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public synchronized void stop() {
        running = false;
        if (monitor != null) monitor.interrupt();
        monitor = null;
    }

    public void acquire() throws InterruptedException { permits.acquire(); }

    public void release() { permits.release(); }

    public int allowed() { return ceiling - withheld.get(); }

    public int lowestAllowed() { return lowest.get(); }

    public long throttleEvents() { return throttleEvents.get(); }

    public String status() {
        return String.format(Locale.ROOT, "concurrency %d/%d (low-water %d, %d throttle event(s)); oldgen %.0f%% after GC, GC share %.1f%%",
                allowed(), ceiling, lowestAllowed(), throttleEvents(), lastOccupancy * 100, lastGcShare * 100);
    }

    private void loop() {
        while (running) {
            try { Thread.sleep(intervalMs); } catch (InterruptedException e) { return; }
            if (!running) return;
            try { sampleAndAdjust(); } catch (Throwable t) { System.err.println("[governor] sample failed: " + t); }
        }
    }

    private void sampleAndAdjust() {
        double occ = occupancy(), gcShare = gcShare();
        lastOccupancy = occ;
        lastGcShare = gcShare;
        boolean pressured = occ >= HIGH_WATER || gcShare >= GC_SHARE_HIGH;
        boolean relaxed = occ <= LOW_WATER && gcShare <= GC_SHARE_LOW;
        int floor = Math.max(1, (int) Math.ceil(ceiling * MIN_FRACTION));
        if (pressured && allowed() > floor) {
            int step = Math.max(1, (allowed() - floor) / 4), got = 0;
            for (int i = 0; i < step; i++) {
                if (!permits.tryAcquire()) break;
                got++;
            }
            if (got > 0) {
                withheld.addAndGet(got);
                lowest.getAndUpdate(v -> Math.min(v, allowed()));
                throttleEvents.incrementAndGet();
                System.out.println(String.format(Locale.ROOT, "[governor] THROTTLE -%d -> concurrency %d/%d  (oldgen %.0f%% after GC, GC share %.1f%%)",
                        got, allowed(), ceiling, occ * 100, gcShare * 100));
            }
        } else if (relaxed && withheld.get() > 0) {
            withheld.decrementAndGet();
            permits.release();
            System.out.println(String.format(Locale.ROOT, "[governor] release +1 -> concurrency %d/%d  (oldgen %.0f%% after GC, GC share %.1f%%)",
                    allowed(), ceiling, occ * 100, gcShare * 100));
        }
    }

    private double occupancy() {
        double worst = 0;
        for (MemoryPoolMXBean p : oldGens) {
            MemoryUsage u = p.getCollectionUsage();
            if (u == null) continue;
            long max = u.getMax() > 0 ? u.getMax() : Runtime.getRuntime().maxMemory();
            if (max > 0) worst = Math.max(worst, u.getUsed() / (double) max);
        }
        if (worst > 0) return worst;
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory();
    }

    private double gcShare() {
        long now = System.nanoTime(), gc = totalGcMillis();
        long wallMs = Math.max(1, (now - lastSampleNanos) / 1_000_000L), delta = Math.max(0, gc - lastGcMillis);
        lastSampleNanos = now;
        lastGcMillis = gc;
        return Math.min(1.0, delta / (double) (wallMs * Math.max(1, ceiling / 4)));
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
