package magat.agents.emcts;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.meter.Meter;

import java.util.List;
import java.util.function.Supplier;

/**
 * A registry entry for one EMCTS-family configuration: plain {@link Emcts} for {@link EmctsParams},
 * {@link QdeltaEmcts} for {@link QdeltaParams}.
 */
public final class EmctsFactory<S, A> implements AgentFactory<S, A> {

    private final String id;
    private final String description;
    private final Supplier<EmctsParams> params;
    private final String attributionControl;
    private final List<String> operations;

    public EmctsFactory(String id, String description, Supplier<EmctsParams> params) {
        this(id, description, params, null);
    }

    public EmctsFactory(String id, String description, Supplier<EmctsParams> params, String attributionControl) {
        this.id = id;
        this.description = description;
        this.params = params;
        this.attributionControl = attributionControl;
        EmctsParams p = params.get();
        this.operations = p instanceof QdeltaParams ? QdeltaEmcts.operations((QdeltaParams) p) : Emcts.OPERATIONS;
        Meter.declare(operations);
    }

    /** Plain EMCTS: greedy initialization, 21 phases, (1+1) incumbent selection, exhaustive repair. */
    public static <S, A> EmctsFactory<S, A> plain() {
        return new EmctsFactory<>("emcts",
                "plain EMCTS: greedy 1-ply initialization, 21 phases / 20 burns, (1+1) incumbent selection, exhaustive greedy repair",
                EmctsParams::new);
    }

    /** EMCTS on (1,5) comma selection: the pinned substrate and baseline of the worked result. */
    public static <S, A> EmctsFactory<S, A> comma(int k) {
        return new EmctsFactory<>("emcts-comma",
                "EMCTS with (1," + k + ") comma selection",
                () -> { EmctsParams p = new EmctsParams(); p.selection = Selection.COMMA; p.childrenPerGeneration = k; return p; });
    }

    /** A variant of this configuration under another id (structure checks; never registered beside its base). */
    public EmctsFactory<S, A> variant(String newId, Selection selection, int k) {
        return new EmctsFactory<>(newId, description + " [" + selection + " k=" + k + "]", () -> {
            EmctsParams p = params.get().copy();
            p.selection = selection;
            p.childrenPerGeneration = k;
            return p;
        });
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public String attributionControl() { return attributionControl; }
    @Override public List<String> operations() { return operations; }

    @Override
    public SearchAgent<S, A> create(long seed, TurnBudget budget) {
        EmctsParams p = params.get().copy();
        return p instanceof QdeltaParams ? new QdeltaEmcts<>(seed, (QdeltaParams) p) : new Emcts<>(seed, p);
    }

    public EmctsParams params() {
        return params.get().copy();
    }
}
