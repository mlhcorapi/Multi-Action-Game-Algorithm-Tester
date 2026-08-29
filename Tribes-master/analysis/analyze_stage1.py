#!/usr/bin/env python3
"""
STAGE 1 analysis: does within-turn learned action bias IMPROVE the search?

Input: test_results/stage1/<Config>/<cu>cu/{results.csv, turn_telemetry.csv, seqlog_*.csv.gz}
written by core.data.exp.Stage1Runner. Every (scenario, visit) is seeded identically across
configs (the v6 visitSeed convention), so all comparisons are PAIRED per (cu, scenario, visit):
a config's effect is the distribution of FinalValue(config) - FinalValue(Baseline) over pairs.

Outputs (analysis/stage1_results/):
  W1 headline.csv        paired mean diff vs Baseline + scenario-clustered bootstrap 95% CI,
                         win/loss/tie sign counts, per (config, cu); also split by scenario source
  W2 dose_response       effect vs epsilon at the main budget (the exploitation-strength curve)
  W3 contrasts.csv       the four design contrasts, each as config-A-minus-config-B paired diffs:
                         Mid vs Coarse | removal on vs off | continuous vs per-burn |
                         shortlist-on-top-of-bias vs bias alone
  W4 efficiency.csv      NodesEvaluated / UniqueSequences / duplicate share per config
                         (the shortlist reinvestment + the diversity-collapse guard-rail)
  W5 anytime.csv         paired diff vs Baseline by bridge-burn phase (when does bias start paying)
  W6 stage2_pairs        from Baseline seqlogs: are UNORDERED pair interaction estimates
                         Dxy = E[V|x,y] - E[V|x,~y] - E[V|~x,y] + E[V|~x,~y]
                         reliable within a turn (odd/even split-half over pairs), and which pairs
                         carry the largest pooled synergy? (Stage-2 groundwork, observation-only.)

Aggregation rule everywhere: statistic per pair (or per turn), then mean with a bootstrap CI that
resamples SCENARIOS (the natural cluster: visits of one scenario share a state). No scipy.

Usage: python analysis/analyze_stage1.py [test_results/stage1] [--outdir analysis/stage1_results]
"""

import os
import sys
import glob
import argparse
import itertools

import numpy as np
import pandas as pd

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAVE_PLT = True
except Exception:
    HAVE_PLT = False

BASELINE = "Baseline"
N_BOOT = 4000
RNG = np.random.default_rng(20260823)

# Stage-2 knobs
PAIR_TOP_KEYS = 22          # most frequent keys per turn considered for pairs
PAIR_MIN_CELL = 8           # min children in each of the four presence cells
PAIR_MIN_TURN_CHILDREN = 150


# --------------------------------------------------------------------------- helpers
def cluster_boot_ci(df, value_col, cluster_col="ScenarioId", n=N_BOOT):
    """Mean + bootstrap 95% CI resampling clusters (scenarios)."""
    v = df[[cluster_col, value_col]].dropna()
    if v.empty:
        return np.nan, np.nan, np.nan, 0
    groups = [g[value_col].to_numpy() for _, g in v.groupby(cluster_col)]
    k = len(groups)
    means = np.empty(n)
    for i in range(n):
        idx = RNG.integers(0, k, k)
        means[i] = np.concatenate([groups[j] for j in idx]).mean()
    return float(v[value_col].mean()), float(np.quantile(means, 0.025)), \
        float(np.quantile(means, 0.975)), k


def spearman(x, y):
    x = pd.Series(x).rank(method="average").to_numpy(float)
    y = pd.Series(y).rank(method="average").to_numpy(float)
    if len(x) < 3 or x.std() == 0 or y.std() == 0:
        return np.nan
    return float(np.corrcoef(x, y)[0, 1])



def sorted_frame(rows, by):
    """DataFrame(rows) sorted by `by`, tolerating no rows at all.

    A stage that has not run yet produces no pairs — Stage B on its own has no Baseline to pair
    against until Stage D's grid exists — and sorting an empty frame by columns it therefore lacks
    raises an opaque KeyError. An empty result is a legitimate state here, not a failure.
    """
    df = pd.DataFrame(rows)
    return df.sort_values(by) if not df.empty else df


# --------------------------------------------------------------------------- load
def load(indir):
    tele, res = [], []
    for p in glob.glob(os.path.join(indir, "*", "*cu", "turn_telemetry.csv")):
        try:
            tele.append(pd.read_csv(p).assign(
                Budget=int(os.path.basename(os.path.dirname(p)).replace("cu", ""))))
        except Exception as e:
            print(f"  WARN unreadable {p}: {e}")
    for p in glob.glob(os.path.join(indir, "*", "*cu", "results.csv")):
        try:
            res.append(pd.read_csv(p).assign(
                Budget=int(os.path.basename(os.path.dirname(p)).replace("cu", ""))))
        except Exception as e:
            print(f"  WARN unreadable {p}: {e}")
    if not tele:
        sys.exit(f"No turn_telemetry.csv under {indir} — run Stage1Runner first.")
    t = pd.concat(tele, ignore_index=True)
    r = pd.concat(res, ignore_index=True) if res else pd.DataFrame()
    t["Gain"] = t.FinalValue - t.InitialValue
    return t, r


def paired(t, config, budget):
    """Join config rows to Baseline rows on (ScenarioId, VisitId) at one budget."""
    a = t[(t.Config == config) & (t.Budget == budget)]
    b = t[(t.Config == BASELINE) & (t.Budget == budget)]
    j = a.merge(b, on=["ScenarioId", "VisitId", "ScenarioSource"], suffixes=("", "_base"))
    j["Diff"] = j.FinalValue - j.FinalValue_base
    # Normalised: improvement relative to what Baseline search itself added on this turn.
    j["RelDiff"] = j.Diff / j.Gain_base.abs().clip(lower=1.0)
    return j


# --------------------------------------------------------------------------- analyses
def run_headline(t):
    rows = []
    for budget in sorted(t.Budget.unique()):
        configs = [c for c in t[t.Budget == budget].Config.unique() if c != BASELINE]
        for cfg in sorted(configs):
            j = paired(t, cfg, budget)
            if j.empty:
                continue
            m, lo, hi, k = cluster_boot_ci(j, "Diff")
            rm, rlo, rhi, _ = cluster_boot_ci(j, "RelDiff")
            wins = int((j.Diff > 0).sum()); losses = int((j.Diff < 0).sum())
            row = dict(Budget=budget, Config=cfg, Pairs=len(j), Scenarios=k,
                       MeanDiff=m, Lo=lo, Hi=hi,
                       RelDiff=rm, RelLo=rlo, RelHi=rhi,
                       Wins=wins, Losses=losses, Ties=len(j) - wins - losses,
                       WinRate=wins / max(1, wins + losses))
            for src in j.ScenarioSource.unique():
                js = j[j.ScenarioSource == src]
                row[f"MeanDiff_{src}"] = float(js.Diff.mean())
            rows.append(row)
    return sorted_frame(rows, ["Budget", "Config"])


def run_contrasts(t):
    """Design contrasts as paired A-minus-B at the main budget (fallback: any shared budget)."""
    pairs = [("Granularity Mid>Coarse", "Bias_e25", "Coarse_e25"),
             ("RemovalCredit on>off", "Bias_e25", "NoRemoval_e25"),
             ("Continuous>PerBurn", "Bias_e25", "PerBurn_e25"),
             ("Shortlist15 alone>Baseline", "Shortlist15", BASELINE),
             ("Short15+Bias>Bias alone", "Short15_e25", "Bias_e25"),
             ("Short5>Short15 (given bias)", "Short5_e25", "Short15_e25")]
    rows = []
    for budget in sorted(t.Budget.unique()):
        d = t[t.Budget == budget]
        for label, ca, cb in pairs:
            a = d[d.Config == ca]
            b = d[d.Config == cb]
            if a.empty or b.empty:
                continue
            j = a.merge(b, on=["ScenarioId", "VisitId"], suffixes=("", "_b"))
            j["Diff"] = j.FinalValue - j.FinalValue_b
            m, lo, hi, k = cluster_boot_ci(j, "Diff")
            rows.append(dict(Budget=budget, Contrast=label, A=ca, B=cb, Pairs=len(j),
                             MeanDiff=m, Lo=lo, Hi=hi,
                             WinRate=float((j.Diff > 0).sum() / max(1, (j.Diff != 0).sum()))))
    return pd.DataFrame(rows)


def run_efficiency(t):
    rows = []
    for (budget, cfg), g in t.groupby(["Budget", "Config"]):
        me = g.MutEvents.replace(0, np.nan)
        rows.append(dict(
            Budget=budget, Config=cfg, Turns=len(g),
            MeanNodes=float(g.NodesEvaluated.mean()),
            MeanUnique=float(g.UniqueSequences.mean()),
            DupShare=float((g.MutChildDuplicates / me).mean()),
            RepairReplacedPerTurn=float(g.MutRepairReplaced.mean()),
            GreedyPickShare=float((g.BiasGreedyPicks
                                   / (g.BiasGreedyPicks + g.BiasUniformPicks).replace(0, np.nan)).mean()),
            ShortlistCallsPerTurn=float(g.ShortlistCalls.mean()),
            MeanPlanMillis=float(g.PlanMillis.mean())))
    df = sorted_frame(rows, ["Budget", "Config"])
    base = df[df.Config == BASELINE].set_index("Budget")
    df["NodesVsBase"] = df.apply(
        lambda r: r.MeanNodes / base.loc[r.Budget].MeanNodes if r.Budget in base.index else np.nan,
        axis=1)
    return df


def run_w7(t):
    """W7 - wall-clock fairness. CO-PRIMARY since Gate A3' (2026-08-29).

    The compute-unit currency is calibrated idle and single-threaded, and A3' measured the Z0
    family's relative op cost rising ~24% under a loaded machine (a cache-light arm loses its idle
    cache advantage to 48-way L3 contention), so a unit budget subsidizes exactly those arms at run
    conditions. This re-scores every arm against Baseline INTERPOLATED AT THE ARM'S OWN PlanMillis:
    Baseline's (PlanMillis, FinalValue) points across the budget ladder form a value-vs-time curve
    per (scenario, visit), and the arm is compared with that curve at the wall-clock it actually
    spent. Paired per (scenario, visit), scenario-clustered CI, terminals excluded (a +/-2e9
    terminal would dominate the interpolation).

    Needs Baseline at >= 2 budgets to have a curve, so it is empty on a Stage-B-only tree and
    fills in retroactively once Stage D adds the other levels. An arm that wins in units but not
    here is a bookkeeping artefact, not an algorithm.
    """
    base = t[(t.Config == BASELINE) & (t.FinalValue.abs() < TERMINAL_VALUE_CUTOFF)]
    if base.empty:
        return pd.DataFrame()
    curves = {}
    for (s, v), g in base.groupby(["ScenarioId", "VisitId"]):
        g = g.sort_values("PlanMillis")
        if len(g) >= 2:
            curves[(s, v)] = (g.PlanMillis.to_numpy(float), g.FinalValue.to_numpy(float))
    if not curves:
        return pd.DataFrame()
    rows = []
    arms = t[(t.Config != BASELINE) & (t.FinalValue.abs() < TERMINAL_VALUE_CUTOFF)]
    for (budget, cfg), g in arms.groupby(["Budget", "Config"]):
        recs = []
        for r in g.itertuples():
            c = curves.get((r.ScenarioId, r.VisitId))
            if c is None:
                continue
            xs, ys = c
            x = float(r.PlanMillis)
            recs.append((r.ScenarioId, r.FinalValue - float(np.interp(x, xs, ys)),
                         x < xs[0] or x > xs[-1]))
        if len(recs) < 10:
            continue
        j = pd.DataFrame(recs, columns=["ScenarioId", "WallDiff", "Clamped"])
        m, lo, hi, k = cluster_boot_ci(j, "WallDiff")
        rows.append(dict(Budget=budget, Config=cfg, Pairs=len(j), Scenarios=k,
                         WallDiff=m, WallLo=lo, WallHi=hi,
                         ClampedShare=float(j.Clamped.mean()),
                         MeanArmMillis=float(g.PlanMillis.mean())))
    return sorted_frame(rows, ["Budget", "Config"])


def run_anytime(r):
    """Paired diff vs Baseline of BestSequenceValue at each burn phase."""
    if r.empty:
        return pd.DataFrame()
    rows = []
    for budget in sorted(r.Budget.unique()):
        d = r[r.Budget == budget]
        b = d[d.Config == BASELINE][["ScenarioId", "VisitId", "BridgeBurningPhaseNumber",
                                     "BestSequenceValue"]]
        for cfg in sorted(c for c in d.Config.unique() if c != BASELINE):
            a = d[d.Config == cfg][["ScenarioId", "VisitId", "BridgeBurningPhaseNumber",
                                    "BestSequenceValue"]]
            j = a.merge(b, on=["ScenarioId", "VisitId", "BridgeBurningPhaseNumber"],
                        suffixes=("", "_b"))
            j["Diff"] = j.BestSequenceValue - j.BestSequenceValue_b
            for ph, g in j.groupby("BridgeBurningPhaseNumber"):
                rows.append(dict(Budget=budget, Config=cfg, Phase=int(ph),
                                 MeanDiff=float(g.Diff.mean()), Pairs=len(g)))
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------- variance design
# Gate B question 2: how much precision does a scenario buy, and how much does a visit?
#
# Visits of one scenario share a state, so they are not independent observations. Under the
# scenario-clustered bootstrap the standard error of a paired mean is
#
#     SE(n_c, v)^2  =  sigma_b^2 / n_c  +  sigma_w^2 / (n_c * v)
#
# Only the first term falls when scenarios are added; visits attack only the second. Two consequences
# follow, and the whole point of measuring rather than assuming is that both depend on the ratio
# between the two variances, which nobody has measured on a corrected tree:
#
#   1. For a FIXED number of turns T = n_c * v, SE^2 = (v * sigma_b^2 + sigma_w^2) / T, which is
#      increasing in v whenever sigma_b^2 > 0. Fewer visits and more scenarios is then always better,
#      for any positive between-scenario variance.
#   2. With the scenario count capped by the portfolio, extra visits approach a floor of
#      sqrt(sigma_b^2 / n_c) and cannot go below it however many are run.

TERMINAL_VALUE_CUTOFF = 1e9     # a detected terminal is worth about +/-2e9


def variance_components(df, value_col="Diff", cluster_col="ScenarioId"):
    """Unbalanced one-way random-effects decomposition of a paired difference."""
    v = df[[cluster_col, value_col]].dropna()
    k = v[cluster_col].nunique()
    n = len(v)
    if k < 2 or n <= k:
        return None
    grand = v[value_col].mean()
    g = v.groupby(cluster_col)[value_col]
    sizes = g.size().to_numpy(float)
    means = g.mean().to_numpy(float)
    ssb = float((sizes * (means - grand) ** 2).sum())
    ssw = float(((v[value_col] - v[cluster_col].map(g.mean())) ** 2).sum())
    msb, msw = ssb / (k - 1), ssw / (n - k)
    # n_0 is the effective group size; it reduces to v exactly when the design is balanced.
    n0 = (n - (sizes ** 2).sum() / n) / (k - 1)
    sw2 = msw
    sb2 = max(0.0, (msb - msw) / n0) if n0 > 0 else 0.0
    total = sb2 + sw2
    return dict(Pairs=n, Clusters=k, MeanVisits=n / k,
                SigmaB2=sb2, SigmaW2=sw2, ICC=(sb2 / total if total > 0 else np.nan),
                SE=float(np.sqrt(sb2 / k + sw2 / n)))


def _design_advice(c):
    """Floor, distance from it, and what the visits were worth in scenarios."""
    k, vbar, sb2, sw2 = c["Clusters"], c["MeanVisits"], c["SigmaB2"], c["SigmaW2"]
    out = dict(SEFloor=float(np.sqrt(sb2 / k)) if sb2 > 0 else 0.0)
    if sb2 <= 0:
        # No detectable between-scenario variance: visits are as good as scenarios, and the ceiling
        # on precision is only the total turn count.
        out.update(ExcessOverFloorPct=np.nan, VisitsWithin5Pct=np.nan,
                   EquivClustersAtV1=np.nan, TurnsUsed=c["Pairs"], TurnsEquiv=np.nan)
        return out
    ratio = sw2 / sb2
    out["ExcessOverFloorPct"] = 100.0 * (np.sqrt(1 + ratio / vbar) - 1)
    out["VisitsWithin5Pct"] = float(np.ceil(ratio / (1.05 ** 2 - 1)))
    # Scenarios at one visit each that would match this design's precision.
    equiv = k * (sb2 + sw2) / (sb2 + sw2 / vbar)
    out["EquivClustersAtV1"] = float(equiv)
    out["TurnsUsed"] = c["Pairs"]
    out["TurnsEquiv"] = float(equiv)
    return out


def run_variance(t):
    rows = []
    for budget in sorted(t.Budget.unique()):
        for config in [c for c in t[t.Budget == budget].Config.unique() if c != BASELINE]:
            j = paired(t, config, budget)
            if j.empty:
                continue
            for stratum, d in (("all", j),
                               ("no-terminal", j[(j.FinalValue.abs() < TERMINAL_VALUE_CUTOFF)
                                                 & (j.FinalValue_base.abs() < TERMINAL_VALUE_CUTOFF)])):
                c = variance_components(d)
                if c is None:
                    continue
                rows.append(dict(Budget=budget, Config=config, Stratum=stratum,
                                 **c, **_design_advice(c)))
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------- stage 2
def load_seqlogs(indir):
    paths = glob.glob(os.path.join(indir, BASELINE, "*cu", "seqlog_*.csv.gz"))
    frames = []
    for p in paths:
        try:
            frames.append(pd.read_csv(p).assign(
                Budget=int(os.path.basename(os.path.dirname(p)).replace("cu", ""))))
        except Exception:
            pass
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def pair_interactions_for_turn(g):
    """All (x, y) interaction estimates for one turn, per iteration-parity half."""
    keys_per_child = g.Keys.str.split("|").apply(set)
    counts = {}
    for s in keys_per_child:
        for k in s:
            counts[k] = counts.get(k, 0) + 1
    top = sorted(counts, key=counts.get, reverse=True)[:PAIR_TOP_KEYS]
    v = g.ChildValue.to_numpy(float)
    vz = (v - v.mean()) / (v.std() if v.std() > 0 else 1.0)
    parity = (g.Iter.to_numpy() % 2)
    pres = {k: np.fromiter((k in s for s in keys_per_child), bool, len(v)) for k in top}
    out = []
    for x, y in itertools.combinations(top, 2):
        px, py = pres[x], pres[y]
        rec = {}
        for half in (0, 1):
            m = parity == half
            cells = [m & px & py, m & px & ~py, m & ~px & py, m & ~px & ~py]
            if min(c.sum() for c in cells) < PAIR_MIN_CELL:
                rec = None
                break
            mu = [vz[c].mean() for c in cells]
            rec[half] = mu[0] - mu[1] - mu[2] + mu[3]
        if rec is not None:
            out.append((x, y, rec[0], rec[1]))
    return out


def run_stage2(seq):
    if seq.empty:
        return pd.DataFrame(), pd.DataFrame()
    rel_rows, pooled = [], {}
    for (budget, sc, vi), g in seq.groupby(["Budget", "ScenarioId", "VisitId"]):
        if len(g) < PAIR_MIN_TURN_CHILDREN:
            continue
        ests = pair_interactions_for_turn(g)
        if len(ests) >= 8:
            rel_rows.append(dict(Budget=budget, ScenarioId=sc, VisitId=vi, Pairs=len(ests),
                                 SplitHalfRho=spearman([e[2] for e in ests],
                                                       [e[3] for e in ests])))
        for x, y, a, b in ests:
            key = (budget, x, y)
            s = pooled.setdefault(key, [0.0, 0])
            s[0] += (a + b) / 2
            s[1] += 1
    rel = pd.DataFrame(rel_rows)
    rel_sum = []
    if not rel.empty:
        for budget, g in rel.groupby("Budget"):
            m, lo, hi, k = cluster_boot_ci(g, "SplitHalfRho")
            rel_sum.append(dict(Budget=budget, Turns=len(g), MeanSplitHalfRho=m, Lo=lo, Hi=hi,
                                Scenarios=k, MedianPairsPerTurn=float(g.Pairs.median())))
    top_rows = [dict(Budget=b, KeyX=x, KeyY=y, Turns=n, MeanInteractionZ=s / n)
                for (b, x, y), (s, n) in pooled.items() if n >= 5]
    top = pd.DataFrame(top_rows)
    if not top.empty:
        top = top.reindex(top.MeanInteractionZ.abs().sort_values(ascending=False).index)
        top = top.groupby("Budget", group_keys=False).head(25)
    return pd.DataFrame(rel_sum), top


# --------------------------------------------------------------------------- figures
def fig_dose_response(head, outdir, budget):
    if not HAVE_PLT:
        return
    eps_map = {"Bias_e75": 0.75, "Bias_e50": 0.50, "Bias_e25": 0.25, "Bias_e10": 0.10}
    d = head[(head.Budget == budget) & head.Config.isin(eps_map)].copy()
    if d.empty:
        return
    d["Eps"] = d.Config.map(eps_map)
    d = d.sort_values("Eps", ascending=False)
    xs = [1.0] + d.Eps.tolist()
    ys = [0.0] + d.MeanDiff.tolist()
    lo = [0.0] + d.Lo.tolist()
    hi = [0.0] + d.Hi.tolist()
    fig, ax = plt.subplots(figsize=(6.5, 4.2))
    ax.errorbar(xs, ys, yerr=[np.array(ys) - np.array(lo), np.array(hi) - np.array(ys)],
                marker="o", capsize=3)
    ax.axhline(0, color="grey", lw=0.8)
    ax.invert_xaxis()
    ax.set_xlabel("epsilon (1.0 = uniform baseline; smaller = greedier)")
    ax.set_ylabel("paired final-value diff vs Baseline")
    ax.set_title(f"Bias dose-response at {budget}cu")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", f"S1_dose_{budget}.png"), dpi=150)
    plt.close(fig)


def fig_anytime(anytime, outdir, budget, configs):
    if not HAVE_PLT or anytime.empty:
        return
    d = anytime[(anytime.Budget == budget) & anytime.Config.isin(configs)]
    if d.empty:
        return
    fig, ax = plt.subplots(figsize=(7, 4.2))
    for cfg, g in d.groupby("Config"):
        g = g.sort_values("Phase")
        ax.plot(g.Phase, g.MeanDiff, marker="o", ms=3, label=cfg)
    ax.axhline(0, color="grey", lw=0.8)
    ax.set_xlabel("bridge-burn phase")
    ax.set_ylabel("paired best-value diff vs Baseline")
    ax.set_title(f"Anytime profile at {budget}cu")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", f"S1_anytime_{budget}.png"), dpi=150)
    plt.close(fig)


# --------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("indir", nargs="?", default="test_results/stage1")
    ap.add_argument("--outdir", default="analysis/stage1_results")
    args = ap.parse_args()
    os.makedirs(os.path.join(args.outdir, "tables"), exist_ok=True)
    os.makedirs(os.path.join(args.outdir, "figures"), exist_ok=True)

    t, r = load(args.indir)
    print(f"[stage1] {len(t):,} turns | configs {sorted(t.Config.unique())} | "
          f"budgets {sorted(t.Budget.unique())}")
    for b in sorted(t.Budget.unique()):
        if not ((t.Config == BASELINE) & (t.Budget == b)).any():
            print(f"  NOTE: no {BASELINE} arm at {b}cu, so nothing can be paired there. Every "
                  f"contrast in this line is a paired difference against {BASELINE} on the same "
                  f"(scenario, visit) seeds; run it at {b}cu before reading these tables.")

    tables = {}
    tables["headline"] = run_headline(t)
    tables["contrasts"] = run_contrasts(t)
    tables["efficiency"] = run_efficiency(t)
    tables["w7_wallclock"] = run_w7(t)
    tables["variance"] = run_variance(t)
    tables["anytime"] = run_anytime(r)
    seq = load_seqlogs(args.indir)
    print(f"[stage1] seqlog children: {len(seq):,}")
    tables["stage2_pair_reliability"], tables["stage2_top_pairs"] = run_stage2(seq)

    for name, tab in tables.items():
        p = os.path.join(args.outdir, "tables", f"{name}.csv")
        tab.to_csv(p, index=False)
        print(f"  wrote {p} ({len(tab)} rows)")

    main_budget = 80000 if (t.Budget == 80000).any() else int(t.Budget.max())
    fig_dose_response(tables["headline"], args.outdir, main_budget)
    for b in sorted(t.Budget.unique()):
        fig_anytime(tables["anytime"], args.outdir, b, ["Bias_e25", "Short15_e25", "Shortlist15"])

    # SUMMARY.md
    h = tables["headline"]
    lines = ["# Stage 1 — biased mutation results", "",
             "Positive MeanDiff = the config finds BETTER final sequences than Baseline on the",
             "same (scenario, visit) seeds. CI is a scenario-clustered bootstrap.", ""]
    for budget in sorted(h.Budget.unique()):
        lines.append(f"## {budget}cu")
        lines.append("")
        lines.append("| Config | Pairs | MeanDiff [CI] | RelDiff | WinRate |")
        lines.append("|---|---|---|---|---|")
        for _, x in h[h.Budget == budget].iterrows():
            sig = "**" if (x.Lo > 0 or x.Hi < 0) else ""
            lines.append(f"| {x.Config} | {x.Pairs} | {sig}{x.MeanDiff:+.1f} "
                         f"[{x.Lo:+.1f}, {x.Hi:+.1f}]{sig} | {x.RelDiff:+.3f} | {x.WinRate:.3f} |")
        lines.append("")
    # W7 - the co-primary wall-clock check (Gate A3' resolution, 2026-08-29).
    w7 = tables["w7_wallclock"]
    if not w7.empty and not h.empty:
        lines.append("## W7 — wall-clock fairness (CO-PRIMARY)")
        lines.append("")
        lines.append("Units and seconds must agree. `WallDiff` re-scores each arm against Baseline")
        lines.append("interpolated at the arm's own PlanMillis (paired per scenario+visit, terminals")
        lines.append("excluded). A3' measured the idle currency subsidizing the cache-light `Z0_*`")
        lines.append("family by ~24% under load, so a units-only win there is an artefact until it")
        lines.append("survives this column.")
        lines.append("")
        lines.append("| Budget | Config | Units MeanDiff | Wall-clock diff [CI] | Verdict |")
        lines.append("|---|---|---|---|---|")
        hidx = h.set_index(["Budget", "Config"])
        for _, x in w7.iterrows():
            key = (x.Budget, x.Config)
            um = hidx.MeanDiff.get(key, np.nan)
            us = bool(key in hidx.index and hidx.Lo.get(key, np.nan) > 0)
            ws = bool(x.WallLo > 0)
            verdict = ("**wins both**" if us and ws else
                       "units only — UNRESOLVED" if us else
                       "wall-clock only" if ws else "no win")
            clamp = f" (clamped {x.ClampedShare:.0%})" if x.ClampedShare > 0.05 else ""
            lines.append(f"| {int(x.Budget)} | {x.Config} | {um:+.1f} | "
                         f"{x.WallDiff:+.1f} [{x.WallLo:+.1f}, {x.WallHi:+.1f}]{clamp} | {verdict} |")
        lines.append("")
    elif not h.empty and len(h.Budget.unique()) < 2:
        lines.append("## W7 — wall-clock fairness (CO-PRIMARY)")
        lines.append("")
        lines.append("Not computable yet: Baseline exists at only one budget, so it has no")
        lines.append("value-vs-time curve to interpolate against. Re-run this analysis after Stage D")
        lines.append("adds the other levels — every arm then gets its W7 verdict retroactively on the")
        lines.append("same rows.")
        lines.append("")

    # Gate B question 2: what a scenario buys, and what a visit buys.
    vt = tables["variance"]
    if not vt.empty:
        v = vt[vt.Stratum == "all"]
        lines.append("## Design: scenarios vs visits")
        lines.append("")
        lines.append("ICC is the share of the paired difference's variance that sits BETWEEN scenarios.")
        lines.append("The floor is the standard error this scenario count can reach with unlimited")
        lines.append("visits; `Excess` is how far the design actually run sits above it. `=Scen@1v` is")
        lines.append("the number of scenarios at ONE visit each that would match the same precision —")
        lines.append("compare it with the turns actually spent.")
        lines.append("")
        lines.append("| Budget | Config | Clusters | Visits | ICC | SE | Floor | Excess | =Scen@1v | Turns spent |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|")
        for _, x in v.iterrows():
            eq = "-" if not np.isfinite(x.EquivClustersAtV1) else f"{x.EquivClustersAtV1:.0f}"
            ex = "-" if not np.isfinite(x.ExcessOverFloorPct) else f"{x.ExcessOverFloorPct:.1f}%"
            lines.append(f"| {int(x.Budget)} | {x.Config} | {int(x.Clusters)} | {x.MeanVisits:.1f} | "
                         f"{x.ICC:.3f} | {x.SE:.1f} | {x.SEFloor:.1f} | {ex} | {eq} | {int(x.TurnsUsed)} |")
        lines.append("")
        med_icc = float(v.ICC.median())
        need = v.VisitsWithin5Pct.dropna()
        lines.append(f"**Median ICC {med_icc:.3f}.** For a fixed number of turns, "
                     "`SE^2 = (v*sigma_b^2 + sigma_w^2) / T` rises with the visit count whenever the")
        lines.append("between-scenario variance is positive — so scenarios beat visits outright, and the")
        lines.append("only reason to run more than one visit is that the portfolio is finite.")
        if not need.empty:
            lines.append("")
            lines.append(f"**Visits to sit within 5% of this portfolio's floor: {int(need.median())} "
                         f"(median over arms).** Past that, precision comes from more scenarios, not")
            lines.append("more visits — spend the turns on the portfolio instead.")
        lines.append("")
        nt = vt[vt.Stratum == "no-terminal"]
        if not nt.empty:
            drift = (nt.set_index(["Budget", "Config"]).ICC
                     - v.set_index(["Budget", "Config"]).ICC).abs().max()
            lines.append(f"Terminal-excluded sensitivity: the largest ICC shift from dropping pairs "
                         f"where either side detected a terminal is {drift:.3f}.")
            lines.append("")

    sr = tables["stage2_pair_reliability"]
    if not sr.empty:
        lines.append("## Stage-2 groundwork: pair-interaction reliability (Baseline seqlogs)")
        lines.append("")
        for _, x in sr.iterrows():
            lines.append(f"- {int(x.Budget)}cu: split-half rho of pair interactions = "
                         f"{x.MeanSplitHalfRho:+.3f} [{x.Lo:+.3f}, {x.Hi:+.3f}] over "
                         f"{int(x.Turns)} turns")
        lines.append("")
    lines.append("Full tables in `tables/`, figures in `figures/`.")
    with open(os.path.join(args.outdir, "SUMMARY.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  wrote {os.path.join(args.outdir, 'SUMMARY.md')}")


if __name__ == "__main__":
    main()
