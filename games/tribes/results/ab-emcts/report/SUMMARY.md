# ab-emcts-dev + ab-emcts-holdout: MAGAT analysis

Splits: dev, holdout. Baseline `emcts-comma`. A positive difference means the arm finds a better
final sequence than the baseline on the same (scenario, visit) seeds; intervals are scenario-clustered
bootstrap 95% CIs (4,000 resamples), bold where they exclude zero.

## Provenance

|  | dev | holdout |
|---|---|---|
| game | tribes | tribes |
| engine commit | the thesis tree (Tribes-master) | the thesis tree (Tribes-master) |
| portfolio identity | ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7 | ec243fd7fe81b4f6e2635b654823348d7fe2838faf3711258f425c04f63b36a7 |
| currency | compute_weights.properties (3076693a) | compute_weights.properties (3076693a) |
| currency reference | emcts@40000 | emcts@40000 |
| experiment | ab-emcts-dev | ab-emcts-holdout |
| experiment sha256 | 5d200d801cbbea1a408dbc9c35d51a683dab7e6ab76d2520b23d490ae2b485b7 | 1cb4d1e5357429a3abfc04bea2753fdd1c8a34881562dbd648290490a744ab31 |
| frozen choices sha256 | - | 60110f1f285d225a5c13afca84828ccd5af567e8f4c74f26ead91a13a82153a7 |
| pre-registration | - | - |
| leaves / turns | 51 / 74,000 | 16 / 22,400 |
| written by MAGAT | 0.1.0-m6 | 0.1.0-m6 |
| imported (dev) | stageE.zip (R7615, stages D and E, 2026-08-29 to 2026-08-31): a thesis run tree (Stage1Runner): no copy or evaluation counts, no initialization split, terminal turns by the |final| >= 1e9 cutoff |  |
| imported (holdout) | stageF.zip (R7615, stage F, 2026-09-02): a thesis run tree (Stage1Runner): no copy or evaluation counts, no initialization split, terminal turns by the |final| >= 1e9 cutoff |  |

## Replication: development and holdout side by side

`yes`: the holdout estimate lands inside the development interval and its own interval excludes
the null (0, or 1x for a multiplier). `ns both`: neither interval excludes it, a null that
replicated. Every headline appears as two numbers or not at all.

**What a `no` means.** When the two halves ran on the same machine in the same currency (the
provenance table says whether they did), they differ in scenarios and in nothing else, so a `no` is
about the scenarios or about the arm, never about the device. It has one of two causes. Either the
development estimate was selected (it was the best of several) and shrinks toward its true value
on scenarios that played no part in the choice: the winner's curse, and the holdout doing its job.
Or the two halves did not measure the same arm: a price or a definition changed between them, which
a MAGAT run's per-leaf currency lock refuses. Name the cause beside the row; a `no` is not noise to
be averaged away.

| Kind | Budget | Comparison | Development | Holdout | Replicates |
|---|---|---|---|---|---|
| headline | 80000 | comma-rand15 | **+139.8 [+111.8, +167.9]** | **+133.8 [+108.0, +160.5]** | yes |
| headline | 80000 | comma-short15 | **+118.9 [+95.2, +142.8]** | **+112.7 [+85.6, +139.1]** | yes |
| headline | 80000 | comma-short15-coarse | **+206.5 [+173.5, +242.5]** | **+199.9 [+166.2, +235.5]** | yes |
| headline | 80000 | comma-z0-coarse | **+268.1 [+228.9, +308.0]** | **+244.3 [+207.3, +282.8]** | yes |
| headline | 80000 | comma-z0-fine | **+47.4 [+20.8, +72.5]** | **+36.4 [+6.2, +63.8]** | yes |
| headline | 80000 | comma-z0-mid | **+169.0 [+135.0, +203.8]** | **+168.2 [+136.5, +202.9]** | yes |
| headline | 80000 | comma-z0-random | +23.9 [-2.5, +48.4] | **+32.6 [+3.8, +59.3]** | yes |
| headline | 400000 | comma-rand15 | **+190.6 [+156.9, +224.6]** | **+184.5 [+148.9, +222.5]** | yes |
| headline | 400000 | comma-short15 | **+170.5 [+135.3, +207.8]** | **+167.5 [+132.5, +202.5]** | yes |
| headline | 400000 | comma-short15-coarse | **+196.7 [+159.2, +236.1]** | **+252.4 [+209.1, +293.1]** | no |
| headline | 400000 | comma-z0-coarse | **+284.8 [+239.0, +332.2]** | **+297.4 [+251.2, +342.9]** | yes |
| headline | 400000 | comma-z0-fine | **+88.8 [+55.6, +121.9]** | **+75.7 [+44.5, +104.5]** | yes |
| headline | 400000 | comma-z0-mid | **+227.8 [+180.3, +275.5]** | **+242.7 [+199.0, +287.9]** | yes |
| headline | 400000 | comma-z0-random | **+42.8 [+9.2, +76.7]** | **+62.0 [+28.5, +95.0]** | yes |
| contrast | 80000 | headline (comma-z0-coarse - emcts-comma) | **+268.1 [+226.1, +309.8]** | **+244.3 [+208.2, +281.4]** | yes |
| contrast | 80000 | the table's own contribution (comma-z0-coarse - comma-z0-random) | **+244.2 [+205.9, +285.9]** | **+211.7 [+176.3, +250.3]** | yes |
| contrast | 80000 | ranking pays at coarse keys (comma-short15-coarse - comma-rand15) | **+66.6 [+38.7, +96.8]** | **+66.1 [+43.2, +90.3]** | yes |
| contrast | 80000 | ranking at mid keys (comma-short15 - comma-rand15) | -20.9 [-42.1, +0.1] | -21.2 [-43.8, +1.7] | ns both |
| contrast | 80000 | a fine table against no table (comma-z0-fine - comma-z0-random) | **+23.4 [+7.4, +39.2]** | +3.8 [-13.1, +20.1] | no |
| contrast | 80000 | repair keys: fine against mid (comma-z0-fine - comma-z0-mid) | **-121.7 [-145.5, -98.3]** | **-131.8 [-160.8, -103.4]** | yes |
| contrast | 80000 | repair keys: mid against coarse (comma-z0-mid - comma-z0-coarse) | **-99.1 [-132.5, -71.0]** | **-76.1 [-104.0, -49.5]** | yes |
| contrast | 80000 | comma-z0-mid: mechanism over its control (comma-z0-mid - comma-z0-random) | **+145.1 [+119.1, +172.7]** | **+135.6 [+108.1, +164.3]** | yes |
| contrast | 400000 | headline (comma-z0-coarse - emcts-comma) | **+284.8 [+238.4, +331.8]** | **+297.4 [+251.3, +343.2]** | yes |
| contrast | 400000 | the table's own contribution (comma-z0-coarse - comma-z0-random) | **+242.0 [+202.6, +283.8]** | **+235.3 [+197.3, +273.6]** | yes |
| contrast | 400000 | ranking pays at coarse keys (comma-short15-coarse - comma-rand15) | +6.1 [-25.1, +36.5] | **+67.9 [+34.1, +99.8]** | no |
| contrast | 400000 | ranking at mid keys (comma-short15 - comma-rand15) | -20.1 [-47.9, +8.4] | -17.0 [-46.6, +12.6] | ns both |
| contrast | 400000 | a fine table against no table (comma-z0-fine - comma-z0-random) | **+46.0 [+15.2, +78.9]** | +13.7 [-14.6, +41.4] | no |
| contrast | 400000 | repair keys: fine against mid (comma-z0-fine - comma-z0-mid) | **-139.0 [-172.0, -107.4]** | **-167.0 [-205.0, -131.8]** | yes |
| contrast | 400000 | repair keys: mid against coarse (comma-z0-mid - comma-z0-coarse) | **-57.0 [-82.7, -30.2]** | **-54.7 [-82.2, -26.6]** | yes |
| contrast | 400000 | comma-z0-mid: mechanism over its control (comma-z0-mid - comma-z0-random) | **+185.0 [+147.3, +224.7]** | **+180.6 [+146.6, +218.2]** | yes |
| multiplier | 80000 | comma-rand15 | **2.6x [2.2x, 3.1x]** | **2.5x [2.1x, 3.0x]** | yes |
| multiplier | 80000 | comma-short15 | **2.2x [1.9x, 2.5x]** | **2.2x [1.9x, 2.6x]** | yes |
| multiplier | 80000 | comma-short15-coarse | **4.3x [3.7x, 5.0x]** | **4.0x [3.3x, 4.8x]** | yes |
| multiplier | 80000 | comma-z0-coarse | **6.4x [5.2x, 8.1x]** | **5.4x [4.4x, 6.6x]** | yes |
| multiplier | 80000 | comma-z0-fine | **1.4x [1.1x, 1.6x]** | 1.2x [1.0x, 1.5x] | no |
| multiplier | 80000 | comma-z0-mid | **3.1x [2.5x, 3.8x]** | **3.1x [2.5x, 3.9x]** | yes |
| multiplier | 80000 | comma-z0-random | 1.2x [0.9x, 1.4x] | **1.3x [1.0x, 1.5x]** | yes |
| multiplier | 400000 | comma-rand15 | **3.7x [2.9x, 4.9x]** | **3.6x [2.7x, 4.9x]** | yes |
| multiplier | 400000 | comma-short15 | **3.3x [2.5x, 4.3x]** | **3.2x [2.6x, 4.1x]** | yes |
| multiplier | 400000 | comma-short15-coarse | **3.8x [2.9x, 5.1x]** | **5.7x [4.2x, 8.0x]** | no |
| multiplier | 400000 | comma-z0-coarse | **7.0x [5.0x, 10.3x]** | **7.9x [5.8x, 11.4x]** | yes |
| multiplier | 400000 | comma-z0-fine | **1.8x [1.4x, 2.4x]** | **1.7x [1.4x, 2.1x]** | yes |
| multiplier | 400000 | comma-z0-mid | **4.8x [3.4x, 7.0x]** | **5.4x [4.0x, 7.7x]** | yes |
| multiplier | 400000 | comma-z0-random | **1.3x [1.0x, 1.7x]** | **1.5x [1.2x, 1.9x]** | yes |

## Headline (holdout): units and seconds

Units: the paired difference (#1); its no-terminal sensitivity beside it. Seconds: W7 (#3), the arm
re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.
The multiplier (#2) and the gain multiple (#6) are the readable sizes; `MeanDiff` itself is the raw
heuristic delta (#11), which is never a result on its own. d (#8) is on the no-terminal pairs.

### 80,000 units

| Arm | Pairs | MeanDiff [CI] | No-terminal | By source | W7 seconds [CI] | Verdict | Budget multiplier | Gain multiple | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-rand15 | 1600 | **+133.8 [+108.0, +160.5]** | +130.4 | dual +95.0, single +172.7 | **+127.5 [+100.9, +154.9]** (clamped 41%) | wins both | **2.5x [2.1x, 3.0x]** | 1.50x | 0.42 |
| comma-short15 | 1600 | **+112.7 [+85.6, +139.1]** | +111.1 | dual +78.3, single +147.1 | **+109.4 [+81.0, +135.8]** (clamped 39%) | wins both | **2.2x [1.9x, 2.6x]** | 1.43x | 0.34 |
| comma-short15-coarse | 1600 | **+199.9 [+166.2, +235.5]** | +194.4 | dual +151.3, single +248.5 | **+184.5 [+153.1, +217.1]** (clamped 16%) | wins both | **4.0x [3.3x, 4.8x]** | 1.75x | 0.54 |
| comma-z0-coarse | 1600 | **+244.3 [+207.3, +282.8]** | +241.7 | dual +183.4, single +305.2 | **+223.3 [+187.1, +261.4]** (clamped 14%) | wins both | **5.4x [4.4x, 6.6x]** (extrapolated) | 1.93x | 0.62 |
| comma-z0-fine | 1600 | **+36.4 [+6.2, +63.8]** | +34.3 | dual +10.2, single +62.7 | +21.6 [-8.9, +48.8] (clamped 23%) | units only (unresolved) | 1.2x [1.0x, 1.5x] | 1.13x | 0.11 |
| comma-z0-mid | 1600 | **+168.2 [+136.5, +202.9]** | +165.1 | dual +118.3, single +218.2 | **+147.7 [+114.9, +183.0]** (clamped 13%) | wins both | **3.1x [2.5x, 3.9x]** | 1.64x | 0.44 |
| comma-z0-random | 1600 | **+32.6 [+3.8, +59.3]** | +30.0 | dual +7.1, single +58.2 | +25.0 [-2.6, +53.1] (clamped 27%) | units only (unresolved) | **1.3x [1.0x, 1.5x]** | 1.12x | 0.10 |

### 400,000 units

| Arm | Pairs | MeanDiff [CI] | No-terminal | By source | W7 seconds [CI] | Verdict | Budget multiplier | Gain multiple | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-rand15 | 1200 | **+184.5 [+148.9, +222.5]** | +180.8 | dual +118.9, single +250.1 | **+189.7 [+154.0, +225.0]** (clamped 58%) | wins both | **3.6x [2.7x, 4.9x]** (extrapolated) | 1.37x | 0.49 |
| comma-short15 | 1200 | **+167.5 [+132.5, +202.5]** | +164.7 | dual +109.8, single +225.2 | **+166.5 [+133.1, +202.1]** (clamped 91%) | wins both | **3.2x [2.6x, 4.1x]** (extrapolated) | 1.34x | 0.43 |
| comma-short15-coarse | 1200 | **+252.4 [+209.1, +293.1]** | +247.5 | dual +190.2, single +314.6 | **+248.5 [+208.4, +289.2]** (clamped 93%) | wins both | **5.7x [4.2x, 8.0x]** (extrapolated) | 1.51x | 0.59 |
| comma-z0-coarse | 1200 | **+297.4 [+251.2, +342.9]** | +293.5 | dual +214.7, single +380.1 | **+294.3 [+248.7, +342.0]** (clamped 96%) | wins both | **7.9x [5.8x, 11.4x]** (extrapolated) | 1.60x | 0.67 |
| comma-z0-fine | 1200 | **+75.7 [+44.5, +104.5]** | +73.9 | dual +34.3, single +117.1 | **+79.8 [+49.9, +109.0]** (clamped 80%) | wins both | **1.7x [1.4x, 2.1x]** (extrapolated) | 1.15x | 0.21 |
| comma-z0-mid | 1200 | **+242.7 [+199.0, +287.9]** | +240.0 | dual +139.9, single +345.4 | **+242.4 [+198.5, +287.3]** (clamped 88%) | wins both | **5.4x [4.0x, 7.7x]** (extrapolated) | 1.49x | 0.54 |
| comma-z0-random | 1200 | **+62.0 [+28.5, +95.0]** | +57.6 | dual +18.0, single +106.1 | **+71.0 [+38.1, +102.1]** (clamped 51%) | wins both | **1.5x [1.2x, 1.9x]** (extrapolated) | 1.12x | 0.16 |

## Headline (dev): units and seconds

Units: the paired difference (#1); its no-terminal sensitivity beside it. Seconds: W7 (#3), the arm
re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.
The multiplier (#2) and the gain multiple (#6) are the readable sizes; `MeanDiff` itself is the raw
heuristic delta (#11), which is never a result on its own. d (#8) is on the no-terminal pairs.

### 40,000 units

| Arm | Pairs | MeanDiff [CI] | No-terminal | By source | W7 seconds [CI] | Verdict | Budget multiplier | Gain multiple | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-bias-coarse-e25 | 1600 | **+62.4 [+44.1, +82.1]** | +62.0 | dual +43.6, single +81.2 | **+54.4 [+36.8, +72.9]** (clamped 36%) | wins both | **2.5x [2.1x, 2.8x]** | 1.29x | 0.23 |
| comma-bias-e10 | 1600 | +13.3 [-5.0, +32.0] | +13.1 | dual -16.6, single +43.1 | +4.4 [-13.7, +22.1] (clamped 30%) | no win | 1.3x [0.8x, 2.0x] | 1.06x | 0.05 |
| comma-bias-e25 | 1600 | **+23.0 [+2.4, +42.5]** | +22.9 | dual -4.6, single +50.6 | +12.8 [-6.6, +32.1] (clamped 23%) | units only (unresolved) | 1.7x [1.0x, 2.2x] | 1.11x | 0.08 |
| comma-bias-e50 | 1600 | **+31.0 [+16.8, +45.9]** | +30.3 | dual +12.5, single +49.6 | **+24.9 [+10.4, +39.0]** (clamped 39%) | wins both | **2.0x [1.5x, 2.3x]** | 1.14x | 0.12 |
| comma-bias-e75 | 1600 | **+25.9 [+13.4, +38.8]** | +25.0 | dual +10.6, single +41.2 | **+17.2 [+4.7, +29.9]** (clamped 23%) | wins both | **1.9x [1.4x, 2.2x]** | 1.12x | 0.10 |
| comma-bias-norem-e25 | 1600 | +1.2 [-12.7, +16.0] | +1.4 | dual -4.4, single +6.9 | -4.6 [-19.1, +10.5] (clamped 40%) | no win | 1.1x [0.7x, 1.6x] | 1.01x | 0.01 |
| comma-bias-perburn-e25 | 1600 | +17.1 [-1.1, +34.9] | +16.5 | dual -9.1, single +43.3 | +10.4 [-8.3, +27.6] (clamped 40%) | no win | 1.5x [0.9x, 2.1x] | 1.08x | 0.06 |
| comma-short15 | 1600 | **+105.7 [+82.8, +129.8]** | +102.8 | dual +70.1, single +141.3 | **+96.1 [+74.2, +117.7]** (clamped 33%) | wins both | **3.3x [2.9x, 3.8x]** | 1.49x | 0.33 |
| comma-short15-bias-e25 | 1600 | **+86.4 [+58.4, +115.1]** | +83.1 | dual +22.2, single +150.7 | **+68.4 [+41.6, +94.8]** (clamped 26%) | wins both | **3.0x [2.4x, 3.6x]** | 1.39x | 0.25 |
| comma-short5-bias-e25 | 1600 | **+84.8 [+59.7, +110.5]** | +81.1 | dual +32.1, single +137.5 | **+60.3 [+35.6, +86.2]** (clamped 21%) | wins both | **2.9x [2.4x, 3.4x]** | 1.38x | 0.24 |
| emcts | 1600 | **-20.4 [-35.3, -6.7]** | -21.5 | dual -28.9, single -11.9 | **-26.3 [-41.5, -12.4]** (clamped 38%) | no win | **0.6x [0.4x, 0.9x]** (outside) | 0.90x | -0.09 |

### 80,000 units

| Arm | Pairs | MeanDiff [CI] | No-terminal | By source | W7 seconds [CI] | Verdict | Budget multiplier | Gain multiple | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-bias-coarse-e25 | 1600 | **+66.8 [+47.1, +87.4]** | +66.2 | dual +42.6, single +90.9 | **+62.8 [+44.0, +83.4]** (clamped 18%) | wins both | **1.6x [1.4x, 1.8x]** | 1.28x | 0.24 |
| comma-bias-e10 | 1600 | +8.4 [-12.9, +29.8] | +8.1 | dual -25.4, single +42.2 | +4.2 [-15.8, +24.7] (clamped 18%) | no win | 1.0x [0.6x, 1.2x] | 1.03x | 0.03 |
| comma-bias-e25 | 1600 | **+21.4 [+0.0, +42.6]** | +21.4 | dual -10.5, single +53.4 | +15.3 [-6.0, +36.2] (clamped 21%) | units only (unresolved) | 1.2x [0.9x, 1.4x] | 1.09x | 0.07 |
| comma-bias-e50 | 1600 | **+38.6 [+23.4, +54.5]** | +37.9 | dual +15.9, single +61.4 | **+35.7 [+20.7, +50.9]** (clamped 18%) | wins both | **1.3x [1.2x, 1.5x]** | 1.16x | 0.14 |
| comma-bias-e75 | 1600 | **+21.7 [+7.8, +35.6]** | +20.7 | dual +7.4, single +36.1 | **+17.7 [+4.1, +31.6]** (clamped 19%) | wins both | **1.2x [1.0x, 1.3x]** | 1.09x | 0.08 |
| comma-bias-fine-e25 | 1600 | **-35.8 [-54.5, -18.7]** | -35.5 | dual -50.0, single -21.5 | **-42.0 [-60.1, -25.1]** (clamped 21%) | no win | **0.4x [0.3x, 0.7x]** (outside) | 0.85x | -0.14 |
| comma-bias-norem-e25 | 1600 | +6.4 [-8.3, +21.6] | +6.4 | dual -2.5, single +15.3 | +1.8 [-12.6, +17.1] (clamped 19%) | no win | 1.0x [0.8x, 1.2x] | 1.03x | 0.02 |
| comma-bias-perburn-e25 | 1600 | **+26.4 [+8.6, +44.3]** | +26.9 | dual -6.0, single +58.9 | **+23.8 [+6.4, +42.0]** (clamped 19%) | wins both | **1.2x [1.0x, 1.4x]** | 1.11x | 0.10 |
| comma-rand15 | 1600 | **+139.8 [+111.8, +167.9]** | +135.7 | dual +101.7, single +178.0 | **+130.0 [+101.6, +158.4]** (clamped 22%) | wins both | **2.6x [2.2x, 3.1x]** | 1.57x | 0.42 |
| comma-short15 | 1600 | **+118.9 [+95.2, +142.8]** | +115.0 | dual +84.6, single +153.3 | **+110.3 [+87.8, +133.4]** (clamped 21%) | wins both | **2.2x [1.9x, 2.5x]** | 1.48x | 0.36 |
| comma-short15-bias-e25 | 1600 | **+87.5 [+58.4, +118.2]** | +84.0 | dual +21.9, single +153.1 | **+77.2 [+48.6, +106.3]** (clamped 20%) | wins both | **1.8x [1.5x, 2.2x]** | 1.35x | 0.24 |
| comma-short15-coarse | 1600 | **+206.5 [+173.5, +242.5]** | +201.5 | dual +175.9, single +237.0 | **+190.6 [+158.0, +224.1]** (clamped 24%) | wins both | **4.3x [3.7x, 5.0x]** | 1.84x | 0.57 |
| comma-short5-bias-e25 | 1600 | **+82.8 [+55.8, +110.0]** | +78.8 | dual +32.2, single +133.3 | **+67.3 [+41.5, +95.0]** (clamped 21%) | wins both | **1.7x [1.4x, 2.0x]** | 1.33x | 0.23 |
| comma-z0-coarse | 1600 | **+268.1 [+228.9, +308.0]** | +264.4 | dual +229.5, single +306.8 | **+250.4 [+212.1, +290.3]** (clamped 23%) | wins both | **6.4x [5.2x, 8.1x]** (extrapolated) | 2.11x | 0.67 |
| comma-z0-fine | 1600 | **+47.4 [+20.8, +72.5]** | +43.4 | dual +17.3, single +77.4 | **+34.5 [+7.5, +60.0]** (clamped 21%) | wins both | **1.4x [1.1x, 1.6x]** | 1.18x | 0.14 |
| comma-z0-mid | 1600 | **+169.0 [+135.0, +203.8]** | +163.5 | dual +112.9, single +225.1 | **+150.0 [+116.3, +184.3]** (clamped 24%) | wins both | **3.1x [2.5x, 3.8x]** | 1.68x | 0.43 |
| comma-z0-random | 1600 | +23.9 [-2.5, +48.4] | +18.9 | dual +7.2, single +40.6 | +12.1 [-13.3, +37.1] (clamped 22%) | no win | 1.2x [0.9x, 1.4x] | 1.08x | 0.06 |
| emcts | 1600 | **-22.3 [-34.9, -9.4]** | -23.6 | dual -25.0, single -19.6 | **-27.4 [-39.7, -15.2]** (clamped 20%) | no win | **0.6x [0.4x, 0.8x]** | 0.90x | -0.10 |
| emcts-bias-e25 | 1600 | -16.1 [-34.0, +1.7] | -17.2 | dual -36.6, single +4.3 | **-20.4 [-38.2, -2.5]** (clamped 19%) | no win | **0.6x [0.4x, 1.0x]** | 0.93x | -0.06 |

### 400,000 units

| Arm | Pairs | MeanDiff [CI] | No-terminal | By source | W7 seconds [CI] | Verdict | Budget multiplier | Gain multiple | d |
|---|---|---|---|---|---|---|---|---|---|
| comma-bias-coarse-e25 | 1200 | **+45.3 [+18.1, +73.3]** | +45.6 | dual +18.7, single +71.9 | **+55.1 [+28.7, +82.7]** (clamped 51%) | wins both | **1.4x [1.1x, 1.7x]** (extrapolated) | 1.10x | 0.14 |
| comma-bias-e10 | 1200 | **-65.0 [-100.4, -33.5]** | -64.7 | dual -104.6, single -25.4 | **-45.3 [-76.7, -13.8]** (clamped 32%) | no win | **0.6x [0.5x, 0.8x]** | 0.86x | -0.18 |
| comma-bias-e25 | 1200 | -4.9 [-33.4, +25.4] | -4.9 | dual -32.2, single +22.3 | -4.4 [-33.2, +24.4] (clamped 96%) | no win | 1.0x [0.8x, 1.2x] | 0.99x | -0.01 |
| comma-bias-e50 | 1200 | +17.6 [-6.6, +44.0] | +17.9 | dual +8.0, single +27.2 | **+27.9 [+3.4, +54.3]** (clamped 42%) | wall-clock only | 1.1x [1.0x, 1.4x] (extrapolated) | 1.04x | 0.06 |
| comma-bias-e75 | 1200 | **+36.0 [+18.8, +53.1]** | +34.2 | dual +32.0, single +40.0 | **+42.4 [+25.3, +60.1]** (clamped 44%) | wins both | **1.3x [1.1x, 1.4x]** (extrapolated) | 1.07x | 0.12 |
| comma-bias-fine-e25 | 1200 | **-92.3 [-119.7, -64.8]** | -91.7 | dual -88.8, single -95.8 | **-81.8 [-107.9, -54.3]** (clamped 43%) | no win | **0.5x [0.4x, 0.6x]** | 0.80x | -0.28 |
| comma-bias-norem-e25 | 1200 | -15.4 [-39.7, +9.5] | -14.2 | dual -25.6, single -5.2 | -7.6 [-31.7, +17.1] (clamped 66%) | no win | 0.9x [0.8x, 1.1x] | 0.97x | -0.05 |
| comma-bias-perburn-e25 | 1200 | -15.6 [-42.3, +12.8] | -15.9 | dual -40.3, single +9.1 | +16.8 [-11.6, +45.5] (clamped 12%) | no win | 0.9x [0.7x, 1.1x] | 0.97x | -0.05 |
| comma-rand15 | 1200 | **+190.6 [+156.9, +224.6]** | +185.3 | dual +153.0, single +228.3 | **+197.1 [+161.0, +233.7]** (clamped 49%) | wins both | **3.7x [2.9x, 4.9x]** (extrapolated) | 1.39x | 0.52 |
| comma-short15 | 1200 | **+170.5 [+135.3, +207.8]** | +168.0 | dual +127.8, single +213.3 | **+169.4 [+134.2, +205.5]** (clamped 92%) | wins both | **3.3x [2.5x, 4.3x]** (extrapolated) | 1.36x | 0.42 |
| comma-short15-bias-e25 | 1200 | **+40.2 [+0.4, +79.3]** | +35.2 | dual -3.4, single +83.8 | **+62.8 [+23.9, +103.0]** (clamped 32%) | wins both | 1.3x [1.0x, 1.7x] (extrapolated) | 1.08x | 0.08 |
| comma-short15-coarse | 1200 | **+196.7 [+159.2, +236.1]** | +189.0 | dual +174.6, single +218.8 | **+200.0 [+161.6, +240.3]** (clamped 52%) | wins both | **3.8x [2.9x, 5.1x]** (extrapolated) | 1.40x | 0.51 |
| comma-short5-bias-e25 | 1200 | **+47.7 [+9.0, +88.7]** | +43.4 | dual +26.1, single +69.3 | **+61.0 [+22.7, +100.4]** (clamped 45%) | wins both | **1.4x [1.0x, 1.9x]** (extrapolated) | 1.09x | 0.10 |
| comma-z0-coarse | 1200 | **+284.8 [+239.0, +332.2]** | +277.5 | dual +243.5, single +326.0 | **+278.4 [+231.1, +325.4]** (clamped 94%) | wins both | **7.0x [5.0x, 10.3x]** (extrapolated) | 1.59x | 0.66 |
| comma-z0-fine | 1200 | **+88.8 [+55.6, +121.9]** | +85.1 | dual +58.9, single +118.7 | **+88.6 [+55.1, +123.3]** (clamped 84%) | wins both | **1.8x [1.4x, 2.4x]** (extrapolated) | 1.18x | 0.24 |
| comma-z0-mid | 1200 | **+227.8 [+180.3, +275.5]** | +222.2 | dual +163.8, single +291.8 | **+224.2 [+177.7, +274.2]** (clamped 87%) | wins both | **4.8x [3.4x, 7.0x]** (extrapolated) | 1.47x | 0.50 |
| comma-z0-random | 1200 | **+42.8 [+9.2, +76.7]** | +37.2 | dual +21.1, single +64.4 | **+46.6 [+10.9, +80.5]** (clamped 60%) | wins both | **1.3x [1.0x, 1.7x]** (extrapolated) | 1.08x | 0.10 |
| emcts | 1200 | **-131.5 [-159.0, -103.7]** | -133.9 | dual -129.6, single -133.4 | **-87.6 [-111.4, -63.1]** | no win | **0.4x [0.3x, 0.5x]** | 0.71x | -0.42 |

## Budget multiplier (holdout)

How much budget `emcts-comma` would need to match each arm: the baseline's mean search gain per level,
interpolated log-linearly in budget, solved for the arm's gain, over the cells where every baseline level
and the arm ran and none ended terminal. `extrapolated` means the crossing lies past the top of the ladder:
a lower bound, since gain is concave in budget. The wall-clock column solves the same crossing in the
baseline's own seconds and divides by the arm's; `arm/baseline time` is the arm's plan time over the
baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 80,000: gain 259.5, 400,000: gain 487.7. Its top step is 5x the units and 3.5x the seconds.

| Budget | Arm | Cells | Arm gain | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 80000 | comma-rand15 | 1188 | 391.2 | **2.5x [2.1x, 3.0x]** | inside | **2.0x [1.8x, 2.3x]** | 1.01 |
| 80000 | comma-short15 | 1188 | 373.9 | **2.2x [1.9x, 2.6x]** | inside | **1.8x [1.6x, 2.1x]** | 1.02 |
| 80000 | comma-short15-coarse | 1188 | 454.6 | **4.0x [3.3x, 4.8x]** | inside | **2.7x [2.3x, 3.1x]** | 1.11 |
| 80000 | comma-z0-coarse | 1188 | 497.4 | **5.4x [4.4x, 6.6x]** | extrapolated | **3.2x [2.7x, 3.8x]** | 1.17 |
| 80000 | comma-z0-fine | 1188 | 289.3 | 1.2x [1.0x, 1.5x] | inside | 1.1x [0.9x, 1.3x] | 1.08 |
| 80000 | comma-z0-mid | 1188 | 421.1 | **3.1x [2.5x, 3.9x]** | inside | **2.1x [1.8x, 2.5x]** | 1.16 |
| 80000 | comma-z0-random | 1188 | 292.1 | **1.3x [1.0x, 1.5x]** | inside | 1.1x [1.0x, 1.3x] | 1.07 |
| 400000 | comma-rand15 | 1188 | 668.5 | **3.6x [2.7x, 4.9x]** | extrapolated | **2.7x [2.2x, 3.4x]** | 1.01 |
| 400000 | comma-short15 | 1188 | 652.3 | **3.2x [2.6x, 4.1x]** | extrapolated | **2.2x [1.9x, 2.7x]** | 1.12 |
| 400000 | comma-short15-coarse | 1188 | 735.1 | **5.7x [4.2x, 8.0x]** | extrapolated | **3.4x [2.7x, 4.5x]** | 1.15 |
| 400000 | comma-z0-coarse | 1188 | 781.1 | **7.9x [5.8x, 11.4x]** | extrapolated | **4.1x [3.2x, 5.6x]** | 1.22 |
| 400000 | comma-z0-fine | 1188 | 561.6 | **1.7x [1.4x, 2.1x]** | extrapolated | **1.4x [1.2x, 1.7x]** | 1.09 |
| 400000 | comma-z0-mid | 1188 | 727.7 | **5.4x [4.0x, 7.7x]** | extrapolated | **3.3x [2.6x, 4.3x]** | 1.14 |
| 400000 | comma-z0-random | 1188 | 545.2 | **1.5x [1.2x, 1.9x]** | extrapolated | **1.4x [1.1x, 1.7x]** | 1.01 |

## Budget multiplier (dev)

How much budget `emcts-comma` would need to match each arm: the baseline's mean search gain per level,
interpolated log-linearly in budget, solved for the arm's gain, over the cells where every baseline level
and the arm ran and none ended terminal. `extrapolated` means the crossing lies past the top of the ladder:
a lower bound, since gain is concave in budget. The wall-clock column solves the same crossing in the
baseline's own seconds and divides by the arm's; `arm/baseline time` is the arm's plan time over the
baseline's at the same budget.

The baseline's ladder (on the first arm's cells): 40,000: gain 211.0, 80,000: gain 240.7, 400,000: gain 469.6. Its top step is 5x the units and 3.7x the seconds.

| Budget | Arm | Cells | Arm gain | Multiplier [CI] | Where | Wall-clock multiplier [CI] | Arm/baseline time |
|---|---|---|---|---|---|---|---|
| 40000 | comma-bias-coarse-e25 | 1176 | 269.7 | **2.5x [2.1x, 2.8x]** | inside | **1.4x [1.3x, 1.6x]** | 1.05 |
| 40000 | comma-bias-e10 | 1176 | 222.7 | 1.3x [0.8x, 2.0x] | inside | 1.0x [0.9x, 1.2x] | 1.06 |
| 40000 | comma-bias-e25 | 1176 | 233.7 | 1.7x [1.0x, 2.2x] | inside | 1.1x [0.9x, 1.3x] | 1.08 |
| 40000 | comma-bias-e50 | 1176 | 243.2 | **2.0x [1.5x, 2.3x]** | inside | **1.3x [1.1x, 1.4x]** | 1.03 |
| 40000 | comma-bias-e75 | 1176 | 238.3 | **1.9x [1.4x, 2.2x]** | inside | **1.2x [1.1x, 1.3x]** | 1.06 |
| 40000 | comma-bias-norem-e25 | 1176 | 214.2 | 1.1x [0.7x, 1.6x] | inside | 1.0x [0.9x, 1.2x] | 1.03 |
| 40000 | comma-bias-perburn-e25 | 1176 | 227.7 | 1.5x [0.9x, 2.1x] | inside | 1.1x [0.9x, 1.3x] | 1.03 |
| 40000 | comma-short15 | 1176 | 313.0 | **3.3x [2.9x, 3.8x]** | inside | **1.9x [1.7x, 2.1x]** | 1.05 |
| 40000 | comma-short15-bias-e25 | 1176 | 297.6 | **3.0x [2.4x, 3.6x]** | inside | **1.6x [1.3x, 1.8x]** | 1.14 |
| 40000 | comma-short5-bias-e25 | 1176 | 293.0 | **2.9x [2.4x, 3.4x]** | inside | **1.4x [1.2x, 1.6x]** | 1.21 |
| 40000 | emcts | 1176 | 189.7 | **0.6x [0.4x, 0.9x]** | outside | **0.8x [0.7x, 0.9x]** | 1.01 |
| 80000 | comma-bias-coarse-e25 | 1176 | 304.3 | **1.6x [1.4x, 1.8x]** | inside | **1.4x [1.2x, 1.5x]** | 1.06 |
| 80000 | comma-bias-e10 | 1176 | 245.1 | 1.0x [0.6x, 1.2x] | inside | 1.0x [0.8x, 1.1x] | 1.07 |
| 80000 | comma-bias-e25 | 1176 | 262.0 | 1.2x [0.9x, 1.4x] | inside | 1.0x [0.9x, 1.2x] | 1.10 |
| 80000 | comma-bias-e50 | 1176 | 277.4 | **1.3x [1.2x, 1.5x]** | inside | **1.2x [1.1x, 1.3x]** | 1.04 |
| 80000 | comma-bias-e75 | 1176 | 261.8 | **1.2x [1.0x, 1.3x]** | inside | 1.1x [1.0x, 1.2x] | 1.07 |
| 80000 | comma-bias-fine-e25 | 1176 | 203.0 | **0.4x [0.3x, 0.7x]** | outside | **0.7x [0.6x, 0.8x]** | 1.10 |
| 80000 | comma-bias-norem-e25 | 1176 | 247.4 | 1.0x [0.8x, 1.2x] | inside | 1.0x [0.9x, 1.1x] | 1.07 |
| 80000 | comma-bias-perburn-e25 | 1176 | 265.5 | **1.2x [1.0x, 1.4x]** | inside | 1.1x [1.0x, 1.2x] | 1.06 |
| 80000 | comma-rand15 | 1176 | 378.5 | **2.6x [2.2x, 3.1x]** | inside | **2.0x [1.7x, 2.3x]** | 1.11 |
| 80000 | comma-short15 | 1176 | 352.3 | **2.2x [1.9x, 2.5x]** | inside | **1.7x [1.6x, 2.0x]** | 1.09 |
| 80000 | comma-short15-bias-e25 | 1176 | 324.5 | **1.8x [1.5x, 2.2x]** | inside | **1.4x [1.2x, 1.7x]** | 1.13 |
| 80000 | comma-short15-coarse | 1176 | 447.5 | **4.3x [3.7x, 5.0x]** | inside | **2.8x [2.4x, 3.2x]** | 1.18 |
| 80000 | comma-short5-bias-e25 | 1176 | 317.1 | **1.7x [1.4x, 2.0x]** | inside | **1.3x [1.1x, 1.5x]** | 1.19 |
| 80000 | comma-z0-coarse | 1176 | 504.0 | **6.4x [5.2x, 8.1x]** | extrapolated | **3.7x [3.1x, 4.5x]** | 1.22 |
| 80000 | comma-z0-fine | 1176 | 286.2 | **1.4x [1.1x, 1.6x]** | inside | 1.1x [1.0x, 1.3x] | 1.14 |
| 80000 | comma-z0-mid | 1176 | 399.4 | **3.1x [2.5x, 3.8x]** | inside | **2.0x [1.7x, 2.4x]** | 1.22 |
| 80000 | comma-z0-random | 1176 | 260.8 | 1.2x [0.9x, 1.4x] | inside | 1.0x [0.8x, 1.2x] | 1.12 |
| 80000 | emcts | 1176 | 215.9 | **0.6x [0.4x, 0.8x]** | inside | **0.8x [0.7x, 0.9x]** | 1.07 |
| 80000 | emcts-bias-e25 | 1176 | 217.9 | **0.6x [0.4x, 1.0x]** | inside | **0.8x [0.7x, 0.9x]** | 1.06 |
| 400000 | comma-bias-coarse-e25 | 1176 | 515.2 | **1.4x [1.1x, 1.7x]** | extrapolated | **1.3x [1.1x, 1.5x]** | 1.04 |
| 400000 | comma-bias-e10 | 1176 | 404.9 | **0.6x [0.5x, 0.8x]** | inside | **0.7x [0.6x, 0.8x]** | 0.97 |
| 400000 | comma-bias-e25 | 1176 | 464.7 | 1.0x [0.8x, 1.2x] | inside | **0.8x [0.7x, 0.9x]** | 1.22 |
| 400000 | comma-bias-e50 | 1176 | 487.5 | 1.1x [1.0x, 1.4x] | extrapolated | 1.1x [1.0x, 1.3x] | 1.00 |
| 400000 | comma-bias-e75 | 1176 | 503.9 | **1.3x [1.1x, 1.4x]** | extrapolated | **1.2x [1.1x, 1.3x]** | 1.00 |
| 400000 | comma-bias-fine-e25 | 1176 | 377.9 | **0.5x [0.4x, 0.6x]** | inside | **0.6x [0.5x, 0.7x]** | 1.01 |
| 400000 | comma-bias-norem-e25 | 1176 | 455.4 | 0.9x [0.8x, 1.1x] | inside | 0.9x [0.8x, 1.0x] | 1.04 |
| 400000 | comma-bias-perburn-e25 | 1176 | 453.7 | 0.9x [0.7x, 1.1x] | inside | 1.0x [0.9x, 1.2x] | 0.89 |
| 400000 | comma-rand15 | 1176 | 655.0 | **3.7x [2.9x, 4.9x]** | extrapolated | **2.9x [2.4x, 3.7x]** | 0.99 |
| 400000 | comma-short15 | 1176 | 637.7 | **3.3x [2.5x, 4.3x]** | extrapolated | **2.3x [1.9x, 2.9x]** | 1.13 |
| 400000 | comma-short15-bias-e25 | 1176 | 504.9 | 1.3x [1.0x, 1.7x] | extrapolated | **1.3x [1.0x, 1.6x]** | 0.97 |
| 400000 | comma-short15-coarse | 1176 | 658.6 | **3.8x [2.9x, 5.1x]** | extrapolated | **3.0x [2.4x, 3.8x]** | 0.99 |
| 400000 | comma-short5-bias-e25 | 1176 | 513.0 | **1.4x [1.0x, 1.9x]** | extrapolated | 1.2x [1.0x, 1.6x] | 1.04 |
| 400000 | comma-z0-coarse | 1176 | 747.1 | **7.0x [5.0x, 10.3x]** | extrapolated | **3.9x [3.0x, 5.4x]** | 1.26 |
| 400000 | comma-z0-fine | 1176 | 554.8 | **1.8x [1.4x, 2.4x]** | extrapolated | **1.5x [1.2x, 1.8x]** | 1.12 |
| 400000 | comma-z0-mid | 1176 | 691.8 | **4.8x [3.4x, 7.0x]** | extrapolated | **3.1x [2.3x, 4.2x]** | 1.17 |
| 400000 | comma-z0-random | 1176 | 506.8 | **1.3x [1.0x, 1.7x]** | extrapolated | 1.2x [1.0x, 1.5x] | 1.04 |
| 400000 | emcts | 1176 | 335.7 | **0.4x [0.3x, 0.5x]** | inside | **0.5x [0.5x, 0.6x]** | 0.84 |

## Attribution and contrasts (holdout)

An arm's mechanism is credited only when the same arm with the mechanism replaced by noise does worse
(`attribution` rows: arm minus its control). `contrast` rows are the spec's own comparisons.

| Budget | Kind | A - B | Label | Pairs | MeanDiff [CI] | No-terminal | WinRate |
|---|---|---|---|---|---|---|---|
| 80000 | contrast | comma-z0-coarse - emcts-comma | headline | 1600 | **+244.3 [+208.2, +281.4]** | +241.7 | 0.812 |
| 80000 | contrast | comma-z0-coarse - comma-z0-random | the table's own contribution | 1600 | **+211.7 [+176.3, +250.3]** | +211.7 | 0.800 |
| 80000 | contrast | comma-short15-coarse - comma-rand15 | ranking pays at coarse keys | 1600 | **+66.1 [+43.2, +90.3]** | +64.0 | 0.604 |
| 80000 | contrast | comma-short15 - comma-rand15 | ranking at mid keys | 1600 | -21.2 [-43.8, +1.7] | -19.3 | 0.492 |
| 80000 | contrast | comma-z0-fine - comma-z0-random | a fine table against no table | 1600 | +3.8 [-13.1, +20.1] | +4.3 | 0.574 |
| 80000 | contrast | comma-z0-fine - comma-z0-mid | repair keys: fine against mid | 1600 | **-131.8 [-160.8, -103.4]** | -130.8 | 0.308 |
| 80000 | contrast | comma-z0-mid - comma-z0-coarse | repair keys: mid against coarse | 1600 | **-76.1 [-104.0, -49.5]** | -76.5 | 0.412 |
| 80000 | attribution | comma-z0-mid - comma-z0-random | comma-z0-mid: mechanism over its control | 1600 | **+135.6 [+108.1, +164.3]** | +135.1 | 0.740 |
| 400000 | contrast | comma-z0-coarse - emcts-comma | headline | 1200 | **+297.4 [+251.3, +343.2]** | +293.5 | 0.831 |
| 400000 | contrast | comma-z0-coarse - comma-z0-random | the table's own contribution | 1200 | **+235.3 [+197.3, +273.6]** | +235.9 | 0.829 |
| 400000 | contrast | comma-short15-coarse - comma-rand15 | ranking pays at coarse keys | 1200 | **+67.9 [+34.1, +99.8]** | +66.7 | 0.626 |
| 400000 | contrast | comma-short15 - comma-rand15 | ranking at mid keys | 1200 | -17.0 [-46.6, +12.6] | -16.2 | 0.496 |
| 400000 | contrast | comma-z0-fine - comma-z0-random | a fine table against no table | 1200 | +13.7 [-14.6, +41.4] | +16.3 | 0.604 |
| 400000 | contrast | comma-z0-fine - comma-z0-mid | repair keys: fine against mid | 1200 | **-167.0 [-205.0, -131.8]** | -166.1 | 0.332 |
| 400000 | contrast | comma-z0-mid - comma-z0-coarse | repair keys: mid against coarse | 1200 | **-54.7 [-82.2, -26.6]** | -53.5 | 0.422 |
| 400000 | attribution | comma-z0-mid - comma-z0-random | comma-z0-mid: mechanism over its control | 1200 | **+180.6 [+146.6, +218.2]** | +182.4 | 0.764 |

## Attribution and contrasts (dev)

An arm's mechanism is credited only when the same arm with the mechanism replaced by noise does worse
(`attribution` rows: arm minus its control). `contrast` rows are the spec's own comparisons.

| Budget | Kind | A - B | Label | Pairs | MeanDiff [CI] | No-terminal | WinRate |
|---|---|---|---|---|---|---|---|
| 40000 | contrast | comma-bias-coarse-e25 - comma-bias-e25 | insertion keys: coarse against mid | 1600 | **+39.4 [+19.8, +61.5]** | +39.1 | 0.597 |
| 40000 | contrast | comma-bias-e25 - comma-bias-norem-e25 | removal credit on against off | 1600 | **+21.7 [+3.7, +37.8]** | +21.5 | 0.557 |
| 40000 | contrast | comma-bias-e25 - comma-bias-perburn-e25 | a continuous table against one per burn | 1600 | +5.9 [-6.4, +17.4] | +6.4 | 0.528 |
| 40000 | contrast | comma-short15-bias-e25 - comma-bias-e25 | a shortlist on top of the bias | 1600 | **+63.5 [+38.1, +88.6]** | +60.2 | 0.653 |
| 40000 | contrast | comma-short5-bias-e25 - comma-short15-bias-e25 | shortlist 5 against 15, with the bias | 1600 | -1.7 [-17.3, +14.1] | -2.1 | 0.486 |
| 40000 | contrast | emcts-comma - emcts | comma selection over plus | 1600 | **+20.4 [+6.9, +34.8]** | +21.5 | 0.591 |
| 80000 | contrast | comma-z0-coarse - emcts-comma | headline | 1600 | **+268.1 [+226.1, +309.8]** | +264.4 | 0.837 |
| 80000 | contrast | comma-z0-coarse - comma-z0-random | the table's own contribution | 1600 | **+244.2 [+205.9, +285.9]** | +245.4 | 0.841 |
| 80000 | contrast | comma-short15-coarse - comma-rand15 | ranking pays at coarse keys | 1600 | **+66.6 [+38.7, +96.8]** | +65.8 | 0.606 |
| 80000 | contrast | comma-short15 - comma-rand15 | ranking at mid keys | 1600 | -20.9 [-42.1, +0.1] | -20.7 | 0.486 |
| 80000 | contrast | comma-z0-fine - comma-z0-random | a fine table against no table | 1600 | **+23.4 [+7.4, +39.2]** | +24.5 | 0.611 |
| 80000 | contrast | comma-z0-fine - comma-z0-mid | repair keys: fine against mid | 1600 | **-121.7 [-145.5, -98.3]** | -120.1 | 0.319 |
| 80000 | contrast | comma-z0-mid - comma-z0-coarse | repair keys: mid against coarse | 1600 | **-99.1 [-132.5, -71.0]** | -100.9 | 0.365 |
| 80000 | contrast | comma-bias-coarse-e25 - comma-bias-e25 | insertion keys: coarse against mid | 1600 | **+45.3 [+25.1, +69.1]** | +44.8 | 0.597 |
| 80000 | contrast | comma-bias-e25 - comma-bias-fine-e25 | insertion keys: mid against fine | 1600 | **+57.2 [+41.7, +72.5]** | +56.9 | 0.641 |
| 80000 | contrast | comma-bias-e25 - comma-bias-norem-e25 | removal credit on against off | 1600 | +15.0 [-5.0, +32.8] | +15.0 | 0.532 |
| 80000 | contrast | comma-bias-e25 - comma-bias-perburn-e25 | a continuous table against one per burn | 1600 | -5.0 [-19.1, +8.2] | -5.5 | 0.487 |
| 80000 | contrast | comma-short15-bias-e25 - comma-bias-e25 | a shortlist on top of the bias | 1600 | **+66.0 [+38.6, +92.9]** | +62.5 | 0.647 |
| 80000 | contrast | comma-short5-bias-e25 - comma-short15-bias-e25 | shortlist 5 against 15, with the bias | 1600 | -4.7 [-21.4, +12.8] | -5.2 | 0.482 |
| 80000 | contrast | emcts-comma - emcts | comma selection over plus | 1600 | **+22.3 [+9.8, +34.7]** | +23.6 | 0.608 |
| 80000 | contrast | emcts-bias-e25 - emcts | the bias on plus selection | 1600 | +6.1 [-8.9, +20.4] | +6.4 | 0.510 |
| 80000 | attribution | comma-z0-mid - comma-z0-random | comma-z0-mid: mechanism over its control | 1600 | **+145.1 [+119.1, +172.7]** | +144.6 | 0.757 |
| 400000 | contrast | comma-z0-coarse - emcts-comma | headline | 1200 | **+284.8 [+238.4, +331.8]** | +277.5 | 0.834 |
| 400000 | contrast | comma-z0-coarse - comma-z0-random | the table's own contribution | 1200 | **+242.0 [+202.6, +283.8]** | +240.3 | 0.842 |
| 400000 | contrast | comma-short15-coarse - comma-rand15 | ranking pays at coarse keys | 1200 | +6.1 [-25.1, +36.5] | +3.6 | 0.549 |
| 400000 | contrast | comma-short15 - comma-rand15 | ranking at mid keys | 1200 | -20.1 [-47.9, +8.4] | -17.3 | 0.516 |
| 400000 | contrast | comma-z0-fine - comma-z0-random | a fine table against no table | 1200 | **+46.0 [+15.2, +78.9]** | +48.0 | 0.621 |
| 400000 | contrast | comma-z0-fine - comma-z0-mid | repair keys: fine against mid | 1200 | **-139.0 [-172.0, -107.4]** | -137.0 | 0.315 |
| 400000 | contrast | comma-z0-mid - comma-z0-coarse | repair keys: mid against coarse | 1200 | **-57.0 [-82.7, -30.2]** | -55.3 | 0.422 |
| 400000 | contrast | comma-bias-coarse-e25 - comma-bias-e25 | insertion keys: coarse against mid | 1200 | **+50.3 [+30.2, +69.8]** | +50.5 | 0.550 |
| 400000 | contrast | comma-bias-e25 - comma-bias-fine-e25 | insertion keys: mid against fine | 1200 | **+87.4 [+62.3, +110.8]** | +86.9 | 0.661 |
| 400000 | contrast | comma-bias-e25 - comma-bias-norem-e25 | removal credit on against off | 1200 | +10.5 [-11.9, +33.3] | +9.4 | 0.536 |
| 400000 | contrast | comma-bias-e25 - comma-bias-perburn-e25 | a continuous table against one per burn | 1200 | +10.7 [-8.2, +31.1] | +11.0 | 0.538 |
| 400000 | contrast | comma-short15-bias-e25 - comma-bias-e25 | a shortlist on top of the bias | 1200 | **+45.2 [+17.6, +72.6]** | +40.1 | 0.561 |
| 400000 | contrast | comma-short5-bias-e25 - comma-short15-bias-e25 | shortlist 5 against 15, with the bias | 1200 | +7.5 [-12.4, +26.1] | +8.1 | 0.493 |
| 400000 | contrast | emcts-comma - emcts | comma selection over plus | 1200 | **+131.5 [+104.3, +158.6]** | +133.9 | 0.756 |
| 400000 | attribution | comma-z0-mid - comma-z0-random | comma-z0-mid: mechanism over its control | 1200 | **+185.0 [+147.3, +224.7]** | +185.0 | 0.776 |

## Oracle regret (holdout)

Over the turns where every arm at the budget produced a non-terminal sequence: how often each arm
found the field's best (ties count for every arm reaching it) and how far below the best it was on
average. Robust to the heuristic's scale; meaningful with three or more arms.

80,000 units, 1,584 turns, 8 arms:

| Arm | Best of field | Mean regret |
|---|---|---|
| comma-z0-coarse | 38.3% | 171.5 |
| comma-short15-coarse | 31.2% | 218.8 |
| comma-z0-mid | 29.0% | 248.0 |
| comma-short15 | 22.7% | 302.0 |
| comma-rand15 | 21.7% | 282.8 |
| comma-z0-fine | 17.6% | 378.8 |
| emcts-comma | 15.3% | 413.1 |
| comma-z0-random | 14.2% | 383.1 |

400,000 units, 1,188 turns, 8 arms:

| Arm | Best of field | Mean regret |
|---|---|---|
| comma-z0-coarse | 38.6% | 196.5 |
| comma-short15-coarse | 34.8% | 242.5 |
| comma-z0-mid | 33.7% | 250.0 |
| comma-short15 | 24.8% | 325.4 |
| comma-rand15 | 23.9% | 309.2 |
| comma-z0-fine | 20.7% | 416.1 |
| emcts-comma | 17.1% | 490.0 |
| comma-z0-random | 15.4% | 432.4 |

## Oracle regret (dev)

Over the turns where every arm at the budget produced a non-terminal sequence: how often each arm
found the field's best (ties count for every arm reaching it) and how far below the best it was on
average. Robust to the heuristic's scale; meaningful with three or more arms.

40,000 units, 1,568 turns, 12 arms:

| Arm | Best of field | Mean regret |
|---|---|---|
| comma-short15 | 35.8% | 198.2 |
| comma-short5-bias-e25 | 29.8% | 219.9 |
| comma-short15-bias-e25 | 29.1% | 217.8 |
| comma-bias-coarse-e25 | 24.6% | 238.9 |
| comma-bias-e75 | 24.0% | 275.9 |
| emcts-comma | 22.4% | 301.0 |
| comma-bias-e50 | 22.0% | 270.7 |
| comma-bias-e25 | 21.3% | 278.0 |
| comma-bias-perburn-e25 | 21.2% | 284.5 |
| comma-bias-norem-e25 | 20.8% | 299.5 |
| comma-bias-e10 | 19.3% | 287.8 |
| emcts | 18.6% | 322.5 |

80,000 units, 1,568 turns, 20 arms:

| Arm | Best of field | Mean regret |
|---|---|---|
| comma-z0-coarse | 36.7% | 177.7 |
| comma-short15-coarse | 27.5% | 240.6 |
| comma-z0-mid | 27.2% | 278.6 |
| comma-rand15 | 22.1% | 306.4 |
| comma-short15 | 22.0% | 327.1 |
| comma-z0-fine | 18.8% | 398.7 |
| comma-short15-bias-e25 | 18.5% | 358.2 |
| comma-short5-bias-e25 | 18.4% | 363.3 |
| comma-bias-e75 | 16.3% | 421.4 |
| emcts-comma | 15.9% | 442.1 |
| comma-bias-e50 | 15.6% | 404.2 |
| comma-bias-coarse-e25 | 15.0% | 375.9 |
| comma-bias-norem-e25 | 14.9% | 435.7 |
| comma-bias-perburn-e25 | 14.9% | 415.2 |
| comma-z0-random | 14.7% | 423.2 |
| comma-bias-e25 | 14.7% | 420.7 |
| emcts | 13.4% | 465.7 |
| comma-bias-e10 | 13.1% | 434.0 |
| comma-bias-fine-e25 | 13.1% | 477.6 |
| emcts-bias-e25 | 12.2% | 459.3 |

400,000 units, 1,176 turns, 19 arms:

| Arm | Best of field | Mean regret |
|---|---|---|
| comma-z0-coarse | 34.3% | 209.7 |
| comma-z0-mid | 29.6% | 265.0 |
| comma-rand15 | 25.2% | 301.8 |
| comma-short15-coarse | 24.8% | 298.2 |
| comma-short15 | 24.6% | 319.1 |
| comma-z0-fine | 21.2% | 402.0 |
| comma-short5-bias-e25 | 19.2% | 443.8 |
| comma-short15-bias-e25 | 18.1% | 451.9 |
| emcts-comma | 17.0% | 487.2 |
| comma-bias-e50 | 16.6% | 469.3 |
| comma-bias-e75 | 16.5% | 452.9 |
| comma-z0-random | 16.2% | 450.0 |
| comma-bias-norem-e25 | 16.0% | 501.4 |
| comma-bias-coarse-e25 | 15.6% | 441.6 |
| comma-bias-perburn-e25 | 15.4% | 503.1 |
| comma-bias-e25 | 14.6% | 492.1 |
| comma-bias-fine-e25 | 13.4% | 578.9 |
| emcts | 12.9% | 621.1 |
| comma-bias-e10 | 11.3% | 551.9 |

## Effect size: Cohen's d

The mean paired difference over its standard deviation, on the no-terminal pairs. It treats the
pairs as independent, which they are not (visits share a scenario): a standardised size that travels
across studies, not a test.

| Split | Budget | Arm | d |
|---|---|---|---|
| holdout | 80000 | comma-rand15 | 0.42 |
| holdout | 80000 | comma-short15 | 0.34 |
| holdout | 80000 | comma-short15-coarse | 0.54 |
| holdout | 80000 | comma-z0-coarse | 0.62 |
| holdout | 80000 | comma-z0-fine | 0.11 |
| holdout | 80000 | comma-z0-mid | 0.44 |
| holdout | 80000 | comma-z0-random | 0.10 |
| holdout | 400000 | comma-rand15 | 0.49 |
| holdout | 400000 | comma-short15 | 0.43 |
| holdout | 400000 | comma-short15-coarse | 0.59 |
| holdout | 400000 | comma-z0-coarse | 0.67 |
| holdout | 400000 | comma-z0-fine | 0.21 |
| holdout | 400000 | comma-z0-mid | 0.54 |
| holdout | 400000 | comma-z0-random | 0.16 |
| dev | 40000 | comma-bias-coarse-e25 | 0.23 |
| dev | 40000 | comma-bias-e10 | 0.05 |
| dev | 40000 | comma-bias-e25 | 0.08 |
| dev | 40000 | comma-bias-e50 | 0.12 |
| dev | 40000 | comma-bias-e75 | 0.10 |
| dev | 40000 | comma-bias-norem-e25 | 0.01 |
| dev | 40000 | comma-bias-perburn-e25 | 0.06 |
| dev | 40000 | comma-short15 | 0.33 |
| dev | 40000 | comma-short15-bias-e25 | 0.25 |
| dev | 40000 | comma-short5-bias-e25 | 0.24 |
| dev | 40000 | emcts | -0.09 |
| dev | 80000 | comma-bias-coarse-e25 | 0.24 |
| dev | 80000 | comma-bias-e10 | 0.03 |
| dev | 80000 | comma-bias-e25 | 0.07 |
| dev | 80000 | comma-bias-e50 | 0.14 |
| dev | 80000 | comma-bias-e75 | 0.08 |
| dev | 80000 | comma-bias-fine-e25 | -0.14 |
| dev | 80000 | comma-bias-norem-e25 | 0.02 |
| dev | 80000 | comma-bias-perburn-e25 | 0.10 |
| dev | 80000 | comma-rand15 | 0.42 |
| dev | 80000 | comma-short15 | 0.36 |
| dev | 80000 | comma-short15-bias-e25 | 0.24 |
| dev | 80000 | comma-short15-coarse | 0.57 |
| dev | 80000 | comma-short5-bias-e25 | 0.23 |
| dev | 80000 | comma-z0-coarse | 0.67 |
| dev | 80000 | comma-z0-fine | 0.14 |
| dev | 80000 | comma-z0-mid | 0.43 |
| dev | 80000 | comma-z0-random | 0.06 |
| dev | 80000 | emcts | -0.10 |
| dev | 80000 | emcts-bias-e25 | -0.06 |
| dev | 400000 | comma-bias-coarse-e25 | 0.14 |
| dev | 400000 | comma-bias-e10 | -0.18 |
| dev | 400000 | comma-bias-e25 | -0.01 |
| dev | 400000 | comma-bias-e50 | 0.06 |
| dev | 400000 | comma-bias-e75 | 0.12 |
| dev | 400000 | comma-bias-fine-e25 | -0.28 |
| dev | 400000 | comma-bias-norem-e25 | -0.05 |
| dev | 400000 | comma-bias-perburn-e25 | -0.05 |
| dev | 400000 | comma-rand15 | 0.52 |
| dev | 400000 | comma-short15 | 0.42 |
| dev | 400000 | comma-short15-bias-e25 | 0.08 |
| dev | 400000 | comma-short15-coarse | 0.51 |
| dev | 400000 | comma-short5-bias-e25 | 0.10 |
| dev | 400000 | comma-z0-coarse | 0.66 |
| dev | 400000 | comma-z0-fine | 0.24 |
| dev | 400000 | comma-z0-mid | 0.50 |
| dev | 400000 | comma-z0-random | 0.10 |
| dev | 400000 | emcts | -0.42 |

## Anytime (holdout)

No curve rows (`curve.csv`) in this tree.

## Anytime (dev)

No curve rows (`curve.csv`) in this tree.

## Diversity (holdout, within-run only)

How each arm spent its budget, never whether that was good. The duplicate share is per mutation event
where the agent reports them, else per node. Agent telemetry means are in `tables/efficiency.csv`.

| Budget | Arm | Nodes | Unique | Dup share | Nodes vs baseline | Plan ms |
|---|---|---|---|---|---|---|
| 80000 | comma-rand15 | 913.9 | 825.3 | 0.072 | 2.00 | 6075 |
| 80000 | comma-short15 | 893.9 | 809.0 | 0.070 | 1.96 | 6132 |
| 80000 | comma-short15-coarse | 972.0 | 876.0 | 0.074 | 2.13 | 6610 |
| 80000 | comma-z0-coarse | 1435.3 | 1293.2 | 0.077 | 3.14 | 6956 |
| 80000 | comma-z0-fine | 1329.6 | 1178.1 | 0.084 | 2.91 | 6451 |
| 80000 | comma-z0-mid | 1421.2 | 1279.4 | 0.076 | 3.11 | 6922 |
| 80000 | comma-z0-random | 1373.4 | 1233.8 | 0.072 | 3.00 | 6393 |
| 80000 | emcts-comma | 457.2 | 407.7 | 0.064 | 1.00 | 5990 |
| 400000 | comma-rand15 | 4690.4 | 4171.0 | 0.080 | 1.88 | 21354 |
| 400000 | comma-short15 | 5134.1 | 4545.5 | 0.080 | 2.06 | 23706 |
| 400000 | comma-short15-coarse | 5265.4 | 4657.6 | 0.083 | 2.12 | 24303 |
| 400000 | comma-z0-coarse | 7788.7 | 6851.2 | 0.088 | 3.13 | 25904 |
| 400000 | comma-z0-fine | 7307.1 | 6282.5 | 0.101 | 2.94 | 23067 |
| 400000 | comma-z0-mid | 7387.2 | 6481.6 | 0.089 | 2.97 | 24045 |
| 400000 | comma-z0-random | 6822.6 | 6042.7 | 0.080 | 2.74 | 21294 |
| 400000 | emcts-comma | 2488.8 | 2176.7 | 0.073 | 1.00 | 21206 |

## Diversity (dev, within-run only)

How each arm spent its budget, never whether that was good. The duplicate share is per mutation event
where the agent reports them, else per node. Agent telemetry means are in `tables/efficiency.csv`.

| Budget | Arm | Nodes | Unique | Dup share | Nodes vs baseline | Plan ms |
|---|---|---|---|---|---|---|
| 40000 | comma-bias-coarse-e25 | 481.7 | 308.1 | 0.197 | 1.29 | 4585 |
| 40000 | comma-bias-e10 | 565.3 | 309.7 | 0.250 | 1.51 | 4611 |
| 40000 | comma-bias-e25 | 532.6 | 327.8 | 0.218 | 1.42 | 4688 |
| 40000 | comma-bias-e50 | 437.0 | 330.9 | 0.152 | 1.17 | 4465 |
| 40000 | comma-bias-e75 | 418.9 | 355.8 | 0.099 | 1.12 | 4603 |
| 40000 | comma-bias-norem-e25 | 388.3 | 288.7 | 0.117 | 1.04 | 4470 |
| 40000 | comma-bias-perburn-e25 | 497.6 | 315.5 | 0.209 | 1.33 | 4482 |
| 40000 | comma-short15 | 697.8 | 640.1 | 0.066 | 1.86 | 4540 |
| 40000 | comma-short15-bias-e25 | 944.8 | 584.5 | 0.254 | 2.52 | 4917 |
| 40000 | comma-short5-bias-e25 | 1189.0 | 711.0 | 0.284 | 3.18 | 5250 |
| 40000 | emcts | 435.5 | 349.1 | 0.103 | 1.16 | 4393 |
| 40000 | emcts-comma | 374.2 | 338.1 | 0.062 | 1.00 | 4345 |
| 80000 | comma-bias-coarse-e25 | 620.0 | 386.5 | 0.204 | 1.31 | 5929 |
| 80000 | comma-bias-e10 | 725.6 | 384.2 | 0.261 | 1.53 | 5919 |
| 80000 | comma-bias-e25 | 699.5 | 417.6 | 0.228 | 1.47 | 6123 |
| 80000 | comma-bias-e50 | 564.9 | 422.0 | 0.157 | 1.19 | 5820 |
| 80000 | comma-bias-e75 | 513.3 | 434.4 | 0.101 | 1.08 | 5923 |
| 80000 | comma-bias-fine-e25 | 686.4 | 384.1 | 0.322 | 1.44 | 6133 |
| 80000 | comma-bias-norem-e25 | 524.7 | 378.3 | 0.123 | 1.10 | 5957 |
| 80000 | comma-bias-perburn-e25 | 662.9 | 408.4 | 0.217 | 1.40 | 5925 |
| 80000 | comma-rand15 | 939.3 | 860.8 | 0.068 | 1.98 | 6155 |
| 80000 | comma-short15 | 917.8 | 839.7 | 0.068 | 1.93 | 6046 |
| 80000 | comma-short15-bias-e25 | 1193.3 | 721.2 | 0.263 | 2.51 | 6248 |
| 80000 | comma-short15-coarse | 978.3 | 894.0 | 0.070 | 2.06 | 6583 |
| 80000 | comma-short5-bias-e25 | 1490.3 | 874.9 | 0.290 | 3.14 | 6589 |
| 80000 | comma-z0-coarse | 1457.6 | 1332.5 | 0.072 | 3.07 | 6778 |
| 80000 | comma-z0-fine | 1334.2 | 1194.4 | 0.082 | 2.81 | 6306 |
| 80000 | comma-z0-mid | 1426.8 | 1296.6 | 0.073 | 3.00 | 6741 |
| 80000 | comma-z0-random | 1369.1 | 1251.4 | 0.068 | 2.88 | 6199 |
| 80000 | emcts | 569.2 | 443.9 | 0.113 | 1.20 | 5966 |
| 80000 | emcts-bias-e25 | 694.6 | 371.6 | 0.259 | 1.46 | 5882 |
| 80000 | emcts-comma | 475.0 | 428.2 | 0.063 | 1.00 | 5569 |
| 400000 | comma-bias-coarse-e25 | 3339.5 | 1766.2 | 0.248 | 1.29 | 21592 |
| 400000 | comma-bias-e10 | 4012.8 | 1607.1 | 0.339 | 1.55 | 20129 |
| 400000 | comma-bias-e25 | 4432.0 | 2124.0 | 0.288 | 1.71 | 25446 |
| 400000 | comma-bias-e50 | 2904.6 | 1990.8 | 0.190 | 1.12 | 20871 |
| 400000 | comma-bias-e75 | 2604.7 | 2132.7 | 0.116 | 1.01 | 20894 |
| 400000 | comma-bias-fine-e25 | 3355.3 | 1617.3 | 0.408 | 1.30 | 20966 |
| 400000 | comma-bias-norem-e25 | 3285.7 | 1882.5 | 0.171 | 1.27 | 21756 |
| 400000 | comma-bias-perburn-e25 | 2906.7 | 1675.9 | 0.245 | 1.12 | 18619 |
| 400000 | comma-rand15 | 4857.3 | 4371.0 | 0.078 | 1.88 | 20666 |
| 400000 | comma-short15 | 5291.7 | 4750.6 | 0.077 | 2.04 | 23361 |
| 400000 | comma-short15-bias-e25 | 5951.5 | 3090.8 | 0.301 | 2.30 | 20189 |
| 400000 | comma-short15-coarse | 4803.5 | 4316.7 | 0.078 | 1.85 | 20644 |
| 400000 | comma-short5-bias-e25 | 7356.6 | 3843.6 | 0.317 | 2.84 | 21574 |
| 400000 | comma-z0-coarse | 8006.5 | 7161.8 | 0.084 | 3.09 | 26129 |
| 400000 | comma-z0-fine | 7317.7 | 6401.3 | 0.097 | 2.83 | 23243 |
| 400000 | comma-z0-mid | 7414.6 | 6596.6 | 0.086 | 2.86 | 24270 |
| 400000 | comma-z0-random | 6884.1 | 6223.2 | 0.075 | 2.66 | 21531 |
| 400000 | emcts | 2538.5 | 1651.8 | 0.183 | 0.98 | 17550 |
| 400000 | emcts-comma | 2589.7 | 2278.4 | 0.072 | 1.00 | 20822 |

## Design (holdout): scenarios versus visits

ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 80000 | comma-rand15 | 200 | 8.0 | 0.292 | 13.8 | 12.1 | 14.2% | 526 | 1600 |
| 80000 | comma-short15 | 200 | 8.0 | 0.254 | 13.6 | 11.6 | 16.9% | 575 | 1600 |
| 80000 | comma-short15-coarse | 200 | 8.0 | 0.373 | 17.2 | 15.6 | 10.0% | 443 | 1600 |
| 80000 | comma-z0-coarse | 200 | 8.0 | 0.409 | 19.1 | 17.6 | 8.7% | 414 | 1600 |
| 80000 | comma-z0-fine | 200 | 8.0 | 0.345 | 14.7 | 13.2 | 11.3% | 469 | 1600 |
| 80000 | comma-z0-mid | 200 | 8.0 | 0.331 | 17.2 | 15.4 | 11.9% | 483 | 1600 |
| 80000 | comma-z0-random | 200 | 8.0 | 0.338 | 14.1 | 12.6 | 11.6% | 476 | 1600 |
| 400000 | comma-rand15 | 200 | 6.0 | 0.392 | 18.3 | 16.3 | 12.2% | 405 | 1200 |
| 400000 | comma-short15 | 200 | 6.0 | 0.317 | 17.8 | 15.2 | 16.6% | 464 | 1200 |
| 400000 | comma-short15-coarse | 200 | 6.0 | 0.440 | 21.6 | 19.6 | 10.1% | 375 | 1200 |
| 400000 | comma-z0-coarse | 200 | 6.0 | 0.477 | 23.5 | 21.6 | 8.7% | 354 | 1200 |
| 400000 | comma-z0-fine | 200 | 6.0 | 0.257 | 15.3 | 12.6 | 21.7% | 525 | 1200 |
| 400000 | comma-z0-mid | 200 | 6.0 | 0.421 | 22.8 | 20.6 | 10.9% | 387 | 1200 |
| 400000 | comma-z0-random | 200 | 6.0 | 0.314 | 17.0 | 14.5 | 16.8% | 467 | 1200 |

Median ICC 0.341. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 19 (median over arms).

## Design (dev): scenarios versus visits

ICC is the share of the paired difference's variance that sits between scenarios. The floor is the
standard error this scenario count can reach with unlimited visits; `Excess` is how far the design
actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match
the same precision.

| Budget | Arm | Scenarios | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns |
|---|---|---|---|---|---|---|---|---|---|
| 40000 | comma-bias-coarse-e25 | 200 | 8.0 | 0.161 | 9.7 | 7.6 | 28.4% | 751 | 1600 |
| 40000 | comma-bias-e10 | 200 | 8.0 | 0.144 | 9.4 | 7.1 | 32.0% | 796 | 1600 |
| 40000 | comma-bias-e25 | 200 | 8.0 | 0.143 | 10.0 | 7.6 | 32.3% | 800 | 1600 |
| 40000 | comma-bias-e50 | 200 | 8.0 | 0.055 | 7.4 | 4.1 | 77.9% | 1157 | 1600 |
| 40000 | comma-bias-e75 | 200 | 8.0 | 0.011 | 6.5 | 1.8 | 257.1% | 1490 | 1600 |
| 40000 | comma-bias-norem-e25 | 200 | 8.0 | 0.053 | 7.3 | 4.1 | 79.7% | 1166 | 1600 |
| 40000 | comma-bias-perburn-e25 | 200 | 8.0 | 0.131 | 9.2 | 6.8 | 35.3% | 835 | 1600 |
| 40000 | comma-short15 | 200 | 8.0 | 0.182 | 11.6 | 9.3 | 24.9% | 703 | 1600 |
| 40000 | comma-short15-bias-e25 | 200 | 8.0 | 0.279 | 14.4 | 12.5 | 15.0% | 542 | 1600 |
| 40000 | comma-short5-bias-e25 | 200 | 8.0 | 0.211 | 13.2 | 10.9 | 21.1% | 646 | 1600 |
| 40000 | emcts | 200 | 8.0 | 0.078 | 7.3 | 4.6 | 57.5% | 1036 | 1600 |
| 80000 | comma-bias-coarse-e25 | 200 | 8.0 | 0.159 | 10.2 | 7.9 | 28.8% | 756 | 1600 |
| 80000 | comma-bias-e10 | 200 | 8.0 | 0.173 | 10.5 | 8.3 | 26.4% | 724 | 1600 |
| 80000 | comma-bias-e25 | 200 | 8.0 | 0.169 | 10.9 | 8.6 | 27.0% | 732 | 1600 |
| 80000 | comma-bias-e50 | 200 | 8.0 | 0.055 | 8.0 | 4.5 | 77.3% | 1155 | 1600 |
| 80000 | comma-bias-e75 | 200 | 8.0 | 0.017 | 7.1 | 2.4 | 190.6% | 1434 | 1600 |
| 80000 | comma-bias-fine-e25 | 200 | 8.0 | 0.142 | 9.1 | 6.9 | 32.5% | 803 | 1600 |
| 80000 | comma-bias-norem-e25 | 200 | 8.0 | 0.044 | 7.6 | 4.0 | 92.2% | 1221 | 1600 |
| 80000 | comma-bias-perburn-e25 | 200 | 8.0 | 0.133 | 9.1 | 6.8 | 34.7% | 828 | 1600 |
| 80000 | comma-rand15 | 200 | 8.0 | 0.308 | 14.4 | 12.7 | 13.2% | 507 | 1600 |
| 80000 | comma-short15 | 200 | 8.0 | 0.183 | 12.2 | 9.8 | 24.9% | 702 | 1600 |
| 80000 | comma-short15-bias-e25 | 200 | 8.0 | 0.294 | 15.3 | 13.4 | 14.0% | 523 | 1600 |
| 80000 | comma-short15-coarse | 200 | 8.0 | 0.415 | 17.7 | 16.3 | 8.4% | 409 | 1600 |
| 80000 | comma-short5-bias-e25 | 200 | 8.0 | 0.213 | 13.8 | 11.4 | 21.0% | 643 | 1600 |
| 80000 | comma-z0-coarse | 200 | 8.0 | 0.473 | 20.6 | 19.3 | 6.7% | 371 | 1600 |
| 80000 | comma-z0-fine | 200 | 8.0 | 0.275 | 13.1 | 11.4 | 15.3% | 547 | 1600 |
| 80000 | comma-z0-mid | 200 | 8.0 | 0.358 | 17.8 | 16.1 | 10.7% | 457 | 1600 |
| 80000 | comma-z0-random | 200 | 8.0 | 0.279 | 13.2 | 11.5 | 15.0% | 541 | 1600 |
| 80000 | emcts | 200 | 8.0 | 0.015 | 6.4 | 2.1 | 207.6% | 1452 | 1600 |
| 80000 | emcts-bias-e25 | 200 | 8.0 | 0.130 | 9.2 | 6.8 | 35.6% | 839 | 1600 |
| 400000 | comma-bias-coarse-e25 | 200 | 6.0 | 0.241 | 13.8 | 11.1 | 23.4% | 544 | 1200 |
| 400000 | comma-bias-e10 | 200 | 6.0 | 0.311 | 16.6 | 14.2 | 17.0% | 470 | 1200 |
| 400000 | comma-bias-e25 | 200 | 6.0 | 0.228 | 14.8 | 11.9 | 25.1% | 561 | 1200 |
| 400000 | comma-bias-e50 | 200 | 6.0 | 0.220 | 12.9 | 10.2 | 26.1% | 571 | 1200 |
| 400000 | comma-bias-e75 | 200 | 6.0 | 0.048 | 8.8 | 4.3 | 107.4% | 968 | 1200 |
| 400000 | comma-bias-fine-e25 | 200 | 6.0 | 0.235 | 14.2 | 11.4 | 24.2% | 551 | 1200 |
| 400000 | comma-bias-norem-e25 | 200 | 6.0 | 0.195 | 12.4 | 9.6 | 29.9% | 607 | 1200 |
| 400000 | comma-bias-perburn-e25 | 200 | 6.0 | 0.219 | 14.2 | 11.3 | 26.3% | 573 | 1200 |
| 400000 | comma-rand15 | 200 | 6.0 | 0.385 | 17.5 | 15.6 | 12.5% | 410 | 1200 |
| 400000 | comma-short15 | 200 | 6.0 | 0.285 | 18.0 | 15.1 | 19.1% | 495 | 1200 |
| 400000 | comma-short15-bias-e25 | 200 | 6.0 | 0.324 | 20.2 | 17.4 | 16.1% | 458 | 1200 |
| 400000 | comma-short15-coarse | 200 | 6.0 | 0.446 | 19.5 | 17.8 | 9.9% | 371 | 1200 |
| 400000 | comma-short5-bias-e25 | 200 | 6.0 | 0.315 | 20.2 | 17.3 | 16.7% | 466 | 1200 |
| 400000 | comma-z0-coarse | 200 | 6.0 | 0.561 | 23.9 | 22.4 | 6.3% | 315 | 1200 |
| 400000 | comma-z0-fine | 200 | 6.0 | 0.370 | 17.5 | 15.4 | 13.3% | 421 | 1200 |
| 400000 | comma-z0-mid | 200 | 6.0 | 0.513 | 24.2 | 22.5 | 7.6% | 336 | 1200 |
| 400000 | comma-z0-random | 200 | 6.0 | 0.367 | 17.4 | 15.3 | 13.5% | 423 | 1200 |
| 400000 | emcts | 200 | 6.0 | 0.268 | 14.2 | 11.8 | 20.7% | 513 | 1200 |

Median ICC 0.216. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises
with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the
only reason to run more than one visit is that the portfolio is finite. Visits needed to sit within 5% of this portfolio's floor: 36 (median over arms).

## Scope notes

- holdout: multipliers outside the ladder: comma-z0-coarse@80000 (extrapolated, 5.4x), comma-rand15@400000 (extrapolated, 3.6x), comma-short15@400000 (extrapolated, 3.2x), comma-short15-coarse@400000 (extrapolated, 5.7x), comma-z0-coarse@400000 (extrapolated, 7.9x), comma-z0-fine@400000 (extrapolated, 1.7x), comma-z0-mid@400000 (extrapolated, 5.4x), comma-z0-random@400000 (extrapolated, 1.5x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- holdout: W7 rows with over a quarter of pairs clamped to the baseline's time range: comma-rand15@80000 (41%), comma-short15@80000 (39%), comma-z0-random@80000 (27%), comma-rand15@400000 (58%), comma-short15@400000 (91%), comma-short15-coarse@400000 (93%), comma-z0-coarse@400000 (96%), comma-z0-fine@400000 (80%), comma-z0-mid@400000 (88%), comma-z0-random@400000 (51%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- holdout: terminal turns: up to 1.0% of a leaf (16 of 16 leaves have any); every headline is shown with and without them.
- dev: multipliers outside the ladder: emcts@40000 (outside, 0.6x), comma-bias-fine-e25@80000 (outside, 0.4x), comma-z0-coarse@80000 (extrapolated, 6.4x), comma-bias-coarse-e25@400000 (extrapolated, 1.4x), comma-bias-e50@400000 (extrapolated, 1.1x), comma-bias-e75@400000 (extrapolated, 1.3x), comma-rand15@400000 (extrapolated, 3.7x), comma-short15@400000 (extrapolated, 3.3x), comma-short15-bias-e25@400000 (extrapolated, 1.3x), comma-short15-coarse@400000 (extrapolated, 3.8x), comma-short5-bias-e25@400000 (extrapolated, 1.4x), comma-z0-coarse@400000 (extrapolated, 7.0x), comma-z0-fine@400000 (extrapolated, 1.8x), comma-z0-mid@400000 (extrapolated, 4.8x), comma-z0-random@400000 (extrapolated, 1.3x). An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.
- dev: W7 rows with over a quarter of pairs clamped to the baseline's time range: comma-bias-coarse-e25@40000 (36%), comma-bias-e10@40000 (30%), comma-bias-e50@40000 (39%), comma-bias-norem-e25@40000 (40%), comma-bias-perburn-e25@40000 (40%), comma-short15@40000 (33%), comma-short15-bias-e25@40000 (26%), emcts@40000 (38%), comma-bias-coarse-e25@400000 (51%), comma-bias-e10@400000 (32%), comma-bias-e25@400000 (96%), comma-bias-e50@400000 (42%), comma-bias-e75@400000 (44%), comma-bias-fine-e25@400000 (43%), comma-bias-norem-e25@400000 (66%), comma-rand15@400000 (49%), comma-short15@400000 (92%), comma-short15-bias-e25@400000 (32%), comma-short15-coarse@400000 (52%), comma-short5-bias-e25@400000 (45%), comma-z0-coarse@400000 (94%), comma-z0-fine@400000 (84%), comma-z0-mid@400000 (87%), comma-z0-random@400000 (60%). A clamped pair is compared with the baseline's nearest level, not interpolated.
- dev: terminal turns: up to 2.0% of a leaf (51 of 51 leaves have any); every headline is shown with and without them.
- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; it cannot re-run the stop rule.
- Full-game win rate (brief #12) is not measured in v0.1; it remains the optional external yardstick.

Full tables in `tables/` (every row names its split and its currency), the same numbers in `report.json`, figures in `figures/` when matplotlib is installed.
