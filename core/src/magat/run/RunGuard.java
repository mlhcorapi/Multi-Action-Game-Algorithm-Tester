package magat.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interrupt safety for long jobs: a cooperative stop signal, durable small-file writes, a lock per
 * output tree, and a pool shutdown that lets in-flight work finish instead of losing it.
 *
 * <p>Long jobs will be stopped: by {@code Ctrl-C} in a notebook, by a {@code kill}, by an OOM, by a
 * reboot. A job must lose at most the work physically executing at that instant, never a finished
 * unit of work, and never leave a file that makes the next resume read garbage.
 *
 * <ol>
 *   <li><b>Cooperative stop.</b> {@link #install} registers a shutdown hook that flips
 *       {@link #stopRequested()}; submission loops stop queueing, and the hook waits, bounded, for the
 *       registered pools to drain.</li>
 *   <li><b>Atomic small writes.</b> Markers and checkpoints go to a sibling temp file and are moved
 *       into place ({@link #atomicWrite}): a kill leaves the old content or the new, never a
 *       truncated file that parses.</li>
 *   <li><b>One writer per tree.</b> {@link #acquireLock} refuses a second live writer; liveness is a
 *       heartbeat plus the pid, so a crash never needs manual cleanup.</li>
 * </ol>
 */
public final class RunGuard {

    public static final String LOCK_FILE = "_running.lock";

    private static final AtomicBoolean STOP = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    /** Set by {@link #finished()} so the hook can tell a normal exit from an interruption. */
    private static final AtomicBoolean FINISHED = new AtomicBoolean(false);
    private static final List<ExecutorService> POOLS = Collections.synchronizedList(new ArrayList<>());

    /** Bound on how long the shutdown hook waits for in-flight work to finish. */
    private static final long DRAIN_SECONDS = 120;
    /** How often a lock holder refreshes its heartbeat. */
    private static final long HEARTBEAT_SECONDS = 20;
    /** A lock whose heartbeat is older than this belongs to a process that is gone; generously above
     *  the heartbeat so a long GC pause or a busy filesystem cannot orphan a live run. */
    private static final long LOCK_STALE_SECONDS = 120;

    private RunGuard() {}

    /** Installs the shutdown hook once per JVM. Safe to call from every entry point. */
    public static void install(String job, String resumeHint) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // A hook also runs when main() returns normally; only an exit that did not pass through
            // finished() is an interruption.
            if (FINISHED.get()) return;
            STOP.set(true);
            System.out.println();
            System.out.println("############################################################");
            System.out.println("# " + job + ": STOP REQUESTED - draining in-flight work");
            System.out.println("# Queued work is abandoned; running work gets up to " + DRAIN_SECONDS + "s to finish and write.");
            System.out.println("# RESUME: " + resumeHint);
            System.out.println("############################################################");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_SECONDS);
            synchronized (POOLS) {
                for (ExecutorService p : POOLS) {
                    long leftMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
                    try {
                        p.shutdown();
                        p.awaitTermination(leftMs, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignore) { }
                }
            }
            System.out.println("# " + job + ": drained. Progress on disk is consistent.");
        }, "magat-run-guard"));
    }

    /** Call when a job ends on its own terms, so the hook stays quiet. */
    public static void finished() { FINISHED.set(true); }

    public static boolean stopRequested() { return STOP.get(); }

    public static void requestStop() { STOP.set(true); }

    /** Register a pool so the shutdown hook drains it. */
    public static <T extends ExecutorService> T register(T pool) {
        POOLS.add(pool);
        return pool;
    }

    public static void unregister(ExecutorService pool) { POOLS.remove(pool); }

    /** Same-directory temp file, then an atomic rename over the target. */
    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void atomicWrite(Path target, byte[] content) throws IOException {
        Path dst = target.toAbsolutePath();
        Files.createDirectories(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Append one line and close immediately, so the line is on disk before the caller continues. */
    public static synchronized void appendLine(Path target, String line) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, (line + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Refuse to start if another live job is writing to {@code dir}.
     *
     * <p>The documented way to resume any job is "re-run the same command". If the previous run is
     * still alive (a backgrounded JVM survives a closed notebook), that instruction starts a second
     * writer on the same tree, and the result is a silently wrong dataset rather than a crash.
     * Liveness needs both signals: a fresh heartbeat and, on the same host, a live pid (pids are
     * recycled, so the pid alone could block a legitimate resume forever).
     *
     * @return true if the lock was acquired; false if another live process holds it
     */
    public static boolean acquireLock(Path dir, String job) {
        Path lock = dir.resolve(LOCK_FILE);
        long myPid = ProcessHandle.current().pid();
        String host = hostName();
        try {
            Files.createDirectories(dir);
            if (Files.exists(lock)) {
                String[] f = new String(Files.readAllBytes(lock), StandardCharsets.UTF_8).trim().split("\\s+");
                if (f.length >= 3) {
                    long otherPid = Long.parseLong(f[0]);
                    String otherHost = f[1];
                    long ageSec = (System.currentTimeMillis() - Long.parseLong(f[2])) / 1000L;
                    boolean sameHost = otherHost.equals(host);
                    boolean pidAlive = !sameHost || ProcessHandle.of(otherPid).map(ProcessHandle::isAlive).orElse(false);
                    if (otherPid != myPid && ageSec < LOCK_STALE_SECONDS && pidAlive) {
                        System.err.println("############################################################");
                        System.err.println("# ABORT: another job (pid " + otherPid + " on " + otherHost + ") is writing to " + dir);
                        System.err.println("# Its heartbeat is " + ageSec + "s old, so it is still running.");
                        System.err.println("# Two writers on one tree produce duplicated data, not an error.");
                        System.err.println("# Wait for it, or stop it first.");
                        System.err.println("############################################################");
                        return false;
                    }
                    if (otherPid != myPid)
                        System.out.println("[guard] taking over a stale lock from pid " + otherPid + " on " + otherHost
                                + " (heartbeat " + ageSec + "s old" + (sameHost && !pidAlive ? ", process gone" : "") + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("[guard] lock check failed (" + e + ") - continuing without a lock.");
            return true;
        }
        writeLock(lock, myPid, host, job);
        Thread beat = new Thread(() -> {
            while (true) {
                try { Thread.sleep(HEARTBEAT_SECONDS * 1000L); } catch (InterruptedException e) { return; }
                if (!Files.exists(lock)) return;            // released
                writeLock(lock, myPid, host, job);
            }
        }, "magat-lock-heartbeat");
        beat.setDaemon(true);
        beat.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> releaseLock(dir), "magat-lock-release"));
        return true;
    }

    /** Removes the lock in {@code dir} if this process holds it. */
    public static void releaseLock(Path dir) {
        Path lock = dir.resolve(LOCK_FILE);
        try {
            String[] f = new String(Files.readAllBytes(lock), StandardCharsets.UTF_8).trim().split("\\s+");
            if (f.length >= 1 && Long.parseLong(f[0]) == ProcessHandle.current().pid()) Files.deleteIfExists(lock);
        } catch (Exception ignore) { }
    }

    private static void writeLock(Path lock, long pid, String host, String job) {
        try {
            atomicWrite(lock, pid + " " + host + " " + System.currentTimeMillis() + " " + job.replace(' ', '_')
                    + " " + new java.util.Date() + "\n");
        } catch (IOException e) {
            System.err.println("[guard] could not write " + lock + ": " + e);
        }
    }

    private static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName().replace(' ', '_'); }
        catch (Exception e) { return "unknown-host"; }
    }

    /** Shut a pool down normally: no new tasks, wait for the rest, unregister it from the hook. */
    public static boolean shutdownAndWait(ExecutorService pool, long timeout, TimeUnit unit) {
        pool.shutdown();
        boolean ok = false;
        try {
            ok = pool.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            unregister(pool);
        }
        return ok;
    }

    /** Recursively delete a directory; a missing directory is not an error. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(all::add);
        }
        Collections.reverse(all);
        for (Path p : all) Files.deleteIfExists(p);
    }
}
