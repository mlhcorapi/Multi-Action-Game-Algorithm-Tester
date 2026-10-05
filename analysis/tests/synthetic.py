"""A synthetic run tree whose every measure has a known answer.

The baseline `base` runs at 1,000 and 4,000 units. On every (scenario, visit) cell its search gain is

    G(B) = 100 + 50 ln(B / 1000) + s + 5 (v - 1)

so its mean gain is exactly log-linear in budget, and the scenario and visit offsets are identical for
every arm on the same cell, so they cancel in every paired comparison. An arm whose gain is G(B') on
every cell is therefore worth exactly B' / B of its budget, in every bootstrap resample:

    good    at 1,000   G(2000)  -> multiplier 2 (inside the ladder)
    great   at 1,000   G(8000)  -> multiplier 8 (extrapolated past the top)
    weak    at 4,000   G(2000)  -> multiplier 0.5 (inside)
    poor    at 1,000   G(500)   -> multiplier 0.5 (outside, below the bottom)
    noisy   at 1,000   G(1000) + 10 on even cells, - 2 on odd ones (a known mean and spread)

Plan times: the baseline spends B / 10 ms; `good` spends 250 ms, which the baseline's line through
(100 ms, G(1000)) and (400 ms, G(4000)) values at exactly G(2000) = good's own value, so its W7
difference is exactly zero, and its wall-clock multiplier is 200 ms needed / 250 ms spent = 0.8.

Scenario 12, visit 0 of `good` is terminal (flagged); scenario 11, visit 1 of `noisy` is terminal by
magnitude only (a blank flag, as an imported thesis row has).
"""
import json
import math
import pathlib

S, V = 12, 3
SOURCES = {s: ("single" if s % 2 else "dual") for s in range(1, S + 1)}
COLUMNS = ["scenario", "source", "phase", "visit", "arm", "budget", "initial", "final", "terminal", "avg_node",
           "advances", "copies", "evals", "units", "init_advances", "init_copies", "init_evals", "init_units",
           "plan_ms", "init_ms", "nodes", "unique", "duplicates", "seq_len"]
WEIGHTS = {"base@1000": (1.0, 0.1, 0.05), "base@4000": (1.0, 0.1, 0.05), "good@1000": (1.2, 0.1, 0.05),
           "great@1000": (1.0, 0.2, 0.05), "weak@4000": (1.0, 0.1, 0.1), "poor@1000": (1.0, 0.1, 0.05),
           "noisy@1000": (1.0, 0.1, 0.05)}


def gain(b: float, s: int, v: int) -> float:
    return 100 + 50 * math.log(b / 1000) + s + 5 * (v - 1)


def leaf_rows(arm, budget, equiv, ms, special=None, telemetry=True):
    rows = []
    for s in range(1, S + 1):
        for v in range(V):
            initial = 1000 + 10 * s
            final = initial + gain(equiv, s, v)
            if special:
                final += special(s, v)
            term = ""
            if arm == "good" and (s, v) == (12, 0):
                final, term = 2.0e9, 1
            elif arm == "noisy" and (s, v) == (11, 1):
                final, term = -2.0e9, ""
            elif term == "":
                term = 0 if arm != "noisy" else ""
            a, c, e = 1000 + s, 400 + v, 300
            w = WEIGHTS[f"{arm}@{budget}"]
            units = a * w[0] + c * w[1] + e * w[2]
            nodes = 50 + s
            # Full precision, not the runner's four decimals: the known answers are exact.
            row = [s, SOURCES[s], 10, v, arm, budget, repr(initial), repr(final), term, repr(initial),
                   a, c, e, repr(units), 10, 5, 5, "10.75", ms, 3, nodes, nodes - 5, 5, 20]
            if telemetry:
                row.append(nodes - 1)
            rows.append(row)
    return rows


def write_leaf(split_dir: pathlib.Path, arm, budget, rows, telemetry=True, profile=None):
    d = split_dir / arm / f"{budget}cu"
    d.mkdir(parents=True, exist_ok=True)
    header = COLUMNS + (["t_mutEvents"] if telemetry else [])
    (d / "turns.csv").write_text(",".join(header) + "\n" + "".join(",".join(str(c) for c in r) + "\n" for r in rows),
                                 encoding="utf-8")
    (d / "_leaf.json").write_text(json.dumps(dict(arm=arm, budget=budget, profile=profile or f"{arm}@{budget}",
                                                  status="complete")), encoding="utf-8")


def write_curve(split_dir: pathlib.Path, arm, budget, offset):
    d = split_dir / arm / f"{budget}cu"
    lines = ["scenario,visit,arm,budget,checkpoint,best,advances,units,ms"]
    for s in range(1, S + 1):
        for v in range(V):
            for cp in range(1, 5):
                lines.append(f"{s},{v},{arm},{budget},{cp},{1000 + 10 * s + cp * 10 + offset * cp},{cp * 100},{cp * 100.0},")
    (d / "curve.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")


def build(root: pathlib.Path, split="dev", spec_path=None) -> pathlib.Path:
    d = root / split
    d.mkdir(parents=True, exist_ok=True)
    write_leaf(d, "base", 1000, leaf_rows("base", 1000, 1000, 100))
    write_leaf(d, "base", 4000, leaf_rows("base", 4000, 4000, 400))
    write_leaf(d, "good", 1000, leaf_rows("good", 1000, 2000, 250))
    write_leaf(d, "great", 1000, leaf_rows("great", 1000, 8000, 500))
    write_leaf(d, "weak", 4000, leaf_rows("weak", 4000, 2000, 300))
    write_leaf(d, "poor", 1000, leaf_rows("poor", 1000, 500, 90, telemetry=False), telemetry=False)
    write_leaf(d, "noisy", 1000, leaf_rows("noisy", 1000, 1000, 100,
                                           special=lambda s, v: 10 if (s * V + v) % 2 == 0 else -2))
    write_curve(d, "base", 1000, 0)
    write_curve(d, "good", 1000, 5)
    run = dict(format=1, split=split, game=dict(id="toy"), portfolio=dict(identity="synthetic"),
               weights=dict(path="synthetic.properties", sha256="0" * 64, reference="base@1000"))
    if spec_path:
        run["experiment"] = dict(name="synthetic", path=str(spec_path), sha256="")
    (d / "_run.json").write_text(json.dumps(run), encoding="utf-8")
    (d / "_split.txt").write_text(split + "\n", encoding="utf-8")
    return d


def write_spec(path: pathlib.Path) -> pathlib.Path:
    path.write_text(json.dumps(dict(
        name="synthetic", game="toy", split="dev", baseline="base",
        levels=[dict(budget=1000, visits=V), dict(budget=4000, visits=V)],
        arms=[dict(id="base"), dict(id="good", attribution="noisy"), dict(id="great"), dict(id="weak"),
              dict(id="poor"), dict(id="noisy")],
        contrasts=[dict(a="great", b="good", label="great over good")])), encoding="utf-8")
    return path


def write_weights(path: pathlib.Path, scale=1.0) -> pathlib.Path:
    lines = ["magat.weights.version = 1", "game = toy", "reference = base@1000",
             "global.wAdvance = 1.0", "global.wCopy = 0.1", "global.wEval = 0.05"]
    for name, (a, c, e) in WEIGHTS.items():
        lines += [f"profile.{name}.wAdvance = {a * scale}", f"profile.{name}.wCopy = {c * scale}",
                  f"profile.{name}.wEval = {e * scale}"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path
