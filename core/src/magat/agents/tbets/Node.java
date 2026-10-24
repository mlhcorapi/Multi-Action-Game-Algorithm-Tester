package magat.agents.tbets;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * A node of the TBETS tree: one complete turn, and the state after it (its end of turn included, so the active
 * player is already the next one). The children of a node are candidate turns for whoever plays next, so the
 * tree deepens across turns, not within one. The root is the empty turn: the state the agent was handed.
 *
 * <p>Fitness is always the searching agent's, which is why the back-up is a minimax: a node whose children are
 * the agent's turns takes their maximum, a node whose children are the opponent's their minimum. A port of the
 * engine's {@code TBETSNode}.
 */
final class Node<S, A> {

    /** The turn played to reach this node; empty for the root. */
    private final ArrayList<A> sequence;
    /** The state after {@link #sequence}; dropped once nothing can expand the node. */
    private S state;

    private final Node<S, A> parent;
    private final ArrayList<Node<S, A>> children = new ArrayList<>();
    /** The keys of this node's children, so that a duplicate turn is rejected. */
    private final HashSet<String> childKeys = new HashSet<>();

    /** Plies from the root; also whose turn the node is (see {@link #isMine}). */
    private final int depth;

    private double fitness;
    private boolean evaluated;
    /** The node has had its initial population of children. */
    private boolean explored;
    /** The game is over in the node's state, so it can have no children. */
    private boolean leaf;
    /** The size of the subtree, the visit count of selection and of progressive widening. */
    private int descendants;

    Node(ArrayList<A> sequence, Node<S, A> parent, int depth) {
        this.sequence = sequence;
        this.parent = parent;
        this.depth = depth;
    }

    /** Whether the turn of this node was the agent's: odd depths, because the root stands for the turn before. */
    boolean isMine() {
        return (depth % 2) == 1;
    }

    /** Whether this node's children are the agent's turns. */
    boolean childrenAreMine() {
        return ((depth + 1) % 2) == 1;
    }

    ArrayList<A> getSequence()            { return sequence; }
    S getState()                          { return state; }
    void setState(S s)                    { this.state = s; }
    Node<S, A> getParent()                { return parent; }
    ArrayList<Node<S, A>> getChildren()   { return children; }
    int getDepth()                        { return depth; }
    double getFitness()                   { return fitness; }
    boolean isExplored()                  { return explored; }
    void setExplored(boolean e)           { this.explored = e; }
    boolean isLeaf()                      { return leaf; }
    void setLeaf(boolean l)               { this.leaf = l; }
    int getDescendants()                  { return descendants; }

    void setFitness(double f) {
        this.fitness = f;
        this.evaluated = true;
    }

    /** Adds a child under its key; false if a sibling already has that key. */
    boolean addChild(Node<S, A> child, String key) {
        if (!childKeys.add(key)) return false;
        children.add(child);
        return true;
    }

    /** One more node in the subtree of this node and of every ancestor. */
    void incDescendants() {
        for (Node<S, A> n = this; n != null; n = n.parent) n.descendants++;
    }

    /**
     * The minimax back-up: this node's fitness becomes the best of its evaluated children for whoever plays
     * them. No change while none is evaluated. True if the value changed, so that the caller stops at the first
     * level left unchanged.
     */
    boolean backup() {
        if (leaf || children.isEmpty()) return false;
        boolean max = childrenAreMine();
        double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        boolean any = false;
        for (Node<S, A> c : children) {
            if (!c.evaluated) continue;
            any = true;
            if (max ? (c.fitness > best) : (c.fitness < best)) best = c.fitness;
        }
        if (!any) return false;
        boolean changed = !evaluated || best != fitness;
        fitness = best;
        evaluated = true;
        return changed;
    }
}
