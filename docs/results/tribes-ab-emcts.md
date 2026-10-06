# Tribes: the AB-EMCTS worked result, re-analysed by MAGAT

*A thesis result re-analysed by MAGAT. The runs are the thesis line's own (the R7615, 2026-08-28 to
2026-09-02), imported unchanged into `games/tribes/results/ab-emcts/`. The Tribes engine is the work of
its authors; see the credits in the repository's `README.md`.*

The line asked whether a within-turn learned action-value table (Qδ) improves EMCTS's search. Its answer,
in the thesis retrospective's words, was yes, but not through the mechanism it was designed around:
zero-evaluation repair directed by a *coarse* table (`comma-z0-coarse`) beats the same search with
exhaustive greedy repair (`emcts-comma`). On 200 held-out scenarios it is worth about 5.4 times the
compute budget, and most of that is the table's own contribution, measured against the identical arm
with its ranking replaced by noise (`comma-z0-random`).

This page asks a narrower question: does MAGAT's analyzer, given only the archived rows, reproduce the
numbers the retrospective printed? It does, to the decimal printed, with one exception, explained below.
`analysis/tests/test_worked_result.py` asserts every row of the table.

## Reproduction

The retrospective's numbers come from the thesis's `analyze_stage1.py`, plus measures computed by hand
after the line finished. MAGAT recomputes them from `games/tribes/results/ab-emcts/`. Point estimates
are deterministic and must match to the printed decimal. Intervals come from a different bootstrap draw
and agree to bootstrap noise, at most 1.3 points here. The retrospective's own two printings of the
headline interval differ by as much ([+208.4, +281.7] in the retrospective, [+208.7, +281.3] in stage F's
`SUMMARY.md`).

Holdout, 80,000 units, against `emcts-comma`, unless the row says otherwise:

| Retrospective | Claim | Retrospective | MAGAT |
|---|---|---|---|
| 1, 4.2 | headline, `comma-z0-coarse` | +244.3 [+208.4, +281.7] | +244.3 [+207.3, +282.8] |
| 1 | the same at 400,000 | +297.4 [+252.5, +342.4] | +297.4 [+251.2, +342.9] |
| 1, 4.2 | the table's own contribution (`- comma-z0-random`) | +211.7 [+175.4, +249.0] | +211.7 [+176.3, +250.3] |
| 1, 4.2 | W7, in wall-clock | +223.3 [+186.6, +261.1], 14% clamped | +223.3 [+187.1, +261.4], 14% clamped |
| 4.2 | without terminal pairs | +241.7 | +241.7 |
| 4.2 | single-source / dual-source scenarios | +305.2 / +183.4 | +305.2 / +183.4 |
| 4.1, 4.2 | `comma-rand15` | +133.8 [+107.2, +161.5] | +133.8 [+108.0, +160.5] |
| 4.1, 4.2 | `comma-short15-coarse` | +199.9 [+167.0, +234.7] | +199.9 [+166.2, +235.5] |
| 4.2 | ranking pays at coarse keys (`short15-coarse - rand15`) | +66.1 [+43.7, +89.9] | +66.1 [+43.2, +90.3] |
| 4.2 | ranking at mid keys (`short15 - rand15`) | -21.2, ns | -21.2 [-43.8, +1.7] |
| 4.2 | a fine table against none (`z0-fine - z0-random`) | +3.8 [-13.3, +20.7], ns | +3.8 [-13.1, +20.1] |
| 4.2 | zero-evaluation arms' repair candidate evaluations | 0.0 | 0.0, all four arms, both budgets |
| 4.1, 4.2 | development: `comma-z0-coarse`, `comma-rand15`, `comma-short15-coarse` | +268.1, +139.8, +206.5 | +268.1, +139.8, +206.5 |
| 4.2 | development: table's contribution, coarse ranking, mid ranking | +244.2, +66.6, -20.9 | +244.2, +66.6, -20.9 |
| 4.2 | development: `z0-fine - z0-random` | +23.4, significant | +23.4 [+7.4, +39.2] |
| 3.1 | baseline gain at 80k / 400k; arm gain at 80k | 259.5 / 487.7; 497.4 | 259.5 / 487.7; 497.4 |
| 3.1 | **equivalent budget multiplier** | **5.4x [4.3x, 6.5x]**, 198 scenarios | **5.4x [4.4x, 6.6x]**, 198 scenarios, extrapolated |
| 3.1 | the same on development | 6.4x [5.2x, 8.1x] | 6.4x [5.2x, 8.1x] |
| 3.2 | the baseline's seconds, 80k to 400k | 3.5x | 3.5x |
| 3.2 | `comma-z0-coarse`'s own seconds over the baseline's | 1.16x | 1.17x (1.16x on all rows) |
| 3.2 | net time-equivalent speedup | ≈3.3x | **3.2x [2.7x, 3.8x]** (3.3x on all rows): see below |
| 3.3 | against plain EMCTS (`emcts`), development | ≈48x [28x, 88x] | 48x [28x, 91x] |
| 3.3 | what 5x the budget buys plain EMCTS / comma | +119.9 / +228.2 | +119.9 / +228.2 |
| 3.4 | search-gain multiple at 80k (500.3 / 258.6) and 400k | 1.93x, 1.60x | 1.93x (500.3 / 258.6), 1.60x |
| 3.4 | oracle: turns where all eight arms produced a sequence | 1,584 | 1,584 |
| 3.4 | oracle: best of field / mean regret, all eight arms | the table | identical, in the same order (`report/SUMMARY.md`) |
| 3.4 | Cohen's d: `z0-coarse`, `short15-coarse`, `rand15`, `z0-random` | 0.62, 0.54, 0.42, 0.10 | 0.62, 0.54, 0.42, 0.10 |
| 4.3 | nodes / unique sequences per turn, `z0-coarse` against `emcts-comma` | 1,435 / 1,293 against 457 / 408 | 1,435 / 1,293 against 457 / 408 |
| 4.3 | the search's size, and the duplicate share | 3.1x; 0.077 against 0.064 | 3.1x; 0.077 against 0.064 |
| 4.3 | biased insertion (development): throughput, duplicate share | 1.47x; 0.063 to 0.23 | 1.47x; 0.063 to 0.23 |
| 5 | ε = 0.10 at 400k (development) | -65.0 | -65.0 |
| 5 | insertion keys: coarse over mid, mid over fine | +45.3, +57.2 | +45.3, +57.2 |
| 5 | repair keys: fine against mid, mid against coarse | -121.7, -99.1 | -121.7, -99.1 |
| 5 | comma selection over plus | +22.3 [+9.8, +34.8] | +22.3 [+9.8, +34.7] |

## How each number is defined, which the reproduction pinned down

- **Terminal turns** (worth about ±2e9) are found by |final| ≥ 1e9. Every pairing table exists with and
  without them. The headline row includes them, as the retrospective's does, and d excludes them.
- **The budget multiplier** uses the (scenario, visit) cells where the baseline ran at every level and the
  arm ran, none of them terminal. On the holdout that is visits 0 to 5 (the 400k level ran six), on 198
  scenarios. Mean search gains are interpolated log-linearly in budget, and the interval resamples
  scenarios. Pairing the cells across levels is what turns 258.6 (every non-terminal 80k row) into
  section 3.1's 259.5.
- **The oracle** uses the turns where every arm at the budget has a row and none is terminal. A tie
  for the best counts for every arm that reaches it.
- **Cohen's d** is the mean of the paired differences over their sample standard deviation, on the pairs
  where neither turn is terminal.

## The one difference: section 3.2

The retrospective computed its wall-clock equivalent (3.8x the baseline's time needed, 1.16x spent,
≈3.3x net) on every non-terminal row: 258.6, 487.7 and 500.3 rather than section 3.1's paired 259.5,
487.7 and 497.4. MAGAT computes its wall-clock multiplier on the same paired cells as the units
multiplier, so the two answer the same question about the same turns. On those cells it is **3.2x
[2.7x, 3.8x]**: the baseline would need 3.73x its own 80k time, and the arm spends 1.17x. The test asserts
both: MAGAT's 3.2x, and that the retrospective's all-rows formula still gives 3.8x, 1.16x and 3.3x on
these rows. "Yes, near enough 4x the thinking time" stands either way.

*Decided 2026-10-06 (DESIGN §17): MAGAT's paired-cell definition stays. The gap is definitional, not
random, and this flag is resolved.*

## What MAGAT adds

- **Replication, row by row.** Every headline, contrast and multiplier appears for development and
  holdout side by side, marked `yes`, `no` or `ns both`. Development and holdout ran on the same machine
  (the R7615) in the same currency: they differ in scenarios, not in devices, so no `no` is a machine
  difference. Every `no` has one of two causes:
  - **The fine table's effect: a genuine winner's-curse shrinkage, the holdout doing its job.** At 80k
    both `no` rows are the fine table, its contrast against no table and its multiplier. That is the
    retrospective's one non-replication. At 400k the fine-table contrast fails again.
  - **A stale development cell: a price changed mid-line.** At 400k the three rows of
    `comma-short15-coarse@400000` fail: its headline, its multiplier and its contrast against
    `comma-rand15`. That development leaf ran under a price superseded before stage F (retrospective
    section 8). MAGAT's per-leaf currency lock now refuses to continue or extend a leaf under a price
    other than its own, so this cannot happen in a MAGAT run.
- **A multiplier for every arm at every budget**, each with its wall-clock twin and a flag for where the
  crossing falls. Every 400k multiplier is an extrapolation past the ladder. That is section 3.5's point
  ("the crossing point sits just past it"), here made explicit for every arm.
- **Not reproduced, derivation unknown:** the "~69% of a turn" repair budget of section 4.3, and the
  anti-additivity figures of section 5 (-19.3, -31.5, -130.3). Both are prose figures, not outputs of
  any budget, and no table computes them. `t_repairUnits` over `units` gives 82% for `emcts-comma` on
  these rows. The interaction `short15-bias-e25 - short15 - bias-e25 + off` gives -42.3, -52.9 and
  -125.4. Neither matches, so neither is the definition the retrospective used. Re-calibrating would
  not recover them: they do not come from the weights. A currency re-derived on another machine (the
  development laptop, say) would be a second currency under its own name, beside the R7615 file, never
  a replacement for it.
- **The per-burn anytime curves** are not in the repository. They are packaged with the full imported
  trees as a downloadable asset (`tools/package_worked_result.py`; see
  `games/tribes/results/ab-emcts/README.md`).
