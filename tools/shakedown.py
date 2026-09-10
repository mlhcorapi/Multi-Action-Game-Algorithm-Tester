#!/usr/bin/env python3
"""Shakedown: every moving part at toy size, in minutes, exiting non-zero on any failure.

It measures nothing. Every number it prints is too small to mean anything; the only question it
answers is "does this all work on this machine". Run before every pull request.

    python tools/shakedown.py [--game tribes]
"""
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
PASS = 0
FAIL = 0


def step(title: str) -> None:
    print(f"\n=== {title}", flush=True)


def check(name: str, cmd: list, want: str = None) -> None:
    global PASS, FAIL
    t0 = time.time()
    p = subprocess.run([str(c) for c in cmd], cwd=str(ROOT), capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (p.stdout or "") + (p.stderr or "")
    ok = p.returncode == 0 and (want is None or want in out)
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

    step("4. Equivalence gate (the backbone of every cross-stage comparison)")
    check("emcts reproduces the thesis agent at 8k on 2 positions",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "emcts",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")

    step("5. Notebook")
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
