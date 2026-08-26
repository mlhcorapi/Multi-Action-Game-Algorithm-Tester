"""
v6_significance.py — does crossover help EMCTS, and which crossover?

This is the inferential half of the v6 analysis. `analyze_v6_full.py` describes the data; this file
tests it, and it exists because two things in the descriptive analysis are not enough to support a
claim in a thesis.

**1. The unit of analysis is the scenario, not the turn.**
The descriptive E1 table computes a 95% CI as `1.96·sd/sqrt(n)` over every turn — n ≈ 5,000 per
cell. That treats the 50 visits of a scenario as 50 independent observations, and they are not: they
share a board, a captured position, a branching factor and a greedy root. The independent replicate
is the SCENARIO (n = 100 in the v6 portfolio), and pooling visits understates the standard error by
up to sqrt(50) ≈ 7×. At that precision every difference looks significant, including ones far too
small to matter. Here the 50 visits of a scenario are AVERAGED first, and the test runs on the
per-scenario paired differences. Every n below is read from the data, so a re-sized portfolio needs
no change here.

**2. The 12 configurations are a 2x2 factorial, and nobody was reading it as one.**

|                        | expansion crossover OFF | expansion crossover ON |
|------------------------|-------------------------|------------------------|
| **BB crossover OFF**   | `EMCTS`                 | `CrossoverExpansion@r` |
| **BB crossover ON**    | `CrossoverBB`           | `CrossoverCombo@r`     |

Every cell is present at every ratio and every budget, so at each (ratio, budget) the design
identifies three separate quantities, not one:

- **BB main effect** — what bridge-burning crossover does on its own: `CrossoverBB − EMCTS`
- **Expansion main effect** — what expansion crossover does on its own: `CrossoverExpansion@r − EMCTS`
- **Interaction** — whether the two combine as the sum of their parts:
  `(CrossoverCombo@r − CrossoverExpansion@r) − (CrossoverBB − EMCTS)`

The interaction is the question "do these two mechanisms reinforce or interfere with each other",
and it is *only* answerable because the design contains all four cells. Reporting each config against
EMCTS — which is all the descriptive analysis does — cannot distinguish "combo is good because BB is
good" from "combo is good because the two operators complement each other".

Method: paired Wilcoxon signed-rank on the per-scenario differences (the design is paired — same
scenarios, same visit seeds, same greedy root for every config), Benjamini–Hochberg correction across
the family of comparisons within each (budget, split), and Cliff's delta as a non-parametric effect
size alongside the raw mean difference. Wilcoxon rather than a t-test because the per-scenario deltas
are heavy-tailed across a portfolio whose branching factor spans several-fold; skewness and excess
kurtosis are reported per comparison so that choice is visible rather than asserted.

The test is implemented here in numpy (normal approximation with tie and zero corrections, which at
n = 100 is already indistinguishable from the exact distribution) rather than taken from scipy, so
the script has no dependency beyond what the descriptive analysis already needs.

Effects are also reported as a percentage of **EMCTS's own search gain** on the same scenarios
(`FinalSequenceValue − InitialSequenceValue`, where the initial value is the shared greedy root and
therefore identical across configs). A raw delta of 12 heuristic points is uninterpretable on its own;
"4% of the improvement EMCTS itself achieves" is not.

Run:    python analysis/v6_significance.py [test_results/v6]
Output: analysis/v6_significance/
          per_scenario_deltas.csv        per-scenario paired Δ, the input to every test
          wilcoxon_vs_emcts.csv          each config vs EMCTS (the headline comparison)
          factorial_effects.csv          BB main / Expansion main / interaction, per ratio × budget
          clustering_comparison.csv      turn-pooled vs scenario-clustered CIs, side by side
          SIGNIFICANCE_V6.md             a written summary of what the numbers say
          F1_vs_emcts.png                paired Δ vs EMCTS, * where p_adj < 0.05
          F2_factorial.png               main effects and interaction per ratio
"""
import sys, warnings
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_v5_full as v5   # loaders, LEAF_RE, ORDER, COLORS

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test_results/v6')
OUT = Path('analysis/v6_significance'); OUT.mkdir(parents=True, exist_ok=True)
ALPHA = 0.05
RATIOS = v5.RATIOS
VALUE = 'FinalSequenceValue'


# ---------------------------------------------------------------- data

def load_per_scenario():
    """Per (Config, budget, scenario) mean over visits — the analysis unit.

    Also returns the per-turn frame, so the clustering comparison can show what pooling visits would
    have claimed."""
    v5.ROOT = ROOT
    burn = v5.load_all()
    burn['ThinkingMs'] = burn['ThinkingMs'].astype(int)
    turn = v5.per_turn(burn)
    if 'InitialSequenceValue' not in turn.columns:
        raise SystemExit('results.csv has no InitialSequenceValue column — wrong tree?')
    turn['Gain'] = turn[VALUE] - turn['InitialSequenceValue']

    portfolio = v5.load_portfolio()
    if not portfolio.empty:
        turn = turn.merge(portfolio[['ScenarioId', 'Source', 'BranchingFactor']],
                          on='ScenarioId', how='left')
    else:
        turn['Source'] = 'unknown'

    keys = ['Config', 'ThinkingMs', 'ScenarioId']
    scen = (turn.groupby(keys + ['Source'], as_index=False)
                .agg(Final=(VALUE, 'mean'), Gain=('Gain', 'mean'), Visits=(VALUE, 'size')))
    return burn, turn, scen


def paired(scen, cfg_a, cfg_b, budget, source=None):
    """Per-scenario paired values for two configs at one budget. Returns (a, b, scenarioIds).

    An inner join on ScenarioId is what makes this paired: a scenario missing for either config —
    which a partially-finished leaf would produce — drops out of BOTH sides rather than biasing one.
    """
    s = scen[scen.ThinkingMs == budget]
    if source is not None:
        s = s[s.Source == source]
    a = s[s.Config == cfg_a].set_index('ScenarioId')
    b = s[s.Config == cfg_b].set_index('ScenarioId')
    common = a.index.intersection(b.index)
    return a.loc[common], b.loc[common], common


# ---------------------------------------------------------------- statistics

def cliffs_delta(x, y):
    """Non-parametric effect size: P(x>y) − P(x<y), in [−1, 1]. Reported because a significant
    Wilcoxon on a hundred-odd scenarios says the sign is reliable, not that the effect is large."""
    x = np.asarray(x); y = np.asarray(y)
    if len(x) == 0 or len(y) == 0:
        return np.nan
    # Pairwise comparison; n is in the hundreds, so the O(n^2) form is fine and exact.
    gt = sum((xi > y).sum() for xi in x)
    lt = sum((xi < y).sum() for xi in x)
    return (gt - lt) / (len(x) * len(y))


def cliff_magnitude(d):
    a = abs(d)
    if np.isnan(a): return 'n/a'
    if a < 0.147: return 'negligible'
    if a < 0.33:  return 'small'
    if a < 0.474: return 'medium'
    return 'large'


def _norm_sf(z):
    """Upper-tail standard normal probability, via the error function in the stdlib."""
    import math
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def wilcoxon_signed_rank(d):
    """Two-sided Wilcoxon signed-rank p-value, normal approximation with tie and zero corrections.

    Zero differences are dropped (Wilcoxon's own handling) and the variance is reduced by the tie
    correction term, which matters here because per-scenario deltas can repeat. At n = 100 the normal
    approximation agrees with the exact distribution to well beyond the precision anyone reads.
    Returns (p, n_used)."""
    d = np.asarray(d, dtype=float)
    d = d[~np.isnan(d)]
    d = d[d != 0]
    n = len(d)
    if n < 6:                       # too few non-zero pairs for the approximation to mean anything
        return (1.0 if n == 0 else np.nan), n
    ranks = _rankdata(np.abs(d))
    w_plus = ranks[d > 0].sum()
    mean_w = n * (n + 1) / 4.0
    # Tie correction: sum over tied groups of (t^3 - t), t = group size.
    _, counts = np.unique(np.abs(d), return_counts=True)
    tie_term = float(((counts ** 3) - counts).sum())
    var_w = (n * (n + 1) * (2 * n + 1) - tie_term / 2.0) / 24.0
    if var_w <= 0:
        return 1.0, n
    z = (abs(w_plus - mean_w) - 0.5) / np.sqrt(var_w)   # continuity correction
    return min(1.0, 2.0 * _norm_sf(max(0.0, z))), n


def _rankdata(a):
    """Average ranks, ties shared — the ranking Wilcoxon requires."""
    a = np.asarray(a, dtype=float)
    order = np.argsort(a, kind='mergesort')
    ranks = np.empty(len(a), dtype=float)
    ranks[order] = np.arange(1, len(a) + 1, dtype=float)
    # Average the ranks within each tied group.
    sorted_a = a[order]
    i = 0
    while i < len(a):
        j = i
        while j + 1 < len(a) and sorted_a[j + 1] == sorted_a[i]:
            j += 1
        if j > i:
            ranks[order[i:j + 1]] = (i + j + 2) / 2.0
        i = j + 1
    return ranks


def test_delta(deltas):
    """Wilcoxon signed-rank on paired differences, plus descriptive stats. Returns a dict."""
    d = np.asarray(deltas, dtype=float)
    d = d[~np.isnan(d)]
    n = len(d)
    out = dict(n=n, n_nonzero=0, mean=np.nan, median=np.nan, sd=np.nan, ci95=np.nan,
               p=np.nan, skew=np.nan, excess_kurtosis=np.nan, pos_frac=np.nan)
    if n < 3:
        return out
    out['mean'] = d.mean()
    out['median'] = float(np.median(d))
    out['sd'] = d.std(ddof=1)
    out['ci95'] = 1.96 * out['sd'] / np.sqrt(n)
    out['pos_frac'] = float((d > 0).mean())
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        out['p'], out['n_nonzero'] = wilcoxon_signed_rank(d)
        # Non-normality diagnostics — the justification for using a rank test rather than a t-test.
        s = d.std(ddof=0)
        if s > 0:
            z = (d - d.mean()) / s
            out['skew'] = float((z ** 3).mean())
            out['excess_kurtosis'] = float((z ** 4).mean() - 3.0)
    return out


def bh(pvals):
    """Benjamini–Hochberg adjusted p-values, order preserved."""
    p = np.asarray(pvals, dtype=float)
    ok = ~np.isnan(p)
    adj = np.full_like(p, np.nan)
    if ok.sum() == 0:
        return adj
    q = p[ok]
    m = len(q)
    order = np.argsort(q)
    ranked = q[order]
    adjusted = ranked * m / (np.arange(m) + 1)
    adjusted = np.minimum.accumulate(adjusted[::-1])[::-1]
    adjusted = np.clip(adjusted, 0, 1)
    back = np.empty(m)
    back[order] = adjusted
    adj[ok] = back
    return adj


# ---------------------------------------------------------------- analyses

def vs_emcts(scen, budgets, sources):
    """Each of the 11 non-baseline configs against EMCTS, per budget, per split."""
    configs = [c for c in v5.ORDER if c != 'EMCTS']
    rows = []
    for budget in budgets:
        for source in sources:
            emcts_gain_ref = None
            block = []
            for cfg in configs:
                a, b, ids = paired(scen, cfg, 'EMCTS', budget, source)
                if len(ids) == 0:
                    continue
                d = (a.Final - b.Final).values
                r = test_delta(d)
                r.update(Config=cfg, Budget=budget,
                         Split=source if source else 'all',
                         cliffs_delta=cliffs_delta(a.Final.values, b.Final.values))
                # Scale: the effect as a share of the search gain EMCTS itself achieves.
                if emcts_gain_ref is None:
                    emcts_gain_ref = b.Gain.mean()
                r['emcts_gain'] = emcts_gain_ref
                r['pct_of_emcts_gain'] = (100.0 * r['mean'] / emcts_gain_ref
                                          if emcts_gain_ref and emcts_gain_ref != 0 else np.nan)
                block.append(r)
            if block:
                adj = bh([r['p'] for r in block])
                for r, a_ in zip(block, adj):
                    r['p_adj'] = a_
                    r['significant'] = bool(a_ < ALPHA) if not np.isnan(a_) else False
                rows.extend(block)
    return pd.DataFrame(rows)


def factorial(scen, budgets, sources):
    """The 2x2 decomposition: BB main effect, Expansion main effect, and their interaction.

    All three are per-scenario paired contrasts, so each gets the same Wilcoxon treatment. The
    interaction is a difference of differences computed WITHIN a scenario — the scenario is held
    fixed across all four cells, which is what makes it a clean contrast rather than a comparison of
    two separately-noisy averages."""
    rows = []
    for budget in budgets:
        for source in sources:
            block = []
            for r in RATIOS:
                cfg_e = f'CrossoverExpansion@{r}'
                cfg_c = f'CrossoverCombo@{r}'
                # All four cells on the scenarios where every one of them exists.
                base, _, ids0 = paired(scen, 'EMCTS', 'CrossoverBB', budget, source)
                if len(ids0) == 0:
                    continue
                s = scen[(scen.ThinkingMs == budget)]
                if source is not None:
                    s = s[s.Source == source]
                cells = {}
                for name, cfg in (('emcts', 'EMCTS'), ('bb', 'CrossoverBB'),
                                  ('exp', cfg_e), ('combo', cfg_c)):
                    cells[name] = s[s.Config == cfg].set_index('ScenarioId')['Final']
                common = cells['emcts'].index
                for v in cells.values():
                    common = common.intersection(v.index)
                if len(common) < 3:
                    continue
                e = cells['emcts'].loc[common].values
                bbv = cells['bb'].loc[common].values
                xe = cells['exp'].loc[common].values
                xc = cells['combo'].loc[common].values

                gain = (s[s.Config == 'EMCTS'].set_index('ScenarioId')['Gain']
                        .loc[common].mean())

                for label, d in (('BB main effect', bbv - e),
                                 ('Expansion main effect', xe - e),
                                 ('Interaction (BB x Expansion)', (xc - xe) - (bbv - e)),
                                 ('Combo vs EMCTS', xc - e),
                                 ('Combo vs best single', xc - np.maximum(bbv, xe))):
                    st = test_delta(d)
                    st.update(Effect=label, Ratio=r, Budget=budget,
                              Split=source if source else 'all', n_scenarios=len(common),
                              emcts_gain=gain,
                              pct_of_emcts_gain=(100.0 * st['mean'] / gain
                                                 if gain and gain != 0 else np.nan))
                    block.append(st)
            if block:
                adj = bh([b['p'] for b in block])
                for b_, a_ in zip(block, adj):
                    b_['p_adj'] = a_
                    b_['significant'] = bool(a_ < ALPHA) if not np.isnan(a_) else False
                rows.extend(block)
    return pd.DataFrame(rows)


def operator_activity(turn_burn, budgets):
    """Did the operator under test actually fire?

    A configuration whose crossover never triggers is not a weak treatment — it is EMCTS wearing a
    different label, and averaging it into a ratio sweep as though it were a data point about
    crossover is a category error. This is a live risk at the top of the ratio sweep: expansion
    crossover fires once every `mutationExpansionRatio` iterations, so at a high ratio and a small
    budget a turn can finish without the counter ever coming round.

    The two operators need different evidence, because they are logged differently:

    - **expansion crossover** — `XoEvents` in the operator telemetry, one row per turn;
    - **bridge-burning crossover** — NOT in `XoEvents` (which counts expansion only). It appears as a
      populated `RawImprovement` on a bridge-burn row in `results.csv`, one per burn that ran a
      tournament. Checking BB against `XoEvents` would report every BB config as dead, which is how
      this check first read them.
    """
    tele = v5.load_telemetry()
    rows = []
    for budget in budgets:
        t = tele[tele.ThinkingMs == budget] if not tele.empty else pd.DataFrame()
        burns = turn_burn[turn_burn.ThinkingMs == budget]
        for cfg in v5.ORDER:
            expects_exp = cfg.startswith('CrossoverExpansion') or cfg.startswith('CrossoverCombo')
            expects_bb = cfg in ('CrossoverBB',) or cfg.startswith('CrossoverCombo')
            rec = dict(Budget=budget, Config=cfg,
                       ExpectsExpansionXo=expects_exp, ExpectsBurnXo=expects_bb)

            s = t[t.Config == cfg] if not t.empty else pd.DataFrame()
            if not s.empty and 'XoEvents' in s.columns:
                xo = s.XoEvents.astype(float)
                rec.update(Turns=len(s), MeanExpansionXo=xo.mean(),
                           MedianExpansionXo=float(xo.median()),
                           TurnsWithZeroExpansionXo_pct=100.0 * float((xo == 0).mean()),
                           MeanMutEvents=(s.MutEvents.astype(float).mean()
                                          if 'MutEvents' in s.columns else np.nan))
            else:
                rec.update(Turns=0, MeanExpansionXo=np.nan, MedianExpansionXo=np.nan,
                           TurnsWithZeroExpansionXo_pct=np.nan, MeanMutEvents=np.nan)

            b = burns[burns.Config == cfg]
            if not b.empty and 'RawImprovement' in b.columns:
                per_turn_burn_xo = (b.assign(has=b.RawImprovement.notna())
                                     .groupby(['ScenarioId', 'VisitId'])['has'].sum())
                rec['MeanBurnXoPerTurn'] = float(per_turn_burn_xo.mean())
                rec['TurnsWithZeroBurnXo_pct'] = 100.0 * float((per_turn_burn_xo == 0).mean())
            else:
                rec['MeanBurnXoPerTurn'] = np.nan
                rec['TurnsWithZeroBurnXo_pct'] = np.nan

            verdicts = []
            if expects_exp:
                verdicts.append(_verdict('expansion', rec['MeanExpansionXo'],
                                         rec['TurnsWithZeroExpansionXo_pct']))
            if expects_bb:
                verdicts.append(_verdict('burn', rec['MeanBurnXoPerTurn'],
                                         rec['TurnsWithZeroBurnXo_pct']))
            rec['Verdict'] = ('n/a' if not verdicts
                              else 'DEAD CELL' if any(v.startswith('DEAD') for v in verdicts)
                              else 'sparse' if any(v.startswith('sparse') for v in verdicts)
                              else 'ok')
            rec['VerdictDetail'] = '; '.join(verdicts) if verdicts else 'no crossover expected'
            rows.append(rec)
    return pd.DataFrame(rows)


def _verdict(kind, mean_events, zero_pct):
    """Classify one operator's activity. `zero_pct` is a PERCENTAGE (0-100), not a fraction — the
    first version of this compared the percentage against 20.0 as though it were a fraction and
    called a config firing in 10% of its turns 'ok'."""
    if mean_events is None or np.isnan(mean_events):
        return f'{kind}: no data'
    if mean_events == 0:
        return f'DEAD ({kind} never fired)'
    if zero_pct is not None and not np.isnan(zero_pct) and zero_pct > 20.0:
        return f'sparse ({kind} absent in {zero_pct:.0f}% of turns, mean {mean_events:.2f}/turn)'
    if mean_events < 5:
        return f'sparse ({kind} only {mean_events:.2f} events/turn)'
    return f'ok ({kind} {mean_events:.1f}/turn)'


def clustering_comparison(turn, scen, budgets):
    """Side-by-side: the CI you get pooling every turn vs clustering by scenario.

    This table exists to make the size of the difference explicit rather than asserted — it is the
    justification for the whole file."""
    rows = []
    for budget in budgets:
        emcts_turn = (turn[(turn.Config == 'EMCTS') & (turn.ThinkingMs == budget)]
                      [['ScenarioId', 'VisitId', VALUE]]
                      .rename(columns={VALUE: 'emcts'}))
        for cfg in [c for c in v5.ORDER if c != 'EMCTS']:
            t = turn[(turn.Config == cfg) & (turn.ThinkingMs == budget)]
            if t.empty:
                continue
            j = t.merge(emcts_turn, on=['ScenarioId', 'VisitId'], how='inner')
            d_turn = (j[VALUE] - j.emcts).values
            a, b, ids = paired(scen, cfg, 'EMCTS', budget)
            d_scen = (a.Final - b.Final).values
            if len(d_turn) == 0 or len(d_scen) == 0:
                continue
            rows.append(dict(
                Budget=budget, Config=cfg,
                n_turns=len(d_turn),
                ci95_pooled_turns=1.96 * d_turn.std(ddof=1) / np.sqrt(len(d_turn)),
                n_scenarios=len(d_scen),
                ci95_clustered=1.96 * d_scen.std(ddof=1) / np.sqrt(len(d_scen)),
                mean=d_scen.mean()))
    t = pd.DataFrame(rows)
    if not t.empty:
        t['ci_inflation_factor'] = (t.ci95_clustered / t.ci95_pooled_turns).round(2)
    return t


# ---------------------------------------------------------------- figures

def fig_vs_emcts(res, budgets):
    if res.empty: return
    sub = res[res.Split == 'all']
    fig, axes = plt.subplots(1, len(budgets), figsize=(5.2 * len(budgets), 6), squeeze=False)
    for ax, budget in zip(axes[0], budgets):
        s = sub[sub.Budget == budget].set_index('Config').reindex(
            [c for c in v5.ORDER if c != 'EMCTS']).dropna(subset=['mean'])
        if s.empty: continue
        ys = np.arange(len(s))
        ax.barh(ys, s['mean'], xerr=s['ci95'], capsize=2, error_kw=dict(lw=0.8),
                color=[v5.COLORS.get(c, '#888') for c in s.index],
                edgecolor='black', linewidth=0.4)
        for y, (_, row) in zip(ys, s.iterrows()):
            if row.get('significant', False):
                ax.text(row['mean'] + np.sign(row['mean']) * (row['ci95'] + 0.02 * abs(s['mean']).max()),
                        y, '*', va='center', fontsize=12)
        ax.set_yticks(ys); ax.set_yticklabels(s.index, fontsize=8)
        ax.axvline(0, color='black', lw=0.8)
        ax.set_title(f'{budget} cu')
        ax.set_xlabel('per-scenario paired Δ vs EMCTS (±95% CI)')
    n_scen = int(sub['n'].median()) if 'n' in sub.columns and len(sub) else 0
    fig.suptitle(f'Δ vs EMCTS — scenario-clustered (n={n_scen}), * = BH-adjusted p < 0.05',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(OUT / 'F1_vs_emcts.png'); plt.close(fig)


def fig_factorial(fac, budgets):
    if fac.empty: return
    effects = ['BB main effect', 'Expansion main effect', 'Interaction (BB x Expansion)']
    sub = fac[(fac.Split == 'all') & (fac.Effect.isin(effects))]
    fig, axes = plt.subplots(1, len(budgets), figsize=(5.2 * len(budgets), 4.6), squeeze=False)
    for ax, budget in zip(axes[0], budgets):
        s = sub[sub.Budget == budget]
        for eff, colour, marker in zip(effects, ['#ff7f0e', '#1f77b4', '#d62728'], ['o', 's', '^']):
            e = s[s.Effect == eff].sort_values('Ratio')
            if e.empty: continue
            ax.errorbar(e.Ratio, e['mean'], yerr=e['ci95'], marker=marker, lw=1.8,
                        capsize=3, color=colour, label=eff)
        ax.axhline(0, color='black', lw=0.8, ls=':')
        ax.set_xscale('log'); ax.set_xticks(RATIOS)
        ax.set_xticklabels([str(r) for r in RATIOS])
        ax.set_xlabel('mutationExpansionRatio (higher = less crossover)')
        ax.set_ylabel('per-scenario paired Δ')
        ax.set_title(f'{budget} cu')
        ax.legend(fontsize=7)
    fig.suptitle('2x2 decomposition — what each crossover does, and whether they interact',
                 fontweight='bold')
    fig.tight_layout(); fig.savefig(OUT / 'F2_factorial.png'); plt.close(fig)


# ---------------------------------------------------------------- report

def _p(v):
    """Render a p-value, including the honest 'not testable' case."""
    return 'n/a' if v is None or (isinstance(v, float) and np.isnan(v)) else f'{v:.2e}'


def write_report(res, fac, clus, act, budgets):
    L = []
    L.append('# v6 significance — does crossover help EMCTS, and which crossover?\n')
    L.append('Unit of analysis: the **scenario** (visits averaged first). Paired Wilcoxon signed-rank, ')
    L.append('Benjamini–Hochberg corrected within each (budget, split).\n')
    L.append('\n`n/a` in a p-value column means the comparison had fewer than 6 non-zero per-scenario ')
    L.append('differences, so no rank test is meaningful — the configurations produced identical or ')
    L.append('near-identical results rather than the test failing.\n')

    if not act.empty:
        dead = act[act.Verdict == 'DEAD CELL']
        sparse = act[act.Verdict == 'sparse']
        L.append('\n## Did the operator actually fire?\n')
        if dead.empty and sparse.empty:
            L.append('Every crossover configuration performed crossover in essentially every turn. '
                     'All cells of the design carry information.\n')
        else:
            L.append('**Cells that do not test what they claim to test:**\n')
            L.append('| budget | config | expansion xo/turn | burn xo/turn | detail | verdict |')
            L.append('|---|---|---|---|---|---|')
            for _, r in pd.concat([dead, sparse]).iterrows():
                L.append(f"| {int(r.Budget)} | {r.Config} | {r.MeanExpansionXo:.2f} | "
                         f"{r.MeanBurnXoPerTurn:.2f} | "
                         f"{r.VerdictDetail} | **{r.Verdict}** |")
            L.append('\nA `DEAD CELL` is EMCTS under another name: the ratio is high enough (or the '
                     'budget small enough) that the crossover counter never comes round inside a turn. '
                     'Such a cell must not be read as "crossover at this ratio does nothing" — it is '
                     '"crossover did not happen". Either drop it from the ratio sweep or say so '
                     'explicitly when reporting.\n')

    if not clus.empty:
        worst = clus.sort_values('ci_inflation_factor', ascending=False).iloc[0]
        L.append('\n## Why the unit matters\n')
        L.append(f'Pooling all turns as independent gives a CI up to '
                 f'**{worst.ci_inflation_factor:.1f}x narrower** than clustering by scenario '
                 f'({worst.Config} at {int(worst.Budget)} cu: ±{worst.ci95_pooled_turns:.3f} pooled over '
                 f'{int(worst.n_turns)} turns vs ±{worst.ci95_clustered:.3f} over '
                 f'{int(worst.n_scenarios)} scenarios). Everything below uses the clustered form.\n')

    L.append('\n## Each configuration vs EMCTS\n')
    for budget in budgets:
        s = res[(res.Budget == budget) & (res.Split == 'all')]
        if s.empty: continue
        L.append(f'\n### {budget} compute-units\n')
        L.append('| config | Δ vs EMCTS | 95% CI | % of EMCTS gain | Cliff\'s δ | p (BH) | sig |')
        L.append('|---|---|---|---|---|---|---|')
        for _, r in s.iterrows():
            L.append(f"| {r.Config} | {r['mean']:+.2f} | ±{r['ci95']:.2f} | "
                     f"{r['pct_of_emcts_gain']:+.1f}% | {r['cliffs_delta']:+.3f} "
                     f"({cliff_magnitude(r['cliffs_delta'])}) | {_p(r['p_adj'])} | "
                     f"{'**yes**' if r['significant'] else 'no'} |")

    L.append('\n## The 2x2 decomposition\n')
    L.append('`CrossoverBB` turns bridge-burning crossover on; `CrossoverExpansion@r` turns expansion ')
    L.append('crossover on; `CrossoverCombo@r` turns on both. All four cells of the design are present ')
    L.append('at every ratio, so the two mechanisms can be separated and their interaction measured.\n')
    for budget in budgets:
        s = fac[(fac.Budget == budget) & (fac.Split == 'all')]
        if s.empty: continue
        L.append(f'\n### {budget} compute-units\n')
        L.append('| effect | ratio | Δ | 95% CI | % of EMCTS gain | p (BH) | sig |')
        L.append('|---|---|---|---|---|---|---|')
        for _, r in s.iterrows():
            L.append(f"| {r.Effect} | {int(r.Ratio)} | {r['mean']:+.2f} | ±{r['ci95']:.2f} | "
                     f"{r['pct_of_emcts_gain']:+.1f}% | {_p(r['p_adj'])} | "
                     f"{'**yes**' if r['significant'] else 'no'} |")

    L.append('\n## How to read the interaction\n')
    L.append('- **Interaction ≈ 0** — the two crossovers are independent: combining them gives the sum ')
    L.append('of their separate effects, and each can be tuned on its own.\n')
    L.append('- **Interaction < 0** — they interfere. The usual mechanism is that both draw parents ')
    L.append('from the same converging elite pool, so the second operator adds cost without adding ')
    L.append('material (see E9 inbreeding and E21 pool collapse in the descriptive analysis).\n')
    L.append('- **Interaction > 0** — they complement each other, and the combination is worth more ')
    L.append('than tuning either alone.\n')
    L.append('\n`Combo vs best single` is the practical version of the same question: is running both ')
    L.append('operators better than running whichever single one is better?\n')

    L.append('\n## Caveats\n')
    L.append('- This measures **search quality within one turn** from a captured position — the value ')
    L.append('of the best action sequence found under a fixed compute budget. It does not measure ')
    L.append('game-playing strength; that requires the tournament phase.\n')
    L.append('- Effects are heuristic-value units on the SIMPLE_HEURISTIC scale. The ')
    L.append('`% of EMCTS gain` column is the interpretable version.\n')
    L.append('- Significance says the SIGN is reliable, at whatever scenario count the tables report. Cliff\'s δ says whether the ')
    L.append('effect is large enough to matter; a "negligible" δ with p < 0.001 is a real but tiny effect.\n')

    (OUT / 'SIGNIFICANCE_V6.md').write_text('\n'.join(L), encoding='utf-8')


def main():
    burn, turn, scen = load_per_scenario()
    budgets = sorted(scen.ThinkingMs.unique())
    sources = [None] + sorted(s for s in scen.Source.dropna().unique() if s != 'unknown')
    print(f'  budgets: {budgets}')
    print(f'  scenarios per cell: {scen.groupby("Config").ScenarioId.nunique().min()}'
          f'  (visits averaged: {scen.Visits.median():.0f} per scenario)')

    scen.to_csv(OUT / 'per_scenario_deltas.csv', index=False)

    res = vs_emcts(scen, budgets, sources)
    res.round(6).to_csv(OUT / 'wilcoxon_vs_emcts.csv', index=False)

    fac = factorial(scen, budgets, sources)
    fac.round(6).to_csv(OUT / 'factorial_effects.csv', index=False)

    clus = clustering_comparison(turn, scen, budgets)
    clus.round(6).to_csv(OUT / 'clustering_comparison.csv', index=False)

    act = operator_activity(burn, budgets)
    if not act.empty:
        act.round(4).to_csv(OUT / 'operator_activity.csv', index=False)
        dead = act[act.Verdict.isin(['DEAD CELL', 'sparse'])]
        if not dead.empty:
            print('  WARNING: crossover barely fires in these cells -- they do not test crossover:')
            for _, r in dead.iterrows():
                print(f'    {int(r.Budget)} cu  {r.Config:26s} {r.VerdictDetail}')

    fig_vs_emcts(res, budgets)
    fig_factorial(fac, budgets)
    write_report(res, fac, clus, act, budgets)

    print(f'  wrote {OUT}/  (SIGNIFICANCE_V6.md, 3 CSVs, 2 figures)')
    if not clus.empty:
        print(f'  CI inflation from clustering: median '
              f'{clus.ci_inflation_factor.median():.1f}x, max {clus.ci_inflation_factor.max():.1f}x')


if __name__ == '__main__':
    main()
