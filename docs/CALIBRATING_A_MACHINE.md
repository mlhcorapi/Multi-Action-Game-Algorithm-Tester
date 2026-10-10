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
and the notebook take them by themselves. A currency keeps the levels and the initialization policy it
was first measured with, so a currency begun at other levels continues under its own name and a new one
needs a new `--name`.

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
python magat.py calibrate-controlled --name laptop-i9-14900hx --preflight
```

```bash
python magat.py calibrate-controlled --name laptop-i9-14900hx --plan
```

```bash
python magat.py calibrate-controlled --name laptop-i9-14900hx
```

1. `--preflight` reads the machine, changes nothing, measures nothing, and says READY or NOT READY.
2. `--plan` lists the sections and what is already priced.
3. With neither, it measures every section that is not yet priced, then runs the two checks.

`--section N` measures one section; that is how the server notebook runs it, one cell per section.
`--section N --redo` measures a section again, and `--section checks` runs only the checks.

**What it does:**

1. **Preflight.** It refuses a machine on battery or in battery saver, a second JVM, a memory limit under
   6 GB, and files it cannot read, such as online-only OneDrive files once the network is off.
   Everything it reads goes into `preflight.json`.
2. **Controls.** These apply to the measuring JVMs only; nothing on the machine is changed.
   - On a hybrid CPU (this laptop), the JVMs run on the performance cores, so the measured thread never
     lands on an efficient core at half the speed.
   - On a server with several L3 caches (the R7615's EPYC has eight), they run on the cache domain that
     was quietest when the invocation started, so the measured thread never migrates between caches.
   - On Windows they also run at high priority with power throttling (EcoQoS) off, and the machine is
     kept awake with the display on (`--let-display-sleep` lets the display turn off).
3. **Sections.** The arms are divided into sections of 8 (`--batch`), planned once per currency and
   numbered for good. Each section is one `magat calibrate` session. The first section run creates the
   currency, and each later one merges into it by ratio, exactly as `calibrate --arms <new>` always has.
4. **A stability check before each section.** It runs `calibrate --estimate`, which writes nothing, back
   to back until two estimates in a row agree within 3%. On a laptop the first of these runs for at
   least 10 minutes, which also warms the machine up (`--soak-minutes`). `--wait-quiet N` holds a
   section for up to N minutes until other work on the machine drops below `--other-cpus`. If it does
   not drop, nothing is started.
5. **A verdict after each section:**
   - **PASS:** the calibrator's gates passed, and the monitor saw nothing disturbing it.
   - **SUSPECT:** the gates passed, but the monitor saw one of these:
     - other work above `--other-cpus` logical CPUs, sustained for 30 seconds;
     - CPU throttling of the container;
     - a section whose reference advance ran more than 5% away from the first section's speed
       (`--speed-tolerance`).

     Measure a suspect section again with `--redo` when the machine is quiet.
   - **FAIL:** the gates failed and nothing was written. A failed section is retried once.
6. **Checks**, which write nothing:
   - this machine against the published R7615 currency;
   - this machine against its own new currency, at the end. After hours of work it should still agree
     with itself.
7. **A monitor**, every 10 seconds, throughout:
   - the power source; a machine that leaves mains power has its section stopped;
   - the CPU used by anything other than the calibration, naming the programs when it stays high;
   - the container's throttling;
   - the busiest measuring CPU's clock;
   - the temperature, where it can be read.
8. **Summaries.** `record/SUMMARY.md` is cumulative: every section's latest verdict, the checks, and the
   `--redo` command for each suspect section. Every invocation also keeps its own folder with
   `SUMMARY.md`, `events.log`, `monitor.csv`, the logs and each section's CSVs. A zip of everything goes
   to `<data>/bundles/calibration-<game>-<name>.zip`.

**Why sections.** A session writes all or nothing. Run as one session, a full calibration is 3.7 hours
on this laptop and about 5 on the R7615. That long, one noisy cell, a machine that has slowed by the
end, or a Jupyter server culled mid-run discards all of it. A section of 8 arms is about an hour on the
laptop and about 1.5 hours on the R7615. Each section is bracketed on its own and costs about 12 more
minutes of warm-ups and reference cells.

**Resuming.** Run the same command again: it measures only what the currency does not price, and skips
checks already recorded (`--redo-checks` repeats them). The currency's clock policy is locked by its
first section that passes, and continuing under a different policy is refused. A merge cancels a
uniform change of speed, but not a change in how compute-bound and memory-bound work scale against
each other.

**Output.** Everything goes under the data root, outside the clone, in
`<data>/calibration/<game>-<name>/`: `compute_weights.properties`, the last section's
`compute_profiles.csv` and `compute_operations.csv`, any `threads.json`, and `record/`.

**Exit codes.**

| Code | Meaning |
|---|---|
| 0 | complete, or the section passed |
| 1 | a section failed twice, or the stability check never settled; run it again |
| 2 | refused |
| 3 | stopped: the machine went on battery |
| 4 | the machine stayed busy and nothing was started |
| 5 | a section is suspect, or the self-check failed |
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

| Policy | The clock | A full calibration on this laptop | Use it when |
|---|---|---|---|
| **A. Performance cores capped below what the cooling sustains (recommended)** | flat at the cap | roughly 5-6 h (an estimate; the stability check prints the real figure) | you want the most controlled conditions without doubling the time |
| B. Boost disabled | flat at the base clock | roughly 10 h | the cap is not honoured, or the stability check will not settle under A |
| C. As shipped: boost on, no cap | follows the temperature | about 5 h (measured pace, 2026-10-06) | you accept some drift; the checks and the brackets measure how much |

With a cap well below the sustained single-core clock, temperature no longer moves the clock. A cell
measured at the end of a section then costs what it would have cost at the start.

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
for every section. On Windows that clock is approximate: it is derived from the processor's performance
counter and the nominal frequency in the registry. On this laptop, capped at 4,000 MHz, it reads about
4,225. Read the spread, not the level. Under policy A the clock is flat (this laptop's first calibration:
4,180-4,250 MHz within every section, against 3,700-5,700 with boost on), and the stability check settles
within a few estimates. A clock that wanders by hundreds of MHz, or reaches boost speeds, means the cap
is not honoured: use B. If the stability check will not settle even with a flat clock, lower the cap,
for example to 3500. The thermal zones are the firmware's ACPI zones, not the CPU's own
sensor; read them as a trend.

## On a shared server (the R7615, through JupyterHub)

The notebook's calibration stage is the server's path. It has one cell with the parameters, the
preflight and the plan, then one cell per section, a cell for anything still missing, and a checks
cell. Each section cell skips itself once its section is priced. Run them one at a time: a section is
about 1.5 hours on the R7615, and the whole currency, with the checks, about 7 to 8 hours spread over
as many sittings as you like. The plan cell prints the real figures after its first estimate.

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

1. **Run sections when the machine is quiet:** nights and weekends. The preflight prints the work on
   the machine right now. Each section cell waits up to `CAL_WAIT_QUIET` minutes (30) for other work to
   drop below `CAL_OTHER_CPUS` logical CPUs (4). If it does not, the cell refuses to start, and you run
   it again later. A section that saw other work anyway is SUSPECT; measure it again with
   `calibrate_section(k, redo=True)`.
2. **Read the preflight's line about other users.** Many JupyterHub containers see the host's `/proc`,
   so the monitor sees other users' work and names it "not visible from here". Then a quiet machine is
   something the script can verify. If the preflight says all the work is this container's, the machine
   may be quiet, or `/proc` may show only this container. In the second case other users are invisible:
   the stability check, the brackets and the speed of each section against the first are the only
   evidence. Rely on them, and consider measuring one section a second time on another day to compare.
3. **Start nothing else in your server while a section runs.** No other notebook executing, no
   terminal job, no `magat run`. The preflight counts the other Jupyter kernels; shut down the
   notebooks you are not using.
4. **Keep the tab open while a section runs.** If the hub culls idle servers, a running cell usually
   counts as activity, but a server's maximum age can still end it. If that happens mid-section,
   nothing is written: run the cell again.
5. **Pick the largest server the hub offers** when you start it, if there is a choice of profiles. More
   guaranteed CPUs means no throttling of the JVM's compiler and collector threads, and at least 6 GB of
   memory is needed. The preflight prints the limits it finds. Any throttling during a section makes it
   SUSPECT.
6. **Ask the administrators.** You cannot change these yourself, but each one helps:
   - Is the R7615 shared, and when is it quiet?
   - Could it be reserved for you for a night?
   - Does your server have a CPU limit (CFS quota), and could it get exclusive cores? On Kubernetes that
     is the static CPU manager with a Guaranteed pod.
   - What are the idle-culling timeout and the maximum server age? Is a 1.5-hour cell safe?
   - Which frequency governor does the machine run, and is boost on?
7. **Keep everything else equal.**
   - Use the same JDK as your runs.
   - Leave the heap at MAGAT's default, 4 GB, which the laptop's currency uses too: do not set
     `MAGAT_HEAP`. The thread sweep does not need it either; the launcher sizes the sweep's heap to its
     workers, within the container's memory limit.
   - Use the portfolio the notebook builds and verifies against the published one.

**What to expect from the check against the published currency.** The R7615 currency was measured on
2026-08-28 by the thesis code, and the new one is measured on the same machine by MAGAT. The
differences are known in advance:
- The thesis ran a 100 GB heap with `AlwaysPreTouch`; MAGAT runs 4 GB.
- The thesis timed through the engine's hooks; MAGAT times through its decorator. On the laptop that
  shifted evaluations by about 3% (`games/tribes/calibration/r7615/ORIGIN.md`).
- Version 2 prices each arm's own operations.
- The JDK may differ.

The check gates each cell's turn price within 10%. Read a FAIL here as a finding about the old
currency, not a fault of the new one. The checks cell also records this machine's metering overhead
(`check overhead`), which `ORIGIN.md` lists as not yet measured on the R7615.

## Reading the result

`record/SUMMARY.md` has a row per section:
- **The latest verdict.**
- **The bracket**, which says how far the reference cell moved between the section's start and end. The
  gate is ±10%; on a steady machine it is within a few percent.
- **Speed vs first:** the section's reference advance as a multiple of the first section's. The merge
  cancels it, and anything between 0.98 and 1.02 says every section ran at the same speed.
- **The worst sustained other work.**
- **The busiest measuring CPU's clock and temperature.**

Then come the checks.

What to do about what it finds:

- **SUSPECT.** Read the reasons, remove their cause, and measure the sections again in one invocation:
  `--section 2,3,4 --redo` (the summary prints the command). If a section is suspect again for the same
  reason, the cause has not gone. Decide whether that reason matters for this section. If it was a short
  burst of other work, the gates that passed say probably not.
- **Every section after the first suspect for its speed, by a similar amount**, on a machine that was
  steady and quiet: that was a calibrator bug, found by the laptop's first calibration and fixed on
  2026-10-08. A merge used to measure its reference cell under the new file's own prices, a different
  search. Measure those sections again with the fixed calibrator.
- **FAIL.** Run the same command again.
- **The self-check failed.** The machine changed speed during the run. Look at the clocks and the
  speeds across sections. Usually this calls for a tighter clock policy (laptop) or quieter hours
  (server), and a fresh currency under a new `--name`.

## Publishing it

The R7615 file stays the published currency. A new currency is published beside it, never over it,
in `games/tribes/calibration/<name>/` (for example `r7615-time/` or `laptop-i9-14900hx/`). That folder
holds:
- its `compute_weights.properties`;
- `record/SUMMARY.md`, and usually the rest of `record/` except the raw `counters.csv` files, which
  `monitor.csv` summarises;
- an `ORIGIN.md`, which says the machine and the date, the clock policy (and, on a laptop, the maker's
  performance mode), the sections and their verdicts, both checks, and the commit.

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

**Measured pace** (2026-10-06, plugged in, Balanced, boost on).

| Budget level | Seconds per turn of the reference arm |
|---|---|
| 40k | 2.49 |
| 80k | 7.49 |
| 400k | 33.36 |

At that pace:

| Run | Time |
|---|---|
| Everything in one session | 3.7 h |
| Four sections of 8 arms | 4.3 h |
| The two checks | 0.6 h |
| **Total under policy C, with the stability checks** | **about 5 h** |

On battery the same estimate was 11.4 hours.

**The R7615**, for comparison: on 2026-08-28 the thesis's calibration of 54 cells took 1 h 59 min, and
the laptop's reference advance has measured at 0.66-0.76 of the R7615's.

**Its calibration** (2026-10-07 to 2026-10-09), under policy A:
- The busiest core held about 4,120-4,250 MHz at no more than 43 °C.
- The four sections took 3.9 h; every section and both checks passed.
- Checked against the R7615 currency, its turn prices drifted by at most 3.6%.
- The first attempt's sections 2-4 were SUSPECT because of the calibrator bug described under "Reading
  the result". They were measured again with the fix.

It is published in `games/tribes/calibration/laptop-i9-14900hx/`, and `ORIGIN.md` there has the record.
It was measured on the engine before `828b130`, which made legal-action generation faster (design §15.3):
on the new engine an advance is about 19% faster at 40k and 80k, which moved the turn prices of `emcts`
and `emcts-comma` there by -4.1% to +3.0%, inside the drift gate; 400k has not been measured cleanly yet.
Before pricing anything new in it, run the self-check on mains:
```bash
python magat.py calibrate --check --game tribes --arms emcts,emcts-comma --weights games/tribes/calibration/laptop-i9-14900hx/compute_weights.properties
```
If a cell is past the gate, measure a fresh currency under a new name.