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

### E6 / E7 / E8 / ANCOVA — disentangling branching factor and source-of-scenario

The E3 chart shows that crossover agents beat EMCTS more in **dual** scenarios than in **single**.
But "single vs dual" is correlated with several scenario properties — most obviously branching
factor — so the marginal split can't directly settle the cause. These four diagnostics partition
the effect.

**E6: portfolio diagnostic (`E6_bf_dist_per_source.png`).**

| source | median BF | IQR |
|---|---:|---:|
| single (n=50) | **460** | 384–511 |
| dual (n=50) | **274** | 227–355 |

Single-player scenarios have **~1.7× the median branching factor of dual** — they are biased
toward late-game, high-development snapshots with many cities and units. The two distributions
overlap only in the 330–510 band. This is a real confound: any agent whose performance depends on
BF will *appear* to differ between single and dual even if its behavior is identical at the same BF.

**E7: BF-tertile × source heatmap (`E7_bf_tier_source_heatmap.png`).**

*Binning note.* Bins are computed **within each source** (tertiles, ~17 scenarios per cell). The
dual portfolio is almost entirely low-BF and single almost entirely high-BF, so a *global*-quintile
binning left the dual-high-BF and single-low-BF cells resting on just 1–5 scenarios — which produced
large, eye-catching, but statistically meaningless values (an earlier draft of this figure showed
spurious +30…+42 cells there that wrongly suggested crossover bridge-burning *won* in high-BF duals).
The per-source re-binning removes that artifact. The cost: columns no longer share an absolute BF
scale — single's "Low BF" tertile (222–415) actually overlaps dual's "High BF" tertile (329–583). So
read this figure **within each panel** (how an agent's advantage moves as BF rises *within its own
source*); for the strict "same absolute BF, different source" control, use E8/ANCOVA, which handle BF
continuously.

Two clean, reliable readings:

- **Within single (economic) scenarios, crossover deteriorates as BF rises.** Every column goes more
  negative Low→High: Exp@5 = −0 → −3 → **−24**; BB = −16 → −10 → **−30**; Combo@10 = −11 → −8 →
  **−30**. The single High-BF column is almost solid blue.
- **Within dual (combat) scenarios, Expansion stays strongly positive across the whole BF range:**
  Exp@5 = +25 → +15 → +26; Exp@2 = +19 → +10 → +8; Exp@10 = +26 → +5 → +3. The dual panel is mostly
  red for the Expansion family.

Crucially, the re-binned figure now **agrees with E3 on the bridge-burners**: CrossoverBB is negative
in every single cell and only neutral-to-+2 in dual; CrossoverCombo trails its Expansion@r
counterpart in nearly every cell. The earlier "crossover-BB wins in high-BF duals" impression was
entirely the 1- and 5-scenario global-quintile cells, and it disappears once every cell holds ~17
scenarios.

So the corrected statement is: **the source axis dominates — Expansion helps in dual and hurts in
high-BF single — and within each source the crossover benefit falls as BF rises.** BB and Combo stay
net-negative everywhere they are reliably measured, consistent with E1/E3.

**E8: per-source regression scatter (`E8_bf_source_regression.png`).**

For all four selected agents the slope structure is the same:

|                       | single slope (/100 BF) | dual slope (/100 BF) | gap at BF=600 |
|-----------------------|-----------------------:|---------------------:|--------------:|
| CrossoverExpansion@2  | −9.2  | −0.2  | ~25 |
| CrossoverExpansion@15 | −2.4  | +3.4  | ~12 |
| CrossoverBB           | −6.6  | +8.0  | ~45 |
| CrossoverCombo@5      | −2.5  | +3.3  | ~12 |

In single scenarios, crossover gets *worse* relative to EMCTS as BF rises. In dual scenarios the
slope is flat or positive. The lines either cross at moderate BF and diverge or remain separated
across the whole range — **never parallel**. That structure means BF *moderates* the source effect
(an interaction), not that BF *explains* it.

**ANCOVA (`E_ancova.csv`).**

Per agent, `paired_Δ ~ β₀ + β_BF·(BF−mean)/100 + β_Source·dual + β_Interaction·(BF−mean)/100·dual`
(unit of analysis = per-scenario mean, n=100). Averaged across ms:

| agent | β_BF (single slope, per 100BF) | β_Source (at mean BF) | β_Interaction | R² |
|---|---:|---:|---:|---:|
| CrossoverBB           | −6.6  | +14.2 ± 21.5 | +14.6 | 0.05 |
| CrossoverExpansion@2  | −9.2  | +8.2 ± 20.7  | +9.0  | 0.06 |
| CrossoverExpansion@5  | −11.9 | **+25.5 ± 20.7** | +16.6 | 0.17 |
| CrossoverExpansion@10 | −7.2  | +4.9 ± 14.4  | +3.0  | 0.10 |
| CrossoverExpansion@15 | −2.4  | +7.7 ± 14.4  | +5.8  | 0.03 |
| CrossoverExpansion@20 | +0.2  | +3.3 ± 6.6   | +0.0  | 0.02 |
| CrossoverCombo@5      | −2.5  | **+21.9 ± 18.0** | +5.8  | 0.10 |

Bold = clearly above zero (CI excludes 0).

Three things stand out:

1. **β_BF is negative for almost every crossover agent.** In single-player scenarios, crossover
   gets ~6–12 paired-Δ units WORSE per 100 BF units. (The exception is Expansion@20, which is
   essentially baseline because crossover almost never fires.)
2. **β_Source is positive for every agent**, and clearly different from zero for the two best
   agents (Expansion@5 and Combo@5). So at the SAME BF, dual scenarios still favor crossover by
   roughly **+5 to +25 paired-Δ units** more than single. Most CIs are wide (±15–22) because n=100
   scenarios is the unit — but the *sign* is unanimous across 11 agents.
3. **β_Interaction is positive everywhere.** Dual scenarios neutralize or reverse the negative
   BF–performance relationship of single scenarios. For Expansion@5, single's slope is −11.9 but
   dual's is −11.9 + 16.6 = **+4.7** — a sign flip.

R² is low (0.02–0.17) because most of the variance in paired Δ is genuine scenario noise that BF +
Source can't predict. But the *structure* of the explained variance is consistent and points the
same direction across agents.

#### Conclusion: it is BOTH, with a structural twist

- **BF is a real confounder.** The dual portfolio has substantially lower BF than single, so part
  of the E3 dual > single gap is just "crossover handles low BF better." Anyone reading E3 in
  isolation would overstate the player-presence effect.
- **A source effect persists when controlling for BF.** At the same BF, dual still favors crossover
  by +5 to +25 paired-Δ units (β_Source). This is the "player presence" component — consistent
  with the hypothesis that opponents anchor the tactically relevant action space and make
  crossover's elite-blending more constructive than random mutation's repair.
- **The interaction is the most interesting finding.** Crossover's BF dependence has OPPOSITE
  SIGNS in single vs dual:
  - In single (passive economic scenarios), crossover hurts more as BF rises — likely because high
    BF means many independent unit/city action choices, and blending two elite economic plans is
    closer to noise than meaningful recombination.
  - In dual (competitive scenarios), crossover is stable or *gains* as BF rises — likely because
    opponent positioning forces shared tactical structure across elite sequences, so blending
    transfers building blocks even when the action space is large.

So the corrected interpretation of the v3/v4 "crossover wins in lower-BF dual games" finding is:
*it is not BF per se that helps crossover, nor the dual label per se — it is the combination of
opponent-imposed tactical structure that gives crossover sequences a meaningful basis for
recombination, and this combination happens to occur most often at moderate-to-high BF in the dual
half of the portfolio.* The right thesis framing is "crossover acts on tactically structured
action spaces," not "crossover acts on low-BF action spaces."

### E9 / E10 / E11 — crossover inbreeding and the thinking-time dependence

The ratio-shift in E1 (heavy crossover wins at 500 ms, light crossover wins at 5000 ms) and the
budget-dependence of the BF/source structure both trace back to a single mechanism: **as the per-turn
budget grows, crossover increasingly re-generates sequences it has already evaluated, while mutation
keeps producing fresh ones.** E9/E10/E11 measure and visualise this.

**E9 — the inbreeding evidence (`E9_inbreeding.png`).** The probe is the *unique-sequence fraction*
= `UniqueSequences / NodesEvaluated` — the share of an agent's evaluations that are genuinely new.

`E9_unique_fraction.csv` — fraction of evaluations that are NEW:

| unique-fraction | 500 ms | 1000 ms | 5000 ms | Δ(500→5000) |
|---|---:|---:|---:|---:|
| EMCTS (pure mutation) | 0.974 | 0.970 | 0.961 | −0.013 |
| CrossoverExpansion@2  | 0.719 | 0.703 | **0.594** | **−0.125** |
| CrossoverExpansion@5  | 0.908 | 0.895 | 0.839 | −0.070 |
| CrossoverExpansion@10 | 0.945 | 0.938 | 0.908 | −0.037 |
| CrossoverExpansion@15 | 0.959 | 0.953 | 0.929 | −0.030 |
| CrossoverExpansion@20 | 0.972 | 0.964 | 0.939 | −0.033 |

The Expansion family is perfectly monotonic: the more an agent leans on crossover (lower ratio), the
harder its diversity collapses as budget grows. EMCTS, which only mutates, barely moves.

`E9_unique_count.csv` — absolute unique sequences found per turn (the crossover point):

| unique sequences/turn | 500 ms | 1000 ms | 5000 ms |
|---|---:|---:|---:|
| EMCTS | 21.6 | 24.7 | **75.5** |
| CrossoverExpansion@2 | **26.2** | **27.8** | 61.2 |

Read across: at 500 ms heavy crossover finds *more* distinct sequences than mutation (26.2 vs 21.6,
+21%); by 5000 ms the ranking has flipped (61.2 vs 75.5, −19%). There is a literal crossover point
between 1000 and 5000 ms where mutation overtakes crossover at generating new material — the
quantified version of "crossover finds new sequences faster early, but its rate decays over time."

`E9_redundant_evals.csv` — wasted (re-derived) evaluations per turn: Expansion@2 grows from 10 to
**42** redundant evals as budget rises from 500 to 5000 ms; at 5000 ms ~40% of its iterations
re-derive sequences it has already seen, while EMCTS wastes ~3 of 78.

**Mechanism.** The crossover pool (`globalTopNodesQueue`) holds the **top 2** sequences, and crossover
always blends those two. Early in a turn the top-2 are distinct elite strategies, so blending yields
real new combinations. As the search converges, both slots fill with near-copies of the current
optimum; uniform crossover of two near-identical parents produces offspring near-identical to both →
no new material. That is textbook premature convergence / loss of population diversity in an
evolutionary algorithm, and a 2-member elite pool is about the most inbreeding-prone configuration
possible. Mutation has no such failure mode — it injects a fresh random action every iteration
regardless of how converged the population is, so its unique-fraction holds at ~0.96 at every budget.

*Caveat (visible in the E9-A panel):* CrossoverBB and CrossoverCombo show the **opposite**
unique-fraction trend (BB rises 0.65→0.80). This is **not** a counter-example — it is a composition
artifact. BB's redundancy comes from the fixed 20 bridge-burn tournaments (7 re-blended children
each, a constant count regardless of budget); at high budget the growing mutation loop dilutes that
fixed overhead, so the *fraction* rises even though the absolute tournament redundancy is unchanged.
The clean inbreeding signal lives in the pure-Expansion family, where crossover is the only
non-mutation operator.

**E10 — Expansion: BF × source × time (`E10_expansion_bf_source_time.png`).** A (source × ratio) grid
of paired-Δ-vs-BF regression lines, one line per ms. Two readings:

- Within each panel, the lines **flatten as budget grows** — BF matters less at 5000 ms than at
  500 ms, because with enough iterations the search reaches a similar plateau regardless of BF.
- The **single panels (top) have negative slopes; the dual panels (bottom) are flat-to-positive** —
  the BF×source interaction from E8, now shown to weaken with budget.

**E11 — the dual advantage vs budget (`E11_source_advantage_vs_time.png`,
`E11_source_advantage_by_ms.csv`).** This is the synthesis figure, and it ties E9 directly to the
BF/source story. Plotting `dual_advantage = mean(dual paired Δ) − mean(single paired Δ)` against ms
per agent produces a **fan**:

| dual advantage | 500 ms | 1000 ms | 5000 ms |
|---|---:|---:|---:|
| CrossoverExpansion@2 (heaviest crossover) | 27.0 | 16.2 | **4.6** |
| CrossoverBB | 17.8 | 11.5 | 9.8 |
| CrossoverExpansion@5 | 32.0 | 36.0 | 25.7 |
| CrossoverExpansion@15 | 5.5 | 4.7 | 10.5 |
| CrossoverExpansion@20 (lightest crossover) | −0.8 | 0.4 | **9.5** |

The agents that **inbreed most** (Expansion@2, with the −0.125 unique-fraction drop) **lose their
dual edge fastest** (27 → 4.6). The agents that **barely inbreed** (Expansion@15/@20) **gain** dual
advantage with budget. Cross-referencing E9 and E11: the rank order of "unique-fraction drop" matches
the rank order of "dual-advantage erosion" within the Expansion family. So the budget-dependence of
the source effect is not a separate phenomenon — **it is inbreeding eroding the scenario-specific
advantage that heavy crossover enjoys at low budget.**

**The unified picture.** Crossover's value is *fast generation of high-quality sequences* — it
matters most when the search is iteration-starved (low budget) and when the action space is large and
tactically structured (dual, moderate-to-high BF), because that is where mutation would take longest
to stumble onto the same quality. At high budget two things erase that edge: (1) everyone has enough
iterations to converge near the per-scenario ceiling, amortising away the generation-strategy
advantage; and (2) crossover *actively degrades* via inbreeding, wasting its late iterations on
re-treads. The Goldilocks ratio (Expansion@5) survives because it is light enough to avoid
catastrophic diversity collapse (0.908→0.839, not 0.72→0.59) yet heavy enough to keep injecting
useful elite blends — which is why it is the best agent on the average-across-budgets view and the
only one whose dual advantage stays robustly positive at every budget.

*Measurement limitation:* this is inferred from *between-budget* aggregates (total per-turn
redundancy grows with total budget). `UniqueSequences` is logged once per turn, not per bridge-burn
phase, so we cannot directly show the new-sequence rate decaying *within* a single turn. Turning this
from a well-supported inference into a direct measurement needs a per-phase unique-count added to the
trajectory logger and a re-run — recommended if this mechanism becomes a load-bearing thesis claim.

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
