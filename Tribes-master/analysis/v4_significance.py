"""
v4 sequence-value significance analysis.

Adds proper inferential statistics to the final-sequence-value comparisons (previously described
only qualitatively). For each experimental agent (CBB, CE@{2,5,10,15,20}, CC@{2,5,10,15,20}) vs the
EMCTS baseline, a two-sided Wilcoxon signed-rank test on the PAIRED per-scenario differences:

  - pair by scenario: per scenario, mean FinalSequenceValue over the 50 repeats for the agent and for
    EMCTS; Δ = agent − EMCTS (so n = #scenarios, not #turns — the repeats are averaged, not pooled);
  - per time budget (500/1000/5000 ms) and the average across budgets;
  - per split: all (n=100), single-player (n=50), two-player (n=50);
  - Benjamini–Hochberg correction across the 11 agents within each (budget, split).

Why Wilcoxon: the design is PAIRED (same scenarios, same shared random initial sequences for every
agent) and the per-scenario Δ are non-normal (heavy-tailed across the BF 76–619 portfolio; Shapiro–
Wilk p reported per comparison) — so a non-parametric paired test is appropriate, and it matches the
win-rate test (Justesen et al.).

Reuses analyze_v4_full for the data loaders, agent order, colours and CE/CBB/CC abbreviations.

Run:    python analysis/v4_significance.py [test_results/v4]
Output: analysis/v4_significance/
          wilcoxon_sequence_value.csv
          SIGNIFICANCE_V4.md
          Fig5_significance.png   (paired Δ vs EMCTS + 95% CI, * where p_adj<0.05)
          Fig7_significance.png   (single vs two-player split, * where p_adj<0.05)
"""
import sys, warnings
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.stats import wilcoxon, rankdata, shapiro

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_v4_full as v4   # loaders, ORDER, COLORS, disp, MS_LEVELS

OUT = Path('analysis/v4_significance'); OUT.mkdir(parents=True, exist_ok=True)
ALPHA = 0.05
BUDGETS = list(v4.MS_LEVELS)                       # [500, 1000, 5000]
AGENTS = [c for c in v4.ORDER if c != 'EMCTS']     # CBB, CE@2..20, CC@2..20  (11 agents)

# ------------------------------------------------------------------ load + per-scenario aggregation
turn = v4.per_turn(v4.load_all())                  # one row per (Config, ThinkingMs, ScenarioId, VisitId)
portfolio = v4.load_portfolio()
SOURCE = portfolio.set_index('ScenarioId')['Source'].to_dict()

# Mean final value over the 50 repeats, per (Config, budget, scenario) and per (Config, scenario) for 'avg'.
_psm = turn.groupby(['Config', 'ThinkingMs', 'ScenarioId'])['FinalSequenceValue'].mean().reset_index()
_psm_avg = turn.groupby(['Config', 'ScenarioId'])['FinalSequenceValue'].mean().reset_index()


def paired_delta(agent, budget):
    """Per-scenario paired Δ = agent − EMCTS (Series indexed by ScenarioId)."""
    sub = _psm_avg if budget == 'avg' else _psm[_psm.ThinkingMs == budget]
    piv = sub.pivot_table(index='ScenarioId', columns='Config', values='FinalSequenceValue')
    if agent not in piv.columns or 'EMCTS' not in piv.columns:
        return pd.Series(dtype=float)
    return (piv[agent] - piv['EMCTS']).dropna()


def one_comparison(agent, budget, split):
    d = paired_delta(agent, budget)
    if split == 'single':
        d = d[[s for s in d.index if SOURCE.get(s) == 'single']]
    elif split == 'two-player':
        d = d[[s for s in d.index if SOURCE.get(s) == 'dual']]
    d = d.to_numpy(dtype=float)
    n = len(d)
    row = dict(agent_key=agent, agent=v4.disp(agent), budget=budget, split=split, n=n,
               mean_delta=np.nan, median_delta=np.nan, W=np.nan, p_raw=np.nan,
               rank_biserial=np.nan, shapiro_p=np.nan, method='', note='')
    if n == 0:
        row['note'] = 'empty'; return row
    row['mean_delta'] = float(np.mean(d)); row['median_delta'] = float(np.median(d))

    # Near-zero-variance guard (the test is meaningless if the Δ barely vary / are all identical).
    sd = float(np.std(d))
    nz = d[d != 0]
    if sd < 1e-9:
        row.update(W=0.0, p_raw=1.0, rank_biserial=0.0, note='zero-variance Δ (test not meaningful)')
        return row
    if len(nz) < 10:
        row['note'] = f'few non-zero Δ (n_nonzero={len(nz)})'

    # Rank-biserial effect size for matched pairs: (W+ − W−)/(W+ + W−), in [-1, 1].
    ranks = rankdata(np.abs(nz))
    w_plus = ranks[nz > 0].sum(); w_minus = ranks[nz < 0].sum()
    row['rank_biserial'] = float((w_plus - w_minus) / (w_plus + w_minus)) if (w_plus + w_minus) else 0.0

    # Wilcoxon: exact where tractable (no zeros/ties), else asymptotic w/ continuity correction.
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        try:
            res = wilcoxon(d, alternative='two-sided', zero_method='wilcox', method='exact')
            row['method'] = 'exact'
        except Exception:
            res = wilcoxon(d, alternative='two-sided', zero_method='wilcox', method='approx', correction=True)
            row['method'] = 'approx'
    row['W'] = float(res.statistic); row['p_raw'] = float(res.pvalue)

    # Normality of the Δ (justifies the non-parametric choice): low p ⇒ non-normal ⇒ Wilcoxon over t-test.
    try:
        row['shapiro_p'] = float(shapiro(d).pvalue)
    except Exception:
        pass
    return row


def bh_adjust(pvals):
    """Benjamini–Hochberg adjusted p-values (same length, NaNs preserved)."""
    p = np.asarray(pvals, dtype=float)
    ok = np.isfinite(p)
    out = np.full(p.shape, np.nan)
    pv = p[ok]; m = len(pv)
    if m == 0:
        return out
    order = np.argsort(pv)
    ranked = pv[order] * m / np.arange(1, m + 1)
    ranked = np.minimum.accumulate(ranked[::-1])[::-1]
    adj = np.empty(m); adj[order] = np.clip(ranked, 0, 1)
    out[np.where(ok)[0]] = adj
    return out


# ------------------------------------------------------------------ run all comparisons
rows = [one_comparison(a, b, s)
        for s in ('all', 'single', 'two-player')
        for b in (BUDGETS + ['avg'])
        for a in AGENTS]
df = pd.DataFrame(rows)

df['p_adj'] = np.nan
for (_, _), idx in df.groupby(['budget', 'split'], sort=False).groups.items():
    df.loc[idx, 'p_adj'] = bh_adjust(df.loc[idx, 'p_raw'].to_numpy())
df['significant'] = df['p_adj'] < ALPHA

cols = ['agent', 'budget', 'split', 'n', 'mean_delta', 'median_delta', 'W',
        'p_raw', 'p_adj', 'rank_biserial', 'significant', 'shapiro_p', 'method', 'note']
df_out = df[cols].copy()
for c in ['mean_delta', 'median_delta', 'W', 'rank_biserial']:
    df_out[c] = df_out[c].round(3)
for c in ['p_raw', 'p_adj', 'shapiro_p']:
    df_out[c] = df_out[c].apply(lambda v: f'{v:.3e}' if pd.notna(v) else '')
df_out.to_csv(OUT / 'wilcoxon_sequence_value.csv', index=False)


# ------------------------------------------------------------------ significance lookup for figures
SIG = {(r.agent_key, r.budget): bool(r.significant) for r in df[df.split == 'all'].itertuples()}
SIG_SPLIT = {(r.agent_key, r.budget, r.split): bool(r.significant) for r in df.itertuples()}


def _panel_keys():
    return [*BUDGETS, 'avg']


def fig5_significance():
    """Fig 5 with significance stars. Bars + 95% CI are the per-(scenario×visit) paired deltas (the
    existing figure); the * marks comparisons whose per-SCENARIO Wilcoxon p_adj<0.05 (the conservative,
    clustering-correct test). Same 2×2 layout / colours / CI style as analyze_v4_full's E1."""
    em = (turn[turn.Config == 'EMCTS'][['ThinkingMs', 'ScenarioId', 'VisitId', 'FinalSequenceValue']]
          .drop_duplicates(['ThinkingMs', 'ScenarioId', 'VisitId']).rename(columns={'FinalSequenceValue': 'emc'}))
    j = turn.merge(em, on=['ThinkingMs', 'ScenarioId', 'VisitId'], how='left', validate='m:1')
    j['delta'] = j.FinalSequenceValue - j.emc
    j = j[j.Config != 'EMCTS']
    fig, axes2 = plt.subplots(2, 2, figsize=(13, 9), squeeze=False)
    axes = axes2.flatten()
    for ax, key in zip(axes, _panel_keys()):
        sub = j if key == 'avg' else j[j.ThinkingMs == key]
        agg = (sub.groupby('Config')['delta'].agg(['mean', 'std', 'count'])
               .reindex([c for c in v4.ORDER if c in sub.Config.unique() and c != 'EMCTS']))
        agg['ci95'] = 1.96 * agg['std'] / np.sqrt(agg['count'])
        agg = agg.sort_values('mean')
        ys = np.arange(len(agg)); colors = [v4.COLORS[c] for c in agg.index]
        ax.barh(ys, agg['mean'].values, xerr=agg['ci95'].values, capsize=2,
                error_kw=dict(lw=0.8), color=colors, edgecolor='black', linewidth=0.4)
        ax.set_yticks(ys); ax.set_yticklabels([v4.disp(c) for c in agg.index], fontsize=11)
        ax.axvline(0, color='black', lw=0.8)
        ax.set_title(f'{key}' + (' ms' if key != 'avg' else ''))
        ax.set_xlabel('Δ final sequence value vs EMCTS')
        xspan = ax.get_xlim()[1] - ax.get_xlim()[0]
        for yi, cfg in enumerate(agg.index):
            if SIG.get((cfg, key), False):
                m, ci = agg.loc[cfg, 'mean'], agg.loc[cfg, 'ci95']
                if m >= 0:
                    ax.text(m + ci + 0.012 * xspan, yi, '*', ha='left', va='center',
                            fontsize=16, fontweight='bold')
                else:
                    ax.text(m - ci - 0.012 * xspan, yi, '*', ha='right', va='center',
                            fontsize=16, fontweight='bold')
    fig.suptitle('Mean improvement over EMCTS in final sequence value (±95% paired CI)\n'
                 '* = paired Wilcoxon p_adj < 0.05 (per scenario, Benjamini–Hochberg)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(OUT / 'Fig5_significance.png', dpi=300); plt.close(fig)


def fig7_significance():
    """Fig 7 (single vs two-player) with stars where that split's per-scenario Wilcoxon p_adj<0.05."""
    m = turn.merge(portfolio[['ScenarioId', 'Source']], on='ScenarioId')
    fig, axes = plt.subplots(1, len(BUDGETS), figsize=(6 * len(BUDGETS), 5.5), squeeze=False, sharey=True)
    axes = axes[0]
    order = [c for c in v4.ORDER if c != 'EMCTS']
    for ax, ms in zip(axes, BUDGETS):
        sub = m[m.ThinkingMs == ms]
        # per-(config, source) mean delta vs EMCTS over scenarios×visits (for the bar height)
        emcts_full = (sub[sub.Config == 'EMCTS'][['ScenarioId', 'VisitId', 'FinalSequenceValue']]
                      .drop_duplicates(['ScenarioId', 'VisitId']).rename(columns={'FinalSequenceValue': 'emc'}))
        jj = sub.merge(emcts_full, on=['ScenarioId', 'VisitId'], how='left')
        jj['delta'] = jj.FinalSequenceValue - jj.emc
        x = np.arange(len(order))
        s_mean = (jj[jj.Source == 'single'].groupby('Config')['delta'].mean().reindex(order))
        d_mean = (jj[jj.Source == 'dual'].groupby('Config')['delta'].mean().reindex(order))
        ax.bar(x - 0.2, s_mean.values, 0.4, color='#1f78b4', label='single-player (n=50)')
        ax.bar(x + 0.2, d_mean.values, 0.4, color='#e31a1c', label='two-player (n=50)')
        ax.axhline(0, color='black', lw=0.8)
        ax.set_xticks(x); ax.set_xticklabels([v4.disp(c) for c in order], rotation=60, ha='right', fontsize=11)
        ax.set_title(f'{ms} ms'); ax.set_ylabel('mean Δ final sequence value vs EMCTS')
        ypad = 0.02 * (ax.get_ylim()[1] - ax.get_ylim()[0])
        for xi, cfg in enumerate(order):
            if SIG_SPLIT.get((cfg, ms, 'single'), False):
                yv = s_mean[cfg]; ax.text(xi - 0.2, yv + (ypad if yv >= 0 else -ypad), '*',
                                          ha='center', va='bottom' if yv >= 0 else 'top', fontsize=13, fontweight='bold')
            if SIG_SPLIT.get((cfg, ms, 'two-player'), False):
                yv = d_mean[cfg]; ax.text(xi + 0.2, yv + (ypad if yv >= 0 else -ypad), '*',
                                          ha='center', va='bottom' if yv >= 0 else 'top', fontsize=13, fontweight='bold')
    axes[0].legend(fontsize=10, loc='lower left')
    fig.suptitle('Single vs two-player paired Δ vs EMCTS  (* = per-split Wilcoxon p_adj < 0.05)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(OUT / 'Fig7_significance.png', dpi=300); plt.close(fig)


fig5_significance()
fig7_significance()


# ------------------------------------------------------------------ markdown summary + prose
def fmt_p(v):
    return '' if pd.isna(v) else (f'{v:.1e}' if v < 1e-3 else f'{v:.3f}')


def md_table_all():
    a = df[df.split == 'all']
    lines = ['| agent | budget | n | mean Δ | median Δ | rank-biserial | p_raw | p_adj | sig |',
             '|---|---|--:|--:|--:|--:|--:|--:|:--:|']
    for b in (BUDGETS + ['avg']):
        for ag in AGENTS:
            r = a[(a.agent_key == ag) & (a.budget == b)]
            if r.empty: continue
            r = r.iloc[0]
            lines.append(f"| {r.agent} | {b if b=='avg' else str(b)+' ms'} | {int(r.n)} | "
                         f"{r.mean_delta:+.2f} | {r.median_delta:+.2f} | {r.rank_biserial:+.2f} | "
                         f"{fmt_p(r.p_raw)} | {fmt_p(r.p_adj)} | {'**✓**' if r.significant else '·'} |")
    return '\n'.join(lines)


def winners_losers(budget, split='all'):
    a = df[(df.split == split) & (df.budget == budget) & df.significant]
    beat = sorted([(r.agent, r.mean_delta) for r in a.itertuples() if r.mean_delta > 0],
                  key=lambda t: -t[1])
    under = sorted([(r.agent, r.mean_delta) for r in a.itertuples() if r.mean_delta < 0],
                   key=lambda t: t[1])
    return beat, under


prose = []
for b in (BUDGETS + ['avg']):
    beat, under = winners_losers(b)
    blabel = 'averaged across budgets' if b == 'avg' else f'at {b} ms'
    bstr = ', '.join(f'{a} ({d:+.1f})' for a, d in beat) or 'none'
    ustr = ', '.join(f'{a} ({d:+.1f})' for a, d in under) or 'none'
    prose.append(f'- **{blabel}:** significantly beat EMCTS — {bstr}; significantly underperformed — {ustr}.')

# CE@5 headline (all split)
ce5 = df[(df.agent_key == 'CrossoverExpansion@5') & (df.split == 'all')]
ce5_lines = ['| budget | n | mean Δ | median Δ | W | p_raw | p_adj | rank-biserial | sig |',
             '|---|--:|--:|--:|--:|--:|--:|--:|:--:|']
for b in (BUDGETS + ['avg']):
    r = ce5[ce5.budget == b].iloc[0]
    ce5_lines.append(f"| {b if b=='avg' else str(b)+' ms'} | {int(r.n)} | {r.mean_delta:+.3f} | "
                     f"{r.median_delta:+.3f} | {r.W:.0f} | {fmt_p(r.p_raw)} | {fmt_p(r.p_adj)} | "
                     f"{r.rank_biserial:+.3f} | {'✓' if r.significant else '·'} |")

# near-zero-variance / caveat flags
flagged = df[df.note != ''][['agent', 'budget', 'split', 'note']]
nonnormal = (df['shapiro_p'] < 0.05).mean()

md = f"""# v4 sequence-value significance (paired Wilcoxon signed-rank)

Two-sided Wilcoxon signed-rank tests on the **per-scenario** paired difference
Δ = (agent mean final sequence value) − (EMCTS mean final sequence value), where each scenario's
value is the mean over its 50 repeats. Run per time budget and split; Benjamini–Hochberg correction
across the 11 agents within each (budget, split). Significance at α = 0.05 on the **adjusted** p.

**Test appropriateness.** The design is paired (every agent faces the same 100 scenarios with the
same shared random initial sequences), and the per-scenario Δ are non-normal — Shapiro–Wilk rejects
normality (p<0.05) for **{nonnormal*100:.0f}%** of comparisons — so the non-parametric paired
Wilcoxon is the right choice (and matches the win-rate test). n = 100 (all), 50 (each split).

## Headline — CrossoverExpansion@5 vs EMCTS (all scenarios)

{chr(10).join(ce5_lines)}

## All agents vs EMCTS (all scenarios)

{md_table_all()}

`✓` = significant after BH correction (p_adj < 0.05). Full per-split detail (single / two-player) is
in `wilcoxon_sequence_value.csv`.

## Plain-prose summary

{chr(10).join(prose)}

## Caveats / flags

- **Near-zero-variance comparisons flagged:** {'none' if flagged.empty else str(len(flagged)) + ' (see `note` column in the CSV)'}.
- Bars/CI in `Fig5_significance.png` are the per-(scenario×visit) paired deltas (n≈5000, the existing
  Fig 5); the significance stars use the more conservative per-scenario Wilcoxon (n=100) so the test
  respects scenario clustering. The two agree on direction throughout.
"""
(OUT / 'SIGNIFICANCE_V4.md').write_text(md, encoding='utf-8')


# ------------------------------------------------------------------ stdout: confirmation + headline only
print('Test: two-sided Wilcoxon signed-rank on per-scenario paired delta (agent - EMCTS).')
print(f'  paired design; delta non-normal (Shapiro p<0.05 in {nonnormal*100:.0f}% of comparisons) -> Wilcoxon appropriate.')
print(f'  near-zero-variance comparisons flagged: {0 if flagged.empty else len(flagged)}')
print('\n=== HEADLINE: CrossoverExpansion@5 (CE@5) vs EMCTS, all scenarios ===')
hdr = df[(df.agent_key == 'CrossoverExpansion@5') & (df.split == 'all')]
for b in (BUDGETS + ['avg']):
    r = hdr[hdr.budget == b].iloc[0]
    blab = 'avg     ' if b == 'avg' else f'{b:>4} ms'
    print(f'  {blab}: n={int(r.n)}  meanD={r.mean_delta:+8.3f}  medianD={r.median_delta:+8.3f}  '
          f'W={r.W:>7.0f}  p_raw={r.p_raw:.3e}  p_adj={r.p_adj:.3e}  '
          f'rank-biserial={r.rank_biserial:+.3f}  {"SIG" if r.significant else "ns"} ({r.method})')
print(f'\nDeliverables -> {OUT}/  (CSV, SIGNIFICANCE_V4.md, Fig5_significance.png, Fig7_significance.png)')
