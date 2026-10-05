"""The measures of brief section 4, one function each (design section 11.2).

Every comparison is PAIRED per (budget, scenario, visit): every arm replans the same positions with
the same seeds, so an arm's effect is the distribution of final(arm) - final(baseline) over the pairs
it shares with the baseline, and every interval resamples scenarios (stats.py). Tables that a terminal
position can dominate exist in two strata: `all` and `no-terminal` (pairs where neither side's turn
reached a terminal state, worth about +/-2e9).

Moved from the thesis's analyze_stage1.py: headline, contrasts (now from the spec), W7, anytime,
efficiency (generalised), variance. New: the budget multiplier (and its wall-clock twin), the
search-gain multiple, oracle regret, Cohen's d, dev/holdout replication, re-pricing.
"""
import numpy as np
import pandas as pd

from .stats import (N_BOOT, cluster_boot_ci, cluster_table, cohens_d, design_advice, quantiles,
                    resampled_means, rng_for, variance_components)

KEYS = ["scenario", "visit"]
STRATA = ("all", "no-terminal")


def frame(rows, by=None) -> pd.DataFrame:
    """DataFrame(rows), stably sorted by `by`, tolerating no rows at all (a stage that has not run yet)."""
    df = pd.DataFrame(rows)
    if df.empty or not by:
        return df
    return df.sort_values(by, kind="mergesort").reset_index(drop=True)


def arms_at(t: pd.DataFrame, budget, exclude=None) -> list:
    return sorted(a for a in t[t.budget == budget].arm.unique() if a != exclude)


def paired(t: pd.DataFrame, a: str, b: str, budget) -> pd.DataFrame:
    """Arm a's rows joined to arm b's on (scenario, visit) at one budget; `diff` is final(a) - final(b)."""
    x = t[(t.arm == a) & (t.budget == budget)]
    y = t[(t.arm == b) & (t.budget == budget)]
    j = x.merge(y, on=KEYS, suffixes=("", "_b"))
    j["diff"] = j["final"] - j["final_b"]
    # Normalised: the improvement relative to what b's own search added on this turn.
    j["rel"] = j["diff"] / j["gain_b"].abs().clip(lower=1.0)
    j["term_any"] = j["term"] | j["term_b"]
    return j


def stratum(j: pd.DataFrame, s: str) -> pd.DataFrame:
    return j if s == "all" else j[~j["term_any"]]


# --------------------------------------------------------------------------- 1 and 8: headline, d
def headline(t, baseline, split="", n=N_BOOT) -> pd.DataFrame:
    """#1 the paired difference against the baseline, per budget and arm, with win/loss/tie counts,
    per-source intervals (a pooled mean can hide a mechanism's scope: the thesis's bias effect was +53
    on single-source scenarios and -10 on dual), and #8 Cohen's d on the paired differences."""
    rows = []
    for budget in sorted(t.budget.unique()):
        if not ((t.arm == baseline) & (t.budget == budget)).any():
            continue
        for arm in arms_at(t, budget, baseline):
            j = paired(t, arm, baseline, budget)
            for s in STRATA:
                d = stratum(j, s)
                if d.empty:
                    continue
                key = ("headline", split, budget, arm, baseline, s)
                m, lo, hi, k = cluster_boot_ci(d, "diff", n=n, key=key)
                rm, rlo, rhi, _ = cluster_boot_ci(d, "rel", n=n, key=key)
                wins, losses = int((d["diff"] > 0).sum()), int((d["diff"] < 0).sum())
                row = dict(Budget=int(budget), Arm=arm, Baseline=baseline, Stratum=s, Pairs=len(d), Scenarios=k,
                           MeanDiff=m, Lo=lo, Hi=hi, D=cohens_d(d["diff"]), RelDiff=rm, RelLo=rlo, RelHi=rhi,
                           Wins=wins, Losses=losses, Ties=len(d) - wins - losses,
                           WinRate=wins / max(1, wins + losses))
                for src in sorted(d["source"].dropna().unique()):
                    sm, slo, shi, _ = cluster_boot_ci(d[d["source"] == src], "diff", n=n, key=key + (src,))
                    row[f"MeanDiff_{src}"], row[f"Lo_{src}"], row[f"Hi_{src}"] = sm, slo, shi
                rows.append(row)
    return frame(rows, ["Budget", "Arm", "Stratum"])


# --------------------------------------------------------------------------- 5: contrasts
def contrasts(t, pairs, split="", n=N_BOOT) -> pd.DataFrame:
    """#5 paired a-minus-b differences for the spec's contrasts and every arm's attribution control
    (the same arm with its mechanism replaced by noise), at every budget where both arms ran."""
    rows = []
    for budget in sorted(t.budget.unique()):
        present = set(t[t.budget == budget].arm)
        for c in pairs:
            if c["a"] not in present or c["b"] not in present:
                continue
            j = paired(t, c["a"], c["b"], budget)
            for s in STRATA:
                d = stratum(j, s)
                if d.empty:
                    continue
                m, lo, hi, k = cluster_boot_ci(d, "diff", n=n, key=("contrast", split, budget, c["a"], c["b"], s))
                rows.append(dict(Budget=int(budget), Kind=c["kind"], Label=c["label"], A=c["a"], B=c["b"], Stratum=s,
                                 Pairs=len(d), Scenarios=k, MeanDiff=m, Lo=lo, Hi=hi, D=cohens_d(d["diff"]),
                                 WinRate=float((d["diff"] > 0).sum() / max(1, int((d["diff"] != 0).sum())))))
    return frame(rows, ["Budget"])


# --------------------------------------------------------------------------- 2: budget multiplier
def locate(M: np.ndarray, A: np.ndarray):
    """Where each arm gain A sits on its baseline curve M (rows: resamples, columns: budget levels).

    Returns the segment index i (the adjacent pair of levels interpolated between; the end segments
    extrapolate past either end of the ladder), the fraction along it, and whether the curve rises at
    every step (a curve that does not cannot be inverted)."""
    n, L = M.shape
    rising = np.all(np.diff(M, axis=1) > 0, axis=1)
    i = np.clip((M <= A[:, None]).sum(axis=1) - 1, 0, L - 2)
    r = np.arange(n)
    with np.errstate(divide="ignore", invalid="ignore"):
        frac = (A - M[r, i]) / (M[r, i + 1] - M[r, i])
    return i, frac, rising


def along(X: np.ndarray, i: np.ndarray, frac: np.ndarray) -> np.ndarray:
    """The point `frac` of the way along segment i of each row of X."""
    r = np.arange(len(i))
    return X[r, i] + frac * (X[r, i + 1] - X[r, i])


def budget_multiplier(t, baseline, split="", n=N_BOOT) -> pd.DataFrame:
    """#2 the equivalent budget multiplier: how much budget the baseline would need to match the arm.

    Search gain (final - initial) on the (scenario, visit) cells where the baseline ran at every one
    of its levels and the arm ran, none of them terminal. The baseline's mean gain per level is
    interpolated log-linearly in budget between adjacent levels; the arm's mean gain is solved for the
    baseline budget that reaches it; the multiplier is that budget over the arm's own. The interval
    resamples scenarios. A crossing past the top of the ladder is `extrapolated` (a lower bound under
    diminishing returns), one below the bottom `outside`.

    Its wall-clock twin solves the same crossing on the baseline's mean plan time per level, log-linear
    in milliseconds, and divides by the arm's own mean plan time: how much more of the baseline's time
    the arm's result is worth."""
    levels = sorted(t[t.arm == baseline].budget.unique())
    if len(levels) < 2:
        return frame([])
    L = len(levels)
    lb = np.log(np.array(levels, float))
    rows = []
    for budget in sorted(t.budget.unique()):
        for arm in arms_at(t, budget, baseline):
            j = t[(t.arm == arm) & (t.budget == budget) & ~t.term][KEYS + ["gain", "plan_ms"]]
            j = j.rename(columns={"gain": "ga", "plan_ms": "ta"})
            for k, b in enumerate(levels):
                x = t[(t.arm == baseline) & (t.budget == b) & ~t.term][KEYS + ["gain", "plan_ms"]]
                j = j.merge(x.rename(columns={"gain": f"g{k}", "plan_ms": f"t{k}"}), on=KEYS)
            if j.empty:
                continue
            cols = [f"g{k}" for k in range(L)] + ["ga"] + [f"t{k}" for k in range(L)] + ["ta"]
            sums, counts, _ = cluster_table(j, cols)
            point = (sums.sum(axis=0) / counts.sum())[None, :]
            boot = resampled_means(sums, counts, n, rng_for("multiplier", split, budget, arm, baseline))
            out = {}
            for name, X in (("point", point), ("boot", boot)):
                M, A, T, ta = X[:, :L], X[:, L], X[:, L + 1:2 * L + 1], X[:, 2 * L + 1]
                i, frac, rising = locate(M, A)
                mult = np.exp(along(np.broadcast_to(lb, M.shape), i, frac)) / budget
                with np.errstate(divide="ignore", invalid="ignore"):
                    logT = np.where(T > 0, np.log(np.where(T > 0, T, 1.0)), np.nan)
                    tmult = np.exp(along(logT, i, frac)) / np.where(ta > 0, ta, np.nan)
                mult[~rising] = np.nan
                tmult[~rising] = np.nan
                out[name] = (mult, tmult, M, A, T, ta, rising)
            mult, tmult, M, A, T, ta, rising = out["point"]
            lo, hi = quantiles(out["boot"][0])
            tlo, thi = quantiles(out["boot"][1])
            flag = ("non-monotone" if not rising[0] else "extrapolated" if A[0] > M[0, -1]
                    else "outside" if A[0] < M[0, 0] else "inside")
            same = levels.index(budget) if budget in levels else None
            rows.append(dict(
                Budget=int(budget), Arm=arm, Baseline=baseline, Cells=int(counts.sum()), Scenarios=len(counts),
                ArmGain=float(A[0]),
                BaseGains=";".join(f"{lv}:{g:.4f}" for lv, g in zip(levels, M[0])),
                Multiplier=float(mult[0]), Lo=lo, Hi=hi, EquivBudget=float(mult[0] * budget), Flag=flag,
                BootUndefined=float(np.mean(~np.isfinite(out["boot"][0]))),
                ArmMs=float(ta[0]),
                BaseMs=";".join(f"{lv}:{x:.1f}" for lv, x in zip(levels, T[0])),
                ArmTimeRatio=float(ta[0] / T[0, same]) if same is not None and T[0, same] > 0 else np.nan,
                TimeMultiplier=float(tmult[0]), TimeLo=tlo, TimeHi=thi))
    return frame(rows, ["Budget", "Arm"])


# --------------------------------------------------------------------------- 6: search-gain multiple
def gain_multiple(t, baseline, split="", n=N_BOOT) -> pd.DataFrame:
    """#6 the arm's mean search gain over the baseline's at the same budget, on the pairs where
    neither turn is terminal; the interval resamples scenarios."""
    rows = []
    for budget in sorted(t.budget.unique()):
        if not ((t.arm == baseline) & (t.budget == budget)).any():
            continue
        for arm in arms_at(t, budget, baseline):
            j = stratum(paired(t, arm, baseline, budget), "no-terminal")
            if j.empty:
                continue
            sums, counts, _ = cluster_table(j, ["gain", "gain_b"])
            ga, gb = sums.sum(axis=0) / counts.sum()
            boot = resampled_means(sums, counts, n, rng_for("gain-multiple", split, budget, arm, baseline))
            with np.errstate(divide="ignore", invalid="ignore"):
                lo, hi = quantiles(boot[:, 0] / boot[:, 1])
            rows.append(dict(Budget=int(budget), Arm=arm, Baseline=baseline, Pairs=int(counts.sum()),
                             Scenarios=len(counts), ArmGain=float(ga), BaseGain=float(gb),
                             GainMultiple=float(ga / gb) if gb != 0 else np.nan, Lo=lo, Hi=hi))
    return frame(rows, ["Budget", "Arm"])


# --------------------------------------------------------------------------- 3: W7, and re-pricing
def rescore(t, baseline, x, split="", n=N_BOOT, key="w7", min_pairs=10) -> pd.DataFrame:
    """Every arm re-scored against the baseline INTERPOLATED AT THE ARM'S OWN SPEND on axis x.

    The baseline's (x, final) points across the budget ladder form a value-versus-spend curve per
    (scenario, visit); the arm is compared with that curve at the spend it actually used. Paired per
    (scenario, visit), scenario-clustered CI, terminals excluded (a +/-2e9 terminal would dominate the
    interpolation); a spend outside the baseline's range is clamped to its end and counted."""
    base = t[(t.arm == baseline) & ~t.term & t[x].notna()]
    curves = {}
    for (s, v), g in base.groupby(KEYS):
        g = g.sort_values(x)
        if len(g) >= 2:
            curves[(s, v)] = (g[x].to_numpy(float), g["final"].to_numpy(float))
    if not curves:
        return frame([])
    rows = []
    arms = t[(t.arm != baseline) & ~t.term & t[x].notna()]
    for (budget, arm), g in arms.groupby(["budget", "arm"]):
        recs = []
        for s, v, xv, fv in zip(g.scenario, g.visit, g[x].to_numpy(float), g["final"].to_numpy(float)):
            c = curves.get((s, v))
            if c is None:
                continue
            xs, ys = c
            recs.append((s, fv - float(np.interp(xv, xs, ys)), xv < xs[0] or xv > xs[-1]))
        if len(recs) < min_pairs:
            continue
        j = pd.DataFrame(recs, columns=["scenario", "diff", "clamped"])
        m, lo, hi, k = cluster_boot_ci(j, "diff", n=n, key=(key, split, budget, arm, baseline))
        rows.append(dict(Budget=int(budget), Arm=arm, Baseline=baseline, Pairs=len(j), Scenarios=k,
                         Diff=m, Lo=lo, Hi=hi, ClampedShare=float(j["clamped"].mean()), MeanSpend=float(g[x].mean())))
    return frame(rows, ["Budget", "Arm"])


def w7(t, baseline, head: pd.DataFrame, split="", n=N_BOOT) -> pd.DataFrame:
    """#3 W7, wall-clock fairness, co-primary since the thesis's Gate A3' (2026-08-29).

    The currency is calibrated idle and single-threaded, and under load a cache-light arm's relative op
    cost rose ~24%, so a unit budget can subsidise exactly the arm that looks best. W7 re-scores each arm
    against the baseline at the wall-clock it actually spent. Needs the baseline at >= 2 budgets. An arm
    that wins in units but not here is unresolved: a bookkeeping artefact until shown otherwise."""
    w = rescore(t, baseline, "plan_ms", split, n, key="w7")
    if w.empty:
        return w
    w = w.rename(columns={"Diff": "WallDiff", "Lo": "WallLo", "Hi": "WallHi", "MeanSpend": "MeanArmMillis"})
    h = head[head.Stratum == "all"].set_index(["Budget", "Arm"]) if not head.empty else pd.DataFrame()
    verdicts, units = [], []
    for r in w.itertuples(index=False):
        key = (r.Budget, r.Arm)
        us = bool(not h.empty and key in h.index and h.loc[key, "Lo"] > 0)
        ws = bool(r.WallLo > 0)
        units.append(float(h.loc[key, "MeanDiff"]) if not h.empty and key in h.index else np.nan)
        verdicts.append("wins both" if us and ws else "units only (unresolved)" if us
                        else "wall-clock only" if ws else "no win")
    w.insert(5, "UnitsDiff", units)
    w["Verdict"] = verdicts
    return w


def reprice(t, leaves, weights, baseline, split="", n=N_BOOT):
    """Each turn's units recomputed under another machine's weights, and every arm re-scored against
    the baseline's value-versus-units curve in that currency, as W7 re-scores against wall-clock.

    What re-pricing cannot do is re-run the stop rule: every search stopped when it had spent its
    budget under the weights it ran with, so a re-priced arm did not do the work it would have done
    under the new prices. Returns (re-scored table, spend per leaf, notes)."""
    t = t.copy()
    t["units_x"] = np.nan
    ran_global = {(int(m.get("budget", -1)), str(m.get("arm"))) for m in leaves
                  if str(m.get("profile", "")).startswith("global")}
    notes, spend = [], []
    for (budget, arm), idx in t.groupby(["budget", "arm"]).groups.items():
        g = t.loc[idx]
        if any(c not in g or g[c].isna().any() for c in ("advances", "copies", "evals")):
            notes.append(f"{arm}@{budget}: the leaf carries no copy or evaluation counts, so it cannot be re-priced")
            continue
        w = weights["profiles"].get(f"{arm}@{budget}")
        if w is None and (int(budget), arm) in ran_global:
            w = weights["globals"]
        if w is None:
            notes.append(f"{arm}@{budget}: not priced in {weights['name']}, so not re-priced")
            continue
        ux = g["advances"] * w[0] + g["copies"] * w[1] + g["evals"] * w[2]
        t.loc[idx, "units_x"] = ux
        spend.append(dict(Budget=int(budget), Arm=arm, Turns=len(g), MeanUnits=float(g["units"].mean()),
                          MeanRepricedUnits=float(ux.mean()), Ratio=float(ux.mean() / g["units"].mean())
                          if g["units"].mean() else np.nan))
    table = rescore(t, baseline, "units_x", split, n, key="reprice")
    if not table.empty:
        table = table.rename(columns={"Diff": "RepricedDiff", "MeanSpend": "MeanRepricedUnits"})
    return table, frame(spend, ["Budget", "Arm"]), notes


# --------------------------------------------------------------------------- 7: oracle
def oracle(t, split="") -> pd.DataFrame:
    """#7 best-of-field share and mean regret per arm, over the (scenario, visit) pairs at a budget
    where every arm has a row and none reached a terminal. Needs >= 3 arms to mean anything: with two,
    best-of-field is a win rate."""
    rows = []
    for budget, d in t.groupby("budget"):
        arms = sorted(d.arm.unique())
        if len(arms) < 3:
            continue
        idx = d.set_index(KEYS + ["arm"])
        fv = idx["final"].unstack("arm")
        tm = idx["term"].astype(float).unstack("arm")
        ok = fv.notna().all(axis=1) & (tm.fillna(1.0) == 0).all(axis=1)
        fv = fv[ok]
        if fv.empty:
            continue
        mx = fv.max(axis=1)
        best = fv.eq(mx, axis=0).mean()
        regret = fv.rsub(mx, axis=0).mean()
        for arm in arms:
            rows.append(dict(Budget=int(budget), Arm=arm, Turns=len(fv), Arms=len(arms),
                             BestShare=float(best[arm]), MeanRegret=float(regret[arm])))
    df = frame(rows)
    return df.sort_values(["Budget", "BestShare"], ascending=[True, False], kind="mergesort").reset_index(drop=True) \
        if not df.empty else df


# --------------------------------------------------------------------------- 9: anytime
def anytime(curve, t, baseline, split="") -> pd.DataFrame:
    """#9 the paired difference of the best value found so far, per anytime checkpoint (EMCTS: per
    bridge-burn phase): when does an effect appear. Pairs where either turn ends terminal are left out,
    since a terminal found a checkpoint earlier would otherwise dominate the mean."""
    if curve is None or curve.empty:
        return frame([])
    rows = []
    term = t.set_index(KEYS + ["arm", "budget"])["term"]
    for budget, d in curve.groupby("budget"):
        b = d[d.arm == baseline][KEYS + ["checkpoint", "best"]]
        if b.empty:
            continue
        for arm in sorted(a for a in d.arm.unique() if a != baseline):
            j = d[d.arm == arm][KEYS + ["checkpoint", "best"]].merge(b, on=KEYS + ["checkpoint"], suffixes=("", "_b"))
            if j.empty:
                continue
            ta = term.reindex(pd.MultiIndex.from_arrays([j.scenario, j.visit, [arm] * len(j), [budget] * len(j)]))
            tb = term.reindex(pd.MultiIndex.from_arrays([j.scenario, j.visit, [baseline] * len(j), [budget] * len(j)]))
            j = j[~(ta.fillna(False).to_numpy(bool) | tb.fillna(False).to_numpy(bool))]
            j = j.assign(diff=j["best"] - j["best_b"])
            for cp, g in j.groupby("checkpoint"):
                rows.append(dict(Budget=int(budget), Arm=arm, Checkpoint=int(cp), MeanDiff=float(g["diff"].mean()),
                                 Pairs=len(g)))
    return frame(rows, ["Budget", "Arm", "Checkpoint"])


# --------------------------------------------------------------------------- 10: diversity
def efficiency(t, baseline, split="") -> pd.DataFrame:
    """#10 nodes, unique sequences and the duplicate share per leaf, with the agent's own telemetry
    means appended. Within-run diagnostics of a mechanism, never a headline: they say how an arm spent
    its budget, not whether that was good."""
    rows = []
    for (budget, arm), g in t.groupby(["budget", "arm"]):
        if "t_mutEvents" in g and g["t_mutEvents"].notna().any():
            dup, basis = (g["duplicates"] / g["t_mutEvents"].replace(0, np.nan)).mean(), "per mutation event"
        else:
            dup, basis = (g["duplicates"] / g["nodes"].replace(0, np.nan)).mean(), "per node"
        row = dict(Budget=int(budget), Arm=arm, Turns=len(g), MeanNodes=float(g["nodes"].mean()),
                   MeanUnique=float(g["unique"].mean()), DupShare=float(dup), DupBasis=basis,
                   MeanPlanMillis=float(g["plan_ms"].mean()), MeanUnits=float(g["units"].mean()),
                   TerminalShare=float(g["term"].mean()))
        for c in sorted(c for c in g.columns if c.startswith("t_")):
            if g[c].notna().any():
                row[c] = float(g[c].mean())
        rows.append(row)
    df = frame(rows, ["Budget", "Arm"])
    if df.empty:
        return df
    base = df[df.Arm == baseline].set_index("Budget")["MeanNodes"]
    df.insert(5, "NodesVsBase", [r.MeanNodes / base[r.Budget] if r.Budget in base.index else np.nan
                                 for r in df.itertuples(index=False)])
    return df


# --------------------------------------------------------------------------- design: variance
def variance(t, baseline, split="") -> pd.DataFrame:
    """The ICC of each paired difference and what a scenario and a visit each buy (stats.py)."""
    rows = []
    for budget in sorted(t.budget.unique()):
        if not ((t.arm == baseline) & (t.budget == budget)).any():
            continue
        for arm in arms_at(t, budget, baseline):
            j = paired(t, arm, baseline, budget)
            for s in STRATA:
                c = variance_components(stratum(j, s))
                if c is not None:
                    rows.append(dict(Budget=int(budget), Arm=arm, Stratum=s, **c, **design_advice(c)))
    return frame(rows, ["Budget", "Arm", "Stratum"])


# --------------------------------------------------------------------------- 4: dev/holdout side by side
def replicates(dev_lo, dev_hi, hold_mean, hold_lo, hold_hi, null=0.0) -> str:
    """`yes` when the holdout point lands inside the development interval and the holdout interval
    excludes the null; `ns both` when neither interval excludes it (a null that replicated); else `no`."""
    if any(not np.isfinite(x) for x in (dev_lo, dev_hi, hold_mean, hold_lo, hold_hi)):
        return "n/a"
    tol = 1e-9 * max(1.0, abs(dev_lo), abs(dev_hi))           # a degenerate interval must contain its own point
    dev_sig = dev_lo > null or dev_hi < null
    hold_sig = hold_lo > null or hold_hi < null
    if dev_lo - tol <= hold_mean <= dev_hi + tol and hold_sig:
        return "yes"
    if not dev_sig and not hold_sig:
        return "ns both"
    return "no"


def replication(dev: dict, hold: dict) -> pd.DataFrame:
    """#4 every headline, contrast and multiplier row that both splits have, side by side."""
    rows = []
    specs = (("headline", ["Budget", "Arm", "Stratum"], lambda r: r["Arm"], ("MeanDiff", "Lo", "Hi"), 0.0),
             ("contrast", ["Budget", "A", "B", "Stratum"], lambda r: r["Label"], ("MeanDiff", "Lo", "Hi"), 0.0),
             ("multiplier", ["Budget", "Arm"], lambda r: r["Arm"], ("Multiplier", "Lo", "Hi"), 1.0))
    tables = {"headline": "headline", "contrast": "contrasts", "multiplier": "budget_multiplier"}
    for kind, keys, name, (m, lo, hi), null in specs:
        d, h = dev.get(tables[kind]), hold.get(tables[kind])
        if d is None or h is None or d.empty or h.empty:
            continue
        j = d.merge(h, on=keys, suffixes=("_dev", "_hold"))
        for r in j.to_dict("records"):
            rd = {k: r[k] for k in keys}
            nm = name({**r, "Label": r.get("Label_dev", r.get("Label"))})
            rows.append(dict(Kind=kind, Budget=int(r["Budget"]), Name=nm,
                             Stratum=r.get("Stratum", "no-terminal"),
                             A=rd.get("A", rd.get("Arm")), B=rd.get("B", r.get("Baseline_dev", "")),
                             DevMean=r[f"{m}_dev"], DevLo=r[f"{lo}_dev"], DevHi=r[f"{hi}_dev"],
                             HoldMean=r[f"{m}_hold"], HoldLo=r[f"{lo}_hold"], HoldHi=r[f"{hi}_hold"],
                             Replicates=replicates(r[f"{lo}_dev"], r[f"{hi}_dev"], r[f"{m}_hold"],
                                                   r[f"{lo}_hold"], r[f"{hi}_hold"], null)))
    return frame(rows)


def terminal_rates(t) -> pd.DataFrame:
    rows = [dict(Budget=int(b), Arm=a, Turns=len(g), TerminalShare=float(g["term"].mean()))
            for (b, a), g in t.groupby(["budget", "arm"])]
    return frame(rows, ["Budget", "Arm"])
