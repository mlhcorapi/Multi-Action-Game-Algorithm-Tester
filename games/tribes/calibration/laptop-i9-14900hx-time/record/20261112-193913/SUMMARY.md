# Controlled calibration: tribes-laptop-i9-14900hx-time

- Verdict: COMPLETE
- Currency: `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx-time\compute_weights.properties` (written)
- This invocation: the session and the checks; record `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx-time\record\20261112-193913`, 0.87 h, finished 2026-11-12 20:31:22, exit 0
- Commit c3afdaed76da7151cf4db255a270699a36e0fd21; Intel(R) Core(TM) i9-14900HX; HP OMEN Gaming Laptop 16-am0xxx; java version "26.0.2.1" 2026-08-18

## Conditions

| | |
|---|---|
| Power | mains, battery 100% |
| Power plan | MAGAT calibration (8c07d645-cc97-420e-9de3-33d46b7ee490); power mode best performance |
| Clock policy | boost aggressive, performance cores capped at 4000 MHz, efficient cores capped at 4000 MHz |
| Measuring JVMs | CPUs 0-15: the performance cores of a hybrid CPU (16 logical CPUs); priority high; power throttling off |
| Other work at the start | 0.2 logical CPUs |
| Kept awake | yes |
| Network | offline |
| Uptime at start | 0.2 h |

## Stability check

| Estimate | 9500 s/turn | 20000 s/turn | 101500 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 0.46 | 0.94 | 4.54 | - | about 4198-4238 MHz (median 4230), up to 43 C |
| 2 | 0.46 | 0.93 | 4.51 | 1.1% | about 4193-4239 MHz (median 4225), up to 43 C |
| 3 | 0.46 | 0.93 | 4.56 | 1.1% | about 4199-4239 MHz (median 4225), up to 43 C |
| 4 | 0.46 | 0.94 | 4.57 | 1.1% | about 4064-4225 MHz (median 4208), up to 44 C |
| 5 | 0.46 | 0.92 | 4.51 | 2.1% | about 3839-4215 MHz (median 4093), up to 45 C |
| 6 | 0.46 | 0.92 | 4.48 | 0.7% | about 4162-4235 MHz (median 4214), up to 44 C |
| 7 | 0.46 | 0.92 | 4.50 | 0.4% | about 4173-4240 MHz (median 4220), up to 43 C |

## The session

| Attempt | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs the file | Other work: mean / worst | Busiest core |
|---|---|---|---|---|---|---|---|
| 1 | emcts, mcts, oep, tbets | PASS | 29.3 | -0.3%/+0.0%/-0.1% | - | 0.1 / 0.1 | about 4157-4249 MHz (median 4221), up to 43 C |
- attempt 1, spend: every cell's mean turn is within 1.10x of its level

The calibrator's own output, every cell and table, is in `session.log`; its measurements in `compute_profiles.csv` and `compute_operations.csv`.

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |
|---|---|---|---|---|
| published | PASS | -0.4% (emcts@101500) | +0.5%/+0.8%/-0.5% | about 4190-4240 MHz (median 4224), up to 43 C |
| self | PASS | 0.2% (emcts@20000) | -0.2%/-0.2%/-0.0% | about 4153-4241 MHz (median 4221), up to 43 C |

Each check's drift table is in `checks.log`.

Other work above 1 logical CPU(s), sustained: 1 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample. Every invocation keeps its own folder in `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx-time\record`.

