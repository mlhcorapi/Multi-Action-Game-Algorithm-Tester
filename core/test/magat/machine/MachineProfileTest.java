package magat.machine;

import org.junit.Test;

import static org.junit.Assert.*;

public class MachineProfileTest {

    @Test
    public void theCeilingIsPhysicalCoresLessTheReserveCappedByTheOverride() {
        assertEquals(61, new MachineProfile(128, 64, 2, 1, "x", true, 3, -1).maxWorkers());
        assertEquals(20, new MachineProfile(128, 64, 2, 1, "x", true, 3, 20).maxWorkers());
        assertEquals(1, new MachineProfile(2, 1, 2, 1, "x", false, 1, -1).maxWorkers());
    }

    @Test
    public void theLadder() {
        ladderIsAscendingAndCapped();
    }

    public static void ladderIsAscendingAndCapped() {
        int[] l = MachineProfile.ladder(61);
        assertArrayEquals(new int[]{8, 15, 23, 31, 38, 46, 53, 61}, l);
        assertArrayEquals(new int[]{1}, MachineProfile.ladder(1));
        assertArrayEquals(new int[]{1, 2, 3}, MachineProfile.ladder(3));
    }
}
