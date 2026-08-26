package core.data.exp;

import core.Constants;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v6 C0 — the thinking-time calibration experiment. Runs BEFORE the v6 isolation phase and produces
 * the numbers that configure it (see {@code analysis/EXPERIMENT_V6_PLAN.md} §4).
 *
 * <p><b>What it measures.</b> Each of the 12 agent configurations is given a fixed amount of SEARCH
 * time on the reference machine and the resulting operation counts are recorded: advances, state
 * copies, heuristic evals, weighted compute-units, and the greedy-initialization cost separately. From
 * that the analysis derives (a) the units→seconds conversion the paper reports, (b) whether that
 * conversion is agent-invariant, and (c) whether it is invariant across scenario source and branching
 * factor, which is the H5 confound check.
 *
 * <p><b>(b) is now a CHECK, not an open question.</b> {@link ComputeCalibrateV6} measures each
 * configuration's per-operation costs at each budget level and prices each one with its own weights,
 * precisely so that a unit means the same wall-clock everywhere. If that worked, {@code SearchUnits /
 * SearchMillis} is FLAT across the 12 configurations here; if it is not flat, the weights are stale or
 * were measured on a different machine, and the budget is not equal compute. C0 is where that shows
 * up, because C0 is the only v6 job that holds time fixed and lets the work vary.
 *
 * <p>Units are priced by the profile of ONE budget level, named in the {@code ProfileLevel} column and
 * settable as the seventh argument (default: the smallest level). A target far from that level's scale
 * will drift, because per-operation costs grow with tree size - which is the same effect the per-level
 * profiles exist to price, seen from the other side.
 *
 * <p><b>Why STOP_TIME here and only here.</b> The question C0 asks is literally "what does this agent
 * do in T seconds", so the calibration must be timed. The experiment it configures is not: it runs on a
 * deterministic operation budget precisely because wall-clock on a loaded multi-threaded server is not
 * reproducible. C0 is therefore run SINGLE-THREADED on a quiesced machine — that machine is the
 * reference machine, and its numbers are what the paper converts to seconds.
 *
 * <p><b>Separate runs per target.</b> The turn is split into {@code depth} phases whose ceilings derive
 * from the TOTAL budget, so a 5000 ms run is not a 1000 ms run with more time on the end — at t=1000 ms
 * a 5000 ms run is in phase 4 with 3 burns done, while a 1000 ms run has completed all 21 phases and
 * all 20 burns. Each target is therefore a separate run, never a checkpoint of a longer one.
 *
 * <p><b>Initialization is excluded from the timed budget</b> and measured separately: the agents run
 * greedy initialization, record its cost, reset the meter, and only then start the timed phase loop
 * (see {@code ExperimentalEMCTSAgent.planTurn}). {@code num_timeMillis} gives the search exactly T ms
 * regardless of how long initialization took. Bridge burning — including the crossover tournament — is
 * INSIDE the timed budget.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.CalibrationRunnerV6 [portfolioDir] [outDir]
 * [targetsMsCsv] [perSource] [reps] [warmup] [profileUnits]}
 */
public class CalibrationRunnerV6 {

    /** Search-time targets, each a separate run. */
    public static final int[] DEFAULT_TARGETS_MS = {500, 1000, 5000};

    /** Scenarios sampled per source (single/dual), stratified across branching-factor tertiles. */
    public static final int DEFAULT_PER_SOURCE = 12;

    /** Repetitions per (target, config, scenario) — the basis for the stability CV. */
    public static final int DEFAULT_REPS = 5;

    /** Turns run and discarded before measurement, so JIT compilation is not charged to the data. */
    public static final int DEFAULT_WARMUP = 40;

    /** CrossoverExpansion / Combo ratios — the same sweep the isolation phase uses. */
    public static final int[] RATIOS = IsolationPhaseRunnerV6.RATIOS;

    public static final String HEADER =
            "TargetMillis,ProfileLevel,Config,Ratio,ScenarioId,Source,BranchingFactor,Rep,"
            + "PlanMillis,InitMillis,SearchMillis,"
            + "InitUnits,InitAdvances,InitCopies,InitEvals,InitFmCalls,"
            + "SearchUnits,SearchAdvances,SearchCopies,SearchEvals,SearchFmCalls,"
            + "NodesEvaluated,UniqueSequences,InitialSequenceValue,FinalSequenceValue,Burns";

    private CalibrationRunnerV6() {}

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int[] targets       = args.length > 2 ? parseInts(args[2]) : DEFAULT_TARGETS_MS;
        int perSource       = args.length > 3 ? Integer.parseInt(args[3].trim()) : DEFAULT_PER_SOURCE;
        int reps            = args.length > 4 ? Integer.parseInt(args[4].trim()) : DEFAULT_REPS;
        int warmup          = args.length > 5 ? Integer.parseInt(args[5].trim()) : DEFAULT_WARMUP;
        // Which budget level's weight profile prices the units reported here. C0 measures time, so the
        // profile does not bound its search; it decides what a "unit" in this CSV is worth, and the
        // whole units/ms flatness check is meaningless without saying which one was used.
        int profileUnits    = args.length > 6 ? Integer.parseInt(args[6].trim())
                                              : ExpAgents.DEFAULT_PROFILE_UNITS;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        // The agents gate on their own num_timeMillis; this only stops the engine timer interfering.
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        RunGuard.install("v6 C0 calibration",
                "re-run the same cell — fully-measured targets are kept, the interrupted one is redone");

        File pDir = new File(portfolioDir);
        File out = new File(outDir);
        ExpCsv.ensureDir(out);
        // Doubly important here: a second C0 process would both corrupt the CSV and, by loading the
        // machine, invalidate the timings that are the whole point of the job.
        if (!RunGuard.acquireLock(out, "CalibrationRunnerV6")) RunGuard.abort(2);
        File csv = new File(out, "calibration.csv");

        System.out.println("############################################################");
        System.out.println("# v6 C0 — thinking-time calibration   (output=" + csv.getPath() + ")");
        System.out.println("# targets(ms)=" + Arrays.toString(targets) + "  perSource=" + perSource
                + "  reps=" + reps + "  warmup=" + warmup);
        System.out.println("# units priced by the " + profileUnits + "cu weight profiles"
                + (core.game.ComputeMeter.hasProfile(
                        core.game.ComputeMeter.profileKey("EMCTS", -1, profileUnits))
                   ? " (calibrated)"
                   : " (NOT calibrated -- falling back to the global weights; run ComputeCalibrateV6"
                     + " first or units/ms cannot be read as a fairness check)"));
        System.out.println("# SINGLE-THREADED by design: this machine is the reference machine.");
        System.out.println("# provenance: java=" + System.getProperty("java.version")
                + "  vm=" + System.getProperty("java.vm.name")
                + "  os=" + System.getProperty("os.name")
                + "  cores=" + Runtime.getRuntime().availableProcessors()
                + "  maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));
        System.out.println("############################################################");
        ServerProfile.get().printBanner("v6 C0 calibration (single-threaded ON PURPOSE)");
        System.out.println("[C0] This job must be the ONLY load on the machine. Its numbers are the "
                + "reference-machine conversion the paper reports, so anything else running — including "
                + "a v6 isolation phase or a thread calibration — invalidates them.");

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(pDir);
        Map<Integer, Integer> bfById = readBranchingFactors(pDir);
        List<Scenario> sample = stratifiedSample(portfolio, bfById, perSource);
        System.out.println("[C0] portfolio=" + portfolio.size() + " scenarios; sampled " + sample.size()
                + " (" + perSource + " per source, BF-tertile stratified)");

        List<Cfg> configs = configs();

        // Resume: adopt any target that a previous run finished in full, and re-measure the rest.
        java.util.Set<Integer> finished = new java.util.LinkedHashSet<>();
        int perTarget = reps * sample.size() * configs.size();
        List<String> carried = loadFinishedTargets(csv, perTarget, finished);
        if (!finished.isEmpty()) {
            System.out.println("[C0] RESUME: targets " + finished + " already complete ("
                    + carried.size() + " rows kept); re-measuring the rest.");
        }

        // Warm the JIT on a config/scenario mix that exercises both operators, then discard.
        if (warmup > 0) {
            System.out.println("[C0] warmup: " + warmup + " turns (discarded)");
            for (int i = 0; i < warmup; i++) {
                Cfg c = configs.get(i % configs.size());
                Scenario sc = sample.get(i % sample.size());
                runOneTurn(c, 250, sc, 9_000 + i, profileUnits);
            }
        }

        List<String> rows = new ArrayList<>(carried);
        long t0 = System.currentTimeMillis();
        int remainingTargets = 0;
        for (int t : targets) if (!finished.contains(t)) remainingTargets++;
        int total = remainingTargets * perTarget;
        int done = 0;

        for (int target : targets) {
            if (finished.contains(target)) {
                System.out.println("[C0] target " + target + "ms — SKIP (already complete on disk)");
                continue;
            }
            if (RunGuard.stopRequested()) {
                System.out.println("[C0] stop requested — completed targets are on disk; re-run to continue.");
                break;
            }
            // Loop order rep → scenario → config keeps the 12 configs adjacent in time, so any thermal
            // or frequency drift over the run cannot align with agent identity.
            for (int rep = 0; rep < reps; rep++) {
                for (Scenario sc : sample) {
                    for (Cfg cfg : configs) {
                        Row r = runOneTurn(cfg, target, sc, rep, profileUnits);
                        if (r != null) {
                            rows.add(String.join(",",
                                    String.valueOf(target), String.valueOf(profileUnits),
                                    cfg.name, cfg.ratio < 0 ? "NA" : String.valueOf(cfg.ratio),
                                    String.valueOf(sc.id), sc.source, String.valueOf(bfById.getOrDefault(sc.id, -1)),
                                    String.valueOf(rep),
                                    String.valueOf(r.planMillis), String.valueOf(r.initMillis),
                                    String.valueOf(r.planMillis - r.initMillis),
                                    String.valueOf(ExpCsv.round(r.initUnits)), String.valueOf(r.initAdvances),
                                    String.valueOf(r.initCopies), String.valueOf(r.initEvals),
                                    String.valueOf(r.initFmCalls),
                                    String.valueOf(ExpCsv.round(r.searchUnits)), String.valueOf(r.searchAdvances),
                                    String.valueOf(r.searchCopies), String.valueOf(r.searchEvals),
                                    String.valueOf(r.searchFmCalls),
                                    String.valueOf(r.nodesEvaluated), String.valueOf(r.uniqueSequences),
                                    String.valueOf(ExpCsv.round(r.initialValue)),
                                    String.valueOf(ExpCsv.round(r.finalValue)),
                                    String.valueOf(r.burns)));
                        }
                        done++;
                        if (done % 100 == 0 || done == total) {
                            System.out.println(String.format(Locale.ROOT,
                                    "    %d/%d turns   elapsed=%s", done, total, elapsed(t0)));
                        }
                    }
                }
            }
            // Persist after every target so an interrupted run keeps completed targets.
            writeCsv(csv, rows);
            System.out.println("[C0] target " + target + "ms complete — " + rows.size()
                    + " rows written   elapsed=" + elapsed(t0));
        }

        writeCsv(csv, rows);
        summarise(rows, targets);
        System.out.println("[C0] DONE   total elapsed=" + elapsed(t0) + "   -> " + csv.getPath());
        RunGuard.finished();
    }

    // ---------------------------------------------------------------------------------------------

    /** The 12 isolation-phase configurations, built in their STOP_TIME calibration flavour. */
    private static List<Cfg> configs() {
        List<Cfg> cfgs = new ArrayList<>();
        cfgs.add(new Cfg("EMCTS", -1));
        cfgs.add(new Cfg("CrossoverBB", -1));
        for (int r : RATIOS) cfgs.add(new Cfg("CrossoverExpansion", r));
        for (int r : RATIOS) cfgs.add(new Cfg("CrossoverCombo", r));
        return cfgs;
    }

    private static AgentSpec specFor(Cfg cfg, int searchMillis, int profileUnits) {
        switch (cfg.name) {
            case "EMCTS":              return ExpAgents.emctsV6Time(searchMillis, profileUnits);
            case "CrossoverBB":        return ExpAgents.crossoverBBV6Time(searchMillis, profileUnits);
            case "CrossoverExpansion": return ExpAgents.crossoverExpansionV6Time(cfg.ratio, searchMillis, profileUnits);
            case "CrossoverCombo":     return ExpAgents.crossoverComboV6Time(cfg.ratio, searchMillis, profileUnits);
            default: throw new IllegalArgumentException("unknown config " + cfg.name);
        }
    }

    /** Runs ONE search turn and extracts its spend telemetry. Returns null if the turn produced none. */
    private static Row runOneTurn(Cfg cfg, int searchMillis, Scenario scenario, int rep, int profileUnits) {
        long seed = IsolationPhaseRunnerV6.visitSeed(scenario.id, rep);
        Agent agent = specFor(cfg, searchMillis, profileUnits).create(seed);
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        int activeTribeId = gs.getActiveTribeID();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(activeTribeId, allIds);

        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try { agent.act(gs, ect); } catch (Throwable t) { return null; }

        List<TurnData> turns = agent.getTurnDataLog();
        if (turns.isEmpty()) return null;
        TurnData t = turns.get(0);
        List<BridgeBurnEventData> burns = agent.getBridgeBurnDataLog();

        Row r = new Row();
        r.planMillis = t.getPlanMillis();
        r.initMillis = t.getInitMillis();
        r.initUnits = t.getInitComputeUnits();
        r.initAdvances = t.getInitAdvances();
        r.initCopies = t.getInitCopies();
        r.initEvals = t.getInitEvals();
        r.initFmCalls = t.getInitFmCalls();
        r.searchUnits = t.getComputeUnits();
        r.searchAdvances = t.getAdvances();
        r.searchCopies = t.getCopies();
        r.searchEvals = t.getEvals();
        r.searchFmCalls = t.getSearchFmCalls();
        r.nodesEvaluated = t.getNodesEvaluated();
        r.uniqueSequences = t.getUniqueSequences();
        r.initialValue = t.getInitialSequenceValue();
        r.finalValue = t.getFinalSequenceValue();
        r.burns = burns == null ? 0 : burns.size();
        return r;
    }

    /** Reads BranchingFactor (manifest column 10) per scenario id. */
    private static Map<Integer, Integer> readBranchingFactors(File portfolioDir) throws Exception {
        Map<Integer, Integer> bf = new HashMap<>();
        File manifest = new File(portfolioDir, "portfolio.csv");
        if (!manifest.exists()) return bf;
        List<String> lines = Files.readAllLines(manifest.toPath());
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            if (f.length < 11) continue;
            try {
                bf.put(Integer.parseInt(f[0].trim()), Integer.parseInt(f[10].trim()));
            } catch (NumberFormatException ignore) { }
        }
        return bf;
    }

    /**
     * Picks {@code perSource} scenarios from each source, spread evenly across that source's
     * branching-factor tertiles. Sampling the real portfolio matters: copy() cost scales with state
     * size and available-action counts grow through a game, so a fresh-game calibration would not
     * transfer to the states the isolation phase actually searches.
     */
    static List<Scenario> stratifiedSample(List<Scenario> portfolio, Map<Integer, Integer> bfById, int perSource) {
        Map<String, List<Scenario>> bySource = new LinkedHashMap<>();
        for (Scenario s : portfolio) bySource.computeIfAbsent(s.source, k -> new ArrayList<>()).add(s);

        List<Scenario> out = new ArrayList<>();
        for (Map.Entry<String, List<Scenario>> e : bySource.entrySet()) {
            List<Scenario> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(s -> bfById.getOrDefault(s.id, 0)));
            int n = list.size();
            if (n == 0) continue;
            int want = Math.min(perSource, n);
            // Even spread over the BF-sorted list == equal representation of the three tertiles.
            for (int k = 0; k < want; k++) {
                int idx = (int) Math.round((k + 0.5) * n / (double) want) - 1;
                out.add(list.get(Math.max(0, Math.min(n - 1, idx))));
            }
        }
        Collections.sort(out, Comparator.comparingInt(s -> s.id));
        return out;
    }

    /** Per-config console summary: the headline conversion and its stability. */
    private static void summarise(List<String> rows, int[] targets) {
        System.out.println();
        System.out.println("==================== C0 summary (per target × config) ====================");
        System.out.printf(Locale.ROOT, "%-24s %8s %12s %12s %12s %10s%n",
                "config", "targetMs", "searchUnits", "units/ms", "initUnits", "nodes");
        for (int target : targets) {
            Map<String, double[]> acc = new LinkedHashMap<>();   // [n, sumUnits, sumSearchMs, sumInit, sumNodes]
            for (String row : rows) {
                String[] f = row.split(",", -1);
                if (Integer.parseInt(f[0]) != target) continue;
                String key = f[1] + (("NA".equals(f[2])) ? "" : "@r" + f[2]);
                double[] a = acc.computeIfAbsent(key, k -> new double[5]);
                a[0] += 1;
                a[1] += Double.parseDouble(f[15]);   // SearchUnits
                a[2] += Double.parseDouble(f[9]);    // SearchMillis
                a[3] += Double.parseDouble(f[10]);   // InitUnits
                a[4] += Double.parseDouble(f[20]);   // NodesEvaluated
            }
            for (Map.Entry<String, double[]> e : acc.entrySet()) {
                double[] a = e.getValue();
                if (a[0] == 0) continue;
                double units = a[1] / a[0], ms = a[2] / a[0];
                System.out.printf(Locale.ROOT, "%-24s %8d %12.0f %12.2f %12.0f %10.1f%n",
                        e.getKey(), target, units, ms > 0 ? units / ms : 0.0, a[3] / a[0], a[4] / a[0]);
            }
        }
        System.out.println("Read units/ms across configs at a fixed target: if it is flat, one shared");
        System.out.println("compute-unit budget is fair; if it varies, budgets must be calibrated per agent.");
    }

    /** Rewrite the whole file: header + rows. Written via a temp file and an atomic rename, so a kill
     *  during the write cannot truncate a calibration that already cost hours. */
    private static void writeCsv(File csv, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        RunGuard.atomicWrite(csv, sb.toString());
    }

    /**
     * Rows already on disk from an earlier, interrupted run, and the set of targets those rows cover
     * completely. C0 persists after each target, so a restart can adopt the finished targets verbatim
     * and only re-measure the one that was cut short — the difference between losing 50 minutes and
     * losing 2.6 hours.
     *
     * <p>A target counts as complete only when its row count matches what a full pass produces
     * ({@code reps × sample × configs}); a partially-written target is dropped and re-run, because
     * mixing halves of two runs of a timing experiment is exactly the kind of silent contamination the
     * calibration exists to avoid.
     */
    private static List<String> loadFinishedTargets(File csv, int expectedPerTarget,
                                                    java.util.Set<Integer> finishedOut) {
        List<String> kept = new ArrayList<>();
        if (!csv.exists()) return kept;
        Map<Integer, List<String>> byTarget = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            int fields = HEADER.split(",").length;
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < fields) continue;             // header change or torn final line
                try {
                    int target = Integer.parseInt(f[0].trim());
                    byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
        } catch (Exception e) {
            System.out.println("[C0] existing calibration.csv unreadable (" + e + ") — starting fresh.");
            return new ArrayList<>();
        }
        for (Map.Entry<Integer, List<String>> e : byTarget.entrySet()) {
            if (e.getValue().size() >= expectedPerTarget) {
                finishedOut.add(e.getKey());
                kept.addAll(e.getValue());
            } else {
                System.out.println("[C0] target " + e.getKey() + "ms is only " + e.getValue().size()
                        + "/" + expectedPerTarget + " rows — discarding it and re-measuring.");
            }
        }
        return kept;
    }

    private static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static final class Cfg {
        final String name; final int ratio;
        Cfg(String name, int ratio) { this.name = name; this.ratio = ratio; }
    }

    private static final class Row {
        long planMillis, initMillis;
        double initUnits, searchUnits;
        long initAdvances, initCopies, initEvals;
        long searchAdvances, searchCopies, searchEvals;
        int initFmCalls, searchFmCalls, nodesEvaluated, uniqueSequences, burns;
        double initialValue, finalValue;
    }
}
