# Stage 0 — signal check summary

## Budget wall-clock on this machine

```
 Budget  Turns  MeanPlanMillis  MedianPlanMillis  P90PlanMillis  MeanInitMillis  MeanSearchUnits  MeanSearchFmCalls  MeanUniqueSequences  MedianEventsPerTurn
  10000    107           707.3             343.0         1903.6           112.3          10046.1             8871.3                280.6                305.0
  40000    101          1910.1            1758.0         3367.0            32.5          40040.6            35459.3                913.3               1011.0
 160000     94          8499.6            6846.5        12498.7            47.2         160052.7           140957.4               3271.6               3856.5
 640000    114         56497.8           39449.5       132365.5            40.0         640068.2           565252.9              10078.6              13153.0
```

## Verdict per (budget, granularity)

Criteria: exploitable signal iff temporal (1st->2nd half) rho >= 0.15 with 95% CI excluding 0.

| Budget | Granularity | Temporal rho [CI] | Odd/even rho | Top-tercile uplift (z) | Turns | Verdict |
|---|---|---|---|---|---|---|
| 10000 | Coarse | +0.742 [+0.664, +0.813] | +0.740 | +0.297 | 68 | **SIGNAL** |
| 40000 | Coarse | +0.754 [+0.698, +0.804] | +0.756 | +0.280 | 97 | **SIGNAL** |
| 160000 | Coarse | +0.804 [+0.753, +0.853] | +0.783 | +0.304 | 90 | **SIGNAL** |
| 640000 | Coarse | +0.883 [+0.851, +0.911] | +0.888 | +0.285 | 108 | **SIGNAL** |
| 10000 | Mid | +0.649 [+0.563, +0.726] | +0.680 | +0.423 | 80 | **SIGNAL** |
| 40000 | Mid | +0.676 [+0.621, +0.731] | +0.686 | +0.490 | 100 | **SIGNAL** |
| 160000 | Mid | +0.742 [+0.686, +0.796] | +0.729 | +0.640 | 90 | **SIGNAL** |
| 640000 | Mid | +0.849 [+0.819, +0.878] | +0.850 | +0.725 | 109 | **SIGNAL** |
| 10000 | Fine | +0.499 [+0.411, +0.583] | +0.513 | +0.359 | 56 | **SIGNAL** |
| 40000 | Fine | +0.527 [+0.454, +0.596] | +0.524 | +0.270 | 83 | **SIGNAL** |
| 160000 | Fine | +0.499 [+0.439, +0.561] | +0.509 | +0.277 | 93 | **SIGNAL** |
| 640000 | Fine | +0.677 [+0.636, +0.716] | +0.671 | +0.322 | 114 | **SIGNAL** |

Full tables in `tables/`, figures in `figures/`.
