# Controlled calibration: tribes-r7615-time

- Verdict: COMPLETE
- Currency: `/home/jovyan/magat_runs/calibration/tribes-r7615-time/compute_weights.properties` (written)
- This invocation: the session and the checks; record `/home/jovyan/magat_runs/calibration/tribes-r7615-time/record/20261025-153423`, 0.77 h, finished 2026-10-25 16:20:32, exit 0
- Commit d5e1f98ff6daae102bd2376cb941c3eff8b78c2f, notebook modified; AMD EPYC 9534 64-Core Processor; Dell Inc. PowerEdge R7615; openjdk version "11.0.29" 2025-10-21

## Conditions

| | |
|---|---|
| Clock policy | frequency governor schedutil |
| Measuring JVMs | CPUs 8-15,72-79: the quietest of 8 L3 domains (16 logical CPUs, 0.0 CPUs busy when chosen) |
| Container | cgroup v2, CPUs 0-127, no CPU limit |
| Other work at the start | 0.0 logical CPUs |
| Uptime at start | 130.9 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.57 | 1.12 | 5.46 | - | 1500-3700 MHz (median 3700), up to 48 C |
| 2 | 0.57 | 1.11 | 5.44 | 0.9% | 3700-3700 MHz (median 3700), up to 47 C |

## The session

| Attempt | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs the file | Other work: mean / worst | Busiest core |
|---|---|---|---|---|---|---|---|
| 1 | emcts, mcts, oep, tbets | PASS | 34.1 | -0.1%/-0.0%/-1.7% | - | 0.1 / 0.2 | 1480-3700 MHz (median 3700), up to 49 C |
- attempt 1, spend: every cell's mean turn is within 1.10x of its level

The calibrator's own output, every cell and table, is in `session.log`; its measurements in `compute_profiles.csv` and `compute_operations.csv`.

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |
|---|---|---|---|---|
| self | PASS | -0.6% (emcts@101500) | -0.5%/+0.1%/+0.1% | 1500-3700 MHz (median 3700), up to 48 C |

Each check's drift table is in `checks.log`.

Other work above 4 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample. Every invocation keeps its own folder in `/home/jovyan/magat_runs/calibration/tribes-r7615-time/record`.

