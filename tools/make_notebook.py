#!/usr/bin/env python3
"""Generate notebooks/run_magat.ipynb and notebooks/maintainers/measure_levels.ipynb.

The notebooks are generated, never hand-edited, so they are diffable and cannot drift from the CLI. One
cell per stage; setup cells (tagged "setup") skip themselves once their outputs exist; every stage
ends with a bundle cell. `tools/notebook_selftest.py` executes the setup cells from three launch
directories and must pass before a commit.

run_magat.ipynb is what every machine runs: it reads the game's published levels and never measures
them. measure_levels.ipynb is the maintainers' notebook that measures them, on the reference machine
only, so nobody changes the levels by running the main notebook from top to bottom.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "notebooks" / "run_magat.ipynb"
OUT_LEVELS = ROOT / "notebooks" / "maintainers" / "measure_levels.ipynb"
# Section cells for the calibration: Tribes registers 32 priced arms besides the reference arm, eight to a
# section. A different count only means the 'rest' cell measures what the numbered cells do not.
CAL_SECTIONS = 4


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text: str, tags=()) -> dict:
    return {"cell_type": "code", "execution_count": None, "outputs": [],
            "metadata": {"tags": list(tags)}, "source": text.strip("\n").splitlines(keepends=True)}


SETUP = r'''
import os, sys, subprocess, pathlib, datetime, zipfile, glob

MAGAT_ROOT = ""     # leave empty: auto-detected. Set an absolute path only if the banner says so.

def _find_root():
    if MAGAT_ROOT:
        return pathlib.Path(MAGAT_ROOT).resolve()
    here = pathlib.Path.cwd().resolve()
    for base in [here, *here.parents]:
        if (base / "magat.py").is_file() and (base / "core" / "src").is_dir():
            return base
        for child in base.glob("*"):
            if child.is_dir() and (child / "magat.py").is_file() and (child / "core" / "src").is_dir():
                return child
    raise RuntimeError(
        f"Cannot find the MAGAT repository at or above {here}. A Jupyter kernel's working directory "
        "is the notebook's own folder (and it keeps the OLD folder if the file was moved without a "
        "kernel restart). Restart the kernel, or set MAGAT_ROOT at the top of this cell.")

ROOT = _find_root()
os.chdir(ROOT)

# Everything a run produces lives OUTSIDE the clone, so a merge and a re-clone never touch it.
DATA = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
os.environ["MAGAT_DATA"] = str(DATA)
LOGS = DATA / "logs"
BUNDLES = DATA / "bundles"
for _d in (LOGS, BUNDLES):
    _d.mkdir(parents=True, exist_ok=True)
assert ROOT not in DATA.parents and DATA != ROOT, f"data root {DATA} must be outside the clone {ROOT}"

GAME = "tribes"
PY = sys.executable
PUBLISHED_PORTFOLIO = ROOT / "games" / GAME / "portfolio" / "v1"
PUBLISHED_WEIGHTS = ROOT / "games" / GAME / "calibration" / "r7615" / "compute_weights.properties"
# Workers for parallel stages. Half the logical CPUs (SMT siblings add heat, not throughput) less two
# for the machine; MAGAT_WORKERS overrides. The machine profile (milestone 3) replaces this guess.
WORKERS = int(os.environ.get("MAGAT_WORKERS") or max(1, (os.cpu_count() or 2) // 2 - 2))
# This machine's own currency: <data>/calibration/<game>-<name>. The published R7615 currency stays in
# the repository; a new calibration of the R7615 is published beside it under this name, never over it.
# A currency keeps the levels it was first measured at: "r7615-time" is the first at the published levels
# (games/<game>/levels.json); "r7615-v2" was the name for one at the thesis's 40,000 / 80,000 / 400,000.
CURRENCY_NAME = os.environ.get("MAGAT_CURRENCY") or "r7615-time"

def magat(*args, check=True):
    """Run one launcher command; the launcher tees its own output to <DATA>/logs/<name>.log."""
    cmd = [PY, str(ROOT / "magat.py"), *map(str, args)]
    print("$ " + " ".join(cmd[2:]), flush=True)
    rc = subprocess.call(cmd)
    if check and rc != 0:
        raise RuntimeError(f"exit {rc}: magat {' '.join(map(str, args))}")
    return rc

def latest_log(name):
    txt = (LOGS / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    return txt.rsplit("\n>>>>> ", 1)[-1]

def assert_in_log(name, needle):
    assert needle in latest_log(name), f"'{needle}' not found in the latest {name} run - read {LOGS / (name + '.log')}"
    print(f"[gate] '{needle}' confirmed in {name}")

def summary(results, until="## Budget multiplier"):
    """Print an analysis's SUMMARY.md up to a section (the provenance, the side by side and the headline)."""
    text = (pathlib.Path(results) / "SUMMARY.md").read_text(encoding="utf-8")
    cut = text.find(until)
    print(text if cut < 0 else text[:cut] + f"... the rest in {pathlib.Path(results) / 'SUMMARY.md'}")

def bundle(name, items, exclude=("seqlog_", "mutlog_", "_running.lock")):
    """Zip the pre-specified files of a stage into <DATA>/bundles/<name>.zip."""
    out = BUNDLES / f"{name}.zip"
    if out.exists():
        out.unlink()
    files = []
    for it in items:
        p = pathlib.Path(it)
        if not p.is_absolute():
            p = DATA / p
        if p.is_dir():
            files += [q for q in p.rglob("*") if q.is_file()]
        elif p.exists():
            files.append(p)
        else:
            files += [pathlib.Path(h) for h in glob.glob(str(p)) if pathlib.Path(h).is_file()]
    files = sorted({f for f in files if not any(x in f.name for x in exclude)})
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(f, str(f.relative_to(DATA)) if DATA in f.parents else f.name)
    print(f"{len(files)} files -> {out}")
    return out

print("repository :", ROOT)
print("data root  :", DATA, "(outside the clone)")
print("python     :", PY)
print("workers    :", WORKERS)
'''


PORTFOLIO_CELL = r'''
PORTFOLIO = DATA / "portfolio" / GAME
if (PORTFOLIO / "_split_dev.txt").exists() and (PORTFOLIO / "_split_holdout.txt").exists():
    print(f"[skip] {PORTFOLIO} is built and frozen")
else:
    magat("portfolio", "build", "--game", GAME, "--out", PORTFOLIO, "--threads", WORKERS)
    magat("portfolio", "freeze", "--portfolio", PORTFOLIO)
print((PORTFOLIO / "split_report.txt").read_text(encoding="utf-8"))
magat("portfolio", "verify", "--game", GAME, "--portfolio", PORTFOLIO, "--published", PUBLISHED_PORTFOLIO, "--plan", 4)
assert_in_log("portfolio-verify", "VERIFY: PASS")
bundle("portfolio", [PORTFOLIO / f for f in ("portfolio.csv", "_split_dev.txt", "_split_holdout.txt",
                                             "identity.txt", "split_report.txt")]
       + [LOGS / "portfolio-build.log", LOGS / "portfolio-freeze.log", LOGS / "portfolio-verify.log"])
'''


MACHINE_CELL = r'''
magat("machine")
'''

LEVELS_CELL = r'''
# The budget levels, read from the game's published games/<game>/levels.json: the units that are 500, 1000
# and 5000 ms of a whole turn (its initialization included), single-threaded on the reference machine,
# averaged over positions spread across the portfolio (docs/CURRENCY.md, "Units as time"). This notebook
# never measures them: every currency is measured at the published levels, whatever machine it is on.
import json
LEVELS_FILE = ROOT / "games" / GAME / "levels.json"
assert LEVELS_FILE.is_file(), f"{LEVELS_FILE} is missing: the levels are published with the repository (pull it again)"
LEVELS_DOC = json.loads(LEVELS_FILE.read_text(encoding="utf-8"))
LEVEL_UNITS = [int(lv["units"]) for lv in LEVELS_DOC["levels"]]
LEVELS = ",".join(map(str, LEVEL_UNITS))
MID_LEVEL = LEVEL_UNITS[len(LEVEL_UNITS) // 2]     # the level single-level checks run at
print(f"levels of {GAME}, measured {LEVELS_DOC['measured']} on {LEVELS_DOC['machine'].split(' | ')[0]}:")
for lv in LEVELS_DOC["levels"]:
    print(f"  {lv['units']:>9,} units = {lv['time_ms']:>5,} ms of thinking on the reference machine (measured {lv['measured']['mean_ms']:,.0f} ms)")
'''

THREADS_CELL = r'''
# threads.json goes beside this machine's currency: that is where a run looks for it.
CALIBRATION = DATA / "calibration" / f"{GAME}-{CURRENCY_NAME}"
REDO_THREADS = False   # True: sweep again; the previous threads.json and thread_sweep.csv are kept, renamed
if REDO_THREADS:
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    for f in ("threads.json", "thread_sweep.csv"):
        if (CALIBRATION / f).exists():
            (CALIBRATION / f).rename(CALIBRATION / f"{f}.before-{stamp}")
            print(f"[redo] kept the previous {f} as {f}.before-{stamp}")
if (CALIBRATION / "threads.json").exists():
    print(f"[skip] {CALIBRATION / 'threads.json'} exists")
else:
    magat("threads", "--game", GAME, "--portfolio", PORTFOLIO, "--out", CALIBRATION, "--levels", LEVELS)
print((CALIBRATION / "threads.json").read_text(encoding="utf-8"))
bundle("threads", [CALIBRATION / "threads.json", CALIBRATION / "thread_sweep.csv", LOGS / "threads.log"])
'''

CALIBRATE_CELL = r'''
# This machine's own currency, on this machine's own portfolio, in sections (calibrate-controlled; read
# docs/CALIBRATING_A_MACHINE.md, "On a shared server"). Idle and single-threaded: nothing else of yours
# may run while a section measures. Every section cell below skips itself once its arms are priced.
# Version 2 (CURRENCY_VERSION = 2) prices every operation an arm declares, its own work included; version
# 1 prices the forward model only, as the published R7615 currency does.
CURRENCY_VERSION = 2
CAL_BATCH = 8           # arms per section, fixed when the first section runs
CAL_OTHER_CPUS = 4.0    # other work on the machine, in logical CPUs, that makes a section SUSPECT
CAL_WAIT_QUIET = 30     # minutes a section waits for the machine to drop below that before refusing to start
CAL_SOAK_MINUTES = 0    # a server needs no warming up: the stability check is two estimates that agree within 3%
WEIGHTS = CALIBRATION / "compute_weights.properties"     # the currency every run below spends

def calibrate_section(section, redo=False):
    # One section (a number), "checks", or "rest" (every section still missing). Raises unless the section
    # passed or is suspect; a suspect one is measured again with redo=True when the machine is quiet.
    args = ["calibrate-controlled", "--name", CURRENCY_NAME, "--game", GAME, "--portfolio", PORTFOLIO,
            "--format", CURRENCY_VERSION, "--batch", CAL_BATCH, "--other-cpus", CAL_OTHER_CPUS,
            "--wait-quiet", CAL_WAIT_QUIET, "--soak-minutes", CAL_SOAK_MINUTES, "--levels", LEVELS]
    args += ["--section", "all", "--no-checks"] if section == "rest" else ["--section", section]
    if redo:
        args.append("--redo")
    rc = magat(*args, check=False)
    if rc == 5:
        print(f"\n*** SUSPECT: see the reasons above. When the machine is quiet, run calibrate_section({section!r}, redo=True).")
    elif rc == 4:
        raise RuntimeError("the machine stayed busy and nothing was started: run this cell again later")
    elif rc != 0:
        raise RuntimeError(f"exit {rc}: run this cell again (a re-run measures only what is missing); "
                           f"the record is {CALIBRATION / 'record' / 'SUMMARY.md'}")
    return rc

magat("calibrate-controlled", "--name", CURRENCY_NAME, "--game", GAME, "--portfolio", PORTFOLIO,
      "--other-cpus", CAL_OTHER_CPUS, "--batch", CAL_BATCH, "--preflight")
'''

SECTION_CELL = r'''
calibrate_section({k})
'''

REST_CELL = r'''
# Any section still missing: one that failed twice, or sections added since (a new arm, another batch).
calibrate_section("rest")
'''

CHECKS_CELL = r'''
# Two checks that write nothing. Against its own new currency, at the published levels: did this machine
# hold its speed across the calibration? Against the published R7615 currency, at that file's own levels
# (the thesis's 40,000 / 80,000 / 400,000, its initialization outside the budget): how far this machine's
# prices are from the thesis's (on the R7615 itself, how far they moved since the thesis measured them).
calibrate_section("checks")
# The metering overhead on this machine, at the middle level of the new currency (ORIGIN.md lists the
# R7615's line as not measured yet).
magat("check", "overhead", "--game", GAME, "--portfolio", PORTFOLIO, "--weights", WEIGHTS, "--budget", MID_LEVEL,
      "--reps", 4, check=False)
magat("weights", "--game", GAME, "--weights", WEIGHTS, "--arms", "@reference", "--levels", LEVELS)
print((CALIBRATION / "record" / "SUMMARY.md").read_text(encoding="utf-8"))
bundle("calibration", [CALIBRATION, LOGS / "calibrate-controlled.log", LOGS / "check-overhead.log"])
'''

UNDER_LOAD_CELL = r'''
# Gate A3' (off by default). The thesis resolved it: the idle currency is the ruler and wall-clock
# (W7) is co-primary, so this is a diagnostic, not a step. It writes compute_weights_load<W>.properties
# beside the idle file, never over it, and prints the idle-versus-loaded ratios.
RUN_UNDER_LOAD = False
if RUN_UNDER_LOAD:
    magat("calibrate", "--game", GAME, "--portfolio", PORTFOLIO, "--out", CALIBRATION,
          "--arms", "emcts", "--under-load", WORKERS)
'''

GATES_CELL = r'''
magat("check", "contract", "--game", GAME, "--scenarios", 8)
assert_in_log("check-contract", "CONTRACT: PASS")
magat("check", "equivalence", "--game", GAME, "--scenarios", 8, "--visits", 2, "--budgets", "8000,40000")
assert_in_log("check-equivalence", "EQUIVALENCE: PASS")
# The audit: each arm's declared operations and forward model account for its turn (fails above 25% unaccounted).
magat("check", "overhead", "--audit", "--game", GAME, "--portfolio", PORTFOLIO, "--arms", "@reference", "--budget", MID_LEVEL)
assert_in_log("check-overhead", "OVERHEAD AUDIT: PASS")
bundle("gates", [LOGS / f"{n}.log" for n in ("check-contract", "check-equivalence", "check-overhead", "test")])
'''

WORKED_CELL = r'''
# The published worked result (the thesis's AB-EMCTS line, imported), re-analysed here in seconds. It must
# reproduce games/tribes/results/ab-emcts/report; docs/results/tribes-ab-emcts.md sets it beside the
# thesis retrospective, number by number.
WORKED = DATA / GAME / "results" / "ab-emcts-published"
magat("analyze", "--run", ROOT / "games" / GAME / "results" / "ab-emcts", "--out", WORKED)
assert_in_log("analyze", "ANALYZE: OK")
summary(WORKED, until="## Headline (dev)")
bundle("worked-result", [WORKED / "SUMMARY.md", WORKED / "tables", LOGS / "analyze.log"])
'''


def experiment_cells(game: str) -> list:
    """One markdown and one code cell per experiment in experiments/<game>/, so the notebook cannot drift
    from the specs it runs. A development experiment runs, is analysed, and is bundled. A holdout
    experiment checks the currency for drift first, refuses to run until its frozen choices exist, then
    runs and is analysed side by side with its development partner (`<x>-holdout.json` pairs with
    `<x>-dev.json`). The shakedown spec runs inside the shakedown."""
    cells = []
    specs = {p.name: p for p in sorted((ROOT / "experiments" / game).glob("*.json"))}
    for spec in specs.values():
        x = json.loads(spec.read_text(encoding="utf-8"))
        if x.get("name") == "shakedown" or "levels" not in x:      # the shakedown's own spec
            continue
        name, split = x["name"], x.get("split", "all")
        levels = ", ".join(f"{l['budget']:,} x {l['visits']}" for l in x.get("levels", []))
        arms = ", ".join(a["id"] if isinstance(a, dict) else a for a in x.get("arms", []))
        turns = sum(len(l.get("arms") or x.get("arms", [])) * l["visits"] for l in x["levels"])
        cells.append(md(f"""
## Experiment: {name}

{x.get("description", "")}

Split `{split}`; baseline `{x.get("baseline")}`; levels (budget x visits) {levels}; arms {arms}
({turns:,} turns per scenario of the split). Re-running the cell resumes: finished leaves are skipped,
a partial leaf continues where it stopped.
"""))
        head = f'''SPEC = ROOT / "experiments" / GAME / "{spec.name}"
RUN = DATA / GAME / "runs" / "{name}"
RESULTS = DATA / GAME / "results" / "{name}"
'''
        if split == "holdout":
            partner = spec.name.replace("-holdout.json", "-dev.json")
            dev_name = json.loads(specs[partner].read_text(encoding="utf-8"))["name"] if partner in specs else None
            frozen = x.get("frozen", "")
            body = head + f'''FROZEN = SPEC.parent / "{frozen}"
# 1. Drift: does this machine still spend the currency the development half was priced in? Writes nothing.
magat("calibrate", "--check", "--game", GAME, "--portfolio", PORTFOLIO, "--arms", "{x.get("baseline")}", "--weights", WEIGHTS, check=False)
# 2. The holdout is read once, and only for choices written down before it is read.
assert FROZEN.is_file(), f"no frozen choices at {{FROZEN}}: write them, from development only, before reading the holdout"
print(FROZEN.read_text(encoding="utf-8"))
magat("run", "--experiment", SPEC, "--split", "holdout", "--portfolio", PORTFOLIO, "--weights", WEIGHTS, "--frozen", FROZEN, "--out", RUN)
assert_in_log("run-holdout", "RUN: COMPLETE")
'''
            if dev_name:
                body += f'''DEV = DATA / GAME / "runs" / "{dev_name}" / "dev"
magat("analyze", "--run", DEV, "--holdout", RUN / "holdout", "--out", RESULTS)
'''
            else:
                body += '''magat("analyze", "--run", RUN / "holdout", "--out", RESULTS)
'''
            body += f'''assert_in_log("analyze", "ANALYZE: OK")
summary(RESULTS)
bundle("run-{name}", [RUN / "holdout", RESULTS / "SUMMARY.md", RESULTS / "tables", LOGS / "calibrate-check.log"])'''
            cells.append(code(body, tags=("holdout",)))
        else:
            cells.append(code(head + f'''magat("run", "--experiment", SPEC, "--split", "{split}", "--portfolio", PORTFOLIO, "--weights", WEIGHTS, "--out", RUN)
assert_in_log("run-{split}", "RUN: COMPLETE")
magat("analyze", "--run", RUN / "{split}", "--out", RESULTS)
assert_in_log("analyze", "ANALYZE: OK")
summary(RESULTS)
bundle("run-{name}", [RUN / "{split}", RESULTS / "SUMMARY.md", RESULTS / "tables"])''', tags=("experiment",)))
    return cells


INVENTORY_CELL = r'''
import json
# Defaults, so this cell also runs on its own after a kernel restart.
PORTFOLIO = globals().get("PORTFOLIO", DATA / "portfolio" / GAME)
CALIBRATION = globals().get("CALIBRATION", DATA / "calibration" / f"{GAME}-{CURRENCY_NAME}")
BUNDLED = [
    ("portfolio", "this machine's portfolio: manifest, split files, identity, the verify log"),
    ("threads", "the thread sweep and threads.json"),
    ("calibration", f"the currency {CURRENCY_NAME}: weights, every section's record and verdict, the checks, the overhead"),
    ("gates", "contract, equivalence, audit"),
    ("worked-result", "the published worked result, re-analysed here"),
@RUNS@    ("shakedown", "the shakedown's log"),
]
FILES = [
    (PORTFOLIO / "identity.txt", "the portfolio's identity"),
    (CALIBRATION / "compute_weights.properties", "this machine's currency"),
    (CALIBRATION / "record" / "SUMMARY.md", "every calibration section's verdict"),
    (CALIBRATION / "threads.json", "the thread choices runs read"),
@REPORTS@]
total, missing = 0, []
print(f"Bundles in {BUNDLES}: download these; together they hold everything the analysis needs.")
for name, what in BUNDLED:
    path = BUNDLES / f"{name}.zip"
    size = path.stat().st_size if path.is_file() else 0
    total += size
    if not size:
        missing.append(name)
    print(f"  [{'ok' if size else 'missing':7}] {path.name:32} {size / 2**20:8.1f} MB   {what}")
print(f"  {total / 2**20:.1f} MB in all")
print("\nThe files behind them:")
for path, what in FILES:
    print(f"  [{'ok' if path.exists() else 'missing':7}] {path}   ({what})")
state = CALIBRATION / "record" / "state.json"
if state.is_file():
    latest = {}
    for row in json.loads(state.read_text(encoding="utf-8")).get("sessions", []):
        latest[row["section"]] = row.get("reliability", row.get("verdict"))
    print("\nCalibration sections, latest verdict: " + (", ".join(f"{k} {v}" for k, v in sorted(latest.items())) or "none yet"))
    doubtful = sorted(k for k, v in latest.items() if v != "PASS")
    if doubtful:
        print(f"  measure these again when the machine is quiet, with calibrate_section(k, redo=True): {doubtful}")
print("\nINVENTORY: " + ("COMPLETE" if not missing else f"{len(missing)} bundle(s) missing: " + ", ".join(missing)))
'''


def inventory_cell(game: str) -> str:
    """The inventory cell, naming the bundle and the report of every experiment the experiment cells run."""
    names = []
    for spec in sorted((ROOT / "experiments" / game).glob("*.json")):
        x = json.loads(spec.read_text(encoding="utf-8"))
        if x.get("name") != "shakedown" and "levels" in x:
            names.append(x["name"])
    runs = "".join(f'    ("run-{n}", "experiment {n}: its run tree and its analysis"),\n' for n in names)
    reports = "".join(f'    (DATA / GAME / "results" / "{n}" / "SUMMARY.md", "the report of {n}"),\n' for n in names)
    return INVENTORY_CELL.replace("@RUNS@", runs).replace("@REPORTS@", reports)


CELLS = [
    md("""
# MAGAT — run everything from here

One cell per stage. Every cell is independently re-runnable: setup cells skip themselves once their
outputs exist, and every stage ends with a `bundle(...)` cell. Run output lives under `~/magat_runs`
(or `MAGAT_DATA`), **outside the clone**.
"""),
    md("""
## Parameters and helpers

Run this first, always. It finds the repository on its own (a Jupyter kernel's working directory is
the notebook's folder, not where the server was launched) and puts the data root outside the clone.
"""),
    code(SETUP, tags=("setup",)),
    md("""
## Build, tests, layering
"""),
    code('magat("build", "--tests")\nmagat("test")\nmagat("layering")', tags=("build",)),
    md("""
## Portfolio

Build this machine's own copy of the portfolio from the game's scenario sources, freeze its split,
and verify it against the published one: the same identity, every manifest row equal, and the same
plans from both sets of scenario files. A rebuild that matches is the evidence that positions, and
therefore every published result, reproduce here. Skips itself once the portfolio is built and frozen;
the journal makes an interrupted build resume where it stopped.
"""),
    code(PORTFOLIO_CELL, tags=("portfolio",)),
    md("""
## Machine, threads, calibration

The machine banner first: physical cores, the worker ceiling and why. Then the thread sweep, which
picks the knee of sustained throughput per budget level (results do not depend on it, only wall-clock
does). Each skips itself once its output exists.
"""),
    code(MACHINE_CELL, tags=("machine",)),
    md("""
### The levels

A budget level is a number of compute units, chosen so that it is a round amount of thinking time on
the reference machine: 500, 1000 and 5000 ms of a whole turn, single-threaded on the R7615, averaged
over positions spread across the portfolio. They are published with the repository
(`games/<game>/levels.json`, with the record behind them), and every currency, check and experiment
below runs at them. This cell reads them; it never measures them. Measuring them again is a
maintainer's job on the reference machine (`notebooks/maintainers/measure_levels.ipynb`), because new
levels mean every currency must be measured again.
"""),
    code(LEVELS_CELL, tags=("levels",)),
    code(THREADS_CELL, tags=("threads",)),
    md("""
### This machine's currency, in sections

`calibrate-controlled` prices every arm at the levels above, in sections of eight. Each section is one
calibration session, under an hour on the R7615, that writes all or nothing and merges into the currency by ratio, so a
culled server or a busy afternoon costs one section, never the whole currency. Before each section it
waits (up to `CAL_WAIT_QUIET` minutes) for the machine to be quiet and checks that two estimates agree.
After each it gives a verdict:
- **PASS**: the calibrator's gates passed and nothing was seen disturbing it.
- **SUSPECT**: the gates passed, but the machine was busy with other work (`CAL_OTHER_CPUS`), the
  container was CPU-throttled, or the session ran at a different speed from the first. Run the cell
  again with `redo=True` when the machine is quiet.
- **FAIL**: nothing was written; it is retried once, and running the cell again measures what is
  missing.

Run the cells one at a time; every one skips itself once its section is priced. Keep this tab open
while a section runs, and start nothing else in this server. `record/SUMMARY.md` in the currency's
folder has every section's verdict, and the last cell bundles it all.
"""),
    code(CALIBRATE_CELL, tags=("calibrate",)),
    *[code(SECTION_CELL.replace("{k}", str(k)), tags=("calibrate", f"section-{k}")) for k in range(1, CAL_SECTIONS + 1)],
    code(REST_CELL, tags=("calibrate",)),
    code(CHECKS_CELL, tags=("calibrate", "checks")),
    code(UNDER_LOAD_CELL, tags=("under-load",)),
    md("""
## Gates and probes

The contract laws on published positions; the equivalence gate, where every ported arm must reproduce
the engine agent it was ported from, field for field; and the audit, where each arm's declared
operations and forward model must account for its turn. Nothing downstream runs otherwise.
"""),
    code(GATES_CELL, tags=("gates",)),
    md("""
## The worked result, re-analysed

The thesis's AB-EMCTS line, imported into MAGAT's run layout: every measure of the brief, in seconds,
from the published rows. A newcomer's first look at what a MAGAT report says.
"""),
    code(WORKED_CELL, tags=("worked-result",)),
    *experiment_cells("tribes"),
    md("""
## Shakedown

Every moving part at toy size, minutes, exits non-zero on any failure. Run before every pull request.
"""),
    code('magat("shakedown")\nbundle("shakedown", [LOGS / "shakedown.log"])'),
    md("""
## Inventory

What to keep from this machine: every bundle the stages above wrote, with a tick, the files behind
them, and the calibration's section verdicts. Download the bundles; everything else can be
regenerated from them.
"""),
    code(inventory_cell("tribes"), tags=("inventory",)),
]


MEASURE_LEVELS_CELL = r'''
# Measure the levels again: the units that are LEVEL_TIMES milliseconds of a whole turn of the reference
# arm (its initialization included), single-threaded, averaged over LEVEL_POSITIONS positions spread across
# the portfolio, within 5%, rounded to 500 units. About an hour on the R7615. It writes
# <data>/calibration/<game>-levels and never touches the published file; publishing is a pull request.
import json
REMEASURE = False        # True only on the reference machine, deliberately: see the cell above
LEVEL_TIMES = "500,1000,5000"
LEVEL_POSITIONS = 64
PUBLISHED_LEVELS = ROOT / "games" / GAME / "levels.json"
MEASURED = DATA / "calibration" / f"{GAME}-levels"
BUILT = DATA / "portfolio" / GAME
PORTFOLIO = BUILT if (BUILT / "_split_dev.txt").exists() else PUBLISHED_PORTFOLIO
print(f"portfolio: {PORTFOLIO}" + ("" if PORTFOLIO == BUILT else "   (the published one: run_magat.ipynb builds this machine's own)"))
if PUBLISHED_LEVELS.is_file():
    print(f"published: {PUBLISHED_LEVELS}")
    print((PUBLISHED_LEVELS.parent / "levels.md").read_text(encoding="utf-8"))
if not REMEASURE:
    print("REMEASURE is False: nothing measured. The published levels stand.")
elif (MEASURED / "levels.json").exists():
    print(f"[skip] {MEASURED / 'levels.json'} exists: rename that folder to measure again")
else:
    magat("calibrate-time", "--game", GAME, "--portfolio", PORTFOLIO, "--times", LEVEL_TIMES,
          "--positions", LEVEL_POSITIONS, "--remeasure")
'''

COMPARE_LEVELS_CELL = r'''
# The measured levels beside the published ones, and the bundle to publish from.
if (MEASURED / "levels.json").exists():
    new = json.loads((MEASURED / "levels.json").read_text(encoding="utf-8"))
    print((MEASURED / "SUMMARY.md").read_text(encoding="utf-8"))
    if PUBLISHED_LEVELS.is_file():
        old = {lv["time_ms"]: lv["units"] for lv in json.loads(PUBLISHED_LEVELS.read_text(encoding="utf-8"))["levels"]}
        for lv in new["levels"]:
            was = old.get(lv["time_ms"])
            print(f"  {lv['time_ms']:>5,} ms: {lv['units']:>9,} units" + (f"   (published {was:,}, {100 * (lv['units'] / was - 1):+.1f}%)" if was else ""))
    bundle("levels", [MEASURED, LOGS / "calibrate-time.log"])
else:
    print(f"nothing measured in {MEASURED}")
'''

LEVELS_CELLS = [
    md("""
# MAGAT maintainers: measure the budget levels

**Run this only on the reference machine (the R7615 for Tribes), and only deliberately.** Every other
machine, and every ordinary run, uses the published levels: `run_magat.ipynb` reads them and never
measures them.

A budget level is a number of compute units that is a round amount of thinking time on the reference
machine: 500, 1000 and 5000 ms of a whole turn, single-threaded, averaged over positions spread across
the portfolio ([units as time](../../docs/CURRENCY.md#units-as-time)). The levels are published in
`games/<game>/levels.json`, with `levels.md` and the record in `levels-record/`.

Every currency, check and experiment is measured at the published levels. New levels change what a unit
means, so **every currency must then be measured again, under a new name**. Measure again only after a
change to what a turn costs, such as a new engine commit or a new reference machine. The measurement writes
to the data root and never touches the repository; publishing new levels is a pull request.
"""),
    md("""
## Parameters and helpers
"""),
    code(SETUP, tags=("setup",)),
    code('magat("build")\nmagat("machine")', tags=("build",)),
    md("""
## Measure

Set `REMEASURE = True` to measure; left `False`, the cell only shows the published levels. The machine
must be idle: nothing else of yours may run while it measures.
"""),
    code(MEASURE_LEVELS_CELL, tags=("levels",)),
    code(COMPARE_LEVELS_CELL, tags=("levels",)),
    md("""
## Publish

Bundle `levels` holds the measurement. To publish it, in a pull request:
1. `levels.json` becomes `games/<game>/levels.json`, `SUMMARY.md` becomes `games/<game>/levels.md`, and
   `probe.json` and `iter-*/` replace `games/<game>/levels-record/` (update its README).
2. Set the levels of every spec that runs at the published levels (`experiments/<game>/reference-ladder.json`),
   and the expected units in `PublishedLevelsTest`.
3. Every currency is then measured again, under a new name (`CURRENCY_NAME` in `run_magat.ipynb`).
"""),
]


def notebook(cells) -> dict:
    return {
        "cells": cells,
        "metadata": {
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"},
            "magat": {"generated_by": "tools/make_notebook.py"},
        },
        "nbformat": 4,
        "nbformat_minor": 5,
    }


def build() -> dict:
    return notebook(CELLS)


NOTEBOOKS = [(OUT, CELLS), (OUT_LEVELS, LEVELS_CELLS)]


def main(argv) -> int:
    stale = 0
    for out, cells in NOTEBOOKS:
        text = json.dumps(notebook(cells), indent=1, ensure_ascii=False) + "\n"
        if "--check" in argv:
            current = out.read_text(encoding="utf-8") if out.exists() else ""
            if current != text:
                print(f"{out} is stale: regenerate with `python tools/make_notebook.py`")
                stale = 1
            else:
                print(f"{out} is up to date")
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        print(f"wrote {out} ({len(cells)} cells)")
    return stale


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
