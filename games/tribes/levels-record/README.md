# The record behind the published Tribes levels

`games/tribes/levels.json` (summary: `games/tribes/levels.md`) holds the budget levels every Tribes currency
and experiment is measured at: **9,500 / 20,000 / 101,500 units**. These are 500, 1000 and 5000 ms of a whole
`emcts` turn, its initialization included, single-threaded on the R7615 (AMD EPYC 9534). Each level is a
mean over 64 positions spread across the portfolio ([units as time](../../../docs/CURRENCY.md#units-as-time)).

They were measured on 2026-10-10 by `magat calibrate-time`, taking 64 min. The engine was `828b130` and MAGAT
was `dd4ae73`, the merge of the levels-as-time change. The `-dirty` in the record is the notebook the
measurement ran from, saved with its outputs.

| File | What it is |
|---|---|
| `probe.json` | The first guess: `emcts` at 20,000 units under the fixed measurement weights, 16 positions. |
| `iter-<k>/compute_weights.properties` (+ `.csv`) | Iteration k's working currency: `emcts` alone, calibrated at that iteration's levels. |
| `iter-<k>/time.json` | That currency's turns timed over 64 positions: each level's mean, median, quartiles, range and initialization. |

**Why it took four iterations.** The probe priced a turn at 39 µs per unit. Under `emcts`'s own prices,
over 64 positions, a unit took 47–51 µs, so the first guess was 22–25% long. Iteration 2 was within 6.4%
and iteration 3 within 2.7%. Iteration 4 timed the rounded levels: +1.1%, +0.6% and +1.6%.

**The working currencies are not currencies to run with.** They price one arm and were measured to find the levels,
not to compare arms. The R7615's currency at these levels is calibrated separately, under its own name.

**Measuring the levels again** is a maintainer's job, done on the reference machine and only after a change that
alters what a turn costs, such as a new engine commit. New levels change what a unit means, so every currency
must then be measured again under a new name. See `notebooks/maintainers/measure_levels.ipynb`.
