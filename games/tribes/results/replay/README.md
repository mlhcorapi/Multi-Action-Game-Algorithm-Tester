# Replay fixture: published holdout turns

`Comma_Off/80000cu/` holds 20 turns of the worked result's holdout leaf `emcts-comma@80000`
(the thesis tree's `Comma_Off` at 80,000 units), exactly as the R7615 wrote them on 2026-09-02 during
stage F: their `turn_telemetry.csv` rows and every `results.csv` row of those turns. The rows are the 20
that `magat check replay --turns 20` picks from the full 1,600-turn leaf, evenly spread over the turns
ordered by scenario and visit.

`magat check replay` re-plans them from the portfolio's scenario files under the published weights and
asserts that every reproducible field agrees: initial and final values, forward-model calls, priced
units, nodes, unique sequences, duplicates, average node value, and the mutation counters. The
directory keeps the thesis tree's leaf layout, so `tools/legacy-arms.json` maps `Comma_Off` to
`emcts-comma` and the budget is read from `80000cu`.

```bash
python magat.py check replay --game tribes --leaf games/tribes/results/replay/Comma_Off/80000cu --turns 20
```

On 2026-10-05 an Intel Core i9-14900HX laptop (Windows 11, JDK 26) reproduced all 20 from both the
published scenario files and its own rebuilt portfolio. The full leaf, and the `comma-z0-coarse` and
`comma-rand15` leaves of the same stage, reproduced 20 of 20 too; the complete stage trees are imported
with the worked result (milestone 6).
