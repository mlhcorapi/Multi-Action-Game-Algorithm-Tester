# Algorithms Overview: EMCTS & ExperimentalEMCTS

This document outlines the mechanics and pseudocode of the Monte Carlo Tree Search based algorithms included in the Tribes framework: `EMCTS`, and its experimental variants under `ExperimentalEMCTS`.

## 1. EMCTS (Evolutionary Monte Carlo Tree Search)

The EMCTS agent is a hybrid approach combining traditional Monte Carlo Tree Search (MCTS) with evolutionary mechanisms, specifically tailored for turn-based strategy games like Tribes. The key characteristic of EMCTS is the concept of **bridge burning** and **phase-based execution**, optimizing search inside a single large turn by executing actions and pruning the tree.

### How It Works

Instead of searching the entire turn space at once, EMCTS splits the allowed time (or iterations) into **phases** (or depths).
Within each phase:
1. It initializes a tree root via a 1-ply greedy initialization, generating a random but heuristically strong action sequence.
2. It iteratively builds the tree from the root. For each iteration:
   - **Selection (pure exploitation):** It expands the *incumbent* - the highest-value node currently in the live tree (UCB with a zero exploration term). A child that improves on the incumbent becomes the new incumbent; one that does not stays a leaf, so failed mutations accumulate as siblings under the incumbent and the tree genuinely branches. (The incumbent is tracked by reference, not recovered by best-child descent: a descent that always follows the best child can never stop at an interior node, which was the pre-fix bug - every new child, better or worse, became the next expansion target, degenerating the tree into a single unguided mutation chain.)
   - **Mutation (Evolutionary aspect):** Once it reaches a node to expand, it applies an evolutionary mutation. It takes the sequence of actions from the node, selects a random point in the sequence, keeps the actions up to that point, randomly picks a new available action, and then appends the remaining actions from the original sequence.
   - **Repair:** Because game states are highly path-dependent, the mutated sequence might be invalid (e.g., trying to move a unit that was already moved, or lacking resources for an action). A **greedy repair** mechanism goes through the newly mutated sequence. If an action is no longer feasible, it replaces it greedily with the highest-scoring valid action.
   - **Evaluation & Incumbent update:** It evaluates the newly created child node with the heuristic. There is no UCB backpropagation - with a zero exploration term a node's score is exactly its own heuristic value, so nothing propagates; the child simply replaces the incumbent (expansion target) and/or the best-ever node (final answer) when strictly better.
3. At the end of a phase, the agent applies **Bridge Burning**: it promotes the root child on the incumbent's path (the lineage of the best live sequence) to be the *new* root and prunes every other branch. If no child improved on the root yet, it falls back to the best immediate child - the burn count is fixed - and the incumbent re-anchors onto the surviving subtree. The final answer replayed over the turn is the best-ever node's full sequence, which is tracked separately and survives pruning.
4. The process repeats for the next phase starting from the new root, effectively "burning the bridge" to alternative early actions in order to focus computational effort deeper into the turn.

### EMCTS Pseudocode

```text
function EMCTS(gameState, maxDepth, timeLimit):
    rootNode  = GreedyInitializeRoot(gameState)
    Evaluate(rootNode)
    incumbent = rootNode        // highest-value node in the LIVE tree (expansion target)
    bestNode  = rootNode        // best sequence EVER seen (survives pruning; the answer)

    timePerPhase = timeLimit / maxDepth

    for phase = 1 to maxDepth:
        while time_spent_in_phase < timePerPhase:
            // 1. Selection: pure exploitation - always expand the incumbent
            nodeToExpand = incumbent

            // 2. Mutation & Repair
            mutatedSequence  = Mutate(nodeToExpand.sequence)
            repairedSequence = GreedyRepair(mutatedSequence, gameState)

            childNode = new Node(repairedSequence, parent = nodeToExpand)

            // 3. Evaluation + incumbent/best update (no UCB backpropagation)
            Evaluate(childNode)
            if childNode.value > incumbent.value: incumbent = childNode
            if childNode.value > bestNode.value:  bestNode  = childNode

        // Bridge Burning: keep the incumbent's lineage, prune every other branch
        newRoot = ChildOfRootOnPathTo(incumbent)         // null if incumbent == root
        if newRoot == null: newRoot = rootNode.getBestChild()
        rootNode = newRoot
        if incumbent not under rootNode:                 // pruned by the fallback
            incumbent = MaxValueNodeIn(rootNode.subtree)

    return bestNode.sequence
```

---

## 2. ExperimentalEMCTS (and variants)

The `ExperimentalEMCTS` algorithms extend the base EMCTS by introducing new genetic operators, mainly focused on **Crossover** operations. There are three variations depending on how crossover is utilized:

- **CrossoverExpansionAgent:** Replaces standard expansion/mutation with Crossover Expansion on a set ratio.
- **CrossoverBBAgent:** Uses Crossover specifically during the Bridge Burning phase to combine top nodes.
- **CrossoverComboAgent:** Uses Crossover in both Expansion and Bridge Burning.

### How It Works

#### Crossover Expansion (Variant 2)
In the standard EMCTS iteration loop, instead of always applying mutation to a single node to generate a child, the algorithm keeps track of a queue of globally top-performing nodes. Periodically (based on `mutationExpansionRatio`), it triggers **Crossover Expansion**:
1. It extracts the top 2 nodes from the global top queue.
2. It performs **Uniform Crossover** on their action sequences: at each position in the action sequence, it randomly chooses the action from either Parent 1 or Parent 2.
3. The resulting sequence is then put through the greedy **Repair** mechanism to ensure game logic feasibility.
4. The repaired crossover child is added to the tree under the stronger of the two parents. (The global top queue is purged of pruned nodes at every bridge burn, so both parents - and therefore the graft - are always inside the live tree, reachable by selection and by the burn-lineage walk.)

#### Crossover Bridge Burning
Standard bridge burning simply picks the best child of the current root. Experimental crossover bridge burning tries to *synthesize* a better root candidate using a hierarchy of crossovers:
1. It extracts the top 8 performing nodes from the iteration phase.
2. It performs a tournament/bracket style crossover:
   - **Level 1:** Pairs the 8 nodes into 4 crossover children.
   - **Level 2:** Pairs the 4 children into 2 crossover children.
   - **Level 3:** Pairs the 2 children into 1 final crossover child.
3. It evaluates all 7 newly generated crossover children.
4. If the best crossover child outperforms the standard bridge-burning target (the root child on the incumbent's path, falling back to the best immediate child), the bred child becomes the new root for the next phase - discarding the old tree entirely, so the search restarts from the bred sequence and the incumbent re-anchors onto it.

### ExperimentalEMCTS Pseudocode

```text
function ExperimentalEMCTS(gameState, maxDepth, timeLimit, useCrossoverExp, useCrossoverBB):
    rootNode = GreedyInitializeRoot(gameState)
    Evaluate(rootNode)

    globalTopNodes = PriorityQueue(maxSize=2)
    phaseTopNodes = PriorityQueue(maxSize=8)

    timePerPhase = timeLimit / maxDepth

    incumbent = rootNode        // highest-value node in the LIVE tree (expansion target)
    bestNode  = rootNode        // best sequence EVER seen (survives pruning; the answer)

    for phase = 1 to maxDepth:
        while time_spent_in_phase < timePerPhase:
            // Selection: pure exploitation - always expand the incumbent
            nodeToExpand = incumbent

            if useCrossoverExp and should_do_crossover_expansion:
                parent1, parent2 = globalTopNodes.getTop(2)
                crossedSequence = UniformCrossover(parent1, parent2)
                repairedSequence = GreedyRepair(crossedSequence, gameState)
                childNode = new Node(repairedSequence, parent = Stronger(parent1, parent2))
            else:
                mutatedSequence = Mutate(nodeToExpand.sequence)
                repairedSequence = GreedyRepair(mutatedSequence, gameState)
                childNode = new Node(repairedSequence, parent = nodeToExpand)

            // Evaluation + incumbent/best update (no UCB backpropagation)
            Evaluate(childNode)
            if childNode.value > incumbent.value: incumbent = childNode
            if childNode.value > bestNode.value:  bestNode  = childNode

            globalTopNodes.add(childNode)
            phaseTopNodes.add(childNode)

        // Bridge Burning: the standard target is the incumbent's lineage
        standardTarget = ChildOfRootOnPathTo(incumbent)
        if standardTarget == null: standardTarget = rootNode.getBestChild()

        if useCrossoverBB and phaseTopNodes.size >= 2:
            bredCandidate = HierarchicalCrossoverBracket(phaseTopNodes)
            newRoot = (bredCandidate.value > standardTarget.value) ? bredCandidate : standardTarget
        else:
            newRoot = standardTarget

        rootNode = newRoot
        if incumbent not under rootNode:                 // fallback promotion or bred root
            incumbent = MaxValueNodeIn(rootNode.subtree)
        globalTopNodes.removeEntriesNotUnder(rootNode)   // crossover parents must be live
        phaseTopNodes.clear(); phaseTopNodes.add(rootNode)

    return bestNode.sequence
```
