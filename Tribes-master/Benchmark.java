import core.levelgen.LevelGenerator;
import core.Types;

public class Benchmark {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        int runs = 500;
        for (int i = 0; i < runs; i++) {
            LevelGenerator lg = new LevelGenerator(i);
            lg.init(30, 3, 4, 0.5, new Types.TRIBE[]{Types.TRIBE.XIN_XI, Types.TRIBE.OUMAJI, Types.TRIBE.BARDUR, Types.TRIBE.KICKOO}, true);
            lg.generate();
        }
        long end = System.currentTimeMillis();
        System.out.println("Time taken: " + (end - start) + "ms");
    }
}
