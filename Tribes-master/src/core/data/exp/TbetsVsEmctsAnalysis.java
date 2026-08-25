package core.data.exp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paired analysis of a {@link TbetsVsEmctsRunner} output file.
 *
 * <p><b>The unit of analysis is the SCENARIO, not the turn.</b> Each scenario is planned {@code
 * visits} times by every arm, and those repeats share a position — they are repeated measures, not
 * independent samples. Pooling them and treating n = scenarios x visits would shrink the standard
 * error by a factor of sqrt(visits) that the data has not earned, which is how a null result gets
 * reported as significant. So the per-visit paired differences are averaged WITHIN a scenario first,
 * and the test runs across scenarios. The per-pair figure is printed underneath, labelled, only so
 * the gap between the two is visible.
 *
 * <p>The comparison metric is {@code FinalValue}: the heuristic value of the state after the turn
 * the agent committed to. It is not {@code Gain} — gain is measured against each agent's own
 * initialization, and the two agents initialize differently (EMCTS: one greedy 1-ply sequence;
 * TBETS: the best of 20 sampled turns), so a gain comparison rewards having started worse.
 */
public class TbetsVsEmctsAnalysis {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            File base = new File(TbetsVsEmctsRunner.BASE);
            File[] files = base.listFiles((d, n) -> (n.startsWith("turns_cu") || n.startsWith("turns_ms")) && n.endsWith(".csv"));
            if (files == null || files.length == 0) {
                System.out.println("No turns_cu*.csv / turns_ms*.csv found in " + base.getPath());
                return;
            }
            java.util.Arrays.sort(files);
            for (File f : files) report(f, budgetOf(f.getName()));
        } else {
            for (String a : args) report(new File(a), budgetOf(new File(a).getName()));
        }
    }

    /** Budget value from the filename; negative encodes "milliseconds" so the header can say so. */
    private static int budgetOf(String filename) {
        try {
            if (filename.startsWith("turns_ms")) {
                return -Integer.parseInt(filename.replace("turns_ms", "").replace(".csv", ""));
            }
            return Integer.parseInt(filename.replace("turns_cu", "").replace(".csv", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ model

    private static final class Row {
        int scenario, visit;
        String source, agent;
        double finalValue, postTurnValue, initialValue, computeUnits, initComputeUnits, totalComputeUnits;
        int turnActions;
        int nodesEvaluated, uniqueSequences, planMillis;
        boolean failed;
    }

    public static void report(File csv, int cu) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < 2) { System.out.println("empty: " + csv); return; }

        Map<String, Integer> col = new LinkedHashMap<>();
        String[] head = lines.get(0).split(",");
        for (int i = 0; i < head.length; i++) col.put(head[i].trim(), i);

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (f.length < head.length) continue;
            Row r = new Row();
            r.scenario          = (int) num(f, col, "ScenarioId");
            r.visit             = (int) num(f, col, "Visit");
            r.source            = f[col.get("Source")].trim();
            r.agent             = f[col.get("Agent")].trim();
            r.initialValue      = num(f, col, "InitialValue");
            r.finalValue        = num(f, col, "FinalValue");
            r.postTurnValue     = num(f, col, "PostTurnValue");
            r.turnActions       = (int) num(f, col, "TurnActions");
            r.nodesEvaluated    = (int) num(f, col, "NodesEvaluated");
            r.uniqueSequences   = (int) num(f, col, "UniqueSequences");
            r.computeUnits      = num(f, col, "ComputeUnits");
            r.initComputeUnits  = num(f, col, "InitComputeUnits");
            r.totalComputeUnits = num(f, col, "TotalComputeUnits");
            r.planMillis        = (int) num(f, col, "PlanMillis");
            r.failed            = num(f, col, "Failed") > 0.5;
            rows.add(r);
        }

        // key = scenario|visit|agent
        Map<String, Row> byKey = new LinkedHashMap<>();
        LinkedHashSet<String> agents = new LinkedHashSet<>();
        LinkedHashSet<Integer> scenarios = new LinkedHashSet<>();
        Map<Integer, String> sourceOf = new LinkedHashMap<>();
        int visits = 0;
        for (Row r : rows) {
            byKey.put(r.scenario + "|" + r.visit + "|" + r.agent, r);
            agents.add(r.agent);
            scenarios.add(r.scenario);
            sourceOf.put(r.scenario, r.source);
            visits = Math.max(visits, r.visit + 1);
        }

        String bar = repeat("=", 92);
        System.out.println();
        System.out.println(bar);
        String budgetLabel = cu < 0
                ? String.format(Locale.US, "%,d ms of search (wall-clock matched)", -cu)
                : String.format(Locale.US, "%,d compute-units", cu);
        System.out.printf(Locale.US, "TBETS vs EMCTS  |  budget %s  |  %d scenarios x %d visits%n",
                budgetLabel, scenarios.size(), visits);
        System.out.println(bar);

        // ---------------------------------------------------------------- per-arm descriptives
        System.out.printf(Locale.US, "%n%-16s %11s %11s %8s %8s %8s %9s %8s %7s%n",
                "arm", "postTurn*", "searchVal", "acts", "evals", "uniqSeq", "searchCU", "initCU", "ms");
        System.out.println(repeat("-", 92));
        for (String a : agents) {
            List<Row> rs = new ArrayList<>();
            for (Row r : rows) if (r.agent.equals(a) && !r.failed) rs.add(r);
            if (rs.isEmpty()) continue;
            System.out.printf(Locale.US, "%-16s %11.2f %11.2f %8.1f %8.0f %8.0f %9.0f %8.0f %7.0f%n",
                    a,
                    mean(rs, r -> r.postTurnValue), mean(rs, r -> r.finalValue),
                    mean(rs, r -> r.turnActions),
                    mean(rs, r -> r.nodesEvaluated), mean(rs, r -> r.uniqueSequences),
                    mean(rs, r -> r.computeUnits), mean(rs, r -> r.initComputeUnits),
                    mean(rs, r -> r.planMillis));
        }

        System.out.println("  * postTurn is the comparison metric: the state the agent's PLAYED turn "
                + "produced, scored by the runner.\n    searchVal is each agent's own search value and is "
                + "NOT comparable across the rollout boundary.");

        long failures = rows.stream().filter(r -> r.failed).count();
        if (failures > 0) System.out.println("\n  !! " + failures + " failed planning episodes (excluded from pairs)");

        // ---------------------------------------------------------------- paired tests vs EMCTS
        String baseline = "EMCTS";
        if (!agents.contains(baseline)) { System.out.println("no EMCTS arm to compare against"); return; }

        for (String a : agents) {
            if (a.equals(baseline)) continue;
            System.out.println();
            System.out.println("  " + a + "  vs  " + baseline + "   (paired on scenario x visit; + favours " + a + ")");
            System.out.println("  " + repeat("-", 88));
            paired(a, baseline, byKey, scenarios, sourceOf, visits, null);
            paired(a, baseline, byKey, scenarios, sourceOf, visits, "single");
            paired(a, baseline, byKey, scenarios, sourceOf, visits, "dual");
        }
        System.out.println();
    }

    /**
     * Runs the paired comparison, optionally restricted to one portfolio source.
     * Per-scenario means first (the correct unit), pooled per-pair figures printed under it.
     */
    private static void paired(String armA, String armB, Map<String, Row> byKey,
                               LinkedHashSet<Integer> scenarios, Map<Integer, String> sourceOf,
                               int visits, String sourceFilter) {

        List<Double> perScenario = new ArrayList<>();
        List<Double> perPair = new ArrayList<>();
        int scenarioWins = 0, pairWins = 0, skipped = 0;

        for (int s : scenarios) {
            if (sourceFilter != null && !sourceFilter.equals(sourceOf.get(s))) continue;

            List<Double> within = new ArrayList<>();
            for (int v = 0; v < visits; v++) {
                Row ra = byKey.get(s + "|" + v + "|" + armA);
                Row rb = byKey.get(s + "|" + v + "|" + armB);
                if (ra == null || rb == null || ra.failed || rb.failed) { skipped++; continue; }
                double d = ra.postTurnValue - rb.postTurnValue;
                within.add(d);
                perPair.add(d);
                if (d > 0) pairWins++;
            }
            if (within.isEmpty()) continue;
            double m = within.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            perScenario.add(m);
            if (m > 0) scenarioWins++;
        }

        String label = sourceFilter == null ? "ALL" : sourceFilter;
        if (perScenario.isEmpty()) {
            System.out.printf(Locale.US, "  %-8s no usable pairs%n", label);
            return;
        }

        int n = perScenario.size();
        double mean = perScenario.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = sd(perScenario, mean);
        double se = n > 1 ? sd / Math.sqrt(n) : Double.NaN;
        double t = (se > 0 && !Double.isNaN(se)) ? mean / se : Double.NaN;
        List<Double> sorted = new ArrayList<>(perScenario);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);

        System.out.printf(Locale.US,
                "  %-8s n=%-3d  mean %+9.2f  (SE %7.2f, t=%+6.2f, %s)   median %+9.2f   "
                + "scenarios won %d/%d (%.0f%%)%n",
                label, n, mean, se, t, verdict(t, n), median,
                scenarioWins, n, 100.0 * scenarioWins / n);

        double pm = perPair.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.printf(Locale.US,
                "  %-8s %s per-turn pairs: n=%d, mean %+.2f, turns won %d/%d (%.0f%%)%s%n",
                "", "↳", perPair.size(), pm, pairWins, perPair.size(),
                100.0 * pairWins / Math.max(1, perPair.size()),
                skipped > 0 ? "   [" + skipped + " skipped]" : "");
    }

    /**
     * A blunt read of the paired t statistic. Two-sided, normal approximation, which is close enough
     * at these n and honest about what it is: this is a screen, not a confirmatory test.
     */
    private static String verdict(double t, int n) {
        if (Double.isNaN(t)) return "n/a";
        double a = Math.abs(t);
        if (n < 8) return "n too small";
        if (a >= 3.29) return "p<0.001";
        if (a >= 2.58) return "p<0.01";
        if (a >= 1.96) return "p<0.05";
        if (a >= 1.64) return "p<0.10";
        return "n.s.";
    }

    // ------------------------------------------------------------------ small helpers

    private interface Get { double of(Row r); }

    private static double mean(List<Row> rs, Get g) {
        if (rs.isEmpty()) return 0;
        double s = 0;
        for (Row r : rs) s += g.of(r);
        return s / rs.size();
    }

    private static double sd(List<Double> xs, double mean) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (double x : xs) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (xs.size() - 1));
    }

    private static double num(String[] f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= f.length) return 0;
        try { return Double.parseDouble(f[i].trim()); } catch (Exception e) { return 0; }
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
