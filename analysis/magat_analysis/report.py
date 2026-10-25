"""`magat analyze`: a run tree in, tables + SUMMARY.md + report.json out.

SUMMARY.md order: provenance; the headline in units and seconds, with the budget multiplier and d beside
it; the multiplier in full; the spec's contrasts; spend, time and plans; the design (variance); re-pricing;
across machines; scope notes.
report.json holds the same numbers for machines. Every table carries the split it came from and the
currency it is denominated in.
"""
import json
import pathlib

import numpy as np
import pandas as pd

from . import __version__, load, measures
from .stats import N_BOOT

TABLES = ("headline", "budget_multiplier", "wallclock", "contrasts", "variance", "terminals", "spend")
EXIT_OK, EXIT_INPUT, EXIT_IDENTICAL = 0, 2, 5
# The header a table is written with when it has no rows (a single-level baseline, no contrasts), so that
# every table is a CSV with columns whatever the run held.
EMPTY = {
    "headline": "Split Budget Arm Baseline Stratum Pairs Scenarios MeanDiff Lo Hi D RelDiff RelLo RelHi Wins Losses Ties "
                "WinRate TermArm TermBase currency",
    "budget_multiplier": "Split Budget Arm Baseline Cells Scenarios ArmFinal BaseFinals Multiplier Lo Hi EquivBudget Flag "
                         "BootUndefined ArmMs BaseMs ArmTimeRatio TimeMultiplier TimeLo TimeHi currency",
    "wallclock": "Split Budget Arm Baseline Pairs UnitsDiff Scenarios WallDiff WallLo WallHi ClampedShare MeanArmMillis "
                 "Verdict currency",
    "contrasts": "Split Budget Label A B Stratum Pairs Scenarios MeanDiff Lo Hi D WinRate currency",
    "variance": "Split Budget Arm Stratum Pairs Clusters MeanVisits SigmaB2 SigmaW2 ICC SE SEFloor ExcessOverFloorPct "
                "VisitsWithin5Pct EquivClustersAtV1 TurnsUsed TurnsEquiv currency",
    "terminals": "Split Budget Arm Turns TerminalShare currency",
    "spend": "Split Budget Arm Turns Spend SpendMax OverShare MeanMillis LevelMillis PlanLength TerminalShare AuditUnpriced "
             "Threads currency",
    "repriced": "Split Budget Arm Baseline Pairs Scenarios RepricedDiff Lo Hi ClampedShare MeanRepricedUnits currency",
    "repriced_spend": "Split Budget Arm Turns MeanUnits MeanRepricedUnits Ratio currency",
    "across": "Split Budget Arm Pairs Scenarios MeanA LoA HiA MeanB LoB HiB Diff DiffLo DiffHi MultA MultALo MultAHi "
              "MultB MultBLo MultBHi SecondsA SecondsB RepricedRatio currency",
}


# --------------------------------------------------------------------------- number formatting
def f(x, spec="+.1f") -> str:
    return "-" if x is None or not np.isfinite(x) else format(float(x), spec)


def ci(m, lo, hi, spec="+.1f") -> str:
    sig = np.isfinite(lo) and np.isfinite(hi) and (lo > 0 or hi < 0)
    s = f"{f(m, spec)} [{f(lo, spec)}, {f(hi, spec)}]"
    return f"**{s}**" if sig else s


def interval(m, lo, hi, spec=".1f") -> str:
    """A value and its interval, unmarked (a mean is not tested against zero)."""
    return f"{f(m, spec)} [{f(lo, spec)}, {f(hi, spec)}]"


def mult(m, lo, hi) -> str:
    if m is None or not np.isfinite(m):
        return "-"
    sig = np.isfinite(lo) and np.isfinite(hi) and (lo > 1 or hi < 1)
    s = f"{m:.1f}x [{f(lo, '.1f')}x, {f(hi, '.1f')}x]"
    return f"**{s}**" if sig else s


def table(header, rows) -> list:
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out + [""]


# --------------------------------------------------------------------------- one split
def analyse_split(sp: load.Split, baseline: str, pairs: list, n: int, weights=None) -> dict:
    t = sp.turns
    out = {}
    out["headline"] = measures.headline(t, baseline, sp.name, n)
    out["budget_multiplier"] = measures.budget_multiplier(t, baseline, sp.name, n)
    out["wallclock"] = measures.wallclock(t, baseline, out["headline"], sp.name, n)
    out["contrasts"] = measures.contrasts(t, pairs, sp.name, n)
    out["variance"] = measures.variance(t, baseline, sp.name)
    out["terminals"] = measures.terminal_rates(t)
    out["spend"] = measures.spend(t, sp.leaves, load.levels((sp.run.get("game") or {}).get("id"))["ms"])
    if weights is not None:
        out["repriced"], out["repriced_spend"], notes = measures.reprice(t, sp.leaves, weights, baseline, sp.name, n)
        sp.notes.extend(notes)
    return out


def stamp(df: pd.DataFrame, split: str, currency: str) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    df = df.copy()
    df.insert(0, "Split", split)
    df["currency"] = currency
    return df


# --------------------------------------------------------------------------- entry point
def analyze(run, out, experiment=None, baseline=None, reprice=None, compare=None, compare_weights=None, n=N_BOOT,
            figures=True, echo=print) -> int:
    try:
        splits = [load.load_split(p) for p in load.find_splits(run)]
        others = {s.name: s for s in (load.load_split(p) for p in load.find_splits(compare))} if compare else {}
    except FileNotFoundError as e:
        echo(f"ANALYZE: {e}")
        return EXIT_INPUT
    for s in splits:
        o = others.get(s.name)
        if o is not None and o.run.get("portfolio", {}).get("identity") != s.run.get("portfolio", {}).get("identity"):
            echo(f"ANALYZE: {s.name}: the run and --compare were played on different portfolios, so their turns do not pair")
            return EXIT_INPUT
    spec_given = load.load_spec(experiment) if experiment else None
    specs = {s.name: spec_given or load.spec_of(s) for s in splits}
    baseline = baseline or next((x.get("baseline") for x in specs.values() if x and x.get("baseline")), None)
    if not baseline:
        echo("ANALYZE: no baseline: pass --baseline, or --experiment with a spec that names one")
        return EXIT_INPUT
    weights = load.read_weights(reprice) if reprice else None
    other_weights, compare_notes = None, []
    if others:
        found = compare_weights or currency_file(next(iter(others.values())))
        if found:
            other_weights = load.read_weights(found)
        else:
            compare_notes.append("the --compare run's currency was not found by its recorded path or among the published "
                                 "currencies, so its turns are not re-priced: name it with --compare-weights")

    for s in splits:
        echo(f"[analyze] {s.name}: {len(s.turns):,} turns in {len(s.leaves)} leaves from {s.path} | "
             f"arms {len(s.turns.arm.unique())} | budgets {sorted(int(b) for b in s.turns.budget.unique())} | "
             f"currency {s.currency()}")
    bad = False
    for s in splits:
        for d in load.identical_arms(s.turns):
            echo(f"ANALYZE: IDENTICAL OUTPUT in {s.name} at {d['budget']}: {d['a']} and {d['b']} agree on "
                 f"{', '.join(d['columns'])} for all {d['turns']} shared turns. Two arms producing identical output "
                 f"is a bug (a renamed duplicate, a parameter that is not wired), not thin data.")
            bad = True
    if bad:
        return EXIT_IDENTICAL

    pairs = load.comparisons(*specs.values())
    results = {}
    for s in splits:
        if not (s.turns.arm == baseline).any():
            s.notes.append(f"{s.name}: the baseline {baseline} has no rows, so nothing can be paired")
        results[s.name] = analyse_split(s, baseline, pairs, n, weights)
        o = others.get(s.name)
        if others and o is None:
            s.notes.append(f"{s.name}: the --compare run has no {s.name} split, so it is not compared")
        elif o is not None:
            results[s.name]["across"], notes = measures.across(s.turns, o.turns, baseline, s.name, n, s.leaves, other_weights)
            s.notes.extend(notes)

    out = pathlib.Path(out)
    (out / "tables").mkdir(parents=True, exist_ok=True)
    names = list(TABLES) + (["repriced", "repriced_spend"] if weights else []) + (["across"] if others else [])
    combined = {}
    for name in names:
        cur = (weights["name"] + f" ({weights['sha256'][:8]})") if name.startswith("repriced") else None
        parts = [stamp(results[s.name].get(name), s.name, cur or s.currency()) for s in splits]
        parts = [p for p in parts if not p.empty]
        df = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
        combined[name] = df
        if df.empty:
            df = pd.DataFrame(columns=EMPTY[name].split())
        df.to_csv(out / "tables" / f"{name}.csv", index=False, float_format="%.4f", lineterminator="\n")
    if figures:
        draw(out, splits, results)
    prov = {s.name: load.provenance(s, specs[s.name]) for s in splits}
    prov_b = {k: load.provenance(o, load.spec_of(o)) for k, o in others.items()}
    for s in splits:
        s.notes.extend(compare_notes)
    summary = render(splits, specs, baseline, results, prov, weights, n, prov_b, other_weights)
    load.write_text(out / "SUMMARY.md", summary)
    write_json(out / "report.json", dict(
        magat_analysis=__version__, baseline=baseline, bootstrap_resamples=n,
        reprice=None if not weights else dict(path=weights["path"], sha256=weights["sha256"]),
        compare=None if not others else dict(provenance=prov_b, currency=None if not other_weights else
                                             dict(path=other_weights["path"], sha256=other_weights["sha256"])),
        provenance=prov, notes={s.name: s.notes for s in splits},
        tables={k: records(v) for k, v in combined.items()}))
    for s in splits:
        for note in s.notes:
            echo(f"  note: {note}")
    echo(f"ANALYZE: OK - {len(combined)} tables ({sum(not v.empty for v in combined.values())} with rows), "
         f"SUMMARY.md and report.json in {out}")
    return EXIT_OK


def currency_file(split: load.Split):
    """The currency a run tree was priced in: its recorded path if the file is there and is the one recorded,
    else a published currency with the recorded hash; None if neither."""
    w = split.run.get("weights") or {}
    sha = str(w.get("sha256", ""))
    p = load.resolve(w["path"]) if w.get("path") else None
    if p is not None and p.is_file() and (not sha or load.sha256(p) == sha):
        return p
    for q in sorted(load.ROOT.glob("games/*/calibration/*/compute_weights.properties")):
        if sha and load.sha256(q) == sha:
            return q
    return None


def records(df: pd.DataFrame) -> list:
    if df is None or df.empty:
        return []
    return json.loads(df.to_json(orient="records", double_precision=10))


def write_json(p: pathlib.Path, obj) -> None:
    load.write_text(p, json.dumps(obj, indent=1, default=lambda o: o.item() if hasattr(o, "item") else str(o)) + "\n")


# --------------------------------------------------------------------------- SUMMARY.md
def render(splits, specs, baseline, results, prov, weights, n, prov_b=None, other_weights=None) -> str:
    names = [s.name for s in splits]
    spec_names = list(dict.fromkeys(x["name"] for x in specs.values() if x and x.get("name")))
    title = " + ".join(spec_names) or splits[0].path.name
    L = [f"# {title}: MAGAT analysis", "",
         f"Splits: {', '.join(names)}. Baseline `{baseline}`. A positive difference means the arm finds a better",
         "final sequence than the baseline on the same (scenario, visit) seeds; intervals are scenario-clustered",
         f"bootstrap 95% CIs ({n:,} resamples), bold where they exclude zero.", ""]

    L += ["## Provenance", ""]
    keys = [("game", "game"), ("engine commit", "engine_commit"), ("portfolio identity", "portfolio_identity"),
            ("currency", "currency"), ("currency reference", "currency_reference"), ("experiment", "experiment"),
            ("experiment sha256", "experiment_sha256"), ("leaves / turns", None), ("written by MAGAT", "magat_commit")]
    rows = []
    for label, k in keys:
        if k is None:
            rows.append([label] + [f"{prov[s]['leaves']} / {prov[s]['turns']:,}" for s in names])
        else:
            rows.append([label] + [str(prov[s].get(k) or "-") for s in names])
    for s in names:
        if prov[s].get("toy"):
            rows.append([f"toy ({s})", "a toy run: measures nothing"] + [""] * (len(names) - 1))
    L += table(["", *names], rows)

    for s in splits:
        L += headline_section(s.name, results[s.name], baseline)
    for s in splits:
        L += multiplier_section(s.name, results[s.name], baseline)
    for s in splits:
        L += contrast_section(s.name, results[s.name])
    for s in splits:
        L += spend_section(s.name, results[s.name], load.levels(prov[s.name]["game"]))
    for s in splits:
        L += variance_section(s.name, results[s.name])
    if weights is not None:
        for s in splits:
            L += reprice_section(s.name, results[s.name], weights)
    for s in splits:
        if "across" in results[s.name]:
            L += across_section(s.name, results[s.name], prov[s.name], (prov_b or {}).get(s.name), other_weights)
    L += scope_section(splits, results, weights)
    L += ["Full tables in `tables/` (every row names its split and its currency), the same numbers in "
          "`report.json`, figures in `figures/` when matplotlib is installed.", ""]
    return "\n".join(L)


def by(df, **kw):
    if df is None or df.empty:
        return pd.DataFrame()
    for k, v in kw.items():
        df = df[df[k] == v]
    return df


def headline_section(split, R, baseline) -> list:
    h = R["headline"]
    if h.empty:
        return [f"## Headline ({split})", "", f"Nothing paired against `{baseline}` here.", ""]
    L = [f"## Headline ({split}): units and seconds", "",
         "Units: the paired difference against the baseline, on the pairs where neither plan ends the game. A plan",
         "that ends it scores a sentinel (about +/-2e9) that would swamp any mean, so those pairs are counted apart",
         "(`Ends the game`: the arm's plans / the baseline's) and the mean over all pairs stands beside it. Seconds:",
         "the arm re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.",
         "The budget multiplier is the readable size; `MeanDiff` itself is the raw heuristic delta, never a",
         "result on its own.", ""]
    wc = R["wallclock"].set_index(["Budget", "Arm"]) if not R["wallclock"].empty else None
    bm = R["budget_multiplier"].set_index(["Budget", "Arm"]) if not R["budget_multiplier"].empty else None
    for budget in sorted(h.Budget.unique()):
        rows = []
        for x in by(h, Budget=budget, Stratum="all").itertuples(index=False):
            k = (budget, x.Arm)
            nt = by(h, Budget=budget, Arm=x.Arm, Stratum="no-terminal")
            y = nt.iloc[0] if not nt.empty else None
            srcs = "-" if y is None else ", ".join(f"{c[len('MeanDiff_'):]} {f(y[c])}" for c in h.columns
                                                   if c.startswith("MeanDiff_") and np.isfinite(y.get(c, np.nan)))
            ends = f"{x.TermArm} / {x.TermBase}" if x.TermArm or x.TermBase else "-"
            wall, verdict = "-", "-"
            if wc is not None and k in wc.index:
                w = wc.loc[k]
                clamp = f" (clamped {w.ClampedShare:.0%})" if w.ClampedShare > 0.05 else ""
                wall, verdict = ci(w.WallDiff, w.WallLo, w.WallHi) + clamp, w.Verdict
            mm = "-"
            if bm is not None and k in bm.index:
                b = bm.loc[k]
                mm = mult(b.Multiplier, b.Lo, b.Hi) + ("" if b.Flag == "inside" else f" ({b.Flag})")
            rows.append([x.Arm, 0 if y is None else y.Pairs, "-" if y is None else ci(y.MeanDiff, y.Lo, y.Hi),
                         f(x.MeanDiff), ends, srcs, wall, verdict, mm, f(np.nan if y is None else y.D, ".2f")])
        L += [f"### {budget:,} units", ""]
        L += table(["Arm", "Pairs", "MeanDiff [CI]", "All pairs", "Ends the game", "By source", "Wall-clock [CI]",
                    "Verdict", "Budget multiplier", "d"], rows)
    return L


def multiplier_section(split, R, baseline) -> list:
    b = R["budget_multiplier"]
    if b.empty:
        return [f"## Budget multiplier ({split})", "",
                f"Not computable: `{baseline}` ran at fewer than two budgets here, so it has no curve to read against.", ""]
    L = [f"## Budget multiplier ({split})", "",
         f"How much budget `{baseline}` would need to match each arm: the baseline's mean plan value per level,",
         "interpolated log-linearly in budget, solved for the arm's mean value, over the cells where every baseline",
         "level and the arm ran and none ended terminal (values, not search gains: each arm's `initial` is its own",
         "starting plan). `extrapolated` means the crossing lies past the top of the ladder: a lower bound, since",
         "value is concave in budget. The wall-clock column solves the same crossing in the baseline's own seconds",
         "and divides by the arm's; `arm/baseline time` is the arm's plan time over the baseline's at the same budget.", ""]
    base = b.iloc[0]
    lv = [p.split(":") for p in base.BaseFinals.split(";")]
    ms = [p.split(":") for p in base.BaseMs.split(";")]
    if len(lv) >= 2:
        step = float(lv[-1][0]) / float(lv[-2][0])
        tstep = float(ms[-1][1]) / float(ms[-2][1]) if float(ms[-2][1]) > 0 else np.nan
        L += [f"The baseline's ladder (on the first arm's cells): " + ", ".join(f"{int(a):,}: value {float(g):.1f}"
              for a, g in lv) + f". Its top step is {step:.1f}x the units and {f(tstep, '.1f')}x the seconds.", ""]
    rows = [[x.Budget, x.Arm, x.Cells, f(x.ArmFinal, ".1f"), mult(x.Multiplier, x.Lo, x.Hi), x.Flag,
             mult(x.TimeMultiplier, x.TimeLo, x.TimeHi), f(x.ArmTimeRatio, ".2f")]
            for x in b.itertuples(index=False)]
    return L + table(["Budget", "Arm", "Cells", "Arm value", "Multiplier [CI]", "Where", "Wall-clock multiplier [CI]",
                      "Arm/baseline time"], rows)


def contrast_section(split, R) -> list:
    c = R["contrasts"]
    if c.empty:
        return []
    L = [f"## Contrasts ({split})", "", "The spec's own paired comparisons, arm A minus arm B, on the pairs where neither",
         "plan ends the game; the mean over all pairs beside it.", ""]
    rows = []
    for x in by(c, Stratum="all").itertuples(index=False):
        nt = by(c, Budget=x.Budget, A=x.A, B=x.B, Stratum="no-terminal")
        y = nt.iloc[0] if not nt.empty else None
        rows.append([x.Budget, f"{x.A} - {x.B}", x.Label, 0 if y is None else y.Pairs,
                     "-" if y is None else ci(y.MeanDiff, y.Lo, y.Hi), f(x.MeanDiff),
                     "-" if y is None else f"{y.WinRate:.3f}"])
    return L + table(["Budget", "A - B", "Label", "Pairs", "MeanDiff [CI]", "All pairs", "WinRate"], rows)


def spend_section(split, R, levels) -> list:
    sp = R.get("spend")
    if sp is None or sp.empty:
        return []
    L = [f"## Spend, time and plans ({split})", "",
         f"`Spend` is the mean turn's units, its initialization included, over the level: bold above "
         f"{measures.SPEND_FLAG:.2f}x, as the calibration flags a cell, with the share of turns above it beside it.",
         "`Seconds` is the mean turn's wall-clock"
         + (f"; `Level` is the time the level was set to, the {levels['arm']} turn's mean, single-threaded on "
            f"{levels['machine']} (`levels.json`)" if levels.get("ms") else "") + ". `Unaccounted` is the run's audit: the share",
         "of the plan time that the currency's prices do not account for, on the threads the leaf ran on. On many",
         "threads it holds contention as well, and turns take longer than their level, unevenly across arms: the",
         "calibration's own audit, on one idle thread, is the clean measure. `Actions` is the mean plan's length, its",
         "end of turn included; `Ends the game` the share of plans that do.", ""]
    rows = []
    for x in sp.itertuples(index=False):
        s = f"{x.Spend:.2f}x"
        rows.append([x.Budget, x.Arm, x.Turns, f"**{s}**" if x.Spend > measures.SPEND_FLAG else s, f"{x.OverShare:.0%}",
                     f(x.MeanMillis / 1000.0, ".2f"), f(x.LevelMillis / 1000.0, ".2f"), f(x.PlanLength, ".1f"),
                     f"{x.TerminalShare:.1%}",
                     "-" if not np.isfinite(x.AuditUnpriced) else f"{x.AuditUnpriced:.0%}"
                     + ("" if not np.isfinite(x.Threads) else f" on {int(x.Threads)}")])
    return L + table(["Budget", "Arm", "Turns", "Spend", f"Over {measures.SPEND_FLAG:.2f}x", "Seconds", "Level",
                      "Actions", "Ends the game", "Unaccounted (threads)"], rows)


def variance_section(split, R) -> list:
    v = by(R["variance"], Stratum="no-terminal")
    if v.empty:
        return []
    L = [f"## Design ({split}): scenarios versus visits", "",
         "On the pairs where neither plan ends the game, as the headline is.",
         "ICC is the share of the paired difference's variance that sits between scenarios. The floor is the",
         "standard error this scenario count can reach with unlimited visits; `Excess` is how far the design",
         "actually run sits above it; `=Scen@1v` is the number of scenarios at one visit each that would match",
         "the same precision.", ""]
    rows = []
    for x in v.itertuples(index=False):
        rows.append([x.Budget, x.Arm, x.Clusters, f"{x.MeanVisits:.1f}", f"{x.ICC:.3f}", f"{x.SE:.1f}",
                     f"{x.SEFloor:.1f}", "-" if not np.isfinite(x.ExcessOverFloorPct) else f"{x.ExcessOverFloorPct:.1f}%",
                     "-" if not np.isfinite(x.EquivClustersAtV1) else f"{x.EquivClustersAtV1:.0f}", int(x.TurnsUsed)])
    L += table(["Budget", "Arm", "Scenarios", "Visits", "ICC", "SE", "Floor", "Excess", "=Scen@1v", "Turns"], rows)
    need = v.VisitsWithin5Pct.dropna()
    L += [f"Median ICC {v.ICC.median():.3f}. For a fixed number of turns, `SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises",
          "with the visit count whenever the between-scenario variance is positive: scenarios beat visits, and the",
          "only reason to run more than one visit is that the portfolio is finite."
          + (f" Visits needed to sit within 5% of this portfolio's floor: {int(need.median())} (median over arms)."
             if not need.empty else ""), ""]
    return L


def reprice_section(split, R, weights) -> list:
    r = R.get("repriced")
    L = [f"## Re-priced in {weights['name']} ({split})", "",
         "**What re-pricing cannot do:** every search stopped when it had spent its budget under the weights it ran",
         "with, so these rows re-score the work each arm did, priced in another currency; they do not show the",
         "work it would have done under those prices.", ""]
    if r is None or r.empty:
        return L + ["No leaf could be re-priced (see the scope notes).", ""]
    rows = [[x.Budget, x.Arm, x.Pairs, ci(x.RepricedDiff, x.Lo, x.Hi), f"{x.ClampedShare:.0%}"]
            for x in r.itertuples(index=False)]
    return L + table(["Budget", "Arm", "Pairs", "Re-scored diff [CI]", "Clamped"], rows)


def across_section(split, R, prov_a, prov_b, other_weights) -> list:
    x = R["across"]
    L = [f"## Across machines ({split})", ""]
    if x.empty:
        return L + ["Nothing to compare: the two runs share no arm at any budget on cells where neither turn was terminal.", ""]
    b = prov_b or {}
    L += [f"A is this run, priced in {prov_a.get('currency')} and written by MAGAT {prov_a.get('magat_commit')}; B is "
          f"`{b.get('path', '?')}`, priced in {b.get('currency', '?')} and written by MAGAT {b.get('magat_commit', '?')}.",
          "The same experiment, positions and seeds, so the same chance events: each machine's search stopped under its",
          "own currency. On the cells both ran, neither turn terminal: each machine's mean value and the paired",
          "difference A - B, which is what the two currencies' prices did to where the searches stopped. The budget",
          "multipliers are dimensionless, so the two should agree. The seconds per turn are each machine's own.",
          ("`A at B's prices` is the units A's turns cost in B's currency over the units they cost in A's."
           if other_weights is not None else "B's currency was not found, so A's turns are not re-priced in it."), ""]
    rows = [[x_.Budget, x_.Arm, x_.Pairs, interval(x_.MeanA, x_.LoA, x_.HiA), interval(x_.MeanB, x_.LoB, x_.HiB),
             ci(x_.Diff, x_.DiffLo, x_.DiffHi), mult(x_.MultA, x_.MultALo, x_.MultAHi), mult(x_.MultB, x_.MultBLo, x_.MultBHi),
             f(x_.SecondsA, ".2f"), f(x_.SecondsB, ".2f"), f(x_.RepricedRatio, ".3f")]
            for x_ in x.itertuples(index=False)]
    return L + table(["Budget", "Arm", "Pairs", "A mean [CI]", "B mean [CI]", "A - B [CI]", "A multiplier [CI]",
                      "B multiplier [CI]", "A s/turn", "B s/turn", "A at B's prices"], rows)


def scope_section(splits, results, weights) -> list:
    L = ["## Scope notes", ""]
    for s in splits:
        R = results[s.name]
        b = R["budget_multiplier"]
        if not b.empty:
            ex = b[b.Flag != "inside"]
            if not ex.empty:
                L.append(f"- {s.name}: multipliers outside the ladder: " + ", ".join(
                    f"{x.Arm}@{x.Budget} ({x.Flag}, {f(x.Multiplier, '.1f')}x)" for x in ex.itertuples(index=False))
                    + ". An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.")
        w = R["wallclock"]
        if not w.empty and (w.ClampedShare > 0.25).any():
            hi = w[w.ClampedShare > 0.25]
            L.append(f"- {s.name}: wall-clock rows with over a quarter of pairs clamped to the baseline's time range: "
                     + ", ".join(f"{x.Arm}@{x.Budget} ({x.ClampedShare:.0%})" for x in hi.itertuples(index=False))
                     + ". A clamped pair is compared with the baseline's nearest level, not interpolated.")
        sp = R.get("spend")
        if sp is not None and not sp.empty and (sp.Spend > measures.SPEND_FLAG).any():
            hi = sp[sp.Spend > measures.SPEND_FLAG]
            L.append(f"- {s.name}: cells whose mean turn spends more than {measures.SPEND_FLAG:.2f}x their level: "
                     + ", ".join(f"{x.Arm}@{x.Budget} ({x.Spend:.2f}x)" for x in hi.itertuples(index=False))
                     + ". Their arms take more than their budget, which favours them in units.")
        tr = R["terminals"]
        if not tr.empty and (tr.TerminalShare > 0).any():
            L.append(f"- {s.name}: terminal turns: up to {tr.TerminalShare.max():.1%} of a leaf "
                     f"({int((tr.TerminalShare > 0).sum())} of {len(tr)} leaves have any). The headline, the "
                     "contrasts and the design leave their pairs out and count them; the tables keep both strata.")
        for note in s.notes:
            L.append(f"- {s.name}: {note}")
    if weights is None:
        L.append("- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; "
                 "it cannot re-run the stop rule.")
    L.append("- These are single turns replanned from captured positions, not full games.")
    return L + [""]


# --------------------------------------------------------------------------- figures
def draw(out: pathlib.Path, splits, results) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return
    (out / "figures").mkdir(exist_ok=True)
    for s in splits:
        b = results[s.name]["budget_multiplier"]
        b = b[np.isfinite(b.Multiplier)] if not b.empty else b
        if b.empty:
            continue
        fig, ax = plt.subplots(figsize=(7, 0.35 * len(b) + 1.2))
        ys = np.arange(len(b))
        ax.errorbar(b.Multiplier, ys, xerr=[(b.Multiplier - b.Lo).clip(lower=0), (b.Hi - b.Multiplier).clip(lower=0)],
                    fmt="o", capsize=3)
        ax.set_yticks(ys)
        ax.set_yticklabels([f"{x.Arm} @ {x.Budget:,}" for x in b.itertuples(index=False)], fontsize=8)
        ax.set_xscale("log")
        ax.axvline(1, color="grey", lw=0.8)
        ax.set_xlabel("equivalent budget multiplier (log scale)")
        ax.set_title(f"Budget multiplier, {s.name}")
        ax.grid(alpha=0.3, axis="x")
        fig.tight_layout()
        fig.savefig(out / "figures" / f"multiplier_{s.name}.png", dpi=130)
        plt.close(fig)
