# v4 sequence-value significance (paired Wilcoxon signed-rank)

Two-sided Wilcoxon signed-rank tests on the **per-scenario** paired difference
Δ = (agent mean final sequence value) − (EMCTS mean final sequence value), where each scenario's
value is the mean over its 50 repeats. Run per time budget and split; Benjamini–Hochberg correction
across the 11 agents within each (budget, split). Significance at α = 0.05 on the **adjusted** p.

**Test appropriateness.** The design is paired (every agent faces the same 100 scenarios with the
same shared random initial sequences), and the per-scenario Δ are non-normal — Shapiro–Wilk rejects
normality (p<0.05) for **97%** of comparisons — so the non-parametric paired
Wilcoxon is the right choice (and matches the win-rate test). n = 100 (all), 50 (each split).

## Headline — CrossoverExpansion@5 vs EMCTS (all scenarios)

| budget | n | mean Δ | median Δ | W | p_raw | p_adj | rank-biserial | sig |
|---|--:|--:|--:|--:|--:|--:|--:|:--:|
| 500 ms | 100 | +8.335 | +3.570 | 2056 | 0.107 | 0.236 | +0.186 | · |
| 1000 ms | 100 | +10.051 | +4.880 | 1974 | 0.058 | 0.107 | +0.218 | · |
| 5000 ms | 100 | +1.179 | -0.050 | 2390 | 0.645 | 0.710 | -0.054 | · |
| avg | 100 | +6.521 | +4.880 | 1955 | 0.096 | 0.132 | +0.194 | · |

## All agents vs EMCTS (all scenarios)

| agent | budget | n | mean Δ | median Δ | rank-biserial | p_raw | p_adj | sig |
|---|---|--:|--:|--:|--:|--:|--:|:--:|
| CBB | 500 ms | 100 | -3.76 | -3.39 | -0.14 | 0.224 | 0.324 | · |
| CE@2 | 500 ms | 100 | +16.36 | +9.09 | +0.46 | 3.8e-05 | 4.2e-04 | **✓** |
| CE@5 | 500 ms | 100 | +8.33 | +3.57 | +0.19 | 0.107 | 0.236 | · |
| CE@10 | 500 ms | 100 | +6.63 | +2.67 | +0.26 | 0.024 | 0.132 | · |
| CE@15 | 500 ms | 100 | +3.67 | +1.49 | +0.21 | 0.071 | 0.236 | · |
| CE@20 | 500 ms | 100 | -0.40 | +0.00 | -0.27 | 0.465 | 0.511 | · |
| CC@2 | 500 ms | 100 | +5.25 | +4.26 | +0.19 | 0.102 | 0.236 | · |
| CC@5 | 500 ms | 100 | +6.11 | +3.26 | +0.17 | 0.132 | 0.242 | · |
| CC@10 | 500 ms | 100 | -3.28 | -0.23 | -0.02 | 0.896 | 0.896 | · |
| CC@15 | 500 ms | 100 | -2.36 | -3.00 | -0.13 | 0.276 | 0.338 | · |
| CC@20 | 500 ms | 100 | -3.49 | -1.89 | -0.14 | 0.236 | 0.324 | · |
| CBB | 1000 ms | 100 | -10.51 | -5.20 | -0.32 | 0.006 | 0.026 | **✓** |
| CE@2 | 1000 ms | 100 | +10.04 | +7.00 | +0.33 | 0.003 | 0.026 | **✓** |
| CE@5 | 1000 ms | 100 | +10.05 | +4.88 | +0.22 | 0.058 | 0.107 | · |
| CE@10 | 1000 ms | 100 | +5.61 | +2.79 | +0.23 | 0.045 | 0.107 | · |
| CE@15 | 1000 ms | 100 | +2.47 | +1.58 | +0.19 | 0.096 | 0.132 | · |
| CE@20 | 1000 ms | 100 | +0.15 | +0.00 | +0.11 | 0.525 | 0.525 | · |
| CC@2 | 1000 ms | 100 | +7.17 | +3.46 | +0.21 | 0.069 | 0.109 | · |
| CC@5 | 1000 ms | 100 | +2.31 | +4.27 | +0.08 | 0.479 | 0.525 | · |
| CC@10 | 1000 ms | 100 | -5.32 | -1.56 | -0.10 | 0.376 | 0.460 | · |
| CC@15 | 1000 ms | 100 | -5.31 | -2.93 | -0.22 | 0.054 | 0.107 | · |
| CC@20 | 1000 ms | 100 | -10.92 | -4.79 | -0.31 | 0.007 | 0.026 | **✓** |
| CBB | 5000 ms | 100 | -23.52 | -16.64 | -0.59 | 8.9e-08 | 3.5e-07 | **✓** |
| CE@2 | 5000 ms | 100 | -13.85 | -6.03 | -0.36 | 0.002 | 0.002 | **✓** |
| CE@5 | 5000 ms | 100 | +1.18 | -0.05 | -0.05 | 0.645 | 0.710 | · |
| CE@10 | 5000 ms | 100 | +1.30 | -0.88 | -0.02 | 0.868 | 0.868 | · |
| CE@15 | 5000 ms | 100 | +4.27 | +3.92 | +0.21 | 0.062 | 0.086 | · |
| CE@20 | 5000 ms | 100 | +3.90 | +1.82 | +0.15 | 0.201 | 0.246 | · |
| CC@2 | 5000 ms | 100 | -30.56 | -23.26 | -0.71 | 5.5e-11 | 6.1e-10 | **✓** |
| CC@5 | 5000 ms | 100 | -17.82 | -12.81 | -0.51 | 4.4e-06 | 8.0e-06 | **✓** |
| CC@10 | 5000 ms | 100 | -19.39 | -15.35 | -0.59 | 9.5e-08 | 3.5e-07 | **✓** |
| CC@15 | 5000 ms | 100 | -17.35 | -13.98 | -0.57 | 3.4e-07 | 7.5e-07 | **✓** |
| CC@20 | 5000 ms | 100 | -18.75 | -15.69 | -0.57 | 2.4e-07 | 6.6e-07 | **✓** |
| CBB | avg | 100 | -12.60 | -8.62 | -0.53 | 2.5e-06 | 2.8e-05 | **✓** |
| CE@2 | avg | 100 | +4.18 | +1.72 | +0.16 | 0.177 | 0.216 | · |
| CE@5 | avg | 100 | +6.52 | +4.88 | +0.19 | 0.096 | 0.132 | · |
| CE@10 | avg | 100 | +4.51 | +2.45 | +0.20 | 0.087 | 0.132 | · |
| CE@15 | avg | 100 | +3.47 | +2.76 | +0.23 | 0.044 | 0.080 | · |
| CE@20 | avg | 100 | +1.22 | +0.15 | +0.10 | 0.393 | 0.414 | · |
| CC@2 | avg | 100 | -6.04 | -3.80 | -0.25 | 0.032 | 0.071 | · |
| CC@5 | avg | 100 | -3.13 | -1.70 | -0.09 | 0.414 | 0.414 | · |
| CC@10 | avg | 100 | -9.33 | -2.18 | -0.26 | 0.022 | 0.060 | · |
| CC@15 | avg | 100 | -8.34 | -6.35 | -0.38 | 8.6e-04 | 0.003 | **✓** |
| CC@20 | avg | 100 | -11.05 | -4.34 | -0.46 | 4.9e-05 | 2.7e-04 | **✓** |

`✓` = significant after BH correction (p_adj < 0.05). Full per-split detail (single / two-player) is
in `wilcoxon_sequence_value.csv`.

## Plain-prose summary

- **at 500 ms:** significantly beat EMCTS — CE@2 (+16.4); significantly underperformed — none.
- **at 1000 ms:** significantly beat EMCTS — CE@2 (+10.0); significantly underperformed — CC@20 (-10.9), CBB (-10.5).
- **at 5000 ms:** significantly beat EMCTS — none; significantly underperformed — CC@2 (-30.6), CBB (-23.5), CC@10 (-19.4), CC@20 (-18.8), CC@5 (-17.8), CC@15 (-17.3), CE@2 (-13.9).
- **averaged across budgets:** significantly beat EMCTS — none; significantly underperformed — CBB (-12.6), CC@20 (-11.1), CC@15 (-8.3).

## Caveats / flags

- **Near-zero-variance comparisons flagged:** 1 (see `note` column in the CSV).
- Bars/CI in `Fig5_significance.png` are the per-(scenario×visit) paired deltas (n≈5000, the existing
  Fig 5); the significance stars use the more conservative per-scenario Wilcoxon (n=100) so the test
  respects scenario clustering. The two agree on direction throughout.
