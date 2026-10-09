# Traps

Each of these cost real time, in the thesis line MAGAT is extracted from or in building MAGAT itself.
Each is now prevented by a mechanism, which is the point of listing them: if you change one of those
mechanisms, this page says what it was holding back.

## Measuring

| Trap | What it cost | What prevents it now |
|---|---|---|
| One global weight set for every arm and budget | up to 73% mis-allocation at 400k: a nominal budget bought that much more work for one arm than another | prices per (arm, budget) ([currency](CURRENCY.md#the-unit)) |
| An algorithm's own work is free in units | the zero-evaluation arms bought 17-22% more wall-clock per unit than the baseline | wall-clock is co-primary (W7); currency version 2 prices declared operations; the audit catches undeclared ones |
| Turns per repetition select the positions | a check on other positions reported a 55% drift that was not there, reproducibly | the turns and the portfolio identity are recorded in the weights file; a check or merge with different ones is refused |
| A merged arm written against the file's absolute reference | on a machine 58% slower, new arms priced 58% too high (the ×1.5820 merge) | re-denomination by ratio: a uniform speed difference cancels exactly |
| A laptop boosts while cool, and the reference cell is measured first | two sessions on one laptop measured the reference advance 14% apart | the bracket (the reference again at the end; ±10%); the stability check before each section |
| A drift gate per operation | failed a machine whose turn prices agreed within 4%, because evaluations, 3% of a turn, were 38% cheaper | the gate is each cell's turn price; per-operation drift is printed as information |
| A laptop on battery | ran at about a third of its speed (an 11.4-hour calibration against 3.7 plugged in) | the preflight refuses battery; the monitor stops a section the moment mains power is lost |
| A hybrid CPU's efficient cores | about half the speed of a performance core, and the scheduler may move the measured thread | the measuring JVMs are confined to the performance cores |
| A laptop's clock follows its temperature | the busiest core ran between 3.7 and 5.7 GHz within single toy sessions, with thermal zones up to 94 °C | a recommended frequency cap in a separate power plan; the clock and temperature recorded per section; the clock policy locked to the currency |
| One long calibration session | one noisy cell, a late slowdown, or a culled Jupyter server discards hours | sections of 8 arms that merge by ratio; any section can be re-run |
| The OS load average inside a container, or on Windows | describes the host, or nothing at all | the load harness gates on its own work; `calibrate-controlled` measures other work by CPU accounting and names it |
| Automatic maintenance on an idle-looking machine | Windows starts scans and updates at 02:00 when nobody is typing, which an unattended calibration looks like | the preflight shows the maintenance time; the plan warns when a run will cross it |
| A currency written with the platform's line separator | on Windows the last merge's lines ended in CRLF and the rest in LF, so the file's hash, which a run's provenance records, depended on the machine that wrote it and on git's line-end conversion (found 2026-10-09) | the calibrator writes LF everywhere (a toy test checks it); `.gitattributes` keeps `*.properties` LF, except the R7615 import, which stays as committed |
| A merge session measured under the prices of the file it extends | the reference cell played a different, shorter search than in the session that wrote the file, so the laptop's sections 2-4 were priced about 6% low (found 2026-10-07) | every cell, in every session and check, is measured under the fixed measurement weights; a toy test requires identical turns across sessions |
| The decorator times operations differently from the engine's own hooks | +3.3% on the evaluation weight, against a currency calibrated through the hooks | measured by `check overhead` and recorded in `ORIGIN.md` |

## Designing experiments

| Trap | What it cost | What prevents it now |
|---|---|---|
| A parameter grid without its null | the champion sat at ε = 1.00, outside the grid, and was found only because one arm happened to have the bias off | the validator refuses a sweep without its null |
| A mechanism credited against the baseline instead of against noise | a throughput gain could pass for a learning gain | attribution controls in the grid, with the same random draws ([methods](METHODS.md#5-grids-carry-their-nulls-and-their-controls)) |
| Two arms producing identical output | read as thin data, when it was a bug | the runner stops on it; the analyzer refuses it |
| Two conceptually distinct parameters tied by a helper | a follow-up stage to untie insertion from repair granularity | separate parameters in `QdeltaParams` |
| An alternate-draw split | 33/27 positions and a 22-point branching imbalance between the halves | matched pairs, stratified by source and phase |
| The holdout read more than once | the winner's curse comes back | the holdout ledger; `--frozen` is mandatory |
| Gates written after the data | a gate fitted to the result is a story | the spec's pre-registration file, hashed into provenance |
| The crossing outside the budget ladder | an extrapolated multiplier is only a lower bound | the multiplier is flagged `extrapolated`; choose the ladder with the crossing inside |

## Running

| Trap | What it cost | What prevents it now |
|---|---|---|
| A Jupyter kernel's working directory is the notebook's folder | paths that worked in a terminal broke in the notebook | the setup cell finds the repository; `notebook selftest` runs it from three directories |
| Output inside the clone | a re-clone or a merge touched results | the data root is outside the clone by default; the launcher refuses one inside it |
| The consumer did not read what the producer wrote | a weights pointer that was never wired, so a run spent the compiled defaults | no default weights path; `--weights` passed to every JVM; an unpriced profile is a hard error |
| A leaf priced in two currencies | a development cell (`comma-short15-coarse@400000`) continued after a merge changed its price | the per-leaf currency lock; `--retire-leaf` is the only way through |
| Deleting a leaf to re-run it | history lost | `--retire-leaf`, logged in `_run.json`; resume by re-running the same command |
| An exception escaping a scenario source read as "no captures" | could change the portfolio without a word | anything escaping `play` stops the build |
| Serialized positions are not byte-identical between JVM runs | hash maps iterate in identity-hash order, so bins differ | `portfolio verify --plan` compares plans from both sets of bins |
| Line endings change a file's hash | a hash recorded on one platform did not match the file on another | `.gitattributes` keeps CSV, JSON and specs LF; outputs are written LF |
| One bootstrap stream for every table | adding an arm moved every other interval | each row's generator is seeded by the row's identity |
| Re-pricing taken for re-running | the search stopped under the weights it ran with | every re-priced table says so |

## Porting

| Trap | What it cost | What prevents it now |
|---|---|---|
| Trusting a port | a re-implementation that differs in one random draw is a different algorithm | the oracle gate, field for field, counters included |
| Initialization work the original does and never uses | the thesis agent makes two dead copies; a faithful port does not | the oracle declares them (`deadInitCopies`) and the gate expects exactly that offset |
| The thesis counted initialization as forward-model work | its `fmCalls` are not MAGAT's `advances` | imported trees leave the count columns blank rather than mislabel them |

## The machine you develop on

| Trap | What it cost | What prevents it now |
|---|---|---|
| A repository in OneDrive | locked build directories mid-build | the launcher retries, then moves stale output aside; `MAGAT_BUILD` moves it out |
| A case-insensitive filesystem | a `magat/` code directory collided with `MAGAT/` docs | the code root is `core/` |
| PATH's `java` is a shim that starts the real JVM | process controls applied to the shim only | the tool follows the launcher's descendants and tunes every JVM |
| `typeperf` cannot write past 260 characters | a deep record path produced no counters | the counters are written to a short temporary path and moved |
| A worktree can disappear | uncommitted work lost | commit small and often; every milestone ends in a commit and a green shakedown |
