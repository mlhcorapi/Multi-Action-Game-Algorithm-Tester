# Compute-unit calibration: per configuration, per budget level

How v6 prices search compute, why the price is not one number, and what breaks if it is.

This document covers the mechanism. The paper-facing argument for budgeting on compute units at all is
in [BUDGET_SUPPLEMENT.md](BUDGET_SUPPLEMENT.md); the run procedure is Part 0 of
[run_v6_jupyter.ipynb](run_v6_jupyter.ipynb) and §5 of [V6_SERVER_RUNBOOK.md](V6_SERVER_RUNBOOK.md).

---

## 1. The problem

v6 budgets search on **compute units** — a weighted count of the three operations that dominate
planning cost:

```
units  =  W_ADVANCE · advance()  +  W_COPY · copy()  +  W_EVAL · evaluateState()
```

A count rather than a clock, because wall-clock on a 90-worker server is not reproducible: two runs of
the same seed would search different amounts. The weights are what turn a count of three different
things into one currency, and they are supposed to be the operations' relative wall-clock costs.

Until now there was **one weight set for the whole pipeline** — `advance=1.0, copy=0.16, eval=0.077` —
measured once, on one configuration (`CrossoverCombo@5`), at one budget (26,800 units), and applied to
all twelve configurations at all three budget levels. That is correct only if an advance, a copy and an
eval cost the same everywhere. They do not:

- **Across configurations.** A crossover agent splices two parent sequences and repairs the result, so
  the states it advances, copies and evaluates are not the states a mutation-only agent reaches. Same
  operation names, different costs.
- **Across budget levels.** At ten times the budget the tree is an order of magnitude larger and stops
  fitting in cache. This was already in the record before anyone priced it:
  [EXPERIMENT_V6_FULL_GAMES_PLAN.md](EXPERIMENT_V6_FULL_GAMES_PLAN.md) §6 measured the 400,000-unit
  level at **26.7×** the wall-clock of the 40,000-unit level rather than 10×, and filed it under
  "that shape dominates the plan".

There is also a subtler defect in the old scheme, and it is the one that cannot be fixed by adjusting
`W_COPY` and `W_EVAL`: **`W_ADVANCE` was defined as 1.0**. One unit meant "one of *your own* advances".
If a crossover agent's advances are 15% dearer than EMCTS's, then a 40,000-unit budget was 15% more
wall-clock for that agent — not by measurement error, but by definition.

### What that costs

A tiny-scale validation run on the dev laptop (24 cells, 2 repetitions of 2 turns each, levels 20,000
and 40,000 — deliberately too small to pass the validity gates, so treat the numbers as indicative):

| level | cheapest advance | dearest advance | spread |
|---|---|---|---|
| 20,000 | CrossoverExpansion@2, 40.1 µs | EMCTS, 47.2 µs | **17.6%** |
| 40,000 | CrossoverCombo@2, 37.6 µs | CrossoverExpansion@50, 42.8 µs | **13.8%** |

Priced with the old global weight set against each cell's own realized operation mix, the same nominal
1,000-unit budget really bought:

| level | min | max | unfairness |
|---|---|---|---|
| 20,000 | 41.2 ms | 47.9 ms | **16.4%** |
| 40,000 | 38.6 ms | 43.5 ms | **12.7%** |

An agent receiving 13–16% more real compute than its rival is an order of magnitude larger than the
effects v6 exists to resolve. It is also *not* a wash: crossover configurations are copy-heavier, so
the direction of the old bias flatters crossover — the hypothesis under test.

---

## 2. What a compute unit is now

> **One compute-unit is the wall-clock cost of one `advance()` in the reference cell — plain EMCTS at
> the smallest budget level — on the calibrated machine.**

Every weight, `W_ADVANCE` included, is a measured ratio against that one operation:

```
W_ADVANCE(config, level) = t_advance(config, level) / t_advance(reference)
W_COPY   (config, level) = t_copy   (config, level) / t_advance(reference)
W_EVAL   (config, level) = t_eval   (config, level) / t_advance(reference)
```

So `W_ADVANCE` is 1.0 **only for the reference cell**. An agent whose advances genuinely take 1.4× the
reference is charged 1.4 units for each one.

### Worked example

Suppose the reference advance is 40 µs, and at the 400,000-unit level `CrossoverCombo@5` measures
52 µs per advance, 11 µs per copy and 2.4 µs per eval. Its profile is:

```
W_ADVANCE = 52 / 40 = 1.300
W_COPY    = 11 / 40 = 0.275
W_EVAL    = 2.4 / 40 = 0.060
```

A turn of that agent doing 200,000 advances, 150,000 copies and 140,000 evals is charged
`200,000·1.300 + 150,000·0.275 + 140,000·0.060 = 309,650` units, and really costs
`200,000·52 + 150,000·11 + 140,000·2.4 = 12.39 s`. Divide: **40 µs per unit** — the reference advance,
exactly as defined. The same arithmetic on any other cell lands on the same 40 µs. That constancy *is*
the fairness property.

### Three consequences

1. **Equal budgets are equal wall-clock across configurations.** The fairness criterion a per-agent
   *time* budget would have given, obtained without a clock — so the budget stays a deterministic count
   and the search stays reproducible run-for-run.
2. **The levels become proportional in time.** The extra per-operation cost at a large budget is now
   charged rather than absorbed, so 400,000 units costs ~10× 40,000 units instead of 26.7×. The
   400k level was ~90% of the full-games phase's cost; it gets materially cheaper.
3. **A unit has a stated nanosecond value** on the reference machine, written into the weights file.

### What this is *not*

It does not make different machines comparable — the weights are that machine's. It does not equalise
anything the meter does not count (action generation outside `advance()`, node allocation, GC
pressure); see §9.

---

## 3. The profile

A **profile** is the identity of one calibrated cell:

```
<config>[_r<ratio>]@<budgetUnits>

EMCTS@40000                 CrossoverBB@80000
CrossoverExpansion_r5@40000 CrossoverCombo_r50@400000
```

Built in exactly one place, [`ComputeMeter.profileKey(config, ratio, budgetUnits)`](../src/core/game/ComputeMeter.java),
so the calibrator that *writes* a weight and the agent that *reads* it cannot drift apart. A ratio of
`-1` (or anything ≤ 0) omits the `_r` segment — which matters for `CrossoverBB`, whose params carry a
`mutationExpansionRatio` its search never reads: its profile is `CrossoverBB@40000`, **not**
`CrossoverBB_r5@40000`.

12 configurations × 3 budget levels = **36 profiles**.

### Where the weights live

`test_results/v6/_calibration/compute_weights.properties`, loaded by `ComputeMeter` on first use.
Override the path with `-Dcompute.weightsFile=…`; delete the file to fall back to the compiled
defaults.

```properties
# One compute-unit = one advance in EMCTS@40000 = 40218.4 ns here.
compute.reference=EMCTS@40000
compute.referenceAdvanceNanos=40218.412

# Global fallback: the reference cell's weights. Used by anything with no profile of its own.
compute.wAdvance=1.000000
compute.wCopy=0.221847
compute.wEval=0.043051

# Per-profile weights. Key: compute.profile.<config>[_r<ratio>]@<budgetUnits>.<op>
compute.profile.EMCTS@40000.wAdvance=1.000000
compute.profile.EMCTS@40000.wCopy=0.221847
compute.profile.EMCTS@40000.wEval=0.043051
compute.profile.EMCTS@40000.advanceNanos=40218.412
compute.profile.CrossoverCombo_r5@400000.wAdvance=1.297340
...
```

Resolution order for a weight, most specific first:

1. `-Dcompute.wCopy` / `-Dcompute.wEval` / `-Dcompute.wAdvance` (or `COMPUTE_W_*` in the environment) —
   these override the **globals** only, not per-profile weights;
2. the profile in force on the calling thread;
3. the file's globals;
4. the compiled defaults (`1.0 / 0.16 / 0.077`).

With no file at all, every profile resolves to the compiled defaults and behaviour is exactly what it
was before profiles existed.

---

## 4. How a turn gets its weights

The isolation phase runs twelve differently-priced configurations at once across ~90 worker threads, so
the active weight set has to be **per thread**, and it has to be re-established on every planning
episode because a pooled thread will run a different configuration next.

```
ExpAgents.crossoverComboV6(5, 400000)
    └─ params.computeProfile = ComputeMeter.profileKey("CrossoverCombo", 5, 400000)
                             = "CrossoverCombo_r5@400000"          ← set once, at construction

agent.act(...) → planTurn(...)
    ├─ ComputeMeter.reset()                                    counters to zero
    ├─ ComputeMeter.useProfile(params.computeProfile)          ← weights installed on THIS thread
    │      profile found    → {1.297, 0.275, 0.060}
    │      profile missing  → globals, and one warning line
    │      profile null     → globals (restores the default for non-v6 agents)
    ├─ greedy init … measured, then reset (init is outside the budget)
    └─ phase loop: ComputeMeter.computeUnits() >= ceiling?      ← priced with the installed weights
```

`useProfile` is called with `null` as deliberately as with a key: an agent that never installs a
profile would otherwise inherit whatever the previous turn on that thread left behind. Call sites:
[`EMCTSAgent.planTurn`](../src/players/emcts/EMCTSAgent.java),
[`ExperimentalEMCTSAgent.planTurn`](../src/players/ExperimentalEMCTS/ExperimentalEMCTSAgent.java), and
[`TBETSAgent`](../src/players/tbets/TBETSAgent.java) (which has no profile of its own — it calls it to
*clear* one).

`computeUnits()` reads two thread-locals (counts and weights) instead of one. It is called once per
search iteration to test the ceiling, never once per operation, so that cost is immaterial.

### API

| method | purpose |
|---|---|
| `profileKey(config, ratio, units)` | build the canonical key |
| `useProfile(name)` | install a profile on this thread; `null` restores the globals |
| `hasProfile(name)` | is a complete weight set loaded for it? |
| `activeProfile()` / `activeWeights()` | what is in force on this thread |
| `profileCount()` / `profileNames()` | what the file defines |
| `profileSummary(name)` / `weightsSummary()` | banner lines |

---

## 5. The calibration

[`ComputeCalibrateV6`](../src/core/data/exp/ComputeCalibrateV6.java) measures every cell.

```bash
java -Xmx32g -cp "out:lib/*" core.data.exp.ComputeCalibrateV6 \
    [portfolioDir] [outDir] [reps] [turnsPerRepCsv] [warmupTurns] [levelsCsv]
```

Defaults: the v6 portfolio and `_calibration` directories, `reps=3`, `turnsPerRep="4,4,2"` (one entry
per level), `warmup=6`, levels from `RunExperimentV6.DEFAULT_COMPUTE_LEVELS`.

### Procedure

1. **Warm up** on the reference cell and discard, so JIT compilation is not timed. Each cell then
   discards its own first turn as well, which is what touches that agent's own code paths.
2. **For each of the 36 cells**, run `reps` repetitions of `turnsPerRep` turns with
   `ComputeMeter.PROFILE` on, which times each `advance()`, `copy()` and `evaluateState()`.
3. Per repetition, divide accumulated nanoseconds by counts → `t_advance`, `t_copy`, `t_eval`. The mean
   across repetitions is the cell's cost; the coefficient of variation across them is the evidence that
   it is a measurement rather than a sample.
4. **Normalise** every cell by the reference cell's `t_advance` (§2).
5. **Gate**, then write.

**Every cell sees identical work.** The scenario for turn *i* and the seed for repetition *r* depend on
`i` and `r` only, never on the configuration — otherwise a difference between two configurations'
per-operation costs could be a difference between the positions they happened to draw.

**Turns per level are not equal** (`4,4,2`) because the sample size is in the *operations*, not the
turns: a single 400,000-unit turn already contributes hundreds of thousands of timed operations while
costing ~19 s of wall-clock. Roughly 35 minutes total, single-threaded.

**Single-threaded on an idle machine, necessarily.** This is a timing measurement, and competing load
inflates the three operations unevenly — copies and evals are memory-bound and suffer most, advances
least — which is precisely the ratio being measured.

### Validity gates

Per cell:

| gate | constant | why |
|---|---|---|
| ≥ 20,000 of each operation | `MIN_OPS` | a per-operation mean divided by too few samples is noise |
| CV across repetitions ≤ 10% | `MAX_CV` | if the same measurement moves between repeats, the machine was not quiet |
| weights plausible (0 < w < 100) | — | catches a broken or mis-attributed timer |

Globally: the load average must be idle (`MAX_LOAD_PER_CORE = 0.35`, skipped on OSes that do not expose
it), and the reference cell must have produced a usable timing.

**If any gate fails, no weights are written** and the pipeline keeps the ones it had — a bad measurement
can never silently reprice the experiment. The measurements still land in `compute_profiles.csv` so the
failure can be diagnosed.

### Outputs

- `compute_weights.properties` — what the pipeline loads (§3).
- `compute_profiles.csv` — one row per cell: operation counts, `AdvanceNanos` / `CopyNanos` /
  `EvalNanos`, the three CVs, the three weights, `MsPerKiloUnitNew`, `MsPerKiloUnitOld`, `Status`.

`MsPerKiloUnitOld` is what 1,000 nominal units really cost that cell **under the old global weights**,
given its own realized operation mix — the column whose spread across cells is the bias being removed.
`MsPerKiloUnitNew` is constant across all cells by construction; the constancy is the fix.

### Console report

Three blocks worth reading, beyond the per-cell table:

- **DOES AN OPERATION COST THE SAME FOR EVERY CONFIGURATION?** — cheapest and dearest advance within
  each level, and the spread. This is the evidence that per-cell weights were needed, and the number to
  quote in the write-up.
- The same question across levels for the reference configuration — the cache effect, priced.
- **WHAT THE OLD GLOBAL WEIGHTS WOULD HAVE MIS-ALLOCATED** — min/max ms per 1,000 nominal units and the
  resulting unfairness percentage.

---

## 6. What this changed elsewhere

| area | change |
|---|---|
| `GameState.advance()` | now timed under `ComputeMeter.PROFILE` (body moved to `advanceInternal`). Advance timing previously existed only as `ExperimentalEMCTSAgent.FM_NANOS`, a process-wide static maintained by one agent family — so the reference configuration, plain `EMCTSAgent`, could not be timed at all. |
| `AlgParams` | new `computeProfile` field, null for every non-v6 agent. |
| `ExpAgents` | v6 factories set the profile. The `*V6Time` factories take a `profileUnits` argument (default: the floor level) because a STOP_TIME run still has to say which weights priced its reported units. |
| Notebook | portfolio build moved to §0.4 and the calibration to §0.5, both in the shared setup — the calibration measures on portfolio positions, and **both phases** price budgets with its output. Part A renumbered A1–A8. |
| Budget levels | **must be re-derived.** 40,000 was chosen by `BudgetFloorProbe` in the old currency; a unit now means something different. Re-run §A3. |
| C0 (`CalibrationRunnerV6`) | role changed, not mechanism. "Is the shared budget agent-invariant, or do we need per-agent budgets?" is settled — so C0's units/ms table is now the **verification** that the weights equalised the currency. It gained a `ProfileLevel` column and a seventh argument naming the profile level that priced its units. |
| Run banners | `RunExperimentV6` and `RunV6FullGames` print `Compute profiles: N/M calibrated`. |
| `BudgetFairnessProbe` | prints which profile priced each configuration; its weight-sensitivity spread now reads as *the size of the bias the profiles remove* rather than an argument that the bias cancels. |
| `analyze_v6_full.py` | **B5** tables the weights that priced the dataset and the spread that made one global set untenable; **B6** reports realized ms per 1,000 units from the run itself; B3 gained an explicit flatness verdict. |
| Smoke test §9 | four checks on the plumbing (§8). |

---

## 7. Operating it

```bash
# 1. build the portfolio (the calibration measures on it)
java -Xmx64g -cp "out:lib/*" core.data.exp.BuildV6Portfolio test_results/v6/_portfolio auto

# 2. calibrate — ~35 min, single-threaded, machine otherwise idle
java -Xmx32g -cp "out:lib/*" core.data.exp.ComputeCalibrateV6

# 3. confirm the plumbing (smoke §9 asserts 36/36 profiles)
java -Xmx4g -cp "out:lib/*" core.data.exp.NewExperimentSmokeTestV6

# 4. re-derive the budget floor IN THE NEW CURRENCY
java -Xmx16g -cp "out:lib/*" core.data.exp.BudgetFloorProbe \
    test_results/v6/_portfolio test_results/v6/_calibration 26800,33500,40000,47000,60000 10

# 5. C0 verifies the currency is flat across configurations
java -Xmx32g -cp "out:lib/*" core.data.exp.CalibrationRunnerV6 \
    test_results/v6/_portfolio test_results/v6/_calibration 500,1000,5000 12 5 40 40000
```

**Re-run the calibration when**: the machine changes, the JDK changes, the agent code changes in a way
that alters what it does per operation, or the budget levels change (a level with no profile falls back
to the globals). **Never mid-experiment** — all configurations must be priced in the same currency for
the comparison to hold, and a dataset half-priced in each is not repairable after the fact.

**Before trusting a run, check three things:**

1. the calibration's own verdict was PASS;
2. the run banner says `Compute profiles: 36/36 calibrated` (33/33 for the tournament);
3. C0's units/ms is flat across configurations.

---

## 8. Failure modes

Every way this can break is silent — the run completes and the numbers look ordinary. Hence the checks.

| failure | what it does | what catches it |
|---|---|---|
| No calibration file | every config priced with compiled defaults from someone else's machine | run banner (`NONE of 36 calibrated`); smoke §9d; `BudgetFairnessProbe` |
| Some levels calibrated, others not | two currencies in one dataset | run banner (`only N/36`); smoke §9d fails |
| Factory builds the wrong key (e.g. `CrossoverBB_r5@…`) | that config silently falls back to globals | smoke §9a compares the key against `profileKey`, and asserts CrossoverBB carries no ratio |
| Agent never installs its profile | thread keeps the previous turn's weights | smoke §9c installs a foreign profile, plans a turn, asserts the thread ends on the turn's own |
| Weights stale (code or machine changed) | budget quietly unequal again | C0 units/ms stops being flat (B3 verdict); B6 corroborates on the run data |
| Calibration measured on a busy machine | copy/eval inflated relative to advance | CV gate, load-average gate → nothing written |
| Weights lost from the dataset | results not interpretable at all | B5 tables them into the analysis output |

---

## 9. Limitations

**The measurement is mildly self-referential.** Per-operation cost depends on how large the tree grew,
which depends on the weights in force while measuring. The dependence is second-order — it moves tree
size, not the shape of the work — and re-running the calibration is the cheap check: a converged
calibration reproduces its own weights within the CV it reports.

**Within-cell variation remains.** Copy cost grows with game-state size and advance cost varies with
action type, so a cell's weights are a mean over the sampled positions. What the per-(config, level)
calibration removes is the *systematic* component — variation aligned with the agent under study and
with the budget level. C0's split by scenario source and branching factor is the check that the
residual is not aligned with the study's independent variable.

**Coverage is three operations.** Action generation outside `advance()`, repair bookkeeping,
priority-queue maintenance, node allocation and GC pressure are not counted. C0's units/ms is the
residual check: if counted units track measured time closely and without agent-specific bias, the
uncounted remainder is not distorting the comparison.

**The weights are provenance, not scratch.** A compute-unit is defined only relative to them, so a run
reproduced against different weights is a different experiment. Keep
`compute_weights.properties` and `compute_profiles.csv` with the dataset.

**One machine.** The weights describe the calibrated machine. Moving the experiment means re-measuring;
the budget *numbers* transfer, their meaning in seconds does not.

---

## 10. Files

| file | role |
|---|---|
| [`core/game/ComputeMeter.java`](../src/core/game/ComputeMeter.java) | counting, per-thread weights, profile resolution |
| [`core/game/GameState.java`](../src/core/game/GameState.java) | `advance()` / `copy()` counting and opt-in timing |
| [`players/heuristics/AlgParams.java`](../src/players/heuristics/AlgParams.java) | `computeProfile` field |
| [`core/data/exp/ComputeCalibrateV6.java`](../src/core/data/exp/ComputeCalibrateV6.java) | the calibration |
| [`core/data/exp/ExpAgents.java`](../src/core/data/exp/ExpAgents.java) | assigns profiles to the v6 configurations |
| [`core/data/exp/CalibrationRunnerV6.java`](../src/core/data/exp/CalibrationRunnerV6.java) | C0 — verifies the currency is flat |
| [`core/data/exp/BudgetFairnessProbe.java`](../src/core/data/exp/BudgetFairnessProbe.java) | operation mix, which profile priced what, weight sensitivity |
| [`core/data/exp/NewExperimentSmokeTestV6.java`](../src/core/data/exp/NewExperimentSmokeTestV6.java) | §9, the plumbing checks |
| [`analyze_v6_full.py`](analyze_v6_full.py) | B3 flatness, B5 profiles, B6 realized time |
