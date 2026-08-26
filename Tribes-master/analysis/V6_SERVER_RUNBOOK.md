# v6 on the Dell PowerEdge R7615 — threading, throttling and restart safety

Companion to `EXPERIMENT_V6_PLAN.md`. That document says *what* v6 measures; this one says how the run
is placed on the server, why the thread count is what it is, and what survives an interruption.
Nothing here changes a single number the experiment produces — see [Why none of this can change a
result](#why-none-of-this-can-change-a-result).

---

## 1. The machine

The R7615 is a single-socket AMD EPYC 9004 (Genoa) server. A fully-populated one reports **192 logical
CPUs from 96 physical cores**. `Runtime.availableProcessors()` returns 192, and using that number is
the default mistake this design exists to avoid.

Three things bend the throughput curve down well before 192:

**SMT does not multiply memory bandwidth.** The two logical threads on a core share load/store units,
L1/L2 and the core's slice of memory bandwidth. This workload is dominated by `GameState.copy()` and
forward-model advances — pointer-chasing over freshly-allocated objects. It is bandwidth- and
allocation-bound, which is precisely the profile SMT helps least. Past the physical core count the
curve flattens and can invert.

**All-core clocks are far below boost, and the drop is progressive.** An EPYC part that boosts near
3.7 GHz on a few cores sustains substantially less with every core loaded. Each thread added past the
sustained-power point makes every other thread slower, so wall-clock can go *up* while the thread count
goes up.

**Every in-flight turn holds a search tree.** Hundreds of MB at the largest budget. Concurrency
multiplies live heap directly, and a run that tips into back-to-back full GCs loses far more throughput
than the extra threads bought — or dies with an `OutOfMemoryError` two days in.

## 2. How the thread count is decided

Three mechanisms, in order, each narrowing the previous one. All of them are in
`core.data.exp`.

### `ServerProfile` — the ceiling

Reads `/proc/cpuinfo` and counts distinct `(physical id, core id)` pairs, which is the portable Linux
way to get the physical core count on an SMT machine. NUMA nodes (the EPYC NPS setting) and the model
name are read for the log. The ceiling is:

```
maxWorkers = physicalCores - reserveCores      (reserve ≈ 3%, min 1, max 4)
```

capped further by `-Dv6.maxThreads=N` if set. On a 96-core R7615 that is **93**. SMT siblings are never
given to workers: they are left to the GC threads, the JIT compiler threads and the OS, which are real
and concurrent. If the physical count cannot be read (non-Linux, unusual container), half of
`availableProcessors()` is assumed — erring low, because erring low costs some throughput while erring
high costs heat, memory and possibly the run.

This ceiling binds **every** pool in the pipeline: portfolio build, thread calibration, isolation phase.

Check it before a long run without executing any work:

```bash
java -cp "out:lib/*" core.data.exp.ServerProfile
```

Overrides: `-Dv6.maxThreads=N`, `-Dv6.reserveCores=N` (or `V6_MAX_THREADS`, `V6_RESERVE_CORES`).

### `ThreadCalibrationV6` — the knee, measured per budget level

Sweeps thread counts up to the ceiling on a fixed workload and picks the **smallest count within 3% of
the best sustained throughput**, not the peak. Paying 50% more threads (and 50% more memory and heat)
for 2% more throughput is a bad trade on a multi-day run.

Two design points matter:

- **Each point is timed in two segments, and the knee is chosen on the second.** The clock a server
  holds for ten seconds is not the clock it holds for ten hours. `ThrottlePct` reports how much slower
  the second segment was than the first; a point that only reaches peak by borrowing boost headroom is
  visibly flagged and is not preferred. `ClockStartMHz`/`ClockEndMHz`/`ClockDropPct` record the same
  effect directly from `/proc/cpuinfo`.
- **Per budget level.** Tree size grows with the budget, so the memory ceiling — and therefore the best
  thread count — at 400k units is not the one at 40k. Each level is swept and checkpointed separately
  (`_tuned_threads_<cu>cu.txt`), and the full curve is written to
  `_calibration/thread_calibration.csv` so it is reportable, not just used.

Per-task heap is estimated from the **largest** measured point, never a running maximum across points:
peak heap contains a fixed component the workers do not own, and dividing that by two threads charges it
as if it were per-task. (That overestimate is not hypothetical — it aborted a sweep at 9 threads on a
machine that comfortably ran 15.)

### `LoadGovernor` — throttling during the run

The calibration measures a *sample*. The real run crosses the whole portfolio, whose branching factors differ
several-fold, and search-tree size tracks branching factor, so a thread count that is comfortable on the
average scenario can be over-committed on a run of wide ones. The governor caps how many turns are
in flight and lowers that cap when the JVM shows pressure:

| signal | source | throttle above | release below |
|---|---|---|---|
| old-gen occupancy after collection | `MemoryPoolMXBean.getCollectionUsage()` | 78% | 60% |
| GC time share of recent wall-clock | `GarbageCollectorMXBean.getCollectionTime()` | 20% | 8% |

Post-collection occupancy is used rather than instantaneous used-heap, which sawtooths with allocation
and says nothing. Permits are withdrawn a quarter at a time and returned one at a time: memory pressure
compounds, so a slow taper can be overtaken by the allocation it is trying to stop, while restoring in
bulk just re-triggers the throttle seconds later. It never falls below 25% of the calibrated count.
Every throttle event is logged, and each leaf prints a summary of how far concurrency fell.

## 3. Multithreaded scenario generation

`BuildV6Portfolio` owns the scheduling; the generation and selection **rules** are `BuildV5Portfolio`'s,
called rather than reimplemented (see §5).

v5 ran source games in barrier-synchronised waves of `threads` attempts. Every wave finished at the
speed of its slowest game, and source-game length varies several-fold, so on a 93-worker server most
workers would idle most of the time. v6 keeps attempts **continuously in flight** with a bounded
look-ahead and consumes their results **in attempt order**.

The look-ahead is adaptive, which matters more than the continuity. The portfolio needs the first
`target` valid captures in attempt order, so any attempt beyond that count is played and thrown away.
A fixed look-ahead of `2 × threads` on a 93-worker machine would speculatively play ~150 single-source
games when about 10 are needed. Instead:

```
lookahead = clamp(ceil(1.5 × capturesStillNeeded / observedYieldPerAttempt), 1, 2 × threads)
```

starting from the optimistic yield (every capture tick valid) and switching to the observed yield after
the first results. The single category, which yields up to 11 captures per game, converges to a handful
of concurrent games; the dual category, whose yield is far below one capture per attempt, correctly
opens the pipeline to the full ceiling.

**Determinism is unaffected.** The portfolio is defined by the *ordered consumption*, not the execution
order, and each attempt is a self-contained game seeded by `seedBase + attempt`. Verified: the v6 and v5
builders produce an identical `portfolio.csv`, at any thread count, and a resumed build produces the
same manifest as an uninterrupted one.

> **Do not compare portfolios with `cmp` on the `.bin` files.** `Tribe` holds enum-keyed `HashMap`s
> (`monuments`, `tribesMet`), and enum `hashCode()` is the JVM identity hash, so the *serialization
> order* of those maps varies between JVM runs and between thread counts. Contents are identical and so
> is everything computed from them — a fixed-seed agent planning from either version produces the same
> greedy root, the same sequence values, the same node counts and the same realized compute units. This
> is a pre-existing property of v5's portfolios too. Compare by `portfolio.csv` and by agent behaviour.

## 4. Interrupt safety

The isolation phase runs for days. It *will* be stopped — Ctrl-C in a notebook, a `kill` to get the
machine back, an OOM, a reboot. `RunGuard` makes an orderly stop cost nothing and a hard kill cost at
most one turn per worker.

### Cooperative stop (SIGTERM / SIGINT / Ctrl-C)

A shutdown hook flips a stop flag. Submission loops stop queueing, and every queued-but-unstarted task
becomes a no-op, so the pool drains in seconds instead of grinding through thousands of pending turns.
Running turns finish and write their rows. A config that did not finish is **not** marked complete, so
the resume continues it per (scenario, visit) rather than assuming it done.

**Stop with SIGTERM, not `kill -9`:**

```bash
pkill -TERM -f RunExperimentV6
```

### What survives a hard kill

| stage | granularity kept | mechanism |
|---|---|---|
| portfolio build | every finished source game | `_build_journal/`: bins written first, then ONE terminated index line per attempt |
| C0 calibration | every fully-measured target | atomic rewrite of `calibration.csv` after each target |
| thread calibration | every finished sweep point | appended to `thread_calibration.csv` as measured |
| isolation phase | every finished turn | per-turn append; telemetry row written last, as proof the turn completed |

Two details are load-bearing:

- **One index line per source game, ending in `END`.** Recording one line per capture looks equivalent
  and is not: a kill between the third and fourth line of an eleven-capture game leaves a journal that
  claims the game yielded three captures, the resume believes it, and the portfolio is silently built
  from the wrong scenarios. That is the one failure mode here that produces *bad data* rather than lost
  time. One line per attempt plus a sentinel reduces the window to a single append and makes a torn
  write detectable.
- **Telemetry is the completion authority in a leaf.** Results rows are written before the telemetry
  row, so on resume any results row not backed by a telemetry row is dropped and recomputed. A line
  truncated by the kill fails the field-count check and is discarded the same way.

Checkpoint and marker files (`_portfolio_complete.txt`, `_greedy_init_ok.txt`, `_tuned_threads_*.txt`)
are written to a temp file and renamed atomically, so a kill mid-write leaves the old content or the new
one, never a truncated file that parses but lies.

**Verified end to end:** an isolation phase hard-killed mid-leaf and resumed produces a dataset
identical to an uninterrupted run — same telemetry byte-for-byte, same results rows once the three
deliberately wall-clock columns (`Timestamp`, `PlanMillis`, `InitMillis`) are excluded.

### Verifying the data afterwards

`core.data.exp.VerifyV6Output` scans every leaf for duplicated `(scenario, visit)` turns, results rows
with no telemetry row behind them, torn rows, and leaves marked complete in `_completed.txt` that are
not. Run it before any analysis, and after any interrupted run:

```bash
java -cp "out:lib/*" core.data.exp.VerifyV6Output test_results/v6
```

`--repair` rewrites the affected leaves keeping the first complete copy of each turn (a `.bak` is kept
beside every rewritten file) and removes the checkpoint key of any leaf that is not actually finished,
so the next run completes it rather than skipping it. Verified against a deliberately damaged tree: the
repaired files come back byte-identical to the undamaged originals.

### One writer per output tree

Every job takes a lock (`_running.lock`) on its output directory and refuses to start beside a live one.
This is not paranoia: the documented way to resume is "re-run the same cell", and a backgrounded
`nohup java` survives a disconnected kernel, a closed browser and a re-executed cell. Two writers on one
tree append to the same CSVs and both assemble the same manifest — the result is a duplicated dataset,
not a crash, and it stays invisible until analysis.

Liveness is decided by a **heartbeat** (refreshed every 20 s, stale after 120 s) *and* the pid, not the
pid alone: the OS recycles pids, so a dead holder's number can be answered by an unrelated process and
block a legitimate resume forever. A lock left by a crash goes stale on its own — no manual cleanup.

## 5. The scenario rules — generation and selection

Unchanged from v5. Stated here in full because v6 reuses them by calling `BuildV5Portfolio`, not by
copying them, and because they are what a reader of the results needs to know.

### Source games

| | single-source | dual-source |
|---|---|---|
| players | EMCTS vs `DoNothingAgent` | EMCTS vs EMCTS |
| game mode | `SCORE` | `CAPITALS` |
| target scenarios | 100 | 100 |
| capture turns | 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30 (11/game) | 15, 20, 25 (3/game) |
| level-seed base | 4 000 000 | 5 000 000 |
| attempt cap | 150 | 1500 |

Common to both: tribes IMPERIUS vs IMPERIUS2 on an 11×11 water-free board; fixed game seed 9 000 001;
source-agent seed 100; source EMCTS budget 26 800 compute-units (the 1000 ms-equivalent, chosen as a
reproducible count rather than a wall-clock). Level seed for attempt *a* is `seedBase + a`, so each
attempt is a fully-determined, self-contained game. The source agent uses **greedy** initialization —
v5's random-init switch applied only to the agents under test, never to portfolio construction, so
nothing about the portfolio needed to change for v6.

Each capture is a **turn-start** state (`Game.captureAtTickAsTurnStart`), and a game stops contributing
once it is over.

### Validation — a capture is kept only if all of these hold

1. the active tribe ID is in range;
2. the active tribe has **≥ 1 city** and **≥ 1 unit**;
3. its score is **≥ 500** (the stuck-game guard: a spawn-pinned tribe sits at its starting score);
4. a tiny EMCTS `act()` on a copy of the state does not throw — an edge-case filter, run on a local
   50 ms timer so it is thread-safe and never mutates global state.

**Stuck-game guard:** if the *first* capture turn of a source game fails validation, the whole game is
abandoned rather than continued — an early failure predicts a degenerate game, not a slow start. A
failure at any later tick skips just that tick. Engine exceptions are swallowed per game: whatever was
captured before the throw is kept and the build moves to the next seed, so one bad seed never aborts the
build.

### Selection

The portfolio is **the first `target` valid captures in `(attempt, tick)` order**, per category —
single first, then dual. IDs are assigned in that same order (`scenario_001` … `scenario_200`), and the
states are written single-threaded in one pass at the end, so IDs never depend on thread timing. Attempts
are consumed in order regardless of which finishes first, which is what makes the parallel build produce
exactly the sequential build's answer.

If a category exhausts its attempt cap before reaching its target, the build warns, writes no completion
marker, and keeps the journal — it does not silently ship a short portfolio.

### Manifest

`portfolio.csv` records, per scenario: id, source, source game id, level seed, game seed, captured tick,
active tribe id, its city/unit counts and score, the branching factor, and the action-type composition of
the state (attack / move / other unit / city / tribe action counts) that hypothesis H5 needs.

## 5b. Is the compute-unit budget reliable, and is it fair?

Two separate questions. Both are now measured rather than assumed, by
`core.data.exp.BudgetFairnessProbe` (run it from notebook §A2b).

### Reliable — yes

Measured over 8 scenarios at 26,800 units, every one of the 12 configurations:

- lands within **0.4%–2.0%** of its target spend (SD a few hundred units on 26,800);
- performs exactly **20 bridge burns**, as the design requires;
- reports a non-zero initialization cost while search still reaches the full budget, which is the
  observation that distinguishes "init excluded" from "init charged".

The mechanism behind that is the cumulative per-phase ceiling: each phase runs until the running total
reaches `(phase+1) × budget / depth`, so whatever a crossover tournament spends is absorbed by the next
phase doing fewer iterations. Overshoot is bounded by one iteration, not by one tournament.

### Fair — because the weights are per configuration and per level

A unit is `W_ADVANCE·advances + W_COPY·copies + W_EVAL·evals`, and since v6 those weights are measured
**per (configuration, budget level)** by `ComputeCalibrateV6` rather than once for the whole pipeline.
The reason is that the operations do not cost the same everywhere: a crossover agent advances, copies
and evaluates states its mutation-only counterpart never reaches, and at ten times the budget the tree
stops fitting in cache. Measured on the dev laptop, per-advance cost varies **13–18% between
configurations within a single level**.

Every weight is relative to one reference operation (an advance in `EMCTS@40000`), so `W_ADVANCE` is
1.0 only for the reference and an agent whose advances really take 1.4× as long is charged 1.4 units
for each. Equal unit budgets are then equal wall-clock, by measurement rather than by assumption.

The numbers below are the *old* single-weight-set analysis, retained because it quantifies what the
per-config profiles remove. It only became *unfairness* to the extent the configurations spend
differently — if they all used operations in the same proportions, a wrong weight would rescale every
budget identically and cancel out of a paired comparison.

They spend nearly alike. Copies per advance range 0.685 (EMCTS) to 0.812 (CrossoverCombo@5); the
weighted split is ~85% advances / ~10% copies / ~4.5% evals for every config. Re-pricing each config's
realized work under alternative weights:

| weighting | worst config vs EMCTS | spread |
|---|---|---|
| as calibrated | 100.0% | 0.00 pp |
| copy weight ×2 | 101.4% | 1.38 pp |
| copy weight ÷2 | 99.2% | 0.80 pp |
| eval weight ×2 | 100.4% | 0.96 pp |
| copy 0.50, eval 0.25 (~3× off) | 103.1% | 3.63 pp |

So a plausible calibration error moves the configurations relative to each other by **about one
percentage point of effective budget**, and only a grossly wrong weighting reaches ~3.6.

Two honest caveats for the write-up:

- The bias has a **direction**. Crossover configurations are slightly copy-heavier, so if copies are
  under-priced they receive marginally *more* real compute than EMCTS — the direction that flatters
  crossover. Worth stating explicitly if a crossover advantage of the same order is reported.
- The fix is not a flag any more, it is a measurement: run `ComputeCalibrateV6` on the R7615
  (notebook §0.5). It writes `compute_weights.properties`, which the whole pipeline auto-loads, with one
  weight set per (config, level). Do it **before** C0, since changing the weights changes what a unit
  is, and re-run `BudgetFloorProbe` afterwards because the floor was derived in the old currency.

Two things to check rather than assume:

- **The run banner.** Both runners print `Compute profiles: N/M calibrated`. Anything short of M means
  some configurations fell back to the global weights — silently, with no error — and their budget is
  no longer equal compute. `BudgetFairnessProbe` prints which profile priced each configuration.
- **C0's `units/ms by config` table**, which is the direct empirical version of the same check: flat
  across configs on the run machine means the currency is fair there. That table is now a pass/fail on
  work already done rather than an open design question.

## 5c. Do the tests answer the crossover question?

**The design does.** The 12 configurations are a complete 2×2 factorial at every ratio and budget:

|  | expansion crossover OFF | expansion crossover ON |
|---|---|---|
| **BB crossover OFF** | `EMCTS` | `CrossoverExpansion@r` |
| **BB crossover ON** | `CrossoverBB` | `CrossoverCombo@r` |

All four cells share the scenario, the visit seed and the greedy root, so the BB main effect, the
expansion main effect and their **interaction** are all identified. That last one — do the two
mechanisms reinforce each other or interfere? — is the question the shape of the design was built to
answer, and it is not answerable from "each config vs EMCTS" alone.

Three things were missing, and are now in place:

1. **The interaction was never computed.** `analysis/v6_significance.py` adds it, per ratio per budget,
   alongside the two main effects and `Combo vs best single`.
2. **The statistics pooled visits as independent.** The descriptive CI treats ~5,000 turns per cell as
   independent replicates, but the 50 visits of a scenario share a board and a root; the replicate is
   the scenario (n=100). Pooling understates the SE by up to √50 ≈ 7×. The significance script averages
   visits first, then runs a paired Wilcoxon signed-rank with Benjamini–Hochberg correction and reports
   Cliff's δ so that "significant" and "large" stay separate — with this much power they are very
   different claims.
3. **Nothing checked that the operator actually fired** — and it was not firing. This turned out to be
   the most consequential finding of the review; it is why the budget levels changed. See §5d.

**What the design does not answer.** This is an isolation phase: it measures the value of the best
action sequence found in ONE turn from a captured position, under a fixed compute budget. That is
search quality, not playing strength. Any claim about winning games needs the tournament phase.

## 5d. The budget levels: 40,000 / 80,000 / 400,000

v5's levels (13,400 / 26,800 / 134,000) were carried into v6 unchanged, and they were too small once
the budget bounded search only.

**Why.** The crossover bridge-burn tournament is a 3-level bracket over the phase's top **eight**
elites (8 → 4 → 2 → 1, seven children evaluated). Two thresholds matter, and they are not the same one:

- the code *enters* the tournament when the elite pool holds **≥ 2**;
- its level-1 round pairs the pool two at a time, so a pool of 2 or 3 produces a single child, trips an
  internal `level1Children.size() < 2` guard, and returns `findBestChild(root)` — **the plain-EMCTS
  promotion** — without logging a crossover event.

So the bracket needs a pool of **≥ 4** to run at all. At 13,400 units it ran at **46%** of the 20
burns: more than half of the "crossover bridge burning" condition was not doing crossover bridge
burning, and nothing in the output said so.

**Measured tournament-completion rate** (`BudgetFloorProbe`, CrossoverBB and CrossoverCombo, worse of
the two, 8 scenarios spanning the portfolio):

| budget | tournaments run | mean bracket /8 | full bracket | new children per phase | sparsest ratio cell |
|---|---|---|---|---|---|
| 13,400 | 46% | 4.3 | 20% | 5.8 | 2.8 xo/turn |
| 26,800 | 63% | 5.5 | 42% | 10.5 | 5.3 |
| 33,500 | 74% | 6.0 | 49% | 14.4 | 6.5 |
| **40,000** | **74%** | **6.1** | **50%** | **17.1** | **7.8** |
| 54,000 | 84% | 6.7 | 66% | 23.7 | 10.8 |
| 70,000 | 89% | 7.1 | 77% | 30.7 | 13.8 |
| 90,000 | 89% | 7.3 | 83% | 39.5 | 18.0 |

**The rate has a ceiling near 0.89, not 1.0.** Branching factor varies several-fold across the
portfolio, and in the widest scenarios a phase's iterations are expensive enough that few children are
produced however large its slice is — roughly one burn in nine can never assemble a pool of four. A
"95% of burns" requirement is therefore unreachable rather than merely expensive, and chasing it would
push the budget up indefinitely.

**40,000 is the floor** because it is the smallest level at which the phase between two burns evaluates
**17 new children — twice the 8-slot pool**, so the elite pool is fully turned over between consecutive
burns rather than merely topped up, and the tournament breeds from nodes that phase actually found. It
reaches ~83% of the achievable coverage ceiling, and it is ~3× the old level, which is what the
symptom (46% coverage) called for.

The other two follow by construction: **80,000 (2×)** and **400,000 (10×)**. Both sit at the coverage
ceiling, so the three levels span one deliberately-constrained condition and two unconstrained ones —
which is a better-shaped budget axis than v5's, where all three were constrained to some degree.

**Cost.** Total work scales with the levels: the new set is ~3× the old one. Re-derive it (notebook
§3d) if the compute weights change, since a unit will then mean something different.

## 6. Why none of this can change a result

- The v6 budget is a **deterministic compute-unit count**, so a turn performs identical work whether it
  runs alone or alongside ninety others. Thread count and throttling change wall-clock only.
- Every turn's output depends only on its `(scenario, visit)` seed, shared across all 12 configs —
  the paired-comparison core, asserted by `RunExperimentV6.verifyGreedyInit` before the run starts.
- Portfolio selection is a property of ordered consumption, not execution order.
- The resume paths reproduce the uninterrupted dataset exactly (verified, §4).
- Asserted automatically: `NewExperimentSmokeTestV6` §8 runs the same turns single-threaded and across
  a pool and requires every outcome to match — greedy root, final sequence value, realized compute
  units, advances/copies/evals, nodes evaluated, distinct sequences and burn count. This is the
  rerun-identity check `EXPERIMENT_V6_PLAN.md` §8.5 left open, in the only form that means anything:
  it compares agent BEHAVIOUR, not files (see the note on serialization order in §3).

The one place where timing *is* the measurement is the **C0 calibration**, and it is the one job that
must stay single-threaded on an otherwise-idle machine. It prints that requirement in its own banner and
takes a lock so a second copy cannot start beside it.

## 7. Operating summary

```bash
# what will this machine do?
java -cp "out:lib/*" core.data.exp.ServerProfile

# compute-unit weights for THIS machine, per config per budget level (~35 min) — writes a file
# the whole pipeline auto-loads, and refuses to write it if any cell fails its validity checks.
# Needs the portfolio: it measures on the positions the experiment actually searches.
java -Xmx32g -cp "out:lib/*" core.data.exp.ComputeCalibrateV6

# smallest budget at which the crossover operators actually run as specified (see 5d)
java -Xmx16g -cp "out:lib/*" core.data.exp.BudgetFloorProbe \
    test_results/v6/_portfolio test_results/v6/_calibration 26800,33500,40000,54000,70000 10

# is the budget fair between the agents, and does each operator fire?
java -Xmx32g -cp "out:lib/*" core.data.exp.BudgetFairnessProbe \
    test_results/v6/_portfolio test_results/v6/_calibration 40000 12

# portfolio (parallel, journalled, restartable)
java -Xmx64g -cp "out:lib/*" core.data.exp.BuildV6Portfolio test_results/v6/_portfolio auto

# C0 — single-threaded, machine otherwise idle
nohup java -Xmx32g -cp "out:lib/*" core.data.exp.CalibrationRunnerV6 \
    test_results/v6/_portfolio test_results/v6/_calibration 500,1000,5000 12 5 40 \
    > v6_calibration.log 2>&1 &

# the run (also the resume command — identical, re-run as-is)
nohup java -Xmx750g -XX:+UseG1GC -XX:MaxGCPauseMillis=500 \
    -cp "out:lib/*" core.data.exp.RunExperimentV6 auto 40000,80000,400000 > v6_run.log 2>&1 &

# stop it cleanly
pkill -TERM -f RunExperimentV6

# check (and repair) the dataset before analysing it
java -cp "out:lib/*" core.data.exp.VerifyV6Output test_results/v6
java -cp "out:lib/*" core.data.exp.VerifyV6Output test_results/v6 --repair   # only if it reports problems

# analyse: description, then inference
python3 analysis/analyze_v6_full.py test_results/v6
python3 analysis/v6_significance.py test_results/v6
```

Cap the workers on a shared machine: add `-Dv6.maxThreads=48`.
