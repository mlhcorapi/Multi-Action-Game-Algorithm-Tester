package magat.probe;

import magat.agent.AgentFactory;
import magat.agent.Episode;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code magat check replay}: the cross-machine determinism claim, tested rather than stated. Given a
 * published leaf, a rebuilt portfolio and the published weights, re-plan K of its turns and assert
 * that every reproducible field agrees: values, operation counts, nodes, unique sequences, priced
 * units and the mechanism's counters. Wall-clock columns are not compared; nothing else is exempt.
 *
 * <p>Reads a MAGAT leaf ({@code turns.csv}).
 */
public final class ReplayCheck {

    private ReplayCheck() {}

    /** One published turn: the fields it carries, by the candidate's names. */
    static final class Row {
        final int scenario, visit;
        final Map<String, Double> fields = new LinkedHashMap<>();

        Row(int scenario, int visit) {
            this.scenario = scenario;
            this.visit = visit;
        }
    }

    public static final class Leaf {
        public final String format;
        public final String arm;         // as written in the leaf
        final List<Row> rows;

        Leaf(String format, String arm, List<Row> rows) {
            this.format = format;
            this.arm = arm;
            this.rows = rows;
        }

        public int size() { return rows.size(); }
    }

    public static final class Result {
        public int replayed, reproduced;
        public final List<String> failures = new ArrayList<>();
        public boolean pass() { return replayed > 0 && replayed == reproduced; }
    }

    // ------------------------------------------------------------------ reading a leaf

    public static Leaf read(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve("turns.csv"))) return readMagat(dir.resolve("turns.csv"));
        throw new IOException(dir + " holds no turns.csv");
    }

    private static Leaf readMagat(Path turns) throws IOException {
        List<String> lines = Files.readAllLines(turns, StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        List<Row> rows = new ArrayList<>();
        String arm = null;
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (f.length != header.size()) continue;
            Row r = new Row(Integer.parseInt(f[0]), Integer.parseInt(f[3]));
            arm = f[4];
            for (int i = 0; i < f.length; i++) {
                String c = header.get(i);
                if (Arrays.asList("scenario", "source", "phase", "visit", "arm", "budget", "plan_ms", "init_ms").contains(c) || f[i].isEmpty()) continue;
                r.fields.put(c.startsWith("t_") ? "t:" + c.substring(2) : c, Double.parseDouble(f[i]));
            }
            rows.add(r);
        }
        return new Leaf("magat", arm, rows);
    }

    // ------------------------------------------------------------------ replaying

    /** The candidate's value of every field a published row may carry. */
    static Map<String, Double> fieldsOf(TurnRecord r) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("initial", r.initial);
        m.put("final", r.finalValue);
        m.put("terminal", r.terminal ? 1.0 : 0.0);
        m.put("avg_node", r.avgNode);
        m.put("advances", (double) r.advances);
        m.put("copies", (double) r.copies);
        m.put("evals", (double) r.evals);
        m.put("units", r.units);
        m.put("init_advances", (double) r.initAdvances);
        m.put("init_copies", (double) r.initCopies);
        m.put("init_evals", (double) r.initEvals);
        m.put("init_units", r.initUnits);
        m.put("nodes", (double) r.nodes);
        m.put("unique", (double) r.unique);
        m.put("duplicates", (double) r.duplicates);
        m.put("seq_len", (double) r.sequenceLength);
        m.put("fm", (double) r.fmCalls());
        for (Map.Entry<String, Long> e : r.operations.entrySet()) m.put("op_" + e.getKey(), (double) e.getValue());
        for (Map.Entry<String, Double> e : r.telemetry.entrySet()) m.put("t:" + e.getKey(), e.getValue());
        return m;
    }

    /**
     * One turn as the runner played it: under the arm's profile at this budget, or, for an arm that is not priced
     * (the random floor), under the installed currency's global weights.
     */
    static <S, A> TurnRecord play(Game<S, A> game, Scenario<S> sc, int visit, AgentFactory<S, A> arm, long budget) {
        if (arm.priced()) return Episode.run(game, sc, visit, arm, budget);
        Weights w = Meter.installed();
        if (w == null) throw new IllegalStateException("no currency is installed to replay " + arm.id() + ", which is not priced, under");
        return Episode.run(game, sc, visit, arm, budget, w.global(), "global");
    }

    public static <S, A> Result run(Game<S, A> game, Portfolio<S> portfolio, AgentFactory<S, A> arm, long budget, Leaf leaf,
                                    int k, int threads) throws Exception {
        List<Row> pick = new ArrayList<>();
        int n = Math.min(k, leaf.rows.size());
        for (int i = 0; i < n; i++) pick.add(leaf.rows.get((int) ((long) i * leaf.rows.size() / n)));
        Result out = new Result();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<List<String>>> fs = new ArrayList<>();
            List<Row> submitted = new ArrayList<>();
            for (Row row : pick) {
                Scenario<S> sc = portfolio.byId(row.scenario);
                if (sc == null) {
                    out.failures.add("scenario " + row.scenario + " is not in this portfolio");
                    out.replayed++;
                    continue;
                }
                submitted.add(row);
                fs.add(pool.submit(() -> compare(row, fieldsOf(play(game, sc, row.visit, arm, budget)))));
            }
            for (int i = 0; i < fs.size(); i++) {
                List<String> d = fs.get(i).get();
                out.replayed++;
                if (d.isEmpty()) out.reproduced++;
                else out.failures.add("scenario " + submitted.get(i).scenario + " visit " + submitted.get(i).visit + ": " + d);
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** Differences between a published row and the candidate's, compared to four decimals. */
    static List<String> compare(Row published, Map<String, Double> mine) {
        List<String> d = new ArrayList<>();
        for (Map.Entry<String, Double> e : published.fields.entrySet()) {
            Double v = mine.get(e.getKey());
            if (v == null) continue;     // a counter this arm does not report any more
            if (Double.compare(TurnRecord.round4(v), TurnRecord.round4(e.getValue())) != 0)
                d.add(String.format(Locale.ROOT, "%s %s vs published %s", e.getKey(), TurnRecord.round4(v), TurnRecord.round4(e.getValue())));
        }
        return d;
    }
}
