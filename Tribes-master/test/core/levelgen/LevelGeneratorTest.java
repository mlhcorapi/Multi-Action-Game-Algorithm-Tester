package core.levelgen;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Field;

public class LevelGeneratorTest {

    @Test
    public void testGetResourceHappyPath() throws Exception {
        LevelGenerator lg = new LevelGenerator(123L);
        // Using reflection to inject custom level
        Field levelField = LevelGenerator.class.getDeclaredField("level");
        levelField.setAccessible(true);
        String[] mockLevel = new String[5];
        mockLevel[0] = "0: R"; // space character
        mockLevel[1] = "1:F";  // valid char
        mockLevel[2] = "2:";   // no resource char
        mockLevel[3] = "3:M:"; // extra colons
        mockLevel[4] = "4::";  // malformed
        levelField.set(lg, mockLevel);

        // "0: R" length is >1 but first char after colon is ' ' -> ""
        assertEquals("", lg.getResource(0));

        // "1:F" -> pieces[1] = "F"
        assertEquals("F", lg.getResource(1));

        // "2:" -> pieces length is 1 (split drops trailing empty string)
        assertEquals("", lg.getResource(2));

        // "3:M:" -> pieces[1] = "M"
        assertEquals("M", lg.getResource(3));
    }

    @Test
    public void testGetResourceExceptionPath() throws Exception {
        LevelGenerator lg = new LevelGenerator(123L);
        // Using reflection to inject custom level
        Field levelField = LevelGenerator.class.getDeclaredField("level");
        levelField.setAccessible(true);
        String[] mockLevel = new String[1];
        // Malformed string like "4::" where pieces.length is > 1 but pieces[1] is empty length
        mockLevel[0] = "4::F";
        levelField.set(lg, mockLevel);

        // "4::F" -> pieces[0] = "4", pieces[1] = "", pieces[2] = "F"
        // This triggers StringIndexOutOfBoundsException on pieces[1].charAt(0) which is caught and returns ""
        assertEquals("", lg.getResource(0));
    }
}
