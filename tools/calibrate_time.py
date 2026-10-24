#!/usr/bin/env python3
"""Find the budget levels, in compute units, that are round amounts of thinking time on this machine.

    python magat.py calibrate-time --game tribes --remeasure              500, 1000 and 5000 ms
    python magat.py calibrate-time --game tribes --remeasure --times 500,1000,5000 --positions 64 --tolerance 0.05

A maintainer's tool, run on the reference machine (the R7615 for Tribes), idle, single-threaded like a
calibration, from notebooks/maintainers/measure_levels.ipynb. Once a game's levels are published
(games/<game>/levels.json) every currency is measured at them, so new levels mean every currency must be
measured again: the tool refuses to run without --remeasure (the toy run of the shakedown excepted).

A level's units mean "this many milliseconds of thinking, initialization included, on the reference
machine, averaged over the portfolio's positions", and every machine that spends the same currency compares
arms as though they had run there. docs/CURRENCY.md says why the mean over positions is the anchor.

1. A probe: the reference arm's whole turns at one budget under the fixed measurement weights give a first
   time per unit, so a first guess of the units for each time.
2. Each iteration: the reference arm is calibrated at the guessed levels (`magat calibrate`, a working
   currency of its own), and its turns are timed under that currency over positions spread across the
   portfolio (`magat check time`). A level's prices make its units buy the same work for every arm, so
   the reference arm's time is the level's time. Any level whose mean is more than the tolerance from its
   target is rescaled by target / measured, and the iteration repeats (at most --max-iterations).
3. The found levels are rounded to the nearest --round units (500), measured once more at the rounded
   values, and written to levels.json with every measurement. Publish it as games/<game>/levels.json;
   from then on the launcher uses those levels wherever none are named.

Output: <data>/calibration/<game>-levels/ (levels.json, SUMMARY.md, probe.json, iter-<k>/ with each
iteration's working currency and timing).
"""
import argparse
import datetime
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable


def magat(args, log_lines=None) -> int:
    """One magat command, its output streamed (and kept), its exit code returned."""
    cmd = [PY, str(ROOT / "magat.py"), *[str(a) for a in args]]
    print("$ magat " + " ".join(str(a) for a in args), flush=True)
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    for line in p.stdout:
        print("    " + line, end="", flush=True)
        if log_lines is not None:
            log_lines.append(line.rstrip("\n"))
    return p.wait()


def read_cells(path: pathlib.Path) -> dict:
    """{budget: cell} of one arm's time.json."""
    return {int(c["budget"]): c for c in json.loads(path.read_text(encoding="utf-8"))["cells"]}


def main(argv) -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass
    ap = argparse.ArgumentParser(prog="magat calibrate-time", description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--game", default="tribes")
    ap.add_argument("--times", default="500,1000,5000", help="milliseconds of thinking time, one level each")
    ap.add_argument("--tolerance", type=float, default=0.05, help="a level is found when its mean is this close to its time")
    ap.add_argument("--round", type=int, default=500, help="round the found units to a multiple of this")
    ap.add_argument("--positions", type=int, default=64, help="positions spread over the portfolio, one turn each, per level")
    ap.add_argument("--arm", help="the reference arm (default: the game's)")
    ap.add_argument("--probe", type=int, default=20000, help="units of the first, fixed-weight probe")
    ap.add_argument("--max-iterations", type=int, default=4)
    ap.add_argument("--portfolio", help="default: the game's published portfolio")
    ap.add_argument("--out", help="default: <data>/calibration/<game>-levels")
    ap.add_argument("--redo", action="store_true", help="measure again over an existing levels.json")
    ap.add_argument("--toy", action="store_true", help="toy sizes for the shakedown: tiny times, few positions, nothing enforced")
    ap.add_argument("--remeasure", action="store_true",
                    help="measure although the game's levels are published (a maintainer's decision: every currency follows)")
    a = ap.parse_args(argv)

    published = ROOT / "games" / a.game / "levels.json"
    if published.is_file() and not (a.remeasure or a.toy):
        doc = json.loads(published.read_text(encoding="utf-8"))
        print(f"The levels of {a.game} are published in {published.relative_to(ROOT).as_posix()}: "
              + ", ".join(f"{lv['time_ms']} ms = {lv['units']:,} units" for lv in doc["levels"])
              + f" (measured {doc.get('measured', '?')}).\nEvery currency and experiment is measured at them; this machine "
              "uses them as they are. Measuring them again is a maintainer's job on the reference machine, after a change "
              "to what a turn costs (notebooks/maintainers/measure_levels.ipynb); it takes --remeasure.")
        print("CALIBRATE-TIME: REFUSED (the levels are published)")
        return 3

    times = [int(t) for t in a.times.split(",")]
    if sorted(times) != times or len(set(times)) != len(times) or times[0] <= 0:
        print("--times: increasing milliseconds, e.g. 500,1000,5000", file=sys.stderr)
        return 2
    data = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    out = pathlib.Path(a.out).resolve() if a.out else data / "calibration" / f"{a.game}-levels"
    out.mkdir(parents=True, exist_ok=True)
    levels_file = out / "levels.json"
    if levels_file.exists() and not a.redo:
        print(levels_file.read_text(encoding="utf-8"))
        print(f"[skip] {levels_file} exists (--redo to measure again)")
        return 0
    common = ["--game", a.game] + (["--portfolio", a.portfolio] if a.portfolio else [])
    arm = ["--arms", a.arm] if a.arm else []
    toy_cal = ["--toy", "--turns", "2", "--reps", "2", "--warmup", "1"] if a.toy else []
    toy_time = ["--warmup", "1"] if a.toy else []
    started = datetime.datetime.now()
    print(f"# calibrate-time: {a.game}, levels for {times} ms of thinking time, initialization in the budget, "
          f"{a.positions} positions, within {100 * a.tolerance:.0f}%, rounded to {a.round:,} units" + ("   (TOY)" if a.toy else ""))

    # ---- 1. the probe: a first time per unit, under the fixed measurement weights
    probe = out / "probe.json"
    rc = magat(["check", "time", *common, *arm, "--fixed-weights", "--levels", a.probe,
                "--positions", min(16, a.positions), *toy_time, "--out", probe])
    if rc != 0 or not probe.exists():
        print("CALIBRATE-TIME: FAIL - the probe did not run")
        return 1
    pc = next(iter(read_cells(probe).values()))
    arm = ["--arms", pc["arm"]]                      # the reference arm the probe timed, and nothing else, from here on
    ms_per_unit = pc["mean_ms"] / pc["mean_units"]
    found = [max(1.0, t / ms_per_unit) for t in times]
    print(f"# probe: {pc['mean_ms']:.0f} ms for {pc['mean_units']:,.0f} units => {1000 * ms_per_unit:.1f} us per unit; "
          f"first guess {', '.join(f'{t} ms: {u:,.0f}' for t, u in zip(times, found))}")

    def measure(k: int, units: list) -> dict:
        """Calibrate the reference arm at these levels into iter-<k>, and time it there."""
        d = out / f"iter-{k}"
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        levels = ",".join(str(u) for u in units)
        if magat(["calibrate", *common, *arm, "--levels", levels, "--out", d, *toy_cal]) != 0:
            if not a.toy:
                raise RuntimeError(f"iteration {k}: the calibration at {levels} failed (see above)")
        weights = d / "compute_weights.properties"
        if not weights.exists():
            raise RuntimeError(f"iteration {k}: no currency written at {levels}")
        timing = d / "time.json"
        magat(["check", "time", *common, *arm, "--weights", weights, "--levels", levels, "--targets", ",".join(map(str, times)),
               "--positions", a.positions, "--tolerance", a.tolerance, *toy_time, "--out", timing])
        if not timing.exists():
            raise RuntimeError(f"iteration {k}: the timing at {levels} did not run")
        cells = read_cells(timing)
        return {"iteration": k, "units": units, "cells": [cells[u] for u in units], "dir": str(d)}

    # ---- 2. iterate until every level's mean is within the tolerance of its time
    iterations = []
    converged = False
    try:
        for k in range(1, a.max_iterations + 1):
            units = [int(round(u)) for u in found]
            if len(set(units)) != len(units):
                raise RuntimeError(f"two times map to the same units {units}; ask for times further apart")
            it = measure(k, units)
            ratios = [c["mean_ms"] / t for c, t in zip(it["cells"], times)]
            it["ratios"] = ratios
            iterations.append(it)
            print(f"# iteration {k}: " + "; ".join(f"{t} ms at {u:,} units -> {c['mean_ms']:.0f} ms ({100 * (r - 1):+.1f}%)"
                                                   for t, u, c, r in zip(times, units, it["cells"], ratios)))
            if all(abs(r - 1) <= a.tolerance for r in ratios):
                converged = True
                break
            found = [u / r for u, r in zip(units, ratios)]

        # ---- 3. round, and measure the rounded levels
        last = iterations[-1]
        rounded = [max(a.round, int(round(u / a.round)) * a.round) for u in last["units"]]
        final = last if rounded == last["units"] else measure(len(iterations) + 1, rounded)
        final["ratios"] = [c["mean_ms"] / t for c, t in zip(final["cells"], times)]
        if final is not last:
            iterations.append(final)
    except RuntimeError as e:
        print(f"CALIBRATE-TIME: FAIL - {e}")
        return 1

    timing = json.loads((pathlib.Path(final["dir"]) / "time.json").read_text(encoding="utf-8"))
    doc = {
        "format": 1,
        "game": a.game,
        "reference_arm": final["cells"][0]["arm"],
        "basis": "the reference arm's whole turn (its initialization paid from the budget), single-threaded, under "
                 "its own calibrated prices at each level; the mean over positions spread across the portfolio",
        "initialization": timing.get("initialization", "in-budget"),
        "machine": timing.get("machine"),
        "engine": timing.get("engine"),
        "magat": timing.get("magat"),
        "measured": datetime.datetime.now().isoformat(timespec="seconds"),
        "positions": timing.get("positions"),
        "tolerance": a.tolerance,
        "rounding": a.round,
        "converged": converged,
        "levels": [{
            "time_ms": t, "units": u, "found_units": f,
            "measured": {key: round(c[key], 2) for key in ("mean_ms", "median_ms", "q1_ms", "q3_ms", "min_ms", "max_ms",
                                                             "mean_init_ms", "mean_units", "mean_init_units")},
            "ratio": round(r, 4)}
            for t, u, f, c, r in zip(times, final["units"], last["units"], final["cells"], final["ratios"])],
        "iterations": [{"iteration": it["iteration"], "units": it["units"], "ratios": [round(r, 4) for r in it["ratios"]]}
                       for it in iterations],
    }
    if a.toy:
        doc["toy"] = True
    levels_file.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    write_summary(out / "SUMMARY.md", doc, started)
    print(f"# -> {levels_file}")
    print("LEVELS: " + " ".join(f"{lv['time_ms']}ms={lv['units']}" for lv in doc["levels"])
          + ("" if converged else "   (NOT CONVERGED)") + ("   (TOY)" if a.toy else ""))
    if not converged and not a.toy:
        print(f"CALIBRATE-TIME: FAIL - not within {100 * a.tolerance:.0f}% after {a.max_iterations} iterations; "
              f"the machine may be too noisy (run it again when it is quiet)")
        return 1
    print("CALIBRATE-TIME: PASS")
    return 0


def write_summary(path: pathlib.Path, doc: dict, started) -> None:
    L = [f"# Time levels: {doc['game']}", "",
         f"Measured {doc['measured']} on {doc['machine']}, engine `{str(doc['engine'])[:10]}`, MAGAT `{str(doc['magat'])[:10]}`"
         + ("  (TOY)" if doc.get("toy") else "") + ".",
         f"Reference arm `{doc['reference_arm']}`, {doc['positions']} positions spread over the portfolio, one turn each, "
         f"single-threaded; initialization {doc['initialization']}.", "",
         "| Time | Units | Found (before rounding) | Mean | Off target | Median | Quartiles | Range | Initialization |",
         "|---|---|---|---|---|---|---|---|---|"]
    for lv in doc["levels"]:
        m = lv["measured"]
        L.append(f"| {lv['time_ms']:,} ms | **{lv['units']:,}** | {lv['found_units']:,} | {m['mean_ms']:,.0f} ms | "
                 f"{100 * (lv['ratio'] - 1):+.1f}% | {m['median_ms']:,.0f} ms | {m['q1_ms']:,.0f}-{m['q3_ms']:,.0f} ms | "
                 f"{m['min_ms']:,.0f}-{m['max_ms']:,.0f} ms | {m['mean_init_ms']:,.0f} ms |")
    L += ["", "| Iteration | Units | Off target |", "|---|---|---|"]
    for it in doc["iterations"]:
        L.append(f"| {it['iteration']} | {', '.join(f'{u:,}' for u in it['units'])} | "
                 f"{', '.join(f'{100 * (r - 1):+.1f}%' for r in it['ratios'])} |")
    L += ["", f"{'Converged' if doc['converged'] else 'NOT converged'}: every level within {100 * doc['tolerance']:.0f}% "
              f"before rounding to {doc['rounding']:,} units. Took {(datetime.datetime.now() - started).total_seconds() / 60:.0f} min.", ""]
    path.write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
