package magat.games.tribes;

import magat.calibrate.Currency;
import magat.meter.Profile;
import magat.meter.Weights;
import magat.portfolio.Manifest;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** The imported R7615 currency loads, belongs to the published portfolio, and prices every worked-result arm. */
public class PublishedCalibrationTest {

    private static final Path CALIBRATION = Paths.get("games", "tribes", "calibration", "r7615", Currency.WEIGHTS_FILE);
    private static final Path PORTFOLIO = Paths.get("games", "tribes", "portfolio", "v1");
    private static final long[] LEVELS = {40_000L, 80_000L, 400_000L};

    @Test
    public void pricesEveryWorkedResultArmAtEveryLevel() throws Exception {
        Weights w = Weights.load(CALIBRATION);
        assertEquals(34, TribesAgents.WORKED_RESULT.size());
        List<String> want = new ArrayList<>();
        for (String arm : TribesAgents.WORKED_RESULT) for (long l : LEVELS) want.add(Profile.key(arm, l));
        assertEquals("missing: " + w.missing(want), 0, w.missing(want).size());
        for (String arm : TribesAgents.REFERENCE) for (long l : LEVELS) assertTrue(arm + "@" + l, w.has(Profile.key(arm, l)));
        for (String p : want) for (double x : w.profile(p)) assertTrue(p, x > 0 && x < 10);
    }

    @Test
    public void isTheVersionOneCurrencyOfThePublishedPortfolio() throws Exception {
        Weights w = Weights.load(CALIBRATION);
        assertEquals("1", w.header().get("magat.weights.version"));
        assertEquals("tribes", w.game());
        assertEquals("emcts@40000", w.reference());
        assertEquals(62870.569, w.referenceAdvanceNanos(), 1e-9);
        assertEquals("8,4,2", w.turnsPerRep());
        assertEquals(Manifest.read(PORTFOLIO).identity(), w.portfolioIdentity());
        // The unit is the engine EMCTS advance; the ported emcts is 0.07% dearer, which is the merge factor.
        assertEquals(1.000659, Currency.factor(w), 1e-9);
        assertNull("the published currency is not a toy", w.header().get("toy"));
    }
}
