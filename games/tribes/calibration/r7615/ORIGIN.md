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
