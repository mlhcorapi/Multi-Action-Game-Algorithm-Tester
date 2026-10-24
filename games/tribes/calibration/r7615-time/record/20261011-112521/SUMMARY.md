# Controlled calibration: tribes-r7615-time

- Currency: `/home/jovyan/magat_runs/calibration/tribes-r7615-time/compute_weights.properties` (written)
- This invocation: section `1`, record `/home/jovyan/magat_runs/calibration/tribes-r7615-time/record/20261011-112521`, 1.01 h, finished 2026-10-11 12:26:18, exit 0
- Commit da8b39b99e7839940f8688e599066ac88a450b77-dirty; AMD EPYC 9534 64-Core Processor; Dell Inc. PowerEdge R7615; openjdk version "11.0.29" 2025-10-21

## Conditions of this invocation

| | |
|---|---|
| Clock policy | frequency governor schedutil |
| Measuring JVMs | CPUs 24-31,88-95: the quietest of 8 L3 domains (16 logical CPUs, 0.0 CPUs busy when chosen) |
| Container | cgroup v2, CPUs 0-127, no CPU limit |
| Other work at the start | 0.0 logical CPUs |
| Uptime at start | 626.7 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.62 | 1.14 | 5.50 | - | 3700-3700 MHz (median 3700), up to 46 C |
| 2 | 0.62 | 1.13 | 5.45 | 0.9% | 1500-3700 MHz (median 3700), up to 48 C |

## Sections measured in this invocation

| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |
|---|---|---|---|---|---|---|---|---|
| section 1 | emcts-comma, emcts-bias-e75, emcts-bias-e50, emcts-bias-e25, emcts-bias-e10, emcts-bias-coarse-e25, emcts-bias-norem-e25, emcts-bias-perburn-e25 | PASS | 56.2 | -0.3%/-0.4%/-0.4% | first | 0.0 / 0.0 | 1500-3700 MHz (median 3700), up to 48 C | 20261011-112521 |

Other work above 4 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

