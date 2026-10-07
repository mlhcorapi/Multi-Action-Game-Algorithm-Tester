# Adding a game

A game joins MAGAT through an adapter. The adapter implements `Game<S, A>` over the game's engine,
names the positions to capture, and registers the arms. Nothing in the core changes. Two complete
examples are in the repository:

- **`core/test/magat/testgame/ToyGame.java`** (about 200 lines): a tiny two-player multi-action game.
  Each turn a player adds 1 or 2 to one of three counters, up to four times. It has stale actions, its
  own randomness and a scenario source, and every contract law holds. Read it first.
- **`games/tribes/`**: the real adapter. `TribesGame`, `TribesState`, `TribesScenarioSources`,
  `TribesAgents` and `TribesModule`, with the engine as a submodule in `games/tribes/engine`.

Read [`CONTRACT.md`](CONTRACT.md) first: it is the interface and its laws.

## 1. The engine

Put the engine where the launcher looks: `games/<game>/engine`, usually as a git submodule. Its
`src/` and `lib/*.jar` are compiled and put on the classpath with everything else. A Java engine is the
easy case. An engine in another language needs a bridge that keeps forward-model calls in-process:
JNI, or a state-serialization bridge fast enough that the meter still measures the game rather than
the bridge. Budget it as a project.

**Check the engine's licence** before copying any of it. Tribes' upstream repository carries none, so
MAGAT uses it as a submodule of a fork, with credit, while permission is sought, and copies nothing in.

If the engine reads data files relative to its working directory, give it a way to find them from
anywhere. Tribes resolves them against `-Dtribes.home`; the launcher passes engine-specific JVM
properties from `ENGINE_PROPS` in `magat.py`.

## 2. The adapter package

`games/<game>/src/magat/games/<game>/`, package `magat.games.<game>`. The layering check enforces the
boundaries:
- the core never imports it;
- it may import the core and its own engine;
- an agent never sees the engine directly.

The CLI loads `--game <game>` by name: `magat.games.<game>.<Game>Module` (for example
`magat.games.tribes.TribesModule`), with a public no-argument constructor.

### `Game<S, A>`

Choose `S` and `A`. `S` is usually a thin wrapper around the engine's state, so the adapter can attach
what the contract needs. `A` is usually the engine's action type. Then implement the methods. The parts
that need care:

| Method | Get this right |
|---|---|
| `copy` | an independent copy (L1). If the engine's copy shares a random generator with the original, seed the copy's generator from the parent's next draw, as `ToyGame.State.copy` does: copies then diverge deterministically |
| `advance` | in place. It must cope with a stale action from a plan being repaired, or `isLegal` must be checked first: decide which, and keep `isLegal` cheap |
| `legalActions` | the actions a search should consider. Filtering out suicidal or pointless actions is the adapter's policy, applied identically for every arm; document it in `configuration()` |
| `isLegal` | never throws, false for stale referents (L4). Engines often throw on a stale id; catch it here |
| `evaluate` | deterministic and finite (L9). Cache heuristic objects per player; never let the evaluation touch the meter or the state |
| `actionKey` | injective over one state's legal actions (L7): plans are de-duplicated on it |
| `actionClass` | three granularities, each a partition of the next finer (L8). It is what a learned table keys on, so choose classes a human would recognise: an action type, then a qualifier, then the full identity |
| `reseed` | pins every random event the state will produce (L2). Without it, a paired comparison is not paired |
| `serialize` | stable for the life of the portfolio: the bins are what every result is measured on. Record the engine commit in `configuration()` |
| `configuration` | every setting that changes what a position or a call means: engine commit, game mode, rule flags. Set engine globals exactly once, in the adapter's constructor, and report them here |

### Scenario sources

A source plays a game from a seed and returns validated captures at fixed phases. Choose the phases
where decisions are rich: early enough to be common, late enough to matter.

- Make `play(attempt)` deterministic in the attempt, so a build is reproducible on any machine at any
  thread count.
- Validate captures. A capture must have something to decide, and must survive a short smoke plan
  without throwing.
- Abandon a stuck game.
- Swallow engine failures; let a bug escape.

Play source games at a **fixed weight set**, never a calibrated currency. The `definition()` string
must name everything that determines what `play` yields: the build journal is keyed by it.

Two sources of different character (Tribes: single-player and two-player games) give the split
something to stratify on. Target a portfolio of a few hundred positions: Tribes uses 400, and its
bins are 1.8 MB in git.

### `GameModule`

A module for the toy game would be (a sketch; `TribesModule` is the real one):

```java
public final class ToyModule implements GameModule<ToyGame.State, ToyGame.Move> {
    public Game<ToyGame.State, ToyGame.Move> game() { ... }
    public AgentRegistry<ToyGame.State, ToyGame.Move> agents() { ... }
    public Map<String, Oracle<ToyGame.State>> oracles() { return Collections.emptyMap(); }
    // optional: referenceArm() (default "emcts"), armSets() for @names
}
```

**The arms.** The reference agents are generic over `Game<S, A>`, so they need no porting. Register
them:

```java
AgentRegistry<S, A> r = new AgentRegistry<>();
r.register(RandomAgent.factory());     // the floor
r.register(EmctsFactory.plain());      // emcts: the reference arm, whose advance defines the unit
r.register(EmctsFactory.comma(5));     // emcts-comma
for (QdeltaArms.Arm a : QdeltaArms.all()) r.register(a.factory());   // the worked result's family, if wanted
```

**Oracles** are only for arms ported from an existing implementation (see
[`ADDING_AN_ALGORITHM.md`](ADDING_AN_ALGORITHM.md)).

**Launcher hooks.** In `magat.py`, add the game's JVM properties to `ENGINE_PROPS`, and once it has
published artefacts, add them to `PUBLISHED`, so that commands default to them.

## 3. Prove it

```bash
python magat.py build --tests
python magat.py layering
python magat.py arms --game <game>
python magat.py portfolio build --game <game> --out ~/magat_runs/portfolio/<game>-toy --targets <source>=2 --threads 2
python magat.py portfolio freeze --portfolio ~/magat_runs/portfolio/<game>-toy
python magat.py check contract --game <game> --portfolio ~/magat_runs/portfolio/<game>-toy --scenarios 4
```

The contract laws come first. A game that fails L2 (determinism) or L10 (metering is complete) cannot
be measured, however good its engine.

## 4. The published artefacts

1. **The portfolio.** `portfolio build` at the full targets, then `portfolio freeze`. Read
   `split_report.txt`: the two halves should match per source and in mean branching. Commit it as
   `games/<game>/portfolio/v1/` with its identity. A second machine rebuilding it with `portfolio verify
   --published` is the evidence that positions reproduce.
2. **The currency.** On the machine the game will be published from, idle:

   ```bash
   python magat.py calibrate-controlled --name <machine> --game <game> --portfolio games/<game>/portfolio/v1
   ```

   This prices every registered arm at the ladder, in sections. Choose the ladder first, with the
   crossing you expect inside it ([methods](METHODS.md#2-the-paired-design)). Commit the result as
   `games/<game>/calibration/<machine>/` with an `ORIGIN.md`.
3. **The gates.** `check structure` with a `structure.json` for the game's equivalences; `check
   overhead --audit` on the reference arms; and `check replay` once a result is published.
4. **The experiments.** A reference ladder spec in `experiments/<game>/`. The notebook generator
   (`tools/make_notebook.py`) makes one cell per spec in `experiments/tribes/`. Add the new game's
   experiments to `CELLS` the same way.

Each game has its own currency: raw units are never compared across games, and budget multipliers
are.
