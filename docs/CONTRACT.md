# The contract

MAGAT has two seams.
- **A game** implements `Game<S, A>` (`core/src/magat/game/Game.java`).
- **An algorithm** implements `SearchAgent<S, A>` against it (`core/src/magat/agent/`).

Everything else (the meter, the portfolio, calibration, the runner, the analyzer) is written once
against these two interfaces and never sees a game's own types. `S` is the game's state type and `A`
its action type.

The forward model an algorithm receives is always the metered one: `MeteredGame` wraps the game,
counts every `advance`, `copy` and `evaluate`, and times them while calibrating. An agent cannot
reach the unmetered game, and a game adapter never counts its own calls. The counts are what the
budget spends and what every result row reports. They transfer exactly between machines, so they
must be complete. Law L10 below checks that.

## `Game<S, A>`

Every method is total (no exceptions for ordinary inputs) and deterministic.

| Method | What it is for |
|---|---|
| `String id()` | a short, stable id (`"tribes"`); it names the currency, the portfolio and the results tree |
| `Map<String,String> configuration()` | every rule or engine setting that changes what a position or a forward-model call means (engine commit, game mode, rule flags). It is frozen into the portfolio manifest and every run's provenance; a run refuses a portfolio whose configuration differs |
| `S copy(S state)` | an independent copy (law L1) |
| `void advance(S state, A action)` | applies one action in place. The action came from `legalActions` or from an earlier state of the same turn (a plan being repaired), so it may be stale |
| `List<A> legalActions(S state)` | the actions the active player may take now, filtered to the ones a search should consider (Tribes leaves out DESTROY and DISBAND). Empty only at a terminal state or a turn boundary |
| `boolean isLegal(S state, A action)` | cheap, side-effect free, never throws: is this (possibly stale) action applicable here? |
| `double evaluate(S state, int player)` | the heuristic value from one player's point of view. Finite; a terminal state may carry a large sentinel, which `TurnRecord.terminal` reports separately |
| `boolean isTerminal(S state)` | |
| `int activePlayer(S state)`, `int playerCount(S state)` | |
| `boolean endsTurn(A action)` | true if the action ends the active player's turn |
| `A endTurnAction(S state)` | the explicit end-of-turn action, or `null` if turns end implicitly |
| `String actionKey(A action)` | the canonical identity of an action within a state; plans are de-duplicated on it. Two distinct legal actions of one state never share a key (L7) |
| `String actionClass(S, A, Granularity)` | the action abstracted for learned tables: COARSE (`MOVE`), MID (`MOVE:RIDER`), FINE (the full identity). Coarser levels partition finer ones (L8) |
| `int branchingFactor(S state)` | a descriptive count for stratification and reporting; the game says what it counts |
| `void reseed(S state, long seed)` | pins the state's own randomness (Tribes: ruin outcomes), so two copies reseeded alike behave alike |
| `byte[] serialize(S)`, `S deserialize(byte[])` | the portfolio's bins |
| `Map<String,String> describe(S state)` | small descriptive facts for the manifest. Deterministic; never used for pairing |
| `List<ScenarioSource<S>> scenarioSources()` | named generators of positions from played games (below) |

### Scenario sources

The portfolio builder schedules, journals, stratifies and assigns ids; a `ScenarioSource` only plays
and validates.

| Member | Meaning |
|---|---|
| `name()` | `"single"`, `"dual"` |
| `phases()` | the capture points within a source game (Tribes: ticks) |
| `maxAttempts()` | the attempt cap before the builder gives up on this source |
| `defaultTarget()` | the captures the published portfolio takes from this source |
| `definition()` | one line naming everything that determines what `play()` yields. It is written into the manifest and keys the build journal |
| `play(int attempt)` | plays source game number `attempt` (deterministic in it) and returns its validated captures in phase order |

`play` applies the source's own validity rule and stuck-game rule, and swallows engine failures: a bad
seed yields fewer captures. Anything that escapes `play` stops the build. A capture is
`(attempt, seed, phase, state)`. A `Scenario` adds its id, source and branching factor, and the
`describe` facts.

Source games are played at a **fixed, documented weight set**, never by a calibrated currency. A
portfolio must not depend on a calibration, or a recalibration would silently rebuild it.

### Deliberately not in the contract

- **No game loop, no `Agent.copy()`, no timers.** MAGAT replans one turn; a game's own turn loop is used
  only inside `ScenarioSource.play`.
- **No score.** A game that wants one puts it in `describe()` at capture time.
- **No pruning heuristics and no partial observability.** Both are engine policies, which a source fixes
  when it captures.
- **No wall-clock.** `TurnRecord` reports it; nothing in the contract consumes it.

### The laws (`magat check contract`)

Each law is a runnable check over captured positions. The shakedown and CI run them all.

| # | Law |
|---|---|
| L1 | `copy` isolates: advancing the copy leaves the original's `legalActions` keys, `evaluate`, `activePlayer` and `isTerminal` unchanged |
| L2 | Determinism: two copies reseeded alike and advanced through the same action keys agree on every L1 observable after every step |
| L3 | Every action in `legalActions(s)` satisfies `isLegal(s, a)`; within the active player's turn, a non-terminal state has at least one legal action |
| L4 | `isLegal` never throws, and is false for actions from another state whose referents do not exist (stale ids) |
| L5 | Applying `endTurnAction(s)` satisfies `endsTurn` and changes the active player or the turn counter |
| L6 | `deserialize(serialize(s))` agrees with `s` on every L1 observable after a fixed action sequence |
| L7 | `actionKey` is injective over `legalActions(s)` |
| L8 | `actionClass(FINE)` determines `actionClass(MID)`, which determines `actionClass(COARSE)` |
| L9 | `evaluate` is deterministic, finite, and independent of the meter's state |
| L10 | After N advances, copies and evaluations through `MeteredGame`, the meter reads exactly N of each: no forward-model work bypasses the contract |

## The agent protocol

One replanned turn is an `Episode` (`core/src/magat/agent/Episode.java`):

```java
MeteredGame<S, A> metered = MeteredGame.of(game);
long seed = Seeds.visit(scenario.id, visit);            // scenarioId * 1_000_000 + visit
S state = metered.inner().copy(scenario.state);         // the working copy is not the agent's spend
metered.reseed(state, seed);                            // the same state and the same randomness for every arm
Meter.declare(arm.operations());
Meter.reset();
Meter.useProfile(Profile.key(arm.id(), budgetUnits));  // the arm's own price: "<arm>@<budget>"
TurnBudget budget = TurnBudget.units(budgetUnits);
SearchAgent<S, A> agent = arm.create(seed, budget);     // a fresh agent per turn
TurnRecord record = agent.plan(metered, state, metered.activePlayer(state), budget);
// then: refused if it recorded an undeclared operation, or one its version-2 profile does not price
```

`Episode` installs the profile from the arm id and the budget, so an arm cannot run under a price that
is not its own. Because every arm replans the same position with the same seed, results are paired.

### `AgentFactory<S, A>`, one per arm

| Member | Meaning |
|---|---|
| `String id()` | the arm id: lowercase, kebab-case, permanent once a result is published under it. It is the profile key's left half (`<arm>@<budget>`) and the results directory's name |
| `SearchAgent<S,A> create(long seed, TurnBudget budget)` | a fresh agent for one turn |
| `String attributionControl()` | the arm that is this one with its mechanism replaced by noise, or `null`. The validator requires it in the grid |
| `boolean priced()` | false for an arm whose work does not depend on the budget (the random floor); such an arm is not calibrated |
| `List<String> operations()` | the algorithm's own operations, which a version-2 currency prices ([`CURRENCY.md`](CURRENCY.md)) |
| `String description()` | one line for banners and `magat arms` |

### `SearchAgent<S, A>`

`TurnRecord plan(Game<S,A> game, S state, int player, TurnBudget budget)` plans one turn from the
agent's own copy of the position. `support()` reports mechanism counters for `magat probe support`;
it is empty for agents without a learned mechanism.

### `TurnBudget`

- `beginSearch()` snapshots the initialization spend and resets the meter. In the budget, the default,
  the search then gets what the initialization left, so the whole turn spends the budget; outside it
  (the thesis's rule, `"initialization": "outside-budget"`) the budget bounds the search alone. An
  agent with no initialization phase never calls it, and the whole turn counts.
- `exhausted()` is `Meter.units() >=` the search's share.
- `ceiling(phase, phases)` is the EMCTS phase rule, `(phase+1) * share / phases`, whole units.
- `units()` is the budget.

### `TurnRecord`

| Field | Meaning |
|---|---|
| `initial`, `finalValue` | the value of the starting plan, and of the best plan found |
| `terminal` | whether the best plan reaches a terminal state |
| `advances`, `copies`, `evals`, `units` | the search's spend after `beginSearch()`: raw counts always, units priced by the profile in force |
| `operations` | the count of each declared operation (one `op_<name>` column each in `turns.csv`) |
| `initAdvances` ... `initUnits`, `initMillis`, `initOperations` | the initialization's spend, reported separately and paid from the budget (unless the turn is budgeted outside it) |
| `planMillis` | the wall-clock of the whole episode: co-primary evidence, never a budget |
| `nodes`, `unique`, `duplicates` | candidates evaluated, the distinct plans among them, and the candidates whose plan had already been evaluated |
| `sequenceLength` | the actions in the final plan |
| `curve` | anytime checkpoints: (index, best so far, advances, units, ms) |
| `telemetry` | agent-defined counters, written as `t_<key>` columns |

### What an agent must promise

- **All forward-model work goes through the `Game` it was given.** A game-agnostic agent has no other
  handle. For Tribes the only escape hatch is greppable, and `tools/check_layering.py` keeps it in the
  adapter.
- **Its only randomness is `new Random(seed)` as given, consumed in a fixed order.** Two arms that differ
  only in a switched-off mechanism consume identical random streams, which is what makes an attribution
  control a control.
- **`beginSearch()` is called at most once**, at the boundary the agent documents.
- **It records exactly the operations its factory declares**: a turn that records an undeclared one is
  refused.

[`ADDING_A_GAME.md`](ADDING_A_GAME.md) and [`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md) walk
through each side. `MAGAT/DESIGN.md` §4–5 is the full design, with the Tribes adapter method by
method.
