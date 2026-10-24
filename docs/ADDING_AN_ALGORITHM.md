# Adding an algorithm

An algorithm is a `SearchAgent<S, A>` written against the `Game` contract ([`CONTRACT.md`](CONTRACT.md)),
with an `AgentFactory` that names it. Once registered it is an **arm**: priced, run, analysed and
compared like every arm before it. This page walks through a complete example, then covers what a real
algorithm adds: its own operations, or an implementation it was ported from. The four reference
algorithms ([`ALGORITHMS.md`](ALGORITHMS.md)) are examples at full size.

## The example: `FirstAgent`

`core/src/magat/agents/first/FirstAgent.java` does random restarts, the smallest search that spends a
budget. Until the budget is gone it samples a random plan for the turn from a copy of the position,
evaluates where the plan leaves the game, and keeps the best. No game registers it.
`core/test/magat/agents/FirstAgentTest.java` holds it to every promise below, and the shakedown
checks that this page shows the compiled file.

```java
package magat.agents.first;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The worked example of {@code docs/ADDING_AN_ALGORITHM.md}: random restarts, the smallest search that
 * spends a budget. Until the budget is exhausted it samples a random plan for the turn from a copy of
 * the position, evaluates where the plan leaves the game, and keeps the best. It declares one operation
 * of its own, a sampled plan's work outside the forward model, so the currency prices it.
 *
 * <p>No game registers it; the guide shows the one line that does.
 */
public final class FirstAgent<S, A> implements SearchAgent<S, A> {

    /** The operations it declares: one sampled plan's own work (its legal-action queries and picks). */
    public static final List<String> OPERATIONS = Collections.singletonList("first.sample");
    private static final Meter.Op SAMPLE = Meter.register("first.sample");
    /** The longest plan it samples, in actions. */
    public static final int MAX_ACTIONS = 20;

    private final Random rnd;

    public FirstAgent(long seed) {
        this.rnd = new Random(seed);
    }

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        long t0 = System.currentTimeMillis();
        double initial = game.evaluate(state, player);
        budget.beginSearch();                         // the initial evaluation is the initialization, paid from the budget
        double best = initial, sum = 0;
        boolean bestTerminal = false;
        int bestLength = 0;
        long samples = 0, duplicates = 0;
        Set<String> seen = new HashSet<>();
        while (!budget.exhausted()) {
            S s = game.copy(state);
            StringBuilder plan = new StringBuilder();
            int length = 0;
            // One sample's own work. The advances inside are the meter's, and their time is not counted twice.
            try (Meter.Span span = SAMPLE.span()) {
                while (length < MAX_ACTIONS && !game.isTerminal(s) && game.activePlayer(s) == player) {
                    List<A> legal = game.legalActions(s);
                    if (legal.isEmpty()) break;
                    A a = legal.get(rnd.nextInt(legal.size()));
                    plan.append(game.actionKey(a)).append(';');
                    game.advance(s, a);
                    length++;
                    if (game.endsTurn(a)) break;
                }
            }
            double v = game.evaluate(s, player);
            samples++;
            sum += v;
            if (!seen.add(plan.toString())) duplicates++;
            if (v > best) {
                best = v;
                bestTerminal = game.isTerminal(s);
                bestLength = length;
            }
        }
        return TurnRecord.builder()
                .values(initial, best, bestTerminal, samples > 0 ? sum / samples : initial)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, 0)
                .planMillis(System.currentTimeMillis() - t0)
                .diversity(samples, seen.size(), duplicates)
                .sequenceLength(bestLength)
                .build();
    }

    /** The registry entry: {@code r.register(FirstAgent.factory())}. */
    public static <S, A> AgentFactory<S, A> factory() {
        Meter.declare(OPERATIONS);
        return new AgentFactory<S, A>() {
            @Override public String id() { return "first"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new FirstAgent<>(seed); }
            @Override public List<String> operations() { return OPERATIONS; }
            @Override public String description() { return "random restarts, the example of docs/ADDING_AN_ALGORITHM.md"; }
        };
    }
}
```

What each part does:

- **`plan` gets its own copy of the position** and the player to plan for. A fresh agent is created
  for every turn, so it may keep per-turn state freely and must keep none across turns.
- **`budget.beginSearch()`** marks the boundary: what came before is initialization, reported in the
  `init_*` columns. It is paid from the budget (the search gets what is left), so a level is the whole
  turn's thinking time, and an expensive initialization is not free. Let it stop when the budget runs out:
  the calibration and `check time` flag a cell whose turns spend more than 1.10× their level. An agent
  with no initialization never calls it, and then the whole turn counts.
- **`budget.exhausted()`** is the stop rule: units spent, at this arm's prices, reach the budget. Every
  `copy`, `advance` and `evaluate` through `game` is counted by the meter, which the agent never touches
  for the forward model.
- **`new Random(seed)` is the only randomness.** The seed is the same for every arm on the same
  `(scenario, visit)`, which makes the comparison paired.
- **`TurnRecord`** carries the values, the raw counts and `Meter.units()`, the wall-clock, the
  diversity counts (`nodes`, `unique`, `duplicates`), and optionally anytime checkpoints and telemetry.
- **The factory** gives the arm its id and declares its operations. The id is permanent once a result
  is published under it: lowercase, kebab-case, the left half of every profile key (`first@9500`), and
  the name of its results directory.

## Declaring your algorithm's operations

The forward model is not all an algorithm does. Tree bookkeeping, ranking candidates and legal-action
queries take time too. The currency prices them
([`CURRENCY.md`](CURRENCY.md#why-every-operation-is-priced)), provided the arm declares them.

1. **Name them.** Lowercase, dot-separated, led by the algorithm's family: `first.sample`,
   `emcts.legal`. `AgentFactory.operations()` returns exactly what this arm's
   parameters make it perform, and the factory registers them with `Meter.declare` when it is created.
2. **Record them** through a handle registered once:
   `static final Meter.Op SAMPLE = Meter.register("first.sample")`.
   - `try (Meter.Span s = SAMPLE.span()) { ... }` records one occurrence, timed while calibrating. A
     span's time excludes the forward-model calls and the spans nested inside it, so nothing is priced
     twice. `FirstAgent`'s span contains advances, and the meter subtracts them.
   - `try (Meter.Span s = LOOKUP.span(n)) { ...n lookups... }` is **the rule for cheap operations**. A
     lookup of tens of nanoseconds cannot be timed one call at a time without the timer dominating it,
     so time the loop that performs n of them as one batch. Where there is no loop, fold the cheap work
     into a coarser span: EMCTS's `emcts.generation` holds a generation's own bookkeeping.
   - `OP.count()` records an untimed occurrence of an operation that is timed elsewhere; its price is
     learned from the timed ones. An operation that is counted but never timed fails its calibration
     cell.
3. **Never self-report the forward model.** `advance`, `copy` and `evaluate` are counted by
   `MeteredGame`, the anchor nobody can under-report.
4. **It is enforced:**
   - a turn that records an undeclared operation is refused;
   - so is a turn under a profile that leaves a recorded operation unpriced;
   - the experiment validator refuses a run whose currency leaves a declared operation unpriced.
5. **The audit checks the declarations.** `magat check audit --arms <id>` times the arm's turns and
   attributes the time to the forward model, to each declared operation, and to nothing. It warns
   above 10% unaccounted and fails above 25%. Undeclared work shows up as "nothing".

On Tribes, `emcts` declares five operations, and with the forward model they leave well under 1% of
its turn unaccounted.

## Registering it

For Tribes, add one line to `TribesAgents.registry()` in
`games/tribes/src/magat/games/tribes/TribesAgents.java`:

```java
r.register(FirstAgent.factory());
```

```bash
python magat.py arms --game tribes
```

`first` is now listed. Before anything is measured, check its declarations:

```bash
python magat.py check audit --game tribes --arms first --budget 8000
```

## An algorithm ported from an existing implementation

If the algorithm already exists elsewhere (a paper's code, a game's own agents), port it, and gate the
port against the original rather than trusting it:

1. Implement an `Oracle<S>` that drives the original implementation on a scenario with the same seed
   and budget, and returns its signature:
   - `initial`, `final`, `advances`, `copies`, `evals`, `nodes`, `unique`, `units`;
   - the initialization spend;
   - the counters.

   It also says whether the original pays its initialization from the budget (`initialization()`): the
   gate plays the port by the same rule, so the two stop at the same point. Register it in the game
   module's `oracles()`.
2. Run the oracle gate:

   ```bash
   python magat.py check equivalence --game tribes --arms <id> --scenarios 8 --visits 2 --budgets 8000,40000
   ```

   It requires the port to reproduce the original field for field, with identical weights on both
   sides: the fixed measurement weights, unless `--weights` names a currency. Make every copy the
   original makes, even one it never reads: where a copy draws from the state's random generator, as
   every Tribes copy does, the copies after it are seeded from what is left, and leaving one out lets
   a chance event come out otherwise. Only a copy that draws nothing may be left out, and declared
   with `deadInitCopies()`. `emcts` passes this gate against the engine's EMCTS agent and `tbets`
   against its TBETS agent, counters included: 32 cells each by default, and 128 each on 64 positions.

## Pricing it, running it, comparing it

1. **Price it in your machine's currency.** Measuring and comparing arms needs an idle machine. With a
   currency of your own, from `calibrate-controlled`, add the arm:

   ```bash
   python magat.py calibrate --game tribes --out $HOME/magat_runs/calibration/tribes-<machine> --arms first
   ```

   This merges into the currency by ratio, and prices its operations as well.
2. **Write a spec** with the arm, the baseline at two levels or more, and the comparisons. For example
   `experiments/tribes/first.json`:

   ```json
   {
     "name": "first",
     "game": "tribes",
     "split": "dev",
     "baseline": "emcts",
     "levels": [ { "budget": 9500, "visits": 4 }, { "budget": 20000, "visits": 4 } ],
     "arms": [ { "id": "emcts" }, { "id": "first" } ],
     "contrasts": [ { "a": "first", "b": "emcts", "label": "random restarts against the baseline" } ]
   }
   ```

3. **Validate it, run it on the development half, and analyse it:**

   ```bash
   python magat.py experiment validate --experiment experiments/tribes/first.json --weights <your weights>
   python magat.py run --experiment experiments/tribes/first.json --split dev --weights <your weights> --out $HOME/magat_runs/tribes/runs/first
   python magat.py analyze --run $HOME/magat_runs/tribes/runs/first --out $HOME/magat_runs/tribes/results/first
   ```

   `SUMMARY.md` gives the paired difference against the baseline in units and in seconds, and the
   budget multiplier. Budget multipliers are dimensionless, so they compare across machines and
   currencies.
4. **Confirm on the holdout.** Once the choices are made on the development half, run the same spec
   with `--split holdout` ([methods](METHODS.md#3-two-halves-explore-on-one-confirm-on-the-other)).

The README's "afternoon path" does all of this at toy size in a few minutes.
