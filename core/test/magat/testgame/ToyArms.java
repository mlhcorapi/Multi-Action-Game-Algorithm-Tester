package magat.testgame;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.EmctsParams;
import magat.meter.Meter;

import java.util.List;

/** Arms the toy tests need beside the registered ones. */
public final class ToyArms {

    private ToyArms() {}

    /**
     * A second priced EMCTS configuration, {@code emcts-alt} (11 phases instead of 21): a different search
     * that records the same operations, for tests that need two priced arms.
     */
    public static <S, A> EmctsFactory<S, A> emctsAlt() {
        return new EmctsFactory<>("emcts-alt", "EMCTS with 11 phases (tests only)", () -> {
            EmctsParams p = new EmctsParams();
            p.phases = 11;
            return p;
        });
    }

    /**
     * {@code spendthrift}: an initialization that cannot stop, spending three times the budget before its search
     * begins (which then has nothing left): the case the spend check flags.
     */
    public static <S, A> AgentFactory<S, A> spendthrift() {
        return new AgentFactory<S, A>() {
            @Override public String id() { return "spendthrift"; }
            @Override public String description() { return "an initialization that spends three times its budget (tests only)"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) {
                return (game, state, player, b) -> {
                    double initial = game.evaluate(state, player);
                    while (Meter.units() < 3.0 * b.units()) {
                        S s = game.copy(state);
                        List<A> legal = game.legalActions(s);
                        if (!legal.isEmpty()) game.advance(s, legal.get(0));
                        game.evaluate(s, player);
                    }
                    b.beginSearch();
                    return TurnRecord.builder().values(initial, initial, false, initial)
                            .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units()).init(b, 0).build();
                };
            }
        };
    }
}
