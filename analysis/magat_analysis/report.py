"""`magat analyze`: run trees in, tables + SUMMARY.md + report.json out (design section 11.5).

SUMMARY.md order: provenance; replication side by side (#4); headline in units and seconds (#1, #3)
with the budget multiplier (#2), the gain multiple (#6) and d (#8) beside it; the multiplier in full;
attribution and contrasts (#5); oracle (#7); anytime (#9); diversity (#10); design (variance);
re-pricing; scope notes. report.json holds the same numbers for machines. Every table carries the
split it came from and the currency it is denominated in.
"""
import json
import pathlib

import numpy as np
import pandas as pd

from . import __version__, load, measures
from .stats import N_BOOT

TABLES = ("replication", "headline", "budget_multiplier", "w7_wallclock", "gain_multiple", "contrasts", "oracle",
          "anytime", "efficiency", "variance", "terminals")
EXIT_OK, EXIT_INPUT, EXIT_IDENTICAL = 0, 2, 5
# The header a table is written with when it has no rows (no holdout, a single-level baseline, no curves),
# so that every table is a CSV with columns whatever the run held.
EMPTY = {
    "replication": "Kind Budget Name Stratum A B DevMean DevLo DevHi HoldMean HoldLo HoldHi Replicates currency",
    "headline": "Split Budget Arm Baseline Stratum Pairs Scenarios MeanDiff Lo Hi D RelDiff RelLo RelHi Wins Losses Ties "
                "WinRate currency",
    "budget_multiplier": "Split Budget Arm Baseline Cells Scenarios ArmGain BaseGains Multiplier Lo Hi EquivBudget Flag "
                         "BootUndefined ArmMs BaseMs ArmTimeRatio TimeMultiplier TimeLo TimeHi currency",
    "w7_wallclock": "Split Budget Arm Baseline Pairs UnitsDiff Scenarios WallDiff WallLo WallHi ClampedShare MeanArmMillis "
                    "Verdict currency",
    "gain_multiple": "Split Budget Arm Baseline Pairs Scenarios ArmGain BaseGain GainMultiple Lo Hi currency",
    "contrasts": "Split Budget Kind Label A B Stratum Pairs Scenarios MeanDiff Lo Hi D WinRate currency",
    "oracle": "Split Budget Arm Turns Arms BestShare MeanRegret currency",
    "anytime": "Split Budget Arm Checkpoint MeanDiff Pairs currency",
    "efficiency": "Split Budget Arm Turns MeanNodes NodesVsBase MeanUnique DupShare DupBasis MeanPlanMillis MeanUnits "
                  "TerminalShare currency",
    "variance": "Split Budget Arm Stratum Pairs Clusters MeanVisits SigmaB2 SigmaW2 ICC SE SEFloor ExcessOverFloorPct "
                "VisitsWithin5Pct EquivClustersAtV1 TurnsUsed TurnsEquiv currency",
    "terminals": "Split Budget Arm Turns TerminalShare currency",
    "repriced": "Split Budget Arm Baseline Pairs Scenarios RepricedDiff Lo Hi ClampedShare MeanRepricedUnits currency",
    "repriced_spend": "Split Budget Arm Turns MeanUnits MeanRepricedUnits Ratio currency",
}


# --------------------------------------------------------------------------- number formatting
def f(x, spec="+.1f") -> str:
    return "-" if x is None or not np.isfinite(x) else format(float(x), spec)


def ci(m, lo, hi, spec="+.1f") -> str:
    sig = np.isfinite(lo) and np.isfinite(hi) and (lo > 0 or hi < 0)
    s = f"{f(m, spec)} [{f(lo, spec)}, {f(hi, spec)}]"
    return f"**{s}**" if sig else s


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
    out["w7_wallclock"] = measures.w7(t, baseline, out["headline"], sp.name, n)
    out["gain_multiple"] = measures.gain_multiple(t, baseline, sp.name, n)
    out["contrasts"] = measures.contrasts(t, pairs, sp.name, n)
    out["oracle"] = measures.oracle(t, sp.name)
    out["anytime"] = measures.anytime(sp.curve, t, baseline, sp.name)
    out["efficiency"] = measures.efficiency(t, baseline, sp.name)
    out["variance"] = measures.variance(t, baseline, sp.name)
    out["terminals"] = measures.terminal_rates(t)
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
def analyze(run, out, holdout=None, experiment=None, baseline=None, reprice=None, n=N_BOOT, figures=True,
            echo=print) -> int:
    try:
        splits = [load.load_split(p) for p in load.find_splits(run)]
        if holdout:
            splits = [s for s in splits if s.name != "holdout"] + [load.load_split(p) for p in load.find_splits(holdout)]
    except FileNotFoundError as e:
        echo(f"ANALYZE: {e}")
        return EXIT_INPUT
    spec_given = load.load_spec(experiment) if experiment else None
    specs = {s.name: spec_given or load.spec_of(s) for s in splits}
    baseline = baseline or next((x.get("baseline") for x in specs.values() if x and x.get("baseline")), None)
    if not baseline:
        echo("ANALYZE: no baseline: pass --baseline, or --experiment with a spec that names one")
        return EXIT_INPUT
    weights = load.read_weights(reprice) if reprice else None

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
    dev = next((s for s in splits if s.name != "holdout"), None)
    hold = next((s for s in splits if s.name == "holdout"), None)
    repl = measures.replication(results[dev.name], results[hold.name]) if dev and hold else pd.DataFrame()

    out = pathlib.Path(out)
    (out / "tables").mkdir(parents=True, exist_ok=True)
    names = list(TABLES) + (["repriced", "repriced_spend"] if weights else [])
    combined = {}
    for name in names:
        if name == "replication":
            df = repl.copy()
            if not df.empty:
                df["currency"] = dev.currency()
        else:
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
    summary = render(splits, specs, baseline, results, repl, prov, weights, n)
    load.write_text(out / "SUMMARY.md", summary)
    write_json(out / "report.json", dict(
        magat_analysis=__version__, baseline=baseline, bootstrap_resamples=n,
        reprice=None if not weights else dict(path=weights["path"], sha256=weights["sha256"]),
        provenance=prov, notes={s.name: s.notes for s in splits},
        tables={k: records(v) for k, v in combined.items()}))
    for s in splits:
        for note in s.notes:
            echo(f"  note: {note}")
    echo(f"ANALYZE: OK - {len(combined)} tables ({sum(not v.empty for v in combined.values())} with rows), "
         f"SUMMARY.md and report.json in {out}")
    return EXIT_OK


def records(df: pd.DataFrame) -> list:
    if df is None or df.empty:
        return []
    return json.loads(df.to_json(orient="records", double_precision=10))


def write_json(p: pathlib.Path, obj) -> None:
    load.write_text(p, json.dumps(obj, indent=1, default=lambda o: o.item() if hasattr(o, "item") else str(o)) + "\n")


# --------------------------------------------------------------------------- SUMMARY.md
def render(splits, specs, baseline, results, repl, prov, weights, n) -> str:
    order = sorted(splits, key=lambda s: (s.name != "holdout", s.name))       # the confirmatory read first
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
            ("experiment sha256", "experiment_sha256"), ("frozen choices sha256", "frozen_sha256"),
            ("pre-registration", "preregistration"), ("leaves / turns", None), ("written by MAGAT", "magat_commit")]
    rows = []
    for label, k in keys:
        if k is None:
            rows.append([label] + [f"{prov[s]['leaves']} / {prov[s]['turns']:,}" for s in names])
        else:
            rows.append([label] + [str(prov[s].get(k) or "-") for s in names])
    for s in names:
        if prov[s].get("imported"):
            imp = prov[s]["imported"]
            rows.append([f"imported ({s})", f"{imp.get('from', '?')}: {imp.get('note', '')}"] + [""] * (len(names) - 1))
        if prov[s].get("toy"):
            rows.append([f"toy ({s})", "a toy run: measures nothing"] + [""] * (len(names) - 1))
    L += table(["", *names], rows)

    if not repl.empty:
        L += ["## Replication: development and holdout side by side", "",
              "`yes`: the holdout estimate lands inside the development interval and its own interval excludes",
              "the null (0, or 1x for a multiplier). `ns both`: neither interval excludes it, a null that",
              "replicated. Every headline appears as two numbers or not at all.", ""]
        r = repl[repl.Stratum != "no-terminal"]
        m = repl[repl.Kind == "multiplier"]
        rows = []
        for x in pd.concat([r, m]).itertuples(index=False):
            name = x.Name if x.Kind != "contrast" else f"{x.Name} ({x.A} - {x.B})"
            fmt = mult if x.Kind == "multiplier" else ci
            rows.append([x.Kind, x.Budget, name, fmt(x.DevMean, x.DevLo, x.DevHi), fmt(x.HoldMean, x.HoldLo, x.HoldHi),
                         x.Replicates])
        L += table(["Kind", "Budget", "Comparison", "Development", "Holdout", "Replicates"], rows)

    for s in order:
        L += headline_section(s.name, results[s.name], baseline)
    for s in order:
        L += multiplier_section(s.name, results[s.name], baseline)
    for s in order:
        L += contrast_section(s.name, results[s.name])
    for s in order:
        L += oracle_section(s.name, results[s.name])
    L += d_section(order, results)
    for s in order:
        L += anytime_section(s.name, results[s.name])
    for s in order:
        L += efficiency_section(s.name, results[s.name])
    for s in order:
        L += variance_section(s.name, results[s.name])
    if weights is not None:
        for s in order:
            L += reprice_section(s.name, results[s.name], weights)
    L += scope_section(order, results, weights)
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
         "Units: the paired difference (#1); its no-terminal sensitivity beside it. Seconds: W7 (#3), the arm",
         "re-scored against the baseline at the wall-clock it actually spent; a units-only win is unresolved.",
         "The multiplier (#2) and the gain multiple (#6) are the readable sizes; `MeanDiff` itself is the raw",
         "heuristic delta (#11), which is never a result on its own. d (#8) is on the no-terminal pairs.", ""]
    w7 = R["w7_wallclock"].set_index(["Budget", "Arm"]) if not R["w7_wallclock"].empty else None
    bm = R["budget_multiplier"].set_index(["Budget", "Arm"]) if not R["budget_multiplier"].empty else None
    gm = R["gain_multiple"].set_index(["Budget", "Arm"]) if not R["gain_multiple"].empty else None
    for budget in sorted(h.Budget.unique()):
        rows = []
        for x in by(h, Budget=budget, Stratum="all").itertuples(index=False):
            k = (budget, x.Arm)
            nt = by(h, Budget=budget, Arm=x.Arm, Stratum="no-terminal")
            ntm = nt.MeanDiff.iloc[0] if not nt.empty else np.nan
            d = nt.D.iloc[0] if not nt.empty else np.nan
            srcs = ", ".join(f"{c[len('MeanDiff_'):]} {f(getattr(x, c))}" for c in h.columns
                             if c.startswith("MeanDiff_") and np.isfinite(getattr(x, c, np.nan)))
            wall, verdict = "-", "-"
            if w7 is not None and k in w7.index:
                w = w7.loc[k]
                clamp = f" (clamped {w.ClampedShare:.0%})" if w.ClampedShare > 0.05 else ""
                wall, verdict = ci(w.WallDiff, w.WallLo, w.WallHi) + clamp, w.Verdict
            mm = "-"
            if bm is not None and k in bm.index:
                b = bm.loc[k]
                mm = mult(b.Multiplier, b.Lo, b.Hi) + ("" if b.Flag == "inside" else f" ({b.Flag})")
            g = f(gm.loc[k].GainMultiple, ".2f") + "x" if gm is not None and k in gm.index and np.isfinite(gm.loc[k].GainMultiple) else "-"
            rows.append([x.Arm, x.Pairs, ci(x.MeanDiff, x.Lo, x.Hi), f(ntm), srcs, wall, verdict, mm, g, f(d, ".2f")])
        L += [f"### {budget:,} units", ""]
        L += table(["Arm", "Pairs", "MeanDiff [CI]", "No-terminal", "By source", "W7 seconds [CI]", "Verdict",
                    "Budget multiplier", "Gain multiple", "d"], rows)
    return L


def multiplier_section(split, R, baseline) -> list:
    b = R["budget_multiplier"]
    if b.empty:
        return [f"## Budget multiplier ({split})", "",
                f"Not computable: `{baseline}` ran at fewer than two budgets here, so it has no curve to read against.", ""]
    L = [f"## Budget multiplier ({split})", "",
         f"How much budget `{baseline}` would need to match each arm: the baseline's mean search gain per level,",
         "interpolated log-linearly in budget, solved for the arm's gain, over the cells where every baseline level",
         "and the arm ran and none ended terminal. `extrapolated` means the crossing lies past the top of the ladder:",
         "a lower bound, since gain is concave in budget. The wall-clock column solves the same crossing in the",
         "baseline's own seconds and divides by the arm's; `arm/baseline time` is the arm's plan time over the",
         "baseline's at the same budget.", ""]
    base = b.iloc[0]
    lv = [p.split(":") for p in base.BaseGains.split(";")]
    ms = [p.split(":") for p in base.BaseMs.split(";")]
    if len(lv) >= 2:
        step = float(lv[-1][0]) / float(lv[-2][0])
        tstep = float(ms[-1][1]) / float(ms[-2][1]) if float(ms[-2][1]) > 0 else np.nan
        L += [f"The baseline's ladder (on the first arm's cells): " + ", ".join(f"{int(a):,}: gain {float(g):.1f}"
              for a, g in lv) + f". Its top step is {step:.0f}x the units and {f(tstep, '.1f')}x the seconds.", ""]
    rows = [[x.Budget, x.Arm, x.Cells, f(x.ArmGain, ".1f"), mult(x.Multiplier, x.Lo, x.Hi), x.Flag,
             mult(x.TimeMultiplier, x.TimeLo, x.TimeHi), f(x.ArmTimeRatio, ".2f")]
            for x in b.itertuples(index=False)]
    return L + table(["Budget", "Arm", "Cells", "Arm gain", "Multiplier [CI]", "Where", "Wall-clock multiplier [CI]",
                      "Arm/baseline time"], rows)


def contrast_section(split, R) -> list:
    c = R["contrasts"]
    if c.empty:
        return []
    L = [f"## Attribution and contrasts ({split})", "",
         "An arm's mechanism is credited only when the same arm with the mechanism replaced by noise does worse",
         "(`attribution` rows: arm minus its control). `contrast` rows are the spec's own comparisons.", ""]
    rows = [[x.Budget, x.Kind, f"{x.A} - {x.B}", x.Label, x.Pairs, ci(x.MeanDiff, x.Lo, x.Hi),
             f(by(c, Budget=x.Budget, A=x.A, B=x.B, Stratum="no-terminal").MeanDiff.max()), f"{x.WinRate:.3f}"]
            for x in by(c, Stratum="all").itertuples(index=False)]
    return L + table(["Budget", "Kind", "A - B", "Label", "Pairs", "MeanDiff [CI]", "No-terminal", "WinRate"], rows)


def oracle_section(split, R) -> list:
    o = R["oracle"]
    if o.empty:
        return []
    L = [f"## Oracle regret ({split})", "",
         "Over the turns where every arm at the budget produced a non-terminal sequence: how often each arm",
         "found the field's best (ties count for every arm reaching it) and how far below the best it was on",
         "average. Robust to the heuristic's scale; meaningful with three or more arms.", ""]
    for budget in sorted(o.Budget.unique()):
        d = by(o, Budget=budget)
        L += [f"{budget:,} units, {int(d.Turns.iloc[0]):,} turns, {int(d.Arms.iloc[0])} arms:", ""]
        L += table(["Arm", "Best of field", "Mean regret"],
                   [[x.Arm, f"{x.BestShare:.1%}", f"{x.MeanRegret:.1f}"] for x in d.itertuples(index=False)])
    return L


def d_section(order, results) -> list:
    rows = []
    for s in order:
        h = by(results[s.name]["headline"], Stratum="no-terminal")
        for x in h.itertuples(index=False):
            rows.append([s.name, x.Budget, x.Arm, f(x.D, ".2f")])
    if not rows:
        return []
    return ["## Effect size: Cohen's d", "",
            "The mean paired difference over its standard deviation, on the no-terminal pairs. It treats the",
            "pairs as independent, which they are not (visits share a scenario): a standardised size that travels",
            "across studies, not a test.", ""] + table(["Split", "Budget", "Arm", "d"], rows)


def anytime_section(split, R) -> list:
    a = R["anytime"]
    if a.empty:
        return [f"## Anytime ({split})", "", "No curve rows (`curve.csv`) in this tree.", ""]
    L = [f"## Anytime ({split})", "",
         "Mean paired difference of the best value found so far, at each quarter of the anytime checkpoints",
         "(EMCTS: bridge-burn phases); `tables/anytime.csv` has every checkpoint.", ""]
    for budget in sorted(a.Budget.unique()):
        d = by(a, Budget=budget)
        cps = sorted(d.Checkpoint.unique())
        pick = sorted({cps[max(0, int(round(q * len(cps))) - 1)] for q in (0.25, 0.5, 0.75, 1.0)})
        rows = []
        for arm in sorted(d.Arm.unique()):
            da = d[d.Arm == arm].set_index("Checkpoint")
            rows.append([arm] + [f(da.MeanDiff.get(cp, np.nan)) for cp in pick])
        L += [f"{budget:,} units:", ""] + table(["Arm"] + [f"checkpoint {cp}" for cp in pick], rows)
    return L


def efficiency_section(split, R) -> list:
    e = R["efficiency"]
    if e.empty:
        return []
    L = [f"## Diversity ({split}, within-run only)", "",
         "How each arm spent its budget, never whether that was good. The duplicate share is per mutation event",
         "where the agent reports them, else per node. Agent telemetry means are in `tables/efficiency.csv`.", ""]
    rows = [[x.Budget, x.Arm, f"{x.MeanNodes:.1f}", f"{x.MeanUnique:.1f}", f"{x.DupShare:.3f}", f(x.NodesVsBase, ".2f"),
             f"{x.MeanPlanMillis:.0f}"] for x in e.itertuples(index=False)]
    return L + table(["Budget", "Arm", "Nodes", "Unique", "Dup share", "Nodes vs baseline", "Plan ms"], rows)


def variance_section(split, R) -> list:
    v = by(R["variance"], Stratum="all")
    if v.empty:
        return []
    L = [f"## Design ({split}): scenarios versus visits", "",
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


def scope_section(order, results, weights) -> list:
    L = ["## Scope notes", ""]
    for s in order:
        R = results[s.name]
        b = R["budget_multiplier"]
        if not b.empty:
            ex = b[b.Flag != "inside"]
            if not ex.empty:
                L.append(f"- {s.name}: multipliers outside the ladder: " + ", ".join(
                    f"{x.Arm}@{x.Budget} ({x.Flag}, {f(x.Multiplier, '.1f')}x)" for x in ex.itertuples(index=False))
                    + ". An extrapolated multiplier is a lower bound; running the baseline at a higher level measures it.")
        w = R["w7_wallclock"]
        if not w.empty and (w.ClampedShare > 0.25).any():
            hi = w[w.ClampedShare > 0.25]
            L.append(f"- {s.name}: W7 rows with over a quarter of pairs clamped to the baseline's time range: "
                     + ", ".join(f"{x.Arm}@{x.Budget} ({x.ClampedShare:.0%})" for x in hi.itertuples(index=False))
                     + ". A clamped pair is compared with the baseline's nearest level, not interpolated.")
        tr = R["terminals"]
        if not tr.empty and (tr.TerminalShare > 0).any():
            L.append(f"- {s.name}: terminal turns: up to {tr.TerminalShare.max():.1%} of a leaf "
                     f"({int((tr.TerminalShare > 0).sum())} of {len(tr)} leaves have any); every headline is shown "
                     "with and without them.")
        for note in s.notes:
            L.append(f"- {s.name}: {note}")
    if weights is None:
        L.append("- Not re-priced: `--reprice other.properties` re-scores every arm in another machine's currency; "
                 "it cannot re-run the stop rule.")
    L.append("- Full-game win rate (brief #12) is not measured in v0.1; it remains the optional external yardstick.")
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
        R = results[s.name]
        a, h = R["anytime"], by(R["headline"], Stratum="all")
        for budget in (sorted(a.Budget.unique()) if not a.empty else []):
            hb = by(h, Budget=budget)
            top = list(hb.reindex(hb.MeanDiff.abs().sort_values(ascending=False).index).Arm.head(3)) if not hb.empty else []
            d = a[(a.Budget == budget) & a.Arm.isin(top)]
            if d.empty:
                continue
            fig, ax = plt.subplots(figsize=(7, 4.2))
            for arm, g in d.groupby("Arm"):
                ax.plot(g.Checkpoint, g.MeanDiff, marker="o", ms=3, label=arm)
            ax.axhline(0, color="grey", lw=0.8)
            ax.set_xlabel("anytime checkpoint")
            ax.set_ylabel("paired best-value diff vs baseline")
            ax.set_title(f"Anytime profile, {s.name}, {budget:,} units")
            ax.grid(alpha=0.3)
            ax.legend(fontsize=8)
            fig.tight_layout()
            fig.savefig(out / "figures" / f"anytime_{s.name}_{budget}.png", dpi=130)
            plt.close(fig)
        b = R["budget_multiplier"]
        b = b[np.isfinite(b.Multiplier)] if not b.empty else b
        if not b.empty:
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
