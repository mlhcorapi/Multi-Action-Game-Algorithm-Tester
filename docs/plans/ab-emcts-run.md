# AB-EMCTS on the R7615: the run sheet

What to do on the R7615, in order, to run the pre-registered experiment
([`ab-emcts-vs-oep.md`](ab-emcts-vs-oep.md)), and what to read at each step. Everything runs from the
notebook, `notebooks/run_magat.ipynb`, except three commands in a terminal in the clone. The files it runs
are committed before the run starts:

- **The experiment:** [`experiments/tribes/ab-emcts.json`](../../experiments/tribes/ab-emcts.json). All
  eight arms, the published levels with 8 / 8 / 6 repetitions, contrasts H1 to H4, and `"reuse": false`.
- **The calibration:** [`calibrations/tribes/ab-emcts.json`](../../calibrations/tribes/ab-emcts.json). The
  currency `r7615-ab-emcts`, which starts as a copy of the published `r7615-time` (`"from"`) and prices the
  three AB-EMCTS arms into it.

Neither file is edited once the run starts. A change found to be needed is a deviation, reported with its
date and reason (the pre-registration, section 7).

## Before you start

- **The clone:** `git pull` to main, then `git submodule update --init` (the engine at `828b130`).
- **The machine:** as quiet as for the published calibration. The session waits up to 30 minutes for other
  work to fall below 4 logical CPUs, and calls itself suspect above that.
- **Time:**
  - The calibration session prints its own estimate once it has measured the reference cell.
  - Each half of the run should take about 20 minutes. The published run took 26 minutes for five arms on
    all 400 positions, and this one plays eight arms on 200 positions per half.

## 1. The notebook's parameters

```python
CALIBRATION_CONFIG = "calibrations/tribes/ab-emcts.json"
PUBLISHED_CURRENCY = "r7615-time"
EXPERIMENTS = [("experiments/tribes/ab-emcts.json", "holdout"), ("experiments/tribes/ab-emcts.json", "dev")]
```

The holdout half runs first, because it decides. A session stopped partway then leaves that half whole.

## 2. Up to the calibration's preflight, then stop

Run the cells through the calibration preflight, then stop. The preflight should say:

- `# from      the published r7615-time (sha256 35db011f70cf...), copied: the currency starts as it`
- `It would measure one session pricing 3 arm(s): emcts-comma, comma-z0-coarse, comma-z0-random, merged
  into the currency by ratio; then the checks against the published currency and against this machine's
  own new currency.`
- `PREFLIGHT: READY`, possibly with warnings to read.

**Bring the preflight's output back before measuring,** and we read it together.

## 3. The calibration session

Run the measuring cell. It measures the reference cell and the three arms at 9,500, 20,000 and 101,500
units in one session, merges them into the copy, and then runs both checks. Its verdict is in
`$HOME/magat_runs/calibration/tribes-r7615-ab-emcts/record/SUMMARY.md`: COMPLETE, with both checks
passing.

**The audit gate** (the pre-registration, section 3: at most 25% of a turn unaccounted for) is in the new
currency. Every new profile records its share, and each must be at most 0.25:

```bash
grep -E "^profile\.(emcts-comma|comma-z0-[a-z]+)@[0-9]+\.unpriced" $HOME/magat_runs/calibration/tribes-r7615-ab-emcts/compute_weights.properties
```

## 4. The gates

Run the gates cell. It checks:
- the contract;
- equivalence for every ported arm, the three AB-EMCTS arms included (they are unpriced in the published
  currency, so they run at the measurement weights, with a note);
- the audit of the reference arms.

Then, in a terminal, the audit of the AB-EMCTS arms on their own, which should end in `AUDIT: PASS`:

```bash
python magat.py check audit --game tribes --arms @ab-emcts --budget 9500
```

If a gate fails, stop there and bring back its log.

## 5. The run

Run the experiments cell. It plays the holdout half, then the development half: all eight arms, every
leaf played and none reused. Running the cell again resumes a stopped run. Its report is
`$HOME/magat_runs/tribes/results/ab-emcts/SUMMARY.md`, with both halves side by side. The spend gate (the
pre-registration, section 3: a mean turn at most 1.10× its level) is in the report's spend table.

## 6. The reproduction check

In a terminal:

```bash
python magat.py check reproduction --run $HOME/magat_runs/tribes/runs/ab-emcts --against games/tribes/results/reference/r7615/all --out $HOME/magat_runs/tribes/results/ab-emcts/reproduction.csv
```

**Expected:**
- `REPRODUCTION: PASS`: every turn of `random`, `mcts`, `emcts`, `oep` and `tbets` identical to the
  published run's in every field except time;
- the three AB-EMCTS arms listed as `not in the other run`.

A failure is reported as a deviation, not fixed: bring it back as it is, and run nothing again.

## 7. Export and bring back

Run the export cell and the inventory. Then download:

- the bundles the inventory lists: the calibration, the gates, `run-ab-emcts` and the shakedown;
- the package `$HOME/magat_runs/exports/ab-emcts-r7615-ab-emcts.magat.zip`;
- `reproduction.csv`, and the output of the three terminal commands.

The analysis against the pre-registration's decision rule (section 6) is done from these files.
