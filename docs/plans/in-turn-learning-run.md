# In-turn learning on the R7615: the run sheet

What to do on the R7615, in order, to run the in-turn learning screen
([`in-turn-learning.md`](in-turn-learning.md), section 6) in one long run, and what to read at each step.
Everything runs from the notebook, `notebooks/run_magat.ipynb`. The files it runs are committed before the
run starts:

- **The experiments:**
  - [`experiments/tribes/in-turn-screen.json`](../../experiments/tribes/in-turn-screen.json): the 29 new
    arms at 500 ms ×4, 1000 ms ×4 and 5000 ms ×3 on the development half. The reference and AB-EMCTS arms
    are taken from the published runs, not played.
  - [`experiments/tribes/in-turn-30s.json`](../../experiments/tribes/in-turn-30s.json): 14 arms at 30 s ×2
    on 100 positions of the development half.

  Each spec's description holds the rule by which the screen chooses what the confirmation tests.
- **The calibration:** seven sittings of one currency, `r7615-in-turn`, in
  [`calibrations/tribes/in-turn/`](../../calibrations/tribes/in-turn/). It starts as a copy of the
  published `r7615-ab-emcts` (`"from"`), which keeps every published price.
  - `published-1` to `published-3` price the 29 new arms at the published levels, 10, 10 and 9 at a
    time.
  - `30s-1` to `30s-4` price the 14 arms at 30 s, 4, 4, 3 and 3 at a time. That is a level the currency
    gains (`"gain-levels"`), measured on the same 16 positions as its others.

  Each sitting is one session that writes when it completes, so a failed one loses only its own arms.

None of these files is edited once the run starts. A change found to be needed is a deviation, reported with
its date and reason.

## What changed from the plan

Written before the run, for review:

1. **`emcts` joins the 13 arms at 30 s**, as the baseline. Section 5 says the baseline's ladder reaches 30 s,
   so every multiplier at 5000 ms becomes a measured one. That needs `emcts`'s own turns at 30 s. It costs
   one cell of calibration (about 30 minutes) and a few minutes of run. On the 30-second positions, its
   turns at the published levels are taken from the published reference run, so its ladder there is
   whole.
2. **The 30-second level is 610,000 units, fixed in advance:** 30 s at the published 5000 ms level's 20.3
   units per millisecond. It is not measured with `check time` before the night. A faithful measurement
   needs the level's own prices, so a calibration at it first: two to three hours with `calibrate-time`, or
   prices borrowed from another level, a few percent off. The night measures it anyway:
   - the calibration's `emcts@610000` cell times 49 single-threaded turns at the level's own prices;
   - the run's `emcts` turns at 30 s time 200 more.

   The report names the level by that measured time.
3. **The 100 positions at 30 s are the split's own rule applied to the development half.** That is
   `Split.deal` of the development half, its first half: 50 single-player and 50 dual positions, every
   stratum within one, and branching like the whole portfolio's (Kolmogorov-Smirnov distance 0.035).
   `InTurnNightTest` derives them again from the published portfolio.
4. **Reuse covers the AB-EMCTS run too.** Every experiment published with the repository is now a source
   of reusable leaves, after the reference one. The screen takes `comma-z0-coarse`, `comma-z0-random` and
   `emcts-comma` from the published AB-EMCTS run, and `random`, `mcts`, `emcts`, `oep` and `tbets` from the
   reference run, each after a spot replay. Only the 29 new arms are played at the published levels.
5. **The selection rule, made exact.** "Excludes zero" became "lies above zero": an arm that loses
   clearly is not a candidate. Where no arm of a group has such an interval, the confirmation keeps
   `comma-z0-coarse`'s own learner or mutation.
6. **The currency's checks run once, at the end.** With several sittings each runs without them
   (`--no-checks`). The checks against the published currency and against the new one then run once, after
   the last sitting. Run after every sitting, they would add several hours, since the new one's check
   plays `emcts` at 30 s.
7. **The gates come first, before any calibration.** The audit covers every arm the night plays, not only
   the reference arms. A gate that fails then stops the notebook before hours of measuring.
8. **The timetable is longer than the plan's 12.8 hours.** The 30-second calibration and the checks were
   underestimated (below).

## The timetable

| Step | What | Hours |
|---|---|---|
| 1 | setup, build, tests, and the gates (the contract; equivalence of every ported arm, the six in-turn oracles among them; the audit of the 36 priced arms) | 0.4 |
| 2-4 | sittings `published-1` to `published-3`: the 29 new arms at the published levels | 3.1 to 4.2 |
| 5 | the screen at the published levels: 29 arms played, 8 taken | 0.6 |
| 6-9 | sittings `30s-1` to `30s-4`: 14 arms at 30 s | 7.7 to 10.1 |
| 10 | the run at 30 s: 14 arms on 100 positions | 0.4 |
| 11 | the currency's checks, once, and the export | 1.1 |
| | **the night** | **13.3 to 16.8** |

- **The bounds.** The lower bound is the calibrator's own estimate: 49 turns per cell, a warm-up per level,
  two estimates for the stability check. The upper bound is that estimate scaled by the 1.36 the AB-EMCTS
  session took over it: its arms ran slower than the reference arm under the measurement weights.
- **Each sitting prints its own estimate** after its stability check, with the time it should finish.
- **The order is a priority list.** The published levels are calibrated and run first, about five hours
  in. A night cut short leaves only the 30-second part to finish, by running the experiments cell again.
- **Start it early in the afternoon,** to be safe at the upper bound.

## Before you start

- **The clone:** `git pull` to main, then `git submodule update --init` (the engine at `828b130`).
- **The machine:** as quiet as for the published calibration. Each session waits up to 30 minutes for
  other work to fall below 4 logical CPUs, and calls itself suspect above that.
- **`MAGAT_CURRENCY` unset,** so the currency is the configs' `r7615-in-turn`.

## 1. The notebook's parameters

```python
CALIBRATION_CONFIG = ["calibrations/tribes/in-turn/published-1.json",
                      "calibrations/tribes/in-turn/published-2.json",
                      "calibrations/tribes/in-turn/published-3.json"]
PUBLISHED_CURRENCY = "r7615-time"
EXPERIMENTS = ["experiments/tribes/in-turn-screen.json",
               ("calibrate", "calibrations/tribes/in-turn/30s-1.json"),
               ("calibrate", "calibrations/tribes/in-turn/30s-2.json"),
               ("calibrate", "calibrations/tribes/in-turn/30s-3.json"),
               ("calibrate", "calibrations/tribes/in-turn/30s-4.json"),
               "experiments/tribes/in-turn-30s.json"]
```

The plan cell then lists the seven sittings, and the two runs where they fall:
- `7 sittings -> the currency r7615-in-turn`;
- `sitting 4` to `sitting 7` as `measured by the experiments cell, here`, between `in-turn-screen` and
  `in-turn-30s`;
- `checks      : once, after the last sitting`.

## 2. Up to the calibration's preflight, then stop

Run the cells through the calibration preflight, then stop. On the way:

- **The gates:** `CONTRACT: PASS`, `EQUIVALENCE: PASS` and `AUDIT: PASS`. The audit lists the 36 priced
  arms of the two specs, each at most 25% of its turn unaccounted for, and names `random` as not audited:
  it spends no units. If a gate fails, stop there and bring back its
  log.
- **The preflight**, one block per sitting:
  - sitting 1: `# from      the published r7615-ab-emcts (sha256 b03a8d8fe827...), copied: the currency starts as it`;
  - sittings 2 to 7: `..., kept: it is that currency, extended`;
  - sittings 4 to 7: `[info  ] the currency gains level(s) 610000, priced on the same positions as its others`;
  - each: `It would measure one session pricing N arm(s): ..., merged into the currency by ratio.` (10,
    10, 9, 4, 4, 3, 3), and `PREFLIGHT: READY`, possibly with warnings to read.

**Bring the preflight's output back before measuring,** and we read it together.

## 3. The night

Run the measuring cell and every cell after it (in JupyterLab, *Run Selected Cell and All Below*). In
order:

1. **Sittings 1 to 3** (the measuring cell). Each prints its verdict, COMPLETE or SUSPECT. One that fails
   is named, and the others still run.
2. **The screen** (the experiments cell): `[run] REUSED` for the 8 taken arms at each level, then the 29 new
   ones played.
3. **Sittings 4 to 7**, in the same cell. The first gains the level: the currency's header becomes
   `levels = 9500,20000,101500,610000`.
4. **The run at 30 s.**
5. **The currency's checks, once:** against the published currency and against the new one, then the
   calibration bundled again.

An entry that fails does not stop the ones after it. The cell names what is not complete at the end.

## 4. In the morning

- **The calibration's verdicts:** `$HOME/magat_runs/calibration/tribes-r7615-in-turn/record/SUMMARY.md`,
  one record per sitting and one for the checks. Each should be COMPLETE, and both checks PASS.
- **The audit gate in the currency:** each new profile records its unaccounted share, at most 0.25:

  ```bash
  grep -E "^profile\.(comma-z0-(mid|fine|hier|linear|mlp)|comma-short3-coarse|mut-[a-z0-9-]+)@[0-9]+\.unpriced" $HOME/magat_runs/calibration/tribes-r7615-in-turn/compute_weights.properties
  ```
- **The two reports:** `$HOME/magat_runs/tribes/results/in-turn-screen/SUMMARY.md` and
  `.../in-turn-30s/SUMMARY.md`. Their spend tables hold the spend gate: a mean turn at most 1.10× its
  level.
- **If the night ran short,** run the experiments cell again. It skips finished leaves and written
  sittings, and continues a partial leaf where it stopped.

## 5. Export and bring back

Run the export cell and the inventory. Then download:

- the bundles the inventory lists: the calibration, the gates, `run-in-turn-screen`, `run-in-turn-30s` and
  the shakedown;
- the packages in `$HOME/magat_runs/exports/`, one per experiment.

The analysis, by the rule in the specs, is done from these files.
