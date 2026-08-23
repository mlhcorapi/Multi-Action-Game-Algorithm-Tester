#!/usr/bin/env python3
"""
STAGE 0 analysis: is there an exploitable per-action value signal WITHIN one EMCTS turn?

Input: the mutation-event logs written by core.data.exp.Stage0SignalRunner
(mutlog_*.csv.gz, turns_*.csv). Every row is one mutation: the action removed, the action
inserted, position, repair damage, and the parent/child sequence values. No agent behaviour
was changed to collect them, so everything below is a property of PLAIN EMCTS search.

The credit assigned to an inserted action is the PAIRED DELTA  child - parent  (the two
sequences differ in exactly one edit + repair), not the raw child value: the delta is an
advantage estimate and is immune to the within-turn drift of the value scale. The analyses:

  R1  Split-half reliability, odd/even iterations   -> is there signal AT ALL (drift-free
      ceiling: both halves cover the same part of the turn)?
  R2  Split-half reliability, 1st half -> 2nd half  -> is the signal stable over the turn,
      i.e. usable by a sampler trained on early events? This is the exploitability number.
  R3  Predictive rho vs training fraction           -> how early does it become usable
      (relevant to updating weights at bridge-burn boundaries)?
  R4  Uplift: in the 2nd half, events whose inserted key ranked top-tercile on 1st-half Q-hat
      vs the average event                          -> the direct payoff estimate of biasing.
  R5  Removed-action credit vs inserted-action credit -> can removal evidence (negated)
      double the sample count?
  R6  Exponential-decay ablation on the training half -> does discounting old events help
      or hurt prediction (the drift-vs-data trade the plan doc discusses)?
  R7  Position signal: mean delta by relative mutation position, and its reliability.
  R8  Credit-scheme comparison: paired delta vs raw child value (MAST-style) as the
      training signal for R2-style prediction.

All of R1-R8 are computed per (budget x granularity x repair-filter) where meaningful:
  granularity: Coarse = ACTION type (~25 keys) | Mid = type + actor/tech qualifier
               | Fine = full action string (ids + coordinates)
  filters:     all events | zero-repair (RepairReplaced == 0) | inserted-survived
Duplicate-sequence children are kept (their delta is real); "NONE" insertions are excluded
from inserted-credit analyses.

Correlations are Spearman (pandas rank + Pearson; no scipy dependency). Aggregation is
always: statistic per TURN first, then mean over turns with a bootstrap 95% CI — turns are
the independent units, events within a turn are not.

Usage:  python analysis/analyze_stage0.py [test_results/stage0] [--outdir analysis/stage0_results]
"""

import os
import sys
import glob
import argparse

import numpy as np
import pandas as pd

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAVE_PLT = True
except Exception:
    HAVE_PLT = False

# ----------------------------------------------------------------------------- knobs
MIN_EVENTS_PER_TURN = 40   # turns with fewer usable events are excluded (reported)
MIN_KEY_N = 3              # a key needs >= this many events on EACH side of a split
MIN_KEYS = 5               # a turn needs >= this many usable keys for a correlation
N_BOOT = 2000              # bootstrap resamples for CIs over turns
TRAIN_FRACTIONS = [0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
DECAY_HALF_LIVES = ["inf", 0.5, 0.125]   # fraction of the training half's event count
GRANS = ["Coarse", "Mid", "Fine"]
FILTERS = ["all", "zerorepair", "survived"]
RNG = np.random.default_rng(20260822)


# ----------------------------------------------------------------------------- helpers
def spearman(x, y):
    """Spearman rho via average ranks + Pearson. Returns nan if degenerate."""
    x = pd.Series(x).rank(method="average").to_numpy(float)
    y = pd.Series(y).rank(method="average").to_numpy(float)
    if len(x) < 3 or x.std() == 0 or y.std() == 0:
        return np.nan
    return float(np.corrcoef(x, y)[0, 1])


def boot_ci(vals, n=N_BOOT):
    """Mean and bootstrap 95% CI of a 1-D array (nan-safe)."""
    v = np.asarray([a for a in vals if np.isfinite(a)], float)
    if len(v) == 0:
        return np.nan, np.nan, np.nan, 0
    if len(v) == 1:
        return float(v[0]), np.nan, np.nan, 1
    idx = RNG.integers(0, len(v), size=(n, len(v)))
    means = v[idx].mean(axis=1)
    return float(v.mean()), float(np.quantile(means, 0.025)), float(np.quantile(means, 0.975)), len(v)


def apply_filter(g, filt):
    if filt == "zerorepair":
        return g[g.RepairReplaced == 0]
    if filt == "survived":
        return g[g.InsertedSurvived == 1]
    return g


def key_col(gran):
    return {"Coarse": "InsertedType", "Mid": "InsertedMid", "Fine": "InsertedFull"}[gran]


def split_half_rho(g, kcol, mode):
    """Rho between per-key mean deltas on two halves of one turn's events.
    mode: 'oddeven' or 'temporal' (IterIdx below/above median)."""
    if mode == "oddeven":
        a, b = g[g.Iter % 2 == 0], g[g.Iter % 2 == 1]
    else:
        med = g.Iter.median()
        a, b = g[g.Iter < med], g[g.Iter >= med]
    ma = a.groupby(kcol).Delta.agg(["mean", "count"])
    mb = b.groupby(kcol).Delta.agg(["mean", "count"])
    j = ma[ma["count"] >= MIN_KEY_N].join(mb[mb["count"] >= MIN_KEY_N],
                                          how="inner", lsuffix="_a", rsuffix="_b")
    if len(j) < MIN_KEYS:
        return np.nan, len(j)
    return spearman(j.mean_a, j.mean_b), len(j)


def predictive_rho(train, test, kcol, weights=None, credit="Delta"):
    """Train per-key means on `train` (optionally weighted), then rho between the key's
    Q-hat and the per-key mean REALISED delta in `test`. Test target is always Delta."""
    if weights is not None:
        t = pd.DataFrame({kcol: train[kcol].to_numpy(),
                          "w": np.asarray(weights, float),
                          "wx": np.asarray(weights, float) * train[credit].to_numpy()})
        gs = t.groupby(kcol).agg(wsum=("w", "sum"), wxsum=("wx", "sum"), n=("w", "size"))
        q = pd.DataFrame({"q": gs.wxsum / gs.wsum.replace(0, np.nan), "n": gs.n})
    else:
        q = train.groupby(kcol)[credit].agg(q="mean", n="count")
    q = q[q.n >= MIN_KEY_N]
    r = test.groupby(kcol).Delta.agg(["mean", "count"])
    r = r[r["count"] >= MIN_KEY_N]
    j = q.join(r, how="inner")
    if len(j) < MIN_KEYS:
        return np.nan, len(j)
    return spearman(j.q, j["mean"]), len(j)


def uplift(train, test, kcol):
    """Mean per-event delta in `test` for keys in the top/bottom tercile of train Q-hat,
    minus the mean over all test events with a trained key. Uses z-scored deltas so turns
    pool on a common scale (z is computed per turn upstream)."""
    q = train.groupby(kcol).Delta.agg(q="mean", n="count")
    q = q[q.n >= MIN_KEY_N]
    if len(q) < MIN_KEYS:
        return np.nan, np.nan
    lo, hi = q.q.quantile([1 / 3, 2 / 3])
    known = test[test[kcol].isin(q.index)]
    if len(known) < MIN_KEY_N * MIN_KEYS:
        return np.nan, np.nan
    base = known.DeltaZ.mean()
    top = known[known[kcol].isin(q[q.q >= hi].index)].DeltaZ
    bot = known[known[kcol].isin(q[q.q <= lo].index)].DeltaZ
    return (float(top.mean() - base) if len(top) else np.nan,
            float(bot.mean() - base) if len(bot) else np.nan)


# ----------------------------------------------------------------------------- load
def load(indir):
    mut_paths = sorted(glob.glob(os.path.join(indir, "mutlog_*cu_g*.csv.gz")))
    if not mut_paths:
        sys.exit(f"No mutlog_*.csv.gz under {indir} — run Stage0SignalRunner first.")
    df = pd.concat([pd.read_csv(p) for p in mut_paths], ignore_index=True)
    df["Delta"] = df.ChildValue - df.ParentValue
    df["RelPos"] = df.MutPos / np.maximum(df.SeqLen - 1, 1)
    # A turn = one planning episode of one game at one budget.
    df["TurnId"] = (df.Budget.astype(str) + "|" + df.GameId.astype(str) + "|"
                    + df.Episode.astype(str))
    # z-scored delta within turn, for pooled effect sizes (uplift, position profile)
    g = df.groupby("TurnId").Delta
    df["DeltaZ"] = (df.Delta - g.transform("mean")) / g.transform("std").replace(0, np.nan)

    turn_paths = sorted(glob.glob(os.path.join(indir, "turns_*cu_g*.csv")))
    tf = (pd.concat([pd.read_csv(p) for p in turn_paths], ignore_index=True)
          if turn_paths else pd.DataFrame())
    sp = os.path.join(indir, "games_summary.csv")
    sf = pd.read_csv(sp).drop_duplicates(["Budget", "GameId"], keep="last") \
        if os.path.exists(sp) else pd.DataFrame()
    return df, tf, sf


# ----------------------------------------------------------------------------- analyses
def turns_iter(df, gran, filt):
    """Yield (budget, turn_id, filtered event frame) for turns that clear the floor."""
    kcol = key_col(gran)
    d = df[df.InsertedType != "NONE"]
    d = apply_filter(d, filt)
    for (budget, tid), g in d.groupby(["Budget", "TurnId"], sort=False):
        if len(g) >= MIN_EVENTS_PER_TURN:
            yield budget, tid, g, kcol


def run_reliability(df):
    rows = []
    for gran in GRANS:
        for filt in FILTERS:
            per = {}
            for budget, tid, g, kcol in turns_iter(df, gran, filt):
                r1, k1 = split_half_rho(g, kcol, "oddeven")
                r2, k2 = split_half_rho(g, kcol, "temporal")
                per.setdefault(budget, []).append((r1, r2, k1))
            for budget, vals in per.items():
                r1 = [v[0] for v in vals]
                r2 = [v[1] for v in vals]
                m1, lo1, hi1, n1 = boot_ci(r1)
                m2, lo2, hi2, n2 = boot_ci(r2)
                rows.append(dict(Budget=budget, Gran=gran, Filter=filt,
                                 OddEvenRho=m1, OddEvenLo=lo1, OddEvenHi=hi1, OddEvenTurns=n1,
                                 TemporalRho=m2, TemporalLo=lo2, TemporalHi=hi2, TemporalTurns=n2,
                                 MedianUsableKeys=float(np.nanmedian([v[2] for v in vals]))))
    return pd.DataFrame(rows).sort_values(["Gran", "Filter", "Budget"])


def run_train_fraction(df):
    rows = []
    for gran in GRANS:
        for budget in sorted(df.Budget.unique()):
            for f in TRAIN_FRACTIONS:
                rhos = []
                for b, tid, g, kcol in turns_iter(df[df.Budget == budget], gran, "all"):
                    cut = g.Iter.quantile(f)
                    rho, _ = predictive_rho(g[g.Iter <= cut], g[g.Iter > cut], kcol)
                    rhos.append(rho)
                m, lo, hi, n = boot_ci(rhos)
                rows.append(dict(Budget=budget, Gran=gran, TrainFraction=f,
                                 PredictiveRho=m, Lo=lo, Hi=hi, Turns=n))
    return pd.DataFrame(rows)


def run_uplift(df):
    rows = []
    for gran in GRANS:
        for filt in ["all", "zerorepair"]:
            per = {}
            for budget, tid, g, kcol in turns_iter(df, gran, filt):
                med = g.Iter.median()
                up, down = uplift(g[g.Iter < med], g[g.Iter >= med], kcol)
                per.setdefault(budget, []).append((up, down))
            for budget, vals in per.items():
                mu, lou, hiu, nu = boot_ci([v[0] for v in vals])
                md, lod, hid, nd = boot_ci([v[1] for v in vals])
                rows.append(dict(Budget=budget, Gran=gran, Filter=filt,
                                 TopTercileUpliftZ=mu, TopLo=lou, TopHi=hiu,
                                 BottomTercileUpliftZ=md, BotLo=lod, BotHi=hid, Turns=nu))
    return pd.DataFrame(rows).sort_values(["Gran", "Filter", "Budget"])


def run_credit_comparison(df):
    """R8: paired-delta credit vs raw child-value credit as the training signal."""
    rows = []
    for gran in GRANS:
        for budget in sorted(df.Budget.unique()):
            rd, rc = [], []
            for b, tid, g, kcol in turns_iter(df[df.Budget == budget], gran, "all"):
                med = g.Iter.median()
                tr, te = g[g.Iter < med], g[g.Iter >= med]
                rd.append(predictive_rho(tr, te, kcol, credit="Delta")[0])
                rc.append(predictive_rho(tr, te, kcol, credit="ChildValue")[0])
            md, lod, hid, n = boot_ci(rd)
            mc, loc, hic, _ = boot_ci(rc)
            diff = [a - b for a, b in zip(rd, rc) if np.isfinite(a) and np.isfinite(b)]
            mdiff, lodiff, hidiff, ndiff = boot_ci(diff)
            rows.append(dict(Budget=budget, Gran=gran, DeltaCreditRho=md, DeltaLo=lod, DeltaHi=hid,
                             ChildValueCreditRho=mc, ChildLo=loc, ChildHi=hic,
                             PairedDiff=mdiff, DiffLo=lodiff, DiffHi=hidiff, Turns=n))
    return pd.DataFrame(rows)


def run_decay(df):
    """R6: decay-weight the training half; does prediction of the test half improve?"""
    rows = []
    for gran in GRANS:
        for budget in sorted(df.Budget.unique()):
            per_hl = {hl: [] for hl in DECAY_HALF_LIVES}
            for b, tid, g, kcol in turns_iter(df[df.Budget == budget], gran, "all"):
                med = g.Iter.median()
                tr, te = g[g.Iter < med], g[g.Iter >= med]
                if len(tr) < MIN_EVENTS_PER_TURN // 2:
                    continue
                for hl in DECAY_HALF_LIVES:
                    if hl == "inf":
                        w = None
                    else:
                        h = max(1.0, hl * len(tr))
                        age = tr.Iter.max() - tr.Iter
                        w = np.power(0.5, age / h)
                    per_hl[hl].append(predictive_rho(tr, te, kcol, weights=w)[0])
            for hl, rhos in per_hl.items():
                m, lo, hi, n = boot_ci(rhos)
                rows.append(dict(Budget=budget, Gran=gran, HalfLife=str(hl),
                                 PredictiveRho=m, Lo=lo, Hi=hi, Turns=n))
    return pd.DataFrame(rows)


def run_removed_credit(df):
    """R5: per key, does -mean(delta when removed) agree with +mean(delta when inserted)?"""
    rows = []
    for gran in GRANS:
        kcol_i = key_col(gran)
        kcol_r = {"Coarse": "RemovedType", "Mid": "RemovedMid", "Fine": "RemovedFull"}[gran]
        for budget in sorted(df.Budget.unique()):
            rhos = []
            d = df[(df.Budget == budget)]
            for tid, g in d.groupby("TurnId", sort=False):
                if len(g) < MIN_EVENTS_PER_TURN:
                    continue
                ins = g[g.InsertedType != "NONE"].groupby(kcol_i).Delta.agg(["mean", "count"])
                rem = g[g.RemovedType != "NONE"].groupby(kcol_r).Delta.agg(["mean", "count"])
                ins = ins[ins["count"] >= MIN_KEY_N]
                rem = rem[rem["count"] >= MIN_KEY_N]
                j = ins.join(rem, how="inner", lsuffix="_i", rsuffix="_r")
                if len(j) < MIN_KEYS:
                    continue
                rhos.append(spearman(j.mean_i, -j.mean_r))
            m, lo, hi, n = boot_ci(rhos)
            rows.append(dict(Budget=budget, Gran=gran, InsertVsNegRemovedRho=m,
                             Lo=lo, Hi=hi, Turns=n))
    return pd.DataFrame(rows)


def run_position(df):
    """R7: pooled mean z-delta by relative-position decile + odd/even reliability."""
    d = df[df.InsertedType != "NONE"].copy()
    d["PosBin"] = np.minimum((d.RelPos * 10).astype(int), 9)
    prof = (d.groupby(["Budget", "PosBin"]).DeltaZ.agg(["mean", "count"]).reset_index()
            .rename(columns={"mean": "MeanDeltaZ", "count": "Events"}))
    rhos = {}
    for (budget, tid), g in d.groupby(["Budget", "TurnId"], sort=False):
        if len(g) < MIN_EVENTS_PER_TURN:
            continue
        a = g[g.Iter % 2 == 0].groupby("PosBin").Delta.agg(["mean", "count"])
        b = g[g.Iter % 2 == 1].groupby("PosBin").Delta.agg(["mean", "count"])
        j = a[a["count"] >= MIN_KEY_N].join(b[b["count"] >= MIN_KEY_N],
                                            how="inner", lsuffix="_a", rsuffix="_b")
        if len(j) >= MIN_KEYS:
            rhos.setdefault(budget, []).append(spearman(j.mean_a, j.mean_b))
    rel = pd.DataFrame([dict(Budget=k, PositionOddEvenRho=boot_ci(v)[0],
                             Lo=boot_ci(v)[1], Hi=boot_ci(v)[2], Turns=len(v))
                        for k, v in rhos.items()])
    return prof, rel


def run_sample_sizes(df):
    rows = []
    d = df[df.InsertedType != "NONE"]
    for gran in GRANS:
        kcol = key_col(gran)
        for budget, dd in d.groupby("Budget"):
            per_turn = dd.groupby("TurnId")
            ev = per_turn.size()
            keys = per_turn[kcol].nunique()
            spk = dd.groupby(["TurnId", kcol]).size().groupby("TurnId").median()
            rows.append(dict(Budget=budget, Gran=gran, Turns=len(ev),
                             MedianEventsPerTurn=float(ev.median()),
                             MedianKeysPerTurn=float(keys.median()),
                             MedianSamplesPerKey=float(spk.median()),
                             PctZeroRepair=float((dd.RepairReplaced == 0).mean() * 100),
                             PctSurvived=float((dd.InsertedSurvived == 1).mean() * 100),
                             PctDuplicates=float((dd.Duplicate == 1).mean() * 100)))
    return pd.DataFrame(rows).sort_values(["Gran", "Budget"])


def run_budget_report(tf, df):
    """The 'how long does a budget last on this machine' table the write-up promises."""
    if tf.empty:
        return pd.DataFrame()
    ev = (df.groupby(["Budget", "TurnId"]).size().rename("Events").reset_index()
          .groupby("Budget").Events.median().rename("MedianEventsPerTurn"))
    rep = tf.groupby("Budget").agg(
        Turns=("PlanMillis", "size"),
        MeanPlanMillis=("PlanMillis", "mean"),
        MedianPlanMillis=("PlanMillis", "median"),
        P90PlanMillis=("PlanMillis", lambda s: float(np.quantile(s, 0.9))),
        MeanInitMillis=("InitMillis", "mean"),
        MeanSearchUnits=("SearchUnits", "mean"),
        MeanSearchFmCalls=("SearchFmCalls", "mean"),
        MeanUniqueSequences=("UniqueSequences", "mean"),
    ).join(ev).reset_index()
    return rep


# ----------------------------------------------------------------------------- figures
def fig_reliability(rel, outdir):
    if not HAVE_PLT or rel.empty:
        return
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), sharey=True)
    for ax, (col, lo, hi, title) in zip(axes, [
            ("OddEvenRho", "OddEvenLo", "OddEvenHi", "R1 odd/even (signal ceiling)"),
            ("TemporalRho", "TemporalLo", "TemporalHi", "R2 1st->2nd half (exploitable)")]):
        for gran in GRANS:
            d = rel[(rel.Gran == gran) & (rel.Filter == "all")].sort_values("Budget")
            if d.empty:
                continue
            ax.errorbar(d.Budget, d[col],
                        yerr=[d[col] - d[lo], d[hi] - d[col]],
                        marker="o", capsize=3, label=gran)
        ax.axhline(0, color="grey", lw=0.8)
        ax.set_xscale("log")
        ax.set_xlabel("search budget (compute units)")
        ax.set_title(title)
        ax.grid(alpha=0.3)
    axes[0].set_ylabel("Spearman rho (mean over turns, 95% CI)")
    axes[0].legend(title="granularity")
    fig.suptitle("Within-turn reliability of per-action delta credit")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", "S0_reliability.png"), dpi=150)
    plt.close(fig)


def fig_train_fraction(tfrac, outdir):
    if not HAVE_PLT or tfrac.empty:
        return
    grans = [g for g in GRANS if not tfrac[tfrac.Gran == g].empty]
    fig, axes = plt.subplots(1, len(grans), figsize=(4.2 * len(grans), 4), sharey=True)
    if len(grans) == 1:
        axes = [axes]
    for ax, gran in zip(axes, grans):
        for budget in sorted(tfrac.Budget.unique()):
            d = tfrac[(tfrac.Gran == gran) & (tfrac.Budget == budget)]
            ax.plot(d.TrainFraction, d.PredictiveRho, marker="o", label=f"{budget}cu")
        ax.axhline(0, color="grey", lw=0.8)
        ax.set_title(gran)
        ax.set_xlabel("training fraction of the turn")
        ax.grid(alpha=0.3)
    axes[0].set_ylabel("predictive Spearman rho")
    axes[0].legend(fontsize=8)
    fig.suptitle("R3: how early is the signal usable within a turn?")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", "S0_train_fraction.png"), dpi=150)
    plt.close(fig)


def fig_uplift(up, outdir):
    if not HAVE_PLT or up.empty:
        return
    fig, ax = plt.subplots(figsize=(6.5, 4.2))
    for gran in GRANS:
        d = up[(up.Gran == gran) & (up.Filter == "all")].sort_values("Budget")
        if d.empty:
            continue
        ax.errorbar(d.Budget, d.TopTercileUpliftZ,
                    yerr=[d.TopTercileUpliftZ - d.TopLo, d.TopHi - d.TopTercileUpliftZ],
                    marker="o", capsize=3, label=f"{gran} top-tercile")
    ax.axhline(0, color="grey", lw=0.8)
    ax.set_xscale("log")
    ax.set_xlabel("search budget (compute units)")
    ax.set_ylabel("uplift in per-event delta (z units)")
    ax.set_title("R4: expected gain from favouring 1st-half top-tercile actions")
    ax.grid(alpha=0.3)
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", "S0_uplift.png"), dpi=150)
    plt.close(fig)


def fig_position(prof, outdir):
    if not HAVE_PLT or prof.empty:
        return
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for budget in sorted(prof.Budget.unique()):
        d = prof[prof.Budget == budget]
        ax.plot(d.PosBin / 10 + 0.05, d.MeanDeltaZ, marker="o", label=f"{budget}cu")
    ax.axhline(0, color="grey", lw=0.8)
    ax.set_xlabel("relative mutation position in sequence")
    ax.set_ylabel("mean delta (z units)")
    ax.set_title("R7: payoff by mutation position")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "figures", "S0_position.png"), dpi=150)
    plt.close(fig)


# ----------------------------------------------------------------------------- verdict
def verdict(rel, up):
    """Signal criteria (pre-registered in STAGE0_SIGNAL_PLAN.md): exploitable signal at a
    (budget, granularity) iff temporal rho's CI excludes 0 AND its mean >= 0.15."""
    lines = []
    for gran in GRANS:
        d = rel[(rel.Gran == gran) & (rel.Filter == "all")].sort_values("Budget")
        for _, r in d.iterrows():
            ok = (np.isfinite(r.TemporalRho) and r.TemporalRho >= 0.15 and
                  np.isfinite(r.TemporalLo) and r.TemporalLo > 0)
            u = up[(up.Gran == gran) & (up.Filter == "all") & (up.Budget == r.Budget)]
            uz = float(u.TopTercileUpliftZ.iloc[0]) if len(u) else np.nan
            lines.append(f"| {r.Budget} | {gran} | {r.TemporalRho:+.3f} "
                         f"[{r.TemporalLo:+.3f}, {r.TemporalHi:+.3f}] | {r.OddEvenRho:+.3f} | "
                         f"{uz:+.3f} | {int(r.TemporalTurns)} | "
                         f"{'**SIGNAL**' if ok else 'no'} |")
    return lines


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("indir", nargs="?", default="test_results/stage0")
    ap.add_argument("--outdir", default="analysis/stage0_results")
    args = ap.parse_args()

    os.makedirs(os.path.join(args.outdir, "tables"), exist_ok=True)
    os.makedirs(os.path.join(args.outdir, "figures"), exist_ok=True)

    df, tf, sf = load(args.indir)
    print(f"[stage0] {len(df):,} mutation events | {df.TurnId.nunique()} turns | "
          f"budgets {sorted(df.Budget.unique())}")

    tables = {}
    tables["sample_sizes"] = run_sample_sizes(df)
    tables["budget_wallclock"] = run_budget_report(tf, df)
    tables["reliability"] = run_reliability(df)
    tables["train_fraction"] = run_train_fraction(df)
    tables["uplift"] = run_uplift(df)
    tables["credit_comparison"] = run_credit_comparison(df)
    tables["decay"] = run_decay(df)
    tables["removed_credit"] = run_removed_credit(df)
    prof, posrel = run_position(df)
    tables["position_profile"] = prof
    tables["position_reliability"] = posrel

    for name, t in tables.items():
        p = os.path.join(args.outdir, "tables", f"{name}.csv")
        t.to_csv(p, index=False)
        print(f"  wrote {p} ({len(t)} rows)")

    fig_reliability(tables["reliability"], args.outdir)
    fig_train_fraction(tables["train_fraction"], args.outdir)
    fig_uplift(tables["uplift"], args.outdir)
    fig_position(prof, args.outdir)

    # SUMMARY.md
    lines = ["# Stage 0 — signal check summary", ""]
    if not tables["budget_wallclock"].empty:
        bw = tables["budget_wallclock"].round(1)
        try:
            bw_txt = bw.to_markdown(index=False)          # needs tabulate
        except ImportError:
            bw_txt = "```\n" + bw.to_string(index=False) + "\n```"
        lines += ["## Budget wall-clock on this machine", "", bw_txt, ""]
    lines += ["## Verdict per (budget, granularity)", "",
              "Criteria: exploitable signal iff temporal (1st->2nd half) rho >= 0.15 with "
              "95% CI excluding 0.", "",
              "| Budget | Granularity | Temporal rho [CI] | Odd/even rho | Top-tercile uplift (z) "
              "| Turns | Verdict |",
              "|---|---|---|---|---|---|---|"]
    lines += verdict(tables["reliability"], tables["uplift"])
    lines += ["", "Full tables in `tables/`, figures in `figures/`."]
    with open(os.path.join(args.outdir, "SUMMARY.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  wrote {os.path.join(args.outdir, 'SUMMARY.md')}")
    print("\n".join(lines[-(len(verdict(tables['reliability'], tables['uplift'])) + 3):]))


if __name__ == "__main__":
    main()
