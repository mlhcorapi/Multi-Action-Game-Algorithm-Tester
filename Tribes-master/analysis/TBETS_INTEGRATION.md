# TBETS in Tribes — port notes, the rollout switch, and replacing the attack bias

TBETS (Turn-Based Evolutionary Tree Search) comes from the TUBSTAP codebase
(`AI/TBETS/AI_TBETS.cs` and its five helper classes). This document records what the Tribes port
does, the two places it deliberately departs from the original, and — the part with the most room
for a decision — what should replace TUBSTAP's hard-coded 80% attack bias.

**Added:** `src/players/tbets/{TBETSAgent,TBETSNode,TBETSParams}.java`, plus a `TBETS` entry in
`Run.PlayerType` / `Run.getAgent` / `Run.parsePlayerTypeStr` and an optional `"TBETS Rollouts"` key
read by `Play`, `Tournament`, `FollowableGame` and `RunElites`.

---

## 1. How TBETS works

The unit of search is a **whole turn**. A node holds one complete action sequence ending in
`END_TURN`, plus the state it produces; a node's children are candidate turns for whoever plays
next. So the tree alternates agent / opponent turns and deepens **across** turns.

```
root  (the state handed to the agent; stands in for the turn just played against me)
 ├── my candidate turn A ──┬── opponent reply A1
 │                         └── opponent reply A2
 ├── my candidate turn B
 └── … numInit = 20 of them
```

One iteration:

1. **Select** — descend from the root. At each level, stop with probability `wideningStopProb`
   while progressive widening says the node is under-expanded (`children < k · descendants^α`);
   otherwise take the UCB-best child.
2. **Expand** — if the node has never been expanded, generate `startingPop` fresh candidate turns
   for it. If it has, **evolve** its existing children: with probability `mutationRatio` mutate one
   tournament-selected child, otherwise uniformly cross two over.
3. **Repair** — a turn is deeply path-dependent, so a mutated or spliced sequence usually will not
   replay. Every action that has become infeasible is replaced against the real forward model.
4. **Evaluate and back up** — minimax, not averaging: a node whose children are the agent's turns
   takes their max, a node whose children are the opponent's takes their min. Values are always in
   the agent's frame.

**Versus the EMCTS family already here.** EMCTS searches *within* one turn and commits to a prefix
at each phase boundary (bridge burning); its depth dimension is later actions of the current turn.
TBETS never commits mid-turn — it holds a population of complete turns and improves them — and its
depth dimension is future turns. The two are close cousins at the operator level (mutate + greedy
repair) and quite different at the search level.

---

## 2. Port decisions

| Decision | Why |
|---|---|
| **Plan once per tick, replay the sequence** | The engine calls `act()` once per action. This is what every plan-based agent here does, and it is what makes the per-turn budget accounting comparable across agents. |
| **`maxPlies = 1` by default** | Ply 2 means generating the *opponent's* whole turn from a state the agent cannot see (`Game` hands each agent a fog-reduced `gs.copy(playerIdx)`), and in a solo scenario `END_TURN` does not even pass control. `expandable()` additionally refuses to grow a ply whose active tribe disagrees with the alternation the minimax assumes, so a deeper setting is safe by construction rather than by convention. |
| **Tournament selection without the sort** | TUBSTAP sorts each sibling list and draws from the top 20%. At this project's budgets a node accumulates thousands of children, so that sort would dominate the search. A plain tournament of size `(tournamentSize+1)/topFraction − 1` = 19 has the same expected winner rank. |
| **Seeding is charged to the budget** (`excludeSeedingFromBudget = false`) | EMCTS excludes its greedy init because *every* EMCTS config runs identical init code, so excluding a shared constant is harmless. Not so here: seeding cost depends on the config under test — rollouts multiply it ~35×, `GREEDY_EPSILON` ~15× — so excluding it would hand the expensive configs free compute and rig the comparison one actually wants to run. |
| **`act()` scans forward past a broken step** | EMCTS abandons the plan at the first infeasible action. A plan can contain one step the engine's own RNG invalidated (`Examine`, `LevelUp`) while the rest stays playable; there is no reason to throw the rest away. |

### Not ported

The C# agent caches its tree **across engine calls** (`savedTree`): it returns one action per call
and, at the start of the next turn, tries to match the observed state against a predicted
opponent-turn child to reuse that subtree. This is left out. Its cache-hit test is
"did the opponent play the turn I predicted?", which cannot be evaluated under fog of war, and at
`maxPlies = 1` there is no predicted opponent turn to match in the first place. Adding it back is
worthwhile only together with a working deeper-ply setting.

---

## 3. The rollout switch — `TBETSParams.useRollouts`

TUBSTAP's TBETS never evaluates a state directly. It scores a candidate turn by attaching two
**phantom** nodes — a scripted opponent turn, then a scripted turn of its own — and evaluating the
state two turns later (`TBETSStateEvaluator.EvaluateNodeFitness`). That is a rollout in all but name.

`useRollouts = false` (default) scores a node as `heuristic.evaluateState(rootState, node.state)`,
the same one-shot evaluation EMCTS and OEP use here. `true` restores the original, with
`rolloutTurns`, `rolloutMaxActionsPerTurn` and `rolloutGreedy` controlling it. The scripted opponent
*minimises* the agent's heuristic — with a single-perspective heuristic there is no other sensible
reading of "the opponent plays well".

**Why off by default.** Three reasons, only one of which is cost:

- **Cost.** A scripted turn is tens of `advance()` calls, and the greedy script adds a
  `copy() + advance() + evaluateState()` per candidate action per step. Measured: root-seeding cost
  ×35, and distinct sequences evaluated per turn drop from ~2200 to ~180 at the same budget.
- **Observability.** The opponent half of the rollout is played over units the agent cannot see.
  TUBSTAP is fully observable; Tribes is not.
- **Applicability.** In a solo scenario the rollout degenerates into the agent moving twice.

**Measured on the scenario portfolio, rollouts lose — see
[TBETS_VS_EMCTS.md](TBETS_VS_EMCTS.md).** Against EMCTS at a matched compute budget, paired over 40
scenarios × 5 visits, the rollout arm scored **−145 (40k units) and −162 (80k units)** on the value
of the turn it actually played (p<0.001 both), winning 13–15% of scenarios, while plain TBETS over
the same pairs scored **+217 and +251**.

The reason is worth keeping, because it is not simply cost: the rollout arm produces the **highest
search value of any arm and the lowest delivered turn**. A node's rollout value is the heuristic two
turns downstream of a *scripted* continuation, and two turns of accrual swamps any difference between
the candidate turns being ranked. An earlier probe in this document appeared to show rollouts
winning; it compared the rollout arm's own inflated value scale against the one-shot arms' and was
wrong.

**If you turn them on anyway, budget them properly.** Because seeding is charged, `useRollouts = true` with
`rolloutGreedy = true` spent the *entire* 3 000-forward-model smoke budget inside root seeding and
ran **zero** search iterations — the agent degenerated into "best of the 20 seeded turns". That is
the accounting behaving correctly, not a bug, but it means the rollout configuration needs a budget
roughly an order of magnitude larger than the one-shot configuration to be searching at all. Either
raise the budget or cut `numInit`. Note that `rolloutGreedy = false` buys search volume by making
the continuation *random*, which is the version the portfolio comparison found worst — cheaper is
not better here.

---

## 4. The attack bias, and what should replace it

### 4.1 What TUBSTAP's bias is, and why it does not transfer

`ATK_BIAS = 0.8` sits in the one place TBETS draws a random action (`GenerateRandomActions`,
`GenerateBiasedAction`), so it shapes turn generation, mutation and repair alike: *with probability
0.8, draw from this unit's attack actions if it has any; otherwise draw a move.*

In TUBSTAP that is close to a correct prior. Every unit acts every turn, the action space is
essentially (move, move-and-attack), and score is damage-driven.

In Tribes almost none of that holds. `ATTACK` is one of ~25 action types; it is feasible only when
an enemy is adjacent **and visible**; and score comes from cities, population, technology and
territory. Measured over 8 scenarios against the rule-based agent, the fraction of biased draws that
found *any* feasible `ATTACK` to fire on:

| Game length | Draws that found an ATTACK |
|---|---|
| 10 turns | **4.3%** |
| 20 turns | **7.2%** |
| 30 turns | **10.5%** |

So an 80% attack prior buys a ~3–8% shift in the action distribution, concentrated in the late game
when the tribes finally make contact; every other draw falls through to uniform. It is not literally
a no-op, but it is a weak, phase-dependent intervention rather than the strong prior it is in
TUBSTAP — and nothing about it is aimed at what Tribes actually rewards.

### 4.2 The hook

Rather than hard-code a replacement, `TBETSAgent.pickBiasedAction` is the single point where an
action is drawn, and `TBETSParams.biasMode` selects the policy. `repairBias` optionally uses a
*different* policy for forced replacements (§4.3, option 4). Four options are implemented and
switchable; two more are sketched.

**The shipped default is `NONE` (uniform).** No candidate has been shown to beat uniform on score at
a sample size that can resolve it, so uniform is the honest starting point and choosing a
replacement is a sweep to run, not a default to inherit.

### 4.3 The options

**1. `CATEGORY_WEIGHTS` — keep the shape of the prior, change its target.** *(implemented)*
The structural replacement: a fixed prior over *kinds* of action, but over kinds Tribes scores.
Actions are grouped into AGGRESSION / EXPANSION / ECONOMY / TECH / UTILITY; a category is drawn by
weight, then an action uniformly within it. Only categories with a feasible action compete, so the
prior never wastes its mass the way the attack bias does. It still *samples*, so it preserves the
diversity the evolutionary operators need. The default weights `{2, 3, 3, 2, 1}` are a starting
point, not a finding — they are the obvious thing to sweep first.

**2. `LEARNED_DELTA` — learn the prior instead of setting it.** *(implemented)*
Per action key (`ACTION` type + actor/tech qualifier), keep the shrunk mean value
`Q = (Σδ + m·globalMean)/(n + m)` and pick the best-scoring feasible key. This is the same estimator
as `players.stage1.Stage1BiasedAgent`, so it is the **turn-level counterpart of the Stage-1 action
bias line** and connects this agent to work already done. Two differences from Stage 1: credit is
Monte-Carlo rather than paired (a TBETS node is a whole turn, so there is no single inserted action
to credit — every key in the sequence takes an equal share of the turn's value), and it therefore
gets ~20 observations per evaluated node instead of one. Costs no forward-model calls. It was the
only policy with a **positive median search gain** and the highest rate of turns improved among the
one-shot policies.

**3. `GREEDY_EPSILON` — the obvious answer, and why it is wrong here.** *(implemented, not
recommended as the generator)*
"Just use the heuristic": with probability `biasStrength`, take the 1-ply greedy best. It fails as a
*generator* in two measurable ways. It is the only policy that spends forward-model calls to make a
single draw (one `copy + advance + evaluate` per candidate), pushing seeding cost up ~15×. And it
collapses diversity — ~500 distinct sequences per turn against ~2200 for the sampling policies —
because near-identical greedy turns leave mutation nothing to vary. Worth keeping precisely because
it makes the diversity/quality tension concrete.

**4. Split generation from repair — `repairBias`.** *(implemented; the reasoning behind it was
half wrong, and that is the interesting part)*
TUBSTAP uses one policy for both jobs, but the jobs differ. **Generation wants diversity**: without
it the evolutionary operators have nothing to work with. **Repair wants quality on a single forced
decision**, where greedy is exactly right and — so the argument went — its cost is paid only on the
actions that actually broke. The EMCTS family here already splits them this way (uniform insertion,
greedy repair); TBETS did not.

Measured, the second half of that argument does not hold **for TBETS**. Adding
`repairBias = GREEDY_EPSILON` to a sampling generator collapsed distinct sequences per turn from
**2530 to ~780** — most of the way to the ~510 of greedy-everywhere — and cost ~10% of the search
budget. The reason is that repair is not the exception here, it is the common case: TBETS mutation
is a **whole-sequence pass** with `mutationRate = 0.4`, so ~40% of positions are deliberately
redrawn and much of what follows is invalidated downstream. Greedy repair therefore rewrites the
bulk of the sequence, which is just a greedy generator wearing a different hat. EMCTS gets away with
the same split because its mutation changes exactly one action, leaving most of the tail feasible.

It is not a dead end, though — it is a real trade rather than a free win. The split *did* raise the
rate of turns improved from 41% to 48–50%, the best figures among the one-shot configurations. Two
ways to keep that without paying in diversity, both cheap:

- **Lower `mutationRate` first, then split.** At a low enough rate repair really is rare and the
  original argument applies. This is the one-line version of the experiment.
- **Bound the greedy repair.** Cap how many actions per sequence it may greedily rewrite, or rank
  candidates by learned value and run the exhaustive greedy over only the top K — exactly what
  `Stage1Params.shortlistK` already does for EMCTS repair.

`repairBias = null` (default) reproduces TUBSTAP's single-policy behaviour exactly — verified: the
whole smoke matrix is unchanged metric-for-metric by the refactor.

**5. Make the prior phase-aware.** *(sketch)*
The attack hit rate rises 4.3% → 10.5% with game length, which is direct evidence that *no constant
is right*: the correct prior in the opening (expand, settle, research) is not the correct prior once
borders touch. Concretely: interpolate `categoryWeights` between an opening vector and a late-game
vector by `gs.getTick() / maxTurns`. Cheap to add on top of option 1 and it targets a
non-stationarity that has already been measured rather than a hypothesised one.

**6. Bias by actor coverage rather than action type.** *(sketch)*
What `ATK_BIAS` really buys in TUBSTAP is that every unit does something useful, because there every
unit acts every turn. The Tribes analogue of that *function* — as opposed to its surface form — is
"do not leave an actor idle": while building a sequence, bias towards actions belonging to actors
that have not yet acted in it. This needs no value model at all and is orthogonal to options 1–5,
so it can be layered on any of them.

---

## 5. What was measured, and what it does and does not support

Setup: TBETS vs `SimpleAgent`, CAPITALS, `STOP_COMPUTE` 40 000 units, `SIMPLE_HEURISTIC`, seeding
charged. The main table is 3 agent seeds × 8 map seeds = **24 games per config**, 12 turns each.
Probe sources are in the scratchpad (`TBETSBiasProbe`, `TBETSAttackProbe`, `TBETSRepairProbe`,
`TBETSSmoke`) — they are diagnostics, not part of the experiment harness.

| config | mean gain | median gain | turns improved | unique seq/turn | search fm/turn | score | sd(score) |
|---|---|---|---|---|---|---|---|
| `NONE` (shipped default) | 114.2 | 0 | 41% | **2530** | 38537 | 2174 | 1007 |
| `NONE` + greedy repair | 101.0 | 0 | 48% | 781 | 34542 | 1849 | 1034 |
| `CATEGORY` + greedy repair | 63.6 | 0 | 41% | 807 | 34530 | 1757 | 1250 |
| `LEARNED` + greedy repair | 92.1 | 0 | **50%** | 763 | 34596 | 2084 | 911 |
| `GREEDY` everywhere | 31.9 | 0 | 29% | 512 | 31867 | 2059 | 1218 |
| `NONE` + rollouts | 342.4 | 110 | **73%** | 179 | 28959 | **3026** | 1731 |

*(Mean gain is not comparable across the rollout boundary — a rollout value is measured two turns
further ahead, so it lives on a different scale. Turns-improved and diversity are scale-free.)*

**Supported (stable across all three runs, and mechanistic):**

- The attack bias fires on 4.3 / 7.2 / 10.5% of draws at 10 / 20 / 30-turn games.
- **Greedy anywhere collapses diversity**, whether it generates or only repairs: 2530 → ~780 (repair
  only) → 512 (both) unique sequences per turn. Greedy generation additionally inflates seeding cost
  ~15×.
- Greedy repair buys a real improvement in the *rate* of turns improved (41% → 48–50%) at that cost.
- Rollouts cost ~35× more seeding and leave ~14× fewer sequences evaluated. **The claim that they
  improved the search is withdrawn** — it compared incommensurable value scales; the portfolio
  comparison in [TBETS_VS_EMCTS.md](TBETS_VS_EMCTS.md) measures turn quality on one scale and finds
  rollouts clearly worse.
- `LEARNED_DELTA` had the highest turns-improved rate among the one-shot policies and, in the 8-seed
  runs, the only positive median search gain.

**Not supported — do not read the score column as a ranking.** With sd ≈ 1000 over 24 games the
standard error is ≈ 205, so the five one-shot configs (1757–2174) sit inside ~2 SE of each other and
are not distinguishable. The earlier 8-seed runs make the point more bluntly: two runs differing
*only* in whether ~120 forward-model calls of seeding out of ~38 600 were charged (a 0.3% budget
difference) swung `CATEGORY_WEIGHTS` from best non-rollout config (2713) to worst (1282). The
rollout gap (+852, ≈ 2.4 SE, unpaired) is the only score difference large enough to be worth
following up, and it is suggestive rather than established. Ranking these policies needs the real
harness, the v6 scenario portfolio, and paired seeds.

---

## 6. Running it

```bash
javac --release 11 -nowarn -cp "lib/*" -d out $(find src -name '*.java')
```

In a tournament/play JSON, name the agent `"TBETS"`. Rollouts are off unless the optional key is
present:

```json
{ "Players": ["TBETS", "MCTS"], "TBETS Rollouts": true }
```

Programmatically, the way the experiment runners build agents:

```java
TBETSParams p = new TBETSParams();
p.stop_type = p.STOP_COMPUTE;
p.heuristic_method = p.SIMPLE_HEURISTIC;   // the heuristic that meters evals
p.num_computeUnits = 40000;
p.biasMode = TBETSParams.Bias.CATEGORY_WEIGHTS;
p.repairBias = TBETSParams.Bias.GREEDY_EPSILON;   // option 4
Agent agent = new TBETSAgent(seed, p);
```

`TBETSAgent` fills the same `TurnData` log as the EMCTS family (initial/final sequence value,
evaluated and unique sequences, forward-model calls, and the compute-unit split between seeding and
search), so existing analysis scripts read it without modification. The search/seeding compute
columns are kept disjoint in both budget modes.

---

## 7. Suggested next steps

1. ~~Rollouts on/off at matched budgets on a portfolio.~~ **Done** —
   [TBETS_VS_EMCTS.md](TBETS_VS_EMCTS.md). Rollouts lose decisively. If the idea is to be revisited,
   the thing to change is not the budget but the objective: score a rollout as a *difference* against
   a common baseline continuation rather than as a level, so two turns of accrual cancels.
2. **`mutationRate` sweep, then re-test `repairBias`.** The generation/repair split failed for a
   specific, fixable reason (§4.3 option 4): at `mutationRate = 0.4` repair rewrites most of the
   sequence. Lowering it should restore the premise the split depends on, and this is a one-parameter
   experiment.
3. **Bias sweep in the real harness** — `NONE`, `CATEGORY_WEIGHTS`, `LEARNED_DELTA` on the v6
   portfolio with paired seeds. The probes can rank them on search-quality metrics but not on score;
   only paired runs at scale will separate them.
4. **TBETS vs EMCTS at equal compute** — the comparison the port exists for. Both now log the same
   quantities the same way, so this needs no new instrumentation.
5. `categoryWeights` sweep, then option 5 (phase-aware weights) if a single vector proves too blunt.
