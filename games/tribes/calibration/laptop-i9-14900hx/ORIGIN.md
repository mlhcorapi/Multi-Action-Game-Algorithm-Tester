# Origin of the laptop-i9-14900hx currency

A version-2 Tribes currency, measured on the development laptop by `magat calibrate-controlled`
between 2026-10-07 and 2026-10-09. Every operation each arm declares is priced. One unit is one advance
of the reference cell `emcts@40000`, 54,426.307 ns on this machine.

It is published beside the R7615 currency, not over it. Results computed under it quote its name,
`laptop-i9-14900hx`.

| | |
|---|---|
| Machine | HP OMEN 16 (16-am0xxx), Intel Core i9-14900HX (8 performance cores with SMT, 16 efficient cores), Windows 11 Home |
| Java | 26.0.2.1 (HotSpot), `-Xmx4g -XX:+UseG1GC` |
| Commit | sections: `55566b1` (main after PR #10); checks: the same |
| Portfolio | `games/tribes/portfolio/v1`, identity `ec243fd7...a7` |
| Levels | 40k, 80k, 400k; turns per repetition 8, 4, 2; 3 repetitions; 6 warm-up turns |
| Arms | 33 × 3 levels = 99 profiles: `emcts` and the 32 registered worked-result arms |
| `compute_weights.properties` sha256 | `4eb04465ba8c260935e2500233af652f0769c7e8d2d05ba540f9a68e192984e3` |

`comma-z0-fine-insfine`, which the R7615 file prices, is not here. It is not registered: the thesis
dropped it, and at epsilon 1.00 it plays exactly as `comma-z0-fine`. `emcts-engine` anchors the R7615 unit
and is never run.

## Conditions

- **Clock policy A.** A "MAGAT calibration" power plan:
  - boost aggressive;
  - performance and efficient cores capped at 4,000 MHz;
  - maximum processor state 100%;
  - energy-performance preference 0;
  - Windows power mode *Best performance*.

  The OMEN Gaming Hub mode is not recorded by the tool. The guide's setup is *Performance*.
- **Measuring JVMs** on CPUs 0-15 (the performance cores), at high priority, with power throttling off.
- **Power and network.** On mains (the battery was charging at 99% during the sections). Offline during
  the sections; Wi-Fi was on during the checks, which write nothing.
- **Stability check.** It settled after 11 minutes (two estimates in a row within 3%) before each
  invocation.
- **The busiest measuring core** held about 4,120-4,250 MHz in every section, at no more than 43 °C,
  without thermal throttling. On Windows that clock is approximate (it reads about 4,220 under a 4,000 cap).
  Its spread is what shows that the cap held.

## Sections

The sections in force were measured in invocation `20261008-215556`, `--section 1,2,3,4 --redo`, 3.9 h.

| Section | Arms | Verdict | Minutes | Bracket (adv / copy / eval) | Speed vs first | Other work, worst |
|---|---|---|---|---|---|---|
| 1 | emcts-comma, emcts-bias-e75, -e50, -e25, -e10, emcts-bias-coarse-e25, -norem-e25, -perburn-e25 | PASS | 54.4 | -0.0 / -0.5 / +0.1% | ×0.993 | 0.14 CPUs |
| 2 | emcts-short15, emcts-short15-bias-e25, emcts-short5-bias-e25, emcts-z0-coarse, -mid, -fine, -fine-insfine, comma-bias-e75 | PASS | 56.5 | -0.0 / -1.1 / -0.2% | ×1.006 | 0.18 CPUs |
| 3 | comma-bias-e50, -e25, -e10, comma-bias-coarse-e25, -norem-e25, -perburn-e25, comma-short15, comma-short15-bias-e25 | PASS | 56.9 | +0.2 / -0.1 / +0.5% | ×0.987 | 0.14 CPUs |
| 4 | comma-short5-bias-e25, comma-z0-coarse, -mid, -fine, comma-z0-random, comma-rand15, comma-short15-coarse, comma-bias-fine-e25 | PASS | 58.1 | -0.1 / -0.6 / -0.4% | ×0.990 | 0.13 CPUs |

Each section also measured the reference arm `emcts` at every level. "Speed vs first" compares the
section's reference advance with the one that defines the unit. In every section the reference cell
played the same turns: `emcts@40000` made 876,402 advances and `emcts@400000` made 2,097,729, as in the
session that created the file. The two fastest repetitions of every cell agreed within 0.6% on the
advance, 1.4% on the copy and 3.1% on the evaluation (the gate is 10%).

## Checks

Invocation `20261009-094245`, `--section checks --redo-checks`, `--arms emcts,emcts-comma`.

| Check | Against | Verdict | Largest turn-price drift | Bracket |
|---|---|---|---|---|
| published | `games/tribes/calibration/r7615` | PASS | +3.6% (`emcts@400000`) | -0.3 / -0.5 / -0.8% |
| self | this file | PASS | -0.6% (`emcts-comma@400000`) | -0.2 / -0.4 / -0.2% |

Against the R7615, evaluations are again about a third cheaper relative to an advance here (−31 to −35%
per operation, information only). The turn prices agree within the 10% gate.

**Laptop against the R7615, every cell.** The ratio of advance prices over the 99 profiles both files
hold:

| Level | Median | Range |
|---|---|---|
| 40k | 1.023 | 0.988 (`emcts-bias-norem-e25`) to 1.058 (`comma-short15-coarse`) |
| 80k | 1.031 | 0.988 (`comma-z0-fine`) to 1.068 (`comma-short5-bias-e25`) |
| 400k | 1.044 | 0.944 (`comma-bias-perburn-e25`) to 1.084 (`comma-short5-bias-e25`) |

At 400k, the advance price varies widely between arms with the same counts: `emcts-z0-fine` is priced
0.88 and `emcts-z0-coarse` 1.55. The R7615 shows the same pattern (0.86 and 1.45). It comes from the
states each arm's search visits, not from noise. Section 1, measured twice in separate JVMs a day apart,
repeated every price within 0.5%.

## The first attempt, superseded

Invocation `20261007-231738` (commit `89e59d1`) measured all four sections and both checks.
- **Section 1** created the file and passed. It set the unit.
- **Sections 2-4** each passed their gates but were SUSPECT, at ×1.066, ×1.069 and ×1.060 the first
  section's speed, on a machine that was flat and quiet. The self-check reported -9.5%.

The cause was a calibrator bug, not the machine (DESIGN §8.1, amendment of 2026-10-08). A merge measured
its cells under the prices of the file it extended. Its reference cell therefore played a shorter search
(872,853 advances, against 876,402) and the 24 arms of sections 2-4 were priced about 6% low. PR #10
fixed it: every cell, in every session and check, is measured under the fixed measurement weights.
All four sections were then measured again. The redone sections 2-4 priced their arms 5-7% higher, as
predicted. Section 1's prices moved by at most 0.5%.

## Edits after the calibration

Comment lines only, made on 2026-10-09; every price is as measured.
- **The header** now states the fixed measurement weights, as a file created since PR #10 does. The
  session that created this file had no file to extend, so it already measured that way.
- **The three merges of the first attempt** each carry a `superseded` line. Their profiles were replaced
  by the redo's merges, which left their `# merged` lines empty.
- **LF line ends throughout.** Until 2026-10-09 `magat calibrate` wrote the lines it formats with the
  platform's line separator, so on Windows the last merge's block had CRLF line ends. It now writes LF
  on every platform, and `.gitattributes` keeps currencies LF, because a run's provenance hashes the
  file's bytes.

## Files

| File | What it is |
|---|---|
| `compute_weights.properties` | the currency |
| `record/SUMMARY.md`, `record/state.json` | every section's latest verdict and the checks; the plan, every session and its conditions |
| `record/<stamp>/` | one invocation each: `SUMMARY.md`, `events.log`, the preflight, the stability soak, the sections' or checks' console logs, the monitor's samples (`monitor.csv`), and each section's `compute_profiles.csv` and `compute_operations.csv` |

Invocations `20261007-215455`, `20261007-215718` and `20261008-195842` are preflights; they measured
nothing.

Not published:
- `counters.csv` (each invocation's raw processor-counter samples, 0.4-2.4 MB; `monitor.csv` summarises
  them);
- the calibration directory's own `compute_profiles.csv` and `compute_operations.csv`, which are section
  4's and are in `record/20261008-215556/section-4-redo/`.

## Using it

Commands default to the R7615 currency, so name this one:
```bash
python magat.py run --experiment <spec.json> --weights games/tribes/calibration/laptop-i9-14900hx/compute_weights.properties
```

On another machine, check it first:
```bash
python magat.py calibrate --check --game tribes --arms emcts,emcts-comma --weights games/tribes/calibration/laptop-i9-14900hx/compute_weights.properties
```
