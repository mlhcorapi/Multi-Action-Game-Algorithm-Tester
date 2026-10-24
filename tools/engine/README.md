# The Tribes engine fork

MAGAT runs Tribes from a fork of [GAIGResearch/Tribes](https://github.com/GAIGResearch/Tribes):
[mlhcorapi/Tribes](https://github.com/mlhcorapi/Tribes), branch `magat-engine`, checked out as the
submodule `games/tribes/engine` at the commit this MAGAT commit pins.

| Commit | What it is |
|---|---|
| `270e7e5` | Upstream Tribes. |
| `82b62ac` | The extended engine the reference agents come from: upstream plus the EMCTS and TBETS agents MAGAT's ports are checked against (and other research agents), with line endings normalised. |
| `b9d27af` | `patch_engine_paths.py`: the engine's data files are found from any working directory. |
| `828b130` | `patch_engine_perf.py`: faster legal-action generation, every output unchanged (the commit adds `MoveGenerationEquivalenceTest`). |

Each patch is idempotent and is recorded here so the fork's changes can be reproduced on an upstream
tree:

```bash
python tools/engine/patch_engine_paths.py games/tribes/engine
```

```bash
python tools/engine/patch_engine_perf.py games/tribes/engine
```

The Tribes authors gave permission for MAGAT's use of their code. None of it is copied into this
repository: the engine is only ever the submodule.
