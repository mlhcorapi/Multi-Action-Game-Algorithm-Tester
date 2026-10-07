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

The AB-EMCTS line worked because its unit was an **operation count, priced**.

## The unit

> **One compute unit is one `advance` by the reference arm at the reference budget on the game's
> reference portfolio, on the machine the weights were calibrated on.**

For Tribes the reference cell is `emcts@40000`. Every price is a ratio to that operation's
nanoseconds:
- `advance`, `copy` and `evaluate`, the forward model's three operations;
- under currency version 2, every operation an algorithm declares of its own.

Prices are set **per (arm, budget)**: the same operation name costs different amounts for different
arms, which reach different states, and at different budgets, where a ten-times larger tree stops
fitting in cache. One global weight set mis-allocated up to 73% at 400k in the thesis.

Budget levels are fixed unit counts (40,000 / 80,000 / 400,000 for Tribes) and are never re-derived
per machine. That is what keeps a search deterministic given its counts, and `check replay` exact.

**Each game has its own currency.** One advance costs wildly different things in different engines,
so raw units are never compared across games. Budget multipliers are dimensionless, and those are.

## The weights file

`compute_weights.properties`, a `java.util.Properties` file both sides read natively.

**Version 1** prices the forward model only:

```
magat.weights.version = 1
game = tribes
reference = emcts@40000
referenceAdvanceNanos = 62870.569
turnsPerRep = 8,4,2
portfolio.identity = ec243fd7...
levels = 40000,80000,400000
profile.emcts@40000.wAdvance = 1.000000
profile.emcts@40000.wCopy = ...
profile.emcts@40000.wEval = ...
```

**Version 2** prices every operation. Its header adds
`operations = advance,copy,eval,emcts.generation,...`, and `global.w.<op>` holds the reference cell's
prices. Each profile has these keys:

```
profile.<arm>@<budget>.w.<op> = ...    the price of one occurrence, as a ratio to the reference advance
profile.<arm>@<budget>.ns.<op> = ...   the measured nanoseconds on this machine
profile.<arm>@<budget>.unpriced = ...  the calibration audit's share of the cell's turn time left unpriced
```

Readers accept both. Under a version-1 file every algorithm operation weighs exactly 0.0, so a
version-1 result reads, prices and replays as it always did. The file records the portfolio identity
and the turns per repetition, because those select the positions a calibration measures: a check on
other positions reports drift that is not there. Measured, that was a 55% phantom drift.

## Why version 2

Under version 1 an algorithm's own work is free in compute units: tree bookkeeping, a learned
table's updates and lookups, ranking candidates, legal-action queries. So an arm that does more of it
gets more wall-clock per unit than its baseline. In the worked result the zero-evaluation arms spent
17-22% more wall-clock per unit, which is why the wall-clock re-score (W7) had to be co-primary.

Under version 2, each arm declares its operations, the meter counts and times them, the calibrator
prices them, and the stop rule spends them. On Tribes the declared operations and the forward model
account for all but 0.1-0.7% of a turn. The arms' own operations are 2-15% of it, the largest being
the legal-action query: one costs about 1.5 advances.
[`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md) says how to declare them.

## Calibrating

`magat calibrate` measures cells `(arm, budget)`:
- every cell on the same portfolio positions, with the same seeds;
- a warm-up per budget level, and one discarded turn per cell;
- per repetition the same turns again, with counts taken from each turn's own record;
- **the fastest repetition** as the estimate, since interference can only slow a repetition down.

Its gates, all enforced:
- the two fastest repetitions agree within 10% for every operation that is at least 1% of a turn;
- enough operations were counted (`MIN_OPS`);
- every weight is plausible;
- **the bracket**: the reference cell is measured again at the end of the session and must not have
  moved by more than 10%. Every weight is a ratio to the first measurement, and a laptop that boosts
  while cool once measured its reference advance 14% away from the rest of the session.

A session that fails any gate writes nothing. Its measurements still go to `compute_profiles.csv`, and
to `compute_operations.csv` per operation. An audit prints how much of each cell's turn time the
priced operations account for, and warns above 10%.

`magat calibrate --estimate` times one repetition of the reference cell per level and prints what
the session would take; it writes nothing. For a calibration you can trust,
`magat calibrate-controlled` runs the same calibrator in sections, with a stability check, a monitor
and a reliability verdict per section: see [`CALIBRATING_A_MACHINE.md`](CALIBRATING_A_MACHINE.md).

### Merging: re-denomination by ratio

Writing into an existing file **merges**. New cells are priced as this session's ratio to this
session's own reference measurement, times the file's stored reference weight:
- a machine uniformly 0.6× or 1.6× as fast cancels exactly;
- what does not cancel is a change in the ratios themselves, which is what the drift check reports.

The thesis code divided absolute nanoseconds by the file's stored reference instead. On a machine (or
an afternoon) 58% slower, that priced the new arms 58% too high. A profile already in the file is
refused unless `--replace` names it: re-pricing an arm changes the currency of every leaf already run
under it.

### The drift check

`magat calibrate --check --arms emcts,emcts-comma` re-measures arms the file already prices and writes
nothing. Its gate is each cell's **turn price**: the cell's own operation counts priced now against
priced as stored, within 10%. Per-operation drift is printed beside it as information. An operation
that is 3% of a turn can drift 40% relative to an advance and move the turn's price by about 1%,
which merges and compares fine. A 10% move in a turn's price would re-size every budget of that arm.

## What transfers between machines

| | Transfers? | How |
|---|---|---|
| Operation counts per turn | **exactly** | every `turns.csv` row carries them (`advances`, `copies`, `evals`, `op_<name>`) |
| The search itself | exactly, given the same weights | a deterministic count budget, `Seeds.visit`, `reseed`, a rebuilt portfolio. `magat check replay` re-plans published turns and compares them field for field |
| The weights | approximately | the published file plus `calibrate --check`. Ratios were stable within about 6% across sessions on the R7615; a cache-light arm moved 24% under load |
| Wall-clock | no, but always reported | `plan_ms` in every row; W7 re-scores against it |
| Budget multipliers | yes | they are dimensionless |

Because every result publishes its raw counts, anyone can **re-price** it with their own weights:
`magat analyze --reprice <weights>` recomputes every turn's units and re-scores every arm against
the baseline in that currency. What re-pricing cannot do is re-run the stop rule: the search stopped
under the weights it ran with, and the report says so. A leaf is re-priced only by a file that prices
exactly the operations it counted.

## The published currency, and others

`games/tribes/calibration/r7615/` is Tribes' published currency. It was measured on 2026-08-28 on a
Dell PowerEdge R7615 (AMD EPYC 9534, 64 cores, JDK 11) by the thesis's calibrator, then converted to
MAGAT's version-1 format. `ORIGIN.md` there has the provenance and the name mapping, the drift checks
of other machines against it, and the metering overhead. A new calibration is published beside it
under its own name, never over it. Examples are a version-2 calibration of the R7615 itself, or the
development laptop's. Results quote the currency they were priced in.

A run tree records the price of every leaf. Continuing a tree under a file in which a leaf's price has
changed is refused (the **currency lock**) until `--retire-leaf` names that leaf to be re-run. A merge
that only adds arms changes no leaf's price.

`MAGAT/DESIGN.md` §6 and §8 are the full design, with every amendment and the reason for it.
