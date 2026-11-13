# Origin of the laptop-i9-14900hx-time currency

A second machine's Tribes currency, measured to show that MAGAT's results transfer between machines
([`docs/results/cross-machine.md`](../../../../docs/results/cross-machine.md)). It prices `emcts`,
`mcts`, `oep` and `tbets` at the published levels **9,500 / 20,000 / 101,500 units**: 12 profiles. One
unit is one advance of the reference cell `emcts@9500`, 45,827.788 ns on this machine, against 55,340.764
ns on the reference machine ([`r7615-time`](../r7615-time/ORIGIN.md)). Each turn's initialization is paid
from its budget.

The published currency stays the R7615's: commands use this one only when it is named with `--weights`.
It was measured on 2026-11-12 by `magat calibrate-controlled`, from a terminal, in one session.

| | |
|---|---|
| Machine | HP OMEN 16 (16-am0xxx), Intel Core i9-14900HX: 8 performance and 16 efficient cores, 32 logical CPUs |
| OS and Java | Windows 11; Oracle Java 26.0.2.1, `-Xmx4g -XX:+UseG1GC`, compiled for Java 11 |
| Commit | `c3afdae` (main after PR #27), clean |
| Engine | `828b130` |
| Portfolio | the published `v1`, identity `ec243fd7...a7` |
| Levels | 9,500, 20,000, 101,500; 16 turns per repetition at each, 3 repetitions, 6 warm-up turns |
| Profiles | 4 arms × 3 levels = 12 |
| `compute_weights.properties` sha256 | `1e3d9ab0c6ddc398d92125e717cdee30bbafa2b1b8c6aa89a300dbd5eb8dc1af` |

## Conditions

- **Power:** on mains, battery full, kept awake, offline (airplane mode), restarted 11 minutes before the
  session's preflight, with the apps worth closing closed.
- **Clock policy:** the "MAGAT calibration" power plan: performance and efficient cores capped at
  4,000 MHz, boost aggressive, energy preference 0; Windows power mode "best performance". The maker's
  own performance mode (OMEN Gaming Hub) is not readable by the preflight. The busiest measuring core
  read 4,157-4,249 MHz (median 4,221; Windows reads a capped clock high), up to 43 °C.
- **Measuring JVMs:** on CPUs 0-15, the performance cores, at high priority with power throttling off.
- **Other work:** 0.1 logical CPUs during the session. One period of at least one CPU of other work fell
  during the stability check, before the session.
- **Stability check:** seven estimates over 11 minutes until two in a row agreed within 3% (0.46 s,
  0.92 s and 4.50 s per `emcts` turn at the three levels).

## The session

| Arms | Verdict | Minutes | Bracket (advance / copy / eval) | Spend |
|---|---|---|---|---|
| `emcts` (the reference), `mcts`, `oep`, `tbets` | PASS | 29.3 | -0.3 / +0.0 / -0.1% | every cell 1.00× its level |

The calibration's audit found every cell's priced operations accounting for at least 99.8% of its turn
time.

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket |
|---|---|---|---|
| Against the published currency, `r7615-time` | PASS | -0.4% (`emcts@101500`) | +0.5 / +0.8 / -0.5% |
| Against this currency (self) | PASS | +0.2% (`emcts@20000`) | -0.2 / -0.2 / -0.0% |

Against the R7615's, this machine's reference advance took ×0.823 the time: it is about 1.2× faster per
unit, and its `emcts` turns cost what the R7615's prices say within 0.4%.

## What is here

- `compute_weights.properties`: the currency. `compute_profiles.csv` and `compute_operations.csv`: the
  session's measurements, per cell and per operation.
- `record/`: the session's summary (`SUMMARY.md`), its clock policy (`policy.json`), two preflights
  (`20261112-192535`, before the restart, and `20261112-193631`), and the session with its checks
  (`20261112-193913`). Left out: the raw processor counters (`counters.csv`, 476 KB), which `monitor.csv`
  summarises.
