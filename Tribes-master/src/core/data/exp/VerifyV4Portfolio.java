package core.data.exp;

import core.Constants;
import core.game.FastGameStateIO;
import core.game.GameState;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verifies every persisted scenario in a v4 portfolio. For each {@code scenario_NNN.bin}:
 * <ol>
 *   <li>load + deserialise (catches Serializable regressions on this scenario's class graph),</li>
 *   <li>validate via {@link BuildV4Portfolio#validateScenario} (active tribe has cities + units AND a
 *       tiny EMCTS planTurn does not throw).</li>
 * </ol>
 *
 * <p>Reports a per-scenario PASS/FAIL line and a final summary. Exit code 0 iff every scenario passes —
 * intended to be called by {@link RunExperimentV4} before launching the (long) isolation phase, so a
 * bad scenario fails the run early rather than silently producing 600 wasted tasks.
 *
 * <p>Args: {@code [portfolioDir]} (default {@code test_results/v4/_portfolio}).
 */
public class VerifyV4Portfolio {

    /** Final report: passing scenario IDs, failing scenario IDs, free-form notes. */
    public static final class Report {
        public final List<Integer> passed = new ArrayList<>();
        public final List<Integer> failed = new ArrayList<>();
        public boolean allPass() { return failed.isEmpty(); }
    }

    public static void main(String[] args) throws Exception {
        String portfolioDir = args.length > 0 ? args[0] : "test_results/v4/_portfolio";
        Report rep = verify(new File(portfolioDir));
        System.out.println("\n========== SUMMARY ==========");
        System.out.println("passed: " + rep.passed.size() + " / " + (rep.passed.size() + rep.failed.size()));
        if (!rep.failed.isEmpty()) {
            System.out.println("failed scenario ids: " + rep.failed);
            System.out.println("To replace: delete the failed scenario_NNN.bin files and their entries");
            System.out.println("from _completed.txt, then re-run BuildV4Portfolio (which will regenerate");
            System.out.println("them from extended capture ranges).");
            System.exit(1);
        } else {
            System.out.println("All scenarios start up properly.");
        }
    }

    /** Walks the portfolio directory; runs validation on each {@code scenario_NNN.bin}. */
    public static Report verify(File portfolioDir) throws Exception {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.TURN_TIME_LIMITED = false;
        Constants.REMOVE_FISHING = true;

        File scenariosDir = new File(portfolioDir, "scenarios");
        if (!scenariosDir.isDirectory()) {
            throw new java.io.FileNotFoundException("portfolio scenarios dir not found: "
                    + scenariosDir.getPath());
        }
        File[] files = scenariosDir.listFiles((d, n) -> n.startsWith("scenario_") && n.endsWith(".bin"));
        if (files == null || files.length == 0) {
            throw new java.io.FileNotFoundException("no scenario_*.bin files in " + scenariosDir.getPath());
        }
        java.util.Arrays.sort(files);

        Report rep = new Report();
        long t0 = System.currentTimeMillis();
        System.out.println("[VerifyV4Portfolio] verifying " + files.length + " scenarios from " + scenariosDir.getPath());
        for (File f : files) {
            int id = idFromFile(f);
            String tag = String.format(Locale.ROOT, "scenario_%03d", id);
            try {
                GameState gs = FastGameStateIO.load(f);
                int aid = gs.getActiveTribeID();
                int cities = gs.getTribes()[aid].getNumCities();
                int units = 0;
                if (gs.getTribes()[aid].getCitiesID() != null) {
                    for (Integer cId : gs.getTribes()[aid].getCitiesID()) {
                        core.actors.City c = (core.actors.City) gs.getActor(cId);
                        if (c != null && c.getUnitsID() != null) units += c.getUnitsID().size();
                    }
                }
                boolean ok = BuildV4Portfolio.validateScenario(gs, id);
                System.out.println(String.format(Locale.ROOT,
                        "  %s tick=%d activeTribe=%d cities=%d units=%d → %s",
                        tag, gs.getTick(), aid, cities, units, ok ? "PASS" : "FAIL"));
                if (ok) rep.passed.add(id); else rep.failed.add(id);
            } catch (Throwable t) {
                System.out.println(String.format(Locale.ROOT, "  %s → FAIL (load/validate threw %s)", tag, t));
                rep.failed.add(id);
            }
        }
        System.out.println(String.format(Locale.ROOT,
                "[VerifyV4Portfolio] done in %.1fs", (System.currentTimeMillis() - t0) / 1000.0));
        return rep;
    }

    private static int idFromFile(File f) {
        String n = f.getName();   // scenario_NNN.bin
        int u = n.indexOf('_'), d = n.lastIndexOf('.');
        return Integer.parseInt(n.substring(u + 1, d));
    }
}
