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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AB-EMCTS: {@link Emcts} whose repair, and optionally whose mutation, is directed by what it learns within the
 * turn. The thesis line's agent ({@code Stage1BiasedAgent} in the engine fork), ported for the AB-EMCTS
 * experiment (docs/plans/ab-emcts-vs-oep.md) and extended for the in-turn learning plan
 * (docs/plans/in-turn-learning.md).
 *
 * <p><b>The learner.</b> Every mutation replaces actions of the plan. When the child is scored, its change in
 * value over its parent is credited to each inserted action's classes and, negated, to each removed one's: in a
 * table of independent classes ({@link ActionTable}), where an estimate shrinks toward the turn's mean, Q(a) =
 * (sum + m·mean) / (n + m), or in another {@link InTurnLearner}. It starts empty every turn.
 *
 * <p><b>The repair.</b> Where EMCTS's repair copies, advances and evaluates every legal candidate to replace an
 * action that is no longer legal, this one takes the candidate with the learner's best estimate (ties uniform):
 * no copy, no candidate advance, no evaluation. The attribution control,
 * {@link QdeltaParams.RepairMode#ZERO_EVAL_RANDOM}, takes an arbitrary legal action at the same cost.
 * {@link QdeltaParams.RepairMode#SHORTLIST} evaluates the best {@code shortlistK} candidates instead, as the
 * engine's {@code SHORTLIST_K} does.
 *
 * <p><b>The mutation</b> ({@link QdeltaParams}): uniform as EMCTS's by default. It can be biased by the table
 * (ε-greedy as the engine's, or Thompson sampling, on every insertion or on some), change several actions at
 * once, guard against duplicate children, choose its position by what mutating each position brought, mix biased
 * and uniform children in a generation, adapt its operator or its step size, read substitutions or learned pairs,
 * keep a tabu memory, or kick from the best plan when the search stalls. Each draws its own random numbers, so an
 * arm with every default makes exactly AB-EMCTS's draws.
 *
 * <p><b>Its own operations</b>, beyond {@link Emcts}'s: {@code qd.classify}, resolving an action's classes;
 * {@code qd.update}, one credit recorded; {@code qd.lookup}, one estimate or posterior draw read;
 * {@code qd.zeropick}, one evaluation-free replacement, its legal-action query included; {@code qd.shortlist},
 * one shortlisted replacement's query and ranking.
 *
 * <p>It needs a game that offers action classes ({@code Game.hasActionClasses}). Its search stops hard at the
 * budget as {@link Emcts}'s does, inside its repair and its mutations too. The equivalence gate plays it against
 * {@code Stage1BiasedAgent} under the engine's accounting where the engine has the same arm.
 */
public final class QdeltaEmcts<S, A> extends Emcts<S, A> {

    private static final Meter.Op CLASSIFY = Meter.register("qd.classify");
    private static final Meter.Op UPDATE = Meter.register("qd.update");
    private static final Meter.Op LOOKUP = Meter.register("qd.lookup");
    private static final Meter.Op ZEROPICK = Meter.register("qd.zeropick");
    private static final Meter.Op SHORTLIST = Meter.register("qd.shortlist");

    /** The operations an arm with these parameters records: {@link Emcts#OPERATIONS}, then its own. */
    public static List<String> operations(QdeltaParams q) {
        List<String> ops = new ArrayList<>(Emcts.OPERATIONS);
        ops.addAll(Arrays.asList("qd.classify", "qd.update"));
        boolean lookups = q.repairMode != QdeltaParams.RepairMode.ZERO_EVAL_RANDOM || q.insertion != QdeltaParams.Insertion.UNIFORM
                || q.learnSite || q.tabuGenerations > 0 || q.adaptive == QdeltaParams.Adaptive.OPERATORS
                || q.biasedChildrenPerGeneration > 0 || q.kickAfterGenerations > 0 || q.blockShare > 0;
        if (lookups) ops.add("qd.lookup");
        if (q.repairMode == QdeltaParams.RepairMode.SHORTLIST) ops.add("qd.shortlist");
        else ops.add("qd.zeropick");
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

    /** A mutation operator: how many actions it changes, and how each inserted action is drawn. */
    private static final class Op {
        final int k;
        final QdeltaParams.Insertion insertion;
        final double share;
        final boolean oneOfThem;

        Op(int k, QdeltaParams.Insertion insertion, double share, boolean oneOfThem) {
            this.k = k;
            this.insertion = insertion;
            this.share = share;
            this.oneOfThem = oneOfThem;
        }
    }

    private static final Op UNIFORM_ONE = new Op(1, QdeltaParams.Insertion.UNIFORM, 1.0, false);
    private static final Op THOMPSON_ONE = new Op(1, QdeltaParams.Insertion.THOMPSON, 1.0, false);
    private static final Op KICK = new Op(5, QdeltaParams.Insertion.THOMPSON, 1.0, false);
    /** The adaptive operator selection's four operators: uniform, Thompson, and one biased of three or of five. */
    private static final Op[] AOS_OPS = {UNIFORM_ONE, THOMPSON_ONE,
            new Op(3, QdeltaParams.Insertion.THOMPSON, 1.0, true), new Op(5, QdeltaParams.Insertion.THOMPSON, 1.0, true)};
    private static final String[] AOS_NAMES = {"aosUniform", "aosThompson", "aosK3", "aosK5"};
    private static final int POSITIONS = 64;
    /** The 1/5 success rule's window, in children. */
    private static final int STEP_WINDOW = 10;
    private static final int MAX_STEP = 8;

    private final QdeltaParams q;
    /** Whether the fine class is resolved: a fine table, or any learner other than a table. */
    private final boolean needFine;
    private final ActionTable live;
    /** The learner, when it is not a table of independent classes (then null). */
    private final InTurnLearner model;
    private final Op baseOp;
    private long budgetUnits = 1;

    // The mutation awaiting its child's score: the classes it credits, where, and with which operator.
    private Node<S, A> pendingChild, pendingParent;
    private final List<String[]> pendIns = new ArrayList<>(), pendRem = new ArrayList<>();
    private final List<String> pendPred = new ArrayList<>();
    private final List<Integer> pendSites = new ArrayList<>();
    private double pendPosition;
    private int pendOp = -1;

    // This child's operator, and what the turn has learned about the mutation itself.
    private Op op;
    private boolean kickNow, blockChild;
    private ArrayList<A> siteSeq;
    private final TargetScale deltaScale = new TargetScale();
    private final double[][] siteStats = new double[POSITIONS][2];
    private double siteSum;
    private long siteN;
    private final Map<String, double[]> subst = new HashMap<>(), pairs = new HashMap<>();
    private final Map<String, Long> tabu = new HashMap<>();
    private final double[][] aos = new double[AOS_OPS.length][2];
    private int stepK, windowChildren, windowSuccesses;
    private long children, sinceBest;
    private boolean kickPending;

    private long biasGreedyPicks, biasUniformPicks, statUpdates, zeroEvalPicks, repairCandidateEvals;
    private long shortlistCalls, shortlistSkipped, duplicatesRedrawn, kicks, tabuBlocked, insertions, stepSum;
    private double repairUnits;
    private final Map<String, Long> insertedClasses = new LinkedHashMap<>();
    private final long[] mutatedPositions = new long[POSITIONS];
    private final long[] aosUse = new long[AOS_OPS.length];

    public QdeltaEmcts(long seed, QdeltaParams params) {
        super(seed, params);
        this.q = params;
        this.needFine = params.learner != QdeltaParams.Learner.TABLE || params.repairKeys == Granularity.FINE
                || params.insertKeys == Granularity.FINE;
        this.live = new ActionTable(needFine && params.learner == QdeltaParams.Learner.TABLE);
        switch (params.learner) {
            case HIER: model = new HierTable(params.shrinkageM); break;
            case LINEAR: model = new LinearLearner(); break;
            case MLP: model = new MlpLearner(seed); break;
            default: model = null;
        }
        this.baseOp = new Op(Math.max(1, params.mutationsPerChild), params.insertion, params.biasShare, params.biasOneOfThem);
        if (!params.plainMutation() && params.learner != QdeltaParams.Learner.TABLE)
            throw new IllegalArgumentException("a learned mutation reads a table's posterior: give it the TABLE learner");
        if (params.insertion == QdeltaParams.Insertion.EPSILON && params.mutationsPerChild > 1)
            throw new IllegalArgumentException("epsilon-greedy insertion is the engine's, with one mutation per child");
        if (params.repairMode == QdeltaParams.RepairMode.SHORTLIST && params.shortlistK <= 0)
            throw new IllegalArgumentException("a shortlist repair needs shortlistK > 0");
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

    private long generation() {
        return children / Math.max(1, q.childrenPerGeneration);
    }

    // ------------------------------------------------------------------ the hooks

    @Override
    protected void onTurnStart() {
        live.reset();
        if (model != null) model.reset();
        pendingChild = null;
        pendingParent = null;
        pendClear();
        op = baseOp;
        kickNow = blockChild = kickPending = false;
        siteSeq = null;
        deltaScale.reset();
        for (double[] e : siteStats) Arrays.fill(e, 0.0);
        siteSum = 0.0;
        siteN = 0;
        subst.clear();
        pairs.clear();
        tabu.clear();
        for (double[] e : aos) Arrays.fill(e, 0.0);
        stepK = Math.max(1, q.mutationsPerChild);
        windowChildren = windowSuccesses = 0;
        children = sinceBest = 0;
        biasGreedyPicks = biasUniformPicks = statUpdates = zeroEvalPicks = repairCandidateEvals = 0;
        shortlistCalls = shortlistSkipped = duplicatesRedrawn = kicks = tabuBlocked = insertions = stepSum = 0;
        repairUnits = 0.0;
        insertedClasses.clear();
        Arrays.fill(mutatedPositions, 0);
        Arrays.fill(aosUse, 0);
    }

    private void pendClear() {
        pendIns.clear();
        pendRem.clear();
        pendPred.clear();
        pendSites.clear();
        pendOp = -1;
    }

    /** The child's operator: the kick, the bandit's draw, the step size, the generation's mix, or the arm's own. */
    @Override
    protected void onChildStart(int childIdx) {
        children++;
        kickNow = false;
        int drawn = -1;
        if (kickPending) {
            op = KICK;
            kickNow = true;
            kickPending = false;
            kicks++;
        } else if (q.adaptive == QdeltaParams.Adaptive.OPERATORS) {
            drawn = drawOperator();
            op = AOS_OPS[drawn];
            aosUse[drawn]++;
        } else if (q.adaptive == QdeltaParams.Adaptive.STEP_SIZE) {
            op = new Op(stepK, q.insertion, q.biasShare, false);
        } else if (q.biasedChildrenPerGeneration > 0) {
            op = childIdx < q.biasedChildrenPerGeneration ? THOMPSON_ONE : UNIFORM_ONE;
        } else {
            op = baseOp;
        }
        stepSum += op.k;
        blockChild = q.blockShare > 0 && rnd.nextDouble() < q.blockShare;
        nextOp = drawn;
    }

    private int nextOp = -1;

    @Override
    protected Node<S, A> parentFor(Node<S, A> incumbent, int childIdx) {
        return kickNow ? bestPlan() : incumbent;
    }

    @Override
    protected int duplicateRedraws() {
        return q.duplicateRedraws;
    }

    @Override
    protected void onDuplicateDiscarded() {
        duplicatesRedrawn++;
    }

    @Override
    protected int chooseSite(ArrayList<A> seq) {
        siteSeq = seq;
        if (!q.learnSite || seq.isEmpty()) return super.chooseSite(seq);
        return thompsonSite(seq.size());
    }

    /** The single-mutation path's insertion: the child's operator decides how it is drawn. */
    @Override
    protected A chooseInsertion(S state, List<A> available, int position, int length) {
        A removed = siteSeq != null && position < siteSeq.size() ? siteSeq.get(position) : null;
        return insert(state, available, position, 0, removed, null);
    }

    /** One mutation (the engine's path, through the hooks), or several at once, or a learned pair. */
    @Override
    protected Node<S, A> mutate(Node<S, A> node, S state) {
        int k = blockChild ? 2 : op.k;
        if (k <= 1 || node.sequence().isEmpty()) return super.mutate(node, state);
        return multiMutate(node, state, k);
    }

    /** The inserted and removed actions' classes, resolved on the prefix state they apply to (read-only). */
    @Override
    protected void afterInsertion(S prefixState, Node<S, A> parent, A inserted, A removed, int position, int length) {
        pendClear();
        pendOp = nextOp;
        pendPosition = length > 1 ? (double) position / (length - 1) : 0.0;
        A pred = siteSeq != null && position > 0 && position - 1 < siteSeq.size() ? siteSeq.get(position - 1) : null;
        record(prefixState, inserted, removed, position, pred);
    }

    /** One mutation's classes, for the credit once its child is scored. */
    private void record(S state, A inserted, A removed, int position, A pred) {
        // One classify span per action classified, as AB-EMCTS always recorded them: their counts are in its published turns.
        String[] ins = null, rem = null;
        String before = null;
        if (inserted != null) {
            try (Meter.Span c = CLASSIFY.span()) {
                ins = classes(inserted, state);
            }
        }
        if (removed != null) {
            try (Meter.Span c = CLASSIFY.span()) {
                rem = classes(removed, state);
            }
        }
        if (q.blockShare > 0 && pred != null) {
            try (Meter.Span c = CLASSIFY.span()) {
                before = game.actionClass(state, pred, q.insertKeys);
            }
        }
        pendIns.add(ins);
        pendRem.add(rem);
        pendPred.add(before);
        pendSites.add(position);
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
        int updates = 0;
        for (int i = 0; i < pendIns.size(); i++)
            updates += (pendIns.get(i) != null ? 1 : 0) + (q.removalCredit && pendRem.get(i) != null ? 1 : 0);
        if (updates > 0) {
            try (Meter.Span u = UPDATE.span(updates)) {
                double progress = model != null ? progress() : 0.0;
                for (int i = 0; i < pendIns.size(); i++) {
                    String[] ins = pendIns.get(i), rem = pendRem.get(i);
                    if (ins != null) add(ins, delta, progress);
                    if (q.removalCredit && rem != null) add(rem, -delta, progress);
                }
                if (!q.plainMutation()) learnAboutMutation(node, delta);
            }
        }
        pendingChild = null;
        pendingParent = null;
        pendClear();
    }

    /** What the mutation rules learn from a scored child, beyond the table's credit. */
    private void learnAboutMutation(Node<S, A> child, double delta) {
        deltaScale.standardize(delta);
        int key = insertKeyIndex();
        for (int i = 0; i < pendIns.size(); i++) {
            String[] ins = pendIns.get(i), rem = pendRem.get(i);
            int site = pendSites.get(i);
            if (ins != null) {
                insertions++;
                insertedClasses.merge(ins[0], 1L, Long::sum);
                mutatedPositions[Math.min(site, POSITIONS - 1)]++;
            }
            if (q.learnSite) {
                double[] e = siteStats[Math.min(site, POSITIONS - 1)];
                e[0] += delta;
                e[1] += 1.0;
                siteSum += delta;
                siteN++;
            }
            if (q.substitutions && ins != null && rem != null) bump(subst, rem[key] + "->" + ins[key], delta);
            if (q.blockShare > 0 && ins != null && pendPred.get(i) != null) bump(pairs, pendPred.get(i) + "->" + ins[key], delta);
            if (q.tabuGenerations > 0 && ins != null && delta < 0) tabu.put(site + "|" + ins[1], generation() + q.tabuGenerations);
        }
        boolean improved = delta > 0;
        if (pendOp >= 0) {
            aos[pendOp][1] += 1.0;
            if (improved) aos[pendOp][0] += 1.0;
        }
        if (q.adaptive == QdeltaParams.Adaptive.STEP_SIZE) {
            windowChildren++;
            if (improved) windowSuccesses++;
            if (windowChildren == STEP_WINDOW) {
                // The 1/5 rule: more than a fifth improving, a bigger step; fewer, a smaller one.
                if (windowSuccesses * 5 > STEP_WINDOW) stepK = Math.min(MAX_STEP, stepK + 1);
                else if (windowSuccesses * 5 < STEP_WINDOW) stepK = Math.max(1, stepK - 1);
                windowChildren = windowSuccesses = 0;
            }
        }
        if (q.kickAfterGenerations > 0) {
            if (child.value() > bestPlan().value()) sinceBest = 0;
            else if (++sinceBest >= (long) q.kickAfterGenerations * Math.max(1, q.childrenPerGeneration)) {
                kickPending = true;
                sinceBest = 0;
            }
        }
    }

    private static void bump(Map<String, double[]> m, String key, double delta) {
        double[] e = m.computeIfAbsent(key, k -> new double[2]);
        e[0] += delta;
        e[1] += 1.0;
    }

    @Override
    protected void moreTelemetry(TurnRecord.Builder record) {
        // Every counter the thesis agent reports, those of mechanisms an arm never uses included: zero there
        // shows they did not fire.
        record.telemetry("biasGreedyPicks", biasGreedyPicks)
              .telemetry("biasUniformPicks", biasUniformPicks)
              .telemetry("shortlistCalls", shortlistCalls)
              .telemetry("shortlistSkipped", shortlistSkipped)
              .telemetry("statUpdates", statUpdates)
              .telemetry("zeroEvalPicks", zeroEvalPicks)
              .telemetry("repairUnits", repairUnits)
              .telemetry("repairCandidateEvals", repairCandidateEvals);
        if (model != null) record.telemetry("modelSquaredError", model.meanSquaredError());
        if (!q.plainMutation()) {
            // The mechanism report (docs/plans/in-turn-learning.md, section 6).
            record.telemetry("mutationsPerChild", children > 0 ? (double) stepSum / children : 0.0)
                  .telemetry("duplicatesRedrawn", duplicatesRedrawn)
                  .telemetry("insertedClassEntropy", entropy(insertedClasses.values()))
                  .telemetry("mutatedPositionEntropy", entropy(Arrays.stream(mutatedPositions).boxed().collect(java.util.stream.Collectors.toList())))
                  .telemetry("kicks", kicks)
                  .telemetry("tabuBlocked", tabuBlocked);
            if (q.adaptive == QdeltaParams.Adaptive.OPERATORS)
                for (int i = 0; i < AOS_OPS.length; i++) record.telemetry(AOS_NAMES[i], children > 0 ? (double) aosUse[i] / children : 0.0);
        }
    }

    /** The entropy, in bits, of a distribution given by counts. */
    private static double entropy(Iterable<Long> counts) {
        long total = 0;
        for (long c : counts) total += c;
        if (total == 0) return 0.0;
        double h = 0.0;
        for (long c : counts)
            if (c > 0) {
                double p = (double) c / total;
                h -= p * StrictMath.log(p) / StrictMath.log(2);
            }
        return h;
    }

    // ------------------------------------------------------------------ the table

    private int insertKeyIndex() {
        return q.insertKeys == Granularity.COARSE ? 0 : q.insertKeys == Granularity.MID ? 1 : 2;
    }

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

    private double shrunk(double[] e, double prior) {
        return (e[0] + q.shrinkageM * prior) / (e[1] + q.shrinkageM);
    }

    /**
     * An insertion candidate's class posterior: {mean, count}. The table's shrunk estimate; with substitutions,
     * what replacing {@code removedKey} by this class brought, shrunk toward that; with a learned pair, what
     * following {@code pairFrom} with it brought, shrunk toward that.
     */
    private double[] posterior(String key, String removedKey, String pairFrom) {
        double[] e = live.entry(q.insertKeys, key);
        double mean = shrunk(e, live.globalMean()), n = e[1];
        if (q.substitutions && removedKey != null) {
            double[] s = subst.get(removedKey + "->" + key);
            if (s != null) {
                mean = shrunk(s, mean);
                n = s[1];
            }
        }
        if (pairFrom != null) {
            double[] p = pairs.get(pairFrom + "->" + key);
            if (p != null) {
                mean = shrunk(p, mean);
                n = p[1];
            }
        }
        return new double[]{mean, n};
    }

    // ------------------------------------------------------------------ the mutation's choices

    /**
     * The action inserted at {@code position}, the child's {@code index}-th insertion: uniform, ε-greedy as the
     * engine's, or Thompson-sampled, as the child's operator says; candidates under a tabu are left out while
     * others remain.
     */
    private A insert(S state, List<A> available, int position, int index, A removed, String pairFrom) {
        List<A> cands = tabuFilter(state, available, position);
        if (pairFrom != null) {
            biasGreedyPicks++;
            return thompsonPick(state, cands, removed, pairFrom);
        }
        switch (op.insertion) {
            case EPSILON:
                if (q.epsilon >= 1.0 || rnd.nextDouble() < q.epsilon) {
                    biasUniformPicks++;
                    return cands.get(rnd.nextInt(cands.size()));
                }
                biasGreedyPicks++;
                return greedyPick(state, cands);
            case THOMPSON: {
                boolean biased = op.oneOfThem ? index == 0 : op.share >= 1.0 || rnd.nextDouble() < op.share;
                if (biased) {
                    biasGreedyPicks++;
                    return thompsonPick(state, cands, removed, null);
                }
                biasUniformPicks++;
                return cands.get(rnd.nextInt(cands.size()));
            }
            default:
                biasUniformPicks++;
                return cands.get(rnd.nextInt(cands.size()));
        }
    }

    /** The engine's greedy insertion: the best estimate at {@code insertKeys}, ties broken by a reservoir draw. */
    private A greedyPick(S state, List<A> cands) {
        A best = null;
        double bestQ = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        try (Meter.Span l = LOOKUP.span(cands.size())) {
            for (A c : cands) {
                double v = qhat(q.insertKeys, c, state);
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

    /**
     * Thompson sampling over the candidates' classes: one draw per distinct class (in the candidates' order), from a
     * normal posterior with its shrunk mean and a spread of the turn's (robust) standard deviation over sqrt(n + m);
     * the class with the highest draw, then one of its candidates uniformly.
     */
    private A thompsonPick(S state, List<A> cands, A removed, String pairFrom) {
        try (Meter.Span l = LOOKUP.span(cands.size())) {
            String removedKey = q.substitutions && removed != null ? game.actionClass(state, removed, q.insertKeys) : null;
            String[] keys = new String[cands.size()];
            Map<String, Double> draws = new HashMap<>();
            String bestKey = null;
            double bestDraw = Double.NEGATIVE_INFINITY, sd = deltaScale.sd();
            for (int i = 0; i < cands.size(); i++) {
                keys[i] = game.actionClass(state, cands.get(i), q.insertKeys);
                if (draws.containsKey(keys[i])) continue;
                double[] post = posterior(keys[i], removedKey, pairFrom);
                double draw = post[0] + sd / StrictMath.sqrt(post[1] + q.shrinkageM) * rnd.nextGaussian();
                draws.put(keys[i], draw);
                if (draw > bestDraw) {
                    bestDraw = draw;
                    bestKey = keys[i];
                }
            }
            A pick = null;
            int seen = 0;
            for (int i = 0; i < cands.size(); i++)
                if (keys[i].equals(bestKey) && rnd.nextInt(++seen) == 0) pick = cands.get(i);
            return pick;
        }
    }

    /** Thompson sampling over positions: each position's shrunk mean change, with the same posterior spread. */
    private int thompsonSite(int length) {
        try (Meter.Span l = LOOKUP.span(length)) {
            double prior = siteN > 0 ? siteSum / siteN : 0.0, sd = deltaScale.sd();
            int best = 0;
            double bestDraw = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < length; p++) {
                double[] e = siteStats[Math.min(p, POSITIONS - 1)];
                double draw = shrunk(e, prior) + sd / StrictMath.sqrt(e[1] + q.shrinkageM) * rnd.nextGaussian();
                if (draw > bestDraw) {
                    bestDraw = draw;
                    best = p;
                }
            }
            return best;
        }
    }

    /** The candidates not under a tabu at this position; all of them if every one is. */
    private List<A> tabuFilter(S state, List<A> available, int position) {
        if (q.tabuGenerations == 0) return available;
        long now = generation();
        List<A> ok = new ArrayList<>(available.size());
        try (Meter.Span l = LOOKUP.span(available.size())) {
            for (A a : available) {
                Long until = tabu.get(position + "|" + game.actionClass(state, a, Granularity.MID));
                if (until == null || until <= now) ok.add(a);
                else tabuBlocked++;
            }
        }
        return ok.isEmpty() ? available : ok;
    }

    /** The adaptive operator selection's draw: each operator's success rate, a normal posterior around it. */
    private int drawOperator() {
        try (Meter.Span l = LOOKUP.span(AOS_OPS.length)) {
            int best = 0;
            double bestDraw = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < AOS_OPS.length; i++) {
                double n = aos[i][1], p = (aos[i][0] + 1) / (n + 2);
                double draw = p + StrictMath.sqrt(p * (1 - p) / (n + 3)) * rnd.nextGaussian();
                if (draw > bestDraw) {
                    bestDraw = draw;
                    best = i;
                }
            }
            return best;
        }
    }

    /**
     * Several mutations at once: {@code k} positions (the first by {@link #chooseSite}, the rest uniform and
     * distinct; a learned pair's two adjacent), each given a new action, in one pass along the plan with the
     * repair: about the forward-model calls of one mutation, plus a legal-action query per extra mutation.
     */
    private Node<S, A> multiMutate(Node<S, A> node, S state, int k) {
        ArrayList<A> seq = node.sequence();
        int length = seq.size();
        int first = chooseSite(seq);
        boolean[] site = new boolean[length];
        site[first] = true;
        if (blockChild) {
            if (first + 1 < length) site[first + 1] = true;
        } else {
            for (int placed = 1, want = Math.min(k, length); placed < want; ) {
                int p = rnd.nextInt(length);
                if (!site[p]) {
                    site[p] = true;
                    placed++;
                }
            }
        }
        pendClear();
        pendOp = nextOp;
        pendPosition = length > 1 ? (double) first / (length - 1) : 0.0;
        double u0 = Meter.units();
        long e0 = Meter.evals();
        try {
            ArrayList<A> out = new ArrayList<>();
            lastRepairKept = 0;
            lastRepairReplaced = 0;
            int index = 0;
            String lastInsertedKey = null;
            for (int i = 0; i < length; i++) {
                if (game.activePlayer(state) != player) break;
                if (spent()) return null;
                if (site[i]) {
                    List<A> available = legalActions(state);
                    if (available.isEmpty()) break;
                    A removed = seq.get(i);
                    String pairFrom = blockChild && index == 1 ? lastInsertedKey : null;
                    A inserted = insert(state, available, i, index, removed, pairFrom);
                    record(state, inserted, removed, i, out.isEmpty() ? null : out.get(out.size() - 1));
                    if (blockChild && index == 0) {
                        try (Meter.Span c = CLASSIFY.span()) {
                            lastInsertedKey = game.actionClass(state, inserted, q.insertKeys);
                        }
                    }
                    advance(state, inserted);
                    out.add(inserted);
                    index++;
                    continue;
                }
                A a = seq.get(i);
                boolean kept;
                try {
                    kept = isLegal(state, a);
                } catch (RuntimeException e) {
                    kept = false;
                }
                if (kept) {
                    out.add(a);
                    try {
                        advance(state, a);
                        lastRepairKept++;
                    } catch (RuntimeException e) {
                        out.remove(out.size() - 1);
                        kept = false;
                    }
                }
                if (!kept) {
                    A best = replace(state, a, length > 1 ? (double) i / (length - 1) : 0.0);
                    if (best == null) break;
                    advance(state, best);
                    out.add(best);
                    lastRepairReplaced++;
                }
            }
            if (!out.isEmpty() && !game.endsTurn(out.get(out.size() - 1))) {
                A end = game.endTurnAction(state);
                if (end != null) out.add(end);
            }
            out = truncateAtEndTurn(out);
            if (spent()) return null;
            return adopt(node, out);
        } finally {
            repairUnits += Meter.units() - u0;
            repairCandidateEvals += Meter.evals() - e0;
        }
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
     * {@link Emcts#repair}'s structure with the replacement made by the learner: the thesis agent's own repair, kept
     * exactly, and stopping when the budget is spent (then the mutation abandons the child).
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
            A best = replace(state, child.get(i), child.size() > 1 ? (double) i / (child.size() - 1) : 0.0);
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

    /** The replacement of an action no longer legal, by the arm's repair rule. */
    private A replace(S state, A replaced, double position) {
        if (q.repairMode == QdeltaParams.RepairMode.SHORTLIST) return shortlistedReplace(state, replaced, position);
        return zeroEvalReplace(state, q.repairMode == QdeltaParams.RepairMode.ZERO_EVAL, position, replaced);
    }

    /** A candidate's estimate for the repair: the model's, the substitution table's, or the table's at repairKeys. */
    private double repairEstimate(A c, S state, double position, double progress, String replacedKey) {
        if (model != null) return model.estimate(classes(c, state), position, progress);
        if (replacedKey == null) return qhat(q.repairKeys, c, state);
        String key = game.actionClass(state, c, q.repairKeys);
        double own = shrunk(live.entry(q.repairKeys, key), live.globalMean());
        double[] s = subst.get(replacedKey + "->" + key);
        return s == null ? own : shrunk(s, own);
    }

    /**
     * The highest-estimate legal candidate, with no copy, no candidate advance and no evaluation; or, for the
     * control, an arbitrary one. Ties break uniformly, and are common: most candidates are unseen and sit at the
     * prior, which is why the granularity matters.
     */
    private A zeroEvalReplace(S state, boolean rankByTable, double position, A replaced) {
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
                String replacedKey = q.substitutions && replaced != null ? game.actionClass(state, replaced, q.repairKeys) : null;
                for (A c : cands) {
                    double v = repairEstimate(c, state, position, progress, replacedKey);
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

    /**
     * The engine's shortlisted repair: the candidates ranked by the learner, with a jitter far below any real
     * change drawn for each to break ties; the best {@code shortlistK} copied, advanced and evaluated; the best of
     * them taken, ties broken by a reservoir draw. With no more candidates than the shortlist, every one is
     * evaluated, with no jitter drawn.
     */
    private A shortlistedReplace(S state, A replaced, double position) {
        List<A> pool;
        try (Meter.Span sl = SHORTLIST.span()) {
            List<A> cands = legalActions(state);
            if (cands.isEmpty()) return null;
            pool = cands;
            if (cands.size() > q.shortlistK) {
                shortlistCalls++;
                shortlistSkipped += cands.size() - q.shortlistK;
                int n = cands.size();
                double[] score = new double[n];
                try (Meter.Span l = LOOKUP.span(n)) {
                    double progress = model != null ? progress() : 0.0;
                    String replacedKey = q.substitutions && replaced != null ? game.actionClass(state, replaced, q.repairKeys) : null;
                    for (int i = 0; i < n; i++) {
                        double jitter = rnd.nextDouble();
                        score[i] = repairEstimate(cands.get(i), state, position, progress, replacedKey) + jitter * 1e-9;
                    }
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
        }
        A bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (A candidate : pool) {
            if (spent()) return null;                   // the repair stops, and mutate abandons the child
            S test = game.copy(state);
            advance(test, candidate);
            double s = game.evaluate(test, player);
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
