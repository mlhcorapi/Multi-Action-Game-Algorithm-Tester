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
 * budget, generations of mutate-and-repair children of the incumbent, scored by the heuristic. Under (1+1)
 * selection, plain EMCTS, a generation is one child, which becomes the incumbent if it beats it. Under
 * (1,k) comma selection a generation is k children of the incumbent, and the best of them becomes the
 * incumbent, better or not ({@link Selection}). At every phase boundary but the last a bridge burn
 * promotes the root child on the incumbent's path and prunes every other branch. The answer is the best
 * plan ever scored, which survives pruning.
 *
 * <p>It stops when its budget is spent, its initialization included. It checks the budget before every
 * candidate a greedy step tries (in the initial plan and in a repair), before every action a child
 * replays or keeps, and before a child is scored; the step or the child the budget runs out in is
 * abandoned, and with it the rest of its generation, and the turn plays the best plan scored by then. A turn passes its budget by at most the one
 * action it was trying. A generation can cost thousands of units on a large position, so finishing it would
 * let a turn run far past a small budget.
 *
 * <p>This is a line-for-line port of the engine fork's {@code ExperimentalEMCTSAgent} with (1+1) or (1,k)
 * selection: every forward-model call and every random draw happens in the same order, which the
 * equivalence gate asserts against that class under that class's accounting
 * ({@link TurnBudget.Initialization#OUTSIDE_BUDGET}, which only the gate uses): its initialization is
 * outside the budget and never cut short, and every bridge burn gets a child, past the budget if need be.
 * That class makes two initialization copies it never reads (its {@code old_gs} copy and the copy handed
 * to the root's evaluation), and the port makes them too: each draws from the state's random generator,
 * and without them every later copy would be seeded otherwise and could meet another chance event.
 *
 * <p><b>Its own operations</b>, recorded through the meter beside the forward model's:
 * {@code emcts.generation}, one child's own work (building and repairing its plan, selection), timed
 * without the operations nested in it; {@code emcts.legal}, one legal-action query; {@code emcts.check},
 * one legality check of a planned action during repair; {@code emcts.seqkey}, one plan key built and
 * looked up or stored for the uniqueness count; and {@code emcts.burn}, one bridge burn (re-rooting and
 * pruning).
 */
public final class Emcts<S, A> implements SearchAgent<S, A> {

    /** The operations EMCTS records, in the order its rows carry them. */
    public static final List<String> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            "emcts.generation", "emcts.legal", "emcts.check", "emcts.seqkey", "emcts.burn"));
    private static final Meter.Op GENERATION = Meter.register("emcts.generation");
    private static final Meter.Op LEGAL = Meter.register("emcts.legal");
    private static final Meter.Op CHECK = Meter.register("emcts.check");
    private static final Meter.Op SEQKEY = Meter.register("emcts.seqkey");
    private static final Meter.Op BURN = Meter.register("emcts.burn");

    private final Random rnd;
    private final EmctsParams params;

    private Game<S, A> game;
    private int player;
    /** The budget the turn stops at; null under the engine's rule, which the equivalence gate plays. */
    private TurnBudget stopAt;

    private Node<S, A> root;
    private Node<S, A> bestNode;
    private Node<S, A> incumbent;
    private int maxChildrenOnANode;

    private long fmCalls;
    private long nodesEvaluated;
    private double sumNodeValue;
    private final HashSet<String> uniqueSeqKeys = new HashSet<>();

    // Operator telemetry: observation only, no forward-model calls, no random draws.
    private long mutEvents, mutBestGains, mutChildDuplicates, mutRepairKept, mutRepairReplaced;
    private double mutBestGainSum;
    private int lastRepairKept, lastRepairReplaced;

    public Emcts(long seed, EmctsParams params) {
        this.rnd = new Random(seed);
        this.params = params;
        if (params.phases < 1) throw new IllegalArgumentException("phases must be >= 1");
        if (params.childrenPerGeneration < 1)
            throw new IllegalArgumentException("childrenPerGeneration must be >= 1 (got " + params.childrenPerGeneration + ")");
        if (params.selection == Selection.PLUS && params.childrenPerGeneration != 1)
            throw new IllegalArgumentException("(1+1) selection makes one child per generation (got " + params.childrenPerGeneration + ")");
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

        // The stop rule: the turn stops when its budget is spent (spent()). The engine's agent never cuts its
        // initialization short and gives every bridge burn a child, past the budget if need be; the equivalence
        // gate plays the port by the engine's accounting (OUTSIDE_BUDGET), and so by that rule.
        stopAt = budget.initializationPolicy() == TurnBudget.Initialization.IN_BUDGET ? budget : null;

        // Greedy 1-ply initialization, on its own copy; the search gets what it leaves of the budget. The
        // engine's agent copies the state once before it (its old_gs) and once for the root's evaluation, which
        // re-scores the root from the root's own state; the port makes both, for the draws they take.
        game.copy(state);
        root = initialPlan(game.copy(state));
        eval(game.copy(state), root);
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
                if (spent()) break;                              // no child past the budget, not even for a burn
                if ((numIters - phaseStartIters) >= maxItersPerPhase) break;

                numIters++;
                boolean abandoned = false;
                try (Meter.Span g = GENERATION.span()) {
                    final int k = params.childrenPerGeneration;
                    Node<S, A> parent = incumbent, bestOfGeneration = null;
                    for (int childIdx = 0; childIdx < k; childIdx++) {
                        if (childIdx > 0) numIters++;
                        Node<S, A> born = expandOnce(parent, state);
                        if (born == null) {                      // the budget ran out in it: the generation ends here
                            abandoned = true;
                            break;
                        }
                        if (bestOfGeneration == null || born.value() > bestOfGeneration.value()) bestOfGeneration = born;
                        // The engine's reservoir pick over the generation, drawn for every child when k > 1 and never
                        // at k = 1: comma selection does not use it, but it takes its draw from the random stream.
                        if (k > 1) rnd.nextInt(childIdx + 1);
                    }
                    if (!abandoned && bestOfGeneration != null) {
                        if (params.selection == Selection.COMMA) incumbent = bestOfGeneration;
                        else if (bestOfGeneration.value() > incumbent.value()) incumbent = bestOfGeneration;
                    }
                }
                if (abandoned) break;
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
                    }
                }
            }
        }

        double finalValue = bestNode.value();
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        boolean terminal = bestNode.state() != null && game.isTerminal(bestNode.state());
        return record
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
                .telemetry("iterations", numIters)
                .build();
    }

    /** The plan the search settled on, for callers that replay it. */
    public ArrayList<A> bestSequence() {
        return bestNode == null ? new ArrayList<>() : new ArrayList<>(bestNode.sequence());
    }

    // ------------------------------------------------------------------ one child

    /** Whether the turn's budget is spent (the stop rule in the class comment); never under the engine's rule. */
    private boolean spent() {
        return stopAt != null && stopAt.exhausted();
    }

    /** One child: mutate-and-repair the expanded node, evaluate, fold into the telemetry; null when the budget ran out in it. */
    private Node<S, A> expandOnce(Node<S, A> toMutate, S state) {
        Node<S, A> child = mutate(toMutate, game.copy(state));
        if (child == null) return null;
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
        return child;
    }

    // ------------------------------------------------------------------ the tree

    private Node<S, A> standardBurnTarget() {
        Node<S, A> onIncumbentPath = childOfRootOnPathTo(incumbent);
        return onIncumbentPath != null ? onIncumbentPath : findBestChild(root);
    }

    private Node<S, A> childOfRootOnPathTo(Node<S, A> node) {
        Node<S, A> current = node;
        while (current != null && current.parent() != root) current = current.parent();
        return current;
    }

    private boolean isInLiveTree(Node<S, A> node) {
        for (Node<S, A> current = node; current != null; current = current.parent())
            if (current == root) return true;
        return false;
    }

    private Node<S, A> subtreeMax(Node<S, A> node) {
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

    private Node<S, A> findBestChild(Node<S, A> node) {
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
    private void unlinkTree(Node<S, A> node, Node<S, A> protectedNode) {
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
    private void eval(S state, Node<S, A> node) {
        if (node.state() == null) {
            for (A move : node.sequence()) advance(state, move);
            node.setState(state);
            node.setValue(game.evaluate(state, player));
        } else {
            node.setValue(game.evaluate(node.state(), player));
        }
        nodesEvaluated++;
        sumNodeValue += node.value();
        try (Meter.Span key = SEQKEY.span()) {
            uniqueSeqKeys.add(seqKey(node.sequence()));
        }
    }

    private String seqKey(List<A> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (A a : seq) sb.append(a == null ? "_" : game.actionKey(a)).append('|');
        return sb.toString();
    }

    private void advance(S state, A move) {
        fmCalls++;
        game.advance(state, move);
    }

    /** The legal actions, as one {@code emcts.legal} operation. */
    private List<A> legalActions(S state) {
        try (Meter.Span q = LEGAL.span()) {
            return game.legalActions(state);
        }
    }

    /** Whether a planned action is legal, as one {@code emcts.check} operation. */
    private boolean isLegal(S state, A action) {
        try (Meter.Span c = CHECK.span()) {
            return game.isLegal(state, action);
        }
    }

    // ------------------------------------------------------------------ operators

    /**
     * The greedy 1-ply initial plan; the state given is consumed and kept by the root node. When the budget
     * runs out, the step under way is abandoned and the plan ends the turn where it is.
     */
    private Node<S, A> initialPlan(S state) {
        ArrayList<A> individual = new ArrayList<>();

        while (!game.isTerminal(state) && game.activePlayer(state) == player
                && individual.size() < (params.nodeSize - 1) && !spent()) {
            List<A> available = legalActions(state);
            if (available.isEmpty()) break;

            A bestAction = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            int numTies = 0;
            for (A candidate : available) {
                if (game.endsTurn(candidate) && available.size() > 1) continue;
                if (spent()) {
                    bestAction = null;
                    break;
                }
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
    private ArrayList<A> truncateAtEndTurn(ArrayList<A> seq) {
        ArrayList<A> out = new ArrayList<>();
        for (A a : seq) {
            out.add(a);
            if (a != null && game.endsTurn(a)) break;
        }
        return out;
    }

    /** Mutation: replace one action of the plan by a uniformly random legal one, then repair. */
    private Node<S, A> mutate(Node<S, A> node, S state) {
        S clone = game.copy(state);
        ArrayList<A> seq = node.sequence();
        int moveToMutate = seq.isEmpty() ? 0 : rnd.nextInt(seq.size());
        ArrayList<A> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            if (spent()) return null;
            advance(state, seq.get(i));
            newSeq.add(seq.get(i));
        }
        List<A> available = legalActions(state);
        if (!available.isEmpty()) {
            newSeq.add(available.get(rnd.nextInt(available.size())));
        }
        for (int i = moveToMutate + 1; i < seq.size(); i++) newSeq.add(seq.get(i));

        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        if (spent()) return null;                       // the budget ran out: the child is never scored or kept
        Node<S, A> newNode = new Node<>(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.children().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;
        return newNode;
    }

    /**
     * Greedy repair: every action still legal is applied; an action that is not (or that throws
     * when applied) is replaced by the legal action with the best 1-ply value. The structure,
     * including what happens to a kept action that throws, is the engine agent's exactly.
     */
    private ArrayList<A> repair(ArrayList<A> child, S state) {
        ArrayList<A> repaired = new ArrayList<>();
        lastRepairKept = 0;
        lastRepairReplaced = 0;

        for (int i = 0; i < child.size(); i++) {
            if (game.activePlayer(state) != player) return repaired;
            if (spent()) return repaired;               // and mutate abandons the child
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

    /** The legal action with the best 1-ply value, ties broken by a reservoir draw; null when the budget runs out first. */
    private A greedyReplacement(S state) {
        List<A> available = legalActions(state);
        A bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (A candidate : available) {
            if (spent()) return null;                   // the repair stops, and mutate abandons the child
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
