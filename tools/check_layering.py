#!/usr/bin/env python3
"""Layering check: the core never imports a game or an engine, and the Tribes escape hatch is used
only by the adapter itself.

    core (core/src, packages magat.*)       may import: java.*, magat.* except magat.games.*
    adapters (games/*/src)                  may import: core, their own engine
    engines (games/*/engine)                are not checked (not ours)

Exit status is the number of violations.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)", re.M)
FORBIDDEN_IN_CORE = ("magat.games.", "core.", "players.", "utils.", "gui.")


def imports(path: pathlib.Path):
    return [m.group(2) for m in IMPORT.finditer(path.read_text(encoding="utf-8", errors="replace"))]


def main() -> int:
    violations = []
    core = ROOT / "core" / "src"
    for f in sorted(core.rglob("*.java")):
        for imp in imports(f):
            if imp.startswith(FORBIDDEN_IN_CORE):
                violations.append(f"{f.relative_to(ROOT)}: core imports {imp}")
    for f in sorted((ROOT / "core" / "test").rglob("*.java")) if (ROOT / "core" / "test").is_dir() else []:
        for imp in imports(f):
            if imp.startswith(FORBIDDEN_IN_CORE):
                violations.append(f"{f.relative_to(ROOT)}: core test imports {imp}")
    # The Tribes escape hatch: raw() may appear only under the adapter's own source tree.
    for f in sorted(ROOT.rglob("*.java")):
        rel = f.relative_to(ROOT)
        parts = rel.parts
        if "engine" in parts or "Tribes-master" in parts or parts[0] in ("build",):
            continue
        if ".raw()" in f.read_text(encoding="utf-8", errors="replace"):
            if not (parts[0] == "games" and "src" in parts):
                violations.append(f"{rel}: uses the TribesState.raw() escape hatch outside the adapter")
    for v in violations:
        print("  [layering] " + v)
    print(f"layering: {len(violations)} violation(s)")
    return len(violations)


if __name__ == "__main__":
    sys.exit(main())
