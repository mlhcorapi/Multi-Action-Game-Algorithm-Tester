# One experiment, two machines

MAGAT budgets a turn in compute units: a priced count of the work an algorithm does, calibrated on each
machine so that a unit buys the same wall-clock of every algorithm's work there. The claim this page
tests is the one v0.1 is built on: **researchers on different machines, each with their own currency, get
the same results.**

The reference experiment (`experiments/tribes/reference.json`) ran on two very different machines:

| | Reference machine | Second machine |
|---|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534, 64 cores | HP OMEN 16 laptop, Intel Core i9-14900HX, 8 + 16 cores |
| System | Linux container (JupyterHub), Java 11 | Windows 11, Java 26 |
| Currency | [`r7615-time`](../../games/tribes/calibration/r7615-time/ORIGIN.md) | [`laptop-i9-14900hx-time`](../../games/tribes/calibration/laptop-i9-14900hx-time/ORIGIN.md) |
| One unit | 55,341 ns | 45,828 ns: about 1.2× faster |
| Run | [`r7615`](../../games/tribes/results/reference/r7615/README.md), 62 workers | [`laptop-i9-14900hx`](../../games/tribes/results/reference/laptop-i9-14900hx/README.md), 15 workers |

Each machine calibrated its own currency at the published levels (9,500, 20,000 and 101,500 units), then
ran the four reference algorithms and the random floor on all 400 positions with the same visits and
seeds: 44,000 turns each. The full comparison is
[`r7615-vs-laptop-i9-14900hx/SUMMARY.md`](../../games/tribes/results/reference/r7615-vs-laptop-i9-14900hx/SUMMARY.md).

## The results agree

The paired difference against `emcts`, on the pairs where neither plan ends the game, with 95% intervals,
and the budget multiplier (how much budget `emcts` would need to match the arm; `≥` a lower bound):

| Level | Arm | R7615 | Laptop | R7615 multiplier | Laptop multiplier |
|---|---|---|---|---|---|
| 9,500 | `oep` | +71 [+42, +100] | +55 [+25, +84] | 2.4× | 2.0× |
| 9,500 | `tbets` | +58 [+29, +87] | +48 [+17, +77] | 2.1× | 1.8× |
| 9,500 | `mcts` | −147 [−178, −119] | −149 [−180, −120] | 0.1× | 0.2× |
| 20,000 | `oep` | +163 [+133, +194] | +149 [+119, +179] | ≥6.8× | ≥5.9× |
| 20,000 | `tbets` | +86 [+57, +115] | +75 [+45, +103] | 2.7× | 2.4× |
| 20,000 | `mcts` | −177 [−209, −146] | −180 [−212, −149] | 0.1× | 0.1× |
| 101,500 | `oep` | +146 [+117, +174] | +144 [+114, +172] | ≥5.8× | ≥5.6× |
| 101,500 | `tbets` | +115 [+86, +144] | +108 [+78, +137] | ≥4.0× | ≥3.7× |
| 101,500 | `mcts` | −255 [−290, −221] | −255 [−292, −220] | <0.1× | <0.1× |

- The same ranking at every level on both machines: `oep` > `tbets` > `emcts` > `mcts` > `random`.
- Every difference between the machines lies inside the other machine's interval, and so does every
  multiplier.
- Per arm, on the same positions, the two machines' mean plan values (about 6,200) agree within 3 points for
  `emcts`, `mcts` and `random`, and within 3-16 points (0.26% at most) for `oep` and `tbets`.

## Position by position

The essence of MAGAT is the portfolio, so the comparison goes below the means: the same position, visit and
seed on both machines.

| Level | Arm | Same turn on both | Correlation of values | Mean gap | Advantage over `emcts`: correlation across positions | Same sign |
|---|---|---|---|---|---|---|
| 9,500 | `mcts` | 95.2% | 1.0000 | 2.4 | 0.995 | 94.9% |
| 9,500 | `oep` | 74.8% | 1.0000 | 16.4 | 0.992 | 94.5% |
| 9,500 | `tbets` | 82.5% | 1.0000 | 10.4 | 0.993 | 96.1% |
| 9,500 | `emcts` | 74.0% | 0.9999 | 21.6 | | |
| 20,000 | `mcts` | 97.1% | 1.0000 | 0.7 | 0.985 | 93.7% |
| 20,000 | `oep` | 73.5% | 1.0000 | 11.8 | 0.982 | 94.0% |
| 20,000 | `tbets` | 82.2% | 1.0000 | 9.2 | 0.981 | 93.2% |
| 20,000 | `emcts` | 47.4% | 0.9997 | 61.5 | | |
| 101,500 | `mcts` | 98.5% | 1.0000 | 0.7 | 0.973 | 93.8% |
| 101,500 | `oep` | 94.4% | 1.0000 | 2.7 | 0.960 | 93.3% |
| 101,500 | `tbets` | 85.4% | 1.0000 | 6.6 | 0.955 | 91.9% |
| 101,500 | `emcts` | 39.3% | 0.9994 | 97.2 | | |

`random` played every turn identically, and every turn that ended the game did so on both machines. The two
machines named the same best algorithm on 91%, 92% and 93% of positions at the three levels. An arm's
advantage over `emcts` varies across positions with a standard deviation of about 300, against a mean gap
between the machines of 17 to 51 per position.

## Why the machines differ at all

A unit buys the same wall-clock of every algorithm's work on its own machine. Machines differ in which
operations they find relatively expensive, so the same budget buys slightly different work on each. The
analysis measures this by pricing the R7615's turns at the laptop's prices:

| | `emcts` | `mcts` | `oep` | `tbets` |
|---|---|---|---|---|
| R7615 turns at the laptop's prices | 0.99-1.00× | 1.00-1.03× | 1.07-1.09× | 1.08-1.09× |

The laptop finds `oep`'s and `tbets`'s work 7-9% dearer than the R7615 does, so a budget buys them about 8%
less work there. Two mechanisms carry that into the values:
- **`mcts`, `oep` and `tbets` search a prefix of the same search.** Same seed, same draws: a smaller budget
  stops the same search earlier, so a turn either comes out identical or the machine that spent more found a
  better plan. Their gaps all point one way, and shrink with the budget as returns diminish.
- **`emcts` changes path.** Its 21 phases are sized from the budget, so a 1% difference in prices moves every
  phase boundary and bridge burn, and the search goes elsewhere. Its turns diverge more, and further at
  larger budgets, but in both directions: its mean value differs between the machines by 0.1, 2.6 and 0.1
  points.

## What a unit charges for

Every operation an algorithm declares is priced and charged against its budget, its own bookkeeping as well
as the game's. On the laptop's run, the share of each budget spent on the algorithm's own operations:

| Level | `emcts` | `mcts` | `oep` | `tbets` |
|---|---|---|---|---|
| 9,500 | 3.9% | 21.4% | 44.0% | 42.0% |
| 20,000 | 3.9% | 15.6% | 38.9% | 41.6% |
| 101,500 | 3.6% | 13.4% | 23.5% | 42.4% |

Each turn's recorded units rebuild exactly from its operation counts and its currency's prices. The
calibration's audit, one turn at a time, found the priced operations accounting for at least 99.8% of every
cell's time on both machines. Timed one at a time under the laptop's currency, a 9,500-unit turn took 287-308
ms for all four algorithms.

The calibration log reads differently, and that is expected: it measures each algorithm under one fixed,
provisional price list in which the algorithms' own operations are free, so that every session plays the
same turns ([`CURRENCY.md`](../CURRENCY.md)). From those measurements it writes the prices the runs use.

## What does not transfer: seconds

| Level | `emcts` | `mcts` | `oep` | `tbets` |
|---|---|---|---|---|
| 9,500, R7615 / laptop (s per turn) | 0.64 / 0.60 | 0.73 / 0.66 | 0.62 / 0.56 | 0.59 / 0.52 |
| 101,500, R7615 / laptop | 6.89 / 6.53 | 9.39 / 7.94 | 6.72 / 6.16 | 6.15 / 5.51 |

The seconds are each run's own: 62 workers on the R7615 and 15 on the laptop's hybrid CPU, where turns running
at once slow each other, `mcts` most. Units are the comparable measure; compare seconds only between runs
with the same parallelism.

## Two ways to use another machine

- **Your own currency** (`--weights` your calibration): a fair budget on your machine, with results that agree
  with the reference machine's as above.
- **The published currency** (no `--weights`): your machine plays the R7615's turns exactly, which `check
  replay` and CI test on every change. On the laptop 1,120 of the R7615's turns replayed field for field.

## Reproducing this page

```bash
python magat.py analyze --run games/tribes/results/reference/r7615 --compare games/tribes/results/reference/laptop-i9-14900hx --out $HOME/magat_runs/tribes/results/r7615-vs-laptop
```

To add your machine: calibrate it ([`CALIBRATING_A_MACHINE.md`](../CALIBRATING_A_MACHINE.md)), run the
reference experiment under your currency, and compare your run with `games/tribes/results/reference/r7615`.

The paper's tables and figures of these results are rebuilt from the published runs by
`python magat.py paper` ([`paper/`](../../paper/README.md)).
