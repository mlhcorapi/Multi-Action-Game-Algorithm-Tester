# Guide for fixing EMCTS NPE

When running single player mode, `EMCTSAgent.java` crashes with a `NullPointerException` on line 177:
```java
ArrayList<EMCTSTreeNode> currentChildren = current.getChildren();
if (currentChildren.size() == 0) {
```
because `currentChildren` is `null`.

This occurs because `node.unlink()` sets `this.children = null` (in `EMCTSTreeNode.java`).
During the Bridge Burning phase, `EMCTSAgent` does:
```java
bestChild.unlink(); // clears parent link
root = bestChild;
```
When `bestChild.unlink()` is called, it clears its own `children` reference, setting it to `null`. Later, when `nodeToExpand()` traverses down from `root`, it gets a null children list from `root.getChildren()`.

## Fix Attempt Notes:
We tried changing `unlink()` to clear the children array instead of setting it to `null`:
```java
if (this.children != null) {
    this.children.clear();
}
```
However, that results in `.get(0)` calls inside `findBestChild` or `nodeToExpand` throwing `IndexOutOfBoundsException` because it clears children of nodes we are trying to keep.
The `unlink()` method currently mixes two responsibilities:
1. Disconnecting a node from its parent.
2. Completely deleting a node and its descendants for Garbage Collection.

The line `bestChild.unlink()` was likely intended to ONLY disconnect `bestChild` from its old parent (since it is becoming the new root), NOT to destroy its children.
We tried adding a `disconnectParent()` method, but there are multiple loops inside `EMCTSAgent` that rely on the behavior of `unlinkTree` or `unlink`.

## Next Steps for the AI:
1. Review `EMCTSAgent.act()` and `bridge burning` logic.
2. When promoting `bestChild` to `root`, we must disconnect it from the old root without destroying its descendants.
3. Review `unlinkTree` method and `node.unlink()`.
4. We also noticed an `IllegalArgumentException` in `mutate()` if `seq` is empty or if `allAvailableActions` is empty. Adding checks there (`seq.isEmpty() ? 0 : m_rnd.nextInt(seq.size())` and `if (!allAvailableActions.isEmpty())`) prevents crashes when no actions are available.
