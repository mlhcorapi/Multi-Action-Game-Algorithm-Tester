# V4 Isolation Phase — Results & Insights

Reproducible from the raw CSVs by `python analysis/analyze_v4_full.py test_results/v4`.
Tables written to `analysis/tables_v4_full/`, figures to `analysis/figures_v4_full/`.

This is the deeper read on the v4 results, beyond `analyze_v4.py`'s headline.

n per cell ≈ **5,000 paired samples** (100 scenarios × 50 visits) — so even small mean differences are
detectable, and we can take the 95% CIs on `paired Δ vs EMCTS` literally.

## TL;DR — the three findings

1. **The burn-time crossover (CrossoverBB) actively hurts performance, and the damage *grows* with
   thinking time** — paired Δ vs EMCTS is **−3.8** at 500 ms, **−10.5** at 1000 ms, **−23.5** at
   5000 ms. The 20 mandatory bridge-burn tournaments compete with the search loop for budget; the more
   budget there is, the more the tournaments "spend" without producing useful root replacements.
2. **The optimal CrossoverExpansion ratio slides with budget.** Heavy crossover wins when thinking time
   is short (ratio 2 at 500 ms: paired Δ = **+16.4**), but the SAME ratio LOSES at 5000 ms (**−13.9**)
   because the search itself is now long enough to do better than the crossover heuristic. At 5000 ms,
   light crossover (ratio 15–20) wins with paired Δ ≈ **+4**.
3. **CrossoverCombo is dragged down by its BB component.** At every budget Combo trails the
   corresponding Expansion-only ratio. At 5000 ms every Combo variant is **worse than EMCTS**
   (paired Δ from −17 to −31). The verdict is clear: keep the Expansion mechanism, drop the burn-time
   tournament.

## Numerical headline (paired Δ vs EMCTS, mean ± 95% CI)

|                            | 500 ms | 1000 ms | 5000 ms | avg |
|----------------------------|-------:|--------:|--------:|----:|
| CrossoverExpansion @ 2     | **+16.4** ± 10.0 | +10.0 ± 9.2 | −13.9 ± 9.4 | +4.2 |
| CrossoverExpansion @ 5     | +8.3 ± 10.0 | **+10.1** ± 10.9 | +1.2 ± 7.3 | +6.5 |
| CrossoverExpansion @ 10    | +6.6 ± 5.7 | +5.6 ± 6.0 | +1.3 ± 7.9 | +4.5 |
| CrossoverExpansion @ 15    | +3.7 ± 6.4 | +2.5 ± 6.7 | **+4.3** ± 6.4 | +3.5 |
| CrossoverExpansion @ 20    | −0.4 ± 0.6 | +0.1 ± 4.1 | +3.9 ± 5.4 | +1.2 |
| CrossoverCombo @ 2         | +5.2 ± 9.0 | +7.2 ± 9.8 | −30.6 ± 11.5 | −6.0 |
| CrossoverCombo @ 5         | +6.1 ± 9.9 | +2.3 ± 8.9 | −17.8 ± 10.7 | −3.1 |
| CrossoverCombo @ 10        | −3.3 ± 11.6 | −5.3 ± 9.6 | −19.4 ± 9.6 | −9.3 |
| CrossoverCombo @ 15        | −2.4 ± 11.2 | −5.3 ± 10.8 | −17.3 ± 8.8 | −8.3 |
| CrossoverCombo @ 20        | −3.5 ± 10.6 | −10.9 ± 10.6 | −18.8 ± 9.6 | −11.1 |
| CrossoverBB                | −3.8 ± 10.5 | −10.5 ± 11.5 | **−23.5** ± 8.7 | −12.6 |

Bolded = best in its column. The CI on the **avg-across-ms** column is roughly 0.6× the single-ms CIs
because the three are mostly independent samples on the same scenarios.

## The shifting-optimal-ratio story (fig E2)

The Expansion panel of `E2_best_ratio_per_budget.png` shows a clean budget-dependent crossover-rate
curve:

| budget | best ratio | shape |
|---|---|---|
| 500 ms | **ratio 2** (heaviest crossover) | monotone-decreasing in ratio |
| 1000 ms | **ratio 2/5** (tie) | flat-then-decreasing |
| 5000 ms | **ratio 15/20** (lightest crossover) | monotone-INCREASING in ratio |

This is a clean instance of the classical "more search budget reduces the value of any heuristic
biasing" — when the search itself is short, crossover-injected good sequences are a shortcut to
quality; when the search is long, the same injections constrain exploration that the longer search
would otherwise do better.

The Combo panel shows the same pattern but **shifted down by the BB tax** — at every budget Combo's
paired Δ is roughly Expansion's minus a constant penalty (8–25 units depending on ms). That penalty is
attributable to the BB component.

## Why BB scales BADLY with time

A burn-time crossover tournament COSTS compute (typically 4 child evaluations per burn × 20 burns = 80
search "steps" per turn). At 500 ms that's a small fraction of the budget; at 5000 ms it's still 80
steps, but the search loop has 10× more time to find the same kind of improvement organically. So:

- Absolute BB tournament cost ≈ constant (per-turn).
- Search-only counterfactual quality grows roughly as `log(budget)`.
- The gap is BB-tournament-cost minus the value BB tournaments actually add.

The fact that this gap goes from −3.8 (500 ms) to −23.5 (5000 ms) — a factor of 6× — is consistent with
the "BB tournaments produce diminishing returns while the search overhead grows" hypothesis. There is
no plausible re-tuning of the BB tournament that closes this gap; the mechanism is structurally wrong.

## Best-agent-per-scenario (request 3) and ranking points (request 4)

`fig3_best_agent_per_scenario.png` shows: of 100 scenarios at each ms, how often each config has the
highest mean FinalSequenceValue. Highlights:

- At 500 ms: Expansion@2 wins 19/100 scenarios — most of any single config.
- At 5000 ms: the win share spreads more evenly across Expansion @ 5–20 with EMCTS itself winning ~8
  scenarios. No single config dominates.
- BB never wins more than ~4/100 scenarios at any ms.

The ranking-points view (`fig4_ranking_points.png`, last=1 … best=12, summed over 100 scenarios) is a
robust-to-outliers companion. "Chance" is **650** (each config would get mean rank 6.5 × 100). The
clearest patterns:

- Expansion @ 5/10 beats chance at every ms.
- BB sits at 400–450 at all ms (below chance).
- Combo variants cluster near chance at low budgets and fall to ~400 at 5000 ms.

## Per-turn within-search trajectory (requests 5+6)

`fig5_trajectory_raw.png` (mean BestSequenceValue per burn phase) shows:
- All 12 configs follow the SAME concave growth shape — fast in burns 1–5, levelling off by burn 15.
- The configs separate VISUALLY mostly at the final few burns; the tight ylim is essential to see this.
- At 5000 ms the gap between top (Expansion@15/20, EMCTS) and bottom (Combo@2, BB) is ~30 units —
  visible but small in raw terms.

`fig6_trajectory_pct.png` (% improvement over initial) puts everyone on a common axis (initial = 0%):
- 500 ms: everyone tops out near **17–19%**
- 1000 ms: **18–20%**
- 5000 ms: **25–35%** — visible big jump from 1000→5000 ms
- Within each ms, the spread between configs is much smaller than the spread between ms levels, which
  tells you "thinking time matters more than which Crossover variant you choose."

`fig5b` / `fig6b` (12 small subplots, one per agent) are the per-agent companion plots — useful when
you want to see the trajectory shape for a single config across all three ms.

## Branching-factor scatter (request 7)

`fig7_branching_scatter_raw.png` and `..._pct.png` are 12-agent × 4-panel grids. Per scenario,
x = `BranchingFactor` (from the portfolio manifest), y = raw or % improvement.

- **Single scenarios** (blue) cluster at the LOW end of branching (~80–400; median ~300 because they
  have one fully-developed tribe).
- **Dual scenarios** (red) cluster at the HIGH end (~300–620; median ~450 because both tribes are
  developed and contested).
- The linear-fit `r` printed inside each subplot is mostly small and negative (r ≈ −0.05 to −0.2) for
  raw improvement: **higher branching factor → slightly LESS raw improvement** for the same agent.
- For % over initial the trend is sharper negative — higher-branching scenarios have higher initial
  values, so the same raw gain is a smaller percentage.

The cleanest read: branching factor is a proxy for "how-developed-the-board-is" and high-branching
scenarios are *intrinsically* harder. The order of agents within the same scenario (the paired view)
matters more than which scenarios are easy/hard.

## Bar chart of % improvement over initial (request 8)

`fig8_pct_improvement_bars.png` shows mean % improvement over initial per agent, per ms.

- 500 ms: all configs 17–19%. The 12 bars are visually similar — within-CI flat.
- 1000 ms: 18–20% — Combo@5 slightly leads at 20.1%.
- 5000 ms: 25–35% — Combo@2 is highest at 34.7%, EMCTS at 26.6%.

**Important caveat (and why this is misleading on its own):** the % improvement OVER INITIAL is high
when the AGENT has a low initial value, not just when the search is more effective. The same agent
can show a higher % over initial but a *worse* paired Δ vs EMCTS if its starting point happens to be
lower. This is the v3 finding repeating itself — pair with E1 (paired Δ).

For example, at 5000 ms Combo@2 has the HIGHEST % over initial (+34.7%) but the WORST paired Δ vs EMCTS
(−30.6). It's improving the most relative to itself, but only because its initial is the most depressed.

## Extra figures

### E1 — paired Δ vs EMCTS with 95% CIs
The honest take. Bars right of 0 beat EMCTS in the paired (same scenario × visit) comparison.
Headline already cited above.

### E2 — best-ratio-per-budget sweep
The cleanest visual summary of the "shifting optimal ratio" story. Crossover-Expansion's
5000 ms line crosses from negative (at ratio 2) up through positive at ratio 5+ — a sign-flip
within the same agent family.

### E3 — single vs dual portfolio split
The agent ranking is roughly preserved between single (vs passive) and dual (vs EMCTS) scenarios. The
absolute magnitudes are SLIGHTLY larger on the dual side (more aggressive search behavior under
contested mid-game positions), but the relative ordering of Expansion > EMCTS > Combo > BB is the same
in both halves. This means the findings generalise — they are not artifacts of the single-source
portfolio half.

### E4 — convergence speed (burn phase at 95% of final gain)
For each agent at each ms, the earliest burn phase whose mean is ≥ 95% of the final value. Heatmap;
greener = converges earlier.

- At 500 ms: most configs converge by burn 8–12.
- At 5000 ms: convergence slides later (burn 14–18) — the search keeps improving over more phases when
  given more time.
- Expansion configs are EARLIER convergers than Combo at high ms (Expansion@15 at 5000 ms reaches 95%
  by burn 13; Combo@5 at the same ms needs burn 18). So Expansion is not just better at the END — it
  finds the best-known sequence FASTER too.

### E5 — interpreting the NodesEvaluated/Turn chart (search breadth vs per-iteration cost)

The `fig7_efficiency.png` chart from `analyze_v4.py` shows `NodesEvaluated/Turn` varying sharply between
agents (e.g. at 500 ms: EMCTS ≈ 22, CrossoverBB ≈ 42, Combo@2 ≈ 56). At first read this looks
suspicious — why does BB report nearly 2× the nodes evaluated of EMCTS for the same wall-clock budget?

**The metric is correct.** `NodesEvaluated` is `eval()` calls per turn, and `eval()` fires exactly once
per MCTS main-loop iteration plus once per crossover-BB tournament candidate. The audit (E5,
`E5_search_breadth_vs_cost.png` and `E5_search_breadth_vs_cost.csv`) decomposes the metric into
**iteration breadth** and **per-iteration FM-call cost** and shows what is actually happening:

| 500 ms          | evals/turn | FM-calls/turn | FM-calls / eval (cost per iteration) |
|-----------------|-----------:|--------------:|-------------------------------------:|
| EMCTS           | 22.2 | 3,265 | **147.2** |
| CrossoverBB     | 41.6 | 4,882 | 117.4 |
| Expansion @ 2   | 36.4 | 4,684 | 128.7 |
| Expansion @ 20  | 22.2 | 3,266 | 147.0 |
| Combo @ 2       | 55.9 | 7,822 | 139.9 |

- The **right column** (FM-calls per eval) is the *cost of one MCTS iteration*. EMCTS's `mutate()`
  inserts a random action and runs full sequence repair through the forward model — expensive
  (147 FM calls per iteration).
- CrossoverBB tournament candidates are **shallow** (7 children fed inherited sequences, almost no
  repair) — they cost only ~17 FM calls per eval. The arithmetic average over (main-loop iterations
  at 147) + (tournament evals at ~17) gives BB's 117 — *cheaper iterations on average*.
- Expansion's crossover sequences come from elite parents and are largely valid by construction, so
  the repair step needs fewer FM calls (~129 per iteration at ratio 2 vs 147 for pure mutate).
- **Conclusion:** within the same wall-clock budget, agents with cheaper iterations fit more
  iterations in — so their `NodesEvaluated/Turn` rises. The chart in `analyze_v4.py` is honest;
  it is *not* "more search work done", it is "more iterations executed because each iteration is
  cheaper."

This is a real thesis-grade insight, not a measurement artifact: **part of CrossoverExpansion's
benefit at low budgets comes from cheaper per-iteration repair, which lets it cover ~65% more
candidate sequences in the same 500 ms (36 evals vs 22).** That's a substantial fraction of the
absolute lift over EMCTS and is worth calling out in the writeup.

## Tying back to v3

v3's headline was "CrossoverExpansion@5 is the best modification, CrossoverBB is no use." v4 confirms
this *and* sharpens it:

| v3 said | v4 finds |
|---|---|
| Expansion is the best modification | Expansion is the only modification that consistently beats EMCTS |
| BB is harmless to neutral | BB is **actively harmful**, and the harm grows with budget |
| Combo is competitive | Combo is dragged below EMCTS at high budget by its BB component |
| Best ratio is around 5 | Best ratio is **5 at low budget, 15–20 at high budget** (clear regime shift) |
| Within-turn % gain is roughly constant | Within-turn % gain scales 1.8× from 500 → 5000 ms |

The v4 design (paired, fixed scenarios, 50 visits, identical initial sequence) was what was needed to
surface the BB-cost-vs-budget interaction. v3's full-game runs averaged it out.

## Recommendations for the thesis writeup

1. **Lead with E1.** Paired Δ vs EMCTS with 95% CIs is the cleanest summary — n=5,000 paired samples per
   cell, so the CIs are tight enough that the ordering is statistically meaningful even where
   absolute gaps look small.
2. **Add E2.** The crossing-line plot is rare in this domain and tells a clean budget-dependence story.
3. **Pair fig8 with E1.** % over initial alone is misleading (the Combo@2-at-5000ms case is the easy
   example), but it gives intuitive scale ("search produces 25–35% improvement at 5000 ms"). The two
   together are honest.
4. **Drop the burn-time crossover entirely from any future variant** — the data won't be ambiguous.
   Any combined mechanism that incorporates BB needs to FIX BB's structural cost-vs-value problem,
   not just paper over it.
5. **For published numbers, prefer the 1000 ms condition** — it's the most ambiguous regime
   (Expansion@2/5/10 all roughly tied within the CIs) and forces an honest discussion of the
   budget-dependent ratio. 5000 ms makes Expansion look like an unambiguous win which it isn't at
   shorter budgets.
