package magat.run;

import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.game.Game;
import magat.game.GameModule;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.HoldoutLedger;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.portfolio.Split;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The runner end to end on the toy game: runs, resumes, guards. */
public class GridRunnerTest {

    private final ToyGame game = new ToyGame();
    private Path portfolioDir, out, work;
    private Portfolio<ToyGame.State> portfolio;

    static final String SPEC = "{ \"name\": \"toy\", \"game\": \"toy\", \"split\": \"dev\", \"baseline\": \"emcts\","
            + " \"levels\": [ {\"budget\": 600, \"visits\": 2}, {\"budget\": 300, \"visits\": 1} ],"
            + " \"arms\": [ {\"id\": \"emcts\"}, {\"id\": \"emcts-comma\"}, {\"id\": \"random\"} ],"
            + " \"sweeps\": [ {\"parameter\": \"selection\", \"arms\": [\"emcts-comma\"], \"null\": \"emcts\"} ] }";

    /** A module over the toy game with the given extra arms registered. */
    public static GameModule<ToyGame.State, ToyGame.Move> module(ToyGame game, List<AgentFactory<ToyGame.State, ToyGame.Move>> extra) {
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(EmctsFactory.comma(3));
        for (AgentFactory<ToyGame.State, ToyGame.Move> f : extra) r.register(f);
        return new GameModule<ToyGame.State, ToyGame.Move>() {
            @Override public Game<ToyGame.State, ToyGame.Move> game() { return game; }
            @Override public AgentRegistry<ToyGame.State, ToyGame.Move> agents() { return r; }
            @Override public Map<String, Oracle<ToyGame.State>> oracles() { return Collections.emptyMap(); }
        };
    }

    /** An arm with another id that is plain EMCTS underneath: a renamed duplicate. */
    static AgentFactory<ToyGame.State, ToyGame.Move> twin() {
        AgentFactory<ToyGame.State, ToyGame.Move> plain = EmctsFactory.plain();
        return new AgentFactory<ToyGame.State, ToyGame.Move>() {
            @Override public String id() { return "emcts-twin"; }
            @Override public SearchAgent<ToyGame.State, ToyGame.Move> create(long seed, TurnBudget budget) { return plain.create(seed, budget); }
            @Override public java.util.List<String> operations() { return plain.operations(); }
        };
    }

    /** A weights file pricing the given arms at 300 and 600; {@code bump} raises emcts-comma@300's copy weight. */
    static Path weights(Path dir, String name, double bump, String... arms) throws Exception {
        StringBuilder sb = new StringBuilder("magat.weights.version = 1\ngame = toy\nreference = emcts@300\nreferenceAdvanceNanos = 1000\n"
                + "turnsPerRep = 2\nglobal.wAdvance = 1\nglobal.wCopy = 0.16\nglobal.wEval = 0.077\n");
        for (String a : arms)
            for (int b : new int[]{300, 600}) {
                double copy = a.equals("emcts-comma") && b == 300 ? 0.16 + bump : 0.16;
                sb.append("profile.").append(a).append('@').append(b).append(".wAdvance = 1\n");
                sb.append("profile.").append(a).append('@').append(b).append(".wCopy = ").append(copy).append('\n');
                sb.append("profile.").append(a).append('@').append(b).append(".wEval = 0.077\n");
            }
        Path f = dir.resolve(name);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** A version-2 file pricing the given arms at 300 and 600, every EMCTS operation at {@code opWeight}. */
    public static Path weightsV2(Path dir, String name, double opWeight, String... arms) throws Exception {
        StringBuilder sb = new StringBuilder("magat.weights.version = 2\ngame = toy\nreference = emcts@300\nreferenceAdvanceNanos = 1000\n"
                + "turnsPerRep = 2\nglobal.w.advance = 1\nglobal.w.copy = 0.16\nglobal.w.eval = 0.077\n");
        for (String a : arms)
            for (int b : new int[]{300, 600}) {
                String p = "profile." + a + "@" + b + ".";
                sb.append(p).append("w.advance = 1\n").append(p).append("w.copy = 0.16\n").append(p).append("w.eval = 0.077\n");
                for (String op : magat.agents.emcts.Emcts.OPERATIONS) sb.append(p).append("w.").append(op).append(" = ").append(opWeight).append('\n');
            }
        Path f = dir.resolve(name);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void aVersionTwoRunWritesTheOperationsAndRecordsTheirPricesAndTheAudit() throws Exception {
        Weights w = Weights.load(weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-comma"));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, options(Split.Half.DEV)));
        Path leaf = Leaf.dir(out.resolve("dev"), "emcts", 600);
        List<String> lines = Files.readAllLines(leaf.resolve(Leaf.TURNS), StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int seqLen = header.indexOf("seq_len");
        assertEquals("the forward model's columns keep their places", Leaf.COLUMNS, header.subList(0, seqLen + 1));
        assertEquals("op_emcts.generation", header.get(seqLen + 1));
        assertTrue(header.containsAll(Arrays.asList("op_emcts.legal", "op_emcts.check", "op_emcts.seqkey", "op_emcts.burn")));
        String[] row = lines.get(1).split(",", -1);
        assertTrue(Long.parseLong(row[header.indexOf("op_emcts.generation")]) > 0);
        // units are every operation's count times its price (to the four decimals rows are written with)
        double units = Double.parseDouble(row[header.indexOf("units")]);
        double priced = Double.parseDouble(row[header.indexOf("advances")]) + 0.16 * Double.parseDouble(row[header.indexOf("copies")])
                + 0.077 * Double.parseDouble(row[header.indexOf("evals")]);
        for (String op : magat.agents.emcts.Emcts.OPERATIONS) priced += 0.5 * Double.parseDouble(row[header.indexOf("op_" + op)]);
        assertEquals(priced, units, 1e-3);
        String meta = read(leaf.resolve(Leaf.META));
        assertTrue(meta.contains("\"currencyVersion\": 2"));
        assertTrue(meta.contains("\"emcts.burn\": 0.5"));
        // The audit (recorded when the leaf's turns took measurable time, which toy turns may not): the plan
        // time its priced units predict at the currency's reference nanoseconds, against the time measured.
        com.eclipsesource.json.JsonObject a = GridRunner.audit(w, 4000, 5, 2);
        assertEquals(4000 * 1000 / 1e6, a.getDouble("predictedMs", 0), 1e-9);
        assertEquals(1 - 4.0 / 5.0, a.getDouble("unpricedShare", 0), 1e-9);
        assertNull("no measured time, no share", GridRunner.audit(w, 4000, 0, 2));
        // the random floor declares nothing and keeps the plain layout
        assertFalse(read(Leaf.dir(out.resolve("dev"), "random", 300).resolve(Leaf.TURNS)).contains("op_"));
    }

    @Test
    public void aVersionOneTreeIsNotContinuedInAVersionTwoCurrency() throws Exception {
        GridRunner.Options o = options(Split.Half.DEV);
        Experiment x = Experiment.load(spec(SPEC));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio,
                Weights.load(weights(work, "w1.properties", 0, "emcts", "emcts-comma")), o));
        // Same forward-model prices, but the arms' own operations are now priced: every priced leaf changes price.
        Weights v2 = Weights.load(weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-comma"));
        assertEquals(GridRunner.MISMATCH, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, v2, o));
        // Retiring them re-runs them in the new currency.
        o.retire = new java.util.LinkedHashSet<>(Arrays.asList("emcts@600", "emcts@300", "emcts-comma@600", "emcts-comma@300"));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, v2, o));
        assertTrue(read(Leaf.dir(out.resolve("dev"), "emcts", 300).resolve(Leaf.TURNS)).contains("op_emcts.legal"));
    }

    private Path spec(String json) throws Exception {
        Path f = work.resolve("spec-" + System.nanoTime() + ".json");
        Files.write(f, json.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private GridRunner.Options options(Split.Half half) {
        GridRunner.Options o = new GridRunner.Options();
        o.split = half;
        o.out = out;
        o.threads = 2;
        return o;
    }

    @Before
    public void setUp() throws Exception {
        work = Files.createTempDirectory("magat-run-work");
        portfolioDir = Files.createTempDirectory("magat-run-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 8), portfolioDir, 2);
        Split.freeze(portfolioDir);
        portfolio = Portfolio.load(game, portfolioDir);
        out = Files.createTempDirectory("magat-run-out");
    }

    @After
    public void tearDown() {
        Meter.install(null);
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void runsTheGridAndAResumeSkipsEveryFinishedLeaf() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-comma"));
        Experiment x = Experiment.load(spec(SPEC));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
        Path root = out.resolve("dev");
        int dev = Split.ids(portfolioDir, Split.Half.DEV).size();
        assertEquals(dev * 2, Leaf.rows(Leaf.dir(root, "emcts", 600)));
        assertEquals(dev, Leaf.rows(Leaf.dir(root, "random", 300)));
        assertEquals(6, Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)).size());
        assertTrue(Files.exists(Leaf.dir(root, "emcts", 600).resolve(Leaf.CURVE)));
        assertTrue(read(Leaf.dir(root, "emcts", 600).resolve(Leaf.META)).contains("\"complete\""));
        String run = read(root.resolve(Provenance.FILE));
        assertTrue(run.contains(portfolio.identity()));
        assertTrue(run.contains("\"split\": \"dev\""));
        String header = Files.readAllLines(Leaf.dir(root, "emcts", 600).resolve(Leaf.TURNS)).get(0);
        assertTrue(header.startsWith("scenario,source,phase,visit,arm,budget,initial,final"));
        assertTrue(header.contains(",advances,copies,evals,units,"));

        String before = read(Leaf.dir(root, "emcts-comma", 600).resolve(Leaf.TURNS));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
        assertEquals("a finished leaf is skipped, not re-run", before, read(Leaf.dir(root, "emcts-comma", 600).resolve(Leaf.TURNS)));
        assertEquals(6, Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)).size());
    }

    @Test
    public void aPartialLeafContinuesPerScenarioAndVisit() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-comma"));
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV));
        Path root = out.resolve("dev"), leaf = Leaf.dir(root, "emcts", 600), turns = leaf.resolve(Leaf.TURNS);
        List<String> full = Files.readAllLines(turns);
        // A kill mid-leaf: the last two turns never finished, the one before was torn mid-append.
        List<String> cut = new ArrayList<>(full.subList(0, full.size() - 3));
        cut.add(full.get(full.size() - 3).substring(0, 12));
        Files.write(turns, (String.join("\n", cut) + "\n").getBytes(StandardCharsets.UTF_8));
        List<String> ck = new ArrayList<>(Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)));
        ck.remove(GridRunner.checkpointKey("emcts", 600, Split.Half.DEV));
        Files.write(root.resolve(GridRunner.CHECKPOINT), (String.join("\n", ck) + "\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
        List<String> resumed = Files.readAllLines(turns);
        assertEquals(full.size(), resumed.size());
        assertEquals("the same turns, whatever order they finished in (wall-clock columns aside)",
                withoutClock(full), withoutClock(resumed));
    }

    /** The rows with plan_ms and init_ms blanked: everything a replayed turn must reproduce exactly. */
    private static java.util.TreeSet<String> withoutClock(List<String> lines) {
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int plan = header.indexOf("plan_ms"), init = header.indexOf("init_ms");
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            f[plan] = "";
            f[init] = "";
            out.add(String.join(",", f));
        }
        return out;
    }

    @Test
    public void theHoldoutNeedsFrozenChoicesAndIsLedgeredOnce() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-comma"));
        Experiment x = Experiment.load(spec(SPEC));
        assertEquals(GridRunner.INVALID, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.HOLDOUT)));
        assertFalse(Files.exists(out.resolve("holdout")));
        GridRunner.Options o = options(Split.Half.HOLDOUT);
        o.frozen = Files.write(work.resolve("frozen.txt"), "champion = emcts-comma\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, o));
        assertEquals(1, HoldoutLedger.load(portfolioDir).entries().size());
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, o));
        assertEquals("a resume of the same read is not a second read", 1, HoldoutLedger.load(portfolioDir).entries().size());
        // The holdout tree refuses the development split.
        GridRunner.Options dev = options(Split.Half.DEV);
        dev.out = out.resolve("holdout").getParent();
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, dev));
        assertTrue("each split has its own tree", Files.isDirectory(out.resolve("dev")) && Files.isDirectory(out.resolve("holdout")));
    }

    @Test
    public void theCurrencyLockRefusesARepricedLeafUnlessItIsRetired() throws Exception {
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.run(module(game, new ArrayList<>()), x, portfolio,
                Weights.load(weights(work, "w1.properties", 0, "emcts", "emcts-comma")), options(Split.Half.DEV));
        Weights repriced = Weights.load(weights(work, "w2.properties", 0.05, "emcts", "emcts-comma"));
        assertEquals(GridRunner.MISMATCH, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, repriced, options(Split.Half.DEV)));

        Path root = out.resolve("dev");
        String untouched = read(Leaf.dir(root, "emcts", 600).resolve(Leaf.TURNS));
        GridRunner.Options o = options(Split.Half.DEV);
        o.retire.add("emcts-comma@300");
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, repriced, o));
        assertTrue(read(root.resolve(Provenance.FILE)).contains("\"retired\""));
        assertTrue(read(Leaf.dir(root, "emcts-comma", 300).resolve(Leaf.META)).contains("0.21"));
        assertEquals("leaves whose price did not change are kept", untouched, read(Leaf.dir(root, "emcts", 600).resolve(Leaf.TURNS)));

        // A file that only adds profiles changes no leaf's price: no retirement needed.
        Weights extended = Weights.load(weights(work, "w3.properties", 0.05, "emcts", "emcts-comma", "emcts-other"));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, extended, options(Split.Half.DEV)));
    }

    @Test
    public void twoArmsWithIdenticalOutputStopTheRun() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-twin"));
        String spec = "{ \"name\": \"twins\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1} ], \"arms\": [ {\"id\": \"emcts\"}, {\"id\": \"emcts-twin\"} ] }";
        assertEquals(GridRunner.IDENTICAL, GridRunner.run(module(game, Collections.singletonList(twin())),
                Experiment.load(spec(spec)), portfolio, w, options(Split.Half.DEV)));
    }

    @Test
    public void aTreeFromAnotherPortfolioIsRefused() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-comma"));
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV));
        Path other = Files.createTempDirectory("magat-run-portfolio2");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), other, 1);
        Split.freeze(other);
        assertEquals(GridRunner.MISMATCH, GridRunner.run(module(game, new ArrayList<>()), x, Portfolio.load(game, other), w, options(Split.Half.DEV)));
    }

    @Test
    public void anInvalidSpecRunsNothing() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts"));     // emcts-comma unpriced
        assertEquals(GridRunner.INVALID, GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w,
                options(Split.Half.DEV)));
        assertFalse(Files.exists(out.resolve("dev")));
        assertEquals(Arrays.asList(600L, 300L), Arrays.asList(Experiment.load(spec(SPEC)).levels.get(0).budget, Experiment.load(spec(SPEC)).levels.get(1).budget));
    }
}
