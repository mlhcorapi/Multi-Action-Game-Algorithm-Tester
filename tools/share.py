#!/usr/bin/env python3
"""Share a result, and take in someone else's: one file that carries a run, the currency it was priced in, its spec
and its report, with a manifest of hashes, and the checks that it is what it says and reproduces here.

    python magat.py export <experiment | run directory> [--name NAME] [--no-curves] [--out FILE]
    python magat.py export --currency <name | weights file> [--name NAME] [--out FILE]
    python magat.py import <file.magat.zip> [--name NAME] [--replay N] [--no-check]
    python magat.py import --list

An experiment is found by its name under <data>/<game>/runs/; a run directory is a run root (holding split trees)
or one split tree, such as a published one (games/tribes/results/reference/r7615/all). The package is
<data>/exports/<name>.magat.zip, laid out as the repository publishes results:

    MANIFEST.json                     what it holds, where it came from, and every file's sha256
    README.md                         the same, to read, with the commands to check it and compare with it
    calibration/<currency>/           the currency: weights, operations, profiles, its record's summary
    experiments/<experiment>.json     the spec, byte for byte, so its hash is the one the run recorded
    results/<name>/<split>/           the run tree (without seqlogs; --no-curves leaves the curves out)
    results/<name>/report/            the analysis of the tree as exported, made afresh

Import refuses a package whose files do not match its manifest, or that names a file outside its own folder, and
unpacks it into <data>/imports/<name>/. Then, unless --no-check:
- every leaf re-plays N of its turns here (--replay, 2), field for field, in the package's currency;
- the report's paired means are derived again from the turns.
IMPORT.md there holds the verdict. An imported currency is then found by its name (--weights <name>), an imported
run's currency and spec by their hashes, and `experiment run` takes an imported run's leaves where it may, as it does
the published run's (docs/SHARING.md).

Exit codes: 0 verified (or imported unchecked with --no-check), 1 a check failed (the package stays imported, its
verdict says why), 2 refused or a usage error.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
MAGAT = ROOT / "magat.py"
FORMAT = 1
EXCLUDE = ("seqlog_", "mutlog_", "_running.lock")
CURRENCY_FILES = ("compute_weights.properties", "compute_operations.csv", "compute_profiles.csv", "threads.json",
                  "thread_sweep.csv", "ORIGIN.md", "record/SUMMARY.md", "record/policy.json")
# The headline's columns that follow from the turns alone, without resampling: what an import derives again.
DERIVED = ["Split", "Budget", "Arm", "Baseline", "Stratum", "Pairs", "Scenarios", "MeanDiff", "Wins", "Losses", "Ties"]


def launcher():
    sys.path.insert(0, str(ROOT))
    try:
        import magat
    finally:
        sys.path.pop(0)
    return magat


def data_root() -> pathlib.Path:
    return pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9._-]+", "-", text.lower()).strip("-.")
    return s or "magat"


def magat(*args, capture=True) -> subprocess.CompletedProcess:
    """One launcher command, its output captured (the launcher logs it too)."""
    return subprocess.run([PY, str(MAGAT), *map(str, args)], capture_output=capture, text=True, encoding="utf-8",
                          errors="replace")


# =========================================================================== export

def split_trees(path: pathlib.Path) -> list:
    """The split trees a run directory holds: itself when it is one, else its children that are."""
    if (path / "_run.json").is_file():
        return [path]
    return sorted(d for d in path.iterdir() if d.is_dir() and (d / "_run.json").is_file()) if path.is_dir() else []


def currencies_by_hash(game: str) -> dict:
    """Every currency this machine can see, by its weights file's sha256: published, measured here, imported."""
    data = data_root()
    found = {}
    for pattern in (ROOT.glob(f"games/{game}/calibration/*/compute_weights.properties"),
                    (data / "calibration").glob("*/compute_weights.properties"),
                    (data / "imports").glob("*/calibration/*/compute_weights.properties")):
        for f in sorted(pattern):
            found.setdefault(sha256(f), f)
    return found


def find_currency(run: dict, game: str) -> pathlib.Path:
    w = run.get("weights") or {}
    sha, recorded = w.get("sha256"), w.get("path")
    if recorded and pathlib.Path(recorded).is_file() and sha256(pathlib.Path(recorded)) == sha:
        return pathlib.Path(recorded)
    f = currencies_by_hash(game).get(sha)
    if f is None:
        raise SystemExit(f"the currency this run was priced in (sha256 {str(sha)[:12]}, recorded at {recorded}) is not on this "
                         "machine: not where it was recorded, and not among the published, measured or imported currencies")
    return f


def find_spec(run: dict, game: str):
    e = run.get("experiment") or {}
    sha, recorded = e.get("sha256"), e.get("path")
    if recorded and pathlib.Path(recorded).is_file() and sha256(pathlib.Path(recorded)) == sha:
        return pathlib.Path(recorded)
    for f in sorted([*(ROOT / "experiments" / game).rglob("*.json"), *(data_root() / "imports").glob("*/experiments/*.json")]):
        if sha and sha256(f) == sha:
            return f
    return None


def currency_name(weights: pathlib.Path, game: str) -> str:
    """A currency's name: its folder's, less the <game>- prefix calibrate-controlled gives a folder of its own."""
    name = weights.parent.name
    return name[len(game) + 1:] if name.startswith(game + "-") else name


def header(weights: pathlib.Path) -> dict:
    out = {}
    for line in weights.read_text(encoding="utf-8", errors="replace").splitlines():
        k, sep, v = line.partition("=")
        if sep and not k.strip().startswith(("#", "profile.", "global.")):
            out[k.strip()] = v.strip()
    return out


def copy_currency(weights: pathlib.Path, dest: pathlib.Path) -> list:
    copied = []
    for rel in CURRENCY_FILES:
        f = weights.parent / rel
        if f.is_file():
            (dest / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, dest / rel)
            copied.append(rel)
    return copied


def copy_tree(src: pathlib.Path, dest: pathlib.Path, curves: bool) -> None:
    for f in sorted(src.rglob("*")):
        if not f.is_file() or any(x in f.name for x in EXCLUDE) or (not curves and f.name.startswith("curve.csv")):
            continue
        (dest / f.relative_to(src)).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(f, dest / f.relative_to(src))


def leaf_summary(tree: pathlib.Path) -> list:
    out = []
    for meta in sorted(tree.glob("*/*cu/_leaf.json")):
        m = read_json(meta)
        turns = meta.parent / "turns.csv"
        rows = max(0, sum(1 for _ in turns.open(encoding="utf-8")) - 1) if turns.is_file() else 0
        entry = dict(arm=m.get("arm"), budget=m.get("budget"), turns=rows, status=m.get("status"), commit=m.get("commit"))
        if m.get("reused"):
            entry["reusedFrom"] = (m["reused"] or {}).get("machine")
        out.append(entry)
    return out


def export(a) -> int:
    game, data = a.game, data_root()
    stage = pathlib.Path(tempfile.mkdtemp(prefix="magat-export-"))
    try:
        if a.currency:
            weights = launcher().currency(a.currency, game)
            cname = currency_name(weights, game)
            name = slug(a.name or cname)
            copy_currency(weights, stage / "calibration" / cname)
            manifest = dict(kind="magat-currency", currencies=[currency_entry(weights, cname)], runs=[], experiment=None, report=None)
        else:
            target = pathlib.Path(a.what)
            root = target if target.is_dir() else data / game / "runs" / a.what
            trees = split_trees(root)
            if not trees:
                print(f"no run at {root}: give an experiment's name (a run under {data / game / 'runs'}) or a run directory",
                      file=sys.stderr)
                return 2
            runs = [read_json(t / "_run.json") for t in trees]
            exp = (runs[0].get("experiment") or {}).get("name") or root.name
            weights = {find_currency(r, game) for r in runs}
            if len(weights) > 1:
                print(f"the run's splits were priced in {len(weights)} currencies; export them one split at a time", file=sys.stderr)
                return 2
            weights = weights.pop()
            cname = currency_name(weights, game)
            name = slug(a.name or f"{exp}-{cname}")
            copy_currency(weights, stage / "calibration" / cname)
            spec = find_spec(runs[0], game)
            if spec is not None:
                (stage / "experiments").mkdir(parents=True)
                shutil.copyfile(spec, stage / "experiments" / f"{exp}.json")
            else:
                print(f"  [warn]  the spec this run recorded ({(runs[0].get('experiment') or {}).get('path')}) is not on this "
                      "machine: the package goes without it, and its report without the spec's contrasts")
            res = stage / "results" / name
            for t in trees:
                copy_tree(t, res / t.name, curves=not a.no_curves)
            print(f"# analysing the run as exported, for its report")
            args = ["analyze", "--run", res, "--out", res / "report", *(["--experiment", stage / "experiments" / f"{exp}.json"] if spec else [])]
            p = magat(*args)
            if p.returncode != 0 or not (res / "report" / "SUMMARY.md").is_file():
                print(p.stdout[-3000:], file=sys.stderr)
                print("the analysis of the exported run failed: nothing was exported", file=sys.stderr)
                return 1
            first = runs[0]
            manifest = dict(
                kind="magat-results",
                currencies=[currency_entry(weights, cname)],
                experiment=None if spec is None else dict(name=exp, sha256=sha256(spec), path=f"experiments/{exp}.json"),
                runs=[dict(split=t.name, path=f"results/{name}/{t.name}", selection=r.get("selection", "all"),
                           currency_sha256=(r.get("weights") or {}).get("sha256"),
                           played_on=((r.get("invocations") or [{}])[0]).get("machine"),
                           magat=sorted({x.get("commit") for x in r.get("invocations") or [] if x.get("commit")}),
                           leaves=leaf_summary(res / t.name)) for t, r in zip(trees, runs)],
                report=f"results/{name}/report",
                portfolio=dict(identity=(first.get("portfolio") or {}).get("identity")),
                engine=dict(commit=((first.get("game") or {}).get("configuration") or {}).get("engine.commit")),
                configuration=(first.get("game") or {}).get("configuration") or {},
            )
        commit, _ = launcher().git_state()
        manifest = dict(format=FORMAT, name=name, game=game, created=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
                        exported_by=dict(magat=commit, machine=machine()), **manifest)
        files = {f.relative_to(stage).as_posix(): sha256(f) for f in sorted(stage.rglob("*")) if f.is_file()}
        manifest["files"] = files
        (stage / "MANIFEST.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8", newline="\n")
        (stage / "README.md").write_text(readme(manifest), encoding="utf-8", newline="\n")
        out = pathlib.Path(a.out) if a.out else data / "exports" / f"{name}.magat.zip"
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            for f in sorted(stage.rglob("*")):
                if f.is_file():
                    z.write(f, f.relative_to(stage).as_posix())
        os.replace(tmp, out)
        size = out.stat().st_size / 2 ** 20
        runs = manifest["runs"]
        print(f"EXPORT: {out} ({size:.1f} MB): {len(files)} files" + (
            f", the run results/{name} ({', '.join(r['split'] for r in runs)}: "
            f"{sum(l['turns'] for r in runs for l in r['leaves']):,} turns) in the currency {manifest['currencies'][0]['name']}"
            if runs else f", the currency {manifest['currencies'][0]['name']}"))
        print(f"Anyone can check it on their machine: python magat.py import {out.name}")
        return 0
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def currency_entry(weights: pathlib.Path, name: str) -> dict:
    h = header(weights)
    return dict(name=name, sha256=sha256(weights), path=f"calibration/{name}/compute_weights.properties",
                reference=h.get("reference"), referenceAdvanceNanos=h.get("referenceAdvanceNanos"), machine=h.get("machine"),
                measured=h.get("measured"), levels=h.get("levels"))


def machine() -> str:
    try:
        import platform
        return platform.processor() or platform.machine()
    except Exception:
        return "unknown"


def readme(m: dict) -> str:
    """The package's README: what it holds, and the commands to check it and use it."""
    c = m["currencies"][0]
    L = [f"# {m['name']}", "", f"A MAGAT package ({m['kind']}, format {m['format']}) for {m['game']}, exported {m['created']} "
         f"by MAGAT `{m['exported_by']['magat']}`.", ""]
    L += ["## The currency", "", f"`{c['name']}`: reference cell `{c['reference']}`, one unit = {c['referenceAdvanceNanos']} ns there; "
          f"measured {c.get('measured') or 'at an unrecorded time'} on {str(c.get('machine') or 'an unrecorded machine').split(' | ')[0]}; "
          f"levels {c.get('levels') or '-'}. Weights sha256 `{c['sha256']}`.", ""]
    if m["runs"]:
        e = m.get("experiment")
        L += ["## The run", "", f"Experiment `{e['name'] if e else '?'}`" + (f" (spec sha256 `{e['sha256'][:12]}...`)" if e else
              " (its spec was not on the exporting machine)") + f"; portfolio `{str(m['portfolio']['identity'])[:12]}...`; engine "
              f"`{str(m['engine']['commit'])[:7]}`.", ""]
        for r in m["runs"]:
            L += [f"Split `{r['split']}`, positions {r['selection']}, played on {str(r['played_on']).split(' | ')[0]} by MAGAT "
                  f"{', '.join(f'`{x[:7]}`' for x in r['magat']) or '?'}:", "", "| Arm | Budget | Turns | Status |", "|---|---|---|---|"]
            L += [f"| {l['arm']} | {l['budget']:,} | {l['turns']:,} | {l['status']}" + (" (reused)" if l.get("reusedFrom") else "") + " |"
                  for l in r["leaves"]]
            L += [""]
        L += [f"The report is `{m['report']}/SUMMARY.md`.", ""]
    imp = f"$HOME/magat_runs/imports/{m['name']}"
    L += ["## Using it", "", "Import it: the files are checked against this manifest, unpacked under the data root, and every leaf "
          "re-plays a sample of its turns on your machine, field for field.", "", "```bash", f"python magat.py import {m['name']}.magat.zip",
          "```", "", f"Then spend its currency, by name, in an experiment of your own:", "", "```bash",
          f"python magat.py experiment run <your spec> --weights {c['name']}", "```", ""]
    if m["runs"]:
        split = m["runs"][0]["split"]
        L += ["In that currency, `experiment run` takes this run's leaves where it may instead of playing them again, so an "
              "algorithm of yours, priced into a copy of the currency, is the only one played. Or compare a run of yours with "
              "this one, side by side and position by position:", "", "```bash",
              f"python magat.py analyze --run <your run> --compare {imp}/results/{m['name']}/{split} --out <report folder>", "```", ""]
    L += ["`docs/SHARING.md` in the MAGAT repository says more.", ""]
    return "\n".join(L)


# =========================================================================== import

def safe(name: str) -> bool:
    p = pathlib.PurePosixPath(name)
    return not (name.startswith(("/", "\\")) or "\\" in name or ":" in name or any(part in ("..", "") for part in p.parts))


def verify(z: zipfile.ZipFile):
    """(manifest, problems): every file the manifest names is there with its hash, and nothing else is."""
    names = [n for n in z.namelist() if not n.endswith("/")]
    bad = [n for n in names if not safe(n)]
    if bad:
        return None, [f"names a file outside its own folder: {bad[0]}"]
    if "MANIFEST.json" not in names:
        return None, ["holds no MANIFEST.json: it is not a MAGAT package"]
    try:
        m = json.loads(z.read("MANIFEST.json").decode("utf-8"))
    except ValueError as e:
        return None, [f"MANIFEST.json is not JSON: {e}"]
    if m.get("format") != FORMAT or m.get("kind") not in ("magat-results", "magat-currency"):
        return m, [f"is format {m.get('format')} of kind {m.get('kind')}; this MAGAT reads format {FORMAT}"]
    files = m.get("files") or {}
    problems = []
    for rel, digest in files.items():
        if not safe(rel):
            problems.append(f"its manifest names a file outside its folder: {rel}")
        elif rel not in names:
            problems.append(f"{rel} is missing")
        elif hashlib.sha256(z.read(rel)).hexdigest() != digest:
            problems.append(f"{rel} does not match its hash in the manifest")
    extra = [n for n in names if n not in files and n not in ("MANIFEST.json", "README.md")]
    problems += [f"{n} is not in the manifest" for n in extra]
    return m, problems


def published_identity(game: str):
    f = ROOT / "games" / game / "portfolio" / "v1" / "identity.txt"
    return f.read_text(encoding="utf-8").split()[0] if f.is_file() else None


def do_import(a) -> int:
    data, src = data_root(), pathlib.Path(a.what)
    if not src.is_file():
        print(f"no file {src}", file=sys.stderr)
        return 2
    with zipfile.ZipFile(src) as z:
        m, problems = verify(z)
        if problems:
            print(f"IMPORT: REFUSED - {src.name} " + ("; ".join(problems[:5]) + (f"; and {len(problems) - 5} more" if len(problems) > 5 else "")))
            return 2
        if not (ROOT / "games" / str(m.get("game"))).is_dir():
            print(f"IMPORT: REFUSED - {src.name} is for the game {m.get('game')}, which this repository does not have")
            return 2
        name = slug(a.name or m["name"])
        dest = data / "imports" / name
        if dest.exists():
            if (dest / "MANIFEST.json").is_file() and (dest / "MANIFEST.json").read_bytes() == z.read("MANIFEST.json"):
                print(f"# {name} is imported already, from the same package: checking it again")
            else:
                print(f"IMPORT: REFUSED - {dest} holds another package of that name: import this one with --name <another name>")
                return 2
        else:
            tmp = dest.with_name(dest.name + ".partial")
            shutil.rmtree(tmp, ignore_errors=True)
            z.extractall(tmp)
            os.replace(tmp, dest)
    print(f"# {src.name}: {len(m['files'])} files, every one as its manifest says -> {dest}")

    lines, failed, warned = [], 0, 0
    game = m.get("game")
    if m["kind"] == "magat-results":
        ident, mine = (m.get("portfolio") or {}).get("identity"), published_identity(game)
        if ident != mine:
            lines.append(f"- [warn] played on portfolio {str(ident)[:12]}, not the published one ({str(mine)[:12]}): its turns are replayed "
                         "by scenario id on the published portfolio")
            warned += 1
        engine, here = (m.get("engine") or {}).get("commit"), launcher().engine_commit(game)
        if engine != here:
            lines.append(f"- [warn] played on engine {str(engine)[:7]}; this checkout's is {str(here)[:7]}: turns may not replay")
            warned += 1
    commit, _ = launcher().git_state()
    if (m.get("exported_by") or {}).get("magat") != commit:
        lines.append(f"- [info] exported by MAGAT {str((m.get('exported_by') or {}).get('magat'))[:12]}; this checkout is {commit[:12]}")

    replayed = derived = None
    if not a.no_check and m["kind"] == "magat-results":
        weights = dest / m["currencies"][0]["path"]
        logs = dest / "checks"
        logs.mkdir(exist_ok=True)
        replayed = [0, 0]
        for run in m["runs"]:
            for leaf in sorted((dest / run["path"]).glob("*/*cu")):
                if not (leaf / "turns.csv").is_file():
                    continue
                p = magat("check", "replay", "--game", game, "--leaf", leaf, "--weights", weights, "--turns", a.replay)
                (logs / f"replay-{run['split']}-{leaf.parent.name}-{leaf.name}.log").write_text(p.stdout + p.stderr, encoding="utf-8")
                ok = "REPLAY: PASS" in p.stdout
                replayed[0] += 1
                replayed[1] += int(ok)
                print(f"  [{'ok' if ok else 'FAIL'}] {run['split']} {leaf.parent.name}@{leaf.name[:-2]}: {a.replay} turn(s) re-played here"
                      + (" field for field" if ok else f": read {logs.name}/replay-{run['split']}-{leaf.parent.name}-{leaf.name}.log"))
        if replayed[1] < replayed[0]:
            failed += 1
        lines.append(f"- [{'ok' if replayed[0] == replayed[1] else 'FAIL'}] replay: {replayed[1]} of {replayed[0]} leaves re-played "
                     f"{a.replay} turn(s) here, field for field, in the package's currency")
        derived = consistency(m, dest, logs)
        if derived is not None:
            lines.append(f"- [{'ok' if derived[0] else 'FAIL'}] report: {derived[1]}")
            failed += int(not derived[0])
    elif a.no_check:
        lines.append("- [info] not checked (--no-check)")

    verdict = ("IMPORTED" if m["kind"] == "magat-currency" else "IMPORTED, NOT CHECKED" if a.no_check
               else "CHECKS FAILED" if failed else "VERIFIED")
    write_record(dest, m, src, verdict, lines)
    for line in lines:
        print("  " + line[2:])
    print(f"IMPORT: {verdict} - {dest}")
    print(next_steps(m, dest))
    return 1 if failed else 0


def consistency(m: dict, dest: pathlib.Path, logs: pathlib.Path):
    """(ok, what) for the report's paired means derived again from the turns, or None when the package has no report."""
    report = dest / (m.get("report") or "")
    if not m.get("report") or not (report / "tables" / "headline.csv").is_file():
        return None
    import csv
    spec = dest / m["experiment"]["path"] if m.get("experiment") else None
    out = pathlib.Path(tempfile.mkdtemp(prefix="magat-import-"))
    try:
        p = magat("analyze", "--run", report.parent, "--out", out, "--boot", "50", "--no-figures",
                  *(["--experiment", spec] if spec else []))
        (logs / "analyze.log").write_text(p.stdout + p.stderr, encoding="utf-8")
        if not (out / "tables" / "headline.csv").is_file():
            return False, "the analysis of the imported turns failed (checks/analyze.log)"

        def rows(f):
            with f.open(encoding="utf-8") as fh:
                return {tuple(r[c] for c in DERIVED[:5]): r for r in csv.DictReader(fh)}

        theirs, mine = rows(report / "tables" / "headline.csv"), rows(out / "tables" / "headline.csv")
        differ = [k for k in theirs if k not in mine or any(not close(theirs[k][c], mine[k][c]) for c in DERIVED[5:])]
        if differ or set(mine) != set(theirs):
            return False, (f"{len(differ)} of the report's {len(theirs)} headline rows do not follow from the turns, e.g. "
                           f"{' '.join(differ[0]) if differ else 'a row the report lacks'}")
        return True, f"the report's {len(theirs)} paired means and win counts follow from the turns"
    finally:
        shutil.rmtree(out, ignore_errors=True)


def close(a: str, b: str) -> bool:
    try:
        x, y = float(a), float(b)
    except ValueError:
        return a == b
    return abs(x - y) <= 1e-6 * max(1.0, abs(x), abs(y))


def write_record(dest: pathlib.Path, m: dict, src: pathlib.Path, verdict: str, lines: list) -> None:
    L = [f"# Import of {m['name']}", "", f"- Verdict: {verdict}", f"- From: `{src}`, imported "
         f"{datetime.datetime.now().isoformat(timespec='seconds')}", f"- Package: {m['kind']}, exported {m['created']} by MAGAT "
         f"`{(m.get('exported_by') or {}).get('magat')}`", "", "## Checks", "", *lines, "", "## Next", "", next_steps(m, dest)]
    (dest / "IMPORT.md").write_text("\n".join(L) + "\n", encoding="utf-8")


def next_steps(m: dict, dest: pathlib.Path) -> str:
    c = m["currencies"][0]["name"]
    s = [f"Its currency is found by name: --weights {c}"]
    if m["kind"] == "magat-results":
        tree = dest / m["runs"][0]["path"]
        s += [f"Compare a run of yours with it: python magat.py analyze --run <your run> --compare {tree} --out <folder>",
              f"In its currency, experiment run takes its leaves where it may, as it does the published run's"]
    return "\n".join(s)


def list_imports() -> int:
    root = data_root() / "imports"
    found = sorted(d for d in root.iterdir() if (d / "MANIFEST.json").is_file()) if root.is_dir() else []
    if not found:
        print(f"nothing imported under {root}")
        return 0
    for d in found:
        m = read_json(d / "MANIFEST.json")
        verdict = next((l[len("- Verdict: "):] for l in (d / "IMPORT.md").read_text(encoding="utf-8").splitlines()
                        if l.startswith("- Verdict: ")), "?") if (d / "IMPORT.md").is_file() else "?"
        runs = ", ".join(f"{r['split']} ({sum(l['turns'] for l in r['leaves']):,} turns)" for r in m.get("runs") or [])
        print(f"  {d.name:40} {verdict:22} currency {m['currencies'][0]['name']}" + (f"; runs {runs}" if runs else ""))
    return 0


def main(argv) -> int:
    if not argv or argv[0] not in ("export", "import"):
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    ap = argparse.ArgumentParser(prog=f"magat {cmd}")
    ap.add_argument("what", nargs="?")
    ap.add_argument("--name")
    ap.add_argument("--game", default="tribes")
    if cmd == "export":
        ap.add_argument("--currency", help="export a currency alone: a published, measured or imported one's name, or a weights file")
        ap.add_argument("--no-curves", action="store_true", help="leave the runs' anytime curves out")
        ap.add_argument("--out", help="the package file (default <data>/exports/<name>.magat.zip)")
        a = ap.parse_args(rest)
        if not a.what and not a.currency:
            ap.error("give an experiment's name or a run directory, or --currency")
        return export(a)
    ap.add_argument("--replay", type=int, default=2, help="turns each leaf re-plays here (default 2)")
    ap.add_argument("--no-check", action="store_true", help="unpack and register it without re-playing anything")
    ap.add_argument("--list", action="store_true", help="what is imported, and each import's verdict")
    a = ap.parse_args(rest)
    if a.list:
        return list_imports()
    if not a.what:
        ap.error("give the package file")
    return do_import(a)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
