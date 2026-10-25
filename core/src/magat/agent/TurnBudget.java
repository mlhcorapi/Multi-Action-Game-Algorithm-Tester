package magat.agent;

import magat.meter.Meter;

/**
 * The compute budget of one planned turn, in units of the profile in force on the calling thread.
 *
 * <p>The budget is the whole turn. An agent with an initialization phase (EMCTS's greedy initial plan)
 * calls {@link #beginSearch()} once at that boundary, which snapshots the initialization's spend for the
 * record and zeroes the meter; the search then gets what the initialization left. A turn spends its
 * budget and stops: an agent checks {@link #exhausted()} between its units of work (an iteration, a
 * generation, a step of its initialization) and finishes the one under way, so a level's units are the
 * turn's thinking time. An agent with no initialization phase never calls {@code beginSearch()}, and the
 * whole turn counts.
 *
 * <p>One exception, {@link Initialization#OUTSIDE_BUDGET}: the engine's EMCTS bounds its search alone and pays
 * its initialization outside it, never cut short, and it gives every bridge burn a child past the budget if
 * need be, so the equivalence gate plays its port by that accounting to compare the two turn for turn
 * ({@link Oracle#initialization}). Nothing else uses it.
 *
 * <p>{@link #ceiling} is the phase rule of the EMCTS family, kept exactly: the per-phase slice is the
 * integer division {@code search units / phases}, so the last ceiling can sit slightly below the budget and
 * the final iteration overshoots it by at most one iteration's work.
 */
public final class TurnBudget {

    /** Whether a turn's initialization is paid from its budget: always, except in the gate of an original that does not. */
    public enum Initialization {
        IN_BUDGET("in-budget"),
        OUTSIDE_BUDGET("outside-budget");

        public final String id;

        Initialization(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * A cell whose turns spend on average more than this multiple of their level is flagged, by the calibration
     * and by {@code check time}: its units are not the level's thinking time. An initialization that cannot stop
     * when the budget runs out is the usual cause.
     */
    public static final double OVERSPEND = 1.10;

    /** The JVM's mode: {@code IN_BUDGET}, which only the equivalence gate changes (and restores) for its comparisons. */
    private static volatile Initialization mode = Initialization.IN_BUDGET;

    public static void setInitialization(Initialization m) {
        if (m == null) throw new IllegalArgumentException("initialization");
        mode = m;
    }

    public static Initialization initialization() {
        return mode;
    }

    private final long units;
    private final Initialization init;
    private long searchUnits;
    private double searchLimit;
    private boolean searchBegun;
    private double initUnits;
    private long initAdvances, initCopies, initEvals;
    private long[] initOps = new long[0];

    private TurnBudget(long units) {
        if (units <= 0) throw new IllegalArgumentException("budget must be positive: " + units);
        this.units = units;
        this.init = mode;
        this.searchUnits = units;
        this.searchLimit = units;
    }

    public static TurnBudget units(long units) {
        return new TurnBudget(units);
    }

    public long units() {
        return units;
    }

    /** Whether this turn's initialization is paid from {@link #units()}. */
    public Initialization initializationPolicy() {
        return init;
    }

    /** What the search may spend, whole units: the budget, less the initialization when it is paid from the budget. */
    public long searchUnits() {
        return searchUnits;
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
        if (init == Initialization.IN_BUDGET) {
            searchLimit = Math.max(0.0, units - initUnits);
            searchUnits = (long) Math.floor(searchLimit);
        }
        searchBegun = true;
    }

    public boolean searchBegun() { return searchBegun; }

    /** True once the meter's units on this thread reach what the search may spend (so the turn has spent its budget). */
    public boolean exhausted() {
        return Meter.units() >= searchLimit;
    }

    /** Cumulative ceiling, in search units, at the end of phase {@code phase} of {@code phases}. */
    public double ceiling(int phase, int phases) {
        long perPhase = Math.max(1L, searchUnits / Math.max(1, phases));
        return (double) (phase + 1) * perPhase;
    }

    public double initUnits()    { return initUnits; }
    public long initAdvances()   { return initAdvances; }
    public long initCopies()     { return initCopies; }
    public long initEvals()      { return initEvals; }

    /** The initialization's count of every operation, on the meter's dense indices (empty before {@link #beginSearch}). */
    public long[] initOperations() { return initOps.clone(); }
}
