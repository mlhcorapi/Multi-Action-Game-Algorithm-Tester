# Dual-vs-Single Experiment — Plan

**Status: PLAN ONLY. No experiment code written yet (per request).**

## Objective

Find the *cause* of the v4 finding that CrossoverExpansion beats EMCTS far more in **dual**
(combat) scenarios than in **single** (economic) scenarios — and in particular test the proposed
mechanism that *combining combat sequences recombines more coherently than combining economic
sequences* against a battery of alternative explanations.

The v4 data establishes the **outcome** (crossover helps more in dual, controlling for branching
factor — see `RESULTS_V4.md` E7/E8/E11/ANCOVA). It cannot establish the **mechanism**, because:
- The crossover offspring-quality metric that exists today (`RawImprovement`) is only logged for the
  CrossoverBB/Combo *tournament*, not the CrossoverExpansion *main-loop* crossover that drives the win;
  it is also confounded by a best-of-8-elite baseline and has a near-empty single-scenario sample
  below 5000 ms.
- Source (single/dual) and branching factor (BF) are confounded in the v4 portfolio
  (single median BF 460 vs dual 274), so the observational data cannot separate "combat" from "BF".

Two complementary studies address these gaps.

---

## Reusing the v4 portfolio

Confirmed reusable as-is — **no regeneration required**:
- All 100 scenarios are saved: `v4/_portfolio/scenarios/scenario_NNN.bin` (gzipped `GameState` via
  `FastGameStateIO`), loadable with `IsolationPhaseRunner.loadPortfolio()`.
- `v4/_portfolio/portfolio.csv` carries full provenance per scenario: `Source`, `SourceLevelSeed`,
  `SourceGameSeed`, `CapturedTick`, cities/units/score, and `BranchingFactor`.
- The working seeds are therefore already known (the manifest's seed columns); if a scenario ever
  needs regenerating, `BuildV4Portfolio` can be re-pointed at those exact seeds rather than
  re-searching. `_completed.txt` confirms all 36 v4 cells finished cleanly.

So Study A points straight at the saved `.bin` files. The seed-fallback path the request mentions is
not needed but is available.

---

## Study A — Instrumented re-run on the v4 portfolio (observational)

Same paired-by-(scenario,visit) design as v4, trimmed to the agents that matter and with new
per-crossover-event logging.

### Configs
- **EMCTS** (paired baseline — required to compute each scenario's crossover *advantage* = paired Δ
  vs EMCTS, which is the quantity we are trying to explain) + **CrossoverExpansion @ {2,5,10,15,20}**.
- Drop CrossoverBB and CrossoverCombo — not needed for this question; halves runtime.
- 6 configs × 3 thinking times {500, 1000, 5000} ms × 100 scenarios × 50 visits = **90,000 planning
  turns** (~half of v4's 180k).
- Bridge burning fixed at 20 (`depth = 21`), identical to v4.
- Shared visit seed across configs → identical random initial sequence (the v4 paired guarantee).

### Core new instrumentation — one row per crossover-expansion EVENT

At the main-loop crossover site (`ExperimentalEMCTSAgent` ~L252–260) the two parents `p1`, `p2` and
the `child` are all in scope, and `child.getValue()` is set right after `eval()`. Emit a new
`crossover_events.csv` with, per event:

| field | meaning |
|---|---|
| scenario, source, BF, visit, ms, ratio, phase | context keys |
| `v1`, `v2` | parent heuristic values (`p1.value`, `p2.value`) |
| `c` | offspring value (`child.value`, post-eval) |
| `parent_overlap` | fraction of positions where `p1.seq[i] == p2.seq[i]` (parent similarity) |
| `parent_jaccard` | Jaccard of the two parents' action multisets (order-free similarity) |
| `child_novel_frac` | fraction of offspring actions present in neither parent (how much repair rewrote) |
| `repair_count` | offspring actions replaced by greedy repair (the "invalidation" signal) |
| `accepted` | did `child` become the new incumbent best (`child.value > bestNode.value`)? |

Derived metrics (in analysis):
- **Coherence ratio** `coh = (c − mid) / (max(v1,v2) − mid)`, `mid = (v1+v2)/2`.
  `coh ≈ 1` → offspring matches the better parent (coherent/combat-like); `coh ≈ 0` → offspring sits
  at the midpoint = the **"two half-upgraded cities" signature** (your economic case); `coh < 0` →
  destructive; `coh > 1` → synergistic.
- **Synergy rate** `P(c ≥ max(v1,v2))`, **destruction rate** `P(c < min(v1,v2))`, **halfway rate**
  `P(c ≈ mid)`.

**Primary test:** distribution of `coh` (and synergy/destruction/halfway rates), single vs dual,
stratified by BF quintile; plus ANCOVA `coh ~ BF + Source + BF×Source`. The hypothesis predicts dual
`coh` clusters at/above 1 and single `coh` clusters at/below 0, *at matched BF*.

### Alternative hypotheses (data collected in the SAME run)

| # | Hypothesis (why crossover helps more in dual) | Prediction | Metric | New logging? |
|---|---|---|---|---|
| **H1** | **Combat sequences recombine more coherently** (primary) | dual `coh` ≫ single `coh` at matched BF | coherence ratio | yes (event log) |
| **H2** | **Elite-pool diversity**: combat yields more *different* elites, so crossover has better raw material; economic elites are near-duplicates so crossover just reshuffles one plan | dual `parent_overlap` ≪ single; crossover benefit rises as overlap falls | `parent_overlap`, `parent_jaccard` | yes (event log) |
| **H3** | **Sequence invalidation / repair burden**: combat sequences are "more easily invalidated"; crossover+repair handles this better than raw mutation | dual `repair_count` higher; crossover's edge over mutation tracks repair rate | `repair_count` for crossover AND for mutation children | yes (event log + mutate log) |
| **H4** | **Headroom / difficulty**: single (economic) is "easy" — EMCTS already near-optimal, no room for crossover to add value; dual has more headroom | crossover advantage correlates with EMCTS's own gain-to-ceiling, not with source per se | EMCTS gain (`Final−Init`) + per-scenario ceiling (max final across configs) | **derivable from v4** (no re-run) |
| **H5** | **Action-type composition of the SEQUENCES** (not just the scenario): it is combat-*actions*, not combat-*scenarios*, that crossover likes | crossover benefit tracks the combat-action fraction of the planned sequence | combat vs economic action histogram of the best sequence | yes (TurnData field) |
| **H6** | **Sequence length / horizon**: combat turns plan shorter/longer sequences; crossover behaves differently by length | crossover benefit tracks sequence length, which differs by source | sequence length (actions before END_TURN) | yes (TurnData field) |
| **H7** | **Landscape ruggedness**: combat = rugged value landscape (favors crossover jumps), economic = smooth (favors mutation hill-climb) | value variance higher in dual; crossover benefit tracks variance | per-turn value variance | yes (TurnData field) |

H2 is the strongest *alternative* and is partly a refinement of H1 — note the layered test:
*among events with matched `parent_overlap`, is `coh` still higher in dual?* If yes → genuine
recombination-coherence effect (H1). If the dual advantage disappears once overlap is matched → it
was really elite-diversity (H2). This separation is the analytical centerpiece.

### Attribution analysis
Per scenario, regress the **crossover advantage** (paired Δ vs EMCTS) on
{mean coherence, mean parent_overlap, repair rate, headroom, combat-action fraction, BF, source}.
Whichever predictor absorbs the single/dual gap is the operative mechanism. This directly answers
"which explanation is doing the work."

### Output layout
```
test_results/dual_vs_single/
  _portfolio/                                  (reuse v4 portfolio)
  EMCTS/{500,1000,5000}ms/results.csv          (turn+burn trajectory, v4 schema)
  CrossoverExpansion/ratio_R/{ms}ms/results.csv
  CrossoverExpansion/ratio_R/{ms}ms/crossover_events.csv   (NEW per-event log)
  _completed.txt
```

---

## Study B — Controlled branching-factor sweep (interventional)

Your second idea: vary BF *systematically within each source* to decouple BF from combat. The v4
observational data can't do this because single≈high-BF and dual≈low-BF are confounded.

### Is it possible? **Yes — programmatically, with caveats.**

`BranchingFactor = gs.getAllAvailableActions().size()` = tribe actions + Σ city actions + Σ unit
actions. Units carry a `status` field (`FRESH → MOVED → ATTACKED → FINISHED`); a unit's available
actions depend on it. So BF is controllable on a **fixed board**:

- **Lower BF (cleanest):** set `unit.status = FINISHED` on K units → removes their actions →
  reduces BF while keeping positions, score, and material identical. Titrate K to hit target bands.
- **Raise BF:** clone/spawn extra units (adds material — less clean).

"Manually set up each scenario" is best realized as a **small BF-controlled scenario builder** (load
a base `.bin` → mutate unit statuses / add units → re-measure `getAllAvailableActions().size()` →
save when it lands in the target band → validate). Hand-editing serialized `GameState`s is
impractical; the builder achieves the same intent reproducibly.

### Design (factorial)
`source {single, dual}` × `BF band {~150, 250, 350, 450, 550}` × `config {EMCTS, Expansion@2/5/10/15/20}`,
each over 50 visits × 3 ms, with the Study-A instrumentation. Use a handful of base scenarios per
source and generate the BF variants from them.

### The decisive test
- If crossover's benefit tracks **BF** (same benefit at the same BF, regardless of source) → it's
  branching factor.
- If crossover's benefit tracks **source/combat** at the **same BF** → it's combat, not BF.

### Caveats (stated honestly)
- Freezing units holds the *board* fixed but not the *optimal plan* (a frozen unit can't act), so it
  is "same board, fewer actions," not a perfectly clean BF-only knob. There is no perfectly clean
  BF-only knob in Tribes.
- Adding units changes material/value.
- BF is controllable to **bands (±)**, not exact values — each unit contributes a variable action
  count.
- Constructed states must pass the v4 validity check (cities≥1, units≥1, a tiny `planTurn` does not
  throw) and avoid the known engine-crash configurations.

---

## Cost estimate
- **Study A:** 90k planning turns (~half of v4). New logging is I/O only, no extra search cost.
- **Study B:** ~2 sources × 5 BF bands × ~3 bases × 6 configs × 3 ms × 50 visits — scales with the
  base-scenario count; start small (3 bases each) and expand if signal is promising.

## What will need code (once the plan is approved)
1. `CrossoverEventData`-style DTO + `crossover_events.csv` writer; logging hook at the
   `ExperimentalEMCTSAgent` main-loop crossover site (p1/p2/child values, overlap, repair count).
2. Optional extra `TurnData` fields for H5/H6/H7 (sequence length, combat-action fraction, value
   variance).
3. `DualVsSingleRunner` — a trim of `IsolationPhaseRunner` (EMCTS + Expansion only, + event CSV).
4. (Study B) a `BuildBfSweep` scenario constructor (freeze/add units, verify BF band, save + manifest).
5. An analysis notebook: coherence test, the seven hypotheses, and the attribution regression.

## Open questions for you
- **Study A agent set:** EMCTS + 5 Expansion ratios only (BB/Combo dropped) — confirm.
- **Action taxonomy for H5:** how to classify `Move` (combat-repositioning vs expansion) — propose
  classifying `Attack` + `Move`-adjacent-to-enemy as combat, everything else economic.
- **Study B base scenarios:** how many bases per source, and which BF bands exactly.
