# MAGAT — Multi-Action Game Algorithm Tester

A framework for evaluating search algorithms in **multi-action games**. In these games a turn is a
sequence of actions, so the branching factor per turn is combinatorial. Evaluating an algorithm by
playing full games is slow, noisy, and incomparable across papers.

MAGAT replaces "play N games, multithreaded, on whatever hardware" with:

1. **A pinned portfolio of captured positions** per game, stratified and split into a development
   half and a holdout half that is read once.
2. **A calibrated compute-unit currency**, so a budget means the same work on any machine, with
   wall-clock reported beside it as an honesty check.
3. **A paired, scenario-clustered statistical design.** Every algorithm replans the same positions with
   the same seeds, so effects are paired differences with honest confidence intervals. Effect sizes are
   reported as **budget multipliers**, which compare across games and papers.
4. **Attribution controls as a first-class pattern.** A mechanism is credited only when the same
   algorithm, with the mechanism replaced by noise, does worse.

The methodology is the product; games are the substrate it runs on. Every published result carries
its raw operation counts, so anyone can re-price it with their own machine's calibrated weights.

## Status

**v0.1.**
- **The game.** The first game is [Tribes](https://github.com/GAIGResearch/Tribes), with:
  - a published portfolio of 400 positions;
  - a published currency, measured on a Dell PowerEdge R7615;
  - 33 algorithm arms, each gated field for field against the implementation it was ported from.
- **The worked result** is the thesis's AB-EMCTS line, re-analysed by MAGAT: it reproduces the thesis
  retrospective's numbers to the printed decimal ([`docs/results/tribes-ab-emcts.md`](docs/results/tribes-ab-emcts.md)).
- **The design.** [`MAGAT/DESIGN.md`](MAGAT/DESIGN.md) is the design, with a dated amendment for every
  departure. The brief it implements is [`MAGAT/BRIEF.md`](MAGAT/BRIEF.md).

## Quick start

You need JDK 11 or newer, Python 3.10 or newer (numpy and pandas for the analysis), and git.

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

The shakedown runs every moving part at toy size, in about ten minutes, and exits non-zero on any
failure. `python magat.py` with no arguments lists every command.

Run output goes outside the clone, under `$HOME/magat_runs` (set `MAGAT_DATA` to move it).

## The afternoon path: add an algorithm and compare it

These steps take a new algorithm from code to a report, on a toy portfolio, in a few minutes. Every
command works the same in bash and in PowerShell. The full-size path is the same commands without the
toy flags, and the guides below cover each step in depth.

**1. Build a toy portfolio, and check it reproduces the published one.**

```bash
python magat.py portfolio build --game tribes --out $HOME/magat_runs/portfolio/tribes-toy --targets single=2,dual=1 --threads 2
```

```bash
python magat.py portfolio freeze --portfolio $HOME/magat_runs/portfolio/tribes-toy
```

```bash
python magat.py portfolio verify --game tribes --portfolio $HOME/magat_runs/portfolio/tribes-toy --published games/tribes/portfolio/v1 --subset --plan 3
```

`VERIFY: PASS` means your machine captured the same positions the reference machine did, row for row,
and plans identically from both.

**2. Replay published turns on your machine.**

```bash
python magat.py check replay --game tribes --leaf games/tribes/results/replay/Comma_Off/80000cu --turns 20
```

`REPLAY: PASS` means 20 published holdout turns re-planned here agree with the published rows, field
for field. That is what makes a comparison with published results paired rather than hopeful.

**3. Register an algorithm.** `core/src/magat/agents/first/FirstAgent.java` is a complete example:
random restarts, the smallest search that spends a budget. Register it by adding one line to
`TribesAgents.registry()` in `games/tribes/src/magat/games/tribes/TribesAgents.java`:

```java
r.register(magat.agents.first.FirstAgent.factory());
```

Then list the arms:

```bash
python magat.py arms --game tribes
```

`first` is listed. Check that the operations it declares account for its time:

```bash
python magat.py check overhead --audit --game tribes --arms first --budget 4000 --turns 2 --reps 1 --warmup 1
```

**4. Price it.** This is a toy calibration of the new arm and the baseline, on the toy portfolio:

```bash
python magat.py calibrate --game tribes --portfolio $HOME/magat_runs/portfolio/tribes-toy --out $HOME/magat_runs/calibration/tribes-toy --arms emcts-comma,first --levels 2000,4000 --turns 2 --reps 2 --warmup 1 --toy
```

**5. Run it against the baseline.** Save this spec as `first-toy.json`:

```json
{
  "name": "first-toy",
  "game": "tribes",
  "split": "dev",
  "baseline": "emcts-comma",
  "levels": [ { "budget": 2000, "visits": 1 }, { "budget": 4000, "visits": 1 } ],
  "arms": [ { "id": "emcts-comma" }, { "id": "first" } ],
  "contrasts": [ { "a": "first", "b": "emcts-comma", "label": "random restarts against the baseline" } ]
}
```

```bash
python magat.py experiment validate --experiment first-toy.json --weights $HOME/magat_runs/calibration/tribes-toy/compute_weights.properties --toy
```

```bash
python magat.py run --experiment first-toy.json --split dev --portfolio $HOME/magat_runs/portfolio/tribes-toy --weights $HOME/magat_runs/calibration/tribes-toy/compute_weights.properties --out $HOME/magat_runs/tribes/runs/first-toy --threads 2 --toy
```

**6. Analyse it.**

```bash
python magat.py analyze --run $HOME/magat_runs/tribes/runs/first-toy/dev --out $HOME/magat_runs/tribes/results/first-toy
```

`SUMMARY.md` in the output gives:
- the provenance;
- the paired difference against the baseline in units and in seconds;
- the budget multiplier and the search-gain multiple;
- every other measure.

At toy size the numbers mean nothing; the path is the point.

**For real.** Do the same steps at full size:
1. Use the published portfolio, which is the default.
2. Calibrate your machine with `calibrate-controlled`
   ([`docs/CALIBRATING_A_MACHINE.md`](docs/CALIBRATING_A_MACHINE.md)).
3. Run a spec at the published ladder on the development half.
4. Read the holdout once, with your choices frozen first.

## Documentation

| Read | For |
|---|---|
| [`docs/METHODS.md`](docs/METHODS.md) | the method: portfolio, split, paired design, holdout, nulls and controls, the measures |
| [`docs/CURRENCY.md`](docs/CURRENCY.md) | what a compute unit is, how a machine prices one, what transfers between machines |
| [`docs/CONTRACT.md`](docs/CONTRACT.md) | the `Game` interface and its laws, and the agent protocol |
| [`docs/ADDING_AN_ALGORITHM.md`](docs/ADDING_AN_ALGORITHM.md) | writing, registering, pricing and gating an algorithm; declaring its operations |
| [`docs/ADDING_A_GAME.md`](docs/ADDING_A_GAME.md) | writing a game adapter, capturing a portfolio, publishing a currency |
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | the launcher, where output goes, the notebook, runs and their guards, the gates |
| [`docs/CALIBRATING_A_MACHINE.md`](docs/CALIBRATING_A_MACHINE.md) | calibrating a laptop or a shared server, in sections, with reliability checks |
| [`docs/TRAPS.md`](docs/TRAPS.md) | every trap that cost time, and what now prevents it |
| [`docs/results/tribes-ab-emcts.md`](docs/results/tribes-ab-emcts.md) | the worked result |

On a server, a run is the notebook [`notebooks/run_magat.ipynb`](notebooks/run_magat.ipynb): one cell
per stage, each skipping itself once its output exists, and each stage ending in a bundle to download.

## Layout

| Path | What |
|---|---|
| `core/` | the Java core (package root `magat`): the meter, the game contract, agents and the reference algorithms, portfolio, calibration, the runner, probes, the CLI |
| `games/tribes/` | the Tribes adapter, its published portfolio, currency and worked result |
| `games/tribes/engine` | the Tribes engine: a git submodule of a fork of GAIGResearch/Tribes |
| `analysis/` | the Python analysis package, `magat_analysis`, and its tests |
| `experiments/` | experiment specs: the reference ladder, the worked result's stages, the shakedown |
| `docs/` | the documentation, and published results beside their sources |
| `notebooks/run_magat.ipynb` | the run notebook, generated by `tools/make_notebook.py` |
| `tools/` | the shakedown, `calibrate-controlled`, the notebook generator and self-test, the layering check, importers |
| `MAGAT/` | the founding documents: brief, starting prompt, design |

## Credits

Tribes is by Diego Perez-Liebana, Yu-Jhen Hsu, Stavros Emmanouilidis, Bobby Khaleque and Raluca
Gaina: *Tribes: A New Turn-Based Strategy Game for AI*, AAAI Conference on Artificial Intelligence
and Interactive Digital Entertainment (AIIDE), 2020. The engine MAGAT runs is a fork of their
repository with the engine changes the thesis line required and MAGAT's own (data files found from any
directory; faster legal-action generation with identical results). The upstream repository carries no
licence file; the authors have given permission for MAGAT's use of their code. The fork is used as a
submodule with attribution, and none of it is copied into this repository.

## License

MAGAT is released under the Apache License 2.0; see [`LICENSE`](LICENSE). The Tribes engine
submodule is not covered by that licence (see Credits).
