package magat.game;

import magat.meter.Meter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The contract laws, as runnable checks over captured positions. Every law is total: a throw inside
 * a law is a failure of that law, not of the check.
 *
 * <p>Runs on the un-metered game for L1–L8 and wraps it itself for L9, so the meter's state never
 * leaks into a law that is not about the meter.
 */
public final class ContractCheck {

    /** One law's verdict. */
    public static final class Verdict {
        public final String law;
        public final String title;
        public final boolean passed;
        public final String detail;

        Verdict(String law, String title, boolean passed, String detail) {
            this.law = law; this.title = title; this.passed = passed; this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "  [%s] %-4s %s%s", passed ? "ok" : "FAIL", law, title,
                    detail == null || detail.isEmpty() ? "" : " - " + detail);
        }
    }

    public static final class Report {
        public final List<Verdict> verdicts = new ArrayList<>();

        public boolean allPassed() {
            for (Verdict v : verdicts) if (!v.passed) return false;
            return true;
        }

        public int failures() {
            int n = 0;
            for (Verdict v : verdicts) if (!v.passed) n++;
            return n;
        }

        public void print() {
            for (Verdict v : verdicts) System.out.println(v);
        }
    }

    private ContractCheck() {}

    /** Snapshot of everything L1/L2/L6 compare: legal action keys, value, active player, terminal. */
    private static <S, A> String observe(Game<S, A> g, S s) {
        StringBuilder sb = new StringBuilder();
        boolean terminal = g.isTerminal(s);
        int player = g.activePlayer(s);
        sb.append("terminal=").append(terminal).append(" player=").append(player);
        sb.append(" value=").append(g.evaluate(s, player));
        sb.append(" legal=");
        for (A a : g.legalActions(s)) sb.append(g.actionKey(a)).append('|');
        return sb.toString();
    }

    /** A deterministic action policy for the laws that walk a few steps: the i-th legal action. */
    private static <S, A> A pick(Game<S, A> g, S s, int i) {
        List<A> legal = g.legalActions(s);
        if (legal.isEmpty()) return null;
        return legal.get(Math.floorMod(i * 7 + 3, legal.size()));
    }

    public static <S, A> Report run(Game<S, A> game, List<Scenario<S>> positions) {
        Report r = new Report();
        if (positions.isEmpty()) {
            r.verdicts.add(new Verdict("L0", "positions to check", false, "no positions given"));
            return r;
        }
        r.verdicts.add(law("L1", "copy isolates the original", () -> l1(game, positions)));
        r.verdicts.add(law("L2", "reseeded copies are deterministic", () -> l2(game, positions)));
        r.verdicts.add(law("L3", "legal actions are non-empty and legal", () -> l3(game, positions)));
        r.verdicts.add(law("L4", "isLegal never throws on stale actions", () -> l4(game, positions)));
        r.verdicts.add(law("L5", "the end-turn action ends the turn", () -> l5(game, positions)));
        r.verdicts.add(law("L6", "serialize/deserialize round-trips", () -> l6(game, positions)));
        r.verdicts.add(law("L7", "action keys are unique within a state", () -> l7(game, positions)));
        r.verdicts.add(law("L8", "evaluate is deterministic and finite", () -> l8(game, positions)));
        r.verdicts.add(law("L9", "the meter counts every operation", () -> l9(game, positions)));
        return r;
    }

    private interface Law { String check() throws Exception; }   // returns null on pass, a detail on failure

    private static Verdict law(String id, String title, Law law) {
        try {
            String detail = law.check();
            return new Verdict(id, title, detail == null, detail == null ? "" : detail);
        } catch (Throwable t) {
            return new Verdict(id, title, false, "threw " + t);
        }
    }

    private static <S, A> String l1(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S original = g.copy(sc.state);
            String before = observe(g, original);
            S c = g.copy(original);
            for (int i = 0; i < 3; i++) {
                A a = pick(g, c, i);
                if (a == null) break;
                g.advance(c, a);
            }
            String after = observe(g, original);
            if (!before.equals(after)) return sc + ": original changed after advancing its copy";
        }
        return null;
    }

    private static <S, A> String l2(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S a = g.copy(sc.state), b = g.copy(sc.state);
            g.reseed(a, 7L); g.reseed(b, 7L);
            for (int i = 0; i < 6; i++) {
                A x = pick(g, a, i), y = pick(g, b, i);
                if (x == null || y == null) {
                    if (x != y) return sc + ": legal sets diverged at step " + i;
                    break;
                }
                if (!g.actionKey(x).equals(g.actionKey(y))) return sc + ": picks diverged at step " + i;
                g.advance(a, x); g.advance(b, y);
                if (!observe(g, a).equals(observe(g, b))) return sc + ": observations diverged after step " + i;
            }
        }
        return null;
    }

    private static <S, A> String l3(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S s = g.copy(sc.state);
            if (g.isTerminal(s)) continue;
            List<A> legal = g.legalActions(s);
            if (legal.isEmpty()) return sc + ": no legal actions at a non-terminal turn start";
            for (A a : legal) if (!g.isLegal(s, a)) return sc + ": listed action not isLegal: " + g.actionKey(a);
        }
        return null;
    }

    private static <S, A> String l4(Game<S, A> g, List<Scenario<S>> ps) {
        if (ps.size() < 2) return null;
        int judgedLegal = 0, tried = 0;
        for (int i = 0; i < ps.size(); i++) {
            S here = g.copy(ps.get(i).state);
            Scenario<S> other = ps.get((i + 1) % ps.size());
            S there = g.copy(other.state);
            for (A stale : g.legalActions(there)) {
                tried++;
                try {
                    if (g.isLegal(here, stale)) judgedLegal++;
                } catch (Throwable t) {
                    return ps.get(i) + ": isLegal threw on an action of " + other + ": " + t;
                }
            }
        }
        // Informational only: actions from another game may coincide by chance.
        System.out.println(String.format(Locale.ROOT,
                "        L4: %d cross-state actions tried, %d judged legal (informational)", tried, judgedLegal));
        return null;
    }

    private static <S, A> String l5(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S s = g.copy(sc.state);
            if (g.isTerminal(s)) continue;
            A end = g.endTurnAction(s);
            if (end == null) continue;               // the game ends turns implicitly
            if (!g.endsTurn(end)) return sc + ": endTurnAction is not endsTurn";
            int before = g.activePlayer(s);
            String legalBefore = observe(g, s);
            g.advance(s, end);
            if (g.activePlayer(s) == before && observe(g, s).equals(legalBefore))
                return sc + ": nothing changed after the end-turn action";
        }
        return null;
    }

    private static <S, A> String l6(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S s = g.copy(sc.state);
            byte[] bytes = g.serialize(s);
            S t = g.deserialize(bytes);
            if (!observe(g, s).equals(observe(g, t))) return sc + ": observation differs after round-trip";
            g.reseed(s, 11L); g.reseed(t, 11L);
            for (int i = 0; i < 4; i++) {
                A x = pick(g, s, i), y = pick(g, t, i);
                if (x == null || y == null) break;
                g.advance(s, x); g.advance(t, y);
                if (!observe(g, s).equals(observe(g, t))) return sc + ": diverged after round-trip at step " + i;
            }
        }
        return null;
    }

    private static <S, A> String l7(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S s = g.copy(sc.state);
            Set<String> keys = new HashSet<>();
            for (A a : g.legalActions(s)) {
                if (!keys.add(g.actionKey(a))) return sc + ": duplicate action key " + g.actionKey(a);
            }
        }
        return null;
    }

    private static <S, A> String l8(Game<S, A> g, List<Scenario<S>> ps) {
        for (Scenario<S> sc : ps) {
            S s = g.copy(sc.state);
            int player = g.activePlayer(s);
            double v1 = g.evaluate(s, player);
            Meter.reset();
            double v2 = g.evaluate(s, player);
            if (Double.isNaN(v1) || Double.isInfinite(v1)) return sc + ": non-finite value " + v1;
            if (Double.compare(v1, v2) != 0) return sc + ": evaluate not deterministic: " + v1 + " vs " + v2;
        }
        return null;
    }

    private static <S, A> String l9(Game<S, A> g, List<Scenario<S>> ps) {
        MeteredGame<S, A> m = MeteredGame.of(g);
        EngineCounters engine = (g instanceof EngineCounters) ? (EngineCounters) g : null;
        Scenario<S> sc = ps.get(0);
        Meter.reset();
        if (engine != null) engine.resetEngineCounts();
        S s = m.copy(sc.state);                       // 1 copy
        int adv = 0, cop = 1, ev = 0;
        for (int i = 0; i < 5; i++) {
            A a = pick(m, s, i);
            if (a == null) break;
            S t = m.copy(s); cop++;
            m.advance(t, a); adv++;
            m.evaluate(t, m.activePlayer(t)); ev++;
        }
        if (Meter.advances() != adv || Meter.copies() != cop || Meter.evals() != ev)
            return String.format(Locale.ROOT, "meter read %d/%d/%d, expected %d/%d/%d",
                    Meter.advances(), Meter.copies(), Meter.evals(), adv, cop, ev);
        if (engine != null) {
            long[] e = engine.engineCounts();
            if (e[0] != adv || e[1] != cop || e[2] != ev)
                return String.format(Locale.ROOT,
                        "engine's own counters read %d/%d/%d against the meter's %d/%d/%d", e[0], e[1], e[2], adv, cop, ev);
        }
        return null;
    }
}
