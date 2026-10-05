# Origin of the published Tribes calibration

Measured on the Dell PowerEdge R7615 (AMD EPYC 9534, 64 physical / 128 logical cores, JDK 11.0.29)
by the thesis line's `ComputeCalibrateV6`, 2026-08-28 (stage A2, 54 cells) and merged into by the
D-prep, E-prep, E3 and later sessions (see the `# merged` lines in the original file). One unit is
one advance of the reference cell `EMCTS@40000` = 62870.569 ns on that machine.

| File | What it is |
|---|---|
| `compute_weights.properties` | the currency in MAGAT's version-1 format, arms renamed per the table below |
| `compute_weights.r7615-original.properties` | the final archived file, verbatim (all merges applied) |
| `compute_weights.r7615-stageA.properties` | the stage A2 file, verbatim, before any merge |
| `compute_profiles.stageA.csv` | every stage A2 cell: counts, per-op nanoseconds, CVs, weights, status |
| `compute_profiles.last.csv` | the last calibration session's cells (the file is overwritten per session) |
| `compute_profiles_load48.csv` | the A3' loaded measurement (48 workers), archived, not the currency |
| `thread_calibration.csv` | the A0b thread sweep per budget level |
| `action_bias_floor*.csv` | the support-probe results for the biased arms |
| `logs/` | the A-stage console logs, including the verify gate's signature for scenario 1 |

**Mapping of archived profile names to MAGAT arm ids.** `EMCTS@<b>` is the engine's own
`EMCTSAgent`, the anchor of the currency, kept as `emcts-engine` and not run by MAGAT;
`AB_Baseline@<b>` is the arm every action-bias contrast was paired against and is MAGAT's `emcts`.

| archived | MAGAT |
|---|---|
| `EMCTS@<b>` | `emcts-engine@<b>` |
| `AB_Baseline@<b>` | `emcts@<b>` |
| `AB_Bias_e75@<b>` | `emcts-bias-e75@<b>` |
| `AB_Bias_e50@<b>` | `emcts-bias-e50@<b>` |
| `AB_Bias_e25@<b>` | `emcts-bias-e25@<b>` |
| `AB_Bias_e10@<b>` | `emcts-bias-e10@<b>` |
| `AB_Coarse_e25@<b>` | `emcts-bias-coarse-e25@<b>` |
| `AB_NoRemoval_e25@<b>` | `emcts-bias-norem-e25@<b>` |
| `AB_PerBurn_e25@<b>` | `emcts-bias-perburn-e25@<b>` |
| `AB_Shortlist15@<b>` | `emcts-short15@<b>` |
| `AB_Short15_e25@<b>` | `emcts-short15-bias-e25@<b>` |
| `AB_Short5_e25@<b>` | `emcts-short5-bias-e25@<b>` |
| `AB_Z0_Coarse@<b>` | `emcts-z0-coarse@<b>` |
| `AB_Z0_Mid@<b>` | `emcts-z0-mid@<b>` |
| `AB_Z0_Fine@<b>` | `emcts-z0-fine@<b>` |
| `AB_Z0_Fine_InsFine@<b>` | `emcts-z0-fine-insfine@<b>` |
| `AB_Comma_Off@<b>` | `emcts-comma@<b>` |
| `AB_Comma_On@<b>` | `comma-bias-e25@<b>` |
| `AB_Comma_e75@<b>` | `comma-bias-e75@<b>` |
| `AB_Comma_e50@<b>` | `comma-bias-e50@<b>` |
| `AB_Comma_e10@<b>` | `comma-bias-e10@<b>` |
| `AB_Comma_Coarse_e25@<b>` | `comma-bias-coarse-e25@<b>` |
| `AB_Comma_Fine_e25@<b>` | `comma-bias-fine-e25@<b>` |
| `AB_Comma_NoRem_e25@<b>` | `comma-bias-norem-e25@<b>` |
| `AB_Comma_PerBurn_e25@<b>` | `comma-bias-perburn-e25@<b>` |
| `AB_Comma_Short15@<b>` | `comma-short15@<b>` |
| `AB_Comma_Short15_Coarse@<b>` | `comma-short15-coarse@<b>` |
| `AB_Comma_Short15_e25@<b>` | `comma-short15-bias-e25@<b>` |
| `AB_Comma_Short5_e25@<b>` | `comma-short5-bias-e25@<b>` |
| `AB_Comma_Rand15@<b>` | `comma-rand15@<b>` |
| `AB_Comma_Z0_Coarse@<b>` | `comma-z0-coarse@<b>` |
| `AB_Comma_Z0_Mid@<b>` | `comma-z0-mid@<b>` |
| `AB_Comma_Z0_Fine@<b>` | `comma-z0-fine@<b>` |
| `AB_Comma_Z0_Fine_InsFine@<b>` | `comma-z0-fine-insfine@<b>` |
| `AB_Comma_Z0_Random@<b>` | `comma-z0-random@<b>` |

**What the archive does not carry.** Per-operation nanoseconds for copies and evaluations exist only
for the stage A2 cells (the per-session CSV was overwritten by later sessions); merged profiles carry
`advanceNanos` only. `turnsPerRep` was not recorded in the file when it was written; `8,4,2` is what
the A2 log states. The E3 re-pricing log was not bundled.

## Extending this currency on another machine

Copy `compute_weights.properties` into a calibration directory and run
`magat calibrate --game tribes --out <dir> --arms <new-arm>`. Each new cell is measured against this
session's own `emcts@40000` advance and multiplied by this file's stored `emcts@40000` weight,
1.000659 (the ported `emcts` advance relative to the engine EMCTS advance that defines the unit), so a
machine uniformly faster or slower than the R7615 prices the new arm exactly as the R7615 would have.
What does not cancel is a change in cost ratios between operations; run
`magat calibrate --check --game tribes --arms emcts,emcts-comma` first, which reports it per operation
against the 10% gate and writes nothing.

## Drift checks against this currency

`magat calibrate --check --game tribes --arms emcts,emcts-comma`: turns per repetition `8,4,2` (from this
file), 3 repetitions, 6 warm-up turns per level, the published portfolio. Drift is per operation in this
file's unit (gate 10%); the turn price is each cell's own operation mix priced now against stored.

| Date | Machine | Bracket (reference moved) | Advance | Copy | Eval | Turn price | Verdict |
|---|---|---|---|---|---|---|---|
| 2026-10-04 | Intel Core i9-14900HX laptop, Windows 11, JDK 26 | +0.6% / +0.3% / +0.9% (held) | +0.6% to +5.3% | -2.0% to -6.9% | -31.3% to -37.8% | -1.7% to +3.7% | FAIL (evaluations) |

On that laptop the heuristic evaluation is about a third cheaper relative to an advance than on the
EPYC 9534, consistently across every cell and three sessions; advances and copies carry over within the
gate. Evaluations are about 3% of a turn's price, so an arm priced there and merged here would be
mis-sized by a few percent, not by a third; the per-operation gate, as pre-registered, still fails it.
Two earlier sessions on the same laptop ran before the session bracket existed; one measured the
reference advance 14% away from the other, which is why every session now brackets its reference.

## Overhead of the metering design (`magat check overhead`)

`emcts@40000` on the calibrator's eight positions, 4 repetitions, the decorator's timing and the
engine's own hooks interleaved turn by turn. Weights are ratios to the advance, so the line that
matters for a currency is the effect on the ratios.

| Date | Machine | advance | copy | eval | effect on wCopy / wEval | dormant engine counter |
|---|---|---|---|---|---|---|
| 2026-10-05 | i9-14900HX laptop, Windows 11, JDK 26 | +7.5% | +7.6% | +11.0% | +0.0% / +3.3% | 1.6 ns per operation (0.003% of an advance) |

The R7615 calibrated through the engine's hooks; a currency calibrated through MAGAT's decorator on
the same machine would differ in evaluation weight by about this much. Runs time nothing, so what a
budget buys is unaffected. The reference machine has not been measured yet:
`python magat.py check overhead --game tribes --reps 4` on an idle R7615 records its line.

## The support probe against the archived floor files

`magat probe support --arm comma-bias-e25 --scenarios 20` under this currency gives the same verdict as
`action_bias_floor_Comma_On.csv` at every level (98.3% / 98.8% / 99.9% supported, all contrast levels),
with more mutations per turn (450 against 313 at 40k): the archived file was produced under an earlier
currency. The plus-era `Bias_e25` probe re-run under the stage A currency it was produced under
(`compute_weights.r7615-stageA.properties`, renamed) gives 300.9 / 371.2 / 1980.5 mutations per turn
and 97.9% / 97.9% / 99.9% supported against the archived 290.0 / 380.9 / 2000.9 and 97.6% / 98.4% /
99.9%. The thesis agent's code is unchanged for that arm between the probe's run and the final tree,
and the weights are identical, so the remaining difference, about 4% in counts with identical
verdicts, is unexplained; the archived probe most likely ran from a different build than the archive
records. The oracle gate and the stage F replays tie the port to the final thesis code exactly.
