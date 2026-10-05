"""import-legacy on the 20-turn replay fixture: a real thesis leaf, as the R7615 wrote it."""
import csv
import json
import pathlib
import shutil
import tempfile
import unittest

from magat_analysis import legacy, load

FIXTURE = load.ROOT / "games" / "tribes" / "results" / "replay"


@unittest.skipUnless((FIXTURE / "Comma_Off" / "80000cu" / "turn_telemetry.csv").is_file(), "replay fixture absent")
class ImportLegacy(unittest.TestCase):

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-legacy-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def imported(self, **kw):
        rc = legacy.import_legacy(FIXTURE, self.tmp / "run", split="holdout", echo=lambda *_: None, **kw)
        self.assertEqual(rc, 0)
        return self.tmp / "run" / "holdout" / "emcts-comma" / "80000cu"

    def test_the_fixture_becomes_a_magat_leaf(self):
        leaf = self.imported()
        with open(leaf / "turns.csv", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), 20)
        self.assertEqual(list(rows[0].keys())[:len(legacy.COLUMNS)], legacy.COLUMNS)
        r = rows[0]
        self.assertEqual((r["scenario"], r["visit"], r["arm"], r["budget"]), ("1", "0", "emcts-comma", "80000"))
        self.assertEqual(r["phase"], "10", "the phase comes from the portfolio manifest (CapturedTick)")
        self.assertEqual((r["initial"], r["final"]), ("6231", "6482"))
        self.assertEqual(r["duplicates"], "70", "MutChildDuplicates")
        self.assertEqual(r["t_repairCandidateEvals"], "18992")
        for blank in ("terminal", "advances", "copies", "evals", "init_advances", "init_units", "seq_len"):
            self.assertEqual(r[blank], "", blank)
        self.assertNotEqual(r["units"], "")
        self.assertNotEqual(r["t_fmCalls"], "")
        meta = json.loads((leaf / "_leaf.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["imported"]["config"], "Comma_Off")
        self.assertIsNone(meta["weights"], "the thesis runner recorded no price per leaf")
        run = json.loads((self.tmp / "run" / "holdout" / "_run.json").read_text(encoding="utf-8"))
        self.assertEqual(run["portfolio"]["identity"][:8], "ec243fd7")
        self.assertNotIn(":\\", json.dumps(run), "no absolute machine paths in provenance")
        self.assertTrue((leaf / "curve.csv").is_file())

    def test_the_imported_leaf_loads_and_keeps_its_values(self):
        self.imported(curve=False)
        sp = load.load_split(self.tmp / "run" / "holdout")
        self.assertEqual(len(sp.turns), 20)
        self.assertFalse((self.tmp / "run" / "holdout" / "emcts-comma" / "80000cu" / "curve.csv").exists())
        self.assertTrue(sp.curve.empty)
        self.assertEqual(int(sp.turns.term.sum()), 0)
        with open(FIXTURE / "Comma_Off" / "80000cu" / "turn_telemetry.csv", encoding="utf-8") as fh:
            src = {(int(r["ScenarioId"]), int(r["VisitId"])): float(r["FinalValue"]) for r in csv.DictReader(fh)}
        for r in sp.turns.itertuples(index=False):
            self.assertEqual(r.final, src[(r.scenario, r.visit)])

    def test_an_unknown_configuration_or_a_foreign_scenario_is_refused(self):
        bad_map = self.tmp / "map.json"
        bad_map.write_text(json.dumps({"Baseline": "emcts"}), encoding="utf-8")
        self.assertEqual(legacy.import_legacy(FIXTURE, self.tmp / "a", split="holdout", mapping=bad_map,
                                              echo=lambda *_: None), 2)
        self.assertEqual(legacy.import_legacy(FIXTURE, self.tmp / "b", split="dev", echo=lambda *_: None), 2,
                         "holdout scenarios are not in the development split")

    def test_numbers_are_written_as_magat_writes_them(self):
        self.assertEqual(legacy.num("6231.0"), "6231")
        self.assertEqual(legacy.num("2000010474.0"), "2000010474")
        self.assertEqual(legacy.num("80055.52790"), "80055.5279")
        self.assertEqual(legacy.num("0.000049"), "0")
        self.assertEqual(legacy.num("1.23456"), "1.2346")
        self.assertEqual(legacy.num(""), "")


if __name__ == "__main__":
    unittest.main()
