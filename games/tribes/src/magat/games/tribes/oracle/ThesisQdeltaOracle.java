package magat.games.tribes.oracle;

import core.Constants;
import core.data.dto.OperatorTelemetryData;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import magat.agent.Oracle;
import magat.agent.Signature;
import magat.agents.emcts.QdeltaParams;
import magat.agents.emcts.Selection;
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
 * The thesis tree's {@code Stage1BiasedAgent}, configured from the same {@link QdeltaParams} as the
 * ported arm and driven exactly as {@code Stage1Runner.runOneTurn} drove it: the oracle of every
 * {@code QdeltaEmcts} arm. Besides the signature it reports the mechanism's own counters, which the
 * gate compares with the port's: an arm whose values matched while its bias fired a different number
 * of times would be the same numbers for a different reason.
 *
 * <p>Priced like {@link ThesisEmctsOracle}: the engine meter's global weights are set to the profile's
 * three numbers and the agent's profile is left null. Two initialization copies are dead, as there.
 */
public final class ThesisQdeltaOracle implements Oracle<TribesState> {

    private final QdeltaParams params;

    public ThesisQdeltaOracle(QdeltaParams params) {
        this.params = params.copy();
    }

    @Override
    public String describe() {
        return "thesis Stage1BiasedAgent(" + params.describe() + ")";
    }

    @Override
    public int deadInitCopies() {
        return 2;
    }

    /** The thesis parameters an arm's {@link QdeltaParams} stand for. */
    static Stage1Params thesisParams(QdeltaParams q, long budgetUnits) {
        Stage1Params p = new Stage1Params();
        p.stop_type = p.STOP_COMPUTE;
        p.num_computeUnits = (int) budgetUnits;
        p.heuristic_method = p.SIMPLE_HEURISTIC;
        p.depth = q.phases;
        p.computeProfile = null;
        p.selectionMode = ExperimentalEMCTSParams.SelectionMode.valueOf(q.selection.name());
        p.childrenPerGeneration = q.childrenPerGeneration;
        p.epsilon = q.epsilon;
        p.insertKeys = granularity(q.insertKeys);
        p.repairKeys = granularity(q.repairKeys);
        p.removalCredit = q.removalCredit;
        p.perBurnUpdates = q.perBurnUpdates;
        p.repairMode = Stage1Params.RepairMode.valueOf(q.repairMode.name());
        p.shortlistK = q.shortlistK;
        p.shrinkageM = q.shrinkageM;
        if (q.selection == Selection.PLUS && q.childrenPerGeneration != 1 || q.randomInitialSequence || q.nodeSize != 20)
            throw new IllegalArgumentException("the thesis oracle has no equivalent of " + q.describe());
        return p;
    }

    private static Stage1Params.Granularity granularity(Granularity g) {
        return Stage1Params.Granularity.valueOf(g.name());
    }

    @Override
    public Signature plan(Scenario<TribesState> scenario, long seed, long budgetUnits, double[] weights) {
        ComputeMeter.W_ADVANCE = weights[0];
        ComputeMeter.W_COPY = weights[1];
        ComputeMeter.W_EVAL = weights[2];
        Constants.TURN_TIME_MILLIS = 10_000_000L;
        Constants.TURN_TIME_LIMITED = false;

        Stage1BiasedAgent agent = new Stage1BiasedAgent(seed, thesisParams(params, budgetUnits));
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
