package players.stage1;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.Build;
import core.actions.cityactions.Spawn;
import core.actions.tribeactions.ResearchTech;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.units.Unit;
import core.game.GameState;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSTreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * STAGE 1 of the action-bias line (see {@code analysis/STAGE1_PLAN.md}): EMCTS whose mutation
 * operator LEARNS, within the current turn, which actions pay — and optionally uses the same
 * statistics to shortlist the greedy repair.
 *
 * <p><b>What is learned.</b> Per action key (Mid granularity: ACTION type + actor/tech qualifier;
 * or Coarse: type only), the running mean of the paired delta {@code childValue - parentValue}
 * credited to the inserted action, optionally the negated delta credited to the removed action.
 * Estimates are shrunk toward the running global mean with prior strength {@code shrinkageM}.
 * Statistics reset at the start of every planning turn (Stage-0: per-turn scope; no decay — decay
 * lost in all twelve Stage-0 cells).
 *
 * <p><b>How it is used.</b>
 * <ul>
 *   <li><i>Biased insertion</i> (epsilon-greedy): with probability {@code 1 - epsilon} the mutation
 *       inserts the feasible action with the highest Q instead of a uniform draw. Ties (e.g. all
 *       keys unseen, all at the prior) break uniformly, so an empty table degrades to uniform.</li>
 *   <li><i>Repair shortlisting</i> ({@code shortlistK > 0}): when greedy repair must replace an
 *       action it normally pays copy+advance+eval for EVERY feasible candidate (~300-450 at
 *       mid-game branching factors); here candidates are ranked by Q (free — no ComputeMeter
 *       charge) and only the top K get the exhaustive treatment. Under a fixed compute budget the
 *       saving is reinvested as extra search iterations automatically.</li>
 * </ul>
 *
 * <p><b>Baseline contract.</b> With every Stage1Params default ({@code epsilon = 1, shortlistK = 0})
 * the agent consumes the identical RNG stream and makes the identical forward-model calls as plain
 * {@link ExperimentalEMCTSAgent}: the epsilon coin is not flipped at epsilon >= 1, bookkeeping is
 * read-only, and repair delegates to super. {@code Stage1Runner verify} asserts bit-equality before
 * every run. Crossover must stay off (constructor-enforced), so every child comes from
 * {@link #mutate} and is scored by the immediately following {@link #eval} — the pairing the
 * credit assignment relies on.
 */
public class Stage1BiasedAgent extends ExperimentalEMCTSAgent {

    /** Key-space bounds: ACTION ordinal x qualifier ordinal (+1; 0 = none/unknown). */
    private static final int A_MAX = 40, Q_MAX = 80;

    private final Stage1Params p1;

    // Live per-turn statistics (reset in randomActions = turn start).
    private final double[][] sumDelta = new double[A_MAX][Q_MAX];
    private final int[][] nObs = new int[A_MAX][Q_MAX];
    private double globalSum;
    private long globalN;

    // Frozen snapshot used for SELECTION when perBurnUpdates (copied from live at each burn).
    private double[][] snapSum;
    private int[][] snapN;
    private double snapGlobalSum;
    private long snapGlobalN;

    // The mutation awaiting its child's evaluation (planTurn always evals right after mutate).
    private ExperimentalEMCTSTreeNode pendingChild, pendingParent;
    private int pendInsA = -1, pendInsQ, pendRemA = -1, pendRemQ;

    // Telemetry (cumulative; the isolation runner uses one agent per single-turn task).
    private long biasGreedyPicks, biasUniformPicks, shortlistCalls, shortlistSkipped, statUpdates;

    // Stage-2 groundwork: per-child sequence composition rows (only when logSeqComposition).
    public static final String SEQLOG_FIELDS = "Iter,Duplicate,ChildValue,NumKeys,Keys";
    private static final int MAX_SEQLOG_ROWS = 200_000;
    private final ArrayList<String> seqLogRows = new ArrayList<>();
    private int iterInTurn = 0;

    public Stage1BiasedAgent(long seed, Stage1Params params) {
        super(seed, params);
        this.p1 = params;
        if (params.useCrossoverExpansion || params.useCrossoverBridgeBurning)
            throw new IllegalArgumentException("Stage1BiasedAgent requires crossover OFF");
        if (params.perBurnUpdates) {
            snapSum = new double[A_MAX][Q_MAX];
            snapN = new int[A_MAX][Q_MAX];
        }
    }

    public long getBiasGreedyPicks()  { return biasGreedyPicks; }
    public long getBiasUniformPicks() { return biasUniformPicks; }
    public long getShortlistCalls()   { return shortlistCalls; }
    public long getShortlistSkipped() { return shortlistSkipped; }
    public long getStatUpdates()      { return statUpdates; }
    public List<String> getSeqLogRows() { return seqLogRows; }

    // ------------------------------------------------------------------ per-turn lifecycle

    /** planTurn's first act; doubles as the turn-start hook: per-turn statistics reset here. */
    @Override
    protected ExperimentalEMCTSTreeNode randomActions(GameState gs) {
        for (int a = 0; a < A_MAX; a++) {
            java.util.Arrays.fill(sumDelta[a], 0.0);
            java.util.Arrays.fill(nObs[a], 0);
            if (snapSum != null) {
                java.util.Arrays.fill(snapSum[a], 0.0);
                java.util.Arrays.fill(snapN[a], 0);
            }
        }
        globalSum = 0.0; globalN = 0;
        snapGlobalSum = 0.0; snapGlobalN = 0;
        pendingChild = null; pendingParent = null; pendInsA = -1; pendRemA = -1;
        iterInTurn = 0;
        return super.randomActions(gs);
    }

    @Override
    protected void recordBridgeBurn(int bridgeBurningPhaseNumber, double bestSequenceValue,
                                    long turnStartTime, int tick) {
        super.recordBridgeBurn(bridgeBurningPhaseNumber, bestSequenceValue, turnStartTime, tick);
        if (p1.perBurnUpdates) {                       // publish the batch at the phase boundary
            for (int a = 0; a < A_MAX; a++) {
                System.arraycopy(sumDelta[a], 0, snapSum[a], 0, Q_MAX);
                System.arraycopy(nObs[a], 0, snapN[a], 0, Q_MAX);
            }
            snapGlobalSum = globalSum; snapGlobalN = globalN;
        }
    }

    // ------------------------------------------------------------------ statistics

    private int actionOrd(Action a) {
        int o = a.getActionType().ordinal();
        return o < A_MAX ? o : A_MAX - 1;
    }

    /** Mid qualifier resolved against the state the action applies in; 0 for Coarse/unknown. */
    private int qualifierOf(Action a, GameState gs) {
        if (p1.coarseKeys) return 0;
        try {
            int q = 0;
            if (a instanceof UnitAction) {
                Actor act = gs.getActor(((UnitAction) a).getUnitId());
                q = (act instanceof Unit) ? ((Unit) act).getType().ordinal() + 1 : 0;
            } else if (a instanceof Build) {
                q = ((Build) a).getBuildingType().ordinal() + 1;
            } else if (a instanceof Spawn) {
                q = ((Spawn) a).getUnitType().ordinal() + 1;
            } else if (a instanceof ResearchTech) {
                q = ((ResearchTech) a).getTech().ordinal() + 1;
            }
            return q < Q_MAX ? q : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void addObs(int a, int q, double delta) {
        sumDelta[a][q] += delta;
        nObs[a][q]++;
        globalSum += delta;
        globalN++;
        statUpdates++;
    }

    /** Shrunk mean delta from the table SELECTION reads (snapshot in perBurn mode, else live). */
    private double qhat(int a, int q) {
        double[][] s = (p1.perBurnUpdates ? snapSum : sumDelta);
        int[][] n = (p1.perBurnUpdates ? snapN : nObs);
        double gSum = (p1.perBurnUpdates ? snapGlobalSum : globalSum);
        long gN = (p1.perBurnUpdates ? snapGlobalN : globalN);
        double gm = gN > 0 ? gSum / gN : 0.0;
        return (s[a][q] + p1.shrinkageM * gm) / (n[a][q] + p1.shrinkageM);
    }

    // ------------------------------------------------------------------ biased mutation

    /**
     * Verbatim copy of {@link ExperimentalEMCTSAgent#mutate} with (a) the uniform insertion draw
     * replaced by the epsilon-greedy rule and (b) read-only bookkeeping for the credit assignment.
     * At epsilon >= 1 the epsilon coin is never flipped, so the RNG stream matches the base class
     * exactly (guarded by {@code Stage1Runner verify}).
     */
    @Override
    protected ExperimentalEMCTSTreeNode mutate(ExperimentalEMCTSTreeNode node, GameState gs) {
        GameState clone = gs.copy();
        ArrayList<Action> seq = node.getSequence();
        int moveToMutate = seq.isEmpty() ? 0 : m_rnd.nextInt(seq.size());
        ArrayList<Action> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            advance(gs, seq.get(i));
            newSeq.add(seq.get(i));
        }
        ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);

        Action inserted = null;
        int insA = -1, insQ = 0;
        if (!allAvailableActions.isEmpty()) {
            if (p1.epsilon >= 1.0 || m_rnd.nextDouble() < p1.epsilon) {
                inserted = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
                biasUniformPicks++;
            } else {
                // Greedy over Q with uniform tie-break (reservoir), so an empty table = uniform.
                double bestQ = Double.NEGATIVE_INFINITY;
                int numTies = 0;
                for (Action cand : allAvailableActions) {
                    double qv = qhat(actionOrd(cand), qualifierOf(cand, gs));
                    if (qv > bestQ) {
                        bestQ = qv; inserted = cand; numTies = 1;
                    } else if (qv == bestQ) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) inserted = cand;
                    }
                }
                biasGreedyPicks++;
            }
            insA = actionOrd(inserted);
            insQ = qualifierOf(inserted, gs);
            newSeq.add(inserted);
        }

        // Removed-action key, resolved on the same prefix state (read-only).
        int remA = -1, remQ = 0;
        if (moveToMutate < seq.size()) {
            Action removed = seq.get(moveToMutate);
            remA = actionOrd(removed);
            remQ = qualifierOf(removed, gs);
        }

        for (int i = moveToMutate + 1; i < seq.size(); i++) {
            newSeq.add(seq.get(i));
        }
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.getChildren().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;

        pendingChild = newNode;
        pendingParent = node;
        pendInsA = insA; pendInsQ = insQ; pendRemA = remA; pendRemQ = remQ;
        iterInTurn++;
        return newNode;
    }

    /** Completes the credit assignment once the child is scored; optionally logs composition. */
    @Override
    public void eval(GameState gs, ExperimentalEMCTSTreeNode node) {
        boolean match = pendingChild != null && node == pendingChild;
        boolean duplicate = match && p1.logSeqComposition
                && uniqueSeqKeys.contains(seqKey(node.getSequence()));
        super.eval(gs, node);
        if (!match) return;

        double delta = node.getValue() - pendingParent.getValue();
        if (pendInsA >= 0) addObs(pendInsA, pendInsQ, delta);
        if (p1.removalCredit && pendRemA >= 0) addObs(pendRemA, pendRemQ, -delta);

        if (p1.logSeqComposition && seqLogRows.size() < MAX_SEQLOG_ROWS)
            seqLogRows.add(compositionRow(node, duplicate));

        pendingChild = null; pendingParent = null; pendInsA = -1; pendRemA = -1;
    }

    // ------------------------------------------------------------------ shortlisted repair

    /**
     * Copy of {@link ExperimentalEMCTSAgent#repair} whose two greedy-replacement sites evaluate only
     * the top-{@code shortlistK} candidates by Q instead of all of them. Delegates to super when
     * shortlisting is off, so the baseline path is the base class's own code.
     */
    @Override
    protected ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
        if (p1.shortlistK <= 0) return super.repair(child, gs);

        ArrayList<Action> repairedChild = new ArrayList<>();
        lastRepairKept = 0; lastRepairReplaced = 0;

        for (int a = 0; a < child.size(); a++) {
            if (!(gs.getActiveTribeID() == getPlayerID())) {
                return repairedChild;
            }
            boolean done;
            try {
                done = checkActionFeasibility(child.get(a), gs);
            } catch (Exception e) {
                done = false;
            }
            if (done) {
                repairedChild.add(child.get(a));
                try {
                    advance(gs, child.get(a));
                } catch (Exception e) {
                    // Feasible-but-throwing action: replace it like an infeasible one.
                    repairedChild.remove(repairedChild.size() - 1);
                    done = false;
                }
                if (done) {
                    lastRepairKept++;
                    continue;
                }
            }
            Action bestAction = shortlistedGreedyReplace(gs);
            if (bestAction != null) {
                advance(gs, bestAction);
                repairedChild.add(bestAction);
                lastRepairReplaced++;
            } else {
                break;
            }
        }

        // Ensure repaired sequence ends with END_TURN (same as base).
        if (!repairedChild.isEmpty()
                && repairedChild.get(repairedChild.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    repairedChild.add(a);
                    break;
                }
            }
        }
        return repairedChild;
    }

    /**
     * The replacement rule: rank feasible candidates by Q (free), pay copy+advance+eval only for the
     * top K, pick the heuristic-best of those (ties broken uniformly, as in the base greedy).
     */
    private Action shortlistedGreedyReplace(GameState gs) {
        ArrayList<Action> cands = this.allGoodActions(gs, m_rnd);
        if (cands.isEmpty()) return null;

        ArrayList<Action> pool = cands;
        if (cands.size() > p1.shortlistK) {
            shortlistCalls++;
            shortlistSkipped += cands.size() - p1.shortlistK;
            // Rank by Q with a random jitter far below any real delta, purely to break ties
            // (unseen keys all sit at the prior) without biasing among equals.
            int n = cands.size();
            double[] score = new double[n];
            for (int i = 0; i < n; i++) {
                Action c = cands.get(i);
                score[i] = qhat(actionOrd(c), qualifierOf(c, gs)) + m_rnd.nextDouble() * 1e-9;
            }
            pool = new ArrayList<>(p1.shortlistK);
            boolean[] taken = new boolean[n];
            for (int k = 0; k < p1.shortlistK; k++) {
                int best = -1;
                for (int i = 0; i < n; i++)
                    if (!taken[i] && (best < 0 || score[i] > score[best])) best = i;
                taken[best] = true;
                pool.add(cands.get(best));
            }
        }

        Action bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (Action candidate : pool) {
            double s;
            try {
                GameState testGs = gs.copy();
                advance(testGs, candidate);
                s = heuristic.evaluateState(old_gs, testGs);
            } catch (Exception e) {
                continue;
            }
            if (s > bestScore) {
                bestScore = s; bestAction = candidate; numTies = 1;
            } else if (s == bestScore) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) bestAction = candidate;
            }
        }
        return bestAction;
    }

    // ------------------------------------------------------------------ Stage-2 composition log

    /** One row: the child's value plus the multiset of Mid keys in its sequence. Unit types are
     *  resolved against the TURN-START state (old_gs): stable and cheap; units spawned mid-sequence
     *  fall back to an unqualified key, which the offline analysis tolerates. */
    private String compositionRow(ExperimentalEMCTSTreeNode node, boolean duplicate) {
        ArrayList<Action> seq = node.getSequence();
        StringBuilder keys = new StringBuilder(seq.size() * 12);
        ArrayList<String> ks = new ArrayList<>(seq.size());
        for (Action a : seq) {
            if (a == null) continue;
            String k;
            if (a instanceof UnitAction) {
                Actor act = old_gs == null ? null : old_gs.getActor(((UnitAction) a).getUnitId());
                k = a.getActionType().name()
                        + (act instanceof Unit ? ":" + ((Unit) act).getType().name() : "");
            } else if (a instanceof Build) {
                k = "BUILD:" + ((Build) a).getBuildingType();
            } else if (a instanceof Spawn) {
                k = "SPAWN:" + ((Spawn) a).getUnitType();
            } else if (a instanceof ResearchTech) {
                k = "RESEARCH_TECH:" + ((ResearchTech) a).getTech();
            } else {
                k = a.getActionType().name();
            }
            ks.add(k);
        }
        java.util.Collections.sort(ks);
        for (int i = 0; i < ks.size(); i++) {
            if (i > 0) keys.append('|');
            keys.append(ks.get(i));
        }
        return (iterInTurn - 1) + "," + (duplicate ? 1 : 0) + ","
                + Math.round(node.getValue() * 10000.0) / 10000.0 + "," + ks.size() + "," + keys;
    }
}
