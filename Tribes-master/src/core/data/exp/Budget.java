package core.data.exp;

/**
 * A named fmcall budget level. Budgets are calibrated to roughly match a target thinking
 * time (see {@link Calibrate}); using fmcalls (not wall-clock) keeps search effort
 * deterministic and reproducible under multithreading.
 */
public class Budget {
    public final String name;     // label used in filenames/columns, e.g. "200ms"
    public final int fmcalls;     // forward-model-call budget per turn

    public Budget(String name, int fmcalls) {
        this.name = name;
        this.fmcalls = fmcalls;
    }
}
