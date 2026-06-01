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
- **Crash-resilient**: the Tribes engine occasionally throws under deep search (a `ClassCastException`
  of a unit to `City`). Such a game is automatically **re-run with a perturbed RNG seed** (the level
  seed is kept fixed, so Phase 3's map is unchanged), up to 4 attempts, so each matchup still reaches
  its full game count. A game that still fails after all attempts is logged (`[warn] … dropped`) and
  that matchup finishes with one fewer game — rare in practice.

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
(~30.8 fmcalls/ms on the dev machine — re-run `Calibrate` on your machine to retune):

| Phase | Budget type | Levels |
|---|---|---|
| 3 | **fmcalls** (all participants plan-once → fair) | `500ms`≈15000, `1000ms`≈30000, `5000ms`≈154000 |
| 2 | **wall-clock time** (per-action MCTS/OEP vs plan-once need equal *per-turn* time) | 500, 1000 ms |

> Phase 2 uses **time**, not fmcalls, on purpose: MCTS/OEP search **per action** while the
> EMCTS-family plan **once per turn**, so an fmcall budget would give MCTS/OEP ~K× more
> compute per turn. Wall-clock time is shared across a turn's actions by the engine, so every
> agent gets an equal per-turn budget. Phase 3 uses fmcalls (all its participants are plan-once).

> **Why the smallest level is 500 ms, not 200 ms.** Bridge burns are fixed at 20 per turn for every
> agent (see §5). For the crossover-bridge-burning agents those 20 tournaments are *mandatory* and
> cost ~7000 fmcalls in total — so a ~200 ms (~6000-fmcall) budget is below that floor: the crossover
> agents would spend their whole budget (and more) just on the burns and do almost no search,
> overrunning the budget by ~20%. At 500 ms (~15000) and above, the 20 burns are a modest share of the
> budget, so every agent spends the **same** total fmcalls/time (verified with `BurnBudgetCheck`:
> all four agents land within ±0.5% of the target at 30000). Phase 2 likewise starts at 500 ms.

To recalibrate budgets for your hardware:
```bash
java -cp "out;lib/*" core.data.exp.Calibrate     # prints suggested fmcall budgets per target ms
```
Then edit the `defaultLevels()` / `defaultTimesMs()` numbers in the `PhaseNRunner` classes.

---

## 3. Phase 3 — Ratio selection + trajectory (runs first)

**Goal:** two jobs in one controlled environment — (1) **choose** the CrossoverExpansion
mutation:crossover-expansion ratio, and (2) measure **how each EMCTS-family agent's search
improves within a turn** (the bridge-burning trajectory) and how its score grows.

**Setup:** SCORE mode, 30-turn limit. Each configuration plays **50** games (the `5000ms` level
plays **25**, since each game there costs ~5× as much and its ratio choice is not used by Phase 2)
on a **fixed map seed** against a **fixed passive opponent** (`DoNothing`, IMPERIUS2); only the
tracked agent's RNG seed varies per game (game *k* uses the same seed across every config), so all
configs face identical conditions and variance is low. Budgets: `500ms`, `1000ms`, `5000ms` (fmcalls).

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
| `Phase3_ratio_turns_<level>.csv` | **ratio test** — one row per turn (`CrossoverExpansion`, all swept ratios) | `AgentName, Ratio, GameId, TurnNumber, ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, NodesVisited, SequencesEvaluated, FinalScore` |
| `Phase3_ratio_trajectory_<level>.csv` | **ratio test** — one row per bridge-burn (`CrossoverExpansion`, all swept ratios) | `AgentName, Ratio, GameId, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp, ScoreAtTurnStart, NodesVisited, SequencesEvaluated, FinalScore` |
| `Phase3_standardBB_<level>.csv` | **family trajectory** — the normal bridge burner (`EMCTS`) | `AgentName, GameId, Ratio, TurnNumber, BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp, ScoreAtTurnStart, NodesVisited, SequencesEvaluated, FinalScore` |
| `Phase3_crossoverBB_<level>.csv` | **family trajectory** — the crossover bridge burners (`CrossoverBB`, `CrossoverCombo`@{5,best}) | …same as standardBB… **plus** `RawImprovement, PercentageImprovement` |
| `phase3_best_ratio.txt` | summary | per-ratio average `FinalSequenceValue` per level + a `BEST_RATIO_<level>=<n>` line per budget level (read automatically by Phase 2) |

- The **ratio-test** CSVs contain only `CrossoverExpansion`, one block per swept ratio; use the
  `Ratio` column for the ratio-vs-`FinalSequenceValue` / ratio-vs-`SequencesEvaluated` analysis.
  (Note the `Ratio` column sits after `AgentName` here, but after `GameId` in the family-trajectory
  CSVs — per the agreed schemas.)
- The **family-trajectory** CSVs use `Ratio = NA` for `EMCTS` and `CrossoverBB`; `CrossoverCombo`
  carries its ratio (it runs at {5, best}). There is no per-turn CSV for these — their per-turn
  values (`ScoreAtTurnStart`, `NodesVisited`, `SequencesEvaluated`, `FinalScore`) ride on each
  bridge-burn row, matching the old 3-phase Phase 3.
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
| `Phase2_games_<level>.csv` | one row **per agent per game** | `Level, Matchup, GameId, AgentName, Ratio, Won, Winner, AvgInitialSeq, AvgFinalSeq, AvgNodesVisited, AvgSequencesEvaluated, EndTurn` |

- `Won` = 1 if this agent won that game, else 0. Win rate of an agent in a matchup = mean of `Won`.
- `AvgInitialSeq / AvgFinalSeq / AvgNodesVisited / AvgSequencesEvaluated` are averaged over that
  agent's turns in the game. (MCTS logs sequence values as `0`; OEP logs `NodesVisited = -1`;
  both log `SequencesEvaluated = -1` — not applicable.)

---

## 5. Metric definitions (read this)

- **`NodesVisited` = forward-model calls that turn** (the computational-workload metric). Under an
  fmcall budget (Phase 3) it ≈ the budget for every agent, so the meaningful comparison there is
  *value gained per node* = `(FinalSequenceValue − InitialSequenceValue) / NodesVisited`. Under Phase 2's
  time budget it varies and indicates efficiency.
- **`SequencesEvaluated` = number of distinct candidate full-turn sequences the search evaluated**
  (one per search iteration). This is the count that *differs* between agents under a fixed FM budget:
  the crossover-bridge-burning variants spend FM calls on their per-transition tournaments, so they
  evaluate **fewer** distinct sequences than `EMCTS`/`Expansion` for the same `NodesVisited` (≈budget).
  `-1` for MCTS (tree search) and OEP (not tracked). It has a structural **floor of 20 per turn**
  (one forced iteration per burning phase) and otherwise ≈ `budget ÷ per-iteration cost`, so it
  varies widely with game state — the floor is reached only at very small budgets (≈2% of turns at
  15000 fmcalls, ≈0% at 30000+).
- **Bridge burns are fixed at 20 per turn for *every* agent** (`depth − 1`, `depth = 21`), so the
  *number* of burns is never a confound between families. Each turn's search is split into 21 phases,
  each given an equal **cumulative** slice of the budget, and a burn fires at each of the first 20
  phase boundaries. A crossover tournament's forward-model calls are charged against the shared budget
  and absorbed by later phases doing fewer iterations — so all agents spend the **same** total budget
  *and* perform the **same** 20 burns. The crossover cost therefore shows up purely as a lower
  `SequencesEvaluated`, not as fewer burns.
- **`FmCallsElapsed`** is the deterministic search-progress axis for bridge-burn/crossover rows
  (prefer it over `Timestamp`, which is wall-clock and noisy under multithreading).
- **`InitialSequenceValue` / `FinalSequenceValue`** = greedy-root (pre-search) vs best-node (post-search)
  value of the turn's single planning episode, using the **SIMPLE** heuristic (raw absolute board value).
  Phase 3 selects the crossover ratio on average `FinalSequenceValue` (search effectiveness).
- **`FinalScore`** = the game's end-of-game score for that agent. It is a separate, *in-game* measure of
  performance; comparing it against `FinalSequenceValue` is really a measure of the heuristic's quality
  (how well heuristic value tracks in-game impact), which is why it is **not** used to choose the ratio.

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
Prints, per agent at the 6000 and 30000 fmcall budgets, the average `NodesVisited`, `SequencesEvaluated`
and bridge-burn count per turn — used to confirm that every agent performs 20 burns and spends the same
total budget (they converge at 30000; 6000 is shown to illustrate the small-budget floor).
