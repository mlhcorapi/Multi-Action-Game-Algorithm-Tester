# What v0.1 set aside

MAGAT v0.1 is the compute-unit currency and four algorithms people already know. MAGAT grew out of a
study of the AB-EMCTS family of algorithms; that work is kept, recoverable, for a later paper built
on MAGAT:

- **the tag `ab-emcts-archive`**: main at `da8b39b`, the last commit before the trim;
- **the branch `ab-emcts`**: the same commit, plus the R7615's 33-arm currency at the published levels
  (`games/tribes/calibration/r7615-time/`, with its record) and `ARCHIVE_NOTES.md`.

Every path below is a path on the tag.

## What was removed

| Area | What | Where it was |
|---|---|---|
| Algorithms | `QdeltaEmcts` and its 31 arms (`QdeltaArms`, `QdeltaParams`), `ActionTable`, `emcts-comma` and EMCTS's other selection modes (comma, comma with random parents, drift, legacy, more than one child per generation) | `core/src/magat/agents/emcts/` |
| Oracles | the oracle for the `QdeltaEmcts` arms | `games/tribes/src/magat/games/tribes/oracle/ThesisQdeltaOracle.java` |
| Contract and protocol | `Game.actionClass` with `Granularity` (law L8 then), `AgentFactory.attributionControl()`, `SearchAgent.support()`, the spec's `"initialization"` policy | `core/src/magat/game/`, `core/src/magat/agent/` |
| Probes | `probe support`, `check structure` with `experiments/tribes/structure.json`, `check overhead`'s comparison of the decorator with the engine's hooks, replays of the earlier leaf format | `core/src/magat/probe/` |
| Experiment workflow | sweeps with their nulls, attribution controls, pre-registration, frozen choices, the holdout ledger, `portfolio ledger`, `run --frozen` | `core/src/magat/run/`, `core/src/magat/portfolio/HoldoutLedger.java` |
| Currency | version-1 weights files, `calibrate --format`, `calibrate --under-load`, and the currencies `games/tribes/calibration/r7615/` (levels 40,000 / 80,000 / 400,000) and `laptop-i9-14900hx/` | `core/src/magat/calibrate/`, `core/src/magat/meter/` |
| Results | the AB-EMCTS line re-analysed (`games/tribes/results/ab-emcts`, `docs/results/tribes-ab-emcts.md`, `experiments/tribes/ab-emcts-*.json`) and its replay fixture (`games/tribes/results/replay`) | |
| Analysis | `import-legacy` (`legacy.py`); the gain multiple, oracle regret, anytime curves, diversity and replication measures, with their tests | `analysis/magat_analysis/` |
| Tools | `import_r7615.py`, `legacy-arms.json`, `package_worked_result.py`, `make_engine_branch.py` | `tools/` |
| Documents | `ALGORITHMS.md` (the 33 arms), `MAGAT/BRIEF.md`, `MAGAT/STARTING_PROMPT.md`, and the full design history `MAGAT/DESIGN.md` | |

## A finding kept for the paper

`emcts-comma` overspends at small levels: about 2.2× its budget at 9,500 units and 1.3× at 20,000,
because its five-plan greedy initialization cannot stop when the budget runs out. Plain `emcts` spends
1.05×, 1.01× and 1.00× at 9,500 / 20,000 / 101,500. `ARCHIVE_NOTES.md` on the branch has the table.
In v0.1 every algorithm's initialization stops when the budget runs out.

## Re-adding an algorithm family

1. Restore its classes from the tag (`git checkout ab-emcts-archive -- <paths>`) and bring them to the
   v0.1 contract: no `actionClass`, and an initialization paid from the budget that stops when it runs
   out.
2. Register its arms in `TribesAgents.registry()`, and any arm set in `TribesAgents.armSets()`.
3. Restore its oracle, register it in `TribesAgents.oracles()`, and gate the port with `magat check
   equivalence --arms <ids>`.
4. Restore the probes and measures it needs, with their tests.
5. Restore or write its specs in `experiments/tribes/`.
6. Run `magat check audit --arms <ids>`, then price it at the published levels with `magat calibrate
   --arms <ids>`, which merges into a currency by ratio. The branch's R7615 currency already prices all
   33 arms at 9,500 / 20,000 / 101,500, on engine `828b130`.
