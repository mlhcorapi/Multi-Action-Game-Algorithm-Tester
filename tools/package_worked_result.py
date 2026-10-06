#!/usr/bin/env python3
"""Package the worked result's full imported trees, anytime curves included, as one downloadable zip.

The repository keeps `turns.csv` per leaf (`games/tribes/results/ab-emcts/`). The per-burn anytime curves
(`curve.csv`, about 28 MB compressed) are left out of it and published as an asset beside it instead
(DESIGN §17, decision 7). This script builds that asset from the thesis line's stage bundles:

    python tools/package_worked_result.py --stage-e ~/Downloads/stageE.zip --stage-f ~/Downloads/stageF.zip

1. re-imports stage E (development) and stage F (holdout) with `import-legacy`, curves included, with
   the same specs, weights and labels as the repository's copy;
2. checks that every leaf's `turns.csv` is byte-identical to the repository's, so the asset is the
   published trees plus their curves and nothing else;
3. zips the two trees with a short README into `<data>/bundles/<name>.zip`, prints its size and sha256,
   and prints the command that would publish it. It uploads nothing: publishing is the owner's step.
"""
import argparse
import hashlib
import os
import pathlib
import shutil
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
from magat_analysis import legacy  # noqa: E402  (the analysis package, from this checkout)

RESULT = ROOT / "games" / "tribes" / "results" / "ab-emcts"
WEIGHTS = ROOT / "games" / "tribes" / "calibration" / "r7615" / "compute_weights.properties"
REPO = "mlhcorapi/Multi-Action-Game-Algorithm-Tester"
STAGES = (
    ("dev", "stage-e", "stageE.zip (R7615, stages D and E, 2026-08-29 to 2026-08-31)", "ab-emcts-dev.json"),
    ("holdout", "stage-f", "stageF.zip (R7615, stage F, 2026-09-02)", "ab-emcts-holdout.json"),
)
ASSET_README = """AB-EMCTS worked result: the full imported trees, anytime curves included
=========================================================================

The thesis line's stage E (development) and stage F (holdout) run trees, imported into MAGAT's leaf
layout by `magat import-legacy`, exactly as games/tribes/results/ab-emcts/ in the repository, plus every
leaf's curve.csv: one row per bridge-burn phase of every turn (20 per turn), the best value found so far
and the forward-model calls and units spent by then.

    ab-emcts/dev/<arm>/<budget>cu/{turns.csv, curve.csv, _leaf.json}      51 leaves, 74,000 turns
    ab-emcts/holdout/<arm>/<budget>cu/{turns.csv, curve.csv, _leaf.json}  16 leaves, 22,400 turns

Every turns.csv is byte-identical to the repository's. With the curves present, `magat analyze` fills its
anytime section (#9):

    python magat.py analyze --run <unzipped>/ab-emcts --out <dir>

A thesis result re-analysed by MAGAT; see docs/results/tribes-ab-emcts.md in the repository. The Tribes
engine is the work of its authors (see the repository's README).
"""


def stage_tree(src: pathlib.Path, split: str, tmp: pathlib.Path) -> pathlib.Path:
    """The archived `stage1/<split>` directory inside a stage bundle (a zip, or a directory it was unzipped to)."""
    if src.is_file() and zipfile.is_zipfile(src):
        dest = tmp / src.stem
        with zipfile.ZipFile(src) as z:
            members = [m for m in z.namelist() if m.startswith(f"stage1/{split}/")]
            if not members:
                sys.exit(f"{src} holds no stage1/{split}/ tree")
            z.extractall(dest, members)
        return dest / "stage1" / split
    for cand in (src / "stage1" / split, src):
        if (cand / "_split.txt").is_file():
            return cand
    sys.exit(f"{src} is neither a stage bundle nor a directory holding stage1/{split}/")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stage-e", required=True, help="stageE.zip, or the directory it was unzipped to")
    ap.add_argument("--stage-f", required=True, help="stageF.zip, or the directory it was unzipped to")
    ap.add_argument("--out", help="where the zip goes (default: <data>/bundles)")
    ap.add_argument("--name", default="ab-emcts-trees-with-curves-v1")
    ap.add_argument("--tag", default="worked-result-ab-emcts-v1", help="the release tag the printed command uses")
    a = ap.parse_args(argv)

    data = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    out_dir = pathlib.Path(a.out).resolve() if a.out else data / "bundles"
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="magat-package-") as t:
        tmp = pathlib.Path(t)
        run = tmp / "ab-emcts"
        for split, flag, label, spec in STAGES:
            tree = stage_tree(pathlib.Path(getattr(a, flag.replace("-", "_"))).expanduser(), split, tmp)
            print(f"[package] importing {split} from {tree}")
            rc = legacy.import_legacy(tree, run, split=split, experiment=ROOT / "experiments" / "tribes" / spec,
                                      weights=WEIGHTS, curve=True, label=label, echo=lambda *_: None)
            if rc != 0:
                print(f"[package] import of {split} failed (exit {rc})")
                return rc

        # The asset must be the published trees plus curves: every turns.csv equal, byte for byte.
        mismatched, compared, curves = [], 0, 0
        for mine in sorted(run.glob("*/*/*cu/turns.csv")):
            rel = mine.relative_to(run)
            published = RESULT / rel
            compared += 1
            if not published.is_file() or published.read_bytes() != mine.read_bytes():
                mismatched.append(str(rel))
            if (mine.parent / "curve.csv").is_file():
                curves += 1
        if mismatched or compared != 67:
            print(f"[package] REFUSED: {compared} leaves imported, {len(mismatched)} differ from the repository's: {mismatched[:5]}")
            return 1
        print(f"[package] {compared} leaves, every turns.csv identical to the repository's; {curves} with curves")

        (run / "README.txt").write_text(ASSET_README, encoding="utf-8")
        zpath = out_dir / f"{a.name}.zip"
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for f in sorted(p for p in run.rglob("*") if p.is_file()):
                z.write(f, str(pathlib.PurePosixPath("ab-emcts", *f.relative_to(run).parts)))
        shutil.rmtree(run, ignore_errors=True)

    sha = hashlib.sha256(zpath.read_bytes()).hexdigest()
    size = zpath.stat().st_size / 1e6
    print(f"PACKAGE: {zpath} ({size:.1f} MB, sha256 {sha})")
    print("\nNothing was uploaded. To publish it as a release asset (this creates the tag and the release):\n")
    print(f'  gh release create {a.tag} "{zpath}" --repo {REPO} \\')
    print('      --title "AB-EMCTS worked result: full imported trees with anytime curves" \\')
    print(f'      --notes "games/tribes/results/ab-emcts plus every leaf\'s curve.csv. sha256 {sha}"')
    print("\nor attach it to a release that already exists:\n")
    print(f'  gh release upload <tag> "{zpath}" --repo {REPO}')
    return 0


if __name__ == "__main__":
    sys.exit(main())
