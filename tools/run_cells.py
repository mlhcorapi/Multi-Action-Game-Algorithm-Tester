#!/usr/bin/env python3
"""Run a cell of the generated notebook for real, at toy size: the shakedown's check that the notebook's own
commands work. The notebook self-test replaces the launcher with a stub; this runs it.

    python tools/run_cells.py gates       [--portfolio DIR] [--spec FILE]
    python tools/run_cells.py experiments --spec FILE --half dev --portfolio DIR --weights FILE --calibration DIR

The cell's source comes from tools/make_notebook.py, the notebook's own setup cell defines its helpers (magat,
assert_in_log, bundle), and only the sizes in each command are made small: --scenarios 2, --visits 1,
--budgets 8000, --budget 4000 and few turns for the audit, and --threads 2 --toy for a run. Everything else (which
command, which arms, which defaults it leans on, which currency) is the cell's. Exit 0 when the cell completes.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import make_notebook as mk  # noqa: E402

SIZES = {"--scenarios": "2", "--visits": "1", "--budgets": "8000", "--budget": "4000"}


def toy(args: list) -> list:
    """The command with its sizes made small, and nothing else changed."""
    out = list(args)
    for i, a in enumerate(out[:-1]):
        if a in SIZES:
            out[i + 1] = SIZES[a]
    if out[:2] == ["check", "audit"]:
        out += ["--turns", "2", "--reps", "1", "--warmup", "1"]
    if out[:2] == ["experiment", "run"]:
        out += ["--threads", "2", "--toy", "--no-bundle"]
    return out


def main(argv) -> int:
    if not argv or argv[0] not in ("gates", "experiments"):
        print(__doc__)
        return 2
    opt = lambda k, d=None: argv[argv.index(k) + 1] if k in argv else d   # noqa: E731
    ns = {}
    exec(compile(mk.SETUP, "<setup-cell>", "exec"), ns)
    real = ns["magat"]

    def magat(*args, check=True):
        args = toy([str(a) for a in args])
        print("[toy] " + " ".join(args), flush=True)
        return real(*args, check=check)

    ns["magat"] = magat
    if argv[0] == "gates":
        ns.update(PORTFOLIO=pathlib.Path(opt("--portfolio", ns["PUBLISHED_PORTFOLIO"])), MID_LEVEL=20000,
                  EXPERIMENT_RUNS=[(pathlib.Path(opt("--spec")), None)] if opt("--spec") else [])
        exec(compile(mk.GATES_CELL, "<gates-cell>", "exec"), ns)
    else:
        ns.update(SCHEDULE=[("run", pathlib.Path(opt("--spec")), opt("--half"))], PORTFOLIO=pathlib.Path(opt("--portfolio")),
                  WEIGHTS=pathlib.Path(opt("--weights")), CALIBRATION=pathlib.Path(opt("--calibration")))
        exec(compile(mk.EXPERIMENTS_CELL, "<experiments-cell>", "exec"), ns)
    print(f"CELL {argv[0].upper()}: COMPLETE")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
