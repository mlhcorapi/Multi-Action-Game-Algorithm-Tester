package magat.agents.mcts;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Monte Carlo tree search over the actions of the current turn, without rollouts: UCT (Kocsis and
 * Szepesvári 2006; Browne et al. 2012) with the game's heuristic in place of a simulation.
 *
 * <p>A node is a position partway through the turn, reached by the actions on the path to it. Each iteration
 * descends from the root by UCB1 while a node has no untried action, expands one untried action of the node it
 * stops at (drawn at random), evaluates the new node's position with the heuristic, and backs that value up
 * the path as a mean. UCB1's exploitation term is a child's mean normalised to the range of values seen so
 * far, and its exploration constant is √2. The end of turn is not one of the tree's actions, so every node's
 * value is that of a position within the turn. A node is a leaf of the tree when the game is over, the turn
 * has passed, the turn has no other action, or its plan is {@link MctsParams#maxActions} long. Once every
 * node has been expanded the search stops, whatever the budget left.
 *
 * <p>The turn played is the most-visited path from the root, then the end of turn. Its value is that of the
 * position the whole plan reaches, the end of turn included, as every arm's is; replaying the plan to find it
 * is the turn's last work. The root's evaluation is the initialization.
 *
 * <p>A node keeps its position only once the search has expanded it. A new node's position is evaluated and
 * dropped, then rebuilt from its parent's (a copy and an advance) the first time the search expands that
 * node, so a chance event in its action may come out differently. The memory a tree holds grows with the
 * nodes it has expanded, a small share of those it has evaluated.
 *
 * <p><b>Its own operations</b>, recorded through the meter beside the forward model's: {@code mcts.iteration},
 * one iteration's own work (the descent, the expansion's bookkeeping and the back-up), timed without the
 * operations nested in it; and {@code mcts.legal}, one legal-action query.
 */
public final class Mcts<S, A> implements SearchAgent<S, A> {

    /** The operations MCTS records, in the order its rows carry them. */
    public static final List<String> OPERATIONS = Collections.unmodifiableList(Arrays.asList("mcts.iteration", "mcts.legal"));
    private static final Meter.Op ITERATION = Meter.register("mcts.iteration");
    private static final Meter.Op LEGAL = Meter.register("mcts.legal");

    /** A position partway through the turn: the actions to it, its statistics, and its position once expanded. */
    private static final class Node<S, A> {
        final A action;
        final Node<S, A> parent;
        final int depth;
        final List<Node<S, A>> children = new ArrayList<>();
        /** The actions not expanded yet; null until the search first expands this node. */
        List<A> untried;
        /** The position, held from the node's first expansion on (the root's always). */
        S state;
        /** The heuristic's value of the node's position when it was created. */
        double value;
        int visits;
        double total;
        /** Nothing below this node can be expanded any more. */
        boolean complete;

        Node(A action, Node<S, A> parent) {
            this.action = action;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
        }

        double mean() {
            return total / visits;
        }
    }

    private final Random rnd;
    private final MctsParams params;

    private Game<S, A> game;
    private int player;
    private double lo, hi;
    private long nodes, expanded, iterations;
    private int maxDepth;
    private double sumNodeValue;
    private List<A> plan = new ArrayList<>();

    public Mcts(long seed, MctsParams params) {
        this.rnd = new Random(seed);
        this.params = params;
        if (params.maxActions < 1) throw new IllegalArgumentException("maxActions must be >= 1");
    }

    // ------------------------------------------------------------------ the turn

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        this.game = game;
        this.player = player;
        long t0 = System.currentTimeMillis();
        Node<S, A> root = new Node<>(null, null);
        root.state = state;
        double initial = game.evaluate(state, player);
        long initMillis = System.currentTimeMillis() - t0;
        budget.beginSearch();                         // the root's evaluation is the initialization, paid from the budget

        root.visits = 1;
        root.total = initial;
        lo = hi = initial;
        root.complete = over(state);
        while (!root.complete && !budget.exhausted()) {
            iterations++;
            try (Meter.Span it = ITERATION.span()) {
                iterate(root);
            }
        }

        // The most-visited path, then the end of turn, played on the root's own position.
        plan = new ArrayList<>();
        for (Node<S, A> n = mostVisited(root); n != null; n = mostVisited(n)) {
            game.advance(state, n.action);
            plan.add(n.action);
        }
        if (!over(state)) {
            A end = endTurn(state);
            if (end != null) {
                game.advance(state, end);
                plan.add(end);
            }
        }
        double fin = game.evaluate(state, player);
        return TurnRecord.builder()
                .values(initial, fin, game.isTerminal(state), nodes > 0 ? sumNodeValue / nodes : initial)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, initMillis)
                .planMillis(System.currentTimeMillis() - t0)
                .diversity(nodes, nodes, 0)
                .sequenceLength(plan.size())
                .telemetry("iterations", iterations)
                .telemetry("expanded", expanded)
                .telemetry("maxDepth", maxDepth)
                .build();
    }

    /** The plan the turn played: the most-visited path, then the end of turn. */
    public List<A> bestSequence() {
        return new ArrayList<>(plan);
    }

    // ------------------------------------------------------------------ one iteration

    /** Descend while every action of a node has been tried, expand one untried action, back its value up. */
    private void iterate(Node<S, A> root) {
        Node<S, A> node = root;
        while (node.untried != null && node.untried.isEmpty()) node = select(node);
        if (node.untried == null && !open(node)) return;
        Node<S, A> child = expand(node);
        for (Node<S, A> n = child; n != null; n = n.parent) {
            n.visits++;
            n.total += child.value;
        }
    }

    /**
     * A node's first expansion: rebuild its position from its parent's (the root has its own) and list the
     * actions to try. False when the rebuilt position has none, which completes the node.
     */
    private boolean open(Node<S, A> node) {
        if (node.state == null) {
            node.state = game.copy(node.parent.state);
            game.advance(node.state, node.action);
        }
        List<A> actions = new ArrayList<>();
        if (!over(node.state)) {
            for (A a : legalActions(node.state)) if (!game.endsTurn(a)) actions.add(a);
        }
        node.untried = actions;
        if (!actions.isEmpty()) {
            expanded++;
            return true;
        }
        if (node.parent != null) node.state = null;   // a leaf after all, which nothing will expand
        settle(node);
        return false;
    }

    /** One untried action, drawn at random, as a new node: evaluated, and its position dropped. */
    private Node<S, A> expand(Node<S, A> node) {
        List<A> untried = node.untried;
        int i = rnd.nextInt(untried.size());
        A a = untried.get(i);
        untried.set(i, untried.get(untried.size() - 1));
        untried.remove(untried.size() - 1);

        S s = game.copy(node.state);
        game.advance(s, a);
        Node<S, A> child = new Node<>(a, node);
        child.value = game.evaluate(s, player);
        nodes++;
        sumNodeValue += child.value;
        if (child.value < lo) lo = child.value;
        if (child.value > hi) hi = child.value;
        if (child.depth > maxDepth) maxDepth = child.depth;
        child.complete = child.depth >= params.maxActions || over(s);
        node.children.add(child);
        if (child.complete) settle(node);
        return child;
    }

    /** UCB1 over the children that can still grow, on means normalised to the range seen; ties drawn at random. */
    private Node<S, A> select(Node<S, A> node) {
        double range = hi - lo;
        double logVisits = Math.log(node.visits);
        Node<S, A> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int ties = 0;
        for (Node<S, A> c : node.children) {
            if (c.complete) continue;
            double exploit = range > 0 ? (c.mean() - lo) / range : 0.0;
            double score = exploit + params.c * Math.sqrt(logVisits / c.visits);
            if (score > bestScore) {
                bestScore = score;
                best = c;
                ties = 1;
            } else if (score == bestScore && rnd.nextInt(++ties) == 0) {
                best = c;
            }
        }
        return best;
    }

    /** Completes a node with nothing left to try whose children are all complete, and so on up the path. */
    private static <S, A> void settle(Node<S, A> node) {
        for (Node<S, A> n = node; n != null && !n.complete; n = n.parent) {
            if (n.untried == null || !n.untried.isEmpty()) return;
            for (Node<S, A> c : n.children) if (!c.complete) return;
            n.complete = true;
        }
    }

    /** The child visited most, ties to the higher mean, then to the first; null for a node without children. */
    private static <S, A> Node<S, A> mostVisited(Node<S, A> node) {
        Node<S, A> best = null;
        for (Node<S, A> c : node.children)
            if (best == null || c.visits > best.visits || (c.visits == best.visits && c.mean() > best.mean())) best = c;
        return best;
    }

    // ------------------------------------------------------------------ the game

    /** The game is over, or the turn is no longer this player's. */
    private boolean over(S state) {
        return game.isTerminal(state) || game.activePlayer(state) != player;
    }

    /** The legal actions, as one {@code mcts.legal} operation. */
    private List<A> legalActions(S state) {
        try (Meter.Span q = LEGAL.span()) {
            return game.legalActions(state);
        }
    }

    /** The end-of-turn action, found by one {@code mcts.legal} operation. */
    private A endTurn(S state) {
        try (Meter.Span q = LEGAL.span()) {
            return game.endTurnAction(state);
        }
    }

    /** The registry entry. */
    public static <S, A> AgentFactory<S, A> factory() {
        Meter.declare(OPERATIONS);
        return new AgentFactory<S, A>() {
            @Override public String id() { return "mcts"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new Mcts<>(seed, new MctsParams()); }
            @Override public List<String> operations() { return OPERATIONS; }
            @Override public String description() {
                return "whole-turn UCT without rollouts: UCB1 (C = sqrt 2) on normalised means, one expansion per iteration "
                        + "evaluated by the heuristic, the most-visited path then the end of turn";
            }
        };
    }
}
