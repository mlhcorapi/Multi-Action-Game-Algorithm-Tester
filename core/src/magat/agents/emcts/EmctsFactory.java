package magat.agents.emcts;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.meter.Meter;

import java.util.List;
import java.util.function.Supplier;

/** A registry entry for one {@link Emcts} configuration. */
public final class EmctsFactory<S, A> implements AgentFactory<S, A> {

    private final String id;
    private final String description;
    private final Supplier<EmctsParams> params;

    public EmctsFactory(String id, String description, Supplier<EmctsParams> params) {
        this.id = id;
        this.description = description;
        this.params = params;
        Meter.declare(Emcts.OPERATIONS);
    }

    /** Plain EMCTS: greedy initialization, 21 phases, (1+1) incumbent selection, exhaustive repair. */
    public static <S, A> EmctsFactory<S, A> plain() {
        return new EmctsFactory<>("emcts",
                "plain EMCTS: greedy 1-ply initialization, 21 phases / 20 burns, (1+1) incumbent selection, exhaustive greedy repair",
                EmctsParams::new);
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public List<String> operations() { return Emcts.OPERATIONS; }

    @Override
    public SearchAgent<S, A> create(long seed, TurnBudget budget) {
        return new Emcts<>(seed, params.get().copy());
    }

    public EmctsParams params() {
        return params.get().copy();
    }
}
