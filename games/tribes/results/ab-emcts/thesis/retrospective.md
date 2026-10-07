# AB-EMCTS — experiment retrospective

*Internal engineering retrospective on the action-bias experimental line, Stages A–F, run
2026-08-28 to 2026-09-02 on a 64-core EPYC 9534 (Dell PowerEdge R7615). Written 2026-09-09.*

*Companion documents: `AB_EMCTS_R7615_PLAN.md` (the design and every gate resolution in full),
`AB_EMCTS_RUNBOOK.md` (how to execute it), `run_ab_emcts.ipynb` (what actually runs it).*

---

## 1. Summary

The line asked whether a within-turn learned action-value table (Qδ) improves EMCTS's search. The
answer is **yes, but not through the mechanism it was designed around**, and the size of the effect
is best expressed as a compute multiplier rather than as a heuristic delta.

**The headline.** On 200 held-out scenarios never used to choose anything, zero-evaluation repair
directed by a *coarse-grained* Qδ table (`Comma_Z0_Coarse`) beats the same search with exhaustive
greedy repair (`Comma_Off`) by **+244.3 [+208.4, +281.7]** heuristic points at 80,000 compute units,
and **+297.4 [+252.5, +342.4]** at 400,000. It wins in wall-clock too (**+223.3**), and it performs
**zero** repair candidate evaluations by construction.

**The attribution.** Of that, **+211.7 [+175.4, +249.0]** is the table's own contribution — measured
against `Comma_Z0_Random`, the identical arm with the ranking replaced by noise. The remainder is a
throughput effect that needs no table at all. That control is the difference between a thesis result
and a claim that the method is merely cheaper.

**The size.** The modification is worth **≈5.4× the compute budget** [4.3×, 6.5×], or **≈3.3× the
wall-clock**. See §3, which is the part of this report most likely to be reused.

**What was refuted.** Four pre-registered expectations failed, all of them informative: the insertion
bias (the mechanism the work is named after), the D2 granularity principle, plus-selection as the
substrate, and Q-ranking as a way to prune a shortlist that still gets evaluated.

Stage G (full games) is **not being run**. The isolation results stand on their own, and §3 exists
because of that decision — it is the substitute for a win-rate translation.

---

## 2. What was measured, and how

**The instrument.** The isolation phase replans a single turn from a captured mid-game position
under a fixed compute budget, and scores the best action sequence the search produced. It is far
cheaper and far less noisy than playing games, which is why the line is built on it.

| | |
|---|---|
| Portfolio | 400 scenarios (200 single-source + 200 dual-source), built once and pinned |
| Split | 200 development / 200 holdout, frozen before any arm ran, holdout read exactly once |
| Budgets | 40k / 80k / 400k compute units, 8 / 8 / 6 visits |
| Statistic | Paired difference per (scenario, visit); mean with a scenario-clustered bootstrap CI |
| Currency | Compute units priced **per arm, per budget level** — one unit = one advance in the reference cell |
| Co-primary | W7 wall-clock fairness: every arm re-scored against the baseline interpolated at the arm's own `PlanMillis` |
| Total | ≈93,600 isolation turns plus 144 logged games; ≈13 h of measured runs and ≈6 h of calibration |

**The gates.** Each stage ended in a pre-registered decision rule written before the data existed.
Four of the six fired against the design's own expectations, which is the main reason to trust the
two that did not.

---

## 3. How big is the effect, without playing games?

This section answers the question the line was ultimately built to answer, and it is the
methodological contribution most likely to transfer: **a heuristic-point delta is uninterpretable on
its own, but it can be converted into a compute-budget multiplier that anyone can read.**

### 3.1 Equivalent budget multiplier — the primary framing

The baseline's own value-versus-budget curve is the natural ruler. Take the search gain
(`FinalValue − InitialValue`, what the search adds over the greedy starting sequence), measure it for
the baseline at two budgets, interpolate log-linearly, and ask what budget the baseline would need to
reach the modified agent's score at 80k.

| | Search gain (holdout, terminal-excluded) |
|---|---|
| `Comma_Off` @ 80k | 259.5 |
| `Comma_Off` @ 400k | 487.7 — *5× the budget buys +228.2* |
| **`Comma_Z0_Coarse` @ 80k** | **497.4 — the modification buys +237.9** |

The modification is worth **1.04 × "one 5× budget step"**. Solving for the crossing point:

> ### **Equivalent budget multiplier: 5.4× [4.3×, 6.5×]**
> *(scenario-clustered bootstrap, 198 holdout scenarios; development gives 6.4× [5.2×, 8.1×])*

**This is a lower bound.** The interpolation is log-linear, and search gain is concave in budget in
practice. If the curve flattens beyond 400k — which is what diminishing returns means — the baseline
would need *more* than 5.4×, not less.

### 3.2 The same question in seconds

Compute units are the fair currency for comparing algorithms, but wall-clock is what a user
experiences, and the two disagree here because `Z0_Coarse` spends 16% more real time per unit of
budget, while the baseline's time grows sub-linearly with units (80k → 400k is 5× the units but only
3.5× the seconds).

| | |
|---|---|
| Baseline time needed to match | 3.8× its own 80k wall-clock |
| Extra time `Z0_Coarse` itself spends | 1.16× |
| **Net time-equivalent speedup** | **≈3.3×** |

**So the honest answer to "is this like giving EMCTS 4× the thinking time?" is: yes, near enough.**
It is ≈5× in the currency the experiment controls and ≈3.3× on the clock. A 4× intuition sits
between the two and is defensible either way, provided the axis is stated.

### 3.3 Against plain, unmodified EMCTS

Against the algorithm as it existed before this work (`Baseline`, plus-selection, no table), the
same calculation returns **≈48× [28×, 88×]**. That number should be quoted with care and probably
not as a headline: it is extrapolation far past the measured ladder, and most of it reflects how
badly plus-selection *scales* rather than how good the new agent is — 5× the budget buys plain EMCTS
only +119.9, against comma's +228.2. The defensible phrasing is that plain EMCTS's budget response is
so flat that the modified agent is **off the measurable scale**, and that fixing selection is a
separate contribution from the table.

### 3.4 Three further measures, all free from existing data

**Search-gain multiple.** At equal budget, `Z0_Coarse` extracts **1.93×** the value the baseline's
search extracts (500.3 vs 258.6 at 80k; 1.60× at 400k). The simplest sentence in the report:
*the search does nearly twice as much work with the same budget.*

**Oracle regret.** Of 1,584 holdout turns where all eight arms produced a sequence:

| Arm | Best of field | Mean regret |
|---|---|---|
| **`Comma_Z0_Coarse`** | **38.3%** | **171.5** |
| `Comma_Short15_Coarse` | 31.2% | 218.8 |
| `Comma_Z0_Mid` | 29.0% | 248.0 |
| `Comma_Short15` | 22.7% | 302.0 |
| `Comma_Rand15` | 21.7% | 282.8 |
| `Comma_Z0_Fine` | 17.6% | 378.8 |
| `Comma_Off` | 15.3% | 413.1 |
| `Comma_Z0_Random` | 14.2% | 383.1 |

This is a different lens and it agrees: the champion finds the field's best sequence 2.5× as often as
the baseline, at 2.4× lower regret. Note `Z0_Random` scoring *below* the baseline — independent
confirmation that the table, not the zero-eval mechanism, is what pays.

**Effect size.** Cohen's *d* = **0.62** on the paired per-turn difference (`Short15_Coarse` 0.54,
`Rand15` 0.42, `Z0_Random` 0.10). Standardised effect sizes travel across studies in a way that
heuristic points and win rates do not.

### 3.5 What would make this a measurement instead of an extrapolation

The ladder stops at 400k, and the crossing point sits just past it. **Running `Comma_Off` alone at
~1.6M and ~3.2M cu would bracket the crossing point directly** — roughly 2 h of compute plus a
calibration cell per new level. It converts the central claim of §3.1 from an extrapolation into a
measurement, and it is the single highest-value follow-up in this document.

---

## 4. Results

### 4.1 The ladder — the line's central finding

Every arm below runs comma k=5 selection with the insertion bias **off**. They differ only in how
the repair step chooses a replacement action, and they are ordered by how much of that decision the
learned table owns.

| Arm | Repair decision | Holdout, 80k | Development |
|---|---|---|---|
| `Comma_Off` | evaluate every candidate | 0 (baseline) | 0 |
| `Comma_Rand15` | evaluate 15 **arbitrary** candidates | **+133.8** | +139.8 |
| `Comma_Short15_Coarse` | table picks *which 15* are evaluated | **+199.9** | +206.5 |
| **`Comma_Z0_Coarse`** | **table picks the replacement outright, no evaluation** | **+244.3** | +268.1 |

> **The table pays in proportion to how much of the decision it owns, and only at coarse
> granularity.** Pre-filter candidates that get evaluated anyway and the heuristic evaluation
> dominates the learned prior; commit with no evaluation at all and the prior is all there is.

That single sentence also covers the insertion side, where the sequence score always overrules the
table: it owns least there, and pays least.

### 4.2 Development versus holdout

| Claim (80k, vs `Comma_Off`) | Development | **Holdout** |
|---|---|---|
| `Comma_Z0_Coarse` — the headline | +268.1 | **+244.3 [+208.4, +281.7]** |
| `− Comma_Z0_Random` — the table's own contribution | +244.2 | **+211.7 [+175.4, +249.0]** |
| `Comma_Rand15` — cheap repair, no table | +139.8 | **+133.8 [+107.2, +161.5]** |
| `Comma_Short15_Coarse` — + coarse ranking | +206.5 | **+199.9 [+167.0, +234.7]** |
| `Short15_Coarse − Rand15` — ranking pays | +66.6 | **+66.1 [+43.7, +89.9]** |
| `Short15 − Rand15` — MID ranking does not | −20.9 (ns) | **−21.2 (ns)** |

Every development effect landed inside its own interval on unseen scenarios. The headline also
survives terminal exclusion (+241.7), holds on both scenario sources (+305.2 single, +183.4 dual),
and passes W7 in wall-clock (+223.3 [+186.6, +261.1], 14% of pairs clamped — a genuine test).
`RepairCandidateEvals` reads exactly **0.0** for all four zero-eval arms, confirming the construction.

**One claim did not replicate, and it sharpened the conclusion.** `Z0_Fine − Z0_Random` was +23.4 and
significant on development; on the holdout it is **+3.8 [−13.3, +20.7], ns**, and ns in wall-clock
too. At Fine granularity the table is indistinguishable from no table. The final ordering is
**Coarse ≫ Mid ≫ Fine ≈ nothing**.

### 4.3 Mechanism

`Z0_Coarse` reaches **1,435 nodes and 1,293 unique sequences per turn** against `Comma_Off`'s 457 and
408 — **3.1× the search** — at an essentially unchanged duplicate share (0.077 vs 0.064). Zero-eval
repair returns the entire repair budget (~69% of a turn) to the search, and the coarse table spends
it on sequences the search has not already seen.

The contrast with the insertion bias is the whole story of the line: biased-insertion arms also
raised throughput (1.47×) but pushed the duplicate share from 0.063 to 0.23–0.34. **Shortlisted and
zero-eval repair convert saved evaluations into new sequences; the insertion bias converted them into
duplicates.**

---

## 5. What the experiment overturned

Four pre-registered expectations failed. Each was written down before the data existed, which is what
makes them findings rather than embarrassments.

**1. The insertion bias — the mechanism the work is named after — does not work.** The ε
dose-response is **not monotone** (peak ≈0.50; ε=0.10 is actively *harmful* at 400k, −65.0), so the
pre-registered extension to ε ∈ {0.05, 0.02} was cancelled by its own rule. Bias and shortlisting are
strongly **anti-additive** (−19.3 / −31.5 / −130.3 across the ladder). The champion runs with the
bias switched off entirely. The table is still *learned* — from unbiased sampling, which is arguably
better training data — it is simply not used to choose insertions.

**2. D2 is refuted.** The principle predicted that granularity should track whether a decision is
corrected downstream: coarse for insertion (a proposal the sequence score re-scores), fine for
zero-eval repair (a commitment nothing checks). Insertion ordered Coarse > Mid > Fine as predicted
(+45.3 then +57.2). Repair was predicted to order Fine > Mid > Coarse and ordered
**Coarse > Mid > Fine as well** — `Z0_Fine − Z0_Mid = −121.7` and `Z0_Mid − Z0_Coarse = −99.1`,
monotone at both budgets and in the exact reverse of the prediction. Both decisions want coarse.

The replacement is simpler, mechanistic, and it made a prediction that survived the holdout:
**statistical support, not decision structure.** Coarse keys accumulate enough observations to be
estimated; fine keys stay sparse and tie at the prior, so a fine table degenerates toward random
tie-breaking. The evidence is the ladder itself — `Z0_Fine` (+36.4) sits nearer `Z0_Random` (+32.6)
than `Z0_Coarse` (+244.3), and the Fine-vs-Random gap vanished out of sample exactly as the account
predicts. `ActionBiasFloorProbe` already measures the quantity this rests on.

**3. Plus-selection was the wrong substrate.** Comma k=5 beat it by +22.3 [+9.8, +34.8] with the bias
off, uniformly across scenario sources. Stage C explained why: P(a mutation improves) decays ~10–15×
as the expansion target ages, and under plus selection **65% of all mutation events at 80k (91% at
400k) hit targets aged 64+**, where improvement probability rounds to zero and 88% of children are
duplicates. Plus-selection spends nearly its whole budget grinding a dead incumbent. Everything from
Stage D onward was re-based on comma, and it is also the baseline.

**4. Q-ranking for a shortlist looked worthless, and was not.** Stage E measured
`Short15 − Rand15 = −20.9` and concluded the ranking added nothing. But that arm inherited **MID**
keys, and the same stage had just shown Coarse ≫ Mid for exactly that decision. Re-tested at Coarse,
the ranking is worth **+66.1** on the holdout. A conclusion was nearly published from a
single-granularity test of a mechanism that had just been shown to be granularity-sensitive.

---

## 6. What worked

**The development/holdout split.** Eleven arms, a champion chosen on the maximum, and a
winner's-curse correction that cost CI width and bought a headline nobody has to caveat. Every effect
replicated. The split was frozen before any arm ran, with a SHA over the ordered scenario tuples so
the freeze is auditable rather than promised.

**The attribution controls — the single highest-value design decision.** `Comma_Rand15` and
`Comma_Z0_Random` are the same arms with the ranking replaced by noise, holding the candidate list,
the shortlist size, the evaluation of survivors, and even the *number of RNG draws* identical. Stage D
had already produced a champion; without these arms the line would have reported a +118.9 improvement
whose cause was unknown, and the honest reading — that most of it was a cheap-repair effect — would
never have surfaced. They cost about 90 minutes of compute.

**W7 as a co-primary check.** Promoted after Gate A3′ found the idle-calibrated currency subsidising
the zero-eval family by ~24% under load. Every headline is quoted in both units and seconds, and the
two agree everywhere it matters. It also caught a per-arm mis-pricing after the fact (§A.5).

**Per-arm, per-budget compute pricing.** The old single global weight set was mis-allocating up to
73% between arms at 400k. Without per-profile weights the entire comparison would have been between
agents given different amounts of real compute.

**The gate discipline.** Writing the decision rule before the data existed is what let four
expectations fail cleanly instead of being argued away. Two rules explicitly fired and re-scoped the
remaining work (comma re-pin; W7 promotion), and one cancelled a planned extension (the ε grid).

**The clone → run → bundle → analyse loop.** Every stage ends by zipping a pre-specified set of
results. Analysis happened off the server against a fixed artefact, and every gate resolution was
committed to the repository as it was decided, so the reasoning is recoverable rather than
remembered.

---

## 7. What did not work (design)

*Process and tooling failures are in Appendix A.*

**The ε grid was centred on a value the data refuted.** Arms clustered at ε = 0.25 with 0.75/0.50/0.10
as satellites. The measured optimum for bias-alone is ≈0.50, and the champion sits at ε = 1.00 —
outside the grid entirely, present only because `Shortlist15` happened to be defined with the bias
off. Had that arm not existed for unrelated reasons, the line would have concluded "the bias helps a
little" and stopped. **A parameter sweep should always include the parameter's null.**

**Granularity was tied across two decisions.** `biasArm` set `repairKeys = insertKeys`, so every Stage
D arm tested one granularity for both decisions. That is why `Short15` was tested at MID and needed a
follow-up arm to correct (§5.4). The two were only separated for Stage E, one stage too late.

**The champion was selected before the control that would attribute it existed.** Gate D named
`Comma_Short15` on 48,000 turns; Stage E then showed that a random shortlist beats it. The controls
should have been in Stage D's grid, not added afterwards — they were cheap, and the ordering meant a
champion was declared on an unattributed effect.

**The budget ladder tops out where the answer lives.** The equivalence point sits just beyond 400k, so
the central claim of §3 is an extrapolation. A fourth level was on the reserve list from the start and
would have cost ~2 h.

**Stage C's power was spent on a foregone conclusion.** 144 logged games returned ρ = 0.55–0.80
against a 0.15 bar — signal in all nine cells, never remotely in doubt. A third of those games would
have settled it, and the remainder would have been better spent on the fourth budget level.

---

## 8. Threats to validity

- **One game, one heuristic, one opponent.** Everything is Tribes with the fixed heuristic; Stage C
  used CAPITALS only. Nothing here establishes that coarse-key zero-eval repair transfers.
- **Isolation-phase value is not playing strength.** The line measures the value of the best sequence
  found in one turn. Stage G was dropped, so the mapping from heuristic points to win rate remains
  **borrowed from the crossover work and unmeasured**. §3 is the substitute, and it is an
  *efficiency* claim, not a strength claim.
- **The 5.4× is extrapolated** past the measured ladder (§3.5), though in the conservative direction.
- **Early game only.** Scenarios are captured mid-game and Stage C capped at 50 ticks.
- **Terminal turns** (~1–2%) are worth ±2e9 and are excluded from every headline; all results are
  reported terminal-excluded as a sensitivity.
- **One machine, one container.** The currency is calibrated on a single host, and the line
  demonstrated twice that this is fragile (§A.4, §A.5).
- **One stale cell.** `Comma_Short15_Coarse` @ 400k on *development* was run under a superseded price
  and reads +196.7 against the holdout's +252.4; its 80k numbers and every other arm are on matching
  prices. Re-running the development analysis after the E3 fix-up resolves it.

---

## 9. Future research

**Ranked by value per hour of compute.**

**1. Fourth budget level (~2 h).** Run `Comma_Off` at ~1.6M and ~3.2M cu to measure the equivalence
point instead of extrapolating it. Converts §3.1 from a modelled claim into a measured one. This is
the obvious next run.

**2. Duplicate avoidance before evaluation (~half a day of work, ~1 h compute).** 75–84% of mutation
children at 80k/400k already exist in the tree. A `seqKey` check before evaluation would reclaim a
large share of the eval budget outright. It changes the operation mix, so it needs its own
calibration cells — but the ceiling is large and the mechanism is already instrumented.

**3. Freshness policies beyond comma (~1 day of work).** Stage C's R9 is the strongest untapped
signal in the line: the payoff frontier is expansion targets aged ≤ 7, and even comma k=5 lets
incumbents age within a generation. Age-capped targeting, per-burn resets, or restart-style
re-anchoring all attack the 65–91% of events currently spent on targets where improvement probability
is ≈0.

**4. Why coarse wins — measure the support curve directly.** The statistical-support account predicts
that a granularity's value tracks observations-per-key. `ActionBiasFloorProbe` already measures
distinct keys and median observations per key. Plotting arm performance against measured support,
across granularities and budgets, would turn a plausible mechanism into a quantitative law — and it
would predict *a priori* which granularity to use in a new domain.

**5. The single/dual-source asymmetry.** The bias mechanism was consistently stronger on
single-source scenarios (+305.2 vs +183.4 for the champion; the insertion bias was +53.4 vs −10.5).
Nothing in the design explains this, and it is measured for free in every existing table.

**6. Table persistence across turns.** The Qδ table is discarded at the end of every turn. Given that
the table's value scales with observations per key, carrying it across turns of the same game — or
priming it from a previous game — is the most direct way to test whether support is really the
binding constraint.

**7. Transfer.** Coarse-key, zero-evaluation repair is not specific to EMCTS: any search that repairs
or mutates a sequence and pays a forward-model call to score each candidate could adopt it. RHEA is
the nearest neighbour in this codebase.

**8. Games, if they return.** Stage G is dropped, not disproved. The design is written
(`AB_EMCTS_R7615_PLAN.md` §Stage G): 7 matchups spanning the known isolation-delta range with an
errors-in-variables regression, ≈5.4 h. If an external-validity claim is ever needed, that is the
shape of it — and §3's measures are what stand in until then.

---

## Appendix A — process and tooling failures

Recorded because the same mistakes are cheap to repeat, and three of them would have destroyed a
result rather than a day.

**A.1 Two worktrees were deleted with uncommitted work.** Both sibling branches sat at their base
commit with empty reflogs; the calibration estimator, the multi-child expansion arms, and the floor
probe were all lost and had to be rebuilt from written notes. *Lesson: commit before a worktree can
disappear — the rebuild cost more than the work.*

**A.2 The notebook assumed the wrong working directory.** A Jupyter kernel's cwd is the notebook
file's folder, not where the server was launched — and it keeps the old folder if the file is moved
without a kernel restart. The first server run died in cell 1, and the assertion blamed the operator.
*Lesson: never assert on cwd; locate the project root and `chdir` to it.*

**A.3 The compute-weights pointer was never wired.** `ComputeMeter` defaults to the v6 tree, and
nothing passed `-Dcompute.weightsFile`. On a fresh clone every measured run would have aborted in
`requireProfiles` — *immediately after* the 2.6 h calibration wrote to a directory nothing read.
Caught by inspection before it fired. *Lesson: verify the consumer reads what the producer writes.*

**A.4 Gate A3′ was mis-gated on container load average.** The loaded calibration reported FAIL three
times because the OS load average reads ~0.11/core inside the JupyterHub container while the machine
is saturated. The measurement had succeeded — every op cost 42–78% more than idle — and was being
discarded by its own detector. The gate now counts the harness's completed work instead.

**A.5 The currency-drift check reported 55% drift on a machine that had not moved.** Turns-per-rep
does not merely set a sample size — it selects *which positions* each level measures. `--checkArms`
fell back to v6's `4,4,4` instead of the action-bias `8,4,2`, so the check compared different
material. The tell was in the output: drift was ~0% at exactly the one level where the two settings
happened to agree. The same bug mis-priced one arm at one level. *Lesson: a comparison must reproduce
the conditions of the thing it compares against; the weights file now records them.*

**A.6 Smaller ones, each caught before it mattered.** An incremental-pricing guard keyed on the wrong
arm name (would have skipped pricing and aborted the next stage); a drift calculation that divided
where the merge multiplies (caught in a unit test); two arms defined as renamed duplicates producing
byte-identical output; a portfolio split that dealt 33/27 with a 22-point branching imbalance; a stage
ordering that would have run a paired comparison before its baseline existed.

**What kept these recoverable.** Every stage's artefacts were bundled and analysed off-server, so
re-analysis never required re-running. Checkpointing per leaf meant a correction cost one leaf, not
one stage. And the gates were written down in advance, which is why A.4 and A.5 were recognised as
detector failures rather than accepted as results.
