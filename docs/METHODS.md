# Methods

MAGAT evaluates a search algorithm by replanning one turn on a fixed set of captured positions, under
a budget of compute units, paired against a baseline that replans the same positions with the same
seeds. It does not play full games, which are slow, noisy and confounded by everything after the
first turn. This page is the method, with each choice and the reason for it. Every rule here was paid
for at least once; [`TRAPS.md`](TRAPS.md) has the bills.

## 1. The portfolio

A portfolio is a set of positions captured from played games:
- `magat portfolio build` plays source games and keeps validated captures at fixed phases;
- `magat portfolio freeze` splits them and fixes their identity.

Tribes' published portfolio (`games/tribes/portfolio/v1/`) is 400 positions:
- 200 from single-player games (`single`, ticks 10-30);
- 200 from two-player games (`dual`, ticks 15/20/25).

**Its identity** is a SHA-256 over the ordered `(id, source, phase)` tuples (`ec243fd7...` for
Tribes). The split files, the weights file, every run tree and every result name it. The build is
deterministic in its seeds and fixed weights, whatever the machine or the thread count. `magat
portfolio verify` checks a rebuild against the published one row by row, and plans from both sets of
scenario files to show they are the same positions.

**The split** is development and holdout, fixed before any arm runs. Positions are stratified by
`(source, phase)` and ordered by branching factor, then dealt in matched pairs, one to each half. The
split files are authoritative and are never recomputed by readers. Matched pairs give halves balanced
by construction, where an alternate draw can leave them unbalanced; `split_report.txt` shows it.

## 2. The paired design

Every arm replans every position of the split, at each budget level, a fixed number of times
(visits). Visit `v` of scenario `s` is seeded `s * 1,000,000 + v`, and the position's own randomness
is reseeded with it. So two arms on the same `(scenario, visit)` start from the same state with the
same chance events, and their difference is a **paired** difference. Pairing is what gives the
design its power. The statistics treat the scenario as the cluster: visits of one position are not
independent evidence.

**The budget ladder** is at least two levels, ideally three, with the crossing inside it. The budget
multiplier (below) interpolates the baseline's curve between levels; past the top of the ladder it
can only extrapolate, and an extrapolated multiplier is a lower bound at best. Choose the ladder so
the effect you expect crosses inside it.

## 3. Two halves: explore on one, confirm on the other

The development half is for exploring: trying parameters, comparing variants, choosing what to
report. The holdout half is for confirming a choice made on the development half, with nothing changed
after looking at it. `magat run --split dev|holdout|all` runs either half or both, and `magat analyze`
reports each half it is given, every table naming its split. MAGAT does not police how often the
holdout is read, so a report should say how it was used. A development effect that shrinks on the
holdout is the winner's curse, and the holdout is doing its job.

## 4. Two arms producing identical output is a bug

Identical rows from two arms are a renamed duplicate or a parameter that is not wired, not thin data.
The runner checks every level for it and stops, and the analyzer checks again on load.

## 5. What is measured

`magat analyze` produces every measure from one run tree.

| # | Measure | Table | What it answers |
|---|---|---|---|
| 1 | Paired per-(scenario, visit) difference, scenario-clustered bootstrap CI, Cohen's *d* | `headline.csv` | is A better than B, and by how much |
| 2 | Equivalent budget multiplier | `budget_multiplier.csv` | how much better, in a unit anyone can read |
| 3 | The wall-clock re-score | `wallclock.csv` | does the result survive in seconds |
| 4 | The spec's contrasts | `contrasts.csv` | the paired comparisons the experiment names |
| 5 | The design: scenarios against visits | `variance.csv` | how precise the run is, and what more visits would buy |
| 6 | Terminal plans | `terminals.csv` | how often a plan ends the game, which the headline reports apart |
| 7 | Spend, time and plans | `spend.csv` | did each turn spend its level, how long did it take, how long are the plans |
| 8 | Re-pricing in another currency | `repriced.csv`, `repriced_spend.csv` | the same work, priced on another machine |
| 9 | Across machines | `across.csv` | the same experiment on two machines, side by side |
| 10 | Full-game win rate | not in v0.1 | an optional external yardstick, never the primary |

How they are computed:

1. **The paired difference.** `final` of A minus `final` of B on each `(scenario, visit)`. The bootstrap
   resamples scenarios (all of a scenario's visits together), so the interval is honest about the
   clustering. A plan that ends the game scores a sentinel (about ±2×10⁹ in Tribes), so one missed win
   would swamp any mean: the headline, the contrasts and the design are taken on the pairs where neither
   plan ends the game, and the plans that do are counted beside them (`TermArm`, `TermBase`). The tables
   keep both strata, all pairs and no-terminal. Win/loss/tie counts and per-source means go beside it.
   The raw difference is a heuristic delta, never a result on its own: it is always read beside the
   budget multiplier.
2. **The budget multiplier.** The baseline's mean `final` at every level it ran, interpolated
   log-linearly in budget. For each arm and level the analyzer solves for the baseline budget that
   reaches the arm's mean `final` on the same cells. The multiplier is that budget divided by the
   arm's, with a bootstrap interval. It is flagged `inside`, `extrapolated` (past the top of the
   ladder), `outside` (below it) or `non-monotone` (a baseline curve that does not rise cannot be
   inverted). Its wall-clock twin solves the same crossing on the baseline's plan time. It compares
   values, not search gains (`final − initial`): an arm's `initial` is its own starting plan (the
   position, a greedy plan, the best of a random population), so a gain does not compare across arms.
3. **The wall-clock re-score.** The arm is re-scored on the baseline's value-versus-`plan_ms` curve at
   its own wall-clock. The verdict is one of *wins both*, *units only* (unresolved: a units-only win
   may be a pricing artefact), *wall-clock only* or *none*, both sides on the pairs where neither plan
   ends the game. Wall-clock is co-primary: every headline is given in units and in seconds. Its seconds
   are the run's own: a run on many threads shares the machine's caches and memory between its turns,
   which slows them, and slows a memory-hungry arm more than the others (point 6).
4. **Contrasts** come from the spec (`contrasts`), not from a list in the code.
5. **The design.** The share of the paired difference's variance that sits between scenarios (the
   ICC), the standard error the scenario count allows with unlimited visits, and how far the run sits
   above it. For a fixed number of turns, more scenarios beat more visits.
6. **Spend, time and plans.** Per arm and level: the mean turn's units, its initialization included,
   over the level, flagged above 1.10× as the calibration flags a cell; the mean seconds per turn, beside
   the time the level was set to (`games/<game>/levels.json`: the reference arm's turn, single-threaded,
   on the reference machine); the mean plan's length; the share of plans that end the game; and the run's
   audit from each leaf, the share of plan time the prices leave unaccounted for, on the leaf's threads.
   On one idle thread a calibrated cell leaves well under 1%, so a large share on many threads is
   contention. On the R7615's first run, at 62 threads, `emcts` took 1.34× to 1.43× its levels' seconds,
   in line with the 70% to 78% efficiency its thread sweep measured at 62 threads.
7. **Across machines** (`--compare`). Two runs of one experiment, each on its own machine and in its
   own currency. On the cells both ran, neither turn terminal: each machine's mean with its interval,
   and the paired difference, which is what the two currencies did to where the searches stopped (the
   chance events are the same). The budget multipliers are dimensionless and should agree; the seconds
   per turn are each machine's own; and one run's turns are re-priced in the other's currency.

The bootstrap draws each row from a generator seeded by the row's own identity, so re-running, or
adding an arm, leaves every other interval where it was. A re-analysis with the same numpy is
byte-identical.

## 6. Turns, not games: the scope

A replanned turn measures what a search does with a position. It does not measure what a stronger
turn is worth over a whole game: that is the job of a full-game yardstick, which v0.1 does not
include. MAGAT's claim is narrower and firmer. Under the same budget, on the same positions, with the
same chance events, this algorithm finds plans of this much more value, and here is the interval, in
units and in seconds.

## 7. Everything is recorded

Every row carries the raw operation counts, the wall-clock and the arm's telemetry. Every leaf
records its prices and the commit that played its turns, and every run records:
- the commit that started it, and each invocation's own;
- the engine commit;
- the game configuration;
- the portfolio identity;
- the spec's hash;
- the machine;
- the JDK.

A published result can be re-analysed, re-priced in another currency, and partly replayed
(`check replay`) on another machine. [`CURRENCY.md`](CURRENCY.md) says what transfers between
machines, and [`OPERATIONS.md`](OPERATIONS.md) says how.
