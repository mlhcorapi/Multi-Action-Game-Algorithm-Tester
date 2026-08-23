# Stage 0 — signal check summary

## Budget wall-clock on this machine

```
 Budget  Turns  MeanPlanMillis  MedianPlanMillis  P90PlanMillis  MeanInitMillis  MeanSearchUnits  MeanSearchFmCalls  MeanUniqueSequences  MedianEventsPerTurn
   6000      7           287.0             277.0          359.8            18.0           5998.1             5418.4                256.9                413.0
```

## Verdict per (budget, granularity)

Criteria: exploitable signal iff temporal (1st->2nd half) rho >= 0.15 with 95% CI excluding 0.

| Budget | Granularity | Temporal rho [CI] | Odd/even rho | Top-tercile uplift (z) | Turns | Verdict |
|---|---|---|---|---|---|---|
| 6000 | Coarse | +0.716 [+0.513, +0.901] | +0.755 | +0.336 | 5 | **SIGNAL** |
| 6000 | Mid | +0.702 [+0.364, +0.910] | +0.685 | +0.709 | 6 | **SIGNAL** |
| 6000 | Fine | +0.394 [+0.203, +0.591] | +0.577 | +0.382 | 7 | **SIGNAL** |

Full tables in `tables/`, figures in `figures/`.
