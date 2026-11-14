#!/usr/bin/env python3
"""Every table and figure of the paper, from the artefacts this repository publishes and nothing else.

    python magat.py paper            write paper/tables/ and paper/figures/
    python magat.py paper --check    write them to a temporary folder; the tables must equal the committed ones byte
                                     for byte, and every figure must render (figures are not compared: their bytes
                                     depend on the plotting library's version and fonts)

It reads:
- the reference experiment's reports on both machines (games/tribes/results/reference/<machine>/report/tables/),
  for the paired differences and budget multipliers, with the intervals the analysis computed;
- their run trees (games/tribes/results/reference/<machine>/all/), for each position's advantage and each arm's
  spend;
- the comparison of the two (games/tribes/results/reference/r7615-vs-laptop-i9-14900hx/tables/agreement.csv);
- both currencies (games/tribes/calibration/<currency>/compute_operations.csv), for what each machine's work costs
  in its own unit;
- the published levels (games/tribes/levels.json).

Each table is written three ways: .csv (the numbers), .md (for reading) and .tex (a booktabs tabular). Each figure
as .pdf (for the paper) and .png (for reading). paper/README.md says what each one shows.
"""
import json
import pathlib
import shutil
import sys
import tempfile

import numpy as np
import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
from magat_analysis import load  # noqa: E402

GAME = "tribes"
REF = ROOT / "games" / GAME / "results" / "reference"
MACHINES = [  # (published run, its currency, its name in the paper)
    ("r7615", "r7615-time", "R7615"),
    ("laptop-i9-14900hx", "laptop-i9-14900hx-time", "Laptop"),
]
COMPARISON = REF / "r7615-vs-laptop-i9-14900hx"
BASELINE = "emcts"
ARMS = ["mcts", "oep", "tbets"]              # compared with the baseline in the figures; random is in the tables
TABLE_ARMS = ["random", "mcts", "oep", "tbets"]
# The reference palette's first three categorical slots (light): they stay apart for every pair, colour-blind included.
COLOURS = {"mcts": "#2a78d6", "oep": "#eb6834", "tbets": "#1baf7a"}
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
STYLE = {"R7615": dict(marker="o", linestyle="-", fillstyle="full"),
         "Laptop": dict(marker="s", linestyle="--", fillstyle="none")}


# --------------------------------------------------------------------------- inputs
def levels() -> list:
    """[(units, ms)] in order, from the published levels."""
    doc = json.loads((ROOT / "games" / GAME / "levels.json").read_text(encoding="utf-8"))
    return [(int(lv["units"]), int(lv["time_ms"])) for lv in doc["levels"]]


def report_table(machine: str, name: str) -> pd.DataFrame:
    return pd.read_csv(REF / machine / "report" / "tables" / f"{name}.csv")


def turns(machine: str) -> pd.DataFrame:
    return load.load_split(REF / machine / "all").turns


def leaves(machine: str) -> list:
    return load.load_split(REF / machine / "all").leaves


def operations(currency: str) -> pd.DataFrame:
    return pd.read_csv(ROOT / "games" / GAME / "calibration" / currency / "compute_operations.csv")


def reference_nanos(currency: str) -> float:
    for line in (ROOT / "games" / GAME / "calibration" / currency / "compute_weights.properties").read_text(encoding="utf-8").splitlines():
        k, _, v = line.partition("=")
        if k.strip() == "referenceAdvanceNanos":
            return float(v)
    raise ValueError(f"{currency}: no referenceAdvanceNanos")


# --------------------------------------------------------------------------- the numbers
def results() -> pd.DataFrame:
    """Each arm against the baseline, per machine and level: the paired difference on the pairs where neither plan
    ends the game, its 95% interval, the win rate, and the budget multiplier with its interval."""
    rows = []
    for machine, _, label in MACHINES:
        h = report_table(machine, "headline")
        h = h[(h.Stratum == "no-terminal") & (h.Baseline == BASELINE)]
        m = report_table(machine, "budget_multiplier").set_index(["Budget", "Arm"])
        for units, ms in levels():
            for arm in TABLE_ARMS:
                r = h[(h.Budget == units) & (h.Arm == arm)]
                if r.empty:
                    continue
                r = r.iloc[0]
                mult = m.loc[(units, arm)] if (units, arm) in m.index else None
                rows.append(dict(Machine=label, Ms=ms, Units=units, Arm=arm, Pairs=int(r.Pairs), MeanDiff=r.MeanDiff, Lo=r.Lo,
                                 Hi=r.Hi, WinRate=r.WinRate,
                                 Multiplier=np.nan if mult is None else mult.Multiplier,
                                 MultLo=np.nan if mult is None else mult.Lo, MultHi=np.nan if mult is None else mult.Hi,
                                 MultFlag="" if mult is None or pd.isna(mult.Flag) else str(mult.Flag)))
    return pd.DataFrame(rows)


def agreement() -> pd.DataFrame:
    """The two machines, turn by turn and position by position (analyze --compare's agreement table)."""
    a = pd.read_csv(COMPARISON / "tables" / "agreement.csv")
    ms = dict(levels())
    a = a[a.Arm.isin(ARMS)].copy()
    a["Ms"] = a.Budget.map(ms)
    return a[["Ms", "Budget", "Arm", "Turns", "Identical", "ScenCorr", "ScenSameSign", "SameBest"]].rename(columns={"Budget": "Units"})


def prices() -> pd.DataFrame:
    """What each arm's work costs on each machine, in that machine's own unit: the arm's operation mix (its counts in
    the R7615's calibration) at each machine's measured nanoseconds per operation, divided by that machine's unit. The
    shift is how much dearer the same work is, in units, on the laptop than on the R7615; the unit itself, one advance
    of emcts@9500, is the same on both by definition."""
    a, b = (operations(c) for _, c, _ in MACHINES)
    na, nb = (reference_nanos(c) for _, c, _ in MACHINES)
    ms = dict(levels())
    rows = []
    for profile, g in a.groupby("Profile", sort=False):
        h = b[b.Profile == profile].set_index("Op")
        g = g.set_index("Op")
        counts = g.Count.astype(float)
        cost_a = float((counts * g.Nanos).sum() / counts.sum())
        cost_b = float((counts * h.Nanos.reindex(g.index)).sum() / counts.sum())
        arm, units = profile.split("@")
        rows.append(dict(Arm=arm, Ms=ms.get(int(units)), Units=int(units), UnitsPerOpA=cost_a / na, UnitsPerOpB=cost_b / nb,
                         Shift=(cost_b / nb) / (cost_a / na) - 1))
    return ordered(pd.DataFrame(rows), ["Units", "Arm"])


def spend() -> pd.DataFrame:
    """How each arm spends its budget, per machine and level: the share of its units paid for its own operations
    (bookkeeping the forward model does not count), the units spent per turn as a share of the budget, and the
    plan's length. The forward model's part of a turn is its advances, copies and evaluations at the leaf's prices;
    the rest of the units it spent are its own operations'."""
    rows = []
    for machine, _, label in MACHINES:
        t = turns(machine)
        sp = report_table(machine, "spend").set_index(["Budget", "Arm"])
        for meta in leaves(machine):
            arm, units = meta["arm"], int(meta["budget"])
            if arm not in TABLE_ARMS + [BASELINE]:
                continue
            w = meta.get("weights") or [np.nan] * 3
            d = t[(t.arm == arm) & (t.budget == units)]
            fm = ((d.advances + d.init_advances) * w[0] + (d.copies + d.init_copies) * w[1] + (d.evals + d.init_evals) * w[2]).sum()
            total = (d.units + d.init_units).sum()
            s = sp.loc[(units, arm)]
            rows.append(dict(Machine=label, Ms=dict(levels())[units], Units=units, Arm=arm,
                             OwnShare=max(0.0, 1 - fm / total) if total else np.nan, Spend=s.Spend, PlanLength=s.PlanLength))
    return ordered(pd.DataFrame(rows), ["Machine", "Units", "Arm"])


def ordered(d: pd.DataFrame, by: list) -> pd.DataFrame:
    """Rows in the paper's order: the machines as listed, levels rising, the baseline then the arms as listed."""
    rank = {"Machine": {label: i for i, (_, _, label) in enumerate(MACHINES)},
            "Arm": {a: i for i, a in enumerate([BASELINE, *TABLE_ARMS])}}
    return d.sort_values(by, key=lambda s: s.map(rank[s.name]) if s.name in rank else s, kind="stable").reset_index(drop=True)


def per_position() -> pd.DataFrame:
    """Each position's advantage over the baseline (the mean over its visits, on the pairs where neither plan ends
    the game), on each machine, for every arm and level: the points the agreement table's correlation is taken over."""
    out = []
    for machine, _, label in MACHINES:
        t = turns(machine)
        base = t[(t.arm == BASELINE) & ~t.term][["scenario", "visit", "budget", "final"]]
        for arm in ARMS:
            a = t[(t.arm == arm) & ~t.term][["scenario", "visit", "budget", "final"]]
            j = a.merge(base, on=["scenario", "visit", "budget"], suffixes=("", "_b"))
            j["diff"] = j["final"] - j["final_b"]
            g = j.groupby(["budget", "scenario"])["diff"].mean().reset_index()
            g["arm"], g["machine"] = arm, label
            out.append(g)
    d = pd.concat(out, ignore_index=True)
    return d.pivot_table(index=["arm", "budget", "scenario"], columns="machine", values="diff").dropna().reset_index()


# --------------------------------------------------------------------------- writing tables
# The budget multiplier's flags (measures.budget_multiplier): a crossing above the baseline's highest level, and below
# its lowest.
FLAG_MARK = {"extrapolated": "†", "outside": "‡"}


def num(x, spec):
    return "" if x is None or (isinstance(x, float) and not np.isfinite(x)) else format(x, spec)


def mult(x) -> str:
    """A budget multiplier: two decimals, or one significant figure below 0.1 (random's are thousandths)."""
    return "0" if x == 0 else f"{x:.2f}" if x >= 0.1 else f"{x:.1g}"


def interval(m, lo, hi, spec):
    return "-" if not np.isfinite(m) else f"{format(m, spec)} [{format(lo, spec)}, {format(hi, spec)}]"


def write_table(out: pathlib.Path, name: str, frame: pd.DataFrame, header: list, rows: list, caption: str, align: str):
    """frame as .csv (its own columns, fixed decimals), and header/rows (strings) as .md and .tex."""
    (out / "tables").mkdir(parents=True, exist_ok=True)
    frame.to_csv(out / "tables" / f"{name}.csv", index=False, lineterminator="\n", float_format="%.6g")
    md = [f"<!-- {caption} -->", "| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    md += ["| " + " | ".join(r) + " |" for r in rows]
    (out / "tables" / f"{name}.md").write_text("\n".join(md) + "\n", encoding="utf-8", newline="\n")
    esc = lambda s: (s.replace("%", r"\%").replace("&", r"\&").replace("_", r"\_").replace("[", "{[}").replace("]", "{]}")
                     .replace("†", r"$^\dagger$").replace("‡", r"$^\ddagger$"))
    tex = [f"% {caption}", f"\\begin{{tabular}}{{{align}}}", r"\toprule", " & ".join(esc(h) for h in header) + r" \\", r"\midrule"]
    tex += [" & ".join(esc(c) for c in r) + r" \\" for r in rows]
    tex += [r"\bottomrule", r"\end{tabular}"]
    (out / "tables" / f"{name}.tex").write_text("\n".join(tex) + "\n", encoding="utf-8", newline="\n")


def tables(out: pathlib.Path):
    lv = levels()
    write_table(out, "levels", pd.DataFrame([dict(Ms=ms, Units=u) for u, ms in lv]), ["Thinking time on the R7615", "Units"],
                [[f"{ms:,} ms", f"{u:,}"] for u, ms in lv],
                "The budget levels: units that are this much thinking time on the reference machine.", "rr")

    r = results()
    rows = []
    for (ms, arm), g in r.groupby(["Ms", "Arm"], sort=False):
        row = [f"{ms:,} ms", arm]
        for _, _, label in MACHINES:
            x = g[g.Machine == label].iloc[0]
            row += [interval(x.MeanDiff, x.Lo, x.Hi, ".1f"),
                    (f"{mult(x.Multiplier)} [{mult(x.MultLo)}, {mult(x.MultHi)}]" if np.isfinite(x.Multiplier) else "-")
                    + FLAG_MARK.get(x.MultFlag, "")]
        rows.append(row)
    header = ["Level", "Arm"] + [h for _, _, label in MACHINES for h in (f"{label}: difference", f"{label}: multiplier")]
    write_table(out, "results", r, header, rows,
                "Each arm against emcts: the paired difference in plan value on the pairs where neither plan ends the game, "
                "and the budget multiplier, with scenario-clustered bootstrap 95% intervals. A multiplier marked † crosses "
                "emcts's curve above its highest level, so it is extrapolated and a lower bound; one marked ‡ crosses below "
                "its lowest level.", "ll" + "rr" * len(MACHINES))

    a = agreement()
    write_table(out, "agreement", a, ["Level", "Arm", "Same turn", "Advantage correlation", "Same sign", "Same best arm"],
                [[f"{x.Ms:,} ms", x.Arm, f"{100 * x.Identical:.1f}%", f"{x.ScenCorr:.3f}", f"{100 * x.ScenSameSign:.1f}%",
                  f"{100 * x.SameBest:.1f}%"] for x in a.itertuples()],
                "The two machines position by position: the share of turns identical on both; across positions, the "
                "correlation of each arm's advantage over emcts and the share with the same sign; the share of positions "
                "on which both name the same best arm.", "llrrrr")

    p = prices()
    write_table(out, "prices", p, ["Arm", "Level", "R7615 (units per operation)", "Laptop (units per operation)", "Laptop against R7615"],
                [[x.Arm, f"{x.Ms:,} ms", f"{x.UnitsPerOpA:.4f}", f"{x.UnitsPerOpB:.4f}", f"{100 * x.Shift:+.1f}%"] for x in p.itertuples()],
                "What each arm's work costs in each machine's own unit: its operation mix at that machine's measured prices. "
                "A positive shift is work the laptop finds dearer than the R7615 does.", "llrrr")

    s = spend()
    write_table(out, "spend", s, ["Machine", "Level", "Arm", "Own operations", "Spend", "Plan length"],
                [[x.Machine, f"{x.Ms:,} ms", x.Arm, f"{100 * x.OwnShare:.1f}%", f"{x.Spend:.3f}", f"{x.PlanLength:.1f}"]
                 for x in s.itertuples()],
                "How each arm spends its budget: the share of a turn's units, its initialization included, paid for the "
                "arm's own operations rather than the forward model's; the units spent per turn as a share of the budget; and "
                "the actions in its plan. random is not priced: it spends no units of its own.", "lllrrr")
    return r, a, p


# --------------------------------------------------------------------------- figures
def style(plt):
    plt.rcParams.update({"font.size": 9, "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED,
                         "ytick.color": MUTED, "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 9,
                         "legend.frameon": False, "svg.hashsalt": "magat", "pdf.fonttype": 42})


def save(fig, out: pathlib.Path, name: str):
    (out / "figures").mkdir(parents=True, exist_ok=True)
    fig.savefig(out / "figures" / f"{name}.pdf", metadata={"CreationDate": None, "Producer": "MAGAT"})
    fig.savefig(out / "figures" / f"{name}.png", dpi=200)


def legend(ax, plt):
    from matplotlib.lines import Line2D
    handles = [Line2D([], [], color=COLOURS[a], marker="o", linestyle="-", label=a) for a in ARMS]
    handles += [Line2D([], [], color=MUTED, label=label, **STYLE[label]) for _, _, label in MACHINES]
    ax.legend(handles=handles, loc="upper left", bbox_to_anchor=(1.01, 1.0), fontsize=8)


def by_level(plt, r: pd.DataFrame, out: pathlib.Path, value: str, lo: str, hi: str, name: str, ylabel: str, log=False):
    lv = levels()
    fig, ax = plt.subplots(figsize=(6.0, 3.2))
    for k, (_, _, label) in enumerate(MACHINES):
        for arm in ARMS:
            g = r[(r.Machine == label) & (r.Arm == arm)].set_index("Units").reindex([u for u, _ in lv])
            x = np.arange(len(lv)) + (k - 0.5) * 0.12 + (ARMS.index(arm) - 1) * 0.03
            y = g[value].to_numpy(float)
            err = [np.clip(y - g[lo].to_numpy(float), 0, None), np.clip(g[hi].to_numpy(float) - y, 0, None)]
            ax.errorbar(x, y, yerr=err, color=COLOURS[arm], capsize=2, linewidth=1.5, markersize=5, elinewidth=1,
                        markeredgewidth=1.2, **STYLE[label])
    ax.axhline(1 if log else 0, color=MUTED, linewidth=0.8)
    if log:
        ax.set_yscale("log")
    ax.set_xticks(range(len(lv)))
    ax.set_xticklabels([f"{ms:,} ms\n{u:,} units" for u, ms in lv])
    ax.set_xlabel("budget level")
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", color=GRID, linewidth=0.6)
    legend(ax, plt)
    fig.tight_layout()
    save(fig, out, name)
    plt.close(fig)


def figures(out: pathlib.Path, r: pd.DataFrame, p: pd.DataFrame):
    try:
        import matplotlib
    except ImportError:
        raise SystemExit("the figures need matplotlib: python -m pip install -r requirements.txt")
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    style(plt)
    by_level(plt, r, out, "MeanDiff", "Lo", "Hi", "difference_by_level", "difference from emcts (plan value)")
    by_level(plt, r[np.isfinite(r.Multiplier)], out, "Multiplier", "MultLo", "MultHi", "multiplier_by_level",
             "budget multiplier against emcts", log=True)

    # Each position's advantage on one machine against the other's, one panel per level.
    d = per_position()
    lv = levels()
    fig, axes = plt.subplots(1, len(lv), figsize=(7.2, 2.8), sharex=True, sharey=True)
    lim = np.nanpercentile(np.abs(d[["R7615", "Laptop"]].to_numpy()), 99.5)
    for ax, (u, ms) in zip(axes, lv):
        g = d[d.budget == u]
        for arm in ARMS:
            h = g[g.arm == arm]
            ax.scatter(h.R7615, h.Laptop, s=8, color=COLOURS[arm], alpha=0.55, linewidths=0, label=arm)
        ax.plot([-lim, lim], [-lim, lim], color=MUTED, linewidth=0.8)
        ax.set_xlim(-lim, lim)
        ax.set_ylim(-lim, lim)
        ax.set_title(f"{ms:,} ms")
        ax.set_xlabel("R7615")
        ax.grid(color=GRID, linewidth=0.5)
    axes[0].set_ylabel("Laptop")
    axes[-1].legend(loc="upper left", bbox_to_anchor=(1.02, 1.0), fontsize=8, markerscale=1.8)
    fig.suptitle("Each position's advantage over emcts, on each machine", fontsize=9)
    fig.tight_layout()
    save(fig, out, "per_position")
    plt.close(fig)

    # What the laptop's unit buys of each arm's work, against the R7615's.
    q = p.copy()
    q["label"] = [f"{a} @ {ms:,} ms" for a, ms in zip(q.Arm, q.Ms)]
    fig, ax = plt.subplots(figsize=(5.2, 0.24 * len(q) + 0.9))
    ys = np.arange(len(q))[::-1]
    ax.hlines(ys, 0, 100 * q.Shift, color=GRID, linewidth=2)
    ax.plot(100 * q.Shift, ys, "o", color=INK, markersize=5)
    ax.axvline(0, color=MUTED, linewidth=0.8)
    ax.set_yticks(ys)
    ax.set_yticklabels(q.label, fontsize=8)
    ax.set_xlabel("dearer on the laptop than on the R7615, in units (%)")
    ax.grid(axis="x", color=GRID, linewidth=0.6)
    fig.tight_layout()
    save(fig, out, "price_shift")
    plt.close(fig)


# --------------------------------------------------------------------------- main
def build(out: pathlib.Path):
    r, _, p = tables(out)
    figures(out, r, p)


def main(argv) -> int:
    out = ROOT / "paper"
    if "--check" not in argv:
        build(out)
        print(f"PAPER: tables and figures written to {out}")
        return 0
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="magat-paper-"))
    try:
        build(tmp)
        stale = []
        for f in sorted((tmp / "tables").iterdir()):
            committed = out / "tables" / f.name
            if not committed.is_file() or committed.read_bytes() != f.read_bytes():
                stale.append(f.name)
        extra = sorted(f.name for f in (out / "tables").iterdir() if not (tmp / "tables" / f.name).exists()) if (out / "tables").is_dir() else []
        made = sorted(f.name for f in (tmp / "figures").iterdir())
        missing = sorted(f.name for f in (out / "figures").iterdir() if f.name not in made) if (out / "figures").is_dir() else []
        print(f"  tables: {len(list((tmp / 'tables').iterdir()))} rebuilt, " + (f"{len(stale)} differ: {', '.join(stale)}" if stale else "every one equal to the committed one"))
        print(f"  figures: {len(made)} rendered" + (f"; committed but no longer made: {', '.join(missing)}" if missing else ""))
        if extra:
            print(f"  committed tables no longer made: {', '.join(extra)}")
        ok = not stale and not extra and not missing
        print("PAPER: " + ("CURRENT" if ok else "STALE - run `python magat.py paper` and commit paper/"))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
