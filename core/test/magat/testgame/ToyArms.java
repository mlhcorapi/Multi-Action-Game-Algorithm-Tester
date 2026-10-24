package magat.testgame;

import magat.agents.emcts.EmctsFactory;
import magat.agents.emcts.EmctsParams;

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
}
