# PROJECT CONTEXT HANDOFF: Session 3 Complete

## Instrumentation of Baseline Agents
We have successfully instrumented the three baseline agents (MCTS, OEP, EMCTS) to harvest `TurnData` and `BridgeBurnEventData` for the multithreaded simulation framework. The data lists are stored locally inside the respective agent instances and are accessible via the `getTurnDataLog()` and `getBridgeBurnDataLog()` hooks overriden from the `Agent` base class.

### 1. MCTSPlayer (`src/players/mcts/MCTSPlayer.java`)
- **Data Log**: Maintains a `List<TurnData> turnDataLog` which is returned by `getTurnDataLog()`.
- **Node Tracking**: `SingleTreeNode` was updated to track `m_numNodes`. It increments its root's node counter whenever `expand()` is called. `MCTSPlayer` retrieves this by calling `m_root.getNumNodes()`.
- **Sequence Values**: `initialSequenceValue` is captured right at the start of `act()` by evaluating the initial game state with the agent's heuristic. `finalSequenceValue` is captured at the very end of `act()` by evaluating a state advanced with the best action chosen by the MCTS procedure.

### 2. OEPAgent (`src/players/oep/OEPAgent.java`)
- **Data Log**: Maintains a `List<TurnData> turnDataLog` which is returned by `getTurnDataLog()`.
- **Node Tracking**: Strictly hardcoded `nodesVisited` to `-1` for all `TurnData` objects created, as OEP does not use standard tree search.
- **Sequence Values**: `initialSequenceValue` is acquired from the best individual of the initial generation by hoisting the evaluation to before the main `numIters` while-loop. The `finalSequenceValue` is obtained from the best fully-evolved individual at the end of the `act()` loop.

### 3. EMCTSAgent (`src/players/emcts/EMCTSAgent.java`)
- **Data Logs**: Maintains a `List<TurnData> turnDataLog` and `List<BridgeBurnEventData> bridgeBurnDataLog`. Returns them via `getTurnDataLog()` and `getBridgeBurnDataLog()`.
- **Node Tracking**: Uses a primitive integer `totalNodesVisited` locally in the `act` function that is incremented alongside every tree mutation (when `nodeToExpand` is followed by `mutate`).
- **Bridge Burning Triggers**: When bridge-burning occurs (promoting the best child to a new root), it registers a `BridgeBurnEventData`. Captures the `expansionPhaseTimestamp` relative to turn start, increments the phase number, and logs the value of the newly established root (`newRootNodeValue`).
- **Sequence Values**: `initialSequenceValue` is grabbed from the fully constructed initial root node (purely exploitative UCB policy). `finalSequenceValue` is evaluated on the final best node at the end of the turn right before acting.
