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

    public static <S, A> AgentFactory<S, A> coarseFactory() {
        return QdeltaEmcts.factory("comma-z0-coarse", "AB-EMCTS: EMCTS whose repair takes a learned table's best action", coarse());
    }

    public static <S, A> AgentFactory<S, A> randomFactory() {
        return QdeltaEmcts.factory("comma-z0-random", "AB-EMCTS's control: the same repair by an arbitrary legal action", random());
    }
}
