# Origin of the r7615-time currency

Tribes' published currency: the reference machine's prices at the published levels **9,500 / 20,000 /
101,500 units** (500, 1000 and 5000 ms of a whole `emcts` turn on this machine,
[`levels.md`](../../levels.md)). It prices the four reference algorithms, `emcts`, `mcts`, `oep` and
`tbets`, at each level: 12 profiles. One unit is one advance of the reference cell `emcts@9500`,
55,340.764 ns on this machine. Each turn's initialization is paid from its budget.

It was measured on 2026-10-25 by `magat calibrate-controlled`, run from the notebook, in one session on a
fresh data root. The launcher points every command that spends a currency at it when `--weights` is not
given.

| | |
|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534 (64 cores, 128 logical CPUs, 1 NUMA node, 8 L3 domains), through JupyterHub: a cgroup v2 container with no CPU or memory limit |
| Java | OpenJDK 11.0.29 (Ubuntu), `-Xmx4g -XX:+UseG1GC` |
| Commit | `d5e1f98` (main after PR #26), with "notebook modified": the notebook the cells ran from, saved with its outputs |
| Engine | `828b130` |
| Portfolio | the server's own rebuild from scratch (400 positions in 215 s), identity `ec243fd7...a7`, `VERIFY: PASS` against `games/tribes/portfolio/v1` |
| Levels | 9,500, 20,000, 101,500; 16 turns per repetition at each, 3 repetitions, 6 warm-up turns |
| Profiles | 4 arms × 3 levels = 12 |
| `compute_weights.properties` sha256 | `35db011f70cf44c29a08943f5e7d79d57147731bf8df3e84ad81b43c049ab9d6` |

## Conditions

- **Clock policy:** the Linux `schedutil` frequency governor. It cannot be changed from the container,
  and neither can the maker's performance mode (Dell's BIOS system profile), which stayed as the server's
  administrators set it. The busiest measuring core ran at 1,480-3,700 MHz (median 3,700), up to 49 °C.
- **Measuring JVMs:** on CPUs 8-15 and 72-79, the quietest of the 8 L3 domains when the session began.
- **Other work:** 0.1 logical CPUs on average, 0.2 at worst; never above the 4 the session allows.
- **Stability check:** two estimates of the reference turn 0.9% apart (0.57 s, 1.12 s and 5.46 s per
  `emcts` turn at the three levels).
- **Uptime:** 5.5 days.

## The session

| Arms | Verdict | Minutes | Bracket (advance / copy / eval) | Spend |
|---|---|---|---|---|
| `emcts` (the reference), `mcts`, `oep`, `tbets` | PASS | 34.1 | -0.1 / -0.0 / -1.7% | every cell 1.00× its level |

The bracket is the reference cell measured again at the end of the session against its start: the
machine held its speed. The calibration's audit found every cell's priced operations accounting for at
least 99.9% of its turn time, one turn at a time.

The advance's price by arm and level, as a ratio to the reference advance, shows why each arm and
level is priced on its own:

| | 9,500 | 20,000 | 101,500 |
|---|---|---|---|
| `emcts` | 1.000 | 0.979 | 0.948 |
| `mcts` | 1.433 | 1.434 | 1.420 |
| `oep` | 0.979 | 0.929 | 0.903 |
| `tbets` | 1.008 | 0.996 | 1.003 |

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket |
|---|---|---|---|
| Against this currency (self) | PASS | -0.6% (`emcts@101500`) | -0.5 / +0.1 / +0.1% |
| Against the published currency | none | this is the first published currency at these levels | |

The `r7615-time` on the `ab-emcts` branch is another currency: the pre-v0.1 one, measured on 2026-10-11
for the 33 arms v0.1 set aside.

## The gates the same afternoon

All at `d5e1f98`, all PASS:
- **Contract:** passed.
- **Equivalence:** `emcts` and `tbets`, 32 cells each, 0 failed.
- **Audit:** 0.1% or less of every arm's turn unaccounted for.
- **Unit tests:** 111 MAGAT tests, the engine's 5, and 25 analysis tests.

The thread sweep chose 62 workers at every level, where each worker ran at 75%, 78% and 72% of its
speed with 8 (`thread_sweep.csv`). The reference experiment priced in this currency, and its analysis,
are in [`games/tribes/results/reference/r7615/`](../../results/reference/r7615/).

## What is here

- `compute_weights.properties`: the currency. `compute_profiles.csv` and `compute_operations.csv`: the
  session's measurements, per cell and per operation.
- `threads.json` and `thread_sweep.csv`: the thread sweep at these levels.
- `record/`: the session's summary (`SUMMARY.md`), its clock policy (`policy.json`), and each
  invocation's folder: the preflight alone (`20261025-153349`), then the session and its self-check
  (`20261025-153423`, with `session.log`, `checks.log`, the monitor's samples and the events).
- `record/logs/`: the launcher's logs of the calibration, the thread sweep, the portfolio rebuild and
  verification, the gates and the unit tests.
