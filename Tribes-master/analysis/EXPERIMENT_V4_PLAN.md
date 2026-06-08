# Experiment v4 — Isolation Phase

Fourth experiment session. Goal: **once and for all uncover how the search trajectory changes between
EMCTS and the experimental agents**, without the cumulative-board-strength endogeneity that confounded
Phase 3 in v1–v3.

## Why v4

v3 showed that even after skipping the degenerate opening (starting from a fixed turn-3 state), Phase-3
results still had too much game-to-game variance to discriminate the algorithms cleanly. The root cause
is structural, not a sample-size issue:

- Each agent plays its **own game**, so by mid-game they are on different boards.
- Search-internal measurements (FinalSequenceValue, NodesEvaluated, etc.) then partly measure
  "who already had the better board," not "who searches better" — the endogeneity issue we hit repeatedly
  in v1–v3.
- More games tightens the means but does **not** remove this confound.

The Isolation Phase eliminates the confound by **paired-by-scenario** measurement.

## Design

### 100 starting scenarios — the "testing portfolio"
A one-time-built portfolio of well-developed mid/late-game `GameState`s used by every agent:

- **50 single-player scenarios** — for each of 5 distinct level seeds, play one EMCTS-vs-passive game
  (SCORE mode, 30-turn limit) and capture the start-of-turn state at ticks 21..30 (the last 10 turns).
- **50 two-player scenarios** — 50 distinct level seeds, each one EMCTS-vs-EMCTS game (CAPITALS, 50-turn
  limit), captured at the start of tick 25.
- All scenarios are on the standard 11×11 water-free board, IMPERIUS vs IMPERIUS2.
- Source EMCTS uses 26 800 compute-units / turn (≈ 1000 ms-equivalent, deterministic — reproducible across
  machines/threads). The portfolio is regenerable by re-running `BuildV4Portfolio` at the same fixed seeds.

A manifest (`portfolio.csv`) records full provenance for each scenario, with these columns (header
must match `BuildV4Portfolio.MANIFEST_HEADER`):
`ScenarioId, Source, SourceGameId, SourceLevelSeed, SourceGameSeed, CapturedTick, ActiveTribeId,
ActiveTribeNumCities, ActiveTribeNumUnits, ActiveTribeScore, BranchingFactor`.

### Single-turn isolation
For every (agent, thinking-time, scenario, visit), the runner:
1. deep-copies the captured `GameState` (so each task is independent);
2. constructs a fresh agent with seed = f(ScenarioId, VisitId);
3. calls `act()` once — this triggers `planTurn()`, which records the full 20-burn trajectory;
4. logs `TurnData` + `BridgeBurnEventData` (+ `CrossoverEventData` for burn-crossover agents) into the
   appropriate leaf CSV.

No game continuation. No opponent reaction. We are measuring *just the search step*.

### Same random initial sequence across agents — the paired-comparison core
The agents share the **same seed** per (scenario, visit), so:
- The `m_rnd` state is identical at the start of `randomActions()`.
- With `params.randomInitialSequence = true`, `randomActions()` picks uniformly at random from valid
  actions — same seed + same state + same code path ⇒ **identical starting sequence** across all 12
  configs. (Verified by `NewExperimentSmokeTestV4`.)

The greedy in-search **repair** mechanism is unchanged: when crossover / mutation produces an invalid
sequence prefix, the existing greedy repair fixes it as before.

This pairing is the whole point. Every (scenario, visit) becomes a tied test of all 12 configs starting
from the *same* board and the *same* initial — the cleanest comparison the experiment can produce.

### Visits per scenario: 50
Each scenario is visited 50 times. Different visits use different visit seeds, so the random starting
sequence varies across visits (but is identical across agents within a (scenario, visit)). With 100
scenarios × 50 visits = **5,000 paired samples** per (config, thinking-time) — enough to surface very
small mean deltas with tight 95 % CIs.

### Configs (12) and thinking times (3) → 36 leaf CSVs
| Agent              | Variants                                                     |
|--------------------|--------------------------------------------------------------|
| EMCTS              | one config (baseline)                                        |
| CrossoverBB        | one config (ratio not used)                                  |
| CrossoverExpansion | ratios 2 / 5 / 10 / 15 / 20                                  |
| CrossoverCombo     | ratios 2 / 5 / 10 / 15 / 20                                  |

Thinking times: **50 / 500 / 5000 ms** (wall clock, STOP_TIME). 20 bridge burns per turn at every level.

### Folder layout (output)
```
test_results/v4/
  _portfolio/
    portfolio.csv               (manifest)
    scenarios/scenario_001.bin  (gzipped binary GameState, FastGameStateIO)
    ...
    scenarios/scenario_100.bin
  EMCTS/
    50ms/results.csv
    500ms/results.csv
    5000ms/results.csv
  CrossoverBB/
    50ms/results.csv  500ms/results.csv  5000ms/results.csv
  CrossoverExpansion/
    ratio_2/{50ms,500ms,5000ms}/results.csv
    ratio_5/{50ms,500ms,5000ms}/results.csv
    ratio_10/{50ms,500ms,5000ms}/results.csv
    ratio_15/{50ms,500ms,5000ms}/results.csv
    ratio_20/{50ms,500ms,5000ms}/results.csv
  CrossoverCombo/   (same ratio_*/ms/ layout)
```

### CSV schema (per row = one bridge-burn observation)
```
ScenarioId, ScenarioSource, ScenarioSourceGameId, ScenarioCapturedTick, VisitId, AgentName, Ratio,
BridgeBurningPhaseNumber, BestSequenceValue, FmCallsElapsed, Timestamp,
ScoreAtTurnStart, InitialSequenceValue, FinalSequenceValue, AvgNodeValue,
FmCalls, NodesEvaluated, UniqueSequences
```
Burn-crossover agents (CrossoverBB / CrossoverCombo) add: `RawImprovement, PercentageImprovement`.
Per-turn columns are repeated across the 20 burn rows of a (scenario, visit). 100×50×20 = **100,000 rows
per leaf CSV** (3 600 000 rows total across all 36 leaves).

## Compute budget — surprisingly small

| time   | turns per ms-pass | wall-clock at 120 threads (idealised) |
|--------|-------------------|---------------------------------------|
| 50 ms  | 12 × 5 000 = 60 k | ~ 25 s                                |
| 500 ms | 60 k              | ~ 4 min                               |
| 5000 ms| 60 k              | ~ 42 min                              |

Total idealised wall-clock ≈ 50 min; realistic with overhead ≈ 1.5–2 h. The portfolio build is a one-time
~10 min cost on top.

RAM is light (we don't run full games — one captured state per active task, small trees). `-Xmx32g` is
ample on the server; the 1 TB headroom is overkill.

## Optimisations actually included

- **Fast binary GameState IO** (`FastGameStateIO`) — gzipped Java native serialization, ~5–10× faster
  than the existing JSON `GameSaver`/`GameLoader` path. Achieved by adding `implements Serializable` to
  ~10 base classes (inheritance carries it to every subclass) and marking the action-cache fields
  `transient` (recomputed on load via `computePlayerActions` — same pattern `GameLoader` already used,
  so the 30+ `Action` subclasses do NOT need to be Serializable).
- **In-memory portfolio** — `IsolationPhaseRunner.loadPortfolio()` reads all 100 scenarios once into
  memory; each task takes a `gs.copy()` deep copy (which the search already uses constantly), so the
  disk I/O cost is paid exactly once per run instead of per task.
- **Embarrassingly parallel tasks** — every (agent, scenario, visit) is independent. Within one
  thinking-time pass we run all 60 000 tasks through a single thread pool.

Search-algorithm code is intentionally NOT changed for performance — keeping it stable preserves
cross-experiment comparability with v2/v3 data and avoids invalidating prior conclusions.

## Race-condition guard (Constants.TURN_TIME_MILLIS)

The agents read the per-turn wall-clock budget from `Constants.TURN_TIME_MILLIS`, which is a static
field. Mutating it across parallel matchups at different ms budgets would be a data race. The v4 runner
sidesteps this by running **one thinking-time pass at a time**: 50 ms first, then 500 ms, then 5000 ms.
Within a pass all 60 k tasks share the same static, so no race.

## How to run

### On the Jupyter server (120 threads, 1 TB RAM) — recommended
Use `analysis/run_v4_jupyter.ipynb` — it walks through: set repo path → check env → compile → `nohup`
the full run + log → analyse → show figures inline. Key points (Linux):
```
javac -d out -cp "lib/*" @_srcs.txt                               # compile
nohup java -Xmx32g -cp "out:lib/*" core.data.exp.RunExperimentV4 120 > v4_run.log 2>&1 &
tail -f v4_run.log                                                 # monitor (resumable)
python analysis/analyze_v4.py test_results/v4                      # analyse after completion
```

### Locally (for smoke-validation)
```
javac -d out -cp "lib/*" $(find src -name '*.java')
java -Xmx4g -cp "out;lib/*" core.data.exp.NewExperimentSmokeTestV4   # ~ 2 s
```

## Sample-size / power notes

For a paired comparison of FinalSequenceValue mean delta vs EMCTS, 5000 paired samples per (config, ms)
give a standard error of `s / sqrt(5000)` ≈ `s / 70.7`, where `s` is the per-pair standard deviation.
Even at large per-pair sd (say 1000 heuristic units, the order we saw in v3), the 95 % CI is ±28 units
— well below the 100+-unit means we observed for the headline ratio effects. So 50 visits is more than
enough to surface meaningful effects.

## Validation

### Portfolio: every scenario must start up properly
- `BuildV4Portfolio` validates **each captured scenario inline**: rejects (and re-tries from the next
  capture position) any scenario whose active tribe has zero cities or zero units, or where a tiny
  EMCTS planTurn throws. The combination guards against persisting:
  - States where the passive opponent is active (would have happened to single-source captures before
    the `captureAtTickAsTurnStart` fix below).
  - Engine-edge states that NPE under planning.
- `VerifyV4Portfolio` walks the entire persisted portfolio and re-runs the same check, with a per-
  scenario PASS/FAIL line and a summary. `RunExperimentV4` calls it after `BuildV4Portfolio` and
  ABORTS the run if any scenario fails — failing in seconds instead of after hours of silently bad
  isolation-phase output.

### `Game.captureAtTickAsTurnStart` — the active-tribe fix
After `Game.tick()` runs its `for i in [0..numPlayers)` loop and then `gs.incTick()`, the captured
state's `activeTribeID` is left pointing at the LAST tribe to act in the previous tick — tribe 1.
For two-player EMCTS-vs-EMCTS source games that's fine (both tribes are developed). For
single-player EMCTS-vs-DoNothing sources, tribe 1 is the **passive opponent** — which has no cities
and no units. A v4 agent reading `gs.getActiveTribeID()` would silently take that role and produce
garbage. The fix: `Game.captureAtTickAsTurnStart(stopTick)` snapshots first, then `initTurn`s
`tribes[0]` so the EMCTS-developed player is the active tribe at `stopTick`. (Plain
`Game.captureAtTick` is untouched — `Game.run` is its only caller besides v4 and it already runs
`initTurn` on the first iteration, so a fixup there would double-process.)

### Pipeline smoke
`NewExperimentSmokeTestV4` runs in ~70 s and asserts:
1. `FastGameStateIO` round-trip preserves `tick`, `activeTribeId`, board size, action count.
2. **Per-scenario startup validation**: each captured tiny scenario has an EMCTS-developed active
   tribe (cities > 0 AND units > 0) AND a planTurn that doesn't throw.
3. **Shared random initial sequence**: given identical visit seeds, EMCTS / CrossoverBB / Expansion@5
   produce the *same* literal action sequence (compared via `getInitialRootSequenceForV4()`, not by
   one float).
4. **Invariance across thinking times**: for one (scenario, visit), all 12 configs × all 3 thinking
   times produce the identical initial sequence (36 / 36 match).
5. Folder layout exactly matches the spec; burn-crossover agents have the extra
   `RawImprovement` / `PercentageImprovement` columns.

All five pass.

## What v4 will resolve

- **Will:** clean paired comparison of every (Expansion ratio, Combo ratio, BB) vs EMCTS, free of the
  cumulative-board confound. Tight CIs even on small effects. The trajectory plot becomes a real cross-agent
  comparison rather than separate plots on different boards.
- **Won't:** absolute competitive performance against baselines (that's Phase 2's job and is already
  covered in v1/v2/v3). v4 is the search-internal isolation; pair its findings with the existing Phase 2.

## Proposed follow-ups (not implemented)

- **Win-rate verification** — re-run a small Phase-2-style head-to-head at the v4-chosen best ratio per
  (agent, ms) to confirm the isolation-phase winner also wins competitively. ~5 matchups × 100 games.
- **Bootstrap CIs** — replace the parametric 95 % CI in `analyze_v4.py` with non-parametric bootstrap if
  the FinalSequenceValue distributions turn out to be heavy-tailed (visible in `fig8_value_distribution`).
- **Per-scenario archetypes** — cluster the portfolio by (cities, units, branching factor) and check
  whether ratio winners differ by archetype (early-game vs late-game heuristics?).
