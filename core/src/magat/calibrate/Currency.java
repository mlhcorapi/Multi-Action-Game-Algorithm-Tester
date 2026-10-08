package magat.calibrate;

import magat.meter.Weights;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writing a currency, merging new prices into one, and measuring how far a machine has drifted from
 * one. Pure functions over text and numbers, so the arithmetic that once went wrong is unit-tested
 * rather than trusted.
 *
 * <p><b>Re-denomination.</b> A calibration session measures every cell relative to its own reference
 * advance. Merged into an existing file, a cell is expressed in the file's unit by one factor: the
 * file's stored weight for its reference profile's advance (1.0 for a file this calibrator wrote;
 * 1.000659 for the imported R7615 file, whose unit is the engine EMCTS advance). That is ratio
 * transfer: it assumes what an operation costs RELATIVE to the reference advance carries from the
 * session to the file's machine, so a uniformly faster or slower machine, or a slow afternoon on the
 * same machine, cancels exactly. What does not cancel is a change in those ratios, and that is what
 * {@link #drift} reports.
 *
 * <p>The thesis calibrator instead divided each new cell's absolute nanoseconds by the file's stored
 * reference nanoseconds, so a session on a machine 58% slower priced its new arms 58% too high (the
 * 2026-08-31 merge, re-denominated x1.5820, re-measured the same day). The brief's rule is that
 * re-denomination makes cross-session drift cancel; ratio transfer is the arithmetic that does.
 */
public final class Currency {

    public static final String WEIGHTS_FILE = "compute_weights.properties";
    public static final String PROFILES_CSV = "compute_profiles.csv";
    public static final String OPERATIONS_CSV = "compute_operations.csv";
    /** The drift gate, the same resolution as the per-cell stability gate. */
    public static final double DRIFT_GATE = 0.10;

    private Currency() {}

    /** What a session records about itself in a file it writes. */
    public static final class Session {
        public String game;
        public String machine;
        public String java = System.getProperty("java.version");
        public String measured = new java.util.Date().toString();
        public String levels;
        public String turnsPerRep;
        public String portfolioIdentity;
        public int reps;
        public boolean toy;
        /** 2 prices every operation an arm declares; 1 the forward model only. */
        public int version = 2;
    }

    // ------------------------------------------------------------------ a fresh file

    /** A new file whose unit is one advance of {@code ref}: version 2 (every operation priced) unless the session says 1. */
    public static String fresh(Session s, List<Cell> cells, Cell ref) {
        StringBuilder sb = new StringBuilder();
        if (s.version >= 2) {
            sb.append("# MAGAT weights, version 2: the ").append(s.game).append(" currency, measured by magat calibrate.\n");
            sb.append(String.format(Locale.ROOT, "# One unit = one advance of %s = %.3f ns on this machine. Every weight below is the%n",
                    ref.profile, ref.tAdvance));
            sb.append("# price of one occurrence of an operation as a ratio to it: the forward model's (advance, copy, eval) and\n");
            sb.append("# each operation the arm declares (DESIGN 6.6), per arm and budget level, so a unit budget buys the same\n");
            sb.append("# wall-clock of all the work an arm does.\n");
        } else {
            sb.append("# MAGAT weights, version 1: the ").append(s.game).append(" currency, measured by magat calibrate.\n");
            sb.append(String.format(Locale.ROOT, "# One unit = one advance of %s = %.3f ns on this machine. Every weight below is a%n",
                    ref.profile, ref.tAdvance));
            sb.append("# ratio to it, so equal unit budgets are equal wall-clock across arms and proportional across levels.\n");
        }
        if (s.toy)
            sb.append("# TOY CALIBRATION: sample-size and stability gates were reported, not enforced. Not a currency.\n");
        sb.append(String.format(Locale.ROOT, "# Every cell was measured under the fixed measurement weights (advance %s, copy %s, eval %s, the arms'%n"
                + "# own operations free), whatever a file being extended prices, so every session plays the same turns.%n",
                Weights.DEFAULT[0], Weights.DEFAULT[1], Weights.DEFAULT[2]));
        sb.append("magat.weights.version = ").append(s.version >= 2 ? 2 : 1).append('\n');
        sb.append("game = ").append(s.game).append('\n');
        sb.append("machine = ").append(s.machine).append('\n');
        sb.append("java = ").append(s.java).append('\n');
        sb.append("measured = ").append(s.measured).append('\n');
        sb.append("levels = ").append(s.levels).append('\n');
        sb.append("reference = ").append(ref.profile).append('\n');
        sb.append(String.format(Locale.ROOT, "referenceAdvanceNanos = %.3f%n", ref.tAdvance));
        sb.append("turnsPerRep = ").append(s.turnsPerRep).append('\n');
        sb.append("portfolio.identity = ").append(s.portfolioIdentity).append('\n');
        sb.append("reps = ").append(s.reps).append('\n');
        if (s.toy) sb.append("toy = true\n");
        if (s.version >= 2) {
            sb.append("operations = ").append(String.join(",", operations(cells, null))).append('\n');
            for (Map.Entry<String, Double> e : ref.prices().entrySet())
                sb.append(String.format(Locale.ROOT, "global.w.%s = %s%n", e.getKey(), weight(e.getKey(), e.getValue())));
            sb.append("\n# profile.<arm>@<budget>.w.<op> is the price of one occurrence of <op> as a ratio to the reference advance;\n");
            sb.append("# ns.<op> its measured nanoseconds on this machine; unpriced, the calibration audit's share of the cell's turn\n");
            sb.append("# time that no priced operation accounts for.\n");
        } else {
            sb.append(String.format(Locale.ROOT, "global.wAdvance = %.6f%nglobal.wCopy = %.6f%nglobal.wEval = %.6f%n",
                    ref.wAdvance, ref.wCopy, ref.wEval));
            sb.append("\n# profile.<arm>@<budget>.{wAdvance,wCopy,wEval} are ratios to the reference advance;\n");
            sb.append("# *Nanos are the measured per-operation costs on this machine.\n");
        }
        for (Cell c : cells) appendProfile(sb, c, 1.0, s.version);
        return sb.toString();
    }

    /** Every operation the cells price, forward model first, then in first-seen order (and any already in {@code existing}). */
    static List<String> operations(List<Cell> cells, List<String> existing) {
        Set<String> ops = new LinkedHashSet<>(magat.meter.Meter.FORWARD_MODEL_OPS);
        if (existing != null) ops.addAll(existing);
        for (Cell c : cells) ops.addAll(c.ops.keySet());
        return new ArrayList<>(ops);
    }

    /** A weight as written: six decimals for the forward model, nine for an algorithm operation, which can cost a millionth of an advance. */
    private static String weight(String op, double w) {
        return String.format(Locale.ROOT, magat.meter.Meter.FORWARD_MODEL_OPS.contains(op) ? "%.6f" : "%.9f", w);
    }

    private static void appendProfile(StringBuilder sb, Cell c, double factor, int version) {
        String p = "profile." + c.profile + ".";
        if (version >= 2) {
            for (Map.Entry<String, Double> e : c.prices().entrySet())
                sb.append(p).append("w.").append(e.getKey()).append(" = ").append(weight(e.getKey(), e.getValue() * factor)).append('\n');
            for (Map.Entry<String, Double> e : c.nanos().entrySet())
                sb.append(String.format(Locale.ROOT, "%sns.%s = %.3f%n", p, e.getKey(), e.getValue()));
            if (!Double.isNaN(c.unpriced)) sb.append(String.format(Locale.ROOT, "%sunpriced = %.4f%n", p, c.unpriced));
            return;
        }
        sb.append(String.format(Locale.ROOT, "%swAdvance = %.6f%n", p, c.wAdvance * factor));
        sb.append(String.format(Locale.ROOT, "%swCopy = %.6f%n", p, c.wCopy * factor));
        sb.append(String.format(Locale.ROOT, "%swEval = %.6f%n", p, c.wEval * factor));
        sb.append(String.format(Locale.ROOT, "%sadvanceNanos = %.3f%n", p, c.tAdvance));
        sb.append(String.format(Locale.ROOT, "%scopyNanos = %.3f%n", p, c.tCopy));
        sb.append(String.format(Locale.ROOT, "%sevalNanos = %.3f%n", p, c.tEval));
    }

    // ------------------------------------------------------------------ merging

    /**
     * The factor that expresses a session's weights (ratios to its own measurement of the file's
     * reference advance) in the file's unit: the file's stored {@code wAdvance} of its reference
     * profile. Multiplied, never divided.
     */
    public static double factor(Weights file) {
        double[] w = file.reference() == null ? null : file.profile(file.reference());
        return w == null ? 1.0 : w[0];
    }

    /**
     * Merges {@code write} into an existing file of version 1 or 2: every existing line is kept except
     * those of a profile being written, header and globals untouched, the new cells re-denominated by
     * {@link #factor} and appended under one {@code # merged} line, in the file's own version. A merge
     * into a version-1 file writes the forward model's prices only, so the file stays one currency (the
     * arms' own operations are measured and reported, and stay free in it); a merge into a version-2 file
     * writes every operation and extends its {@code operations} line.
     *
     * @param ref the session's measurement of the file's reference profile
     */
    public static String merge(List<String> lines, Weights file, Session s, List<Cell> write, Cell ref) {
        String v = file.header().get("magat.weights.version");
        if (!"1".equals(v) && !"2".equals(v))
            throw new IllegalStateException(file.source() + " is not a MAGAT weights file (version 1 or 2); merging into it would mix "
                    + "formats. Convert it first (tools/import_r7615.py shows how).");
        if (!ref.profile.equals(file.reference()))
            throw new IllegalStateException("the session's reference cell is " + ref.profile + " but the file's reference is "
                    + file.reference());
        int version = file.version();
        double f = factor(file);
        Set<String> replacing = new LinkedHashSet<>();
        for (Cell c : write) replacing.add("profile." + c.profile + ".");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            boolean drop = false;
            for (String p : replacing) if (t.startsWith(p)) { drop = true; break; }
            if (drop) continue;
            if (version >= 2 && t.startsWith("operations") && t.replace(" ", "").startsWith("operations=")) {
                List<String> had = new ArrayList<>();
                for (String op : t.substring(t.indexOf('=') + 1).split(",")) if (!op.trim().isEmpty()) had.add(op.trim());
                sb.append("operations = ").append(String.join(",", operations(write, had))).append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        double machine = machineFactor(file, ref);
        sb.append('\n').append(String.format(Locale.ROOT,
                "# merged %s: %d profile(s) measured against this session's %s advance (%.3f ns, x%.4f the file's), "
                        + "re-denominated x%.6f (the file's reference weight) into this file's unit; turnsPerRep %s%s%s%n",
                s.measured, write.size(), ref.profile, ref.tAdvance, machine, f, s.turnsPerRep,
                version < 2 ? "; version 1: the forward model priced, the arms' own operations free" : "", s.toy ? "; TOY" : ""));
        for (Cell c : write) appendProfile(sb, c, f, version);
        return sb.toString();
    }

    /** This session's reference advance over the file's, for the eye: harmless under ratio transfer. */
    public static double machineFactor(Weights file, Cell ref) {
        double fileNs = file.nanos(ref.profile)[0];
        if (Double.isNaN(fileNs) || fileNs <= 0) fileNs = file.referenceAdvanceNanos();
        return fileNs > 0 ? ref.tAdvance / fileNs : Double.NaN;
    }

    // ------------------------------------------------------------------ drift

    /** One operation of one profile, stored against measured now (both in the file's unit). */
    public static final class DriftRow {
        public final String what;
        public final double stored, now, drift;

        DriftRow(String what, double stored, double now) {
            this.what = what;
            this.stored = stored;
            this.now = now;
            this.drift = stored > 0 ? now / stored - 1 : Double.NaN;
        }
    }

    public static final class Drift {
        public final double machineFactor;
        /** Per operation of every compared profile: information, not the verdict. */
        public final List<DriftRow> rows;
        /**
         * Per profile: how much the price of that cell's measured work moves, now against stored, with
         * the operations weighted by how many of each the cell performed. This is the gated number: it is
         * how far a budget priced on this machine would buy a different amount of that arm's work than
         * the stored price says (an evaluation 40% cheaper relative to an advance moves a Tribes turn's
         * price about 1%, because evaluations are about 3% of it).
         */
        public final java.util.Map<String, Double> turnPrice;
        public final List<String> notPriced;
        /** The profile whose turn price moved most, and by how much; null when nothing was compared. */
        public final String worstProfile;
        public final double worstTurnPrice;
        /** The operation that moved most, for the eye; it does not decide. */
        public final DriftRow worstOp;
        public final double gate;

        Drift(double machineFactor, List<DriftRow> rows, java.util.Map<String, Double> turnPrice, List<String> notPriced,
              DriftRow worstOp, double gate) {
            this.machineFactor = machineFactor;
            this.rows = Collections.unmodifiableList(rows);
            this.turnPrice = Collections.unmodifiableMap(turnPrice);
            this.notPriced = Collections.unmodifiableList(notPriced);
            this.worstOp = worstOp;
            this.gate = gate;
            String wp = null;
            double wd = 0;
            for (java.util.Map.Entry<String, Double> e : turnPrice.entrySet())
                if (wp == null || Math.abs(e.getValue()) > Math.abs(wd)) { wp = e.getKey(); wd = e.getValue(); }
            this.worstProfile = wp;
            this.worstTurnPrice = wp == null ? Double.NaN : wd;
        }

        /** Every compared cell's turn price within the gate (vacuously true when nothing overlaps). */
        public boolean pass() { return worstProfile == null || Math.abs(worstTurnPrice) <= gate; }

        public void print() {
            System.out.println(String.format(Locale.ROOT,
                    "  reference advance: x%.4f the file's (machine speed; cancels under ratio re-denomination)", machineFactor));
            System.out.println("  per operation, in the file's unit (information: the gate is each cell's turn price, below):");
            System.out.println(String.format(Locale.ROOT, "  %-40s %10s %10s %8s", "profile.op", "stored", "now", "drift"));
            for (DriftRow r : rows)
                System.out.println(String.format(Locale.ROOT, "  %-40s %10.4f %10.4f %7.1f%%", r.what, r.stored, r.now, 100 * r.drift));
            if (worstOp != null)
                System.out.println(String.format(Locale.ROOT, "  largest per-operation drift %.1f%% (%s), information", 100 * worstOp.drift, worstOp.what));
            if (!turnPrice.isEmpty()) {
                System.out.println(String.format(Locale.ROOT, "  price of each cell's own work, now against stored (gate %.0f%%):", 100 * gate));
                for (java.util.Map.Entry<String, Double> e : turnPrice.entrySet())
                    System.out.println(String.format(Locale.ROOT, "  %-40s %+7.1f%%%s", e.getKey(), 100 * e.getValue(),
                            Math.abs(e.getValue()) > gate ? "   <-- past the gate" : ""));
            }
            for (String p : notPriced) System.out.println("  " + p + ": not priced in the file, nothing to compare");
            if (worstProfile == null) System.out.println("  no overlapping profile to compare");
            else System.out.println(String.format(Locale.ROOT, "  largest drift in a turn's price %.1f%% (%s); gate %.0f%%",
                    100 * worstTurnPrice, worstProfile, 100 * gate));
        }
    }

    /**
     * How far each re-measured cell's price has moved from the file, in the file's unit. Per operation:
     * this session's weight times {@link #factor}, over the stored weight (the reference profile's own
     * advance is 1 on both sides by construction and is skipped). Per cell: the operations the cell
     * performed, priced now against priced as stored, over the operations both sides price; that is the
     * verdict.
     */
    public static Drift drift(Weights file, List<Cell> cells, Cell ref, double gate) {
        double f = factor(file);
        List<DriftRow> rows = new ArrayList<>();
        java.util.Map<String, Double> turnPrice = new java.util.LinkedHashMap<>();
        List<String> notPriced = new ArrayList<>();
        DriftRow worst = null;
        for (Cell c : cells) {
            double[] stored = file.profile(c.profile);
            if (stored == null) { notPriced.add(c.profile); continue; }
            double[] now = c.weights();
            double priceNow = (c.advances * now[0] + c.copies * now[1] + c.evals * now[2]) * f;
            double priceStored = c.advances * stored[0] + c.copies * stored[1] + c.evals * stored[2];
            for (int op = c.profile.equals(ref.profile) ? 1 : 0; op < 3; op++) {
                DriftRow r = new DriftRow(c.profile + "." + Weights.OPS[op], stored[op], now[op] * f);
                rows.add(r);
                if (worst == null || Math.abs(r.drift) > Math.abs(worst.drift)) worst = r;
            }
            // The arm's own operations, where the file prices them too (a version-2 file): in the turn price,
            // and per operation where the stored price is not zero.
            Map<String, Double> storedAll = file.prices(c.profile);
            for (Map.Entry<String, Cell.OpStat> e : c.ops.entrySet()) {
                Double s = storedAll.get(e.getKey());
                if (s == null) continue;
                priceNow += e.getValue().count * e.getValue().w * f;
                priceStored += e.getValue().count * s;
                if (s > 0) {
                    DriftRow r = new DriftRow(c.profile + ".w." + e.getKey(), s, e.getValue().w * f);
                    rows.add(r);
                    if (worst == null || Math.abs(r.drift) > Math.abs(worst.drift)) worst = r;
                }
            }
            if (priceStored > 0) turnPrice.put(c.profile, priceNow / priceStored - 1);
        }
        return new Drift(machineFactor(file, ref), rows, turnPrice, notPriced, worst, gate);
    }

    // ------------------------------------------------------------------ the measurements CSV

    public static String profilesCsv(List<Cell> cells, Cell ref) {
        StringBuilder sb = new StringBuilder("Profile,Arm,Budget,Positions,Advances,Copies,Evals,AdvanceNanos,CopyNanos,EvalNanos,"
                + "GapAdvance,GapCopy,GapEval,CvAdvance,CvCopy,CvEval,W_ADVANCE,W_COPY,W_EVAL,Role,Status,Seconds,Unpriced,Unaccounted\n");
        for (Cell c : cells)
            sb.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.6f,%.6f,%.6f,%s,%s,%.1f,%.4f,%.4f%n",
                    c.profile, c.arm, c.budget, c.positions, c.advances, c.copies, c.evals, c.tAdvance, c.tCopy, c.tEval,
                    c.gapAdvance, c.gapCopy, c.gapEval, c.cvAdvance, c.cvCopy, c.cvEval, c.wAdvance, c.wCopy, c.wEval,
                    c == ref ? "reference" : c.role,
                    c.failures.isEmpty() ? "ok" : "PROBLEM: " + String.join("; ", c.failures).replace(',', ';'),
                    c.seconds, c.unpriced, c.unaccounted));
        return sb.toString();
    }

    /** Every operation of every cell, one row each: the long form of what a version-2 file prices. */
    public static String operationsCsv(List<Cell> cells, Cell ref) {
        StringBuilder sb = new StringBuilder("Profile,Arm,Budget,Op,Count,Timed,Nanos,Gap,Cv,Weight,Share,Role\n");
        for (Cell c : cells) {
            String role = c == ref ? "reference" : c.role;
            double[][] fm = {{c.advances, c.advances, c.tAdvance, c.gapAdvance, c.cvAdvance, c.wAdvance},
                    {c.copies, c.copies, c.tCopy, c.gapCopy, c.cvCopy, c.wCopy}, {c.evals, c.evals, c.tEval, c.gapEval, c.cvEval, c.wEval}};
            for (int i = 0; i < 3; i++)
                sb.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%d,%d,%.3f,%.5f,%.5f,%.9f,,%s%n", c.profile, c.arm, c.budget,
                        magat.meter.Meter.FORWARD_MODEL_OPS.get(i), (long) fm[i][0], (long) fm[i][1], fm[i][2], fm[i][3], fm[i][4], fm[i][5], role));
            for (Map.Entry<String, Cell.OpStat> e : c.ops.entrySet()) {
                Cell.OpStat s = e.getValue();
                sb.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%d,%d,%.3f,%.5f,%.5f,%.9f,%.5f,%s%n", c.profile, c.arm, c.budget, e.getKey(),
                        s.count, s.timed, s.t, s.gap, s.cv, s.w, s.share, role));
            }
        }
        return sb.toString();
    }
}
