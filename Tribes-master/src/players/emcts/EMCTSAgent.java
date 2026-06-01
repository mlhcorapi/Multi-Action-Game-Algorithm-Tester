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
import core.data.dto.TurnData;
import core.data.dto.BridgeBurnEventData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;



public class EMCTSAgent extends Agent {

    private List<TurnData> turnDataLog = new ArrayList<>();
    private List<BridgeBurnEventData> bridgeBurnDataLog = new ArrayList<>();

    private Random m_rnd;
    private StateHeuristic heuristic;
    private EMCTSParams params;

    private EMCTSTreeNode root;
    private EMCTSTreeNode bestNode;
    private GameState old_gs;

    private int fmCallsCount;
    private int fmCallsRun;
    private int totalNodesVisited;
    // Per-turn planning state: the engine calls act() once per action, so we plan the
    // entire turn once (a single phased search) and replay the cached best action
    // sequence across the turn's subsequent act() calls instead of re-searching.
    private ArrayList<Action> turnPlan = new ArrayList<>();
    private int plannedTick = -1;
    // Wall-clock baseline for event timestamps: the start of the turn (set when planning).
    private long turnStartTimeMillis;

    public EMCTSAgent(long seed, EMCTSParams params) {
        super(seed);
        m_rnd = new Random(seed);
        this.params = params;
    }

    @Override
    public List<TurnData> getTurnDataLog() {
        return turnDataLog;
    }

    @Override
    public List<BridgeBurnEventData> getBridgeBurnDataLog() {
        return bridgeBurnDataLog;
    }

    @Override
    public int getNodesVisited() {
        return totalNodesVisited;
    }

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        // The engine calls act() once per action. Plan the ENTIRE turn with a single
        // phased search on the first act() of the turn, then replay the cached best
        // action sequence on subsequent calls. A change in game tick marks a new turn.
        int tick = gs.getTick();
        if (tick != plannedTick) {
            planTurn(gs, ect);
            plannedTick = tick;
        }

        // Tier 1 (Salvage): next action from the cached best macro-sequence.
        Action next = null;
        if (!turnPlan.isEmpty()) {
            Action candidate = turnPlan.remove(0);
            if (candidate != null && candidate.isFeasible(gs)) {
                next = candidate;
            }
        }
        // Tier 2 (Greedy Random): cached step broke; pick a random valid action.
        if (next == null) {
            ArrayList<Action> availableActions = this.allGoodActions(gs, m_rnd);
            if (availableActions != null && !availableActions.isEmpty()) {
                next = availableActions.get(m_rnd.nextInt(availableActions.size()));
            }
        }
        // Tier 3 (End Turn): nothing valid left.
        if (next == null) {
            next = new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
        }
        return next;
    }

    /**
     * Plans the whole turn with a single phased (bridge-burning) search and caches the
     * best full action sequence. Records Initial/Final sequence values, nodesVisited
     * (search iterations), and bridge-burn events for this turn.
     */
    private void planTurn(GameState gs, ElapsedCpuTimer ect) {
        turnStartTimeMillis = System.currentTimeMillis();
        this.fmCallsCount = 0;
        this.totalNodesVisited = 0;
        this.heuristic = params.getStateHeuristic(playerID, allPlayerIDs);

        old_gs = gs.copy();

        // Player score at the very start of the turn (for the score-progression column).
        int scoreAtTurnStart = gs.getScore(playerID);

        // Initialize root with 1-ply greedy initialization
        root = randomActions(gs.copy());
        eval(gs.copy(), root);

        double initialValue = root.getValue();

        bestNode = root;
        fmCallsRun = 0;
        int sequencesEvaluated = 0; // distinct candidate sequences evaluated this turn (one per search iteration)

        // Phase-based bridge burning approach
        long totalTimeMs = ect.remainingTimeMillis();
        long phaseTimeMs = Math.max(1, totalTimeMs / Math.max(1, params.depth));
        int currentPhase = 0;
        int remainingLimit = 5;
        int bridgeBurningPhaseNumber = 0;

        boolean stop = false;
        int numIters = 0;
        double avgTimeTaken = 0;
        double acumTimeTaken = 0;

        while (!stop && currentPhase < params.depth) {
            ElapsedCpuTimer phaseTimer = new ElapsedCpuTimer();
            long phaseStartFmCalls = fmCallsCount;
            int phaseStartIters = numIters;
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
                sequencesEvaluated++;

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

                // Phase completion is gated by the SAME budget as the stop criterion,
                // so bridge burning triggers between phases regardless of stop_type.
                boolean phaseBudgetExhausted;
                if (params.stop_type == params.STOP_FMCALLS) {
                    long fmCallsPerPhase = Math.max(1, params.num_fmcalls / Math.max(1, params.depth));
                    phaseBudgetExhausted = (fmCallsCount - phaseStartFmCalls) >= fmCallsPerPhase;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    int itersPerPhase = Math.max(1, params.num_iterations / Math.max(1, params.depth));
                    phaseBudgetExhausted = (numIters - phaseStartIters) >= itersPerPhase;
                } else {
                    phaseBudgetExhausted = phaseTimer.elapsedMillis() >= phaseTimeMs;
                }
                if (phaseBudgetExhausted) {
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
                    bestChild.unlink();  // Clears parent link safely
                    root = bestChild;
                    
                    bridgeBurningPhaseNumber++;
                    // Log the best sequence value found SO FAR (bestNode), not the promoted
                    // root's value (which degrades as the search dives) - this is the
                    // monotonic "expansion phase" curve.
                    long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTimeMillis;
                    bridgeBurnDataLog.add(new BridgeBurnEventData(-1, gs.getTick(), expansionPhaseTimestamp, bridgeBurningPhaseNumber, bestNode.getValue(), this.fmCallsCount));

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

        // Telemetry for this single turn-planning episode:
        // Initial = greedy root (pre-search); Final = best node found (post-search);
        // nodesVisited = number of search iterations this turn.
        // nodesVisited = forward-model calls this turn (the computational-workload metric;
        // dominated by sequence evaluations + greedy repair, in the thousands at real budgets).
        this.totalNodesVisited = this.fmCallsCount;
        double finalValue = bestNode.getValue();
        turnDataLog.add(new TurnData(-1, gs.getTick(), initialValue, finalValue, totalNodesVisited, scoreAtTurnStart, sequencesEvaluated));

        // Cache the best full action sequence; act() replays it over the turn.
        ArrayList<Action> bestSeq = bestNode.getSequence();
        turnPlan = (bestSeq != null) ? new ArrayList<>(bestSeq) : new ArrayList<>();
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
        if (children == null || children.size() == 0) {
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

    /** Truncate a sequence at the first END_TURN (single-turn plan). In solo games the active
     *  tribe does not change after END_TURN, so builders would otherwise run into future turns. */
    private ArrayList<Action> truncateAtEndTurn(ArrayList<Action> seq) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : seq) {
            out.add(a);
            if (a != null && a.getActionType() == Types.ACTION.END_TURN) break;
        }
        return out;
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
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
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