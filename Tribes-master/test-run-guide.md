# Test-Run Guide — EMCTS Crossover Experiments

This guide explains the three experiment phases, how to launch them with a single command,
what gets written, and how to read each output file.

The experiment measures the impact of adding **crossover** to EMCTS, in two forms:
**Crossover Expansion** (crossover during tree growth) and **Crossover Bridge Burning**
(a crossover tournament at phase transitions). Agents:

| Role | Agents |
|---|---|
| Baselines | `MCTS`, `OEP`, `EMCTS` |
| Experimental | `CrossoverExpansion`, `CrossoverBB`, `CrossoverCombo` |

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
- `RunAllExperiments` runs Phase 1 → reads the best ratio → Phase 2 → Phase 3, writing to
  `test_results/full/{phase1,phase2,phase3}/`.
- **It is resumable**: if it is interrupted, just run the same command again — completed
  matchups are skipped (recorded in each phase's `_completed.txt`) and it continues.

You can also run phases individually (each takes an optional thread count):

```bash
java -Xmx8g -cp "out;lib/*" core.data.exp.Phase1Runner 4
java -Xmx8g -cp "out;lib/*" core.data.exp.Phase2Runner 4   # reads phase1's best ratio
java -Xmx8g -cp "out;lib/*" core.data.exp.Phase3Runner 2   # 2 threads for the heavy 5000ms level
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
- A **phase banner** with elapsed time (`== PHASE 2 / 3 [elapsed 1h 12m 03s]`).
- A **per-matchup counter**: `[Phase2] (7/15) RUN level=500ms;CrossoverBB_vs_MCTS (100 games, 500ms/turn)`.
- A **per-game counter** within each matchup: `    CrossoverBB_vs_MCTS: 40/100`.

### Runtime
Rough order of magnitude on the target machine at 4 threads: **several hours per phase**
(Phase 3's 5000 ms level dominates). It is fully resumable, so it can be run in chunks.

---

## 2. Budgets

All budgets were calibrated so the search effort roughly matches a target *thinking time*
(~30.8 fmcalls/ms on the dev machine — re-run `Calibrate` on your machine to retune):

| Phase | Budget type | Levels |
|---|---|---|
| 1 | **fmcalls** (both agents plan-once → fair) | `500ms`≈15000, `2000ms`≈61000 |
| 2 | **wall-clock time** (per-action MCTS/OEP vs plan-once need equal *per-turn* time) | 200, 500, 1000 ms |
| 3 | **fmcalls** | `200ms`≈6000, `1000ms`≈30000, `5000ms`≈154000 |

> Phase 2 uses **time**, not fmcalls, on purpose: MCTS/OEP search **per action** while the
> EMCTS-family plan **once per turn**, so an fmcall budget would give MCTS/OEP ~K× more
> compute per turn. Wall-clock time is shared across a turn's actions by the engine, so every
> agent gets an equal per-turn budget.

To recalibrate budgets for your hardware:
```bash
java -cp "out;lib/*" core.data.exp.Calibrate     # prints suggested fmcall budgets per target ms
```
Then edit the `defaultLevels()` / `defaultTimesMs()` numbers in the `PhaseNRunner` classes.

---

## 3. Phase 1 — Ratio Analysis

**Goal:** find the mutation-to-crossover ratio for Crossover Expansion that wins most against EMCTS.
**Setup:** `EMCTS` vs `CrossoverExpansion`, Capitals, 50-turn limit, tribes IMPERIUS vs IMPERIUS2,
100 random-map games per (ratio, budget). Ratios tested: **2, 5, 10, 15, 20** (mutations per crossover).

**Output (`test_results/full/phase1/`):** files are suffixed by budget level (`500ms`, `2000ms`).

| File | Granularity | Columns |
|---|---|---|
| `Phase1_turns_<level>.csv` | one row per agent per turn | `Ratio, GameId, AgentName, TurnNumber, ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, NodesVisited` |
| `Phase1_bridgeburns_<level>.csv` | one row per bridge-burn | `Ratio, GameId, AgentName, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp` |
| `Phase1_games_<level>.csv` | one row per game | `Ratio, GameId, Winner, EMCTSScore, CrossoverExpansionScore, EndTurn` |
| `phase1_best_ratio.txt` | summary | win-rate per ratio + `BEST_RATIO=<n>` (read automatically by Phases 2 & 3) |

If the best ratio is **20** (i.e. crossover rarely helps), Phase 2 also evaluates ratio **5** as a sanity baseline.

---

## 4. Phase 2 — Final Win Rates

**Goal:** competitive win rates of each experimental agent vs each baseline.
**Setup:** 9 matchups (3 experimental × 3 baselines; experimental agents do not play each other),
Capitals, 50-turn limit, IMPERIUS vs IMPERIUS2, 100 random-map games per matchup, at 200/500/1000 ms.
`CrossoverExpansion`/`Combo` use the best ratio from Phase 1.

**Output (`test_results/full/phase2/`):** one file per time level.

| File | Granularity | Columns |
|---|---|---|
| `Phase2_games_<level>.csv` | one row **per agent per game** | `Level, Matchup, GameId, AgentName, Ratio, Won, Winner, AvgInitialSeq, AvgFinalSeq, AvgNodesVisited, EndTurn` |

- `Won` = 1 if this agent won that game, else 0. Win rate of an agent in a matchup = mean of `Won`.
- `AvgInitialSeq / AvgFinalSeq / AvgNodesVisited` are averaged over that agent's turns in the game.
  (MCTS logs sequence values as `0` — not applicable; OEP logs `NodesVisited = -1`.)

---

## 5. Phase 3 — Trajectory Analysis

**Goal:** how each EMCTS-family agent's search improves within a turn (the bridge-burning
trajectory) and how its score grows, on a **fixed** scenario.
**Setup:** SCORE mode, 30-turn limit. Each of the 4 EMCTS-family agents (`EMCTS`,
`CrossoverExpansion`, `CrossoverBB`, `CrossoverCombo`) plays 100 games on a **fixed map seed**
against a **fixed passive opponent** (`DoNothing`, IMPERIUS2). Only the tracked agent's RNG seed
varies per game (game *k* uses the same seed across all four agents), so the agents face
identical conditions. Budgets: `200ms`, `1000ms`, `5000ms` (fmcalls).

> A true 1-tribe map is degenerate (no villages to capture → every agent, even random, stalls
> at ~1 city). The passive 2nd tribe gives the map expansion opportunities without interfering,
> so this is "agent vs do-nothing on a fixed map" rather than literal solo play.

**Output (`test_results/full/phase3/`):** two files per budget level, one row **per bridge-burn**.

| File | Agents | Columns |
|---|---|---|
| `Phase3_standardBB_<level>.csv` | `EMCTS`, `CrossoverExpansion` | `AgentName, GameId, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp, ScoreAtTurnStart, NodesVisited, FinalScore` |
| `Phase3_crossoverBB_<level>.csv` | `CrossoverBB`, `CrossoverCombo` | …same… **plus** `RawImprovement, PercentageImprovement` |

- `BestSequenceValue` = best sequence value found so far at that bridge burn (monotonic within a turn).
- `RawImprovement / PercentageImprovement` (crossover-BB only) = best-of-7-bred-children minus best-of-8-elite.

---

## 6. Metric definitions (read this)

- **`NodesVisited` = forward-model calls that turn** (the computational-workload metric). Under an
  fmcall budget (Phases 1 & 3) it ≈ the budget for every agent, so the meaningful comparison there is
  *value gained per node* = `(FinalSequenceValue − InitialSequenceValue) / NodesVisited`. Under Phase 2's
  time budget it varies and indicates efficiency.
- **`FmCallsElapsed`** is the deterministic search-progress axis for bridge-burn/crossover rows
  (prefer it over `Timestamp`, which is wall-clock and noisy under multithreading).
- **`InitialSequenceValue` / `FinalSequenceValue`** = greedy-root (pre-search) vs best-node (post-search)
  value of the turn's single planning episode, using the **SIMPLE** heuristic (raw absolute board value).
- Bridge-burning agents (`CrossoverBB`/`Combo`) complete **fewer** bridge-burn phases per fmcall budget
  than `EMCTS`/`Expansion`, because their per-transition crossover tournament spends forward-model calls
  (the budget is FM-calls; standard bridge burning is free). This is expected.

---

## 7. Validate before the full run

```bash
java -Xmx2g -cp "out;lib/*" core.data.exp.NewExperimentSmokeTest
```
Runs a tiny version of all three phases (few games, short Capitals turns, small budgets) into
`test_results/smoke/` in ~30 s. If every CSV has more than the header row and there are 0 failed
games, the pipeline is healthy and the full run is safe to start.
