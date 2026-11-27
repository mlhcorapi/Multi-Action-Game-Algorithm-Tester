"""Every measure on the synthetic tree (synthetic.py), against its known answer."""
import json
import math
import os
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
        # Resolved: on Windows the temp folder can be named in its 8.3 short form (RUNNER~1 on GitHub's runners),
        # and the code resolves the data root, so an unresolved path names the same file another way.
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-analysis-")).resolve()
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
        self.assertTrue(noisy["terminal"].isna().all(), "the noisy leaf has a blank flag")
        self.assertEqual(noisy.term.sum(), 1, "only its |final| >= 1e9 row is terminal")
        self.assertEqual(int(t.term.sum()), 2)

    def test_a_split_or_its_run_directory(self):
        self.assertEqual([p.name for p in load.find_splits(self.tmp / "run")], ["dev", "holdout"])
        self.assertEqual(load.find_splits(self.tmp / "run" / "holdout"), [self.tmp / "run" / "holdout"])

    def test_the_provenance_names_every_commit_that_played_turns(self):
        def written_by(leaves):
            sp = load.Split(self.sp.path, "dev", dict(magat=dict(commit="aaaa", notebook="modified")), leaves, self.t)
            return load.provenance(sp, None)["magat_commit"]
        self.assertEqual(written_by([dict(commit="aaaa"), dict(commit="aaaa")]), "aaaa (notebook modified)")
        self.assertEqual(written_by([dict(arm="older leaf")]), "aaaa (notebook modified)", "leaves from before commits were recorded")
        self.assertEqual(written_by([dict(commit="aaaa"), dict(commit="bbbb", earlierCommits=["aaaa"])]),
                         "aaaa (notebook modified); its turns were played by aaaa, bbbb")

    def test_a_published_tree_finds_its_spec_in_this_repository_by_hash(self):
        spec = load.ROOT / "experiments" / "tribes" / "reference.json"
        def found(sha):
            run = dict(game=dict(id="tribes"), experiment=dict(name="reference", path="/home/elsewhere/reference.json", sha256=sha))
            return load.spec_of(load.Split(self.sp.path, "all", run, [], self.t))
        self.assertEqual(found(load.sha256(spec))["name"], "reference", "the recorded path is gone; the same bytes are here")
        self.assertIsNone(found("0" * 64), "a spec of that name with other bytes is not the one the run ran")

    def test_the_contrasts_come_from_the_spec(self):
        pairs = load.comparisons(load.load_spec(self.spec))
        self.assertEqual([(p["a"], p["b"], p["label"]) for p in pairs], [("great", "good", "great over good")])

    def test_a_reused_leaf_keeps_its_turns_and_leaves_its_times_out(self):
        tree = self.tmp / "reused"
        syn.build(tree, "dev", self.spec)
        meta = tree / "dev" / "good" / "1000cu" / "_leaf.json"
        m = json.loads(meta.read_text(encoding="utf-8"))
        m["reused"] = dict(**{"from": "/elsewhere/r7615/all/good/1000cu"}, machine="AMD EPYC 9534 64-Core Processor | 128 logical")
        meta.write_text(json.dumps(m), encoding="utf-8")
        sp = load.load_split(tree / "dev")
        good, base = sp.turns[sp.turns.arm == "good"], sp.turns[sp.turns.arm == "base"]
        self.assertTrue(good.plan_ms.isna().all() and good.init_ms.isna().all(), "another machine's times are left out")
        self.assertTrue(good["final"].notna().all() and base.plan_ms.notna().all(), "the turns, and the other leaves' times, stay")
        self.assertTrue(any("1 of 7 leaves were taken from another run" in n and "good@1000" in n for n in sp.notes), sp.notes)
        self.assertEqual(load.provenance(sp, None)["reused"], "1 of 7 leaves, from " + load.shown(pathlib.Path("/elsewhere/r7615")))
        h = measures.headline(sp.turns, "base", "dev", n=200)
        w = measures.wallclock(sp.turns, "base", h, "dev", n=200)
        self.assertNotIn("good", set(w.Arm) if len(w) else set(), "no wall-clock re-score from another machine's times")
        self.assertEqual(load.provenance(self.sp, None)["reused"], "", "a run that took nothing says nothing")

    def test_a_spec_without_contrasts_compares_every_arm_with_every_arm_before_it(self):
        # The reference experiment writes out its ten contrasts; without them, the default is the same ten pairs.
        ref = load.load_spec(load.ROOT / "experiments" / "tribes" / "reference.json")
        bare = self.tmp / "bare.json"
        x = json.loads((load.ROOT / "experiments" / "tribes" / "reference.json").read_text(encoding="utf-8"))
        del x["contrasts"]
        bare.write_text(json.dumps(x), encoding="utf-8")
        pairs = [(c["a"], c["b"]) for c in load.load_spec(bare)["contrasts"]]
        self.assertEqual(pairs, [(c["a"], c["b"]) for c in ref["contrasts"]])
        self.assertEqual(load.load_spec(bare)["contrasts"][-1]["label"], "tbets against oep")
        x["contrasts"] = []
        bare.write_text(json.dumps(x), encoding="utf-8")
        self.assertEqual(load.load_spec(bare)["contrasts"], [], "an empty list asks for none")

    def test_an_imported_currency_and_spec_are_found_by_hash(self):
        data = self.tmp / "data"
        pkg = data / "imports" / "someone-elses"
        (pkg / "calibration" / "theirs").mkdir(parents=True)
        (pkg / "experiments").mkdir(parents=True)
        weights = pkg / "calibration" / "theirs" / "compute_weights.properties"
        weights.write_text("magat.weights.version = 2\nreference = base@1000\n", encoding="utf-8")
        spec = pkg / "experiments" / "theirs.json"
        spec.write_text(json.dumps(dict(name="theirs", game="tribes", arms=["base", "good"], baseline="base")), encoding="utf-8")
        run = dict(game=dict(id="tribes"), weights=dict(path="/their/machine/compute_weights.properties", sha256=load.sha256(weights)),
                   experiment=dict(name="theirs", path="/their/machine/theirs.json", sha256=load.sha256(spec)))
        split = load.Split(self.sp.path, "dev", run, [], self.t)
        before = os.environ.get("MAGAT_DATA")
        os.environ["MAGAT_DATA"] = str(data)
        try:
            self.assertEqual(report.currency_file(split), weights, "the recorded path is gone; the import holds the same bytes")
            self.assertEqual(load.spec_of(split)["name"], "theirs")
        finally:
            if before is None:
                os.environ.pop("MAGAT_DATA", None)
            else:
                os.environ["MAGAT_DATA"] = before

    def test_a_spec_in_a_folder_of_its_game_is_found_by_hash_too(self):
        spec = load.ROOT / "experiments" / "tribes" / "examples" / "oep-vs-tbets.json"
        run = dict(game=dict(id="tribes"), experiment=dict(name="oep-vs-tbets", path="/gone/oep-vs-tbets.json", sha256=load.sha256(spec)))
        found = load.spec_of(load.Split(self.sp.path, "dev", run, [], self.t))
        self.assertEqual([a["id"] for a in found["arms"]], ["emcts", "oep", "tbets"], "the short form's arms, as objects")
        self.assertEqual(len(found["contrasts"]), 3)

    # ------------------------------------------------------------------ the headline and d
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
        self.assertEqual((al.TermArm, al.TermBase), (1, 0), "the pair that ended the game is counted, in both strata")
        self.assertEqual((nt.TermArm, nt.TermBase), (1, 0))
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

    # ------------------------------------------------------------------ the budget multiplier
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

    def test_the_budget_multiplier_compares_values_not_search_gains(self):
        # An arm that starts from a plan of its own, 300 below the baseline's, and ends where the baseline ends at
        # 2,000 units: worth twice its budget, though its search gain is 300 above the baseline's there.
        t = self.t.copy()
        good = t.arm == "good"
        t.loc[good, "initial"] = t.loc[good, "initial"] - 300
        t["gain"] = t["final"] - t["initial"]
        r = self.row(measures.budget_multiplier(t, "base", n=50), Arm="good", Budget=1000)
        self.assertAlmostEqual(r.Multiplier, 2.0, places=9)
        self.assertEqual(r.Flag, "inside")

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

    # ------------------------------------------------------------------ wall-clock
    def test_the_wall_clock_table_rescores_at_the_arms_own_time(self):
        h = measures.headline(self.t, "base", "dev", n=200)
        w = measures.wallclock(self.t, "base", h, "dev", n=200)
        g = self.row(w, Arm="good", Budget=1000)
        self.assertAlmostEqual(g.WallDiff, 0.0, places=9)
        self.assertEqual(g.ClampedShare, 0.0)
        self.assertEqual(g.Verdict, "units only (unresolved)")
        gr = self.row(w, Arm="great", Budget=1000)
        self.assertEqual(gr.ClampedShare, 1.0, "500 ms is past the baseline's 400")
        self.assertAlmostEqual(gr.WallDiff, 50 * LN2, places=9)
        self.assertEqual(gr.Verdict, "wins both")
        # noisy's one terminal pair (-2e9) puts zero inside its interval over all pairs; both sides of the verdict
        # leave that pair out.
        no = self.row(w, Arm="noisy", Budget=1000)
        self.assertLess(self.row(h, Budget=1000, Arm="noisy", Stratum="all").Lo, 0)
        self.assertAlmostEqual(no.UnitsDiff, self.row(h, Budget=1000, Arm="noisy", Stratum="no-terminal").MeanDiff, places=9)
        self.assertEqual(no.Verdict, "wins both")

    # ------------------------------------------------------------------ spend, time and plans
    def test_spend_seconds_plan_length_and_the_runs_audit(self):
        leaves = [dict(arm="good", budget=1000, profile="good@1000", threads=8, audit=dict(unpricedShare=0.2)),
                  dict(arm="noisy", budget=1000, profile="global (arm is not priced)", threads=8, audit=dict(unpricedShare=0.9))]
        sp = measures.spend(self.t, leaves, {1000: 500.0})
        g = self.row(sp, Arm="good", Budget=1000)
        w = syn.WEIGHTS["good@1000"]
        units = [(1000 + s) * w[0] + (400 + v) * w[1] + 300 * w[2] + 10.75 for s in range(1, syn.S + 1) for v in range(syn.V)]
        self.assertAlmostEqual(g.Spend, np.mean(units) / 1000, places=9, msg="its initialization included")
        self.assertAlmostEqual(g.SpendMax, max(units) / 1000, places=9)
        self.assertEqual(g.OverShare, 1.0)
        self.assertEqual((g.MeanMillis, g.LevelMillis, g.PlanLength), (250.0, 500.0, 20.0))
        self.assertAlmostEqual(g.TerminalShare, 1 / (syn.S * syn.V), places=9)
        self.assertEqual((g.AuditUnpriced, g.Threads), (0.2, 8.0))
        self.assertTrue(math.isnan(self.row(sp, Arm="noisy", Budget=1000).AuditUnpriced), "an arm that is not priced has no audit")
        self.assertTrue(math.isnan(self.row(sp, Arm="weak", Budget=4000).LevelMillis), "4,000 is not a published level here")
        self.assertEqual(load.levels("tribes")["ms"][9500], 500.0, "the published Tribes levels")
        self.assertEqual(load.levels("toy")["ms"], {})

    # ------------------------------------------------------------------ contrasts and the design
    def test_contrasts_from_the_spec(self):
        c = measures.contrasts(self.t, load.comparisons(load.load_spec(self.spec)), "dev", n=200)
        gg = self.row(c, A="great", B="good", Budget=1000, Stratum="no-terminal")
        self.assertAlmostEqual(gg.MeanDiff, 50 * math.log(4), places=9)
        self.assertEqual(gg.Label, "great over good")
        self.assertTrue(c[c.Budget == 4000].empty, "no contrast has both its arms at 4,000")

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

    def test_an_arm_that_is_not_priced_reprices_on_the_forward_models_global_weights(self):
        # A real currency's global section carries the reference arm's operations too; the runner played the arm on
        # the forward model's global weights alone, so its re-pricing must not ask for those operations.
        w = load.read_weights(syn.write_weights(self.tmp / "globals.properties"))
        w["globals"]["x.step"] = 0.5
        del w["profiles"]["poor@1000"]
        leaves = [m for m in self.sp.leaves if m.get("arm") != "poor"] + [dict(arm="poor", budget=1000, profile="global (arm is not priced)")]
        _, spend, notes = measures.reprice(self.t, leaves, w, "base", "dev", n=50)
        self.assertFalse(any("poor" in n for n in notes), notes)
        self.assertAlmostEqual(self.row(spend, Arm="poor", Budget=1000).Ratio, 1.0, places=6)

    def test_an_unpriced_leaf_and_a_leaf_without_counts_are_named(self):
        w = load.read_weights(syn.write_weights(self.tmp / "partial.properties"))
        del w["profiles"]["weak@4000"]
        t = self.t.copy()
        t.loc[t.arm == "poor", "copies"] = np.nan
        _, spend, notes = measures.reprice(t, self.sp.leaves, w, "base", "dev", n=50)
        self.assertTrue(any("weak@4000" in n and "not priced" in n for n in notes), notes)
        self.assertTrue(any("poor@1000" in n and "no copy" in n for n in notes), notes)
        self.assertNotIn("weak", set(spend.Arm))

    def test_a_leaf_reprices_with_its_operations(self):
        tree = syn.build_v2(self.tmp / "v2")
        sp = load.load_split(tree)
        w = load.read_weights(syn.write_weights_v2(self.tmp / "v2.properties"))
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
        # A leaf whose operations the file does not price.
        _, spend, notes = measures.reprice(sp.turns, sp.leaves, load.read_weights(syn.write_weights(self.tmp / "fm.properties")),
                                           "base", "dev", n=50)
        self.assertTrue(spend.empty)
        self.assertTrue(any("x.step" in n and "does not price" in n for n in notes), notes)
        # A file that leaves one of the leaf's operations unpriced: likewise.
        _, spend, notes = measures.reprice(sp.turns, sp.leaves, load.read_weights(syn.write_weights_v2(self.tmp / "v2d.properties",
                                                                                                         drop="x.look")), "base", "dev", n=50)
        self.assertTrue(spend.empty)
        # A file that prices operations the leaf never counted.
        _, spend, notes = measures.reprice(self.t, self.sp.leaves, load.read_weights(syn.write_weights_v2(self.tmp / "v2b.properties")),
                                           "base", "dev", n=50)
        self.assertTrue(any("does not count" in n for n in notes), notes)

    def test_a_file_of_another_version_is_refused(self):
        p = self.tmp / "v1.properties"
        p.write_text("magat.weights.version = 1\nreference = base@1000\nprofile.base@1000.wAdvance = 1\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            load.read_weights(p)

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
        for name in ("headline", "budget_multiplier", "wallclock", "contrasts", "variance"):
            df = pd.read_csv(out / "tables" / f"{name}.csv")
            self.assertFalse(df.empty, name)
            self.assertEqual(set(df.Split), {"dev", "holdout"}, name)
            self.assertIn("currency", df.columns, name)
        summary = (out / "SUMMARY.md").read_text(encoding="utf-8")
        for heading in ("## Provenance", "## Headline (holdout)", "## Budget multiplier (dev)", "## Contrasts",
                        "## Spend, time and plans (dev)", "## Design", "## Re-priced", "## Scope notes"):
            self.assertIn(heading, summary)
        self.assertIn("| 1000 | good | 36 | **1.27x** | 100% | 0.25 | - | 20.0 | 2.8% | - |", summary)
        self.assertIn("more than 1.10x their level: good@1000 (1.27x)", summary)
        self.assertLess(summary.index("## Headline (dev)"), summary.index("## Headline (holdout)"))
        good = next(line for line in summary.splitlines() if line.startswith("| good | 35 |"))
        self.assertIn("| 1 / 0 |", good, "the headline leads with the 35 pairs where neither plan ends the game, and counts the 36th")
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



class AcrossMachines(unittest.TestCase):
    """The synthetic experiment on a second machine, B: `good` is 7 better on every cell there, every turn takes
    twice the seconds, and B's currency prices every operation at twice A's prices."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-across-")).resolve()
        spec = syn.write_spec(cls.tmp / "spec.json")
        syn.build(cls.tmp / "a", "dev", spec)
        syn.build(cls.tmp / "b", "dev", spec, shift={"good": 7.0}, ms_scale=2.0)
        cls.wb = syn.write_weights(cls.tmp / "b.properties", scale=2.0)
        cls.a = load.load_split(cls.tmp / "a" / "dev")
        cls.b = load.load_split(cls.tmp / "b" / "dev")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def row(self, df, **kw):
        for k, v in kw.items():
            df = df[df[k] == v]
        self.assertEqual(len(df), 1, f"expected one row for {kw}, got {len(df)}")
        return df.iloc[0]

    def test_each_machines_mean_their_difference_both_multipliers_the_seconds_and_a_in_bs_currency(self):
        x, notes = measures.across(self.a.turns, self.b.turns, "base", "dev", n=200, leaves_a=self.a.leaves,
                                   weights_b=load.read_weights(self.wb))
        self.assertEqual(notes, [])
        good = self.row(x, Arm="good", Budget=1000)
        self.assertEqual(good.Pairs, syn.S * syn.V - 1, "the terminal cell is left out")
        self.assertAlmostEqual(good.MeanB - good.MeanA, 7.0, places=9)
        for v in (good.Diff, good.DiffLo, good.DiffHi):
            self.assertAlmostEqual(v, -7.0, places=9, msg="the paired difference A - B, the same on every cell")
        self.assertAlmostEqual(good.MultA, 2.0, places=9)
        self.assertAlmostEqual(good.MultB, 2.0 * math.exp(7.0 / 50.0), places=9,
                               msg="7 more on B's log-linear baseline curve, 50 per e-fold of budget")
        self.assertAlmostEqual(good.SecondsA, 0.25, places=9)
        self.assertAlmostEqual(good.SecondsB, 0.5, places=9)
        self.assertAlmostEqual(good.RepricedRatio, 2.0, places=9, msg="A's work at B's doubled prices")
        base = self.row(x, Arm="base", Budget=4000)
        self.assertAlmostEqual(base.Diff, 0.0, places=9)
        self.assertTrue(math.isnan(base.MultA), "the baseline has no multiplier against itself")
        self.assertEqual(set(x.Arm), {"base", "good", "great", "weak", "poor", "noisy"})

    def test_turn_by_turn_and_scenario_by_scenario_agreement(self):
        g = measures.agreement(self.a.turns, self.b.turns, "base", "dev")
        base = self.row(g, Arm="base", Budget=1000)
        self.assertEqual((base.Identical, base.TerminalAgree, base.MeanGap), (1.0, 1.0, 0.0), "the same turns on both machines")
        good = self.row(g, Arm="good", Budget=1000)
        self.assertAlmostEqual(good.Identical, 1 / (syn.S * syn.V), places=9, msg="only its terminal turn is the same on both")
        self.assertAlmostEqual(good.MeanGap, 7.0, places=9)
        self.assertAlmostEqual(good.ScenMeanGap, 7.0, places=9, msg="7 more on every scenario")
        self.assertEqual((good.Scenarios, good.ScenSameSign), (syn.S, 1.0), "scenario 12 keeps its two other visits")
        self.assertEqual(good.SameBest, 1.0, "great is the best arm at 1,000 on both machines")
        self.assertTrue(math.isnan(base.ScenCorr), "the baseline has no advantage over itself")

    def test_the_report_finds_bs_currency_or_says_it_did_not(self):
        out = self.tmp / "out"
        rc = report.analyze(self.tmp / "a", out, baseline="base", compare=self.tmp / "b", compare_weights=self.wb,
                            n=100, figures=False, echo=lambda *_: None)
        self.assertEqual(rc, report.EXIT_OK)
        x = pd.read_csv(out / "tables" / "across.csv")
        self.assertEqual(list(x.columns), report.EMPTY["across"].split())
        self.assertAlmostEqual(float(x[(x.Arm == "good")].RepricedRatio.iloc[0]), 2.0, places=4)
        summary = (out / "SUMMARY.md").read_text(encoding="utf-8")
        self.assertIn("## Across machines (dev)", summary)
        self.assertIn("A at B's prices", summary)
        self.assertIn("## The two machines, turn by turn and scenario by scenario (dev)", summary)
        self.assertEqual(list(pd.read_csv(out / "tables" / "agreement.csv").columns), report.EMPTY["agreement"].split())

        # B's recorded currency is a path that does not exist here, and no published currency has its hash.
        rc = report.analyze(self.tmp / "a", self.tmp / "out2", baseline="base", compare=self.tmp / "b", n=50,
                            figures=False, echo=lambda *_: None)
        self.assertEqual(rc, report.EXIT_OK)
        self.assertIn("are not re-priced", (self.tmp / "out2" / "SUMMARY.md").read_text(encoding="utf-8"))
        self.assertTrue(pd.read_csv(self.tmp / "out2" / "tables" / "across.csv").RepricedRatio.isna().all())

    def test_runs_on_different_portfolios_do_not_pair(self):
        syn.build(self.tmp / "c", "dev", None, identity="another portfolio")
        rc = report.analyze(self.tmp / "a", self.tmp / "out3", baseline="base", compare=self.tmp / "c", n=10,
                            figures=False, echo=lambda *_: None)
        self.assertEqual(rc, report.EXIT_INPUT)


if __name__ == "__main__":
    unittest.main()
