# v6 Full Games — Plan

The tournament phase of v6. EMCTS plays complete Tribes games against every experimental
configuration, at every crossover ratio and every budget level, and the only thing recorded is who
won.

This is a companion to [EXPERIMENT_V6_PLAN.md](EXPERIMENT_V6_PLAN.md), not a replacement. The
isolation phase measures the value of the best action sequence found in **one turn** from a captured
position under a fixed budget. That is search quality. `V6_SERVER_RUNBOOK.md` §5c states the limit
plainly: *"Any claim about winning games needs the tournament phase."* This is that phase.

---

## 1. The question

Does crossover expansion, crossover bridge-burning, or their combination make an agent **win more
games** than standard EMCTS — at which mutation:crossover ratios, and at which compute budgets?

That is the whole question. No search telemetry, no per-turn traces, no hypothesis battery: those
are the isolation phase's job and it already answers them at these same budgets. Adding them here
would multiply the output size for data that would be read as a worse-powered duplicate of v6's.

The two phases are designed to be read together: v6 says whether the operators find better plans,
and v6-full says whether better plans become wins. A divergence between them is itself a result, and
one worth reporting — a search improvement that does not convert is evidence about the heuristic or
the horizon, not about the operator.

---

## 2. Design at a glance

| | |
|---|---|
| Matchups | EMCTS vs each of 11 experimental configs, **plus EMCTS vs EMCTS as a control** |
| Configs | CrossoverBB, CrossoverExpansion@{2,5,10,20,50}, CrossoverCombo@{2,5,10,20,50} |
| Budgets | 40,000 / 80,000 / 400,000 compute-units per turn (identical to the isolation phase) |
| Games | 200 per matchup → 12 × 3 × 200 = **7,200 games** |
| Maps | 20 fixed maps, shared by every matchup and every budget |
| Schedule | each map played 10 times: **5 per orientation** |
| Mode | CAPITALS, turn cap set by `GameLengthProbeV6` (§5) |
| Tribes | IMPERIUS2 vs IMPERIUS — a mirror pair |
| Recorded | winner, both scores, map, orientation, end tick, how it was decided |

Output: `test_results/v6_full/<Opponent>/[ratio_<r>/]<cu>cu/games.csv`, one row per game, ~17 short
columns. The whole dataset is well under 2 MB.

---

## 3. Fairness, source by source

The design is a list of biases and what removes each. This section is the one the write-up should
draw on, because "we played 200 games and counted" is not by itself a fair comparison.

### 3A. Tribe — mirror pair

Both agents play tribes with the **same starting technology (ORGANIZATION), the same starting unit
(WARRIOR), and identical per-terrain generation multipliers** (all 1.0 = BASE in `terrainProbs.json`,
verified). IMPERIUS2 exists in the codebase precisely as a duplicate of IMPERIUS for this purpose,
and `Phase2Runner` already used the pairing. Tribe identity therefore contributes nothing.

### 3B. Map — fixed and shared

20 maps, the same 20 in every matchup and at every budget level. This does two distinct things:

- *Within a matchup*, it removes map sampling from the comparison.
- *Between matchups*, it makes all 36 matchups **paired with one another**. A difference between
  CrossoverExpansion@5 and CrossoverExpansion@20 is a difference between the agents, not between the
  maps each happened to draw. This is the full-game analogue of the isolation phase's shared
  `visitSeed(scenario, visit)` across all 12 configs.

### 3C. Spawn and turn order — the orientation swap

**The question that prompted this design was whether Tribes can swap starting positions. It can, and
the mechanism is exact rather than approximate.**

Two facts about the engine:

1. `LevelGenerator` chooses capital cells by maximin distance and assigns them **in tribes-array
   order**: `capitalCells[i]` → `tribes[i]` ([LevelGenerator.java:214](../src/core/levelgen/LevelGenerator.java#L214)).
2. `Game.initGameStructures` seats `players.get(i)` on `tribes[i]`
   ([Game.java:263](../src/core/game/Game.java#L263)).

So holding the tribes array fixed at `{IMPERIUS2, IMPERIUS}` and **reversing the player list**
produces a byte-identical board on which the two agents have exchanged capitals. Nothing is
regenerated, nothing is approximated, and the board does not need to be cached or reloaded.

There is a bonus. The seat index — which is turn order — comes from the **row-major scan order of the
capital tiles** in the generated level (`LevelLoader.extractTribes`). Exchanging capitals therefore
exchanges seats too. **Position and first-move advantage swap together**, and both are balanced by
the same 5-and-5 schedule.

`NewExperimentSmokeTestV6Full` §2 asserts all three properties on all 20 maps: identical board,
exchanged capitals, exchanged seats.

**This is why map asymmetry is measured but not filtered.** The generator grows territory outward
from each capital, so two starting areas are never exactly equal — the measured start-resource
asymmetry across the 20 maps ranges 0 to 11. With each map played 5 times per orientation, a map bias
of any size cancels exactly within that map. Filtering on balance would narrow the map distribution
the result generalises over while removing a bias the schedule already removes. `_maps/maps.csv`
records capital positions, capital distance, and per-start terrain and resource counts so the write-up
can state what the maps looked like.

### 3D. Position sampling — one shared seed grid

The `(map, gameSeed, agentSeed)` grid does not depend on the matchup or the budget level. All 36
matchups are played over an identical set of 200 starting conditions.

### 3E. Mirror pairs

Game `(map m, pair p, orientation 0)` and `(map m, pair p, orientation 1)` share the **level seed, the
game seed, and the per-seat agent seeds**. Agent seeds attach to the **seat**, not to the agent, which
is what makes that possible. The two games are then identical in every respect except which agent
sits where — the duplicate-board scheme used in computer chess and in duplicate bridge, and the reason
the analysis can pair (§7).

### 3F. Compute — deterministic and identical

Both sides get the same per-turn budget in compute-units (`STOP_COMPUTE`), bounding **search after a
common greedy initialization**, using the same `ComputeMeter` weights and the same three levels as the
isolation phase. Because the budget is a deterministic count rather than a clock, a turn performs
identical work whether it runs alone or alongside ninety others: the thread count changes wall-clock
and nothing else. `NewExperimentSmokeTestV6Full` §6 asserts this directly — the same games replay
byte-identically across a thread pool.

### 3G. Everything else — the control arm

`EMCTS vs EMCTS` at each budget, on the same maps with the same swap schedule, **must return 50%
within noise**. If it does not, some asymmetry survived §3A–§3F and that level's other numbers are
not readable. It runs **first** at each level: it is the cheapest matchup and the most informative
failure, so it completes while there is still time to react.

There is one known residual it is watching for. `TribeResult.compareTo` breaks a total tie — equal
score, techs, cities, production, wars, stars — on **tribe id**, i.e. seat order
([TribeResult.java:90](../src/core/game/TribeResult.java#L90)). Exact ties should be vanishingly rare
across a full game, and the orientation swap balances the seat anyway, but the control is what would
show it if the reasoning is wrong.

---

## 4. Why 200 games, and what that buys

200 games gives a 95% confidence interval of roughly **±6.9 pp** on a win rate near 50% — *if games
were independent, which they are not*: ten games share a map. So the per-matchup interval is the
optimistic bound, and the analysis clusters on map (§7).

That is enough to detect a decisive effect and not enough to resolve a small one, which is the right
trade given the isolation phase already measures small effects with far more power. Two things
recover the power where it matters:

- **Pooling within a family.** The five ratios of CrossoverExpansion pool to 1,000 games (±3.1 pp),
  likewise CrossoverCombo. This is the headline test — *does crossover expansion beat EMCTS at this
  budget* — with per-ratio rates as the breakdown beneath it.
- **Pairing.** 100 mirror pairs per matchup, analysed as pairs rather than 200 independent games
  (§7).

The v6 plan warns that greedy initialization should be expected to **compress effect sizes** relative
to v4/v5, because the operators now have to improve on a strong start. A null result at 200 games is
therefore a real possibility and should be reported as one, with its interval, rather than presented
as evidence of no effect.

---

## 5. The turn cap

A CAPITALS game ends either when one tribe holds **every capital** (a conquest), or when the tick
count passes `MAX_TURNS_CAPITALS`, at which point `GameState.computeGameRanking` awards the win to
whoever leads on score. Those measure different things: a conquest is playing strength; score at a
fixed horizon is dominated in Tribes by **technology count**, not territory.

The old Phase 2 tournament capped at 50 and only **1.3%** of its 900 games reached it (mean length
22.4 ticks), so the cap was inert there. But those agents planned on a 500–1000 ms clock and these are
far stronger, and stronger play can push length either way — better attack shortens a game, better
defence lengthens it.

`GameLengthProbeV6` therefore measures it. It plays real games (same maps, same swap, same agents)
under a loose safety cap, reports the end-tick distribution per budget level, and recommends the
smallest cap that would decide **at most 2%** of games. Games reaching the safety cap are **censored,
not discarded** — their share is exactly the statistic of interest.

`RunV6FullGames.TURN_LIMIT` is a compile-time constant, deliberately: the tournament's rules should be
fixed and visible in the source, and a dataset must not silently contain games played under two
different caps. Read the probe, set the constant, rebuild, run.

Whatever the cap turns out to be, `DecidedBy` (CAPITAL / SCORE) is recorded per game and the
conquest share is reported alongside every win rate.

---

## 6. Cost

Measured on the dev machine, single-threaded, at the real budgets:

| budget | s / planning episode | µs per compute-unit |
|---|---|---|
| 40,000 | 0.70 | 17.6 |
| 80,000 | 1.28 | 16.1 |
| 400,000 | **18.77** | 46.9 |

400,000 costs **26.7× the 40,000 level, not 10×** — the tree outgrows cache and the collector starts
charging rent. That shape dominates the plan: at ~45 planning episodes per game (Phase 2's measured
mean length), 2,400 games per level works out at roughly

- 40k ≈ 21 CPU-h, 80k ≈ 38 CPU-h, **400k ≈ 563 CPU-h**

≈ 620 CPU-hours at early-game rates, and more like **900–1,250 CPU-hours** once late-game states —
more units, more cities, costlier `copy()` and `advance()` — are accounted for. On the R7615 at ~93
workers with all-core clock derating, that is **on the order of a day**.

Two consequences worth acting on:

- The 400k level is ~90% of the run. **Anything added at 40k or 80k is nearly free**; anything added
  at 400k is not.
- These are dev-machine numbers. Re-measure on the server before committing — the thread calibration
  (§8) prints exactly this as a side effect of its pilot.

---

## 7. Analysis

`analysis/analyze_v6_full_games.py`. Deliberately small, matching what is recorded.

| output | content |
|---|---|
| `W1_win_rates` | win rate per matchup with a **map-clustered** 95% CI, and the conquest/score split |
| `W2_pooled` | the headline test: each family (Expansion, Combo, BB) vs EMCTS, pooled over ratios, per budget |
| `W3_by_ratio` | win rate vs ratio per budget — the curve that answers "which ratio" |
| `W4_control` | EMCTS-vs-EMCTS per budget, with its CI. **Read this first.** |
| `W5_mirror_pairs` | the paired analysis: per mirror pair, won-both / split / lost-both, with a sign test |
| `W6_balance` | win rate by orientation and by seat, per matchup — the residual-bias diagnostic |
| `W7_game_length` | end-tick distribution and cap-bind rate per level |

Two statistical notes:

- **Clustered CIs, not binomial.** Ten games share a map, so a naive binomial interval understates
  the uncertainty. `W1` uses a cluster-robust interval with the map as the cluster.
- **The paired test is the sharper one.** Within a mirror pair the position and all seeds are
  identical, so a pair where an agent wins both sides is strong evidence and a split is a tie. `W5`
  is a sign test over the 100 pairs and should be quoted alongside the raw rate.

---

## 8. Running it

Machine placement is shared with the isolation phase — `ServerProfile` for the ceiling, `LoadGovernor`
for live throttling, `RunGuard` for cooperative stops — and documented in
[V6_SERVER_RUNBOOK.md](V6_SERVER_RUNBOOK.md).

The one piece that is not shared is the **thread calibration**. `ThreadCalibrationV6Full` exists
because the work item is different: a game, not a turn, and a coarser queue item needs its own knee.
It sweeps on real (short) games per budget level and picks the smallest thread count within 3% of the
best *sustained* throughput. Its one honest limitation is that the games it times are short and
therefore early-game, so its per-task heap estimate is low; it applies a 2× derate before gating, and
the live `LoadGovernor` is the real protection.

Notebook: `analysis/run_v6_full_games_jupyter.ipynb`.

```
0   environment check
1   build
2   smoke test            NewExperimentSmokeTestV6Full   (~1 min, 32 checks)
3   map set               FullGameMapSetV6               (instant; writes _maps/maps.csv)
4   game-length probe     GameLengthProbeV6              -> sets TURN_LIMIT
5   thread calibration    ThreadCalibrationV6Full        (per budget level)
6   run                   RunV6FullGames                 (also the RESUME cell)
7   progress              read-only view over the output tree
8   verify                VerifyV6FullOutput
9   analyse               analyze_v6_full_games.py
```

**Restartability.** Re-running the run cell resumes: finished matchups are skipped whole, a partial
matchup continues per game, and a directory lock refuses a second writer over the same tree. Each
finished game appends its row immediately, so an interruption loses only the games in flight. Stop
with SIGTERM, never `kill -9`.

---

## 9. Decisions taken, and what would change them

1. **200 games per matchup.** Keeps the spec; power comes from pooling and pairing (§4). Raise it at
   40k/80k if the pooled intervals straddle 50% — those levels are ~2 hours of the run.
2. **EMCTS-vs-EMCTS control at all three levels.** ~3% of runtime for the only check that can falsify
   the fairness argument.
3. **Turn cap from the probe, not inherited.** §5.
4. **Map asymmetry measured, not filtered.** §3C.
5. **Win rate only.** Per-game rows carry scores, end tick and decision type because they cost nothing
   and make the win rate interpretable; anything deeper belongs to the isolation phase.

## 10. One finding from building this, which applies beyond it

The first version of the map set drew level seeds consecutively (`BASE, BASE+1, …`). All 20 maps came
out with **one capital on the identical tile and an identical capital-to-capital distance**: the maps
varied in terrain but not in geometry.

The cause is `java.util.Random`'s correlation across nearby seeds. Measured on this project's own seed
range: the first `nextDouble()` fell on the same side of 0.5 for **twelve consecutive seeds**, and the
first `nextInt(121)` walked in near-arithmetic steps rather than scattering. `LevelGenerator` is driven
entirely by one such `Random`, and its terrain placement is a long sequence of
`nextDouble() < probability` tests.

Every seed in this phase is now passed through a SplitMix64 finalizer
(`FullGameMapSetV6.scramble`) before use — level seeds, game seeds and agent seeds alike — and
`NewExperimentSmokeTestV6Full` §1c asserts that capital positions and distances actually vary.

**This pattern is not unique to this phase.** `Phase2Runner` used `g -> 1_000_000L + g` for its level
seeds and `Phase3Runner`, `BuildV4Portfolio` and their successors use the same consecutive-seed idiom.
Those runs are complete and this does not invalidate them — correlated seeds narrow the sampled
variety, they do not bias one agent against another, and every matchup drew the same maps. But it is
worth knowing when reporting how many *distinct* situations a result rests on, and worth fixing in
anything new.
