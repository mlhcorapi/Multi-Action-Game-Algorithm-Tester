package core.data.exp;

import core.game.GameState;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Fast validation of the v3 changes (into test_results/smoke_v3/):
 *   1. Capture a fixed turn-3 start position (EMCTS warmup) and run a tiny Phase 3 from it - verifying the
 *      tracked agent's logged turns BEGIN AT turn 3 (degenerate opening skipped).
 *   2. Run a tiny Phase 2 with the v3 settings - baselines EMCTS+OEP only (NO MCTS) and an all-ratios
 *      override at the single time level - verifying no MCTS matchups appear.
 * If this passes, the turn-3 fixed-start machinery and the Phase-2 flexibility (no-MCTS, all-ratios) work.
 */
public class NewExperimentSmokeTestV3 {

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        String base = "test_results/smoke_v3";
        deleteDir(new File(base));
        int threads = 4;
        int[] ratios = {5, 20};

        System.out.println("==================== 1. CAPTURE fixed turn-3 start ====================");
        GameState start = RunExperimentV3.captureStartState();
        System.out.println("captured tick = " + start.getTick());

        System.out.println("==================== 2. PHASE 3 v3 (from turn 3) ====================");
        Budget[] levels = { new Budget("40ms", 2000, 3) };
        Phase3Runner.run(3, levels, ratios, threads, base + "/phase3", null, start);
        Map<String, Integer> best = Phase3Runner.bestRatiosByLevel(base + "/phase3");

        System.out.println("==================== 3. PHASE 2 v3 (no MCTS; all ratios @40ms) ====================");
        String[] baselines = {"OEP", "EMCTS"};
        Map<Integer, int[]> ratiosOverride = new HashMap<>(); ratiosOverride.put(40, ratios);
        Map<Integer, Integer> gamesOverride = new HashMap<>(); gamesOverride.put(40, 2);
        Phase2Runner.run(2, 8, new int[]{40}, best, threads, base + "/phase2", baselines, ratiosOverride, gamesOverride);

        System.out.println("\n==================== VERIFICATION ====================");
        int minTurn = minTurnNumber(new File(base + "/phase3/Phase3_ratio_turns_40ms.csv"));
        boolean startsAt3 = (minTurn == RunExperimentV3.START_TURN);
        System.out.println("Phase-3 min TurnNumber = " + minTurn + " (expected " + RunExperimentV3.START_TURN + ") -> " + startsAt3);

        Set<String> p2bases = phase2Baselines(new File(base + "/phase2/Phase2_games_40ms.csv"));
        boolean noMcts = !p2bases.contains("MCTS") && p2bases.contains("EMCTS") && p2bases.contains("OEP");
        System.out.println("Phase-2 baselines = " + new TreeSet<>(p2bases) + " (expected EMCTS,OEP; no MCTS) -> " + noMcts);

        verifyDir(new File(base + "/phase3"));
        verifyDir(new File(base + "/phase2"));
        System.out.println("Total smoke_v3 time: " + (System.currentTimeMillis() - t0) / 1000.0 + "s");
        System.out.println((startsAt3 && noMcts)
                ? "PASS: Phase 3 starts at turn 3 from the fixed position; Phase 2 drops MCTS."
                : "FAIL: see the two checks above.");
    }

    /** Smallest TurnNumber (col 3) among CrossoverExpansion rows of a Phase3_ratio_turns CSV. */
    private static int minTurnNumber(File csv) {
        int min = Integer.MAX_VALUE;
        if (!csv.exists()) return -1;
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",");
                if (f.length < 4 || !f[0].trim().equals("CrossoverExpansion")) continue;
                min = Math.min(min, Integer.parseInt(f[3].trim()));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return (min == Integer.MAX_VALUE) ? -1 : min;
    }

    /** Distinct baseline names (right side of "<exp>_vs_<base>") in a Phase2_games CSV. */
    private static Set<String> phase2Baselines(File csv) {
        Set<String> bases = new HashSet<>();
        if (!csv.exists()) return bases;
        try {
            List<String> lines = Files.readAllLines(csv.toPath());
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).split(",");
                if (f.length < 2) continue;
                String[] mm = f[1].split("_vs_");          // Matchup column
                if (mm.length == 2) bases.add(mm[1].trim());
            }
        } catch (Exception e) { e.printStackTrace(); }
        return bases;
    }

    private static void verifyDir(File dir) {
        System.out.println("--- " + dir.getPath() + " ---");
        File[] files = dir.listFiles();
        if (files == null) { System.out.println("  (no output!)"); return; }
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".csv")) continue;
            long lines = 0;
            try { lines = Files.lines(f.toPath()).count(); } catch (Exception ignored) {}
            System.out.println(String.format("  %-42s %d lines", f.getName(), lines));
        }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }
}
