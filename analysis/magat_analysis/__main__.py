"""python -m magat_analysis analyze  (the launcher's `magat analyze`).

    analyze  --run DIR [--experiment SPEC] [--baseline ARM] [--reprice WEIGHTS] --out DIR [--boot N] [--no-figures]
             [--compare DIR [--compare-weights WEIGHTS]]

--run takes a split directory (<run>/<split>, holding <arm>/<budget>cu leaves) or a run directory whose
split children are each analysed. --compare takes the same experiment's run on another machine and sets
the two side by side, split by split: each machine's means, their difference, both budget multipliers,
the seconds per turn, and this run's turns re-priced in the other's currency (found from its provenance,
or named with --compare-weights). Exit codes: 0 OK, 2 bad input, 5 identical arms.
"""
import argparse
import sys

from .stats import N_BOOT


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="magat", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("analyze", help="every measure, the report and report.json from a run tree")
    a.add_argument("--run", required=True)
    a.add_argument("--experiment")
    a.add_argument("--baseline")
    a.add_argument("--reprice")
    a.add_argument("--compare")
    a.add_argument("--compare-weights")
    a.add_argument("--out", required=True)
    a.add_argument("--boot", type=int, default=N_BOOT)
    a.add_argument("--no-figures", action="store_true")
    x = ap.parse_args(argv)
    from .report import analyze
    return analyze(x.run, x.out, experiment=x.experiment, baseline=x.baseline, reprice=x.reprice, compare=x.compare,
                   compare_weights=x.compare_weights, n=x.boot, figures=not x.no_figures)


if __name__ == "__main__":
    sys.exit(main())
