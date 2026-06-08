"""
analyze_v4.py — v4 Isolation Phase analysis.

Reads the v4 folder layout:
    test_results/v4/
        _portfolio/portfolio.csv
        EMCTS/<ms>ms/results.csv
        CrossoverBB/<ms>ms/results.csv
        CrossoverExpansion/ratio_<r>/<ms>ms/results.csv
        CrossoverCombo/ratio_<r>/<ms>ms/results.csv

and produces:
    analysis/figures_v4/*.png
    analysis/tables_v4/*.csv

The methodological centerpiece is the PAIRED-BY-SCENARIO comparison vs EMCTS: every config faces the
same 100 scenarios × 50 visits, so (FinalSequenceValue[agent, s, v] − FinalSequenceValue[EMCTS, s, v])
is a real paired sample. That removes the cumulative-board-strength endogeneity that confounded
Phase-3 in v1–v3 and gives proper paired confidence intervals at far lower n than full-game phases.

Usage:
    python analysis/analyze_v4.py [test_results/v4]
"""
import sys
import os
import glob
import re
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from pathlib import Path

# -------- entry / paths -------------------------------------------------------------------------
ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test_results/v4')
FIGS = Path('analysis/figures_v4'); FIGS.mkdir(parents=True, exist_ok=True)
TABLES = Path('analysis/tables_v4'); TABLES.mkdir(parents=True, exist_ok=True)

# -------- styling -------------------------------------------------------------------------------
plt.rcParams.update({'figure.dpi': 120, 'font.size': 10, 'axes.grid': True, 'grid.alpha': 0.3})
COLORS = {
    'EMCTS':              '#555555',
    'CrossoverExpansion': '#1f77b4',
    'CrossoverBB':        '#ff7f0e',
    'CrossoverCombo':     '#2ca02c',
}
RATIO_CMAP = plt.cm.viridis(np.linspace(0, 0.92, 5))    # ratio 2,5,10,15,20

# -------- portfolio manifest --------------------------------------------------------------------
def load_portfolio():
    """Return the portfolio manifest as a DataFrame (one row per scenario)."""
    p = ROOT / '_portfolio' / 'portfolio.csv'
    if not p.exists():
        print(f'WARN: {p} not found; analysis will skip portfolio diagnostics.')
        return pd.DataFrame()
    return pd.read_csv(p)

# -------- result loading ------------------------------------------------------------------------
LEAF_RE = re.compile(
    r'(?P<agent>EMCTS|CrossoverBB|CrossoverExpansion|CrossoverCombo)'
    r'(?:/ratio_(?P<ratio>\d+))?'
    r'/(?P<ms>\d+)ms/results\.csv$'
)

def load_results():
    """Walk the v4 folder layout, read every results.csv, return one combined DataFrame. Each row is one
    bridge-burn observation; per-turn columns are constant across the 20 burns of a (scenario, visit,
    agent, ms) tuple. Uses pathlib.relative_to (robust to trailing separators and absolute paths) and
    WARNs on any discovered results.csv that didn't match the LEAF_RE — so silent data drops are loud."""
    rows = []
    skipped = []
    root_abs = ROOT.resolve()
    for path in sorted(glob.glob(str(ROOT) + '/**/results.csv', recursive=True)):
        try:
            rel = Path(path).resolve().relative_to(root_abs).as_posix()
        except ValueError:
            skipped.append((path, 'not under root')); continue
        m = LEAF_RE.search(rel)
        if not m:
            skipped.append((path, 'regex no-match: ' + rel)); continue
        df = pd.read_csv(path)
        df['AgentGroup'] = m.group('agent')
        df['RatioInPath'] = int(m.group('ratio')) if m.group('ratio') else -1
        df['ThinkingMs'] = int(m.group('ms'))
        rows.append(df)
    if skipped:
        for p, why in skipped: print(f'  WARN: skipped {p} ({why})')
    if not rows:
        raise FileNotFoundError(f'No results.csv files found under {ROOT}. Run RunExperimentV4 first.')
    return pd.concat(rows, ignore_index=True)

def per_turn(df):
    """Collapse the 20 bridge-burn rows to one row per (agent, ratio, ms, scenario, visit) — turn-level
    columns are constant within a turn, so keep the first burn row. We DROP the per-burn columns so
    code that ever calls e.g. turn_df.BestSequenceValue.mean() doesn't silently average burn-1 values."""
    keys = ['AgentGroup', 'RatioInPath', 'ThinkingMs', 'ScenarioId', 'VisitId']
    out = df.drop_duplicates(subset=keys, keep='first').copy()
    return out.drop(columns=['BestSequenceValue', 'FmCallsElapsed', 'BridgeBurningPhaseNumber',
                              'Timestamp', 'RawImprovement', 'PercentageImprovement'],
                    errors='ignore')

def config_label(g, r):
    if g in ('EMCTS', 'CrossoverBB'):
        return g
    return f'{g}@{r}'

# -------- helpers -------------------------------------------------------------------------------
def ci95(series):
    """95% confidence interval half-width for a numeric series, using the t-distribution multiplier
    (degrades to normal-approx for large n). Suitable for the headline n≈5000 cells; if you later slice
    to per-scenario subgroups (small n), consider bootstrap (np.percentile of resampled means)."""
    s = series.dropna().values
    n = s.size
    if n < 2:
        return np.nan
    sem = s.std(ddof=1) / np.sqrt(n)
    try:
        from scipy.stats import t
        return float(t.ppf(0.975, n - 1) * sem)
    except Exception:
        return float(1.96 * sem)

def paired_delta_vs_emcts(turn_df, value_col='FinalSequenceValue'):
    """For each (config, ms), align rows with EMCTS at the same (ScenarioId, VisitId, ThinkingMs) and
    compute the paired delta. Returns long DataFrame with columns:
        AgentGroup, RatioInPath, ThinkingMs, ScenarioId, VisitId, value, emcts_value, delta.

    Defensive: dedup EMCTS by (ms, scenario, visit) BEFORE merging so a partially-written / cross-machine
    duplicate doesn't silently Cartesian-explode every variant row. validate='m:1' asserts the merge is
    many-to-one (each variant matches at most one EMCTS row), failing loudly otherwise."""
    emcts = (turn_df[turn_df.AgentGroup == 'EMCTS']
             [['ThinkingMs', 'ScenarioId', 'VisitId', value_col]]
             .drop_duplicates(subset=['ThinkingMs', 'ScenarioId', 'VisitId'])
             .rename(columns={value_col: 'emcts_value'}))
    out = turn_df.merge(emcts, on=['ThinkingMs', 'ScenarioId', 'VisitId'], how='left', validate='m:1')
    out['delta'] = out[value_col] - out['emcts_value']
    return out

# -------- TABLES --------------------------------------------------------------------------------
def t1_summary(turn_df):
    """Per (config, ms): mean Init, mean Final, within-turn gain, paired Δ vs EMCTS ± 95% CI, n."""
    delta_df = paired_delta_vs_emcts(turn_df)
    rows = []
    for (g, r, ms), grp in delta_df.groupby(['AgentGroup', 'RatioInPath', 'ThinkingMs']):
        cfg = config_label(g, r)
        d = grp['delta'].dropna()
        rows.append(dict(
            Config=cfg, AgentGroup=g, Ratio=r, ThinkingMs=ms, N=len(d),
            InitSeq=grp.InitialSequenceValue.mean(),
            FinalSeq=grp.FinalSequenceValue.mean(),
            WithinTurnGain=grp.FinalSequenceValue.mean() - grp.InitialSequenceValue.mean(),
            DeltaVsEMCTS=d.mean(),
            CI95=ci95(d),
        ))
    t = pd.DataFrame(rows).round(3)
    t = t.sort_values(['ThinkingMs', 'AgentGroup', 'Ratio'])
    t.to_csv(TABLES / 'T1_paired_delta_vs_emcts.csv', index=False)
    return t

def t2_ratio_sweep(turn_df):
    """Per (Expansion|Combo, ratio, ms): paired delta vs EMCTS ± 95% CI. Best ratio per (agent, ms)."""
    delta_df = paired_delta_vs_emcts(turn_df)
    sub = delta_df[delta_df.AgentGroup.isin(['CrossoverExpansion', 'CrossoverCombo'])]
    rows = []
    for (g, r, ms), grp in sub.groupby(['AgentGroup', 'RatioInPath', 'ThinkingMs']):
        d = grp['delta'].dropna()
        rows.append(dict(AgentGroup=g, Ratio=r, ThinkingMs=ms,
                         DeltaVsEMCTS=d.mean(), CI95=ci95(d), N=len(d)))
    t = pd.DataFrame(rows).round(3).sort_values(['AgentGroup', 'ThinkingMs', 'Ratio'])
    t.to_csv(TABLES / 'T2_ratio_sweep_paired.csv', index=False)
    return t

def t3_efficiency(turn_df):
    """Per (config, ms): FmCalls / NodesEvaluated / UniqueSequences (compute-demand vs throughput)."""
    rows = []
    for (g, r, ms), grp in turn_df.groupby(['AgentGroup', 'RatioInPath', 'ThinkingMs']):
        rows.append(dict(Config=config_label(g, r), ThinkingMs=ms,
                         FmCalls=grp.FmCalls.mean(),
                         NodesEvaluated=grp.NodesEvaluated.mean(),
                         UniqueSequences=grp.UniqueSequences.mean(),
                         AvgNodeValue=grp.AvgNodeValue.mean(),
                         N=len(grp)))
    t = pd.DataFrame(rows).round(3).sort_values(['ThinkingMs', 'Config'])
    t.to_csv(TABLES / 'T3_efficiency.csv', index=False)
    return t

def t4_portfolio(portfolio):
    if portfolio.empty:
        return portfolio
    sub = portfolio.groupby('Source').agg(N=('ScenarioId','size'),
        MeanCities=('ActiveTribeNumCities','mean'),
        MeanUnits=('ActiveTribeNumUnits','mean'),
        MeanScore=('ActiveTribeScore','mean'),
        MeanBranching=('BranchingFactor','mean')).round(2).reset_index()
    sub.to_csv(TABLES / 'T4_portfolio.csv', index=False)
    return sub

# -------- FIGURES -------------------------------------------------------------------------------
def fig_headline_paired_delta(turn_df):
    """fig1 — THE headline: per thinking time, paired Δ FinalSequenceValue vs EMCTS for every config,
    with 95% CIs. This is the cleanest comparison the whole v4 design exists to enable."""
    delta_df = paired_delta_vs_emcts(turn_df)
    delta_df = delta_df[delta_df.AgentGroup != 'EMCTS']    # EMCTS-vs-EMCTS is trivially 0
    delta_df['Config'] = delta_df.apply(lambda r: config_label(r.AgentGroup, r.RatioInPath), axis=1)
    msl = sorted(delta_df.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(5.2 * len(msl), 5.5), squeeze=False)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        sub = delta_df[delta_df.ThinkingMs == ms]
        cfgs = (sub.groupby('Config')['delta'].mean().sort_values().index.tolist())
        means = [sub[sub.Config == c]['delta'].mean() for c in cfgs]
        cis = [ci95(sub[sub.Config == c]['delta']) for c in cfgs]
        colors = [COLORS[c.split('@')[0]] for c in cfgs]
        y = np.arange(len(cfgs))
        ax.barh(y, means, xerr=cis, capsize=3, color=colors, edgecolor='black', linewidth=0.4)
        ax.axvline(0, color='black', lw=0.8)
        ax.set_yticks(y); ax.set_yticklabels(cfgs, fontsize=9)
        ax.set_title(f'{ms} ms')
        ax.set_xlabel('FinalSequenceValue paired Δ vs EMCTS\n(per scenario × visit, ±95% CI)')
    fig.suptitle('PAIRED delta vs EMCTS — same scenarios, same initial sequence',
                 fontweight='bold')
    fig.tight_layout()
    fig.savefig(FIGS / 'fig1_paired_delta_vs_emcts.png'); plt.close(fig)

def fig_trajectory_per_config(df_burn):
    """fig2 — within-turn mean BestSequenceValue trajectory across the 20 bridge-burn phases, one line
    per config, one subplot per thinking time. Companion to fig10 from v3 (absolute), but the values
    are now comparable across configs because they share the SAME scenarios and same random init."""
    msl = sorted(df_burn.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(5.5 * len(msl), 4.8), squeeze=False)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        sub = df_burn[df_burn.ThinkingMs == ms]
        for (g, r), grp in sub.groupby(['AgentGroup', 'RatioInPath']):
            cfg = config_label(g, r)
            traj = grp.groupby('BridgeBurningPhaseNumber')['BestSequenceValue'].mean()
            ls = '-' if r in (-1, 5) else '--'
            ax.plot(traj.index, traj.values, color=COLORS[g], ls=ls, alpha=0.95 if r in (-1, 5) else 0.55,
                    lw=1.8 if r in (-1, 5) else 1.0, label=cfg)
        ax.set_title(f'{ms} ms'); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('mean BestSequenceValue')
    axes[-1].legend(loc='center left', bbox_to_anchor=(1.02, 0.5), fontsize=7, frameon=False)
    fig.suptitle('Within-turn trajectory — mean BestSequenceValue per bridge-burn phase',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig2_trajectory_per_config.png', bbox_inches='tight'); plt.close(fig)

def fig_paired_trajectory_vs_emcts(df_burn):
    """fig3 — paired-by-scenario Δ trajectory: at each burn phase, mean Δ vs EMCTS over (scenario,
    visit). Shows WHEN within the turn each variant pulls ahead/behind EMCTS."""
    emcts = (df_burn[df_burn.AgentGroup == 'EMCTS']
             .groupby(['ThinkingMs', 'ScenarioId', 'VisitId', 'BridgeBurningPhaseNumber'])
             ['BestSequenceValue'].mean().reset_index().rename(columns={'BestSequenceValue': 'emcts_value'}))
    merged = df_burn.merge(emcts, on=['ThinkingMs', 'ScenarioId', 'VisitId', 'BridgeBurningPhaseNumber'])
    merged['delta'] = merged.BestSequenceValue - merged.emcts_value
    msl = sorted(merged.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(5.5 * len(msl), 4.8), squeeze=False, sharey=True)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        sub = merged[(merged.ThinkingMs == ms) & (merged.AgentGroup != 'EMCTS')]
        for (g, r), grp in sub.groupby(['AgentGroup', 'RatioInPath']):
            cfg = config_label(g, r)
            traj = grp.groupby('BridgeBurningPhaseNumber')['delta'].mean()
            ls = '-' if r in (-1, 5) else '--'
            ax.plot(traj.index, traj.values, color=COLORS[g], ls=ls, alpha=0.95 if r in (-1, 5) else 0.55,
                    lw=1.8 if r in (-1, 5) else 1.0, label=cfg)
        ax.axhline(0, color='black', lw=0.8)
        ax.set_title(f'{ms} ms'); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('mean paired Δ vs EMCTS in BestSequenceValue')
    axes[-1].legend(loc='center left', bbox_to_anchor=(1.02, 0.5), fontsize=7, frameon=False)
    fig.suptitle('Paired-by-scenario Δ trajectory vs EMCTS', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig3_paired_trajectory_vs_emcts.png', bbox_inches='tight'); plt.close(fig)

def fig_ratio_sweep_paired(turn_df):
    """fig4 — paired Δ vs EMCTS as a function of crossover ratio (Expansion + Combo), per thinking time.
    Surfaces the per-budget ratio sweet spot in a single panel."""
    delta_df = paired_delta_vs_emcts(turn_df)
    sub = delta_df[delta_df.AgentGroup.isin(['CrossoverExpansion', 'CrossoverCombo'])]
    msl = sorted(sub.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(5.5 * len(msl), 4.5), squeeze=False, sharey=True)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        for g in ['CrossoverExpansion', 'CrossoverCombo']:
            grp = sub[(sub.ThinkingMs == ms) & (sub.AgentGroup == g)]
            agg = grp.groupby('RatioInPath')['delta'].agg(['mean', ci95]).reset_index()
            ax.errorbar(agg['RatioInPath'], agg['mean'], yerr=agg['ci95'], marker='o',
                        color=COLORS[g], capsize=3, label=g)
        ax.axhline(0, color='black', lw=0.8)
        ax.set_title(f'{ms} ms'); ax.set_xlabel('mutationExpansionRatio (higher = less crossover)')
    axes[0].set_ylabel('paired Δ vs EMCTS, ±95% CI')
    axes[0].legend()
    fig.suptitle('Ratio sweep (paired Δ vs EMCTS) — Expansion + Combo, every ratio at every ms',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig4_ratio_sweep_paired.png'); plt.close(fig)

def fig_per_scenario_heatmap(turn_df):
    """fig5 — scenario × config heatmap of mean FinalSequenceValue per scenario. Shows scenario
    heterogeneity AND which configs dominate which scenarios. Per thinking time."""
    msl = sorted(turn_df.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(7 * len(msl), 8), squeeze=False)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        sub = turn_df[turn_df.ThinkingMs == ms].copy()
        sub['Config'] = sub.apply(lambda r: config_label(r.AgentGroup, r.RatioInPath), axis=1)
        pivot = sub.pivot_table(index='ScenarioId', columns='Config', values='FinalSequenceValue', aggfunc='mean')
        # Center on EMCTS column for readability, then DROP EMCTS (it would be all zeros and waste
        # column space) and compute the colorscale on the variant columns only — including the
        # zero-EMCTS column dragged percentile-95 down and visually compressed the heatmap.
        if 'EMCTS' in pivot.columns:
            pivot = pivot.sub(pivot['EMCTS'], axis=0).drop(columns=['EMCTS'])
            label = 'FinalSequenceValue − EMCTS (same scenario)'
        else:
            label = 'FinalSequenceValue'
        if pivot.size and np.isfinite(pivot.values).any():
            q = float(np.nanpercentile(np.abs(pivot.values), 95))
        else:
            q = 1.0
        im = ax.imshow(pivot.values, aspect='auto', cmap='RdBu_r', vmin=-q, vmax=q)
        ax.set_title(f'{ms} ms')
        ax.set_xticks(range(len(pivot.columns)))
        ax.set_xticklabels(pivot.columns, rotation=60, ha='right', fontsize=7)
        ax.set_yticks([])
        ax.set_xlabel('config')
        fig.colorbar(im, ax=ax, fraction=0.04, pad=0.02).set_label(label, fontsize=8)
    axes[0].set_ylabel('scenario (100)')
    fig.suptitle('Per-scenario heatmap: each config relative to EMCTS on the same scenario',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig5_per_scenario_heatmap.png'); plt.close(fig)

def fig_burn_crossover_benefit(df_burn):
    """fig6 — for the burn-crossover agents (CrossoverBB, CrossoverCombo), the per-burn RawImprovement
    distribution + the share of burns improved / unchanged / worsened. v3 fig3 analog."""
    # Only keep rows where a crossover event ACTUALLY fired (the runner writes NaN-empty when no event).
    # If a downstream change ever reverts to writing 0.0 for non-events, this filter still works because
    # we additionally require BridgeBurningPhaseNumber>=1 — but the canonical signal is RawImprovement.notna.
    sub = df_burn[df_burn.AgentGroup.isin(['CrossoverBB', 'CrossoverCombo']) & df_burn.RawImprovement.notna()]
    if sub.empty:
        return
    msl = sorted(sub.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(5 * len(msl), 4.5), squeeze=False, sharey=True)
    axes = axes[0]
    cats = ['improved (>0)', 'unchanged (=0)', 'worsened (<0)']
    for ax, ms in zip(axes, msl):
        ms_sub = sub[sub.ThinkingMs == ms]
        configs = sorted(set([config_label(g, r) for g, r in zip(ms_sub.AgentGroup, ms_sub.RatioInPath)]))
        x = np.arange(len(cats)); width = 0.8 / max(1, len(configs))
        for i, cfg in enumerate(configs):
            g = cfg.split('@')[0]
            r = -1 if '@' not in cfg else int(cfg.split('@')[1])
            d = ms_sub[(ms_sub.AgentGroup == g) & (ms_sub.RatioInPath == r)]['RawImprovement']
            frac = [100 * (d > 0).mean(), 100 * (d == 0).mean(), 100 * (d < 0).mean()]
            # alpha previously could exceed 1.0; clamp.
            alpha = min(1.0, 0.55 + 0.45 * (i / max(1, len(configs) - 1)))
            ax.bar(x + (i - (len(configs)-1)/2) * width, frac, width, label=cfg,
                   color=COLORS[g], alpha=alpha)
        ax.set_xticks(x); ax.set_xticklabels(cats); ax.set_title(f'{ms} ms')
    axes[0].set_ylabel('% of CROSSOVER-FIRED burns')
    axes[-1].legend(loc='center left', bbox_to_anchor=(1.02, 0.5), fontsize=8, frameon=False)
    fig.suptitle('Burn-time crossover outcome — fraction of CROSSOVER-FIRED burns improved / unchanged / worsened',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig6_burn_crossover_benefit.png', bbox_inches='tight'); plt.close(fig)

def fig_efficiency(turn_df):
    """fig7 — search effort under equal wall-clock budget: FmCalls / NodesEvaluated / UniqueSequences
    per config per ms. Per-agent demand for compute and exploration breadth."""
    metrics = [('FmCalls', 'FmCalls/turn'),
               ('NodesEvaluated', 'NodesEvaluated/turn'),
               ('UniqueSequences', 'UniqueSequences/turn')]
    msl = sorted(turn_df.ThinkingMs.unique())
    fig, axes = plt.subplots(len(metrics), len(msl), figsize=(5.5 * len(msl), 3.6 * len(metrics)),
                             squeeze=False, sharex=False)
    for j, ms in enumerate(msl):
        sub = turn_df[turn_df.ThinkingMs == ms].copy()
        sub['Config'] = sub.apply(lambda r: config_label(r.AgentGroup, r.RatioInPath), axis=1)
        cfgs = sorted(sub.Config.unique())
        x = np.arange(len(cfgs))
        for i, (col, lab) in enumerate(metrics):
            ax = axes[i][j]
            means = [sub[sub.Config == c][col].mean() for c in cfgs]
            colors = [COLORS[c.split('@')[0]] for c in cfgs]
            ax.bar(x, means, color=colors)
            ax.set_xticks(x); ax.set_xticklabels(cfgs, rotation=60, ha='right', fontsize=7)
            if j == 0: ax.set_ylabel(lab)
            if i == 0: ax.set_title(f'{ms} ms')
    fig.suptitle('Search effort under equal wall-clock budget', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig7_efficiency.png'); plt.close(fig)

def fig_value_distribution(turn_df):
    """fig8 — distribution of FinalSequenceValue per config per ms. Median + IQR (boxplots) show how
    each config's value distribution compares vs EMCTS's on the SAME scenarios."""
    msl = sorted(turn_df.ThinkingMs.unique())
    fig, axes = plt.subplots(1, len(msl), figsize=(6.5 * len(msl), 5), squeeze=False)
    axes = axes[0]
    for ax, ms in zip(axes, msl):
        sub = turn_df[turn_df.ThinkingMs == ms].copy()
        sub['Config'] = sub.apply(lambda r: config_label(r.AgentGroup, r.RatioInPath), axis=1)
        cfgs = sorted(sub.Config.unique())
        data = [sub[sub.Config == c]['FinalSequenceValue'].dropna().values for c in cfgs]
        bp = ax.boxplot(data, showfliers=True, flierprops={'marker': '.', 'alpha': 0.25},
                        patch_artist=True)
        for patch, c in zip(bp['boxes'], cfgs):
            patch.set_facecolor(COLORS[c.split('@')[0]]); patch.set_alpha(0.6)
        ax.set_xticklabels(cfgs, rotation=60, ha='right', fontsize=7)
        ax.set_title(f'{ms} ms'); ax.set_ylabel('FinalSequenceValue')
    fig.suptitle('FinalSequenceValue distribution per config', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig8_value_distribution.png'); plt.close(fig)

def fig_portfolio(portfolio):
    """fig9 — portfolio diagnostics from the manifest."""
    if portfolio.empty:
        return
    fig, axes = plt.subplots(1, 4, figsize=(18, 4))
    for col, ax, title in zip(['ActiveTribeNumCities', 'ActiveTribeNumUnits', 'ActiveTribeScore',
                               'BranchingFactor'],
                              axes,
                              ['Cities', 'Units', 'Score', 'Branching factor']):
        for src, sub in portfolio.groupby('Source'):
            ax.hist(sub[col], bins=20, alpha=0.6, label=src)
        ax.set_title(title); ax.set_xlabel(col); ax.legend(fontsize=8)
    fig.suptitle('Portfolio diagnostics — composition of the 100 scenarios', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'fig9_portfolio.png'); plt.close(fig)

# -------- main ---------------------------------------------------------------------------------
def main():
    print(f'analyse v4 from {ROOT}')
    portfolio = load_portfolio()
    raw = load_results()
    turn_df = per_turn(raw)
    print(f'  loaded {len(raw):,} burn-rows from {len(turn_df):,} (config, scenario, visit) tuples')
    print(f'  configs:        {sorted(turn_df.AgentGroup.unique())}')
    print(f'  thinking times: {sorted(turn_df.ThinkingMs.unique())} ms')
    print(f'  scenarios:      {turn_df.ScenarioId.nunique()}')
    print(f'  visits:         {turn_df.VisitId.nunique()}')
    # Defensive: every (agent, ratio, ms, scenario, visit) tuple should appear exactly once after per_turn.
    counts = turn_df.groupby(['AgentGroup', 'RatioInPath', 'ThinkingMs',
                              'ScenarioId', 'VisitId']).size()
    if (counts > 1).any():
        print(f'  WARN: {(counts > 1).sum()} (config, scenario, visit) tuples have duplicate rows '
              f'after per_turn — likely a partially-written or merged CSV.')
    # Every config should have the SAME number of (scenario, visit) rows per ms; flag if not.
    per_cfg = turn_df.groupby(['AgentGroup', 'RatioInPath', 'ThinkingMs']).size()
    print(f'  rows per (config, ms): min={per_cfg.min():,} max={per_cfg.max():,} '
          f'(matched ⇒ no missing CSVs); details by ms:')
    print(per_cfg.groupby('ThinkingMs').agg(['min', 'max', 'count']).to_string())

    print('\n== TABLES ==')
    print('T1: paired Δ vs EMCTS'); print(t1_summary(turn_df).to_string(index=False)); print()
    print('T2: ratio sweep (paired)'); print(t2_ratio_sweep(turn_df).to_string(index=False)); print()
    print('T3: efficiency'); print(t3_efficiency(turn_df).to_string(index=False)); print()
    if not portfolio.empty:
        print('T4: portfolio'); print(t4_portfolio(portfolio).to_string(index=False)); print()

    print('\n== FIGURES ==')
    fig_headline_paired_delta(turn_df)
    fig_trajectory_per_config(raw)
    fig_paired_trajectory_vs_emcts(raw)
    fig_ratio_sweep_paired(turn_df)
    fig_per_scenario_heatmap(turn_df)
    fig_burn_crossover_benefit(raw)
    fig_efficiency(turn_df)
    fig_value_distribution(turn_df)
    fig_portfolio(portfolio)
    print(f'\nDone. tables -> {TABLES}/  figures -> {FIGS}/')


if __name__ == '__main__':
    main()
