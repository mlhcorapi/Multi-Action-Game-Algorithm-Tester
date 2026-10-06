package magat.agent;

import magat.meter.Meter;

/**
 * The compute budget of one planned turn, in units of the profile in force on the calling thread.
 *
 * <p>The budget bounds the search that follows {@link #beginSearch()}: an agent with an
 * initialization phase (EMCTS's greedy root) calls it once at that boundary, which snapshots the
 * initialization spend for the record and zeroes the meter. An agent with no such phase never calls
 * it and the whole turn counts.
 *
 * <p>{@link #ceiling} is the phase rule of the EMCTS family, kept exactly: the per-phase slice is the
 * integer division {@code units / phases}, so the last ceiling can sit slightly below the budget and
 * the final iteration overshoots it by at most one iteration's work.
 */
public final class TurnBudget {

    private final long units;
    private boolean searchBegun;
    private double initUnits;
    private long initAdvances, initCopies, initEvals;
    private long[] initOps = new long[0];

    private TurnBudget(long units) {
        if (units <= 0) throw new IllegalArgumentException("budget must be positive: " + units);
        this.units = units;
    }

    public static TurnBudget units(long units) {
        return new TurnBudget(units);
    }

    public long units() {
        return units;
    }

    /** Snapshots the initialization spend (every operation's count) and zeroes the meter; the budget bounds what follows. */
    public void beginSearch() {
        if (searchBegun) throw new IllegalStateException("beginSearch() may be called once per turn");
        initUnits = Meter.units();
        initAdvances = Meter.advances();
        initCopies = Meter.copies();
        initEvals = Meter.evals();
        initOps = Meter.counts();
        Meter.reset();
        searchBegun = true;
    }

    public boolean searchBegun() { return searchBegun; }

    /** True once the meter's units on this thread reach the budget. */
    public boolean exhausted() {
        return Meter.units() >= units;
    }

    /** Cumulative ceiling at the end of phase {@code phase} of {@code phases}. */
    public double ceiling(int phase, int phases) {
        long perPhase = Math.max(1L, units / Math.max(1, phases));
        return (double) (phase + 1) * perPhase;
    }

    public double initUnits()    { return initUnits; }
    public long initAdvances()   { return initAdvances; }
    public long initCopies()     { return initCopies; }
    public long initEvals()      { return initEvals; }

    /** The initialization's count of every operation, on the meter's dense indices (empty before {@link #beginSearch}). */
    public long[] initOperations() { return initOps.clone(); }
}
