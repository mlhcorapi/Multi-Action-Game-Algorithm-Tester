package magat.cli;

import magat.game.GameModule;
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

/** {@code magat run} and {@code magat experiment validate}. */
final class RunCommand {

    private RunCommand() {}

    static void usage() {
        System.out.println("  run               --game <name> --experiment <spec.json> --portfolio <dir> --weights <file> [--out <run>]");
        System.out.println("                    [--split dev|holdout|all] [--threads N] [--threads-at 400000=31,...] [--threads-file threads.json]");
        System.out.println("                    [--machine-factor 1.5] [--frozen <file>] [--retire-leaf arm@budget,...] [--toy] [--audit-warn 0.10]");
        System.out.println("  experiment validate --game <name> --experiment <spec.json> [--weights <file>] [--split ...] [--frozen <file>] [--toy]");
    }

    static <S, A> int run(Args args, String[] argv) throws Exception {
        Main.banner("run");
        GameModule<S, A> module = Games.load(args.require("game"));
        Experiment x = Experiment.load(Paths.get(args.require("experiment")));
        Weights weights = Weights.load(Paths.get(args.require("weights")));
        Portfolio<S> portfolio = Portfolio.load(module.game(), Paths.get(args.require("portfolio")));
        GridRunner.Options o = new GridRunner.Options();
        if (args.has("split")) o.split = Split.Half.parse(args.get("split"));
        String data = System.getProperty("magat.data");
        if (args.has("out")) o.out = Paths.get(args.get("out")).toAbsolutePath();
        else if (data != null && x.name != null) o.out = Paths.get(data, module.game().id(), "runs", x.name);
        else throw new IllegalArgumentException("--out is required (no data root is set)");
        o.threads = args.getInt("threads", 0);
        for (String kv : args.getList("threads-at", new ArrayList<>())) {
            String[] p = kv.split("=");
            if (p.length != 2) throw new IllegalArgumentException("--threads-at takes budget=threads pairs, not '" + kv + "'");
            o.threadsAt.put(Long.parseLong(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        Path defaultSweep = weights.source().toAbsolutePath().getParent().resolve("threads.json");
        o.threadsFile = args.has("threads-file") ? Paths.get(args.get("threads-file")) : Files.isRegularFile(defaultSweep) ? defaultSweep : null;
        o.machineFactor = Double.parseDouble(args.get("machine-factor", "1.0"));
        if (args.has("frozen")) o.frozen = Paths.get(args.get("frozen")).toAbsolutePath();
        o.retire = new LinkedHashSet<>(args.getList("retire-leaf", new ArrayList<>()));
        o.toy = args.flag("toy");
        o.auditWarn = Double.parseDouble(args.get("audit-warn", "0.10"));
        o.args = Arrays.asList(argv);
        RunGuard.install("run " + x.name, "re-run the same command; finished leaves are skipped and a partial leaf continues per scenario and visit");
        int code = GridRunner.run(module, x, portfolio, weights, o);
        RunGuard.finished();
        return code;
    }

    static <S, A> int validate(Args args) throws Exception {
        GameModule<S, A> module = Games.load(args.require("game"));
        Experiment x = Experiment.load(Paths.get(args.require("experiment")));
        Weights weights = args.has("weights") ? Weights.load(Paths.get(args.get("weights"))) : null;
        Split.Half half = Split.Half.parse(args.get("split", x.split == null ? "all" : x.split));
        System.out.println("# experiment " + x.name + " (" + x.source + "), split " + half.key()
                + (weights == null ? "" : ", weights " + weights.source()));
        Validator.Report r = Validator.check(x, module.game().id(), module.agents(), half, weights, args.get("frozen"), args.flag("toy"));
        r.print();
        System.out.println(r.ok() ? "VALIDATION: PASS" + (r.warnings.isEmpty() ? "" : " (" + r.warnings.size() + " warning(s))")
                : "VALIDATION: FAIL (" + r.errors.size() + " error(s))");
        return r.ok() ? 0 : 1;
    }
}
