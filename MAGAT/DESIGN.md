# MAGAT v0.1 — design: package layout, the `Game` contract, the CLI surface

*Proposed 2026-09-10, for review before any extraction starts. `BRIEF.md` is the design authority;
this document says how its seven components and one contract become code, what is moved from the
thesis tree (`Tribes-master/`), what is generalised behind the contract, and in what order. Nothing
here has been built. File references of the form `Tribes-master/src/...` point at the code that is
extracted.*

---

## 0. What was understood, and what is still open

The decisions confirmed on 2026-09-09 (brief §8) are taken as fixed:

| Decision | How this design honours it |
|---|---|
| Java core + Python analysis; metering in-process | Java 11 core; the meter is a decorator over the forward-model contract, on the calling thread, no process boundary (§2.2, §6). Python owns every statistic (§11). |
| v0.1 = core + Tribes | One game, one adapter. The contract is written so a second engine can be added without touching core, but no second game is attempted (§4, §15). |
| Tribes as submodule / dependency, adapter in MAGAT | The engine lives in its own repository, referenced as the git submodule `games/tribes/engine`; `games/tribes/` in MAGAT holds only the adapter, the oracle wrappers, the published calibration and the worked result. Nothing from the engine is copied into MAGAT's tree (§15.3). |
| Cross-machine promise | Published R7615 weights, `magat calibrate --check` drift report, raw op counts on every result row, and re-pricing in the analyzer (§6.5, §14). |
| Reference agents `Random`, plain EMCTS, `Comma_Z0_Coarse` | Ported onto the contract as `random`, `emcts`, `comma-z0-coarse`, each gated against its thesis-tree oracle (§5.4, §12). |
| License | Open. Recommendation in §17; nothing here depends on the choice. |

One thing the brief leaves implicit and this design has to resolve: **"plain EMCTS" is two classes in
the thesis tree.** `players/emcts/EMCTSAgent` is the cell the currency is anchored to (`EMCTS@40000`);
`players/ExperimentalEMCTS/ExperimentalEMCTSAgent` with default parameters is the `Baseline` arm every
action-bias contrast was paired against, and it carries the selection modes (plus / comma) the strong
reference needs. MAGAT ports the second and keeps the first only as an oracle. The currency's anchor
(the reference advance in nanoseconds) is kept exactly as measured; §6.3 says how the two are
reconciled in the published weights file.

Two decisions below go beyond "move and rename" and are flagged for review rather than taken
silently: **metering by decorator** (§2.2) and **porting the reference agents onto the contract**
(§2.3). Everything else is extraction.

---

## 1. Scope of v0.1

**In.** The seven components of brief §6, one game (Tribes), three reference agents plus the arms the
worked result needs, the analyzer producing all twelve measures of brief §4, `run_magat.ipynb`, the
shakedown, the published R7615 calibration, and the AB-EMCTS holdout result reproduced from the
archived run trees as MAGAT's worked example.

**Out**, deliberately, each with where it lives on:

| Not extracted | Why | Where it stays |
|---|---|---|
| Stage 0 (`Stage0MutationLogAgent`, `Stage0SignalRunner`, `analyze_stage0.py`) | thesis-specific observation line, not part of the methodology | thesis tree history |
| Full-game runners, the Stage G yardstick, `FullGameMapSetV6` | measure #12 is the optional yardstick; v0.2 at the earliest | thesis tree history |
| The v6 crossover agents (`CrossoverBB/Expansion/Combo`) | not reference agents; portable later by the §5.4 recipe | engine fork (`players/`) |
| Tribes' own agents (MCTS, RHEA, OEP, TBETS, portfolio agents) | engine property; a `players.Agent` bridge is v0.2 | engine fork |
| `BudgetFloorProbe`, `BudgetFairnessProbe`, `CalibrationRunnerV6` (C0) | v6 diagnostics superseded by W7 and the drift check | thesis tree history |
| `NewExperimentSmokeTest*`, `VerifyV6Output`, `RunExperimentV*`, `Phase*Runner`, `GameLengthProbeV6`, `ThreadBenchmark`, `FmComputeProbe`, `ComputeCalibrate` (v1), TBETS runners, OEP tuning, level-generator Python, MAP-Elites | earlier lines | thesis tree history |

---

## 2. Architecture

### 2.1 Components and the direction of dependencies

```
                 +--------------------------- Python ----------------------------+
                 |  analysis/  (magat_analysis: load -> measures -> report)      |
                 |  magat launcher, notebook generator, shakedown, bundles       |
                 +------------------------------+--------------------------------+
                                                | CSV/JSON on disk, JVM invocations
+------------------------------------ Java: magat (core) ------------------------------------+
|  meter --> game (contract, MeteredGame) --> agent (SearchAgent, TurnRecord, registry)       |
|                    ^                              ^                                         |
|  portfolio --------+     calibrate ---------------+     run ---------+   probe              |
|                    |                              |                  |                      |
|                    +------------- cli (Main: one entry point) <------+----------------------+
+--------------------------------------------------------------------------------------------+
                                                ^
+---------------------------- Java: games/tribes (adapter) ----------------------------------+
|  TribesGame implements Game<TribesState, Action>; TribesScenarioSources; TribesAgents       |
|  (registry entries for this game); oracle wrappers over the engine's thesis agents          |
+--------------------------------------------------------------------------------------------+
                                                ^
                              games/tribes/engine (git submodule: the Tribes fork)
```

Rules, enforced by `tools/check_layering.py` in CI: core (`magat.*` except `magat.games.*`) imports
nothing from `magat.games.*`, `core.*`, `players.*` or `utils.*`; the adapter imports core and the
engine; the engine imports neither. The Python side never imports Java; it reads what Java writes.

### 2.2 Decision A — metering is a decorator over the contract

Today every forward-model call is counted inside the engine (`GameState.advance` and `GameState.copy`
call `ComputeMeter.onAdvance/onCopy`; `TribesSimpleHeuristic.evaluateState` calls `onEval`), and the
opt-in nanosecond timing sits at the same three sites. That is right for one engine and wrong for a
framework: every new game would be patched in three places, and nothing stops an agent from calling
the engine directly and running unmetered.

In MAGAT the meter is `magat.game.MeteredGame<S,A>`, a decorator that every runner, calibrator and
probe wraps around the game before handing it to an agent. `advance`, `copy` and `evaluate` count
(and, when profiling is on, time) exactly the span the engine hooks time today, plus one virtual call.

- A game is metered by implementing the contract. No engine source is touched for metering.
- An agent written against `Game<S,A>` cannot bypass the meter. The Tribes adapter's state type
  `TribesState` keeps the raw `GameState` private, so reaching the engine directly is an explicit,
  greppable escape hatch (`TribesState.raw()`), never an accident.
- The published R7615 weights were measured with the engine hooks. The decorator times the same
  operation over the same span; the added indirection is a few nanoseconds against operations of
  20–90 µs. `magat calibrate --check` measures the residual on the reference machine instead of
  assuming it (§8.4).

**Residual.** The engine fork keeps its own `core.game.ComputeMeter` untouched, because the thesis
agents that serve as oracles (§12.2) gate their budgets on it. MAGAT never reads it. When the oracles
are retired after v0.1, the engine hooks go with them. While MAGAT runs, the engine's counter costs one
thread-local increment per operation, which is noise; §8.4 measures it anyway.

### 2.3 Decision B — the reference agents are ported onto the contract

The alternative is to leave `EMCTSAgent`, `ExperimentalEMCTSAgent` and `Stage1BiasedAgent` as Tribes
agents behind a thin wrapper. It is cheaper, and it would make the contract a fiction: the agents call
`GameState` at roughly forty sites each, `Stage1BiasedAgent` keys its table on Tribes unit and building
types, and a newcomer copying "the reference agent" would inherit Tribes code. The framework's promise,
*add your algorithm and run it against the portfolio*, only holds if the reference algorithms are
themselves written against the contract, so that a game supplies the substrate and an algorithm runs on
every game.

So `magat.agents.emcts` holds the ported search: `Emcts` (plain, with the selection modes) and
`QdeltaEmcts` (the learned action-value table — the thesis's AB-EMCTS — with the insertion bias, repair
modes and granularities as parameters). The port is **line-for-line with type substitutions**
(`GameState` → `S`, `Action` → `A`, `gs.copy()` → `game.copy(s)`, the agent's `advance` helper →
`game.advance`, `heuristic.evaluateState(old, s)` → `game.evaluate(s, player)`, `allGoodActions` →
`game.legalActions`, `checkActionFeasibility` → `game.isLegal`, `END_TURN` tests → `game.endsTurn`,
`Action.toString()` → `game.actionKey`, `actionOrd`/`qualifierOf` → `game.actionClass(s, a, level)`),
not a re-implementation. About 2,900 lines of agent code are affected. This is the one piece of v0.1
nearer "rewrite" than "move", and two gates make it safe:

1. **Oracle equivalence** (§12.2): every ported arm runs beside the thesis-tree agent it was ported
   from, on many portfolio positions, and must reproduce initial value, final value, advances, copies,
   evaluations, nodes and unique sequences — the existing `Stage1Runner verify` gate, widened.
2. **Published-row replay** (§12.5): the archived holdout rows carry those fields for every
   (scenario, visit); on the rebuilt portfolio under the published weights the port must reproduce
   them. That is the equivalence gate against the data the worked result rests on.

The thesis agents stay in the engine fork as oracles until both gates pass for every ported arm.

---

## 3. Repository layout

```
Multi-Action-Game-Algorithm-Tester/           (this repository)
+-- README.md            what MAGAT is, the afternoon path (§14.2), credits (Tribes authors, AIIDE 2020)
+-- LICENSE              (§17)
+-- CITATION.cff
+-- magat.py             the launcher (Python, §13.1): python magat.py build, ...; bin/magat and magat.cmd wrap it
+-- core/                Java core (package root `magat`; the directory is `core/` because `MAGAT/` holds the
|   |                    founding documents and the two names collide on a case-insensitive filesystem)
|   +-- src/magat/meter/        Meter, Weights, Profile
|   +-- src/magat/game/         Game, MeteredGame, Granularity, Scenario, ScenarioSource, Capture, ContractCheck, GameModule
|   +-- src/magat/agent/        SearchAgent, AgentFactory, AgentRegistry, TurnBudget, TurnRecord, Seeds, Episode, Oracle, Signature
|   +-- src/magat/agents/       random/RandomAgent; emcts/{Emcts, EmctsParams, Selection, Node, EmctsFactory, QdeltaEmcts, QdeltaParams, ActionTable}
|   +-- src/magat/portfolio/    Portfolio (loader), PortfolioBuilder, CaptureJournal, Manifest, Identity, Split, HoldoutLedger
|   +-- src/magat/calibrate/    Calibrator, Cell, Estimator, LoadHarness, DriftReport, ThreadSweep
|   +-- src/magat/machine/      MachineProfile (was ServerProfile)
|   +-- src/magat/run/          Experiment, GridRunner, Leaf, Checkpoint, ResultsWriter, LoadGovernor, RunGuard, Provenance
|   +-- src/magat/probe/        EquivalenceCheck, StructureCheck, SupportProbe, ReplayCheck
|   +-- src/magat/cli/          Main, Args, Games, one class per command
|   +-- test/                   JUnit 4, one test class per component, plus the toy game the core tests run on
|   +-- lib/                    minimal-json (MIT) — §15.4; junit and hamcrest come from the engine's lib/
+-- games/tribes/
|   +-- engine/                 git submodule -> the Tribes fork (§15.3)
|   +-- src/magat/games/tribes/ TribesGame, TribesState, TribesScenarioSources, TribesAgents, oracle/*
|   +-- calibration/r7615/      compute_weights.properties (v1), compute_profiles.csv, machine.txt, ORIGIN.md
|   +-- portfolio/v1/           portfolio.csv, _split_dev.txt, _split_holdout.txt, identity.txt (bins: release asset, §7.6)
|   +-- results/ab-emcts/       the worked result: imported dev + holdout trees, tables, REPORT.md (§11.6)
+-- analysis/                   Python package `magat_analysis`, pyproject.toml, tests/
+-- experiments/tribes/         specs (JSON): reference-ladder.json, ab-emcts-dev.json, ab-emcts-holdout.json, shakedown.json
+-- notebooks/run_magat.ipynb   generated by tools/make_notebook.py; never hand-edited
+-- tools/                      make_notebook.py, notebook_selftest.py, shakedown.py, check_layering.py, import_legacy.py, hooks/pre-commit
+-- docs/                       CONTRACT.md, CURRENCY.md, METHODS.md, OPERATIONS.md, ADDING_A_GAME.md, ADDING_AN_ALGORITHM.md, TRAPS.md, results/tribes-ab-emcts.md
+-- MAGAT/                      BRIEF.md, STARTING_PROMPT.md, DESIGN.md — the founding documents, kept
+-- .github/workflows/ci.yml    build (JDK 11/17/21), unit tests, layering, contract laws, oracle gate on 2 scenarios, toy shakedown
```

`Tribes-master/` is deleted from this repository once the engine fork exists and each component's
extraction is complete; until then it is the source being read from. Run output never lives in the
clone: the data root defaults to `~/magat_runs` (§10.3).

---

## 4. The `Game` contract

### 4.1 `Game<S, A>`

Java 11, generic in the state type `S` and the action type `A`. Every method is total (no exceptions
for ordinary inputs) and deterministic. The comments say what each method is for in the methodology;
the laws they must obey are in §4.4.

```java
package magat.game;

public interface Game<S, A> {

    // ---- identity and provenance ----------------------------------------------------------
    /** Short stable id, e.g. "tribes". Names the currency, the portfolio and the results tree. */
    String id();
    /** Every ruleset/engine setting that changes what a position or a forward-model call means
     *  (engine commit, game mode, rule flags). Frozen into the portfolio manifest and every run's
     *  provenance; a run refuses a portfolio whose configuration differs. */
    Map<String, String> configuration();

    // ---- the forward model (every call metered by MeteredGame) -----------------------------
    S copy(S state);
    /** Applies one action in place. The action came from legalActions(state) or from an earlier
     *  state of the same turn (a sequence being repaired), so it may be stale. */
    void advance(S state, A action);
    /** The actions the active player may take now, filtered to the ones a search should consider
     *  (Tribes: without DESTROY and DISBAND). Empty only at a terminal state or a turn boundary. */
    List<A> legalActions(S state);
    /** Cheap, side-effect-free, never throws: is this (possibly stale) action applicable here? */
    boolean isLegal(S state, A action);
    /** Heuristic value of the state from one player's point of view. Finite; a terminal state may
     *  carry a large sentinel, which TurnRecord.terminal reports separately. */
    double evaluate(S state, int player);
    boolean isTerminal(S state);

    // ---- turn structure ---------------------------------------------------------------------
    int activePlayer(S state);
    int playerCount(S state);
    /** True if applying this action ends the active player's turn. */
    boolean endsTurn(A action);
    /** The explicit end-of-turn action in this state, or null if the game ends turns implicitly. */
    A endTurnAction(S state);

    // ---- identity and abstraction of actions ------------------------------------------------
    /** Canonical identity of an action within a state; sequences are de-duplicated on it.
     *  Two distinct legal actions of one state never share a key (law L7). */
    String actionKey(A action);
    /** The action abstracted to a granularity, for learned tables: COARSE ("MOVE"), MID
     *  ("MOVE:RIDER"), FINE (the full identity). Coarser levels partition finer ones (law L8). */
    String actionClass(S state, A action, Granularity level);
    /** A descriptive count for stratification and reporting; the game says what it counts. */
    int branchingFactor(S state);

    // ---- determinism and persistence ----------------------------------------------------------
    /** Pins the state's own stochasticity (e.g. ruin outcomes) so two copies reseeded alike behave
     *  alike under the same actions. */
    void reseed(S state, long seed);
    byte[] serialize(S state);
    S deserialize(byte[] bytes);
    /** Small descriptive facts about a position for the manifest (Tribes: active tribe, cities,
     *  units, score, action-type composition). Deterministic; never used for pairing. */
    Map<String, String> describe(S state);

    // ---- capture ------------------------------------------------------------------------------
    /** Named generators of positions from played games. The portfolio builder schedules, journals
     *  and stratifies; a source only plays and validates. */
    List<ScenarioSource<S>> scenarioSources();
}
```

```java
public enum Granularity { COARSE, MID, FINE }

public interface ScenarioSource<S> {
    String name();                 // "single", "dual"
    int[] phases();                // capture points within a source game (Tribes: ticks)
    int maxAttempts();             // attempt cap before the builder gives up on this source
    int defaultTarget();           // captures the published portfolio takes from this source (Tribes: 200)
    String definition();           // one line naming everything that determines what play() yields;
                                   // written into the manifest and keys the build journal
    /** Plays source game #attempt (deterministic in attempt) and returns its validated captures in
     *  phase order. Applies the source's own validity predicate and stuck-game rule. Swallows engine
     *  failures: a bad seed yields fewer captures. Anything escaping it stops the build. */
    List<Capture<S>> play(int attempt);
}

public final class Capture<S>  { int attempt; long seed; int phase; S state; }
public final class Scenario<S> { int id; String source; int attempt; long seed; int phase;
                                 int branching; S state; Map<String,String> facts; }
```

### 4.2 What is deliberately not in the contract

- No `act()`-per-action game loop, no `Agent.copy()`, no timers. The isolation instrument replans one
  turn; the engine's own turn loop is used only inside `ScenarioSource.play`.
- No score. `ScoreAtTurnStart` was written into every results row and read by nothing; a game that
  wants it puts it in `describe()` at capture time.
- No pruning heuristics, no partial observability. Both are engine policies a `ScenarioSource` fixes
  when it captures.
- No wall-clock. `TurnRecord` reports it; nothing in the contract consumes it.

### 4.3 The Tribes adapter, method by method

| Contract | `TribesGame` implementation | Extracted from |
|---|---|---|
| `id` | `"tribes"` | — |
| `configuration` | engine submodule commit; game mode per source; `REMOVE_FISHING=true`; `MAX_TURNS_CAPITALS`; `TURN_TIME_LIMITED=false`; heuristic `SIMPLE`; `LOSE_BOOST` fix present | `Stage1Runner.applyEngineConstants`, `BuildV5Portfolio` |
| `copy` | `raw.copy()` (the deterministic-cascade copy) | `GameState.copy()` |
| `advance` | `raw.advance(a, true)` | `EMCTSAgent.advance` |
| `legalActions` | the "good actions" filter: city actions minus DESTROY, unit actions minus DISBAND, tribe actions, each `isFeasible` | `Agent.allGoodActions` |
| `isLegal` | actor-kind check (a unit id resolves to a unit of the active tribe; a city id to a city), then `isFeasible` inside try/catch | `EMCTSAgent.checkActionFeasibility` |
| `evaluate` | `TribesSimpleHeuristic(player, allIds).evaluateState(raw)`, one cached instance per player | `AlgParams.getStateHeuristic` |
| `isTerminal` | `raw.isGameOver()` | — |
| `activePlayer` / `playerCount` | `raw.getActiveTribeID()` / `raw.getTribes().length` | — |
| `endsTurn` / `endTurnAction` | `a.getActionType() == END_TURN` / the END_TURN among the tribe actions | `EMCTSAgent.randomActions` tail |
| `actionKey` | `a.toString()` (the identity `ActionKeyCollisionProbe` guards) | `Stage1Params.Granularity.FINE` |
| `actionClass` | COARSE `ACTION` name; MID `ACTION:qualifier` (unit type / building type / spawned unit / tech); FINE `toString()` | `Stage1BiasedAgent.actionOrd/qualifierOf/fineKeyOf` |
| `branchingFactor` | `raw.getAllAvailableActions().size()` — the engine's own unfiltered count, as the published manifest defines it | `BuildV5Portfolio.persistAndManifest` |
| `reseed` | `raw.setRandomGenerator(new Random(seed))` | `Stage1Runner.runOneTurn` |
| `serialize` / `deserialize` | Java serialization + gzip of `GameState`; transient action caches rebuilt on load | `FastGameStateIO` |
| `describe` | active tribe, cities, units, score, attack / move / other-unit / city / tribe action counts | `BuildV5Portfolio.persistAndManifest` |
| `scenarioSources` | `single`: EMCTS vs DoNothing, SCORE, ticks 10..30 step 2, cap 150 attempts; `dual`: EMCTS vs EMCTS, CAPITALS, ticks 15/20/25, cap 1500. Validation: at least one city and one unit, score ≥ 500, a 50 ms smoke plan does not throw. Stuck-game rule: first capture invalid ⇒ abandon the attempt | `BuildV5Portfolio.runOneSourceGame/validateScenario`, `Game.captureAtTickAsTurnStart` |

Engine globals (`core.Constants`) are set exactly once, in `TribesGame`'s constructor, and their
values are what `configuration()` reports. No runner touches them.

Source games are played by the engine's own `EMCTSAgent` at a **fixed, documented weight set**
(`1 / 0.16 / 0.077`, the compiled defaults the R7615 build ran under), never by the calibrated
currency. A portfolio must not depend on a calibration, or a recalibration would silently rebuild it.

### 4.4 Contract laws, asserted by `magat check contract`

Each law is a runnable check over a handful of captured positions; the shakedown runs them all.

| # | Law |
|---|---|
| L1 | `copy` isolates: advancing the copy leaves the original's `legalActions` keys, `evaluate`, `activePlayer` and `isTerminal` unchanged. |
| L2 | Determinism: two copies reseeded alike and advanced through the same action keys agree on every L1 observable after every step. |
| L3 | Every action in `legalActions(s)` satisfies `isLegal(s, a)`; `legalActions` of a non-terminal state within the active player's turn is non-empty. |
| L4 | `isLegal` never throws and is false for actions taken from a different state whose referents do not exist (the stale-id family `StaleActorIdTest` covers for Tribes). |
| L5 | Applying `endTurnAction(s)` satisfies `endsTurn` and changes `activePlayer` or the turn counter. |
| L6 | `deserialize(serialize(s))` agrees with `s` on every L1 observable after a fixed action sequence. |
| L7 | `actionKey` is injective over `legalActions(s)`. |
| L8 | `actionClass(FINE)` determines `actionClass(MID)` determines `actionClass(COARSE)`. |
| L9 | `evaluate` is deterministic, finite, and independent of the meter's state. |
| L10 | After N advances, copies and evaluations through `MeteredGame` the meter reads exactly N of each; for Tribes only, the engine's own counter agrees (no forward-model work bypasses the contract during a planning turn). |

---

## 5. Agents

### 5.1 The per-turn protocol

One replanned turn is an `Episode`. It is the same sequence `Stage1Runner.runOneTurn`,
`ComputeCalibrateV6.runTurn` and `ActionBiasFloorProbe.planOneTurn` each spell out today, written once:

```java
TurnRecord Episode.run(Game<S,A> game, Scenario<S> sc, int visit, AgentFactory<S,A> arm, long budgetUnits) {
    long seed = Seeds.visit(sc.id, visit);                 // scenarioId * 1_000_000 + visit, unchanged
    S s = game.copy(sc.state);  game.reseed(s, seed);       // paired: same state, same stochasticity, every arm
    Meter.reset();  Meter.useProfile(arm.id() + "@" + budgetUnits);
    TurnBudget budget = TurnBudget.units(budgetUnits);
    SearchAgent<S,A> agent = arm.create(seed, budget);      // fresh agent per turn, as today
    return agent.plan(game, s, game.activePlayer(s), budget);
}
```

`Seeds.visit` is the existing `visitSeed`, so pairing is unchanged and the published rows remain
replayable.

### 5.2 Interfaces

```java
public interface AgentFactory<S, A> {
    String id();                                             // the arm id: "emcts", "comma-z0-coarse"
    SearchAgent<S, A> create(long seed, TurnBudget budget);
    /** Which arm is the attribution control for this one, or null. Read by the spec validator. */
    default String attributionControl() { return null; }
}

public interface SearchAgent<S, A> {
    TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget);
    /** Mechanism-support counters for SupportProbe (§12.4); empty for agents without a mechanism. */
    default Map<String, Double> support() { return Map.of(); }
}

public final class TurnBudget {
    /** Snapshots initialization spend and resets the meter: the budget bounds what follows.
     *  An agent with no initialization phase never calls it and the whole turn counts. */
    void beginSearch();
    boolean exhausted();           // Meter.units() >= units
    double ceiling(int phase, int phases);   // (phase+1) * units / phases — the EMCTS phase rule
    double units();                // the budget
}
```

`TurnRecord` (the union of `TurnData`, the per-turn columns of `Stage1Runner.RESULTS_HEADER`, and the
generic part of the telemetry row):

| Field | Meaning |
|---|---|
| `initial`, `final` | value of the starting plan and of the best plan found |
| `advances`, `copies`, `evals`, `units` | search spend after `beginSearch()`; raw counts always, units priced by the profile in force |
| `initAdvances`, `initCopies`, `initEvals`, `initUnits`, `initMillis` | initialization spend, reported and excluded from the budget |
| `planMillis` | wall-clock of the whole episode (co-primary evidence, never a budget) |
| `nodes`, `unique`, `duplicates` | candidates evaluated, distinct sequences among them, candidates whose sequence had already been evaluated |
| `terminal` | the best plan reaches a terminal state (replaces the ±1e9 magnitude test) |
| `sequenceLength` | actions in the final plan |
| `curve` | anytime checkpoints: (index, best so far, advances, units, millis) — the bridge-burn rows, generalised |
| `telemetry` | `Map<String, Double>` of agent-defined counters, written as extra columns (`t_<key>`) |

### 5.3 Registry and arm ids

`AgentRegistry` maps arm ids to factories. Core registers the game-agnostic agents; `TribesAgents`
registers the same ids for Tribes (the factories are generic, the game supplies the substrate) plus
any Tribes-only arms. An arm id is the profile key's left half and the results directory name, so it
is lowercase, kebab-case, and stable forever once a result is published under it.

Adding an algorithm is: implement `SearchAgent` against `Game<S,A>`, register an `AgentFactory` with
an id, name its attribution control if it has a mechanism, price it (`magat calibrate --arms <id>`),
run it. `docs/ADDING_AN_ALGORITHM.md` walks through it with `random` as the example.

### 5.4 The reference agents and the worked-result arms

The renames, old → new. The old names are the profile keys and directory names in the archived run
trees; `tools/import_legacy.py` applies this table (Appendix A has the complete list).

| New id | Old name | Definition |
|---|---|---|
| `random` | `RandomAgent` (approx.) | uniform legal action until the turn ends; the floor |
| `emcts` | `Baseline` (= `ExperimentalEMCTSAgent` defaults) | plain EMCTS: greedy 1-ply initialization, 21 phases / 20 burns, (1+1) incumbent selection, exhaustive greedy repair |
| `emcts-comma` | `Comma_Off` | the same with (1,5) comma selection — the pinned substrate and the baseline of the worked result |
| `comma-z0-coarse` | `Comma_Z0_Coarse` | `emcts-comma` + learned table, zero-evaluation repair by coarse-key argmax, no insertion bias — the strong reference |
| `comma-z0-random` | `Comma_Z0_Random` | the attribution control of the above: table replaced by a uniform draw, same RNG draws |
| `comma-rand15` | `Comma_Rand15` | shortlist of 15 arbitrary candidates, then evaluated — the throughput-only rung |
| `comma-short15-coarse` | `Comma_Short15_Coarse` | shortlist of 15 ranked by coarse keys, then evaluated |
| `comma-short15` | `Comma_Short15` | shortlist ranked by mid keys (Stage D's champion; the anchor) |
| `comma-z0-mid`, `comma-z0-fine` | `Comma_Z0_Mid`, `Comma_Z0_Fine` | the granularity ladder |

`QdeltaEmcts` carries every parameter the thesis's `Stage1Params` has (`epsilon`, `insertKeys`,
`repairKeys`, `removalCredit`, `perBurnUpdates`, `repairMode`, `shortlistK`, `shrinkageM`) plus the
selection mode and offspring count from `ExperimentalEMCTSParams`, so the remaining development-tree
arms (`comma-bias-e25` etc., Appendix A) are registry entries, not new code. The table keys come from
`game.actionClass`; the arrays indexed by Tribes ordinals become an interning index per turn, which
changes no forward-model call and no random draw (the oracle gate proves it).

### 5.5 What an agent must promise

- All forward-model work goes through the `Game` it was given (the metered one). No other handle
  exists for a game-agnostic agent; for Tribes the escape hatch is greppable (§2.2).
- The only randomness is `java.util.Random(seed)` as given, consumed in a fixed order; two arms that
  differ only in a switched-off mechanism consume identical streams (the "same number of RNG draws"
  discipline `Comma_Rand15` was built with).
- `beginSearch()` is called at most once, at the boundary the arm documents.

---

## 6. The meter and the currency

### 6.1 `Meter`

`core/game/ComputeMeter.java` moved to `magat.meter.Meter`, thread-local throughout, with the same
surface: `onAdvance/onCopy/onEval`, `addXNanos`, `PROFILE`, `reset`, `resetNanos`, counts, nanos,
`useProfile(String)`, `units()` (the weighted total in the profile in force). Three changes:

1. **No compiled default file path.** `ComputeMeter` defaulted to `test_results/v6/_calibration/...`
   (trap A.3). `Meter` has no path at all; `Weights` are loaded explicitly by the CLI from
   `--weights` and installed once. A profile asked for while no weights are installed, or missing from
   them, is a hard error unless `--allow-unpriced` was passed (the existing `requireProfiles`, moved to
   the loader so it cannot be forgotten by a caller).
2. **Profile key is `<arm>@<budget>`.** The `_r<ratio>` segment and the `AB_` prefix were v6 and
   action-bias namespaces; the arm id now carries every variant.
3. Counting and timing are called by `MeteredGame`, not by the engine (§2.2).

### 6.2 The unit

Unchanged from brief §3 and `COMPUTE_UNIT_CALIBRATION.md` §2: one unit is one `advance` by the
reference agent at the reference budget on the game's reference portfolio positions, on the machine
the weights were calibrated on. Every weight, `wAdvance` included, is a ratio to that operation's
nanoseconds; per-(arm, budget) profiles; each game its own currency. The reference for Tribes is
`emcts@40000`.

### 6.3 Weights file, version 1

`java.util.Properties`, so both sides read it natively:

```
magat.weights.version = 1
game = tribes
machine = AMD EPYC 9534 32-Core Processor | 64 physical / 128 logical | Dell PowerEdge R7615
java = 11.0.x            measured = 2026-08-28
reference = emcts@40000
referenceAdvanceNanos = 60873.6
turnsPerRep = 8,4,2
portfolio.identity = <sha256 of the portfolio identity the cells were measured on>
levels = 40000,80000,400000
profile.emcts@40000.wAdvance = 1.000000
profile.emcts@40000.wCopy    = ...
profile.emcts@40000.wEval    = ...
profile.emcts@40000.advanceNanos = ...   copyNanos = ...   evalNanos = ...
profile.comma-z0-coarse@80000.wAdvance = ...
...
```

Two additions to what the thesis file carried: `portfolio.identity` (turns-per-rep selects positions
out of a portfolio, so a weights file is tied to one; a check that measures another portfolio is
comparing unlike things — trap A.5 one level up) and per-op nanoseconds for every profile (free, and
what re-pricing and the drift report want).

**Importing the R7615 file.** `games/tribes/calibration/r7615/ORIGIN.md` records the mapping:
`EMCTS@<b>` → the anchor (`referenceAdvanceNanos` and the global fallback), kept as the profile
`emcts-engine@<b>` that MAGAT never runs; `AB_Baseline@<b>` → `profile.emcts@<b>`; every
`AB_Comma_*` → its new id. Both originals are kept verbatim beside the converted file.

*Done 2026-09-10 (milestone 1) by `tools/import_r7615.py` from the archived `_calibration` and
`_portfolio` directories: 105 profiles (35 arms × 3 levels), the portfolio identity
`ec243fd7…` verified against the frozen split files.*

### 6.4 Where the profile is installed

By `Episode`, from the arm id and the budget, before the agent is created (§5.1). Agents no longer
carry a `computeProfile` field, so an arm cannot be run under a profile that is not its own, and the
"agent never installs its profile / inherits the previous turn's" failure mode disappears.

### 6.5 Re-pricing

Every result row carries `advances`, `copies`, `evals` (§10.1). `magat analyze --reprice
other.properties` recomputes each turn's units with another machine's weights and re-scores every
arm against the baseline's value-versus-spend curve in that currency, exactly as W7 re-scores against
wall-clock. What re-pricing cannot do is re-run the stop rule: the search stopped under the weights it
ran with. The report says so on every re-priced table.

---

## 7. Portfolio

### 7.1 Builder

`BuildV6Portfolio` + the rules in `BuildV5Portfolio`, generalised: the builder owns scheduling
(continuous parallelism with bounded look-ahead, attempts consumed in order), the append-only
`CaptureJournal` (bins first, then one `END`-terminated index line per attempt, torn lines replayed),
target counts per source, sequential id assignment across sources in declared order, and the
completion marker. The `ScenarioSource` owns everything game-specific. Output is exactly what the
runner reads, so the consumer/producer check of trap A.3 is one code path.

*Amended 2026-10-04 (milestone 2), three details settled in the extraction:*
- *The journal is keyed by the game, its configuration and each source's `definition()`, not by the
  targets: what an attempt yields does not depend on how many captures are wanted, so a toy build
  grows into a full one without replaying a game, while a changed source never resumes from the old
  one's games.*
- *An exception escaping `play` stops the build (journal kept). The thesis builder read it as "this
  attempt yielded nothing", which can change the portfolio without a word; a source already swallows
  its own engine failures, so whatever escapes is a bug.*
- *A capture labelled with an attempt other than the one played is refused, and a frozen portfolio
  (one with split files) is never rebuilt in place.*

### 7.2 Manifest, version 1

`portfolio.csv`: `ScenarioId,Source,SourceAttempt,SourceSeed,Phase,Branching,` followed by the
`describe()` columns in the game's order. A header block (`# ` lines) carries `game`, the
`configuration()` map, the source definitions and the builder's fixed capture weights. Bins are
`scenarios/scenario_NNN.bin`, opaque bytes from `serialize`.

*Amended 2026-10-04 (milestone 2): the header is `# key: value` lines (`magat-portfolio: 1`, `game`,
`configuration.<key>`, `source.<name>` carrying the definition, which for Tribes includes the source
agent's budget and capture weights, and `targets`). Nothing in the manifest depends on the machine,
the thread count or the time. The published Tribes manifest stays in the thesis format, which the
loader reads as it is; converting it would only break the line from the R7615's own files.*

### 7.3 Identity

`identity.txt` = SHA-256 over the ordered `(id, source, phase)` tuples, as `PortfolioSplit` computes
it today, plus `game` and `configuration()` in the hashed text. It is what the split files, the
weights file, the checkpoint root and every result's provenance name. Branching and `describe` facts
are outside it on purpose (they may be recomputed; a changed identity means a different portfolio).

*Amended 2026-10-04 (milestone 2): `game` and `configuration()` are NOT hashed. The identity is the
thesis formula exactly: SHA-256 over the ordered `id,source,phase` lines. Two reasons. Hashing the
configuration would hash the engine commit, so an engine commit that changes nothing about the
positions would change what every result says it was measured on. And the published portfolio, its
split files, the published weights file and every archived R7615 result already name `ec243fd7...`;
the formula that produced it is the one that keeps them all valid. The game and configuration are
recorded in the manifest header instead: loading a portfolio with a different game is refused, and
`portfolio verify` reports a configuration that differs from the current one.*

### 7.4 Split

`PortfolioSplit` moved as `Split`, rule unchanged: stratify by `(source, phase)`, order by
`(branching, id)`, deal matched pairs with the pair parity and the odd-member carry running globally
across strata; `_split_dev.txt` and `_split_holdout.txt` are authoritative and carry the identity;
readers never recompute; `apply` checks both directions (portfolio lost scenarios / portfolio grew);
`claimOutputRoot` refuses a second split in one tree. The balance report (counts per source, mean
branching per half) is printed and written to `split_report.txt`, because reading it is a step of the
runbook.

### 7.5 The holdout ledger

New. `_holdout_ledger.json` beside the split files records every experiment that has run on the
holdout (spec hash, date, arms). `magat run --split holdout` appends to it and prints it first; a second
experiment reading the same holdout gets a warning it cannot miss, and a `--frozen <file>` argument is
mandatory (the file's hash goes into the ledger and the provenance). "Read once" becomes auditable.

*Amended 2026-10-04 (milestone 2): `HoldoutLedger` and `magat portfolio ledger` exist; a ledger
written for a different portfolio identity is refused. The `run` side (print first, refuse without
`--frozen`, append) arrives with the runner in milestone 4. The ledger is the first JSON the core
writes, so `core/lib/minimal-json-0.9.5.jar` arrived with it.*

### 7.6 Distribution

The published Tribes portfolio is `games/tribes/portfolio/v1/` in git, bins included: the 400
serialized positions are 1.8 MB, small enough that a release asset would only add a step. The bins
are tied to the engine's class shape (Java serialization), which the manifest records as the engine
commit, and they are reproducible: `magat portfolio build` on any machine reproduces the manifest
bit-for-bit (the build is deterministic in its seeds and fixed weights, and the thesis verified that
at every thread count), and `magat portfolio verify` asserts the rebuilt identity equals the
published one before anything runs on it. A run against a portfolio whose identity is not the
published one is allowed, but its results are labelled with that identity and are not comparable with
published leaves.

*Amended 2026-09-10 (milestone 1): bins in git rather than a release asset.*

*Amended 2026-10-04 (milestone 2): `portfolio verify` checks, in order: the manifest parses and names
this game; every bin loads and the branching and `describe` facts recomputed from it equal the
manifest's; `identity.txt` equals the manifest's identity; the split files carry it and the split rule
applied to the manifest reproduces their membership (an audit, never a replacement for the files);
and, with `--published`, the identity and every row (attempt, seed, phase, branching, facts) are
equal. `--subset` compares a toy build row by row with the published row of the same source, attempt
and phase, and `--plan N` plans N scenarios from both sets of bins with the same arm, seed and budget
and requires identical plans, which is how "the same positions" is checked beyond the manifest when
the bins themselves are not byte-identical.*

---

## 8. Calibration

### 8.1 Calibrator

`ComputeCalibrateV6` moved as `Calibrator`, generalised: cells are `(arm, budget)` over the registry
(`--arms`, default: every registered arm), the reference cell always included; measurement on the
same portfolio positions for every cell (`SCENARIO_POOL`, the stride, `scenarioFor` — unchanged);
per-level warm-up with a collection between levels; one discarded profiled turn per cell; counts from
the turn's own record (init + search) against nanoseconds reset per turn; the **fastest-repetition
estimator** with the **two-fastest gap gate**, `MIN_OPS`, the plausibility check; the load average
reported, never gated on; the console report (spread across arms within a level, across levels for the
reference, what one global weight set would mis-allocate); on any failure nothing is written and the
measurements go to `compute_profiles.csv` regardless.

`--turns` defaults to what the weights file being extended recorded (trap A.5); only when there is no
file does the game's default apply (Tribes: `8,4,2`, because the zero-evaluation arms make four times
fewer copies).

*Amended 2026-10-04 (milestone 3): the reference cell is the file's `reference` profile, measured in
every session whatever `--levels` says, because the merge needs this session's measurement of exactly
that cell; without a file it is the game module's `referenceArm()` at the smallest level. `--arms`
accepts the game's named sets (`@reference`, `@worked-result`). Arms whose budget buys no search
(`random`) declare `priced() = false` and are not calibrated by default. `--toy` runs the same gates
and reports them, but a failed cell does not stop the write; the file is marked `toy = true`, a toy
session may not merge into a real currency, and the shakedown and CI use it to exercise write, merge
and check end to end in seconds. Refused before anything is measured: a file of another game, a file
measured on another portfolio identity, `--turns` different from the file's, and a non-version-1
file as a merge target.*

### 8.2 Merge

Writing into an existing file **merges**: header, globals and `referenceAdvanceNanos` untouched, new
cells re-denominated by `thisRef / fileRef`, a profile measured twice takes the new values, one
`# merged` line records date, count and factor. `--arms` prices only what is named (the incremental
E-prep cell as a first-class command); a profile already present is refused unless `--replace` names it
explicitly, which is the E3 fix-up as a command rather than a hand-edit of the properties file.

*Amended 2026-10-04 (milestone 3): **re-denomination is by ratio, not by absolute nanoseconds.** A
session measures every cell relative to its own measurement of the file's reference cell; a merged
cell is that ratio times the file's stored `wAdvance` of its reference profile (1.0 for a file this
calibrator wrote, 1.000659 for the imported R7615 file, whose unit is the engine EMCTS advance). The
thesis code multiplied by `thisRef / fileRef`, i.e. wrote each new cell's absolute nanoseconds over the
file's stored reference nanoseconds; on a machine, or an afternoon, 58% slower that priced the new arms
58% too high (the 2026-08-31 merge, `x1.5820`, re-measured the same day), which is the trap the brief
lists and the opposite of its rule that re-denomination makes cross-session drift cancel. Under ratio
transfer a uniform speed difference cancels exactly, including between the R7615 and a newcomer's
machine, which is what makes §14.2 step 4 possible at all; a change in the ratios themselves does not
cancel, and that is what `--check` reports. The `# merged` line records both numbers: the factor
applied and this session's reference advance as a multiple of the file's (machine speed, informational).
The reference arm is measured at every level of a session but its cells are written only where the file
lacks them or `--replace` names the arm. `CurrencyTest` is the "divide versus multiply" test: a
reference weight of 2.0, a machine uniformly 0.6x, 1x and 1.58x as fast, zero drift and the same merged
weight in every case.*

### 8.3 Drift check

`magat calibrate --check --arms a,b`: re-measures already-priced arms, reports the reference-advance
factor and each op's absolute drift once re-denominated, PASS/FAIL against the 10% gate, writes
nothing. Runs first in the holdout stage of the notebook and in the newcomer's path (§14.2).

*Amended 2026-10-04 (milestone 3): drift is this session's weight times the merge factor over the
stored weight, per operation, in the file's unit, so it is exactly the error a merge made now would
carry; the reference profile's own copy and evaluation ratios are included, its advance (1 by
construction) is not. The reference advance's machine factor is printed beside it and does not gate.
Each cell's turn-price drift (its own operation counts priced now against stored) is printed as
information: the gate stays per operation as pre-registered, but evaluations are about 3% of a Tribes
turn's price, so a 38% evaluation drift is a 1-2% turn-price drift.*

*Amended 2026-10-04 (milestone 3), **the bracket**, new: every session of more than one cell measures
the reference cell again at the end (after its own warm-up), and fails if any of its three operations
moved by more than the gap gate. Within-cell repetitions cannot see a machine whose speed drifts across
the session, and every weight is a ratio to the reference measured first. Found on the first laptop
check against the R7615 currency: two sessions on the same machine, each internally consistent within
2%, measured the reference advance 14% apart while the other levels moved 1-3%, because a laptop boosts
while cool and the reference cell is measured first. This is a hazard for either re-denomination: the
thesis's absolute version is exposed to the machine's speed changing between sessions, the ratio version
to the reference measurement being unrepresentative of the session; the bracket detects the second, and
a session that fails it writes nothing.*

### 8.4 Two measurements added to the check

`--check` also reports (a) the decorator overhead: the reference cell timed through `MeteredGame`
against the same turns timed by the engine's own hooks on the reference machine, once, recorded in
`ORIGIN.md`; and (b) the engine's dormant counter cost (§2.2). Both are expected to be within noise;
the point is that they are measured.

*Amended 2026-10-04 (milestone 3): deferred to milestone 5 with the other probes. (a) needs the
engine's own nanosecond hooks driven through the oracle side by side with the port, and (b) needs a
switch in the fork that turns the dormant counters off; neither is on the acceptance path of this
milestone, and both belong with `check structure` and `probe support`.*

### 8.5 Under load

`--under-load W` kept as in the thesis (the harness runs the same arm on `W−1` threads, gates on its
own completed work, doubles the gap limit, writes `_load<W>` files beside the idle ones, prints the
ratio comparison). It is not on the default path: Gate A3′ resolved that the idle currency is the
ruler and W7 is co-primary.

### 8.6 Thread sweep and machine profile

`ThreadCalibrationV6` → `ThreadSweep` (pilot-sized points, two timed segments, sustained knee within
3%, memory gate after two points, truncated-sweep fallback flagged as an estimate), driven by the
registry's reference arm; `ServerProfile` → `MachineProfile` (physical cores detected on Linux,
conservatively assumed elsewhere, reserve, override, ladder, clock sampling). Output
`thread_sweep.csv` and `threads.json` (`{budget: chosen}`), which `magat run` reads by default.

*Amended 2026-10-04 (milestone 3): `threads.json` is
`{"format": 1, "game", "arm", "portfolio", "machine", "measured", "levels": {"<budget>": {"threads": n,
"estimate": bool}}}`, extended level by level. `--ladder` and `--segment-seconds` exist for toy sweeps.
Physical cores are detected on Linux; on Windows and macOS they are assumed to be half the logical
CPUs and the banner says so (`MAGAT_MAX_THREADS` states the ceiling). The overrides are
`MAGAT_MAX_THREADS` and `MAGAT_RESERVE_CORES` (`-Dmagat.maxThreads`, `-Dmagat.reserveCores`).*

---

## 9. The runner and the experiment spec

### 9.1 Experiment spec

Stage schedules were Java constants in `Stage1Runner.scheduleFor`. In MAGAT an experiment is a JSON
file, validated before anything runs, hashed into provenance, and read by both the runner and the
analyzer. The worked result's holdout stage:

```json
{
  "name": "ab-emcts-holdout",
  "game": "tribes",
  "portfolio": "tribes/v1",
  "split": "holdout",
  "baseline": "emcts-comma",
  "levels": [ { "budget": 80000, "visits": 8 }, { "budget": 400000, "visits": 6 } ],
  "arms": [
    { "id": "emcts-comma" },
    { "id": "comma-z0-coarse",       "attribution": "comma-z0-random" },
    { "id": "comma-z0-random" },
    { "id": "comma-rand15" },
    { "id": "comma-short15-coarse",  "attribution": "comma-rand15" },
    { "id": "comma-short15" },
    { "id": "comma-z0-mid" }, { "id": "comma-z0-fine" }
  ],
  "sweeps": [
    { "parameter": "repairKeys", "arms": ["comma-z0-coarse", "comma-z0-mid", "comma-z0-fine"],
      "null": "comma-z0-random" }
  ],
  "contrasts": [
    { "a": "comma-z0-coarse", "b": "emcts-comma",     "label": "headline" },
    { "a": "comma-z0-coarse", "b": "comma-z0-random", "label": "the table's own contribution" },
    { "a": "comma-short15-coarse", "b": "comma-rand15", "label": "ranking pays at coarse keys" }
  ],
  "preregistration": "docs/results/ab-emcts-preregistration.md",
  "frozen": "frozen_choices.txt"
}
```

Validation, before a run starts: every arm registered and priced at every level; the baseline present
at ≥ 2 levels (or the report cannot produce budget multipliers; a warning on `dev`, an error on
`holdout`); every sweep names its null and the null is in the grid (principle 5); every arm that
declares a mechanism (`AgentFactory.attributionControl()` non-null) has its control in the grid
(principle 6; error on `holdout`, warning on `dev`); `holdout` requires `frozen`.

*Amended 2026-10-05 (milestone 4): a level may list its own `arms` (the thesis schedules ran the
baseline at some levels only); `scenarios: N` limits a run to an evenly spread N of the split (smoke
and shakedown specs only; on the holdout it is a warning, since a partial read is still a read); arms
may be written as bare ids; unknown keys are warnings (typos); `description` is free text the notebook
prints. Also checked: arm ids unique, every arm in a level, sweep and contrast is in the grid, an arm's
spec-level `attribution` is in the grid, budgets positive and unique. Arms whose budget buys no search
(`priced() = false`, the random floor) need no price and run under the file's global weights, which
their `_leaf.json` records. Toy weights are a warning on development and an error on the holdout unless
the run is declared `--toy`. `magat experiment validate` runs the validator alone.*

### 9.2 Grid runner

`Stage1Runner.run/runLeaf` moved as `GridRunner`: level order is the priority order; a leaf is
`(arm, budget)` over the split's scenarios × visits; per-leaf checkpoint key
`arm=<id>;budget=<units>;split=<split>`; resume compaction with the per-turn row as completion
authority (curve rows written first, the turn row last, as the telemetry row is today); per-level
thread override (`--threads-at 400000=31`); `LoadGovernor` and `RunGuard` moved unchanged (cooperative
stop, heartbeat lock per output root, atomic small writes, append-then-verify data); a leaf is marked
complete only when every turn produced a row.

Three new guards, each a trap from brief §9 made structural:

- **Currency lock.** `_run.json` records the weights file's hash on the first run into a tree. A resume
  with a different hash aborts unless `--retire-leaf arm@budget` names the leaves to re-run (their
  checkpoint lines and directories are removed and the change is logged). A leaf can never silently
  hold data priced in two currencies.
- **Identical-output assertion.** After every level, any two arms whose turn rows agree on `(initial,
  final, advances, copies, evals, nodes, unique)` for every turn are reported as a bug and the run
  stops (principle 12). The analyzer repeats the check on load.
- **Portfolio and configuration lock.** The tree records the portfolio identity and `configuration()`;
  a mismatch aborts.

*Amended 2026-10-05 (milestone 4):*
- *The currency lock is per leaf price, not per file. Every `_leaf.json` records the three weights the
  leaf ran under; a resume under a weights file with another hash proceeds if no leaf's own price
  changed (a merge that only added arms changes none), and otherwise lists the leaves whose price
  changed and refuses until `--retire-leaf` names every one of them. Locking on the file hash alone
  would force re-running every leaf after any merge.*
- *The identical-output assertion compares every complete leaf of the level on disk, including leaves
  from earlier invocations, on (initial, final, advances, copies, evals, nodes, unique) per (scenario,
  visit). It is exercised by `GridRunnerTest` with a renamed duplicate of `emcts`, rather than by a
  duplicate registered in the Tribes registry for the shakedown's sake.*
- *Exit codes: 0 complete, 1 stopped or a turn failed, 2 invalid spec, 3 tree locked by a live writer,
  4 tree mismatch or currency lock, 5 identical arms. A failed turn is reported and leaves its leaf
  incomplete; a re-run retries it.*
- *The holdout ledger is appended before the first holdout turn runs, once per (run tree, spec hash): a
  resume of the same read is not a second read, a second experiment on the same holdout is.*

### 9.3 Estimates

`secPerTurn` and the machine factor stay, as `--machine-factor`, and the banner prints the model
behind every estimate. Nothing decides on them.

*Amended 2026-10-05 (milestone 4): the model is the currency itself. A unit is calibrated to the
reference advance's wall-clock, so a turn of B units costs about B x `referenceAdvanceNanos` on the
calibrated machine; the estimate is that times `--machine-factor` over the level's threads. No
regression fitted to one laptop.*

---

## 10. Results on disk

### 10.1 Per leaf: `<run>/<split>/<arm>/<budget>cu/`

`turns.csv`, one row per (scenario, visit):

```
scenario,source,phase,visit,arm,budget,initial,final,terminal,advances,copies,evals,units,
init_advances,init_copies,init_evals,init_units,plan_ms,init_ms,nodes,unique,duplicates,seq_len,
t_<telemetry key>...
```

`curve.csv`, one row per anytime checkpoint: `scenario,visit,arm,budget,checkpoint,best,advances,units,ms`.

`_leaf.json`: arm, budget, the three weights actually in force, threads, host, started/finished,
governor summary, turns done.

The raw op counts are on every row. That is the cross-machine promise's substrate.

*Amended 2026-10-05 (milestone 4): `turns.csv` also carries `avg_node` after `terminal`; values are
written to four decimals as every thesis CSV was, never in exponent notation. A leaf's `t_<key>`
columns are fixed by its first turn's telemetry keys. `_leaf.json` holds arm, budget, profile, the
three weights, threads, host, start and finish times, turns total and done, failures, seconds, the
governor's summary and the status (`running`, `complete`, `incomplete`).*

### 10.2 Per run: `<run>/<split>/`

`_run.json`: MAGAT version and git commit; game id, engine commit, `configuration()`; portfolio name
and identity; split; weights path, hash, reference and machine header; experiment spec hash and
`frozen` hash; `MachineProfile.summary()`; Java version; threads per level; a history of every
`--retire-leaf`. Plus `_completed.txt`, `_split.txt`, `_running.lock`.

*Amended 2026-10-05 (milestone 4): the game, configuration, portfolio and split are written once and
enforced; the weights, experiment and frozen entries describe the latest invocation; `invocations`
lists every one (date, host, machine, Java, the spec and weights hashes, threads per level, the
arguments) and `retired` every retired leaf (date, leaf, turns discarded, the weights hash that
retired it). `toy: true` marks a toy run.*

### 10.3 Data root

Outside the clone, `MAGAT_DATA` or `~/magat_runs`:

```
<data>/<game>/portfolio/<name>/         manifest, splits, identity, scenarios/, _holdout_ledger.json
<data>/<game>/calibration/<name>/       compute_weights.properties, compute_profiles.csv, thread_sweep.csv, threads.json, support_probe.csv
<data>/<game>/runs/<experiment>/<split>/
<data>/<game>/results/<experiment>/<split>/   tables/, figures/, SUMMARY.md, report.json
<data>/logs/  <data>/bundles/
```

---

## 11. The analyzer

### 11.1 Shape

`analysis/magat_analysis/`: `load.py` (a run tree → one turns frame + one curve frame, with the
identical-output check and the provenance), `stats.py` (scenario-clustered bootstrap, variance
components, paired helpers — moved from `analyze_stage1.py`), `measures.py` (one function per measure
below), `report.py` (tables, figures, `SUMMARY.md`, `report.json`), `cli.py`. Dependencies: numpy and
pandas; matplotlib optional. Tests run on a synthetic tree with known answers and, when present, on the
imported worked result (§11.6).

Invocation: `magat analyze --run <dir> [--holdout <dir>] [--experiment spec.json] [--baseline arm]
[--reprice weights.properties] --out <dir>`.

### 11.2 The twelve measures

| # (brief §4) | Table | Implementation |
|---|---|---|
| 1 paired difference | `headline.csv` | `run_headline`, moved: paired per (scenario, visit), scenario-clustered bootstrap CI, win/loss/tie, per-source CIs, terminal-excluded sensitivity as a second column set |
| 2 budget multiplier | `budget_multiplier.csv` | **new.** Search gain `final − initial` (terminal-excluded); the baseline's gain at every level it ran, interpolated log-linearly in budget between adjacent levels; for each arm and level solve for the baseline budget reaching the arm's gain; multiplier = that budget ÷ the arm's budget; bootstrap over scenarios; flagged `extrapolated` when the crossing lies past the ladder (lower bound) and `outside` when below the ladder |
| 3 W7 wall-clock | `w7_wallclock.csv` | `run_w7`, moved: the baseline's value-versus-`plan_ms` curve per (scenario, visit), the arm re-scored at its own wall-clock; clamped share reported; verdict column: wins both / units only (unresolved) / wall-clock only / none |
| 4 dev/holdout side by side | `replication.csv`, the first table of `SUMMARY.md` | **new.** Given `--holdout`, every headline and contrast row shows development and holdout estimates and CIs, and `replicates` = holdout point inside the development interval and the holdout CI excluding zero |
| 5 attribution contrast | `contrasts.csv` | `run_contrasts`, moved, but the pairs come from the spec (`attribution` fields and `contrasts`), not from a hard-coded list |
| 6 search-gain multiple | `gain_multiple.csv` | **new.** Mean arm gain ÷ mean baseline gain per level, bootstrap over scenarios |
| 7 oracle regret | `oracle.csv` | **new.** Over (scenario, visit) pairs where every arm has a row: best-of-field share and mean regret per arm; requires ≥ 3 arms |
| 8 Cohen's *d* | column in `headline.csv` | **new.** Paired *d* (mean ÷ SD of the paired differences), with the clustering caveat printed |
| 9 anytime | `anytime.csv` | `run_anytime`, moved onto `curve.csv` |
| 10 diversity | `efficiency.csv` | `run_efficiency`, generalised onto `nodes`, `unique`, `duplicates`; agent telemetry columns appended when present; labelled within-run only |
| 11 raw delta | the `MeanDiff` column | never a headline on its own: `SUMMARY.md` always prints it beside #2 or #6 |
| 12 full-game win rate | — | not in v0.1; the report's scope note names it as the optional yardstick |

Also kept: `variance.csv` (`run_variance`, the ICC and the scenarios-versus-visits advice) and the
design section of `SUMMARY.md`.

### 11.3 Terminals

Excluded by the `terminal` flag, with the magnitude cutoff (`|final| ≥ 1e9`) as the fallback for
imported legacy rows. Every headline table exists in both strata.

### 11.4 Re-pricing

§6.5. Output tables carry `currency = <weights file name>` in every row.

### 11.5 Report

`SUMMARY.md` order: provenance block (game, engine commit, portfolio identity, currency, spec hash,
frozen choices, pre-registration file); replication side by side (#4); headline in units and seconds
(#1, #3) with #2 and #6 beside it; attribution (#5); oracle (#7); *d* (#8); anytime (#9); diversity
(#10); design (variance); scope notes (extrapolation flags, clamping, terminal rates per arm, what
re-pricing cannot do). `report.json` holds the same numbers for machines.

### 11.6 The worked result

`tools/import_legacy.py` converts an archived `stage1/<split>` tree into the §10 layout: `Config` →
arm id by Appendix A, the per-burn rows into `curve.csv`, the per-turn columns into `turns.csv`
(`copies` and `evals` blank — the action-bias line's results schema did not carry them; only the
calibration cells did), telemetry columns prefixed `t_`. The bundles in `~/Downloads`
(`stageD.zip`/`stageE.zip` for development, `stageF.zip` for the holdout, `frozen_choices.txt`) are
enough for that. The analyzer's acceptance test is then: on the imported trees, reproduce the point
estimates of the retrospective §3 and §4 exactly (`+244.3` at 80k, `+211.7` against the control,
`5.4×`, the oracle table, *d* = 0.62) with intervals agreeing to bootstrap noise. The result ships as
`games/tribes/results/ab-emcts/` and `docs/results/tribes-ab-emcts.md`, credited as a thesis result
re-analysed by MAGAT.

---

## 12. Gates and probes

| Command | What it asserts | Extracted from |
|---|---|---|
| `magat check contract` | laws L1–L10 on captured positions (§4.4) | `ActionKeyCollisionProbe`, `StaleActorIdTest`, the copy-determinism reasoning in `IsolationPhaseRunner` |
| `magat check equivalence` | each ported arm reproduces its oracle's `(initial, final, advances, copies, evals, nodes, unique, units)` and initialization spend on N scenarios × V visits at a calibrated budget, both sides under identical weights (the oracle's engine meter is set to the profile's three numbers; an unpriced toy budget uses the arm's smallest priced level). Default N = 8, V = 2. An oracle declares the initialization copies it makes and never reads (the thesis agent makes two), which the port does not make; the gate expects exactly that offset | `Stage1Runner.verify` |
| `magat check structure` | `--same a=b` pairs are byte-identical over N turns, `--differ c=d` pairs are not, and the tree branches (`maxBranching ≥ 2` in telemetry); the LEGACY = COMMA and k = 1 = DRIFT equivalences become entries in `experiments/tribes/structure.json`. Runs on portfolio turns rather than full games, so it needs no game loop | `SelectionStructureCheck`, `SelectionFixCheck` |
| `magat probe support` | an arm's `support()` counters over N scenarios per level against a criterion from the spec (Tribes/Qδ: ≥ 80% of biased picks after the warm-up burns rest on a key with ≥ 3 observations); a failing level is labelled a *reporting* level in the report | `ActionBiasFloorProbe` |
| `magat check replay` | **new.** Given a published leaf, rebuilt scenarios and the published weights, re-run K turns and assert the rows agree field for field. The cross-machine determinism claim, tested rather than stated | — |

All five run in the shakedown at toy size and print PASS/FAIL; all exit non-zero on FAIL.

---

## 13. The CLI, the notebook, the shakedown

### 13.1 The launcher

`python magat.py` (Python 3, at the repository root; `bin/magat` and `magat.cmd` wrap it) is the one
thing a user types. It locates the root from
any working directory, builds when sources are newer than classes, computes the classpath, opens the
data root, passes `--weights` explicitly to every JVM, and tees every command's output to
`<data>/logs/<name>.log` with a timestamped header (the notebook's `run/java` helpers, promoted). Java
commands go to `magat.cli.Main` in a JVM with `-Xmx`, the G1 flags and any `-D` given; Python commands
run in process. The notebook calls the launcher and nothing else, so the notebook cannot drift from
the CLI.

```
magat build                                      javac --release 11 over magat/src, games/*/src, the engine; --clean
magat machine                                    MachineProfile banner; no work run
magat portfolio build   --game tribes --out <dir> [--threads N] [--targets single=200,dual=200]
magat portfolio freeze  --portfolio <dir>        split files + identity + report; idempotent
magat portfolio verify  --game tribes --portfolio <dir> [--published games/tribes/portfolio/v1] [--subset] [--plan N --arm emcts --budget 8000]
magat portfolio ledger  --portfolio <dir>
magat threads           --portfolio <dir> --out <calib> --levels 40000,80000,400000
magat calibrate         --portfolio <dir> --out <calib> --levels ... [--arms a,b] [--reps 3] [--turns auto] [--warmup 6] [--under-load W] [--replace a]
magat calibrate --check --portfolio <dir> --weights <file> --arms a,b
magat weights           --weights <file> [--game tribes --arms a,b|@set --levels ...]   coverage; exit 1 if any profile is missing
magat check contract    --game tribes --portfolio <dir>
magat check equivalence --game tribes --portfolio <dir> --weights <file> [--arms ...] [--scenarios 8 --visits 2 --budget 40000]
magat check structure   --spec experiments/tribes/structure.json ...
magat check replay      --leaf <published leaf> --portfolio <dir> --weights <file> [--turns 20]
magat probe support     --arm comma-bias-e25 --portfolio <dir> --weights <file> --levels ... [--scenarios 20]
magat run               --experiment <spec.json> --split dev|holdout|all --portfolio <dir> --weights <file> --out <run>
                        [--threads N] [--threads-at 400000=31] [--machine-factor 1.5] [--frozen <file>] [--retire-leaf arm@budget]
magat analyze           --run <dir> [--holdout <dir>] [--experiment <spec>] [--baseline arm] [--reprice <weights>] --out <dir>
magat import-legacy     --stage1-tree <dir> --out <run> [--map tools/legacy-arms.json]
magat bundle            --name <stage> --paths ... [--exclude seqlog_,mutlog_]
magat experiment validate --experiment <spec.json> [--weights <file>] [--split ...] [--frozen <file>]   the validator alone
magat shakedown         [--out <dir>]
magat notebook build|selftest
```

Flags are named, never positional (`ComputeCalibrateV6`'s positional/`auto` tangle is the reason).
Every command prints a one-line banner with the data root, the weights file in force and the
portfolio identity, because those three are what every past mistake turned on.

### 13.2 `run_magat.ipynb`

Generated from `tools/make_notebook.py` so it is diffable and cannot drift from the specs it names.
Cells, in order, each independently re-runnable and self-skipping where its outputs exist:

1. **Parameters and helpers** — root finder (walks up from the notebook's folder; never asserts on
   cwd), data root outside the clone, machine settings (`WORKERS`, `THREADS_AT`, heaps, GC flags),
   `magat()` wrapper, `bundle()`, `assert_in_log()`.
2. **Build** and **shakedown**, then bundle.
3. **Machine**: `magat machine`, `free -g`.
4. **Portfolio**: build + freeze, skipping when the manifest and both split files exist; prints the
   balance report; verifies against the published identity.
5. **Threads**: the sweep, skipping when `threads.json` exists.
6. **Calibrate**: idle, skipping when the weights file exists; the under-load cell present but off by
   default with the A3′ resolution quoted above it.
7. **Gates**: contract, equivalence, structure, support probe; `assert_in_log(PASS)` after each.
8. **One cell per development experiment** in `experiments/tribes/`: run, analyze, print `SUMMARY.md`,
   bundle. Each cell ends in a bundle.
9. **Holdout**: drift check first (`calibrate --check`), then the cell refuses to run until the frozen
   choices file exists, then run + analyze with `--holdout`, print the side-by-side, bundle.
10. **Inventory**: what to keep, with an ok/missing tick per file.

Self-test: `magat notebook selftest` extracts the cells tagged `setup`, executes them with the working
directory set to the repository root, the notebook's folder and the repository's parent, and asserts
the same root and a data root outside it from all three. Installed as a pre-commit hook together with
`check_layering.py` and the unit tests (`tools/hooks/pre-commit`; `magat hooks install`).

### 13.3 The shakedown

`tools/shakedown.py`, cross-platform (the bash version used `mktemp` and `df -PT`; the filesystem
check stays where the OS exposes it). Toy sizes throughout, measures nothing, exits with the failure
count:

build → machine banner (detected vs assumed printed for the eye) → toy portfolio (4 + 4) + freeze →
`check contract` → `check equivalence` at 8k on 2 scenarios → `check structure` → `calibrate` 1 level,
2 reps (the verdict is expected FAIL under `MIN_OPS`; what is checked is that cells are measured and
written) → `calibrate --under-load 3` writes beside, not over → `probe support` → `run` the shakedown
spec on `dev` (3 arms, 1 visit) and on `holdout` into its own tree → re-run `dev` and see every leaf
skipped → the identical-output assertion fires on a deliberately duplicated arm → `analyze` produces
every table → `import-legacy` on a bundled 20-row fixture → `notebook selftest` → `check_layering`.

*Amended 2026-10-05 (milestone 4): the runner stage runs `experiments/tribes/shakedown.json` (3 arms,
2 levels, 1 visit) on the toy portfolio in the toy currency: dev, a re-run that skips every leaf, the
holdout into its own tree with a ledger entry, a retired leaf, the currency lock refusing a leaf whose
price was changed in a copy of the weights, the shipped specs validating against the published
currency, and a bundle. The identical-output assertion and the validator's rejection of a sweep
without its null are asserted by `GridRunnerTest` and `ValidatorTest`, which the junit step runs.*

---

## 14. The cross-machine promise, stated precisely

### 14.1 What transfers

| | Transfers | Mechanism |
|---|---|---|
| Op counts per turn | exactly | `turns.csv` |
| The search itself | exactly, given the same weights | deterministic count budget + `Seeds.visit` + `reseed` + rebuilt portfolio; `check replay` tests it |
| Weights | approximately | published file + `calibrate --check` drift report |
| Wall-clock | no; always reported | `plan_ms`, W7 |
| Budget multipliers | yes | dimensionless |

### 14.2 The afternoon path for a newcomer

1. Clone with submodules; `./magat build`; download the portfolio bins (or build them, minutes on a
   many-core machine) and `magat portfolio verify` against the published identity.
2. `magat check replay` on a published `emcts-comma@80000` leaf: their machine reproduces the published
   rows field for field, which is what makes step 5 a paired comparison rather than a hope.
3. Implement the algorithm against `Game<S,A>`, register it, `magat check contract` still passes.
4. `magat calibrate --check --arms emcts,emcts-comma` (the drift report), then
   `magat calibrate --arms <new>` at the ladder — minutes per level; the merge re-denominates into the
   published currency, and the drift report says how much to trust that.
5. `magat run` the new arm alone on `dev` at the ladder (the reference arms' leaves are the published
   ones: same portfolio, same seeds, same currency, so they need not be re-run); `magat analyze` with the
   published run tree as the field. W7 additionally needs the baseline's `plan_ms` on the new machine,
   so the spec's `wallclock_anchor` lists the one reference arm to re-run for that (default:
   `emcts-comma` at two levels).
6. The report gives the paired difference, the budget multiplier, W7, the oracle share and *d* against
   every published arm, in the published currency, with the drift report attached.

That is the concrete meaning of "know within an afternoon how it compares to everything that came
before".

---

## 15. Build, toolchain, CI, licensing

### 15.1 Build

Plain `javac --release 11` over a generated source list, driven by the launcher — what the thesis
notebook does today, proven on the R7615, with no download and no build tool on the server. Gradle can
be added as a convenience in v0.2; nothing here depends on it. Local development machines run newer
JDKs (this one has 26); `--release 11` keeps the API set honest.

### 15.2 CI

GitHub Actions, JDK 11/17/21 matrix, submodule checkout: build, JUnit, `check_layering`, `check
contract` and `check equivalence` on two scenarios, the toy shakedown, the Python tests. Under fifteen
minutes.

### 15.3 The engine fork

`games/tribes/engine` is a submodule pointing at `mlhcorapi/Tribes`, a public GitHub fork of
`GAIGResearch/Tribes`, branch `magat-engine`: upstream `270e7e5` (2022-08-25, the commit the thesis
tree was taken from, established by comparing the files the thesis did not touch) plus two commits.
The first is the thesis tree with line endings normalised, assembled by `tools/make_engine_branch.py`
so the provenance is reproducible: the engine (`core/`, `utils/`, `gui/`), the thesis's engine
modifications (`setRandomGenerator`, `captureAtTickAsTurnStart`, typed actor lookup and the stale-id
feasibility fixes, `FastGameStateIO`'s `readObject` hook, the `LOSE_BOOST` fix, `REMOVE_FISHING`,
`ComputeMeter` and its hooks), `players/` (upstream agents and the thesis agents that serve as
oracles), `core/data/dto`, the two tests with junit in `lib/`; not `core/data/exp`, `analysis/`, the
shakedown script, `test_results/`, `Benchmark.java`, or the experiment mains in `core/data/`. Against
upstream that is 125 files changed, +7,663/−419. The second commit is the one engine change MAGAT
needs (`tools/patch_engine_paths.py`): `IO.readFile/readJSON` resolve a relative data file against
`-Dtribes.home` when it is not in the working directory, so the engine runs from any directory; the
launcher passes the property. Credit to the Tribes authors is in MAGAT's README.

*Done 2026-09-10 (milestone 1).*

### 15.4 Third-party audit

- `junit-4.13.2`, `hamcrest-core-1.3`: EPL / BSD — fine.
- `json.jar` (JSON.org) carries the "shall be used for Good, not Evil" clause, which is not an
  OSI-approved licence. The engine uses it for level loading; MAGAT's own code will not. The
  experiment spec and the sidecars are parsed with `minimal-json` (MIT, one small jar, no
  dependencies), added to `core/lib/`.
- The R7615 calibration and the worked result are the user's own data.

---

## 16. Milestones

Each milestone ends with the shakedown green on Windows and Linux, the notebook self-test green, and a
commit. `run_magat.ipynb` and `tools/shakedown.py` exist from milestone 0 and grow with each.

| # | Deliverable | Acceptance |
|---|---|---|
| 0 | Repository bootstrap: layout, launcher with `build`, engine fork + submodule, CI skeleton, licence file, README with credits, notebook (parameters + build + shakedown cells), shakedown (build only), layering check | CI green on an empty core |
| 1 | **The seam**: `Game`, `MeteredGame`, `Meter`, `TurnBudget`, `TurnRecord`, `Episode`, `TribesGame`, `TribesState`, `Emcts` ported, `check contract`, `check equivalence` | L1–L10 pass on 8 captured positions; `emcts` reproduces its oracle on 8 scenarios × 2 visits at 8k and 40k; `check equivalence` is in the shakedown. *Done 2026-09-10; the port also reproduces the R7615's own archived verify signature for scenario 1 at 40k (`init=6231.0 final=6486.0 fm=37354 nodes=430 unique=356 units=40056.0483`), and `emcts-comma` came along for free.* |
| 2 | Portfolio: builder, journal, manifest v1, identity, split, ledger, `random`; `TribesScenarioSources` | toy build reproducible at 1 and 4 threads; the published manifest identity (reconstructed from the archived rows, §17) reproduced by a full build on the server. *Done 2026-10-04. The toy Tribes manifest is byte-identical at 1, 2 and 4 threads (and the core test asserts it on the toy game). A full rebuild on a second machine (i9-14900HX, Windows, JDK 26, 20 threads, 15.5 min) reproduced `ec243fd7...`, all 400 rows, both split files from a fresh freeze, and identical `emcts` plans from rebuilt and published bins (`games/tribes/portfolio/v1/REBUILDS.md`); the server rebuild remains a one-command check.* |
| 3 | Calibration: `Calibrator`, merge, `--check`, `--under-load`, `ThreadSweep`, `MachineProfile`, weights v1, R7615 import + `ORIGIN.md` | toy calibration writes and merges; the drift-math unit test (divide vs multiply) exists; the imported file loads and prices every worked-result arm. *Done 2026-10-04: toy calibration writes, merges, refuses re-pricing without `--replace`, checks without writing and writes a loaded file beside the idle one, in the shakedown and CI; `CurrencyTest` is the divide-versus-multiply test (under ratio re-denomination, §8.2 amendment); `PublishedCalibrationTest` and `magat weights --arms @worked-result` show all 34 worked-result arms priced at all three levels. A real drift check of a second machine against the R7615 currency, bracketed, is recorded in `calibration/r7615/ORIGIN.md`: advances and copies within the gate, evaluations a third cheaper (FAIL), turn prices within 4%.* |
| 4 | Runner: spec + validator, `GridRunner`, checkpoints, compaction, currency lock, identical-output assertion, provenance, `run` and `bundle` commands | shakedown spec runs on dev and holdout, resumes, skips, retires a leaf; validator rejects a sweep without its null. *Done 2026-10-05: all of it in the shakedown's runner stage and in CI; a torn partial leaf resumes to the same turns (`GridRunnerTest`); the currency lock, the tree locks and the identical-output assertion each have a test that makes them fire.* |
| 5 | The rest of the family: `emcts-comma`, `QdeltaEmcts` and every worked-result arm; `check structure`, `probe support`, `check replay` | every arm passes its oracle gate; `check replay` reproduces 20 published `emcts-comma@80000` holdout rows on the rebuilt portfolio under the published weights |
| 6 | Analyzer: all twelve measures, side by side, re-pricing, report; `import-legacy`; the worked result imported | the retrospective §3/§4 point estimates reproduced from the imported trees; the Python tests pass on the synthetic tree |
| 7 | Docs (`CONTRACT`, `CURRENCY`, `METHODS`, `OPERATIONS`, `ADDING_*`, `TRAPS`, the worked-result page), full notebook, `Tribes-master/` removed, v0.1 tag once the licence is in | a fresh clone follows `README.md` through §14.2 on the toy portfolio without reading anything else |

Milestone 1 is the one you named as the first code milestone. Its "seam is in the right place" test is
the oracle gate on the ported plain EMCTS through `MeteredGame(TribesGame)`, not on one scenario but
on sixteen turns at two budgets, with units compared as well as values and counts.

---

## 17. Inputs and decisions needed from you

*Status 2026-10-04. Decided: (1) Apache-2.0, in `LICENSE`; (2) a public fork, `mlhcorapi/Tribes`
branch `magat-engine`; (3) the stage A artefacts were supplied and are published under
`games/tribes/portfolio/v1` and `games/tribes/calibration/r7615`; (4) port, with the oracle gate as
the safety net (passing since milestone 1); (5) minimal-json, in `core/lib` since milestone 2.
Still open: (6) the arm ids. The original text follows.*

1. **Licence.** Apache-2.0 is recommended: it adds an explicit patent grant and contributor terms, which
   matter for a framework other groups will build on; MIT is equally acceptable. Milestone 0 writes the
   file; the v0.1 tag waits for it.
2. **Engine fork visibility** (public fork of `GAIGResearch/Tribes` under your account, or private with
   a CI deploy key). The adapter is the same either way.
3. **The R7615 stage A artefacts.** The bundles in `~/Downloads` cover stages B–F (development and
   holdout trees, tables, frozen choices, the D-prep and E-prep calibration logs). `stageA.zip` is not
   on this machine, and it holds the currency and the portfolio identity: `compute_weights.properties`,
   `compute_profiles.csv`, `portfolio.csv`, both split files, `thread_calibration.csv`,
   `action_bias_floor.csv`. If `~/ab_runs/stage1/_calibration/` and `~/ab_runs/stage1/_portfolio/`
   (with `scenarios/`) still exist on the server, copying those two directories is the cleanest route
   — about the size of the portfolio bins. Without them the weights are reconstructible from the D-prep
   and E-prep logs only to the four printed decimals, which is enough for the analysis and not enough
   for `check replay` to be exact; the portfolio identity is reconstructible from the archived rows
   and can then be verified by a rebuild.
4. **Decision B** (§2.3): port the reference agents onto the contract, with the oracle and replay gates
   as the safety net. The alternative keeps them as Tribes agents and makes v0.1 cheaper by roughly a
   milestone, at the cost of a contract nothing runs against.
5. **Spec and sidecar format**: JSON via `minimal-json` (MIT). Taken as the default unless you object.
6. **Arm ids** of §5.4 / Appendix A. They are permanent once a result is published under them.

---

## 18. Brief §9, mapped to mechanisms

| Trap | Mechanism in this design |
|---|---|
| Kernel cwd is the notebook's folder | root finder in the setup cell; `notebook selftest` from three directories in the pre-commit hook |
| Output inside the clone | data root outside the clone by default; the launcher refuses a data root under the repository |
| Consumer does not read what the producer writes | no compiled default weights path; `--weights` passed by the launcher to every JVM; hard error on an unpriced profile; one code path writes and reads the portfolio |
| OS load average meaningless in a container | the load harness gates on its own completed work; the idle load average is informational |
| Turns-per-rep selects positions | recorded in the weights file with the portfolio identity; `--check` refuses to compare under different turns |
| Merged arm written against the file's reference | ratio re-denomination on merge (§8.2 amendment), so a uniformly slower machine cancels; `--check` reports the ratio drift that does not cancel before anything is merged or compared |
| Two arms identical; a grid without its null | the identical-output assertion in the runner and the analyzer; the spec validator requires nulls and attribution controls |
| Commit before a worktree can disappear | every milestone is a commit; the pre-commit hook keeps commits cheap |

---

## Appendix A — rename table (archived name → arm id)

| Archived `Config` | Arm id | Notes |
|---|---|---|
| `Baseline` | `emcts` | plus k=1, ε=1.00, FULL repair, MID keys (inert) |
| `Bias_e25`, `Bias_e75`, `Bias_e50`, `Bias_e10` | `emcts-bias-e25` … | plus-era dose arms (Stage B/D anchors) |
| `Comma_Off` | `emcts-comma` | comma k=5 |
| `Comma_On` | `comma-bias-e25` | comma, ε=0.25, MID insertion |
| `Comma_e75`, `Comma_e50`, `Comma_e10` | `comma-bias-e75`, `comma-bias-e50`, `comma-bias-e10` | |
| `Comma_Coarse_e25` | `comma-bias-coarse-e25` | COARSE insertion keys |
| `Comma_Fine_e25` | `comma-bias-fine-e25` | FINE insertion keys |
| `Comma_NoRem_e25` | `comma-bias-norem-e25` | removal credit off |
| `Comma_PerBurn_e25` | `comma-bias-perburn-e25` | per-burn snapshot |
| `Comma_Short15` | `comma-short15` | shortlist 15, MID ranking, ε=1.00 |
| `Comma_Short15_Coarse` | `comma-short15-coarse` | shortlist 15, COARSE ranking |
| `Comma_Short15_e25`, `Comma_Short5_e25` | `comma-short15-bias-e25`, `comma-short5-bias-e25` | |
| `Comma_Rand15` | `comma-rand15` | attribution control for the shortlist |
| `Comma_Z0_Coarse`, `Comma_Z0_Mid`, `Comma_Z0_Fine` | `comma-z0-coarse`, `comma-z0-mid`, `comma-z0-fine` | zero-evaluation repair |
| `Comma_Z0_Random` | `comma-z0-random` | attribution control for zero-evaluation repair |
| profile `EMCTS@<b>` | anchor of `emcts@<b>` | the currency's reference advance |
| profile `AB_<arm>@<b>` | `<arm id>@<b>` | |

## Appendix B — component-by-component extraction map

| MAGAT | From | Kept verbatim | Generalised | Dropped |
|---|---|---|---|---|
| `meter.Meter`, `Weights` | `core/game/ComputeMeter.java` | thread-local counts/nanos, profile resolution, `useProfile(null)` semantics | key format, explicit loading, per-op nanos, portfolio identity | compiled default path, `-Dcompute.w*` env overrides |
| `game.MeteredGame` | the three engine hook sites | count-then-time span | decorator | — |
| `agent.Episode`, `Seeds` | `Stage1Runner.runOneTurn`, `IsolationPhaseRunnerV6.visitSeed` | seed formula, copy-then-reseed order | one implementation | `setPlayerIDs`, `ElapsedCpuTimer` |
| `agents.emcts.Emcts` | `ExperimentalEMCTSAgent` (search loop, generation, selection modes, burn, repair, greedy init) | every operation and every random draw in the same order | types via the contract | crossover paths (stay in the fork), `PROFILE_FM`, v4 hooks |
| `agents.emcts.QdeltaEmcts` | `Stage1BiasedAgent`, `Stage1Params` | all modes, the warm-up/support counters, the RNG-draw discipline | table keyed by `actionClass` | `logSeqComposition` (Stage 2 groundwork) |
| `portfolio.*` | `BuildV6Portfolio`, `BuildV5Portfolio`, `PortfolioSplit`, `IsolationPhaseRunner.Scenario/loadPortfolio` | scheduling, journal, split rule, identity hash, apply guards, root marker | sources behind `ScenarioSource`, manifest v1, ledger | v4/v5 manifests, `config.txt` prose |
| `calibrate.Calibrator` | `ComputeCalibrateV6` | estimator, gates, warm-up, merge, drift, load harness, reports | suites → registry arms, weights v1 | positional arguments, `checkOnly` static |
| `calibrate.ThreadSweep`, `machine.MachineProfile` | `ThreadCalibrationV6`, `ServerProfile` | all | reference arm from the registry, `threads.json` | `_tuned_threads_*.txt` |
| `run.*` | `Stage1Runner`, `LoadGovernor`, `RunGuard`, `ExpCsv` | leaf loop, compaction, guard, governor, atomic writes | spec file, currency lock, identical-output assertion, provenance | stage constants, `seqlog` side files, `machineFactor` semantics beyond the banner |
| `probe.*` | `Stage1Runner.verify`, `SelectionStructureCheck`, `SelectionFixCheck`, `ActionBiasFloorProbe` | the assertions | many scenarios, portfolio turns instead of full games, criteria from the spec | — |
| `magat_analysis` | `analyze_stage1.py` | bootstrap, W7, variance, anytime, efficiency, headline | spec-driven contrasts, the seven measures listed as new | Stage-2 pair interactions, the hard-coded contrast list |
| notebook, shakedown | `run_ab_emcts.ipynb`, `run_ab_shakedown.sh` | cell discipline, helpers, bundle rules, gate assertions | generated from specs, Python shakedown | stage-specific prose |
