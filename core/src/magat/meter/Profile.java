package magat.meter;

/**
 * The name of a priced cell: {@code <arm>@<budget>}. Built in exactly one place so the calibrator
 * that writes a weight and the episode that reads it cannot drift apart.
 */
public final class Profile {

    private Profile() {}

    public static String key(String arm, long budgetUnits) {
        if (arm == null || arm.isEmpty() || arm.indexOf('@') >= 0)
            throw new IllegalArgumentException("arm id must be non-empty and contain no '@': " + arm);
        return arm + "@" + budgetUnits;
    }

    public static String arm(String key) {
        int at = key.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("not a profile key: " + key);
        return key.substring(0, at);
    }

    public static long budget(String key) {
        int at = key.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("not a profile key: " + key);
        return Long.parseLong(key.substring(at + 1));
    }
}
