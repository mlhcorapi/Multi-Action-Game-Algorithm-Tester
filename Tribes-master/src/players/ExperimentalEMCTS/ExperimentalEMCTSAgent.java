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
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;
import core.data.dto.BridgeBurnEventData;


public class ExperimentalEMCTSAgent extends Agent {

    public ArrayList<CrossoverEventData> crossoverDataList = new ArrayList<>();
    public ArrayList<TurnData> turnDataList = new ArrayList<>();
    public ArrayList<BridgeBurnEventData> bridgeBurnDataList = new ArrayList<>();

    @Override
    public List<CrossoverEventData> getCrossoverDataLog() {
        return crossoverDataList;
    }

    @Override
    public List<TurnData> getTurnDataLog() {
        return turnDataList;
    }

    @Override
    public List<BridgeBurnEventData> getBridgeBurnDataLog() {
        return bridgeBurnDataList;
    }

    /**
     * Records a bridge-burn (root promotion) event. Shared by every experimental
     * variant so that both standard and crossover bridge burning are captured.
     * gameId is a placeholder (-1) and reassigned by the main thread in ExperimentRunner.
     */
    protected void recordBridgeBurn(int bridgeBurningPhaseNumber, double bestSequenceValue, long turnStartTime, int tick) {
        long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;
        bridgeBurnDataList.add(new BridgeBurnEventData(
                -1, tick, expansionPhaseTimestamp, bridgeBurningPhaseNumber, bestSequenceValue, this.fmCallsCount
        ));
    }

    // Per-turn planning state: the engine calls act() once per action, so we plan the
    // entire turn once (a single phased search) and replay the cached best action
    // sequence across the turn's subsequent act() calls instead of re-searching.
    protected ArrayList<Action> turnPlan = new ArrayList<>();
    protected int plannedTick = -1;
    // Wall-clock baseline for event timestamps: the start of the turn (set when planning).
    protected long turnStartTimeMillis;


    protected Random m_rnd;
    protected StateHeuristic heuristic;
    protected ExperimentalEMCTSParams params;

    protected ExperimentalEMCTSTreeNode root;
    protected ExperimentalEMCTSTreeNode bestNode;
    protected GameState old_gs;

    protected int fmCallsCount;
    protected int fmCallsRun;
    // Per-turn search telemetry (reset each planTurn): nodes scored, value sum (mean), distinct keys.
    protected int nodesEvaluated;
    protected double sumNodeValue;
    protected final java.util.HashSet<String> uniqueSeqKeys = new java.util.HashSet<>();
    // Opt-in forward-model profiling (default OFF => zero overhead in real runs). FmComputeProbe sets
    // PROFILE_FM and reads these accumulators to measure what fraction of planning wall-clock is the
    // forward model (gs.advance()) vs everything else (state copies, heuristic eval, bookkeeping).
    public static volatile boolean PROFILE_FM = false;
    public static long FM_NANOS = 0;
    public static long PLAN_NANOS = 0;
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
        // The engine calls act() once per action. Plan the ENTIRE turn with a single
        // phased search on the first act() of the turn, then replay the cached best
        // action sequence on subsequent calls (no mid-turn re-search). A change in the
        // game tick marks a new turn - robust even if the engine auto-ends a turn.
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
     * best full action sequence in turnPlan. Records this turn's telemetry:
     * InitialSequenceValue (greedy root, pre-search), FinalSequenceValue (best node,
     * post-search), nodesVisited (search iterations), and bridge-burn / crossover events.
     */
    protected void planTurn(GameState gs, ElapsedCpuTimer ect) {
        turnStartTimeMillis = System.currentTimeMillis();
        long __planStart = PROFILE_FM ? System.nanoTime() : 0L;

        this.fmCallsCount = 0;
        this.nodesEvaluated = 0;
        this.sumNodeValue = 0.0;
        this.uniqueSeqKeys.clear();
        core.game.ComputeMeter.reset(); // deterministic compute-unit budget (STOP_COMPUTE)
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

        // Initialize PriorityQueue for tracking top nodes (min heap by value to keep the top K elements)
        topNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        topNodesQueue.offer(root);

        globalTopNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        globalTopNodesQueue.offer(root);
        crossoverExpansionCounter = 0;

        // ---- Phase-based bridge burning (FIXED burn count for every agent) ----
        // The turn's search is split into params.depth (=21) phases; a bridge burn fires at each
        // of the first depth-1 (=20) phase boundaries. The number of burns is therefore FIXED at
        // 20 for EVERY agent - standard and crossover alike - so cross-model variance can never be
        // attributed to one family pruning (burning) more or less than another.
        //
        // Each phase is allotted an equal CUMULATIVE slice of the budget: a phase runs MCTS
        // iterations until the running total reaches that phase's ceiling = (phase+1)*budget/depth.
        // Because the ceiling is cumulative, the forward-model calls a crossover bridge-burn
        // tournament spends are absorbed by the NEXT phase doing proportionally fewer iterations.
        // Net effect: every agent consumes the SAME total budget (num_fmcalls / turn time) AND
        // performs the SAME 20 burns; crossover agents simply evaluate fewer distinct sequences
        // (the cost of their tournaments) - which is the effect under study, not a difference in
        // the amount of pruning.
        long turnBudgetTime = ect.remainingTimeMillis();
        ElapsedCpuTimer turnTimer = new ElapsedCpuTimer();
        long fmPerPhase      = Math.max(1, params.num_fmcalls      / Math.max(1, params.depth));
        long computePerPhase = Math.max(1, params.num_computeUnits / Math.max(1, params.depth));
        long itersPerPhase   = Math.max(1, params.num_iterations   / Math.max(1, params.depth));
        long timePerPhase    = Math.max(1, turnBudgetTime          / Math.max(1, params.depth));
        final int MAX_ITERS_PER_PHASE = 1_000_000; // safety net against a no-progress stall
        int numIters = 0;

        for (int currentPhase = 0; currentPhase < params.depth; currentPhase++) {
            // Cumulative budget ceiling at the end of this phase's MCTS iterations.
            long fmCeiling        = (long) (currentPhase + 1) * fmPerPhase;
            double computeCeiling = (double) (currentPhase + 1) * computePerPhase;
            long iterCeiling      = (long) (currentPhase + 1) * itersPerPhase;
            long timeCeiling      = (long) (currentPhase + 1) * timePerPhase;
            int phaseStartIters = numIters;

            // Run iterations until this phase's cumulative budget ceiling is reached. We only
            // force an extra iteration PAST the ceiling when a non-final phase still has no
            // promotable child for its upcoming burn. Agents whose burn inherits a subtree (the
            // common case) therefore do ZERO wasted iterations once over budget, so total spend
            // stays at num_fmcalls / turn time while all 20 burns are still guaranteed.
            while (true) {
                boolean ceilingReached;
                if (params.stop_type == params.STOP_TIME) {
                    ceilingReached = turnTimer.elapsedMillis() >= timeCeiling;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    ceilingReached = numIters >= iterCeiling;
                } else if (params.stop_type == params.STOP_COMPUTE) {
                    ceilingReached = core.game.ComputeMeter.computeUnits() >= computeCeiling;
                } else { // STOP_FMCALLS (default)
                    ceilingReached = fmCallsCount >= fmCeiling;
                }
                boolean needChildForBurn = (currentPhase < params.depth - 1) && root.getChildren().isEmpty();
                if (ceilingReached && !needChildForBurn) break;
                if ((numIters - phaseStartIters) >= MAX_ITERS_PER_PHASE) break;

                numIters++;
                fmCallsRun = 0;

                // Standard MCTS iteration: select, mutate once (or crossover-expand), evaluate, backpropagate.
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
            }

            // Bridge burn at every phase boundary except the last: FIXED at depth-1 = 20 burns,
            // independent of the budget. Standard agents promote the best child (free); crossover
            // agents breed a new root via the tournament (which spends forward-model calls).
            if (currentPhase < params.depth - 1 && root.getChildren().size() > 0) {
                ExperimentalEMCTSTreeNode newRoot;
                if (params.useCrossoverBridgeBurning && topNodesQueue.size() >= 2) {
                    newRoot = performCrossoverBridgeBurning(gs.copy(), currentPhase, turnStartTimeMillis);
                } else {
                    newRoot = findBestChild(root);
                }

                if (newRoot != null && newRoot != root) {
                    // Unlink old root branches except the new root
                    for (ExperimentalEMCTSTreeNode child : root.getChildren()) {
                        if (child != newRoot) {
                            unlinkTree(child, newRoot);
                        }
                    }

                    newRoot.disconnectParent();  // Clears parent link
                    root = newRoot;

                    // Clear and reset queue for next phase
                    topNodesQueue.clear();
                    topNodesQueue.offer(root);

                    // Record the bridge-burn trajectory point (1-indexed phase number), logging the
                    // best sequence value found SO FAR (bestNode) - the monotonic expansion curve.
                    recordBridgeBurn(currentPhase + 1, bestNode.getValue(), turnStartTimeMillis, gs.getTick());
                }
                // If no promotable child exists (only possible on a degenerate short turn), we skip
                // this single burn; that limit applies identically to every agent, so it is unbiased.
            }
        }

        // Telemetry for this single turn-planning episode:
        // Initial = greedy root (pre-search); Final = best node found (post-search);
        // nodesVisited = forward-model calls this turn (the computational-workload metric;
        // dominated by sequence evaluations + greedy repair, in the thousands at real budgets).
        double finalValue = bestNode.getValue();
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        turnDataList.add(new TurnData(
                -1, gs.getTick(), scoreAtTurnStart, initialValue, finalValue, avgNodeValue,
                this.fmCallsCount, nodesEvaluated, uniqueSeqKeys.size()
        ));

        // Cache the best full action sequence; act() replays it over the turn.
        ArrayList<Action> bestSeq = bestNode.getSequence();
        turnPlan = (bestSeq != null) ? new ArrayList<>(bestSeq) : new ArrayList<>();
        if (PROFILE_FM) PLAN_NANOS += System.nanoTime() - __planStart;
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
        // Telemetry: each eval() scores one candidate node (main-loop children, the initial root,
        // and bridge-burn tournament children). Count it (duplicates included), accumulate its value
        // (for the per-turn mean), and record its sequence key (distinct-sequence count).
        nodesEvaluated++;
        sumNodeValue += node.getValue();
        uniqueSeqKeys.add(seqKey(node.getSequence()));
    }

    /** Canonical key for an action sequence (for de-duplication), composed from each action's
     *  content toString(). */
    protected String seqKey(ArrayList<Action> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Action a : seq) sb.append(a == null ? "_" : a.toString()).append('|');
        return sb.toString();
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
        if (PROFILE_FM) {
            long __t0 = System.nanoTime();
            gs.advance(move, true);
            FM_NANOS += System.nanoTime() - __t0;
        } else {
            gs.advance(move, true);
        }
    }

    /**
     * A turn's action sequence must end at the FIRST END_TURN. In solo (1-tribe) games the
     * active tribe does not change after END_TURN, so the mutate/crossover/repair builders
     * would otherwise keep generating actions PAST it into future turns, producing multi-turn
     * plans the agent never executes (and inflating the final-sequence-value metric). Truncate.
     */
    protected ArrayList<Action> truncateAtEndTurn(ArrayList<Action> seq) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : seq) {
            out.add(a);
            if (a != null && a.getActionType() == Types.ACTION.END_TURN) break;
        }
        return out;
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
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
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
    protected ExperimentalEMCTSTreeNode performCrossoverBridgeBurning(GameState gs, int currentPhase, long turnStartTime) {
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

        // Find the best among the up-to-8 elite parents (crossover MEASUREMENT baseline only).
        ExperimentalEMCTSTreeNode bestParent = topEight.get(0);
        for (ExperimentalEMCTSTreeNode parent : topEight) {
            if (parent.getValue() > bestParent.getValue()) {
                bestParent = parent;
            }
        }

        // New-root selection (per the ALGORITHMS spec): the standard bridge-burning target
        // is the best immediate child of the root - identical to EMCTS / CrossoverExpansion.
        // The bred candidate only replaces it if it strictly beats it. This keeps the
        // non-crossover path identical to the baseline, so the experiment isolates the
        // impact of crossover rather than also changing the fallback to "best of 8 elite".
        ExperimentalEMCTSTreeNode standardBestChild = findBestChild(root);
        ExperimentalEMCTSTreeNode newRootCandidate;
        if (standardBestChild == null) {
            newRootCandidate = bestCrossoverChild;
        } else if (bestCrossoverChild.getValue() > standardBestChild.getValue()) {
            newRootCandidate = bestCrossoverChild;
        } else {
            newRootCandidate = standardBestChild;
        }

        if (newRootCandidate.getValue() > bestNode.getValue()) {
            bestNode = newRootCandidate;
        }

        // bestSequenceBefore: heuristic value of the BEST of the up-to-8 elite parent nodes
        //   selected at the end of the previous search phase (before any breeding).
        // bestSequenceAfter: heuristic value of the BEST of the 7 bred children, measured
        //   directly off the crossover generation and BEFORE the tournament fallback that
        //   may keep the parent — so this isolates breeding efficacy, not the final root.
        double scoreBefore = bestParent.getValue();
        double scoreAfter = bestCrossoverChild.getValue();

        double rawImprovement = scoreAfter - scoreBefore;
        double percentageImprovement = (scoreBefore == 0.0) ? 0.0 : ((rawImprovement) / Math.abs(scoreBefore)) * 100.0;

        long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;
        // Use 1-indexed phase number (currentPhase + 1) so this crossover event aligns
        // with the BridgeBurnEventData recorded for the same promotion (which logs the
        // post-increment phase), letting the two files join on (GameId, TurnNumber, phase).
        crossoverDataList.add(new CrossoverEventData(
                -1, gs.getTick(), expansionPhaseTimestamp, currentPhase + 1, scoreBefore, scoreAfter, rawImprovement, percentageImprovement, this.fmCallsCount
        ));

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

        return truncateAtEndTurn(repairedSeq);
    }
}