# Design

MAGAT evaluates search algorithms for multi-action games by replanning single turns on a pinned
portfolio of positions under a **compute-unit budget**. A unit is a priced count of operations, so a
budget buys the same work on any machine. This page is the design of v0.1: what each part is for and
why it is built the way it is. The other documents go into each part in depth; the last section points
to them.

**The takeaway of v0.1.** Compute-based budgeting lets researchers on different machines compare
results. A published result can be replayed exactly elsewhere, re-priced in another machine's currency,
and set beside new results measured anywhere.

## 1. The problem

In a multi-action game a turn is a sequence of actions: in Tribes, moving every unit, building,
researching, then ending the turn. The number of possible turns is combinatorial, and search algorithms
for these games differ mostly in how they spend a fixed amount of thinking on one turn.

The usual way to compare them is to play many full games on whatever hardware is at hand, under a time
limit. That has three problems:
- **It is noisy.** A game's outcome depends on hundreds of turns and on chance; a small difference in
  one turn's quality is lost in it.
- **It is slow.** Each data point is a whole game.
- **It is not comparable across machines.** A time limit buys different amounts of work on different
  hardware, and on one machine under different load. Two papers that both say "1 second per turn" did
  not give their algorithms the same thinking.

## 2. The instrument: one turn, replanned

MAGAT measures what an algorithm does with one turn. A **portfolio** of positions is captured once from
played games and pinned. Each algorithm, an **arm**, replans the turn of the player to move from each
position, many times, under a budget, and MAGAT records the value of the plan it settles on, as the
game's heuristic scores where the plan leaves the game.

The claim this supports is deliberately narrow: under the same budget, on the same positions, with the
same chance events, this algorithm finds plans of this much more value, with this interval. What a
better turn is worth over a whole game is a different question, for a full-game yardstick, which v0.1
does not include.

## 3. The portfolio

- **Captured once, pinned by identity.** `magat portfolio build` plays source games at fixed,
  documented settings and keeps validated captures at fixed phases. `freeze` splits them and fixes their
  identity: a SHA-256 over the ordered `(id, source, phase)` tuples. Every currency, run tree and result
  names it. Tribes' published portfolio, `games/tribes/portfolio/v1/`, is 400 positions: 200 from
  single-player games and 200 from two-player games.
- **Rebuilt anywhere, verified.** The build is deterministic in its seeds, so any machine rebuilds the
  same positions. `portfolio verify` compares a rebuild with the published portfolio row by row, and plans
  from both sets of position files to show they are the same positions.
- **Two matched halves.** Positions are stratified by source and phase, ordered by branching factor, and
  dealt in matched pairs into a development half, for exploring, and a holdout half, for confirming a
  choice made on the first without changing anything after looking.

## 4. The paired design

Every arm replans every position of a split at each budget level, a fixed number of times (visits).
Visit `v` of position `s` is seeded `s × 1,000,000 + v`, for the arm's own random numbers and for the
position's chance events, which are reseeded with it. Two arms on the same `(position, visit)` therefore
start from the same state and meet the same chance events: their difference is a **paired** difference,
which is where the design's power comes from. The statistics treat the position as the cluster, because
visits of one position are not independent evidence.

Two arms producing identical rows is a bug (a renamed duplicate, a parameter that is not wired), never
thin data: the runner stops on it, and the analyzer refuses it.

## 5. The budget: compute units

### What a unit is

A budget is a number of compute units, not seconds or iterations:
- **seconds** depend on the machine and the moment;
- **iterations** reward whoever makes the cheapest iteration;
- **floating-point operations** are the wrong count for a forward model dominated by memory traffic,
  branching and allocation.

A unit is a **count of operations, priced**. The meter counts every forward-model call (`advance`,
`copy`, `evaluate`) exactly, on any machine, and every operation an arm declares of its own (a
legal-action query, a selection step, tree bookkeeping). A machine's **currency** prices each operation
in its own time, as a ratio to one reference `advance`. A turn spends units until its budget is gone, so
the counts are what the budget buys, and they are the same on every machine that spends the same
currency.

### Prices per arm and budget

Prices are set per `(arm, budget)`, because the same operation costs different amounts for arms that
reach different states, and at budgets where a larger tree stops fitting in cache. Every operation an
arm does is priced, its own as well as the forward model's: an arm whose own work were free would buy
more time with a unit than its baseline. Each arm declares its operations, and `check audit` shows that
the declared operations and the forward model account for its whole turn (more than 99% for the four
reference algorithms on Tribes).

### Levels are thinking time on a reference machine

A level is a round amount of **thinking time on the reference machine**. For Tribes the published levels
are 9,500, 20,000 and 101,500 units: 500, 1000 and 5000 ms of a whole turn, single-threaded, on a Dell
PowerEdge R7615 (`games/tribes/levels.json`, measured by `calibrate-time` over 64 positions). Because
prices are per arm, a level buys every arm the same time there. So a result at a level reads: *each arm
thought for this long, single-threaded, on the R7615*, whatever machine computed it. The time is a mean
over positions: a turn's cost per unit depends on its position, and units keep the work fair where a
real time limit would give arms different amounts of work on the same position.

### The whole turn spends the budget

A turn's spend is everything it does, its initialization included (a greedy initial plan, a seeded
population). An initialization outside the budget would let turns run over their level and would give an
algorithm with a costly one that work free. Every arm stops when its budget is spent, passing it by at
most the small unit of work under way, so a smaller budget means less thinking all the way down. The
calibration and `check time` report each cell's mean spend against its level and flag anything above
1.10×. The one exception is the equivalence gate (below), which plays a port by its original's rule.

## 6. Calibrating a machine

`magat calibrate` measures each cell `(arm, budget)` on the same positions with the same seeds, takes the
fastest of several repetitions (interference only slows a repetition down), and writes the prices to
`compute_weights.properties`. Every cell is played under the same fixed measurement weights, so every
session, merge and check measures the same turns. Its gates, all enforced, write nothing when they fail:
- the two fastest repetitions agree within 10% for every operation that matters to a turn;
- enough operations were counted, and every weight is plausible;
- **the bracket**: the reference cell is measured again at the end and must not have moved by more than
  10%.

What the calibrator cannot see is the machine: the power source, the clock policy, other programs.
`calibrate-controlled` adds that. It runs a preflight, keeps the measuring JVM on steady cores, waits
for a stability check (two estimates in a row that agree within 3%), measures every arm in **one
session**, monitors the machine throughout, and ends with a verdict (PASS, SUSPECT or FAIL) and two
checks: the machine against its own new currency, and against the game's published currency once there
is one.

**Extending and checking a currency.**
- A session over a currency that lacks an arm prices that arm and merges it **by ratio** to the session's
  own reference measurement, so a machine that is uniformly faster or slower cancels exactly.
- `calibrate --check` re-measures arms the currency prices and gates each cell's **turn price** within 10%.
- A run tree records the price of every leaf, and refuses to continue a leaf whose price changed (the
  **currency lock**).

## 7. The arms

An algorithm is written once against two interfaces:
- `Game<S, A>`, the game's side of the contract (copy, advance, legal actions, evaluate, turn structure,
  reseed, serialize), with laws that `check contract` runs on captured positions;
- `SearchAgent<S, A>`, which plans one turn from a copy of the position under a `TurnBudget`, and its
  `AgentFactory`, which names the arm and declares its operations.

The agent receives only the metered game. Its only randomness is its seed, and it records a
`TurnRecord`: the values, the raw counts, the units, the wall-clock, and diversity and telemetry.

v0.1 ships four reference algorithms, `@reference`, and a floor:

| Arm | What it is |
|---|---|
| `random` | random legal actions until the turn ends: the floor |
| `mcts` | UCT over the turn's actions, without rollouts (Kocsis and Szepesvári 2006; Browne et al. 2012) |
| `emcts` | Evolutionary MCTS (Baier and Cowling 2018), the engine's variant with (1+1) selection; the reference arm, whose `advance` defines the unit |
| `oep` | Online Evolutionary Planning (Justesen et al. 2017) |
| `tbets` | Turn-Based Evolutionary Tree Search (Taylor Neller), ported from the Tribes engine's agent |

**Ports are gated.** `emcts` and `tbets` are ports of agents in the Tribes engine. An `Oracle` drives the
original on the same position, seed and budget, and `check equivalence` requires the port to reproduce it
field for field: values, every count, the nodes, the units and the counters. The gate plays the port by
its original's rule for the initialization (the engine's EMCTS pays its greedy plan outside the budget,
its TBETS charges its seeding). A port makes every copy its original makes, because each Tribes copy draws
from the position's random generator: leaving one out lets a chance event come out otherwise.

## 8. Running

An experiment is a JSON spec ([`EXPERIMENTS.md`](EXPERIMENTS.md)): the arms and the baseline, the levels
(each a budget and a number of repetitions), the positions (a split, optionally narrowed), the currency
and the contrasts to report. `experiments/tribes/reference.json` runs the four algorithms and the floor at
the three published levels on the whole portfolio.

- **The validator** checks a spec before anything runs: every arm registered and priced at every level it
  runs, every declared operation included.
- **The runner** checkpoints each leaf (`<arm>/<budget>cu/`) per turn, so the same command resumes a
  stopped run, and `--retire-leaf` is the only way to discard one.
- **The guards** are past mistakes made structural: the currency lock, the portfolio and configuration
  lock, the identical-output assertion, and an audit of each leaf's unpriced time.

Results do not depend on the number of threads; only the wall-clock column does. Timing measurements
(calibration, `check audit`, `check time`) need an idle machine. Runs do not, because their results are
counts.

## 9. Analysis

`magat analyze` reads a run tree and writes every measure as a table, with `SUMMARY.md`:
- **the paired difference** against the baseline, per level and arm, with a scenario-clustered bootstrap
  interval, win/loss/tie counts and Cohen's *d*, in two strata (all turns, and turns where no plan ended
  the game);
- **the budget multiplier**: how much budget the baseline would need to match the arm, solved on the
  baseline's mean value per level, interpolated log-linearly in budget. It compares final values on the
  same cells: each arm's `initial` is its own starting plan, so a search gain does not compare across
  arms;
- **the wall-clock re-score**: each arm against the baseline at the wall-clock it actually spent, so a
  win in units that is not a win in seconds is called unresolved;
- the spec's **contrasts**, the **design** (what more positions or visits would buy), and the share of
  **terminal** plans.

Every row carries its raw operation counts, so `analyze --reprice <weights>` re-prices every turn in
another currency and re-scores every arm there. Re-pricing cannot re-run the stop rule, and the report
says so.

## 10. Comparing results across machines

| | Transfers? | How |
|---|---|---|
| Operation counts | exactly | every row carries them |
| The search itself | exactly, under the same weights | deterministic seeds and budgets; `check replay` re-plans published turns field for field |
| The prices | approximately | `calibrate --check` against the published currency gates each cell's turn price within 10% |
| Wall-clock | no, but always reported | `plan_ms` in every row |
| Budget multipliers | yes | they are dimensionless |

So a researcher with a different machine:
1. **calibrates it** with `calibrate-controlled`, and checks it against the published currency;
2. **runs the four algorithms** on the pinned portfolio at the published levels, and their own arm beside
   them;
3. **compares** with the published results:
   - `check replay` shows the machine plays the published turns exactly;
   - `analyze --compare` sets the two runs side by side: each machine's means and their difference, both
     budget multipliers, the seconds per turn, and the new turns re-priced in the published currency;
   - the drift check says how far the two machines' prices are apart.

Raw units are never compared across games: each game has its own currency. Budget multipliers are.

## 11. Determinism and provenance

- **Determinism.** Every source of variation is pinned: the seed per `(position, visit)`, the position's
  reseeded chance events, deterministic legal-action order, fixed measurement weights in calibration, LF
  line endings in every file whose hash is recorded.
- **Provenance.** Every run tree's `_run.json` records the MAGAT and engine commits, the game's
  configuration, the portfolio identity, the weights file and its hash, the spec's hash, the machine and
  the JDK, and every invocation. A commit with uncommitted code is marked `-dirty`; a notebook saved with
  its outputs is reported apart.

## 12. The code

| Part | Where |
|---|---|
| The core: the meter, the contract, agents, portfolio, calibration, the runner, probes, the CLI | `core/src/magat/` |
| The four reference algorithms, the floor and the guide's example | `core/src/magat/agents/` |
| The Tribes adapter and the oracles | `games/tribes/src/` |
| The Tribes engine, a fork of GAIGResearch/Tribes | `games/tribes/engine` (a submodule) |
| The analysis | `analysis/magat_analysis/` |
| The launcher | `magat.py` |

**Layering.** The core never imports a game or an engine; `magat layering` checks it. An adapter wraps its
engine's state (`TribesState`), whose one escape hatch, `raw()`, only the adapter and its oracles use.
Engine code is never copied into MAGAT.

## 13. What v0.1 leaves out

- **Full-game win rates**, as an external yardstick beside the turn measures.
- **The earlier study's algorithms and tooling** (33 EMCTS variants and their analysis), archived with
  the tag `ab-emcts-archive` ([`ARCHIVE.md`](ARCHIVE.md)).

## 14. Where to read more

| Read | For |
|---|---|
| [`METHODS.md`](METHODS.md) | the portfolio, the split, the paired design and each measure, in detail |
| [`CURRENCY.md`](CURRENCY.md) | the unit, the weights file, calibrating, merging and the drift check |
| [`CONTRACT.md`](CONTRACT.md) | the `Game` interface and its laws |
| [`ALGORITHMS.md`](ALGORITHMS.md) | the four reference algorithms and the floor |
| [`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md) | writing, declaring, pricing and gating an algorithm |
| [`ADDING_A_GAME.md`](ADDING_A_GAME.md) | writing a game adapter and capturing its portfolio |
| [`CALIBRATING_A_MACHINE.md`](CALIBRATING_A_MACHINE.md) | `calibrate-controlled` on a laptop or a shared server |
| [`OPERATIONS.md`](OPERATIONS.md) | the launcher, the notebook, runs and their guards, the gates |
| [`TRAPS.md`](TRAPS.md) | each trap that cost time, and what now prevents it |
