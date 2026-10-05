"""`magat import-legacy`: an archived thesis run tree (`stage1/<split>`) -> MAGAT's leaf layout (design 10).

The thesis runner wrote, per leaf `<Config>/<cu>cu/`:
  turn_telemetry.csv  one row per (scenario, visit): values, nodes, unique sequences, plan time and the
                      agent's counters
  results.csv         one row per bridge-burn phase: the best value so far, forward-model calls and units
                      elapsed, with the turn's totals (FmCalls, SearchUnits, AvgNodeValue, InitMillis)
                      repeated on every row

and they become:
  turns.csv           the section 10.1 columns. `advances`, `copies`, `evals`, the `init_*` columns,
                      `terminal` and `seq_len` stay blank: the thesis rows carry no copy or evaluation
                      counts, and their forward-model count (FmCalls, kept as `t_fmCalls`) includes the
                      initialization's advances, which MAGAT's `advances` does not. A blank `terminal`
                      makes the analyzer fall back to the thesis's own cutoff, |final| >= 1e9.
                      MutChildDuplicates is `duplicates`; every other counter is `t_<counter>`, first
                      letter lower-cased, which is the name MAGAT's QdeltaEmcts reports it under.
  curve.csv           checkpoint = BridgeBurningPhaseNumber, best = BestSequenceValue, advances =
                      FmCallsElapsed, units = ComputeUnitsElapsed; `ms` blank (not recorded per phase).
  _leaf.json          arm, budget, profile; the price is not recorded (the thesis runner kept none per
                      leaf), and `imported` names the source files and their hashes.

Thesis configuration names map to arm ids through tools/legacy-arms.json. Every row's scenario must be
in the portfolio, with the same source, and in the split's own scenario list.
"""
import csv
import datetime
import hashlib
import json
import math
import pathlib
from collections import OrderedDict
from decimal import Decimal

from . import __version__
from .load import ROOT, read_weights, resolve, shown

COLUMNS = ["scenario", "source", "phase", "visit", "arm", "budget", "initial", "final", "terminal", "avg_node",
           "advances", "copies", "evals", "units", "init_advances", "init_copies", "init_evals", "init_units",
           "plan_ms", "init_ms", "nodes", "unique", "duplicates", "seq_len"]
CURVE_HEADER = ["scenario", "visit", "arm", "budget", "checkpoint", "best", "advances", "units", "ms"]
MAPPED = {"ScenarioId", "ScenarioSource", "VisitId", "Config", "InitialValue", "FinalValue", "NodesEvaluated",
          "UniqueSequences", "PlanMillis", "MutChildDuplicates"}
DEFAULT_MAP = ROOT / "tools" / "legacy-arms.json"
PUBLISHED_PORTFOLIO = {"tribes": ROOT / "games" / "tribes" / "portfolio" / "v1"}


def num(s) -> str:
    """A value as MAGAT writes it: rounded to four decimals, trailing zeros dropped, never an exponent."""
    if s is None or str(s).strip() == "":
        return ""
    v = float(s)
    if not math.isfinite(v):
        return "NaN" if math.isnan(v) else ("Infinity" if v > 0 else "-Infinity")
    v = math.floor(v * 10000.0 + 0.5) / 10000.0
    return format(Decimal(repr(v)).normalize(), "f")


def lower_first(s: str) -> str:
    return s[:1].lower() + s[1:]


def sha256(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def read_manifest(portfolio: pathlib.Path) -> dict:
    """{scenario id: (source, phase)} from a portfolio manifest, version 1 or the thesis format."""
    lines = [l for l in (portfolio / "portfolio.csv").read_text(encoding="utf-8").splitlines() if l and not l.startswith("#")]
    rows = list(csv.DictReader(lines))
    phase = "Phase" if "Phase" in rows[0] else "CapturedTick"
    return {int(r["ScenarioId"]): (r["Source"], int(r[phase])) for r in rows}


def read_split_ids(portfolio: pathlib.Path, split: str):
    f = portfolio / f"_split_{split}.txt"
    if not f.is_file():
        return None
    return {int(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}


def read_csv(p: pathlib.Path) -> list:
    with open(p, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def write_csv(p: pathlib.Path, header, rows) -> None:
    with open(p, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def leaves_of(tree: pathlib.Path) -> list:
    """The thesis leaves under a tree: `<Config>/<cu>cu/` holding turn_telemetry.csv. A leaf directory
    itself is accepted too (one leaf)."""
    if (tree / "turn_telemetry.csv").is_file():
        return [tree]
    return sorted(p for p in tree.glob("*/*cu") if (p / "turn_telemetry.csv").is_file())


def import_legacy(tree, out, mapping=None, portfolio=None, split=None, experiment=None, weights=None,
                  curve=True, game="tribes", label=None, echo=print) -> int:
    tree = pathlib.Path(tree)
    leaves = leaves_of(tree)
    if not leaves:
        echo(f"IMPORT: {tree} holds no thesis leaves (<Config>/<cu>cu/turn_telemetry.csv)")
        return 2
    root = tree if leaves != [tree] else tree.parent.parent
    split = split or ((root / "_split.txt").read_text(encoding="utf-8").strip() if (root / "_split.txt").is_file() else "")
    if not split:
        echo("IMPORT: the tree has no _split.txt; name the split with --split")
        return 2
    mapping = pathlib.Path(mapping) if mapping else DEFAULT_MAP
    amap = {k: v for k, v in json.loads(mapping.read_text(encoding="utf-8")).items() if not k.startswith("_")}
    portfolio = pathlib.Path(portfolio) if portfolio else PUBLISHED_PORTFOLIO.get(game)
    if portfolio is None or not (portfolio / "portfolio.csv").is_file():
        echo(f"IMPORT: no portfolio manifest at {portfolio}; pass --portfolio")
        return 2
    manifest = read_manifest(portfolio)
    split_ids = read_split_ids(portfolio, split)
    completed = set()
    if (root / "_completed.txt").is_file():
        completed = {l.strip() for l in (root / "_completed.txt").read_text(encoding="utf-8").splitlines() if l.strip()}

    dest = pathlib.Path(out) / split
    existing = dest / "_run.json"
    if existing.is_file() and "imported" not in json.loads(existing.read_text(encoding="utf-8")):
        echo(f"IMPORT: {dest} is a MAGAT run tree, not an import; refusing to write into it")
        return 2
    dest.mkdir(parents=True, exist_ok=True)

    errors, done, total_rows = [], [], 0
    for leaf in leaves:
        config, budget = leaf.parent.name, int(leaf.name[:-2])
        arm = amap.get(config)
        if arm is None:
            errors.append(f"{config}: not in {mapping.name}; add the mapping before importing")
            continue
        tele = read_csv(leaf / "turn_telemetry.csv")
        res_path = leaf / "results.csv"
        res = read_csv(res_path) if res_path.is_file() else []
        firsts, burns = {}, {}
        for r in res:
            k = (int(r["ScenarioId"]), int(r["VisitId"]))
            firsts.setdefault(k, r)
            burns.setdefault(k, []).append(r)
        extra = [c for c in (tele[0].keys() if tele else []) if c not in MAPPED]
        header = COLUMNS + ["t_" + lower_first(c) for c in extra] + ["t_fmCalls"]
        rows, seen = [], set()
        for r in tele:
            if r["Config"] != config:
                errors.append(f"{config}/{leaf.name}: a row says Config={r['Config']}")
                break
            sc, vi = int(r["ScenarioId"]), int(r["VisitId"])
            if (sc, vi) in seen:
                errors.append(f"{config}/{leaf.name}: scenario {sc} visit {vi} appears twice")
                continue
            seen.add((sc, vi))
            if sc not in manifest:
                errors.append(f"{config}/{leaf.name}: scenario {sc} is not in the portfolio at {portfolio}")
                continue
            source, phase = manifest[sc]
            if r["ScenarioSource"] != source:
                errors.append(f"{config}/{leaf.name}: scenario {sc} is {r['ScenarioSource']} here, {source} in the portfolio")
            if split_ids is not None and sc not in split_ids:
                errors.append(f"{config}/{leaf.name}: scenario {sc} is not in the portfolio's {split} split")
            f = firsts.get((sc, vi), {})
            rows.append([sc, source, phase, vi, arm, budget, num(r["InitialValue"]), num(r["FinalValue"]), "",
                         num(f.get("AvgNodeValue")), "", "", "", num(f.get("SearchUnits")), "", "", "", "",
                         num(r["PlanMillis"]), num(f.get("InitMillis")), num(r["NodesEvaluated"]),
                         num(r["UniqueSequences"]), num(r["MutChildDuplicates"]), ""]
                        + [num(r[c]) for c in extra] + [num(f.get("FmCalls"))])
        rows.sort(key=lambda x: (x[0], x[3]))
        out_leaf = dest / arm / f"{budget}cu"
        out_leaf.mkdir(parents=True, exist_ok=True)
        write_csv(out_leaf / "turns.csv", header, rows)
        if curve and res:
            crow = []
            for (sc, vi), bs in sorted(burns.items()):
                if (sc, vi) not in seen:
                    continue
                for b in sorted(bs, key=lambda b: int(b["BridgeBurningPhaseNumber"])):
                    crow.append([sc, vi, arm, budget, int(b["BridgeBurningPhaseNumber"]), num(b["BestSequenceValue"]),
                                 num(b["FmCallsElapsed"]), num(b["ComputeUnitsElapsed"]), ""])
            write_csv(out_leaf / "curve.csv", CURVE_HEADER, crow)
        elif (out_leaf / "curve.csv").exists():
            (out_leaf / "curve.csv").unlink()
        complete = not completed or f"config={config};cu={budget};split={split}" in completed
        meta = OrderedDict(arm=arm, budget=budget, profile=f"{arm}@{budget}", weights=None,
                           status="complete" if complete else "incomplete", turnsDone=len(rows), failures=0,
                           imported=OrderedDict(config=config, source=shown(leaf), turnTelemetrySha256=sha256(leaf / "turn_telemetry.csv"),
                                                resultsSha256=sha256(res_path) if res else None,
                                                note="priced by the thesis runner, which did not record the price per leaf"))
        (out_leaf / "_leaf.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        if complete:
            done.append(f"arm={arm};budget={budget};split={split}")
        total_rows += len(rows)
        echo(f"  {config}/{budget}cu -> {arm}/{budget}cu: {len(rows)} turns"
             + (f", {sum(len(v) for k, v in burns.items() if k in seen)} curve rows" if curve and res else ""))

    if errors:
        for e in errors[:40]:
            echo(f"IMPORT ERROR: {e}")
        if len(errors) > 40:
            echo(f"IMPORT ERROR: ... and {len(errors) - 40} more")
        return 2

    run = OrderedDict(format=1)
    run["magat"] = OrderedDict(version=__version__)
    run["imported"] = OrderedDict(
        **{"from": label or shown(root)}, at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        map=shown(mapping), mapSha256=sha256(mapping),
        note="a thesis run tree (Stage1Runner): no copy or evaluation counts, no initialization split, terminal "
             "turns by the |final| >= 1e9 cutoff")
    run["game"] = OrderedDict(id=game, configuration=OrderedDict(engine="the thesis tree (Tribes-master)"))
    ident = (portfolio / "identity.txt").read_text(encoding="utf-8").split()[0] if (portfolio / "identity.txt").is_file() else "unknown"
    run["portfolio"] = OrderedDict(dir=shown(portfolio), identity=ident)
    run["split"] = split
    if weights:
        w = pathlib.Path(weights)
        run["weights"] = OrderedDict(path=shown(w), sha256=sha256(w),
                                     reference=read_weights(w)["header"].get("reference", "unknown"),
                                     note="the currency the line ran in, in its final state; the runner kept no per-leaf price")
    if experiment:
        e = resolve(experiment)
        spec = json.loads(e.read_text(encoding="utf-8"))
        run["experiment"] = OrderedDict(name=spec.get("name"), path=shown(e), sha256=sha256(e))
        if spec.get("frozen"):
            fz = e.parent / spec["frozen"]
            if fz.is_file():
                run["frozen"] = OrderedDict(path=shown(fz), sha256=sha256(fz))
    (dest / "_run.json").write_text(json.dumps(run, indent=2) + "\n", encoding="utf-8")
    (dest / "_split.txt").write_text(split + "\n", encoding="utf-8")
    (dest / "_completed.txt").write_text("".join(d + "\n" for d in done), encoding="utf-8")
    echo(f"IMPORT: OK - {len(leaves)} leaves, {total_rows:,} turns -> {dest}")
    return 0
