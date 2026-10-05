package magat.cli;

import magat.agent.AgentFactory;
import magat.agent.Oracle;
import magat.game.ContractCheck;
import magat.game.Game;
import magat.game.GameModule;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.probe.EquivalenceCheck;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one Java entry point. The launcher ({@code magat.py}) builds the classpath and passes the data
 * root, the engine commit and the weights file explicitly; nothing here has a default path.
 */
public final class Main {

    public static final String VERSION = "0.1.0-m4";

    private Main() {}

    public static void main(String[] argv) {
        Args args = Args.parse(argv);
        System.setProperty("magat.version", VERSION);
        String cmd = args.word(0);
        int code;
        try {
            if (cmd == null || cmd.equals("help")) { usage(); code = 0; }
            else if (cmd.equals("version")) { System.out.println("magat " + VERSION); code = 0; }
            else if (cmd.equals("check")) code = check(args);
            else if (cmd.equals("arms")) code = arms(args);
            else if (cmd.equals("portfolio")) code = PortfolioCommand.run(args);
            else if (cmd.equals("calibrate")) code = CalibrateCommand.calibrate(args);
            else if (cmd.equals("threads")) code = CalibrateCommand.threads(args);
            else if (cmd.equals("machine")) code = CalibrateCommand.machine();
            else if (cmd.equals("weights")) code = CalibrateCommand.weights(args);
            else if (cmd.equals("run")) code = RunCommand.run(args, argv);
            else if (cmd.equals("experiment")) {
                if (!"validate".equals(args.word(1))) throw new IllegalArgumentException("experiment what? validate");
                code = RunCommand.validate(args);
            }
            else { System.err.println("unknown command: " + cmd); usage(); code = 2; }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        } catch (Exception e) {
            e.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    private static void usage() {
        System.out.println("usage: magat <command> [--flag value ...]");
        System.out.println("  version");
        System.out.println("  arms  --game <name>");
        System.out.println("  check contract    --game <name> --portfolio <dir> [--scenarios 8] [--ids 1,2,...]");
        System.out.println("  check equivalence --game <name> --portfolio <dir> --weights <file> [--arms emcts]");
        System.out.println("                    [--scenarios 8] [--visits 2] [--budgets 8000,40000] [--threads N] [--ids ...] [--verbose]");
        PortfolioCommand.usage();
        CalibrateCommand.usage();
        RunCommand.usage();
    }

    // ------------------------------------------------------------------ shared

    static void banner(String what) {
        System.out.println("# magat " + VERSION + " " + what);
        System.out.println("# data root: " + System.getProperty("magat.data", "(not set)")
                + "   engine: " + System.getProperty("magat.engine.commit", "(not set)"));
    }

    private static <S, A> List<Scenario<S>> pickScenarios(Portfolio<S> p, Args args) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String s : args.getList("ids", new ArrayList<>())) ids.add(Integer.parseInt(s));
        if (!ids.isEmpty()) {
            List<Scenario<S>> out = new ArrayList<>();
            for (int id : ids) {
                Scenario<S> sc = p.byId(id);
                if (sc == null) throw new IllegalArgumentException("no scenario with id " + id);
                out.add(sc);
            }
            return out;
        }
        return p.spread(args.getInt("scenarios", 8));
    }

    private static <S, A> Portfolio<S> loadPortfolio(Game<S, A> game, Args args) throws Exception {
        Path dir = Paths.get(args.require("portfolio"));
        Portfolio<S> p = Portfolio.load(game, dir);
        System.out.println("# portfolio: " + dir + "  (" + p.size() + " scenarios, identity "
                + p.identity().substring(0, 12) + "...)");
        return p;
    }

    // ------------------------------------------------------------------ commands

    private static <S, A> int arms(Args args) {
        GameModule<S, A> module = Games.load(args.require("game"));
        for (AgentFactory<S, A> f : module.agents().all())
            System.out.println(String.format(Locale.ROOT, "  %-24s %s%s", f.id(), f.description(),
                    module.oracles().containsKey(f.id()) ? "   [oracle: " + module.oracles().get(f.id()).describe() + "]" : ""));
        return 0;
    }

    private static int check(Args args) throws Exception {
        String what = args.word(1);
        if ("contract".equals(what)) return checkContract(args);
        if ("equivalence".equals(what)) return checkEquivalence(args);
        throw new IllegalArgumentException("check what? contract | equivalence");
    }

    private static <S, A> int checkContract(Args args) throws Exception {
        banner("check contract");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        System.out.println("# game: " + game.id() + "  " + game.configuration());
        Portfolio<S> p = loadPortfolio(game, args);
        List<Scenario<S>> positions = pickScenarios(p, args);
        System.out.println("# positions: " + positions.size());
        ContractCheck.Report r = ContractCheck.run(game, positions);
        r.print();
        System.out.println(r.allPassed() ? "CONTRACT: PASS" : "CONTRACT: FAIL (" + r.failures() + " law(s))");
        return r.allPassed() ? 0 : 1;
    }

    private static <S, A> int checkEquivalence(Args args) throws Exception {
        banner("check equivalence");
        GameModule<S, A> module = Games.load(args.require("game"));
        Game<S, A> game = module.game();
        Weights weights = Weights.load(Paths.get(args.require("weights")));
        Meter.install(weights);
        System.out.println("# weights: " + weights.summary());
        Portfolio<S> p = loadPortfolio(game, args);
        List<Scenario<S>> scenarios = pickScenarios(p, args);
        int visits = args.getInt("visits", 2);
        long[] budgets = args.getLongs("budgets", new long[]{8_000L, 40_000L});
        int threads = args.getInt("threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        List<String> armIds = args.getList("arms", null);
        if (armIds == null) armIds = new ArrayList<>(module.oracles().keySet());
        System.out.println(String.format(Locale.ROOT, "# arms %s, %d scenarios x %d visits, budgets %s, %d threads",
                armIds, scenarios.size(), visits, Arrays.toString(budgets), threads));

        int failures = 0;
        for (String id : armIds) {
            AgentFactory<S, A> arm = module.agents().get(id);
            Oracle<S> oracle = module.oracles().get(id);
            if (oracle == null) throw new IllegalArgumentException("arm '" + id + "' has no oracle in this game module");
            long t0 = System.currentTimeMillis();
            EquivalenceCheck.Result r = EquivalenceCheck.run(game, arm, oracle, scenarios, visits, budgets, weights, threads);
            EquivalenceCheck.print(r, oracle, args.flag("verbose"));
            System.out.println(String.format(Locale.ROOT, "  (%.1f s)", (System.currentTimeMillis() - t0) / 1000.0));
            failures += r.failures();
        }
        System.out.println(failures == 0 ? "EQUIVALENCE: PASS" : "EQUIVALENCE: FAIL (" + failures + " cell(s))");
        return failures == 0 ? 0 : 1;
    }
}
