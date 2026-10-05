#!/usr/bin/env python3
"""Generate notebooks/run_magat.ipynb.

The notebook is generated, never hand-edited, so it is diffable and cannot drift from the CLI. One
cell per stage; setup cells (tagged "setup") skip themselves once their outputs exist; every stage
ends with a bundle cell. `tools/notebook_selftest.py` executes the setup cells from three launch
directories and must pass before a commit.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "notebooks" / "run_magat.ipynb"


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text: str, tags=()) -> dict:
    return {"cell_type": "code", "execution_count": None, "outputs": [],
            "metadata": {"tags": list(tags)}, "source": text.strip("\n").splitlines(keepends=True)}


SETUP = r'''
import os, sys, subprocess, pathlib, datetime, zipfile, glob

MAGAT_ROOT = ""     # leave empty: auto-detected. Set an absolute path only if the banner says so.

def _find_root():
    if MAGAT_ROOT:
        return pathlib.Path(MAGAT_ROOT).resolve()
    here = pathlib.Path.cwd().resolve()
    for base in [here, *here.parents]:
        if (base / "magat.py").is_file() and (base / "core" / "src").is_dir():
            return base
        for child in base.glob("*"):
            if child.is_dir() and (child / "magat.py").is_file() and (child / "core" / "src").is_dir():
                return child
    raise RuntimeError(
        f"Cannot find the MAGAT repository at or above {here}. A Jupyter kernel's working directory "
        "is the notebook's own folder (and it keeps the OLD folder if the file was moved without a "
        "kernel restart). Restart the kernel, or set MAGAT_ROOT at the top of this cell.")

ROOT = _find_root()
os.chdir(ROOT)

# Everything a run produces lives OUTSIDE the clone, so a merge and a re-clone never touch it.
DATA = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
os.environ["MAGAT_DATA"] = str(DATA)
LOGS = DATA / "logs"
BUNDLES = DATA / "bundles"
for _d in (LOGS, BUNDLES):
    _d.mkdir(parents=True, exist_ok=True)
assert ROOT not in DATA.parents and DATA != ROOT, f"data root {DATA} must be outside the clone {ROOT}"

GAME = "tribes"
PY = sys.executable
PUBLISHED_PORTFOLIO = ROOT / "games" / GAME / "portfolio" / "v1"
PUBLISHED_WEIGHTS = ROOT / "games" / GAME / "calibration" / "r7615" / "compute_weights.properties"
# Workers for parallel stages. Half the logical CPUs (SMT siblings add heat, not throughput) less two
# for the machine; MAGAT_WORKERS overrides. The machine profile (milestone 3) replaces this guess.
WORKERS = int(os.environ.get("MAGAT_WORKERS") or max(1, (os.cpu_count() or 2) // 2 - 2))

def magat(*args, check=True):
    """Run one launcher command; the launcher tees its own output to <DATA>/logs/<name>.log."""
    cmd = [PY, str(ROOT / "magat.py"), *map(str, args)]
    print("$ " + " ".join(cmd[2:]), flush=True)
    rc = subprocess.call(cmd)
    if check and rc != 0:
        raise RuntimeError(f"exit {rc}: magat {' '.join(map(str, args))}")
    return rc

def latest_log(name):
    txt = (LOGS / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    return txt.rsplit("\n>>>>> ", 1)[-1]

def assert_in_log(name, needle):
    assert needle in latest_log(name), f"'{needle}' not found in the latest {name} run - read {LOGS / (name + '.log')}"
    print(f"[gate] '{needle}' confirmed in {name}")

def bundle(name, items, exclude=("seqlog_", "mutlog_", "_running.lock")):
    """Zip the pre-specified files of a stage into <DATA>/bundles/<name>.zip."""
    out = BUNDLES / f"{name}.zip"
    if out.exists():
        out.unlink()
    files = []
    for it in items:
        p = pathlib.Path(it)
        if not p.is_absolute():
            p = DATA / p
        if p.is_dir():
            files += [q for q in p.rglob("*") if q.is_file()]
        elif p.exists():
            files.append(p)
        else:
            files += [pathlib.Path(h) for h in glob.glob(str(p)) if pathlib.Path(h).is_file()]
    files = sorted({f for f in files if not any(x in f.name for x in exclude)})
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(f, str(f.relative_to(DATA)) if DATA in f.parents else f.name)
    print(f"{len(files)} files -> {out}")
    return out

print("repository :", ROOT)
print("data root  :", DATA, "(outside the clone)")
print("python     :", PY)
print("workers    :", WORKERS)
'''


PORTFOLIO_CELL = r'''
PORTFOLIO = DATA / "portfolio" / GAME
if (PORTFOLIO / "_split_dev.txt").exists() and (PORTFOLIO / "_split_holdout.txt").exists():
    print(f"[skip] {PORTFOLIO} is built and frozen")
else:
    magat("portfolio", "build", "--game", GAME, "--out", PORTFOLIO, "--threads", WORKERS)
    magat("portfolio", "freeze", "--portfolio", PORTFOLIO)
print((PORTFOLIO / "split_report.txt").read_text(encoding="utf-8"))
magat("portfolio", "verify", "--game", GAME, "--portfolio", PORTFOLIO, "--published", PUBLISHED_PORTFOLIO, "--plan", 4)
assert_in_log("portfolio-verify", "VERIFY: PASS")
bundle("portfolio", [PORTFOLIO / f for f in ("portfolio.csv", "_split_dev.txt", "_split_holdout.txt",
                                             "identity.txt", "split_report.txt")]
       + [LOGS / "portfolio-build.log", LOGS / "portfolio-freeze.log", LOGS / "portfolio-verify.log"])
'''


MACHINE_CELL = r'''
magat("machine")
'''

THREADS_CELL = r'''
CALIBRATION = DATA / "calibration" / GAME
if (CALIBRATION / "threads.json").exists():
    print(f"[skip] {CALIBRATION / 'threads.json'} exists")
else:
    magat("threads", "--game", GAME, "--portfolio", PORTFOLIO, "--out", CALIBRATION)
print((CALIBRATION / "threads.json").read_text(encoding="utf-8"))
bundle("threads", [CALIBRATION / "threads.json", CALIBRATION / "thread_sweep.csv", LOGS / "threads.log"])
'''

CALIBRATE_CELL = r'''
# This machine's own currency, on this machine's own portfolio. Idle and single-threaded: stop
# everything else first. Extending it later with a new arm is `--arms <new>`, which merges.
if (CALIBRATION / "compute_weights.properties").exists():
    print(f"[skip] {CALIBRATION / 'compute_weights.properties'} exists")
else:
    magat("calibrate", "--game", GAME, "--portfolio", PORTFOLIO, "--out", CALIBRATION)
    assert_in_log("calibrate", "CALIBRATION: PASS")
magat("weights", "--weights", CALIBRATION / "compute_weights.properties")
bundle("calibration", [CALIBRATION / "compute_weights.properties", CALIBRATION / "compute_profiles.csv", LOGS / "calibrate.log"])
'''

DRIFT_CELL = r'''
# Does this machine still spend the published currency? Re-measures the reference arms against the
# R7615 file and writes nothing. Run it before quoting anything beside published results.
magat("calibrate", "--check", "--game", GAME, "--portfolio", PORTFOLIO, "--arms", "emcts,emcts-comma",
      "--weights", PUBLISHED_WEIGHTS, check=False)
bundle("drift", [LOGS / "calibrate-check.log"])
'''

UNDER_LOAD_CELL = r'''
# Gate A3' (off by default). The thesis resolved it: the idle currency is the ruler and wall-clock
# (W7) is co-primary, so this is a diagnostic, not a step. It writes compute_weights_load<W>.properties
# beside the idle file, never over it, and prints the idle-versus-loaded ratios.
RUN_UNDER_LOAD = False
if RUN_UNDER_LOAD:
    magat("calibrate", "--game", GAME, "--portfolio", PORTFOLIO, "--out", CALIBRATION,
          "--arms", "emcts,emcts-comma", "--under-load", WORKERS)
'''

CELLS = [
    md("""
# MAGAT — run everything from here

One cell per stage. Every cell is independently re-runnable: setup cells skip themselves once their
outputs exist, and every stage ends with a `bundle(...)` cell. Run output lives under `~/magat_runs`
(or `MAGAT_DATA`), **outside the clone**.
"""),
    md("""
## Parameters and helpers

Run this first, always. It finds the repository on its own (a Jupyter kernel's working directory is
the notebook's folder, not where the server was launched) and puts the data root outside the clone.
"""),
    code(SETUP, tags=("setup",)),
    md("""
## Build, tests, layering
"""),
    code('magat("build", "--tests")\nmagat("test")\nmagat("layering")', tags=("build",)),
    md("""
## Portfolio

Build this machine's own copy of the portfolio from the game's scenario sources, freeze its split,
and verify it against the published one: the same identity, every manifest row equal, and the same
plans from both sets of scenario files. A rebuild that matches is the evidence that positions, and
therefore every published result, reproduce here. Skips itself once the portfolio is built and frozen;
the journal makes an interrupted build resume where it stopped.
"""),
    code(PORTFOLIO_CELL, tags=("portfolio",)),
    md("""
## Machine, threads, calibration

The machine banner first: physical cores, the worker ceiling and why. Then the thread sweep, which
picks the knee of sustained throughput per budget level (results do not depend on it, only wall-clock
does). Then this machine's own currency, idle and single-threaded, and the drift check against the
published one. Each skips itself once its output exists.
"""),
    code(MACHINE_CELL, tags=("machine",)),
    code(THREADS_CELL, tags=("threads",)),
    code(CALIBRATE_CELL, tags=("calibrate",)),
    code(DRIFT_CELL, tags=("drift",)),
    code(UNDER_LOAD_CELL, tags=("under-load",)),
    md("""
## Gates

The contract laws on published positions, and the equivalence gate: every ported arm must reproduce
its thesis-tree oracle field for field. Nothing downstream runs otherwise.
"""),
    code('magat("check", "contract", "--game", GAME, "--scenarios", 8)\n'
         'assert_in_log("check-contract", "CONTRACT: PASS")\n'
         'magat("check", "equivalence", "--game", GAME, "--arms", "emcts", "--scenarios", 8, "--visits", 2, "--budgets", "8000,40000")\n'
         'assert_in_log("check-equivalence", "EQUIVALENCE: PASS")\n'
         'bundle("gates", [LOGS / "check-contract.log", LOGS / "check-equivalence.log", LOGS / "test.log"])'),
    md("""
## Shakedown

Every moving part at toy size, minutes, exits non-zero on any failure. Run before every pull request.
"""),
    code('magat("shakedown")\nbundle("shakedown", [LOGS / "shakedown.log"])'),
    md("""
---
*Experiment stages and analysis cells are added by the milestones that deliver them (see
`MAGAT/DESIGN.md` §16).*
"""),
]


def build() -> dict:
    return {
        "cells": CELLS,
        "metadata": {
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"},
            "magat": {"generated_by": "tools/make_notebook.py"},
        },
        "nbformat": 4,
        "nbformat_minor": 5,
    }


def main(argv) -> int:
    nb = build()
    text = json.dumps(nb, indent=1, ensure_ascii=False) + "\n"
    if "--check" in argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print(f"{OUT} is stale: regenerate with `python tools/make_notebook.py`")
            return 1
        print(f"{OUT} is up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT} ({len(CELLS)} cells)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
