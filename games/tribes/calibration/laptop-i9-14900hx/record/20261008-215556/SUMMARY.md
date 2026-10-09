# Controlled calibration: tribes-laptop-i9-14900hx

- Currency: `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\compute_weights.properties` (written)
- This invocation: section `1,2,3,4` (redo), record `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\record\20261008-215556`, 3.94 h, finished 2026-10-09 01:52:28, exit 0
- Commit 55566b1b7726d384cdc33a5dfe52479a8b31019a; Intel(R) Core(TM) i9-14900HX; HP OMEN Gaming Laptop 16-am0xxx; java version "26.0.2.1" 2026-08-18

## Conditions of this invocation

| | |
|---|---|
| Power | mains, battery 99%, charging |
| Power plan | MAGAT calibration (7c6ce635-4559-4dfb-a8e8-d51346f59f7d); power mode best performance |
| Clock policy | boost aggressive, performance cores capped at 4000 MHz, efficient cores capped at 4000 MHz |
| Measuring JVMs | CPUs 0-15: the performance cores of a hybrid CPU (16 logical CPUs); priority high; power throttling off |
| Other work at the start | 0.7 logical CPUs |
| Kept awake | yes |
| Network | offline |
| Uptime at start | 9.4 h |

## Stability check

| Estimate | 40000 s/turn | 80000 s/turn | 400000 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 2.33 | 6.85 | 35.05 | - | about 4190-4234 MHz (median 4219), up to 43 C |
| 2 | 2.30 | 6.75 | 34.03 | 2.9% | about 4196-4221 MHz (median 4213), up to 43 C |
| 3 | 2.27 | 6.69 | 34.03 | 1.3% | about 4197-4227 MHz (median 4211), up to 43 C |
| 4 | 2.31 | 6.77 | 34.23 | 1.8% | about 4194-4229 MHz (median 4207), up to 43 C |
| 5 | 2.31 | 6.85 | 34.88 | 1.9% | about 4183-4226 MHz (median 4211), up to 43 C |

## Sections measured in this invocation

| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |
|---|---|---|---|---|---|---|---|---|
| section 1 redo | emcts-comma, emcts-bias-e75, emcts-bias-e50, emcts-bias-e25, emcts-bias-e10, emcts-bias-coarse-e25, emcts-bias-norem-e25, emcts-bias-perburn-e25 | PASS | 54.4 | -0.0%/-0.5%/+0.1% | x0.993 | 0.1 / 0.1 | about 4122-4240 MHz (median 4220), up to 43 C | 20261008-215556 |
| section 2 redo | emcts-short15, emcts-short15-bias-e25, emcts-short5-bias-e25, emcts-z0-coarse, emcts-z0-mid, emcts-z0-fine, emcts-z0-fine-insfine, comma-bias-e75 | PASS | 56.5 | -0.0%/-1.1%/-0.2% | x1.006 | 0.1 / 0.2 | about 4152-4252 MHz (median 4217), up to 43 C | 20261008-215556 |
| section 3 redo | comma-bias-e50, comma-bias-e25, comma-bias-e10, comma-bias-coarse-e25, comma-bias-norem-e25, comma-bias-perburn-e25, comma-short15, comma-short15-bias-e25 | PASS | 56.9 | +0.2%/-0.1%/+0.5% | x0.987 | 0.1 / 0.1 | about 4168-4242 MHz (median 4221), up to 43 C | 20261008-215556 |
| section 4 redo | comma-short5-bias-e25, comma-z0-coarse, comma-z0-mid, comma-z0-fine, comma-z0-random, comma-rand15, comma-short15-coarse, comma-bias-fine-e25 | PASS | 58.1 | -0.1%/-0.6%/-0.4% | x0.990 | 0.1 / 0.1 | about 4152-4253 MHz (median 4218), up to 42 C | 20261008-215556 |

Other work above 1 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

