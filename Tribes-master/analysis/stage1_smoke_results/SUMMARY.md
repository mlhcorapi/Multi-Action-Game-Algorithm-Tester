# Stage 1 — biased mutation results

Positive MeanDiff = the config finds BETTER final sequences than Baseline on the
same (scenario, visit) seeds. CI is a scenario-clustered bootstrap.

## 8000cu

| Config | Pairs | MeanDiff [CI] | RelDiff | WinRate |
|---|---|---|---|---|
| Bias_e25 | 6 | +19.2 [-42.5, +81.7] | +0.639 | 0.750 |
| Short15_e25 | 6 | **+382.5 [+71.7, +773.3]** | +5.230 | 1.000 |

Full tables in `tables/`, figures in `figures/`.
