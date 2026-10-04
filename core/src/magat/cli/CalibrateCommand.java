package magat.cli;

import magat.calibrate.Calibrator;
import magat.calibrate.ThreadSweep;
import magat.game.Game;
import magat.game.GameModule;
import magat.machine.MachineProfile;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.run.RunGuard;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** {@code magat calibrate}, {@code magat threads}, {@code magat machine}, {@code magat weights}. */
final class CalibrateCommand {

    private CalibrateCommand() {}

    static void usage() {
        System.out.println("  machine");
        System.out.println("  threads           --game <name> --portfolio <dir> --out <calib> [--levels 40000,80000,400000] [--ladder 2,4,8] [--segment-seconds 25] [--arm emcts]");
        System.out.println("  calibrate         --game <name> --portfolio <dir> --out <calib> [--levels ...] [--arms a,b|@set] [--reps 3] [--turns 8,4,2]");
        System.out.println("                    [--warmup 6] [--replace a,b] [--under-load W] [--toy]");
        System.out.println("  calibrate --check --game <name> --portfolio <dir> --weights <file> --arms a,b [--levels ...]");
        System.out.println("  weights           --weights <file> [--game <name> --arms a,b|@set --levels 40000,80000,400000]");
    }

    static int machine() {
        MachineProfile m = MachineProfile.get();
        m.printBanner("machine report (no work is run)");
        System.out.println("thread ladder a sweep would measure: " + Arrays.toString(m.threadLadder()));
        double clock = MachineProfile.currentClockMHz(), load = MachineProfile.loadPerCore();
        System.out.println("mean core clock: " + (clock > 0 ? String.format(Locale.ROOT, "%.0f MHz", clock) : "not exposed by this OS"));
        System.out.println("load average: " + (load >= 0 ? String.format(Locale.ROOT, "%.2f per core", load) : "not exposed by this OS"));
        System.out.println("Ceiling = physical cores - reserve, capped by MAGAT_MAX_THREADS; MAGAT_RESERVE_CORES changes the reserve.");
        return 0;
    }

    static <S, A> int threads(Args args) throws Exception {
        Main.banner("threads");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Portfolio<S> p = Portfolio.load(game, Paths.get(args.require("portfolio")));
        ThreadSweep.Options o = new ThreadSweep.Options();
        o.levels = args.getLongs("levels", o.levels);
        if (args.has("ladder")) {
            long[] l = args.getLongs("ladder", null);
            o.ladder = new int[l.length];
            for (int i = 0; i < l.length; i++) o.ladder[i] = (int) l[i];
        }
        o.segmentMillis = 1000L * args.getInt("segment-seconds", 25);
        Path out = Paths.get(args.require("out")).toAbsolutePath();
        RunGuard.install("thread sweep", "re-run the same command; each level's sweep is minutes");
        List<ThreadSweep.Level> levels = ThreadSweep.run(game, module.agents().get(args.get("arm", module.referenceArm())),
                p.scenarios(), p.identity(), out, o);
        RunGuard.finished();
        StringBuilder sb = new StringBuilder();
        for (ThreadSweep.Level l : levels) sb.append(' ').append(l.budget).append('=').append(l.chosen).append(l.estimate ? "(estimate)" : "");
        System.out.println("THREADS:" + sb);
        return levels.size() == o.levels.length ? 0 : 1;
    }

    static <S, A> int calibrate(Args args) throws Exception {
        boolean check = args.flag("check");
        Main.banner(check ? "calibrate --check" : "calibrate");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Portfolio<S> p = Portfolio.load(game, Paths.get(args.require("portfolio")));
        Calibrator.Options o = new Calibrator.Options();
        o.check = check;
        o.levels = args.getLongs("levels", o.levels);
        if (args.has("arms")) o.arms = module.expandArms(args.getList("arms", new ArrayList<>()));
        o.reps = args.getInt("reps", o.reps);
        o.turns = args.get("turns");
        o.warmup = args.getInt("warmup", o.warmup);
        o.underLoad = args.getInt("under-load", 0);
        o.replace = new LinkedHashSet<>(module.expandArms(args.getList("replace", new ArrayList<>())));
        o.toy = args.flag("toy");
        if (check && o.underLoad > 1) throw new IllegalArgumentException("--check and --under-load measure different things; run them separately");
        if (check && o.arms == null) throw new IllegalArgumentException("--check needs --arms: the already-priced arms to re-measure");
        Path out = check ? null : Paths.get(args.require("out")).toAbsolutePath();
        Path checkFile = check ? Paths.get(args.require("weights")).toAbsolutePath() : null;
        RunGuard.install("calibration", "re-run the same command; a calibration writes only when it completes");
        Calibrator.Result r = Calibrator.run(game, module.agents(), module.referenceArm(), p, out, checkFile, o);
        RunGuard.finished();
        System.out.println(r.pass ? (check ? "CHECK: PASS" : "CALIBRATION: PASS " + r.written) : (check ? "CHECK: FAIL" : "CALIBRATION: FAIL"));
        return r.pass ? 0 : 1;
    }

    /** Coverage: which of the given arms and levels a weights file prices. */
    static <S, A> int weights(Args args) throws Exception {
        Weights w = Weights.load(Paths.get(args.require("weights")));
        System.out.println("# " + w.summary());
        System.out.println("# game " + w.game() + ", turnsPerRep " + w.turnsPerRep() + ", portfolio " + w.portfolioIdentity()
                + ("true".equals(w.header().get("toy")) ? "   (TOY)" : ""));
        if (!args.has("arms")) {
            for (String profile : w.profiles()) {
                double[] x = w.profile(profile);
                System.out.println(String.format(Locale.ROOT, "  %-40s %9.6f %9.6f %9.6f", profile, x[0], x[1], x[2]));
            }
            return 0;
        }
        List<String> arms = args.getList("arms", new ArrayList<>());
        if (args.has("game")) {
            GameModule<S, A> module = Games.load(args.get("game"));
            arms = module.expandArms(arms);
        }
        long[] levels = args.getLongs("levels", new long[]{40_000L, 80_000L, 400_000L});
        List<String> want = new ArrayList<>();
        for (String a : arms) for (long l : levels) want.add(Profile.key(a, l));
        List<String> missing = w.missing(want);
        System.out.println(String.format(Locale.ROOT, "# %d arm(s) x %d level(s): %d of %d profiles priced", arms.size(), levels.length,
                want.size() - missing.size(), want.size()));
        for (String m : missing) System.out.println("  missing: " + m);
        System.out.println(missing.isEmpty() ? "WEIGHTS: COVERED" : "WEIGHTS: " + missing.size() + " MISSING");
        return missing.isEmpty() ? 0 : 1;
    }
}
