package core.levelgen;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class LevelGeneratorTest {

    private LevelGenerator generator;

    @Before
    public void setUp() {
        generator = new LevelGenerator(12345L);
        // Initialize simple 3x3 map
        generator.init(3, 1, 1, 0.5, new core.Types.TRIBE[0], false);
    }

    @Test
    public void testCheckResources() {
        // Mock a scenario where a specific resource surrounds the capital
        generator.writeTile(0, "P", "A");
        generator.writeTile(1, "P", "B");
        generator.writeTile(2, "P", "C");
        generator.writeTile(3, "P", "A");
        generator.writeTile(4, "P", "C"); // Capital (index 4)
        generator.writeTile(5, "P", "A");
        generator.writeTile(6, "P", "D");
        generator.writeTile(7, "P", "A");
        generator.writeTile(8, "P", "B");

        // The neighbors of 4 in a 3x3 grid (circle(4, 1)) are 0,1,2,3,5,6,7,8
        // 'A' resources: index 0, 3, 5, 7. Total 4.

        int countA = generator.checkResources('A', 4);
        assertEquals(4, countA);

        int countB = generator.checkResources('B', 4);
        assertEquals(2, countB);

        int countC = generator.checkResources('C', 4); // Not counting the capital itself
        assertEquals(1, countC); // Only index 2 has 'C'

        int countD = generator.checkResources('D', 4);
        assertEquals(1, countD);
    }

    @Test
    public void testCheckResourcesEmptyMap() {
        // Map is initialized with just terrain, no resources
        int countA = generator.checkResources('A', 4);
        assertEquals(0, countA);
    }

    @Test
    public void testCheckResourcesEdgeCaseEmptyResource() {
        // Test edge case where getResource returns an empty string (length 0)
        generator.writeTile(0, "P", null); // Should have empty resource string or " "
        generator.writeTile(1, "P", "");
        generator.writeTile(2, "P", " ");

        // No 'A' resources here, should not throw StringIndexOutOfBoundsException
        int countA = generator.checkResources('A', 4);
        assertEquals(0, countA);
    }
}
