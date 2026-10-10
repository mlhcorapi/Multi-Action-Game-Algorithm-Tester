# Operations

How to run MAGAT: the launcher, where output goes, the notebook, runs and their guards, analysis,
calibration and the checks.

## The launcher

`python magat.py <command>`, from any directory, is the one thing to type (`bin/magat` and `magat.cmd`
wrap it). It:
- finds the repository;
- builds when sources are newer than classes (`javac --release 11`; no build tool needed);
- computes the classpath;
- opens the data root;
- passes the weights file, the engine commit and the data root explicitly to every JVM;
- tees every command's output to `<data>/logs/<command>.log` with a timestamped header.

`python magat.py` with no arguments lists every command. Every flag is named, never positional.

| Command | What it does |
|---|---|
| `build [--clean] [--tests]` | compile the core, the game adapters and the engines |
| `test [--java \| --python]` | JUnit, then the analysis package's unit tests (the worked result's acceptance test included) |
| `layering` | the core imports no game and no engine |
| `shakedown` | every moving part at toy size, about ten minutes; exits non-zero on any failure. Run it before every pull request |
| `arms --game G [--arms a,b\|@set]` | the registered arms, their oracles, which arm is the reference and which are not priced |
| `machine` | cores, the worker ceiling and why; runs no work |
| `portfolio build \| freeze \| verify \| ledger` | [the portfolio](METHODS.md#1-the-portfolio) |
| `threads` | the thread sweep: the knee of sustained throughput per budget level, into `threads.json` |
| `calibrate`, `calibrate --estimate`, `calibrate --check` | [the currency](CURRENCY.md#calibrating) |
| `calibrate-controlled` | a calibration in sections, with reliability checks ([guide](CALIBRATING_A_MACHINE.md)) |
| `calibrate-time --remeasure [--times 500,1000,5000]` | the levels that are those milliseconds of thinking time on this machine: a maintainer's tool for the reference machine, refused without `--remeasure` once the game's levels are published ([units as time](CURRENCY.md#units-as-time)) |
| `weights --weights F [--game G --arms ...]` | what a weights file prices; exit 1 if anything asked for is missing |
| `check contract \| equivalence \| structure \| replay \| overhead` | the gates (below) |
| `check time (--weights F \| --fixed-weights) [--levels ...] [--arms ...] [--targets ms,...]` | single-threaded whole turns over positions spread across the portfolio: each level's thinking time on this machine |
| `probe support` | the mechanism-support probe for learned arms |
| `experiment validate --experiment SPEC` | the spec validator alone |
| `run --experiment SPEC --split dev\|holdout\|all` | run an experiment (below) |
| `analyze --run DIR [--holdout DIR] [--reprice W] --out DIR` | every measure, as tables and `SUMMARY.md` |
| `import-legacy --stage1-tree DIR --out RUN` | a thesis run tree into MAGAT's layout |
| `bundle --name N --paths P...` | zip files into `<data>/bundles/N.zip` |
| `notebook build \| selftest` | regenerate the notebook; self-test its setup cells from three directories |

Commands that need a portfolio or a weights file default to the game's published ones
(`games/<game>/portfolio/v1`, `games/<game>/calibration/r7615/compute_weights.properties`). Every
command prints a banner naming the data root, the weights file in force and the portfolio identity,
because those three are what every past mistake turned on.

## Where output goes

Everything a run produces lives **outside the clone**, under `MAGAT_DATA` (default `~/magat_runs`).
The launcher refuses a data root inside the repository. A merge or a fresh clone then never touches
results.

```
<data>/portfolio/<game>/                    a portfolio you built: manifest, split files, identity, scenarios/
<data>/calibration/<game>-<name>/           a currency: compute_weights.properties, threads.json, record/
<data>/<game>/runs/<experiment>/<split>/    run trees
<data>/<game>/results/<experiment>/         tables/, SUMMARY.md, report.json
<data>/logs/<command>.log                   every command's output, appended with a header per invocation
<data>/bundles/<name>.zip                   what to carry home from a server
```

### A run tree: `<run>/<split>/`

- `_run.json` records:
  - the MAGAT commit;
  - the game, the engine commit and the configuration;
  - the portfolio identity;
  - the weights file and its hash;
  - the spec's and the frozen choices' hashes;
  - the machine and the JDK;
  - every invocation, and every retired leaf.
- `_completed.txt`, and `_running.lock` while a run is writing.
- One leaf per `(arm, budget)`: `<arm>/<budget>cu/`, holding:
  - `turns.csv`: one row per `(scenario, visit)`, with the values, the raw operation counts (`advances`,
    `copies`, `evals`, one `op_<name>` per declared operation), units, initialization spend, `plan_ms`,
    nodes, unique, duplicates, `seq_len` and the telemetry as `t_<key>`;
  - `curve.csv`: the anytime checkpoints;
  - `_leaf.json`: the prices it ran under, threads, host, times, status and the audit.

## The notebook

`notebooks/run_magat.ipynb` is how a run is done on a server: one cell per stage, each self-skipping
once its output exists, each stage ending in a bundle. It is generated by `tools/make_notebook.py`, so
edit that, then run `python magat.py notebook build`. The notebook calls the launcher and nothing
else, so it cannot drift from the CLI. Its stages:

1. **Setup.** It finds the repository from whatever directory the kernel started in; a Jupyter kernel's
   working directory is the notebook's own folder. It puts the data root outside the clone and sets
   `CURRENCY_NAME`.
2. **Build and tests.**
3. **Portfolio.** It builds this machine's own, freezes it, and verifies it against the published one.
4. **Machine and threads.**
5. **Calibration, in sections.** A preflight and plan cell, one cell per section, a cell for anything
   still missing, and a checks cell ([guide](CALIBRATING_A_MACHINE.md#on-a-shared-server-the-r7615-through-jupyterhub)).
6. **Gates.** Contract, equivalence on every ported arm, structure, the audit, replay, and the support
   probe.
7. **The worked result**, re-analysed.
8. **One cell per experiment** in `experiments/<game>/`: run, analyse, summarise, bundle. A holdout spec
   gets the drift check first and refuses to start without its frozen choices.
9. **The shakedown.**
10. **The inventory.** What to keep, with a tick per file and the bundles to download.

## Experiments and runs

An experiment is a JSON spec in `experiments/<game>/`. A spec names:
- the split and the baseline;
- the levels, as `{budget, visits}`;
- the arms, each with its `attribution` control if it has one;
- the `sweeps`, each with its `null`;
- the `contrasts`;
- the `preregistration` file;
- for the holdout, the `frozen` choices.

`magat experiment validate` checks it before anything runs:
- every arm is registered and priced at every level it runs, every declared operation included under
  a version-2 currency;
- the baseline is at two levels or more;
- every sweep has its null in the grid;
- every mechanism has its attribution control in the grid;
- a holdout spec has its frozen choices.

```bash
python magat.py run --experiment experiments/tribes/reference-ladder.json --split dev --out ~/magat_runs/tribes/runs/reference-ladder
```

- **Resume by re-running the same command.** Each leaf is checkpointed per turn; a torn write is
  compacted on resume, and a finished leaf is skipped.
- **Never delete to re-run.** `--retire-leaf arm@budget` discards a leaf deliberately and logs it in
  `_run.json`.
- **Threads.** `--threads N`, `--threads-at 400000=31` per level, or by default the `threads.json`
  beside the weights file. Results do not depend on the thread count; wall-clock does.

**Guards**, each a past mistake made structural:
- **The currency lock.** A leaf whose price changed in the weights file is refused until it is
  retired. A merge that only added arms changes no leaf.
- **The portfolio and configuration lock.** A tree is bound to one portfolio identity and one game
  configuration.
- **The identical-output assertion.** Two arms whose rows agree on every turn stop the run.
- **The holdout ledger** ([methods](METHODS.md#3-the-holdout-is-read-once)).
- **The audit.** Above `--audit-warn` (10%) of a leaf's plan time unpriced, the run says so.

**Exit codes of `run`.**

| Code | Meaning |
|---|---|
| 0 | complete |
| 1 | stopped, or a turn failed: re-run to retry |
| 2 | invalid spec |
| 3 | the tree is locked by a live writer |
| 4 | tree mismatch, or the currency lock |
| 5 | identical arms |

## Analysis

```bash
python magat.py analyze --run ~/magat_runs/tribes/runs/<experiment> --out ~/magat_runs/tribes/results/<experiment>
```

`--run` takes a split directory or a run directory; a run directory's `dev/` and `holdout/` are
analysed side by side. `--reprice <weights>` re-prices every leaf in another currency ([currency](CURRENCY.md#what-transfers-between-machines)).
The output is:
- `tables/<measure>.csv`, each with a `Split` column first and a `currency` column last;
- `SUMMARY.md`, with the provenance, then the replication table, then the headline in units and
  seconds;
- `report.json`.

## The gates

| Command | What it asserts |
|---|---|
| `check contract` | the laws L1-L10 on captured positions ([contract](CONTRACT.md#the-laws-magat-check-contract)) |
| `check equivalence` | each ported arm reproduces its oracle (the reference implementation it was ported from) field for field, including its mechanism counters, on N scenarios × V visits |
| `check structure` | pairs that must be identical are, pairs that must differ do, and the tree branches (`experiments/<game>/structure.json`) |
| `check replay` | published turns re-planned on this machine agree with the published rows field for field |
| `check overhead` | the metering design's own cost: the decorator against the engine's hooks, and the dormant counters |
| `check overhead --audit` | each arm's declared operations and forward model account for its turn: warns above 10% unaccounted, fails above 25% |
| `probe support` | a learned mechanism's estimates rest on enough observations, per level; a failing level is reported, not run differently |

Each prints PASS or FAIL and exits non-zero on FAIL. The shakedown and CI run all of them at toy size.

## Environment variables

| Variable | Effect |
|---|---|
| `MAGAT_DATA` | the data root (default `~/magat_runs`) |
| `MAGAT_BUILD` | the build output, for example out of a synced folder whose client holds files open |
| `MAGAT_HEAP` | the JVM heap for every command. Unset, calibrations and checks get `4g`, so currencies are measured alike; portfolio builds, thread sweeps, runs and loaded calibrations get 1 GB plus 256 MB per worker (`MAGAT_MAX_THREADS`, else half the logical CPUs), at most half the memory available and at least 2 GB, and the launcher prints which. Set it only to override both |
| `MAGAT_MAX_THREADS`, `MAGAT_RESERVE_CORES` | the worker ceiling, and the cores held back |
| `MAGAT_<GAME>_ENGINE` | another engine tree instead of the submodule |
| `MAGAT_WORKERS`, `MAGAT_CURRENCY` | the notebook's worker count and currency name |

## Practical notes

- **Clone with submodules** (`--recurse-submodules`). The Tribes engine is a submodule, and nothing
  builds without it. A clone made without it gets it on its first build: the launcher runs
  `git submodule update --init` for an engine that `.gitmodules` declares but that is not checked out.
  A copy that is not a git clone stops with the command that fetches the engine. The launcher also
  warns when the engine is at another commit than the one this commit of MAGAT pins, because runs
  record the engine's commit.
- **A repository in a synced folder (OneDrive, Dropbox).** Builds can hit locked files; set
  `MAGAT_BUILD` outside the folder. Keep `MAGAT_DATA` outside it too, as it is by default.
- **Which levels.** `--levels` when named; else the levels of the file a calibration extends or checks;
  else the game's published `games/<game>/levels.json` (for Tribes 9,500 / 20,000 / 101,500 units: 500,
  1000 and 5000 ms on the R7615, found by `calibrate-time`); else 40,000 / 80,000 / 400,000. The launcher
  passes the published ones as `--default-levels`. `weights` reports a file's coverage at its own levels.
- **Memory is not what limits the thread count.** A Tribes worker holds 5-35 MB; the rest of the heap is
  the engine's short-lived garbage (2-3 GB a second per worker), and the heap size only sets how often it
  is collected. Cores, and the memory bandwidth that garbage takes, are the limits; the thread sweep
  measures them. A laptop with 16 GB runs its full thread count in the launcher's heap.
- **Timing measurements need an idle machine.** Calibrations, `check overhead` and estimates are timing
  measurements. Runs are not: their results are counts, and only their wall-clock column feels the load.
- **Commit before a worktree can disappear.** The shakedown and the notebook self-test are cheap; run
  them before every commit that touches the CLI or the notebook.
