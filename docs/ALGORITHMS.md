# The algorithms

MAGAT v0.1 ships four reference algorithms and a floor. All five are written against the `Game`
contract ([`CONTRACT.md`](CONTRACT.md)), so a game that implements it gets them unchanged. Each plans one
whole turn and spends its budget in compute units, its initialization included. Each draws its random
numbers from the seed of the `(scenario, visit)` only, so arms are paired turn by turn. The four
algorithms also declare their own operations, so the currency prices their work as well as the forward
model's ([`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md)).

| Arm | Algorithm | Its initialization | `initial` is the value of | Reference |
|---|---|---|---|---|
| `random` | the floor: random legal actions | the position's evaluation | the position | — |
| `mcts` | UCT over the turn's actions, without rollouts | the position's evaluation | the position | Kocsis and Szepesvári 2006; Browne et al. 2012 |
| `emcts` | Evolutionary MCTS, (1+1), 21 phases | the greedy initial plan | the greedy plan | Baier and Cowling 2018 |
| `oep` | Online Evolutionary Planning | the random initial population | its best turn | Justesen et al. 2017 |
| `tbets` | Turn-Based Evolutionary Tree Search | the 20 seeded turns | the best seeded turn | the TUBSTAP implementation (below) |

`@reference` names the four algorithms wherever a list of arms is accepted. `experiments/tribes/reference.json`
runs them and the floor at the published levels, on the whole portfolio, against `emcts`.

What they share:
- **A plan is a whole turn:** at most 20 actions, then the end of turn. Its value, `final` in the turns
  table, is the heuristic's value of the position the plan reaches, the end of turn included.
- **The end of turn is held back.** The three searches that draw actions at random draw it only when
  nothing else is legal, so a random draw cannot end a turn on its first step. `mcts` leaves it out of
  its tree and adds it to the plan it plays.
- **The initialization is paid from the budget,** and in `mcts`, `oep` and `tbets` it stops when the
  budget runs out. The equivalence gate alone plays a port as its original pays it (below).

`initial` means a different thing in each arm (the table above), so a search gain, `final − initial`,
is comparable between turns of one arm, not between arms. The paired difference compares `final`.

## `random`: the floor

A uniformly random legal action, the end of turn among them, until the turn ends or the plan is 20
actions long. It does no search: its two evaluations, of the position and of where the plan leaves it,
are all it spends, so it is not priced. Code: `core/src/magat/agents/random/RandomAgent.java`.

## `mcts`: UCT over the turn's actions

Monte Carlo tree search (Kocsis and Szepesvári 2006; Browne et al. 2012) whose tree is the current
turn: a node is a position partway through it, reached by the actions on the path from the root.

- **Each iteration** descends from the root by UCB1 while a node has no untried action, expands one
  untried action of the node it stops at (drawn at random), evaluates the new node's position with
  the heuristic, and backs that value up the path as a mean. There are no rollouts.
- **UCB1** is `mean + C·√(ln N / n)` with the mean normalised to the range of values seen so far, so
  that the exploration term weighs against values of any scale.
- **A leaf** is a node where the game is over, the turn has passed, the turn has no action left but
  the end of turn, or the plan is 20 actions long. Once every node has been expanded, the search stops.
- **The turn played** is the most-visited path from the root, then the end of turn.
- **Memory.** A new node's position is evaluated and dropped; the first time the search expands that
  node, the position is rebuilt from its parent's (a copy and an advance), so a chance event in its
  action may come out differently from the one evaluated. A tree holds a position only for the nodes
  it has expanded: three 101,500-unit Tribes turns of about 78,000 nodes each ran side by side in a
  1 GB heap.

| Parameter | Default | |
|---|---|---|
| `c` | √2 | UCB1's exploration constant |
| `maxActions` | 20 | the longest plan before the end of turn |

Operations: `mcts.iteration` (an iteration's own work: the descent, the expansion's bookkeeping, the
back-up) and `mcts.legal` (a legal-action query). Code: `core/src/magat/agents/mcts/`.

## `emcts`: Evolutionary MCTS

Baier and Cowling's evolutionary MCTS (2018), in the variant of the engine fork's
`ExperimentalEMCTSAgent` with (1+1) selection.

- **The initial plan** is greedy at one ply: at each step every legal action is tried on a copy and the
  best kept, to 19 actions, then the end of turn.
- **The search** runs in 21 equal slices of the budget. In each, it mutates the incumbent (one action
  replaced by a random legal one), repairs the plan greedily (an action no longer legal is replaced by
  the best legal one), and keeps the child if it beats the incumbent.
- **A bridge burn** at each of the 20 slice boundaries commits the root to the child on the
  incumbent's path and prunes every other branch.
- **The turn played** is the best plan ever scored.

| Parameter | Default | |
|---|---|---|
| `phases` | 21 | slices of the budget; 20 bridge burns |
| `nodeSize` | 20 | the initial plan's length, its end of turn included |

Operations: `emcts.generation`, `emcts.legal`, `emcts.check`, `emcts.seqkey` and `emcts.burn`. Its
greedy initialization is never cut short: on Tribes it takes about 2,000 of the 9,500 units of the
smallest level ([`CURRENCY.md`](CURRENCY.md)). Code: `core/src/magat/agents/emcts/`.

**Gated** against the engine's agent, whose greedy initialization is paid outside the budget, so its
gate plays the port by that rule: `check equivalence --arms emcts`, 32 cells, 0 failed.

## `oep`: Online Evolutionary Planning

Justesen, Mahlmann, Risi and Togelius's online evolutionary planning (2017): a genetic algorithm whose
individuals are whole turns.

- **The initial population** is 50 random turns: random legal actions, to 20, then the end of turn.
- **Each generation** sorts the population by fitness and kills its worse half. The survivors refill it
  with offspring: each the uniform crossover of two survivors drawn at random, with one action
  replaced at random in one offspring in ten.
- **An offspring is played** from the position: an action that is not legal where it falls is
  replaced by a random legal one.
- **Fitness** is the heuristic's value of the position after the end of turn, measured once; the
  survivors keep theirs. **The turn played** is the fittest found.

| Parameter | Default | |
|---|---|---|
| `populationSize` | 50 | whole turns in the population |
| `killRate` | 0.5 | the share removed each generation, the worst first |
| `mutationRate` | 0.1 | the chance an offspring has one action replaced at random |
| `maxActions` | 20 | the longest turn before the end of turn |

The defaults are those of the Tribes engine's `OEPParams`. The engine's own `OEPAgent` is a different
variant, which evolves again before every action; `oep` plans the whole turn once. Operations:
`oep.genome` (a turn's own work: drawing or breeding it, playing it, its key), `oep.legal`, `oep.check`
and `oep.generation` (a generation's selection). Code: `core/src/magat/agents/oep/`.

## `tbets`: Turn-Based Evolutionary Tree Search

A tree search whose unit of search is a whole turn and whose expansion operators are evolutionary: a
port of the engine fork's `players.tbets.TBETSAgent`, which was ported from the C# implementation
written for TUBSTAP (`AI/TBETS/AI_TBETS.cs` in the TUBSTAP_Taylor repository).

- **A node** is a complete turn and the state it leads to; its children are candidate turns for
  whoever plays next, so the tree deepens across turns, not within one.
- **The root is seeded** with 20 generated turns (random legal actions, to 20, then the end of turn).
- **Each iteration** selects a node by UCB under progressive widening (at the default of one ply, the
  root) and either gives it 5 generated turns, the first time, or evolves its children: with
  probability 0.3 it mutates one picked by tournament (each action redrawn with probability 0.4),
  otherwise it crosses two over uniformly. An action no longer legal is replaced at random. Values are
  backed up as a minimax.
- **The turn played** is the root's fittest child.

At one ply every child is a turn of the agent's, and the search is an evolution of whole turns under
tournament selection. The tournament is the engine's: a plain tournament of 19 draws, which has the
selection pressure of TUBSTAP's three draws from the fittest fifth, without sorting the children.

| Parameter | Default | |
|---|---|---|
| `numInit` | 20 | turns seeded at the root |
| `startingPop` | 5 | turns given to a node the first time it is expanded |
| `maxPlies` | 1 | plies of the tree; 2 adds the opponent's replies |
| `nodeSize` | 20 | the longest turn before the end of turn |
| `mutationRatio` | 0.3 | the chance an evolution step mutates rather than crosses over |
| `mutationRate` | 0.4 | the chance a mutation redraws each action |
| `tournamentSize`, `tournamentTopFraction` | 3, 0.2 | TUBSTAP's tournament; the port draws the plain one of 19 |
| `ucbC` | 1.414 | UCB's exploration constant, on fitness normalised to the siblings' range |
| `wideningK`, `wideningAlpha` | 2.0, 0.4 | a node is under-expanded while its children < k·descendants^alpha |
| `wideningStopProb` | 0.95 | the chance selection stops at an under-expanded node |

The defaults are the engine's. Not ported: the rollouts and the action-bias policies, which the
defaults leave off (the policies read Tribes' action types, which the contract does not show).
Departing from the original, the seeding stops when the budget runs out, which it never does at the
gate's budgets or at a published level. Operations: `tbets.turn` (a candidate turn's own work),
`tbets.legal`, `tbets.check` and `tbets.select` (an iteration's descent). Code:
`core/src/magat/agents/tbets/`.

**Gated** against the engine's agent, which charges its seeding to the budget, so its gate plays the
port by that rule: `check equivalence --arms tbets`, 32 cells, 0 failed. The port makes the engine's two
copies of the state before the seeding: each draws from the state's random generator, and without them
the seeded turns would meet other chance events.

## References

- L. Kocsis and C. Szepesvári. *Bandit Based Monte-Carlo Planning.* Machine Learning: ECML 2006, LNCS
  4212, Springer, 2006.
- C. B. Browne, E. Powley, D. Whitehouse, S. M. Lucas, P. I. Cowling, P. Rohlfshagen, S. Tavener,
  D. Perez, S. Samothrakis and S. Colton. *A Survey of Monte Carlo Tree Search Methods.* IEEE
  Transactions on Computational Intelligence and AI in Games 4(1), 2012.
- H. Baier and P. I. Cowling. *Evolutionary MCTS for Multi-Action Adversarial Games.* IEEE Conference
  on Computational Intelligence and Games (CIG), 2018.
- N. Justesen, T. Mahlmann, S. Risi and J. Togelius. *Playing Multi-Action Adversarial Games: Online
  Evolutionary Planning versus Tree Search.* IEEE Transactions on Computational Intelligence and AI in
  Games, 2017.
- TBETS: the C# implementation written for TUBSTAP, the Turn-Based Strategy Academic Package
  (`AI/TBETS/AI_TBETS.cs` in the TUBSTAP_Taylor repository), from which the Tribes engine's
  `TBETSAgent` was ported.

The engine's agents, which `emcts` and `tbets` reproduce and the defaults of `oep` come from, are in
the Tribes engine fork (`games/tribes/engine`, a fork of
[GAIGResearch/Tribes](https://github.com/GAIGResearch/Tribes)).
