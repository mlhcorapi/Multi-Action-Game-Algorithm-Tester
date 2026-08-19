package core.data.exp;

import core.Constants;
import core.data.dto.TurnData;
import core.data.exp.IsolationPhaseRunner.Scenario;
import core.game.ComputeMeter;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Is the compute-unit budget FAIR between the agents it compares?
 *
 * <p>v6 gives every configuration the same number of compute-units, where
 * {@code units = advances + 0.16·copies + 0.077·evals} (the weights live in {@link ComputeMeter}).
 * That is a fair currency only if two things hold, and neither is guaranteed by construction:
 *
 * <ol>
 *   <li><b>The weights match this machine.</b> They are wall-clock ratios measured by
 *       {@link ComputeCalibrateV6}. A copy costs 0.16 of an advance on the machine that last ran a
 *       calibration; on a different memory subsystem it might cost 0.25. Unless a calibration has been
 *       run here, the values in force are the compiled defaults — i.e. someone else's machine.</li>
 *   <li><b>Any weight error is harmless because the agents spend alike.</b> If every config used
 *       operations in the same proportions, a wrong weight would rescale all budgets identically and
 *       cancel out of a paired comparison. It only becomes unfairness to the extent that the configs'
 *       operation MIXES differ — and crossover tournaments are copy- and eval-heavy relative to plain
 *       MCTS iterations, which is exactly the direction that would matter.</li>
 * </ol>
 *
 * <p>This probe measures the second condition directly, and reports how much the first one could
 * distort the comparison. For each configuration it runs a fixed compute budget over a sample of
 * scenarios, records the realized operation mix, and then re-prices every config's work under
 * alternative weights. The output is the <b>spread</b>: if the most and least affected configs move by
 * a fraction of a percent when the copy weight is doubled, the budget is fair regardless of calibration
 * error; if they move by 10%, the weights must be re-calibrated on the run machine before the
 * experiment is trusted.
 *
 * <p>It also reports realized-vs-target adherence and the within-turn overshoot, which is the other
 * half of "reliable": a budget nobody actually stops at is not a budget.
 *
 * <p>This is a diagnostic, not part of the experiment: it writes one CSV and changes nothing.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.BudgetFairnessProbe [portfolioDir] [outDir]
 * [budgetUnits] [scenarios]}
 */
public final class BudgetFairnessProbe {

    public static final String HEADER =
            "Config,Ratio,Scenarios,TargetUnits,MeanSearchUnits,SdSearchUnits,OvershootPct,"
            + "MeanAdvances,MeanCopies,MeanEvals,CopiesPerAdvance,EvalsPerAdvance,"
            + "AdvanceShare,CopyShare,EvalShare,MeanInitUnits,MeanBurns,MeanUniqueSequences,"
            + "MeanExpansionXoPerTurn,MeanBurnXoPerTurn,BurnXoFillRate,TurnsWithNoXoPct";

    private BudgetFairnessProbe() {}

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String outDir       = args.length > 1 ? args[1] : RunExperimentV6.BASE + "/_calibration";
        int budget          = args.length > 2 ? Integer.parseInt(args[2].trim())
                                              : RunExperimentV6.DEFAULT_COMPUTE_LEVELS[0];
        int numScenarios    = args.length > 3 ? Integer.parseInt(args[3].trim()) : 12;

        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.REMOVE_FISHING = true;
        Constants.TURN_TIME_LIMITED = false;
        Constants.TURN_TIME_MILLIS = 10_000_000L;

        File out = new File(outDir);
        ExpCsv.ensureDir(out);
        File csv = new File(out, "budget_fairness.csv");

        System.out.println("############################################################");
        System.out.println("# v6 budget fairness probe   (output=" + csv.getPath() + ")");
        System.out.printf(Locale.ROOT, "# budget=%d units   weights: advance=%.4f copy=%.4f eval=%.4f%n",
                budget, ComputeMeter.W_ADVANCE, ComputeMeter.W_COPY, ComputeMeter.W_EVAL);
        System.out.println("############################################################");

        List<Scenario> portfolio = IsolationPhaseRunner.loadPortfolio(new File(portfolioDir));
        int n = Math.min(numScenarios, portfolio.size());
        // Span the id range so single- and dual-source scenarios are both represented.
        List<Scenario> sample = new ArrayList<>();
        for (int i = 0; i < n; i++) sample.add(portfolio.get((int) ((long) i * portfolio.size() / n)));
        System.out.println("[probe] " + sample.size() + " scenarios x " + configs(budget).size()
                + " configs at " + budget + " units");

        List<Row> rows = new ArrayList<>();
        for (Object[] cfg : configs(budget)) {
            rows.add(measure((String) cfg[0], (Integer) cfg[1], (AgentSpec) cfg[2], sample, budget));
        }

        List<String> lines = new ArrayList<>();
        for (Row r : rows) lines.add(r.toCsv(budget));
        ExpCsv.append(csv, HEADER, lines);

        report(rows, budget);
        System.out.println("\nWrote " + csv.getPath());
    }

    // ---------------------------------------------------------------------------------------------

    private static Row measure(String name, int ratio, AgentSpec spec, List<Scenario> sample, int budget) {
        Row r = new Row(name, ratio);
        List<Double> units = new ArrayList<>();
        for (Scenario sc : sample) {
            long seed = IsolationPhaseRunnerV6.visitSeed(sc.id, 0);
            Agent a = spec.create(seed);
            ArrayList<Integer> ids = new ArrayList<>();
            for (int k = 0; k < sc.state.getTribes().length; k++) ids.add(k);
            a.setPlayerIDs(sc.state.getActiveTribeID(), ids);
            GameState gs = sc.state.copy();
            gs.setRandomGenerator(new java.util.Random(seed));
            ElapsedCpuTimer ect = new ElapsedCpuTimer();
            ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
            try { a.act(gs, ect); } catch (Throwable t) { continue; }
            List<TurnData> log = a.getTurnDataLog();
            if (log.isEmpty()) continue;
            TurnData t = log.get(0);
            units.add(t.getComputeUnits());
            r.advances += t.getAdvances();
            r.copies   += t.getCopies();
            r.evals    += t.getEvals();
            r.initUnits += t.getInitComputeUnits();
            r.uniqueSequences += t.getUniqueSequences();
            r.burns += a.getBridgeBurnDataLog().size();
            // Did the operator under test actually fire? Expansion crossovers are in the operator
            // telemetry; bridge-burn tournaments are a separate log, and a burn only runs one when
            // it has two distinct elites to breed - so "20 burns" does not imply "20 tournaments".
            core.data.dto.OperatorTelemetryData ot = a.getOperatorTelemetry();
            long expXo = ot != null ? ot.xoEvents : 0;
            long burnXo = a.getCrossoverDataLog() != null ? a.getCrossoverDataLog().size() : 0;
            r.expansionXo += expXo;
            r.burnXo += burnXo;
            if (expXo + burnXo == 0) r.turnsWithNoXo++;
            r.n++;
        }
        r.meanUnits = mean(units);
        r.sdUnits = sd(units, r.meanUnits);
        return r;
    }

    /** Re-price one config's realized operation mix under a given (copy, eval) weighting. */
    private static double unitsUnder(Row r, double wCopy, double wEval) {
        return r.advances + wCopy * r.copies + wEval * r.evals;
    }

    private static void report(List<Row> rows, int budget) {
        System.out.println("\n=== 1. Budget adherence (is the budget actually respected?) ===");
        System.out.printf(Locale.ROOT, "  %-24s %12s %10s %11s %8s%n",
                "config", "meanUnits", "sd", "overshoot", "burns");
        for (Row r : rows) {
            System.out.printf(Locale.ROOT, "  %-24s %12.1f %10.1f %10.2f%% %8.1f%n",
                    r.label(), r.meanUnits, r.sdUnits, 100.0 * (r.meanUnits - budget) / budget,
                    r.n > 0 ? r.burns / (double) r.n : 0);
        }

        System.out.println("\n=== 2. Operation mix (the thing that makes weight error matter) ===");
        System.out.printf(Locale.ROOT, "  %-24s %14s %14s %11s %10s %9s%n",
                "config", "copies/advance", "evals/advance", "advShare", "copyShare", "evalShare");
        for (Row r : rows) {
            double u = unitsUnder(r, ComputeMeter.W_COPY, ComputeMeter.W_EVAL);
            System.out.printf(Locale.ROOT, "  %-24s %14.3f %14.3f %10.1f%% %9.1f%% %8.1f%%%n",
                    r.label(), r.copies / (double) Math.max(1, r.advances),
                    r.evals / (double) Math.max(1, r.advances),
                    100.0 * r.advances / u, 100.0 * ComputeMeter.W_COPY * r.copies / u,
                    100.0 * ComputeMeter.W_EVAL * r.evals / u);
        }

        System.out.println("\n=== 2b. Did the operator under test actually FIRE at this budget? ===");
        System.out.println("  A cell whose crossover never runs is EMCTS under another label - it cannot be");
        System.out.println("  read as evidence about crossover. Expansion fires once every `ratio` iterations;");
        System.out.println("  a bridge burn only breeds when it has two distinct elites, so 20 burns does NOT");
        System.out.println("  imply 20 tournaments.");
        System.out.printf(Locale.ROOT, "  %-24s %13s %10s %8s %13s   %s%n",
                "config", "expansionXo", "burnXo", "burns", "burnXo/burn", "verdict");
        for (Row r : rows) {
            int k = Math.max(1, r.n);
            double exp = r.expansionXo / (double) k;
            double bxo = r.burnXo / (double) k;
            double burns = r.burns / (double) k;
            boolean wantsExp = r.name.equals("CrossoverExpansion") || r.name.equals("CrossoverCombo");
            boolean wantsBurn = r.name.equals("CrossoverBB") || r.name.equals("CrossoverCombo");
            String verdict = "n/a (no crossover expected)";
            if (wantsExp || wantsBurn) {
                List<String> v = new ArrayList<>();
                if (wantsExp) v.add(exp == 0 ? "DEAD: expansion never fired"
                        : exp < 5 ? String.format(Locale.ROOT, "thin: expansion %.1f/turn", exp) : "ok");
                if (wantsBurn) v.add(bxo == 0 ? "DEAD: no burn tournaments"
                        : bxo < burns * 0.5 ? String.format(Locale.ROOT,
                            "thin: tournaments in %.0f%% of burns", 100.0 * bxo / Math.max(1, burns)) : "ok");
                verdict = String.join(" / ", v);
            }
            System.out.printf(Locale.ROOT, "  %-24s %13.2f %10.2f %8.1f %13.2f   %s%n",
                    r.label(), exp, bxo, burns, burns > 0 ? bxo / burns : 0, verdict);
        }

        System.out.println("\n=== 3. Sensitivity: how much would a mis-calibrated weight distort the comparison? ===");
        System.out.println("  Each cell is the EFFECTIVE budget a config would receive, relative to EMCTS,");
        System.out.println("  if the true weights were the ones in the row. 100% = same real work as EMCTS.");
        double[][] scenarios = {
                {ComputeMeter.W_COPY, ComputeMeter.W_EVAL},
                {ComputeMeter.W_COPY * 2, ComputeMeter.W_EVAL},
                {ComputeMeter.W_COPY / 2, ComputeMeter.W_EVAL},
                {ComputeMeter.W_COPY, ComputeMeter.W_EVAL * 2},
                {ComputeMeter.W_COPY, ComputeMeter.W_EVAL / 2},
                {0.5, 0.25},
        };
        String[] labels = {"as calibrated", "copy x2", "copy /2", "eval x2", "eval /2", "copy=0.50 eval=0.25"};
        System.out.printf(Locale.ROOT, "  %-22s", "weights");
        for (Row r : rows) System.out.printf(Locale.ROOT, " %10s", shortLabel(r));
        System.out.println("     spread");
        double worstSpread = 0;
        String worstLabel = "";
        for (int i = 0; i < scenarios.length; i++) {
            double wc = scenarios[i][0], we = scenarios[i][1];
            // Under alternative weights, a config that spent its budget under the CALIBRATED weights
            // has actually consumed unitsUnder(alt)/unitsUnder(calibrated) of the true cost. The
            // comparison is fair only if that ratio is the same for every config.
            double base = 0;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            List<Double> rel = new ArrayList<>();
            for (Row r : rows) {
                double ratio = unitsUnder(r, wc, we) / unitsUnder(r, ComputeMeter.W_COPY, ComputeMeter.W_EVAL);
                if (r.name.equals("EMCTS")) base = ratio;
                rel.add(ratio);
            }
            System.out.printf(Locale.ROOT, "  %-22s", labels[i]);
            for (double v : rel) {
                double pct = 100.0 * v / base;
                lo = Math.min(lo, pct); hi = Math.max(hi, pct);
                System.out.printf(Locale.ROOT, " %9.1f%%", pct);
            }
            double spread = hi - lo;
            System.out.printf(Locale.ROOT, "  %8.2f pp%n", spread);
            if (spread > worstSpread) { worstSpread = spread; worstLabel = labels[i]; }
        }

        System.out.println("\n=== VERDICT ===");
        System.out.printf(Locale.ROOT,
                "  Worst-case distortion across the weightings tried: %.2f percentage points (%s).%n",
                worstSpread, worstLabel);
        if (worstSpread < 1.0) {
            System.out.println("  The configs spend alike enough that weight error rescales them all together");
            System.out.println("  and cancels out of the paired comparison. The budget is FAIR as it stands;");
            System.out.println("  re-calibrating the weights on the run machine would change wall-clock, not");
            System.out.println("  the relative treatment of the agents.");
        } else if (worstSpread < 5.0) {
            System.out.println("  Small but not negligible. Re-run ComputeCalibrate on the run machine and bake");
            System.out.println("  the printed W_COPY / W_EVAL into ComputeMeter before the production run, then");
            System.out.println("  re-derive the budget levels from C0. Report the residual as a limitation.");
        } else {
            System.out.println("  LARGE. The configs' operation mixes differ enough that the compute-unit budget");
            System.out.println("  is only fair if the weights are right for the run machine. Re-calibrate with");
            System.out.println("  ComputeCalibrate BEFORE C0 and the production run, and report the sensitivity.");
        }
        System.out.println("  (C0's units/ms table is the direct empirical check of the same question: if");
        System.out.println("   units/ms is flat across configs on the run machine, the currency is fair there.)");
    }

    private static String shortLabel(Row r) {
        if (r.name.equals("EMCTS")) return "EMCTS";
        if (r.name.equals("CrossoverBB")) return "BB";
        String p = r.name.startsWith("CrossoverExpansion") ? "XE" : "XC";
        return p + "@" + r.ratio;
    }

    /** The 12 v6 configurations at one budget. */
    private static List<Object[]> configs(int cu) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"EMCTS", -1, ExpAgents.emctsV6(cu)});
        out.add(new Object[]{"CrossoverBB", -1, ExpAgents.crossoverBBV6(cu)});
        for (int r : IsolationPhaseRunnerV6.RATIOS)
            out.add(new Object[]{"CrossoverExpansion", r, ExpAgents.crossoverExpansionV6(r, cu)});
        for (int r : IsolationPhaseRunnerV6.RATIOS)
            out.add(new Object[]{"CrossoverCombo", r, ExpAgents.crossoverComboV6(r, cu)});
        return out;
    }

    private static double mean(List<Double> v) {
        if (v.isEmpty()) return 0;
        double s = 0; for (double d : v) s += d; return s / v.size();
    }

    private static double sd(List<Double> v, double mean) {
        if (v.size() < 2) return 0;
        double s = 0; for (double d : v) s += (d - mean) * (d - mean);
        return Math.sqrt(s / (v.size() - 1));
    }

    /** Accumulated realized spend for one configuration across the sample. */
    private static final class Row {
        final String name; final int ratio;
        long advances, copies, evals, uniqueSequences, burns;
        long expansionXo, burnXo, turnsWithNoXo;
        double initUnits, meanUnits, sdUnits;
        int n;
        Row(String name, int ratio) { this.name = name; this.ratio = ratio; }
        String label() { return ratio < 0 ? name : name + "@" + ratio; }
        String toCsv(int budget) {
            double u = advances + ComputeMeter.W_COPY * copies + ComputeMeter.W_EVAL * evals;
            int k = Math.max(1, n);
            return String.join(",", label().split("@")[0],
                    ratio < 0 ? "NA" : String.valueOf(ratio),
                    String.valueOf(n), String.valueOf(budget),
                    String.format(Locale.ROOT, "%.2f", meanUnits),
                    String.format(Locale.ROOT, "%.2f", sdUnits),
                    String.format(Locale.ROOT, "%.4f", 100.0 * (meanUnits - budget) / budget),
                    String.format(Locale.ROOT, "%.1f", advances / (double) k),
                    String.format(Locale.ROOT, "%.1f", copies / (double) k),
                    String.format(Locale.ROOT, "%.1f", evals / (double) k),
                    String.format(Locale.ROOT, "%.4f", copies / (double) Math.max(1, advances)),
                    String.format(Locale.ROOT, "%.4f", evals / (double) Math.max(1, advances)),
                    String.format(Locale.ROOT, "%.4f", advances / u),
                    String.format(Locale.ROOT, "%.4f", ComputeMeter.W_COPY * copies / u),
                    String.format(Locale.ROOT, "%.4f", ComputeMeter.W_EVAL * evals / u),
                    String.format(Locale.ROOT, "%.2f", initUnits / k),
                    String.format(Locale.ROOT, "%.2f", burns / (double) k),
                    String.format(Locale.ROOT, "%.1f", uniqueSequences / (double) k),
                    String.format(Locale.ROOT, "%.2f", expansionXo / (double) k),
                    String.format(Locale.ROOT, "%.2f", burnXo / (double) k),
                    String.format(Locale.ROOT, "%.3f", burns > 0 ? burnXo / (double) burns : 0),
                    String.format(Locale.ROOT, "%.1f", 100.0 * turnsWithNoXo / k));
        }
    }
}
