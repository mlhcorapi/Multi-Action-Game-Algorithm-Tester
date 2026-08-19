package core.data.exp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Machine profile + worker-thread policy for the v6 pipeline.
 *
 * <p>Every v6 job that opens a thread pool — portfolio build, thread calibration, isolation phase —
 * asks this class how many workers it is allowed to start. Nothing else decides the number, so the
 * policy lives in exactly one place and the run log records the reasoning once.
 *
 * <p><b>Why {@code availableProcessors()} is the wrong answer on its own.</b> The reference server is
 * a Dell PowerEdge R7615: a single-socket AMD EPYC 9004 (Genoa) machine that reports up to 192 logical
 * CPUs from 96 physical cores. Starting 192 workers on it is worse than starting 96, for three
 * separate reasons:
 * <ul>
 *   <li><b>SMT does not multiply memory bandwidth.</b> The two logical threads on a core share the
 *       load/store units, the L1/L2 and the core's slice of memory bandwidth. This workload is
 *       dominated by {@code GameState.copy()} and forward-model advances — pointer-chasing over
 *       freshly-allocated objects — so it is bandwidth- and allocation-bound, exactly the profile SMT
 *       helps least. Past the physical core count the curve typically flattens and can invert.</li>
 *   <li><b>All-core clocks are far below boost.</b> An EPYC 9004 part that boosts near 3.7 GHz on a few
 *       cores sustains substantially less with every core loaded, and the drop is progressive: each
 *       thread added past the sustained-power point makes every other thread slower. Wall-clock can go
 *       UP while the thread count goes up.</li>
 *   <li><b>Every in-flight turn holds a search tree.</b> At the largest budget these are hundreds of
 *       MB. Concurrency multiplies live heap directly, and a run that pushes the heap into
 *       back-to-back full GCs loses far more throughput than the extra threads bought.</li>
 * </ul>
 *
 * <p><b>The policy.</b> The worker ceiling is the smallest of:
 * <ol>
 *   <li>{@link #physicalCores()} minus a small reserve — never the SMT count. SMT siblings are left to
 *       the GC threads, the JIT compiler threads and the OS, which are real and concurrent;</li>
 *   <li>what the heap can hold, once a per-task footprint is known (see {@link #heapCap});</li>
 *   <li>an explicit operator override, if one is set.</li>
 * </ol>
 * The value is a <i>ceiling</i>, not a target: {@link ThreadCalibrationV6} still measures the machine
 * and usually picks something below it.
 *
 * <p><b>Overrides.</b> {@code -Dv6.maxThreads=N} (or {@code V6_MAX_THREADS=N} in the environment) caps
 * the pool for the whole pipeline; {@code -Dv6.reserveCores=N} changes the reserve. Both are logged.
 *
 * <p>Detection is Linux-first ({@code /proc/cpuinfo}, {@code /sys/devices/system/node}) with a
 * conservative fallback everywhere else: if the physical core count cannot be established, half of
 * {@code availableProcessors()} is assumed to be physical, which errs toward fewer threads, not more.
 */
public final class ServerProfile {

    /** Logical CPUs the JVM is allowed to use (already cgroup-aware in modern JVMs). */
    private final int logicalCpus;
    /** Physical cores backing them, detected or conservatively assumed. */
    private final int physicalCores;
    /** Logical CPUs per physical core; 2 on an SMT-enabled EPYC, 1 with SMT off. */
    private final int smtPerCore;
    /** NUMA nodes visible to the OS (NPS setting on EPYC); 1 when unknown. */
    private final int numaNodes;
    /** Model name from /proc/cpuinfo, or the os/arch pair when unavailable. */
    private final String cpuModel;
    /** True when the physical core count was actually read rather than assumed. */
    private final boolean coresDetected;
    /** Cores held back from the worker pool for GC, JIT and the OS. */
    private final int reserveCores;
    /** Operator override from -Dv6.maxThreads / V6_MAX_THREADS, or -1. */
    private final int override;

    private static ServerProfile cached;

    /** The shared profile for this JVM (detected once). */
    public static synchronized ServerProfile get() {
        if (cached == null) cached = detect();
        return cached;
    }

    private ServerProfile(int logicalCpus, int physicalCores, int smtPerCore, int numaNodes,
                          String cpuModel, boolean coresDetected, int reserveCores, int override) {
        this.logicalCpus = logicalCpus;
        this.physicalCores = physicalCores;
        this.smtPerCore = smtPerCore;
        this.numaNodes = numaNodes;
        this.cpuModel = cpuModel;
        this.coresDetected = coresDetected;
        this.reserveCores = reserveCores;
        this.override = override;
    }

    public int logicalCpus()   { return logicalCpus; }
    public int physicalCores() { return physicalCores; }
    public int smtPerCore()    { return smtPerCore; }
    public int numaNodes()     { return numaNodes; }
    public String cpuModel()   { return cpuModel; }
    public boolean coresDetected() { return coresDetected; }
    public int reserveCores()  { return reserveCores; }

    /**
     * The worker-thread ceiling for this machine, ignoring memory (which needs a measured per-task
     * footprint — see {@link #heapCap}). At least 1, never above the physical core count.
     */
    public int maxWorkers() {
        int cap = Math.max(1, physicalCores - reserveCores);
        if (override > 0) cap = Math.min(cap, override);
        return cap;
    }

    /**
     * How many workers the heap can hold, given a measured per-task footprint.
     *
     * @param perTaskMB  peak live heap attributable to one in-flight turn, in MB; {@code <= 0} means
     *                   "not measured yet", which returns {@link #maxWorkers()} unchanged
     * @param baselineMB heap held outside the workers (the loaded portfolio, mostly)
     * @param safety     fraction of max heap the workers may project into, leaving the rest as GC
     *                   headroom
     */
    public int heapCap(double perTaskMB, double baselineMB, double safety) {
        if (perTaskMB <= 0) return maxWorkers();
        double budgetMB = (Runtime.getRuntime().maxMemory() / (double) (1 << 20)) * safety - baselineMB;
        int cap = (int) Math.floor(budgetMB / perTaskMB);
        return Math.max(1, Math.min(maxWorkers(), cap));
    }

    /**
     * Ascending ladder of thread counts to measure, from clearly under-subscribed up to
     * {@link #maxWorkers()}. Evenly spaced in eighths so the frequency and bandwidth knees are both
     * visible, and it never proposes oversubscription.
     */
    public int[] threadLadder() {
        int max = maxWorkers();
        Set<Integer> s = new LinkedHashSet<>();
        for (double f : new double[]{0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0}) {
            s.add(Math.max(1, (int) Math.round(max * f)));
        }
        List<Integer> list = new ArrayList<>(s);
        java.util.Collections.sort(list);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /**
     * Mean current core clock in MHz across the logical CPUs, or {@code -1} when the OS does not
     * expose it. On Linux this reads {@code /proc/cpuinfo}'s "cpu MHz" lines, which report the
     * instantaneous P-state — so sampling it before and after a load segment shows directly how far
     * the part dropped off its boost clock under all-core load. That is the observable behind the
     * "more threads, slower threads" effect the calibration is trying to find the knee of.
     */
    public static double currentClockMHz() {
        try {
            File cpuinfo = new File("/proc/cpuinfo");
            if (!cpuinfo.canRead()) return -1;
            double sum = 0; int n = 0;
            for (String raw : Files.readAllLines(cpuinfo.toPath(), StandardCharsets.UTF_8)) {
                int colon = raw.indexOf(':');
                if (colon < 0) continue;
                if (!raw.substring(0, colon).trim().equals("cpu MHz")) continue;
                try { sum += Double.parseDouble(raw.substring(colon + 1).trim()); n++; }
                catch (NumberFormatException ignore) { }
            }
            return n > 0 ? sum / n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** One-line summary for run logs. */
    public String summary() {
        return String.format(Locale.ROOT,
                "%s | %d logical CPUs / %d physical cores%s (SMT x%d) | %d NUMA node(s) | "
                + "maxHeap %d MB | reserve %d core(s) | worker ceiling %d%s",
                cpuModel, logicalCpus, physicalCores, coresDetected ? "" : " (ASSUMED)", smtPerCore,
                numaNodes, Runtime.getRuntime().maxMemory() >> 20, reserveCores, maxWorkers(),
                override > 0 ? " (capped by override " + override + ")" : "");
    }

    /** Multi-line block printed at the top of every v6 job. */
    public void printBanner(String jobName) {
        System.out.println("------------------------------------------------------------");
        System.out.println("[machine] " + jobName);
        System.out.println("[machine] " + summary());
        if (!coresDetected) {
            System.out.println("[machine] NOTE: physical core count not readable on this OS; assumed "
                    + "logical/2. Set -Dv6.maxThreads=N to state it explicitly.");
        }
        if (smtPerCore > 1) {
            System.out.println("[machine] SMT is ON. Workers are capped at physical cores: this workload is "
                    + "clone/bandwidth-bound, so sibling threads add heat and memory, not throughput.");
        }
        System.out.println("------------------------------------------------------------");
    }

    // ---------------------------------------------------------------------------------------------
    // detection

    private static ServerProfile detect() {
        int logical = Math.max(1, Runtime.getRuntime().availableProcessors());

        int physical = -1;
        int numa = 1;
        String model = System.getProperty("os.name") + "/" + System.getProperty("os.arch");

        // /proc/cpuinfo: count DISTINCT (physical id, core id) pairs — the portable Linux way to get
        // the physical core count on an SMT machine.
        try {
            File cpuinfo = new File("/proc/cpuinfo");
            if (cpuinfo.canRead()) {
                Set<String> cores = new HashSet<>();
                String pkg = "0", core = null, name = null;
                for (String raw : Files.readAllLines(cpuinfo.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty()) {                       // end of one logical CPU's block
                        if (core != null) cores.add(pkg + ":" + core);
                        core = null;
                        continue;
                    }
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    if (k.equals("physical id")) pkg = v;
                    else if (k.equals("core id")) core = v;
                    else if (k.equals("model name") && name == null) name = v;
                }
                if (core != null) cores.add(pkg + ":" + core);
                if (!cores.isEmpty()) physical = cores.size();
                if (name != null) model = name;
            }
        } catch (Exception ignore) { /* fall through to the assumption below */ }

        // NUMA nodes (EPYC NPS setting). Informational: reported, not acted on.
        try {
            File nodeDir = new File("/sys/devices/system/node");
            File[] nodes = nodeDir.listFiles(f -> f.isDirectory() && f.getName().matches("node\\d+"));
            if (nodes != null && nodes.length > 0) numa = nodes.length;
        } catch (Exception ignore) { }

        boolean detected = physical > 0;
        if (!detected) {
            // Unknown topology: assume SMT is on and half the logical CPUs are physical. Erring low
            // costs some throughput; erring high costs heat, memory and possibly the run.
            physical = Math.max(1, logical / 2);
        }
        // A detected count can still exceed what the JVM may use (cgroup-limited container).
        physical = Math.min(physical, logical);
        int smt = Math.max(1, (int) Math.round(logical / (double) physical));

        // Hold back ~3% of cores (min 1, max 4) for GC, JIT and the OS. On a 96-core R7615 that is 3;
        // on a laptop it is 1.
        int reserve = intProp("v6.reserveCores", "V6_RESERVE_CORES",
                Math.max(1, Math.min(4, (int) Math.round(physical * 0.03))));
        int override = intProp("v6.maxThreads", "V6_MAX_THREADS", -1);

        return new ServerProfile(logical, physical, smt, numa, model, detected, reserve, override);
    }

    /**
     * Prints what this machine will be allowed to do, without running any experiment work. Use it
     * before committing to a multi-day run, and to check the effect of {@code -Dv6.maxThreads} /
     * {@code -Dv6.reserveCores}.
     *
     * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.ServerProfile}
     */
    public static void main(String[] args) {
        ServerProfile p = get();
        p.printBanner("machine policy report (no work will be run)");
        System.out.println("thread ladder the calibration will sweep: "
                + java.util.Arrays.toString(p.threadLadder()));
        double clock = currentClockMHz();
        System.out.println("current mean core clock: "
                + (clock > 0 ? String.format(Locale.ROOT, "%.0f MHz", clock)
                             : "not exposed by this OS"));
        System.out.println();
        System.out.println("Worker ceiling = physicalCores - reserve, then capped by any override.");
        System.out.println("  -Dv6.maxThreads=N    hard cap on workers for every v6 stage");
        System.out.println("  -Dv6.reserveCores=N  cores held back for GC / JIT / OS");
        System.out.println("The calibration picks a count at or below the ceiling from measured");
        System.out.println("sustained throughput; the load governor may lower it further at run time.");
    }

    /** System property, then environment variable, then default. */
    private static int intProp(String sysProp, String envVar, int dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.trim().isEmpty()) v = System.getenv(envVar);
        if (v == null || v.trim().isEmpty()) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
