"""
Analysis + figures for the EMCTS crossover experiment.

Compares vanilla EMCTS (baseline) against the two crossover modifications:
  - CrossoverExpansion : crossover used in the MCTS expansion step (ratio = 1 crossover per
                         `mutationExpansionRatio` expansions; higher ratio = LESS crossover).
  - CrossoverBB        : crossover used at the bridge-burning step (root selection).
  - CrossoverCombo     : both modifications together.

Reads test_results/full/{phase2,phase3}, writes:
  analysis/tables/*.csv   (summary tables)
  analysis/figures/*.png  (figures)

Run:  python analysis/analyze.py
"""
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from pathlib import Path

BASE   = Path('test_results/full')
FIGS   = Path('analysis/figures'); FIGS.mkdir(parents=True, exist_ok=True)
TABLES = Path('analysis/tables');  TABLES.mkdir(parents=True, exist_ok=True)
LEVELS = ['500ms', '1000ms', '5000ms']

# Consistent colors: EMCTS baseline = grey/black, the two mods + combo = distinct hues.
C = {'EMCTS':'#555555', 'CrossoverExpansion':'#1f77b4', 'CrossoverBB':'#ff7f0e',
     'CrossoverCombo':'#2ca02c', 'MCTS':'#9467bd', 'OEP':'#8c564b'}
TURN_COLS = ['ScoreAtTurnStart','InitialSequenceValue','FinalSequenceValue','AvgNodeValue',
             'FmCalls','NodesEvaluated','UniqueSequences','FinalScore']
plt.rcParams.update({'figure.dpi':120, 'font.size':10, 'axes.grid':True, 'grid.alpha':0.3})


# ----------------------------------------------------------------------------- loaders
def per_turn(df):
    """One row per (agent, game, turn): turn-level columns are constant within a turn,
    so keep the first bridge-burn row."""
    return df.drop_duplicates(subset=['AgentName','GameId','TurnNumber'], keep='first').copy()

def load_phase3_turns(level):
    """Per-turn table for all four family agents at a level, with a clean 'Config' label.
    Expansion comes from the ratio CSV (filtered to ratio 5); Combo from the family CSV."""
    std = per_turn(pd.read_csv(BASE/f'phase3/Phase3_standardBB_{level}.csv'))
    cbb = per_turn(pd.read_csv(BASE/f'phase3/Phase3_crossoverBB_{level}.csv'))
    rt  = pd.read_csv(BASE/f'phase3/Phase3_ratio_turns_{level}.csv')   # already per-turn
    cbb['RatioNum'] = pd.to_numeric(cbb['Ratio'], errors='coerce')
    rt['RatioNum']  = pd.to_numeric(rt['Ratio'],  errors='coerce')
    emcts = std[std.AgentName=='EMCTS'].copy()
    exp5  = rt[(rt.AgentName=='CrossoverExpansion') & (rt.RatioNum==5)].copy()
    bb    = cbb[cbb.AgentName=='CrossoverBB'].copy()
    combo5= cbb[(cbb.AgentName=='CrossoverCombo') & (cbb.RatioNum==5)].copy()
    for d,name in [(emcts,'EMCTS'),(exp5,'CrossoverExpansion'),(bb,'CrossoverBB'),(combo5,'CrossoverCombo')]:
        d['Config'] = name
    cols = ['Config','GameId','TurnNumber']+TURN_COLS
    out = pd.concat([emcts[cols],exp5[cols],bb[cols],combo5[cols]], ignore_index=True)
    out['Level'] = level
    return out

def load_ratio_sweep(level):
    rt = pd.read_csv(BASE/f'phase3/Phase3_ratio_turns_{level}.csv')
    rt = rt[rt.AgentName=='CrossoverExpansion'].copy()
    rt['Ratio'] = pd.to_numeric(rt['Ratio'], errors='coerce').astype(int)
    rt['Level'] = level
    return rt

def load_phase2(level):
    p2 = pd.read_csv(BASE/f'phase2/Phase2_games_{level}.csv')
    exp = p2[~p2.AgentName.isin(['MCTS','OEP','EMCTS'])].copy()
    exp['RatioNum'] = pd.to_numeric(exp['Ratio'], errors='coerce')
    exp['Config'] = exp.apply(lambda r: r.AgentName + ('' if pd.isna(r.RatioNum) else f'@r{int(r.RatioNum)}'), axis=1)
    exp['Base']   = exp['Matchup'].str.split('_vs_').str[-1]
    exp['Level']  = level
    return exp

def best_ratios():
    """Parse BEST_RATIO_<level>=<n> from phase3_best_ratio.txt (per-level best CrossoverExpansion ratio)."""
    out = {}
    for line in (BASE/'phase3/phase3_best_ratio.txt').read_text().splitlines():
        line = line.strip()
        if line.startswith('BEST_RATIO_'):
            k, v = line.split('='); out[k.replace('BEST_RATIO_','')] = int(v)
    return out

ALL_TURNS = pd.concat([load_phase3_turns(l) for l in LEVELS], ignore_index=True)
ORDER = ['EMCTS','CrossoverExpansion','CrossoverBB','CrossoverCombo']
BEST_RATIO = best_ratios()   # {'500ms':10, '1000ms':5, '5000ms':5}


# ----------------------------------------------------------------------------- tables
def game_level(df, valcol):
    """Collapse to one value per game (mean over the game's turns), then mean +/- SE over games."""
    g = df.groupby(['Level','Config','GameId'])[valcol].mean().reset_index()
    s = g.groupby(['Level','Config'])[valcol].agg(['mean','std','count'])
    s['se'] = s['std']/np.sqrt(s['count'])
    return s

def t1_family_summary():
    rows=[]
    for lv in LEVELS:
        d = ALL_TURNS[ALL_TURNS.Level==lv]
        for cfg in ORDER:
            x = d[d.Config==cfg]
            fin,ini = x.FinalSequenceValue.mean(), x.InitialSequenceValue.mean()
            rows.append(dict(Level=lv, Config=cfg, Turns=len(x),
                FinalSeq=fin, InitSeq=ini, PctImprWithinTurn=100*(fin-ini)/abs(ini),
                AvgNodeValue=x.AvgNodeValue.mean(), FmCalls=x.FmCalls.mean(),
                NodesEvaluated=x.NodesEvaluated.mean(), UniqueSequences=x.UniqueSequences.mean(),
                UniqSeq_per_kFM=1000*x.UniqueSequences.mean()/x.FmCalls.mean(),
                Nodes_per_kFM=1000*x.NodesEvaluated.mean()/x.FmCalls.mean(),
                UniqSeq_per_Node=x.UniqueSequences.mean()/x.NodesEvaluated.mean(),
                FinalScore=x.FinalScore.mean()))
    t = pd.DataFrame(rows).round(3)
    t.to_csv(TABLES/'T1_phase3_family_summary.csv', index=False)
    return t

def t2_ratio_sweep():
    rows=[]
    for lv in LEVELS:
        g = load_ratio_sweep(lv)
        for r,x in g.groupby('Ratio'):
            rows.append(dict(Level=lv, Ratio=r, FinalSeq=x.FinalSequenceValue.mean(),
                AvgNodeValue=x.AvgNodeValue.mean(), UniqueSequences=x.UniqueSequences.mean(),
                NodesEvaluated=x.NodesEvaluated.mean(), FmCalls=x.FmCalls.mean(),
                FinalScore=x.FinalScore.mean()))
    t = pd.DataFrame(rows).round(3)
    t.to_csv(TABLES/'T2_phase3_ratio_sweep.csv', index=False)
    return t

def t3_winrates():
    frames=[]
    for lv in ['500ms','1000ms']:
        exp = load_phase2(lv)
        wr = exp.groupby(['Config','Base'])['Won'].mean().reset_index()
        wr['Level']=lv; frames.append(wr)
    t = pd.concat(frames, ignore_index=True)
    piv = t.pivot_table(index=['Level','Config'], columns='Base', values='Won').round(3)
    piv.to_csv(TABLES/'T3_phase2_winrates.csv')
    return piv

def t4_improvement_vs_emcts():
    """% change of each modification vs EMCTS on FinalScore (in-game) and FinalSeq (search),
    plus the head-to-head Phase-2 win rate vs EMCTS."""
    fs = game_level(ALL_TURNS,'FinalScore')['mean'].unstack(0)   # rows=Config, cols=Level
    fq = game_level(ALL_TURNS,'FinalSequenceValue')['mean'].unstack(0)
    rows=[]
    for lv in LEVELS:
        base_fs, base_fq = fs.loc['EMCTS',lv], fq.loc['EMCTS',lv]
        for cfg in ['CrossoverExpansion','CrossoverBB','CrossoverCombo']:
            rows.append(dict(Level=lv, Config=cfg,
                FinalScore_pct_vs_EMCTS=100*(fs.loc[cfg,lv]-base_fs)/base_fs,
                FinalSeq_pct_vs_EMCTS=100*(fq.loc[cfg,lv]-base_fq)/base_fq))
    t = pd.DataFrame(rows)
    # attach Phase-2 win rate vs EMCTS (ratio-5 configs where available)
    wr=[]
    for lv in ['500ms','1000ms']:
        exp = load_phase2(lv)
        e = exp[exp.Base=='EMCTS'].groupby('Config')['Won'].mean()
        for cfg_full,val in e.items(): wr.append((lv,cfg_full,val))
    wrdf = pd.DataFrame(wr, columns=['Level','ConfigFull','WinRate_vs_EMCTS'])
    t = t.round(3)
    t.to_csv(TABLES/'T4_improvement_vs_emcts.csv', index=False)
    wrdf.round(3).to_csv(TABLES/'T4b_phase2_winrate_vs_emcts.csv', index=False)
    return t, wrdf


# ----------------------------------------------------------------------------- figures
def fig_ratio_sweep():
    """FinalSequenceValue and UniqueSequences vs crossover-expansion ratio, per budget level.
    Recall: higher ratio = LESS crossover (more mutation)."""
    fig, axes = plt.subplots(1, 2, figsize=(12,4.5))
    for lv in LEVELS:
        g = load_ratio_sweep(lv).groupby('Ratio').agg(
            FinalSeq=('FinalSequenceValue','mean'), Uniq=('UniqueSequences','mean')).reset_index()
        axes[0].plot(g.Ratio, g.FinalSeq, marker='o', label=lv)
        axes[1].plot(g.Ratio, g.Uniq,     marker='o', label=lv)
    for ax in axes: ax.set_xlabel('mutationExpansionRatio  (higher = less crossover)'); ax.legend(title='budget')
    axes[0].set_ylabel('mean FinalSequenceValue'); axes[0].set_title('Search effectiveness vs crossover rate')
    axes[1].set_ylabel('mean UniqueSequences/turn'); axes[1].set_title('Sequence diversity vs crossover rate')
    axes[0].invert_xaxis(); axes[1].invert_xaxis()  # left = more crossover
    fig.suptitle('CrossoverExpansion ratio sweep (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig1_ratio_sweep.png'); plt.close(fig)

def burn_pct_trajectory(df):
    """Mean % gain in BestSequenceValue over each turn's burn-1 value, per bridge-burn phase.
    Normalised per turn (divide by that turn's burn-1 value) so all turns are comparable."""
    piv = df.pivot_table(index=['GameId','TurnNumber'], columns='BridgeBurningPhaseNumber',
                         values='BestSequenceValue')
    if 1 not in piv.columns: return None
    base = piv[1]
    piv = piv[base > 0]; base = base[base > 0]
    return piv.div(base, axis=0).sub(1).mul(100).mean()

def family_frames(lv):
    """The four family trajectories, with CrossoverExpansion AND CrossoverCombo both at the standard
    ratio 5 — so the only difference between them is the bridge-burn-crossover mechanism (the ratio is
    held fixed, not mixed in). Returns [(label, color, burn_rows_df), ...]."""
    std = pd.read_csv(BASE/f'phase3/Phase3_standardBB_{lv}.csv')
    cbb = pd.read_csv(BASE/f'phase3/Phase3_crossoverBB_{lv}.csv')
    rtj = pd.read_csv(BASE/f'phase3/Phase3_ratio_trajectory_{lv}.csv')
    cbb['RatioNum'] = pd.to_numeric(cbb['Ratio'], errors='coerce')
    rtj['RatioNum'] = pd.to_numeric(rtj['Ratio'], errors='coerce')
    return [
        ('EMCTS', C['EMCTS'], std[std.AgentName=='EMCTS']),
        ('CrossoverExpansion@5', C['CrossoverExpansion'], rtj[(rtj.AgentName=='CrossoverExpansion') & (rtj.RatioNum==5)]),
        ('CrossoverBB', C['CrossoverBB'], cbb[cbb.AgentName=='CrossoverBB']),
        ('CrossoverCombo@5', C['CrossoverCombo'], cbb[(cbb.AgentName=='CrossoverCombo') & (cbb.RatioNum==5)]),
    ]

def fig_within_turn_trajectory():
    """Within-turn bridge-burn trajectory per family agent (Expansion and Combo both at ratio 5, so
    only the BB mechanism differs). Mean % gain of BestSequenceValue over its burn-1 value vs phase."""
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5), sharey=True)
    for ax,lv in zip(axes,LEVELS):
        for nm,col,d in family_frames(lv):
            m = burn_pct_trajectory(d)
            if m is not None:
                ax.plot(m.index, m.values, marker='.', color=col, label=nm)
        ax.set_title(lv); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('% gain in BestSequenceValue\nover burn-1 value (mean over turns)')
    axes[0].legend(fontsize=8)
    fig.suptitle('Within-turn search progress across the 20 bridge burns (Phase 3, ratio 5)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig2_within_turn_trajectory.png'); plt.close(fig)

def fig_family_value_trajectory():
    """Absolute companion to fig 2 (the same modification fig 9 is to fig 8): mean ABSOLUTE
    BestSequenceValue per family agent (Expansion/Combo at ratio 5) vs bridge-burn phase."""
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5))   # not sharey: per-level value ranges differ
    for ax,lv in zip(axes,LEVELS):
        for nm,col,d in family_frames(lv):
            m = burn_abs_trajectory(d)
            ax.plot(m.index, m.values, marker='.', color=col, label=nm)
        ax.set_title(lv); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('mean BestSequenceValue\n(absolute, averaged over all turns)')
    axes[0].legend(fontsize=8)
    fig.suptitle('Overall sequence value across the 20 bridge burns (Phase 3, ratio 5)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig10_family_value_trajectory.png'); plt.close(fig)

def fig_expansion_ratio_trajectory():
    """Within-turn bridge-burn trajectory of CrossoverExpansion alone, one line per tested ratio.
    Higher ratio = less crossover. Shows whether the crossover rate changes search-progress shape."""
    ratios = [2, 5, 10, 15, 20]
    cmap = plt.cm.viridis(np.linspace(0, 0.92, len(ratios)))
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5), sharey=True)
    for ax,lv in zip(axes,LEVELS):
        rtj = pd.read_csv(BASE/f'phase3/Phase3_ratio_trajectory_{lv}.csv')
        rtj['RatioNum'] = pd.to_numeric(rtj['Ratio'], errors='coerce')
        for r,col in zip(ratios, cmap):
            m = burn_pct_trajectory(rtj[(rtj.AgentName=='CrossoverExpansion') & (rtj.RatioNum==r)])
            if m is not None:
                lab = f'ratio {r}' + (' (best)' if r==BEST_RATIO[lv] else '')
                ax.plot(m.index, m.values, marker='.', color=col,
                        lw=2.4 if r==BEST_RATIO[lv] else 1.3, label=lab)
        ax.set_title(lv); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('% gain in BestSequenceValue\nover burn-1 value (mean over turns)')
    axes[0].legend(title='mutationExpansionRatio\n(higher = less crossover)', fontsize=8)
    fig.suptitle('CrossoverExpansion within-turn trajectory by crossover ratio (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig8_expansion_ratio_trajectory.png'); plt.close(fig)

def burn_abs_trajectory(df):
    """Mean ABSOLUTE BestSequenceValue per bridge-burn phase (averaged over all turns & games).
    Un-normalised, so it reflects the overall sequence value each ratio actually reaches."""
    return df.pivot_table(index=['GameId','TurnNumber'], columns='BridgeBurningPhaseNumber',
                          values='BestSequenceValue').mean()

def fig_expansion_ratio_value():
    """Like fig 8 but reflecting the OVERALL (absolute) sequence value, not % gain. The ratios
    separate into bands here (the cumulative-board-strength signal that normalisation divides out);
    the within-turn rise across burns is the small slope on each band."""
    ratios = [2, 5, 10, 15, 20]
    cmap = plt.cm.viridis(np.linspace(0, 0.92, len(ratios)))
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5))   # not sharey: per-level value ranges differ
    for ax,lv in zip(axes,LEVELS):
        rtj = pd.read_csv(BASE/f'phase3/Phase3_ratio_trajectory_{lv}.csv')
        rtj['RatioNum'] = pd.to_numeric(rtj['Ratio'], errors='coerce')
        for r,col in zip(ratios, cmap):
            m = burn_abs_trajectory(rtj[(rtj.AgentName=='CrossoverExpansion') & (rtj.RatioNum==r)])
            lab = f'ratio {r}' + (' (best)' if r==BEST_RATIO[lv] else '')
            ax.plot(m.index, m.values, marker='.', color=col,
                    lw=2.4 if r==BEST_RATIO[lv] else 1.3, label=lab)
        ax.set_title(lv); ax.set_xlabel('bridge-burn phase (1..20)')
    axes[0].set_ylabel('mean BestSequenceValue\n(absolute, averaged over all turns)')
    axes[0].legend(title='mutationExpansionRatio\n(higher = less crossover)', fontsize=8)
    fig.suptitle('CrossoverExpansion overall sequence value by crossover ratio (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig9_expansion_ratio_value.png'); plt.close(fig)

def fig_burn_crossover_benefit():
    """Did the burn-time crossover beat the incumbent root? Fraction of burns improved /
    unchanged / worsened, and mean % improvement, for the two burn-crossover agents."""
    fig, axes = plt.subplots(1, 2, figsize=(13,4.5))
    cats = ['improved (>0)','unchanged (=0)','worsened (<0)']
    width=0.13; xb=np.arange(len(cats))
    series=[]
    for lv in LEVELS:
        cbb = pd.read_csv(BASE/f'phase3/Phase3_crossoverBB_{lv}.csv')
        cbb['RatioNum']=pd.to_numeric(cbb['Ratio'],errors='coerce')
        for ag in ['CrossoverBB','CrossoverCombo']:
            d = cbb[cbb.AgentName==ag]
            if ag=='CrossoverCombo': d=d[d.RatioNum==5]
            raw=d['RawImprovement']
            frac=[100*(raw>0).mean(),100*(raw==0).mean(),100*(raw<0).mean()]
            series.append((f'{ag.replace("Crossover","")}-{lv}', frac, raw.mean(), ag, lv))
    for i,(lab,frac,_,ag,lv) in enumerate(series):
        axes[0].bar(xb+(i-len(series)/2)*width, frac, width, label=lab,
                    color=C[ag], alpha=0.4+0.2*LEVELS.index(lv))
    axes[0].set_xticks(xb); axes[0].set_xticklabels(cats); axes[0].set_ylabel('% of bridge burns')
    axes[0].set_title('Outcome of the burn-time crossover'); axes[0].legend(fontsize=7, ncol=2)
    # mean % improvement per burn phase
    for lv in LEVELS:
        cbb = pd.read_csv(BASE/f'phase3/Phase3_crossoverBB_{lv}.csv')
        cbb['RatioNum']=pd.to_numeric(cbb['Ratio'],errors='coerce')
        for ag,ls in [('CrossoverBB','-'),('CrossoverCombo','--')]:
            d = cbb[cbb.AgentName==ag]
            if ag=='CrossoverCombo': d=d[d.RatioNum==5]
            m = d.groupby('BridgeBurningPhaseNumber')['PercentageImprovement'].mean()
            axes[1].plot(m.index, m.values, ls, color=C[ag], alpha=0.5+0.16*LEVELS.index(lv),
                         label=f'{ag.replace("Crossover","")}-{lv}')
    axes[1].axhline(0, color='k', lw=0.8); axes[1].set_xlabel('bridge-burn phase')
    axes[1].set_ylabel('mean % improvement from crossover'); axes[1].set_title('Burn-time crossover benefit vs phase')
    axes[1].legend(fontsize=7, ncol=2)
    fig.suptitle('Crossover at the bridge-burning step rarely improves the root (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig3_burn_crossover_benefit.png'); plt.close(fig)

def fig_winrates():
    """Phase-2 win rate of each modification vs each baseline, per level. The EMCTS column is
    the key 'did the modification beat vanilla EMCTS' test (0.5 = tie; n=100, SE~0.05)."""
    bases=['EMCTS','OEP','MCTS']
    fig, axes = plt.subplots(1, 2, figsize=(13,4.8), sharey=True)
    for ax,lv in zip(axes,['500ms','1000ms']):
        exp=load_phase2(lv)
        wr = exp.groupby(['Config','Base'])['Won'].mean().unstack('Base')
        cfgs=list(wr.index); x=np.arange(len(cfgs)); w=0.25
        for j,b in enumerate(bases):
            ax.bar(x+(j-1)*w, wr[b].values, w, label=f'vs {b}', color=C[b])
        ax.axhline(0.5, color='k', lw=0.8, ls=':')
        ax.set_xticks(x); ax.set_xticklabels(cfgs, rotation=30, ha='right', fontsize=8)
        ax.set_title(lv); ax.set_ylim(0,1.05)
    axes[0].set_ylabel('win rate (experimental agent)'); axes[0].legend()
    fig.suptitle('Phase 2 win rates: crossover modifications vs baselines (CAPITALS, 100 games)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig4_winrates.png'); plt.close(fig)

def fig_value_vs_score():
    """Heuristic search value vs in-game score (Phase 3, per game). Tests how well the SIMPLE
    heuristic's value tracks realised score, and where each agent sits."""
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5), sharex=False)
    for ax,lv in zip(axes,LEVELS):
        d = ALL_TURNS[ALL_TURNS.Level==lv]
        g = d.groupby(['Config','GameId']).agg(val=('FinalSequenceValue','mean'),
                                               score=('FinalScore','first')).reset_index()
        for cfg in ORDER:
            x=g[g.Config==cfg]
            ax.scatter(x.val, x.score, s=14, alpha=0.5, color=C[cfg], label=cfg)
        # pooled correlation
        r=np.corrcoef(g.val,g.score)[0,1]
        ax.set_title(f'{lv}  (pooled r={r:.2f})'); ax.set_xlabel('mean FinalSequenceValue (heuristic)')
    axes[0].set_ylabel('FinalScore (in-game)'); axes[0].legend(fontsize=8)
    fig.suptitle('Heuristic search value vs realised in-game score (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig5_value_vs_score.png'); plt.close(fig)

def fig_efficiency():
    """Search effort/efficiency at fixed compute budget, per agent per level."""
    metrics=[('UniqueSequences','mean UniqueSequences/turn'),
             ('NodesEvaluated','mean NodesEvaluated/turn'),
             ('FmCalls','mean FmCalls/turn (compute demand)')]
    fig, axes = plt.subplots(1, 3, figsize=(15,4.5))
    x=np.arange(len(LEVELS)); w=0.2
    for ax,(col,lab) in zip(axes,metrics):
        for j,cfg in enumerate(ORDER):
            vals=[ALL_TURNS[(ALL_TURNS.Level==lv)&(ALL_TURNS.Config==cfg)][col].mean() for lv in LEVELS]
            ax.bar(x+(j-1.5)*w, vals, w, label=cfg, color=C[cfg])
        ax.set_xticks(x); ax.set_xticklabels(LEVELS); ax.set_ylabel(lab)
        if col=='FmCalls': ax.set_yscale('log')
    axes[0].legend(fontsize=8)
    fig.suptitle('Search effort at equal compute-unit budget (Phase 3)', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS/'fig6_efficiency.png'); plt.close(fig)

def fig_final_score_outcome():
    """Clean outcome comparison: per-game FinalScore (mean +/- 95% CI over games), per level."""
    s = game_level(ALL_TURNS,'FinalScore')
    fig, ax = plt.subplots(figsize=(9,4.8))
    x=np.arange(len(LEVELS)); w=0.2
    for j,cfg in enumerate(ORDER):
        means=[s.loc[(lv,cfg),'mean'] for lv in LEVELS]
        errs =[1.96*s.loc[(lv,cfg),'se'] for lv in LEVELS]
        ax.bar(x+(j-1.5)*w, means, w, yerr=errs, capsize=3, label=cfg, color=C[cfg])
    ax.set_xticks(x); ax.set_xticklabels(LEVELS); ax.set_ylabel('FinalScore (mean +/- 95% CI over games)')
    ax.set_title('In-game score vs passive opponent (Phase 3 SCORE mode)', fontweight='bold')
    ax.legend()
    fig.tight_layout(); fig.savefig(FIGS/'fig7_final_score.png'); plt.close(fig)


if __name__ == '__main__':
    print('Writing tables...')
    print(t1_family_summary().to_string(index=False)); print()
    print(t2_ratio_sweep().to_string(index=False)); print()
    print(t3_winrates().to_string()); print()
    t4,wr = t4_improvement_vs_emcts()
    print(t4.to_string(index=False)); print(); print(wr.to_string(index=False))
    print('\nWriting figures...')
    fig_ratio_sweep(); fig_within_turn_trajectory(); fig_family_value_trajectory()
    fig_expansion_ratio_trajectory(); fig_expansion_ratio_value(); fig_burn_crossover_benefit()
    fig_winrates(); fig_value_vs_score(); fig_efficiency(); fig_final_score_outcome()
    print('Done. Tables -> analysis/tables/  Figures -> analysis/figures/')
