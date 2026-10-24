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
import players.tbets.TBETSAgent;
import players.tbets.TBETSParams;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * The engine fork's {@code TBETSAgent} at its defaults (one ply, no rollouts, no action bias), driven through
 * the engine one turn at a time, as the oracle for the ported {@code Tbets}: the gate asserts that the port
 * makes every forward-model call and every random draw the engine's agent makes, in the same order. Beside the
 * signature it reports the agent's search iterations and its uniform draws, which the port must match too.
 *
 * <p>The oracle prices its budget with the engine's own meter, whose global weights are set to the profile's
 * three numbers before each turn, as {@link EngineEmctsOracle} does. The engine's agent charges its seeding to
 * the budget, so the gate plays the port by the same rule ({@link #initialization}). The port makes the same
 * copies, the two before seeding included, so none is dead.
 */
public final class EngineTbetsOracle implements Oracle<TribesState> {

    @Override
    public String describe() {
        return "engine TBETSAgent (1 ply, no rollouts, no bias, seeding charged)";
    }

    /** The engine's TBETS charges its seeding to the budget. */
    @Override
    public TurnBudget.Initialization initialization() {
        return TurnBudget.Initialization.IN_BUDGET;
    }

    @Override
    public Signature plan(Scenario<TribesState> scenario, long seed, long budgetUnits, double[] weights) {
        ComputeMeter.W_ADVANCE = weights[0];
        ComputeMeter.W_COPY = weights[1];
        ComputeMeter.W_EVAL = weights[2];
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.TURN_TIME_LIMITED = false;

        TBETSParams p = new TBETSParams();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = (int) budgetUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.computeProfile = null;
        TBETSAgent agent = new TBETSAgent(seed, p);

        GameState gs = scenario.state.raw().copy();
        gs.setRandomGenerator(new Random(seed));
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(gs.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        agent.act(gs, ect);

        TurnData t = agent.getTurnDataLog().get(0);
        Map<String, Double> telemetry = new LinkedHashMap<>();
        telemetry.put("iterations", (double) agent.getSearchIterations());
        telemetry.put("uniformPicks", (double) agent.getBiasUniformPicks());
        return new Signature(t.getInitialSequenceValue(), t.getFinalSequenceValue(),
                t.getInitAdvances(), t.getInitCopies(), t.getInitEvals(),
                t.getAdvances(), t.getCopies(), t.getEvals(),
                t.getNodesEvaluated(), t.getUniqueSequences(), t.getComputeUnits(), telemetry);
    }
}
