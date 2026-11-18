#!/usr/bin/env python3
"""Shakedown: every moving part at toy size, in minutes, exiting non-zero on any failure.

It measures nothing. Every number it prints is too small to mean anything; the only question it
answers is "does this all work on this machine". Run before every pull request.

    python tools/shakedown.py [--game tribes]
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
PASS = 0
FAIL = 0


def step(title: str) -> None:
    print(f"\n=== {title}", flush=True)


def check(name: str, cmd: list, want: str = None, codes=(0,), then=None, env=None) -> None:
    """Run one command; it passes when it exits with one of `codes`, prints `want`, and `then()` holds."""
    global PASS, FAIL
    t0 = time.time()
    p = subprocess.run([str(c) for c in cmd], cwd=str(ROOT), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env)
    out = (p.stdout or "") + (p.stderr or "")
    ok = p.returncode in codes and (want is None or want in out)
    if ok and then is not None:
        try:
            ok = bool(then())
        except Exception as e:
            out += "\npost-condition raised " + repr(e)
            ok = False
    print(f"  [{'ok' if ok else 'FAIL'}]   {name}  ({time.time() - t0:.1f}s)", flush=True)
    if ok:
        PASS += 1
    else:
        FAIL += 1
        print("         command: " + " ".join(str(c) for c in cmd))
        for line in out.strip().splitlines()[-15:]:
            print("         " + line)


def main(argv) -> int:
    game = argv[argv.index("--game") + 1] if "--game" in argv else "tribes"
    print("############################################################")
    print(f"# MAGAT SHAKEDOWN   game={game}")
    print("# Toy sizes throughout. This checks that things RUN, not what they measure.")
    print("############################################################")

    step("0. Environment")
    check("java on PATH", ["java", "-version"])
    check("python has numpy and pandas (analysis), and matplotlib (the paper's figures)", [PY, "-c", "import numpy, pandas, matplotlib"])

    step("1. Build")
    check("core, adapter, engine and tests compile", [PY, ROOT / "magat.py", "build", "--tests"], "TEST BUILD OK")
    check("layering holds", [PY, ROOT / "magat.py", "layering"], "0 violation(s)")
    check("docs/ADDING_AN_ALGORITHM.md shows the compiled FirstAgent, verbatim",
          [PY, "-c", "import pathlib, sys; r = pathlib.Path(sys.argv[1]); "
                     "src = (r / 'core/src/magat/agents/first/FirstAgent.java').read_text(encoding='utf-8').replace(chr(13), '').strip(); "
                     "doc = (r / 'docs/ADDING_AN_ALGORITHM.md').read_text(encoding='utf-8').replace(chr(13), ''); "
                     "sys.exit(0 if '```java' + chr(10) + src + chr(10) + '```' in doc else 1)", ROOT])

    step("2. Unit tests")
    check("junit, then the analysis package: one line, everything else in the log",
          [PY, ROOT / "magat.py", "test"], "TESTS: PASS")

    step("3. Contract laws on published positions")
    check("L1-L10 pass", [PY, ROOT / "magat.py", "check", "contract", "--game", game, "--scenarios", "4"], "CONTRACT: PASS")

    step("4. Gates and probes: equivalence, audit")
    check("emcts and tbets reproduce the engine's agents at 8k on 2 positions",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "emcts,tbets",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")
    check("the AB-EMCTS arms reproduce the engine's agents at 8k on 2 positions; unpriced in the published currency, "
          "so at the measurement weights",
          [PY, ROOT / "magat.py", "check", "equivalence", "--game", game, "--arms", "@ab-emcts",
           "--scenarios", "2", "--visits", "1", "--budgets", "8000"], "EQUIVALENCE: PASS")
    check("audit: each arm's declared operations and forward model account for its turn (toy size)",
          [PY, ROOT / "magat.py", "check", "audit", "--game", game, "--arms", "@reference",
           "--budget", "4000", "--turns", "2", "--reps", "1", "--warmup", "1"], "AUDIT: PASS")
    # Each published run: this machine plays its turns field for field, in the currency it was priced in (found by hash).
    currencies = {hashlib.sha256(f.read_bytes()).hexdigest(): f
                  for f in (ROOT / "games" / game / "calibration").glob("*/compute_weights.properties")}
    for published in sorted((ROOT / "games" / game / "results" / "reference").glob("*/all")):
        priced_in = currencies.get(json.loads((published / "_run.json").read_text(encoding="utf-8"))["weights"]["sha256"])
        for leaf in sorted(published.glob("*/9500cu")):
            check(f"replay: 2 of the published {published.parent.name} turns of {leaf.parent.name}@9500 re-plan here field for field",
                  [PY, ROOT / "magat.py", "check", "replay", "--game", game, "--leaf", leaf, "--weights", priced_in, "--turns", "2"],
                  "REPLAY: PASS", then=lambda p=priced_in: p is not None)

    step("5. Portfolio: toy build, freeze, verify against the published portfolio")
    data = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    toy_dir = data / "shakedown" / f"portfolio-{game}"
    shutil.rmtree(toy_dir, ignore_errors=True)
    check("toy build (2 + 1 captures from one source game each) completes",
          [PY, ROOT / "magat.py", "portfolio", "build", "--game", game, "--out", toy_dir,
           "--targets", "single=2,dual=1", "--threads", "2"], "PORTFOLIO: COMPLETE")
    check("freeze writes the split files", [PY, ROOT / "magat.py", "portfolio", "freeze", "--portfolio", toy_dir], "FROZEN: ")
    check("every toy row equals the published row, and plans identically from both bins",
          [PY, ROOT / "magat.py", "portfolio", "verify", "--game", game, "--portfolio", toy_dir,
           "--published", ROOT / "games" / game / "portfolio" / "v1", "--subset", "--plan", "3"], "VERIFY: PASS")
    check("the published portfolio verifies against itself (bins, identity, split rule)",
          [PY, ROOT / "magat.py", "portfolio", "verify", "--game", game], "VERIFY: PASS")

    step("6. Machine, threads, calibration (toy sizes: gates reported, not enforced)")
    check("machine banner", [PY, ROOT / "magat.py", "machine"], "worker ceiling")
    calib = data / "shakedown" / f"calibration-{game}"
    shutil.rmtree(calib, ignore_errors=True)
    weights = calib / "compute_weights.properties"
    check("thread sweep writes threads.json",
          [PY, ROOT / "magat.py", "threads", "--game", game, "--out", calib, "--levels", "4000",
           "--ladder", "1,2", "--segment-seconds", "2"], "THREADS:", then=lambda: (calib / "threads.json").is_file())
    toy = ["--reps", "2", "--warmup", "1", "--toy"]
    check("--estimate times a few turns and says what the session would take, writing nothing",
          [PY, ROOT / "magat.py", "calibrate", "--estimate", "--game", game, "--out", calib, "--arms", "emcts",
           "--levels", "2000,4000", "--turns", "2", *toy],
          "ESTIMATE: ", then=lambda: not weights.is_file())
    check("calibrate writes a new (toy) currency, version 2: every reference algorithm's operations priced and audited",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "@reference", "--levels", "2000,4000",
           "--turns", "2", *toy],
          "=== AUDIT", then=lambda: weights.is_file() and "magat.weights.version = 2" in weights.read_text(encoding="utf-8")
          and all(f"profile.{op.split('.')[0]}@2000.w.{op}" in weights.read_text(encoding="utf-8")
                  for op in ("emcts.generation", "mcts.iteration", "oep.genome", "tbets.turn")))
    snapshot = {}
    check("pricing it again without --replace is refused",
          [PY, ROOT / "magat.py", "calibrate", "--game", game, "--out", calib, "--arms", "emcts", "--levels", "2000,4000", "--toy"],
          "--replace", codes=(2,),
          then=lambda: weights.read_text(encoding="utf-8") == snapshot.setdefault("w", weights.read_text(encoding="utf-8")))
    check("--check gates each cell's turn-price drift and writes nothing",
          [PY, ROOT / "magat.py", "calibrate", "--check", "--game", game, "--weights", weights, "--arms", "emcts",
           "--levels", "4000", *toy],
          "largest drift in a turn's price", codes=(0, 1), then=lambda: weights.read_text(encoding="utf-8") == snapshot["w"])
    check("the toy currency prices the reference arms at its own levels",
          [PY, ROOT / "magat.py", "weights", "--game", game, "--weights", weights, "--arms", "@reference"], "WEIGHTS: COVERED")
    check("a coverage probe says what is still to price and exits 0: progress, not a gate",
          [PY, ROOT / "magat.py", "weights", "--game", game, "--weights", weights, "--arms", "emcts", "--levels", "2000,4000,8000",
           "--progress"], "WEIGHTS: 1 TO PRICE")
    check("check time: whole turns under the toy currency, single-threaded, against a target, with each cell's spend",
          [PY, ROOT / "magat.py", "check", "time", "--game", game, "--weights", weights, "--arms", "@reference",
           "--levels", "2000,4000", "--targets", "100,200", "--positions", "3", "--warmup", "1",
           "--out", calib / "time.json"], "SPEND: ", codes=(0, 1),
          then=lambda: '"spend"' in (calib / "time.json").read_text(encoding="utf-8"))
    timed = data / "shakedown" / f"levels-{game}"
    shutil.rmtree(timed, ignore_errors=True)
    check("calibrate-time: probe, calibrate, time, rescale, round, levels.json (toy)",
          [PY, ROOT / "magat.py", "calibrate-time", "--game", game, "--toy", "--times", "300,600", "--positions", "3",
           "--round", "100", "--probe", "2000", "--max-iterations", "2", "--out", timed], "CALIBRATE-TIME: PASS",
          then=lambda: '"time_ms": 600' in (timed / "levels.json").read_text(encoding="utf-8"))
    controlled = data / "shakedown" / f"controlled-{game}"
    shutil.rmtree(controlled, ignore_errors=True)
    priced = controlled / "compute_weights.properties"
    cc = [PY, ROOT / "magat.py", "calibrate-controlled", "--name", "shakedown", "--game", game, "--out", controlled, "--toy",
          "--arms", "emcts", "--levels", "2000,4000", "--turns", "2", "--reps", "2", "--warmup", "1", "--soak-minutes", "0",
          "--interval", "2"]
    check("calibrate-controlled --preflight: the checklist and what it would measure; nothing measured", [*cc, "--preflight"],
          "PREFLIGHT: READY", then=lambda: not priced.is_file())
    check("one session: controls, stability check, the session, its verdict and the self-check (toy)", cc,
          "CONTROLLED CALIBRATION: COMPLETE",
          then=lambda: "profile.emcts@4000.w.advance" in priced.read_text(encoding="utf-8")
          and "- Verdict: COMPLETE" in (controlled / "record" / "SUMMARY.md").read_text(encoding="utf-8"))
    check("a currency that prices every arm is not measured again", cc, "nothing to measure")
    # The same options in a config file: the same currency, so again nothing to measure.
    cfg = data / "shakedown" / f"controlled-{game}.json"
    cfg.write_text(json.dumps({"name": "shakedown", "game": game, "out": str(controlled), "toy": True, "arms": ["emcts"],
                               "levels": [2000, 4000], "turns": "2", "reps": "2", "warmup": "1", "soak-minutes": 0,
                               "interval": 2}), encoding="utf-8")
    check("--config: the same options written in a file, the same currency, nothing to measure",
          [PY, ROOT / "magat.py", "calibrate-controlled", "--config", cfg], "nothing to measure",
          then=lambda: any((r / "config.json").is_file() for r in (controlled / "record").iterdir() if r.is_dir()))
    bad = data / "shakedown" / f"controlled-{game}-bad.json"
    bad.write_text(json.dumps({"name": "shakedown", "soak_minutes": 0, "checks": True, "levels": ["750ms"]}), encoding="utf-8")
    check("a config with a misspelt key, a mode and an unpublished level is refused, every problem named",
          [PY, ROOT / "magat.py", "calibrate-controlled", "--config", bad, "--preflight"], "did you mean 'soak-minutes'", codes=(2,))
    for example in sorted((ROOT / "calibrations" / game / "examples").glob("*.json")):
        check(f"the example config {example.name} reads, and its preflight says what it would measure",
              [PY, ROOT / "magat.py", "calibrate-controlled", "--config", example, "--out",
               data / "shakedown" / f"config-{example.stem}", "--preflight", "--toy"], "4 arm(s): emcts, mcts, oep, tbets")
    # --from: a currency started as a copy of the published one, which prices only the arms it lacks. Measuring into it
    # is a real session (a toy one may not merge into a real currency): here, the copy and what it would measure.
    published = ROOT / "games" / game / "calibration" / "r7615-time" / "compute_weights.properties"
    copied = data / "shakedown" / f"from-{game}"
    shutil.rmtree(copied, ignore_errors=True)
    cf = [PY, ROOT / "magat.py", "calibrate-controlled", "--name", "shakedown-from", "--game", game, "--out", copied,
          "--from", "r7615-time", "--arms", "@ab-emcts", "--toy", "--preflight"]
    check("--from: the currency starts as a byte copy of the published one, and would price only the arms it lacks", cf,
          "3 arm(s): emcts-comma, comma-z0-coarse, comma-z0-random, merged",
          then=lambda: (copied / "compute_weights.properties").read_bytes() == published.read_bytes())
    check("--from again: the currency there is that copy, kept", cf, "kept: it is that currency, extended")
    changed = copied / "compute_weights.properties"
    if changed.is_file():                                   # one published price changed
        changed.write_text(changed.read_text(encoding="utf-8").replace("w.copy = ", "w.copy = 1", 1), encoding="utf-8")
    check("--from refuses a currency whose published prices changed, naming the line", cf,
          "is not the published r7615-time extended", codes=(2,))
    older = data / "shakedown" / f"controlled-{game}-older"
    shutil.rmtree(older, ignore_errors=True)
    older.mkdir(parents=True)
    (older / "compute_weights.properties").write_text(
        priced.read_text(encoding="utf-8") + "profile.retired-arm@2000.w.advance = 1.0\n", encoding="utf-8")
    check("a currency an older MAGAT wrote, pricing an arm no longer registered, is named in the preflight",
          [*cc[:cc.index("--out") + 1], older, *cc[cc.index("--out") + 2:], "--preflight"], "does not register (retired-arm)")
    kept = {}
    check("--checks checks it again and writes nothing", [*cc, "--checks"], "check    self:",
          then=lambda: priced.read_text(encoding="utf-8") == kept.setdefault("w", priced.read_text(encoding="utf-8")))

    step("7. Runner: the shakedown spec on the toy portfolio and the toy currency")
    spec = ROOT / "experiments" / game / "shakedown.json"
    run_root = data / "shakedown" / f"run-{game}"
    shutil.rmtree(run_root, ignore_errors=True)
    run = [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", weights,
           "--out", run_root, "--threads", "2", "--toy"]
    check("dev: every leaf runs and is checkpointed, its rows counting the arm's own operations",
          [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len((run_root / "dev" / "_completed.txt").read_text(encoding="utf-8").split()) == 10
          and "op_emcts.generation" in (run_root / "dev" / "emcts" / "2000cu" / "turns.csv").read_text(encoding="utf-8").splitlines()[0]
          and "op_tbets.turn" in (run_root / "dev" / "tbets" / "2000cu" / "turns.csv").read_text(encoding="utf-8").splitlines()[0])
    check("re-running the same command skips every finished leaf", [*run, "--split", "dev"], "RUN: COMPLETE",
          then=lambda: len(list((run_root / "dev").glob("*/*cu/turns.csv"))) == 10)
    check("holdout: runs into its own tree", [*run, "--split", "holdout"], "RUN: COMPLETE",
          then=lambda: (run_root / "holdout" / "_run.json").is_file())
    check("a retired leaf is discarded and re-run", [*run, "--split", "dev", "--retire-leaf", "emcts@4000"], "RETIRED emcts@4000")
    reused_root = data / "shakedown" / f"run-{game}-reused"
    shutil.rmtree(reused_root, ignore_errors=True)
    check("--reuse: a second run in the same currency takes every leaf of the first, after re-playing two turns of each",
          [*run[:run.index("--out") + 1], reused_root, *run[run.index("--out") + 2:], "--split", "dev", "--reuse", run_root / "dev"],
          "RUN: COMPLETE", then=lambda: all('"reused"' in m.read_text(encoding="utf-8")
                                          for m in (reused_root / "dev").glob("*/*cu/_leaf.json"))
          and len(list((reused_root / "dev").glob("*/*cu/_leaf.json"))) == 10)
    reuse_spec = data / "shakedown" / f"reuse-{game}.json"
    reuse_spec.write_text(json.dumps(dict(name="reuse", game=game, arms=["emcts", "random"], baseline="emcts",
                                          levels=[9500, dict(budget="1000ms", repetitions=9, arms=["random"])], repetitions=2,
                                          split="all", ids=[1, 2, 3])), encoding="utf-8")
    reuse_root = data / "shakedown" / f"run-{game}-published"
    shutil.rmtree(reuse_root, ignore_errors=True)
    check("experiment run in the published currency takes the published leaves it may, and plays the one it needs more of",
          [PY, ROOT / "magat.py", "experiment", "run", reuse_spec, "--out", reuse_root, "--results",
           data / "shakedown" / f"results-{game}-published", "--no-bundle"], "random@20000 is played: the published leaf holds 24",
          then=lambda: '"reused"' in (reuse_root / "all" / "emcts" / "9500cu" / "_leaf.json").read_text(encoding="utf-8")
          and "leaves reused" in (data / "shakedown" / f"results-{game}-published" / "SUMMARY.md").read_text(encoding="utf-8"))
    own_spec = data / "shakedown" / f"own-{game}.json"
    own_spec.write_text(json.dumps(dict(name="own", game=game, arms=["emcts", "random"], baseline="emcts", levels=[9500],
                                        repetitions=2, split="all", ids=[1, 2, 3], reuse=False)), encoding="utf-8")
    own_root = data / "shakedown" / f"run-{game}-own"
    shutil.rmtree(own_root, ignore_errors=True)
    check("a spec that says \"reuse\": false plays every leaf in the published currency, taking none",
          [PY, ROOT / "magat.py", "experiment", "run", own_spec, "--out", own_root, "--results",
           data / "shakedown" / f"results-{game}-own", "--no-bundle"], "every leaf is played",
          then=lambda: not any('"reused"' in m.read_text(encoding="utf-8") for m in (own_root / "all").glob("*/*cu/_leaf.json"))
          and len(list((own_root / "all").glob("*/*cu/_leaf.json"))) == 2)
    check("... and run refuses to take leaves into it",
          [PY, ROOT / "magat.py", "run", "--experiment", own_spec, "--out", data / "shakedown" / f"run-{game}-own-refused",
           "--reuse", reuse_root / "all"], "leave out --reuse", codes=(2,))
    repriced = calib / "compute_weights_repriced.properties"
    repriced.write_text(weights.read_text(encoding="utf-8").replace("profile.emcts@2000.w.copy = ", "profile.emcts@2000.w.copy = 9"),
                        encoding="utf-8")
    check("the currency lock refuses a leaf whose price changed",
          [PY, ROOT / "magat.py", "run", "--experiment", spec, "--portfolio", toy_dir, "--weights", repriced,
           "--out", run_root, "--threads", "2", "--toy", "--split", "dev"], "currency lock", codes=(4,))
    check("the shipped spec reference validates against the published currency",
          [PY, ROOT / "magat.py", "experiment", "validate", "--experiment",
           ROOT / "experiments" / game / "reference.json"], "VALIDATION: PASS")
    for example in sorted((ROOT / "experiments" / game / "examples").glob("*.json")):
        check(f"the example spec {example.name} validates against the currency it names",
              [PY, ROOT / "magat.py", "experiment", "validate", "--experiment", example], "VALIDATION: PASS")
    short = data / "shakedown" / f"short-{game}.json"
    short.write_text(json.dumps(dict(name="short", game=game, baseline="emcts", arms=["emcts", "random"], levels=[2000, 4000],
                                     repetitions=1, split="all", sources=["single"], scenarios=1)), encoding="utf-8")
    short_root, short_results = data / "shakedown" / f"run-{game}-short", data / "shakedown" / f"results-{game}-short"
    shutil.rmtree(short_root, ignore_errors=True)
    shutil.rmtree(short_results, ignore_errors=True)
    er = [PY, ROOT / "magat.py", "experiment", "run", short, "--portfolio", toy_dir, "--weights", weights, "--out", short_root,
          "--results", short_results, "--threads", "2", "--toy"]
    check("experiment run: a spec in the short forms, validated, run on the one single-player position it selects, "
          "analysed and bundled", er, "EXPERIMENT: COMPLETE",
          then=lambda: '"selection": "sources=single;scenarios=1"' in (short_root / "all" / "_run.json").read_text(encoding="utf-8")
          and len((short_root / "all" / "emcts" / "2000cu" / "turns.csv").read_text(encoding="utf-8").splitlines()) == 2
          and ",single," in (short_root / "all" / "emcts" / "2000cu" / "turns.csv").read_text(encoding="utf-8")
          and "positions selected | sources=single;scenarios=1" in (short_results / "SUMMARY.md").read_text(encoding="utf-8")
          and (data / "bundles" / "run-short.zip").is_file())
    check("experiment run again resumes: every leaf is skipped", er, "SKIP (done)")
    check("a run's --out inside the repository is refused before anything runs",
          [*er[:er.index("--out") + 1], ROOT / "experiments" / "stray-run", *er[er.index("--out") + 2:]],
          "inside the repository", codes=(1,), then=lambda: not (ROOT / "experiments" / "stray-run").exists())
    check("bundle zips a run tree", [PY, ROOT / "magat.py", "bundle", "--name", "shakedown-run", "--paths", run_root], "BUNDLE:")

    step("8. Analyzer: the toy run, re-priced in its own currency")
    results = data / "shakedown" / f"results-{game}"
    shutil.rmtree(results, ignore_errors=True)

    def every_table(out, *extra):
        return all((out / "tables" / f"{n}.csv").is_file() for n in (
            "headline", "budget_multiplier", "wallclock", "contrasts", "variance", "terminals", *extra)) \
            and (out / "SUMMARY.md").is_file()

    def same_units(out):
        """Re-pricing under the run's own weights must reproduce the units it ran on: the analyzer reads
        the weights file the calibrator wrote and the counts the runner wrote, the same way they mean them."""
        rows = (out / "tables" / "repriced_spend.csv").read_text(encoding="utf-8").splitlines()
        h = rows[0].split(",")
        return len(rows) > 1 and all(abs(float(r.split(",")[h.index("Ratio")]) - 1) < 1e-4 for r in rows[1:])

    toy_out = results / "toy"
    check("analyze: every table from the toy trees, re-priced in their own version-2 currency (every operation)",
          [PY, ROOT / "magat.py", "analyze", "--run", run_root, "--reprice", weights, "--out", toy_out], "ANALYZE: OK",
          then=lambda: every_table(toy_out, "repriced", "repriced_spend") and same_units(toy_out))

    def no_difference(out):
        """A run compared with itself: no difference anywhere, and its turns cost the same in the other's currency."""
        rows = (out / "tables" / "across.csv").read_text(encoding="utf-8").splitlines()
        h = rows[0].split(",")
        cells = [r.split(",") for r in rows[1:]]
        return len(cells) > 0 and all(float(c[h.index("Diff")]) == 0 for c in cells) \
            and all(abs(float(c[h.index("RepricedRatio")]) - 1) < 1e-4 for c in cells if c[h.index("RepricedRatio")])

    cmp_out = results / "compare"
    check("analyze --compare: the toy run beside itself, as another machine's run of the same experiment",
          [PY, ROOT / "magat.py", "analyze", "--run", run_root, "--compare", run_root, "--out", cmp_out], "ANALYZE: OK",
          then=lambda: no_difference(cmp_out) and "## Across machines (dev)" in (cmp_out / "SUMMARY.md").read_text(encoding="utf-8"))

    check("paper --check: the paper's tables rebuild from the published artefacts byte for byte, and its figures render",
          [PY, ROOT / "magat.py", "paper", "--check"], "PAPER: CURRENT")

    step("8b. Sharing: export, import into another data root, check, build on it")
    elsewhere = data / "shakedown" / f"elsewhere-{game}"
    shutil.rmtree(elsewhere, ignore_errors=True)
    there = dict(os.environ, MAGAT_DATA=str(elsewhere))
    pkg = data / "shakedown" / f"toy-run-{game}.magat.zip"
    check("export: the toy run, its currency, spec and a fresh report in one package, with a manifest of hashes",
          [PY, ROOT / "magat.py", "export", run_root, "--name", "toy-run", "--out", pkg], "EXPORT: ", then=lambda: pkg.is_file())
    check("import elsewhere: every file as its manifest says, every leaf re-plays a turn here, the report follows from the turns",
          [PY, ROOT / "magat.py", "import", pkg, "--replay", "1"], "IMPORT: VERIFIED", env=there,
          then=lambda: "- Verdict: VERIFIED" in (elsewhere / "imports" / "toy-run" / "IMPORT.md").read_text(encoding="utf-8"))
    edited = data / "shakedown" / f"edited-{game}.magat.zip"
    with zipfile.ZipFile(pkg) as z, zipfile.ZipFile(edited, "w") as w:
        for n in z.namelist():
            w.writestr(n, z.read(n) + (b" " if n.endswith("_run.json") else b""))
    check("a package with an edited file is refused, and nothing of it is unpacked",
          [PY, ROOT / "magat.py", "import", edited, "--name", "edited"], "IMPORT: REFUSED", codes=(2,), env=there,
          then=lambda: not (elsewhere / "imports" / "edited").exists())
    rerun = elsewhere / "rerun"
    check("in the imported currency, named by its name, experiment run takes every leaf from the verified import",
          [PY, ROOT / "magat.py", "experiment", "run", spec, "--split", "dev", "--portfolio", toy_dir, "--weights",
           "calibration-tribes", "--out", rerun, "--results", elsewhere / "rerun-results", "--threads", "2", "--toy", "--no-bundle"],
          "EXPERIMENT: COMPLETE", env=there,
          then=lambda: len(list((rerun / "dev").glob("*/*cu/_leaf.json"))) == 10
          and all('"reused"' in m.read_text(encoding="utf-8") for m in (rerun / "dev").glob("*/*cu/_leaf.json")))
    published_pkg = data / "shakedown" / f"r7615-{game}.magat.zip"
    check("the published R7615 run, exported as a package",
          [PY, ROOT / "magat.py", "export", ROOT / "games" / game / "results" / "reference" / "r7615" / "all", "--out", published_pkg],
          "EXPORT: ")
    check("... and its import re-plays a turn of every leaf here, field for field",
          [PY, ROOT / "magat.py", "import", published_pkg, "--replay", "1"], "IMPORT: VERIFIED", env=there)

    step("9. Notebook")
    check("generated notebook is current and its setup cells run from three directories",
          [PY, ROOT / "magat.py", "notebook", "selftest"], "0 failure(s)")

    print("\n############################################################")
    if FAIL == 0:
        print(f"# SHAKEDOWN PASSED ({PASS} checks).")
    else:
        print(f"# SHAKEDOWN FAILED: {FAIL} of {PASS + FAIL} checks.")
    print("############################################################")
    return FAIL


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
