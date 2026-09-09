# MAGAT — Multi-Action Game Algorithm Tester

*Design brief for the new repository. Written 2026-09-09 at the end of the AB-EMCTS line, whose
retrospective (`Tribes-master/analysis/AB_EMCTS_RETROSPECTIVE.md`) is the evidence behind every
principle below. This branch exists to be cloned into the MAGAT repository; everything MAGAT will
extract from is here, and `STARTING_PROMPT.md` is how the first session begins.*

---

## 1. What MAGAT is

A framework for evaluating search algorithms in **multi-action games** — games where a turn is a
sequence of actions, so the branching factor per turn is combinatorial and full-game evaluation is
slow and noisy. It replaces "play N games, multithreaded, on whatever hardware" with:

1. **A pinned portfolio of captured positions per game**, stratified and split into a development
   half and a holdout half that is read once.
2. **A calibrated compute-unit currency** that makes budgets mean the same amount of forward-model
   work on any machine, with wall-clock reported alongside as an honesty check.
3. **A paired, scenario-clustered statistical design** where every algorithm replans the same
   positions with the same seeds, so effects are measured as paired differences with honest
   confidence intervals — and reported as **budget multipliers**, a dimensionless effect size that
   compares across games and across papers.
4. **Attribution controls as a first-class pattern**: an algorithm's mechanism is only credited when
   the same algorithm with the mechanism replaced by noise performs worse.

The product is the *methodology*. The games are the substrate it runs on.

## 2. Why — the evidence

The AB-EMCTS line ran ~94,000 isolation turns across six gated stages in ~13 h of compute. Four of
six pre-registered expectations were overturned; every development effect replicated on the holdout;
the headline was attributed to its mechanism by a control that cost 90 minutes. The same questions
asked through full games would have cost an order of magnitude more compute, and the attribution
question — the one the thesis turned on — could not have been asked through games at all.

Two failures are also evidence. Stage D declared a champion that Stage E showed was mostly a
throughput effect: **a framework must make the attribution control part of the default grid.** And
Gate E concluded "ranking is worthless" from one granularity setting that the same stage had just
shown to be the wrong one: **a framework must make parameter interactions visible, not leave them
to be noticed.**

## 3. The currency — what a compute unit is, and why not FLOPs

**Do not use FLOPs.** Game forward models are dominated by memory traffic, branching, object
allocation and garbage collection, not floating-point arithmetic; FLOP counts are both hard to obtain
and nearly uncorrelated with the cost that matters. Instruction counts fare little better under a
JIT. The AB-EMCTS line worked because its unit was an **operation count**, not a hardware count.

**The unit is a forward-model operation, priced.** Three operations are metered per thread:
`advance` (apply an action), `copy` (clone a state), `evaluate` (score a state). Each has a weight;
a budget is spent in weighted units. The definition:

> **1 compute unit = one `advance` performed by the reference agent at the reference budget on the
> game's reference portfolio, on the machine the weights were calibrated on.**

What transfers between machines, and what does not:

| | Transfers? | Why |
|---|---|---|
| Op **counts** per turn (advances, copies, evals) | **Yes, exactly** | they are what the algorithm did; hardware-independent |
| The **weights** (relative cost of copy and eval vs advance, per agent, per budget) | Approximately | ratios were stable within ~6% across sessions on the R7615; a cache-light agent moved 24% under load (A3′) |
| Wall-clock | No | but it is always reported, and W7 re-scores against it |
| **Budget multipliers** (§4) | **Yes** | dimensionless |

**So MAGAT publishes, for every result, the raw op counts alongside the units.** Anyone can re-price
a published run with their own calibrated weights. That is the closest thing to "perfect
translation" that exists, and it is honest about the residual.

**Each game has its own currency.** One `advance` costs wildly different things in different
engines, so raw units are never compared across games. Budget multipliers are.

**The reference machine.** The R7615's weights ship as the *published* calibration for each game.
Every other machine runs the shipped calibrator (one command, ~1–2 h per game) and gets its own,
plus a drift report against the published ones. Results quote which calibration they used.

## 4. What to measure — the inventory, ranked by reliability

From most to least trustworthy, with what each is for.

| # | Measure | Reliability | What it answers | Notes |
|---|---|---|---|---|
| 1 | **Paired per-(scenario, visit) difference, scenario-clustered bootstrap CI** | Highest | is A better than B, and by how much | the core statistic; pairing on identical seeds is what gives it power; report terminal-excluded as sensitivity |
| 2 | **Equivalent budget multiplier** | High | *how much* better, in a unit anyone can read | interpolate the baseline's value-vs-budget curve (log-linear); report the CI; it is a lower bound under diminishing returns; needs ≥2 budget levels, ideally 3 with the crossing inside the ladder |
| 3 | **W7 wall-clock re-score** | High | does the units result survive in seconds | co-primary; catches currency mis-pricing after the fact; clamping share must be reported |
| 4 | **Dev/holdout side-by-side** | High (discipline) | is the effect real or the winner's curse | every headline appears as two numbers or not at all |
| 5 | **Attribution contrast (mechanism vs noise)** | High (design) | is the *mechanism* doing it | same arm, mechanism replaced by randomness, RNG draw count held equal |
| 6 | **Search-gain multiple** (`Final − Initial`, ratio to baseline) | Medium-high | how much more the search extracts | simplest sentence; scale-free within a game |
| 7 | **Oracle regret / best-of-field share** | Medium-high | who finds the best sequence | free from existing runs; robust to scale; needs ≥3 arms to be meaningful |
| 8 | **Cohen's *d*** on the paired difference | Medium | cross-study comparability | standard; less interpretable than #2 within the domain |
| 9 | Anytime curves per burn phase | Medium | when the effect appears | secondary |
| 10 | Diversity guard-rails (unique sequences, duplicate share) | Medium | mechanism diagnostics | within-run only; never a headline |
| 11 | Raw heuristic delta | Low | — | game-specific and uninterpretable alone; always convert to #2 or #6 |
| 12 | Full-game win rate | Low per hour | external validity | noisy, expensive, confounded; the thing MAGAT replaces — keep as an optional yardstick, never the primary |

Three of the top eight (#2, #7, #8) were computed *after* the line finished, from data already on
disk. MAGAT's analyzer should produce all twelve from one run.

## 5. Non-negotiable design principles

Each of these was paid for.

1. **Freeze the portfolio and the split before any arm runs**, with a SHA over the ordered scenario
   identity so the freeze is auditable. Deal matched pairs, not alternate draws (the first split was
   33/27 with a 22-point branching imbalance).
2. **Price per (agent, budget level)**, never one global weight set — the old scheme mis-allocated up
   to 73% at 400k. Record turns-per-rep in the weights file; a check that measures different
   positions reports drift that is not there (55%, reproducibly).
3. **Merge new prices into the existing currency**; never re-measure arms whose leaves have already
   run. Re-denominate by the stored reference so cross-session drift cancels. Ship a drift check
   that writes nothing.
4. **Wall-clock is co-primary.** Every headline in units and seconds; a units-only win is
   unresolved.
5. **Every parameter sweep includes the parameter's null.** The AB-EMCTS champion sat at ε = 1.00 —
   outside the grid — and was found only because one arm happened to be defined with the bias off.
6. **Attribution controls are in the default grid, not added after a champion is declared.**
7. **Decouple parameters that are conceptually distinct** (insertion granularity vs repair
   granularity were tied by a helper and it cost a follow-up stage).
8. **Put the crossing point inside the budget ladder.** Extrapolation is a lower bound at best.
9. **Gates are written before the data exists**, with the decision rule and the pre-registered
   response. Four of six fired against expectation and were accepted because of this.
10. **Checkpoint per leaf, resume by re-running the same command, never delete to re-run.** Logs
    append with a header; gates read the latest block. Output lives outside the clone.
11. **A run is a notebook**: one cell per stage, self-skipping setup, a bundle cell per stage that
    zips exactly the files the analysis needs.
12. **Two arms producing identical output is a bug**, not thin data. Assert it.

## 6. Architecture

Seven components and one contract. Names are new — MAGAT does not inherit `v6`, `Stage1`, `AB_`.

**The game contract** (`Game`): `copy(state)`, `advance(state, action)`, `legalActions(state)`,
`evaluate(state)`, `isTerminal(state)`, `serialize/deserialize(state)`, `branchingFactor(state)`,
plus a `capture(...)` hook that yields scenarios from played games. Every forward-model call goes
through the meter. Nothing else about the game is assumed.

| Component | Does | Extract from |
|---|---|---|
| `meter` | thread-local op counters; per-profile weights; budget stop | `core/game/ComputeMeter.java` |
| `portfolio` | capture, stratify (source × phase × branching), freeze, matched-pair split, identity SHA, `apply(split)` guard | `BuildV6Portfolio.java`, `PortfolioSplit.java`, `IsolationPhaseRunner.Scenario` |
| `calibrate` | per-(agent, budget) weights; fastest-rep estimator with two-fastest gate; per-level warmup; merge with re-denomination; `--arms`; `--checkArms`; turns-per-rep recorded; loaded-mode harness with work counter | `ComputeCalibrateV6.java` |
| `run` | agent × budget × scenario × visit grid; per-leaf checkpoint; split-aware keys; resume; per-level thread override; load governor; lock per output tree | `Stage1Runner.java`, `IsolationPhaseRunnerV6.java`, `LoadGovernor.java`, `RunGuard.java`, `ThreadCalibrationV6.java`, `ServerProfile.java` |
| `analyze` | everything in §4; `--baseline` re-anchoring; per-source CIs; dev/holdout side-by-side | `analysis/analyze_stage1.py` |
| `probe` | mechanism-support probe (keys, observations per key); equivalence gates; structure assertions | `ActionBiasFloorProbe.java`, `SelectionStructureCheck.java`, `Stage1Runner.verify` |
| `notebook` | the run loop, self-tests, bundles, shakedown | `analysis/run_ab_emcts.ipynb`, `run_ab_shakedown.sh`, the generator in this line's scratchpad |

**Reference agents** shipped per game: `Random`, the game's plain baseline search (plain EMCTS for
Tribes), and one strong reference (`Comma_Z0_Coarse` for Tribes) so new algorithms have something to
beat and the attribution pattern has a worked example.

**Language.** The extraction is Java; Tribes is Java; the metering must be in-process (a process
boundary on every forward-model call would dominate the thing being counted). The recommendation is
**Java core with a documented game contract**, and a Python analysis layer (already Python). Whether
Python-native games are supported through a bridge is an open decision (§8).

## 7. Games

**Start with Tribes only.** It is the substrate everything was proven on, it is Java, and the
portfolio, calibration, reference agents and a strong result already exist. v0.1 is the methodology
extracted cleanly plus Tribes as its first game. Adding a second game before the contract is stable
would freeze mistakes into it.

On the three engines considered:

- **Stratega** — the right second game. It was built for exactly this niche (turn-based
  multi-unit strategy, multi-action turns, general game description) by the same research lineage as
  Tribes, so its abstractions will map onto the contract. It is C++: a JNI wrapper or a serialized
  state bridge is real work, and a process-boundary bridge is too slow for metering. Budget it as a
  project, not a task.
- **Ludii** — a poor fit for *multi-action* games. Its library is overwhelmingly alternating-move
  single-action board games; the forward model interprets a game description and is comparatively
  slow. It is Java, which is its one advantage. Worth it only for a specific multi-action Ludii game.
- **Griddly** — designed for RL, mostly single-agent, grid-world, Python-first. Fast, but off-target
  for the multi-action question.

The honest framing: those frameworks give you a forward model and let you run games. None gives you
a pinned portfolio, a paired design, a calibrated currency with a wall-clock check, a holdout, or
attribution as a pattern. That gap is the product, and it does not depend on the game count.

## 8. Decisions — confirmed 2026-09-09

| Decision | Confirmed |
|---|---|
| Language / platform | **Java core + Python analysis.** In-process metering; games port to the Java `Game` contract. |
| v0.1 scope | **Core + Tribes.** Stratega is v0.2, after the contract has survived one extraction. |
| Tribes code | **Submodule / dependency, adapter in MAGAT.** Upstream has no license file; public availability grants no redistribution rights by default, so no copying in until the authors give explicit permission (the user will ask). Credit them regardless. Adapter design is identical either way. |
| Cross-machine promise | **Published R7615 weights + one-command recalibration with drift report + raw op counts on every result.** |
| Reference agents | **`Random`, plain EMCTS, `Comma_Z0_Coarse`.** |
| MAGAT license | Open — MIT or Apache-2.0 recommended; not blocking, but required before any release tag. |

## 9. Known traps (each cost real time; do not repeat)

- A Jupyter kernel's cwd is the **notebook file's folder**; locate the project root and `chdir`,
  never assert.
- Output must live **outside** the clone when the loop re-clones.
- Verify the consumer reads what the producer writes (the weights pointer was never wired).
- OS load average is meaningless inside a container; gate on the harness's own work.
- Turns-per-rep selects *positions*; a comparison must reproduce it or it compares different things.
- A merged arm is written against the *file's* reference: a slower machine overcharges exactly the
  newly priced arms.
- Two arms with identical output is a bug. A parameter grid without its null is a design flaw.
- Commit before a worktree can disappear.
