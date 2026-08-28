# AB-EMCTS on the Dell PowerEdge R7615 — staged experimental design

*Written 2026-08-27 for a 64-core R7615 and a ~50 h window. Supersedes the design half of
`PLAYBOOK.md` (which remains the record of the invalidated run) and re-scopes
`ACTION_BIAS_V2_PLAN.md` (written for 6 laptop threads; its Stage A was executed and passed, and that
work is carried forward here). Companion: `VARIANT_GLOSSARY.md` decodes arm names;
`V6_SERVER_RUNBOOK.md` covers thread placement, throttling and restart safety.*

**Design priority, set by the user:** the isolation phase is the primary instrument — it produces more
data, more cheaply, and more reliably than games. Full games are not the headline; they are the
**yardstick** that converts an isolation-phase difference into a statement about playing strength.

---

## 1. The algorithm under test

**AB-EMCTS** = EMCTS plus a within-turn learned action-value table (**Qδ**) steering the two expensive
decisions the search makes on every iteration.

| | Basic EMCTS | AB-EMCTS |
|---|---|---|
| **Insertion** (mutation picks a replacement action) | uniform over the feasible set | with probability 1−ε, the feasible action with the highest shrunk Q̂; uniform otherwise |
| **Repair** (later actions broken by the mutation) | exhaustive `copy+advance+eval` over every candidate (BF ≈ 65) — **~69% of the whole budget**, ~90% of it discarded | rank by Q̂ and either evaluate only the top K, or take the argmax with **no forward-model call at all** |

**The table.** Per turn, reset at turn start. Credit is the **paired delta** `childValue − parentValue`
— an advantage estimate, immune to the value scale's drift across a turn, and native to EMCTS in a way
classical MAST is not. Removal credit on. No decay. Shrinkage toward the running global mean, m = 5.
Coarse and Mid keys are integer-keyed, so their bookkeeping charges nothing to the `ComputeMeter`
and costs nanoseconds. **Fine keys are the exception**: they key on `Action.toString()` — this
codebase's canonical action identity, the thing `ActionKeyCollisionProbe` exists to guarantee — so
a Fine arm pays a string build and a hash per candidate it ranks. That is charged to wall-clock
and not to the budget, which makes W7 the check that decides whether `Z0_Fine` is real.

**Free parameters:** ε; insertion key granularity; repair key granularity; repair mode
(FULL / SHORTLIST-K / ZERO-EVAL); update schedule (continuous vs per-burn snapshot).

Every parameter has a value that recovers plain EMCTS **bit-identically**. That is what makes the
Baseline arm and the experimental arms one implementation, and it is what the verify gate asserts.

---

## 2. What this design inherits

Carried forward from the executed Stage A of `ACTION_BIAS_V2_PLAN.md`, all of which passed on the dev
laptop and none of which is invalidated by moving machines:

| Item | Status |
|---|---|
| `analyze_stage0.py`, `analyze_stage1.py`, plan docs, `run_stage0.sh`, `run_stage1.sh` | recovered from `1d2b800^` |
| `ExpAgents.abParams` / `abStage1Params` / `requireProfiles`; **a missing compute profile is now fatal** | done, negative-tested |
| Verify gates: Baseline ≡ plain `ExperimentalEMCTSAgent` bit-identical; the tree branches (`maxBranching ≥ 2`); the elitist archive holds | done |
| `ActionBiasFloorProbe` — the mechanism actually engages: ≥ 80% of biased insertions rest on a key with ≥ 3 observations | **PASS at all three levels** (97.6 / 99.2 / 99.9%) |
| The finding that the calibrator must warm up **per budget level** and estimate from the **fastest** repetition, not the mean | **rebuilt on this branch** (the original was lost — see below). Verified: run-to-run ratio drift ≤ 2.4%, absolute ns ≤ 3.3%, against a 185% swing before the fix |

> **Work that was lost, and must be rewritten rather than merged.** The two sibling worktrees this
> design originally expected to merge from (`claude/emcts-experiment-design-be38d0`,
> `claude/emcts-performance-bug-experiment-2750c4`) were removed with their changes **never
> committed** — both branches point at the commit this one started from, their reflogs are empty and
> their indexes are gone. What survived is what was written down: their Stage A *results*, quoted in
> the table above, and the two analysis scripts, which were recovered independently from `1d2b800^`.
> Two pieces of code did not:
>
> - **The robust calibration estimator** — rewritten here, and validated against the numbers the
>   original reported (see §5 A2).
> - **The multi-child expansion arms** — still missing. **Stage B cannot run until they exist**, and
>   Stage B is what pins the substrate for D–G. This is now the critical path.
>
> The repository lives inside OneDrive, so its file-version history is the one remaining recovery
> route for the second. Check it before rewriting.

Two things it inherits that are **not** settled, and that this design must handle rather than assume:

- **The selection substrate.** `nodeToExpand()` now returns the incumbent — the highest-value node in
  the live tree, with a zero exploration term. That is **(1+1) plus selection**. The comma-vs-plus line
  found that the *pre-fix* leaf descent is **(1,k) comma selection** once expansion widens, and that it
  beats plus for every k ≥ 3, with the gap widening with budget. So the fixed policy is not
  self-evidently the best available EMCTS, and measuring AB-EMCTS against a weak baseline is the fastest
  way to produce a result a reviewer can dismiss. **Stage B settles this before any operator sweep, and
  whichever selection wins becomes the substrate *and the baseline* for every later stage.**
- **`Z0_*` calibration cells** do not exist yet. The zero-eval repair code now does (work item 8),
  so the cells can be added — and they must be, since a `Z0_*` arm runs several times more
  mutations per turn and is exactly the family the per-config weights exist to price.

---

## 3. What the R7615 changes

The machine is a **64-core** single-socket EPYC 9004 (e.g. 9534), 128 logical CPUs. `ServerProfile`
computes `reserve = max(1, min(4, round(64 × 0.03))) = 2`, so the **worker ceiling is 62**, and SMT
siblings are never given to workers (this workload is clone- and bandwidth-bound, the profile SMT helps
least). `ThreadCalibrationV6` then picks the smallest count within 3% of best *sustained* throughput,
per budget level. **Everything below assumes a measured `W ≈ 48`; replace it with the calibration's
answer before committing.**

Five consequences, in order of how much they change the design.

### 3.1 It is ~5× the 6-thread laptop, not ~10×

More cores, but slower cores. An EPYC part sustaining all-core load runs far below its boost clock,
while the laptop's six loaded threads sit near theirs. The planning model is:

> **server seconds/turn ≈ laptop seconds/turn × 1.6**, at `W = 48` concurrent turns.

| Level | Laptop, 1 thread (measured, Stage A) | Server model, 1 worker | Turns/hour at W = 48 |
|---|---|---|---|
| 40k | 2.57 s | 4.1 s | ~42,000 |
| 80k | 5.78 s | 9.2 s | ~18,800 |
| 400k | 31.79 s | 50.9 s | ~3,400 |

Net: **48 / 9.2 = 5.2 turns/s at 80k, against the laptop's 1.04** — a factor of 5.0.

The 1.6 is a model. Stage A's own history is the reason to distrust it: its 400k prediction was wrong by
1.66× **in the expensive direction**. Every cost in §6 should be re-derived from the first measured
`PlanMillis` of Stage B, and the plan is sized so that being wrong by 1.66× again still fits.

### 3.2 Small stages now under-fill the machine, so the cheap ones should grow

A stage's wall-clock is `max(total work / throughput, longest single task)`. Several stages have **fewer
work items than the machine has workers**. Stage C as previously designed was 8 logged games per budget
— 24 tasks on a 48-worker machine, leaving half the cores idle for its whole duration, and finishing no
sooner than one game takes.

The response is not to shrink the machine but to grow those stages until they fill it: **Stage C goes
from 8 games per budget to 48**, which costs nothing in wall-clock and takes the mutation-event count
from ~1.5 M to ~9 M. Anywhere a stage has fewer tasks than workers, extra replication is free.

### 3.3 Spend the capacity on scenario clusters, not on visits

Under a scenario-clustered bootstrap, the standard error is

```
SE ≈ sqrt( σ²_between / n_clusters  +  σ²_within / (n_clusters × visits) )
```

Visits of one scenario share a state and are not independent, so **only the first term is reducible by
adding scenarios, and adding visits attacks only the second**. Empirically, prior effects came with a CI
of about **±35 at 60 clusters × 10 visits**. Holding visits fixed:

| Clusters | CI (scaling as √) | Resolves effects ≥ |
|---|---|---|
| 60 | ±35 | ~50 |
| 200 | ±19 | ~27 |
| 400 (pooled) | ±13.5 | ~19 |

**This is what makes the headline falsification decidable.** The D2 principle — that granularity should
track whether a decision is corrected downstream — is falsified by `Z0_Fine_InsFine < Z0_Fine`, and the
prior plan pre-registered that effect as *possibly smaller than the ±50 it could resolve*, with
"inconclusive, not null" as the honest outcome. At 200 clusters that contrast becomes decidable. It is
the single best scientific justification for spending the server on the portfolio.

The portfolio build is essentially free here (60 scenarios took 4.4 min on 6 laptop workers; 400 on 48
server workers is the same few minutes), so this costs only the linear per-stage increase.

**Do not simply raise visits to match.** Stage B measures the actual between/within split and fixes the
visit count from it, rather than guessing — and a pilot already shows the guess was wrong in an
interesting direction (see Gate B): visits are *not* worthless, they are merely a worse buy per turn
than scenarios are.

### 3.4 The compute-unit currency was calibrated idle and will be spent under load

This is the one genuinely new risk the server introduces, and it is silent.

`ComputeCalibrateV6` documents that it **must run single-threaded on an idle machine** — it is a timing
measurement. The runs then happen at 48–62 workers. The compute-profile system's promise is *"equal unit
budgets buy equal wall-clock across configurations"*, and that promise rests on per-config cost
**ratios** measured in a regime the experiment never occupies.

The ratios have a specific reason to move. The arms differ several-fold in allocation rate — zero-eval
repair charges nothing for repair and therefore runs 3.4–4.9× the nodes per turn. On one thread, GC has
63 idle cores and is nearly free in wall-clock; at 48 threads it competes with workers for cores, L3 and
memory bandwidth. **An arm that allocates 4× more is penalised far more at 48 threads than at 1**, so
the idle-calibrated weights systematically mis-price exactly the family this line is trying to measure —
and nothing in any CSV would say so.

Stage A3′ tests it directly, and it is why the wall-clock fairness re-test (W7) can be promoted from a
check to a co-primary result.

### 3.5 What the R7615 removes

The previous plan's largest memory risk — 6 in-flight 400k turns with 4.9× trees on an 18 GB heap — is
gone. With hundreds of GB, `LoadGovernor` should essentially never fire. **If it does fire, that is a
signal to investigate, not a condition to accept**, and each leaf already prints how far concurrency
fell.

---

## 4. The instrument

- **Portfolio: 200 single-source + 200 dual-source = 400 scenarios**, built once in Stage A by
  `BuildV6Portfolio` and never rebuilt. Compare portfolios by **manifest**, never by `cmp` — the `.bin`
  snapshots are not byte-reproducible across JVM runs.
  - *Verify the nesting.* The builder takes the first N valid captures in attempt order, so a 200-per-
    stream build should contain v6's 50 and this line's previous 30 as prefixes **within each stream**.
    Confirm by manifest before trusting any cross-line comparison. Note that scenario **ids shift** (200
    singles then 200 duals, so duals start at 200, not 30) — and `visitSeed(scenarioId, visitId)` keys on
    the id, so pairing is only valid *within* this portfolio. Pin it once; never rebuild.
  - *Confirm it does not saturate.* 200 per stream is 2× anything built before. Check the
    branching-factor distribution matches the 60-scenario portfolio's (mean 256 / median 234 / p90 425);
    if the builder runs out of distinct source positions, fall back to 150 + 150 and say so.

- **Development / holdout split, frozen in Stage A** — implemented in `PortfolioSplit`. Every
  judgement call — ε\*, the champion arm, the repair granularity winner, any post-hoc arm — is made on
  **development** only. The **holdout** is read **once**, in Stage F, after those choices are written
  down.
  - This costs CI width on each half (±19 rather than ±13.5 pooled) and buys the one thing extra visits
    cannot: a headline number that is not inflated by the winner's curse. With 11 arms and a per-arm CI
    of ±19, the *maximum* of the arms is biased upward by a meaningful fraction of the effect being
    claimed. A holdout is the only clean fix.
  - **The partition rule is not a first-half cut.** Scenarios are stratified by
    `(Source, CapturedTick)`; within a stratum they are ordered by branching factor and dealt in
    **matched pairs**, alternating which side takes the lower member, with both the pair parity and the
    odd-member carry running *globally* across strata. Three things forced this, each measured on the
    60-scenario portfolio rather than assumed:
    - A contiguous cut is wrong because capture ticks **cycle** as the portfolio is built
      (`{10,12,…,30}` single, `{15,20,25}` dual). A boundary that does not align with the cycle hands
      the halves different game-phase distributions, and the holdout then tests generalisation across
      game phase rather than replication.
    - Dealing without a carry gives every odd stratum's leftover to development. Those biases
      accumulate in one direction: three singleton strata turned a 30/30 split into **33/27**.
    - Plain alternate dealing of a branching-sorted stratum hands one side the lower member of every
      pair, leaving the halves **22 branching points apart (266.0 vs 288.4)**. Matched-pair dealing
      cancels it to second order — measured **278.0 vs 276.3**.

    None of this biases either half's estimate; the analysis is paired within scenario. It makes the
    halves *comparable*, which is what a holdout that must replicate a development effect requires.
  - The id lists are frozen to `_split_dev.txt` / `_split_holdout.txt` beside the manifest, and those
    files are **authoritative** — readers never recompute the partition. Each carries a SHA-256 of the
    portfolio's *identity* (the ordered `(id, source, capturedTick)` tuples), and a run whose portfolio
    no longer matches aborts. Branching factor is deliberately outside the identity: it may legitimately
    be recomputed, whereas changed scenario identity means the frozen split no longer describes this
    portfolio.

  ```bash
  java -cp "out:lib/*" core.data.exp.PortfolioSplit test_results/stage1/_portfolio
  ```

- **Pairing:** `IsolationPhaseRunnerV6.visitSeed(scenarioId, visitId)`, shared across every arm, so all
  comparisons are paired on identical states.

- **Budget ladder: 40k trend / 80k main / 400k confirmation**, matching v6 exactly. Sharing v6's ladder
  shares one calibration matrix and keeps the action-bias and crossover lines on one axis.

- **Baseline at all three levels in every stage.** This is what makes budget-equivalence and
  wall-clock-fairness analysis possible. Do not drop it.

- **Verify gate on every runner**, aborting the run on failure: Baseline ≡ plain
  `ExperimentalEMCTSAgent` bit-for-bit; the tree branches; the elitist archive holds; every arm resolves
  a calibrated compute profile.

---

## 5. The stages

Seven stages, each with a pre-registered decision rule and a gate, ordered so the cheap ones can kill the
line before the expensive ones run.

### Stage A — Machine, currency and gates *(~5.3 h, nothing measured)*

**A0 — Machine gate (new).** Before anything:

```bash
java -cp "out:lib/*" core.data.exp.ServerProfile
```

Confirm 64 physical cores **detected** (not assumed), reserve 2, ceiling 62, the NUMA node count, and
the heap. Then `ThreadCalibrationV6` **per budget level** — tree size grows with the budget, so the best
thread count at 400k is not the one at 40k. Record `W₄₀ₖ`, `W₈₀ₖ`, `W₄₀₀ₖ` and the `ThrottlePct` /
`ClockDropPct` columns; a point that only reaches peak on borrowed boost headroom is not preferred.

Two operational preconditions, both learned the hard way:

- **The machine must be exclusively ours** for the whole run, and *idle* for A2. Stage A's noisiest
  calibration attempt on the laptop was the one that overlapped OneDrive syncing freshly-written
  portfolio files. On the server the equivalents are a lingering v6 job, a backup window, and
  `test_results/` living on NFS instead of local NVMe.
- `-XX:+UseG1GC -XX:MaxGCPauseMillis=500`; `-XX:+UseNUMA` if `ServerProfile` reports more than one node.
  Use `-XX:+AlwaysPreTouch` **on the calibration JVM only** (timing stability); not on a
  several-hundred-GB run JVM, where it merely commits everything up front.

**A1 — Portfolio.** Build 200 + 200, verify nesting and the branching distribution, freeze the split
(§4). ~0.3 h.

```bash
java -cp "out:lib/*" core.data.exp.BuildV6Portfolio test_results/stage1/_portfolio 48 200 200
java -cp "out:lib/*" core.data.exp.PortfolioSplit  test_results/stage1/_portfolio
```

The freeze prints the balance it achieved — counts per source and mean branching per half — which is
the number to read before trusting the split. Afterwards every stage names its half as the runner's
third argument, and the two halves land in separate output trees (`test_results/stage1/dev/…`):

```bash
java -Xmx<N>g -cp "out:lib/*" core.data.exp.Stage1Runner 48 test_results/stage1 dev
```

A `dev` or `holdout` run **will not build a portfolio** — it aborts with the two commands above
instead. Two split runs may be in flight at once, neither should race the other into a build, and a
split frozen against a portfolio that was still being written is not reproducible.

**A2 — Calibrate idle** *(~2.3 h)*. `ComputeCalibrateV6`, single-threaded, on the `ab` suite: the
reference `EMCTS` cell plus all 15 action-bias arms, × 3 levels = **48 cells**.

```bash
java -cp "out:lib/*" core.data.exp.ComputeCalibrateV6      test_results/stage1/_portfolio test_results/stage1/_calibration 3 auto 6 40000,80000,400000 --suite ab
```

Single-threaded means the server gives no speed-up here, and the estimate is now derived rather than
guessed: 16 cells × 3 reps × (8/4/2 turns) × (4.1/9.3/50.9 s) ≈ 2.3 h, of which the 400k level is 81
minutes. Hash `compute_weights.properties` into every leaf CSV; a run reproduced against different
weights is a different experiment.

**The estimator, rebuilt.** Two defects the original calibration hit, both reintroduced when its fix
was lost and both fixed again here:

1. **Warmup ran once, at the smallest level.** A 400k turn builds a far larger tree, so the first
   cells of every larger level were measured while the heap was still growing — full collections
   landing inside their early repetitions and not their later ones, which is exactly the spread the
   gate rejects. Warmup is now per level, with a collection between them.
2. **The estimator averaged the repetitions.** Every repetition runs byte-identical work, so all
   variation is machine interference — and interference can only make a repetition *slower*. The
   mean is therefore contaminated by whichever repetition was disturbed; the **fastest** is the
   least-contended observation. The gate changed with it: not "did every repetition agree" (which
   rejects a cell for the behaviour of its *worst*, a number carrying no information about cost) but
   **"do the two fastest agree within 10%"**. Full CV is still printed beside it as the disturbance
   diagnostic.

This is a better measurement, not a looser gate, and it is verifiable: two consecutive runs now
reproduce their weight **ratios within 2.4%** and their absolute ns within 3.3%, where before the fix
the same cell measured 15,821 ns in one run and 45,088 ns in the next. The per-config spread it
reports (`Z0_Fine` at ~1.21× the reference advance) sits in the same 17.5–22.2% band the original
implementation found, which is the evidence that the rewrite is faithful rather than merely working.

> **The `ab` suite needs more turns at the smallest level, and this is not a tuning preference.**
> `MIN_OPS` floors each counter at 20,000 before the calibrator will divide by it. Zero-eval repair
> makes roughly **four times fewer copies** than every other arm — by construction, since not copying
> candidates is the entire point of the mode. Measured: a `Z0_*` cell yields ~14,700 copies over four
> turns, so the v6 default would fail those four cells and, the gate being all-or-nothing, reject the
> whole calibration. The suite therefore defaults to `8,4,2` turns rather than `4,4,4`. The floor is
> left alone: it is a sanity check on sample size, and the honest way past it is more samples.

**A3′ — Calibrate under load (new, ~1.0 h, server-specific).** Re-measure the `a3` subset — the
reference config plus the three most allocation-divergent arms (`Baseline`, `Short5_e25`, `Z0_Fine`) × 3
levels = 12 cells — with the measured cell running on one worker while `W−1` others run the **same**
configuration. That is the operating condition: a leaf runs one arm across every worker, so loading
with a mixture would measure a machine state that never occurs.

```bash
java -cp "out:lib/*" core.data.exp.ComputeCalibrateV6      test_results/stage1/_portfolio test_results/stage1/_calibration 3 auto 6 40000,80000,400000      --suite a3 --underLoad 48
```

The loaded run **never clobbers the idle weights** — it writes `compute_weights_load48.properties`
alongside them and prints the per-profile comparison and the verdict. Whole-suite-under-load would cost
hours to answer a question four configurations settle.

> **Pre-registered rule.** If every ratio moves by less than the calibrator's own 10% gate, the idle
> weights are the operating currency and W7 stays a secondary check. If any ratio moves by more, the
> **loaded** ratios become the currency, they are re-measured across all cells, **and the wall-clock
> fairness re-test (W7) is promoted to co-primary** — every headline claim is then reported in both
> compute units and seconds, and an arm that wins in one and loses in the other is reported as
> unresolved, not as a win.

**A4 — Verify gates** at a calibrated budget (not a round number), plus the `ActionBiasFloorProbe`
re-run on this machine to confirm the mechanism engages at all three levels. ~0.3 h.

> **Gate A.** All verify assertions pass; every arm resolves a calibrated profile; the floor probe passes
> at 80k; the machine gate reports detected (not assumed) cores. Nothing downstream runs otherwise.

---

### Stage B — Pin the selection substrate, and measure the variance structure *(~0.4 h)*

**Question 1.** Is the bias effect a property of the operator, or of the tree it runs on?

The bias pays off precisely by having good mutations *revisited*, and comma and plus differ in exactly
that. Operator results measured on plus need not transfer — and if comma is simply stronger, then plus is
a weak baseline and every later claim is measured against the wrong control.

**Design.** 2 × 2 factorial, isolation phase, 80k, **development set (200 scenarios) × 8 visits**, paired.

| Arm | Selection | Insertion |
|---|---|---|
| `Plus_Off` | plus, k = 1 (the current fixed policy) | ε = 1.00 |
| `Plus_On` | plus, k = 1 | ε = 0.25, Mid |
| `Comma_Off` | comma, k = 5 | ε = 1.00 |
| `Comma_On` | comma, k = 5 | ε = 0.25, Mid |

6,400 turns ≈ 0.34 h. *Dependency: the multi-child expansion arms live in the
`emcts-performance-bug-experiment` worktree and must be merged first. If that line resolves the main
effect independently, take its answer and run only the two `_On` arms to get the interaction.*

**Pre-registered rules.**

- **Main effect** — `Comma_Off − Plus_Off`. The winner becomes the substrate **and the Baseline** for
  Stages C–G. Name it in every later claim.
- **Interaction** — `(Comma_On − Comma_Off) − (Plus_On − Plus_Off)`, paired per (scenario, visit),
  scenario-clustered bootstrap. CI includes 0 → the operator is **selection-portable**; say so once and
  move on. CI excludes 0 → the operator is **selection-dependent**; every headline claim names its
  substrate and these numbers may not be merged with results measured on the other one.

**Question 2 (this is also the variance probe).** Stage B's four arms × 200 scenarios × 8 visits is a
free, adequately-sized sample of the very quantity §3.3 needs. Decompose the paired-difference variance
into between-scenario and within-scenario components, and report the marginal CI gain per extra cluster
against per extra visit.

> **Gate B.** Fix the substrate, and **fix Stage D's visit count from the measured ICC** rather than from
> the default 8.

**A pilot already contradicts the expectation this gate was written around.** `analyze_stage1.py`'s
variance decomposition, run on 30 scenarios x 6 visits at 20k (2 arms, dev laptop), measures
**ICC 0.08-0.16, median 0.12** — i.e. roughly seven eighths of the paired difference's variance sits
*within* scenario, not between. The analytic standard error it produces agrees with the existing
scenario-clustered bootstrap to within 1-2%, so the two describe the same quantity. Three consequences,
and the third is the one that matters:

1. **The CI-vs-clusters table in §3.3 stands.** At a fixed visit count, `SE^2 = (sigma_b^2 +
   sigma_w^2/v) / n_c` — *both* terms scale with `1/n_c`, so SE falls as `1/sqrt(clusters)` whatever
   the ICC is.
2. **Visits are not wasted.** At 6 visits the SE sits **38-69% above** the floor that unlimited visits
   would reach on those scenarios; ~81 visits would be needed to come within 5% of it. The earlier
   phrasing — "extra visits of the same 60 states buy almost nothing" — is too strong.
3. **Scenarios are still the better buy per turn.** For a fixed turn budget `T = n_c * v`,
   `SE^2 = (v*sigma_b^2 + sigma_w^2) / T`, which rises with `v` whenever `sigma_b^2 > 0`. Measured:
   30 scenarios x 6 visits (180 turns) buys the precision of ~127 scenarios at one visit (127 turns) —
   the same answer for **40% more compute**.

So the ordering is unchanged (build the portfolio out, not up) but the *reason* is turn-efficiency,
not that visits do nothing. Treat the pilot as indicative only: 30 clusters, two arms, at a budget
below the ladder. Stage B measures it at 80k on 200 clusters, and Stage D's visit count comes from
that.

---

### Stage C — Is there still an exploitable signal? *(~0.7 h, observation only)*

**Question.** Within one turn's search, do some actions systematically produce better sequences —
reliably enough and early enough to steer the operator? Previously answered yes, **on a random walk**.
The tree is different now.

**Design.** `Stage0MutationLogAgent` — behaviourally inert, verify-gated. Full games on the v6 map set,
CAPITALS, 50-tick cap, against plain EMCTS at the same budget, on the substrate Stage B pinned. Budgets
{40k, 80k, 400k} × **48 games each** (up from 8 — see §3.2; this fills the machine and costs nothing).
~9 M mutation events.

**New instrumentation, and the reason this is worth re-running rather than assuming.** Log per mutation,
alongside the existing fields: `IncumbentDepth`, `IncumbentVisits`, `IncumbentAge` (mutations since the
incumbent last moved), `IncumbentMoved`.

**Analyses.** R1–R8 as before — split-half reliability; first→second-half predictive ρ; ρ vs training
fraction; top-tercile uplift; removed-vs-inserted agreement; decay ablation; position effects; delta vs
raw-value credit — plus:

- **R9 selection × payoff.** Does mutation payoff depend on the expansion target's age and depth?
- **R10 duplicate rate, re-measured** under the fixed `seqKey` and the fixed selection. Report as a fresh
  number; **do not** compare it to the old 30% → 57%.

**A pilot has already run both, and they are not null** (2 games × {20k, 40k}, 87k mutation events,
dev laptop — indicative only, but the direction is unambiguous):

| Target age | P(mutation improves) | Duplicate rate |
|---|---|---|
| 0 | 0.005 – 0.016 | 0.40 – 0.47 |
| 8–15 | 0.002 | 0.49 – 0.56 |
| 32–63 | 0.001 | 0.72 – 0.77 |
| 64+ | 0.000 | 0.86 – 0.89 |

The improvement rate falls by **roughly an order of magnitude** as the incumbent goes stale, and the
duplicate rate roughly doubles. Per-turn Spearman ρ(age, delta) is −0.035 [−0.070, −0.009] at 20k.
Three things follow:

- **Per-burn resets get their first real argument.** The knob was measured null before, on a search
  where the notion of a stale expansion target did not exist.
- **It is also a mechanism for comma-beating-plus.** A plus incumbent that sits still is, measurably,
  a target whose mutations stop paying and increasingly regenerate sequences already seen. Stage B
  should read this alongside its own result.
- **The expansion target is almost always the root or one step from it** (median depth 0, max 5). The
  tree is not deepening; it is one node being mutated hundreds of times.

Two reporting notes the pilot forced. `times_visited` is **not** logged: it is incremented once per
node and is 1 everywhere, so a column of it would carry no information — `ExpandedChildren` and
`ExpandedAge` are the meaningful analogues. And `P(improve)` and `P(incumbent moved)` are **one fact,
not two**, whenever the expanded node is the incumbent; they separate only under a policy that expands
something else.

**Pre-registered criterion (unchanged).** Exploitable signal iff R2 mean ρ ≥ 0.15 with bootstrap 95% CI
excluding zero, per (budget × granularity).

> **Gate C.** Fails at 80k for every granularity → **stop the line**; the premise is gone and D–G are
> unfundable. Passes at Coarse/Mid but not Fine → proceed; Fine failed representationally, not
> statistically.

---

### Stage D — Does biased insertion improve the search? *(~5.3 h, closed loop)*

The core result. **Development set only.** Runs on the existing 11 arms with no new agent code.

| Order | Level | Arms | Visits | Turns | Hours |
|---|---|---|---|---|---|
| 1 | 80k (main grid) | all 11 | 8 | 17,600 | 0.95 |
| 2 | 40k (trend) | all 11 | 8 | 17,600 | 0.42 |
| 3 | 400k (budget dependence) | all 11 | 6 | 13,200 | 3.89 |

The order is the schedule, not a listing: leaves are checkpointed independently, so an interrupted run
keeps whatever finished. Main grid first, then the cheap trend level, then the expensive
confirmation — a run that dies partway still holds the headline and its budget trend.

Running the **full arm set at 400k**, rather than the three the laptop plan could afford, is what turns
"is the effect real?" into "does the effect grow or shrink with search budget?" — i.e. whether the bias
is a small-budget crutch. That question is worth 3.9 h.

**Arms.** `Baseline` (ε = 1.00); `Bias_e{75,50,25,10}`; `Coarse_e25`; `NoRemoval_e25`; `PerBurn_e25`;
`Shortlist15` (the repair change alone); `Short15_e25`; `Short5_e25`.

*One-line change: `Stage1Runner.CORE` currently carries `Short15_e25`. Make it `Short5_e25` — K = 5 was
the champion, K = 15 was not.*

**Pre-registered contrasts.**

- **W1** each arm − Baseline, paired per (scenario, visit).
- **W2** ε dose-response: test monotonicity; report the best ε with its CI. Extend to ε ∈ {0.05, 0.02}
  **only if** the 1.00 → 0.10 curve is still monotone increasing. "No diversity collapse at ε = 0.10" is
  the most exposed prior result — it was measured without selection, and over-exploitation now has a
  second channel to act through.
- **W3** the four design contrasts — granularity (`Coarse_e25` − `Bias_e25`); removal credit
  (`Bias_e25` − `NoRemoval_e25`); update schedule (`Bias_e25` − `PerBurn_e25`); and composition,
  `(Short15_e25 − Shortlist15) − (Bias_e25 − Baseline)`, which asks whether bias and shortlisting are
  additive.
- **W4** efficiency and the diversity guard-rail (nodes evaluated, unique sequences, duplicate rate —
  **within-run only**).
- **W5** anytime curves per burn phase.
- **W6** budget equivalence: does an arm at 80k beat Baseline at 400k (5× budget)?
- **W7** wall-clock fairness: re-score each arm against Baseline interpolated at that arm's own
  `PlanMillis`. Co-primary if A3′ found the currency load-dependent.

> **Gate D.** The champion is the best arm on W1 whose CI excludes 0 **and** which survives W7. An arm
> that wins in compute units and loses in milliseconds is a bookkeeping artefact, not an algorithm —
> re-open the currency rather than carrying it into Stage E. **Write ε\* and the champion down before
> Stage F runs.**

---

### Stage E — Table-directed repair *(~2.6 h, closed loop; needs new code)*

**Question.** Repair is ~69% of Baseline's budget and ~90% of its evaluations are discarded. Can the same
Qδ table replace the exhaustive scan outright — and does the granularity principle hold?

**Code needed** — a small extension of the existing agent, not a new package:
`repairMode ∈ {FULL, SHORTLIST_K, ZERO_EVAL}` (FULL = the current default), and split the single
`coarseKeys` flag into **`insertKeys`** and **`repairKeys`** ∈ {COARSE, MID, FINE}, since the whole point
is that the two decisions want different granularities.

**Arms** (development set; ε\* = Stage D's winner; insertion Coarse unless stated):

| Arm | Insertion table | Repair |
|---|---|---|
| `Baseline` | ε = 1.00 | FULL greedy |
| `D_champion` | Stage D's winner, carried over unchanged as the cross-stage anchor | |
| `Z0_Coarse` | ε\*, Coarse | ZERO-EVAL, Coarse keys |
| `Z0_Mid` | ε\*, Coarse | ZERO-EVAL, Mid keys |
| `Z0_Fine` | ε\*, Coarse | ZERO-EVAL, Fine keys |
| `Z0_Fine_InsFine` | ε\*, **Fine** | ZERO-EVAL, Fine keys |

80k × 8 visits = 9,600 turns (0.51 h); 400k × 6 visits = 7,200 turns (2.12 h).

**Two pre-registered claims.**

- **E1 — replication.** `Z0_best − Baseline` > 0 with CI excluding 0, at **zero repair evaluations**.

  *Report two numbers, not one.* An earlier draft of this plan said repair's share of charged budget
  "must read 0.000 for the `Z0_*` family". That is wrong, and the implementation makes it measurable:
  repair still **advances** every action it keeps, because that is applying the sequence, not scoring
  a candidate — a cost every arm pays. What goes to zero is **candidate evaluation**. Both counters
  are now in the telemetry (`RepairCandidateEvals`, `RepairUnits`), and measured over 6 scenarios at
  30k they read:

  | Arm | Candidate evals in repair | Units charged in repair |
  |---|---|---|
  | `Baseline` | 99,404 | 145,896 |
  | `Short5_e25` | 27,286 | 92,111 |
  | `Z0_*` | **0** | ~71,400 |

  So the headline is that zero-eval repair chooses replacements for **nothing**, and that this takes
  repair from ~81% of the turn's charged budget to ~40% — not to zero. Quote it that way.
  `RepairCandidateEvals` reading 0 is a construction check, not a finding: `evaluateState` is the only
  thing in any repair path that charges an eval, so a non-zero value there means the mode is not
  actually engaged and the run is a bug.
- **E2 — the D2 falsification.** D2 says granularity should track whether a decision is *corrected
  downstream*: insertion is a proposal the sequence scores afterwards, so abstraction generalises and
  Coarse should win; zero-eval repair is a commitment nothing checks, so precision matters and Fine
  should win. Two predictions, and **both must hold**:
  1. repair granularity orders **Fine > Mid > Coarse**, monotone;
  2. `Z0_Fine_InsFine` < `Z0_Fine` — making the *insertion* table Fine must **hurt**.

  Prediction (2) is the arm that was designed and never run, and it is the one that matters. Without it,
  (1) alone is equally consistent with the boring alternative "Fine is simply better everywhere", and D2
  — the one claim in this line that is about decision structure rather than about the tree, and therefore
  the one most likely to port to other search algorithms — stays unfalsified rather than confirmed.
  **The 200-cluster development set exists to make this contrast decidable** (§3.3): it resolves effects
  ≥ ~27, where the previous design resolved ≥ ~50 and had to pre-register "inconclusive" as a likely
  outcome.

**Deliberately not run**, so the omissions are decisions rather than oversights: selective greedy repair
(promise/spread — null at 5%, harmful at 25%, rejected twice); scan-harvested Q1ply ranking
(mechanistically refuted at −67.6, **and none of its code exists in this repository** — roughly a day of
implementation for 0.3 h of compute; document the non-replication instead). Repair-delta credit moves to
the reserve list (§10) — it was dropped previously for needing ~4× the pairs, and that reason does not
survive the hardware change.

---

### Stage F — Holdout confirmation *(~2.2 h)*

**The headline numbers come from here, and this stage runs exactly once.**

Everything chosen on development — ε\*, the champion, the repair granularity winner — is frozen in
writing before this starts. The arms are the minimum set that carries every claim:

| Arm | Carries |
|---|---|
| `Baseline` | the control |
| `Bias_e*` (bias only, ε\*) | the insertion mechanism alone |
| `D_champion` | Stage D's headline |
| `Z0_best` | Stage E's headline, at zero repair evaluations |
| `Z0_Fine_InsFine` | the D2 falsification (E2·2) |

Holdout set (200 scenarios): 80k × 8 visits = 8,000 turns (0.43 h); 400k × 6 visits = 6,000 turns
(1.77 h).

> **Gate F.** A development-set effect that does not replicate on the holdout is **not a result**. Report
> both numbers side by side, always. If the champion's holdout CI includes 0, the honest headline is the
> mechanism's *direction* plus a negative confirmation — and that is a publishable outcome, not a failure
> to be worked around.

---

### Stage G — The yardstick: games that price an isolation difference *(~5.4 h, 3,900 games)*

**This stage is not the headline. It is the unit conversion.** Every number in this line — every figure
in the glossary's results table — is heuristic-relative search quality on isolated turns. The mapping
from "+4.7% sequence value" to "~72% win rate" is **borrowed from the crossover work, not measured
here**. This stage measures it, so that every isolation result in the thesis can be quoted in win-rate
terms with honest uncertainty.

**Why a spread of arms rather than a deep single matchup.** To estimate a *mapping* you want points
spanning the x-axis, not one point measured very precisely. Six arms whose isolation deltas are already
known, each played to moderate depth, gives twelve regression points across two budgets; the same compute
spent on one champion matchup gives one.

**Design.** Reuse the v6 tournament machinery verbatim: IMPERIUS/IMPERIUS2 mirror pair, a shared
(map, gameSeed, agentSeed) grid across matchups and budgets, 50-tick cap, identical deterministic cu
budget on both sides, orientation swap exact and asserted.

**One change: 75 maps × 4 games = 300 games per matchup**, up from 25 × 4 = 100. Maps 0–24 remain v6's
exact set, so v6 comparability holds — *verify this by manifest, as `FullGameMapSetV6` shares an attempt
counter across maps.* More maps beats more games per map for the same reason more scenarios beats more
visits: the map is the cluster.

**Matchups**, each vs plain EMCTS on the pinned substrate:

| # | Matchup | Level(s) | Purpose |
|---|---|---|---|
| 1 | `Baseline vs EMCTS` | 80k | **Control.** Same agent both sides: the win rate must be 50%. Validates seat/orientation balance and anchors the regression at (0, 50%). v6 dropped this arm; here it earns its place back. |
| 2–6 | `Bias_e75`, `Bias_e25`, `Shortlist15`, `D_champion`, `Z0_best` vs EMCTS | 40k **and** 80k | the five points spanning the isolation-delta range |
| 7 | `D_champion + Crossover vs Crossover` | 80k | composition — crossover was found useful in v6, and the question this work will be asked is whether the two gains are additive or overlapping |

≈ 3,900 games. **Size this stage from a measurement, not from this document.** Run a 4-game timing probe
first and read `PlanMillis`: full-game turns are far cheaper than isolation turns (early positions have
few legal actions), the model here applies a 0.35× discount, and the playbook's own bug table records a
runtime estimate that was wrong by 10× in exactly that direction.

**Analysis — the deliverable is a published mapping.**

- Per matchup: win rate with the **mirror-pair sign test as primary** (a mirror pair is one observation,
  not two — 150 pairs per matchup), plus W6_balance (win rate by orientation and by seat) leading the
  report, as in v6.
- Regress win rate on the arm's isolation-phase paired delta at the same budget, across the 11
  experimental points plus the control's structural (0, 50%).
- **Use an errors-in-variables fit** (Deming/orthogonal regression with the variance ratio set from the
  measured SEs on both axes, or an explicit measurement-error model). Ordinary least squares attenuates
  the slope toward zero when x is itself estimated — and here x carries a CI of roughly ±19. Report the
  slope with a CI, and R².
- **Report the champion's own win rate with its CI as well.** The regression answers "what is an
  isolation delta worth?"; the champion's matchup answers "is *this* agent stronger?". Both are wanted,
  and they can disagree.

> **Gate G.** If the regression's slope CI includes zero — i.e. isolation gains do not reliably predict
> playing strength — **that is a first-class finding and it must be reported as one**, not buried. It
> would mean the isolation phase measures something real about search quality that does not convert into
> games, which is a substantive claim about the methodology this entire line rests on. It also bounds the
> scope of every earlier number, and it must not be quietly upgraded into a playing-strength claim.
> Scope note: the 50-tick cap means this mapping is measured on early-game strength. Say so.

---

## 6. Cost

At the modelled `W = 48`. **Re-derive from Stage B's first measured `PlanMillis`.**

| Stage | Work | Hours |
|---|---|---|
| A — machine, currency, gates, portfolio | calibration only | 5.3 |
| B — selection substrate + variance probe | 6,400 turns | 0.4 |
| C — signal | 144 logged games | 0.7 |
| D — biased insertion, full ladder × 11 arms | 48,400 turns | 5.3 |
| E — table-directed repair | 16,800 turns | 2.6 |
| F — holdout confirmation | 14,000 turns | 2.2 |
| G — the yardstick | 3,900 games | 5.4 |
| **Total** | | **≈ 21.9 h** |

Against a ~50 h window that is a safety factor of 2.5×, which is the right size given that the last model
of this workload was wrong by 1.66× in the expensive direction. If it is wrong by that much again, the
plan still lands at ~34 h.

---

## 7. Analysis conventions

- Statistic computed **per pair**, then averaged with a **scenario-clustered bootstrap CI** — visits of
  one scenario share a state and are not independent.
- Effect size reported three ways: raw diff; **as a multiple of the search gain basic EMCTS achieves on
  its own** (`FinalSequenceValue − InitialSequenceValue`, the interpretable one); and as % of absolute
  value. Quote at least the first two together — and after Stage G, add the win-rate translation.
- **Development and holdout numbers always side by side.** Never quote one without the other.
- **Terminals.** With `LOSE_BOOST` fixed, ~5% of isolation turns detect a terminal worth ±2e9.
  (a) Paired differences only — unpaired means of `FinalSequenceValue` are meaningless. (b) Report the
  terminal stratum and its rate per arm separately. (c) Terminal-excluded sensitivity on every headline
  contrast. (d) Never compare value *levels* across the heuristic-fix boundary.
- **`UniqueSequences`.** Its meaning changed twice. Within-run diversity guard-rail only; never a
  headline, never across the fix boundary.
- **Wall-clock fairness re-test on every stage** — co-primary if A3′ found the currency load-dependent.
- **Provenance in every leaf CSV:** git SHA, portfolio manifest hash, `compute_weights.properties` hash,
  the split name, and the pinned selection policy.
- **Checkpoint a leaf only if every turn produced a row.** And: *any two arms producing identical output
  is a bug, not thin data* — the `Z0_Mid ≡ Z0_Fine` lesson. Abort and investigate.
- **Clear `_completed.txt`** before any re-run, or archive the whole `test_results/<stage>/` tree.
  Runners skip listed leaves without re-checking the code that produced them.

---

## 8. Engineering work list

**Exists and is usable as-is:** `Stage0MutationLogAgent`; `Stage1BiasedAgent` / `Stage1Params` with all
11 arms; `Stage0SignalRunner`; `Stage1Runner`; `BuildV6Portfolio`; `IsolationPhaseRunnerV6.visitSeed`;
`ServerProfile` (already written for this machine); `ThreadCalibrationV6`; `LoadGovernor`; `RunGuard`;
`ComputeCalibrateV6`; `FullGameRunnerV6`; `FullGameMapSetV6`; `analyze_v6_full_games.py`;
`SelectionFixCheck`; `ActionKeyCollisionProbe`; `ExpAgents.abParams` + `requireProfiles`;
`ActionBiasFloorProbe`; `analyze_stage0.py`; `analyze_stage1.py`.

**Never existed in this repository**, contrary to `PLAYBOOK.md`'s file index: `players/scanharvest/`,
`players/phasec/`, `ScanHarvestRunner`, `PhaseBRunner`, `PhaseCRunner` and their analyses. Anything
needed from Phase C is written fresh — which is why Stage E extends `Stage1Params` rather than porting.

**Must be written**, in dependency order:

| # | Work | Stage | Size |
|---|---|---|---|
| 1 | ~~Scenario-**split filter** on `Stage1Runner`~~ — **DONE.** New `core.data.exp.PortfolioSplit`; `Stage1Runner` takes a third argument (`all` \| `dev` \| `holdout`) | A | ~~blocking~~ |
| 2 | Portfolio nesting + branching-distribution verification against the existing manifests | A | small |
| 3 | ~~`ComputeCalibrateV6` cells for the 6 Stage-E arms; loaded-calibration mode for A3′~~ — **DONE.** `--suite {v6,ab,a3,all}` and `--underLoad N`; `ExpAgents.abProfileKey`/`requireProfiles`; every arm now carries a profile and an unpriced arm aborts the run | A | ~~medium~~ |
| 4 | Merge the multi-child expansion arms for the comma/plus contrast | B | medium |
| 5 | ~~Variance decomposition (between/within) in `analyze_stage1.py`~~ — **DONE.** `analyze_stage1.py` recovered from `1d2b800^`; adds `run_variance` (unbalanced one-way random effects, terminal-excluded sensitivity) and a design section in `SUMMARY.md` | B | ~~small~~ |
| 6 | ~~Incumbent logging in `Stage0MutationLogAgent` + R9/R10 in `analyze_stage0.py`~~ — **DONE.** Four columns added (`ExpandedDepth/Children/Age`, `IncumbentMoved`), behavioural-equivalence gate still passes; `analyze_stage0.py` recovered from `1d2b800^` with `run_selection_payoff` and `run_duplicates` | C | ~~small~~ |
| 7 | ~~`Stage1Runner.CORE` → `Short5_e25`; ladder → {40k, 80k, 400k}; visits → 8/8/6~~ — **DONE.** Also replaced the stale per-turn runtime model with one fitted to Stage A's measurements, behind `-Dstage1.machineFactor` | D | ~~one line each~~ |
| 8 | ~~`repairMode`, `insertKeys`, `repairKeys` + the ZERO-EVAL path~~ — **DONE.** `Stage1Params` gained `Granularity` and `RepairMode`; `coarseKeys` split into `insertKeys`/`repairKeys`; the agent keeps all three granularities from one credit stream; `Stage1Runner.stageE(epsStar, dChampion)` builds the six arms | E | ~~medium~~ |
| 9 | `FullGameMapSetV6.NUM_MAPS` 25 → 75, with a manifest check that maps 0–24 are unchanged | G | small |
| 10 | Stage-G `AgentSpec`s and the 7-matchup list; errors-in-variables regression + published mapping in `analyze_v6_full_games.py` | G | medium |

---

## 9. Risk register

| Risk | Signal that it is happening | Pre-registered response |
|---|---|---|
| Idle-calibrated weights mis-price the arms under load | A3′ ratios move > 10% | Loaded ratios become the currency; W7 promoted to co-primary (§3.4) |
| The pinned substrate is the weaker one | Stage B main effect favours comma | Re-pin to comma; it is the baseline as well as the substrate, so nothing else runs on plus |
| The bias effect vanishes on a corrected tree | Stage D W1 CIs span 0 | Stop at D. Report the negative with its mechanism: amplification needed the chain |
| The bias is selection-dependent | Stage B interaction CI excludes 0 | Name the substrate in every claim; do not merge with v6 numbers |
| Winner's curse inflates the headline | Holdout effect ≪ development effect | Already handled — the holdout *is* the headline (Stage F). Report both |
| Diversity collapse at low ε | W4 duplicate rate ≫ Baseline while W1 flattens | The ε extension is gated on monotonicity |
| D2 contrast still underpowered | E2(2) CI spans 0 at 200 clusters | Pool development + holdout for that contrast **only after** Stage F is read, and label it post-hoc |
| Portfolio builder saturates at 200/stream | Branching distribution drifts from the 60-scenario reference | Fall back to 150 + 150 and say so |
| Full games cost far more than modelled | The 4-game timing probe | Cut matchup 7, then the 40k level, then games/map from 4 to 2 |
| Isolation gains do not predict win rate | Stage G slope CI spans 0 | Report it as a finding (Gate G). Do not re-tune the tournament until it agrees |
| Two arms produce identical output | Byte-identical leaves | A bug, not thin data. Abort and investigate |
| `LoadGovernor` throttles on a 100s-of-GB heap | Any throttle event at all | Investigate — on this machine it should never fire |

---

## 10. Reserve list — what to do with the remaining ~30 h

In priority order, each independent of the others. **Nothing here is needed for any claim above.**

1. **Repair-delta credit** (`_RD` arms, ~+0.6 h). Measured ~+10 three times, CI always spanning 0;
   resolving it needs roughly 4× the pairs, which is now affordable. Run at 80k on both splits.
2. **Denser ε** at 80k — {0.35, 0.15, 0.05, 0.02, 0.0}, ~+0.5 h. Turns a 5-point dose-response into an
   identifiable optimum, and locates the collapse point if there is one.
3. **Pool development + holdout** for the headline contrasts, giving 400 clusters and ±13.5 — **only
   after Stage F has been read once**, and labelled post-hoc.
4. **A fourth budget level (1.6M cu) for 3 arms**, ~+2.5 h plus new calibration cells. Answers whether the
   advantage is a small-budget crutch more sharply than the 40k→400k trend can. Breaks ladder-sharing
   with v6, so report it separately.
5. **Deepen Stage G** to 600 games on the two headline matchups, ~+3 h, tightening the mapping's slope.

---

## 11. Reading order for whoever runs this

1. **Stage A end to end before anything else**, A0 and A3′ included. It is unglamorous, and it is the only
   thing standing between this plan and another invalidated run.
2. **Stage B next** — 25 minutes, and it decides both the substrate and the visit count every later stage
   uses.
3. **Stage C** — 40 minutes, and it can kill the line.
4. **Then D, E on development; F on the holdout, once; G last.**
5. Freeze ε\* and the champion **in writing** between D and F. The holdout is worth nothing if it is read
   twice.
