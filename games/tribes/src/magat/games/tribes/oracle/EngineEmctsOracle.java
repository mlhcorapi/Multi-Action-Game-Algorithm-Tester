package magat.games.tribes.oracle;

import core.Constants;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import magat.agent.Oracle;
import magat.agent.Signature;
import magat.game.Scenario;
import magat.games.tribes.TribesState;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.Random;

/**
 * The engine fork's {@code ExperimentalEMCTSAgent} with (1+1) incumbent selection, driven through the
 * engine one turn at a time, as the oracle for the ported {@code Emcts}: the gate asserts that the port
 * makes every forward-model call and every random draw the engine's agent makes, in the same order.
 *
 * <p>The oracle prices its budget with the engine's own meter, whose global weights are set to the
 * profile's three numbers before each turn (its {@code computeProfile} stays null, so
 * {@code useProfile(null)} installs those globals). Both sides therefore stop under the same
 * currency.
 *
 * <p>Two of its initialization copies are dead (the {@code old_gs} copy the SIMPLE heuristic never
 * reads, and the copy handed to the root's evaluation, which the root's own stored state makes
 * unnecessary); the port does not make them, and {@link #deadInitCopies} says so.
 */
public final class EngineEmctsOracle implements Oracle<TribesState> {

    @Override
    public String describe() {
        return "engine ExperimentalEMCTSAgent(PLUS, k=1)";
    }

    @Override
    public int deadInitCopies() {
        return 2;
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
        p.selectionMode = ExperimentalEMCTSParams.SelectionMode.PLUS;
        p.childrenPerGeneration = 1;
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
