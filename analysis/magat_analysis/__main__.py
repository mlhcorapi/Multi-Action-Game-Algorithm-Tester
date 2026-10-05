"""python -m magat_analysis analyze | import-legacy  (the launcher's `magat analyze` and `magat import-legacy`).

    analyze        --run DIR [--holdout DIR] [--experiment SPEC] [--baseline ARM] [--reprice WEIGHTS]
                   --out DIR [--boot N] [--no-figures]
    import-legacy  --stage1-tree DIR --out RUN [--map tools/legacy-arms.json] [--portfolio DIR] [--split NAME]
                   [--experiment SPEC] [--weights FILE] [--no-curve] [--game tribes] [--label TEXT]

--run takes a split directory (<run>/<split>, holding <arm>/<budget>cu leaves) or a run directory whose
dev/ and holdout/ children are analysed side by side. Exit codes: 0 OK, 2 bad input, 5 identical arms.
"""
import argparse
import sys

from .stats import N_BOOT


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="magat", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("analyze", help="every measure, the report and report.json from a run tree")
    a.add_argument("--run", required=True)
    a.add_argument("--holdout")
    a.add_argument("--experiment")
    a.add_argument("--baseline")
    a.add_argument("--reprice")
    a.add_argument("--out", required=True)
    a.add_argument("--boot", type=int, default=N_BOOT)
    a.add_argument("--no-figures", action="store_true")
    i = sub.add_parser("import-legacy", help="a thesis stage1/<split> tree into MAGAT's leaf layout")
    i.add_argument("--stage1-tree", required=True)
    i.add_argument("--out", required=True)
    i.add_argument("--map")
    i.add_argument("--portfolio")
    i.add_argument("--split")
    i.add_argument("--experiment")
    i.add_argument("--weights")
    i.add_argument("--no-curve", action="store_true")
    i.add_argument("--game", default="tribes")
    i.add_argument("--label")
    x = ap.parse_args(argv)
    if x.cmd == "analyze":
        from .report import analyze
        return analyze(x.run, x.out, holdout=x.holdout, experiment=x.experiment, baseline=x.baseline,
                       reprice=x.reprice, n=x.boot, figures=not x.no_figures)
    from .legacy import import_legacy
    return import_legacy(x.stage1_tree, x.out, mapping=x.map, portfolio=x.portfolio, split=x.split,
                         experiment=x.experiment, weights=x.weights, curve=not x.no_curve, game=x.game, label=x.label)


if __name__ == "__main__":
    sys.exit(main())
