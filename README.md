# MAGAT — Multi-Action Game Algorithm Tester

A framework for evaluating search algorithms in **multi-action games**: games where a turn is a
sequence of actions, so the branching factor per turn is combinatorial and evaluating an algorithm
by playing full games is slow, noisy, and incomparable across papers.

MAGAT replaces "play N games, multithreaded, on whatever hardware" with:

1. **A pinned portfolio of captured positions** per game, stratified and split into a development
   half and a holdout half that is read once.
2. **A calibrated compute-unit currency**, so a budget means the same forward-model work on any
   machine, with wall-clock reported beside it as an honesty check.
3. **A paired, scenario-clustered statistical design**: every algorithm replans the same positions
   with the same seeds, so effects are paired differences with honest confidence intervals, and
   effect sizes are reported as **budget multipliers**, which compare across games and papers.
4. **Attribution controls as a first-class pattern**: a mechanism is credited only when the same
   algorithm with the mechanism replaced by noise does worse.

The methodology is the product; games are the substrate it runs on. Every published result carries
its raw operation counts, so anyone can re-price it with their own machine's calibrated weights.

## Status

**v0.1 in progress.** The design is [`MAGAT/DESIGN.md`](MAGAT/DESIGN.md); the brief it implements
is [`MAGAT/BRIEF.md`](MAGAT/BRIEF.md). Milestone 1 is complete: the `Game` contract, the meter, the
Tribes adapter, and plain EMCTS ported onto the contract and gated bit-identical against the
thesis-tree implementation (including the archived verify signature computed on the reference
machine). Milestone 2 adds the portfolio: a parallel, restart-safe builder whose output does not
depend on the machine or the thread count, the frozen development/holdout split, the holdout ledger,
and `portfolio verify`, which checks a rebuild against the published portfolio row by row. Milestone 3
adds calibration: per-(arm, budget) weights with the thesis's fastest-repetition estimator and gates,
merging new arms into an existing currency (by cost ratio, so a faster or slower machine cancels), the
drift check against the published R7615 currency, the loaded-machine diagnostic, the thread sweep and
the machine profile. Milestone 4 adds the runner: experiments are JSON specs, validated before
anything runs (a parameter sweep without its null is rejected), run leaf by leaf with resume by
re-running the same command, and guarded by a currency lock, a portfolio and configuration lock, and an
assertion that no two arms produce identical output. The holdout is entered in a ledger when it is
read. Milestone 5 completes the arm family: the 31 remaining arms of the worked result
(the AB-EMCTS line), each gated against the thesis implementation with its mechanism counters, plus
`check structure`, `probe support`, `check overhead`, and `check replay`, which re-plans published
R7615 holdout turns on your machine and reproduces them field for field. Milestone 6 adds the
analyzer: `magat analyze` turns a run tree into the brief's measures. That means the paired difference
with scenario-clustered intervals, the equivalent budget multiplier and its wall-clock twin, W7, the
search-gain multiple, oracle regret, Cohen's d, attribution contrasts from the spec, and development
and holdout side by side. It can also re-price a run in another machine's currency. `magat
import-legacy` brings the thesis's run trees into MAGAT's layout. The first game is
[Tribes](https://github.com/GAIGResearch/Tribes). The worked result is the thesis's AB-EMCTS line,
imported in [`games/tribes/results/ab-emcts/`](games/tribes/results/ab-emcts/) and re-analysed by
MAGAT. It reproduces the thesis retrospective's numbers to the decimal; see
[`docs/results/tribes-ab-emcts.md`](docs/results/tribes-ab-emcts.md).

## Quick start

Requirements: JDK 11 or newer, Python 3.10+ (pandas and numpy for the analysis), git with submodules.

```bash
git clone --recurse-submodules https://github.com/mlhcorapi/Multi-Action-Game-Algorithm-Tester.git
cd Multi-Action-Game-Algorithm-Tester
python magat.py build --tests
python magat.py test
python magat.py check contract    --game tribes
python magat.py check equivalence --game tribes --arms emcts --scenarios 8 --visits 2 --budgets 8000,40000
python magat.py portfolio verify  --game tribes
python magat.py weights --game tribes --arms @worked-result
python magat.py experiment validate --experiment experiments/tribes/reference-ladder.json
python magat.py check replay --game tribes --leaf games/tribes/results/replay/Comma_Off/80000cu --turns 20
python magat.py analyze --run games/tribes/results/ab-emcts --out ~/magat_runs/tribes/results/ab-emcts
python magat.py shakedown
```

To rebuild the published Tribes portfolio on your own machine and check it is the same one (5 minutes
on the 64-core reference machine, about 15 on a 24-core laptop; see
[`games/tribes/portfolio/v1/REBUILDS.md`](games/tribes/portfolio/v1/REBUILDS.md)):

```bash
python magat.py portfolio build  --game tribes --out ~/magat_runs/portfolio/tribes --threads 20
python magat.py portfolio freeze --portfolio ~/magat_runs/portfolio/tribes
python magat.py portfolio verify --game tribes --portfolio ~/magat_runs/portfolio/tribes --published games/tribes/portfolio/v1 --plan 4
```

To see whether your machine spends the published currency (idle machine, about half an hour), and to
price a new arm into it:

```bash
python magat.py calibrate --check --game tribes --arms emcts,emcts-comma
python magat.py calibrate --game tribes --out <dir with a copy of compute_weights.properties> --arms <new-arm>
```

`python magat.py` with no arguments lists every command (`bin/magat` and `magat.cmd` wrap it). Run
output lives outside the clone, under `~/magat_runs` (override with `MAGAT_DATA`); build output can
be moved out of a synced folder with `MAGAT_BUILD`.

## Layout

| Path | What |
|---|---|
| `core/` | the Java core (package root `magat`): `meter`, `game` (the contract), `agent`, `agents` (the reference algorithms), `portfolio`, `probe`, `cli` |
| `games/tribes/` | the Tribes adapter, the published portfolio and calibration, the worked result |
| `games/tribes/engine` | the Tribes engine, a git submodule of a fork of GAIGResearch/Tribes |
| `analysis/` | the Python analysis package, `magat_analysis` (`magat analyze`, `magat import-legacy`), and its tests |
| `experiments/` | experiment specs: the reference ladder, the worked result's development and holdout stages, the shakedown |
| `docs/results/` | published results, set beside their sources |
| `notebooks/run_magat.ipynb` | the run notebook, generated by `tools/make_notebook.py` |
| `tools/` | shakedown, notebook generator and self-test, layering check, importers |
| `MAGAT/` | the founding documents: brief, starting prompt, design |
| `Tribes-master/` | the thesis tree MAGAT is extracted from; removed when the extraction is complete |

## Credits

Tribes is by Diego Perez-Liebana, Yu-Jhen Hsu, Stavros Emmanouilidis, Bobby Khaleque and Raluca
Gaina: *Tribes: A New Turn-Based Strategy Game for AI*, AAAI Conference on Artificial Intelligence
and Interactive Digital Entertainment (AIIDE), 2020. The engine MAGAT runs is a fork of their
repository with the engine changes the thesis line required; the upstream repository carries no
licence file, and the fork is used with attribution while permission is sought.

## License

MAGAT is released under the Apache License 2.0; see [`LICENSE`](LICENSE). The Tribes engine
submodule is not covered by that licence (see Credits).
