package core.data.exp;

/**
 * A named fmcall budget level. Budgets are calibrated to roughly match a target thinking
 * time (see {@link Calibrate}); using fmcalls (not wall-clock) keeps search effort
 * deterministic and reproducible under multithreading.
 */
public class Budget {
    public final String name;     // label used in filenames/columns, e.g. "200ms"
    public final int fmcalls;     // forward-model-call budget per turn
    public final int games;       // games to run at this level; <=0 means "use the run's default count"

    public Budget(String name, int fmcalls) {
        this(name, fmcalls, -1);
    }

    /** @param games per-level game count (e.g. fewer games at the expensive 5000ms level);
     *               pass <=0 to fall back to the run's default numGames. */
    public Budget(String name, int fmcalls, int games) {
        this.name = name;
        this.fmcalls = fmcalls;
        this.games = games;
    }
}
