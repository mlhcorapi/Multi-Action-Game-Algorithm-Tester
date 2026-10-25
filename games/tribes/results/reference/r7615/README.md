# The reference experiment on the R7615

`experiments/tribes/reference.json` run on the reference machine, priced in Tribes' published currency
[`r7615-time`](../../../calibration/r7615-time/ORIGIN.md). The four reference algorithms and the random
floor replanned single turns on all 400 positions of the published portfolio at the published levels:
9,500, 20,000 and 101,500 units, with 8, 8 and 6 visits per position. That is 44,000 turns in 15 leaves,
against the baseline `emcts`.

| | |
|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534, through JupyterHub; 62 workers at every level |
| Date | 2026-10-25, one invocation, 26 minutes of turns |
| Commit | `d5e1f98`, which every leaf records (the notebook saved with its outputs) |
| Engine | `828b130` |
| Portfolio | `ec243fd7...a7` (the published `v1`, rebuilt on the server and verified) |
| Currency | `r7615-time`, sha256 `35db011f...d6` |
| Experiment | `reference`, sha256 `29ca9e81...62` |

## The result

The paired difference against `emcts` on the pairs where neither plan ends the game, with its
scenario-clustered 95% interval, and how much budget `emcts` would need to match the arm. A `≥` marks a
crossing past the top of the ladder, a lower bound.

| Level | `oep` | `tbets` | `mcts` | `random` |
|---|---|---|---|---|
| 9,500 | +71 [+42, +100], 2.4× | +58 [+29, +87], 2.1× | −147 [−178, −119], 0.1× | −601 |
| 20,000 | +163 [+133, +194], ≥6.8× | +86 [+57, +115], 2.7× | −177 [−209, −146], 0.1× | −658 |
| 101,500 | +146 [+117, +174], ≥5.8× | +115 [+86, +144], ≥4.0× | −255 [−290, −221], <0.1× | −793 |

At every level: `oep` > `tbets` > `emcts` > `mcts` > `random`, every interval clear of zero.
`report/SUMMARY.md` has the whole analysis: the multipliers, the contrasts, spend, time and plans, and the
design. Read it beside these notes:

- **Every turn spent its level:** 1.00× its budget on average for every arm and level. `random` is not
  priced and spends almost nothing.
- **The seconds are 62 turns at a time.** Turns running together share the machine's caches, memory and
  clock. The turns took 1.09× (`tbets`) to 1.68× (`mcts` at 101,500) the time their priced work predicts
  one at a time, and `emcts` took 0.64 s, 1.32 s and 6.90 s against its levels' 0.5, 1 and 5 s. That
  changes no turn: 520 of them, replayed on a laptop, reproduced field for field. It does weigh on the
  wall-clock columns, most for `mcts`.
- **`mcts` plays short plans:** 3.8, 4.2 and 5.1 actions, where the others play 16 to 18
  ([`ALGORITHMS.md`](../../../../../docs/ALGORITHMS.md)).
- **Six two-player positions can be won this turn** (scenarios 204, 329, 332, 341, 390 and 394). A
  winning plan scores about 2×10⁹, so the headline leaves those pairs out and counts them: `tbets`
  missed 2 of 48 wins at 9,500, `mcts` 1 of 36 at 101,500, and `random` found 5 in all.

## Using it

Compare your machine with the reference machine:
1. Calibrate your machine ([`CALIBRATING_A_MACHINE.md`](../../../../../docs/CALIBRATING_A_MACHINE.md)).
2. Run the same experiment under your currency.
3. Compare the two runs:

```bash
python magat.py analyze --run $HOME/magat_runs/tribes/runs/reference --compare games/tribes/results/reference/r7615 --out $HOME/magat_runs/tribes/results/reference-vs-r7615
```

Check that your machine plays the reference machine's turns, field for field:

```bash
python magat.py check replay --game tribes --leaf games/tribes/results/reference/r7615/all/emcts/9500cu --turns 20
```

Analyse it again:

```bash
python magat.py analyze --run games/tribes/results/reference/r7615 --out $HOME/magat_runs/tribes/results/reference-r7615
```

## What is here

- `all/`: the run tree as the runner wrote it, with `_run.json` (its provenance), `_completed.txt`, and
  per leaf `_leaf.json` (prices, threads, commit, timing, the run's audit) and `turns.csv` (one row per
  turn). Left out: `emcts`'s anytime curves (`curve.csv`, 7.9 MB), which nothing here reads.
- `report/`: `magat analyze` of the tree: `SUMMARY.md`, `tables/` and `report.json`.
