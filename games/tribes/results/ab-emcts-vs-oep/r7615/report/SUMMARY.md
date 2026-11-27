# ab-emcts: MAGAT analysis

Splits: dev, holdout. Baseline `emcts`. A positive difference means the arm finds a better
final sequence than the baseline on the same (scenario, visit) seeds; intervals are scenario-clustered
bootstrap 95% CIs (4,000 resamples), bold where they exclude zero.

## Provenance

|  | dev | holdout |
|---|---|---|
| game | tribes | tribes |
| engine commit | 828b13051d02fc55c6e95a3997d3757337883660 | 828b13051d02fc55c6e95a3997d3757337883660 |
| portfolio identity | ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7 | ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7 |
| currency | compute_weights.properties (b03a8d8f) | compute_weights.properties (b03a8d8f) |
| currency reference | emcts@9500 | emcts@9500 |
| experiment | ab-emcts | ab-emcts |
| experiment sha256 | 20190ce02c95650d6d3c5065a1ccc316bb308e2be139c8dcc204e9738b17c2bb | 20190ce02c95650d6d3c5065a1ccc316bb308e2be139c8dcc204e9738b17c2bb |
| leaves / turns | 24 / 35,200 | 24 / 35,200 |
| written by MAGAT | 82f991aa0340aff33d9bded34113fa0704427cbd (notebook modified) | 82f991aa0340aff33d9bded34113fa0704427cbd (notebook modified) |

## Headline (dev): units and seconds

Units: the paired difference against the baseline, on the pairs where neither plan ends the game. A plan
that ends it scores a sentinel (about +/-2e9) that would swamp any mean, so those pairs are counted apart
(`Ends the game`: the arm's plans / the baseline's) and the mean over all pairs stands beside it. Seconds:
the arm re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.
The budget multiplier is the readable size; `MeanDiff` itself is the raw heuristic delta, never a
result on its own.

### 9,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1568 | **+77.8 [+61.1, +95.9]** | +81.8 | 32 / 32 | dual +85.4, single +70.5 | **+76.5 [+59.5, +94.5]** (clamped 53%) | wins both | **2.9x [2.2x, 3.6x]** | 0.36 |
| comma-z0-random | 1568 | -4.9 [-16.0, +6.0] | -0.4 | 32 / 32 | dual -4.6, single -5.1 | -5.7 [-17.4, +5.1] (clamped 56%) | no win | 0.9x [0.8x, 1.1x] (outside) | -0.03 |
| emcts-comma | 1568 | -1.4 [-8.3, +5.7] | -1.1 | 32 / 32 | dual +1.5, single -4.2 | -2.3 [-9.1, +4.6] (clamped 50%) | no win | 1.0x [0.9x, 1.1x] | -0.01 |
| mcts | 1568 | **-126.9 [-165.5, -91.5]** | -128.9 | 32 / 32 | dual -142.3, single -112.2 | **-133.2 [-174.0, -97.6]** (clamped 21%) | no win | **0.2x [0.1x, 0.3x]** (outside) | -0.43 |
| oep | 1568 | **+77.1 [+39.2, +114.9]** | +77.8 | 32 / 32 | dual +54.3, single +99.0 | **+75.2 [+37.0, +111.3]** (clamped 72%) | wins both | **2.8x [1.7x, 4.6x]** | 0.24 |
| random | 1568 | **-574.8 [-667.0, -487.8]** | -37500602.5 | 2 / 32 | dual -669.7, single -483.6 | **-574.8 [-670.5, -483.6]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.83 |
| tbets | 1568 | **+67.9 [+30.5, +106.1]** | +68.7 | 32 / 32 | dual +44.2, single +90.6 | **+66.9 [+29.9, +104.1]** (clamped 78%) | wins both | **2.5x [1.4x, 4.0x]** | 0.20 |

### 20,000 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1568 | **+161.6 [+131.2, +193.5]** | +164.7 | 32 / 32 | dual +143.2, single +179.3 | **+167.3 [+137.2, +199.6]** (clamped 11%) | wins both | **8.4x [6.5x, 11.2x]** (extrapolated) | 0.51 |
| comma-z0-random | 1568 | +9.2 [-11.1, +29.2] | +13.5 | 32 / 32 | dual -2.7, single +20.6 | +12.2 [-7.7, +32.1] (clamped 13%) | no win | 1.1x [0.8x, 1.4x] | 0.04 |
| emcts-comma | 1568 | +3.3 [-7.4, +13.8] | +3.1 | 32 / 32 | dual +3.4, single +3.2 | +5.8 [-4.9, +16.4] (clamped 13%) | no win | 1.0x [0.8x, 1.1x] | 0.02 |
| mcts | 1568 | **-153.4 [-196.8, -113.7]** | -155.4 | 32 / 32 | dual -169.7, single -137.7 | **-156.6 [-198.9, -116.7]** (clamped 20%) | no win | **0.1x [0.1x, 0.2x]** (outside) | -0.47 |
| oep | 1568 | **+167.3 [+124.8, +209.3]** | +167.3 | 32 / 32 | dual +120.9, single +211.9 | **+179.8 [+138.6, +221.1]** (clamped 7%) | wins both | **8.8x [5.2x, 15.6x]** (extrapolated) | 0.45 |
| random | 1568 | **-626.9 [-722.2, -533.5]** | -37500654.7 | 2 / 32 | dual -727.8, single -530.0 | **-574.8 [-667.2, -488.6]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.88 |
| tbets | 1568 | **+98.1 [+59.2, +137.0]** | +99.1 | 32 / 32 | dual +63.0, single +131.8 | **+113.7 [+74.4, +152.0]** (clamped 5%) | wins both | **3.5x [2.1x, 5.8x]** | 0.28 |

### 101,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1176 | **+330.4 [+283.1, +378.4]** | +335.3 | 24 / 24 | dual +282.4, single +376.4 | **+335.8 [+289.9, +387.0]** (clamped 49%) | wins both | **80.9x [38.2x, 211.1x]** (extrapolated) | 0.77 |
| comma-z0-random | 1176 | **+83.5 [+53.5, +112.0]** | +88.9 | 24 / 24 | dual +67.0, single +99.2 | **+88.4 [+59.4, +115.8]** (clamped 51%) | wins both | **3.0x [2.0x, 4.9x]** (extrapolated) | 0.26 |
| emcts-comma | 1176 | **+64.7 [+45.7, +85.6]** | +64.4 | 24 / 24 | dual +79.1, single +50.9 | **+68.3 [+49.4, +87.4]** (clamped 56%) | wins both | **2.4x [1.7x, 3.4x]** (extrapolated) | 0.24 |
| mcts | 1176 | **-224.2 [-270.0, -181.2]** | -1666892.6 | 23 / 24 | dual -229.7, single -218.8 | **-223.9 [-267.9, -182.8]** (clamped 92%) | no win | **0.1x [0.0x, 0.1x]** (outside) | -0.63 |
| oep | 1176 | **+164.0 [+123.6, +205.7]** | +164.7 | 24 / 24 | dual +126.0, single +200.4 | **+178.3 [+136.8, +218.4]** (clamped 33%) | wins both | **8.9x [4.8x, 19.3x]** (extrapolated) | 0.42 |
| random | 1176 | **-753.2 [-854.0, -660.4]** | -38334113.8 | 1 / 24 | dual -835.5, single -674.2 | **-575.5 [-667.9, -482.9]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -1.01 |
| tbets | 1176 | **+135.4 [+95.5, +175.5]** | +137.8 | 24 / 24 | dual +111.3, single +158.5 | **+156.3 [+118.0, +197.0]** (clamped 19%) | wins both | **6.1x [3.3x, 12.5x]** (extrapolated) | 0.37 |

## Headline (holdout): units and seconds

Units: the paired difference against the baseline, on the pairs where neither plan ends the game. A plan
that ends it scores a sentinel (about +/-2e9) that would swamp any mean, so those pairs are counted apart
(`Ends the game`: the arm's plans / the baseline's) and the mean over all pairs stands beside it. Seconds:
the arm re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.
The budget multiplier is the readable size; `MeanDiff` itself is the raw heuristic delta, never a
result on its own.

### 9,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1584 | **+88.3 [+70.3, +108.0]** | +89.2 | 16 / 16 | dual +87.3, single +89.2 | **+86.7 [+68.6, +106.4]** (clamped 57%) | wins both | **2.7x [2.3x, 3.2x]** | 0.40 |
| comma-z0-random | 1584 | +6.4 [-5.3, +18.8] | +8.1 | 16 / 16 | dual +7.0, single +5.8 | +5.5 [-5.8, +17.6] (clamped 56%) | no win | 1.0x [0.9x, 1.2x] | 0.04 |
| emcts-comma | 1584 | +6.3 [-0.2, +13.5] | +7.2 | 16 / 16 | dual +18.8, single -5.9 | +5.3 [-1.2, +12.6] (clamped 50%) | no win | 1.1x [1.0x, 1.2x] | 0.05 |
| mcts | 1584 | **-167.7 [-213.2, -124.0]** | -169.1 | 16 / 16 | dual -207.0, single -129.3 | **-174.6 [-220.8, -131.0]** (clamped 23%) | no win | **0.1x [0.1x, 0.2x]** (outside) | -0.48 |
| oep | 1584 | **+64.9 [+20.1, +109.6]** | +65.0 | 16 / 16 | dual -2.3, single +130.8 | **+62.4 [+17.7, +107.1]** (clamped 72%) | wins both | **2.1x [1.2x, 3.4x]** | 0.18 |
| random | 1584 | **-627.3 [-724.1, -536.8]** | -20000644.4 | 0 / 16 | dual -684.9, single -570.9 | **-627.3 [-720.9, -536.4]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.89 |
| tbets | 1584 | **+49.0 [+4.6, +92.2]** | -2499952.8 | 14 / 16 | dual -13.6, single +110.4 | **+47.6 [+2.5, +92.9]** (clamped 79%) | wins both | 1.7x [1.0x, 2.8x] | 0.13 |

### 20,000 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1584 | **+162.4 [+135.1, +191.4]** | +162.7 | 16 / 16 | dual +126.1, single +197.9 | **+168.5 [+139.3, +198.3]** (clamped 11%) | wins both | **5.9x [4.5x, 7.8x]** (extrapolated) | 0.51 |
| comma-z0-random | 1584 | +10.2 [-6.1, +26.4] | +10.5 | 16 / 16 | dual +3.3, single +16.9 | +14.8 [-0.9, +30.5] (clamped 13%) | no win | 1.1x [0.9x, 1.3x] | 0.05 |
| emcts-comma | 1584 | +11.6 [-0.7, +26.5] | +11.1 | 16 / 16 | dual +18.0, single +5.3 | **+13.9 [+1.6, +28.9]** (clamped 15%) | wall-clock only | 1.2x [1.0x, 1.4x] | 0.05 |
| mcts | 1584 | **-200.0 [-249.0, -153.0]** | -202.0 | 16 / 16 | dual -243.8, single -157.1 | **-205.3 [-253.9, -159.3]** (clamped 22%) | no win | **0.1x [0.0x, 0.1x]** (outside) | -0.53 |
| oep | 1584 | **+159.1 [+115.0, +203.8]** | +157.9 | 16 / 16 | dual +76.3, single +240.3 | **+172.3 [+128.4, +215.1]** (clamped 8%) | wins both | **5.5x [3.4x, 9.2x]** (extrapolated) | 0.40 |
| random | 1584 | **-688.8 [-785.1, -595.0]** | -20000707.0 | 0 / 16 | dual -748.4, single -630.4 | **-627.3 [-717.7, -537.7]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -0.94 |
| tbets | 1584 | **+74.8 [+32.2, +117.4]** | +74.0 | 16 / 16 | dual +5.8, single +142.4 | **+93.4 [+51.3, +134.9]** | wins both | **2.2x [1.4x, 3.5x]** | 0.19 |

### 101,500 units

| Arm | Pairs | MeanDiff [CI] | All pairs | Ends the game | By source | Wall-clock [CI] | Verdict | Budget multiplier | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-z0-coarse | 1188 | **+300.7 [+260.7, +345.0]** | +302.2 | 12 / 12 | dual +234.0, single +366.1 | **+309.5 [+267.5, +352.0]** (clamped 48%) | wins both | **27.7x [16.2x, 53.6x]** (extrapolated) | 0.73 |
| comma-z0-random | 1188 | **+93.3 [+62.8, +123.7]** | +95.8 | 12 / 12 | dual +49.9, single +135.7 | **+100.3 [+70.8, +129.6]** (clamped 56%) | wins both | **2.8x [1.9x, 4.3x]** (extrapolated) | 0.28 |
| emcts-comma | 1188 | **+59.1 [+36.0, +84.3]** | +58.4 | 12 / 12 | dual +59.2, single +58.9 | **+63.7 [+40.5, +90.0]** (clamped 58%) | wins both | **1.9x [1.5x, 2.6x]** (extrapolated) | 0.20 |
| mcts | 1188 | **-284.8 [-338.3, -232.9]** | -286.1 | 12 / 12 | dual -319.2, single -251.0 | **-284.5 [-338.9, -231.5]** (clamped 88%) | no win | **0.0x [0.0x, 0.1x]** (outside) | -0.66 |
| oep | 1188 | **+128.8 [+87.3, +169.9]** | +128.9 | 12 / 12 | dual +58.1, single +198.2 | **+147.6 [+107.3, +188.2]** (clamped 34%) | wins both | **4.1x [2.5x, 7.5x]** (extrapolated) | 0.32 |
| random | 1188 | **-831.4 [-940.6, -733.4]** | -20000848.8 | 0 / 12 | dual -866.6, single -796.9 | **-624.9 [-716.7, -538.0]** (clamped 100%) | no win | **0.0x [0.0x, 0.0x]** (outside) | -1.04 |
| tbets | 1188 | **+94.0 [+52.4, +133.7]** | +94.6 | 12 / 12 | dual +28.8, single +158.0 | **+124.1 [+83.8, +163.6]** (clamped 16%) | wins both | **2.8x [1.8x, 4.9x]** (extrapolated) | 0.25 |

## Budget multiplier (dev)

How much budget `emcts` would need to match each arm: the baseline's mean plan value per level,
interpolated log-linearly in budget, solved for the arm's mean value, over the cells where every baseline
level and the arm ran and none ended terminal (values, not search gains: each arm's `initial` is its own
starting plan). `extrapolated` means the crossing lies past the top of the ladder: a lower bound, since
value is concave in budget. The wall-clock column solves the same crossing in the baseline's own seconds
and divides by the arm's; `arm/baseline time` is the arm's plan time over the baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 9,500: value 5983.2, 20,000: value 6038.8, 101,500: value 6161.0. Its top step is 5.1x the units and 5.2x the seconds.

| Budget | Arm | Cells | Arm value | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 1176 | 6062.7 | **2.9x [2.2x, 3.6x]** | inside | **3.0x [2.3x, 3.7x]** | 1.00 |
| 9500 | comma-z0-random | 1176 | 5977.5 | 0.9x [0.8x, 1.1x] | outside | 0.9x [0.8x, 1.1x] | 0.99 |
| 9500 | emcts-comma | 1176 | 5983.6 | 1.0x [0.9x, 1.1x] | inside | 1.0x [0.9x, 1.1x] | 1.00 |
| 9500 | mcts | 1176 | 5854.9 | **0.2x [0.1x, 0.3x]** | outside | **0.2x [0.1x, 0.3x]** | 1.14 |
| 9500 | oep | 1176 | 6059.8 | **2.8x [1.7x, 4.6x]** | inside | **2.9x [1.7x, 4.7x]** | 0.99 |
| 9500 | random | 1176 | 5407.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.2x]** | 0.01 |
| 9500 | tbets | 1176 | 6051.0 | **2.5x [1.4x, 4.0x]** | inside | **2.7x [1.6x, 4.4x]** | 0.93 |
| 20000 | comma-z0-coarse | 1176 | 6198.4 | **8.4x [6.5x, 11.2x]** | extrapolated | **8.6x [6.7x, 11.6x]** | 0.99 |
| 20000 | comma-z0-random | 1176 | 6043.6 | 1.1x [0.8x, 1.4x] | inside | 1.1x [0.8x, 1.4x] | 1.01 |
| 20000 | emcts-comma | 1176 | 6037.0 | 1.0x [0.8x, 1.1x] | inside | 1.0x [0.8x, 1.2x] | 0.99 |
| 20000 | mcts | 1176 | 5880.3 | **0.1x [0.1x, 0.2x]** | outside | **0.1x [0.1x, 0.2x]** | 1.17 |
| 20000 | oep | 1176 | 6202.5 | **8.8x [5.2x, 15.6x]** | extrapolated | **9.2x [5.5x, 16.7x]** | 0.98 |
| 20000 | random | 1176 | 5407.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.02 |
| 20000 | tbets | 1176 | 6133.5 | **3.5x [2.1x, 5.8x]** | inside | **3.9x [2.4x, 6.4x]** | 0.92 |
| 101500 | comma-z0-coarse | 1176 | 6491.3 | **80.9x [38.2x, 211.1x]** | extrapolated | **82.2x [38.5x, 214.1x]** | 1.03 |
| 101500 | comma-z0-random | 1176 | 6244.4 | **3.0x [2.0x, 4.9x]** | extrapolated | **3.0x [2.0x, 4.9x]** | 1.01 |
| 101500 | emcts-comma | 1176 | 6225.7 | **2.4x [1.7x, 3.4x]** | extrapolated | **2.4x [1.7x, 3.4x]** | 1.01 |
| 101500 | mcts | 1176 | 5936.8 | **0.1x [0.0x, 0.1x]** | outside | **0.0x [0.0x, 0.1x]** | 1.33 |
| 101500 | oep | 1176 | 6324.9 | **8.9x [4.8x, 19.3x]** | extrapolated | **9.2x [5.0x, 20.4x]** | 0.98 |
| 101500 | random | 1176 | 5407.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.2x]** | 0.00 |
| 101500 | tbets | 1176 | 6296.4 | **6.1x [3.3x, 12.5x]** | extrapolated | **6.9x [3.7x, 14.4x]** | 0.90 |

## Budget multiplier (holdout)

How much budget `emcts` would need to match each arm: the baseline's mean plan value per level,
interpolated log-linearly in budget, solved for the arm's mean value, over the cells where every baseline
level and the arm ran and none ended terminal (values, not search gains: each arm's `initial` is its own
starting plan). `extrapolated` means the crossing lies past the top of the ladder: a lower bound, since
value is concave in budget. The wall-clock column solves the same crossing in the baseline's own seconds
and divides by the arm's; `arm/baseline time` is the arm's plan time over the baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 9,500: value 6431.6, 20,000: value 6491.1, 101,500: value 6638.1. Its top step is 5.1x the units and 5.2x the seconds.

| Budget | Arm | Cells | Arm value | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 1188 | 6514.5 | **2.7x [2.3x, 3.2x]** | inside | **2.8x [2.3x, 3.3x]** | 0.99 |
| 9500 | comma-z0-random | 1188 | 6433.9 | 1.0x [0.9x, 1.2x] | inside | 1.0x [0.9x, 1.2x] | 0.99 |
| 9500 | emcts-comma | 1188 | 6436.6 | 1.1x [1.0x, 1.2x] | inside | 1.1x [1.0x, 1.2x] | 1.00 |
| 9500 | mcts | 1188 | 6261.1 | **0.1x [0.1x, 0.2x]** | outside | **0.1x [0.0x, 0.2x]** | 1.13 |
| 9500 | oep | 1188 | 6490.8 | **2.1x [1.2x, 3.4x]** | inside | **2.1x [1.2x, 3.4x]** | 0.98 |
| 9500 | random | 1188 | 5806.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.01 |
| 9500 | tbets | 1188 | 6475.3 | 1.7x [1.0x, 2.8x] | inside | **1.9x [1.1x, 3.0x]** | 0.93 |
| 20000 | comma-z0-coarse | 1188 | 6651.8 | **5.9x [4.5x, 7.8x]** | extrapolated | **6.0x [4.6x, 7.9x]** | 1.00 |
| 20000 | comma-z0-random | 1188 | 6500.6 | 1.1x [0.9x, 1.3x] | inside | 1.1x [0.9x, 1.3x] | 1.02 |
| 20000 | emcts-comma | 1188 | 6504.6 | 1.2x [1.0x, 1.4x] | inside | 1.2x [1.0x, 1.4x] | 1.00 |
| 20000 | mcts | 1188 | 6290.4 | **0.1x [0.0x, 0.1x]** | outside | **0.1x [0.0x, 0.1x]** | 1.19 |
| 20000 | oep | 1188 | 6645.6 | **5.5x [3.4x, 9.2x]** | extrapolated | **5.7x [3.6x, 9.5x]** | 0.99 |
| 20000 | random | 1188 | 5806.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.1x]** | 0.02 |
| 20000 | tbets | 1188 | 6564.2 | **2.2x [1.4x, 3.5x]** | inside | **2.5x [1.5x, 3.8x]** | 0.92 |
| 101500 | comma-z0-coarse | 1188 | 6938.8 | **27.7x [16.2x, 53.6x]** | extrapolated | **27.9x [16.2x, 54.3x]** | 1.03 |
| 101500 | comma-z0-random | 1188 | 6731.4 | **2.8x [1.9x, 4.3x]** | extrapolated | **2.7x [1.9x, 4.2x]** | 1.04 |
| 101500 | emcts-comma | 1188 | 6697.2 | **1.9x [1.5x, 2.6x]** | extrapolated | **1.9x [1.5x, 2.6x]** | 1.02 |
| 101500 | mcts | 1188 | 6353.4 | **0.0x [0.0x, 0.1x]** | outside | **0.0x [0.0x, 0.0x]** | 1.31 |
| 101500 | oep | 1188 | 6766.9 | **4.1x [2.5x, 7.5x]** | extrapolated | **4.3x [2.6x, 7.7x]** | 0.98 |
| 101500 | random | 1188 | 5806.7 | **0.0x [0.0x, 0.0x]** | outside | **0.0x [0.0x, 0.2x]** | 0.00 |
| 101500 | tbets | 1188 | 6732.2 | **2.8x [1.8x, 4.9x]** | extrapolated | **3.2x [2.0x, 5.5x]** | 0.89 |

## Contrasts (dev)

The spec's own paired comparisons, arm A minus arm B, on the pairs where neither
plan ends the game; the mean over all pairs beside it.

| Budget | A - B | Label | Pairs | MeanDiff [CI] | All pairs | WinRate |
|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1568 | +0.7 [-37.2, +43.6] | +4.1 | 0.461 |
| 9500 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1568 | +9.9 [-29.5, +52.9] | +13.1 | 0.487 |
| 9500 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1568 | **+79.2 [+62.5, +96.7]** | +83.0 | 0.734 |
| 9500 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1568 | **+82.7 [+63.9, +102.3]** | +82.2 | 0.788 |
| 20000 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1568 | -5.7 [-43.1, +33.7] | -2.6 | 0.465 |
| 20000 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1568 | **+63.6 [+26.8, +101.8]** | +65.6 | 0.575 |
| 20000 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1568 | **+158.4 [+130.2, +189.3]** | +161.5 | 0.774 |
| 20000 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1568 | **+152.5 [+123.9, +184.0]** | +151.2 | 0.795 |
| 101500 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1176 | **+166.4 [+116.6, +220.2]** | +170.5 | 0.675 |
| 101500 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1176 | **+195.0 [+146.5, +246.5]** | +197.4 | 0.734 |
| 101500 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1176 | **+265.7 [+221.7, +314.1]** | +270.8 | 0.823 |
| 101500 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1176 | **+246.9 [+204.5, +291.1]** | +246.3 | 0.825 |

## Contrasts (holdout)

The spec's own paired comparisons, arm A minus arm B, on the pairs where neither
plan ends the game; the mean over all pairs beside it.

| Budget | A - B | Label | Pairs | MeanDiff [CI] | All pairs | WinRate |
|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1584 | +23.3 [-21.0, +71.2] | +24.2 | 0.476 |
| 9500 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1584 | +39.3 [-3.6, +84.3] | +2500042.0 | 0.497 |
| 9500 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1584 | **+81.9 [+65.1, +101.2]** | +82.0 | 0.699 |
| 9500 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1584 | **+81.9 [+66.6, +99.6]** | +81.1 | 0.766 |
| 20000 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1584 | +3.3 [-35.1, +44.1] | +4.9 | 0.485 |
| 20000 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1584 | **+87.6 [+48.6, +129.3]** | +88.7 | 0.571 |
| 20000 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1584 | **+150.8 [+123.8, +179.1]** | +151.7 | 0.752 |
| 20000 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1584 | **+152.2 [+126.0, +182.5]** | +152.3 | 0.783 |
| 101500 | comma-z0-coarse - oep | H1: AB-EMCTS against OEP | 1188 | **+171.9 [+125.4, +220.1]** | +173.3 | 0.679 |
| 101500 | comma-z0-coarse - tbets | H2: AB-EMCTS against TBETS | 1188 | **+206.7 [+161.5, +253.6]** | +207.5 | 0.726 |
| 101500 | comma-z0-coarse - emcts-comma | H3: AB-EMCTS against the same search with exhaustive repair | 1188 | **+241.6 [+203.6, +281.9]** | +243.8 | 0.789 |
| 101500 | comma-z0-coarse - comma-z0-random | H4: the learned table against an arbitrary legal action | 1188 | **+207.4 [+171.1, +246.4]** | +206.4 | 0.782 |

## Spend, time and plans (dev)

`Spend` is the mean turn's units, its initialization included, over the level: bold above 1.10x, as the calibration flags a cell, with the share of turns above it beside it.
`Seconds` is the mean turn's wall-clock; `Level` is the time the level was set to, the emcts turn's mean, single-threaded on AMD EPYC 9534 64-Core Processor (`levels.json`). `Unaccounted` is the run's audit: the share
of the plan time that the currency's prices do not account for, on the threads the leaf ran on. On many
threads it holds contention as well, and turns take longer than their level, unevenly across arms: the
calibration's own audit, on one idle thread, is the clean measure. `Actions` is the mean plan's length, its
end of turn included; `Ends the game` the share of plans that do.

| Budget | Arm | Turns | Spend | Over 1.10x | Seconds | Level | Actions | Ends the game | Unaccounted (threads) |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 1600 | 1.00x | 0% | 0.58 | 0.50 | 15.4 | 2.0% | 9% on 54 |
| 9500 | comma-z0-random | 1600 | 1.00x | 0% | 0.57 | 0.50 | 16.4 | 2.0% | 8% on 54 |
| 9500 | emcts | 1600 | 1.00x | 0% | 0.58 | 0.50 | 15.7 | 2.0% | 9% on 54 |
| 9500 | emcts-comma | 1600 | 1.00x | 0% | 0.58 | 0.50 | 15.9 | 2.0% | 9% on 54 |
| 9500 | mcts | 1600 | 1.00x | 0% | 0.66 | 0.50 | 3.8 | 2.0% | 20% on 54 |
| 9500 | oep | 1600 | 1.00x | 0% | 0.57 | 0.50 | 17.9 | 2.0% | 7% on 54 |
| 9500 | random | 1600 | 0.00x | 0% | 0.01 | 0.50 | 15.6 | 0.1% | - |
| 9500 | tbets | 1600 | 1.00x | 0% | 0.54 | 0.50 | 17.2 | 2.0% | 2% on 54 |
| 20000 | comma-z0-coarse | 1600 | 1.00x | 0% | 1.23 | 1.00 | 16.3 | 2.0% | 10% on 54 |
| 20000 | comma-z0-random | 1600 | 1.00x | 0% | 1.25 | 1.00 | 17.0 | 2.0% | 11% on 54 |
| 20000 | emcts | 1600 | 1.00x | 0% | 1.24 | 1.00 | 15.9 | 2.0% | 11% on 54 |
| 20000 | emcts-comma | 1600 | 1.00x | 0% | 1.23 | 1.00 | 15.9 | 2.0% | 10% on 54 |
| 20000 | mcts | 1600 | 1.00x | 0% | 1.44 | 1.00 | 4.2 | 2.0% | 23% on 54 |
| 20000 | oep | 1600 | 1.00x | 0% | 1.20 | 1.00 | 17.9 | 2.0% | 8% on 54 |
| 20000 | random | 1600 | 0.00x | 0% | 0.02 | 1.00 | 15.6 | 0.1% | - |
| 20000 | tbets | 1600 | 1.00x | 0% | 1.14 | 1.00 | 17.3 | 2.0% | 3% on 54 |
| 101500 | comma-z0-coarse | 1200 | 1.00x | 0% | 6.58 | 5.00 | 21.5 | 2.0% | 15% on 54 |
| 101500 | comma-z0-random | 1200 | 1.00x | 0% | 6.48 | 5.00 | 20.7 | 2.0% | 13% on 54 |
| 101500 | emcts | 1200 | 1.00x | 0% | 6.40 | 5.00 | 16.4 | 2.0% | 12% on 54 |
| 101500 | emcts-comma | 1200 | 1.00x | 0% | 6.50 | 5.00 | 17.7 | 2.0% | 14% on 54 |
| 101500 | mcts | 1200 | 1.00x | 0% | 8.50 | 5.00 | 5.1 | 1.9% | 34% on 54 |
| 101500 | oep | 1200 | 1.00x | 0% | 6.24 | 5.00 | 17.9 | 2.0% | 10% on 54 |
| 101500 | random | 1200 | 0.00x | 0% | 0.01 | 5.00 | 15.6 | 0.1% | - |
| 101500 | tbets | 1200 | 1.00x | 0% | 5.72 | 5.00 | 17.4 | 2.0% | 2% on 54 |

## Spend, time and plans (holdout)

`Spend` is the mean turn's units, its initialization included, over the level: bold above 1.10x, as the calibration flags a cell, with the share of turns above it beside it.
`Seconds` is the mean turn's wall-clock; `Level` is the time the level was set to, the emcts turn's mean, single-threaded on AMD EPYC 9534 64-Core Processor (`levels.json`). `Unaccounted` is the run's audit: the share
of the plan time that the currency's prices do not account for, on the threads the leaf ran on. On many
threads it holds contention as well, and turns take longer than their level, unevenly across arms: the
calibration's own audit, on one idle thread, is the clean measure. `Actions` is the mean plan's length, its
end of turn included; `Ends the game` the share of plans that do.

| Budget | Arm | Turns | Spend | Over 1.10x | Seconds | Level | Actions | Ends the game | Unaccounted (threads) |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 1600 | 1.00x | 0% | 0.59 | 0.50 | 15.7 | 1.0% | 10% on 54 |
| 9500 | comma-z0-random | 1600 | 1.00x | 0% | 0.59 | 0.50 | 16.7 | 1.0% | 10% on 54 |
| 9500 | emcts | 1600 | 1.00x | 0% | 0.59 | 0.50 | 16.1 | 1.0% | 11% on 54 |
| 9500 | emcts-comma | 1600 | 1.00x | 0% | 0.59 | 0.50 | 16.2 | 1.0% | 11% on 54 |
| 9500 | mcts | 1600 | 1.00x | 0% | 0.67 | 0.50 | 3.9 | 1.0% | 22% on 54 |
| 9500 | oep | 1600 | 1.00x | 0% | 0.58 | 0.50 | 18.2 | 1.0% | 9% on 54 |
| 9500 | random | 1600 | 0.00x | 0% | 0.01 | 0.50 | 15.6 | 0.0% | - |
| 9500 | tbets | 1600 | 1.00x | 0% | 0.55 | 0.50 | 17.5 | 0.9% | 4% on 54 |
| 20000 | comma-z0-coarse | 1600 | 1.00x | 0% | 1.25 | 1.00 | 16.5 | 1.0% | 11% on 54 |
| 20000 | comma-z0-random | 1600 | 1.00x | 0% | 1.27 | 1.00 | 17.4 | 1.0% | 13% on 54 |
| 20000 | emcts | 1600 | 1.00x | 0% | 1.25 | 1.00 | 16.4 | 1.0% | 11% on 54 |
| 20000 | emcts-comma | 1600 | 1.00x | 0% | 1.25 | 1.00 | 16.4 | 1.0% | 11% on 54 |
| 20000 | mcts | 1600 | 1.00x | 0% | 1.48 | 1.00 | 4.2 | 1.0% | 25% on 54 |
| 20000 | oep | 1600 | 1.00x | 0% | 1.23 | 1.00 | 18.2 | 1.0% | 10% on 54 |
| 20000 | random | 1600 | 0.00x | 0% | 0.02 | 1.00 | 15.6 | 0.0% | - |
| 20000 | tbets | 1600 | 1.00x | 0% | 1.14 | 1.00 | 17.6 | 1.0% | 3% on 54 |
| 101500 | comma-z0-coarse | 1200 | 1.00x | 0% | 6.61 | 5.00 | 22.1 | 1.0% | 15% on 54 |
| 101500 | comma-z0-random | 1200 | 1.00x | 0% | 6.68 | 5.00 | 21.2 | 1.0% | 16% on 54 |
| 101500 | emcts | 1200 | 1.00x | 0% | 6.44 | 5.00 | 17.1 | 1.0% | 13% on 54 |
| 101500 | emcts-comma | 1200 | 1.00x | 0% | 6.55 | 5.00 | 18.3 | 1.0% | 14% on 54 |
| 101500 | mcts | 1200 | 1.00x | 0% | 8.44 | 5.00 | 5.2 | 1.0% | 33% on 54 |
| 101500 | oep | 1200 | 1.00x | 0% | 6.31 | 5.00 | 18.2 | 1.0% | 11% on 54 |
| 101500 | random | 1200 | 0.00x | 0% | 0.01 | 5.00 | 15.6 | 0.0% | - |
| 101500 | tbets | 1200 | 1.00x | 0% | 5.75 | 5.00 | 17.7 | 1.0% | 2% on 54 |

## Design (dev): scenarios versus visits

On the pairs where neither plan ends the game, as the headline is.
ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 196 | 8.0 | 0.224 | 8.9 | 7.4 | 19.7% | 611 | 1568 |
| 9500 | comma-z0-random | 196 | 8.0 | 0.116 | 5.7 | 4.1 | 39.9% | 867 | 1568 |
| 9500 | emcts-comma | 196 | 8.0 | 0.009 | 3.6 | 0.9 | 290.8% | 1478 | 1568 |
| 9500 | mcts | 196 | 8.0 | 0.822 | 19.4 | 19.2 | 1.3% | 232 | 1568 |
| 9500 | oep | 196 | 8.0 | 0.627 | 19.1 | 18.4 | 3.7% | 291 | 1568 |
| 9500 | random | 196 | 8.0 | 0.850 | 46.3 | 45.8 | 1.1% | 226 | 1568 |
| 9500 | tbets | 196 | 8.0 | 0.562 | 19.1 | 18.2 | 4.8% | 318 | 1568 |
| 20000 | comma-z0-coarse | 196 | 8.0 | 0.410 | 15.9 | 14.6 | 8.6% | 405 | 1568 |
| 20000 | comma-z0-random | 196 | 8.0 | 0.334 | 10.3 | 9.2 | 11.8% | 470 | 1568 |
| 20000 | emcts-comma | 196 | 8.0 | 0.024 | 5.3 | 2.2 | 144.5% | 1339 | 1568 |
| 20000 | mcts | 196 | 8.0 | 0.773 | 20.8 | 20.4 | 1.8% | 245 | 1568 |
| 20000 | oep | 196 | 8.0 | 0.590 | 21.3 | 20.4 | 4.3% | 306 | 1568 |
| 20000 | random | 196 | 8.0 | 0.836 | 47.4 | 46.9 | 1.2% | 229 | 1568 |
| 20000 | tbets | 196 | 8.0 | 0.572 | 20.1 | 19.2 | 4.6% | 313 | 1568 |
| 101500 | comma-z0-coarse | 196 | 6.0 | 0.565 | 24.6 | 23.2 | 6.2% | 307 | 1176 |
| 101500 | comma-z0-random | 196 | 6.0 | 0.287 | 14.7 | 12.4 | 18.9% | 483 | 1176 |
| 101500 | emcts-comma | 196 | 6.0 | 0.128 | 9.9 | 6.8 | 46.0% | 716 | 1176 |
| 101500 | mcts | 196 | 6.0 | 0.704 | 22.0 | 21.3 | 3.4% | 260 | 1176 |
| 101500 | oep | 196 | 6.0 | 0.464 | 20.9 | 19.1 | 9.2% | 354 | 1176 |
| 101500 | random | 196 | 6.0 | 0.829 | 49.6 | 48.7 | 1.7% | 229 | 1176 |
| 101500 | tbets | 196 | 6.0 | 0.504 | 20.3 | 18.8 | 7.9% | 334 | 1176 |

Median ICC 0.562. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 8 (median over arms).

## Design (holdout): scenarios versus visits

On the pairs where neither plan ends the game, as the headline is.
ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 9500 | comma-z0-coarse | 198 | 8.0 | 0.287 | 9.7 | 8.5 | 14.5% | 527 | 1584 |
| 9500 | comma-z0-random | 198 | 8.0 | 0.167 | 6.0 | 4.7 | 27.3% | 729 | 1584 |
| 9500 | emcts-comma | 198 | 8.0 | 0.013 | 3.5 | 1.1 | 229.3% | 1456 | 1584 |
| 9500 | mcts | 198 | 8.0 | 0.863 | 23.3 | 23.1 | 1.0% | 225 | 1584 |
| 9500 | oep | 198 | 8.0 | 0.727 | 22.9 | 22.4 | 2.3% | 260 | 1584 |
| 9500 | random | 198 | 8.0 | 0.887 | 47.4 | 47.0 | 0.8% | 220 | 1584 |
| 9500 | tbets | 198 | 8.0 | 0.620 | 22.7 | 21.9 | 3.8% | 297 | 1584 |
| 20000 | comma-z0-coarse | 198 | 8.0 | 0.323 | 14.5 | 12.9 | 12.3% | 486 | 1584 |
| 20000 | comma-z0-random | 198 | 8.0 | 0.178 | 8.4 | 6.7 | 25.6% | 706 | 1584 |
| 20000 | emcts-comma | 198 | 8.0 | 0.077 | 6.9 | 4.4 | 58.0% | 1029 | 1584 |
| 20000 | mcts | 198 | 8.0 | 0.811 | 24.4 | 24.1 | 1.4% | 237 | 1584 |
| 20000 | oep | 198 | 8.0 | 0.621 | 22.9 | 22.1 | 3.7% | 296 | 1584 |
| 20000 | random | 198 | 8.0 | 0.875 | 49.0 | 48.6 | 0.9% | 222 | 1584 |
| 20000 | tbets | 198 | 8.0 | 0.597 | 22.1 | 21.3 | 4.1% | 306 | 1584 |
| 101500 | comma-z0-coarse | 198 | 6.0 | 0.460 | 21.7 | 19.8 | 9.4% | 360 | 1188 |
| 101500 | comma-z0-random | 198 | 6.0 | 0.285 | 15.3 | 12.8 | 19.1% | 490 | 1188 |
| 101500 | emcts-comma | 198 | 6.0 | 0.192 | 12.3 | 9.4 | 30.4% | 606 | 1188 |
| 101500 | mcts | 198 | 6.0 | 0.758 | 27.5 | 26.8 | 2.6% | 248 | 1188 |
| 101500 | oep | 198 | 6.0 | 0.446 | 20.8 | 18.9 | 9.9% | 368 | 1188 |
| 101500 | random | 198 | 6.0 | 0.850 | 53.2 | 52.4 | 1.5% | 226 | 1188 |
| 101500 | tbets | 198 | 6.0 | 0.483 | 20.6 | 18.9 | 8.6% | 348 | 1188 |

Median ICC 0.483. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 11 (median over arms).

## Scope notes

- dev: multipliers outside the ladder: comma-z0-random@9500 (outside, 0.9x), mcts@9500 (outside, 0.2x), random@9500 (outside, 0.0x), comma-z0-coarse@20000 (extrapolated, 8.4x), mcts@20000 (outside, 0.1x), oep@20000 (extrapolated, 8.8x), random@20000 (outside, 0.0x), comma-z0-coarse@101500 (extrapolated, 80.9x), comma-z0-random@101500 (extrapolated, 3.0x), emcts-comma@101500 (extrapolated, 2.4x), mcts@101500 (outside, 0.1x), oep@101500 (extrapolated, 8.9x), random@101500 (outside, 0.0x), tbets@101500 (extrapolated, 6.1x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- dev: wall-clock rows with over a quarter of pairs clamped to the baseline's time range: comma-z0-coarse@9500 (53%), comma-z0-random@9500 (56%), emcts-comma@9500 (50%), oep@9500 (72%), random@9500 (100%), tbets@9500 (78%), random@20000 (100%), comma-z0-coarse@101500 (49%), comma-z0-random@101500 (51%), emcts-comma@101500 (56%), mcts@101500 (92%), oep@101500 (33%), random@101500 (100%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- dev: terminal turns: up to 2.0% of a leaf (24 of 24 leaves have any). The headline, the contrasts and the design leave their pairs out and count them; the tables keep both strata.
- holdout: multipliers outside the ladder: mcts@9500 (outside, 0.1x), random@9500 (outside, 0.0x), comma-z0-coarse@20000 (extrapolated, 5.9x), mcts@20000 (outside, 0.1x), oep@20000 (extrapolated, 5.5x), random@20000 (outside, 0.0x), comma-z0-coarse@101500 (extrapolated, 27.7x), comma-z0-random@101500 (extrapolated, 2.8x), emcts-comma@101500 (extrapolated, 1.9x), mcts@101500 (outside, 0.0x), oep@101500 (extrapolated, 4.1x), random@101500 (outside, 0.0x), tbets@101500 (extrapolated, 2.8x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- holdout: wall-clock rows with over a quarter of pairs clamped to the baseline's time range: comma-z0-coarse@9500 (57%), comma-z0-random@9500 (56%), emcts-comma@9500 (50%), oep@9500 (72%), random@9500 (100%), tbets@9500 (79%), random@20000 (100%), comma-z0-coarse@101500 (48%), comma-z0-random@101500 (56%), emcts-comma@101500 (58%), mcts@101500 (88%), oep@101500 (34%), random@101500 (100%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- holdout: terminal turns: up to 1.0% of a leaf (21 of 24 leaves have any). The headline, the contrasts and the design leave their pairs out and count them; the tables keep both strata.
- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; it cannot re-run the stop rule.
- These are single turns replanned from captured positions, not full games.

Full tables in `tables/` (every row names its split and its currency), the same numbers in `report.json`, figures in `figures/` when matplotlib is installed.
