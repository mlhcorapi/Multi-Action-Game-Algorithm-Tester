# Experiment v3 — plan & run guide

Third session. Same structure as v2, with four changes aimed at the variance/degeneracy issues and a
more thorough competitive ratio comparison. **v3 is a fresh, standalone run** (not combined with v1/v2 -
the conditions differ), written to `test_results/v3/`.

## Changes vs v2

| # | Change | Why |
|---|---|---|
| 1 | **500 games for every trial** (Phase 3 all levels; Phase 2 standard levels) | v1/v2 showed real game-to-game variance; 500 games tightens every mean. Re-run from scratch (no additive combining). |
| 2 | **Phase 3 starts from a fixed turn-3 position** | Turns 0-2 are degenerate (every agent plays them identically: 130->271->296->320, zero variance), so they carry no signal and waste compute. v3 captures one EMCTS game's board at the **start of turn 3** and begins ALL Phase-3 games from that single position. |
| 3 | **Phase 2 drops MCTS** (baselines = EMCTS + OEP) | MCTS loses ~100% in this setup and does not differentiate the algorithms. |
| 4 | **Phase 2 at 1000 ms tests ALL ratios** (2,5,10,15,20) for Expansion & Combo, at **300 games/matchup**; 500 ms and 5000 ms keep {5, best} at 500 games | A competitive (not just solo) ratio comparison at the most informative budget. |

## The fixed turn-3 start (how it works)

Implemented entirely in-memory (no serialization, no fog-of-war), reusing the deep-copy the search engine
already relies on:

- **Capture (once):** `RunExperimentV3.captureStartState()` runs one EMCTS game (vs the passive opponent,
  fixed map seed, SCORE mode) and calls `Game.captureAtTick(3)`, which plays ticks 0,1,2 then returns a
  deep copy of the **full** game state at the clean start of turn 3 (the tick counter increments at the end
  of each tick, so after three ticks no agent has acted in turn 3 yet).
- **Inject (per game):** `Game.initFromState(players, start.copy())` begins each Phase-3 game from a fresh
  copy of that state. `ExperimentEngine.runMatchup(..., startState)` wires this through; `Phase3Runner.run`
  takes the start state and passes it down.
- **Validity:** because turns 0-2 are degenerate, the single captured board is the board *every* agent
  would have reached, so it is a fair universal start (this is exactly the "run one EMCTS test, use its
  turn-3 position" approach). The map is the standard 11x11 water-free board (hardcoded in
  `GameState.init`), so the captured position matches the intended setup.
- **Effect on data:** the tracked agent's logged turns now begin at **turn 3** (not 0). The game randomness
  is fixed by the captured state, so across the 500 games the only variation is each agent's search RNG -
  a clean fixed-position search benchmark. The analysis handles the turn offset transparently.

This also realises the v2 plan's proposed "fixed-position benchmark" for the degeneracy/no-clean-board
limitation, at least for the opening.

## How to run

### Locally
```
# compile (Windows: -cp "lib/*" and ; separators; see project build notes)
# 1. (optional) retune OEP at 5000ms, then update ExpAgents.oepTime()'s 5000ms branch and recompile:
java -Xmx8g -cp "out;lib/*" core.data.exp.OepTuning 4 5000 20,50,100 3,5,10 10 test_results/v3/oep_tuning
# NOTE: keep pop <= 100. Larger populations (e.g. 200) can stall OEP when the population converges to
# fewer distinct individuals than the tournament size; and the optimum is small anyway (500ms->100,
# 1000ms->50), so {20,50,100} covers the useful range.
# 2. run v3:
java -Xmx8g -cp "out;lib/*" core.data.exp.RunExperimentV3 4
# 3. analyse (separate output dir analysis/figures_v3, analysis/tables_v3):
python analysis/analyze.py test_results/v3
```

### On the Jupyter server (1 TB / 128 cores)
Use **`analysis/run_v3_jupyter.ipynb`** - it walks through: set repo path -> check env -> compile ->
(optional) OEP 5000 ms retune -> run v3 detached with `nohup` + a log -> analyse -> show figures inline.
Key points for the server (Linux):
- Classpath separator is `:` (not `;`): `-cp "out:lib/*"` (quote it so the shell doesn't glob `lib/*`).
- Give the JVM most of RAM and ~120 threads: `java -Xmx700g -cp "out:lib/*" core.data.exp.RunExperimentV3 120`.
- Run it with `nohup ... &` and `tail -f v3_run.log` so it survives a browser/kernel disconnect; the run is
  resumable (per-config / per-matchup checkpoints) if it does die.
- The 5000 ms Phase-2 matchups are the long pole. With 120 threads and 1 TB RAM the memory limit that
  capped v1/v2 thread counts is gone, so high parallelism is fine.

## Analysis notes
- Analyse v3 **separately**: `python analysis/analyze.py test_results/v3` (different conditions from v1/v2).
  Output is namespaced to `analysis/figures_v3/` + `analysis/tables_v3/`.
- The win-rate figure auto-detects that MCTS is absent and that 1000 ms has all ratios.
- Phase-3 figures simply start at turn 3; the trajectory/efficiency/value-vs-score logic is unchanged.

## Sanity-checked
`NewExperimentSmokeTestV3` runs the whole pipeline at tiny scale and asserts (a) Phase-3 logged turns begin
at turn 3 (fixed-start works) and (b) Phase-2 has only EMCTS+OEP baselines (no MCTS).
