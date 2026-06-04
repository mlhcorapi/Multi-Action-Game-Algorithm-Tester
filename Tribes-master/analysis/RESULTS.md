# EMCTS Crossover Experiment — Results & Discussion

Analysis of `test_results/full/`. Figures in `analysis/figures/`, summary tables in
`analysis/tables/`. Regenerate with `python analysis/analyze.py`.

The experiment compares vanilla **EMCTS** (baseline) against three modifications:

| Agent | What it changes |
|---|---|
| **CrossoverExpansion** | Crossover in the MCTS **expansion** step. Every `mutationExpansionRatio`-th expansion is a crossover of two elite sequences; the rest are normal mutations. **Higher ratio ⇒ *less* crossover** (ratio 2 ≈ 50 % crossover, ratio 20 ≈ 5 %). |
| **CrossoverBB** | Crossover at the **bridge-burning** step — the root committed at each burn is chosen by a crossover tournament instead of plain best-child promotion. |
| **CrossoverCombo** | Both modifications at once. |

Two phases:
- **Phase 3** (controlled): tracked agent vs a passive opponent, fixed map, SCORE mode, 30 turns,
  50 games/config (25 at 5000 ms). Low variance → good for *search-internal* metrics.
- **Phase 2** (competitive): each modification vs each baseline (MCTS, OEP, EMCTS), CAPITALS,
  50 turns, **100 games/matchup**, wall-clock-time budget. This is the *outcome* test.

Budgets are **deterministic compute-units** in Phase 3 (≈500/1000/5000 ms equivalents = 13.4k/26.8k/134k
units) and equal wall-clock time in Phase 2.

---

## TL;DR

1. **Crossover in the expansion step helps; crossover at the bridge-burning step does not.**
   CrossoverExpansion is the only modification that beats vanilla EMCTS by a statistically
   meaningful margin, and only once the budget is large enough to pay for it (win rate vs EMCTS
   **0.64** at 1000 ms; in-game score **+4.9 %** at 1000 ms, **+13.1 %** at 5000 ms). CrossoverBB is
   statistically indistinguishable from EMCTS (win rate ≈ 0.51) and slightly worse on search value.

2. **The expansion crossover works through recombination *quality*, not diversity.** At equal compute
   it explores **fewer** distinct sequences than EMCTS, but the ones it finds score higher. There is an
   inverted-U sweet spot at a *moderate* crossover rate (ratio ≈ 5–10); too much crossover (ratio 2) or
   too little (ratio 20) both underperform.

3. **The bridge-burn crossover almost never improves the committed root** — in 56–90 % of burns it
   changes nothing, it improves the root in only 2–9 %, and its mean effect is slightly **negative** at
   every burn phase. It spends compute that plain EMCTS puts into more search.

4. **Budget interaction:** crossover overhead is only repaid with enough thinking time. At 500 ms every
   modification is a coin-flip against EMCTS; the expansion advantage emerges at 1000 ms and grows at
   5000 ms.

---

## How the metrics are measured (answers to the two questions)

Both are computed inside the agent, per turn, in the `eval(GameState, node)` method that scores every
candidate node with the heuristic. (Code: `EMCTSAgent.java`, `ExperimentalEMCTSAgent.java`,
`OEPAgent.java`.)

### Number of nodes visited (`NodesEvaluated`)
A plain counter `nodesEvaluated`, reset at the start of each turn, **incremented once on every
`eval()` call** — i.e. once for every candidate node the heuristic scores. That covers the initial
root, every search iteration's mutated/expanded child, and every bridge-burn tournament child.

- **Duplicates are included.** The counter never de-duplicates: if two different node objects happen
  to hold the same action sequence, both still increment it. So `NodesEvaluated ≥ UniqueSequences`
  always.
- It is **not** the same as `FmCalls`. `FmCalls` counts forward-model `advance()` calls (incremented
  in the agent's `advance()` wrapper, `fmCallsCount++`) and is the *compute-demand* metric. A single
  node may need several advances to roll its sequence forward, and a node whose state is already
  cached (`node.getGs() != null`) needs **zero** advances but is still counted as one evaluated node.
  So `NodesEvaluated` = number of heuristic evaluations; `FmCalls` = number of state transitions.
- MCTS reports its tree-node count; OEP reports its number of individual evaluations.

### Number of unique sequences (`UniqueSequences`)
Each agent keeps a `HashSet<String> uniqueSeqKeys`, cleared at the start of each turn. On every
`eval()` call it does `uniqueSeqKeys.add(seqKey(node.getSequence()))`, and logs `uniqueSeqKeys.size()`
at the end of the turn.

`seqKey(seq)` builds a **canonical content string**: it concatenates each `Action`'s `toString()`
separated by `|` (a `null` action becomes `_`). Two nodes therefore count as the *same* sequence iff
their ordered action lists stringify identically — this is **content equality, not object identity**,
so re-derived or re-rooted copies of the same plan collapse to one key.

- `UniqueSequences` = number of **distinct action sequences** among all evaluated nodes that turn.
- **No floor, independent of bridge burning.** It is literally a set size. If a promoted root happens
  to be a sequence never evaluated before, it is counted naturally on its next `eval()`; nothing forces
  it to ≥ 20 or ties it to the number of burns.
- MCTS logs `-1` (a per-action tree search has no whole-turn "sequences" to de-duplicate); OEP uses the
  same `seqKey` mechanism over its genomes.

---

## Findings

### 1. Crossover-expansion ratio sweep — `fig1_ratio_sweep.png`, `T2`
`FinalSequenceValue` vs ratio is an **inverted U** at every budget: it peaks at ratio **10 (500 ms)**
or **5 (1000/5000 ms)** and falls off for both heavy crossover (ratio 2) and near-pure mutation
(ratio 20). Meanwhile `UniqueSequences` rises *monotonically* as crossover decreases (ratio 20 explores
the most distinct sequences). So the best-performing setting is **not** the most diverse one — moderate
crossover trades a little breadth for higher-quality recombined sequences. Selected best ratios:
`500ms→10, 1000ms→5, 5000ms→5`.

### 2. In-game score, controlled setting — `fig7_final_score.png`, `T1`/`T4`
Per-game `FinalScore` vs the passive opponent (mean ± 95 % CI):

| vs EMCTS | 500 ms | 1000 ms | 5000 ms |
|---|---|---|---|
| CrossoverExpansion@5 | −3.5 % | **+4.9 %** | **+13.1 %** |
| CrossoverBB | +3.3 % | −5.3 % | −0.9 % |
| CrossoverCombo@5 | −3.8 % | −1.5 % | **+9.6 %** |

CrossoverExpansion's advantage **grows with budget**; CrossoverBB never establishes a consistent gain.
*Caveat:* the per-game CIs overlap (n = 50, and 25 at 5000 ms), so treat these as trends and lean on the
n = 100 Phase-2 win rates for significance.

### 3. Phase-2 win rates — `fig4_winrates.png`, `T3`/`T4b`
(n = 100, SE ≈ 0.05, so 95 % CI ≈ ±0.10; 0.50 = tie.)

| vs EMCTS | 500 ms | 1000 ms |
|---|---|---|
| CrossoverExpansion@5 | 0.51 | **0.64** ✓ |
| CrossoverExpansion@10 | 0.45 | – |
| CrossoverBB | 0.51 | 0.51 |
| CrossoverCombo@5 | 0.56 | 0.59 (borderline) |
| CrossoverCombo@10 | 0.54 | – |

Only **CrossoverExpansion@5 at 1000 ms (0.64)** clears 0.5 by more than a CI. CrossoverBB is a coin-flip
against EMCTS at both budgets. Against the other baselines everyone **crushes MCTS** (0.97–1.00) and
**beats OEP at 1000 ms** (≈0.65). Because all four experimental agents sit in the same seat against the
same baseline under the same seeds, the *relative* ordering (Expansion > Combo > BB ≈ EMCTS) is robust
even to any fixed first-player advantage.

### 4. Why bridge-burn crossover fails — `fig3_burn_crossover_benefit.png`
At each burn, `RawImprovement = bestCrossoverChild.value − incumbent.value`. The crossover leaves the
root **unchanged in 56–90 %** of burns, **improves it in only 2–9 %**, and its mean % effect is
**≤ 0 at every burn phase**. The burn-time crossover tournament rarely beats what plain promotion
already had, so it mostly converts budget into wasted recombinations. (Interestingly it is *less*
harmful under Combo — green — because the expansion crossover has already enriched the candidate pool.)

### 5. Within-turn search trajectory — `fig2` (% gain) and `fig10` (absolute value)
Best-sequence value vs bridge-burn phase for the four family agents, with **CrossoverExpansion and
CrossoverCombo both held at ratio 5** so the only difference between Expansion and Combo is the
bridge-burn-crossover mechanism (the ratio is fixed, not mixed into the comparison).

- **`fig2_within_turn_trajectory.png` (% gain over burn-1):** all variants make most of their gain in the
  first few burns; total within-turn improvement is small (~1.2–2.1 %). Expansion@5 and Combo@5 climb a
  little faster than EMCTS and CrossoverBB is flattest at small budgets — the relative trajectory mildly
  favours the expansion-crossover, while the burn-step crossover itself adds little (see #4).
- **`fig10_family_value_trajectory.png` (absolute value — the same transform fig 9 applies to fig 8):**
  the band *levels* here reflect each agent's cumulative board strength, which is **endogenous** (each
  agent plays its own game), so the ordering shifts with budget and is **not** a clean search-quality
  ranking — e.g. CrossoverBB sits highest at 500 ms but lowest at 5000 ms. The comparable quantity is the
  *slope* (within-turn search gain), which is ~constant across agents (≈ 85 / 110 / 170 units), again
  showing the agents differ mainly in cumulative board value, not in per-turn search gain.

### 5b. Trajectory by crossover-expansion ratio — `fig8` (% gain) vs `fig9` (absolute value)
Two views of **CrossoverExpansion alone, one line per ratio**:

- **`fig8_expansion_ratio_trajectory.png` (% gain over burn-1):** the ratios nearly overlap and the small
  ordering is *inverse to quality* — ratio 2 (the most crossover) looks "best" despite having the worst
  absolute value. This is an artefact of the normalisation, not a real effect (see below).
- **`fig9_expansion_ratio_value.png` (absolute BestSequenceValue):** the ratios separate into clear bands,
  the best ratio (bold) is on top in every panel, and at 5000 ms the inverted-U is unmistakable
  (ratio 5 > 10 > 15 > {2, 20}). This matches the sweep (#1).

**Why the %-over-baseline view is misleading for ranking ratios.** One might expect the greedy initial
sequence value to be identical across ratios (greedy init is ratio-independent *for a fixed board*).
Empirically it is **not** — it varies 4–8 % per level (e.g. 5000 ms: ratio 2 = 7113 vs ratio 5 = 7727) —
because each ratio plays its **own game** and reaches a different board by turn *t*; a stronger-searching
ratio builds a richer board, which raises its greedy starting value too (the initial is *endogenous*).
Meanwhile the **absolute within-turn search gain over that initial is nearly constant across ratios**
(≈ 85 / 110 / 170 units at 500 / 1000 / 5000 ms). So a per-turn % graph divides a ~constant numerator by
a ratio-dependent denominator and **cancels the very signal that distinguishes the ratios**, leaving only
inverse-baseline noise. The ratio's real benefit is a slow, **compounding effect on board development**
(it shows up in the cumulative value level), not a larger per-turn search jump — which is exactly why the
ratio is selected on absolute `FinalSequenceValue` (#1) and why `fig9`, not `fig8`, is the right tool for
comparing ratios.

### 6. Search efficiency at equal compute — `fig6_efficiency.png`, `T1`
At a fixed compute budget EMCTS evaluates the **most unique sequences** (e.g. 5000 ms: EMCTS 2161,
Expansion@5 1803, BB 2001, Combo 1768). Crossover therefore *reduces* raw exploration breadth — it
spends compute recombining elites rather than mutating into fresh territory. That this still yields
higher in-game score for Expansion reconfirms #1: the gain is **quality of recombination**, not
quantity of sequences. `FmCalls` per turn is ≈ 0.85 × the compute-unit budget for every agent, as
expected (advances are ~85 % of compute).

### 7. Heuristic value vs realised score — `fig5_value_vs_score.png`
Per-game `FinalSequenceValue` vs `FinalScore` correlate strongly and the correlation **tightens with
budget** (r = 0.82 → 0.90 → 0.93). The relationship is essentially agent-independent (all four lie on
one cloud), which means the modifications change *where* an agent lands on the value axis, not how
efficiently heuristic value converts to score. This validates the SIMPLE heuristic as a faithful score
proxy and justifies selecting the ratio on `FinalSequenceValue`.

---

## Conclusions on the two modifications

- **Crossover expansion is the worthwhile modification.** With a moderate crossover rate (every ~5th
  expansion) and a sufficient budget it produces higher-value sequences and a real win-rate edge over
  vanilla EMCTS, increasing with thinking time. Its mechanism is recombination quality, not added
  diversity.
- **Crossover bridge-burning is not worthwhile on its own.** It rarely changes the committed root, is
  marginally negative when it does, and yields no win-rate improvement over EMCTS. The compute is better
  spent on ordinary search.
- **Combo** mostly rides the expansion benefit while paying the bridge-burn cost; it is competitive only
  at the largest budget (5000 ms: +9.6 % score, 0.59 vs EMCTS at 1000 ms).
- **Budget matters:** at 500 ms none of the modifications beat EMCTS — the crossover overhead is not
  repaid. The expansion advantage appears at 1000 ms and grows at 5000 ms, so crossover-EMCTS is most
  attractive in longer-thinking-time regimes.

## Caveats
- Phase 3 is vs a passive opponent on a fixed map: ideal for low-variance search metrics but not
  adversarial play; its per-game score CIs overlap, so the Phase-2 win rate carries the significance.
- `FinalSequenceValue` is endogenous (it depends on the board the agent itself built), so absolute
  cross-agent comparisons of it are confounded; within-turn improvement, efficiency ratios, and the
  Phase-2 win rate are the cleaner measures.
- MCTS is weak in this configuration (per-action search, 50-turn CAPITALS), so its ~1.0 losses are not
  very informative beyond "EMCTS-family ≫ MCTS."
- Sample sizes: Phase 2 n = 100/matchup; Phase 3 n = 50 (25 at 5000 ms).
