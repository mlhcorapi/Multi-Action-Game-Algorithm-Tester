package magat.agents.emcts;

import magat.agent.TurnRecord;
import magat.game.Granularity;
import magat.meter.Meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EMCTS whose mutation learns, within the turn, which actions pay, and which can use the same
 * statistics to direct the repair (the AB-EMCTS line's agent).
 *
 * <p><b>What is learned.</b> The running mean of the paired delta {@code childValue - parentValue},
 * credited to the inserted action's class and, negated, to the removed one's, at three granularities
 * at once ({@link ActionTable}), shrunk toward the running global mean with prior strength
 * {@code shrinkageM}. Reset at the start of every turn.
 *
 * <p><b>How it is used.</b> Insertion is epsilon-greedy over the estimate at {@code insertKeys}
 * (ties uniform, so an empty table degrades to uniform). Repair ({@code repairMode}) either runs the
 * base class's exhaustive greedy, or ranks candidates by the estimate at {@code repairKeys} for free
 * and evaluates only the top K, or commits to the argmax with no forward-model call at all; the two
 * random modes replace the ranking by noise with the same number of draws, which is what makes them
 * attribution controls.
 *
 * <p><b>Baseline contract.</b> With every default the agent makes the identical forward-model calls
 * and random draws as {@link Emcts}: the epsilon coin is not flipped at 1, bookkeeping is read-only,
 * full repair is the base class's own method.
 *
 * <p>A line-for-line port of the thesis tree's {@code Stage1BiasedAgent} (its sequence-composition
 * log, observation only, is not carried over); the oracle gate asserts every arm against that class.
 */
public class QdeltaEmcts<S, A> extends Emcts<S, A> {

    /** Burns skipped before the support counters count: the table is empty at turn start by construction. */
    public static final int WARMUP_BURNS = 4;
    /** Observations behind a class before reading it is evidence rather than the prior. */
    public static final int MIN_SUPPORT = 3;

    private final QdeltaParams q;
    private final boolean needFine;
    private final ActionTable live, snap;

    // The mutation awaiting its child's evaluation (expandOnce always evaluates right after mutate).
    private Node<S, A> pendingChild, pendingParent;
    private String[] pendIns, pendRem;          // {coarse, mid, fine} classes, or null

    private int burnsInTurn;
    private long biasGreedyPicks, biasUniformPicks, shortlistCalls, shortlistSkipped, statUpdates, zeroEvalPicks;
    private long biasPicksAfterWarmup, biasSupportedAfterWarmup, repairCandidateEvals;
    private double repairUnits;

    public QdeltaEmcts(long seed, QdeltaParams params) {
        super(seed, params);
        this.q = params;
        switch (params.repairMode) {
            case SHORTLIST_K:
            case SHORTLIST_RANDOM:
                if (params.shortlistK <= 0)
                    throw new IllegalArgumentException("repairMode " + params.repairMode + " needs shortlistK > 0 (got " + params.shortlistK + ")");
                break;
            default:
                if (params.shortlistK != 0)
                    throw new IllegalArgumentException("repairMode " + params.repairMode + " needs shortlistK = 0 (got " + params.shortlistK
                            + "); a mode and a K that disagree would be an arm other than the one its name claims");
        }
        this.needFine = params.insertKeys == Granularity.FINE || params.repairKeys == Granularity.FINE;
        this.live = new ActionTable(needFine);
        this.snap = params.perBurnUpdates ? new ActionTable(needFine) : null;
    }

    // ------------------------------------------------------------------ the turn's lifecycle

    /** The first act of a turn; doubles as the turn-start hook: the statistics reset here. */
    @Override
    protected Node<S, A> initialPlan(S state) {
        live.reset();
        if (snap != null) snap.reset();
        burnsInTurn = 0;
        pendingChild = null;
        pendingParent = null;
        pendIns = null;
        pendRem = null;
        return super.initialPlan(state);
    }

    @Override
    protected void onBridgeBurn(int phaseNumber) {
        burnsInTurn++;
        if (q.perBurnUpdates) snap.copyFrom(live);       // publish the batch at the phase boundary
    }

    @Override
    protected void moreTelemetry(TurnRecord.Builder record) {
        record.telemetry("biasGreedyPicks", biasGreedyPicks)
              .telemetry("biasUniformPicks", biasUniformPicks)
              .telemetry("shortlistCalls", shortlistCalls)
              .telemetry("shortlistSkipped", shortlistSkipped)
              .telemetry("statUpdates", statUpdates)
              .telemetry("zeroEvalPicks", zeroEvalPicks)
              .telemetry("repairUnits", repairUnits)
              .telemetry("repairCandidateEvals", repairCandidateEvals);
    }

    /**
     * The support probe's counters: biased insertions after the warm-up burns, how many chose a class
     * already carrying {@link #MIN_SUPPORT} observations, and the shape of the insertion table.
     */
    @Override
    public Map<String, Double> support() {
        Map<String, Double> m = new LinkedHashMap<>();
        int[] obs = (q.perBurnUpdates ? snap : live).counts(q.insertKeys);
        int[] sorted = obs.clone();
        Arrays.sort(sorted);
        double median = sorted.length == 0 ? 0 : sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
        m.put("picksAfterWarmup", (double) biasPicksAfterWarmup);
        m.put("supportedAfterWarmup", (double) biasSupportedAfterWarmup);
        m.put("distinctInsertKeys", (double) obs.length);
        m.put("medianInsertKeyObservations", median);
        m.put("mutations", (double) mutEvents);
        m.put("biased", q.epsilon < 1.0 ? 1.0 : 0.0);
        return m;
    }

    // ------------------------------------------------------------------ statistics

    private String[] classes(A action, S state) {
        return new String[]{
                game.actionClass(state, action, Granularity.COARSE),
                game.actionClass(state, action, Granularity.MID),
                needFine ? game.actionClass(state, action, Granularity.FINE) : null};
    }

    private ActionTable reading() {
        return q.perBurnUpdates ? snap : live;
    }

    /** Shrunk mean delta of one action's class; an unobserved class returns exactly the global mean. */
    private double qhat(Granularity g, A action, S state) {
        ActionTable t = reading();
        double[] e = t.entry(g, game.actionClass(state, action, g));
        return (e[0] + q.shrinkageM * t.globalMean()) / (e[1] + q.shrinkageM);
    }

    private int observations(Granularity g, A action, S state) {
        return (int) reading().entry(g, game.actionClass(state, action, g))[1];
    }

    private void addObservation(String[] k, double delta) {
        live.add(k[0], k[1], k[2], delta);
        statUpdates++;
    }

    // ------------------------------------------------------------------ biased mutation

    /** {@link Emcts#mutate} with the insertion draw made epsilon-greedy and read-only credit bookkeeping. */
    @Override
    protected Node<S, A> mutate(Node<S, A> node, S state) {
        S clone = game.copy(state);
        ArrayList<A> seq = node.sequence();
        int moveToMutate = seq.isEmpty() ? 0 : rnd.nextInt(seq.size());
        ArrayList<A> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            advance(state, seq.get(i));
            newSeq.add(seq.get(i));
        }
        List<A> available = game.legalActions(state);

        String[] ins = null;
        if (!available.isEmpty()) {
            A inserted = null;
            if (q.epsilon >= 1.0 || rnd.nextDouble() < q.epsilon) {
                inserted = available.get(rnd.nextInt(available.size()));
                biasUniformPicks++;
            } else {
                double bestQ = Double.NEGATIVE_INFINITY;
                int numTies = 0;
                for (A cand : available) {
                    double v = qhat(q.insertKeys, cand, state);
                    if (v > bestQ) {
                        bestQ = v;
                        inserted = cand;
                        numTies = 1;
                    } else if (v == bestQ) {
                        numTies++;
                        if (rnd.nextInt(numTies) == 0) inserted = cand;
                    }
                }
                biasGreedyPicks++;
                if (burnsInTurn >= WARMUP_BURNS && inserted != null) {
                    biasPicksAfterWarmup++;
                    if (observations(q.insertKeys, inserted, state) >= MIN_SUPPORT) biasSupportedAfterWarmup++;
                }
            }
            ins = classes(inserted, state);
            newSeq.add(inserted);
        }
        // The removed action's classes, resolved on the same prefix state (read-only).
        String[] rem = moveToMutate < seq.size() ? classes(seq.get(moveToMutate), state) : null;

        for (int i = moveToMutate + 1; i < seq.size(); i++) newSeq.add(seq.get(i));
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        Node<S, A> newNode = new Node<>(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.children().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;

        pendingChild = newNode;
        pendingParent = node;
        pendIns = ins;
        pendRem = rem;
        return newNode;
    }

    /** Completes the credit assignment once the pending child is scored. */
    @Override
    protected void eval(S state, Node<S, A> node) {
        boolean match = pendingChild != null && node == pendingChild;
        super.eval(state, node);
        if (!match) return;
        double delta = node.value() - pendingParent.value();
        if (pendIns != null) addObservation(pendIns, delta);
        if (q.removalCredit && pendRem != null) addObservation(pendRem, -delta);
        pendingChild = null;
        pendingParent = null;
        pendIns = null;
        pendRem = null;
    }

    // ------------------------------------------------------------------ table-directed repair

    /** Repair, with its cost measured for every mode (the meter reads are pure). */
    @Override
    protected ArrayList<A> repair(ArrayList<A> child, S state) {
        double u0 = Meter.units();
        long e0 = Meter.evals();
        try {
            return q.repairMode == QdeltaParams.RepairMode.FULL ? super.repair(child, state) : tableDirectedRepair(child, state);
        } finally {
            repairUnits += Meter.units() - u0;
            repairCandidateEvals += Meter.evals() - e0;
        }
    }

    /** {@link Emcts#repair}'s structure with the replacement made by the table (the thesis tree's own copy, kept exactly). */
    private ArrayList<A> tableDirectedRepair(ArrayList<A> child, S state) {
        ArrayList<A> repaired = new ArrayList<>();
        lastRepairKept = 0;
        lastRepairReplaced = 0;
        for (int i = 0; i < child.size(); i++) {
            if (game.activePlayer(state) != player) return repaired;
            boolean done;
            try {
                done = game.isLegal(state, child.get(i));
            } catch (RuntimeException e) {
                done = false;
            }
            if (done) {
                repaired.add(child.get(i));
                try {
                    advance(state, child.get(i));
                } catch (RuntimeException e) {
                    // A legal action that throws is replaced like an illegal one.
                    repaired.remove(repaired.size() - 1);
                    done = false;
                }
                if (done) {
                    lastRepairKept++;
                    continue;
                }
            }
            A best;
            switch (q.repairMode) {
                case ZERO_EVAL:        best = zeroEvalReplace(state, true); break;
                case ZERO_EVAL_RANDOM: best = zeroEvalReplace(state, false); break;
                case SHORTLIST_RANDOM: best = shortlistedReplace(state, false); break;
                default:               best = shortlistedReplace(state, true); break;
            }
            if (best == null) break;
            advance(state, best);
            repaired.add(best);
            lastRepairReplaced++;
        }
        if (!repaired.isEmpty() && !game.endsTurn(repaired.get(repaired.size() - 1))) {
            A end = game.endTurnAction(state);
            if (end != null) repaired.add(end);
        }
        return repaired;
    }

    /**
     * Zero-evaluation replacement: the highest-estimate legal candidate, with no copy, no candidate
     * advance and no evaluation. Ties break uniformly, and are common: most candidates are unseen and
     * sit at the prior, which is why the granularity matters here.
     */
    private A zeroEvalReplace(S state, boolean rankByTable) {
        List<A> cands = game.legalActions(state);
        if (cands.isEmpty()) return null;
        zeroEvalPicks++;
        if (!rankByTable) return cands.get(rnd.nextInt(cands.size()));
        A best = null;
        double bestQ = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (A c : cands) {
            double v = qhat(q.repairKeys, c, state);
            if (v > bestQ) {
                bestQ = v;
                best = c;
                numTies = 1;
            } else if (v == bestQ) {
                numTies++;
                if (rnd.nextInt(numTies) == 0) best = c;
            }
        }
        return best;
    }

    /**
     * Rank the legal candidates by the table (free) and evaluate only the top K. The random mode
     * replaces the ranking key by the jitter alone: same candidates, same K, same evaluations of the
     * survivors and the same number of random draws, so the two differ in the ranking and in nothing else.
     */
    private A shortlistedReplace(S state, boolean rankByTable) {
        List<A> cands = game.legalActions(state);
        if (cands.isEmpty()) return null;
        List<A> pool = cands;
        if (cands.size() > q.shortlistK) {
            shortlistCalls++;
            shortlistSkipped += cands.size() - q.shortlistK;
            int n = cands.size();
            double[] score = new double[n];
            for (int i = 0; i < n; i++) {
                double jitter = rnd.nextDouble();
                score[i] = rankByTable ? qhat(q.repairKeys, cands.get(i), state) + jitter * 1e-9 : jitter;
            }
            pool = new ArrayList<>(q.shortlistK);
            boolean[] taken = new boolean[n];
            for (int k = 0; k < q.shortlistK; k++) {
                int best = -1;
                for (int i = 0; i < n; i++)
                    if (!taken[i] && (best < 0 || score[i] > score[best])) best = i;
                taken[best] = true;
                pool.add(cands.get(best));
            }
        }
        A bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (A candidate : pool) {
            double s;
            try {
                S test = game.copy(state);
                advance(test, candidate);
                s = game.evaluate(test, player);
            } catch (RuntimeException e) {
                continue;
            }
            if (s > bestScore) {
                bestScore = s;
                bestAction = candidate;
                numTies = 1;
            } else if (s == bestScore) {
                numTies++;
                if (rnd.nextInt(numTies) == 0) bestAction = candidate;
            }
        }
        return bestAction;
    }
}
