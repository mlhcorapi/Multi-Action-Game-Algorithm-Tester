# Origin of the r7615-ab-emcts currency

The AB-EMCTS experiment's currency ([`docs/results/ab-emcts.md`](../../../../docs/results/ab-emcts.md)): the
published [`r7615-time`](../r7615-time/ORIGIN.md), unchanged, with the three AB-EMCTS arms priced into it on
the same machine. It prices `emcts`, `mcts`, `oep` and `tbets` exactly as `r7615-time` does, every one of
their lines kept, and adds `emcts-comma`, `comma-z0-coarse` and `comma-z0-random` at the published levels
9,500, 20,000 and 101,500: 21 profiles in all. The unit is `r7615-time`'s, one advance of `emcts@9500`.

It was made on 2026-11-27 by `magat calibrate-controlled --config calibrations/tribes/ab-emcts.json`, run
from the notebook. The config starts the currency as a byte copy of `r7615-time` (`"from"`), and one session
priced the three arms into it, merged by ratio.

| | |
|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534, through JupyterHub (cgroup v2, CPUs 0-127, no CPU limit) |
| Java | OpenJDK 11.0.29 |
| Commit | `82f991a` (main after PR #45), "notebook modified": the notebook the cells ran from |
| Engine | `828b130` |
| Session | 0.76 h, finished 2026-11-27 17:20 CET; measuring JVMs on CPUs 16-23 and 80-87, the quietest L3 domain |
| Reference cell | this session's `emcts@9500` advance 55,596.5 ns, ×1.0046 the file's; re-denominated ×1.000000 |
| Clock policy | `schedutil`, as for `r7615-time`; busiest core 1,480-3,700 MHz (median 3,700), up to 51 °C |
| Other work | 0.1 logical CPUs on average, 0.0 at worst |
| `compute_weights.properties` sha256 | `b03a8d8fe82737da80c61160aa51e9ffe7b747b5592f80e75e82d7f809b9c5e7` |

## Verdicts

From `record/SUMMARY.md`:

- **Session:** stability check PASS (bracket −0.4% / −0.6% / −1.7% for advance / copy / eval, speed ×1.005
  the file's), verdict COMPLETE.
- **Check against the published currency:** PASS, the largest turn-price drift −0.4% (`emcts@20000`).
- **Self-check:** PASS, the largest drift −0.7% (`emcts@20000`).
- **Audit:** the share of each new cell's turn time no priced operation accounts for (`unpriced`) is at most
  0.12% (at 9,500), against the experiment's 25% gate.

## Files

- `compute_weights.properties`: the currency. Every line of `r7615-time` is in it, but the `operations`
  line, which the merge extended; the new profiles follow its `# merged` line.
- `compute_profiles.csv`, `compute_operations.csv`: the session's measurements.
- `threads.json`, `thread_sweep.csv`: the notebook's thread sweep on the day.
- `record/SUMMARY.md`, `record/policy.json`: the session's verdict and the clock policy it ran under. The
  session's logs stayed on the server.
