# AB-EMCTS runbook — how to execute the plan on the R7615

*Companion to `AB_EMCTS_R7615_PLAN.md`, which says **what** each stage measures and why. This says
what to type, in what order, and what to look at before continuing. To actually RUN it, open
`analysis/run_ab_emcts.ipynb` — same sequence, one cell per stage, output teed to `$DATA/logs/`. It
locates `Tribes-master/` by itself: a Jupyter kernel's working directory is the notebook's own
folder, never where the server was launched. `V6_SERVER_RUNBOOK.md` covers thread placement,
throttling and restart safety for the machine itself.*

Every command runs from `Tribes-master/`. Substitute `W` with the worker count the calibration picks
(step A0b) and `<N>` with a heap size (see §Heap), and define once per shell:

```bash
DATA=$HOME/ab_runs
WF="-Dcompute.weightsFile=$DATA/stage1/_calibration/compute_weights.properties"
```

**Everything this line produces lives under `$DATA`, outside the clone.** The operating loop is
clone → run a stage → send its bundle → merge the follow-up PR → clone again, so anything written
inside the clone is gone on the next round. The portfolio especially: built once, pinned; a rebuild
silently re-pairs every visit seed.

**`$WF` goes on every measuring or measured JVM** (the notebook's `java()` helper adds it to all of
them). `ComputeMeter`'s compiled default points at the *v6* tree's weights, so without the flag a
run either prices arms in a stale currency or — the guard working as designed — aborts in
`requireProfiles`.

## What to send back after each stage

Each stage's last notebook cell is `bundle(...)`: it zips the pre-specified results into
`$DATA/to_claude/<stage>.zip`. Download that one file and send it back; the analysis and the next PR
come from it.

| Stage | The zip carries |
|---|---|
| shakedown | the full shakedown log |
| A | every A-step log; `compute_profiles.csv` + `compute_weights.properties` and the `_load<W>` pair beside them; `thread_calibration.csv`; `action_bias_floor.csv`; the portfolio manifest and both split files |
| B | `ab_results/B/` (tables, figures, `SUMMARY.md`); B logs; the whole `stage1/dev` tree of results + telemetry CSVs |
| C | `ab_results/C/`; C logs; `games_summary.csv`, `_meta.txt`, per-game `turns_*.csv` |
| D | `ab_results/D/`; D logs; the whole `stage1/dev` tree |
| E | `ab_results/E/`; E logs; the whole `stage1/dev` tree |
| F | `ab_results/F/`; F logs; the whole `stage1/holdout` tree; `frozen_choices.txt` |

Heavy raw streams (`mutlog_*.csv.gz`, `seqlog_*.csv.gz`) never travel by default — they stay on the
server, and specific files can be requested by name.

---

## 0. Before anything: the shakedown *(~6 min)*

```bash
bash run_ab_shakedown.sh
```

Stage A is a ~5.6 h block that is mostly single-threaded and needs an idle machine, so an environment
problem found inside it costs an evening. This runs every moving part at toy size — build, machine
detection, portfolio, split freeze, both equivalence gates, the selection-structure assertions, the
calibration path including `--underLoad`, a real split run, and the analysis. It measures nothing;
18 checks, and it exits non-zero if any fails.

**Read two things by eye even when it passes:**

- The `[machine]` banner must say physical cores were **detected**, not `ASSUMED`. If it says
  ASSUMED, pass `-Dv6.maxThreads=N` to every later command — the detector is Linux-first and a
  container or an unusual topology can defeat it.
- The filesystem line. The working tree — and `$DATA`, where the calibration actually writes and
  times — must be on **local disk**. The recorded failure was a sync daemon waking during a timing
  measurement.

Then delete `test_results/_shakedown`.

---

## Stage A — machine, currency, gates *(~5.6 h; nothing measured)*

### A0 — the machine

```bash
java -cp "out:lib/*" core.data.exp.ServerProfile
```

```bash
nohup java -Xmx<N>g -cp "out:lib/*" core.data.exp.ThreadCalibrationV6 \
      $DATA/stage1/_portfolio $DATA/stage1/_calibration 40000,80000,400000 \
      > $DATA/logs/A0_threads.log 2>&1 &
```

*(Thread calibration needs a portfolio; if you have not built one yet, run A1 first and come back.)*

Record `W` **per level** — tree size grows with the budget, so the best thread count at 400k is not
the one at 40k. Also read `ThrottlePct` and `ClockDropPct`: a point that only reaches peak on
borrowed boost headroom is not the one to run for hours.

### A1 — portfolio and the frozen split *(~0.3 h)*

```bash
java -Xmx<N>g -cp "out:lib/*" core.data.exp.BuildV6Portfolio $DATA/stage1/_portfolio W 200 200
```

```bash
java -cp "out:lib/*" core.data.exp.PortfolioSplit $DATA/stage1/_portfolio
```

The freeze prints the balance it achieved — counts per source and mean branching per half. **Read it
before trusting the split**: the halves should differ by at most one scenario and by a couple of
branching points, not by twenty. Check the branching distribution against the 60-scenario reference
(mean 256 / median 234 / p90 425); if the builder ran out of distinct source positions at 200 per
stream, fall back to 150 + 150 and say so in the write-up.

**This portfolio is pinned for the whole line.** Never rebuild it: `visitSeed` keys on scenario id, so
a rebuild silently re-pairs everything.

### A2 — calibrate idle *(~2.6 h, single-threaded, machine must be idle)*

```bash
nohup java -Xmx<N>g -cp "out:lib/*" core.data.exp.ComputeCalibrateV6 \
      $DATA/stage1/_portfolio $DATA/stage1/_calibration \
      3 auto 6 40000,80000,400000 --suite ab > $DATA/logs/A2_calibrate.log 2>&1 &
```

54 cells: the reference `EMCTS` plus all 17 action-bias arms, × 3 levels. `auto` keeps the suite's own
turn counts (`8,4,2`) — do not replace it with a number unless you mean to, because the zero-eval arms
need the extra turns at 40k to clear the sample floor.

**Nothing else may run on the box during this.** Verdict must read `PASS`; on `FAIL` the weights are
deliberately not written and the measurements are still in `compute_profiles.csv` for inspection.

### A3′ — calibrate under load *(~1.0 h)*

```bash
nohup java -Xmx<N>g -cp "out:lib/*" core.data.exp.ComputeCalibrateV6 \
      $DATA/stage1/_portfolio $DATA/stage1/_calibration \
      3 auto 6 40000,80000,400000 --suite a3 --underLoad W > $DATA/logs/A3_loaded.log 2>&1 &
```

Writes `compute_weights_load<W>.properties` **beside** the idle weights, never over them, and prints
the comparison table and a verdict. This is the gate that decides whether the wall-clock fairness
re-test is a secondary check or co-primary for the whole line, so keep the verdict block.

> **Resolved 2026-08-29 — do not re-run.** The 2026-08-28 run measured what it needed to despite
> reporting FAIL: the container's OS load average is meaningless (0.11/core while every op cost
> 42–78% more than idle), and the gate now counts the harness's own completed work instead.
> Outcome, recorded in the plan's Gate A3′ resolution: idle weights stay the currency; `Z0_Fine`
> +24% under load; **W7 is co-primary** — every `SUMMARY.md` carries a W7 verdict table, and a
> champion must win in both currencies.

### A4 — the gates

```bash
java -Xmx<N>g $WF -cp "out:lib/*" core.data.exp.Stage1Runner verify
```

```bash
java -Xmx<N>g $WF -cp "out:lib/*" core.data.exp.Stage0SignalRunner verify 40000 20
```

```bash
java -Xmx<N>g $WF -cp "out:lib/*" core.data.exp.ActionBiasFloorProbe \
     $DATA/stage1/_portfolio $DATA/stage1/_calibration 40000,80000,400000 20 W
```

The third is the floor probe: **does the bias have evidence to act on at each level, or is it Baseline
with bookkeeping?** `qhat` shrinks toward the running global mean, so an unobserved key returns exactly
that mean; when every candidate key is unseen they all tie and the tie-break is uniform. An arm at too
small a budget then reports a small noisy effect instead of announcing that the mechanism never fired.

The quantity is not the mutation count — the search concentrates on a few actions, so a sparse table
can still be well-supported exactly where it is read — but how much evidence stood behind the key each
biased insertion actually **chose**. Criterion: ≥ 80% of picks from burn 4 onward chose a key carrying
≥ 3 observations.

Run it **after A2**: a budget is a number of units and a unit is defined by the weights, so this answer
is weight-dependent and must be re-taken whenever they change — moving machines included.

> **Gate A.** Both verifies PASS; A2 verdict PASS; A3′ verdict recorded; the floor probe passes at 80k;
> the split frozen and balanced; cores detected. **Nothing downstream runs otherwise.**
>
> A level that fails *only* the floor probe is a **reporting** level — it can still carry a budget trend
> or an anytime curve, but not a claim that the bias helped, because at that budget the bias was not
> operating.

---

## Stage B — the substrate *(~0.4 h)* — **run this before D**

Measured 2026-08-28 (A0b): **`W = 54`** at 80k (47 at 40k; both nearly flat past 39), and the 400k
level is **heap-bound at 31 workers** under `-Xmx64g` — every command with a 400k level below
carries `-Dstage1.threads.400000=31` (the notebook does too). `machineFactor` fitted from A0b:
**1.5**. Widening the 400k level is a decision for Gate B, taken only with `free -g` in hand and
`HEAP` raised together with it.

```bash
nohup java -Xmx<N>g $WF -cp "out:lib/*" core.data.exp.Stage1Runner W $DATA/stage1 dev B \
      > $DATA/logs/B.log 2>&1 &
```

```bash
python analysis/analyze_stage1.py $DATA/stage1/dev --outdir $DATA/ab_results/B
```

Four arms at 80k: `Baseline`, `Bias_e25`, `Comma_Off`, `Comma_On`. The two plus arms are the real
`Baseline` and `Bias_e25` rather than renamed copies, so **Stage D later skips them on resume** — same
checkpoint keys, no work done twice.

**Read `SUMMARY.md` for two things**, and stop before D until you have:

1. **The main effect**, `Comma_Off − Plus_Off`. Whichever wins becomes the substrate *and the
   baseline* for D–G. If comma wins, that is a re-pin, not a footnote.
2. **The design section** — the measured ICC, which sets Stage D's visit count. The default is 8; a
   pilot put the ICC near 0.12, which would say visits are worth more than the plan first assumed.

---

## Stage C — is there still a signal? *(~0.7 h, observation only)*

```bash
nohup java -Xmx<N>g $WF -cp "out:lib/*" core.data.exp.Stage0SignalRunner \
      W 40000,80000,400000 48 emcts 50 $DATA/stage0 > $DATA/logs/C.log 2>&1 &
```

```bash
python analysis/analyze_stage0.py $DATA/stage0 --outdir $DATA/ab_results/C
```

> **Gate C.** Mean ρ ≥ 0.15 with a CI excluding zero, at 80k, for at least one granularity. **Fails
> everywhere → stop the line**; the premise is gone and D–G are unfundable.

Also read R9 and R10 — payoff versus expansion-target age, and the re-measured duplicate rate. A pilot
found the improvement rate falling roughly tenfold as the incumbent goes stale, which is the first
real argument for per-burn resets and a mechanism for comma beating plus.

---

## Stage D — biased insertion *(~5.3 h)*

```bash
nohup java -Xmx<N>g $WF -Dstage1.machineFactor=1.5 -Dstage1.threads.400000=31 -cp "out:lib/*" \
      core.data.exp.Stage1Runner W $DATA/stage1 dev D > $DATA/logs/D.log 2>&1 &
```

All 11 arms at 80k → 40k → 400k (8/8/6 visits). That order is the schedule, not a listing: leaves are
checkpointed independently, so an interrupted run keeps the main grid and its budget trend rather than
having spent the time on the expensive confirmation first.

Set `machineFactor` from Stage B's measured `PlanMillis` so every banner estimate afterwards tells the
truth (1.0 = the dev laptop; the R7615 wants roughly 1.6, but measure it).

> **Gate D.** The champion is the best arm whose CI excludes 0 **and** which survives the wall-clock
> re-test. **Write ε\* and the champion name down before Stage F.**

If the ε curve is still rising at 0.10, extend it — but only then.

---

## Stage E — table-directed repair *(~2.6 h)*

```bash
nohup java -Xmx<N>g $WF -Dstage1.threads.400000=31 -cp "out:lib/*" core.data.exp.Stage1Runner \
      W $DATA/stage1 dev E <champion> > $DATA/logs/E.log 2>&1 &
```

`<champion>` is Stage D's winner (default `Short5_e25`). If ε\* is materially different from 0.25,
change `Stage1Runner.Z0_EPS` **and re-run the `ab` calibration suite** — the arms and their prices
have to move together.

Check `RepairCandidateEvals` reads **0** for every `Z0_*` arm. That is a construction check, not a
finding: a non-zero value means the mode is not engaged and the run is a bug.

---

## Stage F — the holdout *(~2.2 h, runs once)*

**Freeze the choices in writing first.** ε\*, the champion, the repair granularity winner. The holdout
is worth nothing if it is read twice.

```bash
nohup java -Xmx<N>g $WF -Dstage1.threads.400000=31 -cp "out:lib/*" core.data.exp.Stage1Runner \
      W $DATA/stage1 holdout F <champion> > $DATA/logs/F.log 2>&1 &
```

```bash
python analysis/analyze_stage1.py $DATA/stage1/holdout --outdir $DATA/ab_results/F
```

> **Gate F.** A development effect that does not replicate here **is not a result**. Report both
> numbers side by side, always.

---

## Stage G — the yardstick

Needs `#9` (map set 25 → 75) and `#10` (matchup list and the errors-in-variables mapping), which are
not written yet and depend on Stage D's champion. Size it from a 4-game timing probe rather than from
the plan: full-game turns are far cheaper than isolation turns, and this line's own bug table records
a runtime estimate wrong by 10× in exactly that direction.

---

## Heap

`ThreadCalibrationV6` estimates per-task heap from its largest measured point. Until you have that
number, `-Xmx` at roughly `0.75 ×` RAM for the run JVMs is safe on a machine with hundreds of GB, with
`-XX:+UseG1GC -XX:MaxGCPauseMillis=500`, and `-XX:+UseNUMA` if `ServerProfile` reports more than one
node. Use `-XX:+AlwaysPreTouch` **only** on the calibration JVM, where timing stability is the point;
on a several-hundred-GB run JVM it merely commits everything up front.

`LoadGovernor` should essentially never fire on this machine. **If it does, that is a signal to
investigate, not a condition to accept** — every leaf prints how far concurrency fell.

---

## What to keep

Everything below is small, and it is what makes a result reproducible rather than merely reported:

| | |
|---|---|
| `$DATA/logs/*.log` | every run's console output |
| `_calibration/compute_profiles.csv`, `compute_weights.properties` | the currency |
| `_calibration/compute_profiles_load<W>.csv`, `compute_weights_load<W>.properties`, and the **A3′ verdict block** | whether that currency survives contention |
| `_calibration/thread_calibration.csv` | the throughput curve and chosen `W` |
| `_portfolio/portfolio.csv`, `_split_dev.txt`, `_split_holdout.txt` | which scenarios, and which half |
| `$DATA/ab_results/*/SUMMARY.md` and `tables/` | the results |
| `git rev-parse HEAD` | the code that produced all of it |

---

## Restarting

Every runner resumes: re-run the **same command**. Finished leaves are skipped by checkpoint key,
partial leaves continue per (scenario, visit), and Ctrl-C is a cooperative stop that drains in-flight
work rather than losing it.

The one thing that is *not* safe is re-running against changed code with the old checkpoints in place:
a runner skips a listed leaf without re-checking what produced it. **Archive `$DATA/stage1/<split>/`
or delete its `_completed.txt` before any re-run that follows a code change.** A checkpoint written
before the portfolio split existed now aborts the run rather than silently mis-skipping, but that
guard only covers that one case.

Do not run two stages into the same output tree at once. They lock per split directory, but the
simplest way to lose an evening is to have two jobs interleaving in one `results.csv`.
