# The `ab-emcts` branch: MAGAT with the AB-EMCTS family

This branch is MAGAT as it stood at PR #16 (`da8b39b`, tag `ab-emcts-archive`). It also holds the R7615's
33-arm currency at the published levels, measured on 2026-10-11 (`games/tribes/calibration/r7615-time/`).

MAGAT v0.1 on `main` was trimmed to four familiar algorithms (MCTS, EMCTS, OEP, TBETS) and the
compute-unit currency. Everything below was set aside here, not deleted, for the AB-EMCTS paper. To
re-add the family on top of `main`, follow `docs/ARCHIVE.md` there.

## What is here and not on main

- **The AB-EMCTS arms.** 31 `QdeltaEmcts` arms and `emcts-comma`:
  - `core/src/magat/agents/emcts/QdeltaEmcts.java`, `QdeltaArms.java`, `QdeltaParams.java`, `ActionTable.java`;
  - the extra `Selection` modes;
  - `python magat.py arms --game tribes` lists every arm with its parameters.
- **Their oracles.** `games/tribes/src/magat/games/tribes/oracle/ThesisQdeltaOracle.java` (the thesis's `Stage1BiasedAgent`) and `ThesisEmctsOracle` with comma selection, both against the engine fork's thesis agents.
- **Probes:**
  - `probe support` (`SupportProbe`);
  - `check structure` (`StructureCheck`, `experiments/tribes/structure.json`);
  - `SearchAgent.support()`, `AgentFactory.attributionControl()`;
  - `Game.actionClass` with `Granularity`, which the learned tables need.
- **The method around them:**
  - attribution controls and sweeps with null arms;
  - the holdout read once (`HoldoutLedger`, frozen choices, preregistration);
  - the specs `experiments/tribes/ab-emcts-{dev,holdout}.json` with `ab-emcts-frozen.txt`.
- **The worked result:**
  - the thesis's AB-EMCTS line, imported (`games/tribes/results/ab-emcts`, with the thesis documents in `thesis/`);
  - its write-up `docs/results/tribes-ab-emcts.md`;
  - the analysis that reproduces it (`analysis/magat_analysis/legacy.py`, the gain multiple, oracle regret, anytime, diversity and replication measures, `analysis/tests/test_worked_result.py`).
- **Currencies:**
  - the thesis R7615 currency, version 1 (`games/tribes/calibration/r7615`);
  - the laptop's at the old levels 40k/80k/400k (`games/tribes/calibration/laptop-i9-14900hx`);
  - the version-1 format support and its merge factor;
  - the outside-budget initialization rule as a spec and currency option;
  - `--under-load`.
- **Tools:** `tools/import_r7615.py`, `tools/legacy-arms.json`, `tools/package_worked_result.py`.
- **Documents:**
  - the founding documents (`MAGAT/BRIEF.md`, `MAGAT/STARTING_PROMPT.md`);
  - the full design history (`MAGAT/DESIGN.md`, 1,931 lines with every dated amendment);
  - `ALGORITHMS.md`.

## The r7615-time currency

Prices every arm above at 9,500 / 20,000 / 101,500 units with the initialization paid from the budget.
All four sections passed, and both checks and every gate passed. `games/tribes/calibration/r7615-time/ORIGIN.md`
has the record.

It was produced by the notebook's calibration cells, which ran:

```bash
python magat.py calibrate-controlled --name r7615-time --game tribes --portfolio ~/magat_runs/portfolio/tribes --format 2 --batch 8 --other-cpus 4.0 --wait-quiet 30 --soak-minutes 0 --levels 9500,20000,101500 --section 1
```

That command ran once for each section (1 to 4), then with `--section checks`.

**Carry this forward: the comma arms overspend at 9,500 (about 2×) and at 20,000 (about 1.3×).** Their
five greedy initial plans can't be interrupted.
