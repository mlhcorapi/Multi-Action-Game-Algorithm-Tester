# TBETS vs EMCTS on a scenario portfolio

**Headline: TBETS beats EMCTS at a matched budget, in both currencies, at every level tested.
Turning TBETS's rollouts on reverses that and makes it worse than EMCTS.**

Reproduce with:

```bash
java -cp "out;lib/*" core.data.exp.BuildV6Portfolio test_results/tbets_vs_emcts/_portfolio 12 20 20
```
```bash
java -Xmx12g -cp "out;lib/*" core.data.exp.TbetsVsEmctsRunner test_results/tbets_vs_emcts 12 5 40000 80000
```
```bash
java -Xmx8g -cp "out;lib/*" core.data.exp.TbetsVsEmctsRunner test_results/tbets_vs_emcts 1 3 1000 3000 --time
```

---

## 1. Design

**Portfolio.** 40 captured mid-game positions built by `BuildV6Portfolio` under the v5/v6 rules —
20 single-player (EMCTS vs DoNothing, SCORE mode, capture ticks 10–30) and 20 two-player (EMCTS vs
EMCTS, CAPITALS, ticks 15/25). Branching factor across the set runs from 79 to 279 actions.

**Protocol.** Isolation-phase style, the same one `IsolationPhaseRunnerV6` uses. Each arm is handed
`scenario.state.copy()` with the same seeded RNG, plans one turn, and plays it. Paired on
`(scenario, visit)` via `IsolationPhaseRunner.visitSeed`. 5 visits per scenario in the compute arms,
3 in the (much slower, single-threaded) wall-clock arms.

**Metric — `PostTurnValue`, and this matters.** The runner plays the agent's whole turn out and
scores the resulting state itself, with a heuristic it owns:
`evaluateState(stateAsHandedOver, stateAfterTheTurnActuallyPlayed)`.

It deliberately does **not** use each agent's own `FinalSequenceValue`, because those are not
commensurable. With rollouts on, a TBETS node's value is the heuristic *two turns* downstream past a
scripted continuation; an EMCTS node's value is the state one turn out. The two-turn state has had
another round of production and score accrue to it, so it reads higher for reasons unrelated to the
quality of the turn chosen. An earlier draft of this comparison used `FinalSequenceValue` and
reported rollouts winning by a wide margin; that result was an artifact of the scale difference and
is withdrawn. `PostTurnValue` also charges an agent for a cached plan that breaks against the real
state, which `FinalSequenceValue` does not.

**Unit of analysis is the scenario, not the turn.** Visits within a scenario are repeated measures on
one position. Pooling them as if they were independent would shrink the standard error by √visits
that the data has not earned. Paired differences are averaged within a scenario first; the test runs
across the 40 scenarios. The per-turn figure is printed under each row, labelled, for contrast.

**Budget fairness.** Both arms run at the same ceiling in the same currency, and both *exclude*
initialization from it — EMCTS always does (its greedy 1-ply root is never charged), so TBETS was
configured to match rather than being charged for a phase its opponent gets free. Initialization cost
is logged separately for both. As it turns out TBETS initializes ~5× *cheaper* than EMCTS (347 vs
1690 units), so this convention, if it favours anyone, favours EMCTS.

---

## 2. Results

### TBETS (shipped defaults) vs EMCTS

Positive favours TBETS. n = 40 scenarios.

| Budget | Mean Δ | SE | t | p | Median Δ | Scenarios won |
|---|---:|---:|---:|---|---:|---:|
| 40 000 compute-units | **+216.7** | 51.0 | +4.25 | <0.001 | +199.2 | 32/40 (80%) |
| 80 000 compute-units | **+251.4** | 51.8 | +4.85 | <0.001 | +245.0 | 29/40 (73%) |
| 1 000 ms of search | **+128.0** | 58.3 | +2.20 | <0.05 | +80.0 | 27/40 (68%) |
| 3 000 ms of search | **+173.3** | 50.5 | +3.43 | <0.001 | +94.3 | 26/40 (65%) |

Holds on both portfolio halves separately at every level except one: single-player at 1 000 ms is
+131.8 (t = +1.29, n.s.), the only cell that fails to reach significance.

### TBETS with rollouts vs EMCTS

| Budget | Mean Δ | SE | t | p | Scenarios won |
|---|---:|---:|---:|---|---:|
| 40 000 compute-units | **−145.2** | 39.5 | −3.68 | <0.001 | 6/40 (15%) |
| 80 000 compute-units | **−162.4** | 38.5 | −4.22 | <0.001 | 5/40 (13%) |
| 1 000 ms of search | **−189.2** | 46.3 | −4.09 | <0.001 | 5/40 (13%) |
| 3 000 ms of search | **−162.0** | 38.4 | −4.22 | <0.001 | 4/40 (10%) |

### What each arm is doing (80 000 compute-units)

| Arm | PostTurn | SearchVal | Actions/turn | Evals | Unique seq | Search CU | Init CU | ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| EMCTS | 8181.6 | 8189.9 | 14.8 | 1138 | 1054 | 80145 | 1690 | 10029 |
| TBETS | **8433.0** | 8449.4 | 16.8 | **4602** | **4602** | 80008 | **347** | 13463 |
| TBETS-rollouts | 8019.2 | **8962.6** | 16.4 | 1718 | 1718 | 80023 | 986 | 20649 |

---

## 3. Reading the result

**Why TBETS wins.** It converts budget into breadth. At 80 000 units it evaluates **4602 candidate
turns to EMCTS's 1138** — 4× more — because its default action policy samples uniformly, while
EMCTS's greedy repair spends a `copy + advance + evaluate` on *every feasible action* each time it
replaces one. TBETS also carries no duplicates (unique = evals exactly, versus ~7% duplicates for
EMCTS), because it rejects an action-identical sibling at insertion. A secondary effect worth
checking further: TBETS commits ~2 more actions per turn (16.8 vs 14.8), so it is using more of the
turn it is given.

**Why the rollout arm loses, and it is not just cost.** It produces the **highest search value of any
arm and the lowest delivered turn**. That gap is the whole story: it is optimising something other
than what it hands over. Ranking candidate turns by the heuristic two turns downstream of a
*scripted* continuation means two turns of accrual swamps the differences between the candidates
being ranked — and with the cheap script (`rolloutGreedy = false`) that continuation is random, so
the ranking is close to noise. The expensive script is a better proxy but cannot afford to run at
these budgets. This is a sharper reason to leave rollouts off than the cost argument the port
originally gave.

**The wall-clock caveat, and why the arm exists.** Compute-units price `advance`, `copy` and `eval`
and nothing else. TBETS spends ~34% more wall-clock per compute-unit than EMCTS (13463 ms for 80008
units against 10029 ms for 80145), so some of its work — `allGoodActions` feasibility scans, the
per-node sequence-key strings — is not being charged. That is why the wall-clock arms were run at
all. **The win survives**, at roughly half to two-thirds the margin: +128 and +173 rather than +217
and +251. Both readings are reported above; neither alone is the answer.

---

## 4. Threats to validity

- **The portfolio is EMCTS-sourced.** Positions come from games played by EMCTS agents, because that
  is the portfolio this project builds and reusing it keeps these numbers comparable with v5/v6. They
  are ordinary mid-game states, not states tuned to a search, but the distribution is EMCTS's. This
  cuts *against* the reported result rather than for it — the asymmetry favours EMCTS, and TBETS won
  anyway.
- **One turn, not one game.** This measures planning quality on a fixed position. It does not measure
  whether a better turn compounds into a won game — turn quality and game outcome can come apart, and
  nothing here rules that out. A win-rate study is the separate, much more expensive experiment;
  earlier probing here put the per-game score standard deviation at roughly 1000 points, which is why
  it was not attempted at this sample size.
- **One heuristic.** Everything is measured with `TribesSimpleHeuristic`. Both arms optimise it and
  both are scored by it, so the comparison is internally fair, but "better under this heuristic" is
  not the same as "better play".
- **40 scenarios, 5 visits.** Adequate for the effect sizes seen (t between 2.2 and 4.9), thin for
  the per-half breakdowns (n = 20 each).
- **`maxPlies = 1`.** TBETS ran one ply deep, which is its shipped default and the only setting that
  is well defined under fog of war. Its tree machinery — progressive widening, UCB, minimax backup —
  is therefore inert; what was measured is TBETS's evolutionary layer. That is a fair test of the
  agent as shipped, not of TBETS at full depth.

---

## 5. What follows

1. **Take the wall-clock gap seriously.** ~34% of TBETS's time is invisible to the compute meter.
   Either meter it (charge `allGoodActions`) or cut it — the per-node `seqKey` string is built for
   every evaluated node and is the obvious candidate.
2. **Do not pursue rollouts as implemented.** If revisited, change the *objective*, not the budget:
   score a rollout as a difference against a common baseline continuation so the accrual term
   cancels.
3. **A win-rate confirmation** on the v6 full-games harness, now that there is a per-turn effect worth
   confirming.
4. **Re-run the bias sweep against this baseline.** `CATEGORY_WEIGHTS` and `LEARNED_DELTA` were
   inconclusive on score in early probing; the paired per-turn protocol used here is sensitive enough
   to separate them, which the score-based probes were not.
