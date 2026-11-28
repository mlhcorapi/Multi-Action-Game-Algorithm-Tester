# Calibrating a machine

A currency is a list of prices. Each price is the cost of one operation of one arm at one budget, as a
ratio to one reference advance measured in the same session. A good currency needs three things:

- **One speed for the whole session.** Every cell is compared with the reference cell measured at the
  start of its session. If the machine slows by 5% halfway through, every later cell is priced 5%
  too high.
- **Nothing else running.** Other work slows some cells and not others. It also slows memory-bound work
  (copies, evaluations) more than advances, which bends exactly the ratios being measured.
- **Conditions written down.** A later `calibrate --check` on the same machine has to reproduce them, or
  it reports drift that is really a change of settings.

**The levels come first, and they are published.** A currency is measured at the game's published
levels, `games/<game>/levels.json`: for Tribes 9,500 / 20,000 / 101,500 units, which are 500, 1000 and
5000 ms of a whole turn on the R7615 ([units as time](CURRENCY.md#units-as-time)). `calibrate-controlled`
and the notebook take them by themselves. A currency keeps the levels it was first measured at, so a
currency begun at other levels continues under its own name and a new one needs a new `--name`. A level
beyond them, such as a longer thinking time for one experiment, is added only on purpose
([gaining a level](#gaining-a-level)).

The levels are measured on the reference machine alone, with `magat calibrate-time`. That is a
maintainer's job, done from `notebooks/maintainers/measure_levels.ipynb` and only after a change to what a
turn costs, such as a new engine commit. New levels mean every currency must be measured again, so the
tool refuses to run without `--remeasure` once a game's levels are published, and the main notebook never
runs it.

The calibrator enforces what it can see. A cell's two fastest repetitions must agree within 10% (the
gap gate), and every session measures its reference cell again at the end (the bracket). A session
that fails either writes nothing. What it cannot see is the machine itself: the power source, the
clock policy, other programs and other users, the temperature. That is what `calibrate-controlled`
adds.

## The command

```bash
python magat.py calibrate-controlled --name <machine> --preflight
```

```bash
python magat.py calibrate-controlled --name <machine>
```

`--name` names the currency, for example `laptop-i9-14900hx-time`.

1. `--preflight` reads the machine, changes nothing, measures nothing, and says READY or NOT READY and
   what the run would measure.
2. Without it, it measures the whole currency in one session, then runs the checks.

`--checks` runs only the checks, against the currency as it is; `--no-checks` leaves them out.

### A config file

The options can be written in a JSON file instead, kept beside your experiments and read again for the
next session. Each key is an option without its dashes:

```json
{
  "name": "my-server-time",
  "game": "tribes",
  "arms": ["emcts", "mcts", "oep", "tbets"],
  "levels": ["500ms", "1000ms", "5000ms"],
  "soak-minutes": 0,
  "wait-quiet": 30,
  "other-cpus": 4
}
```

```bash
python magat.py calibrate-controlled --config calibrations/tribes/examples/server.json --preflight
```

```bash
python magat.py calibrate-controlled --config calibrations/tribes/examples/server.json
```

- **Lists** (`arms`, `levels`, `check-arms`) are JSON lists. A level is units or a published level's name
  (`"500ms"`, from `games/<game>/levels.json`). A flag such as `toy` or `allow-online` is `true` or `false`.
- **Without `arms` or `levels`**, the session prices the game's reference arms (`@reference`) at the
  published levels; an arm registered beyond them, an experiment's, is priced when named. The reference cell, `emcts` at the lowest level, is always measured, because it defines the unit.
- **An option on the command line wins** over the file, so one file serves several sessions:
  `--config server.json --name r7615-again`.
- **The modes are not in the file.** `--preflight`, `--checks` and `--no-checks` say what one invocation
  does, so they are given on the command line.
- **Nothing is ignored.** A misspelt key, a mode, a level that is not published, or a value of the wrong
  kind refuses the whole file and names every problem, before anything is measured.
- **The file goes into the record** as `config.json`, beside the session's `SUMMARY.md`, which names it.

[`calibrations/tribes/examples/`](../calibrations/tribes/examples/) holds a laptop's config and a shared
server's. The server's uses the settings the notebook passes.

### Extending a published currency

To price new arms on the reference machine without measuring its published arms again, start the currency
as a copy of the published one with `--from` (`"from"` in a config file):

```bash
python magat.py calibrate-controlled --name r7615-plus-mine --from r7615-time --arms mine --preflight
```

- The currency starts as a byte copy of `games/<game>/calibration/<NAME>/compute_weights.properties`, so
  it keeps every published price, and the session prices only the arms the copy lacks. They are merged by
  ratio, in the published unit, and the checks then run as usual.
- The record's `from.json` names the copy's source and its hash.
- A currency already in the folder must be that copy, extended: every line of the published file still in
  it, but the `operations` line, which a merge extends. Otherwise `--from` refuses, naming the first line
  that changed.
- Run on another machine, the new arms' prices carry that machine's relative speeds
  ([cross-machine](results/cross-machine.md)). On the reference machine itself, they are what its own
  calibration would have written.
- A run in such a currency can still take the published run's leaves
  ([reusing the published run](EXPERIMENTS.md#reusing-the-published-run)), since every published price
  is unchanged.

### Gaining a level

A currency prices its arms at its own levels, the `levels` line of its header. To price a level beyond
them, such as 30 seconds for one experiment, say so with `--gain-levels` (`"gain-levels": true` in a
config file):

```bash
python magat.py calibrate-controlled --name r7615-plus-mine --from r7615-time --arms emcts,mine --levels 610000 --gain-levels --preflight
```

- **Without it, a level the currency does not list is refused**, by the preflight and by the calibrator,
  so a mistyped level cannot extend a currency.
- **The new level is measured on the currency's own positions.** Turns per repetition select which
  positions a level measures; the new level takes the turns of the currency's nearest level below it (the
  lowest's, for a level below them all; in a published currency they are all 16), so it measures the
  same material as the others.
- **The header is extended and every other line kept.** `levels` and `turnsPerRep` gain the new level,
  the `operations` line grows as a merge extends it, and every price already there is unchanged. A copy
  made with `--from` that has gained a level is still that currency, extended, as long as every
  published level keeps its turns.
- **Price the reference arm at the new level too.** A multiplier at a level is measured against the
  reference arm at that level, so a level priced without it cannot be spent; the preflight lists it among
  what the session would measure.
- **Once gained, the level is the currency's own**: a later session at it needs no flag, and one that
  prices every arm named at it measures nothing.
- **A session prices only what its arms lack.** Naming every level, `--levels 9500,20000,101500,610000`,
  prices an arm already priced at the first three at the new one alone. A session prices every arm it
  names at every level it names, so its arms must lack the same levels: an arm new at every level beside
  one lacking only the gained level is refused by the preflight, as two sittings.
- **One sitting per level.** A session writes all or nothing, so a long level measured with the others
  risks them all. The notebook takes a list of configs of one currency, one sitting each
  ([on a shared server](#on-a-shared-server-the-r7615-through-jupyterhub)).

**What it does:**

1. **Preflight.** It refuses a machine on battery or in battery saver, a second JVM, a memory limit under
   6 GB, and files it cannot read, such as online-only OneDrive files once the network is off.
   Everything it reads goes into `preflight.json`; the screen shows its warnings.
2. **Controls.** These apply to the measuring JVMs only; nothing on the machine is changed.
   - On a hybrid CPU (Intel's 12th generation on), the JVMs run on the performance cores, so the measured thread never
     lands on an efficient core at half the speed.
   - On a server with several L3 caches (the R7615's EPYC has eight), they run on the cache domain that
     was quietest when the invocation started, so the measured thread never migrates between caches.
   - On Windows they also run at high priority with power throttling (EcoQoS) off, and the machine is
     kept awake with the display on (`--let-display-sleep` lets the display turn off).
3. **A stability check.** It runs `calibrate --estimate`, which writes nothing, back to back until two
   estimates in a row agree within 3%. On a laptop this runs for at least 10 minutes, which also warms the
   machine up (`--soak-minutes`). `--wait-quiet N` holds the start for up to N minutes until other work on
   the machine drops below `--other-cpus`. If it does not drop, nothing is started. The plan it prints
   then says when the run should finish.
4. **The session.** Every arm the game registers, in one `magat calibrate` session, which writes all or
   nothing. Over a currency that lacks an arm (one registered since it was measured), it prices that arm
   in one session and merges it into the currency by ratio, exactly as `calibrate --arms <new>` always has.
5. **A verdict:**
   - **PASS:** the calibrator's gates passed, and the monitor saw nothing disturbing it.
   - **SUSPECT:** the gates passed, but the monitor saw one of these:
     - other work above `--other-cpus` logical CPUs, sustained for 30 seconds;
     - CPU throttling of the container;
     - arms added to a currency more than 5% away from the speed of its first session (`--speed-tolerance`).

     The currency is written. Once the cause is gone, measure it again under a new `--name`.
   - **FAIL:** the gates failed and nothing was written. A failed session is retried once.
6. **Checks**, which write nothing:
   - this machine against its own new currency: after the whole session, it should still agree with itself;
   - this machine against the game's published currency, once there is one.
7. **A monitor**, every 10 seconds, throughout:
   - the power source; a machine that leaves mains power has its session stopped;
   - the CPU used by anything other than the calibration, naming the programs when it stays high;
   - the container's throttling;
   - the busiest measuring CPU's clock;
   - the temperature, where it can be read.
8. **The record.** The screen shows the verdict and a few lines. Each invocation keeps its own folder,
   `record/<date>-<time>/`, with `SUMMARY.md` (the conditions, the stability check, the session and the
   checks), `events.log`, `monitor.csv`, the calibrator's own output in `session.log` and `checks.log`, and
   the session's `compute_profiles.csv` and `compute_operations.csv`. `record/SUMMARY.md` is the latest
   invocation's. A zip of everything goes to `<data>/bundles/calibration-<game>-<name>.zip`.

**Running it again** measures nothing once the currency prices every arm, and `--checks` checks it again.
A session that did not finish wrote nothing, so the same command measures it again. The clock policy of
the session that wrote the currency is kept in `record/policy.json`, and a later session or check under
a different policy is refused: a merge cancels a uniform change of speed, but not a change in how
compute-bound and memory-bound work scale against each other, and a check would report the change as
drift.

**Output.** Everything goes under the data root, outside the clone, in
`<data>/calibration/<game>-<name>/`: `compute_weights.properties`, the session's `compute_profiles.csv`
and `compute_operations.csv`, any `threads.json`, and `record/`.

**Exit codes.**

| Code | Meaning |
|---|---|
| 0 | complete |
| 1 | the session failed twice, or the stability check never settled; run it again |
| 2 | refused |
| 3 | stopped: the machine went on battery |
| 4 | the machine stayed busy and nothing was started |
| 5 | the session is suspect, or the self-check failed |
| 130 | interrupted |

## On the laptop

These are the steps on the development laptop, an HP OMEN 16 with an Intel Core i9-14900HX (8
performance cores with SMT, 16 efficient cores) under Windows 11. On another laptop, follow them as far
as they apply.

### Before the run

Do these while still online, in order.

1. **Plug in and let the battery fill.** A charging battery adds heat, and on some laptops it takes part
   of the adapter's power. HP's adaptive charging may hold the battery at about 80%; it is then not
   charging, which is fine.
2. **Restart the machine.** Give start-up work about 15 minutes, then close whatever started on its own.
3. **Choose the clock policy** (next section) and set it.
4. **Set the power mode.** Windows' power mode for "Plugged in" is *Best performance* (Settings > System >
   Power & battery). In OMEN Gaming Hub > Performance Control, choose Performance, and maximum fan
   speed if it is offered. Keep both for every later check on this laptop. The script cannot read the
   hub's mode, so write it down.
5. **Close everything.** That means:
   - browsers;
   - chat and meeting apps;
   - Claude desktop;
   - editors and IDEs;
   - game launchers;
   - Docker and WSL.

   Quit OneDrive from its tray icon: a paused client still scans. The preflight lists what is still
   running.
6. **Pause Windows Update for a week** (Settings > Windows Update).
7. **Move automatic maintenance away from the run.** Windows runs it daily at 02:00 when the machine
   looks idle, and an unattended calibration looks idle. It includes Defender scans and update work.
   Change the time in Control Panel > System and Security > Security and Maintenance > Maintenance, or
   start early enough to finish first. The plan warns if the run would cross it.
8. **Find a steady place.**
   - A hard surface, with the vents clear.
   - Away from sun and heaters, in a room whose temperature will not change much.
   - The lid open.
   - No external display: on this laptop it can wake the discrete GPU.
9. **Run `--preflight`**, fix what it reports, then **turn on airplane mode**.
10. **Start the run from a terminal you opened yourself**, not from inside an editor or Claude, and leave
    that window visible.
11. **Watch the first stability check**, 10 to 15 minutes, until it prints `steady after ... min` and the
    expected finishing time. Then leave the machine alone.

### Clock policy

This is the setting that matters most on a laptop: its clock follows its temperature. On the
development laptop, with boost on, the busiest performance core ran between 3.7 and 5.7 GHz within a
single toy session, with thermal zones up to 94 °C. Pick one policy, and keep it for
this currency and for every later `calibrate --check` on this laptop. The script reads the policy,
records it, and locks it to the currency.

| Policy | The clock | How long a calibration takes | Use it when |
|---|---|---|---|
| **A. Performance cores capped below what the cooling sustains (recommended)** | flat at the cap | about 1.1-1.2 times as long as under C (the stability check prints the real figure) | you want the most controlled conditions without doubling the time |
| B. Boost disabled | flat at the base clock | about twice as long as under C | the cap is not honoured, or the stability check will not settle under A |
| C. As shipped: boost on, no cap | follows the temperature | the shortest | you accept some drift; the checks and the brackets measure how much |

With a cap well below the sustained single-core clock, temperature no longer moves the clock. A cell
measured at the end of the session then costs what it would have cost at the start.

Make a separate power plan for calibrating, so your everyday plan is untouched. In PowerShell
(administrator, if Windows refuses):

```powershell
$plan = (powercfg /duplicatescheme SCHEME_BALANCED) -replace '.*GUID:\s*(\S+).*', '$1'
powercfg /changename $plan "MAGAT calibration" "Performance cores capped at 4000 MHz"
powercfg /setacvalueindex $plan SUB_PROCESSOR PROCFREQMAX1 4000
powercfg /setacvalueindex $plan SUB_PROCESSOR PROCFREQMAX 4000
powercfg /setacvalueindex $plan SUB_PROCESSOR PERFEPP1 0
powercfg /setacvalueindex $plan SUB_PROCESSOR PERFEPP 0
powercfg /setacvalueindex $plan SUB_SLEEP STANDBYIDLE 0
powercfg /setactive $plan
$plan
```

What each line does:
- `PROCFREQMAX1` caps the performance cores (efficiency class 1 on this CPU).
- `PROCFREQMAX` caps the efficient cores, in case Windows applies it more widely. The JVM never runs
  on them.
- `PERFEPP` and `PERFEPP1` ask for full performance up to the cap.
- `STANDBYIDLE 0` stops the machine sleeping while plugged in.
- The last line prints the plan's GUID: keep it.

For policy B, also run:

```powershell
powercfg /setacvalueindex $plan SUB_PROCESSOR PERFBOOSTMODE 0
powercfg /setacvalueindex $plan SUB_PROCESSOR PROCFREQMAX1 0
powercfg /setacvalueindex $plan SUB_PROCESSOR PROCFREQMAX 0
powercfg /setactive $plan
```

Back to the usual plan:

```powershell
powercfg /setactive SCHEME_BALANCED
```

Keep the "MAGAT calibration" plan, and switch back to it before any later check on this laptop.

**Is the cap honoured?** The stability check prints the busiest core's clock, and the summary prints it
for the session and each check. On Windows that clock is approximate: it is derived from the processor's performance
counter and the nominal frequency in the registry. On this laptop, capped at 4,000 MHz, it reads about
4,225. Read the spread, not the level. Under policy A the clock is flat (on this laptop, 4,180-4,250 MHz
throughout a calibration, against 3,700-5,700 with boost on), and the stability check settles
within a few estimates. A clock that wanders by hundreds of MHz, or reaches boost speeds, means the cap
is not honoured: use B. If the stability check will not settle even with a flat clock, lower the cap,
for example to 3500. The thermal zones are the firmware's ACPI zones, not the CPU's own
sensor; read them as a trend.

## On a shared server (the R7615, through JupyterHub)

The notebook's calibration stage is the server's path. Its parameters cell names a calibration config
([above](#a-config-file)); by default `calibrations/tribes/examples/server.json`, whose settings are the
ones below. Its `name` names the currency, and `MAGAT_CURRENCY` overrides it. The stage has one cell with
the preflight, one cell that measures the currency and checks it, and one that confirms every arm the
config names is priced and bundles the record. The measuring cell skips itself once the currency prices
every arm the config names at every level. Over a currency that lacks an arm, such as one measured before
that arm was registered, it prices the missing arms and merges them in by ratio, and the preflight cell
says so first; to measure a whole new currency in one session instead, give the config a new `name`. A
currency an older MAGAT wrote, one that prices arms this MAGAT no longer registers, should be replaced,
not extended, and the preflight warns about one: move its whole folder
(`<data>/calibration/<game>-<name>`, its `record/` with it) aside, or give the config a new `name`.
Move the runs priced in it aside as well, since the runner will not continue a leaf in another currency.
A session cut short wrote nothing, and running the cell again measures it again. The plan prints the
expected finishing time after the stability check.

The parameters cell may name a list of configs instead of one, the sittings of one currency (they share its
`name`), such as the published levels first and a gained level after
([gaining a level](#gaining-a-level)). Each is its own session and writes when it completes. A sitting
already priced is skipped; one that fails does not stop the next, and the cell names it at the end, so
running the cell again measures only what is missing.
- **The checks run once.** Each sitting runs without them (`--no-checks`), and the checks against the
  published currency and against the new one run once, after the last sitting is written. Run after every
  sitting, a check of a currency with a long level would play the reference arm at it each time.
- **A sitting between runs.** An entry `("calibrate", config)` in the notebook's experiments list measures
  that sitting there: a long level after the runs at the published ones, so that a night cut short has
  those by morning. The preflight cell preflights it with the others, before anything is measured.

### What you cannot change

A notebook server is a container on a shared machine, so you cannot:
- change the clock policy or the frequency governor;
- isolate cores;
- raise the JVM's priority;
- stop other users' jobs.

The script reads what it can of these and records it. From inside the container, it records:
- the CPUs the container may use, its CPU limit and memory limit;
- the frequency governor;
- the L3 domains;
- the measuring CPU's clock;
- the container's throttled time;
- the work on the machine.

### What you can do

1. **Measure when the machine is quiet:** nights and weekends. The preflight prints the work on the
   machine right now. Before measuring, the cell waits up to the config's `wait-quiet` minutes (30) for
   other work to drop below its `other-cpus` logical CPUs (4). If it does not, the cell refuses to start,
   and you run it again later. A session that saw other work anyway is SUSPECT; measure it again under a
   new `name`.
2. **Read the preflight's line about other users.** Many JupyterHub containers see the host's `/proc`,
   so the monitor sees other users' work and names it "not visible from here". Then a quiet machine is
   something the script can verify. If the preflight says all the work is this container's, the machine
   may be quiet, or `/proc` may show only this container. In the second case other users are invisible:
   the stability check, the bracket and the self-check are the only evidence. Rely on them, and consider
   running `--checks` again on another day to compare.
3. **Start nothing else in your server while the calibration runs.** No other notebook executing, no
   terminal job, no `magat run`. The preflight counts the other Jupyter kernels; shut down the
   notebooks you are not using.
4. **Keep the tab open while it runs.** If the hub culls idle servers, a running cell usually counts
   as activity, but a server's maximum age can still end it. If that happens mid-session, nothing is
   written: run the cell again.
5. **Pick the largest server the hub offers** when you start it, if there is a choice of profiles. More
   guaranteed CPUs means no throttling of the JVM's compiler and collector threads, and at least 6 GB of
   memory is needed. The preflight prints the limits it finds. Any throttling during the session makes
   it SUSPECT.
6. **Ask the administrators.** You cannot change these yourself, but each one helps:
   - Is the R7615 shared, and when is it quiet?
   - Could it be reserved for you for a night?
   - Does your server have a CPU limit (CFS quota), and could it get exclusive cores? On Kubernetes that
     is the static CPU manager with a Guaranteed pod.
   - What are the idle-culling timeout and the maximum server age? Is a cell that runs for an hour or
     two safe?
   - Which frequency governor does the machine run, and is boost on?
7. **Keep everything else equal.**
   - Use the same JDK as your runs.
   - Leave the heap at MAGAT's default, 4 GB, which the laptop's currency uses too: do not set
     `MAGAT_HEAP`. The thread sweep does not need it either; the launcher sizes the sweep's heap to its
     workers, within the container's memory limit.
   - Use the portfolio the notebook builds and verifies against the published one.

**The check against the published currency** runs once the game's reference machine has published
one. It gates each cell's turn price within 10%, and says how far this machine's prices are from the
reference machine's.

## Reading the result

`record/SUMMARY.md` starts with the verdict, then the conditions, the stability check, and the session:
- **its verdict**, with the reasons for a SUSPECT one;
- **the bracket**, which says how far the reference cell moved between the session's start and end. The
  gate is ±10%; on a steady machine it is within a few percent;
- **speed vs the file**, for a session that added arms to a currency: its reference advance as a multiple
  of the currency's first session's. The merge cancels it, and anything between 0.98 and 1.02 says the
  two ran at the same speed;
- **the worst sustained other work**;
- **the busiest measuring CPU's clock and temperature**.

Then come the checks.

What to do about what it finds:

- **SUSPECT.** Read the reasons, and remove their cause. If it was a short burst of other work, the
  gates that passed say it probably did not matter; otherwise measure the currency again under a new
  `--name`.
- **FAIL.** Run the same command again.
- **The self-check failed.** The machine changed speed during the run. Look at the clocks and the
  bracket. Usually this calls for a tighter clock policy (laptop) or quieter hours (server), and a fresh
  currency under a new `--name`.
- **The check against the published currency failed.** Not a fault, and the calibration still
  completes: another machine prices some operations differently relative to an advance, which is why
  each machine measures its own. The drift table says by how much. Run with your own currency; the
  published one is the reference machine's.

## Publishing it

A currency is published in `games/<game>/calibration/<name>/`, beside the others and never over one.
That folder holds:
- its `compute_weights.properties`;
- `record/SUMMARY.md`, and usually the rest of `record/` except the raw `counters.csv` files, which
  `monitor.csv` summarises;
- an `ORIGIN.md`, which says the machine and the date, the clock policy (and, on a laptop, the maker's
  performance mode), the session and its verdict, both checks, and the commit.

The reference machine's levels are published as `games/<game>/levels.json`, with its `SUMMARY.md` as
`games/<game>/levels.md` and the rest of the measurement in `games/<game>/levels-record/`. A currency's
`ORIGIN.md` names the levels it was measured at.

Results computed under it quote its name. `python magat.py export --currency <name>` makes the currency a
file to send, and `export <experiment>` a run with the currency it was priced in
([sharing](SHARING.md)).
