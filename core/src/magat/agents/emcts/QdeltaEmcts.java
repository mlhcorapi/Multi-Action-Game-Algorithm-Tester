package magat.agents.emcts;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Granularity;
import magat.meter.Meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AB-EMCTS: {@link Emcts} whose repair is directed by an action-value table it learns within the turn. The
 * thesis line's agent ({@code Stage1BiasedAgent} in the engine fork), ported for the arms of the AB-EMCTS
 * experiment (docs/plans/ab-emcts-vs-oep.md).
 *
 * <p><b>The table.</b> Every mutation replaces one action of the incumbent's plan. When the child is scored,
 * its change in value over its parent is credited to the inserted action's classes and, negated, to the
 * removed one's, at the coarse and mid granularities of the game's {@code actionClass} at once
 * ({@link ActionTable}). An estimate shrinks toward the turn's global mean: Q(a) = (sum + m·mean) / (n + m).
 * The table starts empty every turn.
 *
 * <p><b>The repair.</b> Where EMCTS's repair copies, advances and evaluates every legal candidate to replace an
 * action that is no longer legal, this one takes the candidate with the table's best estimate at
 * {@code repairKeys} (ties uniform): no copy, no candidate advance, no evaluation. The attribution control,
 * {@link QdeltaParams.RepairMode#ZERO_EVAL_RANDOM}, takes an arbitrary legal action at the same cost, so the two
 * differ in the ranking alone. Insertion stays uniform, as in EMCTS.
 *
 * <p><b>Its own operations</b>, beyond {@link Emcts}'s: {@code qd.classify}, resolving an action's classes;
 * {@code qd.update}, one credit recorded; {@code qd.lookup}, one candidate's estimate read (the ranking arm
 * only); {@code qd.zeropick}, one zero-evaluation replacement, its legal-action query included.
 *
 * <p>It needs a game that offers action classes ({@code Game.hasActionClasses}). Its search stops hard at the
 * budget as {@link Emcts}'s does, inside its repair too. The equivalence gate plays it against
 * {@code Stage1BiasedAgent} under the engine's accounting, as {@link Emcts}'s plays its own.
 */
public final class QdeltaEmcts<S, A> extends Emcts<S, A> {

    private static final Meter.Op CLASSIFY = Meter.register("qd.classify");
    private static final Meter.Op UPDATE = Meter.register("qd.update");
    private static final Meter.Op LOOKUP = Meter.register("qd.lookup");
    private static final Meter.Op ZEROPICK = Meter.register("qd.zeropick");

    /** The operations an arm with these parameters records: {@link Emcts#OPERATIONS}, then its own. */
    public static List<String> operations(QdeltaParams q) {
        List<String> ops = new ArrayList<>(Emcts.OPERATIONS);
        ops.addAll(Arrays.asList("qd.classify", "qd.update"));
        if (q.repairMode == QdeltaParams.RepairMode.ZERO_EVAL) ops.add("qd.lookup");
        ops.add("qd.zeropick");
        return Collections.unmodifiableList(ops);
    }

    /** A registry entry: an arm of the experiment, by its id. */
    public static <S, A> AgentFactory<S, A> factory(String id, String description, QdeltaParams params) {
        List<String> ops = operations(params);
        Meter.declare(ops);
        return new AgentFactory<S, A>() {
            @Override public String id() { return id; }
            @Override public String description() { return description + ": " + params.describe(); }
            @Override public List<String> operations() { return ops; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new QdeltaEmcts<>(seed, params.copy()); }
        };
    }

    private final QdeltaParams q;
    /** Whether the fine class is resolved: a fine table, or any learner other than a table. */
    private final boolean needFine;
    private final ActionTable live;
    /** The learner, when it is not a table of independent classes (then null). */
    private final InTurnLearner model;
    private long budgetUnits = 1;

    // The mutation awaiting its child's score, the classes it credits and where in the plan it was.
    private Node<S, A> pendingChild, pendingParent;
    private String[] pendIns, pendRem;
    private double pendPosition;

    private long biasUniformPicks, statUpdates, zeroEvalPicks, repairCandidateEvals;
    private double repairUnits;

    public QdeltaEmcts(long seed, QdeltaParams params) {
        super(seed, params);
        this.q = params;
        this.needFine = params.learner != QdeltaParams.Learner.TABLE || params.repairKeys == Granularity.FINE;
        this.live = new ActionTable(needFine && params.learner == QdeltaParams.Learner.TABLE);
        switch (params.learner) {
            case HIER: model = new HierTable(params.shrinkageM); break;
            case LINEAR: model = new LinearLearner(); break;
            case MLP: model = new MlpLearner(seed); break;
            default: model = null;
        }
    }

    @Override
    public TurnRecord plan(magat.game.Game<S, A> game, S state, int player, TurnBudget budget) {
        if (!game.hasActionClasses())
            throw new IllegalStateException(game.id() + " offers no action classes, which " + getClass().getSimpleName() + " learns over");
        budgetUnits = Math.max(1, budget.units());
        return super.plan(game, state, player, budget);
    }

    /** How much of the turn's budget is spent, 0 to 1 (a model's input). */
    private double progress() {
        return Math.min(1.0, Meter.units() / budgetUnits);
    }

    // ------------------------------------------------------------------ the hooks

    @Override
    protected void onTurnStart() {
        live.reset();
        if (model != null) model.reset();
        pendingChild = null;
        pendingParent = null;
        pendIns = null;
        pendRem = null;
        biasUniformPicks = statUpdates = zeroEvalPicks = repairCandidateEvals = 0;
        repairUnits = 0.0;
    }

    /** The inserted and removed actions' classes, resolved on the prefix state they apply to (read-only). */
    @Override
    protected void afterInsertion(S prefixState, Node<S, A> parent, A inserted, A removed, int position, int length) {
        pendPosition = length > 1 ? (double) position / (length - 1) : 0.0;
        if (inserted != null) {
            biasUniformPicks++;
            try (Meter.Span c = CLASSIFY.span()) {
                pendIns = classes(inserted, prefixState);
            }
        } else {
            pendIns = null;
        }
        if (removed != null) {
            try (Meter.Span c = CLASSIFY.span()) {
                pendRem = classes(removed, prefixState);
            }
        } else {
            pendRem = null;
        }
    }

    @Override
    protected void afterMutation(Node<S, A> parent, Node<S, A> child) {
        pendingChild = child;
        pendingParent = parent;
    }

    /** Completes the credit once the pending child is scored. */
    @Override
    protected void onEvaluated(Node<S, A> node) {
        if (pendingChild == null || node != pendingChild) return;
        double delta = node.value() - pendingParent.value();
        int updates = (pendIns != null ? 1 : 0) + (q.removalCredit && pendRem != null ? 1 : 0);
        if (updates > 0) {
            try (Meter.Span u = UPDATE.span(updates)) {
                double progress = model != null ? progress() : 0.0;
                if (pendIns != null) add(pendIns, delta, progress);
                if (q.removalCredit && pendRem != null) add(pendRem, -delta, progress);
            }
        }
        pendingChild = null;
        pendingParent = null;
        pendIns = null;
        pendRem = null;
    }

    @Override
    protected void moreTelemetry(TurnRecord.Builder record) {
        // Every counter the thesis agent reports, those of mechanisms these arms never use included: zero there
        // shows they did not fire.
        record.telemetry("biasGreedyPicks", 0)
              .telemetry("biasUniformPicks", biasUniformPicks)
              .telemetry("shortlistCalls", 0)
              .telemetry("shortlistSkipped", 0)
              .telemetry("statUpdates", statUpdates)
              .telemetry("zeroEvalPicks", zeroEvalPicks)
              .telemetry("repairUnits", repairUnits)
              .telemetry("repairCandidateEvals", repairCandidateEvals);
        if (model != null) record.telemetry("modelSquaredError", model.meanSquaredError());
    }

    // ------------------------------------------------------------------ the table

    private String[] classes(A action, S state) {
        return new String[]{game.actionClass(state, action, Granularity.COARSE), game.actionClass(state, action, Granularity.MID),
                needFine ? game.actionClass(state, action, Granularity.FINE) : null};
    }

    private void add(String[] k, double delta, double progress) {
        if (model != null) model.credit(k, pendPosition, progress, delta);
        else live.add(k[0], k[1], k[2], delta);
        statUpdates++;
    }

    /** Shrunk mean change of one action's class; an unobserved class returns exactly the global mean. */
    private double qhat(Granularity g, A action, S state) {
        double[] e = live.entry(g, game.actionClass(state, action, g));
        return (e[0] + q.shrinkageM * live.globalMean()) / (e[1] + q.shrinkageM);
    }

    // ------------------------------------------------------------------ the repair

    /** The repair, its units and its candidate evaluations measured (the meter's reads are pure). */
    @Override
    protected ArrayList<A> repair(ArrayList<A> child, S state) {
        double u0 = Meter.units();
        long e0 = Meter.evals();
        try {
            return tableDirectedRepair(child, state);
        } finally {
            repairUnits += Meter.units() - u0;
            repairCandidateEvals += Meter.evals() - e0;
        }
    }

    /**
     * {@link Emcts#repair}'s structure with the replacement made without evaluating anything: the thesis agent's own
     * repair, kept exactly, and stopping when the budget is spent (then the mutation abandons the child).
     */
    private ArrayList<A> tableDirectedRepair(ArrayList<A> child, S state) {
        ArrayList<A> repaired = new ArrayList<>();
        lastRepairKept = 0;
        lastRepairReplaced = 0;
        for (int i = 0; i < child.size(); i++) {
            if (game.activePlayer(state) != player) return repaired;
            if (spent()) return repaired;
            boolean done;
            try {
                done = isLegal(state, child.get(i));
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
            A best = zeroEvalReplace(state, q.repairMode == QdeltaParams.RepairMode.ZERO_EVAL,
                    child.size() > 1 ? (double) i / (child.size() - 1) : 0.0);
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
     * The highest-estimate legal candidate, with no copy, no candidate advance and no evaluation; or, for the
     * control, an arbitrary one. Ties break uniformly, and are common: most candidates are unseen and sit at the
     * prior, which is why the granularity matters.
     */
    private A zeroEvalReplace(S state, boolean rankByTable, double position) {
        try (Meter.Span z = ZEROPICK.span()) {
            List<A> cands = legalActions(state);
            if (cands.isEmpty()) return null;
            zeroEvalPicks++;
            if (!rankByTable) return cands.get(rnd.nextInt(cands.size()));
            A best = null;
            double bestQ = Double.NEGATIVE_INFINITY;
            int numTies = 0;
            try (Meter.Span l = LOOKUP.span(cands.size())) {
                double progress = model != null ? progress() : 0.0;
                for (A c : cands) {
                    double v = model != null ? model.estimate(classes(c, state), position, progress) : qhat(q.repairKeys, c, state);
                    if (v > bestQ) {
                        bestQ = v;
                        best = c;
                        numTies = 1;
                    } else if (v == bestQ) {
                        numTies++;
                        if (rnd.nextInt(numTies) == 0) best = c;
                    }
                }
            }
            return best;
        }
    }
}
