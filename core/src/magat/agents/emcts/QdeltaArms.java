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

    // ---- the in-turn learning plan's mutation rules and self-learning ideas (section 3), each on comma-z0-coarse

    /** {@link #coarse()}, the champion, as the base every mutation rule changes one thing of. */
    private static QdeltaParams onCoarse() {
        return coarse();
    }

    /** ε-greedy insertion at coarse keys, the engine's rule: {@code greedy} of the insertions take the best estimate. */
    public static QdeltaParams eps(double epsilon) {
        QdeltaParams p = onCoarse();
        p.insertion = QdeltaParams.Insertion.EPSILON;
        p.epsilon = epsilon;
        p.insertKeys = Granularity.COARSE;
        return p;
    }

    /** Thompson-sampled insertion at coarse keys: {@code share} of the insertions, the rest uniform. */
    public static QdeltaParams thompson(double share) {
        QdeltaParams p = onCoarse();
        p.insertion = QdeltaParams.Insertion.THOMPSON;
        p.biasShare = share;
        p.insertKeys = Granularity.COARSE;
        return p;
    }

    /** Children {@code k} mutations from their parent: none, the first, or all of the insertions Thompson-biased. */
    public static QdeltaParams kMutations(int k, String biased) {
        QdeltaParams p = "none".equals(biased) ? onCoarse() : thompson(1.0);
        p.mutationsPerChild = k;
        p.biasOneOfThem = "one".equals(biased);
        return p;
    }

    /** The evaluated shortlist repair: the table's best three candidates evaluated, the best taken. */
    public static QdeltaParams shortlist(int k) {
        QdeltaParams p = onCoarse();
        p.repairMode = QdeltaParams.RepairMode.SHORTLIST;
        p.shortlistK = k;
        return p;
    }

    /** Every arm of the plan's screen beyond the learners, by id, with what it is. */
    public static <S, A> java.util.List<AgentFactory<S, A>> mutationAndRepairFactories() {
        java.util.List<AgentFactory<S, A>> out = new java.util.ArrayList<>();
        out.add(mut("comma-short3-coarse", "the repair evaluates the table's best 3 candidates", shortlist(3)));
        QdeltaParams guard = onCoarse();
        guard.duplicateRedraws = 3;
        out.add(mut("mut-guard", "a duplicate child is discarded and made again, up to 3 times; no learning", guard));
        out.add(mut("mut-eps90", "epsilon-greedy insertion, 90% greedy (the thesis's setting)", eps(0.10)));
        out.add(mut("mut-eps50", "epsilon-greedy insertion, 50% greedy", eps(0.50)));
        out.add(mut("mut-eps20", "epsilon-greedy insertion, 20% greedy", eps(0.80)));
        out.add(mut("mut-thompson", "Thompson-sampled insertion", thompson(1.0)));
        out.add(mut("mut-thompson50", "half the insertions Thompson-sampled, the rest uniform", thompson(0.5)));
        QdeltaParams tg = thompson(1.0);
        tg.duplicateRedraws = 3;
        out.add(mut("mut-thompson-guard", "Thompson-sampled insertion, duplicates redrawn", tg));
        for (int k : new int[]{3, 5})
            for (String b : new String[]{"none", "one", "all"})
                out.add(mut("mut-k" + k + "-" + b, k + " mutations per child, " + b + " of them Thompson-biased", kMutations(k, b)));
        QdeltaParams mixed = onCoarse();
        mixed.biasedChildrenPerGeneration = 2;
        out.add(mut("mut-gen-mixed", "two of each generation's children Thompson-biased, three uniform", mixed));
        QdeltaParams site = onCoarse();
        site.learnSite = true;
        out.add(mut("mut-site", "the position chosen by Thompson sampling over positions", site));
        QdeltaParams ts = thompson(1.0);
        ts.learnSite = true;
        out.add(mut("mut-thompson-site", "Thompson-sampled insertion and position", ts));
        QdeltaParams aos = onCoarse();
        aos.adaptive = QdeltaParams.Adaptive.OPERATORS;
        out.add(mut("mut-aos", "adaptive operator selection over four mutation operators", aos));
        QdeltaParams step = onCoarse();
        step.adaptive = QdeltaParams.Adaptive.STEP_SIZE;
        out.add(mut("mut-k-adapt", "mutations per child by the 1/5 success rule, insertions uniform", step));
        QdeltaParams stepT = thompson(1.0);
        stepT.adaptive = QdeltaParams.Adaptive.STEP_SIZE;
        out.add(mut("mut-k-adapt-thompson", "mutations per child by the 1/5 success rule, insertions Thompson-biased", stepT));
        QdeltaParams sub = thompson(1.0);
        sub.substitutions = true;
        out.add(mut("mut-subst", "substitutions learned, read by the insertion and the repair", sub));
        QdeltaParams blocks = onCoarse();
        blocks.blockShare = 0.10;
        out.add(mut("mut-blocks", "a tenth of mutations insert a learned pair of consecutive actions", blocks));
        QdeltaParams tabu = onCoarse();
        tabu.tabuGenerations = 20;
        out.add(mut("mut-tabu", "a failed mutation is not redrawn at its position for 20 generations", tabu));
        QdeltaParams kick = onCoarse();
        kick.kickAfterGenerations = 30;
        out.add(mut("mut-kick", "after 30 generations without improving the best plan, a 5-mutation jump from it", kick));
        return out;
    }

    private static <S, A> AgentFactory<S, A> mut(String id, String what, QdeltaParams p) {
        return QdeltaEmcts.factory(id, "In-turn learning on comma-z0-coarse: " + what, p);
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
