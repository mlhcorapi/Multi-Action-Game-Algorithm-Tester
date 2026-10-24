package magat.run;

import magat.agent.TurnRecord;
import magat.game.Scenario;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One {@code (arm, budget)} leaf on disk: {@code <run>/<split>/<arm>/<budget>cu/} holding
 * {@code turns.csv} (one row per scenario and visit), {@code curve.csv} (one row per anytime
 * checkpoint) and {@code _leaf.json}.
 *
 * <p><b>The turn row is the completion authority.</b> A turn's curve rows are appended first and its
 * turn row last, under one lock; on resume {@link #compact} keeps the well-formed turn rows (a kill
 * mid-append leaves a short final line, which is dropped) and only the curve rows of turns that have a
 * turn row, so a resumed leaf never holds half a turn. The raw operation counts are on every row: the
 * substrate of re-pricing and of the cross-machine promise.
 */
public final class Leaf {

    public static final String TURNS = "turns.csv";
    public static final String CURVE = "curve.csv";
    public static final String META = "_leaf.json";
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "scenario", "source", "phase", "visit", "arm", "budget", "initial", "final", "terminal", "avg_node",
            "advances", "copies", "evals", "units", "init_advances", "init_copies", "init_evals", "init_units",
            "plan_ms", "init_ms", "nodes", "unique", "duplicates", "seq_len"));
    public static final String CURVE_HEADER = "scenario,visit,arm,budget,checkpoint,best,advances,units,ms";
    /** The columns two arms must agree on, turn by turn, to be reported as producing identical output. */
    static final List<String> SIGNATURE = Collections.unmodifiableList(Arrays.asList(
            "initial", "final", "advances", "copies", "evals", "nodes", "unique"));

    private final Path dir;
    private List<String> telemetryKeys;          // fixed by the file's header once it exists
    private List<String> operationKeys;          // the arm's declared operations, likewise fixed by the header

    private Leaf(Path dir) {
        this.dir = dir;
    }

    public static Path dir(Path splitRoot, String arm, long budget) {
        return splitRoot.resolve(arm).resolve(budget + "cu");
    }

    /** Opens a leaf directory, compacting what an interrupted run left, and returns the turns already done. */
    static Leaf open(Path dir, Set<String> doneOut) throws IOException {
        Files.createDirectories(dir);
        Leaf l = new Leaf(dir);
        doneOut.addAll(compact(dir));
        Path turns = dir.resolve(TURNS);
        if (Files.isRegularFile(turns)) {
            List<String> lines = Files.readAllLines(turns, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                l.telemetryKeys = telemetryOf(lines.get(0));
                l.operationKeys = operationsOf(lines.get(0));
            }
        }
        return l;
    }

    public static String key(int scenario, int visit) {
        return scenario + ":" + visit;
    }

    /**
     * Keeps the well-formed, first-seen turn rows and the curve rows of those turns; rewrites both
     * files. Returns the done keys ({@code scenario:visit}).
     */
    static Set<String> compact(Path dir) throws IOException {
        Set<String> done = new LinkedHashSet<>();
        Path turns = dir.resolve(TURNS), curve = dir.resolve(CURVE);
        if (Files.isRegularFile(turns)) {
            List<String> lines = Files.readAllLines(turns, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String header = lines.get(0);
                int fields = header.split(",", -1).length;
                List<String> kept = new ArrayList<>();
                kept.add(header);
                for (int i = 1; i < lines.size(); i++) {
                    String[] f = lines.get(i).split(",", -1);
                    if (f.length != fields) continue;
                    try {
                        if (done.add(key(Integer.parseInt(f[0]), Integer.parseInt(f[3])))) kept.add(lines.get(i));
                    } catch (NumberFormatException ignore) { }
                }
                if (kept.size() != lines.size()) RunGuard.atomicWrite(turns, String.join("\n", kept) + "\n");
            }
        }
        if (Files.isRegularFile(curve)) {
            List<String> lines = Files.readAllLines(curve, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<>();
            kept.add(CURVE_HEADER);
            int fields = CURVE_HEADER.split(",").length;
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",", -1);
                if (f.length != fields) continue;
                try {
                    String k = key(Integer.parseInt(f[0]), Integer.parseInt(f[1]));
                    // Only curve rows of finished turns, and each checkpoint once (a replayed turn rewrites them).
                    if (done.contains(k) && seen.add(k + "#" + f[4])) kept.add(lines.get(i));
                } catch (NumberFormatException ignore) { }
            }
            if (kept.size() != lines.size()) RunGuard.atomicWrite(curve, String.join("\n", kept) + "\n");
        }
        return done;
    }

    /** Appends one finished turn: its curve rows first, its turn row last (the completion authority). */
    synchronized void append(Scenario<?> sc, int visit, String arm, long budget, TurnRecord r) throws IOException {
        Path turns = dir.resolve(TURNS), curve = dir.resolve(CURVE);
        if (telemetryKeys == null) {
            telemetryKeys = new ArrayList<>(r.telemetry.keySet());
            operationKeys = new ArrayList<>(r.operations.keySet());
            List<String> header = new ArrayList<>(COLUMNS);
            for (String k : operationKeys) header.add("op_" + k);
            for (String k : telemetryKeys) header.add("t_" + k);
            Files.write(turns, (String.join(",", header) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        if (!r.curve.isEmpty()) {
            StringBuilder c = new StringBuilder();
            if (!Files.exists(curve)) c.append(CURVE_HEADER).append('\n');
            for (TurnRecord.Checkpoint k : r.curve)
                c.append(sc.id).append(',').append(visit).append(',').append(arm).append(',').append(budget).append(',')
                 .append(k.index).append(',').append(num(k.best)).append(',').append(k.advances).append(',')
                 .append(num(k.units)).append(',').append(k.millis).append('\n');
            Files.write(curve, c.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        StringBuilder t = new StringBuilder();
        t.append(sc.id).append(',').append(sc.source).append(',').append(sc.phase).append(',').append(visit).append(',')
         .append(arm).append(',').append(budget).append(',')
         .append(num(r.initial)).append(',').append(num(r.finalValue)).append(',').append(r.terminal ? 1 : 0).append(',')
         .append(num(r.avgNode)).append(',')
         .append(r.advances).append(',').append(r.copies).append(',').append(r.evals).append(',').append(num(r.units)).append(',')
         .append(r.initAdvances).append(',').append(r.initCopies).append(',').append(r.initEvals).append(',').append(num(r.initUnits)).append(',')
         .append(r.planMillis).append(',').append(r.initMillis).append(',')
         .append(r.nodes).append(',').append(r.unique).append(',').append(r.duplicates).append(',').append(r.sequenceLength);
        // The arm's own operations, counted over the search (re-pricing reads them beside the forward model's).
        for (String k : operationKeys) {
            Long c = r.operations.get(k);
            t.append(',').append(c == null ? "" : String.valueOf(c));
        }
        for (String k : telemetryKeys) {
            Double v = r.telemetry.get(k);
            t.append(',').append(v == null ? "" : num(v));
        }
        t.append('\n');
        Files.write(turns, t.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Rows in {@code turns.csv}, header excluded. */
    static int rows(Path dir) throws IOException {
        Path turns = dir.resolve(TURNS);
        if (!Files.isRegularFile(turns)) return 0;
        return Math.max(0, Files.readAllLines(turns, StandardCharsets.UTF_8).size() - 1);
    }

    /** {@code scenario:visit -> the signature columns}, for the identical-output assertion. */
    static Map<String, String> signatures(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path turns = dir.resolve(TURNS);
        if (!Files.isRegularFile(turns)) return out;
        List<String> lines = Files.readAllLines(turns, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return out;
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int[] idx = new int[SIGNATURE.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = header.indexOf(SIGNATURE.get(i));
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            if (f.length != header.size()) continue;
            StringBuilder sig = new StringBuilder();
            for (int j : idx) sig.append(f[j]).append('|');
            out.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[3])), sig.toString());
        }
        return out;
    }

    private static List<String> telemetryOf(String header) {
        List<String> keys = new ArrayList<>();
        for (String c : header.split(",")) if (c.startsWith("t_")) keys.add(c.substring(2));
        return keys;
    }

    private static List<String> operationsOf(String header) {
        List<String> keys = new ArrayList<>();
        for (String c : header.split(",")) if (c.startsWith("op_")) keys.add(c.substring(3));
        return keys;
    }

    /** Four decimals, never in exponent notation. */
    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        return BigDecimal.valueOf(TurnRecord.round4(v)).stripTrailingZeros().toPlainString();
    }
}
