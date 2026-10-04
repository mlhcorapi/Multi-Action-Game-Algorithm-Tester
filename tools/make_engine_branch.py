#!/usr/bin/env python3
"""Assemble the Tribes engine fork's working tree from the thesis tree.

    python tools/make_engine_branch.py <thesis Tribes-master dir> <fork working tree (checked out at the upstream base)>

Copies the engine subset of the thesis tree over the fork's working tree: the engine (`core/`,
`utils/`, `gui/`, the top-level mains), the thesis's agents (`players/`, kept as oracles), the
`core/data/dto` records those agents write, `test/`, `lib/` (json.jar plus junit/hamcrest for the
tests), and the data files upstream ships. It drops what belongs to MAGAT or to the thesis line's
history: `core/data/exp`, `core/data/*.java`, `analysis/`, `test_results/`, the shakedown script,
build artefacts and generated listings. Text files are written with LF endings so the diff against
upstream shows the thesis's changes and nothing else.

Recorded here, rather than done by hand, so the provenance of the fork is reproducible.
"""
import pathlib
import shutil
import sys

DROP_DIRS = {"analysis", "test_results", "bin", "out", "out_build", ".git", ".idea", "__pycache__"}
DROP_TREES = {("src", "core", "data", "exp")}
DROP_FILES = {"run_ab_shakedown.sh", "Benchmark.java", "sources.txt", "gen_output.txt",
              "test-run-guide.md", ".gitignore"}
DROP_SRC_FILES = {("src", "core", "data", "CsvDataHarvester.java"),
                  ("src", "core", "data", "ExperimentRunner.java"),
                  ("src", "core", "data", "Phase0SmokeTest.java")}
TEXT_SUFFIXES = {".java", ".md", ".txt", ".json", ".csv", ".sh", ".py", ".properties", ".MF"}

GITIGNORE = """*.class
*.iml
.idea/
out/
bin/
save/
res/
map/
*.pyc
py/venv/
.DS_Store
"""


def wanted(rel: pathlib.Path) -> bool:
    parts = rel.parts
    if any(p in DROP_DIRS for p in parts[:-1]):
        return False
    for tree in DROP_TREES:
        if parts[:len(tree)] == tree:
            return False
    if parts in DROP_SRC_FILES:
        return False
    if rel.name in DROP_FILES and len(parts) == 1:
        return False
    return True


def copy_one(src: pathlib.Path, dst: pathlib.Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.suffix in TEXT_SUFFIXES or src.name in ("readme", "MANIFEST.MF"):
        data = src.read_bytes().replace(b"\r\n", b"\n")
        dst.write_bytes(data)
    else:
        shutil.copyfile(src, dst)


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    thesis, fork = pathlib.Path(argv[0]).resolve(), pathlib.Path(argv[1]).resolve()
    if not (thesis / "src" / "core" / "game" / "GameState.java").is_file():
        sys.exit(f"{thesis} is not a Tribes tree")
    if not (fork / ".git").exists():
        sys.exit(f"{fork} is not a git working tree")
    # Clear the fork's working tree (everything but .git), then copy the subset over.
    for child in list(fork.iterdir()):
        if child.name == ".git":
            continue
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    copied = 0
    for src in sorted(thesis.rglob("*")):
        if not src.is_file():
            continue
        rel = src.relative_to(thesis)
        if not wanted(rel):
            continue
        copy_one(src, fork / rel)
        copied += 1
    (fork / ".gitignore").write_text(GITIGNORE, encoding="utf-8")
    print(f"copied {copied} files into {fork}")


if __name__ == "__main__":
    main(sys.argv[1:])
