"""Whether a run reproduces another, turn by turn: `magat check reproduction`.

The same code at the same prices, on the same positions and seeds, plays the same turn on any machine. So every
turn the two runs both hold, matched by arm, budget, scenario and visit, is compared in every field the two record
but the times (plan_ms, init_ms), which are the machine's. An arm or a level the other run never played is listed,
not compared; so is a turn it holds no counterpart of.

Exit codes: 0 every compared turn identical, 1 a turn differs, 2 nothing to compare or bad input.
"""
import pathlib

import pandas as pd

from . import load

ROW_KEYS = ["arm", "budget", "scenario", "visit"]


def compare(ta: pd.DataFrame, tb: pd.DataFrame) -> pd.DataFrame:
    """One row per arm and budget of `ta`: its turns, how many `tb` holds too, how many of those are identical in
    every field both record but the times, and the fields that differ most often."""
    rows = []
    for (arm, budget), a in ta.groupby(["arm", "budget"], sort=True):
        b = tb[(tb.arm == arm) & (tb.budget == budget)]
        row = dict(Arm=arm, Budget=int(budget), Turns=len(a), Compared=0, Identical=0, Unmatched=len(a), Differs="", Note="")
        if b.empty:
            row["Note"] = "not in the other run"
            rows.append(row)
            continue
        a, b = a.dropna(axis=1, how="all"), b.dropna(axis=1, how="all")
        ignored = set(ROW_KEYS) | set(load.TIME_COLUMNS)
        fields = [c for c in a.columns if c in b.columns and c not in ignored]
        only = sorted((set(a.columns) ^ set(b.columns)) - ignored)
        j = a[ROW_KEYS + fields].merge(b[ROW_KEYS + fields], on=ROW_KEYS, suffixes=("", "_other"))
        same = pd.Series(True, index=j.index)
        differs = {}
        for c in fields:
            x, y = j[c], j[c + "_other"]
            eq = (x == y) | (x.isna() & y.isna())
            if not eq.all():
                differs[c] = int((~eq).sum())
            same &= eq
        row.update(Compared=len(j), Identical=int(same.sum()), Unmatched=len(a) - len(j),
                   Differs=", ".join(f"{c} ({n})" for c, n in sorted(differs.items(), key=lambda kv: (-kv[1], kv[0]))[:3]),
                   Note=("recorded by one run only: " + ", ".join(only)) if only else "")
        rows.append(row)
    return pd.DataFrame(rows, columns=["Arm", "Budget", "Turns", "Compared", "Identical", "Unmatched", "Differs", "Note"])


def verdict(table: pd.DataFrame) -> tuple:
    """(exit code, the verdict line) over every compared turn."""
    compared, identical = int(table.Compared.sum()), int(table.Identical.sum())
    if compared == 0:
        return 2, "REPRODUCTION: NOTHING TO COMPARE - the two runs share no turn of any arm at any level"
    if identical == compared:
        return 0, f"REPRODUCTION: PASS - {compared} of {compared} turns identical in every field but the times"
    return 1, f"REPRODUCTION: FAIL - {compared - identical} of {compared} turns differ (the Differs column names the fields)"


def main(run: str, against: str, out: str = None) -> int:
    try:
        others = load.find_splits(against)
        if len(others) != 1:
            print(f"--against {against} holds {len(others)} halves: name one of them, its directory")
            return 2
        other = load.load_split(others[0])
        mine = [load.load_split(d) for d in load.find_splits(run)]
    except FileNotFoundError as e:
        print(f"error: {e}")
        return 2
    print(f"# against {other.path} ({other.name}, {len(other.turns)} turns)")
    tables = []
    for sp in mine:
        t = compare(sp.turns, other.turns)
        print(f"\n## {sp.name}: {sp.path}")
        print(t.to_string(index=False))
        tables.append(t.assign(Split=sp.name))
    table = pd.concat(tables, ignore_index=True)
    unmatched = int(table.loc[table.Compared > 0, "Unmatched"].sum())
    if unmatched:
        print(f"\nnote: {unmatched} turn(s) of the arms both ran have no counterpart in the other run, so are not compared")
    if out:
        p = pathlib.Path(out)
        p.parent.mkdir(parents=True, exist_ok=True)
        table[["Split", *[c for c in table.columns if c != "Split"]]].to_csv(p, index=False, lineterminator="\n")
        print(f"\nwrote {p}")
    code, line = verdict(table)
    print("\n" + line)
    return code
