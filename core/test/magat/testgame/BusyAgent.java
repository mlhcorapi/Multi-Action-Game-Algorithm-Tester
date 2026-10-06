package magat.testgame;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.Collections;
import java.util.List;

/**
 * A test agent for the audit: every iteration does a little forward-model work and then a busy loop
 * that dwarfs it. Declared, the loop is the operation {@code busy.work}, recorded in a span, and the
 * audit finds nearly every nanosecond accounted for; undeclared, the same loop runs outside any span,
 * and the audit must report most of the turn as unpriced.
 */
public final class BusyAgent<S, A> implements SearchAgent<S, A> {

    public static final String WORK = "busy.work";
    private static final Meter.Op WORK_OP = Meter.register(WORK);
    /** Where the loop's result goes, so the JIT cannot remove it. */
    public static volatile double sink;

    private final boolean declared;

    private BusyAgent(boolean declared) {
        this.declared = declared;
    }

    /** About a hundred microseconds of arithmetic: far more than a toy game's forward-model call. */
    static void burn() {
        double x = 1;
        for (int i = 0; i < 100_000; i++) x = x * 1.0000001 + 1e-9;
        sink = x;
    }

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        long t0 = System.currentTimeMillis();
        double initial = game.evaluate(state, player);
        double best = initial;
        while (!budget.exhausted()) {
            S s = game.copy(state);
            List<A> legal = game.legalActions(s);
            if (!legal.isEmpty()) game.advance(s, legal.get(0));
            best = Math.max(best, game.evaluate(s, player));
            if (declared) {
                try (Meter.Span w = WORK_OP.span()) {
                    burn();
                }
            } else {
                burn();
            }
        }
        return TurnRecord.builder().values(initial, best, false, best)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .planMillis(System.currentTimeMillis() - t0).build();
    }

    /** An arm running the agent, with its busy loop declared as {@code busy.work} or not declared at all. */
    public static <S, A> AgentFactory<S, A> factory(String id, boolean declared) {
        return new AgentFactory<S, A>() {
            @Override public String id() { return id; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new BusyAgent<>(declared); }
            @Override public List<String> operations() { return declared ? Collections.singletonList(WORK) : Collections.emptyList(); }
        };
    }
}
