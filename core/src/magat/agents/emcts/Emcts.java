package magat.agents.emcts;

import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Evolutionary MCTS for multi-action turns, written against the {@link Game} contract.
 *
 * <p>The search: a greedy 1-ply initial plan; then, in {@code phases} equal cumulative slices of the
 * budget, generations of {@code k} mutate-and-repair children of the incumbent, scored by the
 * heuristic; a selection rule decides where the incumbent moves; at every phase boundary but the
 * last a bridge burn promotes the root child on the incumbent's path and prunes every other branch.
 * The answer is the best plan ever scored, which survives pruning.
 *
 * <p>This is a line-for-line port of the thesis tree's {@code ExperimentalEMCTSAgent} with its
 * crossover paths left behind: every forward-model call and every random draw happens in the same
 * order, which the equivalence gate asserts against that class. Two initialization copies that class
 * makes and never reads (its {@code old_gs} copy and the copy handed to the root's evaluation) are
 * not made here; the oracle declares them.
 *
 * <p><b>Its own operations</b> (DESIGN §6.6), recorded through the meter beside the forward model's:
 * {@code emcts.generation}, one generation's own work (choosing the node, building and repairing the
 * children's plans, selection), timed without the operations nested in it; {@code emcts.legal}, one
 * legal-action query; {@code emcts.check}, one legality check of a planned action during repair;
 * {@code emcts.seqkey}, one plan key built and looked up or stored for the uniqueness count; and
 * {@code emcts.burn}, one bridge burn (re-rooting and pruning). A version-1 currency prices none of
 * them, so under it they change nothing: they are observation, like the telemetry.
 */
public class Emcts<S, A> implements SearchAgent<S, A> {

    /** The operations every EMCTS-family arm records, in the order its rows carry them. */
    public static final List<String> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            "emcts.generation", "emcts.legal", "emcts.check", "emcts.seqkey", "emcts.burn"));
    protected static final Meter.Op GENERATION = Meter.register("emcts.generation");
    protected static final Meter.Op LEGAL = Meter.register("emcts.legal");
    protected static final Meter.Op CHECK = Meter.register("emcts.check");
    protected static final Meter.Op SEQKEY = Meter.register("emcts.seqkey");
    protected static final Meter.Op BURN = Meter.register("emcts.burn");

    protected final Random rnd;
    protected final EmctsParams params;

    protected Game<S, A> game;
    protected int player;

    protected Node<S, A> root;
    protected Node<S, A> bestNode;
    protected Node<S, A> incumbent;
    protected Node<S, A> lastCreatedChild;
    protected int maxChildrenOnANode;

    protected long fmCalls;
    protected long nodesEvaluated;
    protected double sumNodeValue;
    protected final HashSet<String> uniqueSeqKeys = new HashSet<>();

    // Operator telemetry: observation only, no forward-model calls, no random draws.
    protected long mutEvents, mutBestGains, mutChildDuplicates, mutRepairKept, mutRepairReplaced;
    protected double mutBestGainSum;
    protected int lastRepairKept, lastRepairReplaced;

    public Emcts(long seed, EmctsParams params) {
        this.rnd = new Random(seed);
        this.params = params;
        if (params.childrenPerGeneration < 1)
            throw new IllegalArgumentException("childrenPerGeneration must be >= 1 (got "
                    + params.childrenPerGeneration + ")");
        if (params.phases < 1) throw new IllegalArgumentException("phases must be >= 1");
    }

    // ------------------------------------------------------------------ the turn

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        this.game = game;
        this.player = player;
        long turnStart = System.currentTimeMillis();
        fmCalls = 0; nodesEvaluated = 0; sumNodeValue = 0.0; uniqueSeqKeys.clear();
        mutEvents = mutBestGains = mutChildDuplicates = mutRepairKept = mutRepairReplaced = 0;
        mutBestGainSum = 0.0;
        TurnRecord.Builder record = TurnRecord.builder();

        // Greedy 1-ply initialization, on its own copy; its cost is measured, then excluded.
        root = initialPlan(game.copy(state));
        eval(null, root);
        double initialValue = root.value();
        long initMillis = System.currentTimeMillis() - turnStart;
        budget.beginSearch();

        bestNode = root;
        incumbent = root;
        final int phases = params.phases;
        final int maxItersPerPhase = 1_000_000;
        int numIters = 0;

        for (int phase = 0; phase < phases; phase++) {
            double ceiling = budget.ceiling(phase, phases);
            int phaseStartIters = numIters;

            while (true) {
                boolean ceilingReached = Meter.units() >= ceiling;
                boolean needChildForBurn = (phase < phases - 1) && root.children().isEmpty();
                if (ceilingReached && !needChildForBurn) break;
                if ((numIters - phaseStartIters) >= maxItersPerPhase) break;

                numIters++;
                try (Meter.Span g = GENERATION.span()) {
                    Node<S, A> toMutate = nodeToExpand();
                    final int k = Math.max(1, params.childrenPerGeneration);
                    Node<S, A> bestOfGeneration = null;
                    Node<S, A> randomOfGeneration = null;
                    for (int childIdx = 0; childIdx < k; childIdx++) {
                        if (childIdx > 0) numIters++;
                        Node<S, A> born = expandOnce(toMutate, state);
                        lastCreatedChild = born;
                        if (bestOfGeneration == null || born.value() > bestOfGeneration.value())
                            bestOfGeneration = born;
                        // Reservoir pick, drawn for every k > 1 mode so all multi-child arms consume one
                        // identical random stream; never drawn at k = 1, which keeps the one-child search
                        // bit-identical to the thesis tree.
                        if (k == 1) randomOfGeneration = born;
                        else if (rnd.nextInt(childIdx + 1) == 0) randomOfGeneration = born;
                    }
                    adoptAfterGeneration(bestOfGeneration, randomOfGeneration);
                }
            }

            // Bridge burn at every phase boundary except the last.
            if (phase < phases - 1 && root.children().size() > 0) {
                try (Meter.Span b = BURN.span()) {
                    Node<S, A> newRoot = standardBurnTarget();
                    if (newRoot != null && newRoot != root) {
                        for (Node<S, A> child : root.children()) {
                            if (child != newRoot) unlinkTree(child, newRoot);
                        }
                        newRoot.disconnectParent();
                        root = newRoot;
                        if (!isInLiveTree(incumbent)) incumbent = subtreeMax(root);
                        record.checkpoint(phase + 1, bestNode.value(), fmCalls, Meter.units(),
                                System.currentTimeMillis() - turnStart);
                        onBridgeBurn(phase + 1);
                    }
                }
            }
        }

        double finalValue = bestNode.value();
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        boolean terminal = bestNode.state() != null && game.isTerminal(bestNode.state());
        record
                .values(initialValue, finalValue, terminal, avgNodeValue)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, initMillis)
                .planMillis(System.currentTimeMillis() - turnStart)
                .diversity(nodesEvaluated, uniqueSeqKeys.size(), mutChildDuplicates)
                .sequenceLength(bestNode.sequence() == null ? 0 : bestNode.sequence().size())
                .telemetry("mutEvents", mutEvents)
                .telemetry("mutBestGains", mutBestGains)
                .telemetry("mutBestGainSum", mutBestGainSum)
                .telemetry("mutRepairKept", mutRepairKept)
                .telemetry("mutRepairReplaced", mutRepairReplaced)
                .telemetry("maxBranching", maxChildrenOnANode)
                .telemetry("iterations", numIters);
        moreTelemetry(record);
        return record.build();
    }

    /**
     * Called after every bridge burn that happened, at the point the thesis tree's agent called
     * {@code recordBridgeBurn}: after the prune and the incumbent's re-anchoring. Must not touch the
     * forward model or the random stream.
     */
    protected void onBridgeBurn(int phaseNumber) {
    }

    /** Adds a subclass's telemetry to the turn's record. Observation only. */
    protected void moreTelemetry(TurnRecord.Builder record) {
    }

    /** The plan the search settled on, for callers that replay it. */
    public ArrayList<A> bestSequence() {
        return bestNode == null ? new ArrayList<>() : new ArrayList<>(bestNode.sequence());
    }

    public int maxBranching() {
        return maxChildrenOnANode;
    }

    // ------------------------------------------------------------------ one generation

    /** One child: mutate-and-repair the expanded node, evaluate, fold into the telemetry. */
    protected Node<S, A> expandOnce(Node<S, A> toMutate, S state) {
        Node<S, A> child = mutate(toMutate, game.copy(state));
        mutEvents++;
        mutRepairKept += lastRepairKept;
        mutRepairReplaced += lastRepairReplaced;

        boolean childDuplicate;
        try (Meter.Span key = SEQKEY.span()) {
            childDuplicate = uniqueSeqKeys.contains(seqKey(child.sequence()));
        }
        double prevBest = bestNode.value();
        eval(game.copy(state), child);
        if (childDuplicate) mutChildDuplicates++;

        if (child.value() > prevBest) {
            mutBestGains++;
            mutBestGainSum += child.value() - prevBest;
            bestNode = child;
        }
        child.visited();
        return child;
    }

    /** The selection rule, applied once per generation: the only line the policies differ in. */
    protected void adoptAfterGeneration(Node<S, A> bestOfGeneration, Node<S, A> randomOfGeneration) {
        if (bestOfGeneration == null) return;
        switch (params.selection) {
            case COMMA:
                incumbent = bestOfGeneration;
                break;
            case COMMA_RAND:
                incumbent = randomOfGeneration != null ? randomOfGeneration : bestOfGeneration;
                break;
            case DRIFT:
                incumbent = lastCreatedChild;
                break;
            case LEGACY:
                incumbent = legacyDescendToLeaf();
                break;
            case PLUS:
            default:
                if (bestOfGeneration.value() > incumbent.value()) incumbent = bestOfGeneration;
                break;
        }
    }

    protected Node<S, A> legacyDescendToLeaf() {
        Node<S, A> node = root;
        while (node.children().size() > 0) {
            Node<S, A> best = null;
            for (Node<S, A> c : node.children())
                if (best == null || c.score() > best.score()) best = c;
            node = best;
        }
        return node;
    }

    protected Node<S, A> nodeToExpand() {
        return incumbent != null ? incumbent : root;
    }

    // ------------------------------------------------------------------ the tree

    protected Node<S, A> standardBurnTarget() {
        Node<S, A> onIncumbentPath = childOfRootOnPathTo(incumbent);
        return onIncumbentPath != null ? onIncumbentPath : findBestChild(root);
    }

    protected Node<S, A> childOfRootOnPathTo(Node<S, A> node) {
        Node<S, A> current = node;
        while (current != null && current.parent() != root) current = current.parent();
        return current;
    }

    protected boolean isInLiveTree(Node<S, A> node) {
        for (Node<S, A> current = node; current != null; current = current.parent())
            if (current == root) return true;
        return false;
    }

    protected Node<S, A> subtreeMax(Node<S, A> node) {
        Node<S, A> best = node;
        ArrayDeque<Node<S, A>> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Node<S, A> current = pending.pop();
            if (current.value() > best.value()) best = current;
            for (Node<S, A> c : current.children()) if (c != null) pending.push(c);
        }
        return best;
    }

    protected Node<S, A> findBestChild(Node<S, A> node) {
        ArrayList<Node<S, A>> children = node.children();
        if (children.isEmpty()) return null;
        Node<S, A> bestChild = children.get(0);
        double bestValue = bestChild.value();
        for (Node<S, A> child : children) {
            if (child.value() > bestValue) {
                bestValue = child.value();
                bestChild = child;
            }
        }
        return bestChild;
    }

    /** Iterative, so the depth of a large tree cannot overflow the stack; skips the protected subtree. */
    protected void unlinkTree(Node<S, A> node, Node<S, A> protectedNode) {
        if (node == null || node == protectedNode) return;
        ArrayDeque<Node<S, A>> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Node<S, A> current = pending.pop();
            for (Node<S, A> child : current.children())
                if (child != null && child != protectedNode) pending.push(child);
            current.disconnectParent();
        }
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * Scores a node. A node without a state is reached by replaying its plan on the state given
     * (which the node then keeps); a node with a state is re-scored from it.
     */
    protected void eval(S state, Node<S, A> node) {
        if (node.state() == null) {
            for (A move : node.sequence()) advance(state, move);
            node.setState(state);
            node.setValue(game.evaluate(state, player));
        } else {
            node.setValue(game.evaluate(node.state(), player));
        }
        node.refreshScore(params.bias);
        nodesEvaluated++;
        sumNodeValue += node.value();
        try (Meter.Span key = SEQKEY.span()) {
            uniqueSeqKeys.add(seqKey(node.sequence()));
        }
    }

    protected String seqKey(List<A> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (A a : seq) sb.append(a == null ? "_" : game.actionKey(a)).append('|');
        return sb.toString();
    }

    protected void advance(S state, A move) {
        fmCalls++;
        game.advance(state, move);
    }

    /** The legal actions, as one {@code emcts.legal} operation. */
    protected List<A> legalActions(S state) {
        try (Meter.Span q = LEGAL.span()) {
            return game.legalActions(state);
        }
    }

    /** Whether a planned action is legal, as one {@code emcts.check} operation. */
    protected boolean isLegal(S state, A action) {
        try (Meter.Span c = CHECK.span()) {
            return game.isLegal(state, action);
        }
    }

    // ------------------------------------------------------------------ operators

    /** The greedy 1-ply initial plan; the state given is consumed and kept by the root node. */
    protected Node<S, A> initialPlan(S state) {
        ArrayList<A> individual = new ArrayList<>();

        while (!game.isTerminal(state) && game.activePlayer(state) == player
                && individual.size() < (params.nodeSize - 1)) {
            List<A> available = legalActions(state);
            if (available.isEmpty()) break;

            A bestAction = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            int numTies = 0;

            if (params.randomInitialSequence) {
                ArrayList<A> picks = new ArrayList<>();
                for (A a : available) {
                    if (!game.endsTurn(a) || available.size() == 1) picks.add(a);
                }
                if (!picks.isEmpty()) bestAction = picks.get(rnd.nextInt(picks.size()));
            } else {
                for (A candidate : available) {
                    if (game.endsTurn(candidate) && available.size() > 1) continue;
                    S test = game.copy(state);
                    advance(test, candidate);
                    double score = game.evaluate(test, player);
                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidate;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (rnd.nextInt(numTies) == 0) bestAction = candidate;
                    }
                }
            }

            if (bestAction != null) {
                advance(state, bestAction);
                individual.add(bestAction);
                if (game.endsTurn(bestAction)) break;
            } else {
                break;
            }
        }

        if (!individual.isEmpty() && !game.endsTurn(individual.get(individual.size() - 1))) {
            A end = game.endTurnAction(state);
            if (end != null) individual.add(end);
        }

        Node<S, A> node = new Node<>(individual, null);
        node.setState(game.copy(state));
        return node;
    }

    /** A plan must end at the first end-of-turn action. */
    protected ArrayList<A> truncateAtEndTurn(ArrayList<A> seq) {
        ArrayList<A> out = new ArrayList<>();
        for (A a : seq) {
            out.add(a);
            if (a != null && game.endsTurn(a)) break;
        }
        return out;
    }

    /** Mutation: replace one action of the plan by a uniformly random legal one, then repair. */
    protected Node<S, A> mutate(Node<S, A> node, S state) {
        S clone = game.copy(state);
        ArrayList<A> seq = node.sequence();
        int moveToMutate = seq.isEmpty() ? 0 : rnd.nextInt(seq.size());
        ArrayList<A> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            advance(state, seq.get(i));
            newSeq.add(seq.get(i));
        }
        List<A> available = legalActions(state);
        if (!available.isEmpty()) {
            newSeq.add(available.get(rnd.nextInt(available.size())));
        }
        for (int i = moveToMutate + 1; i < seq.size(); i++) newSeq.add(seq.get(i));

        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        Node<S, A> newNode = new Node<>(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.children().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;
        return newNode;
    }

    /**
     * Greedy repair: every action still legal is applied; an action that is not (or that throws
     * when applied) is replaced by the legal action with the best 1-ply value. The structure,
     * including what happens to a kept action that throws, is the thesis tree's exactly.
     */
    protected ArrayList<A> repair(ArrayList<A> child, S state) {
        ArrayList<A> repaired = new ArrayList<>();
        lastRepairKept = 0;
        lastRepairReplaced = 0;

        for (int i = 0; i < child.size(); i++) {
            if (game.activePlayer(state) != player) return repaired;
            try {
                boolean done = isLegal(state, child.get(i));
                if (!done) {
                    A best = greedyReplacement(state);
                    if (best != null) {
                        advance(state, best);
                        repaired.add(best);
                        lastRepairReplaced++;
                    } else {
                        break;
                    }
                } else {
                    repaired.add(child.get(i));
                    advance(state, child.get(i));
                    lastRepairKept++;
                }
            } catch (RuntimeException e) {
                A best = greedyReplacement(state);
                if (best != null) {
                    advance(state, best);
                    repaired.add(best);
                    lastRepairReplaced++;
                } else {
                    break;
                }
            }
        }

        if (!repaired.isEmpty() && !game.endsTurn(repaired.get(repaired.size() - 1))) {
            A end = game.endTurnAction(state);
            if (end != null) repaired.add(end);
        }
        return repaired;
    }

    /** The legal action with the best 1-ply value, ties broken by a reservoir draw. */
    protected A greedyReplacement(S state) {
        List<A> available = legalActions(state);
        A bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (A candidate : available) {
            S test = game.copy(state);
            advance(test, candidate);
            double score = game.evaluate(test, player);
            if (score > bestScore) {
                bestScore = score;
                bestAction = candidate;
                numTies = 1;
            } else if (score == bestScore) {
                numTies++;
                if (rnd.nextInt(numTies) == 0) bestAction = candidate;
            }
        }
        return bestAction;
    }
}
