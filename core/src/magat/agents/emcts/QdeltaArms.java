package magat.agents.emcts;

import magat.game.Granularity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The arms of the worked result (the AB-EMCTS line) beyond {@code emcts} and {@code emcts-comma}, as
 * the thesis tree's {@code Stage1Runner} defined them, under their MAGAT ids (DESIGN Appendix A).
 * Game-agnostic: the arms read only granularities, and the game supplies the classes.
 *
 * <p>Not here: {@code comma-z0-fine-insfine}, which the published currency prices but the thesis
 * dropped before running it (at epsilon 1.00 the insertion table is never read, so it would produce
 * output identical to {@code comma-z0-fine}: a bug by principle 12).
 */
public final class QdeltaArms {

    private QdeltaArms() {}

    /** The epsilon of the plus-era zero-evaluation arms ({@code Stage1Runner.Z0_EPS}). */
    static final double PLUS_Z0_EPS = 0.25;

    private static QdeltaParams arm(double eps, Granularity insertKeys, Granularity repairKeys, boolean removal, boolean perBurn,
                                    QdeltaParams.RepairMode mode, int k, Selection selection, int children) {
        QdeltaParams p = new QdeltaParams();
        p.epsilon = eps;
        p.insertKeys = insertKeys;
        p.repairKeys = repairKeys;
        p.removalCredit = removal;
        p.perBurnUpdates = perBurn;
        p.repairMode = mode;
        p.shortlistK = k;
        p.selection = selection;
        p.childrenPerGeneration = children;
        return p;
    }

    /** Stage D's arms: repair keys follow insertion keys; a shortlist when K > 0. */
    private static QdeltaParams bias(double eps, Granularity keys, boolean removal, boolean perBurn, int k, Selection s, int children) {
        return arm(eps, keys, keys, removal, perBurn, k > 0 ? QdeltaParams.RepairMode.SHORTLIST_K : QdeltaParams.RepairMode.FULL, k, s, children);
    }

    /** One arm: id, parameters, attribution control (or null), what it is. */
    public static final class Arm {
        public final String id;
        public final QdeltaParams params;
        public final String attribution;
        public final String thesisName;

        Arm(String id, String thesisName, QdeltaParams params, String attribution) {
            this.id = id;
            this.thesisName = thesisName;
            this.params = params;
            this.attribution = attribution;
        }

        public <S, A> EmctsFactory<S, A> factory() {
            return new EmctsFactory<>(id, thesisName + ": " + params.describe(), params::copy, attribution);
        }
    }

    public static List<Arm> all() {
        Selection P = Selection.PLUS, C = Selection.COMMA;
        Granularity CO = Granularity.COARSE, MI = Granularity.MID, FI = Granularity.FINE;
        QdeltaParams.RepairMode Z = QdeltaParams.RepairMode.ZERO_EVAL, ZR = QdeltaParams.RepairMode.ZERO_EVAL_RANDOM,
                SK = QdeltaParams.RepairMode.SHORTLIST_K, SR = QdeltaParams.RepairMode.SHORTLIST_RANDOM, F = QdeltaParams.RepairMode.FULL;
        List<Arm> a = new ArrayList<>();
        // ---- plus era (Stage D on the plus substrate)
        a.add(new Arm("emcts-bias-e75", "Bias_e75", bias(0.75, MI, true, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-e50", "Bias_e50", bias(0.50, MI, true, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-e25", "Bias_e25", bias(0.25, MI, true, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-e10", "Bias_e10", bias(0.10, MI, true, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-coarse-e25", "Coarse_e25", bias(0.25, CO, true, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-norem-e25", "NoRemoval_e25", bias(0.25, MI, false, false, 0, P, 1), null));
        a.add(new Arm("emcts-bias-perburn-e25", "PerBurn_e25", bias(0.25, MI, true, true, 0, P, 1), null));
        a.add(new Arm("emcts-short15", "Shortlist15", bias(1.00, MI, true, false, 15, P, 1), null));
        a.add(new Arm("emcts-short15-bias-e25", "Short15_e25", bias(0.25, MI, true, false, 15, P, 1), null));
        a.add(new Arm("emcts-short5-bias-e25", "Short5_e25", bias(0.25, MI, true, false, 5, P, 1), null));
        a.add(new Arm("emcts-z0-coarse", "Z0_Coarse", arm(PLUS_Z0_EPS, CO, CO, true, false, Z, 0, P, 1), null));
        a.add(new Arm("emcts-z0-mid", "Z0_Mid", arm(PLUS_Z0_EPS, CO, MI, true, false, Z, 0, P, 1), null));
        a.add(new Arm("emcts-z0-fine", "Z0_Fine", arm(PLUS_Z0_EPS, CO, FI, true, false, Z, 0, P, 1), null));
        a.add(new Arm("emcts-z0-fine-insfine", "Z0_Fine_InsFine", arm(PLUS_Z0_EPS, FI, FI, true, false, Z, 0, P, 1), null));
        // ---- the comma grid (Stage D re-based on comma k=5 at the Gate B re-pin)
        a.add(new Arm("comma-bias-e75", "Comma_e75", bias(0.75, MI, true, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-e50", "Comma_e50", bias(0.50, MI, true, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-e25", "Comma_On", bias(0.25, MI, true, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-e10", "Comma_e10", bias(0.10, MI, true, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-coarse-e25", "Comma_Coarse_e25", bias(0.25, CO, true, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-norem-e25", "Comma_NoRem_e25", bias(0.25, MI, false, false, 0, C, 5), null));
        a.add(new Arm("comma-bias-perburn-e25", "Comma_PerBurn_e25", bias(0.25, MI, true, true, 0, C, 5), null));
        a.add(new Arm("comma-short15", "Comma_Short15", bias(1.00, MI, true, false, 15, C, 5), "comma-rand15"));
        a.add(new Arm("comma-short15-bias-e25", "Comma_Short15_e25", bias(0.25, MI, true, false, 15, C, 5), null));
        a.add(new Arm("comma-short5-bias-e25", "Comma_Short5_e25", bias(0.25, MI, true, false, 5, C, 5), null));
        // ---- Stage E: table-directed repair on comma, unbiased insertion
        a.add(new Arm("comma-z0-coarse", "Comma_Z0_Coarse", arm(1.00, MI, CO, true, false, Z, 0, C, 5), "comma-z0-random"));
        a.add(new Arm("comma-z0-mid", "Comma_Z0_Mid", arm(1.00, MI, MI, true, false, Z, 0, C, 5), "comma-z0-random"));
        a.add(new Arm("comma-z0-fine", "Comma_Z0_Fine", arm(1.00, MI, FI, true, false, Z, 0, C, 5), "comma-z0-random"));
        a.add(new Arm("comma-z0-random", "Comma_Z0_Random", arm(1.00, MI, MI, true, false, ZR, 0, C, 5), null));
        a.add(new Arm("comma-rand15", "Comma_Rand15", arm(1.00, MI, MI, true, false, SR, 15, C, 5), null));
        a.add(new Arm("comma-short15-coarse", "Comma_Short15_Coarse", arm(1.00, MI, CO, true, false, SK, 15, C, 5), "comma-rand15"));
        a.add(new Arm("comma-bias-fine-e25", "Comma_Fine_e25", arm(0.25, FI, FI, true, false, F, 0, C, 5), null));
        return Collections.unmodifiableList(a);
    }
}
