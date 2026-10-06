"""Every measure on the synthetic tree (synthetic.py), against its known answer."""
import math
import pathlib
import shutil
import tempfile
import unittest

import numpy as np
import pandas as pd

from magat_analysis import load, measures, report, stats
from tests import synthetic as syn

LN2 = math.log(2)


class SyntheticTree(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-analysis-"))
        cls.spec = syn.write_spec(cls.tmp / "spec.json")
        syn.build(cls.tmp / "run", "dev", cls.spec)
        syn.build(cls.tmp / "run", "holdout", cls.spec)
        cls.sp = load.load_split(cls.tmp / "run" / "dev")
        cls.t = cls.sp.turns

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def row(self, df, **kw):
        for k, v in kw.items():
            df = df[df[k] == v]
        self.assertEqual(len(df), 1, f"expected one row for {kw}, got {len(df)}")
        return df.iloc[0]

    # ------------------------------------------------------------------ loading
    def test_the_terminal_flag_and_its_magnitude_fallback(self):
        t = self.t
        self.assertTrue(t[(t.arm == "good") & (t.scenario == 12) & (t.visit == 0)].term.all())
        noisy = t[t.arm == "noisy"]
        self.assertTrue(noisy["terminal"].isna().all(), "the noisy leaf has a blank flag, like an imported row")
        self.assertEqual(noisy.term.sum(), 1, "only its |final| >= 1e9 row is terminal")
        self.assertEqual(int(t.term.sum()), 2)

    def test_a_split_or_its_run_directory(self):
        self.assertEqual([p.name for p in load.find_splits(self.tmp / "run")], ["dev", "holdout"])
        self.assertEqual(load.find_splits(self.tmp / "run" / "holdout"), [self.tmp / "run" / "holdout"])

    def test_the_spec_contrasts_come_before_the_attribution_controls(self):
        pairs = load.comparisons(load.load_spec(self.spec))
        self.assertEqual([(p["kind"], p["a"], p["b"]) for p in pairs],
                         [("contrast", "great", "good"), ("attribution", "good", "noisy")])

    # ------------------------------------------------------------------ 1, 8
    def test_headline_in_both_strata(self):
        h = measures.headline(self.t, "base", "dev", n=500)
        nt = self.row(h, Budget=1000, Arm="good", Stratum="no-terminal")
        self.assertAlmostEqual(nt.MeanDiff, 50 * LN2, places=9)
        self.assertAlmostEqual(nt.Lo, 50 * LN2, places=9)
        self.assertEqual(nt.Pairs, syn.S * syn.V - 1)
        self.assertEqual(nt.Wins, syn.S * syn.V - 1)
        al = self.row(h, Budget=1000, Arm="good", Stratum="all")
        self.assertEqual(al.Pairs, syn.S * syn.V)
        self.assertGreater(al.MeanDiff, 1e7, "the all-rows stratum keeps the terminal pair")
        self.assertIn("MeanDiff_single", h.columns)
        self.assertIn("MeanDiff_dual", h.columns)
        weak = self.row(h, Budget=4000, Arm="weak", Stratum="all")
        self.assertAlmostEqual(weak.MeanDiff, 50 * math.log(2000 / 4000), places=9)

    def test_cohens_d_is_the_paired_mean_over_its_sample_deviation(self):
        h = measures.headline(self.t, "base", "dev", n=200)
        n = self.row(h, Budget=1000, Arm="noisy", Stratum="no-terminal")
        diffs = np.array([10.0 if (s * syn.V + v) % 2 == 0 else -2.0
                          for s in range(1, syn.S + 1) for v in range(syn.V) if (s, v) != (11, 1)])
        self.assertAlmostEqual(n.D, diffs.mean() / diffs.std(ddof=1), places=9)
        self.assertAlmostEqual(n.MeanDiff, diffs.mean(), places=9)
        self.assertTrue(math.isnan(self.row(h, Budget=1000, Arm="good", Stratum="no-terminal").D),
                        "a constant difference has no spread, so no d")

    # ------------------------------------------------------------------ 2
    def test_the_budget_multiplier_inside_past_and_below_the_ladder(self):
        b = measures.budget_multiplier(self.t, "base", "dev", n=300)
        for arm, budget, m, flag in (("good", 1000, 2.0, "inside"), ("great", 1000, 8.0, "extrapolated"),
                                     ("weak", 4000, 0.5, "inside"), ("poor", 1000, 0.5, "outside")):
            r = self.row(b, Arm=arm, Budget=budget)
            self.assertAlmostEqual(r.Multiplier, m, places=9, msg=arm)
            self.assertAlmostEqual(r.Lo, m, places=9, msg=f"{arm}: every resample sees the same exact curve")
            self.assertAlmostEqual(r.Hi, m, places=9, msg=arm)
            self.assertEqual(r.Flag, flag, arm)
        g = self.row(b, Arm="good", Budget=1000)
        self.assertEqual(g.Cells, syn.S * syn.V - 1, "the terminal cell leaves every series")
        self.assertAlmostEqual(g.TimeMultiplier, 200.0 / 250.0, places=9)
        self.assertAlmostEqual(g.ArmTimeRatio, 250.0 / 100.0, places=9)

    def test_no_multiplier_without_two_baseline_levels(self):
        one = self.t[~((self.t.arm == "base") & (self.t.budget == 4000))]
        self.assertTrue(measures.budget_multiplier(one, "base", n=50).empty)

    def test_a_falling_baseline_curve_is_not_inverted(self):
        t = self.t.copy()
        hi = (t.arm == "base") & (t.budget == 4000)
        t.loc[hi, "final"] = t.loc[hi, "initial"]
        t["gain"] = t["final"] - t["initial"]
        r = self.row(measures.budget_multiplier(t, "base", n=50), Arm="good", Budget=1000)
        self.assertEqual(r.Flag, "non-monotone")
        self.assertTrue(math.isnan(r.Multiplier))

    # ------------------------------------------------------------------ 3
    def test_w7_rescores_at_the_arms_own_wall_clock(self):
        h = measures.headline(self.t, "base", "dev", n=200)
        w = measures.w7(self.t, "base", h, "dev", n=200)
        g = self.row(w, Arm="good", Budget=1000)
        self.assertAlmostEqual(g.WallDiff, 0.0, places=9)
        self.assertEqual(g.ClampedShare, 0.0)
        self.assertEqual(g.Verdict, "units only (unresolved)")
        gr = self.row(w, Arm="great", Budget=1000)
        self.assertEqual(gr.ClampedShare, 1.0, "500 ms is past the baseline's 400")
        self.assertAlmostEqual(gr.WallDiff, 50 * LN2, places=9)
        self.assertEqual(gr.Verdict, "wins both")

    # ------------------------------------------------------------------ 4
    def test_dev_and_holdout_side_by_side(self):
        ho = load.load_split(self.tmp / "run" / "holdout")
        dev = {"headline": measures.headline(self.t, "base", "dev", n=200),
               "contrasts": measures.contrasts(self.t, load.comparisons(load.load_spec(self.spec)), "dev", n=200),
               "budget_multiplier": measures.budget_multiplier(self.t, "base", "dev", n=200)}
        hold = {"headline": measures.headline(ho.turns, "base", "holdout", n=200),
                "contrasts": measures.contrasts(ho.turns, load.comparisons(load.load_spec(self.spec)), "holdout", n=200),
                "budget_multiplier": measures.budget_multiplier(ho.turns, "base", "holdout", n=200)}
        r = measures.replication(dev, hold)
        self.assertEqual(self.row(r, Kind="headline", Name="good", Budget=1000, Stratum="no-terminal").Replicates, "yes")
        self.assertEqual(self.row(r, Kind="multiplier", Name="great", Budget=1000).Replicates, "yes")
        self.assertEqual(set(r.Kind), {"headline", "contrast", "multiplier"})

    def test_the_replication_rule(self):
        self.assertEqual(measures.replicates(10, 20, 15, 5, 25), "yes")
        self.assertEqual(measures.replicates(10, 20, 25, 20, 30), "no", "outside the development interval")
        self.assertEqual(measures.replicates(10, 20, 15, -5, 30), "no", "the holdout interval includes zero")
        self.assertEqual(measures.replicates(-5, 20, 2, -3, 8), "ns both")
        self.assertEqual(measures.replicates(1.5, 3, 2, 1.2, 2.5, null=1.0), "yes")

    # ------------------------------------------------------------------ 5
    def test_contrasts_and_attribution_from_the_spec(self):
        c = measures.contrasts(self.t, load.comparisons(load.load_spec(self.spec)), "dev", n=200)
        gg = self.row(c, A="great", B="good", Budget=1000, Stratum="no-terminal")
        self.assertAlmostEqual(gg.MeanDiff, 50 * math.log(4), places=9)
        self.assertEqual(gg.Kind, "contrast")
        self.assertEqual(self.row(c, A="good", B="noisy", Budget=1000, Stratum="all").Kind, "attribution")
        self.assertTrue(c[c.Budget == 4000].empty, "no contrast has both its arms at 4,000")

    # ------------------------------------------------------------------ 6
    def test_the_gain_multiple(self):
        g = self.row(measures.gain_multiple(self.t, "base", "dev", n=200), Arm="good", Budget=1000)
        cells = [(s, v) for s in range(1, syn.S + 1) for v in range(syn.V) if (s, v) != (12, 0)]
        base = np.mean([syn.gain(1000, s, v) for s, v in cells])
        self.assertAlmostEqual(g.GainMultiple, (base + 50 * LN2) / base, places=9)
        self.assertLessEqual(g.Lo, g.GainMultiple)
        self.assertGreaterEqual(g.Hi, g.GainMultiple)

    # ------------------------------------------------------------------ 7
    def test_oracle_best_of_field_and_regret(self):
        o = measures.oracle(self.t, "dev")
        o = o[o.Budget == 1000]
        self.assertEqual(int(o.Turns.iloc[0]), syn.S * syn.V - 2, "turns where any arm is terminal are left out")
        self.assertEqual(o.iloc[0].Arm, "great")
        self.assertEqual(self.row(o, Arm="great").BestShare, 1.0)
        self.assertAlmostEqual(self.row(o, Arm="base").MeanRegret, 50 * math.log(8), places=9)
        self.assertAlmostEqual(self.row(o, Arm="good").MeanRegret, 50 * math.log(4), places=9)
        self.assertTrue(measures.oracle(self.t[self.t.budget == 4000]).empty, "two arms are not a field")

    # ------------------------------------------------------------------ 9, 10, design
    def test_anytime_on_the_curve(self):
        a = measures.anytime(self.sp.curve, self.t, "base", "dev")
        for cp in range(1, 5):
            r = self.row(a, Arm="good", Checkpoint=cp)
            self.assertAlmostEqual(r.MeanDiff, 5 * cp, places=9)
            self.assertEqual(r.Pairs, syn.S * syn.V - 1, "the terminal turn's checkpoints are left out")

    def test_efficiency_duplicate_share_and_telemetry(self):
        e = measures.efficiency(self.t, "base")
        b = self.row(e, Arm="base", Budget=1000)
        self.assertEqual(b.DupBasis, "per mutation event")
        self.assertAlmostEqual(b.DupShare, np.mean([5 / (50 + s - 1) for s in range(1, syn.S + 1)]), places=9)
        self.assertIn("t_mutEvents", e.columns)
        p = self.row(e, Arm="poor", Budget=1000)
        self.assertEqual(p.DupBasis, "per node", "a leaf without mutation counts falls back to nodes")
        self.assertAlmostEqual(self.row(e, Arm="base", Budget=1000).NodesVsBase, 1.0)

    def test_variance_components_separate_scenarios_from_visits(self):
        between = pd.DataFrame(dict(scenario=np.repeat(np.arange(10), 4), diff=np.repeat(np.arange(10.0), 4)))
        self.assertAlmostEqual(stats.variance_components(between)["ICC"], 1.0)
        within = pd.DataFrame(dict(scenario=np.repeat(np.arange(10), 4), diff=np.tile([1.0, -1.0, 2.0, -2.0], 10)))
        self.assertAlmostEqual(stats.variance_components(within)["ICC"], 0.0)
        self.assertFalse(measures.variance(self.t, "base").empty)

    # ------------------------------------------------------------------ re-pricing
    def test_repricing_under_the_runs_own_weights_reproduces_its_units(self):
        w = load.read_weights(syn.write_weights(self.tmp / "same.properties"))
        table, spend, notes = measures.reprice(self.t, self.sp.leaves, w, "base", "dev", n=100)
        self.assertEqual(notes, [])
        for r in spend.itertuples(index=False):
            self.assertAlmostEqual(r.Ratio, 1.0, places=6, msg=f"{r.Arm}@{r.Budget}")
        w2 = load.read_weights(syn.write_weights(self.tmp / "double.properties", scale=2.0))
        _, spend2, _ = measures.reprice(self.t, self.sp.leaves, w2, "base", "dev", n=100)
        self.assertTrue(np.allclose(spend2.Ratio, 2.0, atol=1e-6))
        self.assertFalse(table.empty)

    def test_an_unpriced_leaf_and_a_leaf_without_counts_are_named(self):
        w = load.read_weights(syn.write_weights(self.tmp / "partial.properties"))
        del w["profiles"]["weak@4000"]
        t = self.t.copy()
        t.loc[t.arm == "poor", "copies"] = np.nan
        _, spend, notes = measures.reprice(t, self.sp.leaves, w, "base", "dev", n=50)
        self.assertTrue(any("weak@4000" in n and "not priced" in n for n in notes), notes)
        self.assertTrue(any("poor@1000" in n and "no copy" in n for n in notes), notes)
        self.assertNotIn("weak", set(spend.Arm))

    # ------------------------------------------------------------------ currency version 2
    def test_a_version_two_leaf_reprices_with_its_operations(self):
        tree = syn.build_v2(self.tmp / "v2")
        sp = load.load_split(tree)
        w = load.read_weights(syn.write_weights_v2(self.tmp / "v2.properties"))
        self.assertEqual(w["version"], 2)
        self.assertEqual(w["profiles"]["good@1000"]["x.step"], 0.6)
        table, spend, notes = measures.reprice(sp.turns, sp.leaves, w, "base", "dev", n=50)
        self.assertEqual(notes, [])
        for r in spend.itertuples(index=False):
            self.assertAlmostEqual(r.Ratio, 1.0, places=9, msg=f"{r.Arm}@{r.Budget}: its own currency reproduces its units")
        _, spend2, _ = measures.reprice(sp.turns, sp.leaves, load.read_weights(syn.write_weights_v2(self.tmp / "v2x2.properties", 2.0)),
                                        "base", "dev", n=50)
        self.assertTrue(np.allclose(spend2.Ratio, 2.0))

    def test_currencies_that_price_different_work_are_not_mixed(self):
        tree = syn.build_v2(self.tmp / "v2mix")
        sp = load.load_split(tree)
        # A version-2 leaf in a version-1 currency: the leaf counts operations the file does not price.
        _, spend, notes = measures.reprice(sp.turns, sp.leaves, load.read_weights(syn.write_weights(self.tmp / "v1.properties")),
                                           "base", "dev", n=50)
        self.assertTrue(spend.empty)
        self.assertTrue(any("x.step" in n and "does not price" in n for n in notes), notes)
        # A file that leaves one of the leaf's operations unpriced: likewise.
        _, spend, notes = measures.reprice(sp.turns, sp.leaves, load.read_weights(syn.write_weights_v2(self.tmp / "v2d.properties",
                                                                                                         drop="x.look")), "base", "dev", n=50)
        self.assertTrue(spend.empty)
        # A version-1 leaf in a version-2 currency: the file prices operations the leaf never counted.
        _, spend, notes = measures.reprice(self.t, self.sp.leaves, load.read_weights(syn.write_weights_v2(self.tmp / "v2b.properties")),
                                           "base", "dev", n=50)
        self.assertTrue(any("does not count" in n for n in notes), notes)

    def test_efficiency_carries_the_audit_and_the_operation_counts(self):
        sp = load.load_split(syn.build_v2(self.tmp / "v2eff"))
        e = measures.efficiency(sp.turns, "base", ref_ns=50_000)
        b = self.row(e, Arm="base", Budget=1000)
        g = sp.turns[(sp.turns.arm == "base") & (sp.turns.budget == 1000)]
        want = 1 - float((g["units"] + g["init_units"]).sum()) * 50_000 / 1e6 / float(g["plan_ms"].sum())
        self.assertAlmostEqual(b.UnpricedShare, want, places=9)
        self.assertIn("op_x.step", e.columns)
        self.assertTrue(math.isnan(self.row(measures.efficiency(sp.turns, "base"), Arm="base", Budget=1000).UnpricedShare),
                        "without the reference nanoseconds there is no audit")

    # ------------------------------------------------------------------ bootstrap
    def test_the_bootstrap_is_deterministic_per_row(self):
        d = pd.DataFrame(dict(scenario=np.repeat(np.arange(20), 3), diff=np.random.default_rng(1).normal(size=60)))
        a = stats.cluster_boot_ci(d, "diff", key=("x", 1))
        self.assertEqual(a, stats.cluster_boot_ci(d, "diff", key=("x", 1)))
        self.assertNotEqual(a, stats.cluster_boot_ci(d, "diff", key=("x", 2)))
        self.assertLess(a[1], a[0])
        self.assertLess(a[0], a[2])
        self.assertEqual(a[3], 20)

    # ------------------------------------------------------------------ the whole report
    def test_analyze_writes_every_table_and_refuses_identical_arms(self):
        out = self.tmp / "out"
        w = syn.write_weights(self.tmp / "same2.properties")
        rc = report.analyze(self.tmp / "run", out, reprice=w, n=200, figures=False, echo=lambda *_: None)
        self.assertEqual(rc, 0)
        for name in report.TABLES + ("repriced", "repriced_spend"):
            self.assertTrue((out / "tables" / f"{name}.csv").is_file(), name)
        for name in ("headline", "budget_multiplier", "w7_wallclock", "replication", "contrasts", "oracle",
                     "gain_multiple", "anytime", "efficiency", "variance"):
            df = pd.read_csv(out / "tables" / f"{name}.csv")
            self.assertFalse(df.empty, name)
            if name != "replication":
                self.assertEqual(set(df.Split), {"dev", "holdout"} if name != "anytime" else {"dev", "holdout"}, name)
            self.assertIn("currency", df.columns, name)
        summary = (out / "SUMMARY.md").read_text(encoding="utf-8")
        for heading in ("## Provenance", "## Replication", "## Headline (holdout)", "## Budget multiplier (dev)",
                        "## Attribution and contrasts", "## Oracle regret", "## Effect size", "## Anytime",
                        "## Diversity", "## Design", "## Re-priced", "## Scope notes"):
            self.assertIn(heading, summary)
        self.assertLess(summary.index("## Replication"), summary.index("## Headline"))
        self.assertTrue((out / "report.json").is_file())

        twin = self.tmp / "twin"
        shutil.copytree(self.tmp / "run" / "dev", twin)
        src = twin / "good" / "1000cu"
        dst = twin / "good-twin" / "1000cu"
        dst.mkdir(parents=True)
        (dst / "turns.csv").write_text((src / "turns.csv").read_text(encoding="utf-8").replace(",good,", ",good-twin,"),
                                       encoding="utf-8")
        self.assertEqual(load.identical_arms(load.load_split(twin).turns)[0]["a"], "good")
        rc = report.analyze(twin, self.tmp / "out-twin", baseline="base", n=50, figures=False, echo=lambda *_: None)
        self.assertEqual(rc, report.EXIT_IDENTICAL)

    def test_no_baseline_is_bad_input(self):
        bare = self.tmp / "bare"
        shutil.copytree(self.tmp / "run" / "dev", bare)
        (bare / "_run.json").write_text("{}", encoding="utf-8")
        self.assertEqual(report.analyze(bare, self.tmp / "o", n=10, figures=False, echo=lambda *_: None),
                         report.EXIT_INPUT)


if __name__ == "__main__":
    unittest.main()
