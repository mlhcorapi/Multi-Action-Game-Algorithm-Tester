package magat.agents.emcts;

/** How a generation's children replace the incumbent ({@link EmctsParams#selection}). */
public enum Selection {
    /** (1+1): one child per generation, which becomes the incumbent only if it beats it. Plain EMCTS. */
    PLUS,
    /**
     * (1,k): k children of the incumbent per generation ({@link EmctsParams#childrenPerGeneration}); the best of
     * them becomes the incumbent, better or not. The engine's {@code SelectionMode.COMMA}.
     */
    COMMA
}
