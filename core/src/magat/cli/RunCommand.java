package magat.cli;

import magat.calibrate.ThreadSweep;
import magat.game.GameModule;
import magat.machine.MachineProfile;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.Split;
import magat.run.Experiment;
import magat.run.GridRunner;
import magat.run.RunGuard;
import magat.run.Validator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

/** {@code magat run} and {@code magat experiment validate}. */
final class RunCommand {

    private RunCommand() {}

    static void usage() {
        System.out.println("  run               --game <name> --experiment <spec.json> --portfolio <dir> --weights <file> [--out <run>]");
        System.out.println("                    [--split dev|holdout|all] [--threads N] [--threads-at 400000=31,...] [--threads-file threads.json]");
        System.out.println("                    [--machine-factor 1.5] [--retire-leaf arm@budget,...] [--toy] [--audit-warn 0.10]");
        System.out.println("                    [--levels-file levels.json]   the game's published levels, for levels named \"500ms\"");
        System.out.println("                    [--reuse <split tree>[,<split tree>...] [--reuse-replay 2]]   take the leaves other runs played at");
        System.out.println("                    this run's prices, each from the first that holds it whole");
        System.out.println("  experiment validate --game <name> --experiment <spec.json> [--weights <file>] [--portfolio <dir>] [--split ...]");
        System.out.println("                    [--threads N] [--levels-file levels.json] [--toy]   with the portfolio: the positions it selects,");
        System.out.println("                    and the run's size and estimated hours");
    }

    /** The spec, its levels named against the game's published ones when {@code --levels-file} is given. */
    static Experiment spec(Args args) throws java.io.IOException {
        Map<String, Long> named = Experiment.levelNames(args.has("levels-file") ? Paths.get(args.get("levels-file")) : null);
        return Experiment.load(Paths.get(args.require("experiment")), named);
    }

    static <S, A> int run(Args args, String[] argv) throws Exception {
        Main.banner("run");
        GameModule<S, A> module = Games.load(args.require("game"));
        Experiment x = spec(args);
        Weights weights = Weights.load(Paths.get(args.require("weights")));
        Portfolio<S> portfolio = Portfolio.load(module.game(), Paths.get(args.require("portfolio")));
        GridRunner.Options o = new GridRunner.Options();
        if (args.has("split")) o.split = Split.Half.parse(args.get("split"));
        String data = System.getProperty("magat.data");
        if (args.has("out")) o.out = Paths.get(args.get("out")).toAbsolutePath();
        else if (data != null && x.name != null) o.out = Paths.get(data, module.game().id(), "runs", x.name);
        else throw new IllegalArgumentException("--out is required (no data root is set)");
        threads(args, weights, o);
        o.retire = new LinkedHashSet<>(args.getList("retire-leaf", new ArrayList<>()));
        o.toy = args.flag("toy");
        o.auditWarn = Double.parseDouble(args.get("audit-warn", "0.10"));
        for (String from : args.getList("reuse", new ArrayList<>())) o.reuse.add(Paths.get(from).toAbsolutePath());
        if (!x.reuse && !o.reuse.isEmpty())
            throw new IllegalArgumentException("the spec says \"reuse\": false, so every leaf is played: leave out --reuse");
        o.reuseReplay = args.getInt("reuse-replay", 2);
        o.args = Arrays.asList(argv);
        RunGuard.install("run " + x.name, "re-run the same command; finished leaves are skipped and a partial leaf continues per scenario and visit");
        int code = GridRunner.run(module, x, portfolio, weights, o);
        RunGuard.finished();
        return code;
    }

    /**
     * The thread choices: --threads, else --threads-file, else the threads.json beside the currency when it was swept on
     * this CPU model, else the machine's own worker ceiling. A currency published from another machine carries that
     * machine's sweep, which says nothing of this one.
     */
    private static void threads(Args args, Weights weights, GridRunner.Options o) throws java.io.IOException {
        o.threads = args.getInt("threads", 0);
        for (String kv : args.getList("threads-at", new ArrayList<>())) {
            String[] p = kv.split("=");
            if (p.length != 2) throw new IllegalArgumentException("--threads-at takes budget=threads pairs, not '" + kv + "'");
            o.threadsAt.put(Long.parseLong(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        Path defaultSweep = weights.source().toAbsolutePath().getParent().resolve("threads.json");
        o.threadsFile = args.has("threads-file") ? Paths.get(args.get("threads-file")) : Files.isRegularFile(defaultSweep) ? defaultSweep : null;
        String swept = o.threadsFile == null || args.has("threads-file") ? null : ThreadSweep.machineModel(o.threadsFile);
        if (swept != null && !swept.equals(MachineProfile.get().cpuModel())) {
            System.out.println("# " + o.threadsFile + " was swept on " + swept + ", not this machine: its thread choices are not used "
                    + "(this machine's ceiling, " + MachineProfile.get().maxWorkers() + ", unless --threads or --threads-file says otherwise)");
            o.threadsFile = null;
        }
        o.machineFactor = Double.parseDouble(args.get("machine-factor", "1.0"));
    }

    static <S, A> int validate(Args args) throws Exception {
        GameModule<S, A> module = Games.load(args.require("game"));
        Experiment x = spec(args);
        Weights weights = args.has("weights") ? Weights.load(Paths.get(args.get("weights"))) : null;
        Split.Half half = Split.Half.parse(args.get("split", x.split == null ? "all" : x.split));
        System.out.println("# experiment " + x.name + " (" + x.source + "), split " + half.key()
                + (weights == null ? "" : ", weights " + weights.source()));
        Validator.Report r = Validator.check(x, module.game().id(), module.agents(), half, weights, args.flag("toy"));
        Portfolio<S> portfolio = args.has("portfolio") ? Portfolio.load(module.game(), Paths.get(args.get("portfolio"))) : null;
        if (portfolio != null && r.ok()) Validator.checkSelection(x, portfolio.scenarios(), portfolio.dir(), half, r);
        r.print();
        if (r.ok()) describe(x, portfolio, half, weights, args);
        System.out.println(r.ok() ? "VALIDATION: PASS" + (r.warnings.isEmpty() ? "" : " (" + r.warnings.size() + " warning(s))")
                : "VALIDATION: FAIL (" + r.errors.size() + " error(s))");
        return r.ok() ? 0 : 1;
    }

    /** What the spec asks for, written out: its levels, its contrasts when they are the default, the positions and the estimate. */
    private static <S> void describe(Experiment x, Portfolio<S> portfolio, Split.Half half, Weights weights, Args args) throws Exception {
        System.out.println("  arms      " + String.join(", ", x.armIds()) + "   baseline " + x.baseline);
        StringBuilder lv = new StringBuilder();
        for (Experiment.Level l : x.levels)
            lv.append(lv.length() == 0 ? "" : ", ").append(l.written.equals(String.valueOf(l.budget)) ? "" : l.written + " = ")
                    .append(l.budget).append(" x ").append(l.visits).append(l.arms == null ? "" : " " + l.arms);
        System.out.println("  levels    " + lv + "   (units x repetitions)");
        if (x.contrastsByDefault) System.out.println("  contrasts every pair, by default: " + x.contrasts.size());
        if (portfolio == null) {
            System.out.println("  (give --portfolio for the positions and the estimate)");
            return;
        }
        int n = x.select(Split.apply(portfolio.scenarios(), portfolio.dir(), half)).size();
        System.out.println("  positions " + n + (half == Split.Half.ALL ? " of the whole portfolio" : " of the " + half.key() + " half")
                + (x.selects() ? " (" + x.selection() + ")" : ""));
        if (weights == null) return;
        GridRunner.Options o = new GridRunner.Options();
        threads(args, weights, o);
        Map<Long, Integer> sweep = o.threadsFile != null && Files.isRegularFile(o.threadsFile) ? ThreadSweep.read(o.threadsFile) : null;
        for (String line : GridRunner.estimate(x, n, weights, o, sweep)) System.out.println(" " + line);
    }
}
