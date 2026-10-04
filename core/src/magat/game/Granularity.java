package magat.game;

/** Levels of action abstraction a game offers to learned tables; coarser levels partition finer ones. */
public enum Granularity {
    /** Action type only, e.g. {@code MOVE}. */
    COARSE,
    /** Type plus one qualifier, e.g. {@code MOVE:RIDER}. */
    MID,
    /** The full action identity, i.e. {@link Game#actionKey}. */
    FINE
}
