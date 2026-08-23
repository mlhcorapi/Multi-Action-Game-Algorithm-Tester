# Stage 0 — Is there a per-action value signal inside one EMCTS turn?

*A laptop-scale, observation-only experiment. Separate from the v6 thesis line; prerequisite for
the planned action-bias modifications (biased mutation, repair shortlisting, and later
combination/co-occurrence models).*

## Question

EMCTS mutation is uniform trial and error: the inserted action is drawn uniformly from the
feasible set. If, within a single turn's search, some actions systematically produce better
sequences than others, that regularity could steer mutation (and shortlist the greedy repair)
— but only if the per-action estimates are **reliable within the turn** and available **early
enough** to act on. Stage 0 measures exactly that, before any biased sampler is built.

Per mutation, the natural credit is the **paired delta** `childValue − parentValue`: the two
sequences differ by one edit (plus repair), so the delta is a per-action advantage estimate
and is largely immune to within-turn drift of the value scale. Repair contaminates it, so
zero-repair and inserted-survived subsets are analysed alongside all events.

## Design

| Element | Choice | Why |
|---|---|---|
| Agent | `Stage0MutationLogAgent` (extends `ExperimentalEMCTSAgent`, crossover off) | Logs every mutation event; **behaviourally identical** to plain EMCTS (same RNG stream). `Stage0SignalRunner verify` asserts equivalence on a full game before data collection. |
| Unit of analysis | One planning turn (episode). Statistics aggregate over turns; events within a turn are never treated as independent. | The proposed learning is per-turn (context-blindness across turns is expected in Tribes). |
| States | Full games on the fixed v6 tournament map set (`FullGameMapSetV6`), CAPITALS, 50-tick cap, vs plain EMCTS at the same budget | Realistic state distribution across game progression; same map universe as the rest of the thesis. |
| Budgets ("thinking times") | `{10k, 40k, 160k, 640k}` compute units of search (`STOP_COMPUTE`) | Deterministic under multithreading (thread-local ComputeMeter). Spans below the v6 floor (40k) to 16× above it — the hypothesis is that longer searches make the signal learnable/usable. Wall-clock per budget is *reported* from PlanMillis, not assumed. |
| Volume | 4 games/budget ≈ 150–200 planning turns per budget | Turns are the replicates; ~150 per cell gives tight CIs on a mean rho. |
| Action granularity | Logged at three levels per event: **Coarse** = ACTION type (~25 keys) · **Mid** = type + actor/tech qualifier (e.g. `MOVE:RIDER`, `BUILD:FARM`) · **Fine** = full action string (ids + coords) | The abstraction sweep. Logging fine + deriving coarse offline means one run answers all three. |

Also logged per event: mutation position, branching factor at the position, repair kept/replaced
counts, whether the inserted action survived repair, duplicate-sequence flag, compute units
elapsed, bridge-burns elapsed — enough to simulate credit/decay/update-schedule variants offline
without re-running anything.

## Analyses (`analyze_stage0.py`)

| # | Measures | Reading |
|---|---|---|
| R1 | Odd/even split-half rho of per-key mean delta, per turn | Signal **ceiling** (drift-free). ≈0 ⇒ nothing to learn at that granularity/budget; stop. |
| R2 | 1st-half → 2nd-half rho | **Exploitable** signal: what a sampler trained on early events could use. The headline number. |
| R3 | Predictive rho vs training fraction (20–80%) | How early it becomes usable — informs updating weights at bridge-burn boundaries. |
| R4 | Uplift: 2nd-half deltas of keys in the 1st-half top tercile vs average (z units) | Direct payoff estimate of ε-greedy-style biasing, without implementing it. |
| R5 | Insert-credit vs negated removed-credit correlation | If high, removal evidence doubles the sample count for free. |
| R6 | Decay ablation (half-life ∞ / 50% / 12.5% of training events) | The recency-vs-data trade, tested offline. |
| R7 | Mean delta by relative mutation position + its reliability | The near-free "which position to mutate" bias. |
| R8 | Delta credit vs raw child-value credit (MAST-style) as training signal | Validates the paired-delta choice. |

**Pre-registered decision criterion.** A (budget × granularity) cell has *exploitable signal* iff
the R2 mean rho ≥ 0.15 with a bootstrap 95% CI excluding 0. Proceed to Stage 1 (ε-greedy biased
mutation + repair shortlist) only for cells that pass; the granularity and minimum budget used in
Stage 1 are whatever passes here. If only R1 passes but R2 fails, the signal exists but drifts
too fast — that redirects Stage 1 toward per-phase re-estimation rather than cumulative Q̂.

## Costs (laptop: i9 / 32 GB; leave headroom for Chrome)

Defaults use 4 worker threads (one game per thread; results are thread-count-independent).
Rough serial per-game cost ≈ `ticks × 2 agents × cu × unit-cost`; expect the default grid
(4 budgets × 4 games, emcts opponent) around **2·10²–10³ s per 640k game** and ~1 h wall on 4
threads overall. The exact per-budget PlanMillis lands in `tables/budget_wallclock.csv` — that
table is the "how long do these budgets last" report. `-Xmx12g` default; the LoadGovernor lowers
concurrency if the heap gets tight (640k games hold large trees).

## Running

```
./run_stage0.sh build      # compile
./run_stage0.sh verify     # instrumented == plain equivalence gate (must PASS)
./run_stage0.sh smoke      # 1-minute end-to-end pipeline check + analysis on toy data
./run_stage0.sh run        # the collection (resumable; Ctrl-C safe)
./run_stage0.sh analyze    # tables + figures + SUMMARY.md under analysis/stage0_results
```

Output: `test_results/stage0/mutlog_<cu>cu_g<k>.csv.gz` (events), `turns_<cu>cu_g<k>.csv`
(per-turn spend/wall-clock), `games_summary.csv`, `_meta.txt` (ComputeMeter weights provenance).

## Threats to validity

- **Repair contamination** — deltas measure mutation+repair. Mitigated by the zero-repair and
  survived-only strata (R-analyses run on each); if conclusions differ across strata, the
  zero-repair stratum is authoritative for the *mutation* question.
- **Heuristic ceiling** — everything here is value-function-relative by design; Stage 0 makes no
  playing-strength claims.
- **Duplicate children** — flagged, kept (their deltas are real observations of the operator).
- **Opponent choice** — emcts-vs-emcts states; passive-opponent states would be easier. If Stage 1
  effects are opponent-sensitive, that is a Stage 1 question, not a Stage 0 one.
