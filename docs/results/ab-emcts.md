# AB-EMCTS against the reference algorithms: the result

The pre-registered experiment ([`plans/ab-emcts-vs-oep.md`](../plans/ab-emcts-vs-oep.md)), run on the R7615
on 2026-11-27 ([the run](../../games/tribes/results/ab-emcts-vs-oep/r7615/README.md)), and decided by the
rule that file fixed beforehand.

**In short:** AB-EMCTS's advantage over EMCTS with exhaustive repair survives MAGAT's rules (H3), and the
learned table is what causes it (H4). Against the other families, it beats TBETS (H2) and matches OEP, the
strongest published algorithm, at 500 and 1000 ms, beating it only at 5000 ms (H1, unresolved). Its
advantage grows with the budget against every arm.

## The decision

On the holdout half (200 positions, confirmatory), `comma-z0-coarse` minus the other arm, on the pairs
where neither plan ends the game: the mean difference in heuristic value with its scenario-clustered
bootstrap 95% interval (4,000 resamples), **bold** where it excludes zero, and the budget multiplier (how
much budget the other arm would need to match it; `≥` where the crossing lies past the top level, a lower
bound).

| Hypothesis | 9,500 units (500 ms) | 20,000 (1000 ms) | 101,500 (5000 ms) | Verdict |
|---|---|---|---|---|
| H1 against `oep` | +23 [−22, +69], 1.1× | +3 [−37, +45], 1.1× | **+172 [+127, +221]**, ≥10.0× | **unresolved** |
| H2 against `tbets` | +39 [−4, +85], 1.4× | **+88 [+49, +129]**, 2.3× | **+207 [+159, +255]**, ≥7.4× | **supported** |
| H3 against `emcts-comma` | **+82 [+64, +101]**, 2.3× | **+151 [+124, +178]**, 3.5× | **+242 [+204, +282]**, ≥7.7× | **supported** |
| H4 against `comma-z0-random` | **+82 [+66, +99]**, 2.3× | **+152 [+125, +182]**, 2.9× | **+207 [+172, +245]**, ≥4.3× | **supported** |

The rule: supported when the difference is positive with its interval excluding zero at two levels or
more and the wall-clock re-score agrees in sign there; refuted when negative so at two or more; unresolved
otherwise. The wall-clock re-score (each arm re-scored at the seconds it actually spent) agrees in sign
wherever the interval excludes zero, and its own interval excludes zero there too: no verdict rests on
units alone. Per unit, `comma-z0-coarse`, `emcts-comma`, `comma-z0-random` and `oep` took the same time
as `emcts` to within 4%; `tbets` took 7-11% less, which its re-score accounts for.

**The development half** (200 positions, exploratory, where the thesis chose the champion) gives the same
four verdicts: H1 significant only at 101,500 (+166), H2 at 20,000 and 101,500 (+64, +195), H3 and H4 at
every level (+79 to +266). Every number of both halves is in
[`decision.csv`](../../games/tribes/results/ab-emcts-vs-oep/r7615/decision.csv).

## What it says

- **The thesis's result holds under MAGAT's rules (H3).** With every operation priced, the initialization
  paid from the budget and a hard stop inside generations, AB-EMCTS is worth 2.3× [1.9×, 2.7×] the budget of
  the same search with exhaustive repair at 9,500 units and 3.5× [2.7×, 4.4×] at 20,000, both inside the
  measured levels, and at least 7.7× at 101,500. The thesis measured 5.4× at 80,000 units of its own
  currency.
- **The learned table causes it (H4).** The control makes the same repair at the same cost with an
  arbitrary legal action. It is no better than plain `emcts` at 9,500 and 20,000 (0.9× to 1.1×), and
  `comma-z0-coarse` beats it by about as much as it beats `emcts-comma`.
- **Against TBETS (H2)** AB-EMCTS wins at 1000 and 5000 ms; at 500 ms the difference (+39) is short of
  significance.
- **Against OEP (H1)** it is level at 500 and 1000 ms (AB-EMCTS wins 48% and 49% of the pairs) and
  clearly ahead at 5000 ms (68%). The pre-registered rule asks for two levels, so H1 is
  unresolved: not supported, and not refuted.
- **The advantage grows with the budget** against every arm: the table learns from every mutation of the
  turn, so a longer turn gives it more to learn from.
- **Against the baseline `emcts`**, on the holdout half: `comma-z0-coarse` 2.7×, ≥5.9× and ≥27.7× at the
  three levels; `oep` 2.1×, ≥5.5×, ≥4.1×; `tbets` 1.7×, 2.2×, ≥2.8×. At 5000 ms AB-EMCTS is the best arm in
  the experiment. Comma selection alone (`emcts-comma`) only helps at 101,500 (≥1.9×).

## The gates

| Gate (pre-registration, section 3) | Outcome |
|---|---|
| Equivalence of the three arms with the engine's agents, under the engine's rule | 256 of 256 cells each on the laptop, mechanism counters included (PRs #41, #42); CI and the shakedown re-run it on every change. The R7615's gate cell asserts it before the experiments cell; that bundle was not brought back. |
| Audit: at most 25% of a turn unaccounted for | at most 0.12%, in the calibration's own audit of every new cell |
| Spend: a mean turn at most 1.10× its level | at most 1.0017×, every arm, level and half |
| Calibration | COMPLETE; the check against the published currency −0.4%, the self-check −0.7% |
| Reproduction (the amendment of 2026-11-18) | the reference arms' 44,000 turns identical to the published run's in every field but the times |
| The package | imports VERIFIED elsewhere: all 48 leaves re-play turns field for field, and the report's 84 paired means follow from the turns |

## Deviations

None after the run started: the spec the run read is the committed file, byte for byte (sha256
`20190ce0...bb`). Before any turn was played, the pre-registration was amended twice, each dated in the
file: the comma arms make one greedy initial plan, not five; and every arm is played in the run, so the
wall-clock re-score compares turns timed together.

## Limits

- **One game and one machine.** Tribes, on the R7615. The units transfer between machines (the
  [cross-machine result](cross-machine.md)), but no other game has been run.
- **The holdout half had been read once before**, by the thesis, for H3 and H4. H1 and H2 are new questions,
  and this is their first test.
- **Four hypotheses, no correction across them**, as pre-registered: every interval is reported here and in
  `decision.csv`.
- **The multipliers at 101,500 are extrapolated** past the top level, so they are lower bounds.
- **Mean differences are heuristic value**, Tribes' own evaluation of the position a plan reaches; the
  multipliers are the readable size.
- **The turns ran 54 at a time.** Seconds measured that way share the machine; every arm shared it alike,
  and no turn depends on it.

## Reproducing it

- **The analysis:** `python magat.py analyze --run games/tribes/results/ab-emcts-vs-oep/r7615 --experiment
  experiments/tribes/ab-emcts.json --out <dir>` rebuilds the report's tables byte for byte; add `--baseline
  oep` (or `tbets`, `emcts-comma`, `comma-z0-random`) for a contrast's wall-clock re-score and multiplier.
- **The turns:** `python magat.py check replay --game tribes --leaf <leaf> --weights
  games/tribes/calibration/r7615-ab-emcts/compute_weights.properties` re-plans any of them here, field for
  field.
- **The experiment:** [`plans/ab-emcts-run.md`](../plans/ab-emcts-run.md) runs it again on a machine of
  your own.
