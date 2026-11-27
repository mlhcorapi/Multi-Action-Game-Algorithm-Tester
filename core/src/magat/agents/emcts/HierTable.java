package magat.agents.emcts;

import magat.game.Granularity;

/**
 * A hierarchical table: a fine class's mean change shrunk toward its mid class's estimate, which is shrunk
 * toward its coarse class's, which is shrunk toward the turn's mean. A class seen rarely falls back on the
 * coarser one it belongs to, so fine information is used where there is enough of it and coarse where there
 * is not. Every shrinkage has the same prior strength m: Q = (sum + m * parent) / (n + m).
 */
final class HierTable implements InTurnLearner {

    private final ActionTable table = new ActionTable(true);
    private final double m;
    private double sqErr;
    private long errN;

    HierTable(double shrinkageM) {
        this.m = shrinkageM;
    }

    @Override
    public void reset() {
        table.reset();
        sqErr = 0.0;
        errN = 0;
    }

    @Override
    public void credit(String[] k, double position, double progress, double delta) {
        double e = delta - estimate(k, position, progress);
        sqErr += e * e;
        errN++;
        table.add(k[0], k[1], k[2], delta);
    }

    @Override
    public double estimate(String[] k, double position, double progress) {
        double coarse = shrink(table.entry(Granularity.COARSE, k[0]), table.globalMean());
        double mid = shrink(table.entry(Granularity.MID, k[1]), coarse);
        return shrink(table.entry(Granularity.FINE, k[2]), mid);
    }

    private double shrink(double[] e, double prior) {
        return (e[0] + m * prior) / (e[1] + m);
    }

    @Override
    public double meanSquaredError() {
        return errN > 0 ? sqErr / errN : 0.0;
    }
}
