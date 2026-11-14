# Sharing results

A MAGAT result is meant to be checked and built on: its currency spent by others, its turns re-played
on their machines, their algorithms run against it. `export` puts a result in one file, and `import`
takes one in, checks it, and makes it usable like the results published with the repository.

## Export

```bash
python magat.py export reference
```

The argument is an experiment's name (its run under `$HOME/magat_runs/<game>/runs/<name>`) or a run
directory: a run root holding split trees, or one split tree, such as a published one
(`games/tribes/results/reference/r7615/all`). The package is
`$HOME/magat_runs/exports/<experiment>-<currency>.magat.zip` (`--name` and `--out` change it). It is laid
out as the repository publishes results:

| In the package | What |
|---|---|
| `MANIFEST.json` | the game, the portfolio's identity, the engine and MAGAT commits; the currency (name, hash, unit, machine, levels); each split (positions, the machine that played it, each leaf's arm, level and turns); and every file's sha256 |
| `README.md` | the same, to read, with the commands to check the package and compare with it |
| `calibration/<currency>/` | the currency the run was priced in: weights, operations, profiles, thread sweep, its origin and its record's summary |
| `experiments/<experiment>.json` | the spec, byte for byte, so its hash is the one the run recorded |
| `results/<name>/<split>/` | the run tree, every split of it, without seqlogs (`--no-curves` leaves the anytime curves out too) |
| `results/<name>/report/` | the analysis of the tree as exported, made afresh by the export |

The currency and the spec are found wherever this machine has them: where the run recorded them, among
the published ones, among this machine's calibrations, or among its imports, by their hashes. A currency
alone, for others to run their own algorithms in, exports with `--currency`:

```bash
python magat.py export --currency laptop-i9-14900hx-time
```

On a server, the notebook's last stages export each experiment. Download the packages from `exports/`
under the data root.

## Import

```bash
python magat.py import reference-r7615-time.magat.zip
```

It checks, in order:
1. **Integrity.** Every file is the one the manifest names, with that hash, and nothing else is in the
   package. A file named outside the package's own folder is refused, as is a package for a game this
   repository does not have. A package refused here is not unpacked.
2. **Compatibility.** The portfolio identity and the engine commit are compared with this checkout's: a
   difference is a warning, since the turns may then not re-play.
3. **Reproduction.** Every leaf re-plays 2 of its turns here (`--replay N` for more), in the package's
   currency, field for field, wall-clock aside: `check replay` on each.
4. **Consistency.** The report's paired means and win counts are derived again from the turns.

The package is unpacked into `$HOME/magat_runs/imports/<name>/`, its verdict written to `IMPORT.md`
there, and the commands to use it printed:

| Verdict | Means | Exit |
|---|---|---|
| `VERIFIED` | every check passed | 0 |
| `IMPORTED` | a currency alone: integrity checked, nothing to re-play | 0 |
| `IMPORTED, NOT CHECKED` | `--no-check`: unpacked without re-playing anything | 0 |
| `CHECKS FAILED` | a turn did not re-play, or the report does not follow from the turns; `IMPORT.md` says which | 1 |
| `REFUSED` | the package is not what its manifest says, or names a file outside its folder; nothing unpacked | 2 |

The [v1.0 release](https://github.com/mlhcorapi/Multi-Action-Game-Algorithm-Tester/releases/tag/v1.0)
has both published runs as packages, `reference-r7615-time.magat.zip` and
`reference-laptop-i9-14900hx-time.magat.zip`, exported from the v1.0 tag.

Importing the same package again checks it again. Another package under a name already taken is
refused: give it `--name`. `python magat.py import --list` lists what is imported, with each verdict.

**What the checks prove.** That the files arrived as they were exported, and that a sample of each leaf's
turns comes out the same here. A leaf whose sampled turns re-play was played by the code, engine and
prices it names; the report check catches a plan value changed after the fact even where the sample
misses it.
The whole proof is a full re-run, which MAGAT makes easy: run the package's spec in its currency with
`--no-reuse`, and `analyze --compare` with the import shows every turn identical.

## Using an import

**Spend its currency**, by its name, as you would a published one:

```bash
python magat.py experiment run my-spec.json --weights laptop-i9-14900hx-time
```

**Compare with its run**, side by side and position by position:

```bash
python magat.py analyze --run $HOME/magat_runs/tribes/runs/my-experiment --compare $HOME/magat_runs/imports/reference-r7615-time/results/reference-r7615-time/all --out $HOME/magat_runs/tribes/results/my-vs-r7615
```

**Build on it.** In its currency, `experiment run` takes its leaves instead of playing them again, as it
does the published run's ([reusing the published run](EXPERIMENTS.md#reusing-the-published-run)). Only a
`VERIFIED` import is taken from. Each leaf comes from the first source that holds it whole at this run's
prices: the published runs first, then the imports by name. So your experiment can take the reference
algorithms from the published run, a colleague's algorithm from their package, and play only yours:
1. Import their package.
2. Price your algorithm into a copy of their currency, so every price they played at is kept:

   ```bash
   mkdir -p $HOME/magat_runs/calibration/tribes-theirs-plus-mine
   ```

   ```bash
   cp $HOME/magat_runs/imports/<package>/calibration/<currency>/compute_weights.properties $HOME/magat_runs/calibration/tribes-theirs-plus-mine/
   ```

   ```bash
   python magat.py calibrate --game tribes --out $HOME/magat_runs/calibration/tribes-theirs-plus-mine --arms <your arm>
   ```

3. Run a spec with their algorithms and yours, in that currency:

   ```bash
   python magat.py experiment run my-spec.json --weights theirs-plus-mine
   ```

   `--reuse <split tree>,<split tree>` names the sources yourself, in order; `--no-reuse` plays every leaf.

## Publishing a result in this repository

A package's folders are the repository's: `calibration/<currency>/` becomes
`games/<game>/calibration/<currency>/`, `results/<name>/<split>/` becomes
`games/<game>/results/reference/<machine>/<split>/`, and its `report/` becomes `.../<machine>/report/`.
Add an `ORIGIN.md` to the currency, saying how it was measured
([publishing a currency](CALIBRATING_A_MACHINE.md#publishing-it)), and a `README.md` to the run, and open
a pull request. CI re-plays the published turns of every published run on every pull request.
