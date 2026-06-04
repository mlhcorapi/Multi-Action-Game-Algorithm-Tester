# Experiment v2 — follow-up session plan

This documents the second experiment session: what it changes, why, how to run it, and a ranked list of
further improvements that are **proposed but not implemented** (with rationale and sketches).

## Why a v2

The v1 analysis (`analysis/RESULTS.md`) answered the core question — **crossover in the expansion step
helps EMCTS, crossover at the bridge-burning step does not** — but Phase 3 (solo play vs a passive
opponent) had three weaknesses:

1. **Too few games / visible variance.** 50 / 50 / 25 games at 500 / 1000 / 5000 ms. Per-game `FinalScore`
   carried ±540–1270 (95% CI), so agent means overlapped and the ranking was noisy.
2. **No 5000 ms competitive data.** Phase 2 (vs baselines) only ran 500 and 1000 ms, so the budget level
   where the expansion effect is *largest* (fig 11/9 at 5000 ms) was never tested adversarially.
3. **OEP was never tuned for 5000 ms** (it reused the 1000 ms params), so a 5000 ms Phase 2 would have
   used an under-tuned OEP baseline.

## What v2 implements

| Change | Detail |
|---|---|
| **Phase 3 → 100 games / level** | Additive top-up. v1 stays untouched; v2 runs only the **delta** (500ms +50, 1000ms +50, 5000ms +75) with **offset seeds/gameIds** (`Budget.firstGameIndex`) so the new games are independent samples that **combine** with v1 to 100 each — reusing, not discarding, the existing 25/50. |
| **Phase 2 → add 5000 ms** | 100 games at the 5000 ms time budget (the existing 500/1000 ms results stay). |
| **OEP retuned at 5000 ms** | `OepTuning` is now parameterized (budget / population / tournament grid / games / outDir) and properly resumable. v2 sweeps 5000 ms with **larger populations**. |
| **Best ratio over the combined sample** | `Phase3Runner` merges the prior dir's ratio-test rows, so the per-level best CrossoverExpansion ratio is chosen over all 100 games. |
| **Analysis merges v1 + v2** | `analyze.py` reads `test_results/full` **and** `test_results/v2` and concatenates (gameIds don't collide). It auto-detects the 5000 ms Phase-2 level and the combined best ratios. Works whether or not v2 has run yet. |

### How to run (in order)

```bash
# 0. build
#    (compile as usual into out/)

# 1. retune OEP at 5000ms with larger populations (writes test_results/v2/oep_tuning/oep_best_params.txt)
java -Xmx8g -cp "out;lib/*" core.data.exp.OepTuning 4 5000 50,100,200 5,10,20 10 test_results/v2/oep_tuning
#    -> then edit ExpAgents.oepTime()'s 5000ms branch with BEST_POP / BEST_TOURNAMENT and rebuild.

# 2. run v2 (Phase 3 top-up to 100 games + Phase 2 at 5000ms)
java -Xmx8g -cp "out;lib/*" core.data.exp.RunExperimentV2 4
#    optional: re-run lower Phase-2 levels too (if the 100-game Phase 3 changed their best ratio):
#    java ... core.data.exp.RunExperimentV2 4 500,1000,5000

# 3. regenerate all figures/tables over the COMBINED v1+v2 data
python analysis/analyze.py
```

`RunExperimentV2` and the runners are resumable (per-config / per-matchup checkpoints), so an interrupted
5000 ms run can be restarted. Validated end-to-end at tiny scale by `NewExperimentSmokeTestV2`
(confirms the delta's gameIds are disjoint from the prior run's, i.e. the samples truly combine).

### Compute note
The 5000 ms work dominates: Phase 3 adds 75 games × 9 configs, and Phase 2 5000 ms is 15 matchups × 100
games of 50-turn CAPITALS at 5 s/turn. Expect this to be the long pole — budget for it, and lean on the
resumability.

---

## Proposed but NOT implemented (ranked by value)

These are the improvements I'd recommend considering; each notes why it wasn't done now.

### 1. Fixed-position ("board-replay") search-quality benchmark — *highest scientific value*
**Problem it solves:** v1 showed Phase 3 has **no clean board-controlled comparison** — early turns are
degenerate (all agents play identically: 130→271→…), and by the time search outcomes diverge the boards
have *already* diverged, so every per-turn metric is confounded by endogenous board strength. More games
(v2) tightens the means but does **not** remove this confound.

**Design:** sample a battery of N representative mid/late-game `GameState`s (e.g. serialize states from
real games at turns 5/10/15/20/25). Then have **every agent plan once on each identical state** and record
`FinalSequenceValue − InitialSequenceValue`, nodes/unique sequences, etc. Because all agents face the
*same* boards, this isolates search quality from cumulative development — a genuinely paired,
board-controlled measurement that *would* let Phase-3 internals rank the agents.

**Why not now:** needs `GameState` serialization/replay plumbing (save → reload → plan-once harness) that
the current engine doesn't expose cleanly; it's a self-contained mini-project rather than a parameter tweak.

### 2. Seat / turn-order balancing in Phase 2 — *cheap, removes a real bias*
**Problem:** Phase 2 always seats the experimental agent as IMPERIUS (first) and the baseline as IMPERIUS2.
Any first-mover/spawn advantage is then baked into every win rate. The *relative* ordering across
experimental agents is still valid (all share the seat), but the absolute win-rate-vs-baseline is biased.

**Design:** play each matchup half with `[exp, base]` and half with `[base, exp]` (distinct seeds), then
average — or, for a strict paired test, replay each game in both orders. Track agents by name so the swap
is transparent.

**Why not now:** for internal consistency it should be applied to *all* Phase-2 levels, which means
re-running 500/1000 ms (extra compute) and makes v2's win rates not directly comparable to v1's. It's a
clean win if you're willing to re-run Phase 2 wholesale. (Hook: add a `balanceSeats` flag to `Phase2Runner`
that splits each matchup into two seatings.)

### 3. Multiple maps in Phase 3 — *external validity*
**Problem:** Phase 3 uses a single fixed map seed, so even 100 games only sample RNG variance on **one**
board; results may be map-specific.

**Design:** sweep K level seeds (e.g. 5 maps × 20 games) instead of 1 map × 100 games.

**Why not now:** it **conflicts with the "reuse the 25" goal** — the existing games are all on one map, so
a multi-map run cannot combine with them. Recommend it as a *separate* study rather than a top-up. (If
adopted, vary `FIXED_LEVEL_SEED` per game-block in `Phase3Runner`.)

### 4. Stronger / more diverse baselines — *more informative win rates*
MCTS loses ~100% in this configuration, so its matchups carry little signal beyond "EMCTS-family ≫ MCTS."
Consider tuning MCTS (rollout depth / length) or adding a scripted/heuristic baseline so the win-rate axis
is more discriminating. Low risk, moderate value.

### 5. Adversarial Phase 3 (vs a real opponent) — *closes the solo/competitive gap*
Phase 3's passive opponent makes it a pure search-quality probe but not representative of real play. A
variant vs a fixed competent opponent (e.g. EMCTS) would make the Phase-3 score trajectory adversarial and
closer to Phase 2 — at the cost of reintroducing opponent variance.

### 6. Statistical reporting — *low effort*
Report bootstrap / paired CIs for the Phase-2 win rates (fig 4 already draws 95% binomial CIs) and, where a
paired design exists, paired tests rather than independent-sample comparisons.

---

## What v2 will and won't resolve

- **Will:** tighter Phase-3 CIs (100 games), a 5000 ms win-rate matrix (the regime where the expansion
  effect is largest), and a correctly-tuned OEP at 5000 ms. This should sharpen the headline result and
  let the 5000 ms in-game advantage of CrossoverExpansion be confirmed adversarially.
- **Won't:** the *endogeneity / no-clean-board* limitation of solo play (only proposal #1 addresses that),
  nor seat bias in Phase 2 (proposal #2). These remain the honest caveats from `RESULTS.md`.
