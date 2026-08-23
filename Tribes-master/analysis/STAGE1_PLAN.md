# Stage 1 — Does within-turn learned action bias improve EMCTS?

*Closed-loop follow-up to Stage 0 (which found a strong, budget-increasing, Mid-granularity
per-action signal in plain EMCTS search). Laptop-scale: ~12 h on 4 threads. Isolation-phase
instrument: single planning turns from a fixed scenario portfolio, paired seeds across configs —
the same design and the same `visitSeed(scenario, visit)` convention as v5/v6.*

## Question

Stage 0 showed the *observational* payoff: mutations that inserted a top-tercile action (by
within-turn Q̂) produced deltas ~0.7 SD above average at Mid/640k. Stage 1 closes the loop: when
the sampler actually *uses* Q̂ — ε-greedy insertion, and Q̂-shortlisted greedy repair — does the
search find better final sequences at the same compute budget, and how do the design knobs
(ε, granularity, removal credit, update schedule, shortlist size) matter?

## Agent

`Stage1BiasedAgent` (extends `ExperimentalEMCTSAgent`). Fixed by Stage-0 results: paired-delta
credit (child − parent), per-turn scope, **no decay** (lost in all 12 Stage-0 cells), shrinkage
toward the running global mean (m = 5). Integer-keyed statistics (ACTION × qualifier ordinals) so
bookkeeping costs ~ns and charges nothing to the ComputeMeter. All defaults recover plain EMCTS
**bit-identically** (same RNG stream, same forward-model calls) — the runner's verify gate asserts
it before every run, so the Baseline config *is* the baseline.

Two uses of Q̂:

1. **Biased insertion** — with prob 1−ε, insert the feasible action with max Q̂ (ties uniform,
   so an empty table degrades to uniform).
2. **Repair shortlisting** (`K`) — greedy repair normally pays copy+advance+eval for *every*
   feasible candidate (~300–450 mid-game); with K on, candidates are ranked by Q̂ (free) and only
   the top K get the exhaustive treatment. Under STOP_COMPUTE the saving is automatically
   reinvested as extra iterations.

## Configurations (11)

| Config | ε | Keys | Removal | Updates | K | Tests |
|---|---|---|---|---|---|---|
| Baseline | 1.0 | – | – | – | 0 | the control (+ logs sequence composition for Stage 2) |
| Bias_e75/e50/e25/e10 | .75/.50/.25/.10 | Mid | on | continuous | 0 | dose-response of bias strength |
| Coarse_e25 | .25 | Coarse | on | continuous | 0 | granularity (Stage-0 F2 said Mid) |
| NoRemoval_e25 | .25 | Mid | off | continuous | 0 | removal credit (Stage-0 F7 said on) |
| PerBurn_e25 | .25 | Mid | on | burn-frozen | 0 | your batched-update-at-burns proposal vs continuous |
| Shortlist15 | 1.0 | Mid | on | continuous | 15 | shortlist alone (no bias) |
| Short15_e25 / Short5_e25 | .25 | Mid | on | continuous | 15 / 5 | shortlist × bias; K dose |

## Schedule (~8–9 h estimated + portfolio build; 12 h budget)

| Level | Configs | Scenarios × visits | Turns | Est. |
|---|---|---|---|---|
| **160k cu** (main grid) | all 11 | 60 × 10 | 6,600 | ~4.5 h |
| **640k cu** (confirmation; largest expected effect) | Baseline, Bias_e25, Short15_e25 | 60 × 6 | 1,080 | ~3 h |
| **40k cu** (budget trend) | same 3 | 60 × 10 | 1,800 | ~0.5 h |

Portfolio: 30 single + 30 dual scenarios — the deterministic first-N subset of the v5/v6 100+100
build (`BuildV6Portfolio`), built on first run (~0.5–1 h, journaled/resumable). Estimates use the
Stage-0 laptop calibration (~45 µs/unit under 4-thread load + ~1 s greedy init per scenario turn);
the run prints per-leaf ETAs and is safe to Ctrl-C + resume at any point.

## Pre-registered readings

- **Primary**: paired mean FinalValue diff, Bias_e25 − Baseline, at 160k. Success = CI > 0.
  (Scenario-clustered bootstrap; visits within a scenario share a state and are not independent.)
- **Expected shape**: effect grows with budget (Stage-0 F1); Mid > Coarse (F2); removal ≥
  no-removal (F7); continuous ≈ per-burn (F6: signal usable from ~20% of the turn).
- **Shortlist**: read quality (final value) *and* volume (NodesEvaluated vs Baseline). Shortlist
  can win by iterating more even if per-repair choices are slightly worse. Short15 alone isolates
  the mechanism; Short15_e25 is the candidate combined operator.
- **Guard-rail**: duplicate-child share (already 57% at 640k unbiased — Stage-0 F10). If a bias
  config's duplicate share rises far above Baseline while its final values do not, the bias is
  collapsing diversity, and ε must stay higher.
- **Stage 2 groundwork** (observation-only, from Baseline seqlogs): split-half reliability of
  *unordered pair interaction* estimates Δ(x,y) = E[V|x,y] − E[V|x,¬y] − E[V|¬x,y] + E[V|¬x,¬y]
  over z-scored child values, plus the pooled top-synergy pair list. This answers "is there
  learnable pair structure beyond single-action effects?" before any pair-aware operator is built.

## Running

```
./run_stage1.sh build
./run_stage1.sh smoke      # ~5-10 min end-to-end check (tiny portfolio, 8k budget)
./run_stage1.sh run        # the 12-hour run (4 threads; resumable)
./run_stage1.sh analyze
```

## Threats to validity

- **Isolation ≠ playing strength**: final sequence value is heuristic-relative search quality; any
  playing-strength claim needs a later full-games phase (as in v6).
- **Feedback effects**: Stage-0 uplift was measured on unbiased trajectories; a biased sampler
  changes which states get visited. That is exactly why Stage 1 exists; disagreement between
  Stage-0 uplift and Stage-1 outcome is itself a result.
- **Portfolio subset**: 60 scenarios (deterministic subset of the v5/v6 200). Narrower map/state
  coverage than the server experiments; adequate for design-selection, not for final claims.
- **One laptop, compute-unit budgets**: wall-clock is reported, never assumed; results are
  reproducible at any thread count by construction.
