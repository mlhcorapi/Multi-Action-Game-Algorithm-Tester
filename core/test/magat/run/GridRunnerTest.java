package magat.run;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import magat.agent.AgentFactory;
import magat.agent.AgentRegistry;
import magat.agent.Oracle;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agents.emcts.EmctsFactory;
import magat.agents.random.RandomAgent;
import magat.game.Game;
import magat.game.Scenario;
import magat.game.GameModule;
import magat.machine.MachineProfile;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.portfolio.Split;
import magat.testgame.Stdout;
import magat.testgame.ToyArms;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            + " \"arms\": [ {\"id\": \"emcts\"}, {\"id\": \"emcts-alt\"}, {\"id\": \"random\"} ] }";

    /** A module over the toy game with the given extra arms registered. */
    public static GameModule<ToyGame.State, ToyGame.Move> module(ToyGame game, List<AgentFactory<ToyGame.State, ToyGame.Move>> extra) {
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(RandomAgent.factory());
        r.register(EmctsFactory.plain());
        r.register(ToyArms.emctsAlt());
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

    /**
     * A weights file pricing the given arms at 300 and 600, the EMCTS operations free; {@code bump} raises
     * emcts-alt@300's copy weight.
     */
    static Path weights(Path dir, String name, double bump, String... arms) throws Exception {
        StringBuilder sb = new StringBuilder("magat.weights.version = 2\ngame = toy\nreference = emcts@300\nreferenceAdvanceNanos = 1000\n"
                + "turnsPerRep = 2\nglobal.w.advance = 1\nglobal.w.copy = 0.16\nglobal.w.eval = 0.077\n");
        for (String a : arms)
            for (int b : new int[]{300, 600}) {
                double copy = a.equals("emcts-alt") && b == 300 ? 0.16 + bump : 0.16;
                String p = "profile." + a + "@" + b + ".";
                sb.append(p).append("w.advance = 1\n").append(p).append("w.copy = ").append(copy).append('\n').append(p).append("w.eval = 0.077\n");
                for (String op : magat.agents.emcts.Emcts.OPERATIONS) sb.append(p).append("w.").append(op).append(" = 0\n");
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
    public void aVersionTwoRunWritesTheOperationsAndRecordsTheirPricesAndTheAudit() throws Exception {
        Weights w = Weights.load(weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-alt"));
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
    public void theAuditWarnsOnOneThreadAndInformsOnMany() {
        JsonObject one = new JsonObject().add("unpricedShare", 0.163).add("predictedMs", 837.0).add("measuredMs", 1000L)
                .add("threads", 1).add("sameMachine", true);
        String warned = GridRunner.auditLine("oep@101500", one, 0.10);
        assertTrue(warned, warned.contains("16.3% of oep@101500's plan time is not accounted for") && warned.contains("threshold 10%"));
        assertNull("below the threshold, nothing", GridRunner.auditLine("oep@101500", one.set("unpricedShare", 0.05), 0.10));
        // On 62 threads the turns slow each other: the time ratio, as information, whatever the share.
        JsonObject many = new JsonObject().add("unpricedShare", 0.174).add("predictedMs", 826.0).add("measuredMs", 1000L)
                .add("threads", 62).add("sameMachine", true);
        String informed = GridRunner.auditLine("emcts@9500", many, 0.10);
        assertTrue(informed, informed.startsWith("[run] audit: emcts@9500 took 1.21x the time its priced work predicts, on 62 threads"));
        assertFalse(informed, informed.contains("threshold") || informed.contains("another machine"));
        assertTrue(GridRunner.auditLine("emcts@9500", many.set("sameMachine", false), 0.10).contains("calibrated on another machine"));
    }

    @Test
    public void theAuditKnowsTheCalibratingMachineByItsCpuModel() throws Exception {
        // A calibration pins its JVMs to a few cores, so its file counts fewer than a run on the same machine sees.
        String model = MachineProfile.get().cpuModel();
        assertEquals("AMD EPYC 9534 64-Core Processor", MachineProfile.modelOf("AMD EPYC 9534 64-Core Processor | 16 physical / 16 logical"));
        Path pinned = weightsV2(work, "pinned.properties", 0.5, "emcts");
        Files.write(pinned, ("machine = " + model + " | 2 physical / 2 logical\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertTrue(GridRunner.audit(Weights.load(pinned), 4000, 5, 2).getBoolean("sameMachine", false));
        Path other = weightsV2(work, "other.properties", 0.5, "emcts");
        Files.write(other, "machine = Another CPU | 2 physical / 2 logical\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertFalse(GridRunner.audit(Weights.load(other), 4000, 5, 2).getBoolean("sameMachine", true));
    }

    @Test
    public void runsTheGridAndAResumeSkipsEveryFinishedLeaf() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
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

        String before = read(Leaf.dir(root, "emcts-alt", 600).resolve(Leaf.TURNS));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
        assertEquals("a finished leaf is skipped, not re-run", before, read(Leaf.dir(root, "emcts-alt", 600).resolve(Leaf.TURNS)));
        assertEquals(6, Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)).size());
    }

    @Test
    public void aPartialLeafContinuesPerScenarioAndVisit() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
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

    @Test
    public void eachInvocationAndEachLeafRecordTheCommitThatPlayedIt() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        Experiment x = Experiment.load(spec(SPEC));
        String was = System.getProperty("magat.git.commit");
        try {
            System.setProperty("magat.git.commit", "aaaa");
            GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV));
            // A stop mid-leaf, then a resume from another commit.
            Path root = out.resolve("dev"), leaf = Leaf.dir(root, "emcts", 600), turns = leaf.resolve(Leaf.TURNS);
            List<String> full = Files.readAllLines(turns);
            Files.write(turns, (String.join("\n", full.subList(0, full.size() - 2)) + "\n").getBytes(StandardCharsets.UTF_8));
            List<String> ck = new ArrayList<>(Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)));
            ck.remove(GridRunner.checkpointKey("emcts", 600, Split.Half.DEV));
            Files.write(root.resolve(GridRunner.CHECKPOINT), (String.join("\n", ck) + "\n").getBytes(StandardCharsets.UTF_8));
            System.setProperty("magat.git.commit", "bbbb");
            Stdout.Captured<Integer> resumed = Stdout.capture(() ->
                    GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
            assertEquals(GridRunner.OK, (int) resumed.value);
            assertTrue(resumed.out, resumed.out.contains("this tree was started by MAGAT aaaa; this invocation is bbbb"));
            assertTrue(resumed.out, resumed.out.contains("emcts@600: its " + (full.size() - 3) + " turn(s) so far were played by MAGAT aaaa"));

            JsonObject run = Json.parse(read(root.resolve(Provenance.FILE))).asObject();
            assertEquals("the tree keeps the commit that started it", "aaaa", run.get("magat").asObject().getString("commit", null));
            JsonArray invocations = run.get("invocations").asArray();
            assertEquals("aaaa", invocations.get(0).asObject().getString("commit", null));
            assertEquals("bbbb", invocations.get(1).asObject().getString("commit", null));
            JsonObject resumedLeaf = Json.parse(read(leaf.resolve(Leaf.META))).asObject();
            assertEquals("bbbb", resumedLeaf.getString("commit", null));
            assertEquals("aaaa", resumedLeaf.get("earlierCommits").asArray().get(0).asString());
            JsonObject untouched = Json.parse(read(Leaf.dir(root, "emcts-alt", 600).resolve(Leaf.META))).asObject();
            assertEquals("a finished leaf keeps the commit that played it", "aaaa", untouched.getString("commit", null));
            assertNull(untouched.get("earlierCommits"));
        } finally {
            if (was == null) System.clearProperty("magat.git.commit");
            else System.setProperty("magat.git.commit", was);
        }
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
    public void eachSplitRunsIntoItsOwnTree() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.Options o = options(Split.Half.HOLDOUT);
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, o));
        assertEquals("a finished run resumes as a no-op", GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, o));
        GridRunner.Options dev = options(Split.Half.DEV);
        dev.out = out.resolve("holdout").getParent();
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, dev));
        assertTrue("each split has its own tree", Files.isDirectory(out.resolve("dev")) && Files.isDirectory(out.resolve("holdout")));
    }

    @Test
    public void theCurrencyLockRefusesARepricedLeafUnlessItIsRetired() throws Exception {
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.run(module(game, new ArrayList<>()), x, portfolio,
                Weights.load(weights(work, "w1.properties", 0, "emcts", "emcts-alt")), options(Split.Half.DEV));
        Weights repriced = Weights.load(weights(work, "w2.properties", 0.05, "emcts", "emcts-alt"));
        Stdout.Captured<Integer> refused = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), x, portfolio, repriced, options(Split.Half.DEV)));
        assertEquals(GridRunner.MISMATCH, (int) refused.value);
        assertTrue(refused.out, refused.out.contains("ABORT (currency lock)"));

        Path root = out.resolve("dev");
        String untouched = read(Leaf.dir(root, "emcts", 600).resolve(Leaf.TURNS));
        GridRunner.Options o = options(Split.Half.DEV);
        o.retire.add("emcts-alt@300");
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, repriced, o));
        assertTrue(read(root.resolve(Provenance.FILE)).contains("\"retired\""));
        assertTrue(read(Leaf.dir(root, "emcts-alt", 300).resolve(Leaf.META)).contains("0.21"));
        assertEquals("leaves whose price did not change are kept", untouched, read(Leaf.dir(root, "emcts", 600).resolve(Leaf.TURNS)));

        // A file that only adds profiles changes no leaf's price: no retirement needed.
        Weights extended = Weights.load(weights(work, "w3.properties", 0.05, "emcts", "emcts-alt", "emcts-other"));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, extended, options(Split.Half.DEV)));
    }

    @Test
    public void twoArmsWithIdenticalOutputStopTheRun() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-twin"));
        String spec = "{ \"name\": \"twins\", \"game\": \"toy\", \"baseline\": \"emcts\","
                + " \"levels\": [ {\"budget\": 300, \"visits\": 1} ], \"arms\": [ {\"id\": \"emcts\"}, {\"id\": \"emcts-twin\"} ] }";
        Stdout.Captured<Integer> stopped = Stdout.capture(() -> GridRunner.run(module(game, Collections.singletonList(twin())),
                Experiment.load(spec(spec)), portfolio, w, options(Split.Half.DEV)));
        assertEquals(GridRunner.IDENTICAL, (int) stopped.value);
        assertTrue(stopped.out, stopped.out.contains("RUN: STOPPED (identical arms)"));
    }

    @Test
    public void aTreeFromAnotherPortfolioIsRefused() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        Experiment x = Experiment.load(spec(SPEC));
        GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV));
        Path other = Files.createTempDirectory("magat-run-portfolio2");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), other, 1);
        Split.freeze(other);
        Stdout.Captured<Integer> refused = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), x, Portfolio.load(game, other), w, options(Split.Half.DEV)));
        assertEquals(GridRunner.MISMATCH, (int) refused.value);
        assertTrue(refused.out, refused.out.contains("ABORT: ") && refused.out.contains("portfolio identity"));
    }

    @Test
    public void anInvalidSpecRunsNothing() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts"));     // emcts-alt unpriced
        Stdout.Captured<Integer> invalid = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, options(Split.Half.DEV)));
        assertEquals(GridRunner.INVALID, (int) invalid.value);
        assertTrue(invalid.out, invalid.out.contains("VALIDATION: FAIL") && invalid.out.contains("emcts-alt@300"));
        assertFalse(Files.exists(out.resolve("dev")));
        assertEquals(Arrays.asList(600L, 300L), Arrays.asList(Experiment.load(spec(SPEC)).levels.get(0).budget, Experiment.load(spec(SPEC)).levels.get(1).budget));
    }

    @Test
    public void aSelectionPlaysOnlyItsPositionsAndAResumeMustSelectTheSame() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        Experiment x = Experiment.load(spec(SPEC.replace("\"split\": \"dev\",", "\"split\": \"dev\", \"phases\": [1],")));
        List<Scenario<ToyGame.State>> dev = Split.apply(portfolio.scenarios(), portfolioDir, Split.Half.DEV);
        long early = dev.stream().filter(s -> s.phase == 1).count();
        assertTrue("the toy portfolio's dev half has both phases", early > 0 && early < dev.size());
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
        Path root = out.resolve("dev");
        assertEquals(early * 2, Leaf.rows(Leaf.dir(root, "emcts", 600)));
        assertTrue(read(root.resolve(Provenance.FILE)).contains("\"selection\": \"phases=1\""));

        Stdout.Captured<Integer> other = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, options(Split.Half.DEV)));
        assertEquals("another selection would mix positions in one tree", GridRunner.MISMATCH, (int) other.value);
        assertTrue(other.out, other.out.contains("selection: this tree holds 'phases=1', this run has 'all'"));
    }

    @Test
    public void aSelectionNeverReachesAcrossTheSplitAndMustLeaveSomething() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        int holdout = Split.ids(portfolioDir, Split.Half.HOLDOUT).iterator().next();
        String[][] refused = {
                {"\"ids\": [" + holdout + "],", "never reaches across the split"},
                {"\"sources\": [\"nope\"],", "source 'nope' is not in the portfolio"},
                {"\"phases\": [7],", "leaves no position of the dev half"},
        };
        for (String[] r : refused) {
            Experiment x = Experiment.load(spec(SPEC.replace("\"split\": \"dev\",", "\"split\": \"dev\", " + r[0])));
            Stdout.Captured<Integer> run = Stdout.capture(() -> GridRunner.run(module(game, new ArrayList<>()), x, portfolio, w, options(Split.Half.DEV)));
            assertEquals(r[0], GridRunner.INVALID, (int) run.value);
            assertTrue(run.out, run.out.contains(r[1]));
        }
        assertFalse("nothing was run", Files.exists(out.resolve("dev")));
        Experiment many = Experiment.load(spec(SPEC.replace("\"split\": \"dev\",", "\"split\": \"dev\", \"sources\": [\"toy\"], \"scenarios\": 99,")));
        Stdout.Captured<Integer> run = Stdout.capture(() -> GridRunner.run(module(game, new ArrayList<>()), many, portfolio, w, options(Split.Half.DEV)));
        assertEquals(GridRunner.OK, (int) run.value);
        assertTrue(run.out, run.out.contains("asks for 99 positions") && run.out.contains("all of them run"));
    }

    /** A leaf's turns without its wall-clock columns, sorted: what a reused leaf must share with a played one. */
    private static List<String> turnsWithoutTime(Path leaf) throws Exception {
        List<String> lines = Files.readAllLines(leaf.resolve(Leaf.TURNS), StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int ms = header.indexOf("plan_ms"), init = header.indexOf("init_ms");
        List<String> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            f[ms] = "";
            f[init] = "";
            out.add(String.join(",", f));
        }
        Collections.sort(out);
        return out;
    }

    @Test
    public void aRunInTheSameCurrencyTakesTheLeavesAnotherRunHoldsWholeAndPlaysTheRest() throws Exception {
        Path wf = weights(work, "w.properties", 0, "emcts", "emcts-alt");
        Weights w = Weights.load(wf);
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, options(Split.Half.DEV)));
        Path published = out.resolve("dev");

        // The same levels, one more visit at 600: the leaves at 300 are taken, those at 600 played.
        Experiment more = Experiment.load(spec(SPEC.replace("{\"budget\": 600, \"visits\": 2}", "{\"budget\": 600, \"visits\": 3}")));
        GridRunner.Options o = options(Split.Half.DEV);
        o.out = Files.createTempDirectory("magat-run-reuse");
        o.reuse = published;
        Stdout.Captured<Integer> run = Stdout.capture(() -> GridRunner.run(module(game, new ArrayList<>()), more, portfolio, w, o));
        assertEquals(run.out, GridRunner.OK, (int) run.value);
        Path root = o.out.resolve("dev");
        for (String arm : new String[]{"emcts", "emcts-alt", "random"}) {
            assertTrue(run.out, run.out.contains("[run] REUSED arm=" + arm + ";budget=300"));
            assertTrue(run.out, run.out.contains("[reuse] " + arm + "@600 is played: the published leaf holds"));
            assertEquals("a reused leaf is the leaf this machine plays, wall-clock aside",
                    turnsWithoutTime(Leaf.dir(published, arm, 300)), turnsWithoutTime(Leaf.dir(root, arm, 300)));
            String meta = read(Leaf.dir(root, arm, 300).resolve(Leaf.META));
            assertTrue(meta, meta.contains("\"reused\"") && meta.contains("\"reproduced\": " + Math.min(2, Leaf.rows(Leaf.dir(root, arm, 300)))));
            assertFalse(read(Leaf.dir(root, arm, 600).resolve(Leaf.META)).contains("\"reused\""));
        }
        assertEquals(6, Files.readAllLines(root.resolve(GridRunner.CHECKPOINT)).size());
        assertTrue(read(root.resolve(Provenance.FILE)).contains("\"reuse\": \"" + published.toString().replace("\\", "\\\\")));

        // A currency that prices one leaf otherwise (emcts-alt@300's copy): that leaf is played, every other is taken.
        // Leaf by leaf, as a currency extended with a new arm keeps every price it had.
        GridRunner.Options other = options(Split.Half.DEV);
        other.out = Files.createTempDirectory("magat-run-reuse2");
        other.reuse = published;
        Weights bumped = Weights.load(weights(work, "w2.properties", 0.05, "emcts", "emcts-alt"));
        Stdout.Captured<Integer> priced = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, bumped, other));
        assertEquals(GridRunner.OK, (int) priced.value);
        assertTrue(priced.out, priced.out.contains("[reuse] emcts-alt@300 is played: it was played at other prices (copy "));
        for (String leaf : new String[]{"emcts;budget=600", "emcts;budget=300", "emcts-alt;budget=600", "random;budget=600", "random;budget=300"})
            assertTrue(leaf + "\n" + priced.out, priced.out.contains("[run] REUSED arm=" + leaf));

        // Another portfolio: nothing is taken, and the run says why.
        Path elsewhere = Files.createTempDirectory("magat-run-portfolio3");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), elsewhere, 1);
        Split.freeze(elsewhere);
        GridRunner.Options third = options(Split.Half.DEV);
        third.out = Files.createTempDirectory("magat-run-reuse4");
        third.reuse = published;
        Stdout.Captured<Integer> moved = Stdout.capture(() ->
                GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), Portfolio.load(game, elsewhere), w, third));
        assertEquals(GridRunner.OK, (int) moved.value);
        assertTrue(moved.out, moved.out.contains("[reuse] not from") && moved.out.contains("played on another portfolio"));
        assertFalse(moved.out, moved.out.contains("REUSED"));
    }

    @Test
    public void aLeafWhoseTurnsDoNotReplayHereIsPlayed() throws Exception {
        Weights w = Weights.load(weights(work, "w.properties", 0, "emcts", "emcts-alt"));
        assertEquals(GridRunner.OK, GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, options(Split.Half.DEV)));
        Path published = out.resolve("dev"), turns = Leaf.dir(published, "emcts", 300).resolve(Leaf.TURNS);
        List<String> played = turnsWithoutTime(Leaf.dir(published, "emcts", 300));
        // Another value in every row's final column: what a changed arm, or a changed engine, would leave behind.
        List<String> lines = Files.readAllLines(turns, StandardCharsets.UTF_8), tampered = new ArrayList<>();
        int fin = Arrays.asList(lines.get(0).split(",")).indexOf("final");
        tampered.add(lines.get(0));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            f[fin] = String.valueOf(Double.parseDouble(f[fin]) + 1);
            tampered.add(String.join(",", f));
        }
        Files.write(turns, tampered, StandardCharsets.UTF_8);

        GridRunner.Options o = options(Split.Half.DEV);
        o.out = Files.createTempDirectory("magat-run-reuse3");
        o.reuse = published;
        Stdout.Captured<Integer> run = Stdout.capture(() -> GridRunner.run(module(game, new ArrayList<>()), Experiment.load(spec(SPEC)), portfolio, w, o));
        assertEquals(GridRunner.OK, (int) run.value);
        assertTrue(run.out, run.out.contains("[reuse] emcts@300 is played: this machine did not re-play"));
        assertTrue(run.out, run.out.contains("[run] REUSED arm=emcts-alt;budget=300"));
        assertEquals("the leaf is played here, not copied", played, turnsWithoutTime(Leaf.dir(o.out.resolve("dev"), "emcts", 300)));
    }
}
