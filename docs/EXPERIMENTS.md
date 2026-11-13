# Configuring an experiment

An experiment is one JSON file. It names the algorithms to compare, the budget levels and how many
times each position is played at each, the positions to play them on, and the currency to spend.
`experiment validate` reads it back, checks it, and estimates how long the run would take, before
anything runs:

```bash
python magat.py experiment validate --experiment experiments/tribes/examples/oep-vs-tbets.json
```

```bash
python magat.py run --experiment experiments/tribes/examples/oep-vs-tbets.json
```

## An example

[`experiments/tribes/examples/oep-vs-tbets.json`](../experiments/tribes/examples/oep-vs-tbets.json):

```json
{
  "name": "oep-vs-tbets",
  "game": "tribes",
  "arms": ["emcts", "oep", "tbets"],
  "baseline": "emcts",
  "levels": ["500ms", "1000ms"],
  "repetitions": 4,
  "split": "dev",
  "sources": ["single"],
  "phases": { "min": 10, "max": 20 },
  "scenarios": 40,
  "weights": "r7615-time"
}
```

`validate` reads it back as:

```text
  arms      emcts, oep, tbets   baseline emcts
  levels    500ms = 9500 x 4, 1000ms = 20000 x 4   (units x repetitions)
  contrasts every pair, by default: 3
  positions 40 of the dev half (sources=single;phases=10..20;scenarios=40)
    level    9500:  3 arm(s) x 40 scenarios x 4 visit(s) on 15 thread(s)
    level   20000:  3 arm(s) x 40 scenarios x 4 visit(s) on 15 thread(s)
  960 turns; estimate 1 min (...)
VALIDATION: PASS
```

## The keys

| Key | What it is | Default |
|---|---|---|
| `name` | the experiment's name, lower-case kebab-case: it names the run's directories | required |
| `game` | the game, `tribes` | required |
| `arms` | the algorithms, by id: `["emcts", "oep"]` (`python magat.py arms --game tribes` lists them) | required |
| `baseline` | the arm every other is compared with; run it at two levels or more for the budget multiplier | required |
| `levels` | the budget levels, in the order to run them ([below](#levels)) | required |
| `repetitions` | how many times each position is played at each level | required, here or per level |
| `split` | `dev`, `holdout` or `all` ([below](#positions)) | `all` |
| `sources`, `phases`, `ids` | which positions of the split to play ([below](#positions)) | all of them |
| `scenarios` | how many positions, spread evenly over what the filters keep | all of them |
| `weights` | the currency ([below](#the-currency)) | the game's published one |
| `contrasts` | the paired comparisons to report, `[{"a": "oep", "b": "tbets", "label": "..."}]` | every pair |
| `description` | free text | none |

An unknown key is a warning, since it is usually a typo. A value of the wrong kind, a level that is not
published, or `visits` and `repetitions` given together are errors: `validate` lists every one at once.

## Levels

A level is any of:
- **units:** `20000`;
- **a published level's name:** `"500ms"`, `"1000ms"` or `"5000ms"` for Tribes, the units that are that
  much thinking time on the reference machine
  ([`games/tribes/levels.json`](../games/tribes/levels.json): 9,500, 20,000 and 101,500);
- **an object**, `{"budget": "1000ms", "repetitions": 8, "arms": ["emcts", "oep"]}`, for a level with its
  own repetitions, or one that runs only some of the arms.

Levels run in the order written, and each finished arm and level is checkpointed. If a run stops
partway, whatever came first is complete, so put the level you need most first.

**Repetitions** (`visits` in older specs) replay each position with a new seed: visit `v` of scenario
`s` is seeded `s × 1,000,000 + v`. The statistics treat the position as the unit, so more positions
usually buy more precision than more repetitions. The analysis's design table says by how much for the
run you have ([methods](METHODS.md)).

## Positions

The portfolio is split once, for good, into a development half and a holdout half
([methods](METHODS.md#3-two-halves-explore-on-one-confirm-on-the-other)):
- **`split`:** explore and make your choices on `dev`, then confirm them once on `holdout`. `all` is both
  halves together, as the reference experiment runs.
- **`sources`:** keep positions from these sources. Tribes has `single` (single-player games, 200
  positions) and `dual` (two-player games, 200).
- **`phases`:** keep positions captured at these turns: a list, `[15, 20]`, or a range,
  `{"min": 10, "max": 20}`. Tribes captured `single` positions at even turns from 10 to 30, and `dual`
  ones at turns 15, 20 and 25.
- **`ids`:** keep only these scenario ids.
- **`scenarios`:** then spread this many evenly over what is left, for a smaller run.

A filter never reaches across the split. An id from the other half is an error, so the holdout stays
unread until it is read whole. A filter that leaves nothing is an error. A `scenarios` count above what
the filters leave is a warning, and everything they leave runs.

The run records its selection in `_run.json`. A resume into the same tree must select the same
positions, or it aborts, so one tree never mixes two selections. The analysis lists the selection in
its provenance table.

## The currency

`weights` names the currency the budgets are spent in. It can be any of:
- **a published currency's name:** `r7615-time` (the reference machine's, and the default) or
  `laptop-i9-14900hx-time` (`games/tribes/calibration/`);
- **a currency this machine measured**, by the name you gave `calibrate-controlled --name`, found in
  `$HOME/magat_runs/calibration/tribes-<name>/`;
- **a weights file**, or a directory holding one, relative to the spec or the current directory.

A `--weights` given on the command line wins over the spec, and the launcher says so.

A currency measured on **this** machine makes a budget a share of this machine's time: each algorithm
gets its own share of the same seconds. A currency measured on **another** machine makes a budget that
machine's time, spent here as the same work. The turns then come out identical to that machine's, and
how long they take here is beside the point ([the currency](CURRENCY.md)). So `validate` gives the
estimate in that machine's hours, and a run under another machine's currency does not use the thread
choices published with it: it uses this machine's own (`--threads` overrides).

## Contrasts

Without `contrasts`, every arm is compared with every arm written before it, in the order written. For
`["random", "mcts", "emcts"]` that is `mcts` against `random`, `emcts` against `random`, then `emcts`
against `mcts`, which is how the reference experiment writes its own ten out. Write `contrasts` to choose
them or to label them, or write `[]` for none.

## The long form

Older specs, such as [`experiments/tribes/reference.json`](../experiments/tribes/reference.json), write
everything out: arms as `{"id": ...}`, levels as `{"budget": ..., "visits": ...}`, and the contrasts in
full. They read exactly as before.
