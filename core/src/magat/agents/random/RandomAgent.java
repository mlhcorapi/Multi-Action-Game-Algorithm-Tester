package magat.agents.random;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.List;
import java.util.Random;

/**
 * The floor: a uniformly random legal action until the turn ends or the plan is as long as an
 * EMCTS plan may be. No search, no budget use beyond the two evaluations that give it a record.
 */
public final class RandomAgent<S, A> implements SearchAgent<S, A> {

    public static final int MAX_ACTIONS = 20;

    private final Random rnd;

    public RandomAgent(long seed) {
        this.rnd = new Random(seed);
    }

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        long t0 = System.currentTimeMillis();
        double initial = game.evaluate(state, player);
        budget.beginSearch();
        int length = 0;
        while (!game.isTerminal(state) && game.activePlayer(state) == player && length < MAX_ACTIONS) {
            List<A> legal = game.legalActions(state);
            if (legal.isEmpty()) break;
            A a = legal.get(rnd.nextInt(legal.size()));
            game.advance(state, a);
            length++;
            if (game.endsTurn(a)) break;
        }
        double fin = game.evaluate(state, player);
        return TurnRecord.builder()
                .values(initial, fin, game.isTerminal(state), fin)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, 0)
                .planMillis(System.currentTimeMillis() - t0)
                .diversity(1, 1, 0)
                .sequenceLength(length)
                .build();
    }

    /** The registry entry. */
    public static <S, A> AgentFactory<S, A> factory() {
        return new AgentFactory<S, A>() {
            @Override public String id() { return "random"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new RandomAgent<>(seed); }
            @Override public String description() { return "uniformly random legal actions; the floor"; }
            @Override public boolean priced() { return false; }
        };
    }
}
