package players.ExperimentalEMCTS;

import core.data.dto.TurnData;
import core.data.dto.BridgeBurnEventData;
import core.data.dto.CrossoverEventData;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import core.actions.Action;
import core.game.GameState;
import utils.ElapsedCpuTimer;
import java.util.PriorityQueue;

public class CrossoverComboAgent extends ExperimentalEMCTSAgent {

    private List<TurnData> turnDataLog = new ArrayList<>();
    private List<CrossoverEventData> crossoverDataLog = new ArrayList<>();

    public CrossoverComboAgent(long seed) {
        super(seed, createParams());
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.heuristic_method = params.DIFF_HEURISTIC;
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = true;
        return params;
    }

    @Override
    public CrossoverComboAgent copy() {
        return new CrossoverComboAgent(getSeed());
    }


    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        long turnStartTime = System.currentTimeMillis();
        int nodesVisited = 0;
        int bridgeBurningPhaseNumber = 0;

        this.fmCallsCount = 0;
        this.heuristic = params.getStateHeuristic(playerID, allPlayerIDs);

        old_gs = gs.copy();

        // Initialize root with 1-ply greedy initialization
        root = randomActions(gs.copy());
        eval(gs.copy(), root);

        double initialSequenceValue = root.getValue();

        // --- BASELINE TRACKING ROW (START) ---
        crossoverDataLog.add(new CrossoverEventData(
                -1, gs.getTick(), 0, 0, initialSequenceValue, initialSequenceValue, 0.0, 0.0
        ));

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
                        nodesVisited++;
                    } else {
                        child = mutate(toMutate, gs.copy());
                        nodesVisited++;
                    }
                } else {
                    child = mutate(toMutate, gs.copy());
                    nodesVisited++;
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

                    double bestSequenceBefore = bestNode.getValue();

                    newRoot = performCrossoverBridgeBurning(gs.copy());

                    double bestSequenceAfter = bestNode.getValue();
                    double rawImprovement = bestSequenceAfter - bestSequenceBefore;
                    double percentageImprovement = (bestSequenceBefore == 0.0) ? 0.0 : (rawImprovement / Math.abs(bestSequenceBefore)) * 100.0;

                    bridgeBurningPhaseNumber++;
                    long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;

                    crossoverDataLog.add(new CrossoverEventData(
                        -1, gs.getTick(), expansionPhaseTimestamp, bridgeBurningPhaseNumber,
                        bestSequenceBefore, bestSequenceAfter, rawImprovement, percentageImprovement
                    ));

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

        double finalSequenceValue = bestNode.getValue();

        // --- MILESTONE TRACKING ROW (END) ---
        long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;
        crossoverDataLog.add(new CrossoverEventData(
                -1, gs.getTick(), expansionPhaseTimestamp, 99, finalSequenceValue, finalSequenceValue, 0.0, 0.0
        ));

        // --- TURN DATA LOGGING ---
        turnDataLog.add(new TurnData(
                -1, gs.getTick(), initialSequenceValue, finalSequenceValue, nodesVisited
        ));

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
    public List<TurnData> getTurnDataLog() {
        return turnDataLog;
    }

    @Override
    public List<CrossoverEventData> getCrossoverDataLog() {
        return crossoverDataLog;
    }

    @Override
    public List<BridgeBurnEventData> getBridgeBurnDataLog() {
        return Collections.emptyList();
    }
}

