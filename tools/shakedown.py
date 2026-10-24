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
    check("docs/ADDING_AN_ALGORITHM.md shows the compiled FirstAgent, verbatim",
          [PY, "-c", "import pathlib, sys; r = pathlib.Path(sys.argv[1]); "
                     "src = (r / 'core/src/magat/agents/first/FirstAgent.java').read_text(encoding='utf-8').replace(chr(13), '').strip(); "
                     "doc = (r / 'docs/ADDING_AN_ALGORITHM.md').read_text(encoding='utf-8').replace(chr(13), ''); "
                     "sys.exit(0 if '```java' + chr(10) + src + chr(10) + '```' in doc else 1)", ROOT])

    step("2. Unit tests")
    check("junit, then the analysis package: one line, everything else in the log",
          [PY, ROOT / "magat.py", "test"], "TESTS: PASS")

    step("3. Contract laws on published positions")
    check("L1-L9 pass", [PY, ROOT / "magat.py", "check", "contract", "--game", game, "--scenarios", "4"], "CONTRACT: PASS")

    step("4. Gates and probes: equivalence, audit")
    check("emcts reproduces the engine's agent at 8k on 2 positions",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "emcts",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")
    check("audit: each arm's declared operations and forward model account for its turn (toy size)",
          [PY, ROOT / "magat.py", "check", "audit", "--game", game, "--arms", "emcts",
           "--budget", "4000", "--turns", "2", "--reps", "1", "--warmup", "1"], "AUDIT: PASS")

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

    step("6. Machine, threads, calibration (toy sizes: gates reported, not enforced)")
    check("machine banner", [PY, ROOT / "magat.py", "machine"], "worker ceiling")
    calib = data / "shakedown" / f"calibration-{game}"
    shutil.rmtree(calib, ignore_errors=True)
    weights = calib / "compute_weights.properties"
    check("thread sweep writes threads.json",
          [PY, ROOT / "magat.py", "threads", "--game", game, "--out", calib, "--levels", "4000",
           "--ladder", "1,2", "--segment-seconds", "2"], "THREADS:", then=lambda: (calib / "threads.json").is_file())
    toy = ["--reps", "2", "--warmup", "1", "--toy"]
    check("--estimate times a few turns and says what the session would take, writing nothing",
          [PY, ROOT / "magat.py", "calibrate", "--estimate", "--game", game, "--out", calib, "--arms", "emcts",
           "--levels", "2000,4000", "--turns", "2", *toy],
          "ESTIMATE: ", then=lambda: not weights.is_file())
    check("calibrate writes a new (toy) currency, version 2: every declared operation priced and audited",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts", "--levels", "2000,4000",
           "--turns", "2", *toy],
          "=== AUDIT", then=lambda: weights.is_file() and "magat.weights.version = 2" in weights.read_text(encoding="utf-8")
          and "profile.emcts@2000.w.emcts.generation" in weights.read_text(encoding="utf-8"))
    snapshot = {}
    check("pricing it again without --replace is refused",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts", "--levels", "2000,4000", "--toy"],
          "--replace", codes=(2,),
          then=lambda: weights.read_text(encoding="utf-8") == snapshot.setdefault("w", weights.read_text(encoding="utf-8")))
    check("--check gates each cell's turn-price drift and writes nothing",
          [PY, ROOT / "magat.py", "calibrate", "--check", "--game", game, "--weights", weights, "--arms", "emcts",
           "--levels", "4000", *toy],
          "largest drift in a turn's price", codes=(0, 1), then=lambda: weights.read_text(encoding="utf-8") == snapshot["w"])
    check("the toy currency prices the reference arms at its own levels",
          [PY, ROOT / "magat.py", "weights", "--game", game, "--weights", weights, "--arms", "@reference"], "WEIGHTS: COVERED")
    check("a coverage probe says what is still to price and exits 0: progress, not a gate",
          [PY, ROOT / "magat.py", "weights", "--game", game, "--weights", weights, "--arms", "emcts", "--levels", "2000,4000,8000",
           "--progress"], "WEIGHTS: 1 TO PRICE")
    check("check time: whole turns under the toy currency, single-threaded, against a target",
          [PY, ROOT / "magat.py", "check", "time", "--game", game, "--weights", weights, "--arms", "emcts",
           "--levels", "2000,4000", "--targets", "100,200", "--positions", "3", "--warmup", "1",
           "--out", calib / "time.json"], "TIME: ", codes=(0, 1), then=lambda: (calib / "time.json").is_file())
    timed = data / "shakedown" / f"levels-{game}"
    shutil.rmtree(timed, ignore_errors=True)
    check("calibrate-time: probe, calibrate, time, rescale, round, levels.json (toy)",
          [PY, ROOT / "magat.py", "calibrate-time", "--game", game, "--toy", "--times", "300,600", "--positions", "3",
           "--round", "100", "--probe", "2000", "--max-iterations", "2", "--out", timed], "CALIBRATE-TIME: PASS",
          then=lambda: '"time_ms": 600' in (timed / "levels.json").read_text(encoding="utf-8"))
    controlled = data / "shakedown" / f"controlled-{game}"
    shutil.rmtree(controlled, ignore_errors=True)
    priced = controlled / "compute_weights.properties"
    sections = [PY, ROOT / "magat.py", "calibrate-controlled", "--name", "shakedown", "--game", game, "--out", controlled, "--toy",
                "--arms", "emcts", "--batch", "1", "--levels", "2000,4000", "--turns", "2",
                "--reps", "2", "--warmup", "1", "--soak-minutes", "0", "--interval", "2"]
    check("calibrate-controlled --plan: one section, nothing measured", [*sections, "--plan"], "SECTIONS: 1",
          then=lambda: not priced.is_file())
    check("one session: preflight, controls, stability check, the session, its verdict and the self-check (toy)",
          sections, "CONTROLLED CALIBRATION: COMPLETE",
          then=lambda: "profile.emcts@4000.w.advance" in priced.read_text(encoding="utf-8"))
    check("a priced section is not measured again without --redo", [*sections, "--section", "1"], "already priced")

    step("7. Runner: the shakedown spec on the toy portfolio and the toy currency")
    spec = ROOT / "experiments" / game / "shakedown.json"
    run_root = data / "shakedown" / f"run-{game}"
    shutil.rmtree(run_root, ignore_errors=True)
    run = [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", weights,
           "--out", run_root, "--threads", "2", "--toy"]
    check("dev: every leaf runs and is checkpointed, its rows counting the arm's own operations",
          [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len((run_root / "dev" / "_completed.txt").read_text(encoding="utf-8").split()) == 4
          and "op_emcts.generation" in (run_root / "dev" / "emcts" / "2000cu" / "turns.csv").read_text(encoding="utf-8").splitlines()[0])
    check("re-running the same command skips every finished leaf", [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len(list((run_root / "dev").glob("*/*cu/turns.csv"))) == 4)
    check("holdout: runs into its own tree", [*run, "--split", "holdout"], "RUN: COMPLETE",
          then=lambda: (run_root / "holdout" / "_run.json").is_file())
    check("a retired leaf is discarded and re-run", [*run, "--split", "dev", "--retire-leaf", "emcts@4000"], "RETIRED emcts@4000")
    repriced = calib / "compute_weights_repriced.properties"
    repriced.write_text(weights.read_text(encoding="utf-8").replace("profile.emcts@2000.w.copy = ", "profile.emcts@2000.w.copy = 9"),
                        encoding="utf-8")
    check("the currency lock refuses a leaf whose price changed",
          [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", repriced,
           "--out", run_root, "--threads", "2", "--toy", "--split", "dev"], "currency lock", codes=(4,))
    # The reference ladder runs at the published levels (games/<game>/levels.json), which no published currency
    # prices yet; its structure is checked until one does.
    check("the shipped spec reference-ladder validates (structure; no currency at the published levels yet)",
          [PY, ROOT / "magat.py", "experiment", "validate", "--no-weights", "--experiment",
           ROOT / "experiments" / game / "reference-ladder.json"], "VALIDATION: PASS")
    check("bundle zips a run tree", [PY, ROOT / "magat.py", "bundle", "--name", "shakedown-run", "--paths", run_root], "BUNDLE:")

    step("8. Analyzer: the toy run, re-priced in its own currency")
    results = data / "shakedown" / f"results-{game}"
    shutil.rmtree(results, ignore_errors=True)

    def every_table(out, *extra):
        return all((out / "tables" / f"{n}.csv").is_file() for n in (
            "headline", "budget_multiplier", "wallclock", "contrasts", "variance", "terminals", *extra)) \
            and (out / "SUMMARY.md").is_file()

    def same_units(out):
        """Re-pricing under the run's own weights must reproduce the units it ran on: the analyzer reads
        the weights file the calibrator wrote and the counts the runner wrote, the same way they mean them."""
        rows = (out / "tables" / "repriced_spend.csv").read_text(encoding="utf-8").splitlines()
        h = rows[0].split(",")
        return len(rows) > 1 and all(abs(float(r.split(",")[h.index("Ratio")]) - 1) < 1e-4 for r in rows[1:])

    toy_out = results / "toy"
    check("analyze: every table from the toy trees, re-priced in their own version-2 currency (every operation)",
          [PY, ROOT / "magat.py", "analyze", "--run", run_root, "--reprice", weights, "--out", toy_out], "ANALYZE: OK",
          then=lambda: every_table(toy_out, "repriced", "repriced_spend") and same_units(toy_out))

    step("9. Notebook")
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
