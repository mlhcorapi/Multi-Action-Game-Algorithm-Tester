package magat.games.tribes.oracle;

import core.Constants;
import core.data.dto.OperatorTelemetryData;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import magat.agent.Oracle;
import magat.agent.Signature;
import magat.agent.TurnBudget;
import magat.agents.emcts.QdeltaParams;
import magat.game.Granularity;
import magat.game.Scenario;
import magat.games.tribes.TribesState;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.stage1.Stage1BiasedAgent;
import players.stage1.Stage1Params;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * The engine fork's {@code Stage1BiasedAgent}, the thesis line's agent, configured from the same
 * {@link QdeltaParams} as the ported arm and driven one turn at a time: the oracle of every
 * {@code QdeltaEmcts} arm. Besides the signature it reports the mechanism's own counters, which the gate
 * compares with the port's: an arm whose values matched while its table fired a different number of times
 * would be the same numbers for a different reason.
 *
 * <p>Priced like {@link EngineEmctsOracle}: the engine meter's global weights are set to the profile's three
 * numbers and the agent's profile is left null. Its greedy initialization is paid outside the budget, as the
 * engine's EMCTS pays it.
 */
public final class EngineQdeltaOracle implements Oracle<TribesState> {

    private final QdeltaParams params;

    public EngineQdeltaOracle(QdeltaParams params) {
        this.params = params.copy();
    }

    @Override
    public String describe() {
        return "engine Stage1BiasedAgent(" + params.describe() + ")";
    }

    @Override
    public TurnBudget.Initialization initialization() {
        return TurnBudget.Initialization.OUTSIDE_BUDGET;
    }

    /** The engine's parameters an arm's {@link QdeltaParams} stand for: insertion uniform, a live table, no shortlist. */
    static Stage1Params engineParams(QdeltaParams q, long budgetUnits) {
        Stage1Params p = new Stage1Params();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = (int) budgetUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = q.phases;
        p.computeProfile = null;
        p.selectionMode = ExperimentalEMCTSParams.SelectionMode.valueOf(q.selection.name());
        p.childrenPerGeneration = q.childrenPerGeneration;
        p.epsilon = 1.0;
        p.insertKeys = Stage1Params.Granularity.MID;
        p.repairKeys = Stage1Params.Granularity.valueOf(q.repairKeys.name());
        p.removalCredit = q.removalCredit;
        p.perBurnUpdates = false;
        p.repairMode = Stage1Params.RepairMode.valueOf(q.repairMode.name());
        p.shortlistK = 0;
        p.shrinkageM = q.shrinkageM;
        if (q.nodeSize != 20 || q.learner != QdeltaParams.Learner.TABLE)
            throw new IllegalArgumentException("the engine's agent has no equivalent of " + q.describe());
        return p;
    }

    @Override
    public Signature plan(Scenario<TribesState> scenario, long seed, long budgetUnits, double[] weights) {
        ComputeMeter.W_ADVANCE = weights[0];
        ComputeMeter.W_COPY = weights[1];
        ComputeMeter.W_EVAL = weights[2];
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.TURN_TIME_LIMITED = false;

        Stage1BiasedAgent agent = new Stage1BiasedAgent(seed, engineParams(params, budgetUnits));
        GameState gs = scenario.state.raw().copy();
        gs.setRandomGenerator(new Random(seed));
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < gs.getTribes().length; i++) allIds.add(i);
        agent.setPlayerIDs(gs.getActiveTribeID(), allIds);
        ElapsedCpuTimer ect = new ElapsedCpuTimer();
        ect.setMaxTimeMillis(Constants.TURN_TIME_MILLIS);
        agent.act(gs, ect);

        TurnData t = agent.getTurnDataLog().get(0);
        OperatorTelemetryData ot = agent.getOperatorTelemetry();
        Map<String, Double> tel = new LinkedHashMap<>();
        tel.put("biasGreedyPicks", (double) agent.getBiasGreedyPicks());
        tel.put("biasUniformPicks", (double) agent.getBiasUniformPicks());
        tel.put("shortlistCalls", (double) agent.getShortlistCalls());
        tel.put("shortlistSkipped", (double) agent.getShortlistSkipped());
        tel.put("statUpdates", (double) agent.getStatUpdates());
        tel.put("zeroEvalPicks", (double) agent.getZeroEvalPicks());
        tel.put("repairUnits", agent.getRepairUnits());
        tel.put("repairCandidateEvals", (double) agent.getRepairCandidateEvals());
        if (ot != null) {
            tel.put("mutEvents", (double) ot.mutEvents);
            tel.put("mutBestGains", (double) ot.mutBestGains);
            tel.put("mutRepairKept", (double) ot.mutRepairKept);
            tel.put("mutRepairReplaced", (double) ot.mutRepairReplaced);
        }
        tel.put("maxBranching", (double) agent.getMaxBranching());
        return new Signature(t.getInitialSequenceValue(), t.getFinalSequenceValue(),
                t.getInitAdvances(), t.getInitCopies(), t.getInitEvals(),
                t.getAdvances(), t.getCopies(), t.getEvals(),
                t.getNodesEvaluated(), t.getUniqueSequences(), t.getComputeUnits(), tel);
    }
}
