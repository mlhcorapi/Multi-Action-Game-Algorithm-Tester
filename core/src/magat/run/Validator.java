package magat.run;

import magat.agent.AgentRegistry;
import magat.game.Scenario;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Split;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks an experiment before anything runs, so a flaw is found before the data exists rather than in
 * the analysis:
 * <ul>
 *   <li>every arm registered, and priced at every level it runs (an unpriced arm spends another currency), for every
 *       operation it declares, not only the forward model's;</li>
 *   <li>the baseline in the grid, at two levels or more (budget multipliers need a curve; a warning);</li>
 *   <li>every contrast names arms in the grid;</li>
 *   <li>given the portfolio, the positions it selects: sources it has, ids of the split being run, and at
 *       least one position left ({@link #checkSelection}).</li>
 * </ul>
 */
public final class Validator {

    private Validator() {}

    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() { return errors.isEmpty(); }

        public void print() {
            for (String w : warnings) System.out.println("  [warn]  " + w);
            for (String e : errors) System.out.println("  [ERROR] " + e);
        }
    }

    /**
     * @param split   the split the run will read (the CLI's, else the spec's)
     * @param weights the currency the run will spend; null skips the pricing rule with a warning
     * @param toyRun  the run is declared a toy (shakedown): toy weights are then expected, not warned about
     */
    public static <S, A> Report check(Experiment x, String game, AgentRegistry<S, A> registry, Split.Half split,
                                      Weights weights, boolean toyRun) {
        Report r = new Report();
        if (x.name == null || x.name.trim().isEmpty()) r.errors.add("the spec has no name");
        else if (!x.name.matches("[a-z0-9][a-z0-9-]*")) r.errors.add("name '" + x.name + "' must be lower case kebab-case (it names directories)");
        if (x.game == null) r.errors.add("the spec names no game");
        else if (!x.game.equals(game)) r.errors.add("the spec is for game '" + x.game + "', not '" + game + "'");
        for (String k : x.unknownKeys) r.warnings.add("unknown key '" + k + "' (ignored; a typo?)");
        r.errors.addAll(x.problems);

        // ---- levels and arms
        if (x.levels.isEmpty()) r.errors.add("no levels");
        Set<Long> budgets = new LinkedHashSet<>();
        for (Experiment.Level l : x.levels) {
            if (l.budget <= 0) continue;                              // a problem the spec's reading already reported
            if (l.visits < 1) r.errors.add("level " + l.written + " has no repetitions: give it \"repetitions\", or the spec a "
                    + "\"repetitions\" every level takes");
            if (!budgets.add(l.budget)) r.errors.add("level " + l.written + " (" + l.budget + " units) is listed twice");
        }
        if (x.arms.isEmpty()) r.errors.add("no arms");
        Set<String> grid = new LinkedHashSet<>();
        for (Experiment.Arm a : x.arms) {
            if (a.id == null) { r.errors.add("an arm has no id"); continue; }
            if (!grid.add(a.id)) r.errors.add("arm " + a.id + " is listed twice");
            if (!registry.has(a.id)) r.errors.add("arm " + a.id + " is not registered for " + game + " (registered: " + registry.ids() + ")");
        }
        for (Experiment.Level l : x.levels)
            if (l.arms != null)
                for (String a : l.arms) if (!grid.contains(a)) r.errors.add("level " + l.written + " runs " + a + ", which is not in the arms list");

        // ---- pricing
        if (weights == null) {
            r.warnings.add("no weights given: pricing not checked");
        } else {
            List<String> unpriced = new ArrayList<>(), unpricedOps = new ArrayList<>();
            for (Experiment.Level l : x.levels)
                for (String a : x.armsAt(l)) {
                    if (l.budget <= 0 || !registry.has(a) || !registry.get(a).priced()) continue;
                    String p = Profile.key(a, l.budget);
                    if (!weights.has(p)) { unpriced.add(p); continue; }
                    for (String op : weights.unpricedOperations(p, registry.get(a).operations())) unpricedOps.add(p + "." + op);
                }
            if (!unpriced.isEmpty())
                r.errors.add("not priced in " + weights.source() + ": " + unpriced + ". Calibrate them (magat calibrate --arms ...) first: "
                        + "an unpriced arm would spend a different currency and nothing in a results file would say so");
            if (!unpricedOps.isEmpty())
                r.errors.add("operations not priced in " + weights.source() + " (a currency prices every operation an arm declares): "
                        + unpricedOps + ". Re-calibrate those arms (--replace): an unpriced operation would be free in one arm's budget "
                        + "and paid for in another's");
            if ("true".equals(weights.header().get("toy")) && !toyRun)
                r.warnings.add("the weights are a TOY calibration, not a currency");
        }

        // ---- baseline
        if (x.baseline == null) r.errors.add("no baseline: every contrast and budget multiplier is against it");
        else if (!grid.contains(x.baseline)) r.errors.add("the baseline " + x.baseline + " is not in the arms list");
        else if (x.levelsOf(x.baseline).size() < 2)
            r.warnings.add("the baseline " + x.baseline + " runs at " + x.levelsOf(x.baseline).size()
                    + " level(s); budget multipliers need its value-versus-budget curve at two or more");

        // ---- contrasts
        for (Experiment.Contrast c : x.contrasts) {
            if (!grid.contains(c.a)) r.errors.add("contrast '" + c.label + "' names " + c.a + ", which is not in the grid");
            if (!grid.contains(c.b)) r.errors.add("contrast '" + c.label + "' names " + c.b + ", which is not in the grid");
        }
        return r;
    }

    /**
     * The positions a spec selects, against the portfolio: every source it keeps exists, every id it keeps
     * exists and belongs to the split being run (a filter never reaches across the split: the holdout is
     * read once, as a whole half), and something is left. A count above what is left is a warning: all of it
     * runs.
     *
     * @param all   every scenario of the portfolio
     * @param split the scenarios of the half the run reads
     */
    public static <S> void checkSelection(Experiment x, List<Scenario<S>> all, List<Scenario<S>> split, Split.Half half, Report r) {
        if (!x.selects()) return;
        Set<String> sources = new TreeSet<>();
        Set<Integer> phases = new TreeSet<>(), inPortfolio = new TreeSet<>(), inSplit = new TreeSet<>();
        for (Scenario<S> s : all) { sources.add(s.source); phases.add(s.phase); inPortfolio.add(s.id); }
        for (Scenario<S> s : split) inSplit.add(s.id);
        if (x.sources != null)
            for (String s : x.sources)
                if (!sources.contains(s)) r.errors.add("source '" + s + "' is not in the portfolio; its sources are " + sources);
        if (x.phases != null)
            for (Integer p : x.phases)
                if (!phases.contains(p)) r.warnings.add("phase " + p + " is not in the portfolio; its phases are " + phases);
        if (x.ids != null)
            for (Integer id : x.ids) {
                if (!inPortfolio.contains(id)) r.errors.add("scenario " + id + " is not in the portfolio");
                else if (!inSplit.contains(id))
                    r.errors.add("scenario " + id + " is not in the " + half.key() + " half: a selection never reaches across the split");
            }
        if (!r.ok()) return;
        int kept = x.select(split).size(), filtered = filtered(x, split).size();
        if (kept == 0) r.errors.add("the selection (" + x.selection() + ") leaves no position of the " + half.key() + " half");
        else if (x.scenarios > filtered)
            r.warnings.add("scenarios asks for " + x.scenarios + " positions; the selection has " + filtered + ", and all of them run");
    }

    /** The scenarios the filters keep, before any count spreads them. */
    private static <S> List<Scenario<S>> filtered(Experiment x, List<Scenario<S>> split) {
        List<Scenario<S>> out = new ArrayList<>();
        for (Scenario<S> s : split) if (x.keeps(s)) out.add(s);
        return out;
    }

    /** {@link #checkSelection} on a portfolio's split, read from its directory. */
    public static <S> void checkSelection(Experiment x, List<Scenario<S>> all, Path portfolioDir, Split.Half half, Report r)
            throws IOException {
        if (x.selects()) checkSelection(x, all, Split.apply(all, portfolioDir, half), half, r);
    }
}
