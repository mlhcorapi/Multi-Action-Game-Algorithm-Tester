package magat.calibrate;

import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.game.Scenario;
import magat.machine.MachineProfile;
import magat.meter.Meter;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.run.RunGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Measures what an advance, a copy and an evaluation cost for each {@code (arm, budget)} cell,
 * relative to one reference operation, and says whether the measurement can be trusted.
 *
 * <p><b>Why per cell.</b> Operations with the same names cost different amounts for different arms
 * (they reach different states) and at different budgets (a ten-times larger tree stops fitting in
 * cache: the thesis measured the top level at 26.7x the wall-clock of the bottom one instead of 10x).
 * Under one global weight set a nominal budget buys a different amount of compute for each arm, which
 * is the confound the budget exists to remove; per-cell pricing mis-allocated up to 73% less.
 *
 * <p><b>Method</b> (the thesis calibrator's, unchanged): every cell is measured on the same portfolio
 * positions with the same seeds; warm-up once per budget level and a collection between levels; one
 * discarded profiled turn per cell; per repetition, the same turns again, nanoseconds reset per turn
 * and counts taken from the turn's own record (initialization plus search, the span the nanoseconds
 * cover); the {@link Estimator fastest repetition} with the two-fastest gap gate, {@code MIN_OPS}, a
 * plausibility check, and the load average checked only when the machine exposes it. On any failure
 * no weights are written, and the measurements go to {@code compute_profiles.csv} regardless.
 *
 * <p>Single-threaded on an idle machine, because this is a timing measurement: other load inflates
 * copies and evaluations (memory-bound) more than advances, which is precisely the ratio measured.
 * {@code --under-load W} measures the other regime on purpose and writes beside, never over.
 *
 * <p>Writing into an existing file merges (see {@link Currency}); {@code --check} measures and
 * reports drift against a file and writes nothing.
 */
public final class Calibrator {

    /** Size of the position pool; levels that run fewer turns take a spread subset of the same pool. */
    public static final int POSITION_POOL = 8;
    /** Per level: the zero-evaluation arms make four times fewer copies, so the bottom level needs eight turns. */
    public static final String DEFAULT_TURNS = "8,4,2";
    public static final int DEFAULT_REPS = 3;
    public static final int DEFAULT_WARMUP = 6;
    public static final long MIN_OPS = 20_000;
    public static final double MAX_GAP = 0.10;
    public static final double MAX_LOAD_PER_CORE = 0.35;

    private Calibrator() {}

    public static final class Options {
        public long[] levels = {40_000L, 80_000L, 400_000L};
        /** Arm ids to price; null means every registered arm whose budget buys search. */
        public List<String> arms;
        public int reps = DEFAULT_REPS;
        /** Turns per repetition per level; null takes the file's, else {@link #DEFAULT_TURNS}. */
        public String turns;
        public int warmup = DEFAULT_WARMUP;
        public int underLoad = 0;
        public Set<String> replace = new LinkedHashSet<>();
        /** Measure and compare against {@code file}, write nothing. */
        public boolean check;
        /** Report the sample-size and stability gates without enforcing them; mark the file. */
        public boolean toy;
    }

    public static final class Result {
        public boolean pass;
        public Path written;
        public Currency.Drift drift;
        public final List<Cell> cells = new ArrayList<>();
    }

    /**
     * @param referenceArm the game's reference arm, used when there is no file to extend
     * @param out          the calibration directory ({@code compute_weights.properties} is extended if present)
     * @param checkFile    with {@code o.check}: the weights file to compare against
     */
    public static <S, A> Result run(Game<S, A> game, AgentRegistry<S, A> registry, String referenceArm,
                                    Portfolio<S> portfolio, Path out, Path checkFile, Options o) throws IOException {
        Path idle = out == null ? null : out.resolve(Currency.WEIGHTS_FILE);
        Path existingPath = o.check ? checkFile : (idle != null && Files.isRegularFile(idle) ? idle : null);
        if (o.underLoad > 1) existingPath = null;         // a loaded run writes its own file beside the idle one
        Weights existing = existingPath == null ? null : Weights.load(existingPath);
        if (o.check && existing == null) throw new IllegalArgumentException("--check needs --weights <file>");

        // ---- what to measure, and the refusals that must happen before hours of measuring
        if (existing != null && existing.game() != null && !existing.game().equals(game.id()))
            throw new IllegalStateException(existingPath + " prices game '" + existing.game() + "', not '" + game.id() + "'");
        if (existing != null && existing.portfolioIdentity() != null && !existing.portfolioIdentity().equals(portfolio.identity()))
            throw new IllegalStateException(existingPath + " was measured on portfolio " + existing.portfolioIdentity()
                    + "; this portfolio is " + portfolio.identity() + ". Turns-per-rep selects positions out of a portfolio, so "
                    + "a calibration on another one measures different material.");
        String reference = existing != null && existing.reference() != null ? existing.reference()
                : Profile.key(referenceArm, Arrays.stream(o.levels).min().orElse(40_000L));
        String refArm = Profile.arm(reference);
        long refBudget = Profile.budget(reference);
        if (!registry.has(refArm)) throw new IllegalStateException("the reference arm '" + refArm + "' is not registered");

        List<String> arms = new ArrayList<>();
        if (o.arms != null) arms.addAll(o.arms);
        else for (AgentFactory<S, A> f : registry.all()) if (f.priced()) arms.add(f.id());
        for (String a : arms) if (!registry.has(a)) throw new IllegalArgumentException("unknown arm '" + a + "'; registered: " + registry.ids());

        TreeSet<Long> levelSet = new TreeSet<>();
        for (long l : o.levels) levelSet.add(l);
        String levelsHeader = existing != null && existing.header().get("levels") != null ? existing.header().get("levels") : join(levelSet);
        String turnsSpec = o.turns != null ? o.turns
                : existing != null && existing.turnsPerRep() != null ? existing.turnsPerRep() : DEFAULT_TURNS;
        if (existing != null && existing.turnsPerRep() != null && !existing.turnsPerRep().equals(turnsSpec))
            throw new IllegalStateException("turns per repetition " + turnsSpec + " differ from the " + existing.turnsPerRep()
                    + " recorded in " + existingPath + ". Turns-per-rep selects which positions each level measures, so the two "
                    + "would measure different material (the 55% phantom drift). Leave --turns unset.");
        long[] turnLevels = parseLongs(levelsHeader);
        int[] turns = parseInts(turnsSpec);

        List<Cell> cells = new ArrayList<>();
        Cell ref = null;
        TreeSet<Long> all = new TreeSet<>(levelSet);
        all.add(refBudget);
        for (long level : all) {
            if (level == refBudget || levelSet.contains(level)) {
                Cell c = new Cell(refArm, level);
                cells.add(c);
                if (level == refBudget) ref = c;
            }
            if (!levelSet.contains(level)) continue;
            for (String a : arms) if (!a.equals(refArm)) cells.add(new Cell(a, level));
        }

        List<Cell> toWrite = new ArrayList<>();
        if (!o.check && o.underLoad <= 1) {
            for (Cell c : cells) {
                boolean present = existing != null && existing.has(c.profile);
                boolean requested = arms.contains(c.arm);
                if (existing == null) toWrite.add(c);
                else if (present && o.replace.contains(c.arm)) toWrite.add(c);
                else if (present && requested && c != ref)
                    throw new IllegalStateException(c.profile + " is already priced in " + existingPath + ". Re-pricing it would "
                            + "change the currency of every leaf already run under it; name it in --replace to do so on purpose.");
                else if (!present && c != ref) toWrite.add(c);
            }
            if (existing != null && o.toy && !"true".equals(existing.header().get("toy")))
                throw new IllegalStateException("a toy calibration may not merge into a real currency (" + existingPath + ")");
        }

        // ---- banner
        long gapLimitPct = Math.round(100 * (o.underLoad > 1 ? 2 * MAX_GAP : MAX_GAP));
        System.out.println("############################################################");
        System.out.println("# compute-unit calibration: per arm, per budget level, relative to one reference advance");
        System.out.println(o.underLoad > 1
                ? "# UNDER LOAD: each cell is timed on one thread while " + (o.underLoad - 1) + " others run the same arm."
                : "# single-threaded; needs an idle machine: this is a timing measurement");
        if (o.toy) System.out.println("# TOY: sample-size and stability gates are reported, not enforced; the file is marked toy");
        System.out.println("# " + (o.check ? "checking against " : existing != null ? "extending " : "new currency in ")
                + (existingPath != null ? existingPath : idle));
        System.out.println("# reference cell " + reference + "   levels " + join(levelSet) + "   arms " + arms);
        System.out.println("# reps " + o.reps + "   turns/rep " + turnsSpec + " over levels " + levelsHeader + "   warmup " + o.warmup
                + "   gap gate " + gapLimitPct + "%   min ops " + MIN_OPS + (o.toy ? " (reported, not enforced)" : ""));
        System.out.println("# portfolio " + portfolio.identity() + " (" + portfolio.size() + " positions)");
        if (!toWrite.isEmpty() && existing != null)
            System.out.println("# will write " + toWrite.size() + " profile(s): " + profilesOf(toWrite));
        MachineProfile.get().printBanner("calibration");
        double loadBefore = MachineProfile.loadPerCore();
        if (loadBefore >= 0)
            System.out.println(String.format(Locale.ROOT, "# load average before starting: %.2f per core%s", loadBefore,
                    loadBefore > MAX_LOAD_PER_CORE ? "   <-- MACHINE IS BUSY" : "   (idle)"));

        // ---- measure
        Weights previous = Meter.installed();
        Meter.install(existing);                    // cells run under their stored profile when priced, else the global weights
        Meter.allowUnpriced(true);
        LoadHarness.WORK_TURNS.set(0);
        long t0 = System.currentTimeMillis();
        // Toy runs apply the same gates; only the verdict treats a failed cell as reported rather than fatal.
        long minOps = MIN_OPS;
        double gapLimit = o.underLoad > 1 ? 2 * MAX_GAP : MAX_GAP;
        try {
            long warmed = -1;
            int done = 0;
            List<Scenario<S>> scenarios = portfolio.scenarios();
            for (Cell c : cells) {
                if (RunGuard.stopRequested()) throw new IllegalStateException("stopped; nothing written");
                AgentFactory<S, A> arm = registry.get(c.arm);
                if (c.budget != warmed) {
                    warmed = c.budget;
                    System.out.println(String.format(Locale.ROOT, "%n[calibrate] warm-up for level %d: %d turns (discarded)", c.budget, o.warmup));
                    AgentFactory<S, A> refFactory = registry.get(refArm);
                    Meter.PROFILE = true;          // warm the timed code path, not the untimed one
                    for (int i = 0; i < o.warmup; i++)
                        Episode.run(game, positionFor(scenarios, i % POSITION_POOL, POSITION_POOL), 9_000 + i, refFactory, c.budget);
                    Meter.PROFILE = false;
                    System.gc();                   // each level starts from a comparable heap
                }
                int n = turnsFor(c.budget, turnLevels, turns);
                LoadHarness load = o.underLoad > 1 ? LoadHarness.start(game, arm, c.budget, scenarios, o.underLoad - 1) : null;
                try {
                    measure(game, arm, c, scenarios, o.reps, n, minOps, gapLimit);
                } finally {
                    if (load != null) load.stop();
                }
                done++;
                System.out.println(String.format(Locale.ROOT,
                        "  [%2d/%2d] %-34s advance %8.1f ns  copy %8.1f ns  eval %8.1f ns   (gap %.1f/%.1f/%.1f%% | CV %.1f/%.1f/%.1f%%)%s",
                        done, cells.size(), c.profile, c.tAdvance, c.tCopy, c.tEval,
                        100 * c.gapAdvance, 100 * c.gapCopy, 100 * c.gapEval, 100 * c.cvAdvance, 100 * c.cvCopy, 100 * c.cvEval,
                        c.failures.isEmpty() ? "" : "   <-- " + c.failures.get(0)));
            }
        } finally {
            Meter.PROFILE = false;
            Meter.allowUnpriced(false);
            Meter.install(previous);
        }
        System.out.println(String.format(Locale.ROOT, "  measured in %.1f min", (System.currentTimeMillis() - t0) / 60000.0));

        Result result = new Result();
        result.cells.addAll(cells);
        if (ref == null || ref.tAdvance <= 0) {
            System.out.println("\nVERDICT: FAIL - the reference cell " + reference + " produced no usable timing; nothing written.");
            return result;
        }
        for (Cell c : cells) c.normalise(ref.tAdvance);
        report(cells, ref);

        // ---- is this a fair benchmark?
        List<String> passes = new ArrayList<>(), failures = new ArrayList<>();
        int bad = 0;
        for (Cell c : cells) if (!c.failures.isEmpty()) bad++;
        if (bad == 0) passes.add("every cell measured cleanly (" + cells.size() + " cells)");
        else if (o.toy) passes.add("TOY: " + bad + " of " + cells.size() + " cells failed their own checks (reported, not enforced)");
        else failures.add(bad + " of " + cells.size() + " cells failed their own checks (listed above)");
        boolean plausible = true;
        for (Cell c : cells) if (!c.plausible()) plausible = false;
        if (plausible) passes.add("weights are plausible in every cell");
        else failures.add("at least one cell produced an implausible weight: a broken or mis-attributed timer");
        double loadAfter = MachineProfile.loadPerCore();
        if (o.underLoad > 1) {
            long work = LoadHarness.WORK_TURNS.get(), want = (long) cells.size() * (o.underLoad - 1);
            if (work >= want)
                passes.add(String.format(Locale.ROOT, "load harness completed %,d turns beside the measured cells (OS load %.2f per core, informational)", work, loadAfter));
            else
                failures.add(String.format(Locale.ROOT, "load harness completed only %,d turns (wanted >= %,d): the machine was not reliably loaded", work, want));
        } else if (loadAfter >= 0) {
            double limit = MAX_LOAD_PER_CORE + 1.0 / Math.max(1, MachineProfile.get().logicalCpus());
            String busy = String.format(Locale.ROOT, "load average %.2f per core: something else was running; timings are inflated unevenly", loadAfter);
            if (loadAfter <= limit) passes.add(String.format(Locale.ROOT, "machine was idle (load %.2f per core, this job included)", loadAfter));
            else if (o.toy) passes.add("TOY: " + busy + " (reported, not enforced)");
            else failures.add(busy);
        } else {
            passes.add("load average not exposed by this OS (not checked)");
        }
        System.out.println("\n=== IS THIS A FAIR BENCHMARK? ===");
        for (String s : passes) System.out.println("  [ok]   " + s);
        for (String s : failures) System.out.println("  [FAIL] " + s);
        boolean fair = failures.isEmpty();

        // ---- check: compare and write nothing
        if (o.check) {
            System.out.println("\n=== CURRENCY DRIFT: THIS MACHINE vs " + existingPath.getFileName() + " ===");
            result.drift = Currency.drift(existing, cells, ref, Currency.DRIFT_GATE);
            result.drift.print();
            result.pass = fair && result.drift.pass();
            System.out.println(result.pass ? "\n  VERDICT: PASS - this machine still spends the currency the stored weights define."
                    : "\n  VERDICT: FAIL - " + (fair ? "the drift is past the gate; arms priced then and now do not spend the same "
                    + "currency. Do not start a stage quoted beside earlier ones until this is understood." : "the measurement itself failed."));
            System.out.println("  (nothing was written; this run only compared)");
            return result;
        }

        Currency.Session s = new Currency.Session();
        s.game = game.id();
        s.machine = MachineProfile.get().describe();
        s.levels = levelsHeader;
        s.turnsPerRep = turnsSpec;
        s.portfolioIdentity = portfolio.identity();
        s.reps = o.reps;
        s.toy = o.toy;
        Path weightsFile = o.underLoad > 1 ? out.resolve("compute_weights_load" + o.underLoad + ".properties") : idle;
        Path csv = out.resolve(o.underLoad > 1 ? "compute_profiles_load" + o.underLoad + ".csv" : Currency.PROFILES_CSV);
        RunGuard.atomicWrite(csv, Currency.profilesCsv(cells, ref));
        if (o.underLoad > 1 && idle != null && Files.isRegularFile(idle)) reportLoadVsIdle(cells, ref, Weights.load(idle), o.underLoad);
        if (!fair) {
            System.out.println("\n  VERDICT: FAIL - weights NOT written; the currency in force is unchanged.");
            System.out.println("  The measurements are in " + csv + ". The usual cause is other load on the machine.");
            return result;
        }
        String text;
        if (existing != null) {
            List<String> lines = Files.readAllLines(existingPath, StandardCharsets.UTF_8);
            text = Currency.merge(lines, existing, s, toWrite, ref);
            System.out.println(String.format(Locale.ROOT, "%n  [merge] kept the currency of %s; wrote %d profile(s) re-denominated x%.6f "
                    + "(this session's reference advance is x%.4f the file's: machine speed, which cancels)",
                    existingPath.getFileName(), toWrite.size(), Currency.factor(existing), Currency.machineFactor(existing, ref)));
        } else {
            text = Currency.fresh(s, cells, ref);
        }
        RunGuard.atomicWrite(weightsFile, text);
        result.pass = true;
        result.written = weightsFile;
        System.out.println("\n  VERDICT: PASS" + (o.toy ? " (TOY)" : " - calibration is trustworthy."));
        System.out.println("  Weights  -> " + weightsFile);
        System.out.println("  Measured -> " + csv);
        return result;
    }

    /**
     * One cell: a discarded profiled turn, then {@code reps} repetitions of the same {@code turns}
     * turns. Nanoseconds are reset before each turn and counts come from that turn's record, so both
     * describe exactly one planning episode (reading the live counters after a loop of turns would
     * divide every turn's nanoseconds by the last turn's search count).
     */
    private static <S, A> void measure(Game<S, A> game, AgentFactory<S, A> arm, Cell c, List<Scenario<S>> scenarios,
                                       int reps, int turns, long minOps, double gapLimit) {
        Meter.PROFILE = true;
        Episode.run(game, positionFor(scenarios, 0, turns), 9_500, arm, c.budget);
        Meter.PROFILE = false;
        for (int rep = 0; rep < reps; rep++) {
            long adv = 0, cop = 0, ev = 0, nsA = 0, nsC = 0, nsE = 0;
            for (int i = 0; i < turns; i++) {
                Scenario<S> sc = positionFor(scenarios, i, turns);
                Meter.resetNanos();
                Meter.PROFILE = true;
                // visit 0 in every repetition: a repetition must repeat, or the spread stops describing the machine
                TurnRecord r = Episode.run(game, sc, 0, arm, c.budget);
                Meter.PROFILE = false;
                adv += r.initAdvances + r.advances;
                cop += r.initCopies + r.copies;
                ev += r.initEvals + r.evals;
                nsA += Meter.advanceNanos();
                nsC += Meter.copyNanos();
                nsE += Meter.evalNanos();
            }
            c.advances += adv;
            c.copies += cop;
            c.evals += ev;
            c.perRep.add(new double[]{adv > 0 ? nsA / (double) adv : 0, cop > 0 ? nsC / (double) cop : 0, ev > 0 ? nsE / (double) ev : 0});
        }
        c.positions = turns;
        c.summarise(minOps, gapLimit);
    }

    /**
     * The position for turn {@code i} of {@code turns}: a slot of a fixed pool independent of the
     * budget, so every level measures the same positions (a weight means its ratio to the reference
     * cell, which sits at another level); the stride spreads the pool across the portfolio.
     */
    static <S> Scenario<S> positionFor(List<Scenario<S>> portfolio, int i, int turns) {
        int slot = (int) ((long) i * POSITION_POOL / Math.max(1, turns));
        return portfolio.get((int) (((long) slot * 37) % portfolio.size()));
    }

    /** Turns at {@code budget}: its entry when listed, else the entry of the nearest listed level below it. */
    static int turnsFor(long budget, long[] levels, int[] turns) {
        int idx = 0;
        for (int i = 0; i < levels.length; i++) if (levels[i] <= budget) idx = i;
        return turns[Math.min(idx, turns.length - 1)];
    }

    // ------------------------------------------------------------------ reporting

    private static void report(List<Cell> cells, Cell ref) {
        System.out.println(String.format(Locale.ROOT, "%n=== RESULT: weights relative to one advance of %s = %.1f ns ===", ref.profile, ref.tAdvance));
        System.out.println(String.format(Locale.ROOT, "  %-34s %10s %10s %10s %12s", "profile", "wAdvance", "wCopy", "wEval", "ns/advance"));
        for (Cell c : cells)
            System.out.println(String.format(Locale.ROOT, "  %-34s %10.4f %10.4f %10.4f %12.1f", c.profile, c.wAdvance, c.wCopy, c.wEval, c.tAdvance));

        System.out.println("\n=== DOES AN OPERATION COST THE SAME FOR EVERY ARM? ===");
        TreeSet<Long> levels = new TreeSet<>();
        for (Cell c : cells) levels.add(c.budget);
        for (long level : levels) {
            Cell lo = null, hi = null;
            for (Cell c : cells) {
                if (c.budget != level || c.tAdvance <= 0) continue;
                if (lo == null || c.tAdvance < lo.tAdvance) lo = c;
                if (hi == null || c.tAdvance > hi.tAdvance) hi = c;
            }
            if (lo != null)
                System.out.println(String.format(Locale.ROOT, "  %8d  cheapest advance %s %.1f ns, dearest %s %.1f ns: spread %.1f%%",
                        level, lo.arm, lo.tAdvance, hi.arm, hi.tAdvance, 100 * (hi.tAdvance / lo.tAdvance - 1)));
        }
        System.out.println("\n  The reference arm across levels:");
        for (Cell c : cells)
            if (c.arm.equals(ref.arm))
                System.out.println(String.format(Locale.ROOT, "    %8d units: %8.1f ns/advance (%.2fx the reference cell)", c.budget, c.tAdvance, c.tAdvance / ref.tAdvance));

        System.out.println("\n=== WHAT ONE GLOBAL WEIGHT SET WOULD MIS-ALLOCATE ===");
        System.out.println("  Wall-clock 1,000 nominal units buy under the fixed weights 1/0.16/0.077, given each cell's own operation mix.");
        for (long level : levels) {
            double lo = Double.MAX_VALUE, hi = 0;
            String loName = "", hiName = "";
            for (Cell c : cells) {
                if (c.budget != level || c.advances == 0) continue;
                double oldUnits = c.advances * Weights.DEFAULT[0] + c.copies * Weights.DEFAULT[1] + c.evals * Weights.DEFAULT[2];
                double nanos = c.advances * c.tAdvance + c.copies * c.tCopy + c.evals * c.tEval;
                double ms = oldUnits > 0 ? (1000.0 * nanos / oldUnits) / 1e6 : 0;
                if (ms < lo) { lo = ms; loName = c.profile; }
                if (ms > hi) { hi = ms; hiName = c.profile; }
            }
            if (hi > 0)
                System.out.println(String.format(Locale.ROOT, "  %8d  %.3f to %.3f ms: %.1f%% unfair (%s vs %s)",
                        level, lo, hi, 100 * (hi / lo - 1), loName, hiName));
        }
    }

    /** Gate A3': do the per-arm ratios hold under load? Both sides in the idle file's unit. */
    private static void reportLoadVsIdle(List<Cell> cells, Cell ref, Weights idle, int underLoad) {
        System.out.println("\n=== PER-ARM WEIGHT RATIOS, IDLE vs " + underLoad + " THREADS ===");
        double f = Currency.factor(idle);
        double worst = 0;
        String where = "";
        int compared = 0;
        for (Cell c : cells) {
            double[] w = idle.profile(c.profile);
            if (w == null || w[0] <= 0) continue;
            double change = c.wAdvance * f / w[0] - 1;
            compared++;
            if (Math.abs(change) > Math.abs(worst)) { worst = change; where = c.profile; }
            System.out.println(String.format(Locale.ROOT, "  %-34s idle %8.4f  loaded %8.4f  %+6.1f%%", c.profile, w[0], c.wAdvance * f, 100 * change));
        }
        if (compared == 0) { System.out.println("  no overlapping profiles"); return; }
        System.out.println(String.format(Locale.ROOT, "  largest move %.1f%% (%s) over %d profiles", 100 * worst, where, compared));
        System.out.println(Math.abs(worst) <= MAX_GAP
                ? "  VERDICT: ratios HOLD under load; the idle weights are the currency, wall-clock (W7) a check."
                : "  VERDICT: ratios MOVE under load; report every headline in units AND seconds (W7 co-primary).");
    }

    // ------------------------------------------------------------------ helpers

    private static String join(Set<Long> s) {
        StringBuilder sb = new StringBuilder();
        for (long l : s) { if (sb.length() > 0) sb.append(','); sb.append(l); }
        return sb.toString();
    }

    private static String profilesOf(List<Cell> cells) {
        List<String> out = new ArrayList<>();
        for (Cell c : cells) out.add(c.profile);
        return out.toString();
    }

    static long[] parseLongs(String csv) {
        String[] p = csv.split(",");
        long[] out = new long[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Long.parseLong(p[i].trim());
        return out;
    }

    static int[] parseInts(String csv) {
        String[] p = csv.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        return out;
    }
}
