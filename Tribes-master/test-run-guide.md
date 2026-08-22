# Test-Run Guide — EMCTS Crossover Experiments

This guide explains the experiment phases, how to launch them with a single command,
what gets written, and how to read each output file.

The experiment measures the impact of adding **crossover** to EMCTS, in two forms:
**Crossover Expansion** (crossover during tree growth) and **Crossover Bridge Burning**
(a crossover tournament at phase transitions). Agents:

| Role | Agents |
|---|---|
| Baselines | `MCTS`, `OEP`, `EMCTS` |
| Experimental | `CrossoverExpansion`, `CrossoverBB`, `CrossoverCombo` |

There are **two phases**, run in this order:

- **Phase 3 — Ratio selection + trajectory** (low-variance isolated setup): sweeps the
  CrossoverExpansion mutation:crossover-expansion ratio and records per-turn search trajectories,
  then picks the best ratio **per budget level** by average `FinalSequenceValue`.
- **Phase 2 — Win rates**: experimental-vs-baseline matchups, using **{5 (a fixed standard), the
  per-budget best ratio}** chosen in Phase 3.

> Phase 3 runs **before** Phase 2 because it selects the crossover ratio that Phase 2 needs.
> (The old standalone "Phase 1" ratio analysis was folded into Phase 3's controlled environment —
> see §3 for why.)

---

## 0. Build

From the `Tribes-master/` directory:

```bash
# bash / Git Bash
javac -d out -cp "lib/*" $(find src -name "*.java")
```
```powershell
# Windows PowerShell
javac -d out -cp "lib/*" (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object FullName)
```

Class-path separator is `;` on Windows and `:` on Linux/macOS — adjust the run commands below accordingly.

---

## 1. Run everything (one command)

```bash
java -Xmx8g -cp "out;lib/*" core.data.exp.RunAllExperiments 4
```

- The trailing `4` is the **worker-thread count** (optional, default 4).
- `RunAllExperiments` runs **Phase 3** (ratio sweep + trajectory) → reads the best ratio →
  **Phase 2** (win rates), writing to `test_results/full/{phase3,phase2}/`.
- **It is resumable**: if it is interrupted, just run the same command again — completed
  matchups are skipped (recorded in each phase's `_completed.txt`) and it continues.
- **Crash-resilient**: a game that throws is automatically **re-run with a perturbed RNG seed** (the
  level seed is kept fixed, so Phase 3's map is unchanged), up to 4 attempts, so each matchup still
  reaches its full game count. A game that still fails after all attempts is logged
  (`[warn] … dropped`) and that matchup finishes with one fewer game.
  The `ClassCastException` of a unit to `City` that used to trigger this under deep search was a
  stale-actor-id bug in the action layer and is fixed — see `test/StaleActorIdTest.java`. The retry
  path is kept as a net for anything else (e.g. `OutOfMemoryError`).

You can also run the phases individually (each takes an optional thread count). **Run Phase 3
first** — Phase 2 reads the ratio it writes:

```bash
java -Xmx8g -cp "out;lib/*" core.data.exp.Phase3Runner 2   # FIRST: ratio sweep + trajectory; writes phase3_best_ratio.txt (2 threads for the heavy 5000ms level)
java -Xmx8g -cp "out;lib/*" core.data.exp.Phase2Runner 4   # reads phase3's best ratio
```
(The standalone runners write to `test_results/phaseN/`; the orchestrator writes to `test_results/full/phaseN/`.)

### Threads & memory (important)
Each concurrent game holds a search tree in memory (~0.5–0.6 GB at the 1000 ms-equivalent
budget, more at higher budgets). **Always cap the heap with `-Xmx` below your free RAM** —
this is what prevents the out-of-memory swap-storm. On a 16 GB machine, close other apps and
use `-Xmx8g` with **4 threads**. For Phase 3's heaviest budget (`5000ms`), use 2 threads
(`Phase3Runner 2`) or more heap.

### Progress indication
The console shows, live:
- A **phase banner** with elapsed time (`== PHASE 2 - Win rates  [elapsed 1h 12m 03s]`).
- A **per-matchup counter**: `[Phase3] (7/24) RUN level=1000ms;agent=CrossoverExpansion;ratio=10 (50 games)`.
- A **per-game counter** within each matchup: `    CrossoverExpansion@r10: 40/50`.

### Runtime
Rough order of magnitude on the target machine at 4 threads: **several hours per phase**
(Phase 3's 5000 ms level dominates). It is fully resumable, so it can be run in chunks.

---

## 2. Budgets

All budgets were calibrated so the search effort roughly matches a target *thinking time*
(~26.8 compute-units/ms on the dev machine — re-run `ComputeCalibrate` on your machine to retune):

| Phase | Budget type | Levels |
|---|---|---|
| 3 | **compute-units** (`STOP_COMPUTE`; all participants plan-once → fair, and *deterministic*) | `500ms`≈13400, `1000ms`≈26800, `5000ms`≈134000 |
| 2 | **wall-clock time** (per-action MCTS/OEP vs plan-once need equal *per-turn* time) | 500, 1000 ms |

> **What a "compute-unit" is.** A weighted count of the three operations that dominate planning
> cost: `units = 1.0·advances + 0.16·stateCopies + 0.077·heuristicEvals` (weights are per-op time
> ratios from `ComputeCalibrate`; see `core.game.ComputeMeter`). Forward-model `advance()` calls are
> only ~79 % of total compute (the rest is state copies + heuristic evals, and that fraction *varies
> by agent*), so budgeting on raw fmcalls would quietly hand some agents more real compute than
> others. Counting **compute-units** instead bounds total planning work directly, and because it is a
> deterministic operation count (not wall-clock), search effort is reproducible across runs, machines,
> and thread counts. `FmCalls` is still recorded as the per-turn compute-*demand* metric (see §6).

> Phase 2 uses **time**, not compute-units, on purpose: MCTS/OEP search **per action** while the
> EMCTS-family plan **once per turn**, so a per-turn compute budget would give MCTS/OEP ~K× more
> compute per turn. Wall-clock time is shared across a turn's actions by the engine, so every
> agent gets an equal per-turn budget. Phase 3 uses compute-units (all its participants are plan-once).

> **Why the smallest level is 500 ms, not 200 ms.** Bridge burns are fixed at 20 per turn for every
> agent (see §5). For the crossover-bridge-burning agents those 20 tournaments are *mandatory* and
> cost a large share of a small budget — so a ~200 ms-equivalent budget is below that floor: the
> crossover agents would spend their whole budget (and more) just on the burns and do almost no
> search, overrunning the budget. At 500 ms (≈13400 units) and above, the 20 burns are a modest share
> of the budget, so every agent spends the **same** total compute-units/time (verified with
> `BurnBudgetCheck`). Phase 2 likewise starts at 500 ms.

To recalibrate budgets for your hardware:
```bash
java -cp "out;lib/*" core.data.exp.ComputeCalibrate   # prints per-op weights + suggested compute-unit budgets per target ms
```
Then edit the `defaultLevels()` / `defaultTimesMs()` numbers in the `PhaseNRunner` classes (and the
`W_COPY` / `W_EVAL` weights in `ComputeMeter` if the per-op ratios shift on your hardware).

---

## 3. Phase 3 — Ratio selection + trajectory (runs first)

**Goal:** two jobs in one controlled environment — (1) **choose** the CrossoverExpansion
mutation:crossover-expansion ratio, and (2) measure **how each EMCTS-family agent's search
improves within a turn** (the bridge-burning trajectory) and how its score grows.

**Setup:** SCORE mode, 30-turn limit. Each configuration plays **50** games (the `5000ms` level
plays **25**, since each game there costs ~5× as much and its ratio choice is not used by Phase 2)
on a **fixed map seed** against a **fixed passive opponent** (`DoNothing`, IMPERIUS2); only the
tracked agent's RNG seed varies per game (game *k* uses the same seed across every config), so all
configs face identical conditions and variance is low. Budgets: `500ms`, `1000ms`, `5000ms` (compute-units).

Configurations run:
- `EMCTS` and `CrossoverBB` (ratio-independent),
- `CrossoverExpansion` at each ratio in **{2, 5, 10, 15, 20}** (the sweep),
- `CrossoverCombo` at **{5, the per-level best ratio}** (the standard 5 plus the per-budget best).

**Ratio selection:** for **each budget level**, the best ratio is the one with the highest
**average `FinalSequenceValue`** at that level — the best heuristic value the search reaches. The
tree search optimises heuristic value (it has no direct control over in-game score or combat; it
only finds the highest-scoring sequence it can), so *search effectiveness*, not game outcome, is
the right basis. Selecting **per level** lets the best ratio differ by budget (e.g. 10 at 500 ms,
5 at 1000 ms). Downstream — Phase 2 and this phase's `CrossoverCombo` trajectory — uses
**{5 (a fixed standard baseline), best-for-that-level}** at each level. Result →
`phase3_best_ratio.txt`, consumed by Phase 2.

> **Why select the ratio here, not in a win-rate-vs-EMCTS phase?** Three reasons: (1) no
> opponent-interference confound — the signal is the agent's own search quality, not the
> interaction with a particular opponent; (2) no train/test leakage — the ratio is not tuned on
> EMCTS (an agent Phase 2 later tests against); (3) far lower variance — a continuous fixed-seed
> measure separates ratios with many fewer games than a noisy binary win-rate.
>
> A true 1-tribe map is degenerate (no villages to capture → every agent, even random, stalls at
> ~1 city), so the passive 2nd tribe gives the map expansion opportunities without interfering:
> "agent vs do-nothing on a fixed map" rather than literal solo play.

**Output (`test_results/full/phase3/`):** files are suffixed by budget level (`500ms`, `1000ms`, `5000ms`).

The output is split by experiment into **four** CSVs per level — two for the ratio test
(`CrossoverExpansion` only, all swept ratios) and two for the family trajectory comparison:

| File | Granularity | Columns |
|---|---|---|
| `Phase3_ratio_turns_<level>.csv` | **ratio test** — one row per turn (`CrossoverExpansion`, all swept ratios) | `AgentName, Ratio, GameId, TurnNumber, ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, AvgNodeValue, FmCalls, NodesEvaluated, UniqueSequences, FinalScore` |
| `Phase3_ratio_trajectory_<level>.csv` | **ratio test** — one row per bridge-burn (`CrossoverExpansion`, all swept ratios) | `AgentName, Ratio, GameId, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp, ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, AvgNodeValue, FmCalls, NodesEvaluated, UniqueSequences, FinalScore` |
| `Phase3_standardBB_<level>.csv` | **family trajectory** — the normal bridge burner (`EMCTS`) | `AgentName, GameId, Ratio, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp, ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, AvgNodeValue, FmCalls, NodesEvaluated, UniqueSequences, FinalScore` |
| `Phase3_crossoverBB_<level>.csv` | **family trajectory** — the crossover bridge burners (`CrossoverBB`, `CrossoverCombo`@{5,best}) | …same as standardBB… **plus** `RawImprovement, PercentageImprovement` |
| `phase3_best_ratio.txt` | summary | per-ratio average `FinalSequenceValue` per level + a `BEST_RATIO_<level>=<n>` line per budget level (read automatically by Phase 2) |

- The **ratio-test** CSVs contain only `CrossoverExpansion`, one block per swept ratio; use the
  `Ratio` column for the per-ratio efficiency analysis (`FinalSequenceValue`, `NodesEvaluated`,
  `UniqueSequences` per ratio). (`Ratio` sits after `AgentName` here, but after `GameId` in the
  family-trajectory CSVs — per the agreed schemas.)
- The **family-trajectory** CSVs use `Ratio = NA` for `EMCTS` and `CrossoverBB`; `CrossoverCombo`
  carries its ratio (it runs at {5, best}). The per-turn metrics (`InitialSequenceValue`,
  `FinalSequenceValue`, `AvgNodeValue`, `FmCalls`, `NodesEvaluated`, `UniqueSequences`,
  `ScoreAtTurnStart`, `FinalScore`) ride on each bridge-burn row, so a per-turn summary is a
  group-by `(GameId, TurnNumber)`.
- `BestSequenceValue` = best sequence value found so far at that bridge burn (monotonic within a turn).
- `RawImprovement / PercentageImprovement` (crossover-BB only) = best-of-7-bred-children minus best-of-8-elite.

---

## 4. Phase 2 — Win rates

**Goal:** competitive win rates of each experimental agent vs each baseline.
**Setup:** 9 matchups (3 experimental × 3 baselines; experimental agents do not play each other),
Capitals, 50-turn limit, IMPERIUS vs IMPERIUS2, 100 random-map games per matchup, at 500/1000 ms.
`CrossoverExpansion`/`Combo` run at **{5 (standard), the per-budget best ratio}** from Phase 3 —
each time level uses the best ratio Phase 3 found at the level of the same name (so 500 ms uses
the 500 ms best, 1000 ms uses the 1000 ms best). When the best happens to be 5, only ratio 5 runs.

**Output (`test_results/full/phase2/`):** one file per time level.

| File | Granularity | Columns |
|---|---|---|
| `Phase2_games_<level>.csv` | one row **per agent per game** | `Level, Matchup, GameId, AgentName, Ratio, Won, Winner, AvgInitialSeq, AvgFinalSeq, AvgNodeValue, AvgFmCalls, AvgNodesEvaluated, AvgUniqueSequences, EndTurn` |

- `Won` = 1 if this agent won that game, else 0. Win rate of an agent in a matchup = mean of `Won`.
- The `Avg*` columns are averaged over that agent's turns in the game. Every agent (incl. the MCTS and
  OEP baselines) now logs a final heuristic value (`AvgFinalSeq`) and `AvgFmCalls`/`AvgNodesEvaluated`,
  so search effort and achieved value are comparable across all six agents. `AvgUniqueSequences` and
  `AvgNodeValue` are `-1` for MCTS (a per-action tree search, no sequences).

---

## 5. Metric definitions (read this)

The three workload metrics are kept distinct so algorithm efficiency can be compared properly:
- **`FmCalls` = forward-model `advance()` calls that turn** — the COMPUTE-demand *metric* (recorded for
  every agent so search effort is comparable). It is **not** the Phase-3 budget any more: the budget is
  in **compute-units** (`STOP_COMPUTE`; weighted advances + copies + evals — see §2), because raw
  fmcalls are only ~79 % of real compute and that fraction varies by agent. Identical sequences are
  re-evaluated (no cross-node caching), so every node's forward-model cost is counted.
- **`NodesEvaluated` = candidate nodes scored that turn** (every `eval()`: the initial root, each
  search iteration's child, and each bridge-burn tournament child) — **duplicates included**. For MCTS
  this is the MCTS tree-node count; for OEP, the number of individual evaluations.
- **`UniqueSequences` = distinct action sequences among those evaluated** (de-duplicated by a per-action
  key). Independent of bridge burning (no floor). `-1` for MCTS (a per-action tree search, no sequences).
- **`AvgNodeValue` = mean heuristic value over all nodes evaluated that turn** (search quality). `-1` for MCTS.
- **Efficiency ratios** (computed in analysis): `NodesEvaluated / FmCalls`, `UniqueSequences / FmCalls`,
  `UniqueSequences / NodesEvaluated`, value-per-node. The crossover-bridge-burning variants spend FM
  calls on their per-transition tournaments, so for the same `FmCalls` they evaluate fewer nodes / fewer
  unique sequences than `EMCTS`/`Expansion` — that is the efficiency cost under study.
- **Bridge burns are fixed at 20 per turn for *every* agent** (`depth − 1`, `depth = 21`), so the
  *number* of burns is never a confound between families (each turn's search is split into 21 phases,
  a burn at each of the first 20 boundaries; a crossover tournament's compute-units are charged against
  the shared budget and absorbed by later phases). The crossover cost shows up as lower `NodesEvaluated` /
  `UniqueSequences`, not as fewer burns.
- **`FmCallsElapsed`** is the deterministic search-progress axis for bridge-burn/crossover rows
  (prefer it over `Timestamp`, which is wall-clock and noisy under multithreading).
- **`InitialSequenceValue` / `FinalSequenceValue`** = greedy-root (pre-search) vs best-node (post-search)
  value of the turn's single planning episode, using the **SIMPLE** heuristic (raw absolute board value).
  Phase 3 selects the crossover ratio on average `FinalSequenceValue` (search effectiveness).
- **`FinalScore`** = the game's end-of-game score for that agent. It is a separate, *in-game* measure of
  performance; comparing it against `FinalSequenceValue` is really a measure of the heuristic's quality
  (how well heuristic value tracks in-game impact), which is why it is **not** used to choose the ratio.
- **Maps** are **11×11** (`DEFAULT_MAP_SIZE` for 2 tribes) and **water-free** (the level generator runs
  with `includeWater=false`, so the board is 100% land — no deep/shallow water, fish or whales). Because
  of that, the runners enable `REMOVE_FISHING`, so the SIMPLE heuristic **penalises** the now-useless
  naval techs (`FISHING/SAILING/WHALING/NAVIGATION/AQUATISM`) instead of rewarding their research.

---

## 6. Validate before the full run

```bash
java -Xmx2g -cp "out;lib/*" core.data.exp.NewExperimentSmokeTest
```
Runs a tiny version of both phases (Phase 3 with a 2-ratio sweep, then Phase 2 using the chosen
ratio; few games, short turns, small budgets) into `test_results/smoke/` in ~80 s. If every CSV has
more than the header row, `phase3_best_ratio.txt` is present, and there are 0 failed games, the
pipeline is healthy and the full run is safe to start.

### Fairness diagnostic (optional)
```bash
java -Xmx2g -cp "out;lib/*" core.data.exp.BurnBudgetCheck
```
Prints, per agent at the 13400 and 26800 **compute-unit** budgets (the 500 ms / 1000 ms Phase-3
levels), the average `FmCalls`, `UniqueSequences` and bridge-burn count per turn — used to confirm
every agent performs 20 burns at an equal compute-unit budget. Note `FmCalls` is *expected to differ*
across agents now: the equalised quantity is compute-units, so the crossover variants' different
advance/copy/eval mix shows up as a different FM-call demand (that gap is the efficiency cost under study).

Experiment analysis:
  - **(possibly) Change to make** Make nodes visited not "fm calls", but the unique number of nodes evaluated. Some of the nodes may be have the same sequence same as other nodes. Does the FM evaluate an identical sequence again or not? Or does it assign the node the same value as another node with the identical sequence? Are nodes with identical sequences currently included in the "nodes visited" count or not? They should be.

  - **(possibly) Change to make** Sequences evaluated should not have floor at 20. It should be independent of a bridge burning phase. If the new root is a unique sequence, then of course it should be counted. How is this number derived? How is it counted?

  - **Investigate and maybe change** How well do FMcalls really track computational use? What percentage of the algorithms computational use is due to FMcalls? A solid number from a properly run test should be used to justify using FM calls as the budget rather than something else, such as time. For example, if FM calls consist of 99.9 percent of the algorithms computational use, then it is justified.

  - We need to be able to make a solid comparison of the efficiency of each algorithm and the modifications made to them. The basis of this will be fmcall use (or computational demand), vs number of nodes evaluated. 

  - The relative efficiency of using different crossover expansion ratios will be tested by comparing the nodes evaluated between the different ratio using players. 
  - The efficiency in finding new sequences will be used by comparing the number of unique sequences per FM call,the number of unique sequences per node evaluated.
  - The overall efficiency of the algorithms will be the found by comparing the average value of the nodes they are able to evaluate
    - Another measure will be comparing the average % value improvement from the initial to final sequence. 
    - Another measure will be to compare the trajectory of searches: Best sequence over time. Differences between algorithms may show in how the value of the best node changes over time. There may be short/long term algorithm (and ratio) champions. 
  - The baseline measures will be used just to see in-game performance and compare nodes visited by MCTS vs EMCTS vs the experimental agents. 
  - **Change to be made:** Asess the final turn heuristic value of MCTS and OEP (OEP may even be done already, not sure). This will allow for a general search value comparison.
  - **Change to be made:** The OEP parameters need to be tuned to the time settings. The starting population will need to change. Kill rate can remain the same. Tournament size I don't know what is, but will probably need to be tuned. 


Graphs to make: 
- For phase three: Average trajectory of sequence value per bridge burning phase (average across the 50 games for all the agents. Treat Crossover expansion with different ratios as seperate agents)
- Average turn heuristic value vs score gained to justify heuristic value graph

Statistics to derive:
- FMCalls' percentage of EMCTS family total compute use

Table to make:
1:{ Phase 3 summary
Columns: Agent, Average final sequence value, average sequence improvement, average final score, average nodes evaluated, average unique sequences found. The "average" means across the 50 test games played in the phase three environment. 

Rows: Ratios, meaning 2,5,10,15 and 20. 
Also add Crossover BB, CrossoverCombo at ratio 5.

This will show which ratio was best and allow for full comparison between the experimental agents vs the baseline EMCTS.
}

2:{ Win rate matrix from Phase 2
Columns: Experimental Agents CrossoverExpansion (at ratio 5 + best ratio), CrossoverBB and CrossoverCombo (at ratio 5)
Rows: Baselines --> MCTS, OEP, EMCTS

The cells in the middle will only display the experimental agent's win rate against the baseline. 

Will be 2 copies (one for each time budget) --> Or maybe split each of the cells in 2?? -->split cells would be neater
}

3:{ Percentage improvement in the core performance metrics per crossover expansion ratio and crossoverBB. Uses EMCTS as a baseline. May not be displayed, but just used during discussion.
Should use phase 3 data. 

}