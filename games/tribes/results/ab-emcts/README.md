# The worked result: AB-EMCTS on Tribes

The thesis's action-bias line (AB-EMCTS), run on the R7615 (AMD EPYC 9534, Dell PowerEdge R7615) between
2026-08-28 and 2026-09-02, imported into MAGAT's run layout and re-analysed by `magat analyze`. It is a
thesis result, re-analysed by MAGAT, not a MAGAT run: every row was produced by the thesis tree's runner.
The Tribes engine it ran on is the work of its authors (see the credits in the repository's `README.md`).

| | |
|---|---|
| `dev/` | stages D and E on the 200 development scenarios: 20 arms, 51 leaves, 74,000 turns (from `stageE.zip`; the 37 leaves of `stageD.zip` are byte-identical inside it) |
| `holdout/` | stage F, the one read of the 200 held-out scenarios: 8 arms at 80,000 and 400,000 units, 16 leaves, 22,400 turns (from `stageF.zip`) |
| `report/` | `magat analyze --run games/tribes/results/ab-emcts`: `SUMMARY.md`, `tables/`, `report.json`, figures |
| specs | `experiments/tribes/ab-emcts-dev.json`, `ab-emcts-holdout.json`, and the frozen choices `ab-emcts-frozen.txt` |

`docs/results/tribes-ab-emcts.md` sets the analysis beside the thesis's own retrospective, number by
number; `analysis/tests/test_worked_result.py` asserts that it reproduces.

## What the import kept, and what the thesis rows never had

`magat import-legacy` checked every row against the published portfolio (`games/tribes/portfolio/v1`,
identity `ec243fd7...`): every scenario is in the manifest with the same source, and in the split it
claims. Configuration names became arm ids through `tools/legacy-arms.json`.

- **Kept:** initial and final values, nodes, unique sequences, duplicates, average node value, priced
  units, plan and initialization milliseconds, and every agent counter (`t_mutEvents`,
  `t_repairCandidateEvals`, ...), plus the forward-model call count as `t_fmCalls`.
- **Blank:** `advances`, `copies`, `evals` and the `init_*` columns. The thesis rows carry no copy or
  evaluation counts, and their forward-model count includes the initialization's advances, which MAGAT's
  `advances` does not. These leaves therefore cannot be re-priced in another currency (`--reprice` says
  so); their op counts exist only for the calibration cells (`games/tribes/calibration/r7615`).
- **Terminal turns** are marked by the thesis's own cutoff, |final| >= 1e9 (the `terminal` column is blank).
- **Prices:** the thesis runner kept no per-leaf price, so `_leaf.json` records none; `_run.json` names
  the currency the line ran in (`games/tribes/calibration/r7615`), in its final state. One development
  leaf, `comma-short15-coarse@400000`, ran under a price that was superseded before stage F
  (retrospective section 8); the replication table marks it as the one headline that does not replicate.
- **Not in the repository: the per-burn anytime curves.** Each leaf's `curve.csv` holds one row per
  bridge-burn phase of every turn: the best value found so far, and the forward-model calls and units
  spent by then. They are kept out of the repository and published as a downloadable asset (DESIGN §17,
  decision 7).

## The downloadable asset: the full trees, anytime curves included

`ab-emcts-trees-with-curves-v1.zip` (30.5 MB) holds both trees exactly as they are here plus every
leaf's `curve.csv`. Every `turns.csv` in it is byte-identical to this directory's; the packaging script
refuses otherwise. Unzipped, it analyses with the anytime section (#9) filled:

```bash
python magat.py analyze --run <unzipped>/ab-emcts --out <dir>
```

It is built from the thesis line's stage bundles, which are not public, by
`tools/package_worked_result.py`. That script re-imports both stages with their curves, checks them
against these trees, zips them, and prints the command that would publish the zip as a release asset.
It uploads nothing; publishing is the owner's step.

```bash
python tools/package_worked_result.py --stage-e ~/Downloads/stageE.zip --stage-f ~/Downloads/stageF.zip
```

Built on 2026-10-06, its sha256 was `f61a96903c962a7d3a0891290732809aaab71e60c0a9f5c509680a7e8afca097`.
The `imported.at` stamp in each `_run.json` dates a rebuild, so a later rebuild has another hash with
the same rows.

## Re-analysing

```bash
python magat.py analyze --run games/tribes/results/ab-emcts --out <dir>
```

The bootstrap is seeded per row, so with the same numpy this reproduces `report/` byte for byte (another
numpy may move an interval by bootstrap noise, never a point estimate). With `--baseline emcts` the same
command measures every arm against plain EMCTS (the retrospective's section 3.3).
