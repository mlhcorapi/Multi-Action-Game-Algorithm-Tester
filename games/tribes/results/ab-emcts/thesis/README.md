# The thesis documents behind the worked result

These are the AB-EMCTS line's own documents, kept verbatim from the thesis tree (`Tribes-master/`). That
tree was removed from this repository on 2026-10-07, at the end of the extraction (MAGAT milestone 7).

| File | Was | What it is |
|---|---|---|
| `retrospective.md` | `analysis/AB_EMCTS_RETROSPECTIVE.md` | the retrospective of stages A-F. MAGAT's re-analysis is checked against its section 3 and 4 numbers (`analysis/tests/test_worked_result.py`, `docs/results/tribes-ab-emcts.md`) |
| `r7615-plan.md` | `analysis/AB_EMCTS_R7615_PLAN.md` | the staged design for the R7615, written before the runs |
| `runbook.md` | `analysis/AB_EMCTS_RUNBOOK.md` | how the plan was executed on the R7615 |
| `compute-unit-calibration.md` | `analysis/COMPUTE_UNIT_CALIBRATION.md` | the thesis calibrator's method: per configuration, per budget level |

They use the thesis's names (`AB_Baseline`, `Comma_Z0_Coarse`, `ComputeCalibrateV6` and so on).
`MAGAT/DESIGN.md` Appendix A and `tools/legacy-arms.json` map every archived arm name to its MAGAT id.
File references in them point at the thesis tree. That tree is in this repository's history, last at
commit `bb3f511`. Its engine, and the thesis agents MAGAT's ports are gated against, are in the engine
fork, `mlhcorapi/Tribes`, branch `magat-engine` (`games/tribes/engine`).
