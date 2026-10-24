"""The measures, one function each.

Every comparison is PAIRED per (budget, scenario, visit): every arm replans the same positions with
the same seeds, so an arm's effect is the distribution of final(arm) - final(baseline) over the pairs
it shares with the baseline, and every interval resamples scenarios (stats.py). Tables that a terminal
position can dominate exist in two strata: `all` and `no-terminal` (pairs where neither side's turn
reached a terminal state, worth about +/-2e9).

    headline           the paired difference against the baseline, with Cohen's d
    budget_multiplier  how much budget the baseline would need to match an arm (and in its seconds)
    wallclock          each arm re-scored against the baseline at the wall-clock it spent
    contrasts          the spec's own paired comparisons
    variance           what a scenario and a visit each buy (the design)
    reprice            each turn's work priced in another machine's currency, re-scored
    across             the same experiment on two machines: each one's means, multipliers and seconds
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


# --------------------------------------------------------------------------- headline
def headline(t, baseline, split="", n=N_BOOT) -> pd.DataFrame:
    """The paired difference against the baseline, per budget and arm, with win/loss/tie counts,
    per-source intervals (a pooled mean can hide where an effect lives), and Cohen's d on the paired
    differences."""
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


# --------------------------------------------------------------------------- contrasts
def contrasts(t, pairs, split="", n=N_BOOT) -> pd.DataFrame:
    """Paired a-minus-b differences for the spec's contrasts, at every budget where both arms ran."""
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
                rows.append(dict(Budget=int(budget), Label=c["label"], A=c["a"], B=c["b"], Stratum=s,
                                 Pairs=len(d), Scenarios=k, MeanDiff=m, Lo=lo, Hi=hi, D=cohens_d(d["diff"]),
                                 WinRate=float((d["diff"] > 0).sum() / max(1, int((d["diff"] != 0).sum())))))
    return frame(rows, ["Budget"])


# --------------------------------------------------------------------------- budget multiplier
def locate(M: np.ndarray, A: np.ndarray):
    """Where each arm value A sits on its baseline curve M (rows: resamples, columns: budget levels).

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
    """The equivalent budget multiplier: how much budget the baseline would need to match the arm.

    The plan's value (`final`) on the (scenario, visit) cells where the baseline ran at every one of its
    levels and the arm ran, none of them terminal. The baseline's mean value per level is interpolated
    log-linearly in budget between adjacent levels; the arm's mean value is solved for the baseline
    budget that reaches it; the multiplier is that budget over the arm's own. Values and not search gains
    (final - initial): an arm's `initial` is its own starting plan, so a gain does not compare across
    arms, while a value on the same cells does, and gives the gain's answer for an arm that starts as the
    baseline does. The interval resamples scenarios. A crossing past the top of the ladder is
    `extrapolated` (a lower bound under diminishing returns), one below the bottom `outside`.

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
            j = t[(t.arm == arm) & (t.budget == budget) & ~t.term][KEYS + ["final", "plan_ms"]]
            j = j.rename(columns={"final": "va", "plan_ms": "ta"})
            for k, b in enumerate(levels):
                x = t[(t.arm == baseline) & (t.budget == b) & ~t.term][KEYS + ["final", "plan_ms"]]
                j = j.merge(x.rename(columns={"final": f"v{k}", "plan_ms": f"t{k}"}), on=KEYS)
            if j.empty:
                continue
            cols = [f"v{k}" for k in range(L)] + ["va"] + [f"t{k}" for k in range(L)] + ["ta"]
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
                ArmFinal=float(A[0]),
                BaseFinals=";".join(f"{lv}:{g:.4f}" for lv, g in zip(levels, M[0])),
                Multiplier=float(mult[0]), Lo=lo, Hi=hi, EquivBudget=float(mult[0] * budget), Flag=flag,
                BootUndefined=float(np.mean(~np.isfinite(out["boot"][0]))),
                ArmMs=float(ta[0]),
                BaseMs=";".join(f"{lv}:{x:.1f}" for lv, x in zip(levels, T[0])),
                ArmTimeRatio=float(ta[0] / T[0, same]) if same is not None and T[0, same] > 0 else np.nan,
                TimeMultiplier=float(tmult[0]), TimeLo=tlo, TimeHi=thi))
    return frame(rows, ["Budget", "Arm"])


# --------------------------------------------------------------------------- wall-clock, and re-pricing
def rescore(t, baseline, x, split="", n=N_BOOT, key="wallclock", min_pairs=10) -> pd.DataFrame:
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


def wallclock(t, baseline, head: pd.DataFrame, split="", n=N_BOOT) -> pd.DataFrame:
    """Wall-clock fairness: each arm re-scored against the baseline at the wall-clock it actually spent.

    The currency is calibrated idle and single-threaded, so under other conditions a unit budget can buy
    one arm more time than another. Needs the baseline at >= 2 budgets. An arm that wins in units but not
    here is unresolved: a bookkeeping artefact until shown otherwise."""
    w = rescore(t, baseline, "plan_ms", split, n, key="wallclock")
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
    the baseline's value-versus-units curve in that currency, as the wall-clock table re-scores against time.

    What re-pricing cannot do is re-run the stop rule: every search stopped when it had spent its
    budget under the weights it ran with, so a re-priced arm did not do the work it would have done
    under the new prices. Returns (re-scored table, spend per leaf, notes).

    A leaf's work is its forward-model counts and one `op_<name>` count per operation its arm declares. It
    is re-priced only when the other file prices exactly the operations the leaf counts; otherwise it is
    named and skipped, since one side's work would be priced on an operation the other never counted."""
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
        counted = sorted(c[3:] for c in g.columns if c.startswith("op_") and g[c].notna().any())
        priced = sorted(o for o in w if o not in ("advance", "copy", "eval"))
        if counted != priced:
            only_leaf, only_file = sorted(set(counted) - set(priced)), sorted(set(priced) - set(counted))
            notes.append(f"{arm}@{budget}: " + "; ".join(
                ([f"the leaf counts {only_leaf}, which {weights['name']} does not price"] if only_leaf else [])
                + ([f"{weights['name']} prices {only_file}, which the leaf does not count"] if only_file else []))
                + ", so it is not re-priced: the two currencies price different work")
            continue
        ux = g["advances"] * w["advance"] + g["copies"] * w["copy"] + g["evals"] * w["eval"]
        for op in counted:
            ux = ux + g["op_" + op] * w[op]
        t.loc[idx, "units_x"] = ux
        spend.append(dict(Budget=int(budget), Arm=arm, Turns=len(g), MeanUnits=float(g["units"].mean()),
                          MeanRepricedUnits=float(ux.mean()), Ratio=float(ux.mean() / g["units"].mean())
                          if g["units"].mean() else np.nan))
    table = rescore(t, baseline, "units_x", split, n, key="reprice")
    if not table.empty:
        table = table.rename(columns={"Diff": "RepricedDiff", "MeanSpend": "MeanRepricedUnits"})
    return table, frame(spend, ["Budget", "Arm"]), notes


# --------------------------------------------------------------------------- across machines
def across(ta, tb, baseline, split="", n=N_BOOT, leaves_a=None, weights_b=None):
    """The same experiment on two machines, A and B, each priced in its own currency, per budget and arm.

    On the (scenario, visit) cells both machines ran, neither turn terminal: each machine's mean final value
    with a scenario-clustered interval, and the paired difference A - B. The chance events are the same on
    both, so the difference is what the two currencies' prices did to where each search stopped. Beside
    them: each machine's budget multiplier, which is dimensionless, so the two should agree; each one's
    seconds per turn; and, given B's currency, A's turns re-priced in it (`RepricedRatio`: the units A's
    work costs at B's prices over the units it cost at A's). Returns (table, notes)."""
    def by_key(df):
        return {} if df.empty else {(int(r.Budget), r.Arm): r for r in df.itertuples(index=False)}

    ma, mb = by_key(budget_multiplier(ta, baseline, split, n)), by_key(budget_multiplier(tb, baseline, split, n))
    spend, notes = {}, []
    if weights_b is not None and leaves_a is not None:
        _, sp, notes = reprice(ta, leaves_a, weights_b, baseline, split, n)
        spend = by_key(sp)
    rows = []
    for budget in sorted(set(ta.budget.unique()) & set(tb.budget.unique())):
        for arm in sorted(set(ta[ta.budget == budget].arm) & set(tb[tb.budget == budget].arm)):
            x = ta[(ta.arm == arm) & (ta.budget == budget)]
            y = tb[(tb.arm == arm) & (tb.budget == budget)]
            j = x.merge(y, on=KEYS, suffixes=("", "_b"))
            j = j[~(j["term"] | j["term_b"])].copy()
            if j.empty:
                continue
            j["diff"] = j["final"] - j["final_b"]
            key = ("across", split, budget, arm)
            m_a, lo_a, hi_a, k = cluster_boot_ci(j, "final", n=n, key=key)
            m_b, lo_b, hi_b, _ = cluster_boot_ci(j, "final_b", n=n, key=key)
            d, d_lo, d_hi, _ = cluster_boot_ci(j, "diff", n=n, key=key)
            row = dict(Budget=int(budget), Arm=arm, Pairs=len(j), Scenarios=k, MeanA=m_a, LoA=lo_a, HiA=hi_a,
                       MeanB=m_b, LoB=lo_b, HiB=hi_b, Diff=d, DiffLo=d_lo, DiffHi=d_hi)
            for side, m in (("A", ma), ("B", mb)):
                r = m.get((int(budget), arm))
                row[f"Mult{side}"] = r.Multiplier if r is not None else np.nan
                row[f"Mult{side}Lo"] = r.Lo if r is not None else np.nan
                row[f"Mult{side}Hi"] = r.Hi if r is not None else np.nan
            row["SecondsA"] = float(j["plan_ms"].mean() / 1000.0)
            row["SecondsB"] = float(j["plan_ms_b"].mean() / 1000.0)
            r = spend.get((int(budget), arm))
            row["RepricedRatio"] = r.Ratio if r is not None else np.nan
            rows.append(row)
    return frame(rows, ["Budget", "Arm"]), notes


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


def terminal_rates(t) -> pd.DataFrame:
    rows = [dict(Budget=int(b), Arm=a, Turns=len(g), TerminalShare=float(g["term"].mean()))
            for (b, a), g in t.groupby(["budget", "arm"])]
    return frame(rows, ["Budget", "Arm"])
