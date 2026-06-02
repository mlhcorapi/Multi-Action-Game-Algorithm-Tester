package core.data.exp;

/**
 * A named per-turn budget level for the deterministic phases (Phase 1/3). The value is in
 * COMPUTE-UNITS (see {@link core.game.ComputeMeter}) - a weighted count of advances + state copies +
 * heuristic evals, calibrated to a target thinking-time bracket. Using a deterministic count (not
 * wall-clock) keeps search effort reproducible across runs/machines and under multithreading, while
 * capturing ~all of the planning compute (not just forward-model calls).
 */
public class Budget {
    public final String name;     // label used in filenames/columns, e.g. "500ms"
    public final int budget;      // per-turn budget value, in compute-units (STOP_COMPUTE)
    public final int games;       // games to run at this level; <=0 means "use the run's default count"

    public Budget(String name, int budget) {
        this(name, budget, -1);
    }

    /** @param games per-level game count (e.g. fewer games at the expensive 5000ms level);
     *               pass <=0 to fall back to the run's default numGames. */
    public Budget(String name, int budget, int games) {
        this.name = name;
        this.budget = budget;
        this.games = games;
    }
}
