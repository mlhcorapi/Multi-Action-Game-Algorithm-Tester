package magat.games.tribes.oracle;

import core.Constants;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import magat.agent.Oracle;
import magat.agent.Signature;
import magat.agent.TurnBudget;
import magat.game.Scenario;
import magat.games.tribes.TribesState;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.Random;

/**
 * The engine fork's {@code ExperimentalEMCTSAgent} with (1+1) or (1,k) selection, driven through the
 * engine one turn at a time, as the oracle for the ported {@code Emcts}: the gate asserts that the port
 * makes every forward-model call and every random draw the engine's agent makes, in the same order.
 *
 * <p>The oracle prices its budget with the engine's own meter, whose global weights are set to the
 * profile's three numbers before each turn (its {@code computeProfile} stays null, so
 * {@code useProfile(null)} installs those globals). Both sides therefore stop under the same
 * currency.
 *
 * <p>Two of its initialization copies are never read (the {@code old_gs} copy the SIMPLE heuristic
 * ignores, and the copy handed to the root's evaluation, which the root's own stored state makes
 * unnecessary). The port makes them all the same, because each draws from the state's random generator:
 * without them, every later copy would be seeded otherwise, and on 64 positions 12 of 128 turns met
 * another chance event.
 */
public final class EngineEmctsOracle implements Oracle<TribesState> {

    private final ExperimentalEMCTSParams.SelectionMode selection;
    private final int childrenPerGeneration;

    /** (1+1) selection: the oracle of plain {@code emcts}. */
    public EngineEmctsOracle() {
        this(ExperimentalEMCTSParams.SelectionMode.PLUS, 1);
    }

    /** The engine's agent at a selection mode and children per generation: (COMMA, 5) is {@code emcts-comma}'s. */
    public EngineEmctsOracle(ExperimentalEMCTSParams.SelectionMode selection, int childrenPerGeneration) {
        this.selection = selection;
        this.childrenPerGeneration = childrenPerGeneration;
    }

    @Override
    public String describe() {
        return "engine ExperimentalEMCTSAgent(" + selection + ", k=" + childrenPerGeneration + ")";
    }

    /** The engine's EMCTS bounds its search alone: its greedy initialization is paid outside the budget. */
    @Override
    public TurnBudget.Initialization initialization() {
        return TurnBudget.Initialization.OUTSIDE_BUDGET;
    }

    @Override
    public Signature plan(Scenario<TribesState> scenario, long seed, long budgetUnits, double[] weights) {
        ComputeMeter.W_ADVANCE = weights[0];
        ComputeMeter.W_COPY = weights[1];
        ComputeMeter.W_EVAL = weights[2];
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.TURN_TIME_LIMITED = false;

        ExperimentalEMCTSParams p = new ExperimentalEMCTSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = (int) budgetUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = 21;
        p.computeProfile = null;
        p.selectionMode = selection;
        p.childrenPerGeneration = childrenPerGeneration;
        ExperimentalEMCTSAgent agent = new ExperimentalEMCTSAgent(seed, p);

        GameState gs = scenario.state.raw().copy();
        gs.setRandomGenerator(new Random(seed));
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(gs.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        agent.act(gs, ect);

        TurnData t = agent.getTurnDataLog().get(0);
        return new Signature(t.getInitialSequenceValue(), t.getFinalSequenceValue(),
                t.getInitAdvances(), t.getInitCopies(), t.getInitEvals(),
                t.getAdvances(), t.getCopies(), t.getEvals(),
                t.getNodesEvaluated(), t.getUniqueSequences(), t.getComputeUnits());
    }
}
