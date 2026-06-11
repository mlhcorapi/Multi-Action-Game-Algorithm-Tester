# V5 — Single-vs-Dual Hypothesis Battery

## The mystery

v4 found that crossover's advantage over EMCTS is concentrated in **dual** (two-player) scenarios:
the BF-tertile × source heatmap (E7) and the single/dual split (E3) show crossover roughly neutral
to strongly positive across the whole dual BF range, but deteriorating with branching factor in
single (passive-opponent) scenarios — strongly negative in high-BF singles. The ANCOVA confirmed a
positive source effect at the same BF for all 11 crossover configs, plus a BF × source interaction
(negative BF slope in single, flat-to-positive in dual). v4's data could not say **why**.

v5 instruments the search itself to discriminate five candidate explanations. All measures are
**observation-only**: collecting them performs no forward-model calls, no game-state copies and no
heuristic evaluations, so the deterministic compute budget and the search trajectories are
completely unchanged by the instrumentation.

## Hypotheses and discriminating measurements

### H1 — Blend incoherence (building-block incompatibility)
In economic (single) scenarios, elite sequences pursue *independent* plans — e.g. each fully
develops a different city. Uniform crossover of two such plans yields a child that does both
halfway: individually feasible actions, jointly wasteful. In dual scenarios the opponent anchors a
shared tactical structure, so blends recombine compatible building blocks.

*Measurement:* per main-loop crossover event, the child's value against its two parents' values.
Columns: `XoChildVsBestParentSum`, `XoChildVsMeanParentSum`, `XoChildBeatsBestParent`,
`XoChildWorseThanBothParents` (counts), normalised per event in analysis.

*Prediction if true:* "child below both parents" elevated in single at the same BF tier; mean
(child − best parent) more negative in single. → **E18 blend coherence**.

### H2 — Repair disruption
High-BF single states invalidate spliced actions at more positions, so the greedy repair loop
rewrites much of the crossover child — destroying the blended material before it is ever evaluated.
Crossover then degenerates into expensive greedy resampling.

*Measurement:* per child, actions **kept** vs **greedily replaced** during repair — separately for
crossover children (`XoRepairKept` / `XoRepairReplaced`) and mutation children (`MutRepairKept` /
`MutRepairReplaced`). The mutation columns are the baseline: if mutation children are equally
disrupted in single scenarios, disruption is scenario-generic, not crossover-specific.

*Prediction if true:* crossover replaced-fraction rises with BF in single and exceeds the mutation
baseline; in dual it stays near the baseline. → **E19 repair disruption**.

### H3 — Elite-pool collapse (inbreeding-by-source)
Crossover always breeds the top-2-ever nodes. If the economic landscape is smoother, the top-2
converge to near-clones faster, so crossover re-breeds the same pair into duplicate children and
its iterations are wasted — v4's inbreeding mechanism (E9), but now resolved by source.

*Measurement:* per event, the parents' positional similarity (`XoParentSimilaritySum`; fraction of
aligned positions holding the same action); duplicate-child counts for both operators
(`XoChildDuplicates`, `MutChildDuplicates` — child's post-repair sequence already evaluated this
turn).

*Prediction if true:* parent similarity and crossover duplicate rate higher in single than dual at
the same budget, and the single-dual gap grows with budget. → **E20 elite-pool collapse**.

### H4 — Operator credit (who finds the improvements)
Perhaps crossover children simply never drive the best sequence forward in single games — whatever
their average quality — so every crossover iteration displaces a productive mutation.

*Measurement:* per turn, count and summed best-value gain of new-best events attributed to the
generating operator: `MutBestGains` / `MutBestGainSum` vs `XoBestGains` / `XoBestGainSum`. (Credit
covers the two main-loop operators; the initial root and BB tournaments are excluded — tournaments
are already logged per-burn as CrossoverEventData.)

*Prediction if true:* crossover's share of total best-gain is substantial in dual and near zero in
single, with the gap widening at high BF. → **E17 operator credit**.

### H5 — Action-space composition (combat availability, not player count)
"Single vs dual" is a label; the mechanism could be the **composition** of the action space —
contested combat actions (present in dual) vs sprawling economic actions (dominant in late-game
single captures). Note the single games do contain a passive opponent with units, so attack actions
exist there too; what differs is whether they matter.

*Measurement:* the v5 portfolio manifest gains per-scenario action-type counts of the captured
state: `NumAttackActions`, `NumMoveActions`, `NumOtherUnitActions`, `NumCityActions`,
`NumTribeActions` (categories: `Attack` | `Move` | other `UnitAction` | `CityAction` |
`TribeAction`).

*Prediction if true:* within either source, crossover's paired Δ correlates with attack share at
fixed BF; the source label adds little once composition is controlled. → **E21 action composition**
(+ joins with the paired-Δ data for the deeper test).

## Where the data lives

- **`<leaf>/turn_telemetry.csv`** — one row per (scenario, visit) turn, next to each leaf's
  `results.csv`. Schema: `IsolationPhaseRunnerV5.TELEMETRY_HEADER`; semantics:
  `core.data.dto.OperatorTelemetryData`. Written by all 12 configs (EMCTS/CrossoverBB log zero
  crossover events by construction — they are the mutation baseline).
- **`_portfolio/portfolio.csv`** — v4 columns plus the five action-composition columns.
- Instrumentation lives in `ExperimentalEMCTSAgent` (mutation + crossover) and `EMCTSAgent`
  (mutation baseline); the smoke test (`NewExperimentSmokeTestV5`, section 5) asserts the telemetry
  is present and sane before a full run.

## Analysis outputs (analyze_v5_full.py)

| figure/table | hypothesis | discriminating signal |
|---|---|---|
| `E17_operator_credit.*` | H4 | % of best-sequence gain from crossover, single vs dual × budget |
| `E18_blend_coherence.*` | H1 | % blends beating/below both parents, source × BF tier |
| `E19_repair_disruption.*` | H2 | % child actions replaced by repair, crossover vs mutation baseline |
| `E20_pool_collapse.*` | H3 | parent similarity + duplicate rates, source × budget |
| `E21_action_composition.*` | H5 | action-type mix and attack share of the 200 scenarios |

The hypotheses are not mutually exclusive — H1/H2/H3 are mechanisms, H4 is the bottom line they
produce, and H5 reframes "source" as a continuous property. The battery is designed so each gets an
independent, directly-logged signal rather than an inference from outcome data.

## Symmetry and overhead

**Symmetry.** The mutation-side telemetry is collected by *identical code* in `EMCTSAgent` (the
baseline) and in the mutation branch of `ExperimentalEMCTSAgent`: same counters, same pre-`eval`
duplicate check, same best-gain attribution, and the same kept/replaced accounting inside `repair()`.
The `seqKey` sequence-identity function is byte-for-byte identical in both classes, so the
duplicate-rate comparison (E20) and the repair baseline (E19) are like-for-like. The crossover-only
fields are zero by construction for EMCTS / CrossoverBB, which is exactly what makes them the
mutation baseline.

**Overhead.** Measured by running 120 fixed-compute turns per agent with the instrumentation present
vs. reverted (identical seeds, single-threaded):

| agent | without telemetry | with telemetry | overhead |
|---|---:|---:|---:|
| EMCTS (mutation only) | 676.9 ms/turn | 682.5 ms/turn | **+0.8 %** |
| CrossoverExpansion@5 (mutation + crossover) | 676.7 ms/turn | 694.4 ms/turn | **+2.6 %** |

Crucially the per-turn `FinalSequenceValue` and forward-model-call totals were **identical** with and
without telemetry (e.g. 605,561.0 and 2,802,589 for EMCTS in both builds) — the instrumentation
performs no `advance`/`copy`/`eval`, so it consumes **zero** compute units and leaves the search
trajectory and every result **unchanged**. The only cost is a few % of wall-clock (one extra
`seqKey` per iteration for the duplicate check, plus one `O(len)` parent-similarity pass per
crossover event). Under the STOP_COMPUTE budget that wall-clock cost does not affect the experiment
at all; it only makes a run take ~1–3 % longer.
