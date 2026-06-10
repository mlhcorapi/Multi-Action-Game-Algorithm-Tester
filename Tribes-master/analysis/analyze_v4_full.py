"""
analyze_v4_full.py — comprehensive v4 analysis (the "full" companion to analyze_v4.py).

Produces every table and figure the user requested in the v4 follow-up:
  1. Per-scenario × agent FinalSequenceValue tables — raw + Δ vs EMCTS + % vs EMCTS — one per ms.
  2. The same averaged across ms.
  3. Best-agent-per-scenario bar chart (wins) — per ms + averaged.
  4. Ranking-point bar chart (last=1 … best=12, summed) — per ms + averaged.
  5. Trajectory of BestSequenceValue per burn phase — per ms × per agent and averaged.
  6. Trajectory as % improvement over initial sequence — same layout as 5.
  7. Branching-factor vs improvement scatter — per agent per ms + per agent averaged. Raw + %.
  8. Bar chart of mean % improvement over initial sequence — per ms + averaged.

Plus a handful of analysis-driven extras the data invites:
  E1. Paired delta vs EMCTS with 95% CIs (n≈5000 per cell — small effects are detectable).
  E2. Best-ratio-per-budget table for Expansion + Combo (the v3-style sweep).
  E3. Single-vs-dual portfolio split: does the ranking change?
  E4. Convergence speed — burn phase at which each agent reaches 95% of its final gain.

Run:  python analysis/analyze_v4_full.py [test_results/v4]
Output: analysis/figures_v4_full/*.png  +  analysis/tables_v4_full/*.csv
"""
import os, sys, glob, re
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import to_rgba
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test_results/v4')
FIGS = Path('analysis/figures_v4_full'); FIGS.mkdir(parents=True, exist_ok=True)
TABLES = Path('analysis/tables_v4_full'); TABLES.mkdir(parents=True, exist_ok=True)

MS_LEVELS = [500, 1000, 5000]
RATIOS = [2, 5, 10, 15, 20]

# 12 configs, fixed display order: baseline → BB → Expansion × ratios → Combo × ratios.
ORDER = ['EMCTS', 'CrossoverBB']
for r in RATIOS: ORDER.append(f'CrossoverExpansion@{r}')
for r in RATIOS: ORDER.append(f'CrossoverCombo@{r}')

# Consistent colors. Black = EMCTS baseline; orange = BB; blue palette = Expansion ratios; green palette = Combo ratios.
def _shades(base_color, n):
    rgba = np.array(to_rgba(base_color))
    return [tuple(rgba * (0.55 + 0.45 * i/(n-1))) for i in range(n)]

COLORS = {'EMCTS': '#222222', 'CrossoverBB': '#ff7f0e'}
for cfg, c in zip([f'CrossoverExpansion@{r}' for r in RATIOS], _shades('#1f77b4', 5)): COLORS[cfg] = c
for cfg, c in zip([f'CrossoverCombo@{r}'     for r in RATIOS], _shades('#2ca02c', 5)): COLORS[cfg] = c

plt.rcParams.update({'figure.dpi': 110, 'font.size': 9, 'axes.grid': True, 'grid.alpha': 0.3})

LEAF_RE = re.compile(r'(?P<agent>EMCTS|CrossoverBB|CrossoverExpansion|CrossoverCombo)'
                     r'(?:/ratio_(?P<ratio>\d+))?'
                     r'/(?P<ms>\d+)ms/results\.csv$')


# -------------------------------------------------- loaders ------
def _config_label(agent, ratio):
    if agent in ('EMCTS', 'CrossoverBB'):
        return agent
    return f'{agent}@{int(ratio)}'

def load_all():
    """Load every results.csv with provenance columns added. Returns a single DataFrame with one row
    per (config, scenario, visit, burn). Memory: ~3.5 GB at full v4 size — fine on 32 GB."""
    frames = []
    for f in sorted(glob.glob(str(ROOT) + '/**/results.csv', recursive=True)):
        rel = f.replace(os.sep, '/').split(str(ROOT).replace(os.sep, '/') + '/', 1)[-1]
        m = LEAF_RE.search(rel)
        if not m: continue
        df = pd.read_csv(f)
        df['Config'] = _config_label(m.group('agent'), m.group('ratio')) if m.group('ratio') \
                        else m.group('agent')
        df['ThinkingMs'] = int(m.group('ms'))
        frames.append(df)
    if not frames:
        raise FileNotFoundError(f'No results.csv under {ROOT}')
    return pd.concat(frames, ignore_index=True)

def per_turn(df_burn):
    """One row per (Config, ms, scenario, visit) — turn-level columns are identical across all 20 burn
    rows so 'first' picks the right value. Drops per-burn columns to prevent later silent
    averaging-burn-1-values bugs."""
    keys = ['Config', 'ThinkingMs', 'ScenarioId', 'VisitId']
    out = df_burn.drop_duplicates(subset=keys, keep='first').copy()
    return out.drop(columns=['BridgeBurningPhaseNumber', 'BestSequenceValue', 'FmCallsElapsed',
                              'Timestamp', 'RawImprovement', 'PercentageImprovement'], errors='ignore')

def load_portfolio():
    p = ROOT / '_portfolio' / 'portfolio.csv'
    return pd.read_csv(p) if p.exists() else pd.DataFrame()


# -------------------------------------------------- request 1+2: scenario × agent tables --
def scenario_agent_table(turn_df, ms_or_avg):
    """Mean FinalSequenceValue per (scenario, config). For 'avg', average over ms BEFORE
    aggregating so each scenario × visit × ms × config contributes equally."""
    if ms_or_avg == 'avg':
        sub = turn_df
    else:
        sub = turn_df[turn_df.ThinkingMs == ms_or_avg]
    # Mean over visits per (scenario, config). For 'avg' this also averages over ms.
    means = sub.groupby(['ScenarioId', 'Config'])['FinalSequenceValue'].mean().unstack('Config')
    # Reorder columns to the canonical order so EMCTS is first
    cols = [c for c in ORDER if c in means.columns]
    means = means[cols]
    return means

def add_improvement_columns(means, baseline='EMCTS', save_name=None):
    """Take a scenario × Config means table; return a wider table with raw Δ and % Δ vs the baseline,
    plus a summary footer (mean / std / 95% CI per column)."""
    raw = means.subtract(means[baseline], axis=0)
    pct = raw.div(means[baseline].abs(), axis=0) * 100.0
    # Compose final wide table
    wide = pd.concat({'meanFinal': means.round(2),
                      'rawDeltaVsEMCTS': raw.round(2),
                      'pctDeltaVsEMCTS': pct.round(3)}, axis=1)
    wide.columns = [f'{lev0}__{lev1}' for lev0, lev1 in wide.columns]
    # Summary footer
    summary = pd.DataFrame({
        'meanFinal_mean': means.mean(),
        'meanFinal_std':  means.std(),
        'rawDelta_mean':  raw.mean(),
        'rawDelta_std':   raw.std(),
        'rawDelta_CI95':  1.96 * raw.std() / np.sqrt(len(raw)),
        'pctDelta_mean':  pct.mean(),
        'pctDelta_CI95':  1.96 * pct.std() / np.sqrt(len(pct)),
        'wins_vs_emcts':  (raw > 0).sum(),
    }).round(3)
    if save_name:
        wide.to_csv(TABLES / f'{save_name}_per_scenario.csv')
        summary.to_csv(TABLES / f'{save_name}_summary.csv')
    return wide, summary


# -------------------------------------------------- request 3: best agent per scenario --
def best_agent_counts(turn_df, ms_or_avg):
    """For each scenario, which config has the highest mean FinalSequenceValue? Return counts."""
    means = scenario_agent_table(turn_df, ms_or_avg)
    winners = means.idxmax(axis=1)
    return winners.value_counts().reindex([c for c in ORDER if c in means.columns]).fillna(0).astype(int)

def fig_best_per_scenario(turn_df):
    panels = [(ms, f'{ms} ms') for ms in MS_LEVELS] + [('avg', 'avg across ms')]
    fig, axes2 = plt.subplots(2, 2, figsize=(11, 8), squeeze=False)
    axes = axes2.flatten()
    for ax, (key, title) in zip(axes, panels):
        counts = best_agent_counts(turn_df, key)
        colors = [COLORS[c] for c in counts.index]
        ax.bar(range(len(counts)), counts.values, color=colors, edgecolor='black', linewidth=0.4)
        ax.set_xticks(range(len(counts)))
        ax.set_xticklabels(counts.index, rotation=60, ha='right', fontsize=7)
        ax.set_title(title); ax.set_ylabel('scenarios won')
        for i, v in enumerate(counts.values):
            if v: ax.text(i, v + 0.4, str(v), ha='center', fontsize=8)
    fig.suptitle('Best-agent-per-scenario count (argmax of mean FinalSequenceValue)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig3_best_agent_per_scenario.png'); plt.close(fig)
    # also save the tables
    for key, title in panels:
        c = best_agent_counts(turn_df, key)
        c.to_csv(TABLES / f'T3_best_agent_count_{key}.csv')


# -------------------------------------------------- request 4: ranking points --
def ranking_points(turn_df, ms_or_avg):
    """For each scenario, rank configs by mean FinalSequenceValue. last=1, best=K. Sum per config."""
    means = scenario_agent_table(turn_df, ms_or_avg)
    ranks = means.rank(axis=1, method='average', ascending=True)
    return ranks.sum().reindex([c for c in ORDER if c in means.columns])

def fig_ranking_points(turn_df):
    panels = [(ms, f'{ms} ms') for ms in MS_LEVELS] + [('avg', 'avg across ms')]
    fig, axes2 = plt.subplots(2, 2, figsize=(11, 8), squeeze=False)
    axes = axes2.flatten()
    n_configs = 12
    for ax, (key, title) in zip(axes, panels):
        pts = ranking_points(turn_df, key)
        avg_pt = pts / 100   # 100 scenarios; mean rank per scenario
        colors = [COLORS[c] for c in pts.index]
        ax.bar(range(len(pts)), pts.values, color=colors, edgecolor='black', linewidth=0.4)
        ax.set_xticks(range(len(pts)))
        ax.set_xticklabels(pts.index, rotation=60, ha='right', fontsize=7)
        # Reference line at "chance" (mean rank * 100 scenarios)
        ax.axhline(100 * (n_configs+1) / 2, color='red', ls=':', lw=1, label='chance')
        ax.set_title(f'{title}  (chance: {100*(n_configs+1)/2:.0f})'); ax.set_ylabel('total ranking points')
        for i, (v, a) in enumerate(zip(pts.values, avg_pt.values)):
            ax.text(i, v + 5, f'{a:.1f}', ha='center', fontsize=7)
    fig.suptitle('Ranking points (last=1 … best=12 per scenario, summed over 100 scenarios)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig4_ranking_points.png'); plt.close(fig)
    for key, title in panels:
        ranking_points(turn_df, key).to_csv(TABLES / f'T4_ranking_points_{key}.csv', header=['points'])


# -------------------------------------------------- request 5+6: trajectories --
def trajectory_data(df_burn, kind='raw'):
    """Returns a DataFrame indexed by (Config, ThinkingMs, BridgeBurningPhaseNumber) with the mean
    BSV (kind=raw) or mean %-over-initial (kind=pct) across all (scenario, visit)."""
    if kind == 'raw':
        return df_burn.groupby(['Config','ThinkingMs','BridgeBurningPhaseNumber'])['BestSequenceValue'].mean()
    elif kind == 'pct':
        # %-over-initial per (scenario, visit, burn). Initial is constant within (scenario, visit) but
        # we compute per-row to avoid edge cases.
        m = df_burn.copy()
        m['pct'] = (m.BestSequenceValue - m.InitialSequenceValue) / m.InitialSequenceValue.abs() * 100
        return m.groupby(['Config','ThinkingMs','BridgeBurningPhaseNumber'])['pct'].mean()
    else:
        raise ValueError(kind)

def _set_tight_ylim(ax, ys, pad_frac=0.06):
    ys = np.asarray(ys); ys = ys[np.isfinite(ys)]
    if ys.size == 0: return
    lo, hi = ys.min(), ys.max()
    span = max(1e-9, hi - lo)
    ax.set_ylim(lo - span*pad_frac, hi + span*pad_frac)

def fig_trajectories(df_burn, kind='raw', name_prefix='fig5'):
    """Trajectory of BSV (raw) or %-over-initial (pct) across burn phases.
    Layout: 4 panels (3 ms + 1 averaged), all 12 configs on each — the most useful comparison view."""
    traj = trajectory_data(df_burn, kind)
    panels = [(ms, f'{ms} ms') for ms in MS_LEVELS] + [('avg', 'avg across ms')]
    fig, axes2 = plt.subplots(2, 2, figsize=(13, 9), squeeze=False)
    axes = axes2.flatten()
    ylabel = 'mean BestSequenceValue' if kind == 'raw' \
             else 'mean % improvement over initial sequence'
    for ax, (key, title) in zip(axes, panels):
        all_y = []
        for cfg in ORDER:
            if cfg not in traj.index.get_level_values('Config'): continue
            if key == 'avg':
                series = traj.loc[cfg].groupby('BridgeBurningPhaseNumber').mean()
            else:
                if (cfg, key) not in traj.index.droplevel(2): continue
                series = traj.loc[(cfg, key)]
            ax.plot(series.index, series.values, color=COLORS[cfg], label=cfg, lw=1.7,
                    marker='o', markersize=3)
            all_y.extend(series.values.tolist())
        # Tight ylim so lines are distinguishable — the user's clarity request.
        _set_tight_ylim(ax, all_y)
        ax.set_xlabel('bridge-burn phase (1..20)'); ax.set_title(title)
        ax.set_ylabel(ylabel)
    # Global legend below the figure
    h, l = axes[0].get_legend_handles_labels()
    fig.legend(h, l, loc='lower center', bbox_to_anchor=(0.5, -0.02), ncol=4, fontsize=8, frameon=False)
    fig.suptitle(('Within-turn trajectory (raw value)' if kind == 'raw'
                  else 'Within-turn trajectory (% improvement over initial)'),
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / f'{name_prefix}_trajectory_{kind}.png', bbox_inches='tight')
    plt.close(fig)

def fig_trajectories_per_agent(df_burn, kind='raw', name_prefix='fig5b'):
    """Per-agent layout: 3×4 grid (3 ms + 1 avg cols × 4-row sub-grids of agents). For each agent shows
    its trajectory at every ms with adjusted ylim. Use this when you want to see one agent in detail."""
    traj = trajectory_data(df_burn, kind)
    configs = [c for c in ORDER if c in traj.index.get_level_values('Config')]
    fig, axes = plt.subplots(3, 4, figsize=(14, 9), squeeze=False)
    # Pick 12 representatives — all 12 configs
    for i, cfg in enumerate(configs[:12]):
        ax = axes[i // 4][i % 4]
        all_y = []
        for ms in MS_LEVELS:
            if (cfg, ms) not in traj.index.droplevel(2): continue
            s = traj.loc[(cfg, ms)]
            ax.plot(s.index, s.values, label=f'{ms} ms', lw=1.7, marker='o', markersize=3,
                    alpha=0.5 + 0.5 * (MS_LEVELS.index(ms) / (len(MS_LEVELS)-1)))
            all_y.extend(s.values.tolist())
        ax.set_title(cfg, fontsize=10, color=COLORS[cfg], fontweight='bold')
        _set_tight_ylim(ax, all_y, pad_frac=0.08)
        if i % 4 == 0:
            ax.set_ylabel('BSV' if kind == 'raw' else '% over init')
        if i // 4 == 2:
            ax.set_xlabel('burn phase')
        if i == 0: ax.legend(fontsize=7, loc='lower right')
    fig.suptitle(('Within-turn trajectory per agent — raw' if kind == 'raw'
                  else 'Within-turn trajectory per agent — % over initial'), fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / f'{name_prefix}_trajectory_per_agent_{kind}.png',
                                      bbox_inches='tight')
    plt.close(fig)


# -------------------------------------------------- request 7: branching scatter --
def fig_branching_scatter(turn_df, portfolio, kind='raw'):
    """Branching-factor (per scenario, from manifest) vs mean improvement (per scenario × config).
    Grid: 12 agents × 4 (3 ms + avg) = 48 subplots. Trendline added for clarity."""
    if portfolio.empty:
        print('  WARN: no portfolio — skipping branching scatter'); return
    # Per (config, ms, scenario) improvement
    if kind == 'raw':
        m = turn_df.groupby(['Config', 'ThinkingMs', 'ScenarioId']).apply(
            lambda d: (d.FinalSequenceValue - d.InitialSequenceValue).mean(), include_groups=False).reset_index()
        m.columns = ['Config', 'ThinkingMs', 'ScenarioId', 'value']
        ylabel = 'raw improvement over initial'
    else:
        m = turn_df.groupby(['Config', 'ThinkingMs', 'ScenarioId']).apply(
            lambda d: ((d.FinalSequenceValue - d.InitialSequenceValue) / d.InitialSequenceValue.abs() * 100).mean(),
            include_groups=False).reset_index()
        m.columns = ['Config', 'ThinkingMs', 'ScenarioId', 'value']
        ylabel = '% improvement over initial'
    m = m.merge(portfolio[['ScenarioId', 'BranchingFactor', 'Source']], on='ScenarioId')

    configs = [c for c in ORDER if c in m.Config.unique()]
    panels = MS_LEVELS + ['avg']
    fig, axes = plt.subplots(len(configs), len(panels),
                             figsize=(2.6 * len(panels), 1.8 * len(configs)),
                             squeeze=False, sharex=True)
    for i, cfg in enumerate(configs):
        for j, key in enumerate(panels):
            ax = axes[i][j]
            if key == 'avg':
                sub = m[m.Config == cfg].groupby('ScenarioId').agg({'value':'mean',
                                                                     'BranchingFactor':'first',
                                                                     'Source':'first'}).reset_index()
            else:
                sub = m[(m.Config == cfg) & (m.ThinkingMs == key)]
            xs = sub.BranchingFactor.values; ys = sub.value.values
            # Color by source so single (cooler) vs dual (warmer) is visible
            mask_single = sub.Source.values == 'single'
            ax.scatter(xs[mask_single], ys[mask_single], s=10, alpha=0.6,
                       color='#1f78b4', label='single' if (i==0 and j==0) else None)
            ax.scatter(xs[~mask_single], ys[~mask_single], s=10, alpha=0.6,
                       color='#e31a1c', label='dual' if (i==0 and j==0) else None)
            if xs.size >= 5:
                # Fit a simple linear trend
                p = np.polyfit(xs, ys, 1)
                xx = np.linspace(xs.min(), xs.max(), 50)
                ax.plot(xx, p[0]*xx + p[1], color='black', lw=1.0, alpha=0.6)
                r = np.corrcoef(xs, ys)[0, 1]
                ax.text(0.02, 0.95, f'r={r:.2f}', transform=ax.transAxes, fontsize=7,
                        va='top', bbox=dict(boxstyle='round,pad=0.1', fc='white', ec='none', alpha=0.7))
            if j == 0:
                ax.set_ylabel(cfg, fontsize=8, color=COLORS[cfg], fontweight='bold')
            if i == 0:
                ax.set_title(f'{key}' + (' ms' if key != 'avg' else ''), fontsize=10)
            if i == len(configs)-1:
                ax.set_xlabel('branching factor', fontsize=8)
            ax.tick_params(labelsize=7)
    if axes[0][0].get_legend_handles_labels()[1]:
        axes[0][0].legend(fontsize=7, loc='lower right')
    fig.suptitle(f'Branching factor vs {ylabel} (per scenario)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / f'fig7_branching_scatter_{kind}.png',
                                     bbox_inches='tight', dpi=110)
    plt.close(fig)


# -------------------------------------------------- request 8: % imp over initial bars --
def fig_pct_improvement_bars(turn_df):
    """Bar chart of mean % improvement over initial sequence, per agent. 4 panels (per ms + avg)."""
    turn_df = turn_df.copy()
    turn_df['pctImp'] = (turn_df.FinalSequenceValue - turn_df.InitialSequenceValue) \
                       / turn_df.InitialSequenceValue.abs() * 100
    panels = [(ms, f'{ms} ms') for ms in MS_LEVELS] + [('avg', 'avg across ms')]
    fig, axes2 = plt.subplots(2, 2, figsize=(11, 8), squeeze=False, sharey=True)
    axes = axes2.flatten()
    for ax, (key, title) in zip(axes, panels):
        sub = turn_df if key == 'avg' else turn_df[turn_df.ThinkingMs == key]
        means = sub.groupby('Config')['pctImp'].mean().reindex([c for c in ORDER if c in sub.Config.unique()])
        cis = sub.groupby('Config')['pctImp'].apply(lambda s: 1.96*s.std()/np.sqrt(len(s)))\
                 .reindex(means.index)
        colors = [COLORS[c] for c in means.index]
        ax.bar(range(len(means)), means.values, yerr=cis.values, capsize=2,
               error_kw=dict(lw=0.8), color=colors, edgecolor='black', linewidth=0.4)
        ax.set_xticks(range(len(means)))
        ax.set_xticklabels(means.index, rotation=60, ha='right', fontsize=7)
        ax.set_title(title)
        ax.set_ylabel('mean % improvement over initial sequence (±95% CI)')
    fig.suptitle('% improvement over initial sequence — per agent per ms',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig8_pct_improvement_bars.png'); plt.close(fig)
    # save table
    for key, _ in panels:
        sub = turn_df if key == 'avg' else turn_df[turn_df.ThinkingMs == key]
        sub.groupby('Config')['pctImp'].agg(['mean','std','count']).to_csv(
            TABLES / f'T8_pct_imp_over_init_{key}.csv')


# -------------------------------------------------- extras --
def extra_paired_delta_with_ci(turn_df):
    """E1. Paired delta vs EMCTS with 95% CIs. With n≈5000 paired samples per cell, even small effects
    surface. The honest reading of the data."""
    emcts = turn_df[turn_df.Config == 'EMCTS'][['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]\
                .rename(columns={'FinalSequenceValue':'emcts_final'})\
                .drop_duplicates(['ThinkingMs','ScenarioId','VisitId'])
    j = turn_df.merge(emcts, on=['ThinkingMs','ScenarioId','VisitId'], how='left', validate='m:1')
    j['delta'] = j.FinalSequenceValue - j.emcts_final
    # Independent y-axes: each panel sorts bars by its own mean, so each panel needs its own labels
    # in the matching order. With sharey=True matplotlib lets the last-set y-tick labels win for ALL
    # panels, which was the cause of the label/bar mismatch in the previous version.
    fig, axes2 = plt.subplots(2, 2, figsize=(13, 9), squeeze=False, sharey=False)
    axes = axes2.flatten()
    # per ms + avg
    keys = [*MS_LEVELS, 'avg']
    for ax, key in zip(axes, keys):
        sub = j if key == 'avg' else j[j.ThinkingMs == key]
        sub = sub[sub.Config != 'EMCTS']
        agg = sub.groupby('Config')['delta'].agg(['mean','std','count']).reindex(
            [c for c in ORDER if c in sub.Config.unique()])
        agg['ci95'] = 1.96 * agg['std'] / np.sqrt(agg['count'])
        agg = agg.sort_values('mean')
        ys = np.arange(len(agg))
        colors = [COLORS[c] for c in agg.index]
        ax.barh(ys, agg['mean'].values, xerr=agg['ci95'].values, capsize=2,
                error_kw=dict(lw=0.8), color=colors, edgecolor='black', linewidth=0.4)
        ax.set_yticks(ys); ax.set_yticklabels(agg.index, fontsize=8)
        ax.axvline(0, color='black', lw=0.8)
        ax.set_title(f'{key}' + (' ms' if key != 'avg' else ''))
        ax.set_xlabel('paired Δ vs EMCTS (FinalSequenceValue, ±95% CI)')
    fig.suptitle('Paired Δ vs EMCTS (same scenario × visit pair, ±95% CI)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E1_paired_delta_with_ci.png'); plt.close(fig)
    # table
    for key in keys:
        sub = j if key == 'avg' else j[j.ThinkingMs == key]
        sub = sub[sub.Config != 'EMCTS']
        agg = sub.groupby('Config')['delta'].agg(['mean','std','count'])
        agg['ci95'] = 1.96 * agg['std'] / np.sqrt(agg['count'])
        agg.round(3).to_csv(TABLES / f'E1_paired_delta_{key}.csv')


def extra_best_ratio_per_budget(turn_df):
    """E2. Best ratio per budget for Expansion + Combo (the v3-style sweep view).
    Mean FinalSequenceValue per (ratio, ms), reported as both raw and paired Δ vs EMCTS."""
    rows = []
    emcts = turn_df[turn_df.Config == 'EMCTS'][['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]\
              .rename(columns={'FinalSequenceValue':'emcts_final'})\
              .drop_duplicates(['ThinkingMs','ScenarioId','VisitId'])
    for agent_base in ('CrossoverExpansion','CrossoverCombo'):
        for r in RATIOS:
            cfg = f'{agent_base}@{r}'
            for ms in MS_LEVELS:
                sub = turn_df[(turn_df.Config==cfg) & (turn_df.ThinkingMs==ms)]
                if sub.empty: continue
                m = sub.merge(emcts, on=['ThinkingMs','ScenarioId','VisitId'])
                rows.append(dict(Agent=agent_base, Ratio=r, ms=ms,
                                 meanFinal=m.FinalSequenceValue.mean(),
                                 deltaVsEMCTS=(m.FinalSequenceValue - m.emcts_final).mean(),
                                 n=len(m)))
    t = pd.DataFrame(rows).round(3)
    t.to_csv(TABLES / 'E2_best_ratio_per_budget.csv', index=False)
    # Companion figure: ratio sweep per ms per agent_base
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), squeeze=False)
    axes = axes[0]
    for ax, agent_base in zip(axes, ('CrossoverExpansion','CrossoverCombo')):
        for ms in MS_LEVELS:
            sub = t[(t.Agent==agent_base) & (t.ms==ms)].sort_values('Ratio')
            ax.plot(sub.Ratio, sub.deltaVsEMCTS, marker='o', label=f'{ms} ms', lw=1.8)
        ax.axhline(0, color='black', lw=0.8, ls=':')
        ax.set_xticks(RATIOS); ax.set_xlabel('mutationExpansionRatio (higher = less crossover)')
        ax.set_ylabel('paired Δ vs EMCTS')
        ax.set_title(agent_base); ax.legend(fontsize=8)
    fig.suptitle('Best ratio per budget — Crossover{Expansion,Combo} (paired Δ vs EMCTS)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E2_best_ratio_per_budget.png'); plt.close(fig)


def extra_single_vs_dual(turn_df, portfolio):
    """E3. Single (vs passive) vs Dual (vs EMCTS) portfolio split. Does the ranking change between
    'developed solo' and 'competitive mid-game' scenarios?"""
    if portfolio.empty: return
    m = turn_df.merge(portfolio[['ScenarioId','Source']], on='ScenarioId')
    rows = []
    for src in ('single', 'dual'):
        sub = m[m.Source == src]
        e = sub[sub.Config=='EMCTS'][['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]\
              .rename(columns={'FinalSequenceValue':'emcts_final'})\
              .drop_duplicates(['ThinkingMs','ScenarioId','VisitId'])
        joined = sub.merge(e, on=['ThinkingMs','ScenarioId','VisitId'])
        joined['delta'] = joined.FinalSequenceValue - joined.emcts_final
        for cfg in [c for c in ORDER if c != 'EMCTS']:
            for ms in MS_LEVELS:
                z = joined[(joined.Config==cfg) & (joined.ThinkingMs==ms)]
                if z.empty: continue
                rows.append(dict(Source=src, Config=cfg, ms=ms,
                                 deltaVsEMCTS=z.delta.mean(), n=len(z)))
    t = pd.DataFrame(rows).round(3)
    t.to_csv(TABLES / 'E3_single_vs_dual.csv', index=False)
    # Plot: per ms, side-by-side single vs dual paired Δ
    fig, axes = plt.subplots(1, len(MS_LEVELS), figsize=(6*len(MS_LEVELS), 5.5),
                             squeeze=False, sharey=True)
    axes = axes[0]
    for ax, ms in zip(axes, MS_LEVELS):
        sub = t[t.ms == ms]
        configs_present = [c for c in ORDER if c != 'EMCTS' and c in sub.Config.unique()]
        x = np.arange(len(configs_present))
        s = sub[sub.Source=='single'].set_index('Config').reindex(configs_present)['deltaVsEMCTS']
        d = sub[sub.Source=='dual'  ].set_index('Config').reindex(configs_present)['deltaVsEMCTS']
        ax.bar(x - 0.2, s.values, 0.4, color='#1f78b4', label='single-player scenarios (n=50)')
        ax.bar(x + 0.2, d.values, 0.4, color='#e31a1c', label='dual-player scenarios (n=50)')
        ax.axhline(0, color='black', lw=0.8)
        ax.set_xticks(x); ax.set_xticklabels(configs_present, rotation=60, ha='right', fontsize=7)
        ax.set_title(f'{ms} ms')
        ax.set_ylabel('mean FinalSequenceValue advantage over EMCTS')
    axes[0].legend(fontsize=8, loc='lower left')
    fig.suptitle('Single-player vs dual-player portfolio split — paired Δ vs EMCTS',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E3_single_vs_dual.png'); plt.close(fig)


def extra_branching_source_diagnostics(turn_df, portfolio):
    """E6/E7/E8 + ANCOVA: disentangle whether the single/dual gap in E3 is driven by
    branching factor (BF) or by an independent source-of-scenario effect (player presence,
    combat density, etc.).

    The strategy:
        E6 — diagnostic: are single and dual portfolios drawn from the same BF distribution?
        E7 — heatmap: paired Δ vs EMCTS, sliced by BF quintile × source × agent.
        E8 — scatter: per-source regression lines of paired Δ vs BF for selected agents.
        ANCOVA — per (agent, ms): paired Δ ~ BF + Source + BF×Source. β_Source is the
                  source effect AFTER controlling for BF — the answer to the central question.
    """
    if portfolio.empty:
        print('  E6/E7/E8/ANCOVA: portfolio missing, skipping'); return

    # Build a per (Config, ms, scenario, visit) paired-delta table, joined with portfolio metadata.
    emcts = (turn_df[turn_df.Config == 'EMCTS']
             [['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]
             .drop_duplicates(['ThinkingMs','ScenarioId','VisitId'])
             .rename(columns={'FinalSequenceValue':'emcts_final'}))
    j = turn_df.merge(emcts, on=['ThinkingMs','ScenarioId','VisitId'], how='left', validate='m:1')
    j = j.merge(portfolio[['ScenarioId','Source','BranchingFactor']], on='ScenarioId')
    j['delta'] = j.FinalSequenceValue - j.emcts_final
    j = j[j.Config != 'EMCTS'].copy()

    # BF tertiles computed WITHIN each source. Global quintiles put dual almost entirely in low-BF
    # bins (19/17/8/5/1 scenarios) and single in high-BF bins (1/3/12/15/19), so the off-diagonal
    # cells rested on 1–5 scenarios and were visually dominant but statistically meaningless.
    # Per-source tertiles give ~17 scenarios per cell — every cell is reliable. Trade-off: columns
    # no longer align on absolute BF across the two panels, so each column is labelled with its own
    # source's BF range and scenario count.
    portfolio = portfolio.copy()
    TIER_LABELS = ['Low BF', 'Mid BF', 'High BF']
    tier_edges = {}     # source -> bin-edge array (len 4)
    tier_counts = {}    # source -> scenario count per tier
    portfolio['BF_tier'] = pd.Series(index=portfolio.index, dtype=object)
    for src in ('single', 'dual'):
        mask = portfolio.Source == src
        cats, edges = pd.qcut(portfolio.loc[mask, 'BranchingFactor'], 3,
                              labels=TIER_LABELS, retbins=True)
        portfolio.loc[mask, 'BF_tier'] = cats.astype(str)
        tier_edges[src] = edges
        tier_counts[src] = cats.value_counts().reindex(TIER_LABELS)
    portfolio['BF_tier'] = pd.Categorical(portfolio['BF_tier'], categories=TIER_LABELS, ordered=True)
    j = j.merge(portfolio[['ScenarioId','BF_tier']], on='ScenarioId')

    # ----------------------------------------------------- E6: BF distribution per source ----
    fig, ax = plt.subplots(figsize=(9, 4.2))
    s_bf = portfolio[portfolio.Source == 'single'].BranchingFactor
    d_bf = portfolio[portfolio.Source == 'dual'].BranchingFactor
    bins = np.linspace(portfolio.BranchingFactor.min(), portfolio.BranchingFactor.max(), 22)
    ax.hist(s_bf, bins=bins, alpha=0.55, color='#1f78b4',
            label=f'single (n={len(s_bf)}; median={s_bf.median():.0f}, IQR {s_bf.quantile(0.25):.0f}–{s_bf.quantile(0.75):.0f})',
            edgecolor='black', linewidth=0.4)
    ax.hist(d_bf, bins=bins, alpha=0.55, color='#e31a1c',
            label=f'dual (n={len(d_bf)}; median={d_bf.median():.0f}, IQR {d_bf.quantile(0.25):.0f}–{d_bf.quantile(0.75):.0f})',
            edgecolor='black', linewidth=0.4)
    ax.axvline(s_bf.median(), color='#1f78b4', linestyle='--', lw=1.5)
    ax.axvline(d_bf.median(), color='#e31a1c', linestyle='--', lw=1.5)
    ax.set_xlabel('Branching factor (per scenario)')
    ax.set_ylabel('scenario count')
    ax.legend(fontsize=9, loc='upper right')
    ax.set_title('Branching-factor distribution per source',
                 fontweight='bold', fontsize=10)
    fig.tight_layout(); fig.savefig(FIGS / 'E6_bf_dist_per_source.png'); plt.close(fig)
    # Mann-Whitney for "are the two distributions different?"
    try:
        from scipy.stats import mannwhitneyu
        mw = mannwhitneyu(s_bf, d_bf, alternative='two-sided')
        print(f'  E6: single BF median={s_bf.median():.0f} (IQR {s_bf.quantile(0.25):.0f}-{s_bf.quantile(0.75):.0f}); '
              f'dual BF median={d_bf.median():.0f} (IQR {d_bf.quantile(0.25):.0f}-{d_bf.quantile(0.75):.0f}); '
              f'Mann-Whitney U={mw.statistic:.0f}, p={mw.pvalue:.3g}')
    except Exception:
        print(f'  E6: single BF median={s_bf.median():.0f}; dual BF median={d_bf.median():.0f}')
    # Persist the diagnostic summary
    diag = pd.DataFrame({
        'source': ['single','dual'],
        'n_scenarios': [len(s_bf), len(d_bf)],
        'BF_mean': [s_bf.mean(), d_bf.mean()],
        'BF_median': [s_bf.median(), d_bf.median()],
        'BF_q25': [s_bf.quantile(0.25), d_bf.quantile(0.25)],
        'BF_q75': [s_bf.quantile(0.25), d_bf.quantile(0.75)],
        'BF_min': [s_bf.min(), d_bf.min()],
        'BF_max': [s_bf.max(), d_bf.max()],
    }).round(2)
    diag.to_csv(TABLES / 'E6_bf_dist_per_source.csv', index=False)

    # ----------------------------------------------------- E7: BF-tertile × source heatmap ----
    configs = [c for c in ORDER if c != 'EMCTS' and c in j.Config.unique()]
    pivot_single = (j[j.Source == 'single'].groupby(['Config','BF_tier'], observed=True)['delta']
                    .mean().unstack('BF_tier').reindex(index=configs, columns=TIER_LABELS))
    pivot_dual = (j[j.Source == 'dual'].groupby(['Config','BF_tier'], observed=True)['delta']
                  .mean().unstack('BF_tier').reindex(index=configs, columns=TIER_LABELS))
    vmax = max(abs(np.nanmax(pivot_single.values)), abs(np.nanmin(pivot_single.values)),
               abs(np.nanmax(pivot_dual.values)), abs(np.nanmin(pivot_dual.values)))
    fig, axes = plt.subplots(1, 2, figsize=(12, 6), squeeze=False)
    axes = axes[0]
    for ax, pivot, src, label in zip(axes, [pivot_single, pivot_dual],
                                     ['single', 'dual'], ['single-player', 'dual-player']):
        im = ax.imshow(pivot.values, cmap='RdBu_r', vmin=-vmax, vmax=vmax, aspect='auto')
        # ^ RdBu_r: red = positive (beats EMCTS), blue = negative (loses to EMCTS)
        edges = tier_edges[src]
        ax.set_xticks(range(len(TIER_LABELS))); ax.set_xticklabels(
            [f'{TIER_LABELS[i]}\n{edges[i]:.0f}–{edges[i+1]:.0f}'
             for i in range(len(TIER_LABELS))], fontsize=8)
        ax.set_yticks(range(len(configs))); ax.set_yticklabels(configs, fontsize=8)
        ax.set_xlabel('Branching factor')
        ax.set_title(f'{label} scenarios')
        for (i, k), v in np.ndenumerate(pivot.values):
            if np.isfinite(v):
                ax.text(k, i, f'{v:+.0f}', ha='center', va='center', fontsize=8,
                        color='white' if abs(v) > vmax * 0.55 else 'black')
        fig.colorbar(im, ax=ax, shrink=0.85).set_label('paired Δ vs EMCTS')
    fig.suptitle('BF-tertile × source heatmap — paired Δ vs EMCTS (averaged over ms and visits)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E7_bf_tier_source_heatmap.png'); plt.close(fig)
    pivot_single.round(2).to_csv(TABLES / 'E7_bf_tier_single.csv')
    pivot_dual.round(2).to_csv(TABLES / 'E7_bf_tier_dual.csv')

    # ----------------------------------------------------- E8: BF scatter with regression lines ----
    select_agents = ['CrossoverExpansion@2', 'CrossoverExpansion@15', 'CrossoverBB', 'CrossoverCombo@5']
    # Per-scenario delta means (averaged over visits and ms) so each scenario is one data point.
    per_scen = (j.groupby(['Config','ScenarioId','BranchingFactor','Source'], observed=True)['delta']
                  .mean().reset_index())
    fig, axes2 = plt.subplots(2, 2, figsize=(12, 8.5), squeeze=False)
    axes = axes2.flatten()
    for ax, cfg in zip(axes, select_agents):
        sub = per_scen[per_scen.Config == cfg]
        for src, color in [('single', '#1f78b4'), ('dual', '#e31a1c')]:
            s = sub[sub.Source == src]
            ax.scatter(s.BranchingFactor, s['delta'], s=22, alpha=0.55, color=color,
                       edgecolor='black', linewidth=0.3, label=f'{src} (n={len(s)})')
            if len(s) >= 3:
                p = np.polyfit(s.BranchingFactor, s['delta'], 1)
                xx = np.linspace(s.BranchingFactor.min(), s.BranchingFactor.max(), 50)
                ax.plot(xx, p[0]*xx + p[1], color=color, lw=2.2)
                r = np.corrcoef(s.BranchingFactor, s['delta'])[0, 1]
                ax.text(0.02, 0.93 if src == 'single' else 0.86,
                        f'{src}: slope={p[0]*100:+.2f}/100BF  r={r:+.2f}',
                        transform=ax.transAxes, fontsize=8, color=color,
                        bbox=dict(boxstyle='round,pad=0.2', fc='white', ec='none', alpha=0.85))
        ax.axhline(0, color='black', lw=0.6, linestyle=':')
        ax.set_title(cfg, fontsize=10, color=COLORS.get(cfg, 'black'), fontweight='bold')
        ax.set_xlabel('Branching factor')
        ax.set_ylabel('paired Δ vs EMCTS')
        ax.legend(fontsize=8, loc='lower right')
    fig.suptitle('Branching factor vs paired Δ vs EMCTS — per-source regression lines',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E8_bf_source_regression.png'); plt.close(fig)

    # ----------------------------------------------------- ANCOVA: source effect, controlling for BF ----
    # Per (Config, ms): paired_delta ~ BF_centered + Source_dual + BF_centered * Source_dual.
    # Unit of analysis = per-scenario mean delta (so BF is one value per row, not repeated). This
    # avoids inflating n by treating 50 visits per scenario as independent.
    rows = []
    bf_mean = portfolio.BranchingFactor.mean()
    for cfg in [c for c in ORDER if c != 'EMCTS']:
        for ms in [*MS_LEVELS, 'avg']:
            if ms == 'avg':
                sub = (j[j.Config == cfg]
                       .groupby(['ScenarioId','BranchingFactor','Source'], observed=True)['delta']
                       .mean().reset_index())
            else:
                sub = (j[(j.Config == cfg) & (j.ThinkingMs == ms)]
                       .groupby(['ScenarioId','BranchingFactor','Source'], observed=True)['delta']
                       .mean().reset_index())
            if len(sub) < 6: continue
            bf_c = sub.BranchingFactor.values - bf_mean
            src_d = (sub.Source.values == 'dual').astype(float)
            X = np.column_stack([np.ones(len(sub)), bf_c, src_d, bf_c * src_d])
            y = sub.delta.values
            beta, *_ = np.linalg.lstsq(X, y, rcond=None)
            yhat = X @ beta
            resid = y - yhat
            dof = max(1, len(y) - X.shape[1])
            sigma2 = (resid ** 2).sum() / dof
            try:
                cov = sigma2 * np.linalg.inv(X.T @ X)
                se = np.sqrt(np.diag(cov))
            except np.linalg.LinAlgError:
                se = np.full(4, np.nan)
            ci = 1.96 * se
            ss_res = (resid ** 2).sum()
            ss_tot = ((y - y.mean()) ** 2).sum()
            r2 = 1 - ss_res / ss_tot if ss_tot > 0 else np.nan
            # Rescale BF coefficients to "per 100 BF units" for readability (raw BF spans ~80-620)
            rows.append(dict(
                Config=cfg, ms=ms, n_scenarios=len(sub),
                Intercept=round(beta[0], 2),               Intercept_CI95=round(ci[0], 2),
                beta_BF_per100=round(beta[1] * 100, 3),    beta_BF_CI95=round(ci[1] * 100, 3),
                beta_SourceDual=round(beta[2], 2),         beta_Source_CI95=round(ci[2], 2),
                beta_BFxSrc_per100=round(beta[3] * 100, 3),beta_BFxSrc_CI95=round(ci[3] * 100, 3),
                R2=round(r2, 4),
            ))
    t = pd.DataFrame(rows)
    t.to_csv(TABLES / 'E_ancova.csv', index=False)
    # Print just the 'avg' rows for at-a-glance reading
    print('\n  ANCOVA (avg across ms): paired_delta = intercept + beta_BF*(BF-mean)/100 '
          '+ beta_Source*DualDummy + beta_Interaction*(centered BF)*DualDummy')
    print(t[t.ms == 'avg'][['Config','beta_BF_per100','beta_SourceDual','beta_Source_CI95',
                            'beta_BFxSrc_per100','R2','n_scenarios']]
                            .to_string(index=False))


def extra_search_breadth_vs_cost(turn_df):
    """E5. Iteration breadth vs per-iteration cost — what the NodesEvaluated/Turn chart in analyze_v4
    actually means. Each MCTS iteration calls eval() exactly once (nodesEvaluated++), but the FmCalls
    each iteration consumes varies sharply by agent:
        - mutate-then-repair (EMCTS): full sequence repair after a random action insertion
        - crossover-expansion: parents are elite/valid, so repair touches fewer actions
        - BB tournaments: 7 shallow eval()s per burn, each fed an inherited sequence (cheap)
    Net effect: agents with cheaper iterations fit MORE iterations into the same wall-clock budget,
    so their NodesEvaluated/Turn is higher even though no extra work is done per iteration. This view
    decomposes the chart into (i) iteration breadth, (ii) FM-call cost per iteration."""
    rows = []
    for cfg in [c for c in ORDER if c in turn_df.Config.unique()]:
        for ms in MS_LEVELS:
            sub = turn_df[(turn_df.Config == cfg) & (turn_df.ThinkingMs == ms)]
            if sub.empty: continue
            n_iter = sub.NodesEvaluated.mean()
            fm = sub.FmCalls.mean()
            rows.append(dict(Config=cfg, ms=ms,
                             evals_per_turn=n_iter,
                             fmCalls_per_turn=fm,
                             fmCalls_per_eval=fm/n_iter if n_iter else np.nan))
    t = pd.DataFrame(rows).round(2)
    t.to_csv(TABLES / 'E5_search_breadth_vs_cost.csv', index=False)
    fig, axes = plt.subplots(1, 3, figsize=(15, 4.5), squeeze=False)
    axes = axes[0]
    metrics = [('evals_per_turn',   'eval() calls per turn'),
               ('fmCalls_per_turn', 'forward-model calls per turn'),
               ('fmCalls_per_eval', 'FM calls per eval')]
    cfgs_present = [c for c in ORDER if c in t.Config.unique()]
    x = np.arange(len(cfgs_present))
    for ax, (col, ylab) in zip(axes, metrics):
        for i, ms in enumerate(MS_LEVELS):
            sub = t[t.ms == ms].set_index('Config').reindex(cfgs_present)
            ax.plot(x, sub[col].values, marker='o', label=f'{ms} ms', lw=1.5)
        ax.set_xticks(x); ax.set_xticklabels(cfgs_present, rotation=60, ha='right', fontsize=7)
        ax.set_ylabel(ylab); ax.legend(fontsize=8)
    fig.suptitle('Per-iteration cost decomposition', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E5_search_breadth_vs_cost.png',
                                     bbox_inches='tight'); plt.close(fig)


def extra_convergence_speed(df_burn):
    """E4. Convergence speed: which agents reach 95% of their final gain earliest? Per agent per ms."""
    traj = trajectory_data(df_burn, kind='raw')
    rows = []
    for cfg in ORDER:
        if cfg not in traj.index.get_level_values('Config'): continue
        for ms in MS_LEVELS:
            if (cfg, ms) not in traj.index.droplevel(2): continue
            s = traj.loc[(cfg, ms)]
            start = s.iloc[0]; end = s.iloc[-1]
            if end - start <= 0:
                rows.append(dict(Config=cfg, ms=ms, burn_at_95=np.nan,
                                 start=start, end=end))
                continue
            thresh = start + 0.95 * (end - start)
            # first burn at which value exceeds threshold
            burn_at_95 = int(s[s >= thresh].index.min())
            rows.append(dict(Config=cfg, ms=ms, burn_at_95=burn_at_95,
                             start=start, end=end))
    t = pd.DataFrame(rows).round(3)
    t.to_csv(TABLES / 'E4_convergence_burn_at_95pct.csv', index=False)
    pivot = t.pivot_table(index='Config', columns='ms', values='burn_at_95')
    pivot = pivot.reindex([c for c in ORDER if c in pivot.index])
    fig, ax = plt.subplots(figsize=(9, 5))
    im = ax.imshow(pivot.values, aspect='auto', cmap='RdYlGn_r',
                   vmin=1, vmax=20)
    ax.set_xticks(range(pivot.shape[1])); ax.set_xticklabels([f'{c} ms' for c in pivot.columns])
    ax.set_yticks(range(pivot.shape[0])); ax.set_yticklabels(pivot.index, fontsize=8)
    for (i, j), v in np.ndenumerate(pivot.values):
        if np.isfinite(v):
            ax.text(j, i, int(v), ha='center', va='center',
                    color='white' if v < 6 else 'black', fontsize=9)
    fig.colorbar(im, ax=ax).set_label('burn phase at which 95% of final gain is reached')
    ax.set_title('Convergence speed — burn phase at 95% of final gain', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E4_convergence_speed.png'); plt.close(fig)


def _paired_delta_table(turn_df, portfolio):
    """Helper: per (Config, ms, scenario, visit) paired Δ vs EMCTS, joined with Source + BranchingFactor.
    Shared by E10/E11 so the merge logic lives in one place."""
    emcts = (turn_df[turn_df.Config == 'EMCTS']
             [['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]
             .drop_duplicates(['ThinkingMs','ScenarioId','VisitId'])
             .rename(columns={'FinalSequenceValue':'emcts_final'}))
    j = turn_df.merge(emcts, on=['ThinkingMs','ScenarioId','VisitId'], how='left', validate='m:1')
    if not portfolio.empty:
        j = j.merge(portfolio[['ScenarioId','Source','BranchingFactor']], on='ScenarioId')
    j['delta'] = j.FinalSequenceValue - j.emcts_final
    return j[j.Config != 'EMCTS'].copy()


def extra_inbreeding(turn_df):
    """E9. The 'inbreeding' / diversity-collapse evidence. As the per-turn budget grows, crossover
    increasingly RE-GENERATES sequences it has already evaluated (the top-2 elite parents converge to
    near-copies of the current optimum, so uniform crossover of them produces no new material), while
    mutation injects a fresh random action every iteration regardless of convergence. Probe metric:
        unique-sequence fraction = UniqueSequences / NodesEvaluated  (share of evals that are NEW).
    Produces the three tables used in the write-up + a compact 2-panel figure."""
    g = (turn_df.groupby(['Config','ThinkingMs'])
                .agg(evals=('NodesEvaluated','mean'),
                     unique=('UniqueSequences','mean'),
                     fmcalls=('FmCalls','mean'))
                .reset_index())
    g['uniq_frac'] = g['unique'] / g['evals']
    g['redundant'] = g['evals'] - g['unique']
    configs = [c for c in ORDER if c in turn_df.Config.unique()]
    frac = g.pivot(index='Config', columns='ThinkingMs', values='uniq_frac').reindex(configs)
    cnt  = g.pivot(index='Config', columns='ThinkingMs', values='unique').reindex(configs)
    red  = g.pivot(index='Config', columns='ThinkingMs', values='redundant').reindex(configs)
    frac.round(4).to_csv(TABLES / 'E9_unique_fraction.csv')
    cnt.round(2).to_csv(TABLES / 'E9_unique_count.csv')
    red.round(2).to_csv(TABLES / 'E9_redundant_evals.csv')
    # Δ(500→5000) summary column — the headline "more inbreeding at high budget" number.
    summary = pd.DataFrame({
        'uniq_frac_500': frac[500], 'uniq_frac_5000': frac[5000],
        'delta_uniq_frac_500_to_5000': (frac[5000] - frac[500]),
        'unique_500': cnt[500], 'unique_5000': cnt[5000],
        'redundant_500': red[500], 'redundant_5000': red[5000],
    }).round(4)
    summary.to_csv(TABLES / 'E9_inbreeding_summary.csv')

    fig, axes = plt.subplots(1, 2, figsize=(13, 5.2), squeeze=False)
    axes = axes[0]
    # Panel A — unique fraction vs ms, ALL configs (reveals the two opposite mechanisms by family).
    axA = axes[0]
    for cfg in configs:
        ys = [frac.loc[cfg, ms] for ms in MS_LEVELS]
        axA.plot(MS_LEVELS, ys, marker='o', color=COLORS[cfg], lw=1.8, label=cfg)
    axA.set_xscale('log'); axA.set_xticks(MS_LEVELS); axA.set_xticklabels([str(m) for m in MS_LEVELS])
    axA.set_xlabel('thinking time (ms, log scale)')
    axA.set_ylabel('unique-sequence fraction')
    axA.set_title('A. Unique-sequence fraction (all configs)', fontsize=10)
    axA.legend(fontsize=6, ncol=2, loc='center left')
    # Panel B — absolute unique sequences/turn, EMCTS vs Expansion family: the crossover point.
    axB = axes[1]
    focus = ['EMCTS'] + [f'CrossoverExpansion@{r}' for r in RATIOS]
    for cfg in focus:
        ys = [cnt.loc[cfg, ms] for ms in MS_LEVELS]
        lw = 2.8 if cfg in ('EMCTS','CrossoverExpansion@2') else 1.4
        axB.plot(MS_LEVELS, ys, marker='o', color=COLORS[cfg], lw=lw, label=cfg)
    axB.set_xscale('log'); axB.set_xticks(MS_LEVELS); axB.set_xticklabels([str(m) for m in MS_LEVELS])
    axB.set_xlabel('thinking time (ms, log scale)')
    axB.set_ylabel('unique sequences found per turn')
    axB.set_title('B. Unique sequences per turn (Expansion family)', fontsize=10)
    axB.legend(fontsize=7, loc='upper left')
    fig.suptitle('Diversity of evaluated sequences vs thinking time', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E9_inbreeding.png'); plt.close(fig)
    print('\n  E9 unique-sequence fraction (config × ms):')
    print(frac.round(3).to_string())
    print('\n  E9 absolute unique sequences / turn (config × ms):')
    print(cnt.round(1).to_string())


def extra_expansion_bf_time(turn_df, portfolio):
    """E10. For the Expansion family specifically (the user's BF question): how does the
    BF→performance relationship depend on BOTH source and thinking time? Regression lines of paired Δ
    vs BF, one per ms, in a (source × ratio) grid. Flatter lines at high ms ⇒ BF dependence weakens
    with budget; single slopes negative vs dual flat/positive ⇒ the source interaction."""
    if portfolio.empty:
        print('  E10: portfolio missing, skipping'); return
    j = _paired_delta_table(turn_df, portfolio)
    ratios_show = [2, 5, 15]
    ms_colors = dict(zip(MS_LEVELS, ['#fdae6b', '#e6550d', '#7f2704']))  # light→dark by ms
    fig, axes = plt.subplots(2, len(ratios_show), figsize=(4.7*len(ratios_show), 8.4),
                             squeeze=False, sharex=True, sharey=True)
    for col, r in enumerate(ratios_show):
        cfg = f'CrossoverExpansion@{r}'
        for row, src in enumerate(['single','dual']):
            ax = axes[row][col]
            sub_all = j[(j.Config == cfg) & (j.Source == src)]
            for ms in MS_LEVELS:
                s = (sub_all[sub_all.ThinkingMs == ms]
                     .groupby(['ScenarioId','BranchingFactor'])['delta'].mean().reset_index())
                if len(s) < 3: continue
                ax.scatter(s.BranchingFactor, s['delta'], s=9, alpha=0.25, color=ms_colors[ms])
                p = np.polyfit(s.BranchingFactor, s['delta'], 1)
                xx = np.linspace(s.BranchingFactor.min(), s.BranchingFactor.max(), 50)
                ax.plot(xx, p[0]*xx + p[1], color=ms_colors[ms], lw=2.4,
                        label=f'{ms} ms (slope {p[0]*100:+.1f}/100BF)')
            ax.axhline(0, color='black', lw=0.6, ls=':')
            ax.set_title(f'{cfg} — {src}', fontsize=9, fontweight='bold')
            if col == 0: ax.set_ylabel(f'{src} scenarios\npaired Δ vs EMCTS')
            if row == 1: ax.set_xlabel('branching factor')
            ax.legend(fontsize=6.5, loc='upper right')
    axes[0][0].set_ylim(-60, 60)  # clip to the central bulk so slope lines stay readable
    fig.suptitle('CrossoverExpansion — branching factor × source × thinking time',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E10_expansion_bf_source_time.png'); plt.close(fig)


def extra_source_effect_vs_time(turn_df, portfolio):
    """E11. The clean summary of the time-dependence we discussed: each agent's dual-vs-single
    advantage SHRINKS as the per-turn budget grows — the BF/source structure is a low-budget
    phenomenon. dual_advantage = mean(dual paired Δ) − mean(single paired Δ)."""
    if portfolio.empty:
        print('  E11: portfolio missing, skipping'); return
    j = _paired_delta_table(turn_df, portfolio)
    rows = []
    for cfg in [c for c in ORDER if c != 'EMCTS']:
        for ms in MS_LEVELS:
            sub = j[(j.Config == cfg) & (j.ThinkingMs == ms)]
            single = sub[sub.Source == 'single']['delta'].mean()
            dual = sub[sub.Source == 'dual']['delta'].mean()
            rows.append(dict(Config=cfg, ms=ms, single_delta=single, dual_delta=dual,
                             dual_minus_single=dual - single))
    t = pd.DataFrame(rows)
    t.round(2).to_csv(TABLES / 'E11_source_advantage_by_ms.csv', index=False)
    fig, ax = plt.subplots(figsize=(9.5, 5.6))
    for cfg in [c for c in ORDER if c != 'EMCTS']:
        sub = t[t.Config == cfg].set_index('ms').reindex(MS_LEVELS)
        emphasize = cfg.startswith('CrossoverExpansion') or cfg == 'CrossoverBB'
        ax.plot(MS_LEVELS, sub['dual_minus_single'].values, marker='o', color=COLORS[cfg],
                lw=2.4 if emphasize else 1.0, alpha=1.0 if emphasize else 0.35,
                label=cfg if emphasize else None, zorder=3 if emphasize else 1)
    ax.axhline(0, color='black', lw=0.8)
    ax.set_xscale('log'); ax.set_xticks(MS_LEVELS); ax.set_xticklabels([str(m) for m in MS_LEVELS])
    ax.set_xlabel('thinking time (ms, log scale)')
    ax.set_ylabel('dual advantage  =  mean(dual paired Δ) − mean(single paired Δ)')
    ax.set_title('Dual-vs-single advantage vs thinking time', fontweight='bold')
    ax.legend(fontsize=8, ncol=2)
    fig.tight_layout(); fig.savefig(FIGS / 'E11_source_advantage_vs_time.png'); plt.close(fig)
    print('\n  E11 dual-minus-single advantage by ms (positive = stronger in dual):')
    print(t.pivot(index='Config', columns='ms', values='dual_minus_single')
            .reindex([c for c in ORDER if c != 'EMCTS']).round(2).to_string())


# -------------------------------------------------- main --
def main():
    print(f'Loading from {ROOT} ...')
    df_burn = load_all()
    turn_df = per_turn(df_burn)
    portfolio = load_portfolio()
    print(f'  loaded {len(df_burn):,} burn rows; {len(turn_df):,} (config, scenario, visit) tuples')

    # --- 1+2: scenario × agent tables (raw + Δ vs EMCTS + %), per ms and averaged
    print('\nTABLES 1+2: scenario × agent tables')
    for ms in MS_LEVELS:
        means = scenario_agent_table(turn_df, ms)
        wide, summary = add_improvement_columns(means, save_name=f'T1_scenario_agent_{ms}ms')
        print(f'\n  -- {ms} ms summary --')
        print(summary[['meanFinal_mean','rawDelta_mean','rawDelta_CI95','pctDelta_mean','wins_vs_emcts']]
              .round(3).to_string())
    means_avg = scenario_agent_table(turn_df, 'avg')
    wide_avg, summary_avg = add_improvement_columns(means_avg, save_name='T2_scenario_agent_avg')
    print(f'\n  -- averaged across ms --')
    print(summary_avg[['meanFinal_mean','rawDelta_mean','rawDelta_CI95','pctDelta_mean','wins_vs_emcts']]
          .round(3).to_string())

    # --- 3: best per scenario
    print('\nFIGURE 3: best agent per scenario'); fig_best_per_scenario(turn_df)
    # --- 4: ranking points
    print('FIGURE 4: ranking points');           fig_ranking_points(turn_df)
    # --- 5: trajectory (raw)
    print('FIGURE 5: trajectory (raw)');         fig_trajectories(df_burn, kind='raw', name_prefix='fig5')
    print('FIGURE 5b: trajectory per agent (raw)'); fig_trajectories_per_agent(df_burn, kind='raw', name_prefix='fig5b')
    # --- 6: trajectory (% over initial)
    print('FIGURE 6: trajectory (% over init)'); fig_trajectories(df_burn, kind='pct', name_prefix='fig6')
    print('FIGURE 6b: trajectory per agent (% over init)'); fig_trajectories_per_agent(df_burn, kind='pct', name_prefix='fig6b')
    # --- 7: branching scatter (raw + %)
    print('FIGURE 7: branching scatter (raw)'); fig_branching_scatter(turn_df, portfolio, kind='raw')
    print('FIGURE 7: branching scatter (%)');   fig_branching_scatter(turn_df, portfolio, kind='pct')
    # --- 8: pct improvement bars
    print('FIGURE 8: % improvement bars');       fig_pct_improvement_bars(turn_df)

    # --- extras
    print('\nEXTRAS:')
    print('E1: paired delta with CI');           extra_paired_delta_with_ci(turn_df)
    print('E2: best ratio per budget');          extra_best_ratio_per_budget(turn_df)
    print('E3: single vs dual portfolio split'); extra_single_vs_dual(turn_df, portfolio)
    print('E4: convergence speed');              extra_convergence_speed(df_burn)
    print('E5: search breadth vs per-iter cost'); extra_search_breadth_vs_cost(turn_df)
    print('E6/E7/E8/ANCOVA: BF vs source diagnostics'); extra_branching_source_diagnostics(turn_df, portfolio)
    print('E9: crossover inbreeding / diversity collapse'); extra_inbreeding(turn_df)
    print('E10: Expansion BF × source × time');          extra_expansion_bf_time(turn_df, portfolio)
    print('E11: source advantage vs thinking time');     extra_source_effect_vs_time(turn_df, portfolio)

    print(f'\nDone. tables -> {TABLES}/  figures -> {FIGS}/')


if __name__ == '__main__':
    main()
