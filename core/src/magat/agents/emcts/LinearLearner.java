package magat.agents.emcts;

import java.util.Arrays;

/**
 * A linear model on the class identities: the estimate is the sum of a learned coarse, mid and fine effect,
 * plus learned weights on the position and the progress. Fitted online by one stochastic-gradient step per
 * example on the standardized change ({@link TargetScale}), with L2 regularization. It starts at zero, so
 * every action ties until it has learned something, as an empty table's do.
 *
 * <p>Between {@link HierTable} (counting, with backoff) and {@link MlpLearner} (the same inputs, nonlinear),
 * it isolates what additive regression adds.
 */
final class LinearLearner implements InTurnLearner {

    static final double LEARNING_RATE = 0.05;
    static final double L2 = 1e-4;

    private final double[] w = new double[ClassFeatures.SIZE];
    private double bias;
    private final TargetScale scale = new TargetScale();
    private double sqErr;
    private long errN;

    @Override
    public void reset() {
        Arrays.fill(w, 0.0);
        bias = 0.0;
        scale.reset();
        sqErr = 0.0;
        errN = 0;
    }

    @Override
    public double estimate(String[] k, double position, double progress) {
        int[] a = ClassFeatures.active(k);
        return bias + w[a[0]] + w[a[1]] + w[a[2]] + w[ClassFeatures.POSITION] * position + w[ClassFeatures.PROGRESS] * progress;
    }

    @Override
    public void credit(String[] k, double position, double progress, double delta) {
        double y = scale.standardize(delta);
        double err = y - estimate(k, position, progress);
        sqErr += err * err;
        errN++;
        for (int i : ClassFeatures.active(k)) w[i] += LEARNING_RATE * (err - L2 * w[i]);
        w[ClassFeatures.POSITION] += LEARNING_RATE * (err * position - L2 * w[ClassFeatures.POSITION]);
        w[ClassFeatures.PROGRESS] += LEARNING_RATE * (err * progress - L2 * w[ClassFeatures.PROGRESS]);
        bias += LEARNING_RATE * err;
    }

    @Override
    public double meanSquaredError() {
        return errN > 0 ? sqErr / errN : 0.0;
    }
}
