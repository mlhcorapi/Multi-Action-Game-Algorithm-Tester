package magat.run;

import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Split;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks an experiment before anything runs. Each rule is a design principle of the brief made
 * structural, so a flaw is found before the data exists rather than in the analysis:
 * <ul>
 *   <li>every arm registered, and priced at every level it runs (an unpriced arm spends another currency); under a
 *       version-2 currency, priced for every operation it declares, not only the forward model's;</li>
 *   <li>the baseline in the grid at two levels or more (budget multipliers need a curve): an error on the
 *       holdout, a warning on development;</li>
 *   <li>every parameter sweep names its null and the null is in the grid (principle 5: the AB-EMCTS
 *       champion sat at the null, outside the grid, and was found by accident);</li>
 *   <li>every arm with a mechanism has its attribution control in the grid (principle 6): an error on
 *       the holdout, a warning on development;</li>
 *   <li>the holdout requires a frozen-choices file.</li>
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
     * @param frozen  the frozen-choices file given on the command line, or null
     * @param toyRun  the run is declared a toy (shakedown): toy weights are then allowed on the holdout
     */
    public static <S, A> Report check(Experiment x, String game, AgentRegistry<S, A> registry, Split.Half split,
                                      Weights weights, String frozen, boolean toyRun) {
        Report r = new Report();
        boolean holdout = split == Split.Half.HOLDOUT;
        if (x.name == null || x.name.trim().isEmpty()) r.errors.add("the spec has no name");
        else if (!x.name.matches("[a-z0-9][a-z0-9-]*")) r.errors.add("name '" + x.name + "' must be lower case kebab-case (it names directories)");
        if (x.game == null) r.errors.add("the spec names no game");
        else if (!x.game.equals(game)) r.errors.add("the spec is for game '" + x.game + "', not '" + game + "'");
        for (String k : x.unknownKeys) r.warnings.add("unknown key '" + k + "' (ignored; a typo?)");
        if (x.initialization == null)
            r.errors.add("initialization '" + x.initializationValue + "': in-budget (MAGAT's standard) or outside-budget (the thesis's)");
        else if (!x.initializationDeclared)
            r.warnings.add("no \"initialization\": each turn's initialization stays outside its budget, as the thesis ran (and as a spec "
                    + "written before 2026-10-10 means); declare \"initialization\": \"in-budget\" for MAGAT's standard, where a level is the "
                    + "whole turn's thinking time");

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
            int declaring = 0;
            for (Experiment.Level l : x.levels)
                for (String a : x.armsAt(l)) {
                    if (!registry.has(a) || !registry.get(a).priced()) continue;
                    String p = Profile.key(a, l.budget);
                    if (!weights.has(p)) { unpriced.add(p); continue; }
                    List<String> declared = registry.get(a).operations();
                    if (!declared.isEmpty()) declaring++;
                    for (String op : weights.unpricedOperations(p, declared)) unpricedOps.add(p + "." + op);
                }
            if (!unpriced.isEmpty())
                r.errors.add("not priced in " + weights.source() + ": " + unpriced + ". Calibrate them (magat calibrate --arms ...) first: "
                        + "an unpriced arm would spend a different currency and nothing in a results file would say so");
            if (!unpricedOps.isEmpty())
                r.errors.add("operations not priced in " + weights.source() + " (version 2 prices every operation an arm declares): "
                        + unpricedOps + ". Re-calibrate those arms (--replace): an unpriced operation would be free in one arm's budget "
                        + "and paid for in another's");
            if (weights.version() < 2 && declaring > 0)
                r.warnings.add("the weights are version 1: the forward model is priced and the arms' own operations run free, as in every "
                        + "published version-1 result; their counts are still recorded");
            if ("true".equals(weights.header().get("toy")))
                (holdout && !toyRun ? r.errors : r.warnings).add("the weights are a TOY calibration, not a currency"
                        + (toyRun ? " (allowed: the run is declared --toy)" : ""));
        }

        // ---- baseline
        if (x.baseline == null) r.errors.add("no baseline: every contrast and budget multiplier is against it");
        else if (!grid.contains(x.baseline)) r.errors.add("the baseline " + x.baseline + " is not in the arms list");
        else if (x.levelsOf(x.baseline).size() < 2)
            (holdout ? r.errors : r.warnings).add("the baseline " + x.baseline + " runs at " + x.levelsOf(x.baseline).size()
                    + " level(s); budget multipliers need its value-versus-budget curve at two or more");

        // ---- sweeps carry their null (principle 5)
        for (Experiment.Sweep s : x.sweeps) {
            String what = "sweep '" + (s.parameter == null ? "?" : s.parameter) + "'";
            if (s.nullArm == null || s.nullArm.trim().isEmpty())
                r.errors.add(what + " has no null: every parameter sweep includes the parameter's null setting");
            else if (!grid.contains(s.nullArm))
                r.errors.add(what + ": its null " + s.nullArm + " is not in the grid");
            for (String a : s.arms) if (!grid.contains(a)) r.errors.add(what + " names " + a + ", which is not in the arms list");
            if (s.arms.isEmpty()) r.warnings.add(what + " names no arms");
        }

        // ---- attribution controls (principle 6)
        for (Experiment.Arm a : x.arms) {
            if (a.attribution != null && !grid.contains(a.attribution))
                r.errors.add("arm " + a.id + " names its attribution control " + a.attribution + ", which is not in the grid");
            if (a.id == null || !registry.has(a.id)) continue;
            AgentFactory<S, A> f = registry.get(a.id);
            String control = f.attributionControl();
            if (control != null && !grid.contains(control))
                (holdout ? r.errors : r.warnings).add("arm " + a.id + " has a mechanism; its attribution control " + control
                        + " must be in the grid, or nothing credits the mechanism rather than the noise");
        }

        // ---- contrasts
        for (Experiment.Contrast c : x.contrasts) {
            if (!grid.contains(c.a)) r.errors.add("contrast '" + c.label + "' names " + c.a + ", which is not in the grid");
            if (!grid.contains(c.b)) r.errors.add("contrast '" + c.label + "' names " + c.b + ", which is not in the grid");
        }

        // ---- the holdout
        if (holdout && (frozen == null || frozen.trim().isEmpty()) && (x.frozen == null || x.frozen.trim().isEmpty()))
            r.errors.add("a holdout run needs the frozen-choices file (--frozen, or \"frozen\" in the spec): the choices it tests "
                    + "must be written down before it is read");
        if (holdout && x.scenarios > 0)
            r.warnings.add("a holdout run limited to " + x.scenarios + " scenarios still counts as a read of the holdout");
        return r;
    }
}
