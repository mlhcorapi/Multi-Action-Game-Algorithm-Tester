"""The analyzer's acceptance test (design section 11.6): on the imported worked result, the AB-EMCTS
retrospective's section 3 and 4 numbers, point estimates to the decimal they were printed with and
intervals to bootstrap noise.

Source: Tribes-master/analysis/AB_EMCTS_RETROSPECTIVE.md (2026-09-09), computed on the R7615 from the
same rows by the thesis's analyze_stage1.py and by hand. The one number MAGAT does not reproduce is
section 3.2's wall-clock equivalent (3.3x): it was computed on all rows, where section 3.1 used the cells
paired across the ladder; MAGAT uses the paired cells for both and gets 3.2x (see
docs/results/tribes-ab-emcts.md). The test pins MAGAT's value and checks that the all-rows computation
gives the retrospective's.
"""
import math
import unittest

import numpy as np

from magat_analysis import load, measures

RESULT = load.ROOT / "games" / "tribes" / "results" / "ab-emcts"
BASE = "emcts-comma"
N = 4000


def one(df, **kw):
    for k, v in kw.items():
        df = df[df[k] == v]
    assert len(df) == 1, f"{kw}: {len(df)} rows"
    return df.iloc[0]


@unittest.skipUnless((RESULT / "holdout" / "_run.json").is_file(), "the worked result is not imported")
class Retrospective(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.hold = load.load_split(RESULT / "holdout")
        cls.dev = load.load_split(RESULT / "dev")
        pairs = load.comparisons(load.spec_of(cls.dev), load.spec_of(cls.hold))
        h, d = cls.hold.turns, cls.dev.turns
        cls.H = dict(headline=measures.headline(h, BASE, "holdout", N), contrasts=measures.contrasts(h, pairs, "holdout", N),
                     multiplier=measures.budget_multiplier(h, BASE, "holdout", N),
                     gain=measures.gain_multiple(h, BASE, "holdout", N), oracle=measures.oracle(h, "holdout"),
                     efficiency=measures.efficiency(h, BASE))
        cls.H["w7"] = measures.w7(h, BASE, cls.H["headline"], "holdout", N)
        cls.D = dict(headline=measures.headline(d, BASE, "dev", N), contrasts=measures.contrasts(d, pairs, "dev", N),
                     multiplier=measures.budget_multiplier(d, BASE, "dev", N),
                     plain=measures.budget_multiplier(d, "emcts", "dev", N), efficiency=measures.efficiency(d, BASE))

    # ------------------------------------------------------------------ helpers
    def printed(self, value, text, spec="+.1f"):
        self.assertEqual(format(value, spec), text)

    def interval(self, lo, hi, plo, phi, point):
        """Within bootstrap noise of the published interval: a tenth of its half-width, at least 1.0."""
        tol = max(1.0, 0.1 * (phi - plo) / 2)
        self.assertLess(abs(lo - plo), tol, f"lower bound {lo:.1f} against the published {plo}")
        self.assertLess(abs(hi - phi), tol, f"upper bound {hi:.1f} against the published {phi}")
        self.assertTrue(lo <= point <= hi)

    def ratio_interval(self, lo, hi, plo, phi):
        """A multiplier's bounds were printed to one decimal and its upper tail is skewed: 6% of each bound."""
        self.assertLess(abs(lo - plo), 0.06 * plo + 0.05, f"lower bound {lo:.2f} against the published {plo}")
        self.assertLess(abs(hi - phi), 0.06 * phi + 0.05, f"upper bound {hi:.2f} against the published {phi}")

    def headline(self, R, arm, budget=80000, stratum="all"):
        return one(R["headline"], Arm=arm, Budget=budget, Stratum=stratum)

    def contrast(self, R, a, b, budget=80000, stratum="all"):
        return one(R["contrasts"], A=a, B=b, Budget=budget, Stratum=stratum)

    # ------------------------------------------------------------------ section 1 and 4.2: holdout
    def test_the_headline(self):
        r = self.headline(self.H, "comma-z0-coarse")
        self.printed(r.MeanDiff, "+244.3")
        self.interval(r.Lo, r.Hi, 208.4, 281.7, r.MeanDiff)
        self.assertEqual((r.Pairs, r.Scenarios), (1600, 200))
        r4 = self.headline(self.H, "comma-z0-coarse", 400000)
        self.printed(r4.MeanDiff, "+297.4")
        self.interval(r4.Lo, r4.Hi, 252.5, 342.4, r4.MeanDiff)

    def test_the_headline_survives_terminal_exclusion_and_holds_on_both_sources(self):
        self.printed(self.headline(self.H, "comma-z0-coarse", stratum="no-terminal").MeanDiff, "+241.7")
        r = self.headline(self.H, "comma-z0-coarse")
        self.printed(r.MeanDiff_single, "+305.2")
        self.printed(r.MeanDiff_dual, "+183.4")

    def test_the_attribution(self):
        c = self.contrast(self.H, "comma-z0-coarse", "comma-z0-random")
        self.printed(c.MeanDiff, "+211.7")
        self.interval(c.Lo, c.Hi, 175.4, 249.0, c.MeanDiff)

    def test_the_ladder_on_the_holdout(self):
        for arm, text, lo, hi in (("comma-rand15", "+133.8", 107.2, 161.5),
                                  ("comma-short15-coarse", "+199.9", 167.0, 234.7)):
            r = self.headline(self.H, arm)
            self.printed(r.MeanDiff, text)
            self.interval(r.Lo, r.Hi, lo, hi, r.MeanDiff)
        c = self.contrast(self.H, "comma-short15-coarse", "comma-rand15")
        self.printed(c.MeanDiff, "+66.1")
        self.interval(c.Lo, c.Hi, 43.7, 89.9, c.MeanDiff)
        m = self.contrast(self.H, "comma-short15", "comma-rand15")
        self.printed(m.MeanDiff, "-21.2")
        self.assertTrue(m.Lo < 0 < m.Hi, "MID ranking: not significant")

    def test_the_fine_table_did_not_replicate(self):
        c = self.contrast(self.H, "comma-z0-fine", "comma-z0-random")
        self.printed(c.MeanDiff, "+3.8")
        self.interval(c.Lo, c.Hi, -13.3, 20.7, c.MeanDiff)
        self.assertTrue(c.Lo < 0 < c.Hi)
        self.printed(self.contrast(self.D, "comma-z0-fine", "comma-z0-random").MeanDiff, "+23.4")
        self.assertGreater(self.contrast(self.D, "comma-z0-fine", "comma-z0-random").Lo, 0)

    def test_w7_in_wall_clock(self):
        w = one(self.H["w7"], Arm="comma-z0-coarse", Budget=80000)
        self.printed(w.WallDiff, "+223.3")
        self.interval(w.WallLo, w.WallHi, 186.6, 261.1, w.WallDiff)
        self.assertEqual(format(w.ClampedShare, ".0%"), "14%")
        self.assertEqual(w.Verdict, "wins both")

    def test_zero_evaluation_repair_evaluates_nothing(self):
        e = self.H["efficiency"]
        for arm in ("comma-z0-coarse", "comma-z0-mid", "comma-z0-fine", "comma-z0-random"):
            for budget in (80000, 400000):
                self.assertEqual(one(e, Arm=arm, Budget=budget).t_repairCandidateEvals, 0.0, arm)

    # ------------------------------------------------------------------ section 4.1, 4.2: development
    def test_the_development_column(self):
        for arm, text in (("comma-z0-coarse", "+268.1"), ("comma-rand15", "+139.8"), ("comma-short15-coarse", "+206.5")):
            self.printed(self.headline(self.D, arm).MeanDiff, text)
        for a, b, text in (("comma-z0-coarse", "comma-z0-random", "+244.2"), ("comma-short15-coarse", "comma-rand15", "+66.6"),
                           ("comma-short15", "comma-rand15", "-20.9")):
            self.printed(self.contrast(self.D, a, b).MeanDiff, text)

    # ------------------------------------------------------------------ section 3.1: the budget multiplier
    def test_the_budget_multiplier(self):
        m = one(self.H["multiplier"], Arm="comma-z0-coarse", Budget=80000)
        self.assertEqual(m.BaseGains, "80000:259.5034;400000:487.6608")
        self.printed(m.ArmGain, "497.4", ".1f")
        self.assertEqual(m.Scenarios, 198)
        self.printed(m.Multiplier, "5.4", ".1f")
        self.assertEqual(m.Flag, "extrapolated", "a lower bound: the crossing is past 400k")
        self.ratio_interval(m.Lo, m.Hi, 4.3, 6.5)
        d = one(self.D["multiplier"], Arm="comma-z0-coarse", Budget=80000)
        self.printed(d.Multiplier, "6.4", ".1f")
        self.ratio_interval(d.Lo, d.Hi, 5.2, 8.1)

    # ------------------------------------------------------------------ section 3.2: in seconds
    def test_the_same_question_in_seconds(self):
        m = one(self.H["multiplier"], Arm="comma-z0-coarse", Budget=80000)
        ms = dict(p.split(":") for p in m.BaseMs.split(";"))
        self.printed(float(ms["400000"]) / float(ms["80000"]), "3.5", ".1f")
        # MAGAT: the paired cells of section 3.1, for units and seconds alike.
        self.printed(m.TimeMultiplier, "3.2", ".1f")
        self.printed(m.ArmTimeRatio, "1.17", ".2f")
        # The retrospective's 3.8x, 1.16x and 3.3x: the same formula on every non-terminal row.
        t = self.hold.turns
        t = t[~t.term]
        g = t.groupby(["arm", "budget"]).gain.mean()
        ms_all = t.groupby(["arm", "budget"]).plan_ms.mean()
        frac = (g["comma-z0-coarse", 80000] - g[BASE, 80000]) / (g[BASE, 400000] - g[BASE, 80000])
        need = (ms_all[BASE, 400000] / ms_all[BASE, 80000]) ** frac
        spent = ms_all["comma-z0-coarse", 80000] / ms_all[BASE, 80000]
        self.printed(need, "3.8", ".1f")
        self.printed(spent, "1.16", ".2f")
        self.printed(need / spent, "3.3", ".1f")

    # ------------------------------------------------------------------ section 3.3: against plain EMCTS
    def test_against_plain_emcts(self):
        p = one(self.D["plain"], Arm="comma-z0-coarse", Budget=80000)
        self.printed(p.Multiplier, "48", ".0f")
        self.ratio_interval(p.Lo, p.Hi, 28, 88)
        g = dict((int(a), float(b)) for a, b in (x.split(":") for x in p.BaseGains.split(";")))
        self.printed(g[400000] - g[80000], "+119.9")
        h = one(self.H["multiplier"], Arm="comma-z0-coarse", Budget=80000)
        hg = dict((int(a), float(b)) for a, b in (x.split(":") for x in h.BaseGains.split(";")))
        self.printed(hg[400000] - hg[80000], "+228.2")

    # ------------------------------------------------------------------ section 3.4: three further measures
    def test_the_search_gain_multiple(self):
        g = one(self.H["gain"], Arm="comma-z0-coarse", Budget=80000)
        self.printed(g.GainMultiple, "1.93", ".2f")
        self.printed(g.ArmGain, "500.3", ".1f")
        self.printed(g.BaseGain, "258.6", ".1f")
        self.printed(one(self.H["gain"], Arm="comma-z0-coarse", Budget=400000).GainMultiple, "1.60", ".2f")

    def test_the_oracle_table(self):
        o = self.H["oracle"]
        o = o[o.Budget == 80000]
        self.assertEqual(int(o.Turns.iloc[0]), 1584)
        published = [("comma-z0-coarse", "38.3%", "171.5"), ("comma-short15-coarse", "31.2%", "218.8"),
                     ("comma-z0-mid", "29.0%", "248.0"), ("comma-short15", "22.7%", "302.0"),
                     ("comma-rand15", "21.7%", "282.8"), ("comma-z0-fine", "17.6%", "378.8"),
                     ("emcts-comma", "15.3%", "413.1"), ("comma-z0-random", "14.2%", "383.1")]
        self.assertEqual(list(o.Arm), [p[0] for p in published], "the order of the field")
        for arm, share, regret in published:
            r = one(o, Arm=arm)
            self.assertEqual(format(r.BestShare, ".1%"), share, arm)
            self.printed(r.MeanRegret, regret, ".1f")

    def test_cohens_d(self):
        for arm, d in (("comma-z0-coarse", "0.62"), ("comma-short15-coarse", "0.54"), ("comma-rand15", "0.42"),
                       ("comma-z0-random", "0.10")):
            self.printed(self.headline(self.H, arm, stratum="no-terminal").D, d, ".2f")

    # ------------------------------------------------------------------ section 4.3: mechanism
    def test_the_mechanism(self):
        e = self.H["efficiency"]
        z, c = one(e, Arm="comma-z0-coarse", Budget=80000), one(e, Arm=BASE, Budget=80000)
        self.assertEqual((round(z.MeanNodes), round(z.MeanUnique)), (1435, 1293))
        self.assertEqual((round(c.MeanNodes), round(c.MeanUnique)), (457, 408))
        self.printed(z.NodesVsBase, "3.1", ".1f")
        self.printed(z.DupShare, "0.077", ".3f")
        self.printed(c.DupShare, "0.064", ".3f")
        de = self.D["efficiency"]
        b = one(de, Arm="comma-bias-e25", Budget=80000)
        self.printed(b.NodesVsBase, "1.47", ".2f")
        self.printed(one(de, Arm=BASE, Budget=80000).DupShare, "0.063", ".3f")
        self.printed(b.DupShare, "0.23", ".2f")

    # ------------------------------------------------------------------ section 5, where it is a table row
    def test_what_the_experiment_overturned(self):
        self.printed(self.headline(self.D, "comma-bias-e10", 400000).MeanDiff, "-65.0")
        self.printed(self.contrast(self.D, "comma-bias-coarse-e25", "comma-bias-e25").MeanDiff, "+45.3")
        self.printed(self.contrast(self.D, "comma-bias-e25", "comma-bias-fine-e25").MeanDiff, "+57.2")
        self.printed(self.contrast(self.D, "comma-z0-fine", "comma-z0-mid").MeanDiff, "-121.7")
        self.printed(self.contrast(self.D, "comma-z0-mid", "comma-z0-coarse").MeanDiff, "-99.1")
        s = self.contrast(self.D, "emcts-comma", "emcts")
        self.printed(s.MeanDiff, "+22.3")
        self.interval(s.Lo, s.Hi, 9.8, 34.8, s.MeanDiff)
        self.printed(self.headline(self.H, "comma-z0-fine").MeanDiff, "+36.4")
        self.printed(self.headline(self.H, "comma-z0-random").MeanDiff, "+32.6")

    # ------------------------------------------------------------------ the shipped report is this one
    def test_the_shipped_report_matches(self):
        import pandas as pd
        p = RESULT / "report" / "tables" / "headline.csv"
        self.assertTrue(p.is_file(), "games/tribes/results/ab-emcts/report is regenerated with magat analyze")
        shipped = one(pd.read_csv(p), Split="holdout", Arm="comma-z0-coarse", Budget=80000, Stratum="all")
        mine = self.headline(self.H, "comma-z0-coarse")
        for c in ("MeanDiff", "Lo", "Hi", "D"):
            self.assertTrue(math.isclose(shipped[c], mine[c], abs_tol=1e-4), c)
        self.assertTrue(np.isfinite(shipped.MeanDiff_single))


if __name__ == "__main__":
    unittest.main()
