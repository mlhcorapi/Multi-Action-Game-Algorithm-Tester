package magat.agents.tbets;

/**
 * Parameters of {@link Tbets}: the Tribes engine's {@code TBETSParams} defaults, for the parameters the port
 * keeps. The budget is always the compute budget of the {@link magat.agent.TurnBudget}, and the seeding is
 * paid from it, as the engine's default charges it.
 */
public class TbetsParams {

    // ------------------------------------------------------------------ tree shape

    /** Candidate turns generated at the root before the search starts (the seeding). */
    public int numInit = 20;

    /** Candidate turns given to a node the first time the search expands it. */
    public int startingPop = 5;

    /** Plies of the tree: 1 searches the agent's own turn only; 2 adds the opponent's reply. */
    public int maxPlies = 1;

    /** The longest turn, in actions before the end of turn. */
    public int nodeSize = 20;

    // ------------------------------------------------------------------ evolution

    /** The chance that an evolution step mutates one child rather than crossing two over. */
    public double mutationRatio = 0.3;

    /** The chance that a mutation redraws each action of the turn it copies. */
    public double mutationRate = 0.4;

    /** TUBSTAP's tournament: this many draws from the fittest {@link #tournamentTopFraction} of the children. */
    public int tournamentSize = 3;

    /** See {@link #tournamentSize}; the port draws the equivalent plain tournament ({@link Tbets}). */
    public double tournamentTopFraction = 0.2;

    // ------------------------------------------------------------------ selection

    /** UCB's exploration constant, on fitness normalised to the range among siblings. */
    public double ucbC = 1.414;

    /** Progressive widening: a node is under-expanded while children < wideningK * descendants^wideningAlpha. */
    public double wideningK = 2.0;

    /** See {@link #wideningK}. */
    public double wideningAlpha = 0.4;

    /** The chance that selection stops at an under-expanded node. */
    public double wideningStopProb = 0.95;

    /** Below this range of sibling fitness, UCB's exploitation term is flat (the engine's {@code AlgParams.epsilon}). */
    public double epsilon = 1e-6;

    /** Safety net against a search that makes no progress. */
    public int maxIterations = 1_000_000;

    public TbetsParams copy() {
        TbetsParams p = new TbetsParams();
        p.numInit = numInit;
        p.startingPop = startingPop;
        p.maxPlies = maxPlies;
        p.nodeSize = nodeSize;
        p.mutationRatio = mutationRatio;
        p.mutationRate = mutationRate;
        p.tournamentSize = tournamentSize;
        p.tournamentTopFraction = tournamentTopFraction;
        p.ucbC = ucbC;
        p.wideningK = wideningK;
        p.wideningAlpha = wideningAlpha;
        p.wideningStopProb = wideningStopProb;
        p.epsilon = epsilon;
        p.maxIterations = maxIterations;
        return p;
    }
}
