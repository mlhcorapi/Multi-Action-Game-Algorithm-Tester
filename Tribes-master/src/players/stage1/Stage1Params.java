package players.stage1;

import players.ExperimentalEMCTS.ExperimentalEMCTSParams;

/**
 * Parameters for {@link Stage1BiasedAgent}. Every default is the DO-NOTHING setting: an agent built
 * from an unmodified Stage1Params behaves bit-identically to plain ExperimentalEMCTSAgent (the
 * runner's verify step asserts this), so the baseline config and the experimental configs share one
 * implementation.
 *
 * <p>The non-default values encode the Stage-0 conclusions (see
 * {@code analysis/STAGE0_SIGNAL_PLAN.md} and the Stage-0 results): Mid-granularity keys, paired-delta
 * credit, removal credit on, no decay (statistics accumulate over the whole turn and reset at turn
 * start), shrinkage toward the running global mean.
 */
public class Stage1Params extends ExperimentalEMCTSParams {

    /**
     * Probability that the mutation-insertion step draws UNIFORMLY from the feasible set (the
     * baseline behaviour). With probability 1 - epsilon the step instead picks the feasible action
     * with the highest shrunk mean delta (ties broken uniformly). 1.0 = bias machinery never fires
     * and the RNG stream is identical to plain EMCTS.
     */
    public double epsilon = 1.0;

    /** true = Coarse keys (ACTION type only); false = Mid keys (type + actor/tech qualifier). */
    public boolean coarseKeys = false;

    /** Credit the REMOVED action with the negated delta (doubles evidence; Stage-0 F7). */
    public boolean removalCredit = true;

    /**
     * true = selection reads a snapshot of the statistics frozen at the last bridge burn (the
     * "batched update at phase boundaries" variant); false = selection reads the live statistics
     * (continuous updates). Accumulation is always continuous.
     */
    public boolean perBurnUpdates = false;

    /**
     * Greedy-repair shortlisting: when an action must be replaced, rank the feasible candidates by
     * shrunk mean delta and run the exhaustive copy+advance+eval greedy over only the top K.
     * 0 = off (full greedy over every candidate, the baseline behaviour).
     */
    public int shortlistK = 0;

    /** Shrinkage prior strength m: Q(a) = (sumDelta_a + m * globalMean) / (n_a + m). */
    public double shrinkageM = 5.0;

    /**
     * Log one row per evaluated mutation child with the child sequence's Mid-key composition
     * (Stage-2 groundwork: offline co-occurrence / interaction analysis). Observation only; enabled
     * on the Baseline config so the interaction estimates come from UNBIASED search data.
     */
    public boolean logSeqComposition = false;
}
