"""A run tree -> one turns frame, with the provenance and the identical-output check.

A split directory is `<run>/<split>/` as the runner writes it: one leaf per `<arm>/<budget>cu/` holding
`turns.csv` (one row per scenario and visit) and `_leaf.json`, plus `_run.json` for the tree. The table
may be gzipped (`turns.csv.gz`), as a published tree may ship it.

A blank `terminal` flag falls back to a magnitude cutoff, |final| >= 1e9 (a detected terminal is worth
about +/-2e9).
"""
import hashlib
import json
import pathlib
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parents[2]          # the repository: analysis/magat_analysis/load.py
TERMINAL_VALUE_CUTOFF = 1e9
SIGNATURE = ["initial", "final", "advances", "copies", "evals", "nodes", "unique"]
TURN_TABLES = ("turns.csv", "turns.csv.gz")


@dataclass
class Split:
    path: pathlib.Path
    name: str                                   # dev, holdout, ... (the tree's _split.txt, else the folder name)
    run: dict                                   # _run.json, or {} when the tree has none
    leaves: list                                # each leaf's _leaf.json, plus "dir"
    turns: pd.DataFrame
    notes: list = field(default_factory=list)

    @property
    def label(self) -> str:
        return self.name

    def currency(self) -> str:
        """The weights file this tree was priced in, as every output row names it."""
        w = self.run.get("weights") or {}
        name = pathlib.Path(str(w.get("path", ""))).name or "unknown currency"
        sha = str(w.get("sha256", ""))[:8]
        return f"{name} ({sha})" if sha else name


def shown(p) -> str:
    """A path for provenance: relative to the repository when inside it, else its last two parts, so a
    published file does not carry the directories of the machine that wrote it."""
    p = pathlib.Path(p).resolve()
    try:
        return p.relative_to(ROOT).as_posix()
    except ValueError:
        return ".../" + "/".join(p.parts[-2:])


def write_text(p, text: str) -> None:
    """Write with LF line endings on every platform: outputs are byte-identical wherever they are written."""
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def resolve(p) -> pathlib.Path:
    """A path as written in a spec or a provenance file: absolute, else relative to the repository."""
    p = pathlib.Path(str(p))
    return p if p.is_absolute() else (ROOT / p)


def first_existing(d: pathlib.Path, names):
    for n in names:
        if (d / n).is_file():
            return d / n
    return None


def is_split_dir(d: pathlib.Path) -> bool:
    return any(first_existing(leaf, TURN_TABLES) for leaf in d.glob("*/*cu") if leaf.is_dir())


def find_splits(path) -> list:
    """The split directories a --run argument names: the directory itself when it holds leaves,
    else its `dev` and `holdout` (and any other) children that do, development first."""
    d = pathlib.Path(path)
    if not d.is_dir():
        raise FileNotFoundError(f"{d} is not a directory")
    if is_split_dir(d):
        return [d]
    kids = [k for k in sorted(d.iterdir()) if k.is_dir() and is_split_dir(k)]
    order = {"dev": 0, "holdout": 1}
    kids.sort(key=lambda k: (order.get(k.name, 2), k.name))
    if not kids:
        raise FileNotFoundError(f"{d} holds no leaves (<arm>/<budget>cu/turns.csv) and no split directories that do")
    return kids


def read_json(p: pathlib.Path) -> dict:
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def load_split(path) -> Split:
    d = pathlib.Path(path)
    run = read_json(d / "_run.json")
    name = (d / "_split.txt").read_text(encoding="utf-8").strip() if (d / "_split.txt").is_file() else ""
    name = name or run.get("split") or d.name
    turns, leaves, notes = [], [], []
    for leaf in sorted(p for p in d.glob("*/*cu") if p.is_dir()):
        meta = read_json(leaf / "_leaf.json")
        meta["dir"] = str(leaf)
        tfile = first_existing(leaf, TURN_TABLES)
        if tfile is None:
            notes.append(f"{leaf.relative_to(d)}: no turns table, skipped")
            continue
        t = pd.read_csv(tfile, dtype={"arm": str, "source": str})
        if t.empty:
            notes.append(f"{leaf.relative_to(d)}: no turns yet, skipped")
            continue
        meta["turns"] = len(t)
        leaves.append(meta)
        turns.append(t)
    if not turns:
        raise FileNotFoundError(f"{d}: no leaf holds any turns")
    t = pd.concat(turns, ignore_index=True)
    t = prepare(t)
    return Split(d, name, run, leaves, t, notes)


def prepare(t: pd.DataFrame) -> pd.DataFrame:
    """Typed keys, the search gain, and the terminal flag (the magnitude cutoff where the flag is blank)."""
    t = t.copy()
    for c in ("scenario", "visit", "budget"):
        t[c] = t[c].astype(int)
    t["arm"] = t["arm"].astype(str)
    t["gain"] = t["final"] - t["initial"]
    flag = pd.to_numeric(t["terminal"], errors="coerce") if "terminal" in t else pd.Series(np.nan, index=t.index)
    t["term"] = np.where(flag.notna(), flag == 1, t["final"].abs() >= TERMINAL_VALUE_CUTOFF)
    return t


# --------------------------------------------------------------------------- identical output
def identical_arms(t: pd.DataFrame) -> list:
    """Pairs of arms whose rows agree on every signature column, turn by turn, at some budget.

    Two arms producing identical output is a bug, not thin data: the runner stops on it and the
    analyzer refuses to analyse it. Columns blank on either side are not compared."""
    found = []
    for budget, d in t.groupby("budget"):
        arms = sorted(d.arm.unique())
        frames = {a: d[d.arm == a].set_index(["scenario", "visit"]) for a in arms}
        for i, a in enumerate(arms):
            for b in arms[i + 1:]:
                x, y = frames[a], frames[b]
                common = x.index.intersection(y.index)
                if len(common) == 0:
                    continue
                cols = [c for c in SIGNATURE if c in x and c in y
                        and x.loc[common, c].notna().all() and y.loc[common, c].notna().all()]
                if not cols:
                    continue
                if (x.loc[common, cols].to_numpy(float) == y.loc[common, cols].to_numpy(float)).all():
                    found.append(dict(budget=int(budget), a=a, b=b, turns=int(len(common)), columns=cols))
    return found


# --------------------------------------------------------------------------- the experiment spec
def load_spec(path) -> dict:
    p = resolve(path)
    x = json.loads(p.read_text(encoding="utf-8"))
    x["_path"] = str(p)
    x["_sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
    x["arms"] = [a if isinstance(a, dict) else {"id": a} for a in x.get("arms", [])]
    return x


def spec_of(split: Split):
    """The spec recorded in the tree's provenance, when the file is still where it was."""
    e = split.run.get("experiment") or {}
    if e.get("path") and resolve(e["path"]).is_file():
        return load_spec(e["path"])
    return None


def comparisons(*specs) -> list:
    """The contrasts the specs declare. Duplicates (the same a and b) are kept once, the first label winning."""
    out, seen = [], set()
    for x in specs:
        if not x:
            continue
        for c in x.get("contrasts", []):
            key = (c["a"], c["b"])
            if key not in seen:
                seen.add(key)
                out.append(dict(a=c["a"], b=c["b"], label=c.get("label", f"{c['a']} - {c['b']}")))
    return out


# --------------------------------------------------------------------------- weights files
FORWARD_MODEL = ("advance", "copy", "eval")


def read_weights(path) -> dict:
    """A MAGAT weights file (version 2): the header, and every profile's prices as {op: weight}, keyed
    advance, copy, eval and the names of the operations the arm declares (profile.<p>.w.<op>). Arm ids
    hold no dots and budgets are digits, so a profile's name ends at the first dot after its '@'."""
    header, prof, glob_ops = {}, {}, {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = (s.strip() for s in line.split("=", 1))
        if k.startswith("profile."):
            rest = k[len("profile."):]
            at = rest.find("@")
            dot = rest.find(".", at) if at >= 0 else -1
            if dot <= 0:
                continue
            name, field = rest[:dot], rest[dot + 1:]
            if field.startswith("w."):
                prof.setdefault(name, {})[field[2:]] = float(v)
        elif k.startswith("global.w."):
            glob_ops[k[len("global.w."):]] = float(v)
        else:
            header[k] = v
    version = str(header.get("magat.weights.version", "")).strip()
    if version != "2":
        raise ValueError(f"{path} is not a version-2 MAGAT weights file (magat.weights.version = {version or 'missing'})")
    profiles = {n: w for n, w in prof.items() if all(o in w for o in FORWARD_MODEL)}
    glob = {"advance": glob_ops.get("advance", 1.0), "copy": glob_ops.get("copy", 0.0), "eval": glob_ops.get("eval", 0.0)}
    glob.update({o: w for o, w in glob_ops.items() if o not in FORWARD_MODEL})
    return dict(path=str(path), name=pathlib.Path(path).name, header=header, version=2, profiles=profiles,
                globals=glob, sha256=hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest())


# --------------------------------------------------------------------------- provenance
def provenance(split: Split, spec) -> dict:
    r = split.run
    game = r.get("game") or {}
    cfg = game.get("configuration") or {}
    w = r.get("weights") or {}
    p = r.get("portfolio") or {}
    out = dict(
        split=split.name,
        path=shown(split.path),
        game=game.get("id", "unknown"),
        engine_commit=cfg.get("engine.commit") or cfg.get("engine") or "unknown",
        portfolio_identity=p.get("identity", "unknown"),
        currency=split.currency(),
        currency_reference=w.get("reference", "unknown"),
        experiment=(spec or {}).get("name") or (r.get("experiment") or {}).get("name", "none"),
        experiment_sha256=(spec or {}).get("_sha256") or (r.get("experiment") or {}).get("sha256", ""),
        magat_commit=(r.get("magat") or {}).get("commit") or (r.get("magat") or {}).get("version") or "unknown",
        toy=bool(r.get("toy", False)),
        leaves=len(split.leaves),
        turns=int(len(split.turns)),
    )
    return out
