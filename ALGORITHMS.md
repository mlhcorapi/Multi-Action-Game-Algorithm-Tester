# Algorithms Overview: EMCTS & ExperimentalEMCTS

This document outlines the mechanics and pseudocode of the Monte Carlo Tree Search based algorithms included in the Tribes framework: `EMCTS`, and its experimental variants under `ExperimentalEMCTS`.

## 1. EMCTS (Evolutionary Monte Carlo Tree Search)

The EMCTS agent is a hybrid approach combining traditional Monte Carlo Tree Search (MCTS) with evolutionary mechanisms, specifically tailored for turn-based strategy games like Tribes. The key characteristic of EMCTS is the concept of **bridge burning** and **phase-based execution**, optimizing search inside a single large turn by executing actions and pruning the tree.

### How It Works

Instead of searching the entire turn space at once, EMCTS splits the allowed time (or iterations) into **phases** (or depths).
Within each phase:
1. It initializes a tree root via a 1-ply greedy initialization, generating a random but heuristically strong action sequence.
2. It iteratively builds the tree from the root. For each iteration:
   - **Selection/Exploitation:** It greedily traverses down the current tree from the root by selecting children that have the highest evaluation score.
   - **Mutation (Evolutionary aspect):** Once it reaches a node to expand, it applies an evolutionary mutation. It takes the sequence of actions from the node, selects a random point in the sequence, keeps the actions up to that point, randomly picks a new available action, and then appends the remaining actions from the original sequence.
   - **Repair:** Because game states are highly path-dependent, the mutated sequence might be invalid (e.g., trying to move a unit that was already moved, or lacking resources for an action). A **greedy repair** mechanism goes through the newly mutated sequence. If an action is no longer feasible, it replaces it greedily with the highest-scoring valid action.
   - **Evaluation & Backpropagation:** It evaluates the newly created child node and backpropagates the score (using standard UCB updates) back up the tree.
3. At the end of a phase, the agent applies **Bridge Burning**. It selects the best-performing child of the root, adds its first action to the final output list, and makes that child the *new* root of the tree.
4. The process repeats for the next phase starting from the new root, effectively "burning the bridge" to alternative early actions in order to focus computational effort deeper into the turn.

### EMCTS Pseudocode

```text
function EMCTS(gameState, maxDepth, timeLimit):
    rootNode = GreedyInitializeRoot(gameState)
    Evaluate(rootNode)

    timePerPhase = timeLimit / maxDepth

    for phase = 1 to maxDepth:
        while time_spent_in_phase < timePerPhase:
            // 1. Selection
            nodeToExpand = TraverseGreedy(rootNode)

            // 2. Mutation & Repair
            mutatedSequence = Mutate(nodeToExpand.sequence)
            repairedSequence = GreedyRepair(mutatedSequence, nodeToExpand.gameState)

            childNode = new Node(repairedSequence, nodeToExpand)

            // 3. Evaluation
            Evaluate(childNode)

            // 4. Backpropagation
            Backpropagate(childNode)

        // Bridge Burning: Commit to the best immediate action and prune the rest
        bestChild = rootNode.getBestChild()
        rootNode = bestChild

    return bestNode.sequence.pop_first()
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
4. The repaired crossover child is added to the tree under the stronger of the two parents.

#### Crossover Bridge Burning
Standard bridge burning simply picks the best child of the current root. Experimental crossover bridge burning tries to *synthesize* a better root candidate using a hierarchy of crossovers:
1. It extracts the top 8 performing nodes from the iteration phase.
2. It performs a tournament/bracket style crossover:
   - **Level 1:** Pairs the 8 nodes into 4 crossover children.
   - **Level 2:** Pairs the 4 children into 2 crossover children.
   - **Level 3:** Pairs the 2 children into 1 final crossover child.
3. It evaluates all 7 newly generated crossover children.
4. If the best crossover child outperforms the standard "best child" from the tree, the crossover child becomes the new root for the next phase.

### ExperimentalEMCTS Pseudocode

```text
function ExperimentalEMCTS(gameState, maxDepth, timeLimit, useCrossoverExp, useCrossoverBB):
    rootNode = GreedyInitializeRoot(gameState)
    Evaluate(rootNode)

    globalTopNodes = PriorityQueue(maxSize=2)
    phaseTopNodes = PriorityQueue(maxSize=8)

    timePerPhase = timeLimit / maxDepth

    for phase = 1 to maxDepth:
        while time_spent_in_phase < timePerPhase:
            nodeToExpand = TraverseGreedy(rootNode)

            if useCrossoverExp and should_do_crossover_expansion:
                parent1, parent2 = globalTopNodes.getTop(2)
                crossedSequence = UniformCrossover(parent1, parent2)
                repairedSequence = GreedyRepair(crossedSequence, parent1.gameState)
                childNode = new Node(repairedSequence, Stronger(parent1, parent2))
            else:
                mutatedSequence = Mutate(nodeToExpand.sequence)
                repairedSequence = GreedyRepair(mutatedSequence, nodeToExpand.gameState)
                childNode = new Node(repairedSequence, nodeToExpand)

            Evaluate(childNode)
            Backpropagate(childNode)

            globalTopNodes.add(childNode)
            phaseTopNodes.add(childNode)

        // Bridge Burning
        bestChild = rootNode.getBestChild()

        if useCrossoverBB and phaseTopNodes.size >= 2:
            bestCrossoverCandidate = HierarchicalCrossoverBracket(phaseTopNodes)
            if bestCrossoverCandidate.score > bestChild.score:
                bestChild = bestCrossoverCandidate

        rootNode = bestChild
        phaseTopNodes.clear()

    return bestNode.sequence.pop_first()
```
