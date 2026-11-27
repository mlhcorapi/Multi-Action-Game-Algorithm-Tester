package magat.agents.emcts;

import magat.agent.AgentFactory;
import magat.game.Granularity;

/**
 * The AB-EMCTS experiment's arms beyond {@code emcts-comma} (docs/plans/ab-emcts-vs-oep.md), as the thesis
 * froze them: the champion and its attribution control. Both run on (1,5) comma selection.
 */
public final class QdeltaArms {

    private QdeltaArms() {}

    private static QdeltaParams arm(QdeltaParams.RepairMode mode, Granularity repairKeys) {
        QdeltaParams p = new QdeltaParams();
        p.selection = Selection.COMMA;
        p.childrenPerGeneration = 5;
        p.repairMode = mode;
        p.repairKeys = repairKeys;
        p.removalCredit = true;
        return p;
    }

    /** The thesis's champion ({@code Comma_Z0_Coarse}): zero-evaluation repair by the table at coarse keys. */
    public static QdeltaParams coarse() {
        return arm(QdeltaParams.RepairMode.ZERO_EVAL, Granularity.COARSE);
    }

    /**
     * Its attribution control ({@code Comma_Z0_Random}): the same repair with the ranking replaced by an arbitrary
     * legal action. Its table is kept and credited as the champion's is, at the same cost; its {@code repairKeys}
     * (mid, as the thesis froze it) are never read, since nothing is ranked.
     */
    public static QdeltaParams random() {
        return arm(QdeltaParams.RepairMode.ZERO_EVAL_RANDOM, Granularity.MID);
    }

    /** {@link #coarse()} read at mid keys: the in-turn learning plan's {@code T-mid}. */
    public static QdeltaParams mid() {
        return arm(QdeltaParams.RepairMode.ZERO_EVAL, Granularity.MID);
    }

    /** {@link #coarse()} read at fine keys, the action itself: {@code T-fine}. */
    public static QdeltaParams fine() {
        return arm(QdeltaParams.RepairMode.ZERO_EVAL, Granularity.FINE);
    }

    /** The repair ranked by another learner than a table of independent classes (docs/plans/in-turn-learning.md). */
    public static QdeltaParams learned(QdeltaParams.Learner learner) {
        QdeltaParams p = arm(QdeltaParams.RepairMode.ZERO_EVAL, Granularity.COARSE);
        p.learner = learner;
        return p;
    }

    public static <S, A> AgentFactory<S, A> coarseFactory() {
        return QdeltaEmcts.factory("comma-z0-coarse", "AB-EMCTS: EMCTS whose repair takes a learned table's best action", coarse());
    }

    public static <S, A> AgentFactory<S, A> midFactory() {
        return QdeltaEmcts.factory("comma-z0-mid", "In-turn learning: the repair ranked by a table at mid keys", mid());
    }

    public static <S, A> AgentFactory<S, A> fineFactory() {
        return QdeltaEmcts.factory("comma-z0-fine", "In-turn learning: the repair ranked by a table at fine keys", fine());
    }

    public static <S, A> AgentFactory<S, A> hierFactory() {
        return QdeltaEmcts.factory("comma-z0-hier", "In-turn learning: the repair ranked by a hierarchical table (fine, backed off to mid and coarse)",
                learned(QdeltaParams.Learner.HIER));
    }

    public static <S, A> AgentFactory<S, A> linearFactory() {
        return QdeltaEmcts.factory("comma-z0-linear", "In-turn learning: the repair ranked by a linear model on the classes, fitted online",
                learned(QdeltaParams.Learner.LINEAR));
    }

    public static <S, A> AgentFactory<S, A> mlpFactory() {
        return QdeltaEmcts.factory("comma-z0-mlp", "In-turn learning: the repair ranked by a small neural network on the classes, trained online",
                learned(QdeltaParams.Learner.MLP));
    }

    public static <S, A> AgentFactory<S, A> randomFactory() {
        return QdeltaEmcts.factory("comma-z0-random", "AB-EMCTS's control: the same repair by an arbitrary legal action", random());
    }
}
