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
