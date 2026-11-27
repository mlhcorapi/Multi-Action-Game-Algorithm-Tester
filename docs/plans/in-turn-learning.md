# In-turn learning on comma-EMCTS: a research plan

**Status: a plan for review (2026-11-27), not yet a pre-registration.** Once its choices are agreed, its
confirmatory stage (E3) is frozen as a pre-registration of its own, as
[`ab-emcts-vs-oep.md`](ab-emcts-vs-oep.md) was, before any of its turns are played.

## 1. Where things stand

The AB-EMCTS experiment ([`results/ab-emcts.md`](../results/ab-emcts.md)) established, under MAGAT's
rules:
- A table of action values learned within the turn, read at coarse keys to make repair evaluation-free,
  makes comma-EMCTS worth 2.3× to ≥7.7× the budget of the same search with exhaustive repair (H3).
- The learned ranking causes it (H4).
- AB-EMCTS beats TBETS, and draws level with OEP at 500 and 1000 ms but beats it at 5000 ms (H1
  unresolved).
- **Its advantage grows with the thinking time** against every arm.

The thesis line ([`ARCHIVE.md`](../ARCHIVE.md), tag `ab-emcts-archive`) had already explored part of the
design space, on its own currency:

| Question | What it found |
|---|---|
| Repair keys | coarse beat mid (+99), mid beat fine (+122); a fine table was no better than none on the holdout (+3.8, ns) |
| Insertion biased by the table | ε-greedy, the best-estimated action 90% of the time (ε = 0.10), hurt: −65 at 400k. The duplicate share rose from 6% to 23%, so it collapsed the search's diversity. Among biased arms, coarse insertion keys beat mid (+45) and mid beat fine (+57) |
| Shortlist repair (evaluate the table's top 15) | good (+200), but below zero-evaluation repair (+244) |
| Where the gain comes from | mostly from repair becoming nearly free: exhaustive repair took 82% of `emcts-comma`'s units |

Three things were never tested:
- **Learners other than a table of independent classes.** Everything was counting with shrinkage toward
  the turn's mean.
- **Mutation biased without losing diversity.** The thesis tried one rule, ε-greedy insertion. It never
  tried a rule that keeps exploring what it has not tried, a guard against duplicate children, or a learned
  choice of which position to mutate.
- **Long thinking.** Its top level was 400,000 of its own units, about 20 s, and it was read only as an
  extrapolation.

How much a learner has to learn from in one turn, on the published AB-EMCTS run (median evaluated
children per turn, `comma-z0-coarse`):

| 500 ms | 1000 ms | 5000 ms | 30 s (extrapolated) |
|---|---|---|---|
| 172 | 412 | 1,863 | about 11,000 |

A few hundred samples suit a table. A model with many parameters cannot learn much from 172, so its test
belongs at the long levels.

## 2. Research questions

Each comes with the direction expected, written down so it can be wrong.

- **RQ1. Granularity over time.** Which key granularity makes the best evaluation-free repair, and does
  the best one become finer as the thinking time grows?
  *Expected:* coarse ≥ mid > fine at 500 and 1000 ms, as in the thesis. By 30 s mid catches coarse,
  because there are more samples per class. Fine stays worst: it is too sparse even then.
- **RQ2. Backoff.** Does a hierarchical table, in which a fine estimate shrinks toward its mid class, which
  shrinks toward its coarse class, use fine information without losing coarse's robustness?
  *Expected:* it matches coarse at 500 ms, and beats every single-granularity table at 5 s and 30 s.
- **RQ3. Function approximation.** Does an online neural network, given the same information as the
  tables, beat the best table with its own compute charged? At what thinking time does it start to pay?
  And does it pay more with richer, game-specific inputs (RQ3b)?
  *Expected:* it loses at 500 ms (cold start, and its cost), is level at 5 s, and wins at 30 s.
- **RQ4. How repair spends it.** Does a repair that evaluates the learner's best few candidates beat one
  that evaluates none, once the budget is long enough to afford it?
  *Expected:* a shortlist of 3 beats evaluation-free repair at 30 s only.
- **RQ5. Learning to mutate.** A mutation chooses a position in the plan and an action to put there, both
  uniformly in EMCTS. Can what the turn learns improve those choices, without collapsing the search's
  diversity as the thesis's ε-greedy insertion did?
  - **RQ5a. The inserted action.** Does a biased insertion that keeps exploring beat uniform insertion?
    The candidates are Thompson sampling from each class's estimate and uncertainty, and a milder
    ε-greedy than the thesis's. Is the duplicate share what decides it?
    *Expected:* ε-greedy loses again, with its duplicate share up. Thompson keeps duplicates near
    uniform's and beats it at 5 s and 30 s, but not at 500 ms. A guard that redraws duplicate children
    changes little on its own, since uniform insertion rarely duplicates (6%), but recovers part of
    ε-greedy's loss.
  - **RQ5b. The position.** Is where to mutate worth learning, from how often mutating each position
    improved the plan?
    *Expected:* a small gain. The positions are not alike: a change early in a plan moves everything
    after it.
  - **RQ5c. With repair.** Does a learned mutation add to evaluation-free repair, or do the two overlap?
    *Expected:* they overlap in part, so the combination gains less than the sum of the two. The thesis
    found such combinations anti-additive.
  - **RQ5d. With time.** Does a learned mutation need more data than a learned repair, and so pay only
    later?
    *Expected:* yes. Its gain over uniform grows with the thinking time faster than repair's did.
- **RQ6. The long run.** Does in-turn learning's advantage keep growing to 30 s, level off, or reverse? Does
  OEP, level at 500 and 1000 ms, fall behind, or catch up by 30 s?
  *Expected:* the advantage over `emcts-comma` keeps growing, but more slowly; the best learner beats OEP
  at 30 s.

## 3. The design space

Everything is built on comma-EMCTS, (1,5) comma selection, with uniform insertion unless stated. A learner
is credited as AB-EMCTS's is: every evaluated child's change in value goes to the action inserted, and,
negated, to the one removed.

**Learners**, from counting to function approximation:

| Id (working) | Learner | Estimate of an action's value change |
|---|---|---|
| `T-coarse` | table, coarse keys (= `comma-z0-coarse`) | shrunk class mean, toward the turn's mean |
| `T-mid`, `T-fine` | table, mid or fine keys | the same |
| `T-hier` | hierarchical table | fine mean shrunk toward its mid class, mid toward its coarse class, coarse toward the turn's mean |
| `L-classes` | linear model on the class identities | sum of learned coarse, mid and fine effects, fitted online |
| `N-classes` | small neural network on the class identities | as `L-classes`, plus their interactions (one hidden layer) |
| `N-features` | the network with game features too | `N-classes`, given the state and action features (RQ3b) |

`T-hier`, `L-classes` and `N-classes` form a ladder over the same information: counting with backoff,
additive regression, then nonlinear regression. That ladder isolates what each step adds.

**Repair** (RQ4):

| Id | What it does |
|---|---|
| `z0` (evaluation-free repair) | each replacement is the candidate with the best estimate; nothing evaluated (AB-EMCTS) |
| `short3` (shortlist repair) | the best 3 candidates are evaluated, and the best of them taken |

**Mutation rules** (RQ5), with `z0` repair:

| Id | The position | The inserted action | Duplicate children |
|---|---|---|---|
| `uniform` (control) | uniform | uniform among the legal actions (EMCTS) | kept |
| `guard` (control) | uniform | uniform | discarded and redrawn, up to 3 times: the guard alone, with no learning |
| `eps` | uniform | ε-greedy, ε = 0.5: half the insertions take the best-estimated legal action | kept |
| `thompson` | uniform | Thompson sampling: each legal action's class gets a draw from its estimate's posterior (its shrunk mean, variance shrinking with its count), and the highest draw is inserted | kept |
| `thompson+guard` | uniform | Thompson | redrawn |
| `site` | Thompson over positions, from the change mutating each position brought | uniform | kept |
| `thompson+site` | Thompson over positions | Thompson | kept |

- **The controls separate each ingredient.** `guard` is the duplicate guard without learning, so a gain
  from `thompson+guard` can be split between the two.
- **Thompson is the natural rule here.** The tables are already shrunk means with a count, which is a
  posterior. Its draws keep trying classes it has seen little of, which is the exploration ε-greedy lost.
- **Costs are charged.** A biased insertion scores every legal candidate, up to about 270 at the start of
  a Tribes turn. That is one lookup and one draw per candidate per mutation, priced like the table's own
  lookups. A redrawn child pays for its repair again.

**Controls:**
- `comma-z0-random`: the same repair with no learning;
- `emcts-comma`: exhaustive repair;
- `comma-z0-coarse`: the current champion;
- `oep` and `tbets`: the strongest other families.

## 4. The neural network

- **The framing.** Within a turn, each evaluated child says how much its inserted action changed the
  plan's value. Predicting that change from the action is an online regression, a contextual bandit.
  It is not multi-step reinforcement learning. Full RL, valuing an action by the later actions of the
  plan it enables (temporal-difference learning along the plan), would need a reward per step. That
  means evaluating every prefix of a plan, which costs evaluations the search would otherwise spend.
  It is a natural next step if the bandit form shows a network can learn in a turn. This plan does not
  try it.
- **The inputs:**
  - **`N-classes`:** the action's coarse, mid and fine classes, each hashed into a one-hot block (64
    buckets each, so three of 192 inputs are on), plus the position in the plan and the turn's progress.
    Every game with action classes provides these, so no contract change is needed.
  - **`N-features`:** adds features of the action and the state. Tribes would supply them through an
    optional `Game.actionFeatures(state, action)` capability with a law of its own (a fixed length, finite
    values, no change to the state), as `actionClass` is supplied. Examples: unit type, health, distance to
    the nearest enemy and city, terrain, the target's owner, and cost.
- **The network:** one hidden layer of 16 tanh units, and a linear output, the predicted change. Its
  weights start from the turn's seed, and it is trained by one stochastic-gradient step per credited
  action, with L2 regularization. It is pure Java with no library: about 200 lines, as MAGAT takes no
  dependencies.
- **Determinism.** Weights are initialized from the turn's random stream, updates are sequential, and
  every transcendental is `StrictMath`'s, so a turn re-plays field for field on any JVM. That keeps
  `check replay` and `check reproduction` valid for it.
- **Its cost is charged.** Its operations are declared and priced like the table's: `nn.features`,
  `nn.forward` (one candidate scored) and `nn.backward` (one update). A network that costs more than it
  earns loses, at equal budget. That is the comparison MAGAT's currency is built for.
- **For mutation,** Thompson sampling needs an uncertainty the network does not give. Its mutation use
  (a softmax over its predictions, or a small ensemble) waits for RQ3's answer.
- **Gate.** No original exists to gate it against. Its correctness rests on:
  - toy tests: it learns a planted signal, and a learning rate of zero leaves it at its prior;
  - determinism: the same visit gives the same turn;
  - the audit.

## 5. The 30-second level

- **Units.** The published levels are 9,500, 20,000 and 101,500 units for 500, 1000 and 5000 ms on the
  R7615, about 20 units per millisecond at 5000 ms, so 30 s is about 610,000 units. The level is measured
  on the R7615 the way the published ones were: single-threaded whole turns of `emcts`, with
  `check time` and a target of 30,000 ms, rounded to 500 units. Specs write it in units.
  `games/tribes/levels.json` is a published artefact and stays as it is.
- **Prices.** A price depends on the level: from 9,500 to 101,500, `emcts`'s advance moved 5% and `oep`'s
  8%. So the 30-second level gets prices of its own, never borrowed from 101,500's.
  - A currency today keeps the levels it was first measured at. So MAGAT gains one change: **a currency
    can gain a level**, measured on the same 16 positions as its others, with every existing line kept,
    as `--from` keeps them.
  - `r7615-ab-emcts`, extended this way, keeps every published price. The published turns at the three
    published levels then stay reusable.
- **Cost on the R7615.**
  - **Calibration** is single-threaded, so the long level is the expensive part: 54 turns of 30 s, about
    27 minutes per arm at that level, against about 6 minutes per arm for all three published levels
    together.
  - **The runs** are cheap: 54 workers play 200 positions × 2 repetitions × 30 s in about 4 minutes of
    turns per arm.
- **Repetitions:** 2 at 30 s. Positions, not repetitions, carry the precision, and a few repetitions
  already show the long-run behaviour.
- **What the analysis gains:**
  - The baseline's ladder reaches 30 s, so every multiplier at 5000 ms, all extrapolated today, becomes a
    measured one.
  - Each 30-second turn's 20 anytime checkpoints show its value at 1.5 s, 3 s, … within the long turn: a
    learning curve per arm. Its schedule is a 30-second turn's, not a shorter turn's, and the curves are
    read as that.

## 6. The experiments

The portfolio's development half is for exploring; a confirmatory half is read once, for the frozen
choices. Every spec plays only the arms whose turns do not exist yet, and takes the rest from published
runs. At 30 s nothing is published, so every arm compared there is played.

| | E1: learners (RQ1-RQ3) | E2: repair and mutation (RQ4, RQ5) | E3: confirmation (RQ1-RQ6) |
|---|---|---|---|
| Half | development | development | the confirmatory half (decision D2) |
| Arms played | `T-mid`, `T-fine`, `T-hier`, `L-classes`, `N-classes`; `N-features` only if `N-classes` passes E1's gate | on `T-coarse`, the current champion's learner: `short3`, and the mutation rules `guard`, `eps`, `thompson`, `thompson+guard`, `site`, `thompson+site` | a 2 × 2 of {`T-coarse`, E1's winner} × {uniform mutation, E2's winning rule}, `short3` if it won, plus `comma-z0-random`, `emcts-comma`, `oep` and `tbets` |
| Reused | `T-coarse`, `comma-z0-random`, `emcts-comma` and the reference arms at the published levels (the AB-EMCTS and reference runs) | the same | at the published levels, the published runs; on a fresh portfolio (D2), nothing: every arm is played |
| Levels | 500 ms ×4, 1000 ms ×4, 5000 ms ×3, 30 s ×2 | the same | 500 ms ×8, 1000 ms ×8, 5000 ms ×6, 30 s ×2 |
| Decides | which learners go to E3 (on dev: exploratory) | which repair and mutation rule go to E3 | the hypotheses, by the AB-EMCTS rule: CI excluding zero at ≥2 of 4 levels, no-terminal stratum, wall-clock agreeing in sign (for arms played in the same run) |

- **E1 and E2 share one R7615 session.** E2 builds on `T-coarse`, so it does not wait for E1's winner, and
  E3 then tests the combination. The thesis found coarse keys best for insertion as well as repair, so
  `T-coarse` is a fair base.
- **E3's 2 × 2 answers RQ5c directly.** Its four cells are the champion, the better learner alone, the
  better mutation alone, and both together. Their interaction is the additivity question, measured on
  turns no choice was made on.
- **Each arm's mechanism is reported beside its result.** That covers the duplicate share (duplicates over
  nodes), unique plans, the share of mutations that improve on their parent, how the mutated positions
  spread, and how varied the inserted classes are. A rule that wins with its diversity collapsed, or loses
  with it intact, is read with that in view.

- **E1's gate for `N-features`.** It is built only if `N-classes` is at least level with `T-hier` at 5000
  ms or 30 s on dev. Otherwise richer inputs to a model that cannot yet learn its classes are premature.
- **R7615 time:**
  - **E1 and E2, one session:** about 8 h of calibration. That is twelve new arms at four levels, about 33
    minutes each, and three controls at 30 s, 27 minutes each. It can be split into two sittings: the
    published levels first (about 1.2 h), then 30 s (about 6.8 h). Then about 1.5 h of runs.
  - **E3:** every arm is priced by then except the 2 × 2's combined cell, which needs about 33 minutes of
    calibration. Then about 1.5 h of runs per half.

## 7. What MAGAT needs first

Each is a PR of its own, before E1:
1. **The learners** (E1):
   - `T-fine`, restored from the archive;
   - `T-hier`;
   - `L-classes` and `N-classes`, with their priced operations;
   - toy tests and the audit.
   An arm set `@in-turn`.
2. **A currency gains a level:** `calibrate` (and `calibrate-controlled`) can price a level the file
   lacks, on its own 16 positions, keeping every line. With tests, and the shakedown.
3. **The 30-second level, measured:** `check time` on the R7615, recorded in a short results note. It runs
   with E1's calibration, in the same session sheet.
4. **E1's spec, calibration config and run sheet**, as #45 did for AB-EMCTS.

5. **The mutation rules and the shortlist repair:**
   - EMCTS gains hooks for the choice of position and of inserted action, and a duplicate guard. With no
     rule set they draw exactly plain EMCTS's random numbers, and its gate shows it.
   - `eps` and `short3` are gated against the engine's `Stage1BiasedAgent`, which implements both
     (`epsilon` with `insertKeys`, and `shortlistK`).
   - `guard`, `thompson` and `site` have no original, so they rest on toy tests, determinism and the audit.
   - New telemetry: how varied the inserted classes are, and the spread of mutated positions.

`N-features` comes after E1, and only if E1 calls for it.

## 8. Decisions for review

- **D1. Scope of the network.** This plan proposes the bandit form only: a regression on each action's
  value change. Full temporal-difference RL is left for after it. Is that the right first step?
- **D2. Which half confirms.** The published portfolio's holdout half has been read twice: by the thesis
  (H3, H4) and by the AB-EMCTS experiment. A fresh portfolio, 400 new positions captured the way `v1` was,
  would give E3 a confirmatory set nothing has seen. The cost: about an hour, a new published portfolio
  beside `v1` (never replacing it), and playing every E3 arm on it, since no published turns exist there.
  The currency carries over: a run on another portfolio only warns that its prices were measured on `v1`'s
  positions, and prices are the machine's, not the positions'.
  This plan recommends it.
- **D3. The level.** 30 s with 2 repetitions, at about 610,000 units measured on the R7615, as above?
- **D4. E2's arms.** Are `short3` and the six mutation rules the right set? In particular, `eps` at
  ε = 0.5 is milder than the thesis's 0.10, which collapsed. Is one ε enough?
- **D5. One session for E1 and E2,** with E2 built on `T-coarse` rather than on E1's winner, and E3's 2 × 2
  joining them? The alternative waits for E1, then runs E2 on its winner, which costs a second R7615
  session and about a week.
