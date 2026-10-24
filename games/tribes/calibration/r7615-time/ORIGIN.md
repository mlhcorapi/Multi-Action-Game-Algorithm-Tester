# Origin of the r7615-time currency

A version-2 Tribes currency, measured on the R7615 by `magat calibrate-controlled` (run from the notebook)
on 2026-10-11. It prices all 33 AB-EMCTS arms at the published levels **9,500 / 20,000 / 101,500 units**,
which are 500, 1000 and 5000 ms of a whole `emcts` turn on this machine. Each turn's initialization is
paid from its budget. One unit is one advance of the reference cell `emcts@9500`, 57,696.049 ns on this
machine.

It lives on the `ab-emcts` branch. MAGAT v0.1 (main) dropped these arms; this is the currency for
re-adding them.

| | |
|---|---|
| Machine | Dell PowerEdge R7615, AMD EPYC 9534 (64 cores, 128 logical CPUs, 1 NUMA node, 8 L3 domains), through JupyterHub |
| Java | OpenJDK 11.0.29 (Ubuntu), `-Xmx4g -XX:+UseG1GC` |
| Commit | `da8b39b` (main after PR #16), recorded as `-dirty` (the checkout's notebook, saved with its outputs) |
| Engine | `828b130` |
| Portfolio | the server's own rebuild, identity `ec243fd7...a7`, `VERIFY: PASS` against `games/tribes/portfolio/v1` |
| Levels | 9,500, 20,000, 101,500; turns per repetition 16, 16, 16; 3 repetitions; 6 warm-up turns |
| Arms | 33 × 3 levels = 99 profiles: `emcts` and the 32 registered AB-EMCTS arms |
| `compute_weights.properties` sha256 | `b5cccd755fbc0034cec720a5a817360395bcfdc48c446d02d672bcbc5abc7fc4` |

## Conditions

- **Clock policy:** the `schedutil` frequency governor, which can't be changed from the container. The busiest measuring core ran 1,480-3,700 MHz (median 3,700), up to 50 °C.
- **Measuring JVMs:** on the quietest of the 8 L3 domains (16 logical CPUs), chosen at the start of each invocation.
- **Other work:** none was seen in any section (the worst was 0.0 logical CPUs). The preflight warned that 3 other Jupyter kernels were open; none of them ran anything.
- **Stability check:** two estimates within 3% before every invocation (0.2-1.3%).

## Sections

| Section | Arms | Verdict | Minutes | Bracket (adv / copy / eval) | Speed vs first |
|---|---|---|---|---|---|
| 1 | emcts-comma, emcts-bias-e75, -e50, -e25, -e10, emcts-bias-coarse-e25, -norem-e25, -perburn-e25 | PASS | 56.2 | -0.3 / -0.4 / -0.4% | first |
| 2 | emcts-short15, emcts-short15-bias-e25, emcts-short5-bias-e25, emcts-z0-coarse, -mid, -fine, -fine-insfine, comma-bias-e75 | PASS | 60.9 | -0.1 / -0.1 / -1.4% | ×1.004 |
| 3 | comma-bias-e50, -e25, -e10, comma-bias-coarse-e25, -norem-e25, -perburn-e25, comma-short15, comma-short15-bias-e25 | PASS | 64.1 | -0.1 / -0.2 / -1.4% | ×1.002 |
| 4 | comma-short5-bias-e25, comma-z0-coarse, -mid, -fine, comma-z0-random, comma-rand15, comma-short15-coarse, comma-bias-fine-e25 | PASS | 60.3 | -0.2 / -0.1 / -0.3% | ×0.998 |

Each section also measured the reference arm `emcts` at every level.

## Checks (write nothing)

| Check | Verdict | Largest turn-price drift |
|---|---|---|
| Against the thesis R7615 currency, at its 40k/80k/400k, initialization outside the budget | PASS | -4.6% (`emcts@400000`) |
| Against this currency (self) | PASS | +0.6% (`emcts@101500`) |

## The gates the same evening

All PASS:
- **Contract:** passed.
- **Equivalence:** 33 arms × 32 cells, 0 failed.
- **Structure:** passed.
- **Replay:** 20 of the thesis's holdout turns reproduced.
- **Overhead:** the decorator costs 0.08% of an advance, 0.67% of a copy and 1.54% of an evaluation.
- **Audit:** unaccounted time 0.1% or less.
- **Support probe:** `comma-bias-e25` was a contrast arm at all three levels.

**Thread sweep:** 62 workers at every level (`threads.json`).

## One finding to carry forward

**The comma arms overspend at the small levels.** Their (1,5) selection starts from five greedy initial
plans, and an initialization cannot be interrupted. Per turn, under the fixed measurement weights:

| | 9,500 | 20,000 | 101,500 |
|---|---|---|---|
| `emcts` | 1.05× | 1.01× | 1.00× |
| `emcts-comma` | 2.2× | 1.3× | 1.0× |
| `comma-bias-e25` | 2.0× | 1.25× | 1.0× |

At 9,500 a comma arm spends its whole budget, and more, before it searches. Re-adding them needs an
initialization that stops at the budget, or a smallest level they can afford.

## What is here

- `compute_weights.properties`, `compute_profiles.csv`, `compute_operations.csv`: the currency and the last session's measurements.
- `threads.json` and `thread_sweep.csv`: the thread sweep at these levels.
- `support_probe_comma-bias-e25.csv`: the support probe.
- `record/`: every invocation's record and summary, `state.json`, and, in `record/logs/`, the launcher logs of the calibration, the checks and the gates.
