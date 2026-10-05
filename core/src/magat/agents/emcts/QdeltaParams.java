package magat.agents.emcts;

import magat.game.Granularity;

/**
 * Parameters of {@link QdeltaEmcts}. Every default is the do-nothing setting: an agent built from
 * unmodified parameters is bit-identical to plain {@link Emcts} (epsilon 1 never flips the coin; full
 * repair is the base class's own code).
 *
 * <p>From the thesis tree's {@code Stage1Params}, every field kept.
 */
public class QdeltaParams extends EmctsParams {

    /** What the repair does when an action of a mutated plan has to be replaced. */
    public enum RepairMode {
        /** Exhaustive greedy: copy, advance and evaluate every legal candidate (the base behaviour). */
        FULL,
        /** Rank candidates by the learned table (free) and evaluate only the top {@link #shortlistK}. */
        SHORTLIST_K,
        /** Take the table's argmax outright: no copy, no candidate advance, no evaluation. */
        ZERO_EVAL,
        /** The attribution control of {@link #SHORTLIST_K}: the same K candidates evaluated, chosen at random,
         *  with the same number of random draws. */
        SHORTLIST_RANDOM,
        /** The attribution control of {@link #ZERO_EVAL}: an arbitrary legal action, the same zero calls. */
        ZERO_EVAL_RANDOM
    }

    /** Probability the insertion draws uniformly; otherwise it takes the table's argmax (ties uniform). 1 = off. */
    public double epsilon = 1.0;
    /** Granularity the insertion reads. */
    public Granularity insertKeys = Granularity.MID;
    /** Granularity the repair ranker reads; separate from insertion because the two decisions want different ones. */
    public Granularity repairKeys = Granularity.MID;
    /** Credit the removed action with the negated delta. */
    public boolean removalCredit = true;
    /** Selection reads a snapshot frozen at the last bridge burn instead of the live table. */
    public boolean perBurnUpdates = false;
    public RepairMode repairMode = RepairMode.FULL;
    /** Shortlist size: positive for the shortlist modes, 0 for every other. */
    public int shortlistK = 0;
    /** Shrinkage prior strength m: Q(a) = (sum_a + m * globalMean) / (n_a + m). */
    public double shrinkageM = 5.0;

    @Override
    public QdeltaParams copy() {
        QdeltaParams p = new QdeltaParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        p.bias = bias;
        p.randomInitialSequence = randomInitialSequence;
        p.selection = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        p.epsilon = epsilon;
        p.insertKeys = insertKeys;
        p.repairKeys = repairKeys;
        p.removalCredit = removalCredit;
        p.perBurnUpdates = perBurnUpdates;
        p.repairMode = repairMode;
        p.shortlistK = shortlistK;
        p.shrinkageM = shrinkageM;
        return p;
    }

    /** One line naming every setting, for descriptions and logs. */
    public String describe() {
        return String.format(java.util.Locale.ROOT, "%s k=%d, epsilon %.2f, insert %s, repair %s by %s%s, removal credit %s, %s updates",
                selection, childrenPerGeneration, epsilon, insertKeys, repairMode, repairKeys,
                shortlistK > 0 ? " (K=" + shortlistK + ")" : "", removalCredit ? "on" : "off", perBurnUpdates ? "per-burn" : "continuous");
    }
}
