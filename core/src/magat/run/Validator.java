package magat.run;

import magat.agent.AgentRegistry;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Split;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks an experiment before anything runs, so a flaw is found before the data exists rather than in
 * the analysis:
 * <ul>
 *   <li>every arm registered, and priced at every level it runs (an unpriced arm spends another currency), for every
 *       operation it declares, not only the forward model's;</li>
 *   <li>the baseline in the grid, at two levels or more (budget multipliers need a curve; a warning);</li>
 *   <li>every contrast names arms in the grid.</li>
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

        // ---- levels and arms
        if (x.levels.isEmpty()) r.errors.add("no levels");
        Set<Long> budgets = new LinkedHashSet<>();
        for (Experiment.Level l : x.levels) {
            if (l.budget <= 0) r.errors.add("a level has no positive budget");
            if (l.visits < 1) r.errors.add("level " + l.budget + " has no visits");
            if (!budgets.add(l.budget)) r.errors.add("level " + l.budget + " is listed twice");
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
                for (String a : l.arms) if (!grid.contains(a)) r.errors.add("level " + l.budget + " runs " + a + ", which is not in the arms list");

        // ---- pricing
        if (weights == null) {
            r.warnings.add("no weights given: pricing not checked");
        } else {
            List<String> unpriced = new ArrayList<>(), unpricedOps = new ArrayList<>();
            for (Experiment.Level l : x.levels)
                for (String a : x.armsAt(l)) {
                    if (!registry.has(a) || !registry.get(a).priced()) continue;
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
}
