#!/usr/bin/env python3
"""Shakedown: every moving part at toy size, in minutes, exiting non-zero on any failure.

It measures nothing. Every number it prints is too small to mean anything; the only question it
answers is "does this all work on this machine". Run before every pull request.

    python tools/shakedown.py [--game tribes]
"""
import os
import pathlib
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
PASS = 0
FAIL = 0


def step(title: str) -> None:
    print(f"\n=== {title}", flush=True)


def check(name: str, cmd: list, want: str = None, codes=(0,), then=None) -> None:
    """Run one command; it passes when it exits with one of `codes`, prints `want`, and `then()` holds."""
    global PASS, FAIL
    t0 = time.time()
    p = subprocess.run([str(c) for c in cmd], cwd=str(ROOT), capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (p.stdout or "") + (p.stderr or "")
    ok = p.returncode in codes and (want is None or want in out)
    if ok and then is not None:
        try:
            ok = bool(then())
        except Exception as e:
            out += "\npost-condition raised " + repr(e)
            ok = False
    print(f"  [{'ok' if ok else 'FAIL'}]   {name}  ({time.time() - t0:.1f}s)", flush=True)
    if ok:
        PASS += 1
    else:
        FAIL += 1
        print("         command: " + " ".join(str(c) for c in cmd))
        for line in out.strip().splitlines()[-15:]:
            print("         " + line)


def main(argv) -> int:
    game = argv[argv.index("--game") + 1] if "--game" in argv else "tribes"
    print("############################################################")
    print(f"# MAGAT SHAKEDOWN   game={game}")
    print("# Toy sizes throughout. This checks that things RUN, not what they measure.")
    print("############################################################")

    step("0. Environment")
    check("java on PATH", ["java", "-version"])
    check("python has numpy and pandas (analysis)", [PY, "-c", "import numpy, pandas"])

    step("1. Build")
    check("core, adapter, engine and tests compile", [PY, ROOT / "magat.py", "build", "--tests"], "TEST BUILD OK")
    check("layering holds", [PY, ROOT / "magat.py", "layering"], "0 violation(s)")

    step("2. Unit tests")
    check("junit", [PY, ROOT / "magat.py", "test"], "OK (")

    step("3. Contract laws on published positions")
    check("L1-L10 pass", [PY, ROOT / "magat.py", "check", "contract", "--game", game, "--scenarios", "4"], "CONTRACT: PASS")

    step("4. Gates and probes: equivalence, structure, replay, support, overhead")
    check("emcts reproduces the thesis agent at 8k on 2 positions",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "emcts",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")
    check("the headline arm, its control and a biased arm reproduce theirs, counters included",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "comma-z0-coarse,comma-rand15,comma-bias-e25",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")
    check("structure: the selection equivalences, the baseline contract, every mechanism differs from its control",
          [PY, ROOT / "magat.py", "check", "structure", "--game", game, "--spec", ROOT / "experiments" / game / "structure.json"],
          "STRUCTURE: PASS")
    check("replay: published R7615 holdout turns reproduce field for field",
          [PY, ROOT / "magat.py", "check", "replay", "--game", game, "--leaf",
           ROOT / "games" / game / "results" / "replay" / "Comma_Off" / "80000cu", "--turns", "3"], "REPLAY: PASS")
    check("support: the bias engages (toy size)",
          [PY, ROOT / "magat.py", "probe", "support", "--game", game, "--arm", "comma-bias-e25", "--levels", "40000",
           "--scenarios", "2"], "SUPPORT: ", codes=(0, 1))
    check("overhead: decorator and dormant counters are measured (toy size)",
          [PY, ROOT / "magat.py", "check", "overhead", "--game", game, "--turns", "2", "--reps", "2", "--warmup", "1"],
          "OVERHEAD: MEASURED")

    step("5. Portfolio: toy build, freeze, verify against the published portfolio")
    data = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    toy_dir = data / "shakedown" / f"portfolio-{game}"
    shutil.rmtree(toy_dir, ignore_errors=True)
    check("toy build (2 + 1 captures from one source game each) completes",
          [PY, ROOT / "magat.py", "portfolio", "build", "--game", game, "--out", toy_dir,
           "--targets", "single=2,dual=1", "--threads", "2"], "PORTFOLIO: COMPLETE")
    check("freeze writes the split files", [PY, ROOT / "magat.py", "portfolio", "freeze", "--portfolio", toy_dir], "FROZEN: ")
    check("every toy row equals the published row, and plans identically from both bins",
          [PY, ROOT / "magat.py", "portfolio", "verify", "--game", game, "--portfolio", toy_dir,
           "--published", ROOT / "games" / game / "portfolio" / "v1", "--subset", "--plan", "3"], "VERIFY: PASS")
    check("the published portfolio verifies against itself (bins, identity, split rule)",
          [PY, ROOT / "magat.py", "portfolio", "verify", "--game", game], "VERIFY: PASS")
    check("the holdout ledger reads", [PY, ROOT / "magat.py", "portfolio", "ledger", "--portfolio",
                                       ROOT / "games" / game / "portfolio" / "v1"], "holdout ledger")

    step("6. Machine, threads, calibration (toy sizes: gates reported, not enforced)")
    check("machine banner", [PY, ROOT / "magat.py", "machine"], "worker ceiling")
    calib = data / "shakedown" / f"calibration-{game}"
    shutil.rmtree(calib, ignore_errors=True)
    weights = calib / "compute_weights.properties"
    check("thread sweep writes threads.json",
          [PY, ROOT / "magat.py", "threads", "--game", game, "--out", calib, "--levels", "4000",
           "--ladder", "1,2", "--segment-seconds", "2"], "THREADS:", then=lambda: (calib / "threads.json").is_file())
    toy = ["--reps", "2", "--warmup", "1", "--toy"]
    check("calibrate writes a new (toy) currency",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts", "--levels", "2000,4000",
           "--turns", "2", *toy],
          "CALIBRATION: PASS", then=weights.is_file)
    snapshot = {}
    check("a second arm merges into it, re-denominated",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts-comma",
           "--levels", "2000,4000", *toy],
          "[merge] kept the currency",
          then=lambda: "profile.emcts-comma@4000.wAdvance" in snapshot.setdefault("w", weights.read_text(encoding="utf-8")))
    check("pricing it again without --replace is refused",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts-comma", "--levels", "4000", "--toy"],
          "--replace", codes=(2,), then=lambda: weights.read_text(encoding="utf-8") == snapshot["w"])
    check("--check reports drift and writes nothing",
          [PY, ROOT / "magat.py", "calibrate", "--check", "--game", game, "--weights", weights, "--arms", "emcts-comma",
           "--levels", "4000", *toy],
          "largest drift", codes=(0, 1), then=lambda: weights.read_text(encoding="utf-8") == snapshot["w"])
    check("--under-load writes beside the idle file, never over it",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts-comma",
           "--under-load", "3", "--levels", "4000", "--turns", "2", *toy],
          "IDLE vs 3 THREADS",
          then=lambda: (calib / "compute_weights_load3.properties").is_file() and weights.read_text(encoding="utf-8") == snapshot["w"])
    check("the published currency prices every worked-result arm",
          [PY, ROOT / "magat.py", "weights", "--game", game, "--arms", "@worked-result"], "WEIGHTS: COVERED")

    step("7. Runner: the shakedown spec on the toy portfolio and the toy currency")
    spec = ROOT / "experiments" / game / "shakedown.json"
    run_root = data / "shakedown" / f"run-{game}"
    shutil.rmtree(run_root, ignore_errors=True)
    run = [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", weights,
           "--out", run_root, "--threads", "2", "--toy"]
    check("dev: every leaf runs and is checkpointed", [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len((run_root / "dev" / "_completed.txt").read_text(encoding="utf-8").split()) == 6)
    check("re-running the same command skips every finished leaf", [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len(list((run_root / "dev").glob("*/*cu/turns.csv"))) == 6)
    check("holdout: runs into its own tree and is entered in the holdout ledger", [*run, "--split", "holdout"], "this read is entry 1",
          then=lambda: (run_root / "holdout" / "_run.json").is_file())
    check("a retired leaf is discarded and re-run", [*run, "--split", "dev", "--retire-leaf", "emcts-comma@4000"], "RETIRED emcts-comma@4000")
    repriced = calib / "compute_weights_repriced.properties"
    repriced.write_text(weights.read_text(encoding="utf-8").replace("profile.emcts-comma@2000.wCopy = ", "profile.emcts-comma@2000.wCopy = 9"),
                        encoding="utf-8")
    check("the currency lock refuses a leaf whose price changed",
          [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", repriced,
           "--out", run_root, "--threads", "2", "--toy", "--split", "dev"], "currency lock", codes=(4,))
    check("the shipped specs validate against the published currency",
          [PY, ROOT / "magat.py", "experiment", "validate", "--experiment", ROOT / "experiments" / game / "reference-ladder.json"],
          "VALIDATION: PASS")
    check("bundle zips a run tree", [PY, ROOT / "magat.py", "bundle", "--name", "shakedown-run", "--paths", run_root], "BUNDLE:")

    step("8. Notebook")
    check("generated notebook is current and its setup cells run from three directories",
          [PY, ROOT / "magat.py", "notebook", "selftest"], "0 failure(s)")

    print("\n############################################################")
    if FAIL == 0:
        print(f"# SHAKEDOWN PASSED ({PASS} checks).")
    else:
        print(f"# SHAKEDOWN FAILED: {FAIL} of {PASS + FAIL} checks.")
    print("############################################################")
    return FAIL


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
