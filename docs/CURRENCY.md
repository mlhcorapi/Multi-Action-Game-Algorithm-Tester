# The currency

A budget in MAGAT is a number of **compute units**, not seconds and not iterations. This page says
what a unit is, how a machine prices one, what transfers between machines, and what does not.

## Why not seconds, iterations or FLOPs

- **Wall-clock depends on the machine and the moment.** A budget in seconds makes every result
  machine-specific, and lets a busy afternoon change it.
- **Iterations reward whoever makes the cheapest iteration.** Two algorithms with the same iteration
  count can do very different amounts of work.
- **FLOPs are the wrong count.** A game's forward model is dominated by memory traffic, branching,
  allocation and garbage collection, not floating-point arithmetic. FLOP counts are hard to obtain and
  nearly uncorrelated with the cost that matters, and instruction counts fare little better under a
  JIT.

MAGAT's unit is an **operation count, priced**. The counts are exact on every machine; each machine
prices them in its own time.

## The unit

> **One compute unit is one `advance` by the reference arm at the reference budget on the game's
> reference portfolio, on the machine the weights were calibrated on.**

The reference cell is the reference arm at the currency's smallest level: for Tribes, `emcts@9500`.
Every price is a ratio to that operation's nanoseconds:
- `advance`, `copy` and `evaluate`, the forward model's three operations;
- every operation an algorithm declares of its own.

Prices are set **per (arm, budget)**: the same operation name costs different amounts for different
arms, which reach different states, and at different budgets, where a ten-times larger tree stops
fitting in cache. One weight set for every arm and budget would mis-allocate work between them
([`TRAPS.md`](TRAPS.md)).

Budget levels are fixed unit counts and are never re-derived per machine. That is what keeps a search
deterministic given its counts, and `check replay` exact.

## Units as time

A level is chosen so that it is a round amount of **thinking time on the reference machine**: 500, 1000
and 5000 ms of a whole turn, single-threaded on the R7615, for Tribes. Because every arm is priced per
(arm, budget), a level's units buy the same time for every arm, so a result at a level reads: *each arm
thought for this long, single-threaded, on the R7615*. That holds on any machine that spends the same
currency, whatever its own speed, because a budget is a count of work.

`magat calibrate-time` finds the levels, on the reference machine. It times the reference arm's whole
turns under its own prices at guessed levels, rescales until each level's mean is within 5% of its
time, and rounds to the nearest 500 units. The result is published as `games/<game>/levels.json`
(units per level, the measured mean, median, quartiles and range, and the machine and engine), and the
launcher then uses those levels wherever no `--levels` is named. `magat check time` reports any arm's
time at any level under any currency, and its mean spend against the level.

For Tribes, measured on the R7615 on 2026-10-10 over 64 positions:

| Time | Units | Measured mean | Median | Quartiles | Initialization |
|---|---|---|---|---|---|
| 500 ms | 9,500 | 506 ms | 389 ms | 276-569 ms | 160 ms |
| 1000 ms | 20,000 | 1,006 ms | 796 ms | 583-1,190 ms | 158 ms |
| 5000 ms | 101,500 | 5,078 ms | 4,163 ms | 3,059-6,077 ms | 159 ms |

A unit is about 50 µs of an `emcts` turn there. At 500 ms, EMCTS's initial plan takes about 2,000 of the
9,500 units and a third of the time. Measuring the levels again is a maintainer's decision, because every
currency must then be measured again (`notebooks/maintainers/measure_levels.ipynb`).

**The time is a mean over positions.** Units make arms comparable, not positions: a turn's time per
unit depends on its position several-fold (12 to 68 µs over 40 Tribes positions on the laptop, half
of them between 24 and 44), mostly through the number of units on the board, whose moves the engine
recomputes after every advance. So a level is its time on average over positions spread across the
portfolio, and a single turn may take a third of it or twice it. A real time budget would make every
turn take the same time, and arms would then do different amounts of work on the same position; units
keep the work fair and the time true on average.

**The initialization is paid from the budget.** A turn's spend is everything it does: EMCTS's greedy
initial plan (a copy, an advance and an evaluation for each legal action at each step, 600 to 3,200 units
at a 20,000-unit level on Tribes) and then the search, which gets what the initialization left. Paid
outside the budget, the initialization would let turns run 3-18% over their level, and would give an
algorithm with a costly initialization that work free. The one exception is the equivalence gate
(`check equivalence`), which counts as the engine's reference agents do, so that a port and its original
can be compared turn for turn.

**Each game has its own currency.** One advance costs wildly different things in different engines,
so raw units are never compared across games. Budget multipliers are dimensionless, and those are.

## The weights file

`compute_weights.properties`, a `java.util.Properties` file both sides read natively. Its header names
the machine, the levels, the reference cell, the portfolio and the operations it prices:

```
magat.weights.version = 2
game = tribes
reference = emcts@9500
referenceAdvanceNanos = 57825.133
levels = 9500,20000,101500
turnsPerRep = 16,16,16
portfolio.identity = ec243fd7...
operations = advance,copy,eval,emcts.generation,emcts.legal,emcts.check,emcts.seqkey,emcts.burn
global.w.advance = 1.000000
...
```

`global.w.<op>` holds the reference cell's prices. Each profile has these keys:

```
profile.<arm>@<budget>.w.<op> = ...    the price of one occurrence, as a ratio to the reference advance
profile.<arm>@<budget>.ns.<op> = ...   the measured nanoseconds on this machine
profile.<arm>@<budget>.unpriced = ...  the calibration audit's share of the cell's turn time left unpriced
```

MAGAT reads version 2 and refuses any other file with a message. The file records the portfolio
identity and the turns per repetition, because those select the positions a calibration measures: a
check on other positions would report drift that is not there.

## Why every operation is priced

The forward model is not all an algorithm does: tree bookkeeping, ranking candidates and legal-action
queries take time too. If that work were free in compute units, an arm that does more of it would get
more wall-clock per unit than its baseline. So each arm declares its operations, the meter counts and
times them, the calibrator prices them, and the stop rule spends them. On Tribes the declared
operations and the forward model account for all but 0.1-0.4% of a turn of each reference algorithm.
The largest of an arm's own operations is the legal-action query, about 1.5 advances each: over 40% of
an `oep` or `tbets` turn.
[`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md) says how to declare them.

## Calibrating

`magat calibrate` measures cells `(arm, budget)`:
- every cell on the same portfolio positions, with the same seeds;
- a warm-up per budget level, and one discarded turn per cell;
- per repetition the same turns again, with counts taken from each turn's own record;
- **the fastest repetition** as the estimate, since interference can only slow a repetition down;
- every cell played under the same **fixed measurement weights**: advance 1, copy 0.16, eval 0.077, the
  arms' own operations free. This holds whatever the file being extended or checked already prices. The
  budget's stop rule decides which turn is played, so this makes every session, merge and check measure
  the same turns.

Its gates, all enforced:
- the two fastest repetitions agree within 10% for every operation that is at least 1% of a turn;
- enough operations were counted (`MIN_OPS`);
- every weight is plausible;
- **the bracket**: the reference cell is measured again at the end of the session and must not have
  moved by more than 10%. Every weight is a ratio to the first measurement, and a laptop that boosts
  while cool can measure its reference advance well away from the rest of the session.

A session that fails any gate writes nothing. Its measurements still go to `compute_profiles.csv`, and
to `compute_operations.csv` per operation. An audit prints how much of each cell's turn time the
priced operations account for, and warns above 10%. A spend check prints each cell's mean turn,
initialization included, against its level, and flags a cell above 1.10× it (`SPEND:`): such a turn
thinks longer than its level says, usually because an initialization cannot stop when the budget runs
out. Neither fails a calibration.

`magat calibrate --estimate` times one repetition of the reference cell per level and prints what
the session would take; it writes nothing. For a calibration you can trust,
`magat calibrate-controlled` runs the same calibrator in one session, with a preflight, a stability check,
a monitor and a reliability verdict: see [`CALIBRATING_A_MACHINE.md`](CALIBRATING_A_MACHINE.md).

### Merging: re-denomination by ratio

Writing into an existing file **merges**. New cells are priced as this session's ratio to this
session's own reference measurement, times the file's stored reference weight:
- a machine uniformly 0.6× or 1.6× as fast cancels exactly;
- what does not cancel is a change in the ratios themselves, which is what the drift check reports.

A profile already in the file is refused unless `--replace` names it: re-pricing an arm changes the
currency of every leaf already run under it.

### The drift check

`magat calibrate --check --arms emcts` re-measures arms the file already prices and writes nothing. Its
gate is each cell's **turn price**: the cell's own operation counts priced now against priced as
stored, within 10%. Per-operation drift is printed beside it as information. An operation that is 3%
of a turn can drift 40% relative to an advance and move the turn's price by about 1%, which merges and
compares fine. A 10% move in a turn's price would re-size every budget of that arm.

## What transfers between machines

| | Transfers? | How |
|---|---|---|
| Operation counts per turn | **exactly** | every `turns.csv` row carries them (`advances`, `copies`, `evals`, `op_<name>`) |
| The search itself | exactly, given the same weights | a deterministic count budget, `Seeds.visit`, `reseed`, a rebuilt portfolio. `magat check replay` re-plans published turns and compares them field for field |
| The weights | approximately | the published file plus `calibrate --check`. Ratios were stable within about 6% across sessions on the R7615 |
| Wall-clock | no, but always reported | `plan_ms` in every row; the wall-clock re-score uses it |
| Budget multipliers | yes | they are dimensionless |

Because every result publishes its raw counts, anyone can **re-price** it with their own weights:
`magat analyze --reprice <weights>` recomputes every turn's units and re-scores every arm against
the baseline in that currency. What re-pricing cannot do is re-run the stop rule: the search stopped
under the weights it ran with, and the report says so. A leaf is re-priced only by a file that prices
exactly the operations it counted.

`magat analyze --run A --compare B` sets two machines' runs of one experiment side by side: each machine's
mean values and their paired difference, both budget multipliers, each machine's seconds per turn, and A's
turns re-priced in B's currency.

## Published currencies

Tribes' currency, measured on the R7615 at the published levels, will be published in
`games/tribes/calibration/<name>/`, with an `ORIGIN.md` giving its provenance. Until then, every
command that spends a currency takes `--weights`, and `calibrate-controlled` makes one for your
machine. A new calibration is published beside the others under its own name, never over one, and
results quote the currency they were priced in.

**A currency describes the costs of the engine it was measured on.** A budget is a count, so a result
reproduces exactly on any engine commit with the same outputs, but its prices describe the engine it
was measured on. When the Tribes engine's legal-action generation became faster without changing any
output (engine `828b130`), an advance on the development laptop became about 19% faster while a copy
and an evaluation did not. Measure a currency on the engine commit the runs use.

A run tree records the price of every leaf. Continuing a tree under a file in which a leaf's price has
changed is refused (the **currency lock**) until `--retire-leaf` names that leaf to be re-run. A merge
that only adds arms changes no leaf's price.
