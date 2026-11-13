# The reference experiment on a laptop

`experiments/tribes/reference.json` run on a second machine, an HP OMEN 16 laptop, priced in that machine's
own currency, [`laptop-i9-14900hx-time`](../../../calibration/laptop-i9-14900hx-time/ORIGIN.md). The same 400
positions, levels, visits and seeds as [the reference machine's run](../r7615/README.md): 44,000 turns in 15
leaves, against the baseline `emcts`. How the two compare is in
[`docs/results/cross-machine.md`](../../../../../docs/results/cross-machine.md).

| | |
|---|---|
| Machine | HP OMEN 16, Intel Core i9-14900HX (8 performance and 16 efficient cores), Windows 11, Java 26; 15 workers at every level |
| Date | 2026-11-12, one invocation from a terminal, 96 minutes of turns |
| Commit | `c3afdae`, which every leaf records |
| Engine | `828b130` |
| Portfolio | the published `v1`, `ec243fd7...a7` |
| Currency | `laptop-i9-14900hx-time`, sha256 `1e3d9ab0...af` |
| Experiment | `reference`, sha256 `29ca9e81...62` |

## The result

The paired difference against `emcts` on the pairs where neither plan ends the game, with its 95% interval,
and how much budget `emcts` would need to match the arm (`≥`: a lower bound past the top of the ladder).

| Level | `oep` | `tbets` | `mcts` | `random` |
|---|---|---|---|---|
| 9,500 | +55 [+25, +84], 2.0× | +48 [+17, +77], 1.8× | −149 [−180, −120], 0.2× | −601 |
| 20,000 | +149 [+119, +179], ≥5.9× | +75 [+45, +103], 2.4× | −180 [−212, −149], 0.1× | −661 |
| 101,500 | +144 [+114, +172], ≥5.6× | +108 [+78, +137], ≥3.7× | −255 [−292, −220], <0.1× | −793 |

At every level: `oep` > `tbets` > `emcts` > `mcts` > `random`, every interval clear of zero, as on the R7615.
Every turn spent 1.00× its budget. The turns took 1.18× (`tbets`) to 1.71× (`mcts` at 101,500) the time their
priced work predicts one at a time: 15 turns at once on a hybrid CPU, some of them on its efficient cores.
`report/SUMMARY.md` has the whole analysis.

## What is here

- `all/`: the run tree as the runner wrote it (`_run.json`, `_completed.txt`, and per leaf `_leaf.json` and
  `turns.csv`). Left out: `emcts`'s anytime curves (`curve.csv`).
- `report/`: `magat analyze` of the tree.
