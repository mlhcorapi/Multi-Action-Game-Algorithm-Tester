# Controlled calibration: tribes-laptop-i9-14900hx

- Currency: `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\compute_weights.properties` (written)
- This invocation: section `checks`, record `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\record\20261009-094245`, 0.68 h, finished 2026-10-09 10:23:56, exit 0
- Commit 55566b1b7726d384cdc33a5dfe52479a8b31019a; Intel(R) Core(TM) i9-14900HX; HP OMEN Gaming Laptop 16-am0xxx; java version "26.0.2.1" 2026-08-18

## Conditions of this invocation

| | |
|---|---|
| Power | mains, battery 100% |
| Power plan | MAGAT calibration (7c6ce635-4559-4dfb-a8e8-d51346f59f7d); power mode best performance |
| Clock policy | boost aggressive, performance cores capped at 4000 MHz, efficient cores capped at 4000 MHz |
| Measuring JVMs | CPUs 0-15: the performance cores of a hybrid CPU (16 logical CPUs); priority high; power throttling off |
| Other work at the start | 4.6 logical CPUs |
| Kept awake | yes |
| Network | online: Wi-Fi |
| Uptime at start | 21.2 h |

## Stability check

| Estimate | 40000 s/turn | 80000 s/turn | 400000 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 2.32 | 6.80 | 34.84 | - | about 4199-4233 MHz (median 4225), up to 43 C |
| 2 | 2.30 | 6.72 | 34.19 | 1.9% | about 4173-4225 MHz (median 4209), up to 42 C |
| 3 | 2.28 | 6.69 | 34.07 | 0.9% | about 4187-4227 MHz (median 4207), up to 42 C |
| 4 | 2.30 | 6.78 | 34.20 | 1.3% | about 4148-4225 MHz (median 4206), up to 42 C |
| 5 | 2.30 | 6.81 | 34.70 | 1.5% | about 4193-4231 MHz (median 4211), up to 42 C |

## Checks in this invocation (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |
|---|---|---|---|---|
| published | PASS | 3.6% (emcts@400000) | -0.3%/-0.5%/-0.8% | about 4167-4236 MHz (median 4208), up to 42 C |
| self | PASS | -0.6% (emcts-comma@400000) | -0.2%/-0.4%/-0.2% | about 4168-4227 MHz (median 4203), up to 42 C |

Other work above 1 logical CPU(s), sustained: 1 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

