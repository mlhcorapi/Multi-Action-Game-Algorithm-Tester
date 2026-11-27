# MAGAT's documentation

The [README](../README.md) gets you from a clone to a first experiment. These documents take it from
there, by what you want to do.

## Running experiments

| Read | For |
|---|---|
| [`EXPERIMENTS.md`](EXPERIMENTS.md) | configuring an experiment (its arms, levels, repetitions, positions and currency), running it in one command, and reusing the published run |
| [`CALIBRATING_A_MACHINE.md`](CALIBRATING_A_MACHINE.md) | measuring a laptop's or a shared server's currency in one session, and a calibration config file |
| [`SHARING.md`](SHARING.md) | exporting a result as one file, importing and checking someone else's, and building on it |
| [`OPERATIONS.md`](OPERATIONS.md) | the launcher, where output goes, the notebook, runs and their guards, the gates |

## Extending MAGAT

| Read | For |
|---|---|
| [`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md) | writing, declaring, pricing and gating an algorithm, and comparing it with the reference four |
| [`ADDING_A_GAME.md`](ADDING_A_GAME.md) | writing a game adapter, capturing a portfolio, publishing a currency |
| [`CONTRACT.md`](CONTRACT.md) | the `Game` interface and its laws, and the agent protocol |

## Understanding it

| Read | For |
|---|---|
| [`DESIGN.md`](DESIGN.md) | the design on one page |
| [`METHODS.md`](METHODS.md) | the portfolio, the split, the paired design, the measures |
| [`CURRENCY.md`](CURRENCY.md) | what a compute unit is, how a machine prices one, what transfers between machines |
| [`ALGORITHMS.md`](ALGORITHMS.md) | the four reference algorithms and the floor, and their references |

## Results

| Read | For |
|---|---|
| [`results/cross-machine.md`](results/cross-machine.md) | the reference experiment on two machines, compared position by position |
| [`results/ab-emcts.md`](results/ab-emcts.md) | the pre-registered AB-EMCTS experiment: AB-EMCTS against OEP, TBETS and its own baseline and control, decided on the holdout half |
| [`../paper/README.md`](../paper/README.md) | the paper's tables and figures, and how they are rebuilt from the published runs |

## Plans

| Read | For |
|---|---|
| [`plans/2.0-compute-unit.md`](plans/2.0-compute-unit.md) | finding the best basket of operations for 2.0's compute unit, including one independent of any decision-making algorithm |
| [`plans/ab-emcts-vs-oep.md`](plans/ab-emcts-vs-oep.md) | the pre-registration of AB-EMCTS against the reference algorithms, under MAGAT 1.0's rules |
| [`plans/ab-emcts-run.md`](plans/ab-emcts-run.md) | the run sheet for that experiment on the R7615: the notebook's parameters, what to read at each step, what to bring back |

## History

| Read | For |
|---|---|
| [`TRAPS.md`](TRAPS.md) | each trap that cost time, and what now prevents it |
| [`ARCHIVE.md`](ARCHIVE.md) | what v0.1 set aside, where it lives, and how to re-add it |
