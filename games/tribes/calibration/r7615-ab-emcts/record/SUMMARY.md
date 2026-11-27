# Controlled calibration: tribes-r7615-ab-emcts

- Verdict: COMPLETE
- Currency: `/home/jovyan/magat_runs/calibration/tribes-r7615-ab-emcts/compute_weights.properties` (written)
- This invocation: the session and the checks; record `/home/jovyan/magat_runs/calibration/tribes-r7615-ab-emcts/record/20261127-163421`, 0.76 h, finished 2026-11-27 17:20:07, exit 0; options from `/home/jovyan/Multi-Action-Game-Algorithm-Tester/calibrations/tribes/ab-emcts.json`, copied as `config.json`
- Commit 82f991aa0340aff33d9bded34113fa0704427cbd, notebook modified; AMD EPYC 9534 64-Core Processor; Dell Inc. PowerEdge R7615; openjdk version "11.0.29" 2025-10-21

## Conditions

| | |
|---|---|
| Clock policy | frequency governor schedutil |
| Measuring JVMs | CPUs 16-23,80-87: the quietest of 8 L3 domains (16 logical CPUs, 0.0 CPUs busy when chosen) |
| Container | cgroup v2, CPUs 0-127, no CPU limit |
| Other work at the start | 0.0 logical CPUs |
| Uptime at start | 490.9 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.57 | 1.11 | 5.46 | - | 1500-3700 MHz (median 3700), up to 49 C |
| 2 | 0.57 | 1.12 | 5.46 | 0.9% | 1500-3700 MHz (median 3700), up to 49 C |

## The session

| Attempt | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs the file | Other work: mean / worst | Busiest core |
|---|---|---|---|---|---|---|---|
| 1 | emcts-comma, comma-z0-coarse, comma-z0-random | PASS | 26.2 | -0.4%/-0.6%/-1.7% | x1.005 | 0.1 / 0.0 | 1480-3700 MHz (median 3700), up to 51 C |
- attempt 1, spend: every cell's mean turn is within 1.10x of its level

The calibrator's own output, every cell and table, is in `session.log`; its measurements in `compute_profiles.csv` and `compute_operations.csv`.

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |
|---|---|---|---|---|
| published | PASS | -0.4% (emcts@20000) | -0.0%/-0.3%/+0.0% | 1500-3700 MHz (median 3700), up to 50 C |
| self | PASS | -0.7% (emcts@20000) | -0.2%/-0.5%/-1.9% | 1500-3700 MHz (median 3700), up to 51 C |

Each check's drift table is in `checks.log`.

Other work above 4 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample. Every invocation keeps its own folder in `/home/jovyan/magat_runs/calibration/tribes-r7615-ab-emcts/record`.

