package magat.agents.emcts;

import magat.game.Granularity;

import java.util.ArrayList;
import java.util.List;

/**
 * Parameters of {@link QdeltaEmcts}: {@link EmctsParams} plus what the turn learns and where it uses it
 * (docs/plans/ab-emcts-vs-oep.md, docs/plans/in-turn-learning.md). Every default is AB-EMCTS's: a live table
 * at coarse keys, credited with every mutation's change in value (the inserted action's classes, and negated
 * the removed one's), directing an evaluation-free repair, with uniform one-action mutations.
 */
public class QdeltaParams extends EmctsParams {

    /** What the repair does when an action of a mutated plan has to be replaced. */
    public enum RepairMode {
        /** Take the learner's best estimate: no copy, no candidate advance, no evaluation. */
        ZERO_EVAL,
        /** The attribution control of {@link #ZERO_EVAL}: an arbitrary legal action, the same zero calls. */
        ZERO_EVAL_RANDOM,
        /** Evaluate the learner's best {@link #shortlistK} candidates and take the best of them, as the engine's {@code SHORTLIST_K}. */
        SHORTLIST
    }

    /** What the turn learns with: a table of independent classes at {@link #repairKeys}, or a model (InTurnLearner). */
    public enum Learner {
        /** A table of independent classes, read at {@link #repairKeys}: AB-EMCTS's. */
        TABLE,
        /** A hierarchical table: fine shrunk toward mid, mid toward coarse, coarse toward the turn's mean. */
        HIER,
        /** A linear model on the three classes' identities, the position and the progress, fitted online. */
        LINEAR,
        /** A small neural network on the same inputs, trained online. */
        MLP
    }

    /** What a mutation inserts. The biased rules read the table at {@link #insertKeys}. */
    public enum Insertion {
        /** Uniform among the legal actions, as EMCTS. */
        UNIFORM,
        /** ε-greedy as the engine's {@code Stage1BiasedAgent}: uniform with probability {@link #epsilon}, otherwise the best estimate, ties uniform. */
        EPSILON,
        /** Thompson sampling: one draw per class from its estimate's posterior, the class with the highest draw, then one of its actions uniformly. */
        THOMPSON
    }

    /** What the search adapts within the turn. */
    public enum Adaptive {
        NONE,
        /** A bandit over four mutation operators, credited with how often their children beat their parent. */
        OPERATORS,
        /** The number of mutations per child, by the evolution strategies' 1/5 success rule. */
        STEP_SIZE
    }

    public RepairMode repairMode = RepairMode.ZERO_EVAL;
    public Learner learner = Learner.TABLE;
    /** The granularity the repair reads. */
    public Granularity repairKeys = Granularity.COARSE;
    /** Credit the removed action's classes with the negated change in value. */
    public boolean removalCredit = true;
    /** Shrinkage prior strength m: Q(a) = (sum_a + m * globalMean) / (n_a + m). */
    public double shrinkageM = 5.0;
    /** {@link RepairMode#SHORTLIST}: how many candidates are evaluated. */
    public int shortlistK = 0;

    // ---- the mutation (docs/plans/in-turn-learning.md, section 3)
    public Insertion insertion = Insertion.UNIFORM;
    /** {@link Insertion#EPSILON}: the probability of a uniform insertion (the engine's {@code epsilon}). */
    public double epsilon = 1.0;
    /** The granularity a biased insertion reads. */
    public Granularity insertKeys = Granularity.COARSE;
    /** {@link Insertion#THOMPSON}: the share of insertions biased, each decided by a coin; 1 biases them all. */
    public double biasShare = 1.0;
    /** How many actions a mutation changes. */
    public int mutationsPerChild = 1;
    /** With several mutations per child: only the first biased, the rest uniform. */
    public boolean biasOneOfThem = false;
    /** The duplicate guard: how many times a child identical to a plan already scored is made again. */
    public int duplicateRedraws = 0;
    /** This many of each generation's children have every insertion Thompson-biased, the rest uniform (0: off). */
    public int biasedChildrenPerGeneration = 0;
    /** The mutated position chosen by Thompson sampling over positions, from the change mutating each brought. */
    public boolean learnSite = false;
    public Adaptive adaptive = Adaptive.NONE;
    /** A table of what replacing a class by another brought, read by the insertion and by the repair. */
    public boolean substitutions = false;
    /** The share of mutations that insert a learned pair of consecutive actions (0: off). */
    public double blockShare = 0.0;
    /** A mutation whose child lost to its parent is not drawn again at its position for this many generations (0: off). */
    public int tabuGenerations = 0;
    /** After this many generations without improving the best plan, one child jumps from the best plan (0: off). */
    public int kickAfterGenerations = 0;

    @Override
    public QdeltaParams copy() {
        QdeltaParams p = new QdeltaParams();
        p.phases = phases;
        p.nodeSize = nodeSize;
        p.selection = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        p.repairMode = repairMode;
        p.learner = learner;
        p.repairKeys = repairKeys;
        p.removalCredit = removalCredit;
        p.shrinkageM = shrinkageM;
        p.shortlistK = shortlistK;
        p.insertion = insertion;
        p.epsilon = epsilon;
        p.insertKeys = insertKeys;
        p.biasShare = biasShare;
        p.mutationsPerChild = mutationsPerChild;
        p.biasOneOfThem = biasOneOfThem;
        p.duplicateRedraws = duplicateRedraws;
        p.biasedChildrenPerGeneration = biasedChildrenPerGeneration;
        p.learnSite = learnSite;
        p.adaptive = adaptive;
        p.substitutions = substitutions;
        p.blockShare = blockShare;
        p.tabuGenerations = tabuGenerations;
        p.kickAfterGenerations = kickAfterGenerations;
        return p;
    }

    /** Whether the mutation is EMCTS's own: one uniform action, at a uniform position, nothing learned about it. */
    public boolean plainMutation() {
        return insertion == Insertion.UNIFORM && mutationsPerChild == 1 && duplicateRedraws == 0 && biasedChildrenPerGeneration == 0
                && !learnSite && adaptive == Adaptive.NONE && !substitutions && blockShare == 0 && tabuGenerations == 0
                && kickAfterGenerations == 0;
    }

    /** One line naming every setting, for descriptions and logs. AB-EMCTS's arms read as they always have. */
    public String describe() {
        String by = learner == Learner.TABLE ? String.valueOf(repairKeys) : learner + " learner";
        String repair = repairMode == RepairMode.SHORTLIST ? "SHORTLIST of " + shortlistK : String.valueOf(repairMode);
        String line = String.format(java.util.Locale.ROOT, "%s k=%d, insertion %s, repair %s by %s, removal credit %s, shrinkage m=%.1f",
                selection, childrenPerGeneration, insertionLine(), repair, by, removalCredit ? "on" : "off", shrinkageM);
        List<String> more = new ArrayList<>();
        if (mutationsPerChild > 1) more.add(mutationsPerChild + " mutations per child" + (biasOneOfThem ? ", the first biased" : ""));
        if (duplicateRedraws > 0) more.add("duplicates redrawn up to " + duplicateRedraws + " times");
        if (biasedChildrenPerGeneration > 0) more.add(biasedChildrenPerGeneration + " children per generation biased, the rest uniform");
        if (learnSite) more.add("position by Thompson sampling");
        if (adaptive != Adaptive.NONE) more.add("adaptive " + adaptive);
        if (substitutions) more.add("substitutions");
        if (blockShare > 0) more.add(String.format(java.util.Locale.ROOT, "pairs inserted %.0f%% of the time", 100 * blockShare));
        if (tabuGenerations > 0) more.add("tabu for " + tabuGenerations + " generations");
        if (kickAfterGenerations > 0) more.add("kick after " + kickAfterGenerations + " stalled generations");
        return more.isEmpty() ? line : line + "; " + String.join("; ", more);
    }

    private String insertionLine() {
        switch (insertion) {
            case EPSILON: return String.format(java.util.Locale.ROOT, "epsilon-greedy (epsilon %.2f) by %s", epsilon, insertKeys);
            case THOMPSON: return "Thompson by " + insertKeys + (biasShare < 1 ? String.format(java.util.Locale.ROOT, " (%.0f%% of insertions)", 100 * biasShare) : "");
            default: return "uniform";
        }
    }
}
