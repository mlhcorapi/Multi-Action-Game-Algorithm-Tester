"""
analyze_v6_full.py — comprehensive v6 analysis.

Produces **the same tables and figures as v5**, plus the budget sections B1-B6, which turn "the
budget was equalised across agents" from a claim into a reported result.

Rather than forking 1400 lines, this imports `analyze_v6_base` — the v5 analysis engine, vendored
into v6 — and re-points its module globals at the v6 output tree. Every v5 analysis therefore runs on
v6 data by *identical* code, which is what makes a v5-vs-v6 comparison meaningful and what keeps the
figures' v5 layout, colours and naming (fig3-fig8, T1-T8, E1-E16, and the hypothesis battery
E17-E21). See that file's header for why it lives here rather than under its original name.

Two v6-specific data fixes are applied in the loader before any of that runs:

  1. `FmCalls` is remapped to SEARCH-only forward-model calls (the raw per-turn total is kept as
     `FmCallsTotal`). v6 charges the budget for search only, and its greedy initialization is far
     more expensive than v5's random one, so leaving `FmCalls` as the turn total would inflate every
     inherited cost metric (E5 fmCalls_per_turn, E15 fm_per_node, ...) by an initialization cost that
     is not part of the budget — and inflate it *more* in high-branching-factor scenarios, which is
     exactly the axis the single-vs-dual analysis reads.
  2. `ComputeUnitsElapsed` is dropped at the per-turn level (it is a per-burn column, like
     `FmCallsElapsed`), so it cannot be silently averaged as if it were a turn-level value.

Run:  python analysis/analyze_v6_full.py [test_results/v6]
Output: analysis/figures_v6_full/*.png  +  analysis/tables_v6_full/*.csv
"""
import sys, glob, re
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_v6_base as base   # noqa: E402  (path must be set first)


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test_results/v6')
FIGS = Path('analysis/figures_v6_full'); FIGS.mkdir(parents=True, exist_ok=True)
TABLES = Path('analysis/tables_v6_full'); TABLES.mkdir(parents=True, exist_ok=True)
CALIB = ROOT / '_calibration' / 'calibration.csv'

LEAF_RE = re.compile(r'(?P<agent>EMCTS|CrossoverBB|CrossoverExpansion|CrossoverCombo)'
                     r'(?:/ratio_(?P<ratio>\d+))?'
                     r'/(?P<ms>\d+)cu/results\.csv$')


def detect_levels(root):
    """Budget levels are read from the folder names, not hardcoded: C0 may change them."""
    levels = set()
    for f in glob.glob(str(root) + '/**/results.csv', recursive=True):
        m = LEAF_RE.search(f.replace('\\', '/'))
        if m:
            levels.add(int(m.group('ms')))
    return sorted(levels)


def install_v6_context():
    """Re-point the v5 module at the v6 tree and patch its two schema differences."""
    base.ROOT = ROOT
    base.FIGS = FIGS
    base.TABLES = TABLES

    levels = detect_levels(ROOT)
    if levels:
        base.MS_LEVELS = levels
    print(f'  budget levels detected: {base.MS_LEVELS}')

    _load_all = base.load_all
    _per_turn = base.per_turn

    def load_all_v6():
        df = _load_all()
        # Fix 1: make FmCalls mean SEARCH forward-model calls, so every inherited cost metric is
        # measured on the same work the budget bounds.
        if 'InitFmCalls' in df.columns:
            df['FmCallsTotal'] = df['FmCalls']
            df['FmCalls'] = df['FmCalls'] - df['InitFmCalls']
        return df

    def per_turn_v6(df_burn):
        out = _per_turn(df_burn)
        # Fix 2: ComputeUnitsElapsed is per-burn, like FmCallsElapsed.
        return out.drop(columns=['ComputeUnitsElapsed'], errors='ignore')

    base.load_all = load_all_v6
    base.per_turn = per_turn_v6


# ==================================================================== budget adherence (new) ==

def _bf_tiers(portfolio):
    """Per-source branching-factor tertiles, matching the v5 telemetry analyses' convention."""
    if portfolio.empty or 'BranchingFactor' not in portfolio.columns:
        return pd.DataFrame()
    p = portfolio[['ScenarioId', 'Source', 'BranchingFactor']].copy()
    p['BFTier'] = (p.groupby('Source').BranchingFactor
                    .transform(lambda s: pd.qcut(s, 3, labels=['low', 'mid', 'high'], duplicates='drop')))
    return p


def b1_budget_adherence(turn_df, df_burn):
    """B1 — did every agent actually spend the budget, and no more?

    The budget is a per-turn ceiling on SEARCH compute-units. This reports realized spend against
    target for each (config, level). Overshoot is expected and bounded: the ceiling is only tested at
    iteration boundaries, so a turn can exceed it by up to the cost of the single iteration in flight
    — which is larger for the crossover agents (a tournament, or a crossover child plus repair) than
    for a mutation step. That is the number a reviewer will want to see quantified rather than
    asserted, and it is the reason this table exists.
    """
    if 'SearchUnits' not in turn_df.columns:
        print('  B1: no SearchUnits column (pre-v6 data?), skipping'); return
    t = turn_df.copy()
    t['ratio'] = t.SearchUnits / t.ThinkingMs      # ThinkingMs holds the compute-unit level
    g = t.groupby(['Config', 'ThinkingMs']).agg(
        n=('SearchUnits', 'size'),
        units_mean=('SearchUnits', 'mean'),
        units_sd=('SearchUnits', 'std'),
        ratio_mean=('ratio', 'mean'),
        ratio_p50=('ratio', lambda s: s.quantile(0.50)),
        ratio_p99=('ratio', lambda s: s.quantile(0.99)),
        ratio_max=('ratio', 'max'),
    ).reset_index()
    g['overshoot_pct_mean'] = (g.ratio_mean - 1.0) * 100
    g['overshoot_pct_max'] = (g.ratio_max - 1.0) * 100
    g = g.round(4)
    g.to_csv(TABLES / 'B1_budget_adherence.csv', index=False)

    print('\n  B1 realized SEARCH spend vs target budget (ratio 1.00 = exactly on budget):')
    print(g.pivot(index='Config', columns='ThinkingMs', values='ratio_mean')
           .reindex(base.ORDER).round(3).to_string())
    spread = g.groupby('ThinkingMs').ratio_mean.agg(['min', 'max'])
    print('\n  spread of mean spend across the 12 configs (tight = the budget is equalised):')
    print(spread.round(3).to_string())

    # Figure: left = realized/target per config; right = per-phase pacing (should be ~linear in phase).
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.8))
    x = np.arange(len(base.ORDER))
    width = 0.8 / max(1, len(base.MS_LEVELS))
    for i, ms in enumerate(base.MS_LEVELS):
        sub = g[g.ThinkingMs == ms].set_index('Config').reindex(base.ORDER)
        axes[0].bar(x + (i - (len(base.MS_LEVELS) - 1) / 2) * width, sub.ratio_mean.values, width,
                    label=f'{ms} cu')
    axes[0].axhline(1.0, color='black', lw=1, ls='--')
    axes[0].set_xticks(x); axes[0].set_xticklabels(base.ORDER, rotation=45, ha='right', fontsize=7)
    axes[0].set_ylabel('realized SEARCH units / target'); axes[0].legend(fontsize=7)
    axes[0].set_title('Budget adherence per config', fontsize=10)

    if 'ComputeUnitsElapsed' in df_burn.columns:
        for cfg in ('EMCTS', 'CrossoverBB', 'CrossoverCombo@5'):
            sub = df_burn[(df_burn.Config == cfg) & (df_burn.ThinkingMs == base.MS_LEVELS[-1])]
            if sub.empty: continue
            pace = sub.groupby('BridgeBurningPhaseNumber').ComputeUnitsElapsed.mean()
            axes[1].plot(pace.index, pace.values / base.MS_LEVELS[-1], marker='o', ms=3,
                         label=cfg, color=base.COLORS.get(cfg))
        axes[1].plot([0, 20], [0, 1], color='black', lw=1, ls='--', label='even pacing')
        axes[1].set_xlabel('bridge-burn phase'); axes[1].set_ylabel('cumulative spend / budget')
        axes[1].legend(fontsize=7); axes[1].set_title('Within-turn pacing', fontsize=10)
    fig.suptitle('B1 — budget adherence and pacing', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'B1_budget_adherence.png'); plt.close(fig)


def b2_op_mix(turn_df):
    """B2 — the operation mix, and how unfair an equal-fmcalls budget would have been.

    This is the empirical justification for budgeting on weighted compute-units rather than on
    forward-model calls. At an equal compute budget the agents do materially different numbers of
    advances, because they split their work differently between advancing, copying and evaluating.

    `equal_fmcalls_compute_ratio` makes the consequence concrete. If A does F_A advances where EMCTS
    does F_E at the same unit budget, then under an *equal-fmcalls* budget A would have consumed
    F_E/F_A times EMCTS's real compute. A value of 1.4 means that budgeting on fmcalls would have
    handed that agent 40% more real compute than the baseline for the same nominal budget.
    """
    cols = {'SearchAdvances', 'SearchCopies', 'SearchEvals'}
    if not cols.issubset(turn_df.columns):
        print('  B2: no op-count columns, skipping'); return
    g = turn_df.groupby(['Config', 'ThinkingMs']).agg(
        advances=('SearchAdvances', 'mean'),
        copies=('SearchCopies', 'mean'),
        evals=('SearchEvals', 'mean'),
        units=('SearchUnits', 'mean'),
        nodes=('NodesEvaluated', 'mean'),
        unique=('UniqueSequences', 'mean'),
    ).reset_index()
    g['copies_per_advance'] = g.copies / g.advances
    g['evals_per_advance'] = g.evals / g.advances
    g['units_per_advance'] = g.units / g.advances

    emcts = g[g.Config == 'EMCTS'].set_index('ThinkingMs').advances
    g['equal_fmcalls_compute_ratio'] = g.apply(
        lambda r: emcts.get(r.ThinkingMs, np.nan) / r.advances if r.advances else np.nan, axis=1)
    g = g.round(4)
    g.to_csv(TABLES / 'B2_op_mix.csv', index=False)

    print('\n  B2 operation mix per turn (at equal compute-unit budget):')
    print(g.pivot(index='Config', columns='ThinkingMs', values='units_per_advance')
           .reindex(base.ORDER).round(3).to_string())
    print('\n  B2 what an EQUAL-FMCALLS budget would have given each agent, relative to EMCTS')
    print('     (1.00 = fair; >1 = that agent would have received more real compute):')
    print(g.pivot(index='Config', columns='ThinkingMs', values='equal_fmcalls_compute_ratio')
           .reindex(base.ORDER).round(3).to_string())

    fig, ax = plt.subplots(figsize=(9, 4.6))
    x = np.arange(len(base.ORDER))
    width = 0.8 / max(1, len(base.MS_LEVELS))
    for i, ms in enumerate(base.MS_LEVELS):
        sub = g[g.ThinkingMs == ms].set_index('Config').reindex(base.ORDER)
        ax.bar(x + (i - (len(base.MS_LEVELS) - 1) / 2) * width,
               sub.equal_fmcalls_compute_ratio.values, width, label=f'{ms} cu')
    ax.axhline(1.0, color='black', lw=1, ls='--')
    ax.set_xticks(x); ax.set_xticklabels(base.ORDER, rotation=45, ha='right', fontsize=7)
    ax.set_ylabel('relative real compute under an equal-fmcalls budget')
    ax.legend(fontsize=7)
    ax.set_title('B2 — the unfairness a forward-model-call budget would have introduced',
                 fontweight='bold', fontsize=10)
    fig.tight_layout(); fig.savefig(FIGS / 'B2_op_mix.png'); plt.close(fig)


def b3_reference_conversion():
    """B3 — the units→seconds conversion for the reference machine, from the C0 calibration.

    This is what lets the paper say "40,000 units is about a second of thinking on machine X".

    The agent column is the important one, and since the weights became per (config, level) it is a
    CHECK rather than a design question: a unit is defined as a fixed amount of wall-clock, so
    units/ms should be flat across configs. Spread here means some config was priced with the wrong
    weights - stale, measured elsewhere, or its profile missing from the calibration file - and its
    nominal budget bought it more or less real compute than everyone else's.
    """
    if not CALIB.exists():
        print(f'  B3: no calibration at {CALIB} — run CalibrationRunnerV6 first, skipping'); return
    c = pd.read_csv(CALIB)
    c['Config'] = np.where(c.Ratio.astype(str) == 'NA', c.Config,
                           c.Config + '@r' + c.Ratio.astype(str))
    c['SearchMillis'] = c.SearchMillis.clip(lower=1)
    c['UnitsPerMs'] = c.SearchUnits / c.SearchMillis

    by_cfg = c.pivot_table(index='Config', columns='TargetMillis', values='UnitsPerMs', aggfunc='mean')
    by_src = c.pivot_table(index='Source', columns='TargetMillis', values='UnitsPerMs', aggfunc='mean')
    budgets = c.pivot_table(index='Config', columns='TargetMillis', values='SearchUnits', aggfunc='mean')

    stab = c.groupby(['TargetMillis', 'Config']).SearchUnits.agg(['mean', 'std'])
    stab['cv'] = stab['std'] / stab['mean']

    by_cfg.round(3).to_csv(TABLES / 'B3_reference_conversion.csv')
    budgets.round(0).to_csv(TABLES / 'B3_per_agent_budgets.csv')
    by_src.round(3).to_csv(TABLES / 'B3_conversion_by_source.csv')

    overall = c.UnitsPerMs.mean()
    spread = by_cfg.mean(axis=1)
    print(f'\n  B3 reference-machine conversion: {overall:.2f} compute-units per ms overall')
    print(f'     => 1 s of search is about {overall * 1000:,.0f} units on this machine')
    print('\n  units/ms by config (FLAT across configs = one shared budget is equal thinking time):')
    print(by_cfg.round(2).to_string())
    print(f'\n     across-config spread: min {spread.min():.2f}, max {spread.max():.2f}, '
          f'ratio {spread.max() / max(spread.min(), 1e-9):.2f}x')
    print('\n  units/ms by scenario source (the H5 confound check — should be flat):')
    print(by_src.round(2).to_string())
    print('\n  repetition stability (CV of SearchUnits; small = the reference machine was steady):')
    print(stab.cv.groupby('TargetMillis').describe()[['mean', 'max']].round(4).to_string())
    flat = (by_cfg.max() / by_cfg.min() - 1) * 100
    print('\n  FLATNESS across configs per target (the per-config weight check):')
    for t, v in flat.items():
        verdict = 'flat' if v < 5 else ('drifting' if v < 15 else 'NOT FLAT - weights suspect')
        print(f'     {t:>6} ms: {v:5.1f}%   {verdict}')
    print('  A unit is defined as a fixed amount of wall-clock, so a large spread means a config was')
    print('  priced with weights that are not its own - see B5 for which weights were in force.')

    print('\n  units spent per config x target (also flat when the currency is right):')
    print(budgets.round(0).to_string())

    fig, axes = plt.subplots(1, 2, figsize=(13, 4.6))
    for ms in sorted(c.TargetMillis.unique()):
        sub = by_cfg[ms].reindex([c for c in base.ORDER if c in by_cfg.index])
        axes[0].plot(range(len(sub)), sub.values, marker='o', ms=4, label=f'{ms} ms')
    axes[0].set_xticks(range(len(by_cfg.index)))
    axes[0].set_xticklabels([c for c in base.ORDER if c in by_cfg.index], rotation=45, ha='right', fontsize=7)
    axes[0].set_ylabel('compute-units per ms'); axes[0].legend(fontsize=7)
    axes[0].set_title('Conversion by config (flat = shared budget is fair)', fontsize=10)

    for src in sorted(c.Source.unique()):
        sub = c[c.Source == src].groupby('TargetMillis').UnitsPerMs.mean()
        axes[1].plot(sub.index, sub.values, marker='s', ms=5, label=src)
    axes[1].set_xlabel('target search time (ms)'); axes[1].set_ylabel('compute-units per ms')
    axes[1].legend(fontsize=8)
    axes[1].set_title('Conversion by scenario source (H5 confound check)', fontsize=10)
    fig.suptitle('B3 — reference-machine conversion', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'B3_reference_conversion.png'); plt.close(fig)


def b5_compute_profiles():
    """B5 — the per-(config, level) weights that priced this dataset.

    Provenance, and the evidence for why they are per-cell at all. A compute-unit is only defined
    relative to these numbers, so a dataset without them is not interpretable; and the spread of
    `AdvanceNanos` within a level is the measurement that made one global weight set untenable.
    """
    prof = ROOT / '_calibration' / 'compute_profiles.csv'
    if not prof.exists():
        print(f'  B5: no {prof.name} - this tree was produced WITHOUT per-config weight calibration,')
        print('      so every config was priced with the global weights. Report that as a limitation.')
        return
    p = pd.read_csv(prof)
    p.to_csv(TABLES / 'B5_compute_profiles.csv', index=False)

    print('\n  B5 per-operation cost by profile (ns), and the weights derived from them:')
    print(p[['Profile', 'AdvanceNanos', 'CopyNanos', 'EvalNanos',
             'W_ADVANCE', 'W_COPY', 'W_EVAL']].round(4).to_string(index=False))

    spread = p.groupby('BudgetUnits').AdvanceNanos.agg(['min', 'max'])
    spread['spread_pct'] = (spread['max'] / spread['min'] - 1) * 100
    print('\n  per-advance cost spread WITHIN a level (why the weights are per-config):')
    print(spread.round(1).to_string())

    if 'MsPerKiloUnitOld' in p.columns:
        old = p.groupby('BudgetUnits').MsPerKiloUnitOld.agg(['min', 'max'])
        old['unfairness_pct'] = (old['max'] / old['min'] - 1) * 100
        print('\n  what 1,000 nominal units really bought under the OLD single weight set (ms):')
        print(old.round(3).to_string())
        print(f"  under the per-config weights it is {p.MsPerKiloUnitNew.iloc[0]:.3f} ms for every "
              'cell by construction - that constancy is the fix.')

    bad = p[p.Status != 'ok'] if 'Status' in p.columns else p.iloc[0:0]
    if len(bad):
        print(f'\n  WARNING: {len(bad)} cell(s) failed their calibration checks:')
        print(bad[['Profile', 'Status']].to_string(index=False))


def b6_realized_time_per_unit(turn_df):
    """B6 — did equal unit budgets actually cost equal wall-clock in the run itself?

    B5 is what the calibration measured; this is what the experiment then did. `PlanMillis` minus
    `InitMillis` is the search time of a turn and `SearchUnits` is what it was charged, so their ratio
    is the realized price of a unit. With per-config weights it should be near-constant across configs
    within a level.

    Read it as corroboration, not as proof: the isolation phase runs ~90 turns at once, so PlanMillis
    carries whatever contention and clock behaviour the machine had at that moment, and a leaf that
    happened to run during a throttled stretch looks dear. C0 (B3) is the clean measurement, because it
    is single-threaded on a quiesced machine; this one has the virtue of being measured on the actual
    dataset.
    """
    need = {'PlanMillis', 'InitMillis', 'SearchUnits'}
    if not need.issubset(turn_df.columns):
        print('  B6: no wall-clock columns in this tree, skipping'); return
    t = turn_df.copy()
    t['SearchMillis'] = (t.PlanMillis - t.InitMillis).clip(lower=1)
    t['MsPerKiloUnit'] = 1000.0 * t.SearchMillis / t.SearchUnits.clip(lower=1)
    g = (t.groupby(['ThinkingMs', 'Config']).MsPerKiloUnit
          .agg(['median', 'mean', 'count']).reset_index())
    g.round(4).to_csv(TABLES / 'B6_realized_ms_per_unit.csv', index=False)

    piv = g.pivot(index='Config', columns='ThinkingMs', values='median')
    print('\n  B6 realized ms per 1,000 SEARCH units (median), by config x budget level:')
    print(piv.reindex([c for c in base.ORDER if c in piv.index]).round(3).to_string())
    spread = (piv.max() / piv.min() - 1) * 100
    print('\n  spread across configs within a level:')
    for lvl, v in spread.items():
        verdict = 'consistent' if v < 15 else 'wide - check B5 and the governor/throttle log'
        print(f'     {lvl:>8} cu: {v:5.1f}%   {verdict}')
    print('  (Multi-threaded wall-clock, so treat this as corroboration of B3 rather than a')
    print('   measurement in its own right.)')


def b4_init_cost(turn_df, portfolio):
    """B4 — how big the excluded greedy initialization actually is.

    v6 measures initialization and deliberately keeps it OUT of the budget. This quantifies what that
    decision is worth. Initialization evaluates every available action at every sequence position, so
    its cost scales with branching factor — and branching factor differs systematically by scenario
    source. Charging it would therefore have handed single scenarios less search budget than dual,
    along the exact axis the single-vs-dual analysis measures. The `share_of_budget` column is the
    size of the bias that exclusion avoids.
    """
    if 'InitUnits' not in turn_df.columns:
        print('  B4: no InitUnits column, skipping'); return
    t = turn_df.copy()
    tiers = _bf_tiers(portfolio)
    if not tiers.empty:
        t = t.merge(tiers, on='ScenarioId', how='left')
        src_col = 'Source'
    else:
        src_col = 'ScenarioSource'
        t['BFTier'] = 'all'

    t['share_of_budget'] = t.InitUnits / t.ThinkingMs

    by_src = t.groupby([src_col, 'ThinkingMs']).agg(
        init_units_mean=('InitUnits', 'mean'),
        init_units_median=('InitUnits', 'median'),
        init_ms_mean=('InitMillis', 'mean'),
        share_of_budget=('share_of_budget', 'mean'),
    ).round(4).reset_index()
    by_src.to_csv(TABLES / 'B4_init_cost.csv', index=False)

    by_tier = t.groupby([src_col, 'BFTier'], observed=False).agg(
        init_units_mean=('InitUnits', 'mean'),
        share_of_budget=('share_of_budget', 'mean'),
    ).round(4).reset_index()
    by_tier.to_csv(TABLES / 'B4_init_cost_by_bf_tier.csv', index=False)

    print('\n  B4 greedy-initialization cost, by scenario source (EXCLUDED from the budget):')
    print(by_src.to_string(index=False))
    print('\n  B4 by branching-factor tertile — the bias that excluding it avoids:')
    print(by_tier.to_string(index=False))

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.4))
    for src, color in (('single', '#1f78b4'), ('dual', '#e31a1c')):
        s = by_src[by_src[src_col] == src]
        if s.empty: continue
        axes[0].plot(s.ThinkingMs, s.init_units_mean, marker='o', color=color, label=src)
    axes[0].set_xscale('log'); axes[0].set_xlabel('budget (compute-units)')
    axes[0].set_ylabel('greedy-init cost (units)'); axes[0].legend(fontsize=8)
    axes[0].set_title('Initialization cost by source', fontsize=10)

    tiers_order = ['low', 'mid', 'high']
    x = np.arange(len(tiers_order))
    for src, color, off in (('single', '#1f78b4', -0.2), ('dual', '#e31a1c', +0.2)):
        s = by_tier[by_tier[src_col] == src].set_index('BFTier').reindex(tiers_order)
        if s.share_of_budget.isna().all(): continue
        axes[1].bar(x + off, s.share_of_budget.values * 100, 0.4, color=color, label=src)
    axes[1].set_xticks(x); axes[1].set_xticklabels(tiers_order)
    axes[1].set_xlabel('branching-factor tertile')
    axes[1].set_ylabel('init cost as % of the search budget'); axes[1].legend(fontsize=8)
    axes[1].set_title('What charging init would have cost each tier', fontsize=10)
    fig.suptitle('B4 — the excluded greedy initialization', fontweight='bold')
    fig.tight_layout(); fig.savefig(FIGS / 'B4_init_cost.png'); plt.close(fig)


# ==================================================================================== main ==

def main():
    print(f'v6 analysis — loading from {ROOT}')
    install_v6_context()

    # Every v5 table and figure, produced by v5's own code against the v6 tree.
    base.main()

    # The v6-specific budget section.
    print('\n' + '=' * 78)
    print('BUDGET ADHERENCE (v6 additions)')
    print('=' * 78)
    df_burn = base.load_all()
    turn_df = base.per_turn(df_burn)
    portfolio = base.load_portfolio()

    print('\nB1: budget adherence');        b1_budget_adherence(turn_df, df_burn)
    print('\nB2: operation mix');           b2_op_mix(turn_df)
    print('\nB3: reference conversion');    b3_reference_conversion()
    print('\nB4: init cost (excluded)');    b4_init_cost(turn_df, portfolio)
    print('\nB5: compute-unit weight profiles'); b5_compute_profiles()
    print('\nB6: realized time per unit');    b6_realized_time_per_unit(turn_df)

    print(f'\nDone. tables -> {TABLES}/  figures -> {FIGS}/')


if __name__ == '__main__':
    main()
