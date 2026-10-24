package magat.agents.tbets;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * TBETS, Turn-Based Evolutionary Tree Search: a tree search whose unit of search is a whole turn and whose
 * expansion operators are evolutionary, written against the {@link Game} contract.
 *
 * <p>Every node holds a complete turn, ending in the end of turn, and the state it leads to; its children are
 * candidate turns for whoever plays next. The search seeds the root with {@code numInit} generated turns, then,
 * each iteration, selects a node (descending by UCB while progressive widening says a node has enough children),
 * and either gives it {@code startingPop} generated turns, the first time, or evolves its children: with
 * probability {@code mutationRatio} it mutates one picked by tournament, otherwise it crosses two over. A
 * generated, mutated or crossed-over turn is played from the parent's state, an action that is no longer legal
 * replaced at random; it is evaluated by the heuristic, and its value is backed up as a minimax. The turn played
 * is the root's fittest child. At the default of one ply every child is a turn of the agent's, and the search is
 * an evolution of whole turns under tournament selection.
 *
 * <p>This is a port of the Tribes engine's {@code players.tbets.TBETSAgent} at its defaults, which was ported
 * from the C# implementation written for TUBSTAP ({@code AI/TBETS/AI_TBETS.cs} in the TUBSTAP_Taylor
 * repository). Every forward-model call and every random draw happens in the order the engine's agent makes
 * them, which the equivalence gate asserts against that class, so the port makes its two copies of the state
 * before the seeding as well. Not ported: the rollouts and the action-bias policies, which the defaults leave
 * off (the policies read Tribes' action types, which the contract does not show). Departing from the original,
 * the seeding stops when the budget runs out, which it never does at the gate's budgets or at a published
 * level. The tournament is the engine's: a plain tournament of {@code (tournamentSize + 1) / tournamentTopFraction - 1}
 * draws, which has the selection pressure of TUBSTAP's "sort, then draw from the top fraction" without the sort.
 *
 * <p>The seeding is the initialization, paid from the budget, as the engine's default charges it.
 *
 * <p><b>Its own operations</b>, recorded through the meter beside the forward model's: {@code tbets.turn}, one
 * candidate turn's own work (generating, mutating or crossing it over, its key, and adding it to the tree),
 * timed without the operations nested in it; {@code tbets.legal}, one legal-action query; {@code tbets.check},
 * one legality check of a turn's action as it is replayed; and {@code tbets.select}, one iteration's descent.
 */
public final class Tbets<S, A> implements SearchAgent<S, A> {

    /** The operations TBETS records, in the order its rows carry them. */
    public static final List<String> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            "tbets.turn", "tbets.legal", "tbets.check", "tbets.select"));
    private static final Meter.Op TURN = Meter.register("tbets.turn");
    private static final Meter.Op LEGAL = Meter.register("tbets.legal");
    private static final Meter.Op CHECK = Meter.register("tbets.check");
    private static final Meter.Op SELECT = Meter.register("tbets.select");

    private final Random rnd;
    private final TbetsParams params;
    /** The plain tournament's size; see the class comment. */
    private final int tournamentSize;

    private Game<S, A> game;
    private int player;
    private Node<S, A> root;
    private ArrayList<A> turnPlan = new ArrayList<>();

    private long nodesEvaluated, duplicates, uniformPicks;
    private double sumNodeValue;
    private final HashSet<String> uniqueSeqKeys = new HashSet<>();
    private int searchIterations;

    public Tbets(long seed, TbetsParams params) {
        this.rnd = new Random(seed);
        this.params = params;
        double frac = params.tournamentTopFraction > 0 ? params.tournamentTopFraction : 1.0;
        this.tournamentSize = Math.max(2, (int) Math.round((params.tournamentSize + 1) / frac) - 1);
        if (params.maxPlies < 1) throw new IllegalArgumentException("maxPlies must be >= 1");
    }

    // ------------------------------------------------------------------ the turn

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        this.game = game;
        this.player = player;
        long t0 = System.currentTimeMillis();

        // The engine's agent copies the state twice before seeding: the old-state argument of its heuristic,
        // which the contract's evaluation does not take, and the root's. Each copy draws from the state's
        // generator, so the port makes both, and its turns meet the chance events the engine's meet.
        game.copy(state);
        root = new Node<>(new ArrayList<>(), null, 0);
        root.setState(game.copy(state));

        // The seeding, paid from the budget.
        exploit(root, params.numInit, budget);
        double initialValue = bestChildValue(root);
        long initMillis = System.currentTimeMillis() - t0;
        budget.beginSearch();

        while (searchIterations < params.maxIterations) {
            if (budget.exhausted()) break;
            searchIterations++;

            Node<S, A> selected;
            try (Meter.Span s = SELECT.span()) {
                selected = selectNode();
            }
            if (selected == null) break;

            if (!selected.isExplored() && expandable(selected)) {
                exploit(selected, params.startingPop, null);
            } else if (!selected.getChildren().isEmpty()) {
                try (Meter.Span t = TURN.span()) {
                    evolve(selected);
                }
            } else {
                // Neither expandable nor evolvable: the tree cannot grow any further this turn.
                break;
            }
        }

        Node<S, A> best = bestChild(root);
        turnPlan = best != null && best.getSequence() != null ? new ArrayList<>(best.getSequence()) : new ArrayList<>();
        double finalValue = best != null ? best.getFitness() : initialValue;
        return TurnRecord.builder()
                .values(initialValue, finalValue, best != null && best.isLeaf(),
                        nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, initMillis)
                .planMillis(System.currentTimeMillis() - t0)
                .diversity(nodesEvaluated, uniqueSeqKeys.size(), duplicates)
                .sequenceLength(turnPlan.size())
                .telemetry("iterations", searchIterations)
                .telemetry("uniformPicks", uniformPicks)
                .telemetry("rootChildren", root.getChildren().size())
                .build();
    }

    /** The turn the search settled on, for callers that replay it. */
    public ArrayList<A> bestSequence() {
        return new ArrayList<>(turnPlan);
    }

    // ------------------------------------------------------------------ selection

    /**
     * Descends from the root, stopping where progressive widening says a node is under-expanded or where the
     * UCB-best child is one the tree may not grow past.
     */
    private Node<S, A> selectNode() {
        Node<S, A> current = root;
        while (current.getChildren().size() > 1) {
            // Depth is uniform across siblings: past maxPlies no child can be expanded, so there is nothing to
            // descend into (at the default of one ply the descent always stops at the root).
            if (current.getDepth() + 1 >= params.maxPlies) break;
            if (underExpanded(current) && rnd.nextDouble() < params.wideningStopProb) break;

            Node<S, A> next = ucbChild(current);
            if (next == null || !expandable(next)) break;
            current = next;
        }
        return current;
    }

    /** {@code children < k * descendants^alpha}: too few children for how much work went below the node. */
    private boolean underExpanded(Node<S, A> node) {
        double threshold = params.wideningK * Math.pow(Math.max(1, node.getDescendants()), params.wideningAlpha);
        return node.getChildren().size() < threshold;
    }

    /**
     * Whether the tree may grow children under this node: not a leaf, within the plies, and the player to move
     * the one the tree's alternation expects (in a solo game the end of turn does not pass the move).
     */
    private boolean expandable(Node<S, A> node) {
        if (node.isLeaf() || node.getState() == null) return false;
        if (node.getDepth() >= params.maxPlies) return false;
        boolean activeIsMe = game.activePlayer(node.getState()) == player;
        return activeIsMe == node.childrenAreMine();
    }

    /**
     * UCB over the children, oriented so that the agent's turns maximise and the opponent's minimise, on fitness
     * normalised to the siblings' range (the heuristic is unbounded, and raw it would leave the exploration term
     * nothing to weigh against); ties drawn at random.
     */
    private Node<S, A> ucbChild(Node<S, A> node) {
        double sign = node.childrenAreMine() ? 1.0 : -1.0;
        double parentVisits = node.getDescendants() + 1.0;

        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Node<S, A> child : node.getChildren()) {
            double v = sign * child.getFitness();
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        double range = hi - lo;

        Node<S, A> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (Node<S, A> child : node.getChildren()) {
            double childVisits = child.getDescendants() + 1.0;
            double exploit = range > params.epsilon ? (sign * child.getFitness() - lo) / range : 0.0;
            double score = exploit + params.ucbC * Math.sqrt(Math.log(parentVisits) / childVisits);
            if (score > bestScore) {
                bestScore = score;
                best = child;
                numTies = 1;
            } else if (score == bestScore) {
                numTies++;
                if (rnd.nextInt(numTies) == 0) best = child;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ expansion

    /**
     * Gives an unexpanded node its initial population of generated turns. The root's (the seeding) stops when
     * the budget runs out; a {@code null} budget gives every turn, as the engine does inside the search.
     */
    private void exploit(Node<S, A> node, int numChildren, TurnBudget budget) {
        for (int i = 0; i < numChildren; i++) {
            if (budget != null && budget.exhausted()) break;
            try (Meter.Span t = TURN.span()) {
                S s = game.copy(node.getState());
                ArrayList<A> seq = generateTurn(s);
                addChild(node, seq, s);
            }
        }
        node.setExplored(true);
    }

    /** Evolves an expanded node: mutate one child, or cross two over. */
    private void evolve(Node<S, A> node) {
        ArrayList<Node<S, A>> kids = node.getChildren();
        if (kids.isEmpty()) return;

        boolean mutateInstead = rnd.nextDouble() < params.mutationRatio;
        if (mutateInstead || kids.size() == 1) {
            Node<S, A> template = tournamentSelect(node, null);
            if (template != null) mutate(node, template);
        } else {
            Node<S, A> p1 = tournamentSelect(node, null);
            Node<S, A> p2 = tournamentSelect(node, p1);
            if (p1 != null && p2 != null) crossover(node, p1, p2);
        }
    }

    /** A plain tournament of {@link #tournamentSize} draws among a node's children, oriented as {@link #ucbChild}. */
    private Node<S, A> tournamentSelect(Node<S, A> node, Node<S, A> exclude) {
        ArrayList<Node<S, A>> kids = node.getChildren();
        if (kids.isEmpty()) return null;
        double sign = node.childrenAreMine() ? 1.0 : -1.0;

        Node<S, A> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tournamentSize; i++) {
            Node<S, A> c = kids.get(rnd.nextInt(kids.size()));
            if (c == exclude) continue;
            double score = sign * c.getFitness();
            if (best == null || score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null) {
            // Every draw hit the excluded node; take any other child.
            for (Node<S, A> c : kids) if (c != exclude) return c;
        }
        return best;
    }

    /**
     * Mutation: replays the template turn from the parent's state, redrawing each action with probability
     * {@code mutationRate} and replacing at random one that is no longer legal.
     */
    private void mutate(Node<S, A> node, Node<S, A> template) {
        S s = game.copy(node.getState());
        int owner = game.activePlayer(s);
        ArrayList<A> seq = new ArrayList<>();

        for (A orig : template.getSequence()) {
            if (game.isTerminal(s) || game.activePlayer(s) != owner || seq.size() >= params.nodeSize) break;

            boolean resample = rnd.nextDouble() < params.mutationRate;
            A chosen;
            if (!resample && isLegal(s, orig)) {
                chosen = orig;
            } else {
                List<A> feasible = legalActions(s);
                if (feasible.isEmpty()) break;
                chosen = pickAction(feasible);
            }
            if (chosen == null) break;

            game.advance(s, chosen);
            seq.add(chosen);
            if (game.endsTurn(chosen)) break;
        }

        appendEndTurn(seq, s, owner);
        addChild(node, seq, s);
    }

    /**
     * Crossover: a uniform per-position crossover of two sibling turns, replayed from the parent's state, an
     * action the spliced prefix has made illegal replaced at random.
     */
    private void crossover(Node<S, A> node, Node<S, A> a, Node<S, A> b) {
        ArrayList<A> s1 = a.getSequence();
        ArrayList<A> s2 = b.getSequence();
        int n = Math.min(s1.size(), s2.size());

        ArrayList<A> crossed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) crossed.add(rnd.nextBoolean() ? s1.get(i) : s2.get(i));

        S s = game.copy(node.getState());
        int owner = game.activePlayer(s);
        ArrayList<A> seq = new ArrayList<>();

        for (A act : crossed) {
            if (game.isTerminal(s) || game.activePlayer(s) != owner || seq.size() >= params.nodeSize) break;

            A chosen;
            if (isLegal(s, act)) {
                chosen = act;
            } else {
                List<A> feasible = legalActions(s);
                if (feasible.isEmpty()) break;
                chosen = pickAction(feasible);
            }
            if (chosen == null) break;

            game.advance(s, chosen);
            seq.add(chosen);
            if (game.endsTurn(chosen)) break;
        }

        appendEndTurn(seq, s, owner);
        addChild(node, seq, s);
    }

    /** Builds one complete turn for whoever is to move in {@code s}, advancing {@code s} through it. */
    private ArrayList<A> generateTurn(S s) {
        ArrayList<A> seq = new ArrayList<>();
        int owner = game.activePlayer(s);

        while (!game.isTerminal(s) && game.activePlayer(s) == owner && seq.size() < params.nodeSize) {
            List<A> feasible = legalActions(s);
            if (feasible.isEmpty()) break;

            A a = pickAction(feasible);
            if (a == null) break;

            game.advance(s, a);
            seq.add(a);
            if (game.endsTurn(a)) break;
        }

        appendEndTurn(seq, s, owner);
        return seq;
    }

    /** Closes a turn with the end of turn (advancing into it) unless it already ended or the game is over. */
    private void appendEndTurn(ArrayList<A> seq, S s, int owner) {
        if (!seq.isEmpty() && game.endsTurn(seq.get(seq.size() - 1))) return;
        if (game.isTerminal(s) || game.activePlayer(s) != owner) return;

        A end = endTurn(s);
        if (end != null) {
            game.advance(s, end);
            seq.add(end);
        }
    }

    /**
     * Adds a newly built turn as a child: rejects a duplicate of a sibling, evaluates it, drops its state if
     * nothing can expand it, and backs its value up the tree.
     */
    private void addChild(Node<S, A> parent, ArrayList<A> seq, S resultingState) {
        if (seq == null || seq.isEmpty()) return;

        String key = seqKey(seq);
        Node<S, A> child = new Node<>(seq, parent, parent.getDepth() + 1);
        child.setState(resultingState);
        if (!parent.addChild(child, key)) {          // an identical sibling is already there
            duplicates++;
            return;
        }
        child.incDescendants();

        evaluate(child, key);

        // Nothing reads the state of a node that can never be expanded again (every operator replays from the
        // parent's state), and at one ply the root gains a child an iteration: holding them all would hold one
        // state per iteration.
        if (child.isLeaf() || child.getDepth() >= params.maxPlies) child.setState(null);

        for (Node<S, A> n = child.getParent(); n != null; n = n.getParent()) if (!n.backup()) break;
    }

    // ------------------------------------------------------------------ evaluation

    private void evaluate(Node<S, A> node, String key) {
        S s = node.getState();
        if (game.isTerminal(s)) node.setLeaf(true);
        double value = game.evaluate(s, player);
        node.setFitness(value);
        nodesEvaluated++;
        sumNodeValue += value;
        uniqueSeqKeys.add(key);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The one place an action is drawn: uniformly among the feasible actions other than the end of turn, which
     * is taken only when it is the only one (so a draw cannot end a turn on its first step).
     */
    private A pickAction(List<A> feasible) {
        List<A> pool = new ArrayList<>(feasible.size());
        A end = null;
        for (A a : feasible) {
            if (!game.endsTurn(a)) pool.add(a);
            else if (end == null) end = a;
        }
        if (pool.isEmpty()) return end;
        uniformPicks++;
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** The legal actions, as one {@code tbets.legal} operation. */
    private List<A> legalActions(S s) {
        try (Meter.Span q = LEGAL.span()) {
            return game.legalActions(s);
        }
    }

    /** The end-of-turn action, found by one {@code tbets.legal} operation; null where the turn cannot end. */
    private A endTurn(S s) {
        try (Meter.Span q = LEGAL.span()) {
            return game.endTurnAction(s);
        }
    }

    /** Whether a turn's action is still legal, as one {@code tbets.check} operation. */
    private boolean isLegal(S s, A a) {
        try (Meter.Span c = CHECK.span()) {
            return game.isLegal(s, a);
        }
    }

    /** The first child of the highest fitness. */
    private Node<S, A> bestChild(Node<S, A> node) {
        Node<S, A> best = null;
        for (Node<S, A> c : node.getChildren()) if (best == null || c.getFitness() > best.getFitness()) best = c;
        return best;
    }

    private double bestChildValue(Node<S, A> node) {
        Node<S, A> best = bestChild(node);
        return best != null ? best.getFitness() : 0.0;
    }

    /** The key of an action sequence: duplicates are rejected and unique turns counted on it. */
    private String seqKey(ArrayList<A> seq) {
        StringBuilder sb = new StringBuilder();
        for (A a : seq) sb.append(a == null ? "_" : game.actionKey(a)).append('|');
        return sb.toString();
    }

    /** The registry entry. */
    public static <S, A> AgentFactory<S, A> factory() {
        Meter.declare(OPERATIONS);
        return new AgentFactory<S, A>() {
            @Override public String id() { return "tbets"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new Tbets<>(seed, new TbetsParams()); }
            @Override public List<String> operations() { return OPERATIONS; }
            @Override public String description() {
                return "turn-based evolutionary tree search, the engine's TBETS at its defaults: 20 seeded turns, "
                        + "evolved by tournament mutation and crossover, one ply, no rollouts";
            }
        };
    }
}
