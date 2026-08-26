package core.data.exp;

import core.game.ComputeMeter;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v6 FULL GAMES — the tournament phase. Single entry point.
 *
 * <p>EMCTS plays 100 complete games against each of the 11 experimental configurations at each of
 * three compute budgets: <b>33 matchups, 3,300 games</b>. The only outcome recorded is who won. Detailed search telemetry is what the v6 isolation phase is for; this phase
 * answers the question that one deliberately cannot — the isolation phase measures the value of the
 * best sequence found in one turn from a captured position, which is search quality, and a win rate
 * is playing strength.
 *
 * <p>See {@code analysis/EXPERIMENT_V6_FULL_GAMES_PLAN.md} for the design and
 * {@code analysis/V6_SERVER_RUNBOOK.md} for the machine placement (both phases share
 * {@link ServerProfile}, {@link LoadGovernor} and {@link RunGuard}).
 *
 * <h2>Fairness, in the order the sources of bias were removed</h2>
 * <ol>
 *   <li><b>Tribe.</b> IMPERIUS2 and IMPERIUS are a mirror pair — same starting technology, same
 *       starting unit, identical terrain-generation weights.</li>
 *   <li><b>Map.</b> {@value FullGameMapSetV6#NUM_MAPS} fixed maps, the same ones in every matchup and
 *       at every budget, each played {@value FullGameRunnerV6#GAMES_PER_MAP} times.</li>
 *   <li><b>Spawn and turn order.</b> Each map is played
 *       {@value FullGameRunnerV6#GAMES_PER_ORIENTATION} times per orientation, and an orientation
 *       swap exchanges capitals AND seats, so position and first-move advantage cancel within every
 *       map.</li>
 *   <li><b>Position sampling.</b> The seed grid is identical across matchups and budgets, so every
 *       matchup is played over the same 100 starting conditions.</li>
 *   <li><b>Compute.</b> A deterministic per-turn compute-unit budget (STOP_COMPUTE), identical for
 *       both sides, bounding search after a common greedy initialization — the same currency and the
 *       same three levels as the isolation phase.</li>
 *   <li><b>Residual anything.</b> There is no self-play control arm; see
 *       {@link FullGameRunnerV6#matchups}. The swap is asserted directly by
 *       {@link NewExperimentSmokeTestV6Full} §2, the realized schedule is checked by
 *       {@link VerifyV6FullOutput}, and W6 of the analysis reports every matchup's win rate by
 *       orientation and by seat — so residual asymmetry shows up in all 33 arms.</li>
 * </ol>
 *
 * <h2>Restartability</h2>
 * <p>Re-running this command resumes: finished matchups are skipped whole, a partial matchup
 * continues per game, and a directory lock refuses to start a second writer over the same tree.
 * Stopping is a SIGTERM, never {@code kill -9}.
 *
 * <p>Usage: {@code java -Xmx<heap> -cp "out:lib/*" core.data.exp.RunV6FullGames [threads|auto] [cuCsv]}
 */
public final class RunV6FullGames {

    private RunV6FullGames() {}

    public static final String BASE = "test_results/v6_full";

    /**
     * The same three budget levels as the isolation phase, in compute-units of SEARCH (the common
     * greedy initialization is excluded from the budget). Kept identical on purpose: the point of this
     * phase is to say whether the search-quality differences the isolation phase measures at these
     * budgets turn into wins at the same budgets, and a different budget axis would break that link.
     * See {@link RunExperimentV6#DEFAULT_COMPUTE_LEVELS} for where 40,000 comes from.
     */
    public static final int[] COMPUTE_LEVELS = RunExperimentV6.DEFAULT_COMPUTE_LEVELS;

    /**
     * Tick cap for a game, after which {@code GameState.computeGameRanking} awards the win on score.
     *
     * <p><b>Set this from {@link GameLengthProbeV6}, not by assumption.</b> The old Phase 2 tournament
     * used 50 and only 1.3% of its 900 games reached it, so the cap was effectively inert there — but
     * those agents planned on a 500–1000 ms clock, and these are far stronger. Run the probe
     * (notebook §3), read its recommendation, and change this constant if it differs.
     *
     * <p>It is a compile-time constant rather than a file the runner reads so that the tournament's
     * rules are fixed and visible in the source, and so a dataset cannot silently contain games played
     * under two different caps.
     */
    public static final int TURN_LIMIT = 50;

    public static void main(String[] args) throws Exception {
        RunGuard.install("v6 FULL GAMES",
                "re-run the SAME command — finished matchups are skipped and a partial one continues "
                + "per game");
        String threadsArg = args.length > 0 ? args[0] : "auto";
        int[] cuLevels = args.length > 1 ? GameLengthProbeV6.parseInts(args[1]) : COMPUTE_LEVELS;
        long t0 = System.currentTimeMillis();

        File baseDir = new File(BASE);
        ExpCsv.ensureDir(baseDir);
        // "Re-run the same cell to resume" is only safe if the previous run really has stopped.
        if (!RunGuard.acquireLock(baseDir, "RunV6FullGames")) RunGuard.abort(2);

        File calibDir = new File(baseDir, "_calibration");
        File mapsDir = new File(baseDir, "_maps");
        File ckpt = new File(baseDir, "_completed.txt");
        ExpCsv.ensureDir(calibDir);

        ServerProfile machine = ServerProfile.get();
        List<FullGameMapSetV6.MapInfo> maps = FullGameMapSetV6.build();
        FullGameMapSetV6.writeManifest(mapsDir, maps);

        int totalMatchups = cuLevels.length * FullGameRunnerV6.matchups(cuLevels[0]).size();
        int totalGames = totalMatchups * FullGameRunnerV6.GAMES_PER_MATCHUP;

        System.out.println("############################################################");
        System.out.println("# v6 FULL GAMES — tournament phase   (output=" + BASE + ")");
        System.out.println("# " + totalMatchups + " matchups x " + FullGameRunnerV6.GAMES_PER_MATCHUP
                + " games = " + String.format(Locale.ROOT, "%,d", totalGames) + " games");
        System.out.println("# Budgets: " + java.util.Arrays.toString(cuLevels) + " compute-units/turn (search only)");
        System.out.println("# Turn cap: " + TURN_LIMIT + " ticks (games past it are decided on score;"
                + " GameLengthProbeV6 sets this)");
        System.out.println("# Maps: " + FullGameMapSetV6.summary(maps));
        System.out.println("# Tribes: " + FullGameMapSetV6.TRIBES[0] + " vs " + FullGameMapSetV6.TRIBES[1]
                + " (mirror pair), swapped every " + FullGameRunnerV6.GAMES_PER_ORIENTATION + " games");
        System.out.println("# Compute weights: " + ComputeMeter.weightsSummary());
        System.out.println("# " + machine.summary());
        System.out.println("############################################################");
        System.out.println();

        ExpCsv.writeText(new File(baseDir, "config.txt"), config(cuLevels, maps, machine));

        Set<String> done = ExpCsv.readCompleted(ckpt);
        if (!done.isEmpty())
            System.out.println("[v6full] resuming: " + done.size() + "/" + totalMatchups
                    + " matchup(s) already complete");

        for (int cu : cuLevels) {
            if (RunGuard.stopRequested()) break;
            System.out.println();
            System.out.println("=== budget level " + String.format(Locale.ROOT, "%,d", cu) + " units ===");

            int threads = resolveThreads(threadsArg, cu, calibDir, maps);
            List<FullGameRunnerV6.Matchup> finished =
                    FullGameRunnerV6.runLevel(cu, maps, threads, baseDir, done);
            for (FullGameRunnerV6.Matchup m : finished) {
                if (done.add(m.key())) {
                    ExpCsv.markCompleted(ckpt, m.key());
                    System.out.println("  DONE " + m.key());
                }
            }
        }

        long mins = (System.currentTimeMillis() - t0) / 60000;
        System.out.println();
        if (RunGuard.stopRequested()) {
            System.out.println("[v6full] STOPPED after " + mins + " min with " + done.size() + "/"
                    + totalMatchups + " matchup(s) complete. Re-run the same command to resume.");
        } else {
            System.out.println("[v6full] complete in " + mins + " min. " + done.size() + "/"
                    + totalMatchups + " matchup(s). Output in " + BASE);
            System.out.println("[v6full] next: VerifyV6FullOutput, then analysis/analyze_v6_full_games.py");
        }
        RunGuard.finished();
    }

    /**
     * Worker count for one budget level: an explicit number, or the per-level calibrated knee.
     *
     * <p>The knee is checkpointed per level ({@code _tuned_threads_full_<cu>cu.txt}) so a resumed run
     * does not re-sweep. Tree size grows with the budget, so the best thread count at 400,000 units is
     * not the one at 40,000 and each level is calibrated separately.
     */
    private static int resolveThreads(String arg, int cu, File calibDir,
                                      List<FullGameMapSetV6.MapInfo> maps) throws Exception {
        if (!arg.equalsIgnoreCase("auto")) {
            int t = Integer.parseInt(arg.trim());
            System.out.println("  threads: " + t + " (explicit)");
            return t;
        }
        File tuned = new File(calibDir, "_tuned_threads_full_" + cu + "cu.txt");
        if (tuned.isFile()) {
            try {
                int t = Integer.parseInt(new String(Files.readAllBytes(tuned.toPath())).trim());
                if (t > 0) {
                    System.out.println("  threads: " + t + " (calibrated earlier, " + tuned.getName() + ")");
                    return t;
                }
            } catch (Exception e) {
                System.err.println("  could not read " + tuned.getName() + " (" + e + "); re-calibrating");
            }
        }
        System.out.println("  calibrating thread count for this level...");
        ThreadCalibrationV6Full.Result r = ThreadCalibrationV6Full.calibrate(
                cu, maps, null, new File(calibDir, "thread_calibration_full.csv"));
        RunGuard.atomicWrite(tuned, String.valueOf(r.chosen));
        return r.chosen;
    }

    /** Everything needed to interpret the dataset without reading the source. */
    private static String config(int[] cuLevels, List<FullGameMapSetV6.MapInfo> maps,
                                 ServerProfile machine) {
        StringBuilder sb = new StringBuilder();
        sb.append("v6 FULL GAMES - tournament phase\n");
        sb.append("mode=CAPITALS turnLimit=").append(TURN_LIMIT)
          .append(" gamesPerMatchup=").append(FullGameRunnerV6.GAMES_PER_MATCHUP)
          .append(" maps=").append(FullGameMapSetV6.NUM_MAPS)
          .append(" gamesPerMap=").append(FullGameRunnerV6.GAMES_PER_MAP)
          .append(" perOrientation=").append(FullGameRunnerV6.GAMES_PER_ORIENTATION).append('\n');
        sb.append("tribes=").append(FullGameMapSetV6.TRIBES[0]).append(" vs ")
          .append(FullGameMapSetV6.TRIBES[1]).append(" (mirror pair)\n");
        sb.append("budget=STOP_COMPUTE (search only, after a common greedy init) levels=")
          .append(java.util.Arrays.toString(cuLevels)).append('\n');
        sb.append("computeWeights=").append(ComputeMeter.weightsSummary()).append('\n');
        sb.append("ratios=").append(java.util.Arrays.toString(FullGameRunnerV6.RATIOS)).append('\n');
        sb.append("opponents=CrossoverBB,CrossoverExpansion@ratio,CrossoverCombo@ratio (no self-play control)\n");
        sb.append("seedGrid: every seed is scramble(structuredKey) - SplitMix64 finalizer, because\n")
          .append("  java.util.Random is correlated across nearby seeds. levelSeed=scramble(")
          .append(FullGameMapSetV6.LEVEL_SEED_BASE).append("+mapIndex), gameSeed=scramble(")
          .append(FullGameRunnerV6.GAME_SEED_BASE).append("+map*1000+pair), agentSeed=scramble(")
          .append(FullGameRunnerV6.AGENT_SEED_BASE)
          .append("+map*1000+pair[+seat1Offset]); identical across matchups and budgets\n");
        sb.append("mirrorPairs: (map,pair) x {orientation 0,1} share every seed; only the seating differs\n");
        sb.append("maps: ").append(FullGameMapSetV6.summary(maps)).append('\n');
        sb.append("engine: REMOVE_FISHING=true (water-free maps), VISUALS=false, TURN_TIME_LIMITED=false\n");
        sb.append("machine: ").append(machine.summary()).append('\n');
        sb.append("java: ").append(System.getProperty("java.version"))
          .append("  maxHeapMB=").append(Runtime.getRuntime().maxMemory() >> 20).append('\n');
        return sb.toString();
    }
}
