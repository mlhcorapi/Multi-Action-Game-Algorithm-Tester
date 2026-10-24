# Controlled calibration: tribes-r7615-time

- Currency: `/home/jovyan/magat_runs/calibration/tribes-r7615-time/compute_weights.properties` (written)
- This invocation: section `2`, record `/home/jovyan/magat_runs/calibration/tribes-r7615-time/record/20261011-125712`, 1.09 h, finished 2026-10-11 14:02:50, exit 0
- Commit da8b39b99e7839940f8688e599066ac88a450b77-dirty; AMD EPYC 9534 64-Core Processor; Dell Inc. PowerEdge R7615; openjdk version "11.0.29" 2025-10-21

## Conditions of this invocation

| | |
|---|---|
| Clock policy | frequency governor schedutil |
| Measuring JVMs | CPUs 8-15,72-79: the quietest of 8 L3 domains (16 logical CPUs, 0.0 CPUs busy when chosen) |
| Container | cgroup v2, CPUs 0-127, no CPU limit |
| Other work at the start | 0.0 logical CPUs |
| Uptime at start | 628.2 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.62 | 1.13 | 5.43 | - | 3700-3700 MHz (median 3700), up to 48 C |
| 2 | 0.62 | 1.13 | 5.44 | 0.2% | 1500-3700 MHz (median 3700), up to 49 C |

## Sections measured in this invocation

| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |
|---|---|---|---|---|---|---|---|---|
| section 2 | emcts-short15, emcts-short15-bias-e25, emcts-short5-bias-e25, emcts-z0-coarse, emcts-z0-mid, emcts-z0-fine, emcts-z0-fine-insfine, comma-bias-e75 | PASS | 60.9 | -0.1%/-0.1%/-1.4% | x1.004 | 0.0 / 0.0 | 1500-3700 MHz (median 3700), up to 49 C | 20261011-125712 |

Other work above 4 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

