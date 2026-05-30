package core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiplomacyTest {

    @Test
    public void testCheckConsequencesNegativeRelationship() {
        Diplomacy diplomacy = new Diplomacy(3);

        diplomacy.setAllegianceStatus(1, 2, -10);
        diplomacy.setAllegianceStatus(2, 1, -10);

        diplomacy.checkConsequences(20, 0, 2);

        int[][] statuses = diplomacy.getAllegianceStatus();
        assertEquals("Test failed! Expected statuses[1][0] to be -10", -10, statuses[1][0]);
        assertEquals("Test failed! Expected statuses[0][1] to be -10", -10, statuses[0][1]);
    }

    @Test
    public void testCheckConsequencesPositiveRelationship() {
        Diplomacy diplomacy = new Diplomacy(3);

        // Positive relationship, should not trigger consequences
        diplomacy.setAllegianceStatus(1, 2, 10);
        diplomacy.setAllegianceStatus(2, 1, 10);

        diplomacy.checkConsequences(20, 0, 2);

        int[][] statuses = diplomacy.getAllegianceStatus();
        assertEquals("Test failed! Expected statuses[1][0] to be 0", 0, statuses[1][0]);
        assertEquals("Test failed! Expected statuses[0][1] to be 0", 0, statuses[0][1]);
    }

    @Test
    public void testCheckConsequencesInitIsTarget() {
        Diplomacy diplomacy = new Diplomacy(3);

        diplomacy.setAllegianceStatus(1, 2, -10);
        diplomacy.setAllegianceStatus(2, 1, -10);

        // This will have i = 1, targetTribeID = 2.
        // And i != initTribeID -> 1 != 1 which is false.
        // So no consequences should trigger for i=1 if init=1.
        diplomacy.checkConsequences(20, 1, 2);

        int[][] statuses = diplomacy.getAllegianceStatus();
        // Relationship between 1 and 1 shouldn't be affected by this consequence logic normally,
        // but even if it were, we check if it tried to update anything incorrectly.
        // Let's check a dummy 0th index relationship to see if it stayed 0.
        assertEquals("Test failed! Expected statuses[1][0] to be 0", 0, statuses[1][0]);
    }

    @Test
    public void testCheckConsequencesMultipleRelationships() {
        Diplomacy diplomacy = new Diplomacy(4);

        diplomacy.setAllegianceStatus(1, 3, -10);
        diplomacy.setAllegianceStatus(3, 1, -10);

        diplomacy.setAllegianceStatus(2, 3, -20);
        diplomacy.setAllegianceStatus(3, 2, -20);

        // target 3 has negative relationships with 1 and 2
        // init is 0
        diplomacy.checkConsequences(10, 0, 3);

        // value 10 gets halved and inverted to -5
        int[][] statuses = diplomacy.getAllegianceStatus();
        assertEquals("Test failed! Expected statuses[1][0] to be -5", -5, statuses[1][0]);
        assertEquals("Test failed! Expected statuses[0][1] to be -5", -5, statuses[0][1]);
        assertEquals("Test failed! Expected statuses[2][0] to be -5", -5, statuses[2][0]);
        assertEquals("Test failed! Expected statuses[0][2] to be -5", -5, statuses[0][2]);
    }
}
