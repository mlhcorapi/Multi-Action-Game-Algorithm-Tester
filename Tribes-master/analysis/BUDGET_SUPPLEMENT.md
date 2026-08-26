# Supplementary: the thinking-time budget

Paper-facing description of how search effort is equalised across agents. Written to be lifted into a
methods appendix. Implementation: [`ComputeMeter`](../src/core/game/ComputeMeter.java),
[`ComputeCalibrate`](../src/core/data/exp/ComputeCalibrate.java),
[`CalibrationRunnerV6`](../src/core/data/exp/CalibrationRunnerV6.java).

---

## S1. The problem

Every agent compared here plans one turn and is then scored on the plan it produced. For that
comparison to mean anything, each agent must be given the same amount of *thinking*. Three
conventional ways to define "the same amount" all fail here, in ways this project can demonstrate
rather than assert:

**Wall-clock time** is the notion of fairness readers actually care about, but it is not reproducible
in our setting. The experiments run many games concurrently on a shared server, so delivered compute
per second depends on load. In the v4 data (wall-clock budgets) the 500 ms and 1000 ms levels
produced almost the same amount of work — EMCTS made 3,265 forward-model calls per turn at 500 ms and
3,420 at 1000 ms, a 4.7% increase for double the time — and the implied throughput was roughly 1
forward-model call per millisecond against the ~27 per millisecond a quiesced single-threaded run
achieves. A budget that delivers so little of its nominal value, and varies with machine load, cannot
support a claim about search effort.

**Forward-model calls** are the standard machine-independent budget in this literature: the Tribes
reference work budgets its benchmark agents at 2000 forward-model calls per action, and the usual
justification is that a call count is independent of hardware, implementation, and instrumentation.
But a call count is only a good proxy for effort if agents spend the same *fraction* of their effort
on forward-model calls, and ours do not. At equal wall-clock in v4, forward-model calls per turn
ranged from 3,265 (EMCTS) to 7,822 (CrossoverCombo@2) — a 2.4× spread — with evaluations per turn
spreading 2.5× and calls-per-evaluation ranging 117–148. Budgeting on forward-model calls would
therefore have handed some agents substantially more real compute than others.

**Search iterations** fail for the same reason, more starkly: a crossover-expansion iteration and a
bridge-burn tournament cost far more than a mutation iteration.

## S2. The unit

We budget on **compute units**, a weighted count of the three operations that dominate planning cost:

```
units  =  W_ADVANCE · advance()   +   W_COPY · copy()   +   W_EVAL · evaluateState()
```

One compute unit is defined as **the mean wall-clock cost of one forward-model call
(`GameState.advance()`) in the reference cell on the reference machine** — the reference cell being
plain EMCTS at the smallest budget level. A compute unit is therefore an *advance-equivalent*: a common
currency in which the three kinds of work an agent does can be added up.

**The weights are measured per (configuration, budget level), not once.** An advance, a copy and an
eval do not cost the same for every algorithm — a crossover agent operates on states a mutation-only
agent never reaches — nor at every budget, since a ten-times-larger tree stops fitting in cache.
Measured spread of per-advance cost between configurations within one level: **13–18%**. Every weight,
`W_ADVANCE` included, is therefore a measured ratio against the reference operation rather than a
definition: an agent whose advances take 1.4× the reference time is charged 1.4 units per advance. On
the dev laptop the resulting sets are roughly `W_ADVANCE` 0.80–1.00, `W_COPY` ≈ 0.20, `W_EVAL` ≈ 0.04.

This matters for reading the budget numbers. A budget of 13,400 units does **not** mean 13,400
forward-model calls. It means "as much planning work as 13,400 forward-model calls would cost". An
agent that spends its budget on, say, 10,000 advances, 12,000 state copies and 20,000 evaluations has
spent 10,000 + 1,920 + 1,540 = 13,460 units. Two agents at the same unit budget will generally show
quite different operation counts — that difference is precisely what the unit is designed to absorb.

The counters live in the game engine and the heuristic, not in the agents: they increment inside
`GameState.advance()`, `GameState.copy()` and `TribesSimpleHeuristic.evaluateState()`, which are
shared code on every agent's path. No agent can be metered more leniently than another. The meter is
thread-local and is reset at the start of each planning episode, so concurrent games never interfere
and the count is a deterministic function of the search, not of the machine.

## S3. From units to seconds

The conversion is a property of the reference machine, established once:

1. `ComputeCalibrate` times real searches and reports mean per-operation cost. On the development
   machine a forward-model call took on the order of 37 µs.
2. One millisecond therefore buys about 27 advance-equivalents, i.e. **≈26.8 units/ms**.
3. The budget levels follow: 13,400 units ≈ 500 ms, 26,800 ≈ 1 s, 134,000 ≈ 5 s of thinking.

The seconds figure is a description of the reference machine, not a property of the experiment. Run
the same 13,400-unit budget on faster hardware and the agent performs the *same work* in *less time*;
that is the reproducibility the design is buying. Budget levels are named in units throughout, with
the seconds equivalence stated once against a named machine — the same convention as reference-machine
normalisation in CPU benchmarking.

In v6 the conversion is re-derived by a dedicated calibration experiment
([`CalibrationRunnerV6`](../src/core/data/exp/CalibrationRunnerV6.java)) run single-threaded on a
quiesced machine, sampling the actual scenario portfolio, with each target time run separately, five
repetitions per cell, and JIT warm-up discarded. It reports the conversion, its variation across
agents and across scenario sources, and its stability across repetitions.

## S4. What the budget covers

The budget bounds **search**. Two boundary decisions define it, and both are deliberate.

**Greedy initialization is outside the budget.** Every agent begins its turn by building an initial
action sequence with a greedy 1-ply procedure: at each position it evaluates every available action
and keeps the best. This step is identical code in all agents and, given the shared per-(scenario,
visit) seed, produces an identical initial sequence in all of them — so its cost is a common constant
that carries no information about the operators under study.

It is excluded for a second and stronger reason. Because initialization evaluates every available
action at every position, its cost scales with the branching factor. Branching factor differs
systematically between our two scenario sources (mean 452 in single-player scenarios, 290 in
two-player ones). Charging initialization to the budget would therefore have left single scenarios
with systematically less *search* budget than dual scenarios — a bias aligned exactly with the
single-versus-dual contrast the study is trying to measure. Excluding it removes that coupling. The
cost is measured and reported separately (table B4) rather than discarded.

The budget is consequently stated as: *search effort following a common greedy initialization.*

**Bridge burning is inside the budget.** At each of the 20 phase boundaries the agent commits to a
partial plan. Standard agents promote the best child, which costs nothing. Crossover agents instead
run a tournament that synthesises and evaluates new candidates, which is expensive. That cost is
charged to the same budget, and this is essential rather than incidental: the question the experiment
asks is whether crossover's benefit exceeds its cost, so making the tournament free would answer a
question nobody asked. The phase ceilings are cumulative — a phase runs until total spend reaches
`(phase+1) × budget / 21` — so a tournament that overspends in one phase is absorbed by the next phase
running proportionally fewer iterations. Every agent finishes the turn on the same total budget having
performed the same 20 burns; the crossover agents simply evaluate fewer distinct sequences. That gap
is the effect under study.

## S5. Why this is fair across agents and across budget levels

**Across agents.** The unit is a time-equivalence measure rather than an activity count. Equal units
means equal predicted cost on the reference machine, whatever mix of advancing, copying and evaluating
an agent chose. An agent that evaluates more per forward-model call is charged for those evaluations;
one that clones state more often is charged for the clones. The alternatives all fail this test on our
data — an equal-forward-model-call budget would have differed by up to 2.4× in real compute (quantified
per agent in table B2), and an equal-iteration budget worse still.

**Across budget levels.** The unit is defined without reference to a level, so the same conversion
applies at 13,400 units and at 134,000: doubling the budget doubles the work purchased. What is *not*
automatically proportional is the amount of useful search, because a turn carries fixed costs —
initialization, and the 20 mandatory bridge burns. v6 removes the larger of these from the budget
entirely, which makes the levels closer to proportional in search effort than in v4/v5, and reports
what remains.

**The condition this rests on.** Fairness across agents holds if and only if the weights correctly
describe relative operation cost *for that agent*. That is an empirical claim, and it was tested rather
than assumed — and it failed for a single global weight set: per-advance cost varies 13–18% between
configurations within one budget level, so the same nominal budget bought measurably different amounts
of real compute.

The weights are therefore calibrated per (configuration, budget level), which makes the fairness
criterion direct — equal units are equal reference-machine thinking time — while keeping the budget a
deterministic operation count, so the search stays reproducible. That is both properties at once; the
alternative of giving each agent the operation count it completes in T seconds achieves the first by
turning the budget back into a clock, which is what v5 moved away from.

The claim remains falsifiable, and C0 is where it is falsified: it measures realized units per
millisecond for each agent separately, and with the profiles in force that figure must be flat across
agents. Spread there means a profile is stale or missing, not that the design is wrong.

## S6. What is reported

Every run logs realized spend per turn, so budget adherence is a result rather than a claim:

| table | what it establishes |
|---|---|
| `B1_budget_adherence` | realized search units against target per agent per level — mean, SD, overshoot distribution and maximum; plus within-turn pacing across the 20 phases |
| `B2_op_mix` | advances/copies/evals per turn per agent, and how much more or less real compute an equal-forward-model-call budget would have given each agent |
| `B3_reference_conversion` | the units→seconds conversion, its variation across agents and scenario sources, and its stability across repetitions |
| `B4_init_cost` | the excluded initialization cost by source and branching-factor tertile — the size of the bias that excluding it avoids |
| `B5_compute_profiles` | the per-(config, level) weights that priced this dataset, the per-operation times behind them, and the spread that made one global weight set untenable |
| `B6_realized_ms_per_unit` | wall-clock actually spent per 1,000 units in the run itself, by agent and level — corroboration that equal budgets cost equal time |

Overshoot is expected and bounded rather than zero. The ceiling is tested at iteration boundaries, so
a turn can exceed it by up to the cost of the iteration in flight; that cost is larger for a crossover
tournament than for a mutation step, so overshoot is mildly agent-dependent. B1 quantifies it.

## S7. Limitations

**Coverage.** Three operation types are counted. Action generation outside `advance()`, repair
bookkeeping, priority-queue maintenance, node allocation and garbage-collection pressure are not.
Profiling indicates the forward model is well short of 100% of planning wall-clock, with the remainder
dominated by copies and evaluations, but the accounting is not exhaustive. The calibration's
units-per-millisecond figure is the residual check: if counted units track measured time closely and
without agent-specific bias, the uncounted remainder is not distorting the comparison.

**Weight provenance.** The weights are measured on one machine from real searches, on the same
portfolio positions the experiment plans from. Per-operation cost is not perfectly constant even within
a cell — state-clone cost grows with game-state size, and forward-model cost varies with action type —
so a cell's weights are a mean over the positions sampled. What the per-(config, level) calibration
removes is the *systematic* component of that variation: differences aligned with the agent under study
and with the budget level. Residual within-cell variation remains, and the calibration reports the
conversion split by scenario source and branching factor, which is the check that it is not aligned
with the study's independent variable.

**Weights are part of the dataset.** A compute-unit is only defined relative to them, so
`compute_weights.properties` and `compute_profiles.csv` are provenance, not scratch files: a run
reproduced against different weights is a different experiment. The run banners print how many profiles
were in force, and B5 tables them alongside the results they priced.

**The self-referential step.** Per-operation cost depends on how large the tree grew, which depends on
the weights in force while measuring. The dependence is second-order — it moves tree size, not the
shape of the work — and re-running the calibration is the cheap check: a converged calibration
reproduces its own weights within the coefficient of variation it reports.

**Reproducibility.** A deterministic operation count makes the *budget* reproducible. It does not by
itself make the *run* reproducible: that additionally requires seeded RNG and no incidental
nondeterminism elsewhere in the search. This is asserted by the invariance gate on the shared initial
sequence but is not a full rerun-identity check.
