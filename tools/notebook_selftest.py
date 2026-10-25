#!/usr/bin/env python3
"""Self-test of the notebooks (notebooks/run_magat.ipynb and notebooks/maintainers/measure_levels.ipynb):
execute each one's setup cells from three launch directories (the repository root, the notebook's
folder, and the repository's parent, as JupyterHub launches it) and assert every launch resolves the
same repository and a data root outside it.

Runs in well under a second and is part of the pre-commit hook. Also checks that the notebooks are
the ones tools/make_notebook.py generates, and runs the run notebook's calibration cells, with the
launcher stubbed, on no currency, one that lacks a reference arm and one that prices them all.
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
NOTEBOOKS = [ROOT / "notebooks" / "run_magat.ipynb", ROOT / "notebooks" / "maintainers" / "measure_levels.ipynb"]


def setup_cells(nb: dict):
    for c in nb["cells"]:
        if c["cell_type"] == "code" and "setup" in c.get("metadata", {}).get("tags", []):
            yield "".join(c["source"])


def main() -> int:
    for nb in NOTEBOOKS:
        if not nb.is_file():
            print(f"missing {nb}: run `python tools/make_notebook.py`")
            return 1
    stale = subprocess.call([sys.executable, str(ROOT / "tools" / "make_notebook.py"), "--check"])
    if stale != 0:
        return 1
    failures = 0
    for nb in NOTEBOOKS:
        print(f"{nb.relative_to(ROOT).as_posix()}:")
        failures += selftest(nb)
    print(f"{NOTEBOOKS[0].relative_to(ROOT).as_posix()}, the calibration cells:")
    failures += calibration_cells(NOTEBOOKS[0])
    print(f"notebook selftest: {failures} failure(s)")
    return failures


def selftest(NB: pathlib.Path) -> int:
    nb = json.loads(NB.read_text(encoding="utf-8"))
    cells = list(setup_cells(nb))
    if not cells:
        print("  [FAIL] no cells tagged 'setup'")
        return 1
    data_root = tempfile.mkdtemp(prefix="magat-selftest-")
    launch_dirs = [ROOT, NB.parent, ROOT.parent]
    roots = set()
    failures = 0
    for d in launch_dirs:
        env = {"MAGAT_DATA": data_root}
        ns = {"__name__": "__selftest__"}
        cwd = os.getcwd()
        os.environ.update(env)
        try:
            os.chdir(d)
            for src in cells:
                exec(compile(src, "<setup-cell>", "exec"), ns)
            found = ns["ROOT"]
            roots.add(pathlib.Path(found).resolve())
            data = pathlib.Path(ns["DATA"]).resolve()
            inside = ROOT == data or ROOT in data.parents
            print(f"  [{'FAIL' if inside else 'ok'}] launched from {d}: root {found}, data {data}")
            failures += int(inside)
        except Exception as e:
            print(f"  [FAIL] launched from {d}: {e}")
            failures += 1
        finally:
            os.chdir(cwd)
    if len(roots) != 1 or next(iter(roots)) != ROOT:
        print(f"  [FAIL] launches resolved different repositories: {roots}")
        failures += 1
    return failures


def calibration_cells(NB: pathlib.Path) -> int:
    """The preflight and measuring cells measure a currency that is missing or lacks a reference arm, and skip
    one that prices them all. The launcher is a stub that answers the coverage check (`magat weights`)."""
    nb = json.loads(NB.read_text(encoding="utf-8"))
    cells = ["".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code"
             and set(c.get("metadata", {}).get("tags", [])) in ({"calibrate"}, {"calibrate", "measure"})]
    if len(cells) != 2:
        print(f"  [FAIL] expected the preflight and the measuring cell, found {len(cells)} cells")
        return 1
    failures = 0
    with tempfile.TemporaryDirectory(prefix="magat-selftest-") as tmp:
        weights = pathlib.Path(tmp) / "compute_weights.properties"
        for case, covered, should_measure in (("no currency", None, True),
                                              ("a currency that lacks a reference arm", 1, True),
                                              ("a currency that prices every reference arm", 0, False)):
            if covered is None:
                weights.unlink(missing_ok=True)
            else:
                weights.write_text("# a stub currency\n", encoding="utf-8")
            calls, printed = [], []

            def magat(*args, check=True):
                calls.append(args)
                return covered if args[0] == "weights" else 0

            ns = {"magat": magat, "summary": lambda *a, **k: None, "print": lambda *a, **k: printed.append(" ".join(map(str, a))),
                  "CALIBRATION": weights.parent, "CURRENCY_NAME": "selftest", "GAME": "tribes", "PORTFOLIO": weights.parent,
                  "LEVELS": "9500,20000,101500"}
            try:
                for src in cells:
                    exec(compile(src, "<calibration-cell>", "exec"), ns)
            except Exception as e:
                print(f"  [FAIL] {case}: {e}")
                failures += 1
                continue
            measured = any(a[0] == "calibrate-controlled" and "--preflight" not in a for a in calls)
            noted = any("does not price every reference arm" in p for p in printed)
            ok = measured == should_measure and noted == (covered == 1)
            print(f"  [{'ok' if ok else 'FAIL'}] {case}: {'measured' if measured else 'skipped'}"
                  + (", and the preflight cell says so first" if noted else ""))
            failures += int(not ok)
    return failures


if __name__ == "__main__":
    sys.exit(main())
