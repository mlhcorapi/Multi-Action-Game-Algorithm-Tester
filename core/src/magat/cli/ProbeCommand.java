package magat.cli;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import magat.agent.AgentFactory;
import magat.agent.TurnBudget;
import magat.calibrate.Calibrator;
import magat.game.Game;
import magat.game.GameModule;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.probe.OverheadCheck;
import magat.probe.ReplayCheck;
import magat.probe.TimeCheck;
import magat.run.RunGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code magat check replay | overhead | time}. */
final class ProbeCommand {

    private ProbeCommand() {}

    static void usage() {
        System.out.println("  check replay      --game <name> --leaf <published leaf dir> --portfolio <dir> --weights <file> [--turns 20]");
        System.out.println("                    [--arm id] [--budget units] [--map tools/legacy-arms.json] [--threads N]");
        System.out.println("  check overhead    --game <name> --portfolio <dir> --weights <file> [--arm emcts] [--budget 40000] [--reps 3] [--turns 8]");
        System.out.println("  check overhead --audit --game <name> --portfolio <dir> [--arms a,b|@set] [--budget 40000] [--turns 4] [--reps 2]");
        System.out.println("                    [--warn 0.10] [--fail 0.25]: how much of each arm's turn its declared operations account for");
    }

    private static int threads(Args args) {
        return args.getInt("threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    static <S, A> int replay(Args args) throws Exception {
        Main.banner("check replay");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Path dir = Paths.get(args.require("leaf"));
        ReplayCheck.Leaf leaf = ReplayCheck.read(dir);
        String arm = args.get("arm");
        if (arm == null && leaf.format.equals("thesis")) {
            Path map = Paths.get(args.get("map", "tools/legacy-arms.json"));
            JsonObject m = Json.parse(new String(Files.readAllBytes(map), StandardCharsets.UTF_8)).asObject();
            if (m.get(leaf.arm) == null) throw new IllegalArgumentException("no MAGAT arm for thesis config '" + leaf.arm + "' in " + map);
            arm = m.getString(leaf.arm, null);
        } else if (arm == null) {
            arm = leaf.arm;
        }
        long budget;
        if (args.has("budget")) budget = Long.parseLong(args.get("budget"));
        else {
            Matcher mm = Pattern.compile("(\\d+)cu$").matcher(dir.toAbsolutePath().normalize().getFileName().toString());
            if (!mm.find()) throw new IllegalArgumentException("cannot read the budget from " + dir + "; pass --budget");
            budget = Long.parseLong(mm.group(1));
        }
        Weights w = Weights.load(Paths.get(args.require("weights")));
        Meter.install(w);
        Portfolio<S> p = Portfolio.load(game, Paths.get(args.require("portfolio")));
        int k = args.getInt("turns", 20);
        System.out.println(String.format(Locale.ROOT, "# leaf %s (%s format, %d turns, written by %s) -> arm %s @ %d", dir, leaf.format, leaf.size(), leaf.arm, arm, budget));
        System.out.println("# weights " + w.summary() + "; portfolio " + p.identity().substring(0, 12) + "...");
        AgentFactory<S, A> factory = module.agents().get(arm);
        ReplayCheck.Result r = ReplayCheck.run(game, p, factory, budget, leaf, k, threads(args));
        for (String f : r.failures) System.out.println("  [FAIL] " + f);
        System.out.println(r.pass() ? String.format(Locale.ROOT, "REPLAY: PASS (%d/%d published turns reproduced field for field)", r.reproduced, r.replayed)
                : String.format(Locale.ROOT, "REPLAY: FAIL (%d of %d reproduced)", r.reproduced, r.replayed));
        return r.pass() ? 0 : 1;
    }

    static <S, A> int overhead(Args args) throws Exception {
        if (args.flag("audit")) return audit(args);
        Main.banner("check overhead");
        GameModule<S, A> module = Games.load(args.require("game"));
        Weights w = Weights.load(Paths.get(args.require("weights")));
        Meter.install(w);
        Portfolio<S> p = Portfolio.load(module.game(), Paths.get(args.require("portfolio")));
        String arm = args.get("arm", module.referenceArm());
        long budget = Long.parseLong(args.get("budget", "40000"));
        int turns = args.getInt("turns", 8);
        List<Scenario<S>> positions = new ArrayList<>();
        for (int i = 0; i < turns; i++) positions.add(calibrationPosition(p.scenarios(), i, turns));
        System.out.println("# " + arm + "@" + budget + ", " + turns + " positions (the calibrator's pool), " + args.getInt("reps", 3)
                + " repetitions, single-threaded; idle machine wanted");
        OverheadCheck.Result r = OverheadCheck.run(module.game(), module.agents().get(arm), budget, positions, args.getInt("reps", 3), args.getInt("warmup", 3));
        System.out.println(r.supported ? "OVERHEAD: MEASURED" : "OVERHEAD: NOT SUPPORTED");
        return 0;
    }

    /**
     * {@code check overhead --audit}: for each arm, the share of its turn time that neither the forward model
     * nor the operations it declares account for. Single-threaded, every turn profiled, priced with the
     * fixed capture weights (the shares do not depend on a currency); the fastest repetition counts.
     * Warns above {@code --warn} and fails above {@code --fail}.
     */
    static <S, A> int audit(Args args) throws Exception {
        Main.banner("check overhead --audit");
        GameModule<S, A> module = Games.load(args.require("game"));
        Portfolio<S> p = Portfolio.load(module.game(), Paths.get(args.require("portfolio")));
        List<String> ids = module.expandArms(args.getList("arms", new ArrayList<>(java.util.Collections.singletonList(module.referenceArm()))));
        List<AgentFactory<S, A>> arms = new ArrayList<>();
        for (String id : ids) arms.add(module.agents().get(id));
        long budget = Long.parseLong(args.get("budget", "40000"));
        int turns = args.getInt("turns", 4);
        List<Scenario<S>> positions = new ArrayList<>();
        for (int i = 0; i < turns; i++) positions.add(calibrationPosition(p.scenarios(), i, turns));
        double warn = Double.parseDouble(args.get("warn", String.valueOf(OverheadCheck.AUDIT_WARN)));
        double fail = Double.parseDouble(args.get("fail", String.valueOf(OverheadCheck.AUDIT_FAIL)));
        System.out.println(String.format(Locale.ROOT, "# %d arm(s) @ %d, %d positions (the calibrator's pool), %d repetitions, single-threaded; "
                + "warn above %.0f%%, fail above %.0f%% unaccounted", arms.size(), budget, turns, args.getInt("reps", 2), 100 * warn, 100 * fail));
        List<OverheadCheck.Audit> r = OverheadCheck.audit(module.game(), arms, budget, positions, args.getInt("reps", 2), args.getInt("warmup", 1));
        boolean ok = OverheadCheck.report(r, warn, fail);
        System.out.println(ok ? "OVERHEAD AUDIT: PASS" : "OVERHEAD AUDIT: FAIL");
        return ok ? 0 : 1;
    }

    /** The calibrator's position rule (a pool of eight for its eight turns), so the overhead is measured on the positions a currency was. */
    private static <S> Scenario<S> calibrationPosition(List<Scenario<S>> portfolio, int i, int turns) {
        return Calibrator.positionFor(portfolio, i, turns, Math.max(8, turns));
    }

    /**
     * {@code magat check time}: single-threaded whole turns of arms at levels under a currency (or the fixed
     * measurement weights), over positions spread across the portfolio; the mean is the level's thinking time.
     */
    static <S, A> int time(Args args) throws Exception {
        Main.banner("check time");
        GameModule<S, A> module = Games.load(args.require("game"));
        Portfolio<S> p = Portfolio.load(module.game(), Paths.get(args.require("portfolio")));
        TurnBudget.setInitialization(TurnBudget.Initialization.of(args.get("initialization", TurnBudget.Initialization.IN_BUDGET.id)));
        long[] levels = args.getLongs("levels", args.getLongs("default-levels", null));
        if (levels == null) throw new IllegalArgumentException("check time needs --levels (no published levels for this game)");
        List<String> armIds = args.has("arms") ? module.expandArms(args.getList("arms", new ArrayList<>()))
                : java.util.Collections.singletonList(module.referenceArm());
        List<AgentFactory<S, A>> arms = new ArrayList<>();
        for (String a : armIds) arms.add(module.agents().get(a));
        double[] targets = null;
        if (args.has("targets")) {
            long[] t = args.getLongs("targets", null);
            if (t.length != levels.length) throw new IllegalArgumentException("--targets needs one time (ms) per level");
            targets = new double[t.length];
            for (int i = 0; i < t.length; i++) targets[i] = t[i];
        }
        boolean fixed = args.flag("fixed-weights");
        String weights = "fixed measurement weights";
        if (!fixed) {
            Weights w = Weights.load(Paths.get(args.require("weights")));
            Meter.install(w);
            weights = w.source().toAbsolutePath().toString();
        }
        int positions = args.getInt("positions", TimeCheck.DEFAULT_POSITIONS);
        double tolerance = Double.parseDouble(args.get("tolerance", String.valueOf(TimeCheck.DEFAULT_TOLERANCE)));
        System.out.println(String.format(Locale.ROOT, "# %d arm(s) at %s, single-threaded, one turn on each of %d positions spread over the "
                + "portfolio; priced by %s; initialization %s", arms.size(), Arrays.toString(levels), Math.min(positions, p.size()),
                weights, TurnBudget.initialization()));
        System.out.println(TimeCheck.header());
        RunGuard.install("check time", "re-run the same command");
        List<TimeCheck.Cell> cells = TimeCheck.run(module.game(), arms, p.scenarios(), levels, targets, positions,
                args.getInt("warmup", TimeCheck.DEFAULT_WARMUP), fixed);
        RunGuard.finished();
        if (args.has("out")) {
            Path out = Paths.get(args.get("out")).toAbsolutePath();
            TimeCheck.write(out, TimeCheck.json(cells, weights, Math.min(positions, p.size()), module.game().id()));
            System.out.println("# -> " + out);
        }
        if (targets == null) {
            System.out.println("TIME: MEASURED");
            return 0;
        }
        boolean ok = true;
        for (TimeCheck.Cell c : cells) ok &= c.within(tolerance);
        System.out.println(ok ? String.format(Locale.ROOT, "TIME: PASS (every cell's mean within %.0f%% of its target)", 100 * tolerance)
                : String.format(Locale.ROOT, "TIME: OUTSIDE (a cell's mean is more than %.0f%% from its target)", 100 * tolerance));
        return ok ? 0 : 1;
    }
}
