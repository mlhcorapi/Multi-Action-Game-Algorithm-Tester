package core.data.exp;

import core.Constants;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.GameState;
import players.Agent;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.stage1.Stage1BiasedAgent;
import players.stage1.Stage1Params;
import players.stage1.Stage1Params.Granularity;
import players.stage1.Stage1Params.RepairMode;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams.SelectionMode;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * STAGE 1 of the action-bias line (see {@code analysis/STAGE1_PLAN.md}): does within-turn learned
 * action bias IMPROVE the search, in the isolation-phase instrument (single planning turns from a
 * fixed scenario portfolio, paired seeds across configurations — the same design and the same
 * {@code visitSeed} convention as v5/v6)?
 *
 * <p><b>Configurations</b> (all Stage-0-informed; every experimental knob has its baseline-recovering
 * setting, and the Baseline config IS the recovered baseline, verified bit-identical to plain
 * {@code ExperimentalEMCTSAgent} before the run starts):
 * <ul>
 *   <li>epsilon dose-response: 0.75 / 0.50 / 0.25 / 0.10 (Mid keys, removal credit, continuous);</li>
 *   <li>granularity contrast at eps=0.25: Coarse vs Mid;</li>
 *   <li>removal-credit contrast at eps=0.25: off vs on;</li>
 *   <li>update-schedule contrast at eps=0.25: per-burn snapshot vs continuous;</li>
 *   <li>repair shortlisting: K=15 alone (eps=1), K=15 and K=5 combined with eps=0.25;</li>
 *   <li>Baseline additionally logs per-child sequence composition (Stage-2 groundwork:
 *       co-occurrence / interaction analysis on UNBIASED search data).</li>
 * </ul>
 *
 * <p><b>Schedule</b> — v6's budget ladder, every arm at every level, on the 200-scenario development
 * split. Hours are for 48 workers on the reference R7615 (see {@link #SEC_PER_UNIT}):
 * <pre>
 *    80k cu : all 11 configs x 200 scenarios x 8 visits  (17,600 turns, ~0.9 h)  [main grid]
 *    40k cu : all 11 configs x 200 scenarios x 8 visits  (17,600 turns, ~0.4 h)  [budget trend]
 *   400k cu : all 11 configs x 200 scenarios x 6 visits  (13,200 turns, ~3.9 h)  [budget dependence]
 * </pre>
 * The portfolio (200 single + 200 dual) is built once in Stage A and split into a development half
 * and a holdout half; see {@link PortfolioSplit}. A {@code dev} or {@code holdout} run will not build
 * one.
 *
 * <p>Resumable per (config, budget) and per (scenario, visit) with the v6 telemetry-row-is-authority
 * compaction; Ctrl-C is a cooperative stop. Output:
 * {@code test_results/stage1/<Config>/<cu>cu/results.csv + turn_telemetry.csv [+ seqlog_*.csv.gz]}.
 *
 * <p><b>Splits.</b> The run sees one half of the portfolio at a time — {@code dev} for every stage
 * that makes a choice, {@code holdout} for the single confirmation pass, {@code all} for the legacy
 * whole-portfolio behaviour. See {@link PortfolioSplit} for why. The split determines the output
 * tree ({@code <base>/dev/...}), is recorded in the checkpoint key, and is claimed in a marker file,
 * so two splits can never quietly share one {@code results.csv}. The portfolio itself is shared:
 * it lives at {@code <base>/_portfolio}, outside either split's tree.
 *
 * <p>Usage:
 * <pre>
 *   java -Xmx12g -cp "out;lib/*" core.data.exp.Stage1Runner [threads] [baseDir] [split]
 *       threads  default 4
 *       baseDir  default test_results/stage1
 *       split    all (default) | dev | holdout
 *   java -cp "out;lib/*" core.data.exp.Stage1Runner smoke
 *   java -cp "out;lib/*" core.data.exp.Stage1Runner verify
 * </pre>
 */
public final class Stage1Runner {

    private Stage1Runner() {}

    public static final String BASE = "test_results/stage1";
    /** Portfolio size to build when one is missing. 200 + 200 = 400 scenarios: under a
     *  scenario-clustered bootstrap the CI is driven by the number of clusters, not by visits. */
    public static final int NUM_SINGLE = 200, NUM_DUAL = 200;
    /**
     * Budget the equivalence gate runs at. A ladder level, not a round number: the gate asserts that
     * Baseline is bit-identical to plain EMCTS, and "identical" includes realized compute units — so
     * both sides must be priced by a calibrated profile, which only exists at a calibrated level.
     */
    public static final int VERIFY_BUDGET = 40_000;

    /**
     * Per-turn wall-clock model for the run-time estimates printed below — <b>single thread</b>.
     *
     * <p>Fitted to Stage A's three measured points on the dev laptop (40k = 2.57 s, 80k = 5.78 s,
     * 400k = 31.79 s), which it reproduces to within 1%. The model it replaces
     * ({@code cu * 4.5e-5 + 1.2}) predicted 19.2 s at 400k against a measured 31.79 — wrong by 1.66x,
     * in the expensive direction. "Estimate from measured PlanMillis, not intuition" is in this
     * line's own bug table; this is that lesson applied to the estimator itself.
     *
     * <p>{@code -Dstage1.machineFactor=N} scales it for a machine that is not the dev laptop: an EPYC
     * server sustains a far lower all-core clock than a laptop running six threads near boost, so the
     * R7615 wants roughly 1.6. The value is printed in the banner, so an estimate can always be read
     * back to the assumption behind it.
     */
    static final double SEC_PER_UNIT = 8.117e-5, SEC_INTERCEPT = -0.677;

    static double secPerTurn(int cu) {
        double factor = Double.parseDouble(System.getProperty("stage1.machineFactor", "1.0"));
        return Math.max(0.1, cu * SEC_PER_UNIT + SEC_INTERCEPT) * factor;
    }

    public static final String RESULTS_HEADER =
            "ScenarioId,ScenarioSource,ScenarioSourceGameId,ScenarioCapturedTick,VisitId,Config,"
            + "BridgeBurningPhaseNumber,BestSequenceValue,FmCallsElapsed,ComputeUnitsElapsed,"
            + "ScoreAtTurnStart,InitialSequenceValue,FinalSequenceValue,AvgNodeValue,"
            + "FmCalls,NodesEvaluated,UniqueSequences,SearchUnits,PlanMillis,InitMillis";
    private static final int RESULTS_FIELDS = RESULTS_HEADER.split(",").length;

    public static final String TELEMETRY_HEADER =
            "ScenarioId,ScenarioSource,VisitId,Config,InitialValue,FinalValue,"
            + "NodesEvaluated,UniqueSequences,PlanMillis,"
            + "MutEvents,MutBestGains,MutChildDuplicates,MutRepairKept,MutRepairReplaced,"
            + "BiasGreedyPicks,BiasUniformPicks,ShortlistCalls,ShortlistSkipped,StatUpdates,"
            + "ZeroEvalPicks,RepairUnits,RepairCandidateEvals";
    private static final int TELEMETRY_FIELDS = TELEMETRY_HEADER.split(",").length;

    // ------------------------------------------------------------------ configurations

    /** One agent configuration: a name and the Stage1Params it runs with. */
    static final class Config {
        final String name;
        final double eps;
        final Granularity insertKeys, repairKeys;
        final boolean removal, perBurn;
        final RepairMode repairMode;
        final int shortlistK;
        final boolean logSeq;
        /** Expansion policy and offspring count. PLUS/1 is the corrected baseline every other arm
         *  is measured against, and is what every arm outside Stage B uses. */
        final SelectionMode selectionMode;
        final int childrenPerGeneration;
        Config(String name, double eps, Granularity insertKeys, Granularity repairKeys,
               boolean removal, boolean perBurn, RepairMode repairMode, int shortlistK,
               boolean logSeq) {
            this(name, eps, insertKeys, repairKeys, removal, perBurn, repairMode, shortlistK, logSeq,
                 SelectionMode.PLUS, 1);
        }
        Config(String name, double eps, Granularity insertKeys, Granularity repairKeys,
               boolean removal, boolean perBurn, RepairMode repairMode, int shortlistK,
               boolean logSeq, SelectionMode selectionMode, int childrenPerGeneration) {
            this.name = name; this.eps = eps;
            this.insertKeys = insertKeys; this.repairKeys = repairKeys;
            this.removal = removal; this.perBurn = perBurn;
            this.repairMode = repairMode; this.shortlistK = shortlistK; this.logSeq = logSeq;
            this.selectionMode = selectionMode; this.childrenPerGeneration = childrenPerGeneration;
        }
        /** The same arm on a different expansion policy — Stage B's only degree of freedom. */
        Config onSelection(String newName, SelectionMode mode, int k) {
            return new Config(newName, eps, insertKeys, repairKeys, removal, perBurn,
                    repairMode, shortlistK, logSeq, mode, k);
        }
        Agent create(long seed, int cu) {
            Stage1Params p = new Stage1Params();
            p.stop_type = p.STOP_COMPUTE;
            p.num_computeUnits = cu;
            p.heuristic_method = p.SIMPLE_HEURISTIC;
            p.depth = ExpAgents.DEPTH;
            p.epsilon = eps;
            p.insertKeys = insertKeys;
            p.repairKeys = repairKeys;
            p.removalCredit = removal;
            p.perBurnUpdates = perBurn;
            p.repairMode = repairMode;
            p.shortlistK = shortlistK;
            p.logSeqComposition = logSeq;
            // Priced per (arm, budget level). Without this the arm silently falls back to the global
            // weights while the calibrated arms do not, and the two are then spending different
            // currencies for the same nominal budget.
            p.selectionMode = selectionMode;
            p.childrenPerGeneration = childrenPerGeneration;
            p.computeProfile = ExpAgents.abProfileKey(name, cu);
            return new Stage1BiasedAgent(seed, p);
        }
        AgentSpec spec(int cu) {
            return new AgentSpec(ExpAgents.AB_PREFIX + name, seed -> create(seed, cu));
        }
    }

    /**
     * Stage D arm: repair untouched (FULL greedy) unless a shortlist size is given.
     *
     * <p>Repair keys follow insertion keys here, because Stage D predates the split: these arms were
     * defined when one {@code coarseKeys} flag drove both decisions, so tying them is what keeps an
     * arm's meaning identical to the one that was measured before. Stage E is where the two
     * granularities come apart, and it sets them explicitly.
     */
    private static Config biasArm(String name, double eps, Granularity insertKeys,
                                  boolean removal, boolean perBurn, int shortlistK, boolean logSeq) {
        return new Config(name, eps, insertKeys, insertKeys, removal, perBurn,
                shortlistK > 0 ? RepairMode.SHORTLIST_K : RepairMode.FULL, shortlistK, logSeq);
    }

    //                                 name             eps   insertKeys        rem   perBurn  K  logSeq
    static final Config BASELINE   = biasArm("Baseline",     1.00, Granularity.MID,   true, false,  0, true);
    static final Config BIAS_E75   = biasArm("Bias_e75",     0.75, Granularity.MID,   true, false,  0, false);
    static final Config BIAS_E50   = biasArm("Bias_e50",     0.50, Granularity.MID,   true, false,  0, false);
    static final Config BIAS_E25   = biasArm("Bias_e25",     0.25, Granularity.MID,   true, false,  0, false);
    static final Config BIAS_E10   = biasArm("Bias_e10",     0.10, Granularity.MID,   true, false,  0, false);
    static final Config COARSE_E25 = biasArm("Coarse_e25",   0.25, Granularity.COARSE,true, false,  0, false);
    static final Config NOREM_E25  = biasArm("NoRemoval_e25",0.25, Granularity.MID,  false, false,  0, false);
    static final Config PERBURN_E25= biasArm("PerBurn_e25",  0.25, Granularity.MID,   true, true,   0, false);
    static final Config SHORT15    = biasArm("Shortlist15",  1.00, Granularity.MID,   true, false, 15, false);
    static final Config SHORT15_E25= biasArm("Short15_e25",  0.25, Granularity.MID,   true, false, 15, false);
    static final Config SHORT5_E25 = biasArm("Short5_e25",   0.25, Granularity.MID,   true, false,  5, false);

    static final List<Config> ALL = List.of(BASELINE, BIAS_E75, BIAS_E50, BIAS_E25, BIAS_E10,
            COARSE_E25, NOREM_E25, PERBURN_E25, SHORT15, SHORT15_E25, SHORT5_E25);
    static final List<Config> CORE = List.of(BASELINE, BIAS_E25, SHORT5_E25);

    // ------------------------------------------------------------------ Stage E: zero-eval repair

    /**
     * The epsilon Stage E's zero-eval arms run at.
     *
     * <p>This is a named constant rather than a parameter, and that is a deliberate reversal of an
     * earlier draft. Compute profiles are keyed by <i>arm name</i>, so an arm whose epsilon varied at
     * run time would be priced against a cell measured at some other epsilon, silently. One constant
     * that the runner and {@link ComputeCalibrateV6} both read makes the dependency explicit instead.
     *
     * <p>0.25 is the prior champion. <b>If Stage D selects a materially different epsilon*, change it
     * here and re-run the {@code ab} calibration suite</b> — the arms and their prices have to move
     * together.
     */
    static final double Z0_EPS = 0.25;

    /** Zero-evaluation repair arm: insertion biased at {@link #Z0_EPS}, repair by Q-argmax, no eval. */
    static Config zeroEval(String name, Granularity insertKeys, Granularity repairKeys) {
        return new Config(name, Z0_EPS, insertKeys, repairKeys, true, false,
                RepairMode.ZERO_EVAL, 0, false);
    }

    static final Config Z0_COARSE   = zeroEval("Z0_Coarse",       Granularity.COARSE, Granularity.COARSE);
    static final Config Z0_MID      = zeroEval("Z0_Mid",          Granularity.COARSE, Granularity.MID);
    static final Config Z0_FINE     = zeroEval("Z0_Fine",         Granularity.COARSE, Granularity.FINE);
    static final Config Z0_INS_FINE = zeroEval("Z0_Fine_InsFine", Granularity.FINE,   Granularity.FINE);

    /**
     * Stage E's arms. {@code dChampion} is Stage D's winner, carried over unchanged as the
     * cross-stage anchor.
     *
     * <p>The last two are the D2 falsification, and they only mean something together:
     * {@code Z0_Fine} vs {@code Z0_Mid} vs {@code Z0_Coarse} must order Fine &gt; Mid &gt; Coarse,
     * <i>and</i> {@code Z0_Fine_InsFine} must lose to {@code Z0_Fine} — making the INSERTION table
     * fine has to hurt. Without the second, the first is equally consistent with "fine is simply
     * better everywhere".
     */
    static List<Config> stageE(Config dChampion) {
        return List.of(BASELINE, dChampion, Z0_COARSE, Z0_MID, Z0_FINE, Z0_INS_FINE);
    }

    // ------------------------------------------------------------------ Stage B: the substrate

    /**
     * Stage B's 2 x 2: expansion policy crossed with the insertion bias.
     *
     * <p>The contrast that matters is the <b>interaction</b> — whether the bias pays the same on both
     * substrates. The bias works by having good mutations revisited, and comma and plus differ in
     * exactly that, so an operator result measured on one need not transfer to the other. The main
     * effect matters too, and more bluntly: whichever policy wins becomes the substrate <i>and the
     * baseline</i> for every later stage, because measuring AB-EMCTS against the weaker of two
     * available searches is the fastest way to produce a dismissible result.
     *
     * <p>k = 5 for comma is not arbitrary. Comma at k = 1 is not comma at all — with one child, "adopt
     * the best of the generation unconditionally" is a neutral random walk, which
     * {@code SelectionStructureCheck} asserts by showing it identical to DRIFT. Comma needs offspring
     * to select among before it is a policy.
     */
    static final Config COMMA_OFF = BASELINE.onSelection("Comma_Off", SelectionMode.COMMA, 5);
    static final Config COMMA_ON  = BIAS_E25.onSelection("Comma_On",  SelectionMode.COMMA, 5);

    /**
     * Stage B's 2 x 2 — but only TWO of its cells are new arms.
     *
     * <p>The plus half of the factorial is {@code Baseline} and {@code Bias_e25} themselves: PLUS at
     * k = 1 is the corrected default, so a separate "Plus_Off" arm would be the same configuration
     * under a second name, producing byte-identical output on identical seeds. This line treats two
     * arms with identical output as a bug, and it would be right to — so the factorial is completed
     * at analysis time from arms that already exist rather than by duplicating them.
     *
     * <p>That also makes the plus half FREE: Stage D already runs both at 80k on the development
     * split, on the same scenarios and the same visit seeds, so Stage B only has to run the two comma
     * arms and pair against Stage D's rows.
     */
    static List<Config> stageB() {
        return List.of(BASELINE, BIAS_E25, COMMA_OFF, COMMA_ON);
    }

    /** The arms Stage B actually has to RUN; the plus half comes from Stage D's grid. */
    static List<Config> stageBNewArms() {
        return List.of(COMMA_OFF, COMMA_ON);
    }

    /**
     * Every arm this line can run, by name — the registry {@link ComputeCalibrateV6} calibrates and
     * the runners look arms up in. Adding an arm anywhere else and forgetting it here means it runs
     * unpriced, which {@link ExpAgents#requireProfiles} turns from silent into fatal.
     */
    static final List<Config> CORE_ARMS = List.of(BASELINE, BIAS_E75, BIAS_E50, BIAS_E25, BIAS_E10,
            COARSE_E25, NOREM_E25, PERBURN_E25, SHORT15, SHORT15_E25, SHORT5_E25,
            Z0_COARSE, Z0_MID, Z0_FINE, Z0_INS_FINE),
            STAGE_B_ARMS = stageBNewArms();

    static final List<Config> ALL_ARMS = concat(CORE_ARMS, STAGE_B_ARMS);

    private static List<Config> concat(List<Config> a, List<Config> b) {
        List<Config> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static Config armByName(String name) {
        for (Config c : ALL_ARMS) if (c.name.equals(name)) return c;
        throw new IllegalArgumentException("unknown action-bias arm '" + name + "'; known: "
                + ALL_ARMS.stream().map(c -> c.name).collect(java.util.stream.Collectors.toList()));
    }

    /** An {@link AgentSpec} for one arm at one budget level, for the calibrator. */
    public static AgentSpec specFor(String arm, int computeUnits) {
        return armByName(arm).spec(computeUnits);
    }

    /** Arm names, in calibration order. */
    public static List<String> armNames() {
        List<String> out = new ArrayList<>();
        for (Config c : ALL_ARMS) out.add(c.name);
        return out;
    }

    /** One budget level of the schedule. */
    static final class Level {
        final int cu; final int visits; final List<Config> configs;
        Level(int cu, int visits, List<Config> configs) {
            this.cu = cu; this.visits = visits; this.configs = configs;
        }
    }

    /**
     * Stage D's schedule: v6's budget ladder, every arm at every level.
     *
     * <p><b>Why v6's ladder</b> (40k / 80k / 400k) rather than the old 160k / 640k: those were set in
     * the pre-profile compute currency, and reusing v6's levels shares one calibration matrix and puts
     * the action-bias and crossover lines on one axis.
     *
     * <p><b>Why all 11 arms at 400k</b>, where the laptop schedule could afford only {@link #CORE}:
     * that is what turns "is the effect real?" into "does it grow or shrink with search budget?" —
     * i.e. whether the bias is a small-budget crutch. It is the single most expensive block in the
     * plan (~3.9 h of ~5.3 h) and it buys the answer to a question the trend level alone cannot reach.
     *
     * <p><b>Why this order.</b> Leaves are checkpointed independently, so an interrupted run keeps
     * whatever finished — which makes the order a priority list. The main grid comes first, then the
     * cheap trend level, then the expensive confirmation, so a run that dies partway still holds the
     * headline and its budget trend.
     */
    static List<Level> schedule() {
        return List.of(
                new Level(80_000, 8, ALL),     // main grid
                new Level(40_000, 8, ALL),     // budget trend — cheap, so it runs before 400k
                new Level(400_000, 6, ALL));   // budget dependence / confirmation
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("smoke")) { smoke(); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("verify")) {
            List<Scenario> p = smokePortfolio();
            System.exit(verify(p.get(0)) ? 0 : 1);
        }
        int threads = args.length > 0 ? Integer.parseInt(args[0].trim()) : 4;
        File baseDir = new File(args.length > 1 ? args[1].trim() : BASE);
        PortfolioSplit.Split split;
        try {
            split = PortfolioSplit.Split.parse(args.length > 2 ? args[2] : "all");
        } catch (IllegalArgumentException e) {
            System.err.println("[stage1] " + e.getMessage());
            System.exit(2);
            return;
        }
        run(threads, baseDir, NUM_SINGLE, NUM_DUAL, schedule(), split);
    }

    static void run(int threads, File baseDir, int numSingle, int numDual, List<Level> schedule,
                    PortfolioSplit.Split split) throws Exception {
        RunGuard.install("STAGE 1 BIAS",
                "re-run the SAME command — finished leaves are skipped, partial leaves continue "
                + "per (scenario, visit)");
        ExpCsv.ensureDir(baseDir);

        // The portfolio is shared by both splits, so it lives at the base and never inside a
        // split's output tree. The outputs are separated: pointing the analysis at one directory
        // is what makes "the holdout was read exactly once" an auditable fact rather than a promise.
        File pDir = new File(baseDir, "_portfolio");
        File outDir = split.isSubset() ? new File(baseDir, split.key()) : baseDir;
        ExpCsv.ensureDir(outDir);
        if (!RunGuard.acquireLock(outDir, "Stage1Runner")) RunGuard.abort(2);
        PortfolioSplit.claimOutputRoot(outDir, split);
        applyEngineConstants();

        // Cheapest precondition first: a checkpoint we cannot interpret invalidates the whole run,
        // so it is worth finding out before the portfolio build and the equivalence gate.
        File ckpt = new File(outDir, "_completed.txt");
        Set<String> done = ExpCsv.readCompleted(ckpt);
        requireSplitAwareCheckpoint(ckpt, done);

        long t0 = System.currentTimeMillis();

        // Portfolio: deterministic first-N subset of the v5/v6 build procedure; journaled/resumable.
        if (!new File(pDir, "_portfolio_complete.txt").exists()) {
            if (split.isSubset()) {
                // Two split runs may be in flight at once; neither should race the other into a
                // build, and a split frozen against a portfolio built mid-run is not reproducible.
                System.err.println("[stage1] ABORT: no portfolio at " + pDir + ", and a '"
                        + split.key() + "' run will not build one.");
                System.err.println("[stage1]   Build it once and freeze the split first:");
                System.err.println("[stage1]     java -cp \"out:lib/*\" core.data.exp.BuildV6Portfolio "
                        + pDir + " <threads> " + numSingle + " " + numDual);
                System.err.println("[stage1]     java -cp \"out:lib/*\" core.data.exp.PortfolioSplit " + pDir);
                RunGuard.abort(6);
                return;
            }
            System.out.println("[stage1] building portfolio (" + numSingle + " single + " + numDual
                    + " dual) — one-time cost, journaled; expect ~0.5-1 h on 4 workers");
            BuildV6Portfolio.build(pDir.getPath(), threads, numSingle, numDual);
            applyEngineConstants();   // the builder sets its own engine constants; restore ours
        }
        List<Scenario> full = IsolationPhaseRunner.loadPortfolio(pDir);
        List<Scenario> portfolio;
        try {
            portfolio = PortfolioSplit.apply(full, pDir, split);
        } catch (IllegalStateException e) {
            System.err.println("[stage1] ABORT: " + e.getMessage());
            RunGuard.abort(4);
            return;
        }
        System.out.println("[stage1] portfolio loaded: " + full.size() + " scenarios; split="
                + split.key() + " -> " + portfolio.size() + " in play");

        banner(schedule, threads, outDir, split, portfolio.size());

        // Every arm must be priced before any of them runs. An unpriced arm falls back to the global
        // weights, spending a different currency for the same nominal budget, and no CSV records it.
        try {
            ExpAgents.requireProfiles("Stage 1 " + split.key(), requiredProfiles(schedule));
        } catch (IllegalStateException e) {
            System.err.println("[stage1] ABORT: " + e.getMessage());
            RunGuard.abort(7);
            return;
        }

        // Equivalence gate: Baseline params must reproduce plain EMCTS bit-for-bit.
        if (!verify(portfolio.get(0))) {
            System.err.println("[stage1] ABORT: Baseline is not bit-identical to plain EMCTS");
            RunGuard.abort(3);
        }

        for (Level level : schedule) {
            for (Config cfg : level.configs) {
                if (RunGuard.stopRequested()) { finish(t0); return; }
                String key = "config=" + cfg.name + ";cu=" + level.cu + ";split=" + split.key();
                if (done.contains(key)) {
                    System.out.println("[stage1] SKIP (done): " + key);
                    continue;
                }
                boolean complete = runLeaf(level, cfg, portfolio, outDir, threads);
                if (complete) {
                    ExpCsv.markCompleted(ckpt, key);
                    System.out.println("[stage1] DONE " + key + "   elapsed=" + elapsed(t0));
                } else {
                    System.out.println("[stage1] INTERRUPTED " + key + " — resume continues "
                            + "per (scenario, visit)");
                    finish(t0);
                    return;
                }
            }
        }
        finish(t0);
    }

    private static void finish(long t0) {
        System.out.println("[stage1] finished in " + elapsed(t0)
                + ". Analyse with: python analysis/analyze_stage1.py");
        RunGuard.finished();
    }

    /**
     * Checkpoint entries written before splits existed name a (config, budget) leaf without saying
     * which scenarios produced it. Resuming against them would skip leaves that may hold the other
     * half of the portfolio — the runner "skips any leaf listed there without re-checking the code
     * that produced it", which is precisely the failure this guard exists to make loud.
     */
    private static void requireSplitAwareCheckpoint(File ckpt, Set<String> done) {
        List<String> legacy = new ArrayList<>();
        for (String k : done) if (!k.contains(";split=")) legacy.add(k);
        if (legacy.isEmpty()) return;
        System.err.println("[stage1] ABORT: " + ckpt + " holds " + legacy.size()
                + " checkpoint entr" + (legacy.size() == 1 ? "y" : "ies")
                + " from before the portfolio split existed, e.g. '" + legacy.get(0) + "'.");
        System.err.println("[stage1]   Such an entry does not say which half of the portfolio it "
                + "covers, so resuming would mark leaves complete that were never run on this split.");
        System.err.println("[stage1]   Archive the output tree, or delete " + ckpt + ", and re-run.");
        RunGuard.abort(5);
    }

    private static void banner(List<Level> schedule, int threads, File outDir,
                               PortfolioSplit.Split split, int scenarios) {
        System.out.println("############################################################");
        System.out.println("# STAGE 1 — within-turn action bias, isolation phase   (output=" + outDir + ")");
        System.out.println("# split=" + split.key() + "   scenarios=" + scenarios);
        long totalTurns = 0; double estSec = 0;
        for (Level l : schedule) {
            long t = (long) l.configs.size() * scenarios * l.visits;
            totalTurns += t;
            estSec += t * secPerTurn(l.cu);
        }
        System.out.println(String.format(Locale.ROOT,
                "# %d turns planned; rough estimate %.1f h on %d workers "
                + "(single-thread model x machineFactor=%s)",
                totalTurns, estSec / 3600.0 / threads, threads,
                System.getProperty("stage1.machineFactor", "1.0")));
        System.out.println("# ComputeMeter weights: " + core.game.ComputeMeter.weightsSummary());
        System.out.println("############################################################");
    }

    /** Profiles this schedule needs: one per (arm, level), plus the level the verify gate runs at. */
    static List<String> requiredProfiles(List<Level> schedule) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(ExpAgents.abProfileKey(ExpAgents.AB_BASELINE_ARM, VERIFY_BUDGET));
        for (Level l : schedule)
            for (Config c : l.configs) out.add(ExpAgents.abProfileKey(c.name, l.cu));
        return new ArrayList<>(out);
    }

    static void applyEngineConstants() {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.VERBOSE = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;   // agents gate on compute units, not the clock
    }

    // ------------------------------------------------------------------ one leaf

    /** Runs all outstanding (scenario, visit) turns of one (config, budget) leaf. */
    private static boolean runLeaf(Level level, Config cfg, List<Scenario> portfolio, File outDir,
                                   int threads) throws Exception {
        File dir = new File(outDir, cfg.name + "/" + level.cu + "cu");
        ExpCsv.ensureDir(dir);
        File csv = new File(dir, "results.csv");
        File tele = new File(dir, "turn_telemetry.csv");
        Set<String> doneTurns = compactPartialLeaf(csv, tele);

        int total = portfolio.size() * level.visits;
        System.out.println(String.format(Locale.ROOT,
                "[stage1] RUN %s @ %dcu: %d turns (%d already done), est %.0f min",
                cfg.name, level.cu, total - doneTurns.size(), doneTurns.size(),
                (total - doneTurns.size()) * secPerTurn(level.cu) / 60.0 / threads));

        AtomicInteger completed = new AtomicInteger(doneTurns.size());
        AtomicInteger skipped = new AtomicInteger();
        ExecutorService pool = RunGuard.register(Executors.newFixedThreadPool(threads));
        LoadGovernor governor = new LoadGovernor(threads, 5_000L);
        governor.start();
        Object writeLock = new Object();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Scenario sc : portfolio) {
                for (int v = 0; v < level.visits; v++) {
                    if (doneTurns.contains(sc.id + ":" + v)) continue;
                    final Scenario scenario = sc;
                    final int visit = v;
                    futures.add(pool.submit(() -> {
                        if (RunGuard.stopRequested()) { skipped.incrementAndGet(); return; }
                        try {
                            governor.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            skipped.incrementAndGet();
                            return;
                        }
                        try {
                            TurnOut out = runOneTurn(cfg, level.cu, scenario, visit, dir);
                            synchronized (writeLock) {
                                // Telemetry LAST: it is the completion authority on resume.
                                ExpCsv.append(csv, RESULTS_HEADER, out.burnRows);
                                ExpCsv.append(tele, TELEMETRY_HEADER,
                                        Collections.singletonList(out.telemetryRow));
                            }
                        } catch (Throwable t) {
                            System.err.println("[stage1] task failed " + cfg.name + "@" + level.cu
                                    + " scenario=" + scenario.id + " visit=" + visit + ": " + t);
                        } finally {
                            governor.release();
                        }
                        int c = completed.incrementAndGet();
                        if (c % 100 == 0 || c == total) {
                            System.out.println(String.format(Locale.ROOT,
                                    "    %s@%dcu: %d/%d   [%s]", cfg.name, level.cu, c, total,
                                    governor.status()));
                        }
                    }));
                }
            }
            for (Future<?> f : futures) f.get();
        } finally {
            governor.stop();
            RunGuard.shutdownAndWait(pool, 1);
        }
        return skipped.get() == 0 && !RunGuard.stopRequested();
    }

    // ------------------------------------------------------------------ one turn

    private static final class TurnOut {
        final List<String> burnRows;
        final String telemetryRow;
        TurnOut(List<String> burnRows, String telemetryRow) {
            this.burnRows = burnRows; this.telemetryRow = telemetryRow;
        }
    }

    /** One planning turn from a captured scenario — the v6 isolation pattern verbatim. */
    private static TurnOut runOneTurn(Config cfg, int cu, Scenario scenario, int visitId, File leafDir)
            throws Exception {
        long seed = IsolationPhaseRunnerV6.visitSeed(scenario.id, visitId);
        Agent agent = cfg.create(seed, cu);
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        int activeTribeId = gs.getActiveTribeID();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(activeTribeId, allIds);

        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        agent.act(gs, ect);

        List<TurnData> turns = agent.getTurnDataLog();
        if (turns.isEmpty()) throw new IllegalStateException("agent produced no TurnData");
        TurnData t = turns.get(0);

        String turnCols = t.getScoreAtTurnStart() + ","
                + ExpCsv.round(t.getInitialSequenceValue()) + ","
                + ExpCsv.round(t.getFinalSequenceValue()) + ","
                + ExpCsv.round(t.getAvgNodeValue()) + ","
                + t.getFmCalls() + "," + t.getNodesEvaluated() + "," + t.getUniqueSequences() + ","
                + ExpCsv.round(t.getComputeUnits()) + "," + t.getPlanMillis() + "," + t.getInitMillis();

        List<String> rows = new ArrayList<>();
        for (BridgeBurnEventData b : agent.getBridgeBurnDataLog()) {
            rows.add(scenario.id + "," + scenario.source + "," + scenario.sourceGameId + ","
                    + scenario.capturedTick + "," + visitId + "," + cfg.name + ","
                    + b.getBridgeBurningPhaseNumber() + ","
                    + ExpCsv.round(b.getBestSequenceValue()) + ","
                    + b.getFmCallsElapsed() + "," + ExpCsv.round(b.getComputeUnitsElapsed()) + ","
                    + turnCols);
        }

        core.data.dto.OperatorTelemetryData ot = agent.getOperatorTelemetry();
        Stage1BiasedAgent s1 = (agent instanceof Stage1BiasedAgent) ? (Stage1BiasedAgent) agent : null;
        String teleRow = scenario.id + "," + scenario.source + "," + visitId + "," + cfg.name + ","
                + ExpCsv.round(t.getInitialSequenceValue()) + ","
                + ExpCsv.round(t.getFinalSequenceValue()) + ","
                + t.getNodesEvaluated() + "," + t.getUniqueSequences() + "," + t.getPlanMillis() + ","
                + (ot == null ? "0,0,0,0,0" : ot.mutEvents + "," + ot.mutBestGains + ","
                    + ot.mutChildDuplicates + "," + ot.mutRepairKept + "," + ot.mutRepairReplaced) + ","
                + (s1 == null ? "0,0,0,0,0" : s1.getBiasGreedyPicks() + "," + s1.getBiasUniformPicks()
                    + "," + s1.getShortlistCalls() + "," + s1.getShortlistSkipped() + ","
                    + s1.getStatUpdates()) + ","
                + (s1 == null ? "0,0,0" : s1.getZeroEvalPicks() + ","
                    + ExpCsv.round(s1.getRepairUnits()) + "," + s1.getRepairCandidateEvals());

        // Stage-2 groundwork: per-child sequence composition (Baseline only).
        if (cfg.logSeq && s1 != null && !s1.getSeqLogRows().isEmpty()) {
            File fin = new File(leafDir, "seqlog_s" + scenario.id + "_v" + visitId + ".csv.gz");
            File tmp = new File(leafDir, fin.getName() + ".tmp");
            String prefix = scenario.id + "," + visitId + ",";
            try (Writer w = new OutputStreamWriter(
                    new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16),
                    StandardCharsets.UTF_8)) {
                w.write("ScenarioId,VisitId," + Stage1BiasedAgent.SEQLOG_FIELDS);
                w.write('\n');
                for (String r : s1.getSeqLogRows()) {
                    w.write(prefix);
                    w.write(r);
                    w.write('\n');
                }
            }
            if (fin.exists() && !fin.delete()) throw new IllegalStateException("cannot replace " + fin);
            if (!tmp.renameTo(fin)) throw new IllegalStateException("cannot rename " + tmp);
        }
        return new TurnOut(rows, teleRow);
    }

    // ------------------------------------------------------------------ verify

    /**
     * The equivalence gate: Stage1BiasedAgent with all-default knobs must plan a scenario turn
     * bit-identically to plain ExperimentalEMCTSAgent (same values, same forward-model calls, same
     * node and unique-sequence counts). Guards the copied mutate()/repair() against drift.
     */
    static boolean verify(Scenario scenario) {
        applyEngineConstants();
        String a = verifyRun(scenario, false);
        String b = verifyRun(scenario, true);
        boolean ok = a.equals(b);
        System.out.println("[stage1-verify] plain:    " + a);
        System.out.println("[stage1-verify] baseline: " + b);
        System.out.println(ok ? "[stage1-verify] PASS — Baseline is bit-identical to plain EMCTS"
                              : "[stage1-verify] FAIL — copied operators have drifted from the base class");
        return ok;
    }

    private static String verifyRun(Scenario scenario, boolean stage1) {
        long seed = IsolationPhaseRunnerV6.visitSeed(scenario.id, 0);
        Agent agent;
        if (stage1) {
            agent = BASELINE.create(seed, VERIFY_BUDGET);
        } else {
            // Priced as the Baseline arm: the gate compares realized compute units, so an
            // unpriced plain agent would be measured in the global currency and the two sides would
            // differ for a reason that has nothing to do with the operators under test.
            agent = new ExperimentalEMCTSAgent(seed,
                    ExpAgents.abParams(ExpAgents.AB_BASELINE_ARM, VERIFY_BUDGET));
        }
        GameState gs = scenario.state.copy();
        gs.setRandomGenerator(new java.util.Random(seed));
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(gs.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        try {
            agent.act(gs, ect);
        } catch (Throwable t) {
            return "THREW: " + t;
        }
        TurnData t = agent.getTurnDataLog().get(0);
        return "init=" + ExpCsv.round(t.getInitialSequenceValue())
                + " final=" + ExpCsv.round(t.getFinalSequenceValue())
                + " fm=" + t.getFmCalls() + " nodes=" + t.getNodesEvaluated()
                + " unique=" + t.getUniqueSequences()
                + " units=" + ExpCsv.round(t.getComputeUnits());
    }

    // ------------------------------------------------------------------ smoke

    private static List<Scenario> smokePortfolio() throws Exception {
        File pDir = new File("test_results/stage1_smoke/_portfolio");
        if (!new File(pDir, "_portfolio_complete.txt").exists()) {
            System.out.println("[stage1-smoke] building tiny portfolio (4 single + 2 dual)...");
            BuildV6Portfolio.build(pDir.getPath(), 4, 4, 2);
        }
        applyEngineConstants();
        return IsolationPhaseRunner.loadPortfolio(pDir);
    }

    /** Tiny end-to-end: tiny portfolio, 8k budget, 1 visit, three configs. A few minutes. */
    private static void smoke() throws Exception {
        RunGuard.install("STAGE 1 SMOKE", "re-run to resume");
        List<Scenario> portfolio = smokePortfolio();
        if (!verify(portfolio.get(0))) { RunGuard.abort(3); }
        File outDir = new File("test_results/stage1_smoke");
        List<Level> sched = List.of(new Level(8_000, 1, CORE));
        for (Level l : sched)
            for (Config c : l.configs)
                runLeaf(l, c, portfolio, outDir, 2);
        System.out.println("[stage1-smoke] done. Analyse with: "
                + "python analysis/analyze_stage1.py test_results/stage1_smoke "
                + "--outdir analysis/stage1_smoke_results");
        RunGuard.finished();
    }

    // ------------------------------------------------------------------ resume compaction

    /** v6-style: a telemetry row proves its turn finished; results rows without one are dropped. */
    private static Set<String> compactPartialLeaf(File csv, File tele) throws Exception {
        Set<String> done = new LinkedHashSet<>();
        List<String> teleKept = new ArrayList<>();
        if (tele.exists()) {
            List<String> lines = Files.readAllLines(tele.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < TELEMETRY_FIELDS) continue;
                try {
                    String key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[2].trim());
                    if (done.add(key)) teleKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
        }
        if (tele.exists() || !teleKept.isEmpty()) writeAll(tele, TELEMETRY_HEADER, teleKept);
        List<String> resKept = new ArrayList<>();
        if (csv.exists()) {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length < RESULTS_FIELDS) continue;
                try {
                    String key = Integer.parseInt(f[0].trim()) + ":" + Integer.parseInt(f[4].trim());
                    if (done.contains(key)) resKept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
            writeAll(csv, RESULTS_HEADER, resKept);
        }
        return done;
    }

    private static void writeAll(File f, String header, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String elapsed(long t0) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
