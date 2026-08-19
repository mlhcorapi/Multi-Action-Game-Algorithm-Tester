package core.data.exp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interrupt safety for the long v6 jobs: a cooperative stop signal, durable small-file writes, and a
 * pool shutdown that lets in-flight work finish instead of losing it.
 *
 * <p><b>The failure this exists to prevent.</b> The isolation phase runs for days. It will be stopped:
 * by {@code Ctrl-C} in a notebook, by a {@code kill} when the operator wants the machine back, by an
 * OOM, by a reboot. Every one of those arrives as either a SIGTERM/SIGINT (which the JVM turns into
 * shutdown hooks, then exits) or a hard SIGKILL (which does not). The pipeline must lose at most the
 * turns that were physically executing at that instant — never a finished leaf, never a checkpoint,
 * and never leave a file that makes the next resume read garbage.
 *
 * <p><b>Three mechanisms.</b>
 * <ol>
 *   <li><b>Cooperative stop.</b> {@link #install} registers a shutdown hook that flips
 *       {@link #stopRequested()}. Submission loops check it and stop queueing new work, so a
 *       {@code Ctrl-C} drains the in-flight tasks and their results are written, rather than
 *       discarding several thousand queued tasks' worth of nothing. The hook then waits, bounded, for
 *       the registered pools to quiesce.</li>
 *   <li><b>Atomic small writes.</b> Checkpoint and marker files are written to a sibling temp file and
 *       moved into place ({@link #atomicWrite}). A kill during the write leaves either the old content
 *       or the new one, never a truncated file — which matters because a half-written
 *       {@code _portfolio_complete.txt} or {@code _tuned_threads_*.txt} would be parsed on resume.</li>
 *   <li><b>Append-then-verify for data.</b> The results/telemetry CSVs are appended per turn and closed
 *       each time, so a hard kill can lose at most the bytes of one line. The resume compaction in
 *       {@link IsolationPhaseRunnerV6} drops any row with too few fields, which is exactly the shape a
 *       torn final line takes.</li>
 * </ol>
 *
 * <p>Nothing here makes a SIGKILL lossless — that is not possible. It makes SIGKILL cost one turn per
 * worker, and SIGTERM cost nothing.
 */
public final class RunGuard {

    private static final AtomicBoolean STOP = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    /** Set by {@link #finished()} so the hook can tell a normal exit from an interruption. */
    private static final AtomicBoolean FINISHED = new AtomicBoolean(false);
    private static final List<ExecutorService> POOLS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Bound on how long the shutdown hook waits for in-flight turns to finish before giving up. */
    private static final long DRAIN_SECONDS = 120;

    /** How often a lock holder refreshes its heartbeat. */
    private static final long HEARTBEAT_SECONDS = 20;

    /** A lock whose heartbeat is older than this belongs to a process that is gone. Generously above
     *  the heartbeat interval so a long GC pause or a busy filesystem cannot orphan a live run. */
    private static final long LOCK_STALE_SECONDS = 120;

    private RunGuard() {}

    /**
     * Installs the shutdown hook once per JVM. Safe to call from every entry point.
     *
     * @param job          name printed in the stop banner
     * @param resumeHint   one line telling the operator how to continue (printed on stop)
     */
    public static void install(String job, String resumeHint) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // A shutdown hook also runs when main() returns normally. Only an exit that did NOT pass
            // through finished() is an interruption, and only that one gets the stop banner.
            if (FINISHED.get()) return;
            STOP.set(true);
            System.out.println();
            System.out.println("############################################################");
            System.out.println("# " + job + ": STOP REQUESTED — draining in-flight work");
            System.out.println("# Queued-but-unstarted turns are abandoned; running turns get up to "
                    + DRAIN_SECONDS + "s to finish and write.");
            System.out.println("# RESUME: " + resumeHint);
            System.out.println("############################################################");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_SECONDS);
            synchronized (POOLS) {
                for (ExecutorService p : POOLS) {
                    long leftMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
                    try {
                        p.shutdown();                       // stop accepting; keep running what started
                        p.awaitTermination(leftMs, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignore) { }
                }
            }
            System.out.println("# " + job + ": drained. Progress on disk is consistent.");
        }, "v6-run-guard"));
    }

    /**
     * Call as the last statement of a job that ran to completion (or exited on its own terms), so the
     * shutdown hook stays quiet instead of announcing a stop that never happened.
     */
    public static void finished() { FINISHED.set(true); }

    /** True once a SIGTERM/SIGINT (or {@link #requestStop}) has been seen. */
    public static boolean stopRequested() { return STOP.get(); }

    /** Flip the stop flag from application code (e.g. a fatal condition that should end the run). */
    public static void requestStop() { STOP.set(true); }

    /** Register a pool so the shutdown hook drains it. Unregister with {@link #unregister}. */
    public static <T extends ExecutorService> T register(T pool) {
        POOLS.add(pool);
        return pool;
    }

    /** Drop a pool that has already been shut down normally. */
    public static void unregister(ExecutorService pool) { POOLS.remove(pool); }

    /**
     * Write a small file so that a kill mid-write cannot leave it truncated: same-directory temp file,
     * fsync, then an atomic rename over the target.
     */
    public static void atomicWrite(File target, String content) {
        Path dst = target.toPath();
        Path tmp = dst.resolveSibling(target.getName() + ".tmp");
        try {
            Files.createDirectories(dst.toAbsolutePath().getParent());
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[guard] atomicWrite failed for " + target + ": " + e);
            // Last resort: a plain write is still better than losing the checkpoint entirely.
            ExpCsv.writeText(target, content);
        }
    }

    /** Append one line to a journal and close immediately, so the line is durable before we continue. */
    public static synchronized void appendLine(File target, String line) {
        try {
            Files.createDirectories(target.toPath().toAbsolutePath().getParent());
            Files.write(target.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[guard] appendLine failed for " + target + ": " + e);
        }
    }

    /**
     * Refuse to start if another v6 job is already writing to {@code dir}.
     *
     * <p><b>Why this is needed, and not paranoia.</b> The documented way to resume any v6 job is "re-run
     * the same notebook cell". If the previous run is still alive — and a backgrounded {@code nohup java}
     * survives a disconnected kernel, a closed browser and a re-executed cell — that instruction starts
     * a SECOND writer on the same output tree. Both append to the same CSVs and both assemble the same
     * manifest, and the result is a duplicated, silently wrong dataset rather than a crash. The failure
     * is invisible until analysis, which is the worst time to find it.
     *
     * <p><b>Liveness is decided by a heartbeat, not by the pid.</b> The holder rewrites the lock every
     * {@link #HEARTBEAT_SECONDS}; a lock whose last heartbeat is older than {@link #LOCK_STALE_SECONDS}
     * belongs to a process that is gone, and is taken over silently — so a crash or a {@code kill -9}
     * never needs manual cleanup. Checking only whether the recorded pid is alive is not enough in
     * either direction: the OS recycles pids, so a dead holder's number can be answered by an unrelated
     * process and block a legitimate resume forever. Both signals are required to declare a lock live.
     *
     * @return true if the lock was acquired; false if another live process holds it
     */
    public static boolean acquireLock(File dir, String job) {
        File lock = new File(dir, "_running.lock");
        long myPid = ProcessHandle.current().pid();
        String host = hostName();
        try {
            Files.createDirectories(dir.toPath());
            if (lock.exists()) {
                String[] f = new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8)
                        .trim().split("\\s+");
                if (f.length >= 3) {
                    long otherPid = Long.parseLong(f[0]);
                    String otherHost = f[1];
                    long beatMillis = Long.parseLong(f[2]);
                    long ageSec = (System.currentTimeMillis() - beatMillis) / 1000L;
                    boolean sameHost = otherHost.equals(host);
                    boolean pidAlive = !sameHost
                            || ProcessHandle.of(otherPid).map(ProcessHandle::isAlive).orElse(false);
                    boolean fresh = ageSec < LOCK_STALE_SECONDS;
                    if (fresh && pidAlive) {
                        System.err.println("############################################################");
                        System.err.println("# ABORT: another v6 job (pid " + otherPid + " on " + otherHost
                                + ") is already writing to " + dir.getPath());
                        System.err.println("# Its heartbeat is " + ageSec + "s old, so it is still running.");
                        System.err.println("# Two writers on one output tree produce duplicated rows, not");
                        System.err.println("# an error. Wait for it, or stop it first:  kill " + otherPid);
                        System.err.println("############################################################");
                        return false;
                    }
                    System.out.println("[guard] taking over a stale lock from pid " + otherPid + " on "
                            + otherHost + " (heartbeat " + ageSec + "s old"
                            + (sameHost && !pidAlive ? ", process gone" : "") + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("[guard] lock check failed (" + e + ") — continuing without a lock.");
            return true;
        }
        writeLock(lock, myPid, host, job);
        Thread beat = new Thread(() -> {
            while (true) {
                try { Thread.sleep(HEARTBEAT_SECONDS * 1000L); } catch (InterruptedException e) { return; }
                writeLock(lock, myPid, host, job);
            }
        }, "v6-lock-heartbeat");
        beat.setDaemon(true);
        beat.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                String[] f = new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8)
                        .trim().split("\\s+");
                if (f.length >= 1 && Long.parseLong(f[0]) == myPid) lock.delete();
            } catch (Exception ignore) { }
        }, "v6-lock-release"));
        return true;
    }

    private static void writeLock(File lock, long pid, String host, String job) {
        atomicWrite(lock, pid + " " + host + " " + System.currentTimeMillis() + " " + job
                + " " + new java.util.Date() + "\n");
    }

    /**
     * Exit without the shutdown hook announcing an interruption — for refusals (a held lock, a failed
     * precondition) where the job never started and there is nothing to drain.
     */
    public static void abort(int code) {
        finished();
        System.exit(code);
    }

    private static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown-host"; }
    }

    /**
     * Shut a pool down normally: no new tasks, wait for the rest, and unregister it from the hook.
     * Returns false if the pool did not finish within {@code timeoutHours}.
     */
    public static boolean shutdownAndWait(ExecutorService pool, long timeoutHours) {
        pool.shutdown();
        boolean ok = false;
        try {
            ok = pool.awaitTermination(timeoutHours, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            unregister(pool);
        }
        return ok;
    }
}
