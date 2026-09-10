#!/usr/bin/env python3
"""Self-test of notebooks/run_magat.ipynb: execute its setup cells from three launch directories
(the repository root, the notebook's folder, and the repository's parent, as JupyterHub launches
it) and assert every launch resolves the same repository and a data root outside it.

Runs in well under a second and is part of the pre-commit hook. Also checks that the notebook is
the one tools/make_notebook.py generates.
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
NB = ROOT / "notebooks" / "run_magat.ipynb"


def setup_cells(nb: dict):
    for c in nb["cells"]:
        if c["cell_type"] == "code" and "setup" in c.get("metadata", {}).get("tags", []):
            yield "".join(c["source"])


def main() -> int:
    if not NB.is_file():
        print(f"missing {NB}: run `python tools/make_notebook.py`")
        return 1
    stale = subprocess.call([sys.executable, str(ROOT / "tools" / "make_notebook.py"), "--check"])
    if stale != 0:
        return 1
    nb = json.loads(NB.read_text(encoding="utf-8"))
    cells = list(setup_cells(nb))
    if not cells:
        print("no cells tagged 'setup'")
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
    print(f"notebook selftest: {failures} failure(s)")
    return failures


if __name__ == "__main__":
    sys.exit(main())
