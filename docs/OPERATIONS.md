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
| `test [--java \| --python]` | JUnit, then the analysis package's unit tests: one line, and each failure when there is one. Everything the tests print is in `<data>/logs/test.log` |
| `layering` | the core imports no game and no engine |
| `shakedown` | every moving part at toy size, about ten minutes; exits non-zero on any failure. Run it before every pull request |
| `arms --game G [--arms a,b\|@set \| --default]` | the registered arms, their oracles, which arm is the reference and which are not priced; `--default` the arms a calibration prices when it names none (the game's `@reference`) |
| `machine` | cores, the worker ceiling and why; runs no work |
| `portfolio build \| freeze \| verify` | [the portfolio](METHODS.md#1-the-portfolio) |
| `threads` | the thread sweep: the knee of sustained throughput per budget level, into `threads.json` |
| `calibrate`, `calibrate --estimate`, `calibrate --check` | [the currency](CURRENCY.md#calibrating) |
| `export <experiment \| run dir> [--name N] [--no-curves]`, `export --currency NAME` | a result, or a currency, as one file to share, with a manifest of hashes ([sharing](SHARING.md)) |
| `import <file> [--replay N] [--no-check]`, `import --list` | a package checked against its manifest, unpacked under `imports/`, and re-played here |
| `paper [--check]` | every table and figure of the paper, from the published runs ([paper](../paper/README.md)) |
| `calibrate-controlled` | a calibration in one session, with reliability checks ([guide](CALIBRATING_A_MACHINE.md)); `--config FILE` reads its options from a JSON file |
| `calibrate-time --remeasure [--times 500,1000,5000]` | the levels that are those milliseconds of thinking time on this machine: a maintainer's tool for the reference machine, refused without `--remeasure` once the game's levels are published ([units as time](CURRENCY.md#units-as-time)) |
| `weights --weights F [--game G --arms ...] [--progress]` | what a weights file prices; exit 1 if anything asked for is missing. With `--progress`, a probe of a calibration under way: what is still to price, and exit 0 |
| `check contract \| equivalence \| replay \| audit` | the gates (below) |
| `check time (--weights F \| --fixed-weights) [--levels ...] [--arms ...] [--targets ms,...]` | single-threaded whole turns over positions spread across the portfolio: each level's thinking time on this machine, and each cell's mean spend against its level, flagged above 1.10× |
| `experiment validate SPEC [--weights F \| --no-weights]` | the spec validator alone, then the spec read back and the run's estimated hours |
| `experiment run SPEC [--split S] [--weights F] [--out D] [--results D] [--no-bundle] [--no-reuse]` | validate, run (resuming), analyse, summarise and bundle, in one command; in a published currency, the published run's leaves are taken where they may be ([experiments](EXPERIMENTS.md#reusing-the-published-run)) |
| `run --experiment SPEC --split dev\|holdout\|all` | run an experiment (below) |
| `analyze --run DIR [--experiment SPEC] [--baseline ARM] [--reprice W] [--compare DIR] --out DIR` | every measure, as tables and `SUMMARY.md`; with `--compare`, beside another machine's run of the same experiment |
| `bundle --name N --paths P...` | zip files into `<data>/bundles/N.zip` |
| `notebook build \| selftest` | regenerate the notebook; self-test its setup cells from three directories |

Commands that need a portfolio default to the game's published one (`games/<game>/portfolio/v1`), and
commands that take levels default to its published levels (`games/<game>/levels.json`), and commands that
spend a currency default to its published one (for Tribes, the R7615's `r7615-time`): pass `--weights` to
spend your own machine's. Every command prints a banner naming the
data root, the weights file in force and the portfolio identity, because those three are what every
past mistake turned on.

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
  - the MAGAT commit, marked `-dirty` when a tracked file other than a notebook has uncommitted changes.
    A notebook run in Jupyter saves its outputs into the file, so that is recorded apart, as
    `"notebook": "modified"`;
  - the game, the engine commit and the configuration;
  - the portfolio identity;
  - the weights file and its hash;
  - the spec's hash;
  - the machine and the JDK;
  - every invocation, and every retired leaf.
- `_completed.txt`, and `_running.lock` while a run is writing.
- One leaf per `(arm, budget)`: `<arm>/<budget>cu/`, holding:
  - `turns.csv`: one row per `(scenario, visit)`, with the values, the raw operation counts (`advances`,
    `copies`, `evals`, one `op_<name>` per declared operation), units, initialization spend, `plan_ms`,
    nodes, unique, duplicates, `seq_len` and the telemetry as `t_<key>`;
  - `curve.csv`: the checkpoints of an agent that records them (EMCTS: one per phase);
  - `_leaf.json`: the prices it ran under, threads, host, times, status and the audit.

## The notebook

`notebooks/run_magat.ipynb` is how a run is done on a server: one cell per stage, each self-skipping
once its output exists, each stage ending in a bundle. It is generated by `tools/make_notebook.py`, so
edit that, then run `python magat.py notebook build`. The notebook calls the launcher and nothing
else, so it cannot drift from the CLI. Its stages:

1. **Parameters.** The only cell to edit. It names this machine's calibration config
   ([guide](CALIBRATING_A_MACHINE.md#a-config-file)), or `None` and a published currency to spend
   instead, and the experiment specs to run ([experiments](EXPERIMENTS.md)). Adding a spec needs no new
   notebook.
2. **Setup and the plan.** Setup finds the repository from whatever directory the kernel started in; a
   Jupyter kernel's working directory is the notebook's own folder. It puts the data root outside the
   clone. The plan reads the files the parameters name, takes the currency's name from the config
   (`MAGAT_CURRENCY` overrides it), and prints what the notebook will do.
3. **Build, tests and layering.**
4. **Portfolio.** It builds this machine's own, freezes it, and verifies it against the published one.
5. **Machine, threads and calibration.** It reads the published levels, sweeps the thread counts, and
   measures this machine's currency with the config: a preflight, one cell that measures the currency in
   one session and checks it, and one that bundles it
   ([guide](CALIBRATING_A_MACHINE.md#on-a-shared-server-the-r7615-through-jupyterhub)). With no config,
   the three skip.
6. **Gates.** The contract, equivalence on every ported arm, and the audit.
7. **Experiments.** One cell runs every spec of the parameters through `experiment run`, in the plan's
   currency (it wins over a spec's own `weights`) and with this machine's thread sweep.
8. **The shakedown.**
9. **The inventory.** What to keep, with a tick per file and the bundles to download.

## Experiments and runs

An experiment is a JSON spec ([`docs/EXPERIMENTS.md`](EXPERIMENTS.md) describes every key). A spec names:
- the arms and the baseline;
- the levels, as units or a published level's name (`"500ms"`), with their `repetitions` (the older
  `visits`), each optionally with its own `arms`;
- the positions: the split, optionally narrowed by `sources`, `phases`, `ids` and a `scenarios` count;
- optionally the currency (`weights`, a published currency's name or a file) and the `contrasts` (every
  pair by default).

`magat experiment validate` checks it before anything runs:
- every arm is registered and priced at every level it runs, every declared operation included;
- the baseline is at two levels or more, or the budget multiplier cannot be computed (a warning);
- the positions it selects exist, belong to the split it runs, and are not none;
- every key is one it knows (an unknown key is a warning: a typo?).

It then reads the spec back (its levels in units, its positions) and estimates the run's hours.

```bash
python magat.py experiment run experiments/tribes/reference.json --weights <your-machine>
```

`experiment run` validates, runs, analyses, prints the headline and bundles
([experiments](EXPERIMENTS.md#running-it)). `run` and `analyze` are its steps on their own, for a
run tree or a report somewhere else:

```bash
python magat.py run --experiment experiments/tribes/reference.json --weights <your weights> --out $HOME/magat_runs/tribes/runs/reference
```

- **Resume by re-running the same command.** Each leaf is checkpointed per turn; a torn write is
  compacted on resume, and a finished leaf is skipped.
- **Never delete to re-run.** `--retire-leaf arm@budget` discards a leaf deliberately and logs it in
  `_run.json`.
- **Reuse another run's leaves.** `--reuse <split tree>` takes each leaf that run holds whole, played at
  this run's prices on the same portfolio and engine, after this machine re-plays two of its turns
  (`--reuse-replay N`) field for field ([experiments](EXPERIMENTS.md#reusing-the-published-run)).
- **Threads.** `--threads N`, `--threads-at 101500=31` per level, or by default the `threads.json`
  beside the weights file when it was swept on this CPU model, else this machine's worker ceiling: a
  currency published from another machine carries that machine's thread choices, which say nothing of
  this one. Results do not depend on the thread count; wall-clock does.

**Guards**, each a past mistake made structural:
- **The currency lock.** A leaf whose price changed in the weights file is refused until it is
  retired. A merge that only added arms changes no leaf.
- **The portfolio and configuration lock.** A tree is bound to one portfolio identity and one game
  configuration.
- **The identical-output assertion.** Two arms whose rows agree on every turn stop the run.
- **The audit.** Each priced leaf records the time its turns took against the time its priced work
  predicts. On one thread the run warns above `--audit-warn` (10%) of the plan time unpriced. On many
  threads the turns slow each other, so it prints the ratio as information (`took 1.21x the time its
  priced work predicts, on 62 threads`); the calibration's idle audit is the clean measure. An arm that
  is not priced, the random floor, is not audited.

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
python magat.py analyze --run $HOME/magat_runs/tribes/runs/<experiment> --out $HOME/magat_runs/tribes/results/<experiment>
```

`--run` takes a split directory or a run directory; each split a run directory holds (`dev/`,
`holdout/`) is analysed and reported. `--reprice <weights>` re-prices every leaf in another currency
([currency](CURRENCY.md#what-transfers-between-machines)). `--compare <run>` takes the same experiment's
run on another machine and adds, split by split, per arm and level: each machine's mean value with its
interval, the paired difference, both budget multipliers, the seconds per turn, and this run's turns
re-priced in the other machine's currency. That currency is found from the other run's provenance (its
recorded path, or a published currency with its hash), or named with `--compare-weights`. Runs on
different portfolios are refused. The output is:
- `tables/<measure>.csv`, each with a `Split` column first and a `currency` column last;
- `SUMMARY.md`: the provenance, then the headline in units and seconds, the budget multiplier, the
  spec's contrasts, the design, any re-pricing, and the scope notes;
- `report.json`, the same numbers for machines;
- `figures/`, when matplotlib is installed.

## The gates

| Command | What it asserts |
|---|---|
| `check contract` | the laws L1-L9 on captured positions ([contract](CONTRACT.md#the-laws-magat-check-contract)) |
| `check equivalence` | each ported arm reproduces its oracle (the reference implementation it was ported from) field for field, including its counters, on N scenarios × V visits. Without `--weights` both sides are priced with the fixed measurement weights, and so is an arm the currency does not price yet |
| `check replay` | the turns of a published leaf, re-planned on this machine, agree with its rows field for field |
| `check audit` | each arm's declared operations and forward model account for its turn: warns above 10% unaccounted, fails above 25% |

Each prints PASS or FAIL and exits non-zero on FAIL. The shakedown and CI run the contract, equivalence
and the audit at toy size.

## Environment variables

| Variable | Effect |
|---|---|
| `MAGAT_DATA` | the data root (default `~/magat_runs`) |
| `MAGAT_BUILD` | the build output, for example out of a synced folder whose client holds files open |
| `MAGAT_HEAP` | the JVM heap for every command. Unset, calibrations and checks get `4g`, so currencies are measured alike; portfolio builds, thread sweeps and runs get 1 GB plus 256 MB per worker (`MAGAT_MAX_THREADS`, else half the logical CPUs), at most half the memory available and at least 2 GB, and the launcher prints which. Set it only to override both |
| `MAGAT_MAX_THREADS`, `MAGAT_RESERVE_CORES` | the worker ceiling, and the cores held back |
| `MAGAT_<GAME>_ENGINE` | another engine tree instead of the submodule |
| `MAGAT_WORKERS`, `MAGAT_CURRENCY` | the notebook's worker count, and its currency's name over the calibration config's |

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
  1000 and 5000 ms on the R7615, found by `calibrate-time`); else, for a game with none published,
  40,000 / 80,000 / 400,000. The launcher passes the published ones as `--default-levels`. `weights`
  reports a file's coverage at its own levels.
- **Memory is not what limits the thread count.** A Tribes worker holds 5-35 MB; the rest of the heap is
  the engine's short-lived garbage (2-3 GB a second per worker), and the heap size only sets how often it
  is collected. Cores, and the memory bandwidth that garbage takes, are the limits; the thread sweep
  measures them. A laptop with 16 GB runs its full thread count in the launcher's heap.
- **Timing measurements need an idle machine.** Calibrations, estimates, `check audit` and `check time`
  are timing measurements. Runs are not: their results are counts, and only their wall-clock column
  feels the load.
- **Commit before a worktree can disappear.** The shakedown and the notebook self-test are cheap; run
  them before every commit that touches the CLI or the notebook.
