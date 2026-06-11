"""
analyze_v5_full.py — comprehensive v5 analysis (compute-budget companion; same analyses as v4).

Produces every table and figure the user requested in the v5 follow-up:
  1. Per-scenario × agent FinalSequenceValue tables — raw + Δ vs EMCTS + % vs EMCTS — one per budget.
  2. The same averaged across budgets.
  3. Best-agent-per-scenario bar chart (wins) — per budget + averaged.
  4. Ranking-point bar chart (last=1 … best=12, summed) — per budget + averaged.
  5. Trajectory of BestSequenceValue per burn phase — per budget × per agent and averaged.
  6. Trajectory as % improvement over initial sequence — same layout as 5.
  7. Branching-factor vs improvement scatter — per agent per budget + per agent averaged. Raw + %.
  8. Bar chart of mean % improvement over initial sequence — per budget + averaged.

Plus a handful of analysis-driven extras the data invites:
  E1. Paired delta vs EMCTS with 95% CIs (n≈5000 per cell — small effects are detectable).
  E2. Best-ratio-per-budget table for Expansion + Combo (the v3-style sweep).
  E3. Single-vs-dual portfolio split: does the ranking change?
  E4. Convergence speed — burn phase at which each agent reaches 95% of its final gain.

v5 additions — the single-vs-dual hypothesis battery, built on the per-turn operator telemetry
(turn_telemetry.csv per leaf; see analysis/V5_HYPOTHESES.md for the design):
  E17. Operator credit (H4) — share of best-sequence gain from crossover vs mutation children.
  E18. Blend coherence (H1) — crossover child value vs its two parents, by source × BF tier.
  E19. Repair disruption (H2) — actions replaced during repair, crossover vs mutation baseline.
  E20. Elite-pool collapse (H3) — parent similarity + duplicate-child rates by source × budget.
  E21. Action composition (H5) — combat/economy action mix of the captured scenarios.

Run:  python analysis/analyze_v5_full.py [test_results/v5]
Output: analysis/figures_v5_full/*.png  +  analysis/tables_v5_full/*.csv
"""
import os, sys, glob, re
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import to_rgba
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test_results/v5')
FIGS = Path('analysis/figures_v5_full'); FIGS.mkdir(parents=True, exist_ok=True)
TABLES = Path('analysis/tables_v5_full'); TABLES.mkdir(parents=True, exist_ok=True)

MS_LEVELS = [13400, 26800, 134000]
RATIOS = [2, 5, 10, 20, 50]

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
                     r'/(?P<ms>\d+)cu/results\.csv$')


# -------------------------------------------------- loaders ------
def _config_label(agent, ratio):
    if agent in ('EMCTS', 'CrossoverBB'):
        return agent
    return f'{agent}@{int(ratio)}'

def load_all():
    """Load every results.csv with provenance columns added. Returns a single DataFrame with one row
    per (config, scenario, visit, burn). Memory: ~3.5 GB at full v5 size — fine on 32 GB."""
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
    """Mean FinalSequenceValue per (scenario, config). For 'avg', average over budgets BEFORE
    aggregating so each scenario × visit × budget × config contributes equally."""
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
    panels = [(ms, f'{ms} cu') for ms in MS_LEVELS] + [('avg', 'avg across budgets')]
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
    panels = [(ms, f'{ms} cu') for ms in MS_LEVELS] + [('avg', 'avg across budgets')]
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
    panels = [(ms, f'{ms} cu') for ms in MS_LEVELS] + [('avg', 'avg across budgets')]
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
            ax.plot(s.index, s.values, label=f'{ms} cu', lw=1.7, marker='o', markersize=3,
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
                ax.set_title(f'{key}' + (' cu' if key != 'avg' else ''), fontsize=10)
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
    """Bar chart of mean % improvement over initial sequence, per agent. 4 panels (per budget + avg)."""
    turn_df = turn_df.copy()
    turn_df['pctImp'] = (turn_df.FinalSequenceValue - turn_df.InitialSequenceValue) \
                       / turn_df.InitialSequenceValue.abs() * 100
    panels = [(ms, f'{ms} cu') for ms in MS_LEVELS] + [('avg', 'avg across budgets')]
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
    fig.suptitle('% improvement over initial sequence — per agent per budget',
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
    # per budget + avg
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
        ax.set_title(f'{key}' + (' cu' if key != 'avg' else ''))
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
    # Companion figure: ratio sweep per budget per agent_base
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), squeeze=False)
    axes = axes[0]
    for ax, agent_base in zip(axes, ('CrossoverExpansion','CrossoverCombo')):
        for ms in MS_LEVELS:
            sub = t[(t.Agent==agent_base) & (t.ms==ms)].sort_values('Ratio')
            ax.plot(sub.Ratio, sub.deltaVsEMCTS, marker='o', label=f'{ms} cu', lw=1.8)
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
    # Plot: per budget, side-by-side single vs dual paired Δ
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
        ax.set_title(f'{ms} cu')
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
    fig.suptitle('BF-tertile × source heatmap — paired Δ vs EMCTS (averaged over budgets and visits)',
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
    print('\n  ANCOVA (avg across budgets): paired_delta = intercept + beta_BF*(BF-mean)/100 '
          '+ beta_Source*DualDummy + beta_Interaction*(centered BF)*DualDummy')
    print(t[t.ms == 'avg'][['Config','beta_BF_per100','beta_SourceDual','beta_Source_CI95',
                            'beta_BFxSrc_per100','R2','n_scenarios']]
                            .to_string(index=False))


def extra_search_breadth_vs_cost(turn_df):
    """E5. Iteration breadth vs per-iteration cost — what the NodesEvaluated/Turn chart in the basic analyze script
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
            ax.plot(x, sub[col].values, marker='o', label=f'{ms} cu', lw=1.5)
        ax.set_xticks(x); ax.set_xticklabels(cfgs_present, rotation=60, ha='right', fontsize=7)
        ax.set_ylabel(ylab); ax.legend(fontsize=8)
    fig.suptitle('Per-iteration cost decomposition', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E5_search_breadth_vs_cost.png',
                                     bbox_inches='tight'); plt.close(fig)


def extra_convergence_speed(df_burn):
    """E4. Convergence speed: which agents reach 95% of their final gain earliest? Per budget."""
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
    ax.set_xticks(range(pivot.shape[1])); ax.set_xticklabels([f'{c} cu' for c in pivot.columns])
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
    axA.set_xlabel('compute budget (units, log scale)')
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
    axB.set_xlabel('compute budget (units, log scale)')
    axB.set_ylabel('unique sequences found per turn')
    axB.set_title('B. Unique sequences per turn (Expansion family)', fontsize=10)
    axB.legend(fontsize=7, loc='upper left')
    fig.suptitle('Diversity of evaluated sequences vs compute budget', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E9_inbreeding.png'); plt.close(fig)
    print('\n  E9 unique-sequence fraction (config × budget):')
    print(frac.round(3).to_string())
    print('\n  E9 absolute unique sequences / turn (config × budget):')
    print(cnt.round(1).to_string())


def extra_expansion_bf_time(turn_df, portfolio):
    """E10. For the Expansion family specifically (the user's BF question): how does the
    BF→performance relationship depend on BOTH source and compute budget? Regression lines of paired Δ
    vs BF, one per budget, in a (source × ratio) grid. Flatter lines at high budget ⇒ BF dependence weakens
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
                        label=f'{ms} cu (slope {p[0]*100:+.1f}/100BF)')
            ax.axhline(0, color='black', lw=0.6, ls=':')
            ax.set_title(f'{cfg} — {src}', fontsize=9, fontweight='bold')
            if col == 0: ax.set_ylabel(f'{src} scenarios\npaired Δ vs EMCTS')
            if row == 1: ax.set_xlabel('branching factor')
            ax.legend(fontsize=6.5, loc='upper right')
    axes[0][0].set_ylim(-60, 60)  # clip to the central bulk so slope lines stay readable
    fig.suptitle('CrossoverExpansion — branching factor × source × compute budget',
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
    ax.set_xlabel('compute budget (units, log scale)')
    ax.set_ylabel('dual advantage  =  mean(dual paired Δ) − mean(single paired Δ)')
    ax.set_title('Dual-vs-single advantage vs compute budget', fontweight='bold')
    ax.legend(fontsize=8, ncol=2)
    fig.tight_layout(); fig.savefig(FIGS / 'E11_source_advantage_vs_time.png'); plt.close(fig)
    print('\n  E11 dual-minus-single advantage by budget (positive = stronger in dual):')
    print(t.pivot(index='Config', columns='ms', values='dual_minus_single')
            .reindex([c for c in ORDER if c != 'EMCTS']).round(2).to_string())


def _ratio_of(cfg):
    """Integer crossover ratio from a config label like 'CrossoverExpansion@5'."""
    return int(cfg.split('@')[1])


def extra_ratio_nodes_unique(turn_df):
    """E12. Effect of the crossover ratio on nodes evaluated, forward-model calls, and the
    unique-sequence fraction, for the CrossoverExpansion family. NodesEvaluated counts full
    turn-length sequences scored (tens per turn); FmCalls is the forward-model budget (thousands)."""
    rows = []
    for fam in ('CrossoverExpansion', 'CrossoverCombo'):
        for r in RATIOS:
            cfg = f'{fam}@{r}'
            for ms in MS_LEVELS:
                s = turn_df[(turn_df.Config == cfg) & (turn_df.ThinkingMs == ms)]
                if s.empty: continue
                rows.append(dict(family=fam, ratio=r, ms=ms,
                                 nodes=s.NodesEvaluated.mean(),
                                 fmcalls=s.FmCalls.mean(),
                                 unique=s.UniqueSequences.mean(),
                                 uniq_frac=s.UniqueSequences.mean() / s.NodesEvaluated.mean()))
    t = pd.DataFrame(rows).round(4)
    t.to_csv(TABLES / 'E12_ratio_sweep.csv', index=False)

    exp = t[t.family == 'CrossoverExpansion']
    fig, axes = plt.subplots(1, 3, figsize=(15, 4.3), squeeze=False); axes = axes[0]
    panels = [('nodes', 'nodes evaluated per turn'),
              ('fmcalls', 'forward-model calls per turn'),
              ('uniq_frac', 'unique-sequence fraction')]
    for ax, (col, ylab) in zip(axes, panels):
        for ms in MS_LEVELS:
            sub = exp[exp.ms == ms].sort_values('ratio')
            ax.plot(sub.ratio, sub[col], marker='o', lw=1.8, label=f'{ms} cu')
        ax.set_xticks(RATIOS); ax.set_xlabel('crossover ratio')
        ax.set_ylabel(ylab); ax.legend(fontsize=8)
    fig.suptitle('CrossoverExpansion: effect of crossover ratio on search breadth and diversity',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E12_ratio_nodes_unique.png'); plt.close(fig)


def extra_bb_nodes_table(turn_df):
    """E13. Nodes evaluated and unique-sequence fraction WITH vs WITHOUT crossover bridge burning.
    Pairs hold the rest of the configuration fixed: EMCTS↔CrossoverBB (no expansion) and
    CrossoverExpansion@r↔CrossoverCombo@r (expansion at ratio r)."""
    def stats(cfg, ms):
        s = turn_df[(turn_df.Config == cfg) & (turn_df.ThinkingMs == ms)]
        if s.empty: return (np.nan, np.nan)
        return (s.NodesEvaluated.mean(), s.UniqueSequences.mean() / s.NodesEvaluated.mean())
    pairs = [('no expansion', 'EMCTS', 'CrossoverBB')]
    for r in RATIOS:
        pairs.append((f'expansion@{r}', f'CrossoverExpansion@{r}', f'CrossoverCombo@{r}'))
    rows = []
    for base, no_bb, bb in pairs:
        for ms in MS_LEVELS:
            n0, u0 = stats(no_bb, ms); n1, u1 = stats(bb, ms)
            rows.append(dict(base=base, ms=ms,
                             nodes_noCBB=round(n0, 1), nodes_CBB=round(n1, 1),
                             uniqFrac_noCBB=round(u0, 3), uniqFrac_CBB=round(u1, 3)))
    t = pd.DataFrame(rows)
    t.to_csv(TABLES / 'E13_with_without_cbb.csv', index=False)
    print('\n  E13 nodes & unique fraction, with vs without crossover bridge burning:')
    print(t.to_string(index=False))


def extra_bb_usefulness(df_burn):
    """E14. Usefulness of crossover bridge burning: for each CBB agent, the share of CBB events whose
    bred child improved on the best elite parent (RawImprovement>0), tied it (=0), or was worse (<0).
    Plus the pooled average across all CBB agents."""
    if 'RawImprovement' not in df_burn.columns:
        print('  E14: no RawImprovement column, skipping'); return
    ev = df_burn[df_burn.RawImprovement.notna()]
    cbb = ['CrossoverBB'] + [f'CrossoverCombo@{r}' for r in RATIOS]
    rows = []
    for cfg in cbb:
        s = ev[ev.Config == cfg]
        if s.empty: continue
        rows.append(dict(Config=cfg, n=len(s),
                         improved=(s.RawImprovement > 0).mean(),
                         no_change=(s.RawImprovement == 0).mean(),
                         worse=(s.RawImprovement < 0).mean()))
    rows.append(dict(Config='all CBB (pooled)', n=len(ev),
                     improved=(ev.RawImprovement > 0).mean(),
                     no_change=(ev.RawImprovement == 0).mean(),
                     worse=(ev.RawImprovement < 0).mean()))
    t = pd.DataFrame(rows)
    t.round(4).to_csv(TABLES / 'E14_bb_usefulness.csv', index=False)

    fig, ax = plt.subplots(figsize=(10, 5))
    x = np.arange(len(t))
    imp, noc, wor = t.improved.values * 100, t.no_change.values * 100, t.worse.values * 100
    ax.bar(x, imp, color='#2ca02c', label='improved')
    ax.bar(x, noc, bottom=imp, color='#9e9e9e', label='no change')
    ax.bar(x, wor, bottom=imp + noc, color='#d62728', label='worse')
    ax.set_xticks(x); ax.set_xticklabels(t.Config, rotation=45, ha='right', fontsize=8)
    ax.set_ylabel('% of crossover bridge-burn events'); ax.set_ylim(0, 100)
    ax.legend(fontsize=8, loc='upper right')
    fig.suptitle('Usefulness of crossover bridge burning — bred child vs best elite parent',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E14_bb_usefulness.png'); plt.close(fig)


def extra_crossover_mechanism(turn_df):
    """E15/E16. Why does crossover change performance? Decompose the per-turn search into breadth
    (NodesEvaluated), diversity (unique fraction), repair cost (FmCalls per node) and node-region
    quality (AvgNodeValue = mean heuristic value of all evaluated nodes), then test which of these
    co-moves with paired Δ vs EMCTS at each budget. AvgNodeValue is a proxy for HOW GOOD a region
    of sequence space the search spends its evaluations in — high = concentrated near the optimum."""
    em = (turn_df[turn_df.Config == 'EMCTS']
          [['ThinkingMs','ScenarioId','VisitId','FinalSequenceValue']]
          .drop_duplicates(['ThinkingMs','ScenarioId','VisitId']).rename(columns={'FinalSequenceValue':'emc'}))
    jj = turn_df.merge(em, on=['ThinkingMs','ScenarioId','VisitId'])
    jj['pdelta'] = jj.FinalSequenceValue - jj.emc
    rows = []
    for cfg in [c for c in ORDER if c in turn_df.Config.unique()]:
        for ms in MS_LEVELS:
            s = turn_df[(turn_df.Config == cfg) & (turn_df.ThinkingMs == ms)]
            if s.empty: continue
            nodes = s.NodesEvaluated.mean(); uniq = s.UniqueSequences.mean()
            rows.append(dict(Config=cfg, ms=ms, nodes=nodes, unique=uniq, uniq_frac=uniq / nodes,
                             fm_per_node=s.FmCalls.mean() / nodes, AvgNodeValue=s.AvgNodeValue.mean(),
                             pdelta=jj[(jj.Config == cfg) & (jj.ThinkingMs == ms)].pdelta.mean()))
    t = pd.DataFrame(rows)
    t.round(3).to_csv(TABLES / 'E15_search_metrics.csv', index=False)

    # --- E15: what crossover does to the search — repair cost & node-region quality vs ratio ---
    exp = (t[t.Config.str.startswith('CrossoverExpansion')]
           .assign(ratio=lambda d: d.Config.str.split('@').str[1].astype(int)))
    emt = t[t.Config == 'EMCTS'].set_index('ms')
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.3), squeeze=False); axes = axes[0]
    for ax, (col, ylab) in zip(axes, [('fm_per_node', 'forward-model calls per node'),
                                      ('AvgNodeValue', 'mean value of evaluated nodes')]):
        for ms in MS_LEVELS:
            sub = exp[exp.ms == ms].sort_values('ratio')
            line, = ax.plot(sub.ratio, sub[col], marker='o', lw=1.8, label=f'{ms} cu')
            ax.axhline(emt.loc[ms, col], color=line.get_color(), ls=':', lw=1.2)
        ax.set_xticks(RATIOS); ax.set_xlabel('crossover ratio'); ax.set_ylabel(ylab); ax.legend(fontsize=8)
    fig.suptitle('What crossover does to the search (dotted = EMCTS baseline)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E15_crossover_mechanism.png'); plt.close(fig)

    # --- E16: which search property predicts performance, by budget (correlation across configs) ---
    metrics = [('nodes', 'nodes evaluated'), ('uniq_frac', 'unique fraction'),
               ('AvgNodeValue', 'mean node value')]
    fig, ax = plt.subplots(figsize=(8, 5))
    width = 0.26; x = np.arange(len(MS_LEVELS))
    for i, (col, lab) in enumerate(metrics):
        corrs = [t[t.ms == ms].pdelta.corr(t[t.ms == ms][col]) for ms in MS_LEVELS]
        ax.bar(x + (i - 1) * width, corrs, width, label=lab)
    ax.axhline(0, color='black', lw=0.8)
    ax.set_xticks(x); ax.set_xticklabels([f'{m} cu' for m in MS_LEVELS])
    ax.set_ylabel('correlation with paired Δ vs EMCTS (across 12 configs)'); ax.set_ylim(-1, 1)
    ax.legend(fontsize=8)
    fig.suptitle('Which search property predicts performance, by compute budget', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E16_performance_attribution.png'); plt.close(fig)


# -------------------------------------------------- v5 operator telemetry: E17-E21 --
# One turn_telemetry.csv per leaf (one row per scenario × visit) collected to discriminate the
# hypotheses for the single-vs-dual gap. See analysis/V5_HYPOTHESES.md for the full design.
TELE_RE = re.compile(r'(?P<agent>EMCTS|CrossoverBB|CrossoverExpansion|CrossoverCombo)'
                     r'(?:/ratio_(?P<ratio>\d+))?'
                     r'/(?P<ms>\d+)cu/turn_telemetry\.csv$')

def load_telemetry():
    """One row per (config, budget, scenario, visit) with the operator-telemetry counters."""
    frames = []
    for f in sorted(glob.glob(str(ROOT) + '/**/turn_telemetry.csv', recursive=True)):
        rel = f.replace(os.sep, '/').split(str(ROOT).replace(os.sep, '/') + '/', 1)[-1]
        m = TELE_RE.search(rel)
        if not m: continue
        df = pd.read_csv(f)
        df['Config'] = _config_label(m.group('agent'), m.group('ratio')) if m.group('ratio') \
                        else m.group('agent')
        df['ThinkingMs'] = int(m.group('ms'))   # compute budget (cu); column name kept for consistency
        frames.append(df)
    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True)

def _bf_tiers_by_source(portfolio):
    """Per-source BF tertiles (~1/3 of each source per tier) — same convention as E7."""
    p = portfolio.copy()
    labels = ['Low BF', 'Mid BF', 'High BF']
    p['BF_tier'] = pd.Series(index=p.index, dtype=object)
    for src in ('single', 'dual'):
        mask = p.Source == src
        p.loc[mask, 'BF_tier'] = pd.qcut(p.loc[mask, 'BranchingFactor'], 3, labels=labels).astype(str)
    p['BF_tier'] = pd.Categorical(p['BF_tier'], categories=labels, ordered=True)
    return p[['ScenarioId', 'BF_tier']]


def extra_operator_credit(tele):
    """E17 (H4 operator credit). Decompose each agent's best-sequence improvements by generating
    operator: the share of total best-gain value contributed by crossover children vs mutation
    children, by source. If crossover rarely drives the best sequence forward in single games, its
    iterations are pure overhead there."""
    xo_cfgs = [c for c in ORDER if '@' in c and c in tele.Config.unique()]
    rows = []
    for cfg in xo_cfgs:
        for ms in MS_LEVELS:
            for src in ('single', 'dual'):
                s = tele[(tele.Config == cfg) & (tele.ThinkingMs == ms) & (tele.ScenarioSource == src)]
                if s.empty: continue
                xg, mg = s.XoBestGainSum.sum(), s.MutBestGainSum.sum()
                rows.append(dict(Config=cfg, budget=ms, source=src,
                                 xo_gain_share=xg / (xg + mg) if (xg + mg) > 0 else np.nan,
                                 xo_best_events=s.XoBestGains.sum(), mut_best_events=s.MutBestGains.sum(),
                                 xo_events=s.XoEvents.sum(), mut_events=s.MutEvents.sum(),
                                 xo_gain_per_event=xg / max(1, s.XoEvents.sum()),
                                 mut_gain_per_event=mg / max(1, s.MutEvents.sum())))
    t = pd.DataFrame(rows)
    t.round(4).to_csv(TABLES / 'E17_operator_credit.csv', index=False)
    fig, axes = plt.subplots(1, len(MS_LEVELS), figsize=(5.2 * len(MS_LEVELS), 5),
                             squeeze=False, sharey=True)
    axes = axes[0]
    for ax, ms in zip(axes, MS_LEVELS):
        sub = t[t.budget == ms]
        x = np.arange(len(xo_cfgs))
        sng = sub[sub.source == 'single'].set_index('Config').reindex(xo_cfgs)['xo_gain_share']
        dul = sub[sub.source == 'dual'].set_index('Config').reindex(xo_cfgs)['xo_gain_share']
        ax.bar(x - 0.2, sng.values * 100, 0.4, color='#1f78b4', label='single')
        ax.bar(x + 0.2, dul.values * 100, 0.4, color='#e31a1c', label='dual')
        ax.set_xticks(x); ax.set_xticklabels(xo_cfgs, rotation=60, ha='right', fontsize=7)
        ax.set_title(f'{ms} cu')
        ax.set_ylabel('% of best-sequence gain from crossover children')
    axes[0].legend(fontsize=8)
    fig.suptitle('Share of best-sequence improvement contributed by crossover vs mutation',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E17_operator_credit.png'); plt.close(fig)


def extra_blend_coherence(tele, portfolio):
    """E18 (H1 blend coherence). Quality of crossover blends relative to their two parents, by
    source × BF tier. 'Beats both parents' = synergistic blend; 'below both parents' = destructive
    blend. If economic (single) blends are structurally incoherent, destructive rates should be
    elevated there even at the same BF tier."""
    if portfolio.empty:
        print('  E18: portfolio missing, skipping'); return
    tiers = _bf_tiers_by_source(portfolio)
    t = tele.merge(tiers, on='ScenarioId')
    exp_cfgs = [f'CrossoverExpansion@{r}' for r in RATIOS
                if f'CrossoverExpansion@{r}' in t.Config.unique()]
    xs = ['Low BF', 'Mid BF', 'High BF']
    rows = []
    for cfg in [c for c in ORDER if '@' in c and c in t.Config.unique()]:
        for src in ('single', 'dual'):
            for tier in xs:
                s = t[(t.Config == cfg) & (t.ScenarioSource == src) & (t.BF_tier == tier)]
                ev = s.XoEvents.sum()
                if ev == 0: continue
                rows.append(dict(Config=cfg, source=src, BF_tier=tier, xo_events=ev,
                                 beats_both_pct=100 * s.XoChildBeatsBestParent.sum() / ev,
                                 below_both_pct=100 * s.XoChildWorseThanBothParents.sum() / ev,
                                 child_vs_best_parent=s.XoChildVsBestParentSum.sum() / ev,
                                 child_vs_mean_parent=s.XoChildVsMeanParentSum.sum() / ev))
    tt = pd.DataFrame(rows)
    tt.round(4).to_csv(TABLES / 'E18_blend_coherence.csv', index=False)
    fig, axes = plt.subplots(2, 2, figsize=(11, 8), squeeze=False, sharex=True)
    metrics = [('beats_both_pct', '% of blends beating both parents'),
               ('below_both_pct', '% of blends below both parents')]
    for i, (col, ylab) in enumerate(metrics):
        for j, src in enumerate(('single', 'dual')):
            ax = axes[i][j]
            for cfg in exp_cfgs:
                s = tt[(tt.Config == cfg) & (tt.source == src)].set_index('BF_tier').reindex(xs)
                ax.plot(range(3), s[col].values, marker='o', color=COLORS[cfg], label=cfg, lw=1.8)
            ax.set_xticks(range(3)); ax.set_xticklabels(xs)
            if j == 0: ax.set_ylabel(ylab)
            if i == 0: ax.set_title(f'{src} scenarios')
            if i == 0 and j == 0: ax.legend(fontsize=6.5)
    fig.suptitle('Crossover blend quality vs the two parents (Expansion family, pooled budgets)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E18_blend_coherence.png'); plt.close(fig)


def extra_repair_disruption(tele, portfolio):
    """E19 (H2 repair disruption). Fraction of child actions greedily REPLACED during repair —
    crossover children vs the mutation baseline, by source × BF tier. If repair rewrites crossover
    children specifically in single/high-BF states, the spliced material never survives to be
    evaluated and crossover degenerates into greedy resampling."""
    if portfolio.empty:
        print('  E19: portfolio missing, skipping'); return
    tiers = _bf_tiers_by_source(portfolio)
    t = tele.merge(tiers, on='ScenarioId')
    exp_cfgs = [f'CrossoverExpansion@{r}' for r in RATIOS
                if f'CrossoverExpansion@{r}' in t.Config.unique()]
    xs = ['Low BF', 'Mid BF', 'High BF']
    rows = []
    for cfg in [c for c in ORDER if c in t.Config.unique()]:
        for src in ('single', 'dual'):
            for tier in xs:
                s = t[(t.Config == cfg) & (t.ScenarioSource == src) & (t.BF_tier == tier)]
                if s.empty: continue
                mk, mr = s.MutRepairKept.sum(), s.MutRepairReplaced.sum()
                xk, xr = s.XoRepairKept.sum(), s.XoRepairReplaced.sum()
                rows.append(dict(Config=cfg, source=src, BF_tier=tier,
                                 mut_replaced_frac=mr / (mk + mr) if (mk + mr) else np.nan,
                                 xo_replaced_frac=xr / (xk + xr) if (xk + xr) else np.nan))
    tt = pd.DataFrame(rows)
    tt.round(4).to_csv(TABLES / 'E19_repair_disruption.csv', index=False)
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.8), squeeze=False, sharey=True)
    axes = axes[0]
    for ax, src in zip(axes, ('single', 'dual')):
        e = tt[(tt.Config == 'EMCTS') & (tt.source == src)].set_index('BF_tier').reindex(xs)
        ax.plot(range(3), e.mut_replaced_frac.values * 100, marker='s', color='#222222', ls='--',
                lw=2.0, label='mutation children (EMCTS)')
        for cfg in exp_cfgs:
            s = tt[(tt.Config == cfg) & (tt.source == src)].set_index('BF_tier').reindex(xs)
            ax.plot(range(3), s.xo_replaced_frac.values * 100, marker='o', color=COLORS[cfg],
                    lw=1.8, label=f'crossover children ({cfg})')
        ax.set_xticks(range(3)); ax.set_xticklabels(xs)
        ax.set_title(f'{src} scenarios'); ax.set_ylabel('% of child actions replaced by repair')
    axes[0].legend(fontsize=6.5)
    fig.suptitle('Repair disruption of generated children (pooled budgets)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E19_repair_disruption.png'); plt.close(fig)


def extra_pool_collapse(tele):
    """E20 (H3 elite-pool collapse). Positional similarity of the two crossover parents and the
    duplicate-child rates, vs compute budget, by source. High similarity + high duplicate rate = the
    top-2 pool has converged to near-clones and crossover re-breeds the same material (inbreeding)."""
    exp_cfgs = [f'CrossoverExpansion@{r}' for r in RATIOS
                if f'CrossoverExpansion@{r}' in tele.Config.unique()]
    rows = []
    for cfg in exp_cfgs + (['EMCTS'] if 'EMCTS' in tele.Config.unique() else []):
        for ms in MS_LEVELS:
            for src in ('single', 'dual'):
                s = tele[(tele.Config == cfg) & (tele.ThinkingMs == ms) & (tele.ScenarioSource == src)]
                if s.empty: continue
                xe = s.XoEvents.sum(); me = s.MutEvents.sum()
                rows.append(dict(Config=cfg, budget=ms, source=src,
                                 parent_similarity=s.XoParentSimilaritySum.sum() / xe if xe else np.nan,
                                 xo_dup_rate=s.XoChildDuplicates.sum() / xe if xe else np.nan,
                                 mut_dup_rate=s.MutChildDuplicates.sum() / me if me else np.nan))
    t = pd.DataFrame(rows)
    t.round(4).to_csv(TABLES / 'E20_pool_collapse.csv', index=False)
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), squeeze=False)
    axes = axes[0]
    panels = [('parent_similarity', 'mean parent–parent positional similarity'),
              ('xo_dup_rate', 'duplicate-child rate (crossover children)')]
    for ax, (col, ylab) in zip(axes, panels):
        for cfg in exp_cfgs:
            for src, ls in (('dual', '-'), ('single', '--')):
                s = t[(t.Config == cfg) & (t.source == src)].set_index('budget').reindex(MS_LEVELS)
                ax.plot(MS_LEVELS, s[col].values, marker='o', color=COLORS[cfg], ls=ls, lw=1.7,
                        label=f'{cfg} ({src})' if col == 'parent_similarity' else None)
        ax.set_xscale('log'); ax.set_xticks(MS_LEVELS)
        ax.set_xticklabels([str(m) for m in MS_LEVELS])
        ax.set_xlabel('compute budget (units, log scale)'); ax.set_ylabel(ylab)
    axes[0].legend(fontsize=6, ncol=2)
    fig.suptitle('Elite-pool similarity and duplicate breeding by source (solid = dual, dashed = single)',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E20_pool_collapse.png'); plt.close(fig)


def extra_action_composition(portfolio):
    """E21 (H5 action-space composition). Action-type composition of the captured scenarios — is the
    single/dual gap better explained by combat-action availability than by the source label? The
    per-scenario columns also let downstream analysis stratify performance by attack share."""
    needed = {'NumAttackActions', 'NumMoveActions', 'NumOtherUnitActions',
              'NumCityActions', 'NumTribeActions'}
    if portfolio.empty or not needed.issubset(portfolio.columns):
        print('  E21: portfolio lacks action-composition columns, skipping'); return
    p = portfolio.copy()
    p['attack_share'] = p.NumAttackActions / p.BranchingFactor
    cats = ['NumAttackActions', 'NumMoveActions', 'NumOtherUnitActions',
            'NumCityActions', 'NumTribeActions']
    labels = ['Attack', 'Move', 'Other unit', 'City', 'Tribe']
    summary = p.groupby('Source')[cats + ['attack_share', 'BranchingFactor']].mean().round(3)
    summary.to_csv(TABLES / 'E21_action_composition.csv')
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.6), squeeze=False)
    axes = axes[0]
    x = np.arange(len(cats))
    for src, color, off in (('single', '#1f78b4', -0.2), ('dual', '#e31a1c', +0.2)):
        axes[0].bar(x + off, summary.loc[src, cats].values, 0.4, color=color, label=src)
    axes[0].set_xticks(x); axes[0].set_xticklabels(labels)
    axes[0].set_ylabel('mean available actions per scenario'); axes[0].legend(fontsize=8)
    for src, color in (('single', '#1f78b4'), ('dual', '#e31a1c')):
        s = p[p.Source == src]
        axes[1].scatter(s.BranchingFactor, s.attack_share * 100, s=18, alpha=0.6, color=color,
                        label=src, edgecolor='black', linewidth=0.3)
    axes[1].set_xlabel('branching factor'); axes[1].set_ylabel('attack actions (% of action space)')
    axes[1].legend(fontsize=8)
    fig.suptitle('Action-type composition of the captured scenarios', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'E21_action_composition.png'); plt.close(fig)
    print(summary.to_string())


# -------------------------------------------------- main --
def main():
    print(f'Loading from {ROOT} ...')
    df_burn = load_all()
    turn_df = per_turn(df_burn)
    portfolio = load_portfolio()
    print(f'  loaded {len(df_burn):,} burn rows; {len(turn_df):,} (config, scenario, visit) tuples')

    # --- 1+2: scenario × agent tables (raw + Δ vs EMCTS + %), per budget and averaged
    print('\nTABLES 1+2: scenario × agent tables')
    for ms in MS_LEVELS:
        means = scenario_agent_table(turn_df, ms)
        wide, summary = add_improvement_columns(means, save_name=f'T1_scenario_agent_{ms}cu')
        print(f'\n  -- {ms} cu summary --')
        print(summary[['meanFinal_mean','rawDelta_mean','rawDelta_CI95','pctDelta_mean','wins_vs_emcts']]
              .round(3).to_string())
    means_avg = scenario_agent_table(turn_df, 'avg')
    wide_avg, summary_avg = add_improvement_columns(means_avg, save_name='T2_scenario_agent_avg')
    print(f'\n  -- averaged across budgets --')
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
    print('E11: source advantage vs compute budget');     extra_source_effect_vs_time(turn_df, portfolio)
    print('E12: ratio vs nodes & unique fraction');      extra_ratio_nodes_unique(turn_df)
    print('E13: nodes & unique fraction with/without CBB'); extra_bb_nodes_table(turn_df)
    print('E14: bridge-burning usefulness');             extra_bb_usefulness(df_burn)
    print('E15/E16: crossover mechanism + attribution'); extra_crossover_mechanism(turn_df)

    # --- v5 operator telemetry: the single-vs-dual hypothesis battery (see V5_HYPOTHESES.md)
    print('\nOPERATOR TELEMETRY (single-vs-dual hypotheses):')
    tele = load_telemetry()
    if tele.empty:
        print('  no turn_telemetry.csv found — skipping E17-E20')
    else:
        print('E17: operator credit (H4)');     extra_operator_credit(tele)
        print('E18: blend coherence (H1)');     extra_blend_coherence(tele, portfolio)
        print('E19: repair disruption (H2)');   extra_repair_disruption(tele, portfolio)
        print('E20: elite-pool collapse (H3)'); extra_pool_collapse(tele)
    print('E21: action composition (H5)');      extra_action_composition(portfolio)

    print(f'\nDone. tables -> {TABLES}/  figures -> {FIGS}/')


if __name__ == '__main__':
    main()
