# AB-EMCTS against the reference algorithms: pre-registration

**Written 2026-11-18, before any code for it exists and before any of its turns are played.** This file
is committed first and not edited after the run starts. A change before the run is a new commit that
says what changed and why; anything decided after the data exists is reported as exploratory.

*Corrected 2026-11-18, before any code or turn: the comma arms make one greedy initial plan, as `emcts`
does, not five. Their archived overspend came from generations of five children that could not stop
when the budget ran out (the engine's `ExperimentalEMCTSAgent` and the archived port both read so).
Sections 1 and 3 say so now; nothing else changed.*

## 1. The question

The thesis line (archived at the tag `ab-emcts-archive`, [`ARCHIVE.md`](../ARCHIVE.md)) found that
`comma-z0-coarse`, EMCTS on (1,5) comma selection with zero-evaluation repair directed by a coarse
learned action-value table, beats the same search with exhaustive repair (`emcts-comma`) on 200 held-out
Tribes positions. It was worth about **5.4× the compute budget** [4.3×, 6.5×] and **3.2× the wall-clock
time** [2.7×, 3.8×] at 80,000 units of the thesis's own currency.

Two things are open:
- **It was never compared with the algorithms outside the EMCTS family.** In MAGAT 1.0, `oep` and
  `tbets` beat `emcts` at every published level, `oep` by budget multipliers of about 2.4× to 6.8×.
- **It was measured under the thesis's rules.** A version-1 currency left the arms' own operations free,
  so the zero-evaluation arms used 17-22% more time per unit; the greedy initial plan was paid outside the
  budget; and a generation of five children could not be cut short when the budget ran out.

This experiment answers both under MAGAT 1.0's rules.

## 2. Hypotheses

On the **holdout half** (section 5), at each published level, on the pairs where neither plan ends the
game:

| | Hypothesis | Contrast (A − B) |
|---|---|---|
| H1 | `comma-z0-coarse` finds better plans than `oep` | `comma-z0-coarse − oep` > 0 |
| H2 | `comma-z0-coarse` finds better plans than `tbets` | `comma-z0-coarse − tbets` > 0 |
| H3 | the thesis's result survives MAGAT 1.0's rules | `comma-z0-coarse − emcts-comma` > 0 |
| H4 | the learned table itself causes it | `comma-z0-coarse − comma-z0-random` > 0 |

**Expectations, written down so they can be wrong:** H3 and H4 hold, as they did in the thesis, at a
smaller size, since the table's work is now paid for. H1 and H2 are genuinely open: no prior result
bears on them.

## 3. The arms

Three arms are added; the other five come from the published reference run.

| Arm | What it is | Its archived definition |
|---|---|---|
| `emcts-comma` | EMCTS with (1,5) comma selection and exhaustive greedy repair: the thesis's baseline | `EmctsFactory.comma(5)` |
| `comma-z0-coarse` | the same search, with each repair taking the argmax of a learned action-value table at coarse keys: zero candidate evaluations. Insertion uniform (ε = 1), removal credited, live table, shrinkage m = 5 | `QdeltaArms`: `arm(1.00, MID, COARSE, removal, live, ZERO_EVAL, 0, COMMA, 5)`, frozen as the champion in `ab-emcts-frozen.txt` |
| `comma-z0-random` | the same zero-evaluation repair with the table's ranking replaced by an arbitrary legal action: the attribution control | `arm(1.00, MID, MID, removal, live, ZERO_EVAL_RANDOM, 0, COMMA, 5)` |
| `mcts`, `emcts`, `oep`, `tbets`, `random` | MAGAT 1.0's reference algorithms and floor | the published run `games/tribes/results/reference/r7615/` |

No parameter is tuned for this experiment. The champion and its control were frozen on the development
half by the thesis, before its holdout read.

**Ported, not re-designed.** The arms are restored from the archive and fitted to 1.0's rules, with
exactly these changes:
1. Every operation an arm declares is priced (a version-2 currency), the learned table's included.
2. The initialization, one greedy plan as in `emcts`, is paid from the budget and stops when it runs
   out.
3. The search stops hard at its budget, as 1.0's `emcts` does, **including inside a generation**: a
   comma generation of five children stops at the child the budget runs out in. In the archive it
   could not, and the comma arms overspent about 2.2× at 9,500 units.

**Gates, all passed before any run** (a failure is fixed and the gate run again, never waived):
- **Equivalence, under the engine's own budget rule.** `emcts-comma` against the engine's
  `ExperimentalEMCTS` with comma selection, and `comma-z0-coarse` and `comma-z0-random` against the
  thesis's `Stage1BiasedAgent`, field for field, as `emcts` and `tbets` are gated in 1.0.
- **Audit:** each arm's declared operations and forward model account for at least 75% of its turn (at
  most 25% unaccounted).
- **Spend:** each arm's mean turn spends at most 1.10× its level at every level, initialization included.

## 4. Levels, currency and machine

- **Levels:** the published 9,500 / 20,000 / 101,500 units (500 / 1000 / 5000 ms of thinking on the
  R7615), with 8 / 8 / 6 repetitions, as the reference experiment. These are not the thesis's levels
  (40,000 / 80,000 / 400,000 in its own currency), so sizes are not compared one to one with the thesis's.
- **Currency:** the three arms priced on the R7615 **into a copy of the published `r7615-time`
  currency**, which keeps every published price. The reference arms' published turns are therefore the
  ones this run would play, and `experiment run` takes them after re-playing a sample. Only the three new
  arms are played.
- **Machine:** the R7615, in one calibration session, and the run on the same machine.

## 5. Positions, and which half decides

The run plays all 400 published positions, as the reference run does.

- **The holdout half (200 positions) is confirmatory.** The champion was chosen on the development half,
  so a development result carries the winner's curse.
- **The development half is reported beside it,** as exploratory.

The thesis has read the holdout half once already, for H3 and H4. H1 and H2 are new questions, and no
choice is made on this read, so it remains a clean test of them.

## 6. The decision rule

For each hypothesis, on the holdout half, using MAGAT's analysis as released in 1.0:
- **Supported** if the paired difference is positive, with a scenario-clustered bootstrap 95% interval
  excluding zero (4,000 resamples), **at two or more of the three levels**, on the pairs where neither
  plan ends the game; **and** the wall-clock re-score agrees in sign at those levels.
- **Refuted** if the difference is negative with the interval excluding zero at two or more levels.
- **Unresolved** otherwise.

Four hypotheses are tested with one rule each, and no correction across them is claimed: every
interval is reported, so readers can apply their own. The budget multipliers against `emcts` and
against `emcts-comma` are reported with their flags (extrapolated or inside the levels), but they do
not decide anything.

## 7. What is reported, whatever the outcome

- every hypothesis's verdict, with its intervals at every level, on both halves;
- the budget multipliers, the wall-clock re-score and the spend of every arm;
- every gate's outcome, and every deviation from this file, with its date and reason;
- the run, its currency and its report, exported as a package anyone can import and re-play.

If H1 or H2 is refuted, that is the result, and it is reported as plainly as a supported one.

## 8. The order of work

1. This file.
2. MAGAT's contract gains `Game.actionClass` with its granularities, which the learned table reads,
   as an optional capability, with Tribes's implementation.
3. EMCTS gains comma selection, fitted to section 3's rules; `emcts-comma` is gated.
4. The AB-EMCTS arms are restored, gated, audited and tested.
5. The experiment's spec and calibration recipe.
6. On the R7615, by the owner: the calibration session, the gates, then the run.
7. The analysis, against section 6.
