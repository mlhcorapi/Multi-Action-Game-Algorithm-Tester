package players.emcts;

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
import java.util.Random;



public class EMCTSAgent extends Agent {

    private Random m_rnd;
    private StateHeuristic heuristic;
    private EMCTSParams params;

    private EMCTSTreeNode root;
    private EMCTSTreeNode bestNode;
    private GameState old_gs;

    private int fmCallsCount;
    private int fmCallsRun;


    public EMCTSAgent(long seed, EMCTSParams params) {
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
                EMCTSTreeNode toMutate = nodeToExpand();
                EMCTSTreeNode child = mutate(toMutate, gs.copy());
                eval(gs.copy(), child);

                if (child.getValue() > bestNode.getValue()) {
                    bestNode = child;
                }

                child.visited();

                // Backpropagate score up the tree (standard UCB update)
                EMCTSTreeNode current = child;
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
                EMCTSTreeNode bestChild = findBestChild(root);
                
                if (bestChild != null && bestChild != root) {
                    // Unlink old root branches except best path
                    for (EMCTSTreeNode child : root.getChildren()) {
                        if (child != bestChild) {
                            unlinkTree(child);
                        }
                    }
                    
                    // Update root and clear old parent reference
                    bestChild.unlink();  // Clears parent link
                    root = bestChild;
                    
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

        if (bestNode.getSequence() == null || bestNode.getSequence().isEmpty()) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            if (!allAvailableActions.isEmpty()) {
                return allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
            }
            if (gs.canEndTurn(gs.getActiveTribeID())) {
                return new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
            }
            return null;
        }
        Action nextAct = bestNode.returnNext();
        if (!nextAct.isFeasible(gs)) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            if (!allAvailableActions.isEmpty()) {
                return allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
            }
            if (gs.canEndTurn(gs.getActiveTribeID())) {
                return new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
            }
            return null;
        }
        return nextAct;
    }

    @Override
    public Agent copy() {
        return null;
    }

    public void eval(GameState gs, EMCTSTreeNode node) {
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

    private EMCTSTreeNode nodeToExpand() {
        ArrayList<EMCTSTreeNode> children = root.getChildren();

        // If root has no children, return root for first mutation
        if (children.size() == 0) {
            return root;
        }

        // Pure exploitation traversal: traverse down the tree by always selecting
        // the child with the highest score (value-based only, no exploration term)
        EMCTSTreeNode current = root;

        while (true) {
            if (current == null) {
                return root;
            }
            ArrayList<EMCTSTreeNode> currentChildren = current.getChildren();

            if (currentChildren == null) {
                return current;
            }
            // If no children, this is a leaf node - return it for mutation
            if (currentChildren.size() == 0) {
                return current;
            }

            // Select child with highest score (pure exploitation)
            EMCTSTreeNode bestChild = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (EMCTSTreeNode child : currentChildren) {
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
    private EMCTSTreeNode randomActions(GameState gs) {
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

        EMCTSTreeNode node = new EMCTSTreeNode(individual, null);
        node.setGs(gs.copy());
        return node;
    }


    private void advance(GameState gs, Action move) {
        this.fmCallsCount++;
        this.fmCallsRun++;
        gs.advance(move, true);
    }

    //mutate a random action
    private EMCTSTreeNode mutate(EMCTSTreeNode node, GameState gs) {
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
        EMCTSTreeNode newNode = new EMCTSTreeNode(newSeq, node);
        node.addChild(newNode);

        return newNode;
    }

    /**
     * Repair a sequence of actions, ensuring feasibility and validity.
     * Uses 1-ply greedy repair: if an action fails, replace it by selecting
     * the available action with the highest heuristic score.
     */
    private ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
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

    private boolean checkActionFeasibility(Action a, GameState gs) {
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
    private void extend(EMCTSTreeNode node, GameState gs) {
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
        EMCTSTreeNode newNode = new EMCTSTreeNode(newActions, node.getParent());
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
    private EMCTSTreeNode findBestChild(EMCTSTreeNode node) {
        ArrayList<EMCTSTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            return null;
        }

        EMCTSTreeNode bestChild = children.get(0);
        double bestValue = bestChild.getValue();

        for (EMCTSTreeNode child : children) {
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
    private void unlinkTree(EMCTSTreeNode node) {
        if (node == null) {
            return;
        }

        ArrayList<EMCTSTreeNode> children = node.getChildren();
        if (children != null) {
            for (EMCTSTreeNode child : children) {
                unlinkTree(child);
            }
        }

        node.unlink();
    }
}