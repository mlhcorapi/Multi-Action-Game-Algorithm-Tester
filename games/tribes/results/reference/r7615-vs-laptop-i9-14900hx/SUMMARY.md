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
| currency | compute_weights.properties (35db011f) |
| currency reference | emcts@9500 |
| experiment | reference |
| experiment sha256 | 29ca9e81fed203801a6c0a021dbe397be2d14b42c9c119d118238c1de1615d62 |
| leaves / turns | 15 / 44,000 |
| written by MAGAT | d5e1f98ff6daae102bd2376cb941c3eff8b78c2f (notebook modified) |

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
| mcts | 3152 | **-147.4 [-178.2, -119.1]** | -149.0 | 48 / 48 | dual -175.0, single -120.7 | **-155.0 [-186.1, -125.9]** (clamped 20%) | no win | **0.1x [0.1x, 0.2x]** (outside) | -0.45 |
| oep | 3152 | **+71.0 [+41.5, +99.8]** | +71.4 | 48 / 48 | dual +25.7, single +114.9 | **+68.6 [+39.3, +97.8]** (clamped 72%) | wins both | **2.4x [1.6x, 3.4x]** | 0.20 |
| random | 3152 | **-601.2 [-669.5, -537.1]** | -28750623.4 | 2 / 48 | dual -677.4, single -527.3 | **-601.2 [-669.4, -538.0]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.86 |
| tbets | 3152 | **+58.4 [+28.5, +87.0]** | -1249942.1 | 46 / 48 | dual +15.0, single +100.5 | **+57.2 [+28.5, +87.2]** (clamped 80%) | wins both | **2.1x [1.4x, 2.9x]** | 0.16 |

### 20,000 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| mcts | 3152 | **-176.8 [-209.4, -146.0]** | -178.7 | 48 / 48 | dual -207.1, single -147.4 | **-181.8 [-213.9, -150.3]** (clamped 22%) | no win | **0.1x [0.1x, 0.1x]** (outside) | -0.50 |
| oep | 3152 | **+163.2 [+133.0, +194.2]** | +162.6 | 48 / 48 | dual +98.3, single +226.1 | **+176.5 [+146.9, +207.2]** (clamped 8%) | wins both | **6.8x [4.7x, 10.0x]** (extrapolated) | 0.43 |
| random | 3152 | **-658.0 [-727.1, -593.7]** | -28750680.9 | 2 / 48 | dual -738.2, single -580.2 | **-601.2 [-666.6, -539.6]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.91 |
| tbets | 3152 | **+86.4 [+56.9, +115.2]** | +86.6 | 48 / 48 | dual +34.1, single +137.1 | **+103.4 [+73.4, +131.9]** (clamped 5%) | wins both | **2.7x [1.9x, 3.8x]** | 0.23 |

### 101,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| mcts | 2364 | **-254.6 [-290.0, -221.0]** | -833589.4 | 35 / 36 | dual -274.9, single -234.9 | **-254.2 [-289.7, -220.5]** (clamped 91%) | no win | **0.0x [0.0x, 0.1x]** (outside) | -0.64 |
| oep | 2364 | **+146.3 [+117.4, +173.6]** | +146.8 | 36 / 36 | dual +91.7, single +199.3 | **+163.5 [+134.5, +193.1]** (clamped 32%) | wins both | **5.8x [3.9x, 9.1x]** (extrapolated) | 0.37 |
| random | 2364 | **-792.5 [-863.3, -722.1]** | -29167481.3 | 1 / 36 | dual -851.2, single -735.6 | **-600.3 [-665.4, -538.1]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -1.02 |
| tbets | 2364 | **+114.6 [+86.1, +143.7]** | +116.2 | 36 / 36 | dual +69.6, single +158.3 | **+140.9 [+112.4, +168.2]** (clamped 17%) | wins both | **4.0x [2.7x, 6.0x]** (extrapolated) | 0.30 |

## Budget multiplier (all)

How much budget `emcts` would need to match each arm: the baseline's mean plan value per level,
interpolated log-linearly in budget, solved for the arm's mean value, over the cells where every baseline
level and the arm ran and none ended terminal (values, not search gains: each arm's `initial` is its own
starting plan). `extrapolated` means the crossing lies past the top of the ladder: a lower bound, since
value is concave in budget. The wall-clock column solves the same crossing in the baseline's own seconds
and divides by the arm's; `arm/baseline time` is the arm's plan time over the baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 9,500: value 6208.5, 20,000: value 6266.1, 101,500: value 6400.8. Its top step is 5.1x the units and 5.2x the seconds.

| Budget | Arm | Cells | Arm value | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 9500 | mcts | 2364 | 6059.0 | **0.1x [0.1x, 0.2x]** | outside | **0.1x [0.1x, 0.2x]** | 1.15 |
| 9500 | oep | 2364 | 6276.4 | **2.4x [1.6x, 3.4x]** | inside | **2.4x [1.7x, 3.5x]** | 0.98 |
| 9500 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.01 |
| 9500 | tbets | 2364 | 6264.3 | **2.1x [1.4x, 2.9x]** | inside | **2.2x [1.5x, 3.2x]** | 0.92 |
| 20000 | mcts | 2364 | 6086.4 | **0.1x [0.1x, 0.1x]** | outside | **0.1x [0.1x, 0.1x]** | 1.19 |
| 20000 | oep | 2364 | 6425.1 | **6.8x [4.7x, 10.0x]** | extrapolated | **7.1x [4.9x, 10.4x]** | 0.99 |
| 20000 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.01 |
| 20000 | tbets | 2364 | 6349.9 | **2.7x [1.9x, 3.8x]** | inside | **3.0x [2.1x, 4.2x]** | 0.93 |
| 101500 | mcts | 2364 | 6146.1 | **0.0x [0.0x, 0.1x]** | outside | **0.0x [0.0x, 0.0x]** | 1.36 |
| 101500 | oep | 2364 | 6547.1 | **5.8x [3.9x, 9.1x]** | extrapolated | **6.2x [4.1x, 9.7x]** | 0.98 |
| 101500 | random | 2364 | 5608.2 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.00 |
| 101500 | tbets | 2364 | 6515.4 | **4.0x [2.7x, 6.0x]** | extrapolated | **4.6x [3.1x, 7.0x]** | 0.89 |

## Contrasts (all)

The spec's own paired comparisons, arm A minus arm B, on the pairs where neither
plan ends the game; the mean over all pairs beside it.

| Budget | A - B | Label | Pairs | MeanDiff [CI] | All pairs | WinRate |
|---|---|---|---|---|---|---|
| 9500 | mcts - random | MCTS over the floor | 3152 | **+453.8 [+406.0, +503.6]** | +28750474.4 | 0.962 |
| 9500 | emcts - random | EMCTS over the floor | 3152 | **+601.2 [+540.1, +667.1]** | +28750623.4 | 0.987 |
| 9500 | oep - random | OEP over the floor | 3152 | **+672.2 [+617.1, +729.7]** | +28750694.8 | 0.998 |
| 9500 | tbets - random | TBETS over the floor | 3154 | **+659.9 [+605.4, +716.7]** | +27500681.4 | 0.997 |
| 9500 | emcts - mcts | EMCTS against MCTS | 3152 | **+147.4 [+119.4, +176.2]** | +149.0 | 0.808 |
| 9500 | oep - mcts | OEP against MCTS | 3152 | **+218.4 [+197.5, +240.2]** | +220.4 | 0.933 |
| 9500 | tbets - mcts | TBETS against MCTS | 3152 | **+205.8 [+184.1, +228.6]** | -1249793.1 | 0.910 |
| 9500 | oep - emcts | OEP against EMCTS | 3152 | **+71.0 [+40.8, +99.2]** | +71.4 | 0.651 |
| 9500 | tbets - emcts | TBETS against EMCTS | 3152 | **+58.4 [+30.2, +86.3]** | -1249942.1 | 0.631 |
| 9500 | tbets - oep | TBETS against OEP | 3152 | **-12.6 [-21.1, -4.4]** | -1250013.5 | 0.499 |
| 20000 | mcts - random | MCTS over the floor | 3152 | **+481.2 [+429.7, +534.0]** | +28750502.2 | 0.967 |
| 20000 | emcts - random | EMCTS over the floor | 3152 | **+658.0 [+593.9, +724.3]** | +28750680.9 | 0.992 |
| 20000 | oep - random | OEP over the floor | 3152 | **+821.2 [+759.1, +883.4]** | +28750843.4 | 0.999 |
| 20000 | tbets - random | TBETS over the floor | 3152 | **+744.4 [+684.0, +802.4]** | +28750767.4 | 0.999 |
| 20000 | emcts - mcts | EMCTS against MCTS | 3152 | **+176.8 [+146.1, +209.1]** | +178.7 | 0.810 |
| 20000 | oep - mcts | OEP against MCTS | 3152 | **+340.0 [+311.6, +368.4]** | +341.3 | 0.964 |
| 20000 | tbets - mcts | TBETS against MCTS | 3152 | **+263.2 [+240.3, +288.9]** | +265.3 | 0.948 |
| 20000 | oep - emcts | OEP against EMCTS | 3152 | **+163.2 [+133.1, +193.7]** | +162.6 | 0.747 |
| 20000 | tbets - emcts | TBETS against EMCTS | 3152 | **+86.4 [+56.6, +115.6]** | +86.6 | 0.666 |
| 20000 | tbets - oep | TBETS against OEP | 3152 | **-76.8 [-87.6, -66.6]** | -76.0 | 0.324 |
| 101500 | mcts - random | MCTS over the floor | 2365 | **+538.5 [+483.1, +596.8]** | +28333892.0 | 0.977 |
| 101500 | emcts - random | EMCTS over the floor | 2364 | **+792.5 [+720.4, +862.7]** | +29167481.3 | 0.996 |
| 101500 | oep - random | OEP over the floor | 2364 | **+938.8 [+871.1, +1007.7]** | +29167628.1 | 1.000 |
| 101500 | tbets - random | TBETS over the floor | 2364 | **+907.1 [+842.0, +974.2]** | +29167597.6 | 1.000 |
| 101500 | emcts - mcts | EMCTS against MCTS | 2364 | **+254.6 [+220.7, +289.2]** | +833589.4 | 0.883 |
| 101500 | oep - mcts | OEP against MCTS | 2364 | **+400.9 [+369.1, +433.5]** | +833736.2 | 0.972 |
| 101500 | tbets - mcts | TBETS against MCTS | 2364 | **+369.2 [+339.4, +400.1]** | +833705.6 | 0.982 |
| 101500 | oep - emcts | OEP against EMCTS | 2364 | **+146.3 [+117.4, +175.5]** | +146.8 | 0.719 |
| 101500 | tbets - emcts | TBETS against EMCTS | 2364 | **+114.6 [+85.0, +142.9]** | +116.2 | 0.700 |
| 101500 | tbets - oep | TBETS against OEP | 2364 | **-31.7 [-42.4, -20.8]** | -30.6 | 0.410 |

## Spend, time and plans (all)

`Spend` is the mean turn's units, its initialization included, over the level: bold above 1.10x, as the calibration flags a cell, with the share of turns above it beside it.
`Seconds` is the mean turn's wall-clock; `Level` is the time the level was set to, the emcts turn's mean, single-threaded on AMD EPYC 9534 64-Core Processor (`levels.json`). `Unaccounted` is the run's audit: the share
of the plan time that the currency's prices do not account for, on the threads the leaf ran on. On many
threads it holds contention as well, and turns take longer than their level, unevenly across arms: the
calibration's own audit, on one idle thread, is the clean measure. `Actions` is the mean plan's length, its
end of turn included; `Ends the game` the share of plans that do.

| Budget | Arm | Turns | Spend | Over 1.10x | Seconds | Level | Actions | Ends the game | Unaccounted (threads) |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | emcts | 3200 | 1.00x | 0% | 0.64 | 0.50 | 15.9 | 1.5% | 18% on 62 |
| 9500 | mcts | 3200 | 1.00x | 0% | 0.73 | 0.50 | 3.8 | 1.5% | 28% on 62 |
| 9500 | oep | 3200 | 1.00x | 0% | 0.62 | 0.50 | 18.0 | 1.5% | 15% on 62 |
| 9500 | random | 3200 | 0.00x | 0% | 0.01 | 0.50 | 15.6 | 0.1% | - |
| 9500 | tbets | 3200 | 1.00x | 0% | 0.59 | 0.50 | 17.4 | 1.4% | 10% on 62 |
| 20000 | emcts | 3200 | 1.00x | 0% | 1.32 | 1.00 | 16.1 | 1.5% | 16% on 62 |
| 20000 | mcts | 3200 | 1.00x | 0% | 1.58 | 1.00 | 4.2 | 1.5% | 30% on 62 |
| 20000 | oep | 3200 | 1.00x | 0% | 1.30 | 1.00 | 18.1 | 1.5% | 15% on 62 |
| 20000 | random | 3200 | 0.00x | 0% | 0.02 | 1.00 | 15.6 | 0.1% | - |
| 20000 | tbets | 3200 | 1.00x | 0% | 1.23 | 1.00 | 17.4 | 1.5% | 10% on 62 |
| 101500 | emcts | 2400 | 1.00x | 0% | 6.90 | 5.00 | 16.7 | 1.5% | 19% on 62 |
| 101500 | mcts | 2400 | 1.00x | 0% | 9.40 | 5.00 | 5.1 | 1.5% | 40% on 62 |
| 101500 | oep | 2400 | 1.00x | 0% | 6.71 | 5.00 | 18.1 | 1.5% | 16% on 62 |
| 101500 | random | 2400 | 0.00x | 0% | 0.01 | 5.00 | 15.6 | 0.0% | - |
| 101500 | tbets | 2400 | 1.00x | 0% | 6.14 | 5.00 | 17.6 | 1.5% | 8% on 62 |

## Design (all): scenarios versus visits

On the pairs where neither plan ends the game, as the headline is.
ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | mcts | 394 | 8.0 | 0.846 | 15.2 | 15.0 | 1.1% | 455 | 3152 |
| 9500 | oep | 394 | 8.0 | 0.683 | 14.9 | 14.5 | 2.9% | 545 | 3152 |
| 9500 | random | 394 | 8.0 | 0.869 | 33.1 | 32.8 | 0.9% | 445 | 3152 |
| 9500 | tbets | 394 | 8.0 | 0.595 | 14.9 | 14.3 | 4.2% | 610 | 3152 |
| 20000 | mcts | 394 | 8.0 | 0.795 | 16.1 | 15.8 | 1.6% | 480 | 3152 |
| 20000 | oep | 394 | 8.0 | 0.606 | 15.6 | 15.0 | 4.0% | 601 | 3152 |
| 20000 | random | 394 | 8.0 | 0.856 | 34.1 | 33.8 | 1.0% | 451 | 3152 |
| 20000 | tbets | 394 | 8.0 | 0.585 | 15.0 | 14.3 | 4.3% | 618 | 3152 |
| 101500 | mcts | 394 | 6.0 | 0.738 | 17.7 | 17.2 | 2.9% | 504 | 2364 |
| 101500 | oep | 394 | 6.0 | 0.455 | 14.7 | 13.5 | 9.5% | 722 | 2364 |
| 101500 | random | 394 | 6.0 | 0.840 | 36.4 | 35.8 | 1.6% | 454 | 2364 |
| 101500 | tbets | 394 | 6.0 | 0.494 | 14.5 | 13.4 | 8.2% | 682 | 2364 |

Median ICC 0.710. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 4 (median over arms).

## Across machines (all)

A is this run, priced in compute_weights.properties (35db011f) and written by MAGAT d5e1f98ff6daae102bd2376cb941c3eff8b78c2f (notebook modified); B is `games/tribes/results/reference/laptop-i9-14900hx/all`, priced in compute_weights.properties (1e3d9ab0) and written by MAGAT c3afdaed76da7151cf4db255a270699a36e0fd21.
The same experiment, positions and seeds, so the same chance events: each machine's search stopped under its
own currency. On the cells both ran, neither turn terminal: each machine's mean value and the paired
difference A - B, which is what the two currencies' prices did to where the searches stopped. The budget
multipliers are dimensionless, so the two should agree. The seconds per turn are each machine's own.
`A at B's prices` is the units A's turns cost in B's currency over the units they cost in A's.

| Budget | Arm | Pairs | A mean [CI] | B mean [CI] | A - B [CI] | A multiplier [CI] | B multiplier [CI] | A s/turn | B s/turn | A at B's prices |
|---|---|---|---|---|---|---|---|---|---|---|
| 9500 | emcts | 3152 | 6206.1 [5579.8, 6801.5] | 6206.0 [5594.0, 6833.4] | +0.1 [-2.6, +2.8] | - | - | 0.64 | 0.60 | 0.996 |
| 9500 | mcts | 3152 | 6058.6 [5445.6, 6662.8] | 6057.0 [5442.1, 6651.5] | **+1.6 [+0.8, +2.7]** | **0.1x [0.1x, 0.2x]** | **0.2x [0.1x, 0.2x]** | 0.73 | 0.66 | 1.030 |
| 9500 | oep | 3152 | 6277.1 [5648.2, 6882.7] | 6260.6 [5646.0, 6856.5] | **+16.4 [+14.1, +19.0]** | **2.4x [1.6x, 3.4x]** | **2.0x [1.4x, 2.8x]** | 0.62 | 0.56 | 1.089 |
| 9500 | random | 3198 | 5597.0 [4980.6, 6194.0] | 5597.0 [5003.6, 6201.8] | +0.0 [+0.0, +0.0] | **0.0x [0.0x, 0.0x]** | **0.0x [0.0x, 0.0x]** | 0.01 | 0.00 | 1.000 |
| 9500 | tbets | 3154 | 6265.9 [5637.6, 6877.8] | 6255.5 [5647.3, 6878.6] | **+10.4 [+8.4, +12.8]** | **2.1x [1.4x, 2.9x]** | **1.8x [1.3x, 2.6x]** | 0.59 | 0.52 | 1.084 |
| 20000 | emcts | 3152 | 6262.9 [5669.2, 6850.1] | 6265.4 [5668.2, 6857.7] | -2.6 [-8.0, +2.6] | - | - | 1.32 | 1.26 | 0.990 |
| 20000 | mcts | 3152 | 6086.0 [5480.8, 6660.4] | 6085.9 [5495.6, 6678.5] | +0.2 [-0.2, +0.5] | **0.1x [0.1x, 0.1x]** | **0.1x [0.1x, 0.2x]** | 1.57 | 1.38 | 1.019 |
| 20000 | oep | 3152 | 6426.0 [5795.1, 7047.1] | 6414.2 [5814.4, 7031.6] | **+11.8 [+10.1, +13.7]** | **6.8x [4.7x, 10.0x]** | **5.9x [4.0x, 8.7x]** | 1.30 | 1.17 | 1.080 |
| 20000 | random | 3198 | 5597.0 [5013.6, 6209.2] | 5597.0 [5013.0, 6195.6] | +0.0 [+0.0, +0.0] | **0.0x [0.0x, 0.0x]** | **0.0x [0.0x, 0.0x]** | 0.02 | 0.01 | 1.000 |
| 20000 | tbets | 3152 | 6349.2 [5727.2, 6958.9] | 6340.0 [5744.4, 6958.9] | **+9.2 [+7.6, +11.2]** | **2.7x [1.9x, 3.8x]** | **2.4x [1.7x, 3.4x]** | 1.23 | 1.09 | 1.087 |
| 101500 | emcts | 2364 | 6400.8 [5751.2, 7005.8] | 6400.8 [5794.4, 7010.7] | -0.1 [-8.5, +8.6] | - | - | 6.89 | 6.53 | 0.991 |
| 101500 | mcts | 2365 | 6146.6 [5519.0, 6727.0] | 6146.5 [5532.2, 6747.0] | +0.1 [-0.3, +0.6] | **0.0x [0.0x, 0.1x]** | **0.0x [0.0x, 0.1x]** | 9.39 | 7.94 | 0.997 |
| 101500 | oep | 2364 | 6547.1 [5923.4, 7166.5] | 6544.4 [5962.3, 7173.5] | **+2.7 [+1.8, +3.6]** | **5.8x [3.9x, 9.1x]** | **5.6x [3.7x, 9.0x]** | 6.72 | 6.16 | 1.067 |
| 101500 | random | 2399 | 5602.3 [4996.6, 6188.2] | 5602.3 [5014.3, 6195.4] | +0.0 [+0.0, +0.0] | **0.0x [0.0x, 0.0x]** | **0.0x [0.0x, 0.0x]** | 0.01 | 0.00 | 1.000 |
| 101500 | tbets | 2364 | 6515.4 [5901.7, 7162.9] | 6508.8 [5891.4, 7141.7] | **+6.6 [+5.3, +8.0]** | **4.0x [2.7x, 6.0x]** | **3.7x [2.5x, 5.6x]** | 6.15 | 5.51 | 1.086 |

## The two machines, turn by turn and scenario by scenario (all)

The same (scenario, visit) and seed on both. `Identical`: turns with the same final value. On the turns where
neither plan ends the game: the correlation of the two machines' values, their mean gap and the gap 95% of turns
stay under. Per scenario, each arm's advantage over `emcts` (visits averaged) on each machine: the correlation
of the two, the share with the same sign, their mean gap, and how much the advantage varies across scenarios
(SD), for scale. `Same best arm`: the share of scenarios on which both machines name the same best arm.

| Budget | Arm | Turns | Identical | Corr | Mean gap | 95% under | Scenario corr | Same sign | Scenario gap | Effect SD | Same best arm |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 9500 | emcts | 3200 | 74.0% | 0.9999 | 21.6 | 150 (2.98%) | - | - | - | - | 90.9% |
| 9500 | mcts | 3200 | 95.2% | 1.0000 | 2.4 | 0 (0.00%) | 0.995 | 94.9% | 16.6 | 302.0 | 90.9% |
| 9500 | oep | 3200 | 74.8% | 1.0000 | 16.4 | 110 (1.64%) | 0.992 | 94.5% | 25.0 | 296.1 | 90.9% |
| 9500 | random | 3200 | 100.0% | 1.0000 | 0.0 | 0 (0.00%) | 0.999 | 99.7% | 14.9 | 657.4 | 90.9% |
| 9500 | tbets | 3200 | 82.5% | 1.0000 | 10.4 | 45 (0.87%) | 0.993 | 96.1% | 20.7 | 294.9 | 90.9% |
| 20000 | emcts | 3200 | 47.4% | 0.9997 | 61.5 | 275 (7.23%) | - | - | - | - | 92.4% |
| 20000 | mcts | 3200 | 97.1% | 1.0000 | 0.7 | 0 (0.00%) | 0.985 | 93.7% | 29.8 | 319.1 | 92.4% |
| 20000 | oep | 3200 | 73.5% | 1.0000 | 11.8 | 75 (1.06%) | 0.982 | 94.0% | 34.9 | 310.3 | 92.4% |
| 20000 | random | 3200 | 100.0% | 1.0000 | 0.0 | 0 (0.00%) | 0.997 | 100.0% | 30.0 | 677.1 | 92.4% |
| 20000 | tbets | 3200 | 82.2% | 1.0000 | 9.2 | 45 (0.82%) | 0.981 | 93.2% | 32.4 | 296.8 | 92.4% |
| 101500 | emcts | 2400 | 39.3% | 0.9994 | 97.2 | 468 (9.56%) | - | - | - | - | 92.9% |
| 101500 | mcts | 2400 | 98.5% | 1.0000 | 0.7 | 0 (0.00%) | 0.973 | 93.8% | 49.5 | 351.3 | 92.9% |
| 101500 | oep | 2400 | 94.4% | 1.0000 | 2.7 | 3 (0.03%) | 0.960 | 93.3% | 49.6 | 292.5 | 92.9% |
| 101500 | random | 2400 | 100.0% | 1.0000 | 0.0 | 0 (0.00%) | 0.993 | 100.0% | 49.1 | 721.8 | 92.9% |
| 101500 | tbets | 2400 | 85.4% | 1.0000 | 6.6 | 25 (0.48%) | 0.955 | 91.9% | 51.2 | 287.1 | 92.9% |

## Scope notes

- all: multipliers outside the ladder: mcts@9500 (outside, 0.1x), random@9500 (outside, 0.0x), mcts@20000 (outside, 0.1x), oep@20000 (extrapolated, 6.8x), random@20000 (outside, 0.0x), mcts@101500 (outside, 0.0x), oep@101500 (extrapolated, 5.8x), random@101500 (outside, 0.0x), tbets@101500 (extrapolated, 4.0x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- all: wall-clock rows with over a quarter of pairs clamped to the baseline's time range: oep@9500 (72%), random@9500 (100%), tbets@9500 (80%), random@20000 (100%), mcts@101500 (91%), oep@101500 (32%), random@101500 (100%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- all: terminal turns: up to 1.5% of a leaf (15 of 15 leaves have any). The headline, the contrasts and the design leave their pairs out and count them; the tables keep both strata.
- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; it cannot re-run the stop rule.
- These are single turns replanned from captured positions, not full games.

Full tables in `tables/` (every row names its split and its currency), the same numbers in `report.json`, figures in `figures/` when matplotlib is installed.
