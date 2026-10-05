"""Scenario-clustered statistics, moved from the thesis's analyze_stage1.py.

Aggregation rule everywhere: a statistic per pair (or per turn), then its mean with a bootstrap CI
that resamples SCENARIOS, the natural cluster: the visits of one scenario share a position, so they
are not independent observations. No scipy.

Two changes from the thesis code, neither of which changes an estimate:

- The resampled mean is computed from per-scenario sums and counts (a resample's concatenated mean is
  its total over its count), so 4,000 resamples cost one matrix product rather than 4,000 concatenations.
- Every row draws from its own generator, seeded from the row's identity (table, split, budget, arms,
  stratum). The thesis drew every row from one global stream, so a row's interval depended on which
  rows were computed before it; here re-running the analysis, or adding an arm, leaves every other
  row's interval exactly where it was.
"""
import hashlib

import numpy as np
import pandas as pd

N_BOOT = 4000
BASE_SEED = 20260823          # the thesis analysis's seed, kept as the base of every row's stream


def rng_for(*key) -> np.random.Generator:
    """A generator seeded from a row's identity: the same row gets the same resamples on every run."""
    h = hashlib.sha256("|".join(str(k) for k in key).encode("utf-8")).digest()
    return np.random.default_rng([BASE_SEED, int.from_bytes(h[:8], "little")])


def cluster_table(df: pd.DataFrame, cols, cluster_col: str = "scenario"):
    """Per-cluster sums of `cols` (rows with a missing value in any of them dropped) and counts."""
    v = df[[cluster_col, *cols]].dropna()
    if v.empty:
        return np.empty((0, len(cols))), np.empty(0), v
    g = v.groupby(cluster_col)
    return g[list(cols)].sum().to_numpy(float), g.size().to_numpy(float), v


def resampled_means(sums: np.ndarray, counts: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    """Means of every column over n cluster resamples: an (n, columns) array."""
    k = len(counts)
    idx = rng.integers(0, k, size=(n, k))
    return sums[idx].sum(axis=1) / counts[idx].sum(axis=1)[:, None]


def cluster_boot_ci(df: pd.DataFrame, value_col: str, cluster_col: str = "scenario", n: int = N_BOOT, key=()):
    """Mean and bootstrap 95% CI resampling clusters: (mean, lo, hi, clusters)."""
    sums, counts, v = cluster_table(df, [value_col], cluster_col)
    if v.empty:
        return np.nan, np.nan, np.nan, 0
    means = resampled_means(sums, counts, n, rng_for(value_col, *key))[:, 0]
    return (float(v[value_col].mean()), float(np.quantile(means, 0.025)), float(np.quantile(means, 0.975)),
            len(counts))


def quantiles(x: np.ndarray):
    """The 2.5% and 97.5% points of a bootstrap distribution, ignoring resamples where it is undefined."""
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if x.size == 0:
        return np.nan, np.nan
    return float(np.quantile(x, 0.025)), float(np.quantile(x, 0.975))


def cohens_d(diff: pd.Series) -> float:
    """Paired Cohen's d: the mean of the paired differences over their standard deviation.

    It treats pairs as independent, which they are not (visits share a scenario), so it is a
    standardised size for comparison across studies, never a test; the report says so beside it."""
    d = diff.dropna()
    if len(d) < 2:
        return np.nan
    sd = float(d.std(ddof=1))
    return float(d.mean()) / sd if sd > 0 else np.nan


# --------------------------------------------------------------------------- variance design
# How much precision does a scenario buy, and how much does a visit? (The thesis's Gate B question 2.)
#
# Visits of one scenario share a state, so they are not independent observations. Under the
# scenario-clustered bootstrap the standard error of a paired mean is
#
#     SE(n_c, v)^2  =  sigma_b^2 / n_c  +  sigma_w^2 / (n_c * v)
#
# Only the first term falls when scenarios are added; visits attack only the second. Two consequences
# follow, and both depend on the ratio between the two variances, which is why it is measured:
#
#   1. For a FIXED number of turns T = n_c * v, SE^2 = (v * sigma_b^2 + sigma_w^2) / T, which is
#      increasing in v whenever sigma_b^2 > 0. Fewer visits and more scenarios is then always better,
#      for any positive between-scenario variance.
#   2. With the scenario count capped by the portfolio, extra visits approach a floor of
#      sqrt(sigma_b^2 / n_c) and cannot go below it however many are run.

def variance_components(df: pd.DataFrame, value_col: str = "diff", cluster_col: str = "scenario"):
    """Unbalanced one-way random-effects decomposition of a paired difference."""
    v = df[[cluster_col, value_col]].dropna()
    k = v[cluster_col].nunique()
    n = len(v)
    if k < 2 or n <= k:
        return None
    grand = v[value_col].mean()
    g = v.groupby(cluster_col)[value_col]
    sizes = g.size().to_numpy(float)
    means = g.mean().to_numpy(float)
    ssb = float((sizes * (means - grand) ** 2).sum())
    ssw = float(((v[value_col] - v[cluster_col].map(g.mean())) ** 2).sum())
    msb, msw = ssb / (k - 1), ssw / (n - k)
    # n_0 is the effective group size; it reduces to v exactly when the design is balanced.
    n0 = (n - (sizes ** 2).sum() / n) / (k - 1)
    sw2 = msw
    sb2 = max(0.0, (msb - msw) / n0) if n0 > 0 else 0.0
    total = sb2 + sw2
    return dict(Pairs=n, Clusters=k, MeanVisits=n / k,
                SigmaB2=sb2, SigmaW2=sw2, ICC=(sb2 / total if total > 0 else np.nan),
                SE=float(np.sqrt(sb2 / k + sw2 / n)))


def design_advice(c: dict) -> dict:
    """Floor, distance from it, and what the visits were worth in scenarios."""
    k, vbar, sb2, sw2 = c["Clusters"], c["MeanVisits"], c["SigmaB2"], c["SigmaW2"]
    out = dict(SEFloor=float(np.sqrt(sb2 / k)) if sb2 > 0 else 0.0)
    if sb2 <= 0:
        # No detectable between-scenario variance: visits are as good as scenarios, and the ceiling
        # on precision is only the total turn count.
        out.update(ExcessOverFloorPct=np.nan, VisitsWithin5Pct=np.nan,
                   EquivClustersAtV1=np.nan, TurnsUsed=c["Pairs"], TurnsEquiv=np.nan)
        return out
    ratio = sw2 / sb2
    out["ExcessOverFloorPct"] = 100.0 * (np.sqrt(1 + ratio / vbar) - 1)
    out["VisitsWithin5Pct"] = float(np.ceil(ratio / (1.05 ** 2 - 1)))
    # Scenarios at one visit each that would match this design's precision.
    equiv = k * (sb2 + sw2) / (sb2 + sw2 / vbar)
    out["EquivClustersAtV1"] = float(equiv)
    out["TurnsUsed"] = c["Pairs"]
    out["TurnsEquiv"] = float(equiv)
    return out
