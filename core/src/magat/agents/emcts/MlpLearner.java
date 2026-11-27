package magat.agents.emcts;

import java.util.Arrays;
import java.util.Random;

/**
 * A small neural network on the class identities: {@link ClassFeatures}' inputs, one hidden layer of
 * {@value #HIDDEN} tanh units and a linear output, the predicted standardized change ({@link TargetScale}).
 * Trained online by one stochastic-gradient step per example (backpropagation), with L2 regularization.
 *
 * <ul>
 *   <li><b>Its weights</b> are drawn at the start of every turn from a stream of its own, seeded from the
 *       arm's seed, never from the search's: so it and {@link LinearLearner} make the same search draws, and
 *       differ in the model alone.</li>
 *   <li><b>Its output weights start at zero</b>, so every action ties until it has learned something, as an
 *       empty table's do.</li>
 *   <li><b>Deterministic:</b> {@code StrictMath.tanh}, and every sum in a fixed order.</li>
 * </ul>
 */
final class MlpLearner implements InTurnLearner {

    static final int HIDDEN = 16;
    static final double LEARNING_RATE = 0.02;
    static final double L2 = 1e-4;
    static final double INIT = 0.5;

    private final long seed;
    private final double[][] w1 = new double[ClassFeatures.SIZE][HIDDEN];
    private final double[] b1 = new double[HIDDEN];
    private final double[] w2 = new double[HIDDEN];
    private double b2;
    private final double[] h = new double[HIDDEN];
    private final TargetScale scale = new TargetScale();
    private double sqErr;
    private long errN;

    MlpLearner(long seed) {
        this.seed = seed;
    }

    @Override
    public void reset() {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL);
        for (double[] row : w1)
            for (int j = 0; j < HIDDEN; j++) row[j] = (2 * r.nextDouble() - 1) * INIT;
        Arrays.fill(b1, 0.0);
        Arrays.fill(w2, 0.0);
        b2 = 0.0;
        scale.reset();
        sqErr = 0.0;
        errN = 0;
    }

    /** The forward pass; leaves the hidden activations in {@link #h}. */
    private double forward(int[] a, double position, double progress) {
        double out = b2;
        for (int j = 0; j < HIDDEN; j++) {
            double z = b1[j] + w1[a[0]][j] + w1[a[1]][j] + w1[a[2]][j]
                    + w1[ClassFeatures.POSITION][j] * position + w1[ClassFeatures.PROGRESS][j] * progress;
            h[j] = StrictMath.tanh(z);
            out += w2[j] * h[j];
        }
        return out;
    }

    @Override
    public double estimate(String[] k, double position, double progress) {
        return forward(ClassFeatures.active(k), position, progress);
    }

    @Override
    public void credit(String[] k, double position, double progress, double delta) {
        double y = scale.standardize(delta);
        int[] a = ClassFeatures.active(k);
        double err = y - forward(a, position, progress);
        sqErr += err * err;
        errN++;
        for (int j = 0; j < HIDDEN; j++) {
            double dh = err * w2[j] * (1 - h[j] * h[j]);     // through the output weight as it was
            w2[j] += LEARNING_RATE * (err * h[j] - L2 * w2[j]);
            b1[j] += LEARNING_RATE * dh;
            for (int i : a) w1[i][j] += LEARNING_RATE * (dh - L2 * w1[i][j]);
            w1[ClassFeatures.POSITION][j] += LEARNING_RATE * (dh * position - L2 * w1[ClassFeatures.POSITION][j]);
            w1[ClassFeatures.PROGRESS][j] += LEARNING_RATE * (dh * progress - L2 * w1[ClassFeatures.PROGRESS][j]);
        }
        b2 += LEARNING_RATE * err;
    }

    @Override
    public double meanSquaredError() {
        return errN > 0 ? sqErr / errN : 0.0;
    }
}
