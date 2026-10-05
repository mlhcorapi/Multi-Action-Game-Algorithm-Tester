package magat.agent;

import magat.game.Game;
import magat.game.MeteredGame;
import magat.game.Scenario;
import magat.meter.Meter;
import magat.meter.Profile;

/**
 * One replanned turn: the sequence every runner, calibrator and probe used to spell out for itself,
 * written once. Copy the captured state, pin its stochasticity to the visit seed, install the arm's
 * profile for this budget, create a fresh agent, plan.
 */
public final class Episode {

    private Episode() {}

    public static <S, A> TurnRecord run(Game<S, A> game, Scenario<S> scenario, int visit,
                                        AgentFactory<S, A> arm, long budgetUnits) {
        return run(game, scenario, visit, arm, budgetUnits, Profile.key(arm.id(), budgetUnits));
    }

    /** As {@link #run(Game, Scenario, int, AgentFactory, long)} with an explicit profile (probes only). */
    public static <S, A> TurnRecord run(Game<S, A> game, Scenario<S> scenario, int visit,
                                        AgentFactory<S, A> arm, long budgetUnits, String profile) {
        return run(game, scenario, visit, arm, budgetUnits, () -> Meter.useProfile(profile));
    }

    /**
     * As {@link #run(Game, Scenario, int, AgentFactory, long)} with explicit weights under a label
     * (probes that must price both sides of a comparison identically at an unpriced budget).
     */
    public static <S, A> TurnRecord run(Game<S, A> game, Scenario<S> scenario, int visit,
                                        AgentFactory<S, A> arm, long budgetUnits, double[] weights, String label) {
        return run(game, scenario, visit, arm, budgetUnits, () -> Meter.useWeights(weights, label));
    }

    private static <S, A> TurnRecord run(Game<S, A> game, Scenario<S> scenario, int visit,
                                         AgentFactory<S, A> arm, long budgetUnits, Runnable pricing) {
        return planned(game, scenario, visit, arm, budgetUnits, pricing).record;
    }

    /** A planned turn together with the agent that planned it, for probes that read its {@code support()}. */
    public static final class Planned<S, A> {
        public final SearchAgent<S, A> agent;
        public final TurnRecord record;

        Planned(SearchAgent<S, A> agent, TurnRecord record) {
            this.agent = agent;
            this.record = record;
        }
    }

    /** As {@link #run(Game, Scenario, int, AgentFactory, long)}, keeping the agent. */
    public static <S, A> Planned<S, A> planned(Game<S, A> game, Scenario<S> scenario, int visit,
                                               AgentFactory<S, A> arm, long budgetUnits) {
        String profile = Profile.key(arm.id(), budgetUnits);
        return planned(game, scenario, visit, arm, budgetUnits, () -> Meter.useProfile(profile));
    }

    private static <S, A> Planned<S, A> planned(Game<S, A> game, Scenario<S> scenario, int visit,
                                                AgentFactory<S, A> arm, long budgetUnits, Runnable pricing) {
        MeteredGame<S, A> metered = MeteredGame.of(game);
        long seed = Seeds.visit(scenario.id, visit);
        S state = metered.inner().copy(scenario.state);    // the working copy is not the agent's spend
        metered.reseed(state, seed);
        Meter.reset();
        pricing.run();
        TurnBudget budget = TurnBudget.units(budgetUnits);
        SearchAgent<S, A> agent = arm.create(seed, budget);
        return new Planned<>(agent, agent.plan(metered, state, metered.activePlayer(state), budget));
    }
}
