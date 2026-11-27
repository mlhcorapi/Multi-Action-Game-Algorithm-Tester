package magat.agents.emcts;

/**
 * The inputs the learned models read: the action's coarse, mid and fine classes, each hashed into a one-hot
 * block (64, 64 and 256 buckets: fine classes are far more numerous), and two numbers, the position in the
 * plan and the turn's progress. Three of the one-hot inputs are on for any action. {@code String.hashCode} is
 * fixed by the language, so the encoding is the same on every JVM.
 */
final class ClassFeatures {

    static final int COARSE_BUCKETS = 64, MID_BUCKETS = 64, FINE_BUCKETS = 256;
    static final int POSITION = COARSE_BUCKETS + MID_BUCKETS + FINE_BUCKETS;
    static final int PROGRESS = POSITION + 1;
    static final int SIZE = PROGRESS + 1;

    private ClassFeatures() {}

    /** The indices of the three one-hot inputs that are on. */
    static int[] active(String[] k) {
        return new int[]{
                Math.floorMod(hash(k[0]), COARSE_BUCKETS),
                COARSE_BUCKETS + Math.floorMod(hash(k[1]), MID_BUCKETS),
                COARSE_BUCKETS + MID_BUCKETS + Math.floorMod(hash(k[2]), FINE_BUCKETS)};
    }

    private static int hash(String s) {
        return s == null ? 0 : s.hashCode();
    }
}
