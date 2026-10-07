# Calibrating a machine

A currency is a list of prices. Each price is the cost of one operation of one arm at one budget,
as a ratio to one reference advance measured in the same session. A good currency therefore needs
three things:

- **One speed for hours.** Every cell in a session is compared with the reference cell measured at
  its start. If the machine slows by 5% halfway through, every later cell is priced 5% too high.
- **Nothing else running.** Other work slows some cells and not others. It also slows memory-bound
  work (copies, evaluations) more than advances, which bends exactly the ratios being measured.
- **Written-down conditions.** A later `calibrate --check` on the same machine must be able to
  reproduce them, or it will report drift that is really a change of settings.

The calibrator enforces what it can see. A cell's two fastest repetitions must agree within 10%
(the gap gate), and every session measures its reference cell again at the end (the bracket). A
session that fails either check writes nothing. What the calibrator cannot see is the machine
itself: its power source, its clock policy, other programs, the temperature. That is what
`calibrate-offline` adds.

## The command

```bash
python magat.py calibrate-offline --name laptop-i9-14900hx --preflight
```

```bash
python magat.py calibrate-offline --name laptop-i9-14900hx
```

The first command is the checklist. It reads the machine, changes nothing, measures nothing, and
says READY or NOT READY. The second command runs the calibration:

1. **Preflight.** The same checklist, saved to `preflight.json`. The run is refused if the machine
   is on battery, battery saver is on, another JVM is running, or a file it needs is online-only in
   OneDrive.
2. **Controls.** These apply to every measuring JVM. The machine is kept awake, with the display on
   (`--let-display-sleep` lets the display turn off). On a hybrid CPU the JVM is confined to the
   performance cores, so the measured thread never lands on an efficient core at half the speed.
   The JVM also runs at high priority, with Windows' power throttling (EcoQoS) switched off for it.
   Nothing on the machine is changed: these are properties of the processes the script starts.
3. **Soak.** It runs `calibrate --estimate`, which writes nothing, back to back for at least 10
   minutes, until two estimates in a row agree within 3%. A machine that never gets there in 45
   minutes is not steady, and the run stops before measuring anything. Then it prints the plan and
   the expected finishing time.
4. **Sessions.** The arms are calibrated in batches of 8 (`--batch`), each batch as one `magat
   calibrate` session. The first session creates the currency; each later one merges into it by
   ratio, exactly as `calibrate --arms <new>` always has. A failed session is retried once, after
   the other batches.
5. **Checks** (these write nothing). This machine is checked against the published R7615 currency.
   Then, after hours of work, it is checked against its own new currency on `emcts,emcts-comma`.
   That self-check should pass with small drift; if it does not, the machine did not hold its
   speed.
6. **Monitor**, throughout. Every 10 seconds it records the power source and battery. It also
   records the CPU used by anything other than the calibration, and when that stays above one
   logical CPU it names the programs using it. `typeperf` records the busiest performance core's
   clock and the thermal zones. If the machine goes on battery, the measurement is stopped at
   once. On battery this laptop runs at about a third of its speed, so nothing measured after that
   point is comparable with what came before.
7. **Summary and bundle.** It writes `SUMMARY.md` and zips the currency directory into
   `<data>/bundles/calibration-tribes-<name>.zip`.

Why batches: a session writes all or nothing. Run as one session, a full calibration is 3.7 hours.
That long, one noisy cell, or a laptop that has slowed 10% by the end of the session, discards all
of it. Batches of 8 arms make sessions of about an hour. Each one is bracketed on its own, and each
costs about 12 more minutes of warm-ups and reference cells. `--batch 0` runs everything as one
session.

**Resuming.** Run the same command again. It measures only the arms the currency does not price yet,
and skips checks already recorded (`--redo-checks` repeats them). The currency's clock policy is
locked by its first session. A later invocation under a different policy is refused, because a merge
cancels a uniform change of speed, but not a change in how compute-bound and memory-bound work scale
against each other.

**Output.** Everything goes under the data root, outside the clone. The currency is in
`<data>/calibration/tribes-<name>/`: `compute_weights.properties`, plus the last session's
`compute_profiles.csv` and `compute_operations.csv`. Each invocation's record is in
`offline/<stamp>/`:
- `SUMMARY.md`;
- `events.log`;
- `preflight.json`;
- `monitor.csv`;
- `counters.csv`;
- one log each for the build, the soak, the coverage check, the sessions and the checks;
- each session's own CSVs (a later session overwrites the ones in the currency directory).

**Exit codes.**

| Code | Meaning |
|---|---|
| 0 | complete |
| 1 | incomplete: a session failed twice, or the soak never settled; run the same command again |
| 2 | refused by the preflight |
| 3 | stopped: the machine went on battery |
| 130 | interrupted |

## Before the run

Do this while still online, in order.

1. **Plug in and let the battery fill.** A charging battery adds heat, and on some laptops it takes
   part of the adapter's power. HP's adaptive charging may hold the battery at about 80%; it is
   then not charging, which is fine.
2. **Restart the machine.** After a restart, give start-up work about 15 minutes to finish, then
   close whatever started on its own. Uptime is in the preflight; 22 days is too long.
3. **Choose the clock policy** (next section) and set it.
4. **Set Windows' power mode for "Plugged in" to *Best performance*.** It is in Settings > System >
   Power & battery > Power mode. If the laptop maker's tool has a performance mode, choose it and
   keep it for every later check on this machine. The script cannot read it, so write it down. On
   this laptop that is OMEN Gaming Hub > Performance Control: choose Performance, and maximum fan
   speed if it is offered. A fixed fan speed keeps the temperature steadier than a fan curve.
5. **Close everything.** That includes:
   - browsers;
   - chat and meeting apps;
   - Claude desktop;
   - editors and IDEs;
   - game launchers;
   - Docker and WSL.

   Quit OneDrive from its tray icon (pausing it is not enough: a paused client still scans). The
   preflight lists what it finds still running.
6. **Pause Windows Update for a week** (Settings > Windows Update).
7. **Move automatic maintenance away from the run.** Windows runs it daily at 02:00 by default,
   when the machine looks idle, and an unattended calibration looks idle. It includes Defender
   scans and update work. Change the time in Control Panel > System and Security > Security and
   Maintenance > Maintenance, or start the run early enough to finish before 02:00.
8. **Put the laptop where its temperature can stay steady.**
   - On a hard surface, with the vents clear.
   - Away from sun and heaters, in a room whose temperature will not change much overnight.
   - With the lid open.
   - With no external display: on this laptop one can wake the discrete GPU, which adds heat.
9. **Run `--preflight`**, fix what it reports, then **turn on airplane mode**.
10. **Start the run from a terminal window you opened yourself**, not from inside an editor or
    Claude. Leave that window visible.
11. **Watch the soak**, about 10 to 15 minutes, until it prints `steady after ... min`, then the plan
    and the expected finishing time. After that, leave the machine alone: no keyboard or mouse, and
    no lid.

## Clock policy

This is the setting that matters most. A laptop's clock follows its temperature. In toy runs on
this laptop (2026-10-07, boost on, other programs open), the busiest performance core ran between
3.7 and 5.4 GHz within single sessions, while the thermal zones reached 73-75 °C. Pick one policy,
and keep it for this currency and for every later `calibrate --check` on this machine. The script
reads the policy, records it in the summary, and locks it to the currency.

| Policy | The clock | A full calibration on this laptop | Use it when |
|---|---|---|---|
| **A. Performance cores capped below what the cooling sustains (recommended)** | flat at the cap | roughly 5-6 h (an estimate; the soak prints the real figure) | you want the most controlled conditions without doubling the time |
| B. Boost disabled | flat at the base clock | roughly 10 h | the cap is not honoured, or the soak will not settle under A |
| C. As shipped: boost on, no cap | follows the temperature | about 5 h (measured pace, 2026-10-06) | you accept some drift; the soak and the brackets measure how much |

A cap well below the sustained single-core clock means temperature no longer moves the clock. Cells
measured at the end of a session then cost what they would have cost at its start. A cap also sits
nearer the all-core clock a multi-threaded `magat run` gets on this laptop than single-core boost
does. A currency is measured idle and single-threaded either way (gate A3' of the thesis), so that is
a small point in its favour, not the reason.

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
- `PROCFREQMAX` caps the efficient cores. The JVM never runs on them; the line is there in case
  Windows applies it more widely.
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

Back to your usual plan:

```powershell
powercfg /setactive SCHEME_BALANCED
```

Keep the "MAGAT calibration" plan, and switch to it again before any later check on this laptop.
`powercfg /delete <guid>` removes it.

**Is the cap honoured?** The soak prints the busiest core's clock during the last estimate, read
from the processor's own performance counter, and the summary prints it for every session. Under
policy A, the median should sit at the cap and the soak should settle within a few estimates. If the
clock runs above the cap, the cap is not honoured: use B. If it runs well below the cap, lower the
cap, for example to 3500. The thermal zones are the firmware's ACPI zones, not the CPU's own
sensor. Read them as a trend.

## Reading the result

`SUMMARY.md` has these sections:

- **Conditions.** Power, plan, power mode, clock policy, the cores the JVMs were confined to,
  network and uptime.
- **Soak.** Every estimate, per budget level, the change from the previous one, and the busiest
  core's clock.
- **Sessions.** Each batch's verdict and duration, and its bracket: how far the reference cell
  moved between the session's start and end. The gate is ±10%; on a steady machine it is within a
  few percent. Then *speed vs file*: this session's reference advance as a multiple of the first
  session's. It cancels under the merge, and anything between 0.98 and 1.02 says every session ran
  at the same speed. Then the other work on the machine and the busiest core's clock.
- **Checks.** The self-check should pass with a small largest turn-price drift. The check against
  the published R7615 currency is a finding about this machine, not an error. On 2026-10-04, under
  uncontrolled conditions, this laptop's turn prices were within 4% of the R7615's
  (`games/tribes/calibration/r7615/ORIGIN.md`).

What to do about what it finds:

- **A session failed.** Its failures are listed under the sessions table. Run the same command again.
- **Other work during a session.** That session still passed its own gates, so its cells were not
  visibly affected. The summary prints the `calibrate --replace` command, in case you want those
  arms measured again anyway.
- **The self-check failed.** The machine changed speed during the run. Look at the busiest-core
  clock and the brackets across sessions. Usually this means a tighter clock policy and a fresh
  currency, under a new `--name`.

## Publishing it as a second currency

The R7615 file stays the published currency. A laptop currency is published beside it, under its own
name, never over it: `games/tribes/calibration/<name>/` holds its `compute_weights.properties`, the
`SUMMARY.md`, and an `ORIGIN.md`. ORIGIN.md says:
- the machine and the date;
- the clock policy and the maker's performance mode;
- the soak, the brackets and both checks;
- the commit.

Results computed under it quote its name.

## The development laptop

**Machine.** HP OMEN 16 (16-am0xxx), Intel Core i9-14900HX, Windows 11, JDK 26:
- 8 performance cores with SMT, logical CPUs 0-15;
- 16 efficient cores, logical CPUs 16-31;
- Modern Standby (S0 low-power idle).

**As found on 2026-10-07.**
- The Balanced plan, with *Best performance* when plugged in.
- Boost *aggressive*, and no frequency cap.
- Up for 22 days.
- Windows Update's pause had expired.
- OneDrive, Claude, two browsers and Steam were running.

**Measured pace** (2026-10-06, plugged in, Balanced, boost on, before these controls existed).

| Budget level | Seconds per turn of the reference arm |
|---|---|
| 40k | 2.49 |
| 80k | 7.49 |
| 400k | 33.36 |

At that pace:

| Run | Time |
|---|---|
| Everything in one session | 3.7 h |
| Four sessions of 8 arms | 4.3 h |
| The two checks | 0.6 h |
| The soak | 10-15 min |
| **Total under policy C** | **about 5 h** |

On battery the same estimate was 11.4 hours.
