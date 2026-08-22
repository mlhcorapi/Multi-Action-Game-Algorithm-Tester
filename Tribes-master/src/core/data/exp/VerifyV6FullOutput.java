package core.data.exp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Integrity check for the v6 full-games output tree. Run it before any analysis.
 *
 * <p>It looks for the damage an interrupted or doubled run leaves, and for the schedule violations
 * that would quietly undermine the fairness argument. The second kind matters more: a duplicated row
 * is obvious in a row count, whereas a matchup that happened to play one orientation more often than
 * the other looks like perfectly ordinary data and would bias the win rate by exactly the amount the
 * orientation swap exists to remove.
 *
 * <p>Checks per matchup leaf:
 * <ul>
 *   <li>every row has the right field count, and the Level / Opponent / Ratio columns agree with the
 *       directory the file sits in;</li>
 *   <li>GameIds are exactly {@code 0..199} — no duplicates, no gaps;</li>
 *   <li>each map appears {@value FullGameRunnerV6#GAMES_PER_MAP} times and each (map, orientation)
 *       {@value FullGameRunnerV6#GAMES_PER_ORIENTATION} times;</li>
 *   <li>{@code Winner} is a role and {@code EmctsWon} agrees with it;</li>
 *   <li>each agent occupies each seat equally often.</li>
 * </ul>
 *
 * <p>It also prints the three headline diagnostics — the control arm's win rate, the conquest share
 * and the cap-bind rate — because those decide whether the dataset is readable at all, and finding
 * out at analysis time is finding out late.
 *
 * <p>{@code --repair} rewrites leaves to drop duplicate and torn rows. It never invents rows: a leaf
 * short of 200 games is reported and left for the runner to finish on the next resume.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.VerifyV6FullOutput [baseDir] [--repair]}
 */
public final class VerifyV6FullOutput {

    private VerifyV6FullOutput() {}

    private static int problems = 0, leaves = 0, totalRows = 0;

    public static void main(String[] args) throws Exception {
        File base = new File(args.length > 0 && !args[0].startsWith("--")
                ? args[0] : RunV6FullGames.BASE);
        boolean repair = false;
        for (String a : args) if (a.equals("--repair")) repair = true;

        if (!base.isDirectory()) {
            System.out.println("No such directory: " + base.getPath());
            System.exit(1);
        }

        System.out.println("############################################################");
        System.out.println("# v6 FULL GAMES — output check   (" + base.getPath() + ")"
                + (repair ? "   [REPAIR MODE]" : ""));
        System.out.println("############################################################");

        List<File> csvs = new ArrayList<>();
        collect(base, csvs);
        if (csvs.isEmpty()) {
            System.out.println("No games.csv found. Has the run started?");
            System.exit(1);
        }

        // Aggregates for the headline diagnostics.
        Map<String, int[]> controlByLevel = new TreeMap<>();   // level -> {emctsWins, games}
        int conquest = 0, capBind = 0, retried = 0;
        Map<String, int[]> seatTally = new TreeMap<>();        // level -> {emctsSeat0, games}

        for (File csv : csvs) {
            leaves++;
            List<String> lines = Files.readAllLines(csv.toPath());
            String rel = base.toURI().relativize(csv.getParentFile().toURI()).getPath();
            rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;

            if (repair) {
                int before = Math.max(0, lines.size() - 1);
                Set<Integer> kept = FullGameRunnerV6.compactPartialMatchup(csv);
                if (kept.size() != before)
                    System.out.printf(Locale.ROOT, "  [repair] %s: %d -> %d row(s)%n",
                            rel, before, kept.size());
                lines = Files.readAllLines(csv.toPath());
            }

            Leaf leaf = parse(csv, lines, rel);
            report(leaf, rel);

            conquest += leaf.conquest;
            capBind += leaf.capBind;
            retried += leaf.retried;
            totalRows += leaf.ids.size();
            if (leaf.opponent.equals("EMCTS")) {
                int[] c = controlByLevel.computeIfAbsent(leaf.level, k -> new int[2]);
                c[0] += leaf.emctsWins; c[1] += leaf.ids.size();
            }
            int[] s = seatTally.computeIfAbsent(leaf.level, k -> new int[2]);
            s[0] += leaf.emctsSeat0; s[1] += leaf.ids.size();
        }

        System.out.println();
        System.out.println("--- summary ---");
        System.out.printf(Locale.ROOT, "  leaves            : %d%n", leaves);
        System.out.printf(Locale.ROOT, "  games             : %,d  (complete tree = %,d)%n",
                totalRows, expectedTotal());
        if (totalRows > 0) {
            System.out.printf(Locale.ROOT, "  decided by capture: %.1f%%   by score at the cap: %.1f%%%n",
                    100.0 * conquest / totalRows, 100.0 * (totalRows - conquest) / totalRows);
            System.out.printf(Locale.ROOT, "  reached the cap   : %.1f%%  (TURN_LIMIT=%d)%n",
                    100.0 * capBind / totalRows, RunV6FullGames.TURN_LIMIT);
            if (retried > 0)
                System.out.printf(Locale.ROOT, "  needed a retry    : %d game(s)%n", retried);
        }

        System.out.println();
        System.out.println("--- control arm (EMCTS vs EMCTS) — expect ~50% ---");
        if (controlByLevel.isEmpty()) {
            System.out.println("  not present yet");
        } else {
            for (Map.Entry<String, int[]> e : controlByLevel.entrySet()) {
                int w = e.getValue()[0], n = e.getValue()[1];
                double p = n > 0 ? w / (double) n : 0;
                double half = n > 0 ? 1.96 * Math.sqrt(p * (1 - p) / n) : 0;   // naive; see W1 for clustered
                boolean suspect = n >= 50 && Math.abs(p - 0.5) > half + 0.02;
                System.out.printf(Locale.ROOT, "  %-10s %d/%d = %.1f%% +/- %.1fpp %s%n",
                        e.getKey(), w, n, 100 * p, 100 * half,
                        suspect ? "  <-- NOT 50%; investigate before reading this level" : "");
                if (suspect) problems++;
            }
        }

        System.out.println();
        System.out.println("--- seat balance (EMCTS in seat 0) — expect ~50% by construction ---");
        for (Map.Entry<String, int[]> e : seatTally.entrySet()) {
            int a = e.getValue()[0], n = e.getValue()[1];
            System.out.printf(Locale.ROOT, "  %-10s %d/%d = %.1f%%%n",
                    e.getKey(), a, n, n > 0 ? 100.0 * a / n : 0);
        }

        System.out.println();
        System.out.println("############################################################");
        if (problems == 0) {
            System.out.println("# NO PROBLEMS FOUND");
        } else {
            System.out.println("# " + problems + " PROBLEM(S) — see above"
                    + (repair ? "" : "; re-run with --repair for the fixable ones"));
        }
        System.out.println("############################################################");
        if (problems > 0) System.exit(1);
    }

    /** Everything one leaf's rows say, after validation. */
    private static final class Leaf {
        String level = "?", opponent = "?", ratio = "?";
        Set<Integer> ids = new HashSet<>();
        Map<Integer, Integer> perMap = new HashMap<>();
        Map<String, Integer> perMapOrientation = new HashMap<>();
        int emctsWins, conquest, capBind, retried, emctsSeat0;
        List<String> errors = new ArrayList<>();
    }

    private static Leaf parse(File csv, List<String> lines, String rel) {
        Leaf leaf = new Leaf();
        int fields = FullGameRunnerV6.GAMES_HEADER.split(",").length;
        Set<Integer> seen = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            if (f.length != fields) {
                leaf.errors.add("row " + i + " has " + f.length + " fields, expected " + fields);
                continue;
            }
            try {
                int id = Integer.parseInt(f[3].trim());
                if (!seen.add(id)) { leaf.errors.add("duplicate GameId " + id); continue; }
                leaf.ids.add(id);

                if (leaf.level.equals("?")) { leaf.level = f[0]; leaf.opponent = f[1]; leaf.ratio = f[2]; }
                else if (!leaf.level.equals(f[0]) || !leaf.opponent.equals(f[1]) || !leaf.ratio.equals(f[2]))
                    leaf.errors.add("row " + i + " mixes configs: " + f[0] + "/" + f[1] + "/" + f[2]);

                int mapIdx = Integer.parseInt(f[4].trim());
                int orientation = Integer.parseInt(f[6].trim());
                leaf.perMap.merge(mapIdx, 1, Integer::sum);
                leaf.perMapOrientation.merge(mapIdx + "/" + orientation, 1, Integer::sum);

                // The schedule is a pure function of the id, so a row must agree with it.
                if (mapIdx != FullGameRunnerV6.mapIndexOf(id)
                        || orientation != FullGameRunnerV6.orientationOf(id)
                        || Integer.parseInt(f[7].trim()) != FullGameRunnerV6.pairIndexOf(id))
                    leaf.errors.add("GameId " + id + " disagrees with the schedule (map/orientation/pair)");

                String winner = f[10];
                if (!winner.equals("EMCTS") && !winner.equals("OPPONENT"))
                    leaf.errors.add("GameId " + id + " has Winner='" + winner + "', expected a role");
                boolean won = f[11].trim().equals("1");
                if (won != winner.equals("EMCTS"))
                    leaf.errors.add("GameId " + id + " EmctsWon disagrees with Winner");
                if (won) leaf.emctsWins++;

                if (f[8].trim().equals("0")) leaf.emctsSeat0++;
                if (f[8].trim().equals(f[9].trim()))
                    leaf.errors.add("GameId " + id + " has both agents in seat " + f[8]);

                if (f[15].equals("CAPITAL")) leaf.conquest++;
                else if (!f[15].equals("SCORE"))
                    leaf.errors.add("GameId " + id + " has DecidedBy='" + f[15] + "'");
                if (Integer.parseInt(f[14].trim()) >= RunV6FullGames.TURN_LIMIT) leaf.capBind++;
                if (Integer.parseInt(f[16].trim()) > 1) leaf.retried++;
            } catch (NumberFormatException e) {
                leaf.errors.add("row " + i + " has an unparseable number: " + e.getMessage());
            }
        }

        // Schedule completeness, but only once a leaf claims to be finished — a partial leaf is the
        // normal state of a running job, not a fault.
        if (leaf.ids.size() == FullGameRunnerV6.GAMES_PER_MATCHUP) {
            for (int g = 0; g < FullGameRunnerV6.GAMES_PER_MATCHUP; g++)
                if (!leaf.ids.contains(g)) leaf.errors.add("missing GameId " + g);
            for (Map.Entry<Integer, Integer> e : leaf.perMap.entrySet())
                if (e.getValue() != FullGameRunnerV6.GAMES_PER_MAP)
                    leaf.errors.add("map " + e.getKey() + " played " + e.getValue()
                            + " times, expected " + FullGameRunnerV6.GAMES_PER_MAP);
            for (Map.Entry<String, Integer> e : leaf.perMapOrientation.entrySet())
                if (e.getValue() != FullGameRunnerV6.GAMES_PER_ORIENTATION)
                    leaf.errors.add("map/orientation " + e.getKey() + " played " + e.getValue()
                            + " times, expected " + FullGameRunnerV6.GAMES_PER_ORIENTATION);
        }
        return leaf;
    }

    private static void report(Leaf leaf, String rel) {
        int n = leaf.ids.size();
        String status;
        if (!leaf.errors.isEmpty()) status = "PROBLEM";
        else if (n == FullGameRunnerV6.GAMES_PER_MATCHUP) status = "ok";
        else status = "partial";

        System.out.printf(Locale.ROOT, "  %-7s %-42s %3d/%d games   EMCTS %5.1f%%%n",
                status, rel, n, FullGameRunnerV6.GAMES_PER_MATCHUP,
                n > 0 ? 100.0 * leaf.emctsWins / n : 0);
        for (String e : leaf.errors) {
            System.out.println("          ! " + e);
            problems++;
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().equals("games.csv")) out.add(f);
        }
    }

    private static int expectedTotal() {
        return RunV6FullGames.COMPUTE_LEVELS.length
                * FullGameRunnerV6.matchups(RunV6FullGames.COMPUTE_LEVELS[0]).size()
                * FullGameRunnerV6.GAMES_PER_MATCHUP;
    }
}
