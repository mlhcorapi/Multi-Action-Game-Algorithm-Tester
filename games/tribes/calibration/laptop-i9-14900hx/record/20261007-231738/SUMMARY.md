# Controlled calibration: tribes-laptop-i9-14900hx

- Currency: `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\compute_weights.properties` (written)
- This invocation: section `all`, record `C:\Users\mlcor\magat_runs\calibration\tribes-laptop-i9-14900hx\record\20261007-231738`, 4.20 h, finished 2026-10-08 03:29:31, exit 5
- Commit 89e59d135388f9a0ccee0ab20505ece308b9275d; Intel(R) Core(TM) i9-14900HX; HP OMEN Gaming Laptop 16-am0xxx; java version "26.0.2.1" 2026-08-18

## Conditions of this invocation

| | |
|---|---|
| Power | mains, battery 100% |
| Power plan | MAGAT calibration (8b0ed4de-a1ca-4797-af2e-000b6ea8004e); power mode best performance |
| Clock policy | boost aggressive, performance cores capped at 4000 MHz, efficient cores capped at 4000 MHz |
| Measuring JVMs | CPUs 0-15: the performance cores of a hybrid CPU (16 logical CPUs); priority high; power throttling off |
| Other work at the start | 0.8 logical CPUs |
| Kept awake | yes |
| Network | offline |
| Uptime at start | 2.1 h |

## Stability check

| Estimate | 40000 s/turn | 80000 s/turn | 400000 s/turn | Change | Busiest core |
|---|---|---|---|---|---|
| 1 | 2.34 | 6.84 | 34.79 | - | 4217-4234 MHz (median 4227), up to 43 C |
| 2 | 2.31 | 6.76 | 34.27 | 1.5% | 4212-4229 MHz (median 4222), up to 44 C |
| 3 | 2.29 | 6.72 | 33.98 | 0.9% | 4217-4233 MHz (median 4226), up to 44 C |
| 4 | 2.30 | 6.73 | 34.07 | 0.4% | 4205-4232 MHz (median 4223), up to 43 C |
| 5 | 2.27 | 6.69 | 33.93 | 1.3% | 4189-4226 MHz (median 4219), up to 44 C |

## Sections measured in this invocation

| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |
|---|---|---|---|---|---|---|---|---|
| section 1 | emcts-comma, emcts-bias-e75, emcts-bias-e50, emcts-bias-e25, emcts-bias-e10, emcts-bias-coarse-e25, emcts-bias-norem-e25, emcts-bias-perburn-e25 | PASS | 54.8 | +0.1%/-0.4%/-0.4% | first | 0.2 / 0.2 | 4180-4241 MHz (median 4225), up to 44 C | 20261007-231738 |
| section 2 | emcts-short15, emcts-short15-bias-e25, emcts-short5-bias-e25, emcts-z0-coarse, emcts-z0-mid, emcts-z0-fine, emcts-z0-fine-insfine, comma-bias-e75 | SUSPECT | 53.4 | +0.1%/-0.9%/+0.1% | x1.066 | 0.2 / 0.3 | 4194-4250 MHz (median 4221), up to 43 C | 20261007-231738 |
| section 3 | comma-bias-e50, comma-bias-e25, comma-bias-e10, comma-bias-coarse-e25, comma-bias-norem-e25, comma-bias-perburn-e25, comma-short15, comma-short15-bias-e25 | SUSPECT | 56.1 | +0.1%/-0.8%/-0.1% | x1.069 | 0.2 / 0.2 | 4188-4241 MHz (median 4224), up to 43 C | 20261007-231738 |
| section 4 | comma-short5-bias-e25, comma-z0-coarse, comma-z0-mid, comma-z0-fine, comma-z0-random, comma-rand15, comma-short15-coarse, comma-bias-fine-e25 | SUSPECT | 53.8 | +0.1%/+0.3%/-0.4% | x1.060 | 0.2 / 0.3 | 4187-4247 MHz (median 4223), up to 43 C | 20261007-231738 |
- section 2: ran at x1.066 the speed of the currency's first session
- section 3: ran at x1.069 the speed of the currency's first session
- section 4: ran at x1.060 the speed of the currency's first session

## Checks in this invocation (write nothing)

| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |
|---|---|---|---|---|
| published | PASS | 2.2% (emcts-comma@80000) | -0.1%/-0.1%/+0.5% | 4195-4232 MHz (median 4220), up to 42 C |
| self | PASS | -9.5% (emcts@400000) | +0.0%/-0.7%/-1.3% | 4182-4231 MHz (median 4215), up to 42 C |

Other work above 1 logical CPU(s), sustained: 0 period(s); each is in `events.log` with the programs that were using the CPU, and `monitor.csv` has every sample.

