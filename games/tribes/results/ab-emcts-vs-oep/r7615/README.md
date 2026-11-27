# The AB-EMCTS experiment on the R7615

[`experiments/tribes/ab-emcts.json`](../../../../../experiments/tribes/ab-emcts.json), pre-registered in
[`docs/plans/ab-emcts-vs-oep.md`](../../../../../docs/plans/ab-emcts-vs-oep.md) and run by
[`docs/plans/ab-emcts-run.md`](../../../../../docs/plans/ab-emcts-run.md), on the reference machine, priced
in [`r7615-ab-emcts`](../../../calibration/r7615-ab-emcts/ORIGIN.md). The result and its reading are in
[`docs/results/ab-emcts.md`](../../../../../docs/results/ab-emcts.md).

| | |
|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534, through JupyterHub; 54 workers at every level |
| Date | 2026-11-27: the holdout half 16:23-16:47 UTC, the development half 16:47-17:11 UTC |
| Commit | `82f991a`, which every leaf records |
| Engine | `828b130` |
| Portfolio | `ec243fd7...a7` (the published `v1`) |
| Currency | `r7615-ab-emcts`, sha256 `b03a8d8f...e7` |
| Experiment | `ab-emcts`, sha256 `20190ce0...bb`, the committed file unchanged |
| Size | 8 arms × 3 levels on each half: 48 leaves, 70,400 turns; every leaf played, none reused |

## Files

- `holdout/`, `dev/`: the two run trees, `<arm>/<budget>cu/turns.csv` with each leaf's `_leaf.json`. The
  per-turn anytime curves (`curve.csv`) are left out, as in the published reference run; they are in the
  package the run was exported as.
- `report/`: the analysis as the run produced it, against the baseline `emcts`: `SUMMARY.md`, its tables
  and figures. `python magat.py analyze --run games/tribes/results/ab-emcts-vs-oep/r7615 --experiment
  experiments/tribes/ab-emcts.json --out <dir>` rebuilds its tables byte for byte.
- `decision.csv`: the pre-registered decision, hypothesis by hypothesis, level by level, on both halves:
  `comma-z0-coarse` against each contrast's other arm, with the interval, the wall-clock re-score and the
  budget multiplier, from the same analysis run with that arm as the baseline.
- `reproduction.csv`: `check reproduction` of both halves against the published reference run: the five
  reference arms' 44,000 turns, identical in every field but the times.

Every turn re-plans here: `python magat.py check replay --game tribes --leaf <leaf> --weights
games/tribes/calibration/r7615-ab-emcts/compute_weights.properties`.
