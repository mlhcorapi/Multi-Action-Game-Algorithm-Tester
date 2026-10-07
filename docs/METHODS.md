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

**The split** is development and holdout, frozen before any arm runs. Positions are stratified by
`(source, phase)` and ordered by branching factor, then dealt in matched pairs, one to each half. The
split files are authoritative and are never recomputed by readers. Alternate draws gave the first
thesis split 33/27 positions and a 22-point branching imbalance; matched pairs give halves balanced
by construction, and `split_report.txt` shows it.

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

## 3. The holdout is read once

The development half is for exploring: sweeps, ablations, choosing a champion. The holdout half is
for confirming, once, with choices frozen first:
- `magat run --split holdout` refuses to start without `--frozen <file>`, the frozen choices, whose
  hash goes into the provenance;
- the run appends to `_holdout_ledger.json` and prints the ledger first;
- a second experiment on the same holdout gets a warning it cannot miss.

Every headline appears twice, development and holdout side by side, or not at all. A development
effect that shrinks on the holdout is the winner's curse, and the holdout is doing its job.

## 4. Gates are written before the data exists

Each stage has gates: a decision rule and the response to each outcome, recorded in the spec's
`preregistration` file before the run. Four of six gates in the thesis fired against expectation and
were accepted *because* the response was pre-registered. A gate written after the data is a story.

## 5. Grids carry their nulls and their controls

- **Every parameter sweep includes the parameter's null.** The AB-EMCTS champion sat at ε = 1.00,
  outside the swept grid, and was found only because one arm happened to be defined with the bias off.
  The spec validator refuses a sweep without its null.
- **A mechanism is credited only against its attribution control:** the same arm with the mechanism
  replaced by noise, consuming the same random draws. `comma-z0-coarse` (a learned table picks repair
  actions) is credited by its contrast with `comma-z0-random` (a uniform draw in the table's place),
  not by its contrast with the baseline. An arm that declares a control
  (`AgentFactory.attributionControl()`) must have it in the grid: an error on the holdout, a warning in
  development.
- **Two arms producing identical output is a bug, not thin data.** The runner checks every level for it
  and stops, and the analyzer checks again on load.
- **Conceptually distinct parameters are decoupled.** Insertion granularity and repair granularity were
  tied by a helper in the thesis, and untying them cost a follow-up stage.

## 6. What is measured

`magat analyze` produces every measure from one run tree. They are listed in order of reliability.

| # | Measure | Table | What it answers |
|---|---|---|---|
| 1 | Paired per-(scenario, visit) difference, scenario-clustered bootstrap CI | `headline.csv` | is A better than B, and by how much |
| 2 | Equivalent budget multiplier | `budget_multiplier.csv` | how much better, in a unit anyone can read |
| 3 | W7: the wall-clock re-score | `w7_wallclock.csv` | does the result survive in seconds |
| 4 | Development and holdout side by side | `replication.csv` | is it real, or the winner's curse |
| 5 | Attribution contrast | `contrasts.csv` | is the mechanism doing it |
| 6 | Search-gain multiple | `gain_multiple.csv` | how much more the search extracts |
| 7 | Oracle regret and best-of-field share | `oracle.csv` | who finds the best plan |
| 8 | Cohen's *d* on the paired difference | in `headline.csv` | comparability across studies |
| 9 | Anytime curves | `anytime.csv` | when the effect appears |
| 10 | Diversity (unique plans, duplicate share) | `efficiency.csv` | mechanism diagnostics, within one run only |
| 11 | The raw heuristic difference | `MeanDiff` | nothing on its own: always beside #2 or #6 |
| 12 | Full-game win rate | not in v0.1 | an optional external yardstick, never the primary |

How the main ones are computed:

1. **The paired difference.** `final` of A minus `final` of B on each `(scenario, visit)`. The bootstrap
   resamples scenarios (all of a scenario's visits together), so the interval is honest about the
   clustering. Each row is reported in two strata, all turns and turns where neither plan is terminal,
   because a terminal plan carries a sentinel value. Win/loss/tie counts and per-source intervals go
   beside it.
2. **The budget multiplier.** The search gain (`final − initial`) of the baseline at every level it
   ran, interpolated log-linearly in budget. For each arm and level the analyzer solves for the
   baseline budget that reaches the arm's gain. The multiplier is that budget divided by the arm's,
   with a bootstrap interval. It is flagged `inside`, `extrapolated` (past the top of the ladder),
   `outside` (below it) or `non-monotone` (a baseline curve that does not rise cannot be inverted).
   Its wall-clock twin solves the same crossing on the baseline's plan time.
3. **W7.** The arm is re-scored on the baseline's value-versus-`plan_ms` curve at its own wall-clock.
   The verdict is one of *wins both*, *units only* (unresolved: a units-only win may be a pricing
   artefact), *wall-clock only* or *none*. Wall-clock is co-primary: every headline is given in units
   and in seconds.
4. **Replication.** Each development row carries its holdout twin. The state is `yes` when the holdout
   point is inside the development interval and the holdout interval excludes the null; `no`
   otherwise; and `ns both` when neither interval excludes the null, which is a null that replicated.
5. **Contrasts** come from the spec (`contrasts`, and every arm's `attribution`), not from a list in
   the code.

The bootstrap draws each row from a generator seeded by the row's own identity, so re-running, or
adding an arm, leaves every other interval where it was. A re-analysis with the same numpy is
byte-identical.

## 7. Turns, not games: the scope

A replanned turn measures what a search does with a position. It does not measure what a stronger
turn is worth over a whole game: that is the job of measure #12, the optional yardstick. MAGAT's claim
is narrower and firmer. Under the same budget, on the same positions, with the same chance events,
this algorithm finds plans of this much more value, and here is the interval, in units and in
seconds.

## 8. Everything is recorded

Every row carries the raw operation counts, the wall-clock and the arm's telemetry. Every leaf
records its prices, and every run records:
- the commit;
- the engine commit;
- the game configuration;
- the portfolio identity;
- the spec's and the frozen choices' hashes;
- the machine;
- the JDK.

A published result can be re-analysed, re-priced in another currency, and partly replayed
(`check replay`) on another machine. [`OPERATIONS.md`](OPERATIONS.md) says how.

The worked example of all of this is the thesis's AB-EMCTS line, re-analysed by MAGAT:
[`results/tribes-ab-emcts.md`](results/tribes-ab-emcts.md).
