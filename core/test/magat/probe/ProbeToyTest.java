package magat.probe;

import magat.agents.emcts.EmctsFactory;
import magat.agent.AgentRegistry;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.portfolio.Split;
import magat.run.Experiment;
import magat.run.GridRunner;
import magat.testgame.BusyAgent;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/** Replay of a MAGAT leaf and the structure check, on the toy game. */
public class ProbeToyTest {

    private final ToyGame game = new ToyGame();

    @After
    public void tearDown() {
        Meter.install(null);
    }

    @Test
    public void aLeafReplaysFieldForFieldAndATamperedOneDoesNot() throws Exception {
        Path pdir = Files.createTempDirectory("magat-replay-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), pdir, 1);
        Split.freeze(pdir);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        Path work = Files.createTempDirectory("magat-replay");
        Path weights = work.resolve("w.properties");
        Files.write(weights, ("magat.weights.version = 1\nreference = emcts@400\nreferenceAdvanceNanos = 1000\n"
                + "profile.emcts-comma@400.wAdvance = 1\nprofile.emcts-comma@400.wCopy = 0.2\nprofile.emcts-comma@400.wEval = 0.1\n"
                + "profile.emcts@400.wAdvance = 1\nprofile.emcts@400.wCopy = 0.2\nprofile.emcts@400.wEval = 0.1\n").getBytes(StandardCharsets.UTF_8));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\",\"levels\":[{\"budget\":400,\"visits\":2}],"
                + "\"arms\":[\"emcts\",\"emcts-comma\"]}").getBytes(StandardCharsets.UTF_8));
        GridRunner.Options o = new GridRunner.Options();
        o.split = Split.Half.ALL;
        o.out = work.resolve("run");
        o.threads = 2;
        Weights w = Weights.load(weights);
        assertEquals(GridRunner.OK, GridRunner.run(magat.run.GridRunnerTest.module(game, new ArrayList<>()), Experiment.load(spec), p, w, o));

        Path leafDir = magat.run.Leaf.dir(o.out.resolve("all"), "emcts-comma", 400);
        ReplayCheck.Leaf leaf = ReplayCheck.read(leafDir);
        assertEquals("magat", leaf.format);
        Meter.install(w);
        ReplayCheck.Result ok = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>comma(3), 400, leaf, 100, 2);
        assertTrue(ok.failures.toString(), ok.pass());
        assertEquals(leaf.size(), ok.replayed);

        // The same turns replayed by another arm do not reproduce.
        ReplayCheck.Result other = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 400, leaf, 100, 2);
        assertFalse(other.pass());
    }

    @Test
    public void aVersionTwoLeafReplaysWithItsOperationCounts() throws Exception {
        Path pdir = Files.createTempDirectory("magat-replay2-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), pdir, 1);
        Split.freeze(pdir);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        Path work = Files.createTempDirectory("magat-replay2");
        Weights w = Weights.load(magat.run.GridRunnerTest.weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-comma"));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\",\"levels\":[{\"budget\":300,\"visits\":2}],"
                + "\"arms\":[\"emcts\",\"emcts-comma\"]}").getBytes(StandardCharsets.UTF_8));
        GridRunner.Options o = new GridRunner.Options();
        o.split = Split.Half.ALL;
        o.out = work.resolve("run");
        o.threads = 2;
        assertEquals(GridRunner.OK, GridRunner.run(magat.run.GridRunnerTest.module(game, new ArrayList<>()), Experiment.load(spec), p, w, o));
        Path leafDir = magat.run.Leaf.dir(o.out.resolve("all"), "emcts", 300);
        Meter.install(w);
        ReplayCheck.Result ok = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 300, ReplayCheck.read(leafDir), 100, 2);
        assertTrue(ok.failures.toString(), ok.pass());

        // An operation count tampered with is a difference, like any other field.
        Path turns = leafDir.resolve(magat.run.Leaf.TURNS);
        java.util.List<String> lines = new ArrayList<>(Files.readAllLines(turns, StandardCharsets.UTF_8));
        java.util.List<String> header = java.util.Arrays.asList(lines.get(0).split(","));
        String[] row = lines.get(1).split(",", -1);
        int col = header.indexOf("op_emcts.legal");
        row[col] = String.valueOf(Long.parseLong(row[col]) + 1);
        lines.set(1, String.join(",", row));
        Files.write(turns, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        ReplayCheck.Result bad = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 300, ReplayCheck.read(leafDir), 100, 2);
        assertFalse(bad.pass());
        assertTrue(bad.failures.toString(), bad.failures.toString().contains("op_emcts.legal"));
    }

    @Test
    public void aRunWithItsInitializationInTheBudgetReplaysAsItWasBudgeted() throws Exception {
        Path pdir = Files.createTempDirectory("magat-replay3-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), pdir, 1);
        Split.freeze(pdir);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        Path work = Files.createTempDirectory("magat-replay3");
        Weights w = Weights.load(magat.run.GridRunnerTest.weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-comma"));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\",\"initialization\":\"in-budget\","
                + "\"levels\":[{\"budget\":300,\"visits\":2}],\"arms\":[\"emcts\",\"emcts-comma\"]}").getBytes(StandardCharsets.UTF_8));
        GridRunner.Options o = new GridRunner.Options();
        o.split = Split.Half.ALL;
        o.out = work.resolve("run");
        o.threads = 2;
        assertEquals(GridRunner.OK, GridRunner.run(magat.run.GridRunnerTest.module(game, new ArrayList<>()), Experiment.load(spec), p, w, o));
        assertEquals("the run leaves the JVM's policy as it found it", magat.agent.TurnBudget.Initialization.IN_BUDGET,
                magat.agent.TurnBudget.initialization());
        assertTrue(new String(Files.readAllBytes(o.out.resolve("all").resolve("_run.json")), StandardCharsets.UTF_8)
                .contains("\"initialization\": \"in-budget\""));
        Path leafDir = magat.run.Leaf.dir(o.out.resolve("all"), "emcts", 300);
        ReplayCheck.Leaf leaf = ReplayCheck.read(leafDir);
        assertEquals(magat.agent.TurnBudget.Initialization.IN_BUDGET, leaf.initialization);
        Meter.install(w);
        ReplayCheck.Result ok = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 300, leaf, 100, 2);
        assertTrue(ok.failures.toString(), ok.pass());
    }

    @Test
    public void aTimeCheckReportsEveryTurnOfEveryArmAtEveryLevel() throws Exception {
        java.util.List<magat.game.Scenario<ToyGame.State>> positions = game.positions(5);
        java.util.List<TimeCheck.Cell> cells = TimeCheck.run(game, java.util.Arrays.asList(EmctsFactory.<ToyGame.State, ToyGame.Move>plain(),
                EmctsFactory.<ToyGame.State, ToyGame.Move>comma(3)), positions, new long[]{300, 600}, new double[]{1000, 1000}, 4, 1, true);
        assertEquals(4, cells.size());
        for (TimeCheck.Cell c : cells) {
            assertEquals(4, c.ms.length);
            assertTrue(c.meanMs > 0 && c.minMs <= c.medianMs && c.medianMs <= c.maxMs && c.q1Ms <= c.q3Ms);
            assertTrue("a turn spends its level, its initialization included (" + c.meanUnits + ")", c.meanUnits >= c.budget * 0.9);
            assertTrue(c.meanInitUnits > 0);
            assertEquals(c.meanMs / 1000, c.ratio(), 1e-12);
            assertFalse("a toy turn is nowhere near a second", c.within(0.05));
        }
        assertEquals("emcts@300 then emcts-comma@300", "emcts-comma", cells.get(1).arm);
        assertEquals(600, cells.get(2).budget);
        Path out = Files.createTempDirectory("magat-time").resolve("time.json");
        TimeCheck.write(out, TimeCheck.json(cells, "fixed", 4, "toy"));
        String json = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"mean_ms\"") && json.contains("\"ratio\"") && json.contains("\"initialization\": \"in-budget\""));
        assertFalse("profiling is left as it was found", Meter.PROFILE);
    }

    @Test
    public void theAuditFailsAnArmWhoseWorkIsUndeclared() {
        java.util.List<magat.game.Scenario<ToyGame.State>> positions = game.positions(2);
        java.util.List<OverheadCheck.Audit> a = OverheadCheck.audit(game, java.util.Arrays.asList(
                BusyAgent.<ToyGame.State, ToyGame.Move>factory("busy-declared", true),
                BusyAgent.<ToyGame.State, ToyGame.Move>factory("busy-undeclared", false)), 300, positions, 2, 1);
        assertTrue("declared: " + a.get(0).unaccounted, a.get(0).unaccounted < OverheadCheck.AUDIT_WARN);
        assertTrue(a.get(0).shares.get(BusyAgent.WORK) > 0.5);
        assertTrue("undeclared: " + a.get(1).unaccounted, a.get(1).unaccounted > OverheadCheck.AUDIT_FAIL);
        assertTrue(OverheadCheck.report(a.subList(0, 1), OverheadCheck.AUDIT_WARN, OverheadCheck.AUDIT_FAIL));
        assertFalse(OverheadCheck.report(a, OverheadCheck.AUDIT_WARN, OverheadCheck.AUDIT_FAIL));
    }

    @Test
    public void theStructureSpecAssertsEquivalencesDifferencesAndBranching() throws Exception {
        Path pdir = Files.createTempDirectory("magat-structure-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), pdir, 1);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        AgentRegistry<ToyGame.State, ToyGame.Move> r = new AgentRegistry<>();
        r.register(EmctsFactory.plain());
        r.register(EmctsFactory.comma(3));
        Path spec = Files.createTempFile("structure", ".json");
        Files.write(spec, ("{ \"budget\": 1500, \"scenarios\": 4, \"visits\": 1,"
                + " \"variants\": { \"legacy-k3\": {\"base\": \"emcts\", \"selection\": \"LEGACY\", \"k\": 3},"
                + "                \"comma-k3\": {\"base\": \"emcts\", \"selection\": \"COMMA\", \"k\": 3},"
                + "                \"plus-k3\": {\"base\": \"emcts\", \"selection\": \"PLUS\", \"k\": 3},"
                + "                \"q\": {\"base\": \"emcts\", \"qdelta\": true} },"
                + " \"same\": [[\"legacy-k3\", \"comma-k3\"], [\"comma-k3\", \"emcts-comma\"], [\"q\", \"emcts\"]],"
                + " \"differ\": [[\"plus-k3\", \"comma-k3\"]],"
                + " \"branching\": [{\"arm\": \"legacy-k3\", \"exactly\": 3}] }").getBytes(StandardCharsets.UTF_8));
        StructureCheck.Result res = StructureCheck.run(game, r, p.scenarios(), spec, 2);
        assertTrue(res.failures.toString(), res.ok());
        assertEquals(5, res.passes.size());

        Path bad = Files.createTempFile("structure-bad", ".json");
        Files.write(bad, ("{ \"budget\": 1500, \"scenarios\": 2,"
                + " \"same\": [[\"emcts\", \"emcts-comma\"]], \"differ\": [[\"emcts\", \"emcts\"]] }").getBytes(StandardCharsets.UTF_8));
        StructureCheck.Result fails = StructureCheck.run(game, r, p.scenarios(), bad, 2);
        assertEquals(2, fails.failures.size());
    }
}
