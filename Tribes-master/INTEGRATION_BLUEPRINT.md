# INTEGRATION BLUEPRINT: Data Harvester Hooks

This document serves as the architectural blueprint indicating where the DTO data harvesters will hook into the execution loops of the respective agents. The base `Agent.java` class exposes the following interfaces which main execution threads will call at the end of the simulation:

```java
public List<TurnData> getTurnDataLog()
public List<BridgeBurnEventData> getBridgeBurnDataLog()
public List<CrossoverEventData> getCrossoverDataLog()
```

## 1. Baseline Agents Integration Points

### MCTS & EMCTS Search Loop
* **Turn Start Hook**: At the beginning of the `act` method, capture `System.currentTimeMillis()`. Evaluate the root node or starting sequence selection policy to assign `initial_sequence_value`.
* **Search Execution**: Accumulate unique node expansions in a local primitive counter to set `nodes_visited`.
* **Bridge Burn Trigger (EMCTS Only)**: Within the tree search loop, if bridge-burn criteria is met mid-turn:
  * Increment `bridge_burning_phase_number`.
  * Calculate `expansion_phase_timestamp` by subtracting turn start time from `System.currentTimeMillis()`.
  * Capture the evaluation of the newly allocated root node for `new_root_node_value`.
  * Instantiate a `BridgeBurnEventData` object and add it to the agent's internal list.
* **Turn End Hook**: Right before returning the selected action in `act`, capture the final selection value for `final_sequence_value`. Instantiate a `TurnData` object and add it to the agent's internal list.

### OEP Search Loop
* **Turn Start Hook**: At the beginning of the `act` method, track start time and assign `initial_sequence_value` from the initial generation's best individual evaluation.
* **Search Execution**: Bypass node expansion tracking entirely. Hardcode `nodes_visited` output strictly to `-1`.
* **Turn End Hook**: Right before returning the selected action, assign `final_sequence_value` using the final generation's best evolved individual evaluation. Instantiate a `TurnData` object and add it to the agent's internal list.

## 2. Experimental Agents Integration Points

### CrossoverExpansion Loop
* **Crossover Expansion Trigger**: When a crossover-driven tree expansion phase finishes within the search loop, capture `System.currentTimeMillis()` minus turn start time to save to `expansion_phase_timestamp`. Record the sequence value under the phase marker `99` if it represents the final layout milestone.

### CrossoverBB & CrossoverCombo Loop
* **Crossover Phase Hook**: Right before the crossover operation executes on the candidate pool, evaluate and cache the score of the existing best sequence (`bestSequenceBefore`). Immediately after the crossover routine runs, evaluate the resulting sequences to find the new best sequence score (`bestSequenceAfter`).
* **Direct Math Calculations**: Compute the raw difference and the percentage delta using the safe absolute value formula: `((After - Before) / Math.abs(Before)) * 100`. Handle division-by-zero safely by defaulting to `0.0` if `Before == 0`.
* **Bridge Burn Trigger**: Combine the crossover data tracking with the standard bridge-burning counter increments and timestamp deltas. Instantiate a `CrossoverEventData` object and add it to the agent's internal list.
