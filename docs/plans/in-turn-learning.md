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
| Insertion biased by the table | hurt (ε = 0.10: −65 at 400k); the duplicate share rose from 6% to 23%: it collapsed the search's diversity |
| Shortlist repair (evaluate the table's top 15) | good (+200), but below zero-evaluation repair (+244) |
| Where the gain comes from | mostly from repair becoming nearly free: exhaustive repair took 82% of `emcts-comma`'s units |

Two things were never tested:
- **Learners other than a table of independent classes.** Everything was counting with shrinkage toward
  the turn's mean.
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
- **RQ4. Where to spend what is learned.** With the best learner, does using it beyond evaluation-free
  repair pay? The candidates are a shortlist the repair evaluates, exploration-aware insertion, and the
  choice of which action to mutate.
  *Expected:* a small evaluated shortlist (k = 3) beats evaluation-free repair at 30 s only.
  Exploration-aware insertion keeps the diversity the thesis's ε-greedy insertion lost, but gains little.
- **RQ5. The long run.** Does in-turn learning's advantage keep growing to 30 s, level off, or reverse? Does
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

**Uses of what is learned** (RQ4), each with the RQ1 to RQ3 winner:

| Use | What it does |
|---|---|
| `z0` (evaluation-free repair) | each replacement is the candidate with the best estimate; nothing evaluated (AB-EMCTS) |
| `short3` (shortlist repair) | the best 3 candidates are evaluated, and the best of them taken |
| `thompson` (insertion) | the inserted action is drawn by Thompson sampling from each class's estimate and uncertainty, so rarely tried classes are still explored |
| `site` (mutation site) | the position to mutate is chosen in proportion to how often mutating there improved the plan |

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

| | E1: learners (RQ1-RQ3) | E2: uses (RQ4) | E3: confirmation (RQ1-RQ5) |
|---|---|---|---|
| Half | development | development | the confirmatory half (decision D2) |
| Arms played | `T-mid`, `T-fine`, `T-hier`, `L-classes`, `N-classes`; `N-features` only if `N-classes` passes E1's gate | the E1 winner with `short3`, `thompson`, `site` | the 2 or 3 frozen winners, plus `comma-z0-coarse`, `comma-z0-random`, `emcts-comma`, `oep`, `tbets` at 30 s |
| Reused | `T-coarse`, `comma-z0-random`, `emcts-comma` and the reference arms at the published levels (the AB-EMCTS and reference runs) | E1's arms | at the published levels, the published runs |
| Levels | 500 ms ×4, 1000 ms ×4, 5000 ms ×3, 30 s ×2 | the same | 500 ms ×8, 1000 ms ×8, 5000 ms ×6, 30 s ×2 |
| Decides | which learners go to E2 and E3 (on dev: exploratory) | which use goes to E3 | the hypotheses, by the AB-EMCTS rule: CI excluding zero at ≥2 of 4 levels, no-terminal stratum, wall-clock agreeing in sign (for arms played in the same run) |

- **E1's gate for `N-features`.** It is built only if `N-classes` is at least level with `T-hier` at 5000
  ms or 30 s on dev. Otherwise richer inputs to a model that cannot yet learn its classes are premature.
- **R7615 time:**
  - **E1:** about 4 h of calibration (five new arms at four levels, about 33 minutes each, and three
    controls at 30 s, 27 minutes each), then under 1 h of runs.
  - **E2:** about 2 h of calibration and 30 min of runs.
  - **E3:** prices exist by then; about 1 h of runs per half.

## 7. What MAGAT needs first

Each is a PR of its own, before E1:
1. **The learners:**
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

E2's uses (`short3`, `thompson`, `site`) and `N-features` come after E1, if E1 calls for them.

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
- **D4. E2's uses.** Are `short3`, `thompson` and `site` the right three, or are some not worth an arm?
