# Controlled calibration: tribes-r7615-time

- Currency: `/home/jovyan/magat_runs/calibration/tribes-r7615-time/compute_weights.properties` (written)
- This invocation: section `4`, record `/home/jovyan/magat_runs/calibration/tribes-r7615-time/record/20261011-183813`, 1.08 h, finished 2026-10-11 19:43:15, exit 0
- Commit da8b39b99e7839940f8688e599066ac88a450b77-dirty; AMD EPYC 9534 64-Core Processor; Dell Inc. PowerEdge R7615; openjdk version "11.0.29" 2025-10-21

## Conditions of this invocation

| | |
|---|---|
| Clock policy | frequency governor schedutil |
| Measuring JVMs | CPUs 24-31,88-95: the quietest of 8 L3 domains (16 logical CPUs, 0.0 CPUs busy when chosen) |
| Container | cgroup v2, CPUs 0-127, no CPU limit |
| Other work at the start | 0.0 logical CPUs |
| Uptime at start | 633.9 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.62 | 1.14 | 5.49 | - | 1500-3700 MHz (median 3700), up to 47 C |
| 2 | 0.62 | 1.13 | 5.45 | 0.9% | 3700-3700 MHz (median 3700), up to 48 C |

## Sections measured in this invocation

| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |
|---|---|---|---|---|---|---|---|---|
| section 4 | comma-short5-bias-e25, comma-z0-coarse, comma-z0-mid, comma-z0-fine, comma-z0-random, comma-rand15, comma-short15-coarse, comma-bias-fine-e25 | PASS | 60.3 | -0.2%/-0.1%/-0.3% | x0.998 | 0.0 / 0.0 | 1480-3700 MHz (median 3700), up to 47 C | 20261011-183813 |

Other work above 4 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

