package magat.agents.emcts;

/**
 * How the search chooses the node it expands next, in evolutionary-strategy terms. Extracted from
 * the thesis tree's {@code ExperimentalEMCTSParams.SelectionMode}, semantics unchanged.
 */
public enum Selection {
    /** (1+k): expand the incumbent; adopt the best child only if it beats the incumbent. */
    PLUS,
    /** (1,k): expand the incumbent; adopt the best child unconditionally. */
    COMMA,
    /** (1,k) without the best-of-k filter: adopt a uniformly random child. */
    COMMA_RAND,
    /** Always expand the most recently created child. */
    DRIFT,
    /** The pre-fix policy: descend from the root taking the highest-score child until a leaf. */
    LEGACY
}
