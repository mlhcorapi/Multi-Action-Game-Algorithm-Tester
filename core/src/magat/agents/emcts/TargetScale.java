package magat.agents.emcts;

/**
 * The models' targets, standardized online: a change in value is turned into its distance from the turn's
 * running mean in running standard deviations, so a learning rate means the same thing whatever the game's
 * scale. Robust to the outliers a game's evaluation can produce (a plan that ends the game is worth about
 * 2e9 in Tribes): once ten changes are seen, a change is first clipped to five deviations of the mean, and
 * every standardized target is clipped to [-5, 5].
 */
final class TargetScale {

    private long n;
    private double mean, m2;

    void reset() {
        n = 0;
        mean = 0.0;
        m2 = 0.0;
    }

    /** Takes in one change and returns it standardized. */
    double standardize(double delta) {
        double d = delta;
        if (n >= 10) {
            double lim = 5.0 * sd();
            d = Math.max(mean - lim, Math.min(mean + lim, d));
        }
        n++;
        double before = mean;
        mean += (d - before) / n;
        m2 += (d - before) * (d - mean);
        return Math.max(-5.0, Math.min(5.0, (d - mean) / sd()));
    }

    private double sd() {
        if (n < 2) return 1.0;
        return Math.max(StrictMath.sqrt(m2 / (n - 1)), 1e-9);
    }
}
