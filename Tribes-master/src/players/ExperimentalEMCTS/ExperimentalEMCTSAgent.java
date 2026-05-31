package players.ExperimentalEMCTS;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.CityAction;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.City;
import core.actors.units.Unit;
import core.game.GameState;
import players.Agent;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.Random;



public class ExperimentalEMCTSAgent extends Agent {

    protected Random m_rnd;
    protected StateHeuristic heuristic;
    protected ExperimentalEMCTSParams params;

    protected ExperimentalEMCTSTreeNode root;
    protected ExperimentalEMCTSTreeNode bestNode;
    protected GameState old_gs;

    protected int fmCallsCount;
    protected int fmCallsRun;
    protected PriorityQueue<ExperimentalEMCTSTreeNode> topNodesQueue;
    protected PriorityQueue<ExperimentalEMCTSTreeNode> globalTopNodesQueue;
    protected int crossoverExpansionCounter = 0;


    public ExperimentalEMCTSAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed);
        m_rnd = new Random(seed);
        this.params = params;
    }

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {

        this.fmCallsCount = 0;
        this.heuristic = params.getStateHeuristic(playerID, allPlayerIDs);

        old_gs = gs.copy();

        // Initialize root with 1-ply greedy initialization
        root = randomActions(gs.copy());
        eval(gs.copy(), root);

        bestNode = root;
        fmCallsRun = 0;

        // Initialize PriorityQueue for tracking top nodes (min heap by value to keep the top K elements)
        topNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        topNodesQueue.offer(root);

        globalTopNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        globalTopNodesQueue.offer(root);
        crossoverExpansionCounter = 0;

        // Phase-based bridge burning approach
        long totalTimeMs = ect.remainingTimeMillis();
        long phaseTimeMs = Math.max(1, totalTimeMs / Math.max(1, params.depth));
        int currentPhase = 0;
        int remainingLimit = 5;

        boolean stop = false;
        int numIters = 0;
        double avgTimeTaken = 0;
        double acumTimeTaken = 0;

        while (!stop && currentPhase < params.depth) {
            ElapsedCpuTimer phaseTimer = new ElapsedCpuTimer();
            long phaseStartFmCalls = fmCallsCount;
            boolean phaseComplete = false;

            // Execute MCTS iterations within this phase
            while (!phaseComplete && !stop) {
                numIters++;
                ElapsedCpuTimer elapsedTimerIteration = new ElapsedCpuTimer();
                fmCallsRun = 0;

                // Standard MCTS iteration: select, mutate once, evaluate, backpropagate
                ExperimentalEMCTSTreeNode toMutate = nodeToExpand();
                ExperimentalEMCTSTreeNode child = null;

                if (params.useCrossoverExpansion && globalTopNodesQueue.size() >= 2) {
                    crossoverExpansionCounter++;
                    if (crossoverExpansionCounter >= params.mutationExpansionRatio) {
                        crossoverExpansionCounter = 0;

                        ArrayList<ExperimentalEMCTSTreeNode> topNodesList = new ArrayList<>(globalTopNodesQueue);
                        ExperimentalEMCTSTreeNode p1 = topNodesList.get(0);
                        ExperimentalEMCTSTreeNode p2 = topNodesList.get(1);

                        ArrayList<Action> crossoverSeq = crossover(p1, p2, gs.copy());

                        ExperimentalEMCTSTreeNode strongerParent = (p1.getValue() > p2.getValue()) ? p1 : p2;
                        child = new ExperimentalEMCTSTreeNode(crossoverSeq, strongerParent);
                        strongerParent.addChild(child);
                    } else {
                        child = mutate(toMutate, gs.copy());
                    }
                } else {
                    child = mutate(toMutate, gs.copy());
                }

                eval(gs.copy(), child);

                if (child.getValue() > bestNode.getValue()) {
                    bestNode = child;
                }

                child.visited();

                // Track top nodes for crossover bridge burning
                topNodesQueue.offer(child);
                if (topNodesQueue.size() > 8) {
                    topNodesQueue.poll();
                }

                globalTopNodesQueue.offer(child);
                if (globalTopNodesQueue.size() > 2) {
                    globalTopNodesQueue.poll();
                }

                // Backpropagate score up the tree (standard UCB update)
                ExperimentalEMCTSTreeNode current = child;
                while (current != null) {
                    current.refreshScore(params.bias);
                    current = current.getParent();
                }

                // Check iteration stopping criteria
                long iterationTime = elapsedTimerIteration.elapsedMillis();
                acumTimeTaken += iterationTime;
                avgTimeTaken = acumTimeTaken / numIters;

                if (params.stop_type == params.STOP_TIME) {
                    long remaining = ect.remainingTimeMillis();
                    stop = remaining <= 2 * avgTimeTaken || remaining <= remainingLimit;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    stop = numIters >= params.num_iterations;
                } else if (params.stop_type == params.STOP_FMCALLS) {
                    stop = (fmCallsCount > params.num_fmcalls) || 
                           (fmCallsRun > (params.num_fmcalls - fmCallsCount));
                }

                // Check if phase time is exhausted
                if (phaseTimer.elapsedMillis() >= phaseTimeMs) {
                    phaseComplete = true;
                }
            }

            // Bridge Burning: promote best child to new root if phase complete and not at end
            if (!stop && currentPhase < params.depth - 1 && root.getChildren().size() > 0) {
                ExperimentalEMCTSTreeNode newRoot = null;

                // Crossover Bridge Burning: Use crossover to generate new root candidates
                if (params.useCrossoverBridgeBurning && topNodesQueue.size() >= 2) {
                    newRoot = performCrossoverBridgeBurning(gs.copy());
                } else {
                    // Standard Bridge Burning: Promote best child
                    newRoot = findBestChild(root);
                }

                if (newRoot != null && newRoot != root) {
                    // Unlink old root branches except new root
                    for (ExperimentalEMCTSTreeNode child : root.getChildren()) {
                        if (child != newRoot) {
                            unlinkTree(child, newRoot);
                        }
                    }
                    
                    // Update root and clear old parent reference
                    newRoot.disconnectParent();  // Clears parent link
                    root = newRoot;

                    // Clear and reset queue for next phase
                    topNodesQueue.clear();
                    topNodesQueue.offer(root);
                    
                    // Continue search from new root
                    currentPhase++;
                } else {
                    // No improvement, exit phase loop
                    break;
                }
            } else {
                // Reached end of phase budget or stop condition
                break;
            }
        }

        Action bestAction = null;
        if (bestNode.getSequence() != null && !bestNode.getSequence().isEmpty()) {
            bestAction = bestNode.returnNext();
            if (bestAction != null && !bestAction.isFeasible(gs)) {
                bestAction = null;
            }
        }

        // Tier 1 is already bestAction

        // Tier 2 (Random)
        if (bestAction == null) {
            ArrayList<Action> availableActions = this.allGoodActions(gs, m_rnd);
            if (availableActions != null && !availableActions.isEmpty()) {
                bestAction = availableActions.get(m_rnd.nextInt(availableActions.size()));
            }
        }

        // Tier 3 (DoNothing/EndTurn)
        if (bestAction == null) {
            bestAction = new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
        }

        return bestAction;
    }

    @Override
    public Agent copy() {
        return null;
    }

    public void eval(GameState gs, ExperimentalEMCTSTreeNode node) {
        if(node.getGs() == null) {
            for (Action move : node.getSequence()) {
                advance(gs, move);
            }
            node.setGs(gs);
            node.setValue(heuristic.evaluateState(old_gs, gs));
        }else{
            node.setValue(heuristic.evaluateState(old_gs, node.getGs()));
        }
    }

    protected ExperimentalEMCTSTreeNode nodeToExpand() {
        ArrayList<ExperimentalEMCTSTreeNode> children = root.getChildren();

        // If root has no children, return root for first mutation
        if (children == null || children.size() == 0) {
            return root;
        }

        // Pure exploitation traversal: traverse down the tree by always selecting
        // the child with the highest score (value-based only, no exploration term)
        ExperimentalEMCTSTreeNode current = root;

        while (true) {
            if (current == null) {
                return root;
            }
            ArrayList<ExperimentalEMCTSTreeNode> currentChildren = current.getChildren();

            if (currentChildren == null) {
                return current;
            }
            // If no children, this is a leaf node - return it for mutation
            if (currentChildren.size() == 0) {
                return current;
            }

            // Select child with highest score (pure exploitation)
            ExperimentalEMCTSTreeNode bestChild = currentChildren.get(0);
            double bestScore = bestChild.getScore();

            for (int i = 1; i < currentChildren.size(); i++) {
                ExperimentalEMCTSTreeNode child = currentChildren.get(i);
                if (child.getScore() > bestScore) {
                    bestScore = child.getScore();
                    bestChild = child;
                }
            }

            // Move down the tree to the best child
            current = bestChild;
        }
    }

    /**
     * 1-ply greedy initialization: for each action in the sequence,
     * evaluate all available actions and select the one with the highest heuristic score.
     */
    protected ExperimentalEMCTSTreeNode randomActions(GameState gs) {
        ArrayList<Action> individual = new ArrayList<>();
        
        while ((!gs.isGameOver() && (gs.getActiveTribeID() == getPlayerID())) && 
               (individual.size() < (params.NODE_SIZE - 1))) {
            
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            if (allAvailableActions.isEmpty()) {
                break;
            }

            Action bestAction = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            GameState bestState = null;
            int numTies = 0;

            // Greedy evaluation: try each action and pick the highest-scoring one
            for (Action candidateAction : allAvailableActions) {
                // Skip END_TURN unless it's the only option
                if (candidateAction.getActionType() == Types.ACTION.END_TURN && 
                    allAvailableActions.size() > 1) {
                    continue;
                }

                GameState testGs = gs.copy();
                advance(testGs, candidateAction);
                double score = heuristic.evaluateState(old_gs, testGs);

                if (score > bestScore) {
                    bestScore = score;
                    bestAction = candidateAction;
                    bestState = testGs;
                    numTies = 1;
                } else if (score == bestScore) {
                    numTies++;
                    if (m_rnd.nextInt(numTies) == 0) {
                        bestAction = candidateAction;
                        bestState = testGs;
                    }
                }
            }

            if (bestAction != null) {
                advance(gs, bestAction);
                individual.add(bestAction);

                // If we added END_TURN, we're done
                if (bestAction.getActionType() == Types.ACTION.END_TURN) {
                    break;
                }
            } else {
                break;
            }
        }

        // Ensure sequence ends with END_TURN if not already present
        if (!individual.isEmpty() && 
            individual.get(individual.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            Action endAction = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    endAction = a;
                    break;
                }
            }
            if (endAction != null) {
                individual.add(endAction);
            }
        }

        ExperimentalEMCTSTreeNode node = new ExperimentalEMCTSTreeNode(individual, null);
        node.setGs(gs.copy());
        return node;
    }


    protected void advance(GameState gs, Action move) {
        this.fmCallsCount++;
        this.fmCallsRun++;
        gs.advance(move, true);
    }

    //mutate a random action
    protected ExperimentalEMCTSTreeNode mutate(ExperimentalEMCTSTreeNode node, GameState gs) {
        GameState clone = gs.copy();
        ArrayList<Action> seq = node.getSequence();
        int moveToMutate = seq.isEmpty() ? 0 : m_rnd.nextInt(seq.size());
        ArrayList<Action> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            advance(gs, seq.get(i));
            newSeq.add(seq.get(i));
        }
        ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);

        if (!allAvailableActions.isEmpty()) {
            Action a = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
            newSeq.add(a);
        }

        for (int i = moveToMutate + 1; i < seq.size(); i++) {
            newSeq.add(seq.get(i));
        }
        newSeq = repair(newSeq, clone);
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newSeq, node);
        node.addChild(newNode);

        return newNode;
    }

    /**
     * Repair a sequence of actions, ensuring feasibility and validity.
     * Uses 1-ply greedy repair: if an action fails, replace it by selecting
     * the available action with the highest heuristic score.
     */
    protected ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
        ArrayList<Action> repairedChild = new ArrayList<>();

        for (int a = 0; a < child.size(); a++) {
            if (!(gs.getActiveTribeID() == getPlayerID())) {
                return repairedChild;
            }

            try {
                boolean done = checkActionFeasibility(child.get(a), gs.copy());

                if (!done) {
                    // Action is infeasible - use greedy selection for replacement
                    ArrayList<Action> allAvailableActions = this.allGoodActions(gs.copy(), m_rnd);
                    
                    Action bestAction = null;
                    double bestScore = Double.NEGATIVE_INFINITY;
                    int numTies = 0;

                    for (Action candidateAction : allAvailableActions) {
                        GameState testGs = gs.copy();
                        advance(testGs, candidateAction);
                        double score = heuristic.evaluateState(old_gs, testGs);

                        if (score > bestScore) {
                            bestScore = score;
                            bestAction = candidateAction;
                            numTies = 1;
                        } else if (score == bestScore) {
                            numTies++;
                            if (m_rnd.nextInt(numTies) == 0) {
                                bestAction = candidateAction;
                            }
                        }
                    }

                    if (bestAction != null) {
                        advance(gs, bestAction);
                        repairedChild.add(bestAction);
                    } else {
                        break;
                    }
                } else {
                    repairedChild.add(child.get(a));
                    advance(gs, child.get(a));
                }
            } catch (Exception e) {
                // Action threw exception - use greedy selection for replacement
                ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);

                Action bestAction = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                int numTies = 0;

                for (Action candidateAction : allAvailableActions) {
                    GameState testGs = gs.copy();
                    advance(testGs, candidateAction);
                    double score = heuristic.evaluateState(old_gs, testGs);

                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidateAction;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) {
                            bestAction = candidateAction;
                        }
                    }
                }

                if (bestAction != null) {
                    advance(gs, bestAction);
                    repairedChild.add(bestAction);
                } else {
                    break;
                }
            }
        }

        // Ensure repaired sequence ends with END_TURN
        if (!repairedChild.isEmpty() && 
            repairedChild.get(repairedChild.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            Action end = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    end = a;
                    break;
                }
            }
            if (end != null) {
                repairedChild.add(end);
            }
        }

        return repairedChild;
    }

    protected boolean checkActionFeasibility(Action a, GameState gs) {
        if (gs.isGameOver())
            return false;

        if (a instanceof UnitAction) {
            UnitAction ua = (UnitAction) a;
            int unitId = ua.getUnitId();
            Actor act = gs.getActor(unitId);
            if (!(act instanceof Unit) || act.getTribeId() != gs.getActiveTribeID())
                return false;
        } else if (a instanceof CityAction) {
            CityAction ca = (CityAction) a;
            int cityId = ca.getCityId();
            Actor act = gs.getActor(cityId);
            if (!(act instanceof City) || act.getTribeId() != gs.getActiveTribeID())
                return false;
        }

        boolean feasible = false;
        try {
            feasible = a.isFeasible(gs);
        } catch (Exception e) {
        }

        return feasible;
    }

    // try to see of the node can be extended
    protected void extend(ExperimentalEMCTSTreeNode node, GameState gs) {
        GameState clone = gs.copy();
        ArrayList<Action> currentActions = node.getSequence();
        ArrayList<Action> newActions = new ArrayList<>();
        for (int i = 0; i < currentActions.size() - 1; i++) {
            newActions.add(currentActions.get(i));
            advance(gs, currentActions.get(i));
        }

        if (this.allGoodActions(gs, m_rnd).size() > 1) {
            while (!gs.isGameOver() && (gs.getActiveTribeID() == getPlayerID())) {
                ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
                Action a = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
                if (!(a.getActionType() == Types.ACTION.END_TURN)) {
                    advance(gs, a);
                    newActions.add(a);
                } else if (allAvailableActions.size() == 1) {
                    advance(gs, a);
                    newActions.add(a);
                    break;
                }
            }
        } else {
            return;
        }
        //newActions = repair(newActions, clone.copy());
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newActions, node.getParent());
        eval(clone, newNode);
        if (newNode.getValue() > node.getValue()) {
            node.setSequence(newActions);
            node.setValue(newNode.getValue());
        }
        //clear links so that JVM can clear it
        newNode.unlink();
    }

    /**
     * Find the best child of a node based on highest value.
     * Used during bridge burning phase transitions.
     */
    protected ExperimentalEMCTSTreeNode findBestChild(ExperimentalEMCTSTreeNode node) {
        ArrayList<ExperimentalEMCTSTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            return null;
        }

        ExperimentalEMCTSTreeNode bestChild = children.get(0);
        double bestValue = bestChild.getValue();

        for (ExperimentalEMCTSTreeNode child : children) {
            if (child.getValue() > bestValue) {
                bestValue = child.getValue();
                bestChild = child;
            }
        }

        return bestChild;
    }

    /**
     * Recursively unlink a tree node and all its descendants to allow garbage collection.
     * Called during bridge burning to free memory from abandoned branches.
     */
    protected void unlinkTree(ExperimentalEMCTSTreeNode node, ExperimentalEMCTSTreeNode protectedNode) {
        if (node == null || node == protectedNode) {
            return;
        }

        ArrayList<ExperimentalEMCTSTreeNode> children = node.getChildren();
        if (children != null) {
            for (ExperimentalEMCTSTreeNode child : children) {
                unlinkTree(child, protectedNode);
            }
        }

        node.unlink();
    }

    /**
     * Performs hierarchical crossover-based bridge burning to generate a new root.
     * Extracts top 8 nodes, performs 3 levels of random crossover pairings:
     * - Level 1: 8 nodes -> 4 children via crossover
     * - Level 2: 4 children -> 2 children via crossover
     * - Level 3: 2 children -> 1 child via crossover
     * All 7 children are evaluated, and the best is compared with bestNode to determine new root.
     */
    protected ExperimentalEMCTSTreeNode performCrossoverBridgeBurning(GameState gs) {
        // Extract top 8 nodes from the priority queue
        ArrayList<ExperimentalEMCTSTreeNode> topEight = new ArrayList<>(topNodesQueue);
        
        // Ensure we have at least 2 nodes for crossover
        if (topEight.size() < 2) {
            return findBestChild(root);
        }

        // Shuffle to create random pairings
        Collections.shuffle(topEight, m_rnd);

        ArrayList<ExperimentalEMCTSTreeNode> level1Children = new ArrayList<>();
        ArrayList<ExperimentalEMCTSTreeNode> allCrossoverChildren = new ArrayList<>();

        // Level 1: Create 4 children from top 8 (4 random pairs)
        for (int i = 0; i < Math.min(topEight.size() - 1, 8); i += 2) {
            if (i + 1 < topEight.size()) {
                ArrayList<Action> crossoverSeq = crossover(topEight.get(i), topEight.get(i + 1), gs.copy());
                ExperimentalEMCTSTreeNode child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
                eval(gs.copy(), child);
                level1Children.add(child);
                allCrossoverChildren.add(child);
            }
        }

        // If we have fewer than 4 children, use standard bridge burning instead
        if (level1Children.size() < 2) {
            return findBestChild(root);
        }

        ArrayList<ExperimentalEMCTSTreeNode> level2Children = new ArrayList<>();

        // Level 2: Create 2 children from 4 level1 children (2 random pairs)
        Collections.shuffle(level1Children, m_rnd);
        for (int i = 0; i < Math.min(level1Children.size() - 1, 4); i += 2) {
            if (i + 1 < level1Children.size()) {
                ArrayList<Action> crossoverSeq = crossover(level1Children.get(i), level1Children.get(i + 1), gs.copy());
                ExperimentalEMCTSTreeNode child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
                eval(gs.copy(), child);
                level2Children.add(child);
                allCrossoverChildren.add(child);
            }
        }

        // Level 3: Create 1 child from 2 level2 children (1 pair)
        ExperimentalEMCTSTreeNode level3Child = null;
        if (level2Children.size() >= 2) {
            ArrayList<Action> crossoverSeq = crossover(level2Children.get(0), level2Children.get(1), gs.copy());
            level3Child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
            eval(gs.copy(), level3Child);
            allCrossoverChildren.add(level3Child);
        }

        // Find the best among all 7 crossover children
        ExperimentalEMCTSTreeNode bestCrossoverChild = allCrossoverChildren.get(0);
        for (ExperimentalEMCTSTreeNode child : allCrossoverChildren) {
            if (child.getValue() > bestCrossoverChild.getValue()) {
                bestCrossoverChild = child;
            }
        }

        // Find the best among the original parent nodes
        ExperimentalEMCTSTreeNode bestParent = topEight.get(0);
        for (ExperimentalEMCTSTreeNode parent : topEight) {
            if (parent.getValue() > bestParent.getValue()) {
                bestParent = parent;
            }
        }

        // Compare with the best parent node and return the winner
        ExperimentalEMCTSTreeNode newRootCandidate;
        if (bestCrossoverChild.getValue() > bestParent.getValue()) {
            newRootCandidate = bestCrossoverChild;
        } else {
            newRootCandidate = bestParent;
        }

        if (newRootCandidate.getValue() > bestNode.getValue()) {
            bestNode = newRootCandidate;
        }
        return newRootCandidate;
    }

    /**
     * Performs uniform crossover on two parent action sequences.
     * For each position, randomly selects an action from either parent using m_rnd.nextBoolean().
     * The resulting sequence is then repaired using greedy selection to ensure action validity.
     * 
     * @param p1 First parent node
     * @param p2 Second parent node
     * @param gs Current game state for action validation and greedy evaluation
     * @return An ArrayList of actions representing the crossed-over and repaired sequence
     */
    protected ArrayList<Action> crossover(ExperimentalEMCTSTreeNode p1, ExperimentalEMCTSTreeNode p2, GameState gs) {
        ArrayList<Action> seq1 = p1.getSequence();
        ArrayList<Action> seq2 = p2.getSequence();
        ArrayList<Action> crossoverSeq = new ArrayList<>();

        // Determine the length of the crossover sequence (use the shorter parent length)
        int maxLength = Math.min(seq1.size(), seq2.size());

        // Perform uniform crossover: for each position, randomly select from either parent
        for (int i = 0; i < maxLength; i++) {
            if (m_rnd.nextBoolean()) {
                crossoverSeq.add(seq1.get(i));
            } else {
                crossoverSeq.add(seq2.get(i));
            }
        }

        // Repair the crossover sequence using greedy evaluation
        ArrayList<Action> repairedSeq = new ArrayList<>();
        GameState testGs = gs.copy();

        for (int i = 0; i < crossoverSeq.size(); i++) {
            if (!(testGs.getActiveTribeID() == getPlayerID())) {
                // Game state no longer under our control, stop repair
                break;
            }

            Action currentAction = crossoverSeq.get(i);
            boolean isValid = false;

            // Check if the current action is feasible
            try {
                isValid = checkActionFeasibility(currentAction, testGs.copy());
            } catch (Exception e) {
                isValid = false;
            }

            if (isValid) {
                // Action is valid, add it and advance state
                repairedSeq.add(currentAction);
                advance(testGs, currentAction);
            } else {
                // Action is invalid - use greedy selection to find best replacement
                ArrayList<Action> allAvailableActions = this.allGoodActions(testGs.copy(), m_rnd);

                if (allAvailableActions.isEmpty()) {
                    // No actions available, stop repair
                    break;
                }

                Action bestAction = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                int numTies = 0;

                for (Action candidateAction : allAvailableActions) {
                    GameState candidateGs = testGs.copy();
                    advance(candidateGs, candidateAction);
                    double score = heuristic.evaluateState(old_gs, candidateGs);

                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidateAction;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) {
                            bestAction = candidateAction;
                        }
                    }
                }

                if (bestAction != null) {
                    repairedSeq.add(bestAction);
                    advance(testGs, bestAction);
                } else {
                    // No viable action found, stop repair
                    break;
                }
            }
        }

        // Ensure repaired sequence ends with END_TURN
        if (!repairedSeq.isEmpty() && 
            repairedSeq.get(repairedSeq.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(testGs, m_rnd);
            Action endAction = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    endAction = a;
                    break;
                }
            }
            if (endAction != null) {
                repairedSeq.add(endAction);
            }
        }

        return repairedSeq;
    }
}