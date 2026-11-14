#!/usr/bin/env python3
"""Self-test of the notebooks (notebooks/run_magat.ipynb and notebooks/maintainers/measure_levels.ipynb):
execute each one's setup cells from three launch directories (the repository root, the notebook's
folder, and the repository's parent, as JupyterHub launches it) and assert every launch resolves the
same repository and a data root outside it.

Runs in well under a second and is part of the pre-commit hook. Also checks that the notebooks are
the ones tools/make_notebook.py generates, and runs the run notebook's calibration cells, with the
launcher stubbed, on no currency, one that lacks an arm, one that prices them all, a config that names
its own arms and levels, and no calibration at all (a published currency); and its experiments cell on
two specs. A notebook run in Jupyter is current when its cells are the generated ones; that check is
tested too.
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
    print(f"{NOTEBOOKS[0].relative_to(ROOT).as_posix()}, the experiments cell:")
    failures += experiments_cell(NOTEBOOKS[0])
    print("make_notebook.py --check, on a notebook run in Jupyter:")
    failures += run_notebooks()
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


def tagged(NB: pathlib.Path, *tags) -> list:
    """The sources of the code cells whose tags are exactly one of `tags` (each a set)."""
    nb = json.loads(NB.read_text(encoding="utf-8"))
    return ["".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code" and set(c.get("metadata", {}).get("tags", [])) in tags]


LEVELS_DOC = {"levels": [{"time_ms": 500, "units": 9500}, {"time_ms": 1000, "units": 20000}, {"time_ms": 5000, "units": 101500}]}


def calibration_cells(NB: pathlib.Path) -> int:
    """The preflight and measuring cells measure a currency that is missing or lacks an arm the config names, and
    skip one that prices them all; a config's own arms and levels are what the coverage check asks for; with no
    calibration config nothing is measured or checked. The launcher is a stub that answers the coverage check
    (`magat weights`)."""
    cells = tagged(NB, {"calibrate"}, {"calibrate", "measure"})
    if len(cells) != 2:
        print(f"  [FAIL] expected the preflight and the measuring cell, found {len(cells)} cells")
        return 1
    failures = 0
    with tempfile.TemporaryDirectory(prefix="magat-selftest-") as tmp:
        weights = pathlib.Path(tmp) / "compute_weights.properties"
        own = {"name": "selftest", "arms": ["emcts", "oep"], "levels": ["500ms", 20000]}
        for case, cal, covered, should_measure in (
                ("no currency", {"name": "selftest"}, None, True),
                ("a currency that lacks an arm the config names", {"name": "selftest"}, 1, True),
                ("a currency that prices every arm the config names", {"name": "selftest"}, 0, False),
                ("a config that names its own arms and levels", own, 1, True),
                ("no calibration config: the published currency", None, 1, False)):
            if covered is None:
                weights.unlink(missing_ok=True)
            else:
                weights.write_text("# a stub currency\n", encoding="utf-8")
            calls, printed = [], []

            def magat(*args, check=True):
                calls.append(args)
                return covered if args[0] == "weights" else 0

            ns = {"magat": magat, "summary": lambda *a, **k: None, "print": lambda *a, **k: printed.append(" ".join(map(str, a))),
                  "CALIBRATING": cal is not None, "CAL": cal or {}, "CAL_CONFIG": pathlib.Path(tmp) / "config.json",
                  "CALIBRATION": weights.parent, "WEIGHTS": weights, "CURRENCY_NAME": "selftest", "GAME": "tribes",
                  "PORTFOLIO": weights.parent, "LEVELS": "9500,20000,101500", "LEVELS_DOC": LEVELS_DOC}
            try:
                for src in cells:
                    exec(compile(src, "<calibration-cell>", "exec"), ns)
            except Exception as e:
                print(f"  [FAIL] {case}: {e!r}")
                failures += 1
                continue
            measured = any(a[0] == "calibrate-controlled" and "--preflight" not in a for a in calls)
            noted = any("does not price every arm the config names" in p for p in printed)
            ok = measured == should_measure and noted == (covered == 1 and cal is not None)
            if cal is own:
                asked = [a for a in calls if a[0] == "weights"]
                ok = ok and bool(asked) and all("emcts,oep" in a and "9500,20000" in a for a in asked) \
                    and not any("--levels" in a for a in calls if a[0] == "calibrate-controlled")
            if cal is None:
                ok = ok and not calls
            print(f"  [{'ok' if ok else 'FAIL'}] {case}: {'measured' if measured else 'skipped'}"
                  + (", and the preflight cell says so first" if noted else "")
                  + (": the coverage check asks for its arms and levels, and the session is given none"
                     if cal is own and ok else "") + (", nothing called" if cal is None and ok else ""))
            failures += int(not ok)
    return failures


def experiments_cell(NB: pathlib.Path) -> int:
    """The experiments cell runs every spec of the parameters cell, in order, through `experiment run`, in the plan's
    currency and with this machine's thread sweep."""
    cells = tagged(NB, {"experiment"})
    if len(cells) != 1:
        print(f"  [FAIL] expected one experiments cell, found {len(cells)}")
        return 1
    calls = []
    specs = [pathlib.Path("a.json"), pathlib.Path("b.json")]
    ns = {"magat": lambda *args, check=True: calls.append(args) or 0, "EXPERIMENT_SPECS": specs,
          "PORTFOLIO": pathlib.Path("portfolio"), "WEIGHTS": pathlib.Path("w.properties"), "CALIBRATION": pathlib.Path("cal")}
    exec(compile(cells[0], "<experiments-cell>", "exec"), ns)
    ok = [a[:3] for a in calls] == [("experiment", "run", s) for s in specs] \
        and all(a[a.index("--weights") + 1] == ns["WEIGHTS"] and a[a.index("--threads-file") + 1] == pathlib.Path("cal") / "threads.json"
                for a in calls)
    print(f"  [{'ok' if ok else 'FAIL'}] two specs: experiment run on each, in order, in the plan's currency and thread sweep")
    exports = tagged(NB, {"export"})
    calls.clear()
    if len(exports) == 1:
        exec(compile(exports[0], "<export-cell>", "exec"), dict(ns, EXPERIMENT_NAMES=["a", "b"]))
    shared = len(exports) == 1 and calls == [("export", "a"), ("export", "b")]
    print(f"  [{'ok' if shared else 'FAIL'}] and the export cell packages each experiment by its name")
    return int(not ok) + int(not shared)


def run_notebooks() -> int:
    """The check takes a notebook that Jupyter ran and saved, outputs and its own metadata in the file, for current
    while the commit holds the generated one; it still finds a changed cell, a commit that carries outputs, and
    the generated notebook with other line endings stale."""
    sys.path.insert(0, str(ROOT / "tools"))
    import make_notebook as mk
    generated = json.dumps(mk.notebook(mk.CELLS), indent=1, ensure_ascii=False) + "\n"
    nb = json.loads(generated)
    for i, c in enumerate(nb["cells"]):                  # what Jupyter adds when it runs a notebook and saves it
        c["id"] = f"cell-{i}"
        if c["cell_type"] == "code":
            c["execution_count"] = i + 1
            c["outputs"] = [{"name": "stdout", "output_type": "stream", "text": ["done\n"]}]
            c["metadata"]["scrolled"] = True
    nb["metadata"]["language_info"] = {"name": "python", "version": "3.12.4"}
    ran = json.dumps(nb, indent=1, sort_keys=True, ensure_ascii=False) + "\n"
    first_code = next(i for i, c in enumerate(nb["cells"]) if c["cell_type"] == "code")
    nb["cells"][first_code]["source"].append("print('edited')\n")
    edited = json.dumps(nb, indent=1, sort_keys=True, ensure_ascii=False) + "\n"
    g = generated.encode("utf-8")
    failures = 0
    for case, saved, committed_as, want in (
            ("the generated notebook", g, g, True),
            ("the generated notebook, run and saved by Jupyter", ran.encode("utf-8"), g, True),
            ("the same, outside a git checkout", ran.encode("utf-8"), None, True),
            ("a run notebook with a cell changed", edited.encode("utf-8"), g, False),
            ("a run notebook that is also the commit's", ran.encode("utf-8"), ran.encode("utf-8"), False),
            ("the generated notebook with CRLF line endings", generated.replace("\n", "\r\n").encode("utf-8"), g, False)):
        got = bool(mk.current(saved, mk.CELLS, committed_as))
        print(f"  [{'ok' if got == want else 'FAIL'}] {case}: {'current' if got else 'stale'}")
        failures += int(got != want)
    return failures


if __name__ == "__main__":
    sys.exit(main())
