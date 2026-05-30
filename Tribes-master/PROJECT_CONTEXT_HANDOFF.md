PROJECT CONTEXT HANDOFF: Sessions 1 & 2 Complete
1. DTOs and Tracking Fields
We have established four pure, thread-safe (immutable) Data Transfer Objects located in src/core/data/dto/ to transport metrics out of the search agents:

GameSummaryData:
agentName (String)
gameId (int)
gameDurationTurns (int)
gameOutcomeScore (int)
TurnData:
gameId (int)
turnNumber (int)
initialSequenceValue (double)
finalSequenceValue (double)
nodesVisited (int)
BridgeBurnEventData:
gameId (int)
turnNumber (int)
expansionPhaseTimestamp (long)
bridgeBurningPhaseNumber (int)
newRootNodeValue (double)
CrossoverEventData:
gameId (int)
turnNumber (int)
expansionPhaseTimestamp (long)
bridgeBurningPhaseNumber (int)
bestSequenceBefore (double)
bestSequenceAfter (double)
rawImprovement (double)
percentageImprovement (double)
2. CsvDataHarvester
The CsvDataHarvester (src/core/data/CsvDataHarvester.java) is a synchronous file-writing utility designed to be thread-safe by exclusively being called from the main thread. It forces the creation of a test_results/ directory.

It supports appending to three distinct phase-based output files and handles header creation on the first write. To make downstream Pandas parsing easier, GameSummaryData and TurnData are combined into a single flattened row:

Phase_X_Summary.csv: Contains GameSummaryData fields merged with each TurnData instance.
Phase_X_Bridge_Burns.csv: Contains the BridgeBurnEventData metrics.
Phase_X_Crossovers.csv: Contains the CrossoverEventData metrics.
3. Multithreaded Architecture
The execution engine is managed by the newly created ExperimentRunner (src/core/data/ExperimentRunner.java).

Thread Limitation (Critical): It uses an ExecutorService capped rigidly at 10 threads via Executors.newFixedThreadPool(10) to prevent the 13th Gen i7 host machine from locking up.
Worker Pattern: Each simulation is wrapped in a GameSimulationWorker (Callable<SimulationResult>). The worker executes the game, retrieves DTO logs from the targeted Agent's exposed hooks, and returns a compiled SimulationResult.
Timeout & Safety: The Future.get() call imposes a 30-second timeout per simulation to break out of runaway search trees.
Disk Writing Constraint: Only the Main Thread is permitted to read resolved Futures and pass the data to the CsvDataHarvester.
4. Integration Blueprint (Hooks)
The architecture intercepts game metrics safely without introducing state mutations into core game processes. The Agent.java class has been provided with the following stub hooks:

// DTO Collection Exposer Hooks
public List<TurnData> getTurnDataLog() { return Collections.emptyList(); }
public List<BridgeBurnEventData> getBridgeBurnDataLog() { return Collections.emptyList(); }
public List<CrossoverEventData> getCrossoverDataLog() { return Collections.emptyList(); }
In Session 3: Agent Instrumentation, the goal will be to implement internal Lists (or thread-safe collections) inside the specific target agents (MCTS, EMCTS, OEP, etc.) to populate these DTOs during their decision-making phase and override these getter hooks to return the populated data arrays to the ExperimentRunner when requested at the end of the game.

