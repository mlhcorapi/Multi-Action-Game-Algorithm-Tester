package magat.machine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The machine, and how many workers a parallel stage may start on it.
 *
 * <p>Every stage that opens a thread pool asks this class for its ceiling, so the policy lives in one
 * place and every log records it once.
 *
 * <p><b>Why {@code availableProcessors()} is the wrong answer on its own.</b> The workload is
 * dominated by state copies and forward-model advances over freshly allocated objects: bandwidth- and
 * allocation-bound, exactly the profile SMT helps least. All-core clocks sit well below boost, so a
 * thread added past the sustained-power point slows every other thread. And every in-flight turn holds
 * a search tree, so concurrency multiplies live heap. The ceiling is therefore the smallest of
 * <ol>
 *   <li>physical cores minus a small reserve (SMT siblings are left to GC, JIT and the OS);</li>
 *   <li>what the heap can hold once a per-task footprint is known ({@link #heapCap});</li>
 *   <li>an operator override: {@code -Dmagat.maxThreads=N} or {@code MAGAT_MAX_THREADS}.</li>
 * </ol>
 * It is a ceiling, not a target: the thread sweep measures the machine and usually picks less.
 *
 * <p>Physical cores are read from {@code /proc/cpuinfo} on Linux. Elsewhere half the logical CPUs are
 * assumed physical, which errs toward fewer threads; the banner says when the count was assumed.
 *
 */
public final class MachineProfile {

    private final int logicalCpus;
    private final int physicalCores;
    private final int smtPerCore;
    private final int numaNodes;
    private final String cpuModel;
    private final boolean coresDetected;
    private final int reserveCores;
    private final int override;

    private static MachineProfile cached;

    /** The profile of this JVM's machine, detected once. */
    public static synchronized MachineProfile get() {
        if (cached == null) cached = detect();
        return cached;
    }

    MachineProfile(int logicalCpus, int physicalCores, int smtPerCore, int numaNodes, String cpuModel,
                   boolean coresDetected, int reserveCores, int override) {
        this.logicalCpus = logicalCpus;
        this.physicalCores = physicalCores;
        this.smtPerCore = smtPerCore;
        this.numaNodes = numaNodes;
        this.cpuModel = cpuModel;
        this.coresDetected = coresDetected;
        this.reserveCores = reserveCores;
        this.override = override;
    }

    public int logicalCpus()       { return logicalCpus; }
    public int physicalCores()     { return physicalCores; }
    public int smtPerCore()        { return smtPerCore; }
    public int numaNodes()         { return numaNodes; }
    public String cpuModel()       { return cpuModel; }
    public boolean coresDetected() { return coresDetected; }
    public int reserveCores()      { return reserveCores; }

    /** The worker ceiling ignoring memory: at least 1, never above physical cores less the reserve. */
    public int maxWorkers() {
        int cap = Math.max(1, physicalCores - reserveCores);
        if (override > 0) cap = Math.min(cap, override);
        return cap;
    }

    /**
     * How many workers the heap can hold.
     *
     * @param perTaskMB  peak live heap of one in-flight turn; {@code <= 0} means not measured yet
     * @param baselineMB heap held outside the workers
     * @param safety     fraction of max heap the workers may project into
     */
    public int heapCap(double perTaskMB, double baselineMB, double safety) {
        if (perTaskMB <= 0) return maxWorkers();
        double budgetMB = (Runtime.getRuntime().maxMemory() / (double) (1 << 20)) * safety - baselineMB;
        return Math.max(1, Math.min(maxWorkers(), (int) Math.floor(budgetMB / perTaskMB)));
    }

    /** Ascending thread counts to sweep: eighths of the ceiling, so both knees are visible, never oversubscribed. */
    public int[] threadLadder() {
        return ladder(maxWorkers());
    }

    static int[] ladder(int max) {
        Set<Integer> s = new LinkedHashSet<>();
        for (double f : new double[]{0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0})
            s.add(Math.max(1, (int) Math.round(max * f)));
        List<Integer> list = new ArrayList<>(s);
        Collections.sort(list);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /** Mean current core clock in MHz from {@code /proc/cpuinfo}, or -1 where the OS does not expose it. */
    public static double currentClockMHz() {
        try {
            File cpuinfo = new File("/proc/cpuinfo");
            if (!cpuinfo.canRead()) return -1;
            double sum = 0;
            int n = 0;
            for (String raw : Files.readAllLines(cpuinfo.toPath(), StandardCharsets.UTF_8)) {
                int colon = raw.indexOf(':');
                if (colon < 0 || !raw.substring(0, colon).trim().equals("cpu MHz")) continue;
                try { sum += Double.parseDouble(raw.substring(colon + 1).trim()); n++; }
                catch (NumberFormatException ignore) { }
            }
            return n > 0 ? sum / n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 1-minute load average per logical CPU, or -1 where the OS does not expose it. */
    public static double loadPerCore() {
        try {
            double la = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            return la < 0 ? -1 : la / Math.max(1, Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            return -1;
        }
    }

    /** One line for logs and file headers. */
    public String summary() {
        return String.format(Locale.ROOT,
                "%s | %d logical / %d physical%s (SMT x%d) | %d NUMA node(s) | maxHeap %d MB | reserve %d | worker ceiling %d%s",
                cpuModel, logicalCpus, physicalCores, coresDetected ? "" : " (ASSUMED)", smtPerCore, numaNodes,
                Runtime.getRuntime().maxMemory() >> 20, reserveCores, maxWorkers(),
                override > 0 ? " (capped by override " + override + ")" : "");
    }

    /** The machine as the weights file records it: model and core counts. */
    public String describe() {
        return cpuModel + " | " + physicalCores + " physical" + (coresDetected ? "" : " (assumed)") + " / " + logicalCpus + " logical";
    }

    /**
     * The CPU model a {@link #describe()} or {@link #summary()} line names. The core counts after it are the
     * ones the JVM was allowed, so they differ between a pinned calibration and a run on the same machine.
     */
    public static String modelOf(String described) {
        int bar = described.indexOf(" | ");
        return (bar < 0 ? described : described.substring(0, bar)).trim();
    }

    public void printBanner(String job) {
        System.out.println("------------------------------------------------------------");
        System.out.println("[machine] " + job);
        System.out.println("[machine] " + summary());
        if (!coresDetected)
            System.out.println("[machine] physical cores are not readable on this OS; assumed logical/2. "
                    + "Set MAGAT_MAX_THREADS=N to state the ceiling explicitly.");
        if (smtPerCore > 1)
            System.out.println("[machine] SMT is on; workers are capped at physical cores (this workload is "
                    + "copy- and bandwidth-bound, so sibling threads add heat and memory, not throughput).");
        System.out.println("------------------------------------------------------------");
    }

    private static MachineProfile detect() {
        int logical = Math.max(1, Runtime.getRuntime().availableProcessors());
        int physical = -1, numa = 1;
        String model = System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        try {
            File cpuinfo = new File("/proc/cpuinfo");
            if (cpuinfo.canRead()) {
                Set<String> cores = new HashSet<>();
                String pkg = "0", core = null, name = null;
                for (String raw : Files.readAllLines(cpuinfo.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty()) {
                        if (core != null) cores.add(pkg + ":" + core);
                        core = null;
                        continue;
                    }
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String k = line.substring(0, colon).trim(), v = line.substring(colon + 1).trim();
                    if (k.equals("physical id")) pkg = v;
                    else if (k.equals("core id")) core = v;
                    else if (k.equals("model name") && name == null) name = v;
                }
                if (core != null) cores.add(pkg + ":" + core);
                if (!cores.isEmpty()) physical = cores.size();
                if (name != null) model = name;
            }
        } catch (Exception ignore) { }
        if (model.startsWith("Windows")) {
            String id = System.getenv("PROCESSOR_IDENTIFIER");
            if (id != null && !id.trim().isEmpty()) model = id.trim() + " (" + model + ")";
        }
        try {
            File[] nodes = new File("/sys/devices/system/node").listFiles(f -> f.isDirectory() && f.getName().matches("node\\d+"));
            if (nodes != null && nodes.length > 0) numa = nodes.length;
        } catch (Exception ignore) { }

        boolean detected = physical > 0;
        if (!detected) physical = Math.max(1, logical / 2);
        physical = Math.min(physical, logical);
        int smt = Math.max(1, (int) Math.round(logical / (double) physical));
        int reserve = intProp("magat.reserveCores", "MAGAT_RESERVE_CORES", Math.max(1, Math.min(4, (int) Math.round(physical * 0.03))));
        int override = intProp("magat.maxThreads", "MAGAT_MAX_THREADS", -1);
        return new MachineProfile(logical, physical, smt, numa, model, detected, reserve, override);
    }

    private static int intProp(String sysProp, String envVar, int dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.trim().isEmpty()) v = System.getenv(envVar);
        if (v == null || v.trim().isEmpty()) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
