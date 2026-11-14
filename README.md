# MAGAT — Multi-Action Game Algorithm Tester

MAGAT evaluates search algorithms for **multi-action games**, where a turn is a sequence of actions,
by replanning single turns on a pinned portfolio of positions under a **compute-unit budget**. A unit
is a priced count of operations, so a budget buys the same work on any machine.

**The takeaway: compute-based budgeting lets researchers on different machines compare results.** A
result measured on one machine can be replayed exactly on another, re-priced in that machine's
currency, and set beside results measured anywhere. The first game is
[Tribes](https://github.com/GAIGResearch/Tribes).

## How it works

- **One turn, replanned.** A portfolio of 400 Tribes positions is captured once and pinned by its
  identity. Every algorithm (an **arm**) replans the turn of the player to move from each position,
  several times, with the same seeds and the same chance events as every other arm, so arms are
  compared pair by pair. The measure is the value of the plan, as the game's heuristic scores it.
- **A budget in compute units.** The meter counts every forward-model call (`advance`, `copy`,
  `evaluate`) and every operation an algorithm declares of its own. A machine's **currency** prices each
  operation in that machine's time. A turn spends units until its budget is gone, so the work a budget
  buys is a count: the same on every machine.
- **Levels are thinking time on a reference machine.** Tribes' levels are 9,500, 20,000 and 101,500
  units: 500, 1000 and 5000 ms of a whole turn, single-threaded, on a Dell PowerEdge R7615
  ([`games/tribes/levels.md`](games/tribes/levels.md)). A result at a level reads as though every arm
  had thought that long on the R7615, whichever machine computed it.
- **Results you can read across papers.** The paired difference against a baseline comes with
  scenario-clustered intervals, and the effect is also given as a **budget multiplier**: how much more
  budget the baseline would need to match the arm. Multipliers are dimensionless, so they compare
  across machines and currencies.

[`docs/DESIGN.md`](docs/DESIGN.md) is the whole design on one page.

## What you can do with it

**1. Calibrate your own machine.** One session prices every arm on your machine, with a preflight, a
stability check, a monitor and a verdict. It needs mains power and nothing else running, and says when
it will finish once the stability check has settled
([`docs/CALIBRATING_A_MACHINE.md`](docs/CALIBRATING_A_MACHINE.md)):

```bash
python magat.py calibrate-controlled --name <your-machine> --preflight
```

```bash
python magat.py calibrate-controlled --name <your-machine>
```

**2. Run the four reference algorithms on the pinned portfolio.** `experiments/tribes/reference.json`
runs `mcts`, `emcts`, `oep`, `tbets` and the random floor at the three published levels
([`docs/ALGORITHMS.md`](docs/ALGORITHMS.md)). One command validates the spec, runs it, analyses it,
prints the headline, and bundles the run and its report:

```bash
python magat.py experiment run experiments/tribes/reference.json --weights <your-machine>
```

`--weights <your-machine>` finds the currency you measured by its name
(`$HOME/magat_runs/calibration/tribes-<your-machine>/`). Without it, a run spends the published currency,
the R7615's, and your machine plays the R7615's turns exactly: a replay of the reference machine, not a
measurement of yours. The report is `$HOME/magat_runs/tribes/results/reference/SUMMARY.md`. Running the
same command again resumes a run that stopped.

**3. Compare with published results.** Every published row carries its raw operation counts, so:
- **replay**: `check replay` re-plans the published turns on your machine and compares them field for
  field;
- **side by side**: `analyze --run <your run> --compare <the published run>` gives, per arm and level,
  each machine's mean and their difference, both budget multipliers and the seconds per turn;
- **re-pricing**: `analyze --reprice <currency>` prices every turn of a run in another machine's currency
  and re-scores every arm there;
- **the drift check**: `calibrate-controlled` checks your machine against the published currency, cell
  by cell, and says how far apart the two machines' prices are.

The R7615's currency and the reference results are not published yet; they come with v0.1.

**4. Add your own algorithm.** Write it against the `Game` contract, declare its operations, register
it, and compare it with the four ([`docs/ADDING_AN_ALGORITHM.md`](docs/ADDING_AN_ALGORITHM.md)).

## Quick start

You need JDK 11 or newer, Python 3.10 or newer (with numpy and pandas for the analysis), and git.

```bash
git clone --recurse-submodules https://github.com/mlhcorapi/Multi-Action-Game-Algorithm-Tester.git
```

```bash
cd Multi-Action-Game-Algorithm-Tester
```

```bash
python magat.py build --tests
```

```bash
python magat.py test
```

```bash
python magat.py shakedown
```

The shakedown runs every moving part at toy size in about ten minutes and exits non-zero on any
failure. `python magat.py` with no arguments lists every command. Output goes outside the clone, under
`$HOME/magat_runs` (`MAGAT_DATA` moves it). On a server, [`notebooks/run_magat.ipynb`](notebooks/run_magat.ipynb)
runs everything, one cell per stage, each stage ending in a bundle to download.

## The afternoon path: add an algorithm and compare it, at toy size

These steps take a new algorithm from code to a report in a few minutes, on a toy portfolio. At toy size
the numbers mean nothing; the path is the point. The full-size path is the same commands without the toy
flags. Every command works the same in bash and in PowerShell.

1. **Build a toy portfolio and verify it against the published one** (`VERIFY: PASS` means your machine
   captured the same positions, row for row):

   ```bash
   python magat.py portfolio build --game tribes --out $HOME/magat_runs/portfolio/tribes-toy --targets single=2,dual=1 --threads 2
   ```

   ```bash
   python magat.py portfolio freeze --portfolio $HOME/magat_runs/portfolio/tribes-toy
   ```

   ```bash
   python magat.py portfolio verify --game tribes --portfolio $HOME/magat_runs/portfolio/tribes-toy --published games/tribes/portfolio/v1 --subset --plan 3
   ```

2. **Register an algorithm.** `core/src/magat/agents/first/FirstAgent.java` is a complete example, random
   restarts. Add one line to `TribesAgents.registry()` in
   `games/tribes/src/magat/games/tribes/TribesAgents.java`, then list the arms and audit it:

   ```java
   r.register(magat.agents.first.FirstAgent.factory());
   ```

   ```bash
   python magat.py arms --game tribes
   ```

   ```bash
   python magat.py check audit --game tribes --arms first --budget 4000 --turns 2 --reps 1 --warmup 1
   ```

3. **Price it**, beside the baseline, in a toy currency:

   ```bash
   python magat.py calibrate --game tribes --portfolio $HOME/magat_runs/portfolio/tribes-toy --out $HOME/magat_runs/calibration/tribes-toy --arms emcts,first --levels 2000,4000 --turns 2 --reps 2 --warmup 1 --toy
   ```

4. **Run it against the baseline.** Save this spec as `first-toy.json`:

   ```json
   {
     "name": "first-toy",
     "game": "tribes",
     "arms": ["emcts", "first"],
     "baseline": "emcts",
     "levels": [2000, 4000],
     "repetitions": 1,
     "split": "dev"
   }
   ```

   [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) describes every key a spec can have. Then validate, run
   and analyse it in one command, in the toy currency of step 3, found by its name:

   ```bash
   python magat.py experiment run first-toy.json --portfolio $HOME/magat_runs/portfolio/tribes-toy --weights toy --threads 2 --toy
   ```

5. **Read the report**, `$HOME/magat_runs/tribes/results/first-toy/SUMMARY.md`. It gives the provenance,
   the paired difference against the baseline in units and in seconds, the budget multiplier, the design
   and the scope notes.

## Status

**v0.1, in progress.** Tribes, with:
- a published portfolio of 400 positions, and published levels;
- four reference algorithms, `mcts`, `emcts`, `oep` and `tbets`, and `random`, the floor. `emcts` and
  `tbets` are ports of the engine's own agents, gated field for field against them;
- a published currency, the reference machine's (`games/tribes/calibration/r7615-time/`), and the
  reference experiment's run on it (`games/tribes/results/reference/r7615/`), to compare your machine
  with;
- the same experiment on a second machine, a laptop, in its own currency: the same ranking, every
  difference inside the intervals, and agreement position by position
  ([`docs/results/cross-machine.md`](docs/results/cross-machine.md)).

MAGAT grew out of an earlier study; what v0.1 set aside, and where it lives, is in
[`docs/ARCHIVE.md`](docs/ARCHIVE.md).

## Documentation

| Read | For |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | the design of v0.1 on one page |
| [`docs/METHODS.md`](docs/METHODS.md) | the portfolio, the split, the paired design, the measures |
| [`docs/CURRENCY.md`](docs/CURRENCY.md) | what a compute unit is, how a machine prices one, what transfers between machines |
| [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) | configuring an experiment: its arms, levels, repetitions, positions and currency |
| [`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) | the four reference algorithms and the floor |
| [`docs/CONTRACT.md`](docs/CONTRACT.md) | the `Game` interface and its laws, and the agent protocol |
| [`docs/ADDING_AN_ALGORITHM.md`](docs/ADDING_AN_ALGORITHM.md) | writing, declaring, pricing and gating an algorithm |
| [`docs/ADDING_A_GAME.md`](docs/ADDING_A_GAME.md) | writing a game adapter, capturing a portfolio, publishing a currency |
| [`docs/CALIBRATING_A_MACHINE.md`](docs/CALIBRATING_A_MACHINE.md) | calibrating a laptop or a shared server, in one session |
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | the launcher, where output goes, the notebook, runs and their guards, the gates |
| [`docs/results/cross-machine.md`](docs/results/cross-machine.md) | the reference experiment on two machines, compared position by position |
| [`docs/TRAPS.md`](docs/TRAPS.md) | each trap that cost time, and what now prevents it |
| [`docs/ARCHIVE.md`](docs/ARCHIVE.md) | what v0.1 set aside, where it lives, and how to re-add it |

## Layout

| Path | What |
|---|---|
| `core/` | the Java core (package root `magat`): the meter, the game contract, the agents, portfolio, calibration, the runner, probes, the CLI |
| `games/tribes/` | the Tribes adapter, its published portfolio and levels |
| `games/tribes/engine` | the Tribes engine: a git submodule of a fork of GAIGResearch/Tribes |
| `analysis/` | the Python analysis package, `magat_analysis`, and its tests |
| `experiments/` | experiment specs: the reference experiment, the shakedown, and `examples/` |
| `notebooks/` | the run notebook and the maintainers' levels notebook, generated by `tools/make_notebook.py` |
| `tools/` | the shakedown, `calibrate-controlled`, `calibrate-time`, the notebook generator and self-test, the layering check, the engine patches |
| `docs/` | the documentation |

## Credits

Tribes is by Diego Perez-Liebana, Yu-Jhen Hsu, Stavros Emmanouilidis, Bobby Khaleque and Raluca
Gaina: *Tribes: A New Turn-Based Strategy Game for AI*, AAAI Conference on Artificial Intelligence
and Interactive Digital Entertainment (AIIDE), 2020. The engine MAGAT runs is a fork of their
repository, with the agents MAGAT's ports are checked against and MAGAT's own changes: data files found
from any directory, and faster legal-action generation with identical results
([`tools/engine/README.md`](tools/engine/README.md)). The upstream repository carries no licence file;
the authors have given permission for MAGAT's use of their code. The fork is used as a submodule with
attribution, and none of it is copied into this repository.

The reference algorithms come from Kocsis and Szepesvári (2006) and Browne et al. (2012) for MCTS,
Baier and Cowling (2018) for EMCTS, Justesen et al. (2017) for OEP, and Taylor Neller for TBETS
([the paper](https://doi.org/10.1007/978-3-032-23657-9_12),
[the code](https://github.com/TaylorNeller/TUBSTAP_Taylor)). Their references are in
[`docs/ALGORITHMS.md`](docs/ALGORITHMS.md#references).

## License

MAGAT is released under the Apache License 2.0; see [`LICENSE`](LICENSE). The Tribes engine
submodule is not covered by that licence (see Credits).
