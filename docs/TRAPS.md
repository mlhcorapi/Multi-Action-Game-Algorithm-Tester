# Traps

Each of these cost real time, in the study MAGAT grew out of or in building MAGAT itself. Each is now
prevented by a mechanism, which is the point of listing them: if you change one of those mechanisms,
this page says what it was holding back.

## Measuring

| Trap | What it cost | What prevents it now |
|---|---|---|
| One global weight set for every arm and budget | up to 73% mis-allocation at 400k: a nominal budget bought that much more work for one arm than another | prices per (arm, budget) ([currency](CURRENCY.md#the-unit)) |
| An algorithm's own work is free in units | arms that evaluate nothing bought 17-22% more wall-clock per unit than their baseline | every declared operation is priced; the audit catches undeclared ones; wall-clock is co-primary |
| Turns per repetition select the positions | a check on other positions reported a 55% drift that was not there, reproducibly | the turns and the portfolio identity are recorded in the weights file; a check or merge with different ones is refused |
| A merged arm written against the file's absolute reference | on a machine 58% slower, new arms priced 58% too high | re-denomination by ratio: a uniform speed difference cancels exactly |
| A laptop boosts while cool, and the reference cell is measured first | two sessions on one laptop measured the reference advance 14% apart | the bracket (the reference again at the end; ±10%); the stability check before the session |
| A drift gate per operation | failed a machine whose turn prices agreed within 4%, because evaluations, 3% of a turn, were 38% cheaper | the gate is each cell's turn price; per-operation drift is printed as information |
| A laptop on battery | ran at about a third of its speed (an 11.4-hour calibration against 3.7 plugged in) | the preflight refuses battery; the monitor stops the session the moment mains power is lost |
| A hybrid CPU's efficient cores | about half the speed of a performance core, and the scheduler may move the measured thread | the measuring JVMs are confined to the performance cores |
| A laptop's clock follows its temperature | the busiest core ran between 3.7 and 5.7 GHz within single toy sessions, with thermal zones up to 94 °C | a recommended frequency cap in a separate power plan; the clock and temperature recorded for the session and each check; the clock policy locked to the currency |
| One long calibration session | with 33 arms, one noisy cell, a late slowdown, or a culled Jupyter server discarded hours | v0.1 prices four algorithms, so one session is short enough to re-run whole; a currency that lacks an arm gets it in a session of its own, merged by ratio |
| The OS load average inside a container, or on Windows | describes the host, or nothing at all | `calibrate-controlled` measures other work by CPU accounting and names it |
| Automatic maintenance on an idle-looking machine | Windows starts scans and updates at 02:00 when nobody is typing, which an unattended calibration looks like | the preflight shows the maintenance time; the plan warns when a run will cross it |
| A budget that left the initialization outside it | EMCTS's greedy initial plan (600-3,200 units at a 20,000-unit level) ran unbudgeted: turns ran 3-18% over their level, and an algorithm with a costly initialization got that work free (2026-10-10) | the budget always pays for the initialization; only the equivalence gate counts as the engine's reference agents do |
| A search that finishes its unit of work past the budget | EMCTS kept the engine's rule, a child for every bridge burn, and its generations' greedy repairs cost thousands of units on large positions: on the R7615, `emcts` at 9,500 units spent 1.035× on average and up to 1.99× | EMCTS checks the budget inside a generation and abandons the one it runs out in (2026-10-25); only the equivalence gate plays the engine's rule |
| A currency extended across code generations | on the R7615 the pre-v0.1 currency (33 arms, measured 11 October) was still in its folder, so the v0.1 session priced its three new arms into it: 108 profiles from two code generations, where a fresh 12 were meant | the preflight warns when a currency prices arms this MAGAT does not register; to start fresh, move the whole folder aside or calibrate under another name |
| The calibrating JVM's core count taken for the machine | a calibration pins its JVMs, so its currency describes the R7615 as 16 cores, and the run's audit blamed "another machine" for its own 62 threads' contention | the audit compares CPU models |
| Levels measured from the main notebook | the notebook's levels cell ran `calibrate-time` on whatever machine ran it while no levels were published, and any machine could have re-measured them | the levels are published (`games/<game>/levels.json`); the main notebook only reads them, the measurement lives in `notebooks/maintainers/measure_levels.ipynb`, and `calibrate-time` refuses without `--remeasure` (2026-10-10) |
| A level with no time attached | units compared arms but said nothing about how long an arm had thought | levels are found by `calibrate-time` as 500, 1000 and 5000 ms of a whole turn on the R7615, averaged over positions, and published in `levels.json` |
| A thread sweep's memory gate fed the peak heap in use | the peak counts garbage awaiting collection, which grows with the heap, so in MAGAT's fixed 8 GB heap the R7615's sweep skipped its mid and upper thread counts (2026-10-09) | the gate uses the heap held after collections, as the load governor does (a worker holds 5-35 MB); the heavy commands get a heap sized to their workers; a point that runs out of memory ends the sweep with the points before it |
| A clone made without its submodules | the engine directory was empty, so javac reported a hundred missing packages (`core.actions`, `players.*`) instead of one missing engine (on the R7615, 2026-10-09) | the launcher checks every declared engine before compiling: it fetches a missing one in a git clone, and otherwise names what to fetch; an engine at another commit than the pinned one is reported |
| A currency written with the platform's line separator | on Windows the last merge's lines ended in CRLF and the rest in LF, so the file's hash, which a run's provenance records, depended on the machine that wrote it and on git's line-end conversion (found 2026-10-09) | the calibrator writes LF everywhere (a toy test checks it); `.gitattributes` keeps `*.properties` LF |
| A merge session measured under the prices of the file it extends | the reference cell played a different, shorter search than in the session that wrote the file, so the laptop's sections 2-4 were priced about 6% low (found 2026-10-07) | every cell, in every session and check, is measured under the fixed measurement weights; a toy test requires identical turns across sessions |

## Designing experiments

| Trap | What it cost | What prevents it now |
|---|---|---|
| Two arms producing identical output | read as thin data, when it was a bug | the runner stops on it; the analyzer refuses it |
| An alternate-draw split | 33/27 positions and a 22-point branching imbalance between the halves | matched pairs, stratified by source and phase |
| The crossing outside the budget ladder | an extrapolated multiplier is only a lower bound | the multiplier is flagged `extrapolated`; choose the ladder with the crossing inside |
| A search gain compared across arms | each arm's `initial` is its own starting plan, so the budget multiplier credited `random` and `mcts` with the whole turn and `emcts` only with what its search added to its greedy plan (found 2026-10-24) | the multiplier compares final values on the same cells |

## Running

| Trap | What it cost | What prevents it now |
|---|---|---|
| A Jupyter kernel's working directory is the notebook's folder | paths that worked in a terminal broke in the notebook | the setup cell finds the repository; `notebook selftest` runs it from three directories |
| A notebook cell for a calibration section the plan does not have | the cell exited 2 and read as a failed calibration, where nothing had failed | one cell measures the whole currency |
| A measuring cell that skipped itself because a currency file existed | on the R7615, a currency measured before v0.1, which priced `emcts` but not `mcts`, `oep` or `tbets`, was taken for complete, and the coverage check after it failed | the cell skips only once the currency prices every arm the calibration config names (every reference arm by default) at every level, and otherwise lets `calibrate-controlled` price what is missing; `notebook selftest` runs the cells on no currency, an incomplete one, a complete one, a config with its own arms and levels, and none |
| A notebook check that compared bytes | on the R7615 the shakedown called the notebook stale after the notebook had run: Jupyter had saved the cells' outputs into the file | a notebook that has been run is current when its cells are the generated ones and the commit holds the generated notebook byte for byte, so line endings and committed outputs are still caught; `notebook selftest` checks each case |
| Output inside the clone | a re-clone or a merge touched results | the data root is outside the clone by default; the launcher refuses one inside it, and a run's `--out` inside it |
| A relative path typed outside the repository | the JVM and the analysis run in the repository, so `--experiment my.json` typed in another directory named a file there, and an `--out` landed beside it | the launcher makes every path option absolute against the directory the command was typed in |
| The consumer did not read what the producer wrote | a weights pointer that was never wired, so a run spent the compiled defaults | no default weights path; `--weights` passed to every JVM; an unpriced profile is a hard error |
| A leaf priced in two currencies | a development cell continued after a merge changed its price | the per-leaf currency lock; `--retire-leaf` is the only way through |
| A run's commit rewritten by an invocation that played nothing | on the R7615 a second clone resumed a finished run, and `_run.json` named its commit for turns the first clone had played | the tree keeps the commit that started it; each invocation and each leaf record their own, and a resume under another commit says so |
| Deleting a leaf to re-run it | history lost | `--retire-leaf`, logged in `_run.json`; resume by re-running the same command |
| Another machine's thread choices | the R7615's published currency carries its `threads.json`, so a run on the laptop in that currency would have started 62 workers on 32 logical CPUs (found 2026-11-13, before any run did) | a `threads.json` is used only on the CPU model it was swept on |
| Another machine's times in a run that reused its turns | the wall-clock re-score would have set the R7615's seconds for the reused baseline against this machine's for the arm it played (found 2026-11-14, before any run did) | a reused leaf's times are left out of every time measure, and the report lists the reused leaves |
| An import that failed its checks, taken from | a package whose turns were changed and its hashes recomputed passes the integrity check, and a leaf's two sampled turns can miss the changed one, so reuse would have taken it (found 2026-11-14, before any run did) | only a `VERIFIED` import is a source for reuse; the report check derives the paired means again from every turn |
| A resume that selects other positions | a tree would mix turns from two selections of the portfolio | the run records its selection in `_run.json`, and a resume that selects otherwise aborts |
| An exception escaping a scenario source read as "no captures" | could change the portfolio without a word | anything escaping `play` stops the build |
| Serialized positions are not byte-identical between JVM runs | hash maps iterate in identity-hash order, so bins differ | `portfolio verify --plan` compares plans from both sets of bins |
| Line endings change a file's hash | a hash recorded on one platform did not match the file on another | `.gitattributes` keeps CSV, JSON and specs LF; outputs are written LF |
| One bootstrap stream for every table | adding an arm moved every other interval | each row's generator is seeded by the row's identity |
| A terminal sentinel inside a mean | a plan that ends the game scores about 2×10⁹, so on the R7615 one missed win moved an arm's mean by 625,000, and two missed wins turned `tbets`'s verdict at 9,500 into "wall-clock only" | the headline, contrasts and design are taken on the pairs where neither plan ends the game, and the plans that do are counted |
| Re-pricing taken for re-running | the search stopped under the weights it ran with | every re-priced table says so |

## Porting

| Trap | What it cost | What prevents it now |
|---|---|---|
| Trusting a port | a re-implementation that differs in one random draw is a different algorithm | the oracle gate, field for field, counters included |
| A copy the original makes and never reads, left out of the port | every Tribes copy draws from the state's random generator, so the `emcts` port, which left out the engine's two unread copies, seeded every later copy otherwise: it passed its gate's 32 turns, but failed 12 of 128 on 64 positions where a chance event came out otherwise (found 2026-10-24) | the ports make every copy their originals make; `deadInitCopies` is only for a copy that draws nothing |

## The machine you develop on

| Trap | What it cost | What prevents it now |
|---|---|---|
| A repository in OneDrive | locked build directories mid-build | the launcher retries, then moves stale output aside; `MAGAT_BUILD` moves it out |
| A case-insensitive filesystem | a `magat/` code directory collided with `MAGAT/` docs | the code root is `core/` |
| PATH's `java` is a shim that starts the real JVM | process controls applied to the shim only | the tool follows the launcher's descendants and tunes every JVM |
| `typeperf` cannot write past 260 characters | a deep record path produced no counters | the counters are written to a short temporary path and moved |
| A worktree can disappear | uncommitted work lost | commit small and often; every milestone ends in a commit and a green shakedown |
