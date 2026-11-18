import io
import pathlib
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout

from magat_analysis import load, reproduce
from tests import synthetic as syn


class Reproduction(unittest.TestCase):
    """`magat check reproduction`: a run against another, turn by turn, every field but the times."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-reproduce-"))
        cls.published = syn.build(cls.tmp / "published", "all")
        # The same turns on a machine twice as slow, and a run in which one arm plays differently.
        syn.build(cls.tmp / "slower", "holdout", ms_scale=2.0)
        syn.build(cls.tmp / "changed", "holdout", shift={"good": 1.0})

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_it(self, run, against=None, out=None):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = reproduce.main(str(run), str(against or self.published), str(out) if out else None)
        return code, buf.getvalue()

    def test_the_same_turns_at_other_times_reproduce(self):
        code, text = self.run_it(self.tmp / "slower", out=self.tmp / "slower.csv")
        self.assertEqual(code, 0, text)
        self.assertIn("REPRODUCTION: PASS", text)
        t = reproduce.compare(load.load_split(self.tmp / "slower" / "holdout").turns, load.load_split(self.published).turns)
        self.assertTrue((t.Compared == t.Turns).all() and (t.Identical == t.Compared).all(), t)
        written = (self.tmp / "slower.csv").read_text(encoding="utf-8")
        self.assertTrue(written.startswith("Split,Arm,Budget,Turns,Compared,Identical"), written[:80])

    def test_a_turn_that_differs_fails_and_its_field_is_named(self):
        code, text = self.run_it(self.tmp / "changed")
        self.assertEqual(code, 1, text)
        self.assertIn("REPRODUCTION: FAIL", text)
        t = reproduce.compare(load.load_split(self.tmp / "changed" / "holdout").turns, load.load_split(self.published).turns)
        good = t[t.Arm == "good"].iloc[0]
        # Every turn moved but the one the builder pins to a terminal value whatever the shift (scenario 12, visit 0).
        self.assertEqual(good.Identical, 1)
        self.assertEqual(good.Compared, good.Turns)
        self.assertTrue(good.Differs.startswith("final ("), good.Differs)
        self.assertTrue((t[t.Arm != "good"].Identical == t[t.Arm != "good"].Compared).all(), "the other arms still reproduce")

    def test_an_arm_the_other_run_never_played_is_listed_not_compared(self):
        mine = load.load_split(self.tmp / "slower" / "holdout").turns
        other = load.load_split(self.published).turns
        t = reproduce.compare(mine, other[other.arm != "great"])
        great = t[t.Arm == "great"].iloc[0]
        self.assertEqual((great.Compared, great.Note), (0, "not in the other run"))
        self.assertEqual(reproduce.verdict(t)[0], 0, "what is compared is identical")
        self.assertEqual(reproduce.verdict(reproduce.compare(mine, other[other.arm == "nobody"]))[0], 2, "nothing to compare")

    def test_against_names_one_half(self):
        both = self.tmp / "both"
        syn.build(both, "dev")
        syn.build(both, "holdout")
        code, text = self.run_it(self.tmp / "slower", against=both)
        self.assertEqual(code, 2)
        self.assertIn("name one of them", text)


if __name__ == "__main__":
    unittest.main()
