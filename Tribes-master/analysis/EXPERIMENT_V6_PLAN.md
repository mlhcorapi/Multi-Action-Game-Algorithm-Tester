# V6 — Plan

v6 keeps v5's research questions and its full measurement battery, and changes four things: how the
thinking budget is set and verified, how the initial sequence is built, how many repeats are run, and
how the whole thing is driven.

v6 runs in two parts. **Part I is a standalone calibration experiment (C0)** that must complete and be
read before Part II is configured — it decides the budget rule and resolves one open design collision
(§5). **Part II is the isolation run** proper.

---

## 1. Research questions (unchanged)

Carried over verbatim from v4/v5; v6 re-answers them on a defensible budget.

**Performance.** Does crossover expansion improve turn-level plan quality over standard EMCTS, at which
mutation:crossover ratios, and at which thinking times? Does crossover bridge-burning help
independently of crossover expansion, and do the two compose (Combo) or interfere?

**The single-vs-dual gap.** v4 found crossover's advantage concentrated in dual (two-player) scenarios
and deteriorating with branching factor in single (passive-opponent) scenarios. v5's hypothesis battery
(H1–H5) instruments the search to discriminate five explanations: blend incoherence, repair disruption,
elite-pool collapse, operator credit, and action-space composition. **All five carry into v6 unchanged**,
with the same directly-logged signals — see [V5_HYPOTHESES.md](V5_HYPOTHESES.md), which remains the
governing document for the telemetry semantics.

The reason to re-run rather than reuse v5: the budget under which v5 was collected is the thing under
repair, and the initialization change (§3C) alters the search's starting point for every agent, so
v5's numbers do not transfer.

---

## 2. Changes from v5 at a glance

| # | Change | Status |
|---|---|---|
| A | Thinking budget set from a reference-machine calibration; realized spend logged per turn | Part I decides the rule |
| B | Greedy shared initial sequence, replacing the uniformly-random one | Requires §5 resolution |
| C | 50 visits per scenario (was 100) | Straightforward |
| D | Notebook-driven with a progress cell | Mirrors v5 notebook |
| E | Portfolio halved to 100 scenarios (50 single / 50 dual); same 12 configs, same telemetry | Scenario count halved |

---

## 3. The four changes in detail

### A. Budget: calibrated, and verified from logged spend

v5 budgeted on a single shared compute-unit scalar
(`units = 1.0·advances + 0.16·copies + 0.077·evals`, [ComputeMeter](../src/core/game/ComputeMeter.java))
with weights measured once, and **logged no evidence that the budget was actually equalised** — the only
workload column in `TurnData` is `FmCalls`, which is *expected* to differ across agents.

v6 fixes both halves:

1. **The budget rule is chosen from calibration data**, not assumed (Part I).
2. **Realized spend is logged every turn**, so budget adherence becomes a reported result rather than a
   claim. New per-turn columns:

| column | purpose |
|---|---|
| `ComputeUnits` | realized spend; overshoot vs. target is then measurable |
| `Advances`, `Copies`, `Evals` | the op mix — supports counterfactual reweighting and the "equal fmcalls would have been unfair by x%" table |
| `InitUnits` | cost of the greedy initialization alone (see §5) |
| `PlanMillis` | wall-clock, logged as evidence only — never used as a budget |

All are observation-only: reading the meter performs no `advance`/`copy`/`eval`, so trajectories are
unchanged, exactly as with the existing v5 telemetry.

**How the budget rule was settled.** Two schemes were live, and they differed only by the size of the
cost-model error:

- *Shared scalar* (v5): every agent gets the same unit budget under one global weight set. Fair **iff**
  those weights are right for every agent; a weight error biases whichever agent has the most divergent
  op mix, and more samples do not fix it.
- *Per-agent calibrated*: each agent gets the op count it completes in T seconds on the reference
  machine. The fairness criterion is direct ("equal reference-machine thinking time"), but the budget
  stops being a deterministic count and becomes a clock — which is exactly what v5 moved away from,
  because wall-clock on a loaded 90-worker server is not reproducible.

**v6 takes the third option, which has both properties: keep the shared scalar, and calibrate the
WEIGHTS per (configuration, budget level).** `ComputeCalibrateV6` measures what an advance, a copy and
an eval actually cost for each of the 12 configurations at each of the 3 levels, and expresses all of
them relative to one reference operation (an advance in `EMCTS@40000`). Every weight, `W_ADVANCE`
included, is then a real cost ratio rather than a definition, so:

- an equal unit budget is equal reference-machine thinking time — the per-agent scheme's fairness
  criterion, obtained without a clock;
- the budget stays a deterministic count, so the search is still reproducible run-for-run;
- the levels become proportional *in time*: measured per-op cost grows with tree size, which is why the
  400,000-unit level cost 26.7× the 40,000-unit level rather than 10× under one weight set. Charging
  that cost makes it ~10×.

The measurement that forced this: per-advance cost varies **13–18% between configurations within a
level** (dev laptop, `compute_profiles.csv`), and priced with one global weight set the same nominal
budget bought up to ~16% more wall-clock for one agent than another. That is an order of magnitude
larger than the effects the experiment is trying to resolve.

Part I measures which is warranted. The deciding statistic is **ms-per-unit variation between agents vs.
within an agent across scenarios** (§4.4).

### B. Greedy shared initial sequence

v5 forced `params.randomInitialSequence = true` on every agent
([ExpAgents.java:196](../src/core/data/exp/ExpAgents.java)), so all 12 configs started a
(scenario, visit) from the same *uniformly-random* sequence. v6 reverts to the greedy 1-ply
initialization that is standard EMCTS's default — for each position, evaluate every available action and
take the best.

**Implementation is a flag flip, not new code.** `randomInitialSequence` already defaults to `false`, and
the greedy branch already exists in both
[EMCTSAgent.randomActions()](../src/players/emcts/EMCTSAgent.java) and
[ExperimentalEMCTSAgent.randomActions()](../src/players/ExperimentalEMCTS/ExperimentalEMCTSAgent.java).
v6 simply stops setting the flag in the v6 agent factories.

**Sameness across agents still holds, but for a different reason and it must be re-verified.** The greedy
loop breaks score ties randomly (`m_rnd.nextInt(numTies)`), so the initial sequence is a function of the
shared per-visit seed, not a pure function of the state. Since all 12 configs run byte-identical
initialization code before any operator-specific work, they consume the same RNG draws in the same order
and must produce identical roots. v5's existing invariance gate
(`RunExperimentV5.verifyRandomInit`, checkpointed as `_random_init_ok.txt`) already asserts exactly this
and **is kept for v6**, renamed to reflect that it now guards a greedy root.

**Two consequences to carry into the analysis:**

1. *Repeats now sample less.* Under random init, visits varied both the starting point and the search
   RNG. Under greedy init the starting point is near-constant per scenario (varying only through tie-breaks),
   so the 50 repeats sample search-RNG variation almost exclusively. This tightens the paired comparison —
   which is part of why 50 is defensible where v5 used 100 (§3C) — but it means `InitialSequenceValue`
   becomes approximately a per-scenario constant, and any analysis using it as a covariate changes meaning.
2. *Headroom shrinks.* The v5 comment on the random-init mode is explicit that it produces an
   "intentionally worse-than-greedy" start to widen room-for-improvement during search. Greedy init removes
   that headroom deliberately: v6 measures whether the operators improve on a *strong* start, which is the
   more realistic and more demanding question, but effect sizes should be expected to compress relative to v4/v5.

### C. 50 visits per scenario, over a 100-scenario portfolio

`IsolationPhaseRunnerV5.VISITS_PER_SCENARIO` 100 → 50, and `BuildV6Portfolio` 200 scenarios → **100**
(50 single / 50 dual). Total turns per budget level: 100 scenarios × 50 visits × 12 configs =
**60,000** (v5: 240,000). Across three budget levels, **180,000 turns** total (v5: 720,000) — but each
turn carries roughly 3× v5's budget.

Justification for the paper: the design is paired at (scenario, visit) and greedy init removes the
starting-point variance component (§3B), so the residual being averaged is smaller than in v5. The
per-scenario-unit significance analysis from v4 (Fig5 at n=100) should be re-run at n=50 to confirm the
comparisons of interest remain adequately powered — **this is a check to perform on the Part I data
before committing**, not an assumption.

**Why the second cut came out of scenarios rather than out of visits.** Both halve the turn count and
therefore the wall-clock, but they are not the same cut. The independent replicate is the *scenario*:
the 50 visits of one scenario share a board, a captured position, a branching factor and a greedy
root, and `v6_significance.py` averages them before testing anything. Halving the visits would have
left the reported n untouched while making each scenario's mean noisier — a cost that never appears in
a table. Halving the scenarios puts the cost where it is read: n goes 200 → 100, about a √2 widening
of the paired Wilcoxon's resolution, with each scenario mean as precise as before. The 50/50
single-dual split is kept so the branching-factor contrast H5 rests on is untouched.

### D. Notebook-driven

`analysis/run_v6_jupyter.ipynb`. Since the tournament phase joined it, **one notebook covers both
phases**: Part 0 is the shared setup (environment, build, machine policy, compute-unit weights — both
phases price budgets in the same units, from the same file), Part A is this phase, Part B is the full
games ([EXPERIMENT_V6_FULL_GAMES_PLAN.md](EXPERIMENT_V6_FULL_GAMES_PLAN.md)). The table below is Part
A:

| section | contents |
|---|---|
| 0.1 | Environment check (java, CPU topology, memory, disk, stray processes and run locks in both trees) |
| 0.2 | Build (`javac @sources.txt`) |
| 0.3 | **Machine policy** — `ServerProfile` prints the worker ceiling and thread ladder, running no work |
| 0.4 | Portfolio build — parallel, journalled, restartable. **Shared**: §0.5 measures on it |
| 0.5 | **Compute-unit weights per (config, level)** — `ComputeCalibrateV6`, ~35 min; writes the weights both phases price their budgets with |
| A1 | Smoke test — `NewExperimentSmokeTestV6`, pipeline correctness plus the weight-profile plumbing |
| A2 | Budget fairness probe — realized op mix, which profile priced each config, weight sensitivity |
| A3 | Budget floor probe — the smallest level at which the operators structurally run |
| A4 | **Calibration run (C0)** — the units→ms conversion, and the flatness check on §0.5 |
| A5 | Run v6 (background + log) — also the RESUME cell; progress, thread curve and graceful stop |
| A6 | Output integrity (`VerifyV6Output`) |
| A7 | Analyse (`analyze_v6_full.py`) |
| A8 | Significance (`v6_significance.py`) |

The portfolio build and the weight calibration sit in Part 0 rather than Part A because both phases
depend on them: the tournament prices its budgets with the same weights, and those weights have to be
measured on real portfolio positions rather than on fresh-game states.

Server placement — the thread ceiling, the per-level calibration, the runtime load governor and what
survives an interruption — is documented separately in
[V6_SERVER_RUNBOOK.md](V6_SERVER_RUNBOOK.md).

The progress cell (A5b) reports, without touching the running job: per-(agent, ratio, budget) checkpoint
completion from `_completed.txt`; per-leaf row counts vs. expected (100 × 50); turns/minute and projected
finish; live thread count and RSS; and the tail of the run log. Everything it needs is already written by
the resumable runner — it is a read-only view over the output tree.

---

## 4. Part I — the C0 calibration experiment

Runs first, standalone, and produces the numbers that configure Part II. Runs **single-threaded on the
quiesced dev machine** — this is the reference machine, and its numbers are what the paper converts to
seconds.

### 4.1 Why it cannot be shortcut

v4's aggregated tables ([E5_search_breadth_vs_cost.csv](tables_v4_full/E5_search_breadth_vs_cost.csv))
already contain a contaminated version of this measurement, and it shows two things:

- **Agents differ 2.4× in fmcall throughput at equal wall-clock** (500 ms level: EMCTS 3,265 fmcalls/turn,
  CrossoverCombo@2 7,822). Evals/turn spread 2.5×; fmcalls-per-eval ranges 117–148. The op mix genuinely
  varies by agent — this is the empirical justification for the whole exercise, and belongs in the paper.
- **Those throughput numbers are not usable as a calibration.** EMCTS did 3,265 fmcalls at 500 ms and
  3,420 at 1000 ms — +4.7% for double the time. Fitting the 1000→5000 ms points gives ~1 fmcall/ms against
  the ~27 advances/ms `ComputeCalibrate` measured single-threaded. The v4 runs were evidently heavily
  contended, so their delivered compute bears little relation to nominal thinking time.

### 4.2 Design

- **Budget type during calibration:** `STOP_TIME` — the question is "what does this agent do in T seconds".
- **Targets:** 500 / 1000 / 5000 ms, **each a separate run**.

  > **Do not checkpoint a 5 s run at 1 s to obtain the 1 s numbers.** The turn is split into 21 phases with
  > ceilings derived from the *total* budget (`timePerPhase = turnBudgetTime / depth`,
  > [ExperimentalEMCTSAgent.java:225](../src/players/ExperimentalEMCTS/ExperimentalEMCTSAgent.java#L225)).
  > A 5 s budget gives 238 ms/phase, a 1 s budget 48 ms/phase; at t=1 s the 5 s run is in phase 4 with 3
  > burns done, while a real 1 s run has finished all 21 phases and all 20 burns. Different trajectories,
  > different op mixes.

- **Sample:** ~24 portfolio scenarios stratified by source (12 single / 12 dual) and BF tertile, × 5
  repetitions × 12 configs. Sampling the *actual portfolio* matters: `copy()` cost scales with state size
  and available-action counts grow through a game, so a fresh-game calibration would not transfer.
- **Structure:** mirrors the isolation phase exactly — one search turn per (scenario, visit), same configs,
  same greedy init.
- **Hygiene:** discard JIT warmup turns; interleave configs so thermal drift cannot align with agent
  identity; record JVM version, flags, `-Xmx`, and CPU model as run provenance.
- **Do not assume steady throughput — measure it.** The 5 repetitions exist to report a coefficient of
  variation, which is the evidence that the reference machine was stable.

**Cost:** 12 configs × 24 scenarios × 5 reps = 1,440 turns per target; (0.5 + 1 + 5) s ≈ **2.6 hours**
single-threaded. Cheap for what it settles.

### 4.3 Recorded per turn

Target ms, realized ms, `Advances`, `Copies`, `Evals`, `InitUnits`, scenario id/source/BF, config, rep —
plus op counts at each of the 21 **phase boundaries** (phase-indexed checkpoints are the natural unit here,
not wall-clock seconds).

### 4.4 Outputs, and what each decides

| output | question it answers |
|---|---|
| Per-agent op counts per target, with CI | what each agent completes in T ms |
| **ms per compute-unit, by agent** | **verifies the per-config weights**: a unit is defined as fixed wall-clock, so this must be FLAT across agents. Spread means stale or missing profiles |
| ms per compute-unit, by scenario source and BF tertile | the H5 confound: does a fixed unit budget buy different real compute in single vs. dual? |
| Op mix by phase index | within-turn drift as the tree grows |
| Op mix by target time | does composition differ at 500 ms vs. 5000 ms? |
| CV across repetitions | validates the reference-machine stability assumption |
| **`InitUnits` by source and BF tertile** | resolves §5 |

**Decision rule.** Settled in §3A: the composite is kept and the *weights* are calibrated per
(configuration, level), so ms-per-unit being agent-invariant is now the pass condition rather than the
decision. If C0 still shows spread between agents, the cause is a stale or missing profile, not a design
question — re-run `ComputeCalibrateV6` on the run machine and re-derive the levels. If ms-per-unit
varies substantially *within* an agent across scenarios, the composite is still the right currency (it
absorbs mix drift) and the residual is reported as a limitation.

---

## 5. Open collision: greedy init vs. the budget

**This must be resolved before Part II is configured, and it is the main reason C0 runs first.**

Greedy initialization evaluates *every available action at every sequence position*. Cost per candidate is
one copy + one advance + one eval ≈ 1.24 compute units, over up to `NODE_SIZE - 1` = 19 positions. The v6
portfolio's branching factors are large — from
[E6_bf_dist_per_source.csv](tables_v4_full/E6_bf_dist_per_source.csv), BF mean **452 single / 290 dual**,
max 619.

A back-of-envelope estimate (assuming BF halves on average as units are consumed) puts greedy init at
roughly **5,000–6,000 units in single scenarios and 3,000–4,000 in dual**. Against the calibrated budgets
that is on the order of **40% (single) and 25% (dual) of the entire 13,400-unit "500 ms" level**. Under v5's
random init the same step cost ~19 advances — negligible. C0 measures the real figure via `InitUnits`;
treat these numbers as the reason to look, not as results.

Two problems follow if init is charged to the budget:

1. **At the small budget there is little left to search with.** Combined with the 20 mandatory bridge burns,
   the low level risks becoming initialization-plus-burns with almost no operator activity — and the
   operators are the object of study.
2. **The overcharge is correlated with the independent variable.** Init cost scales with BF, and BF differs
   systematically by source (452 vs. 290). Charging init to the budget therefore hands single scenarios
   systematically *less search budget* than dual — along the exact axis the single-vs-dual analysis measures.

**Recommended resolution: exclude the greedy initialization from the budget** and charge only post-init
search. The initial sequence is now identical across all 12 configs by construction, so its cost is a
common constant that carries no information about the operators; excluding it advantages no one and removes
a BF-correlated confound from the primary contrast. The paper then states the budget precisely as
*"search budget following a common greedy initialization"*, and reports `InitUnits` separately — which is
more informative than burying it.

Alternatives if C0 shows init cost is small: leave it inside the budget (simplest, no explanation needed),
or raise the budget levels so init is a modest share. **Decide from the C0 numbers.**

---

## 6. Part II — the v6 isolation run

Unchanged from v5 except where noted:

- **Portfolio:** 100 scenarios (50 single / 50 dual) — half of v5's, see §3C — rebuilt by
  `BuildV6Portfolio` with the same generation rules and the same action-composition columns v5 added
  for H5. Resumable, skipped if `_portfolio_complete.txt` exists; the completion marker records the
  counts it was built for, so a changed count forces a rebuild rather than reusing the wrong set.
- **Configs (12):** EMCTS, CrossoverBB, CrossoverExpansion@{2,5,10,20,50}, CrossoverCombo@{2,5,10,20,50}.
  EMCTS and CrossoverBB log zero crossover events by construction — they are the mutation baseline.
- **Visits:** 50 per scenario, shared `visitSeed(scenarioId, visitId)` across all 12 configs (the paired
  guarantee).
- **Budgets:** three levels, values and rule set by C0.
- **Init:** greedy, shared, invariance-gated.
- **Output:** `test_results/v6/<Agent>/[ratio_<r>/]<level>/results.csv` + `turn_telemetry.csv`, same layout
  and same resumability (per-(agent, ratio, budget) checkpoint plus per-(scenario, visit) recovery).

**Runtime:** ≈ half of v5's turn count. Actual wall-clock depends on delivered server throughput, which the
v4 evidence (§4.1) suggests can be far below single-thread rates under contention — the compute budget makes
the *work* fixed, not the elapsed time. Budget for a multi-hour job and rely on the resume path.

---

## 7. Analysis

`analysis/analyze_v6_full.py`, carrying over every v5 output unchanged so v5→v6 is a like-for-like
comparison: fig3/fig4 (best agent per scenario, ranking points), fig5/fig5b (trajectories), fig7 (branching
scatter), fig8 (% improvement bars), E1 (paired Δ with CI), E2 (best ratio per budget), E3 (single vs dual),
E6/E7 (BF distribution and tiers), E9 (inbreeding), E11–E15, the ANCOVA, and the v5 hypothesis battery
E17–E21 (operator credit, blend coherence, repair disruption, pool collapse, action composition).

**New for v6 — the budget-adherence section**, which is what makes the methodology reportable:

| table/figure | content |
|---|---|
| `B1_budget_adherence` | realized `ComputeUnits` vs. target, per agent per level: mean, SD, overshoot distribution, max |
| `B2_op_mix` | advances/copies/evals per turn per agent — and what an equal-fmcalls budget *would* have given each agent, quantifying the unfairness avoided |
| `B3_reference_conversion` | the C0 units→seconds table for the reference machine, with provenance |
| `B4_init_cost` | `InitUnits` by source and BF tertile, and its share of the budget |

---

## 8. Decisions still open

1. **Budget rule** — shared scalar vs. per-agent calibrated; decided by C0 §4.4.
2. **Init inside or outside the budget** — §5; recommendation is outside, decided by C0's `InitUnits`.
3. ~~**Budget levels** — the "500 ms" level may need raising.~~ **Resolved by measurement: the levels are
   now 40,000 / 80,000 / 400,000** (floor, 2×, 10×). It needed raising by considerably more than
   expected, and for a sharper reason than "init and burns dominate it".

   The crossover bridge-burn tournament is a 3-level bracket over the phase's top **eight** elites. Its
   level-1 round pairs them two at a time, so a pool of 2 or 3 yields a single child, trips an internal
   guard and returns the plain-EMCTS best-child promotion — silently, and without logging a crossover
   event. The bracket needs a pool of **≥ 4** to run at all. At 13,400 units it ran at only **46%** of
   the 20 burns, so over half of the "crossover bridge burning" condition was not running crossover
   bridge burning.

   `BudgetFloorProbe` (notebook §3d) measures this. The completion rate rises 46% → 63% → 74% → 84% →
   89% across 13.4k → 26.8k → 40k → 54k → 70k units and then **saturates near 0.89**: in the
   widest-branching scenarios a phase produces few children however large its slice, so about one burn
   in nine can never assemble a pool of four. 40,000 is the smallest level at which the phase between
   two burns evaluates **17 new children — twice the 8-slot pool**, so the pool is fully turned over
   between burns. See `V6_SERVER_RUNBOOK.md` §5d for the full table and reasoning.

   Note this supersedes the assumption that C0 alone would settle the levels: C0 answers "what does an
   agent do in T ms", which is orthogonal to "is the operator structurally able to run".
4. **n=50 power check** — re-run the v4 per-scenario-unit significance analysis at n=50 on C0 or v5 data
   before committing.
5. ~~**Reproducibility assertion** (deferred from v5) — a rerun-identity check at 1 vs. T threads.~~
   **Answered empirically; see [V6_SERVER_RUNBOOK.md](V6_SERVER_RUNBOOK.md) §3–§4.** The result is not
   the clean yes it was expected to be, and the distinction matters for how the property should be
   asserted:

   - **Agent behaviour and portfolio selection are reproducible.** The v6 and v5 builders produce an
     identical `portfolio.csv` at any thread count; a fixed-seed agent planning from either build's
     scenarios produces the same greedy root, sequence values, node counts and realized compute units.
     A hard-killed and resumed isolation phase reproduces an uninterrupted run's dataset exactly, once
     the three deliberately wall-clock columns (`Timestamp`, `PlanMillis`, `InitMillis`) are excluded.
   - **The scenario `.bin` files are NOT byte-reproducible, and cannot be made so without changing the
     engine.** `Tribe` holds enum-keyed `HashMap`s (`monuments`, `tribesMet`); enum `hashCode()` is the
     JVM identity hash, so those maps' *serialization order* varies between JVM runs and between thread
     counts. Contents are identical; only the order entries appear in the byte stream differs. This is a
     pre-existing property of v5's portfolios, not something v6 introduced.

   So a smoke-test assertion should compare the **manifest and a fixed-seed planning trace**, never a
   checksum of the state files. Making the files themselves byte-stable would mean replacing those maps
   with `EnumMap`/`LinkedHashMap` in the engine — a larger change, and one that would invalidate every
   existing portfolio cache.

---

## 9. Build order

1. C0 harness + `InitUnits`/spend logging → run C0 (~2.6 h) → read §4.4 outputs.
2. Resolve decisions 1–3 from the C0 data.
3. `BuildV6Portfolio`, `IsolationPhaseRunnerV6`, `RunExperimentV6`, `NewExperimentSmokeTestV6`.
4. `run_v6_jupyter.ipynb` (Part 0 + Part A; Part B is the tournament phase).
5. Run v6.
6. `analyze_v6_full.py`.
