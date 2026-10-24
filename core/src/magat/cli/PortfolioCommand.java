package magat.cli;

import magat.agent.AgentFactory;
import magat.game.Game;
import magat.game.GameModule;
import magat.portfolio.Identity;
import magat.portfolio.Manifest;
import magat.portfolio.PortfolioBuilder;
import magat.portfolio.PortfolioVerify;
import magat.portfolio.Split;
import magat.run.RunGuard;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** {@code magat portfolio build | freeze | verify}. */
final class PortfolioCommand {

    private PortfolioCommand() {}

    static void usage() {
        System.out.println("  portfolio build   --game <name> --out <dir> [--threads N] [--targets single=200,dual=200]");
        System.out.println("  portfolio freeze  --portfolio <dir>");
        System.out.println("  portfolio verify  --game <name> --portfolio <dir> [--published <dir>] [--subset] [--plan N] [--arm emcts] [--budget 8000]");
    }

    static int run(Args args) throws Exception {
        String what = args.word(1);
        if ("build".equals(what)) return build(args);
        if ("freeze".equals(what)) return freeze(args);
        if ("verify".equals(what)) return verify(args);
        throw new IllegalArgumentException("portfolio what? build | freeze | verify");
    }

    private static <S, A> int build(Args args) throws Exception {
        Main.banner("portfolio build");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Path out = Paths.get(args.require("out")).toAbsolutePath();
        int threads = args.getInt("threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        Map<String, Integer> targets = PortfolioBuilder.targets(game.scenarioSources(), args.get("targets"));
        System.out.println("# game: " + game.id() + "  " + game.configuration());
        RunGuard.install("portfolio build", "re-run the same command; finished source games are read from the journal, not replayed");
        PortfolioBuilder.Result r = PortfolioBuilder.build(game, targets, out, threads);
        RunGuard.finished();
        if (r.complete && !r.skipped)
            System.out.println("Next: magat portfolio freeze --portfolio " + out);
        System.out.println(r.complete ? "PORTFOLIO: COMPLETE " + r.identity : "PORTFOLIO: INCOMPLETE");
        return r.complete ? 0 : 1;
    }

    private static int freeze(Args args) throws Exception {
        Main.banner("portfolio freeze");
        Path dir = Paths.get(args.require("portfolio")).toAbsolutePath();
        Manifest m = Manifest.read(dir);
        System.out.println("# portfolio: " + dir + "  (" + m.size() + " scenarios)");
        Split.Frozen f = Split.freeze(dir);
        System.out.println(f.written ? "froze the split:" : "already frozen; the split files match the manifest:");
        for (String line : f.report.trim().split("\n")) System.out.println("  " + line);
        System.out.println("FROZEN: " + f.identity);
        return 0;
    }

    private static <S, A> int verify(Args args) throws Exception {
        Main.banner("portfolio verify");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Path dir = Paths.get(args.require("portfolio")).toAbsolutePath();
        PortfolioVerify.Against<S, A> against = null;
        if (args.has("published")) {
            against = new PortfolioVerify.Against<>();
            against.published = Paths.get(args.get("published")).toAbsolutePath();
            against.subset = args.flag("subset");
            against.plan = args.getInt("plan", 0);
            against.budget = args.getLongs("budget", new long[]{8_000L})[0];
            if (against.plan > 0) {
                AgentFactory<S, A> arm = module.agents().get(args.get("arm", "emcts"));
                against.arm = arm;
            }
            System.out.println("# against: " + against.published + (against.subset ? " (subset)" : ""));
        }
        System.out.println("# portfolio: " + dir);
        PortfolioVerify.Report r = PortfolioVerify.run(game, dir, against);
        r.print();
        System.out.println(r.ok() ? "VERIFY: PASS" : "VERIFY: FAIL (" + r.failures.size() + " check(s))");
        return r.ok() ? 0 : 1;
    }
}
