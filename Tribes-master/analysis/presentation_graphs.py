"""
Presentation graphs (v4) — three single-message figures distilled from E3.

Each figure communicates ONE takeaway from the E3 single-vs-dual analysis, kept deliberately
simple for a live audience:

  P1  Smaller crossover ratios win at short thinking budgets; larger ratios win at long budgets.
  P2  CrossoverExpansion (CE@5) significantly beats EMCTS — but only in two-player games.
  P3  Crossover bridge-burning (CBB) hurts, increasingly with time (at these small search budgets).

All numbers come from the committed paired-Wilcoxon table
    analysis/v4_significance/wilcoxon_sequence_value.csv
so the figures are reproducible without the raw results. Metric = paired Δ final sequence value
vs EMCTS (positive = better than EMCTS). ★ = significant after Benjamini-Hochberg correction.

Run:  python analysis/presentation_graphs.py
"""

from pathlib import Path
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
CSV = HERE / "v4_significance" / "wilcoxon_sequence_value.csv"
OUT = HERE / "figures_presentation"
OUT.mkdir(exist_ok=True)

# ---- style: big, clean, presentation-friendly ----
plt.rcParams.update({
    "font.size": 15,
    "axes.titlesize": 19,
    "axes.titleweight": "bold",
    "axes.labelsize": 15,
    "xtick.labelsize": 14,
    "ytick.labelsize": 13,
    "legend.fontsize": 14,
    "figure.dpi": 120,
    "savefig.dpi": 300,
    "axes.spines.top": False,
    "axes.spines.right": False,
})

BUDGETS = [500, 1000, 5000]
YLABEL = "Δ final sequence value vs EMCTS\n(higher = better; 0 = EMCTS)"
FOOT = "Paired Wilcoxon vs EMCTS, BH-corrected, α = 0.05.   ★ = significant."

C_SHORT = "#E08214"   # 500 ms (warm)
C_LONG = "#2166AC"    # 5000 ms (cool)
C_TWO = "#1B9E77"     # two-player (stands out)
C_SINGLE = "#9E9E9E"  # single-player (muted)
C_BAD = "#C0392B"     # harmful (red)


def load():
    df = pd.read_csv(CSV)
    df["budget"] = df["budget"].astype(str)
    df["significant"] = df["significant"].astype(str).str.lower().eq("true")
    return df


def get(df, agent, budget, split):
    row = df[(df.agent == agent) & (df.budget == str(budget)) & (df.split == split)]
    if row.empty:
        return None, False
    return float(row.mean_delta.iloc[0]), bool(row.significant.iloc[0])


def style_axis(ax):
    ax.axhline(0, color="black", lw=1.4, zorder=1)
    ax.grid(axis="y", color="#dddddd", lw=0.8, zorder=0)
    ax.set_axisbelow(True)


def annotate(ax, xc, y, sig, color):
    """Value label just past the bar/point end, ★ a little further out (never into the axis/title)."""
    up = y >= 0
    ax.annotate(f"{y:+.0f}", (xc, y), textcoords="offset points",
                xytext=(0, 7 if up else -15), ha="center", fontsize=12, color="#222222", zorder=4)
    if sig:
        ax.annotate("★", (xc, y), textcoords="offset points",
                    xytext=(0, 24 if up else -31), ha="center", fontsize=17, color=color, zorder=4)


# ---------------------------------------------------------------------------
# P1 — ratio depends on thinking time
# ---------------------------------------------------------------------------
def fig_ratio_vs_time(df):
    ratios = [2, 5, 10, 15, 20]
    fig, ax = plt.subplots(figsize=(10, 6))
    style_axis(ax)

    ax.set_ylim(-18, 20)
    for budget, color, lbl in [(500, C_SHORT, "500 ms  (short)"),
                               (5000, C_LONG, "5000 ms  (long)")]:
        ys, sig = [], []
        for r in ratios:
            v, s = get(df, f"CE@{r}", budget, "all")
            ys.append(v); sig.append(s)
        ax.plot(ratios, ys, "-o", color=color, lw=3, ms=9, label=lbl, zorder=3)
        for r, y, s in zip(ratios, ys, sig):
            if s:
                ax.annotate("★", (r, y), textcoords="offset points",
                            xytext=(0, 12 if y >= 0 else -22), ha="center",
                            fontsize=17, color=color, zorder=4)

    ax.set_xticks(ratios)
    ax.set_xlabel("Crossover ratio  (apply crossover every N expansions; lower = more crossover)")
    ax.set_ylabel(YLABEL)
    ax.set_title("More crossover wins on short budgets;\nless crossover wins on long budgets")
    ax.legend(title="Thinking time", loc="upper right", frameon=False)
    fig.text(0.01, 0.005, FOOT + "   Pooled over all 100 scenarios.", fontsize=10, color="#555555")
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    fig.savefig(OUT / "P1_ratio_vs_time.png")
    plt.close(fig)
    print("wrote P1_ratio_vs_time.png")


# ---------------------------------------------------------------------------
# P2 — CE@5 wins only in two-player
# ---------------------------------------------------------------------------
def fig_ce_single_vs_dual(df):
    import numpy as np
    fig, ax = plt.subplots(figsize=(10, 6))
    style_axis(ax)
    ax.set_ylim(-19, 37)
    x = np.arange(len(BUDGETS)); w = 0.38

    for off, split, color, lbl in [(-w / 2, "single", C_SINGLE, "Single-player (vs passive)"),
                                   (+w / 2, "two-player", C_TWO, "Two-player (vs EMCTS)")]:
        ys, sig = [], []
        for b in BUDGETS:
            v, s = get(df, "CE@5", b, split)
            ys.append(v); sig.append(s)
        bars = ax.bar(x + off, ys, w, color=color, label=lbl, zorder=2)
        for rect, y, s in zip(bars, ys, sig):
            annotate(ax, rect.get_x() + rect.get_width() / 2, y, s, color)

    ax.set_xticks(x); ax.set_xticklabels([f"{b} ms" for b in BUDGETS])
    ax.set_xlabel("Thinking time")
    ax.set_ylabel(YLABEL)
    ax.set_title("Crossover (CE) significantly beats EMCTS —\nbut only against an opponent")
    ax.legend(loc="upper right", frameon=False)
    fig.text(0.01, 0.005, FOOT + "   n = 50 per group.   Shown: CE@5.", fontsize=10, color="#555555")
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    fig.savefig(OUT / "P2_CE_single_vs_dual.png")
    plt.close(fig)
    print("wrote P2_CE_single_vs_dual.png")


# ---------------------------------------------------------------------------
# P3 — CBB is harmful
# ---------------------------------------------------------------------------
def fig_cbb_bad(df):
    import numpy as np
    fig, ax = plt.subplots(figsize=(10, 6))
    style_axis(ax)
    ax.set_ylim(-30, 3)
    x = np.arange(len(BUDGETS))
    ys, sig = [], []
    for b in BUDGETS:
        v, s = get(df, "CBB", b, "all")
        ys.append(v); sig.append(s)

    bars = ax.bar(x, ys, 0.55, color=C_BAD, zorder=2)
    for rect, y, s in zip(bars, ys, sig):
        annotate(ax, rect.get_x() + rect.get_width() / 2, y, s, C_BAD)

    ax.set_xticks(x); ax.set_xticklabels([f"{b} ms" for b in BUDGETS])
    ax.set_xlabel("Thinking time")
    ax.set_ylabel(YLABEL)
    ax.set_title("Crossover bridge-burning (CBB) hurts —\nand more so with more time")
    fig.text(0.01, 0.005, FOOT + "   Pooled (n=100).   At these small search budgets.",
             fontsize=10, color="#555555")
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    fig.savefig(OUT / "P3_CBB_bad.png")
    plt.close(fig)
    print("wrote P3_CBB_bad.png")


if __name__ == "__main__":
    df = load()
    fig_ratio_vs_time(df)
    fig_ce_single_vs_dual(df)
    fig_cbb_bad(df)
    print(f"\nAll figures written to {OUT}")
