package magat.probe;

import magat.agents.emcts.EmctsFactory;
import magat.meter.Meter;
import magat.meter.Weights;
import magat.portfolio.Portfolio;
import magat.portfolio.PortfolioBuilder;
import magat.portfolio.Split;
import magat.run.Experiment;
import magat.run.GridRunner;
import magat.testgame.BusyAgent;
import magat.testgame.Stdout;
import magat.testgame.ToyArms;
import magat.testgame.ToyGame;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/** Replay of a MAGAT leaf, the time check and the audit, on the toy game. */
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
        StringBuilder file = new StringBuilder("magat.weights.version = 2\nreference = emcts@400\nreferenceAdvanceNanos = 1000\n");
        for (String arm : new String[]{"emcts-alt", "emcts"}) {
            String pre = "profile." + arm + "@400.";
            file.append(pre).append("w.advance = 1\n").append(pre).append("w.copy = 0.2\n").append(pre).append("w.eval = 0.1\n");
            for (String op : magat.agents.emcts.Emcts.OPERATIONS) file.append(pre).append("w.").append(op).append(" = 0\n");
        }
        Files.write(weights, file.toString().getBytes(StandardCharsets.UTF_8));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\",\"levels\":[{\"budget\":400,\"visits\":2}],"
                + "\"arms\":[\"emcts\",\"emcts-alt\"]}").getBytes(StandardCharsets.UTF_8));
        GridRunner.Options o = new GridRunner.Options();
        o.split = Split.Half.ALL;
        o.out = work.resolve("run");
        o.threads = 2;
        Weights w = Weights.load(weights);
        assertEquals(GridRunner.OK, GridRunner.run(magat.run.GridRunnerTest.module(game, new ArrayList<>()), Experiment.load(spec), p, w, o));

        Path leafDir = magat.run.Leaf.dir(o.out.resolve("all"), "emcts-alt", 400);
        ReplayCheck.Leaf leaf = ReplayCheck.read(leafDir);
        assertEquals("magat", leaf.format);
        Meter.install(w);
        ReplayCheck.Result ok = ReplayCheck.run(game, p, ToyArms.<ToyGame.State, ToyGame.Move>emctsAlt(), 400, leaf, 100, 2);
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
        Weights w = Weights.load(magat.run.GridRunnerTest.weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-alt"));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\",\"levels\":[{\"budget\":300,\"visits\":2}],"
                + "\"arms\":[\"emcts\",\"emcts-alt\"]}").getBytes(StandardCharsets.UTF_8));
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
    public void aVersionTwoRunReplaysFieldForField() throws Exception {
        Path pdir = Files.createTempDirectory("magat-replay3-portfolio");
        PortfolioBuilder.build(game, Collections.singletonMap("toy", 6), pdir, 1);
        Split.freeze(pdir);
        Portfolio<ToyGame.State> p = Portfolio.load(game, pdir);
        Path work = Files.createTempDirectory("magat-replay3");
        Weights w = Weights.load(magat.run.GridRunnerTest.weightsV2(work, "w2.properties", 0.5, "emcts", "emcts-alt"));
        Path spec = Files.write(work.resolve("s.json"), ("{\"name\":\"r\",\"game\":\"toy\",\"baseline\":\"emcts\","
                + "\"levels\":[{\"budget\":300,\"visits\":2}],\"arms\":[\"emcts\",\"emcts-alt\"]}").getBytes(StandardCharsets.UTF_8));
        GridRunner.Options o = new GridRunner.Options();
        o.split = Split.Half.ALL;
        o.out = work.resolve("run");
        o.threads = 2;
        assertEquals(GridRunner.OK, GridRunner.run(magat.run.GridRunnerTest.module(game, new ArrayList<>()), Experiment.load(spec), p, w, o));
        Path leafDir = magat.run.Leaf.dir(o.out.resolve("all"), "emcts", 300);
        ReplayCheck.Leaf leaf = ReplayCheck.read(leafDir);
        Meter.install(w);
        ReplayCheck.Result ok = ReplayCheck.run(game, p, EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), 300, leaf, 100, 2);
        assertTrue(ok.failures.toString(), ok.pass());
    }

    @Test
    public void aTimeCheckReportsEveryTurnOfEveryArmAtEveryLevel() throws Exception {
        java.util.List<magat.game.Scenario<ToyGame.State>> positions = game.positions(5);
        java.util.List<TimeCheck.Cell> cells = TimeCheck.run(game, java.util.Arrays.asList(EmctsFactory.<ToyGame.State, ToyGame.Move>plain(),
                ToyArms.<ToyGame.State, ToyGame.Move>emctsAlt()), positions, new long[]{300, 600}, new double[]{1000, 1000}, 4, 1, true);
        assertEquals(4, cells.size());
        for (TimeCheck.Cell c : cells) {
            assertEquals(4, c.ms.length);
            assertTrue(c.meanMs > 0 && c.minMs <= c.medianMs && c.medianMs <= c.maxMs && c.q1Ms <= c.q3Ms);
            assertTrue("a turn spends its level, its initialization included (" + c.meanUnits + ")", c.meanUnits >= c.budget * 0.9);
            assertEquals(c.meanUnits / c.budget, c.spend(), 1e-12);
            assertTrue(c.meanInitUnits > 0);
            assertEquals(c.meanMs / 1000, c.ratio(), 1e-12);
            assertFalse("a toy turn is nowhere near a second", c.within(0.05));
        }
        assertEquals("emcts@300 then emcts-alt@300", "emcts-alt", cells.get(1).arm);
        assertEquals(600, cells.get(2).budget);
        Path out = Files.createTempDirectory("magat-time").resolve("time.json");
        TimeCheck.write(out, TimeCheck.json(cells, "fixed", 4, "toy"));
        String json = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"mean_ms\"") && json.contains("\"ratio\"") && json.contains("\"positions\": 4"));
        assertFalse("profiling is left as it was found", Meter.PROFILE);
    }

    @Test
    public void aTimeCheckFlagsACellThatSpendsPastItsLevel() throws Exception {
        java.util.List<magat.game.Scenario<ToyGame.State>> positions = game.positions(3);
        Stdout.Captured<java.util.List<TimeCheck.Cell>> timed = Stdout.capture(() -> TimeCheck.run(game, java.util.Arrays.asList(
                EmctsFactory.<ToyGame.State, ToyGame.Move>plain(), ToyArms.<ToyGame.State, ToyGame.Move>spendthrift()),
                positions, new long[]{300}, null, 3, 1, true));
        TimeCheck.Cell spendthrift = timed.value.get(1);
        assertEquals(3.0, spendthrift.spend(), 0.1);
        assertTrue(spendthrift.overspends());
        assertTrue(timed.out, timed.out.contains("spendthrift@300") && timed.out.contains("<-- spends over its level"));
        assertTrue(TimeCheck.overspent(timed.value).toString(), TimeCheck.overspent(timed.value).get(
                TimeCheck.overspent(timed.value).size() - 1).startsWith("spendthrift@300 3.0"));
    }

    @Test
    public void theAuditFailsAnArmWhoseWorkIsUndeclared() throws Exception {
        java.util.List<magat.game.Scenario<ToyGame.State>> positions = game.positions(2);
        java.util.List<AuditCheck.Audit> a = AuditCheck.audit(game, java.util.Arrays.asList(
                BusyAgent.<ToyGame.State, ToyGame.Move>factory("busy-declared", true),
                BusyAgent.<ToyGame.State, ToyGame.Move>factory("busy-undeclared", false)), 300, positions, 2, 1);
        assertTrue("declared: " + a.get(0).unaccounted, a.get(0).unaccounted < AuditCheck.AUDIT_WARN);
        assertTrue(a.get(0).shares.get(BusyAgent.WORK) > 0.5);
        assertTrue("undeclared: " + a.get(1).unaccounted, a.get(1).unaccounted > AuditCheck.AUDIT_FAIL);
        assertTrue(Stdout.capture(() -> AuditCheck.report(a.subList(0, 1), AuditCheck.AUDIT_WARN, AuditCheck.AUDIT_FAIL)).value);
        Stdout.Captured<Boolean> failed = Stdout.capture(() -> AuditCheck.report(a, AuditCheck.AUDIT_WARN, AuditCheck.AUDIT_FAIL));
        assertFalse(failed.value);
        assertTrue(failed.out, failed.out.contains("busy-undeclared") && failed.out.contains("<-- FAIL"));
    }
}
