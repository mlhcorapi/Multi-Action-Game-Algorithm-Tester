package magat.agents.emcts;

import java.util.ArrayList;

/** A node of the EMCTS tree: a plan, its value, its state after the plan, and its children. */
public final class Node<S, A> {

    private ArrayList<A> sequence;
    private Node<S, A> parent;
    private final ArrayList<Node<S, A>> children = new ArrayList<>();
    private double value;
    private S state;

    public Node(ArrayList<A> sequence, Node<S, A> parent) {
        this.sequence = sequence;
        this.parent = parent;
    }

    public void addChild(Node<S, A> child) { children.add(child); }
    public ArrayList<Node<S, A>> children() { return children; }
    public Node<S, A> parent() { return parent; }
    public ArrayList<A> sequence() { return sequence; }
    public void setSequence(ArrayList<A> sequence) { this.sequence = sequence; }
    public double value() { return value; }
    public void setValue(double value) { this.value = value; }
    public S state() { return state; }
    public void setState(S state) { this.state = state; }

    /** Cuts the link to the parent so a burned branch can be collected. */
    public void disconnectParent() { this.parent = null; }
}
