package magat.portfolio;

import magat.game.Scenario;
import magat.testgame.ToyGame;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class PortfolioTest {

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static Path writePortfolio(ToyGame game, boolean legacyHeader) throws IOException {
        Path dir = Files.createTempDirectory("magat-portfolio");
        Files.createDirectories(dir.resolve(Portfolio.SCENARIOS_DIR));
        List<Scenario<ToyGame.State>> positions = game.positions(4);
        List<String> lines = new ArrayList<>();
        lines.add(legacyHeader
                ? "ScenarioId,Source,SourceGameId,SourceLevelSeed,SourceGameSeed,CapturedTick,Pos0,Pos1,Pos2,BranchingFactor"
                : "# magat-portfolio: 1\n# game: toy\nScenarioId,Source,SourceAttempt,SourceSeed,Phase,Branching,Pos0,Pos1,Pos2");
        for (Scenario<ToyGame.State> sc : positions) {
            String facts = sc.facts.get("Pos0") + "," + sc.facts.get("Pos1") + "," + sc.facts.get("Pos2");
            lines.add(legacyHeader
                    ? sc.id + "," + sc.source + "," + sc.attempt + "," + sc.seed + ",9," + sc.phase + "," + facts + "," + sc.branching
                    : sc.id + "," + sc.source + "," + sc.attempt + "," + sc.seed + "," + sc.phase + "," + sc.branching + "," + facts);
            Files.write(dir.resolve(Portfolio.SCENARIOS_DIR).resolve(Portfolio.binName(sc.id)), game.serialize(sc.state));
        }
        Files.write(dir.resolve(Portfolio.MANIFEST), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void loadsVersionOneAndLegacyManifestsAlike() throws Exception {
        ToyGame game = new ToyGame();
        Portfolio<ToyGame.State> v1 = Portfolio.load(game, writePortfolio(game, false));
        Portfolio<ToyGame.State> legacy = Portfolio.load(game, writePortfolio(game, true));
        assertEquals(4, v1.size());
        assertEquals(4, legacy.size());
        assertEquals(v1.identity(), legacy.identity());
        List<Scenario<ToyGame.State>> expected = game.positions(4);
        StringBuilder sb = new StringBuilder();
        for (Scenario<ToyGame.State> sc : expected) sb.append(sc.identityLine()).append('\n');
        assertEquals(sha256(sb.toString()), v1.identity());
        for (int i = 0; i < 4; i++) {
            Scenario<ToyGame.State> a = v1.scenarios().get(i), b = legacy.scenarios().get(i), e = expected.get(i);
            assertEquals(e.id, a.id); assertEquals(e.phase, b.phase); assertEquals(e.branching, a.branching);
            assertEquals(e.facts.get("Pos1"), a.facts.get("Pos1"));
            assertEquals(e.facts.get("Pos1"), b.facts.get("Pos1"));
            assertEquals(game.evaluate(e.state, 0), game.evaluate(a.state, 0), 0.0);
        }
        assertEquals(2, v1.spread(2).size());
        assertNotNull(v1.byId(3));
    }
}
