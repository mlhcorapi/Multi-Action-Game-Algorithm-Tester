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
currency begun at other levels continues under its own name and a new one needs a new `--name`.

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
python magat.py calibrate-controlled --name laptop-i9-14900hx-time --preflight
```

```bash
python magat.py calibrate-controlled --name laptop-i9-14900hx-time
```

1. `--preflight` reads the machine, changes nothing, measures nothing, and says READY or NOT READY and
   what the run would measure.
2. Without it, it measures the whole currency in one session, then runs the checks.

`--checks` runs only the checks, against the currency as it is; `--no-checks` leaves them out.

**What it does:**

1. **Preflight.** It refuses a machine on battery or in battery saver, a second JVM, a memory limit under
   6 GB, and files it cannot read, such as online-only OneDrive files once the network is off.
   Everything it reads goes into `preflight.json`; the screen shows its warnings.
2. **Controls.** These apply to the measuring JVMs only; nothing on the machine is changed.
   - On a hybrid CPU (this laptop), the JVMs run on the performance cores, so the measured thread never
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

This is the setting that matters most on a laptop: its clock follows its temperature. In toy runs on
this laptop (2026-10-07, boost on, other programs open), the busiest performance core ran between 3.7
and 5.7 GHz within single sessions, with thermal zones up to 94 °C. Pick one policy, and keep it for
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
4,225. Read the spread, not the level. Under policy A the clock is flat (this laptop's first calibration:
4,180-4,250 MHz within every section, against 3,700-5,700 with boost on), and the stability check settles
within a few estimates. A clock that wanders by hundreds of MHz, or reaches boost speeds, means the cap
is not honoured: use B. If the stability check will not settle even with a flat clock, lower the cap,
for example to 3500. The thermal zones are the firmware's ACPI zones, not the CPU's own
sensor; read them as a trend.

## On a shared server (the R7615, through JupyterHub)

The notebook's calibration stage is the server's path. It has one cell with the parameters and the
preflight, one cell that measures the currency and checks it, and one that confirms every reference arm
is priced and bundles the record. The measuring cell skips itself once the currency exists. A session
cut short wrote nothing, and running the cell again measures it again. The plan prints the expected
finishing time after the stability check.

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
   machine right now. Before measuring, the cell waits up to `CAL_WAIT_QUIET` minutes (30) for other work
   to drop below `CAL_OTHER_CPUS` logical CPUs (4). If it does not, the cell refuses to start, and you
   run it again later. A session that saw other work anyway is SUSPECT; measure it again under a new
   `CURRENCY_NAME`.
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

Results computed under it quote its name.

## The development laptop

**Machine.** HP OMEN 16 (16-am0xxx), Intel Core i9-14900HX, Windows 11, JDK 26:
- 8 performance cores with SMT, logical CPUs 0-15;
- 16 efficient cores, logical CPUs 16-31;
- Modern Standby.

**As found on 2026-10-07.**
- The Balanced plan, with *Best performance* when plugged in.
- Boost aggressive, and no frequency cap.
- Up for 22 days.
- Windows Update's pause had expired.
- OneDrive, Claude, two browsers and Steam were running.

**Its first calibration** (2026-10-07 to 2026-10-09, at the earlier levels 40,000 / 80,000 / 400,000,
under policy A):
- The busiest core held about 4,120-4,250 MHz at no more than 43 °C.
- 33 arms in four sections took 3.9 h; every section and both checks passed.
- Checked against the R7615's currency of the time, its turn prices drifted by at most 3.6%.
- Its reference advance measured at 0.66-0.76 of the R7615's.
- On battery the same calibration was estimated at 11.4 hours, against 3.7 plugged in.

That currency is archived ([`ARCHIVE.md`](ARCHIVE.md)). The laptop's currency at the published levels,
on the current engine, is measured under a new name:

```bash
python magat.py calibrate-controlled --name laptop-i9-14900hx-time --preflight
```