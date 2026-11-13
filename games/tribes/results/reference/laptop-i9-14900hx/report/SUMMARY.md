# reference: MAGAT analysis

Splits: all. Baseline `emcts`. A positive difference means the arm finds a better
final sequence than the baseline on the same (scenario, visit) seeds; intervals are scenario-clustered
bootstrap 95% CIs (4,000 resamples), bold where they exclude zero.

## Provenance

|  | all |
|---|---|
| game | tribes |
| engine commit | 828b13051d02fc55c6e95a3997d3757337883660 |
| portfolio identity | ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7 |
| currency | compute_weights.properties (1e3d9ab0) |
| currency reference | emcts@9500 |
| experiment | reference |
| experiment sha256 | 29ca9e81fed203801a6c0a021dbe397be2d14b42c9c119d118238c1de1615d62 |
| leaves / turns | 15 / 44,000 |
| written by MAGAT | c3afdaed76da7151cf4db255a270699a36e0fd21 |

## Headline (all): units and seconds

Units: the paired difference against the baseline, on the pairs where neither plan ends the game. A plan
that ends it scores a sentinel (about +/-2e9) that would swamp any mean, so those pairs are counted apart
(`Ends the game`: the arm's plans / the baseline's) and the mean over all pairs stands beside it. Seconds:
the arm re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.
The budget multiplier is the readable size; `MeanDiff` itself is the raw heuristic delta, never a
result on its own.

### 9,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| mcts | 3152 | **-149.0 [-180.3, -120.3]** | -150.5 | 48 / 48 | dual -178.0, single -120.9 | **-155.6 [-186.9, -126.5]** (clamped 32%) | no win | **0.2x [0.1x, 0.2x]** (outside) | -0.45 |
| oep | 3152 | **+54.6 [+25.2, +83.9]** | +55.0 | 48 / 48 | dual +11.4, single +96.5 | **+52.3 [+22.8, +81.3]** (clamped 75%) | wins both | **2.0x [1.4x, 2.8x]** | 0.16 |
| random | 3152 | **-601.1 [-669.2, -536.4]** | -28750623.4 | 2 / 48 | dual -679.0, single -525.6 | **-601.1 [-669.0, -537.6]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.86 |
| tbets | 3152 | **+48.0 [+17.1, +76.9]** | -1249952.3 | 46 / 48 | dual +2.1, single +92.6 | **+47.1 [+17.6, +77.7]** (clamped 83%) | wins both | **1.8x [1.3x, 2.6x]** | 0.13 |

### 20,000 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| mcts | 3152 | **-179.6 [-212.0, -149.1]** | -180.9 | 48 / 48 | dual -211.2, single -148.9 | **-181.0 [-212.6, -149.9]** (clamped 18%) | no win | **0.1x [0.1x, 0.2x]** (outside) | -0.51 |
| oep | 3152 | **+148.8 [+119.2, +179.2]** | +148.8 | 48 / 48 | dual +83.7, single +211.9 | **+167.9 [+138.8, +198.7]** (clamped 7%) | wins both | **5.9x [4.0x, 8.7x]** (extrapolated) | 0.40 |
| random | 3152 | **-660.6 [-729.8, -595.9]** | -28750682.8 | 2 / 48 | dual -741.9, single -581.7 | **-601.1 [-666.6, -539.2]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.91 |
| tbets | 3152 | **+74.6 [+45.0, +102.5]** | +75.5 | 48 / 48 | dual +20.2, single +127.3 | **+97.4 [+67.9, +125.8]** | wins both | **2.4x [1.7x, 3.4x]** | 0.20 |

### 101,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| mcts | 2364 | **-254.8 [-291.9, -219.8]** | -833590.1 | 35 / 36 | dual -279.3, single -231.0 | **-253.9 [-290.6, -218.1]** (clamped 83%) | no win | **0.0x [0.0x, 0.1x]** (outside) | -0.62 |
| oep | 2364 | **+143.6 [+113.5, +171.7]** | +143.6 | 36 / 36 | dual +84.5, single +200.9 | **+164.4 [+135.4, +193.5]** (clamped 28%) | wins both | **5.6x [3.7x, 9.0x]** (extrapolated) | 0.36 |
| random | 2364 | **-792.6 [-863.8, -721.3]** | -29167481.9 | 1 / 36 | dual -855.2, single -731.8 | **-598.3 [-663.2, -535.6]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -1.02 |
| tbets | 2364 | **+107.9 [+78.1, +137.4]** | +109.0 | 36 / 36 | dual +59.5, single +154.9 | **+140.2 [+111.6, +168.3]** (clamped 13%) | wins both | **3.7x [2.5x, 5.6x]** (extrapolated) | 0.28 |

## Budget multiplier (all)

How much budget `emcts` would need to match each arm: the baseline's mean plan value per level,
interpolated log-linearly in budget, solved for the arm's mean value, over the cells where every baseline
level and the arm ran and none ended terminal (values, not search gains: each arm's `initial` is its own
starting plan). `extrapolated` means the crossing lies past the top of the ladder: a lower bound, since
value is concave in budget. The wall-clock column solves the same crossing in the baseline's own seconds
and divides by the arm's; `arm/baseline time` is the arm's plan time over the baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 9,500: value 6206.5, 20,000: value 6265.9, 101,500: value 6400.8. Its top step is 5.1x the units and 5.2x the seconds.

| Budget | Arm | Cells | Arm value | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 9500 | mcts | 2364 | 6057.4 | **0.2x [0.1x, 0.2x]** | outside | **0.1x [0.1x, 0.2x]** | 1.11 |
| 9500 | oep | 2364 | 6259.9 | **2.0x [1.4x, 2.8x]** | inside | **2.1x [1.4x, 2.9x]** | 0.94 |
| 9500 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.1x [0.0x, 0.3x]** | 0.01 |
| 9500 | tbets | 2364 | 6254.0 | **1.8x [1.3x, 2.6x]** | inside | **2.1x [1.5x, 3.0x]** | 0.87 |
| 20000 | mcts | 2364 | 6086.0 | **0.1x [0.1x, 0.2x]** | outside | **0.1x [0.1x, 0.1x]** | 1.10 |
| 20000 | oep | 2364 | 6413.1 | **5.9x [4.0x, 8.7x]** | extrapolated | **6.5x [4.5x, 9.6x]** | 0.94 |
| 20000 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.1x [0.0x, 0.2x]** | 0.00 |
| 20000 | tbets | 2364 | 6339.5 | **2.4x [1.7x, 3.4x]** | inside | **2.8x [2.0x, 4.0x]** | 0.87 |
| 101500 | mcts | 2364 | 6146.0 | **0.0x [0.0x, 0.1x]** | outside | **0.0x [0.0x, 0.1x]** | 1.22 |
| 101500 | oep | 2364 | 6544.4 | **5.6x [3.7x, 9.0x]** | extrapolated | **6.1x [4.0x, 9.9x]** | 0.94 |
| 101500 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.1x [0.0x, 0.3x]** | 0.00 |
| 101500 | tbets | 2364 | 6508.8 | **3.7x [2.5x, 5.6x]** | extrapolated | **4.4x [2.9x, 6.9x]** | 0.84 |

## Contrasts (all)

The spec's own paired comparisons, arm A minus arm B, on the pairs where neither
plan ends the game; the mean over all pairs beside it.

| Budget | A - B | Label | Pairs | MeanDiff [CI] | All pairs | WinRate |
|---|---|---|---|---|---|---|
| 9500 | mcts - random | MCTS over the floor | 3152 | **+452.1 [+404.9, +501.4]** | +28750472.8 | 0.962 |
| 9500 | emcts - random | EMCTS over the floor | 3152 | **+601.1 [+539.7, +667.8]** | +28750623.4 | 0.987 |
| 9500 | oep - random | OEP over the floor | 3152 | **+655.7 [+601.4, +712.3]** | +28750678.4 | 0.998 |
| 9500 | tbets - random | TBETS over the floor | 3154 | **+649.4 [+595.7, +706.0]** | +27500671.1 | 0.997 |
| 9500 | emcts - mcts | EMCTS against MCTS | 3152 | **+149.0 [+120.7, +178.1]** | +150.5 | 0.808 |
| 9500 | oep - mcts | OEP against MCTS | 3152 | **+203.6 [+183.6, +224.6]** | +205.6 | 0.923 |
| 9500 | tbets - mcts | TBETS against MCTS | 3152 | **+197.0 [+175.2, +219.9]** | -1249801.7 | 0.901 |
| 9500 | oep - emcts | OEP against EMCTS | 3152 | **+54.6 [+24.7, +83.2]** | +55.0 | 0.624 |
| 9500 | tbets - emcts | TBETS against EMCTS | 3152 | **+48.0 [+18.6, +77.1]** | -1249952.3 | 0.610 |
| 9500 | tbets - oep | TBETS against OEP | 3152 | -6.6 [-15.5, +1.9] | -1250007.3 | 0.522 |
| 20000 | mcts - random | MCTS over the floor | 3152 | **+481.0 [+429.7, +533.9]** | +28750501.9 | 0.967 |
| 20000 | emcts - random | EMCTS over the floor | 3152 | **+660.6 [+595.7, +727.0]** | +28750682.8 | 0.992 |
| 20000 | oep - random | OEP over the floor | 3152 | **+809.4 [+747.6, +871.4]** | +28750831.6 | 0.999 |
| 20000 | tbets - random | TBETS over the floor | 3152 | **+735.1 [+675.3, +792.6]** | +28750758.3 | 0.998 |
| 20000 | emcts - mcts | EMCTS against MCTS | 3152 | **+179.6 [+148.5, +211.2]** | +180.9 | 0.816 |
| 20000 | oep - mcts | OEP against MCTS | 3152 | **+328.4 [+300.7, +356.0]** | +329.7 | 0.960 |
| 20000 | tbets - mcts | TBETS against MCTS | 3152 | **+254.1 [+231.2, +279.7]** | +256.4 | 0.944 |
| 20000 | oep - emcts | OEP against EMCTS | 3152 | **+148.8 [+119.2, +179.0]** | +148.8 | 0.726 |
| 20000 | tbets - emcts | TBETS against EMCTS | 3152 | **+74.6 [+45.3, +102.9]** | +75.5 | 0.649 |
| 20000 | tbets - oep | TBETS against OEP | 3152 | **-74.2 [-85.1, -64.0]** | -73.3 | 0.333 |
| 101500 | mcts - random | MCTS over the floor | 2365 | **+538.3 [+483.0, +596.9]** | +28333891.8 | 0.977 |
| 101500 | emcts - random | EMCTS over the floor | 2364 | **+792.6 [+718.7, +862.5]** | +29167481.9 | 0.996 |
| 101500 | oep - random | OEP over the floor | 2364 | **+936.2 [+868.4, +1005.1]** | +29167625.5 | 1.000 |
| 101500 | tbets - random | TBETS over the floor | 2364 | **+900.5 [+835.5, +967.3]** | +29167590.9 | 1.000 |
| 101500 | emcts - mcts | EMCTS against MCTS | 2364 | **+254.8 [+219.5, +291.2]** | +833590.1 | 0.874 |
| 101500 | oep - mcts | OEP against MCTS | 2364 | **+398.4 [+366.8, +431.0]** | +833733.7 | 0.972 |
| 101500 | tbets - mcts | TBETS against MCTS | 2364 | **+362.7 [+333.3, +393.3]** | +833699.1 | 0.980 |
| 101500 | oep - emcts | OEP against EMCTS | 2364 | **+143.6 [+113.5, +173.2]** | +143.6 | 0.715 |
| 101500 | tbets - emcts | TBETS against EMCTS | 2364 | **+107.9 [+76.9, +137.3]** | +109.0 | 0.708 |
| 101500 | tbets - oep | TBETS against OEP | 2364 | **-35.7 [-46.2, -25.0]** | -34.6 | 0.398 |

## Spend, time and plans (all)

`Spend` is the mean turn's units, its initialization included, over the level: bold above 1.10x, as the calibration flags a cell, with the share of turns above it beside it.
`Seconds` is the mean turn's wall-clock; `Level` is the time the level was set to, the emcts turn's mean, single-threaded on AMD EPYC 9534 64-Core Processor (`levels.json`). `Unaccounted` is the run's audit: the share
of the plan time that the currency's prices do not account for, on the threads the leaf ran on. On many
threads it holds contention as well, and turns take longer than their level, unevenly across arms: the
calibration's own audit, on one idle thread, is the clean measure. `Actions` is the mean plan's length, its
end of turn included; `Ends the game` the share of plans that do.

| Budget | Arm | Turns | Spend | Over 1.10x | Seconds | Level | Actions | Ends the game | Unaccounted (threads) |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | emcts | 3200 | 1.00x | 0% | 0.60 | 0.50 | 15.9 | 1.5% | 27% on 15 |
| 9500 | mcts | 3200 | 1.00x | 0% | 0.66 | 0.50 | 3.8 | 1.5% | 34% on 15 |
| 9500 | oep | 3200 | 1.00x | 0% | 0.56 | 0.50 | 18.0 | 1.5% | 22% on 15 |
| 9500 | random | 3200 | 0.00x | 0% | 0.00 | 0.50 | 15.6 | 0.1% | - |
| 9500 | tbets | 3200 | 1.00x | 0% | 0.52 | 0.50 | 17.4 | 1.4% | 16% on 15 |
| 20000 | emcts | 3200 | 1.00x | 0% | 1.26 | 1.00 | 16.1 | 1.5% | 27% on 15 |
| 20000 | mcts | 3200 | 1.00x | 0% | 1.38 | 1.00 | 4.2 | 1.5% | 34% on 15 |
| 20000 | oep | 3200 | 1.00x | 0% | 1.17 | 1.00 | 18.1 | 1.5% | 22% on 15 |
| 20000 | random | 3200 | 0.00x | 0% | 0.01 | 1.00 | 15.6 | 0.1% | - |
| 20000 | tbets | 3200 | 1.00x | 0% | 1.09 | 1.00 | 17.4 | 1.5% | 16% on 15 |
| 101500 | emcts | 2400 | 1.00x | 0% | 6.55 | 5.00 | 16.8 | 1.5% | 29% on 15 |
| 101500 | mcts | 2400 | 1.00x | 0% | 7.95 | 5.00 | 5.1 | 1.5% | 41% on 15 |
| 101500 | oep | 2400 | 1.00x | 0% | 6.15 | 5.00 | 18.1 | 1.5% | 24% on 15 |
| 101500 | random | 2400 | 0.00x | 0% | 0.00 | 5.00 | 15.6 | 0.0% | - |
| 101500 | tbets | 2400 | 1.00x | 0% | 5.51 | 5.00 | 17.6 | 1.5% | 16% on 15 |

## Design (all): scenarios versus visits

On the pairs where neither plan ends the game, as the headline is.
ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | mcts | 394 | 8.0 | 0.850 | 15.4 | 15.2 | 1.1% | 453 | 3152 |
| 9500 | oep | 394 | 8.0 | 0.693 | 15.0 | 14.6 | 2.7% | 539 | 3152 |
| 9500 | random | 394 | 8.0 | 0.870 | 33.2 | 32.9 | 0.9% | 445 | 3152 |
| 9500 | tbets | 394 | 8.0 | 0.612 | 15.2 | 14.6 | 3.9% | 596 | 3152 |
| 20000 | mcts | 394 | 8.0 | 0.796 | 16.0 | 15.8 | 1.6% | 479 | 3152 |
| 20000 | oep | 394 | 8.0 | 0.601 | 15.2 | 14.6 | 4.1% | 606 | 3152 |
| 20000 | random | 394 | 8.0 | 0.857 | 34.2 | 33.8 | 1.0% | 451 | 3152 |
| 20000 | tbets | 394 | 8.0 | 0.581 | 14.7 | 14.1 | 4.4% | 622 | 3152 |
| 101500 | mcts | 394 | 6.0 | 0.752 | 18.4 | 17.9 | 2.7% | 496 | 2364 |
| 101500 | oep | 394 | 6.0 | 0.476 | 15.2 | 14.0 | 8.8% | 699 | 2364 |
| 101500 | random | 394 | 6.0 | 0.842 | 36.6 | 36.1 | 1.6% | 454 | 2364 |
| 101500 | tbets | 394 | 6.0 | 0.497 | 15.0 | 13.8 | 8.1% | 678 | 2364 |

Median ICC 0.723. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 4 (median over arms).

## Scope notes

- all: multipliers outside the ladder: mcts@9500 (outside, 0.2x), random@9500 (outside, 0.0x), mcts@20000 (outside, 0.1x), oep@20000 (extrapolated, 5.9x), random@20000 (outside, 0.0x), mcts@101500 (outside, 0.0x), oep@101500 (extrapolated, 5.6x), random@101500 (outside, 0.0x), tbets@101500 (extrapolated, 3.7x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- all: wall-clock rows with over a quarter of pairs clamped to the baseline's time range: mcts@9500 (32%), oep@9500 (75%), random@9500 (100%), tbets@9500 (83%), random@20000 (100%), mcts@101500 (83%), oep@101500 (28%), random@101500 (100%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- all: terminal turns: up to 1.5% of a leaf (15 of 15 leaves have any). The headline, the contrasts and the design leave their pairs out and count them; the tables keep both strata.
- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; it cannot re-run the stop rule.
- These are single turns replanned from captured positions, not full games.

Full tables in `tables/` (every row names its split and its currency), the same numbers in `report.json`, figures in `figures/` when matplotlib is installed.
