# In-turn learning on comma-EMCTS: a research plan

**Status: a plan for review (2026-11-27), not yet a pre-registration.** Its screen runs on the development
half and decides nothing on its own. Its confirmation is frozen as a pre-registration of its own, as
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

What was never tested:
- **Learners other than a table of independent classes.** Everything was counting with shrinkage toward
  the turn's mean.
- **Mutation biased without losing diversity.** The thesis tried one rule, ε-greedy insertion at a strong
  bias, with one mutation per child. It never tried:
  - a rule that keeps exploring what it has not tried;
  - biasing only some mutations;
  - children several mutations from their parent;
  - a guard against duplicates;
  - a learned choice of which position to mutate.
- **The search learning about itself:** which operator works on this position, how large a step to take,
  what not to try again.
- **Long thinking.** Its top level was 400,000 of its own units, about 20 s, read only as an extrapolation.

How much a learner has to learn from in one turn, on the published AB-EMCTS run (median evaluated
children per turn, `comma-z0-coarse`):

| 500 ms | 1000 ms | 5000 ms | 30 s (extrapolated) |
|---|---|---|---|
| 172 | 412 | 1,863 | about 11,000 |

A few hundred samples suit a table. A model with many parameters cannot learn much from 172, so its test
belongs at the long levels.

## 2. Research questions

Each comes with the direction expected, written down so it can be wrong.

**What to learn with** (the learners):
- **RQ1. Granularity over time.** Which key granularity makes the best evaluation-free repair, and does
  the best one become finer as the thinking time grows?
  *Expected:* coarse ≥ mid > fine at 500 and 1000 ms, as in the thesis. By 30 s mid catches coarse; fine
  stays worst.
- **RQ2. Backoff.** Does a hierarchical table, with fine estimates shrunk toward their mid class and mid
  toward coarse, use fine information without losing coarse's robustness?
  *Expected:* it matches coarse at 500 ms, and beats every single-granularity table at 5 s and 30 s.
- **RQ3. Function approximation.** Does an online neural network, given the same information as the
  tables, beat the best table with its own compute charged, and from what thinking time? Does it pay
  more with richer, game-specific inputs (RQ3b)?
  *Expected:* it loses at 500 ms (cold start, and its cost), is level at 5 s, and wins at 30 s.

**Where to use it:**
- **RQ4. Repair.** Does a repair that evaluates the learner's best few candidates beat one that evaluates
  none, once the budget can afford it?
  *Expected:* a shortlist of 3 beats evaluation-free repair at 30 s only.
- **RQ5. Mutation.** Can what the turn learns improve the mutation without collapsing the search's
  diversity?
  - **RQ5a. The inserted action.** Does a biased insertion that keeps exploring beat uniform insertion?
    The candidates are Thompson sampling from each class's estimate and uncertainty, and ε-greedy. Is the
    duplicate share what decides it?
    *Expected:* strong ε-greedy loses again, with its duplicate share up. Thompson keeps duplicates near
    uniform's and beats it at 5 s and 30 s, but not at 500 ms.
  - **RQ5b. Biasing only some mutations.** Is a partial bias, some insertions learned and the rest
    uniform, better than either extreme, because it keeps the plans unique?
    *Expected:* yes. Half the insertions biased beats all of them and none of them.
  - **RQ5c. Mutation strength.** Do children several mutations from their parent (3 or 5) beat
    one-mutation children? Does biasing one of the several, the rest uniform, give the best of both: a
    learned step with exploration around it?
    *Expected:* uniform multi-mutation loses at short times, since most random multi-changes are worse.
    One biased mutation among three beats one-mutation children at 5 s and 30 s. Five is too many.
  - **RQ5d. The position.** Is where to mutate worth learning?
    *Expected:* a small gain, since a change early in a plan moves everything after it.
  - **RQ5e. With repair, and with time.** Does a learned mutation add to evaluation-free repair, or
    overlap with it? Does it need more thinking time than repair to pay?
    *Expected:* they overlap in part. Mutation's gain grows with the thinking time faster than repair's.

**The search learning about itself** (new):
- **RQ6. Adaptive operators.** Can the turn learn which mutation operator works on this position, by a
  bandit over a few operators credited with how often their children win, and beat the best fixed
  operator? Can it learn its step size, by an evolution strategy's success rule?
  *Expected:* the adaptive arm lands near the best fixed operator at every level, without the best one
  having to be known in advance. At 30 s it beats it, because the best operator changes as a turn goes on.
- **RQ7. Structure beyond single actions.** Do substitutions (what to replace a given action with) and
  building blocks (pairs of consecutive actions whose presence goes with improvement) carry information
  a per-action table lacks?
  *Expected:* substitutions do, for repair as well as insertion. Building blocks pay only at 30 s.
- **RQ8. What not to try.** Does a tabu memory of recently failed mutations, or a learned kick when
  progress stalls, keep long searches productive?
  *Expected:* both matter little at 500 ms, and help at 30 s, where comma-EMCTS's later generations
  mostly repeat failures.

**And over all of them:**
- **RQ9. The long run.** Does in-turn learning's advantage keep growing to 30 s, level off, or reverse?
  Does OEP, level at 500 and 1000 ms, fall behind, or catch up by 30 s?
  *Expected:* the advantage over `emcts-comma` keeps growing, more slowly. The best learner beats OEP at
  30 s.

## 3. The design space

Everything is built on comma-EMCTS, (1,5) comma selection. A learner is credited as AB-EMCTS's is: every
evaluated child's change in value goes to each action inserted and, negated, to each one removed.

**Learners** (RQ1-RQ3), each with evaluation-free repair and uniform mutation:

| Id (working) | Learner | Estimate of an action's value change |
|---|---|---|
| `T-coarse` | table, coarse keys (= `comma-z0-coarse`) | shrunk class mean, toward the turn's mean |
| `T-mid`, `T-fine` | table, mid or fine keys | the same |
| `T-hier` | hierarchical table | fine mean shrunk toward its mid class, mid toward its coarse class, coarse toward the turn's mean |
| `L-classes` | linear model on the class identities | sum of learned coarse, mid and fine effects, fitted online |
| `N-classes` | small neural network on the class identities | as `L-classes`, plus their interactions (one hidden layer) |
| `N-features` | the network with game features too | `N-classes`, given state and action features (RQ3b) |

`T-hier`, `L-classes` and `N-classes` form a ladder over the same information: counting with backoff,
additive regression, nonlinear regression. That ladder isolates what each step adds.

**Repair** (RQ4): `z0`, evaluation-free, takes the best estimate (AB-EMCTS). `short3` evaluates the
best 3 candidates and takes the best of them.

**Mutation** (RQ5), each on `T-coarse` with `z0` repair. A child is *k* mutations from its parent: *k*
positions drawn without repeats, at each of which a new action is drawn, in one pass along the plan with
the repair, so a 3-mutation child costs about what a 1-mutation child does.

| Id | *k* | Which insertions are biased, and how | Positions | Duplicates |
|---|---|---|---|---|
| `uniform` (control) | 1 | none (EMCTS) | uniform | kept |
| `guard` (control) | 1 | none | uniform | discarded and redrawn, up to 3 times: the guard alone, with no learning |
| `eps90` | 1 | ε-greedy, 90% greedy: the thesis's setting, under MAGAT's rules | uniform | kept |
| `eps50`, `eps20` | 1 | ε-greedy, 50% and 20% greedy: partial bias (RQ5b) | uniform | kept |
| `thompson` | 1 | all, Thompson sampling | uniform | kept |
| `thompson50` | 1 | half, Thompson; the rest uniform (RQ5b) | uniform | kept |
| `thompson+guard` | 1 | all, Thompson | uniform | redrawn |
| `k3-none`, `k5-none` | 3, 5 | none (RQ5c's control) | uniform | kept |
| `k3-one`, `k5-one` | 3, 5 | one, Thompson; the rest uniform | uniform | kept |
| `k3-all`, `k5-all` | 3, 5 | all, Thompson | uniform | kept |
| `gen-mixed` | 1 | two of each generation's five children all-Thompson, three uniform | uniform | kept |
| `site` | 1 | none | Thompson over positions, from the change mutating each brought | kept |
| `thompson+site` | 1 | all, Thompson | Thompson over positions | kept |

- **Thompson is the natural rule.** The tables are already shrunk means with a count, which is a
  posterior. Its draws keep trying classes it has seen little of, which is the exploration ε-greedy lost.
- **The controls separate each ingredient.** `guard` is the duplicate guard without learning, and
  `k3-none` and `k5-none` the mutation strength without learning.
- **Costs are charged.** A biased insertion scores every legal candidate, up to about 270 at the start of
  a Tribes turn: one lookup and one draw per candidate, priced like the table's lookups. A redrawn child
  pays for its repair again.

**The search learning about itself** (RQ6-RQ8), each on `T-coarse` with `z0` repair:

| Id | Idea | What it learns, from what |
|---|---|---|
| `aos` | adaptive operator selection | a Thompson bandit over four operators (`uniform`, `thompson`, `k3-one`, `k5-one`), each credited with how often its children beat their parent. Every child is made by the operator drawn |
| `k-adapt` | a success rule for the step size | *k* between 1 and 8, raised when more than a fifth of recent children improved on their parent and lowered when fewer did (the evolution strategies' 1/5 rule). Insertions uniform |
| `k-adapt+thompson` | the same | with every insertion Thompson-biased |
| `subst` | substitutions | a table of what replacing class *a* by class *b* brought, shrunk toward *b*'s own estimate. Used for insertion (Thompson over the replacement of the action removed) and for repair (the replacement of the illegal action) |
| `blocks` | building blocks | a table of consecutive class pairs, credited when a child holding the pair improves. A tenth of mutations insert a learned pair at once |
| `tabu` | what not to try | a mutation (position, inserted class) whose child lost to its parent is not drawn again for the next 20 generations |
| `kick` | a learned kick | when 30 generations pass without improving the best plan, the next child is five Thompson-biased mutations from the best plan |

**Controls:**
- `comma-z0-random`: the same repair with no learning;
- `emcts-comma`: exhaustive repair;
- `comma-z0-coarse`: the current champion, which is `T-coarse` with `uniform` mutation;
- `oep` and `tbets`: the strongest other families.

## 4. The neural network

- **The framing.** Within a turn, each evaluated child says how much its inserted actions changed the
  plan's value. Predicting that change from the action is an online regression, a contextual bandit.
  It is not multi-step reinforcement learning. Full RL, valuing an action by the later actions of the
  plan it enables (temporal-difference learning along the plan), would need a reward per step. That
  means evaluating every prefix of a plan, which costs evaluations the search would otherwise spend.
  It is a natural next step if the bandit form shows a network can learn in a turn.
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
  action, with L2 regularization. It is pure Java with no library, as MAGAT takes no dependencies.
- **Determinism.** Weights are initialized from the turn's random stream, updates are sequential, and
  every transcendental is `StrictMath`'s, so a turn re-plays field for field on any JVM. That keeps
  `check replay` and `check reproduction` valid for it.
- **Its cost is charged.** Its operations are declared and priced like the table's: `nn.features`,
  `nn.forward` (one candidate scored) and `nn.backward` (one update). A network that costs more than it
  earns loses, at equal budget.
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
  `check time` and a target of 30,000 ms, rounded to 500 units.
  - This takes about 30 minutes, in a short sitting before the night, so the night's specs can name it.
  - Specs write it in units. `games/tribes/levels.json` is a published artefact and stays as it is.
- **Prices.** A price depends on the level: from 9,500 to 101,500, `emcts`'s advance moved 5% and `oep`'s
  8%. So the 30-second level gets prices of its own, never borrowed from 101,500's.
  - A currency today keeps the levels it was first measured at. So MAGAT gains one change: **a currency
    can gain a level**, measured on the same 16 positions as its others, with every existing line kept,
    as `--from` keeps them.
  - `r7615-ab-emcts`, extended this way, keeps every published price. The published turns at the three
    published levels then stay reusable.
- **Repetitions:** 2 at 30 s. Positions, not repetitions, carry the precision, and a few repetitions
  already show the long-run behaviour.
- **What the analysis gains:**
  - The baseline's ladder reaches 30 s, so every multiplier at 5000 ms, all extrapolated today, becomes a
    measured one.
  - Each 30-second turn's 20 anytime checkpoints show its value at 1.5 s, 3 s, … within the long turn: a
    learning curve per arm. Its schedule is a 30-second turn's, not a shorter turn's, and the curves are
    read as that.

## 6. The experiments

### The screen: one night on the R7615, about 13 hours

All of RQ1-RQ9 are explored on the development half, in one overnight session. It is exploratory: it
chooses what the confirmation tests, and its intervals are read as screening, not as findings.

- **Arms played: 29 new ones.**
  - five learners: `T-mid`, `T-fine`, `T-hier`, `L-classes`, `N-classes`;
  - `short3`;
  - sixteen mutation rules, from `guard` to `thompson+site`;
  - seven self-learning ideas, from `aos` to `kick`.
- **Reused** at the three published levels, from the published AB-EMCTS and reference runs: `T-coarse`
  (`comma-z0-coarse`), `comma-z0-random`, `emcts-comma`, `oep`, `tbets` and the rest.
- **Levels:**
  - 500 ms ×4, 1000 ms ×4 and 5000 ms ×3 for every new arm;
  - 30 s ×2 for 13 arms, chosen beforehand for the questions about long thinking:
    - the controls `comma-z0-coarse`, `comma-z0-random`, `emcts-comma` and `oep`;
    - `T-mid`, `T-fine`, `T-hier`, `N-classes` (RQ1-RQ3);
    - `short3` (RQ4);
    - `thompson` and `k3-one` (RQ5);
    - `aos` (RQ6);
    - `kick` (RQ8).
- **Precision.** On 200 positions with 4 repetitions, a paired interval is about ±20 to ±25 points. The
  AB-EMCTS effects were +80 to +240, so the screen finds effects of their size and misses ones much smaller.
- **Wall-clock.** The wall-clock re-score compares arms played in the same run: every new arm with every
  other, and all 13 at 30 s. Against the reused arms the comparison is in units only, as you asked of
  reuse.
- **What it chooses**, by a rule written into the night's spec before it runs. The confirmation takes:
  - the best learner (RQ1-RQ3);
  - the best mutation or self-learning rule (RQ5-RQ8);
  - each judged by its mean paired difference with `comma-z0-coarse` over the levels it ran at, among
    the arms whose interval excludes zero at one level or more;
  - at most two others the screen singles out, named with the reason.

**Its timetable:**

| Sitting | What | Hours |
|---|---|---|
| Before the night | the 30-second level measured (`check time`) | 0.5 |
| 1 | setup, build, tests, the gates (`eps90`, `eps50`, `eps20` and `short3` against `Stage1BiasedAgent`) | 0.5 |
| 2-4 | calibration at the three published levels: 29 new arms in three sittings of about 1.2 h, each a session that writes when it completes | 3.7 |
| 5 | the run at the published levels: 29 arms | 0.7 |
| 6-9 | calibration at 30 s: 13 arms in four sittings of about 1.6 h | 6.5 |
| 10 | the run at 30 s: 13 arms | 1.0 |
| 11 | the currency's checks, once, and the export | 0.4 |
| | **the night** | **about 12.8** |

A failed sitting loses only its own arms. The others are already written, and the sitting runs again the
next night. The published levels are calibrated and run first, so if the night runs short, that part of
the screen is complete by morning and only the 30-second part waits.

### The confirmation

A 2 × 2 of {`T-coarse`, the screen's best learner} × {uniform mutation, its best mutation or
self-learning rule}, at 500 ms ×8, 1000 ms ×8, 5000 ms ×6 and 30 s ×2.
- **Arms:**
  - the four cells: the champion, each winner alone, and both together;
  - up to two arms the screen singled out;
  - `comma-z0-random`, `emcts-comma`, `oep` and `tbets`.
- **Its interaction term** measures whether learned repair and learned mutation add up (RQ5e), on
  positions no choice was made on.
- **Its decision rule** is the AB-EMCTS one: an interval excluding zero at two or more of the four
  levels, in the no-terminal stratum, with the wall-clock re-score agreeing in sign among arms played in
  the same run.
- **Where:** on a fresh portfolio (decision D2), in about 3 h of runs. Every arm is priced by then except
  the combined cell, about 33 minutes.

**Every arm's mechanism is reported beside its result:**
- the duplicate share (duplicates over nodes), and unique plans;
- the share of children that improve on their parent;
- the mutation strength and positions used;
- how varied the inserted classes are;
- the operator shares, for `aos`.

A rule that wins with its diversity collapsed, or loses with it intact, is read with that in view.

## 7. What MAGAT needs first

Each is a PR of its own, before the night:
1. **The learners:**
   - `T-fine`, restored from the archive;
   - `T-hier`;
   - `L-classes` and `N-classes`, with their priced operations;
   - toy tests and the audit.
2. **The mutation framework:** EMCTS gains hooks for the mutated positions, the inserted actions, the
   number of mutations per child, the operator per child, and a duplicate guard.
   - With nothing set they draw exactly plain EMCTS's random numbers, and its gate shows it.
   - The rules of section 3 are built on it: the ε-greedy, Thompson and partial-bias rules, *k*-mutation,
     `gen-mixed`, `site`, `aos`, `k-adapt`, `subst`, `blocks`, `tabu` and `kick`.
   - `eps90`, `eps50`, `eps20` and `short3` are gated against the engine's `Stage1BiasedAgent`, which
     implements both (`epsilon` with `insertKeys`, and `shortlistK`). The rest have no original, so they
     rest on toy tests, determinism and the audit.
   - New telemetry for the mechanism report.
3. **A currency gains a level:** `calibrate` and `calibrate-controlled` can price a level the file lacks,
   on its own 16 positions, keeping every line. The notebook takes a list of calibration configs, each its
   own sitting. With tests, and the shakedown.
4. **The night's specs, configs and run sheet,** with the 30-second level measured, as #45 did for
   AB-EMCTS.

`N-features` comes after the screen, and only if `N-classes` is at least level with `T-hier` at 5000 ms or
30 s there.

## 8. Decisions for review

- **D1. Scope of the network.** This plan proposes the bandit form only. Full temporal-difference RL is
  left for after it. Is that the right first step?
- **D2. Which half confirms.** The published portfolio's holdout half has been read twice: by the thesis
  (H3, H4) and by the AB-EMCTS experiment. A fresh portfolio, 400 new positions captured the way `v1` was,
  would give the confirmation a set nothing has seen.
  - The cost: about an hour, a new published portfolio beside `v1` (never replacing it), and playing every
    confirmation arm on it, since no published turns exist there.
  - The currency carries over: a run on another portfolio only warns that its prices were measured on
    `v1`'s positions, and prices are the machine's, not the positions'.

  This plan recommends it.
- **D3. The level.** 30 s with 2 repetitions, at about 610,000 units measured on the R7615?
- **D4. The screen's arms.** Are the 29 the right ones, and are the 13 chosen for 30 s the right ones?
  Dropping arms at 30 s saves about 31 minutes each.
- **D5. The night.** About 12.8 h, in sittings that each write when they complete, with the 30-second ones
  last. Is that the shape you want, or two shorter nights?
