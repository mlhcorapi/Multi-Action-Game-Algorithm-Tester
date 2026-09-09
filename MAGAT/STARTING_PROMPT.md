# Starting prompt for the first MAGAT session

*Paste everything below the line into the first Claude Code session in the MAGAT repository.*

---

You are starting **MAGAT — Multi-Action Game Algorithm Tester**, a new open framework for
evaluating search algorithms in multi-action games. This repository is a clone of the
`for-testing-project` branch of my thesis repo, so everything MAGAT will be built from is already
here. Read `MAGAT/BRIEF.md` first and treat it as the design authority; it was written at the end of
the experimental line it distils, and every principle in it was paid for.

**The vision, in my words.** Researchers currently test multi-action game algorithms by playing
full games, multithreaded, without compute budgets, on whatever hardware they have. That is slow,
noisy, and results cannot be compared across papers. MAGAT brings the approach I developed instead:
a standard **portfolio of captured positions** per game, a **calibrated compute-unit currency** so a
budget means the same forward-model work on any machine, a **paired scenario-clustered statistical
design** with a development/holdout split, and **effect sizes reported as budget multipliers** so
results are dimensionless and comparable. A new researcher should be able to add their algorithm,
run it against the existing portfolio, and know within an afternoon how it compares to everything
that came before — and anyone reading their result should be able to re-price it with their own
machine's weights from the published op counts. `Tribes-master/analysis/AB_EMCTS_RETROSPECTIVE.md`
§3 is the worked example of what a MAGAT result should look like.

**What v0.1 is.** The methodology extracted into a clean, game-agnostic core, with Tribes as its
first game and a strong worked result already in place. Not a second game. Not engine integrations.
The brief's §6 extraction map says which existing file becomes which component, and §5 lists the
twelve principles that are not negotiable. The old names (`v6`, `Stage1`, `AB_`, `ExperimentV6`) do
not survive the extraction; name things for what they are.

**Decisions I have confirmed** *(fill in from the answers to the questions in the brief's §8 —
the session should not proceed on any of these until they are stated here)*:

- Language / platform: ______
- v0.1 scope: ______
- How Tribes code is included, and its license: ______
- Cross-machine promise: ______
- Reference agents: ______
- MAGAT license: ______

**How to work.** The same discipline the line ended with, from day one:

1. **Plan before building.** Propose the package layout, the `Game` contract, and the CLI surface as
   a document first; I will review it before any extraction starts.
2. **Extract, don't rewrite.** The existing components work and were tested at scale. Move them,
   rename them, generalize the game-specific seams behind the contract, and keep their tests.
3. **A run is a notebook.** Ship `run_magat.ipynb` alongside the CLI from the first commit: one cell
   per stage, setup cells that skip themselves, a bundle cell per stage. Self-test the notebook by
   executing its setup cells from three launch directories before every commit.
4. **A shakedown script** that exercises every moving part at toy size in minutes, exits non-zero on
   any failure, and is run before every PR.
5. **Commit small and often**, and never leave work uncommitted in a worktree.
6. **Read `MAGAT/BRIEF.md` §9 before touching the calibrator or the notebook.** Every item there is
   a trap that already cost a day.

**The first deliverable is the design document**, not code. When it is approved, the first code
milestone is: the `Game` contract, the meter, and the Tribes adapter, with the existing equivalence
gate (`Baseline` bit-identical to plain EMCTS) passing through the new interface. That proves the
seam is in the right place before anything else is moved.

Begin by reading the brief and the retrospective, then ask me the §8 questions that are still
blank above, then propose the design.
