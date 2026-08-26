#!/usr/bin/env python3
"""
Analysis for the v6 FULL-GAMES tournament.

Deliberately small, matching what the phase records: EMCTS's win rate against each experimental
configuration, at each crossover ratio and each compute budget. Search telemetry belongs to the
isolation phase (`analyze_v6_full.py`), which measures it at these same budgets with far more power.

Three things here are not the obvious defaults, and each exists for a reason:

  * Confidence intervals CLUSTER ON MAP. Four of a matchup's 100 games share a map and are therefore
    correlated; a plain binomial interval treats them as four independent observations and understates
    the uncertainty. W1 reports a cluster-robust interval with the map as the cluster.

  * The headline test POOLS over ratios. 100 games gives about +/- 10pp at 50%, which cannot resolve a
    small effect. Pooling the five ratios of a family gives 500 games and about +/- 4pp, and "does
    crossover expansion beat EMCTS at this budget" is the question the write-up actually asks.

  * A PAIRED test is reported alongside the raw rate. Games are generated as mirror pairs: the same
    map, the same seeds, the two agents swapped between the seats. Within a pair, everything except
    who sits where is identical, so winning both sides is strong evidence and splitting is a tie.
    W5 is a sign test over those pairs and is the sharper of the two tests.

There is no EMCTS-vs-EMCTS control arm in the v6 schedule, so W4 is normally empty. What it stood for
-- evidence that no seat or spawn asymmetry survived the orientation swap -- is covered by
NewExperimentSmokeTestV6Full §2 (the swap asserted directly on every map), VerifyV6FullOutput (the
realized schedule) and W6 below, which reports every matchup's win rate by orientation and by seat.
Read W6 in the place the control used to hold: a systematic orientation gap across all 33 arms means
the same thing a control arm off 50% would have meant. W4 still fills in if a tree does contain
self-play rows.

Usage:  python3 analysis/analyze_v6_full_games.py [test_results/v6_full] [--outdir analysis/v6_full_games]
"""

import os
import sys
import math
import glob
import argparse

import numpy as np
import pandas as pd

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAVE_PLT = True
except Exception:                                    # figures are a bonus; tables are the product
    HAVE_PLT = False

Z = 1.959963984540054                                # two-sided 95%

# Games in a complete matchup: FullGameRunnerV6.GAMES_PER_MATCHUP (25 maps x 4 games). Used only to
# flag matchups that are still short, never in an estimate -- every table computes from the rows it
# actually has, so a partially-complete tree analyses correctly, just with wider intervals.
EXPECTED_GAMES_PER_MATCHUP = 100


# ----------------------------------------------------------------------------- loading

def load(base):
    """Every games.csv under `base`, concatenated, with the config columns normalised."""
    paths = sorted(glob.glob(os.path.join(base, "**", "games.csv"), recursive=True))
    if not paths:
        sys.exit(f"No games.csv found under {base}. Has the run started?")

    frames = []
    for p in paths:
        try:
            # keep_default_na=False: the Ratio column uses the literal string "NA" for the configs
            # that take no ratio (EMCTS, CrossoverBB), and pandas would otherwise read that as a
            # missing value -- which then fails the int cast in W3 and silently drops those matchups.
            # No column in this file has genuine missing values, so this is the correct read.
            df = pd.read_csv(p, keep_default_na=False, na_values=[])
        except Exception as e:                       # a torn final row should not stop the analysis
            print(f"  [warn] could not read {p}: {e}")
            continue
        if df.empty:
            continue
        frames.append(df)

    if not frames:
        sys.exit(f"Found {len(paths)} games.csv file(s) under {base} but none had any rows.")

    df = pd.concat(frames, ignore_index=True)
    df["Ratio"] = df["Ratio"].astype(str)
    df["ComputeUnits"] = df["Level"].str.replace("cu", "", regex=False).astype(int)
    df["Config"] = np.where(df["Ratio"] == "NA",
                            df["Opponent"],
                            df["Opponent"] + "@" + df["Ratio"])
    # The opponent config identifies the matchup; EMCTS-vs-EMCTS is the control arm.
    df["IsControl"] = df["Opponent"] == "EMCTS"
    df["Family"] = df["Opponent"]
    return df.sort_values(["ComputeUnits", "Opponent", "Ratio", "GameId"]).reset_index(drop=True)


# ----------------------------------------------------------------------------- intervals

def wilson(wins, n):
    """Wilson score interval. Used when there is no clustering to account for."""
    if n == 0:
        return (float("nan"),) * 3
    p = wins / n
    d = 1 + Z ** 2 / n
    centre = (p + Z ** 2 / (2 * n)) / d
    half = Z * math.sqrt(p * (1 - p) / n + Z ** 2 / (4 * n ** 2)) / d
    return p, max(0.0, centre - half), min(1.0, centre + half)


def clustered_ci(g, cluster="MapIndex", outcome="EmctsWon"):
    """
    95% CI for a win rate whose observations are grouped into clusters (here: maps).

    Uses the standard between-cluster variance estimator: the rate is a ratio of totals, and its
    variance is estimated from how much the clusters' contributions vary about that ratio. With
    equal-sized clusters, as here, it reduces to a design effect on the binomial interval -- but it
    is written in the general form so a partially-complete matchup is still handled correctly.

    Falls back to Wilson when there are too few clusters for a between-cluster estimate to mean
    anything.
    """
    n = len(g)
    if n == 0:
        return float("nan"), float("nan"), float("nan"), 0
    wins = int(g[outcome].sum())
    p = wins / n

    sizes = g.groupby(cluster).size()
    m = len(sizes)
    if m < 3:
        est, lo, hi = wilson(wins, n)
        return est, lo, hi, m

    # var(p_hat) for a ratio estimator over clusters.
    per = g.groupby(cluster)[outcome].agg(["sum", "count"])
    resid = per["sum"] - p * per["count"]
    var = (m / (m - 1)) * (resid ** 2).sum() / (n ** 2)
    half = Z * math.sqrt(max(var, 0.0))
    return p, max(0.0, p - half), min(1.0, p + half), m


def sign_test(both, split, none):
    """
    Two-sided exact binomial p for the mirror-pair sign test.

    `split` pairs (each agent wins one side) are ties and are discarded, which is the standard
    treatment: they carry no directional information. The test is over the pairs that were decided
    the same way on both sides.
    """
    n = both + none
    if n == 0:
        return float("nan")
    k = min(both, none)
    # P(X <= k) + P(X >= n-k) under Binomial(n, 0.5), computed exactly.
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


# ----------------------------------------------------------------------------- tables

def w1_win_rates(df, out):
    """Win rate per matchup, with a map-clustered CI and the conquest / cap split."""
    rows = []
    for (cu, cfg), g in df.groupby(["ComputeUnits", "Config"], sort=True):
        p, lo, hi, nmaps = clustered_ci(g)
        n = len(g)
        _, wlo, whi = wilson(int(g["EmctsWon"].sum()), n)
        rows.append(dict(
            ComputeUnits=cu, Opponent=g["Opponent"].iloc[0],
            Ratio=g["Ratio"].iloc[0], Config=cfg, Games=n, Maps=nmaps,
            EmctsWinRate=round(p, 4),
            CI_lo=round(lo, 4), CI_hi=round(hi, 4),
            CI_lo_unclustered=round(wlo, 4), CI_hi_unclustered=round(whi, 4),
            OppWinRate=round(1 - p, 4),
            # DecidedBy == SCORE means the turn cap ended the game and the score ranking chose the
            # winner, so the conquest share IS the cap-bind rate's complement -- no separate column.
            ConquestPct=round(100 * (g["DecidedBy"] == "CAPITAL").mean(), 1),
            MedianEndTurn=int(g["EndTurn"].median()) if n else 0,
        ))
    t = pd.DataFrame(rows).sort_values(["ComputeUnits", "Opponent", "Ratio"])
    # Stated from each side: the opponent wins if EMCTS's upper bound is below 50%, and vice versa.
    t["OppBeatsEmcts"] = t["CI_hi"] < 0.5
    t["EmctsBeatsOpp"] = t["CI_lo"] > 0.5
    t.to_csv(os.path.join(out, "W1_win_rates.csv"), index=False)
    return t


def w2_pooled(df, out):
    """The headline test: each family pooled over its ratios, per budget."""
    rows = []
    for (cu, fam), g in df[~df["IsControl"]].groupby(["ComputeUnits", "Family"], sort=True):
        p, lo, hi, nmaps = clustered_ci(g)
        rows.append(dict(
            ComputeUnits=cu, Family=fam, Ratios=g["Ratio"].nunique(), Games=len(g),
            EmctsWinRate=round(p, 4), CI_lo=round(lo, 4), CI_hi=round(hi, 4),
            FamilyWinRate=round(1 - p, 4),
            Verdict=("family beats EMCTS" if hi < 0.5 else
                     "EMCTS beats family" if lo > 0.5 else
                     "no difference detected"),
        ))
    t = pd.DataFrame(rows).sort_values(["ComputeUnits", "Family"])
    t.to_csv(os.path.join(out, "W2_pooled.csv"), index=False)
    return t


def w3_by_ratio(df, out):
    """Win rate against ratio, per family per budget -- the 'which ratio' curve."""
    sub = df[(~df["IsControl"]) & (df["Ratio"] != "NA")].copy()
    if sub.empty:
        return pd.DataFrame()
    sub["RatioNum"] = sub["Ratio"].astype(int)
    rows = []
    for (cu, fam, r), g in sub.groupby(["ComputeUnits", "Family", "RatioNum"], sort=True):
        p, lo, hi, _ = clustered_ci(g)
        rows.append(dict(ComputeUnits=cu, Family=fam, Ratio=r, Games=len(g),
                         OppWinRate=round(1 - p, 4),
                         CI_lo=round(1 - hi, 4), CI_hi=round(1 - lo, 4)))
    t = pd.DataFrame(rows).sort_values(["ComputeUnits", "Family", "Ratio"])
    t.to_csv(os.path.join(out, "W3_by_ratio.csv"), index=False)
    return t


def w4_control(df, out):
    """Self-play rows, if any. Empty under the v6 schedule -- see the module docstring and W6."""
    rows = []
    for cu, g in df[df["IsControl"]].groupby("ComputeUnits", sort=True):
        p, lo, hi, nmaps = clustered_ci(g)
        # Also split by orientation and seat: if the overall rate is 50% but the halves are not,
        # the swap is masking a bias rather than removing it.
        o0 = g[g["Orientation"] == 0]["EmctsWon"].mean() if (g["Orientation"] == 0).any() else float("nan")
        o1 = g[g["Orientation"] == 1]["EmctsWon"].mean() if (g["Orientation"] == 1).any() else float("nan")
        s0 = g[g["EmctsSeat"] == 0]["EmctsWon"].mean() if (g["EmctsSeat"] == 0).any() else float("nan")
        rows.append(dict(
            ComputeUnits=cu, Games=len(g), Maps=nmaps,
            EmctsWinRate=round(p, 4), CI_lo=round(lo, 4), CI_hi=round(hi, 4),
            FiftyPctInCI=bool(lo <= 0.5 <= hi),
            WinRate_orientation0=round(o0, 4), WinRate_orientation1=round(o1, 4),
            WinRate_seat0=round(s0, 4),
            SeatAdvantagePP=round(100 * abs(s0 - 0.5), 1) if not math.isnan(s0) else float("nan"),
        ))
    t = pd.DataFrame(rows)
    t.to_csv(os.path.join(out, "W4_control.csv"), index=False)
    return t


def w5_mirror_pairs(df, out):
    """
    The paired test. Each (map, pair) is played in both orientations; within that pair everything
    but the seating is identical.
    """
    rows = []
    for (cu, cfg), g in df.groupby(["ComputeUnits", "Config"], sort=True):
        piv = g.pivot_table(index=["MapIndex", "PairIndex"], columns="Orientation",
                            values="EmctsWon", aggfunc="first")
        if 0 not in piv.columns or 1 not in piv.columns:
            continue
        piv = piv.dropna(subset=[0, 1])
        both = int(((piv[0] == 1) & (piv[1] == 1)).sum())     # EMCTS won from both sides
        none = int(((piv[0] == 0) & (piv[1] == 0)).sum())     # opponent won from both sides
        split = int(len(piv) - both - none)
        p = sign_test(both, split, none)
        rows.append(dict(
            ComputeUnits=cu, Opponent=g["Opponent"].iloc[0], Ratio=g["Ratio"].iloc[0],
            Config=cfg, Pairs=len(piv),
            EmctsWonBoth=both, Split=split, OppWonBoth=none,
            Decisive=both + none,
            SignTestP=round(p, 5) if not math.isnan(p) else float("nan"),
            Significant=bool(p < 0.05) if not math.isnan(p) else False,
            Favours=("EMCTS" if both > none else "OPPONENT" if none > both else "-"),
        ))
    t = pd.DataFrame(rows).sort_values(["ComputeUnits", "Opponent", "Ratio"])
    t.to_csv(os.path.join(out, "W5_mirror_pairs.csv"), index=False)
    return t


def w6_balance(df, out):
    """Residual-bias diagnostic: win rate by orientation and by seat, per matchup."""
    rows = []
    for (cu, cfg), g in df.groupby(["ComputeUnits", "Config"], sort=True):
        rec = dict(ComputeUnits=cu, Config=cfg, Games=len(g))
        for o in (0, 1):
            sub = g[g["Orientation"] == o]
            rec[f"Win_orientation{o}"] = round(sub["EmctsWon"].mean(), 4) if len(sub) else float("nan")
            rec[f"N_orientation{o}"] = len(sub)
        for s in (0, 1):
            sub = g[g["EmctsSeat"] == s]
            rec[f"Win_seat{s}"] = round(sub["EmctsWon"].mean(), 4) if len(sub) else float("nan")
        # A large gap between the orientations is exactly what the swap is there to average away.
        # It is not a fault -- it means the maps are one-sided -- but it should be visible.
        if rec["N_orientation0"] and rec["N_orientation1"]:
            rec["OrientationGapPP"] = round(
                100 * abs(rec["Win_orientation0"] - rec["Win_orientation1"]), 1)
        rows.append(rec)
    t = pd.DataFrame(rows)
    t.to_csv(os.path.join(out, "W6_balance.csv"), index=False)
    return t


def w7_game_length(df, out, turn_limit=None):
    """End-tick distribution and how often the cap decided the game."""
    rows = []
    for cu, g in df.groupby("ComputeUnits", sort=True):
        rows.append(dict(
            ComputeUnits=cu, Games=len(g),
            MinEndTurn=int(g["EndTurn"].min()), MedianEndTurn=float(g["EndTurn"].median()),
            P90=float(g["EndTurn"].quantile(0.90)), P99=float(g["EndTurn"].quantile(0.99)),
            MaxEndTurn=int(g["EndTurn"].max()),
            ConquestPct=round(100 * (g["DecidedBy"] == "CAPITAL").mean(), 1),
            ScoreAtCapPct=round(100 * (g["DecidedBy"] == "SCORE").mean(), 1),
            RetriedGames=int((g["Attempts"] > 1).sum()),
        ))
    t = pd.DataFrame(rows)
    t.to_csv(os.path.join(out, "W7_game_length.csv"), index=False)
    return t


# ----------------------------------------------------------------------------- figures

def figures(w1, w3, w4, out):
    if not HAVE_PLT:
        print("  (matplotlib unavailable - skipping figures)")
        return
    fig_dir = os.path.join(out, "figures")
    os.makedirs(fig_dir, exist_ok=True)

    # F1: opponent win rate vs ratio, one panel per budget.
    if not w3.empty:
        levels = sorted(w3["ComputeUnits"].unique())
        fig, axes = plt.subplots(1, len(levels), figsize=(5 * len(levels), 4), squeeze=False)
        for ax, cu in zip(axes[0], levels):
            sub = w3[w3["ComputeUnits"] == cu]
            for fam, g in sub.groupby("Family"):
                g = g.sort_values("Ratio")
                ax.errorbar(g["Ratio"], 100 * g["OppWinRate"],
                            yerr=[100 * (g["OppWinRate"] - g["CI_lo"]),
                                  100 * (g["CI_hi"] - g["OppWinRate"])],
                            marker="o", capsize=3, label=fam)
            ax.axhline(50, color="grey", ls="--", lw=1)
            ax.set_xscale("log")
            ax.set_xticks(sorted(sub["Ratio"].unique()))
            ax.get_xaxis().set_major_formatter(matplotlib.ticker.ScalarFormatter())
            ax.set_xlabel("mutation : crossover ratio")
            ax.set_ylabel("win rate vs EMCTS (%)")
            ax.set_title(f"{cu:,} compute-units")
            ax.legend(fontsize=8)
        fig.suptitle("v6 full games - win rate against EMCTS by crossover ratio "
                     "(95% CI, clustered on map)")
        fig.tight_layout()
        fig.savefig(os.path.join(fig_dir, "F1_winrate_by_ratio.png"), dpi=150)
        plt.close(fig)

    # F2: every matchup as a forest plot, with the control marked.
    if not w1.empty:
        levels = sorted(w1["ComputeUnits"].unique())
        fig, axes = plt.subplots(1, len(levels), figsize=(5.5 * len(levels), 6), squeeze=False)
        for ax, cu in zip(axes[0], levels):
            sub = w1[w1["ComputeUnits"] == cu].sort_values("Config").reset_index(drop=True)
            y = np.arange(len(sub))
            opp = 1 - sub["EmctsWinRate"]
            lo, hi = 1 - sub["CI_hi"], 1 - sub["CI_lo"]
            colours = ["tab:grey" if c == "EMCTS" else "tab:blue" for c in sub["Config"]]
            ax.errorbar(100 * opp, y, xerr=[100 * (opp - lo), 100 * (hi - opp)],
                        fmt="o", capsize=3, ecolor="lightgrey", linestyle="none")
            ax.scatter(100 * opp, y, c=colours, zorder=3)
            ax.axvline(50, color="grey", ls="--", lw=1)
            ax.set_yticks(y)
            ax.set_yticklabels(sub["Config"], fontsize=8)
            ax.set_xlabel("opponent win rate vs EMCTS (%)")
            ax.set_title(f"{cu:,} compute-units")
        title = "v6 full games - opponent win rate against EMCTS"
        if (w1["Config"] == "EMCTS").any():          # only if a self-play arm is in the tree
            title += " (grey = EMCTS-vs-EMCTS self-play, expect 50%)"
        fig.suptitle(title)
        fig.tight_layout()
        fig.savefig(os.path.join(fig_dir, "F2_forest.png"), dpi=150)
        plt.close(fig)


# ----------------------------------------------------------------------------- report

def report(df, w1, w2, w3, w4, w5, w6, w7, out):
    lines = []
    a = lines.append
    a("# v6 Full Games — results\n")
    a(f"{len(df):,} games across {df['Config'].nunique()} configurations "
      f"and {df['ComputeUnits'].nunique()} budget levels.\n")

    a("\n## Balance — read this first\n")
    a("The v6 schedule has no EMCTS-vs-EMCTS control arm. What it stood for — evidence that no seat "
      "or spawn advantage survived the orientation swap — is reported here instead, and across every "
      "arm rather than in one: each map is played the same number of times in each orientation, and "
      "the two games of a mirror pair differ only in who sits where. A **systematic** gap in the "
      "same direction across the arms means what a control arm off 50% would have meant. Gaps of "
      "either sign, scattered, are sampling noise — at 50 games per orientation, one standard error "
      "is about 7pp.\n")
    if w6.empty or "OrientationGapPP" not in w6.columns:
        a("\n_No balance table available._\n")
    else:
        b = w6.dropna(subset=["OrientationGapPP"])
        a("\n| budget | arms | median orientation gap | widest arm | median seat-0 win rate |")
        a("|---|---|---|---|---|")
        for cu, g in b.groupby("ComputeUnits", sort=True):
            worst = g.loc[g.OrientationGapPP.idxmax()]
            a(f"| {int(cu):,} | {len(g)} | {g.OrientationGapPP.median():.1f} pp | "
              f"{worst.Config} ({worst.OrientationGapPP:.1f} pp) | {g.Win_seat0.median():.3f} |")
        a("\nPer-matchup detail is in `W6_balance.csv`.\n")
    if not w4.empty:
        a("\n### Self-play rows in this tree\n")
        a("This dataset also contains EMCTS-vs-EMCTS games, which should sit at 50%.\n")
        a("\n| budget | games | EMCTS win rate | 95% CI | 50% in CI? |")
        a("|---|---|---|---|---|")
        for _, r in w4.iterrows():
            a(f"| {int(r.ComputeUnits):,} | {int(r.Games)} | {100*r.EmctsWinRate:.1f}% | "
              f"{100*r.CI_lo:.1f}–{100*r.CI_hi:.1f}% | "
              f"{'yes' if r.FiftyPctInCI else '**NO**'} |")

    a("\n## Headline — each family pooled over its ratios\n")
    if w2.empty:
        a("_No experimental matchups yet._\n")
    else:
        a("| budget | family | games | family win rate | 95% CI | verdict |")
        a("|---|---|---|---|---|---|")
        for _, r in w2.iterrows():
            a(f"| {int(r.ComputeUnits):,} | {r.Family} | {int(r.Games)} | "
              f"{100*r.FamilyWinRate:.1f}% | {100*(1-r.CI_hi):.1f}–{100*(1-r.CI_lo):.1f}% | "
              f"{r.Verdict} |")

    a("\n## Per matchup\n")
    a("Win rate is stated from the **opponent's** side: above 50% means the experimental "
      "configuration beat EMCTS. Intervals are clustered on map.\n")
    a("\n| budget | config | games | opponent win rate | 95% CI | conquest | sign test |")
    a("|---|---|---|---|---|---|---|")
    sig = w5.set_index(["ComputeUnits", "Config"]) if not w5.empty else None
    for _, r in w1.iterrows():
        s = ""
        if sig is not None and (r.ComputeUnits, r.Config) in sig.index:
            row = sig.loc[(r.ComputeUnits, r.Config)]
            s = f"p={row.SignTestP:.3f} ({row.Favours})" if not pd.isna(row.SignTestP) else ""
        a(f"| {int(r.ComputeUnits):,} | {r.Config} | {int(r.Games)} | "
          f"{100*(1-r.EmctsWinRate):.1f}% | {100*(1-r.CI_hi):.1f}–{100*(1-r.CI_lo):.1f}% | "
          f"{r.ConquestPct:.0f}% | {s} |")

    a("\n## Game length and how games were decided\n")
    a("| budget | games | median end tick | p99 | max | by conquest | by score at the cap |")
    a("|---|---|---|---|---|---|---|")
    for _, r in w7.iterrows():
        a(f"| {int(r.ComputeUnits):,} | {int(r.Games)} | {r.MedianEndTurn:.0f} | {r.P99:.0f} | "
          f"{int(r.MaxEndTurn)} | {r.ConquestPct:.0f}% | {r.ScoreAtCapPct:.0f}% |")
    a("\nA high 'by score at the cap' share means the turn cap, not play, is deciding games — "
      "see `GameLengthProbeV6` and `RunV6FullGames.TURN_LIMIT`.\n")

    a("\n## Method notes\n")
    per_map = int(df.groupby(["ComputeUnits", "Config", "MapIndex"]).size().median())
    a(f"- Intervals cluster on map: {per_map} games share each map, so a plain binomial interval "
      "understates uncertainty.\n")
    a("- The sign test is over mirror pairs — the same map and seeds with the agents swapped "
      "between seats. Split pairs are ties and are discarded.\n")
    a("- There are no draws: the engine always resolves exactly one winner.\n")

    path = os.path.join(out, "RESULTS_V6_FULL_GAMES.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("base", nargs="?", default="test_results/v6_full")
    ap.add_argument("--outdir", default="analysis/v6_full_games")
    args = ap.parse_args()

    os.makedirs(args.outdir, exist_ok=True)
    df = load(args.base)
    print(f"Loaded {len(df):,} games from {args.base}")

    incomplete = df.groupby(["ComputeUnits", "Config"]).size()
    partial = incomplete[incomplete < EXPECTED_GAMES_PER_MATCHUP]
    if len(partial):
        print(f"  [warn] {len(partial)} matchup(s) have fewer than "
              f"{EXPECTED_GAMES_PER_MATCHUP} games — results below are provisional:")
        for k, v in partial.items():
            print(f"         {k}: {v}")

    w1 = w1_win_rates(df, args.outdir)
    w2 = w2_pooled(df, args.outdir)
    w3 = w3_by_ratio(df, args.outdir)
    w4 = w4_control(df, args.outdir)
    w5 = w5_mirror_pairs(df, args.outdir)
    w6 = w6_balance(df, args.outdir)
    w7 = w7_game_length(df, args.outdir)
    figures(w1, w3, w4, args.outdir)
    path = report(df, w1, w2, w3, w4, w5, w6, w7, args.outdir)

    print(f"\nTables  -> {args.outdir}/W*.csv")
    print(f"Figures -> {args.outdir}/figures/")
    print(f"Report  -> {path}")

    if not w4.empty:
        print("\nSELF-PLAY ROWS (must be ~50%):")
        for _, r in w4.iterrows():
            flag = "ok" if r.FiftyPctInCI else "  <-- 50% OUTSIDE THE CI; investigate"
            print(f"  {int(r.ComputeUnits):>7,} units: {100*r.EmctsWinRate:5.1f}% "
                  f"[{100*r.CI_lo:.1f}, {100*r.CI_hi:.1f}]  {flag}")
    elif not w6.empty and "OrientationGapPP" in w6.columns:
        # W6 is what stands in for a control arm, so print its headline rather than leaving the
        # check to whoever remembers to open the report.
        b = w6.dropna(subset=["OrientationGapPP"])
        print("\nBALANCE (no control arm; a systematic orientation gap is the thing to catch):")
        for cu, g in b.groupby("ComputeUnits", sort=True):
            print(f"  {int(cu):>7,} units: median gap {g.OrientationGapPP.median():4.1f} pp, "
                  f"widest {g.OrientationGapPP.max():4.1f} pp, "
                  f"median seat-0 win rate {g.Win_seat0.median():.3f}")


if __name__ == "__main__":
    main()
