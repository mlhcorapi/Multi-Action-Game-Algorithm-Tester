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
 * of its own, a sampled plan's work outside the forward model, so a version-2 currency prices it.
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
        budget.beginSearch();                         // from here on, every priced operation is paid from the budget
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
