package magat.agents.emcts;

import magat.game.Granularity;

/**
 * Parameters of {@link QdeltaEmcts}: {@link EmctsParams} plus the learned table's. Only the settings the
 * AB-EMCTS experiment runs are here (docs/plans/ab-emcts-vs-oep.md): insertion uniform, as in EMCTS;
 * every mutation's change in value credited to the inserted action's classes and, negated, to the removed
 * one's; a live table; and a repair that replaces an action by the table's best estimate, or by an
 * arbitrary legal action in the attribution control. The thesis's other settings stay archived
 * ({@code ab-emcts-archive}).
 */
public class QdeltaParams extends EmctsParams {

    /** What the repair does when an action of a mutated plan has to be replaced. */
    public enum RepairMode {
        /** Take the table's best estimate at {@link #repairKeys}: no copy, no candidate advance, no evaluation. */
        ZERO_EVAL,
        /** The attribution control of {@link #ZERO_EVAL}: an arbitrary legal action, the same zero calls. */
        ZERO_EVAL_RANDOM
    }

    public RepairMode repairMode = RepairMode.ZERO_EVAL;
    /** The granularity the repair reads. */
    public Granularity repairKeys = Granularity.COARSE;
    /** Credit the removed action's classes with the negated change in value. */
    public boolean removalCredit = true;
    /** Shrinkage prior strength m: Q(a) = (sum_a + m * globalMean) / (n_a + m). */
    public double shrinkageM = 5.0;

    @Override
    public QdeltaParams copy() {
        QdeltaParams p = new QdeltaParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        p.selection = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        p.repairMode = repairMode;
        p.repairKeys = repairKeys;
        p.removalCredit = removalCredit;
        p.shrinkageM = shrinkageM;
        return p;
    }

    /** One line naming every setting, for descriptions and logs. */
    public String describe() {
        return String.format(java.util.Locale.ROOT, "%s k=%d, insertion uniform, repair %s by %s, removal credit %s, shrinkage m=%.1f",
                selection, childrenPerGeneration, repairMode, repairKeys, removalCredit ? "on" : "off", shrinkageM);
    }
}
