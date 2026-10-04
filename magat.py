#!/usr/bin/env python3
"""MAGAT launcher: the one thing a user types.

Locates the repository from any working directory, builds when sources are newer than classes,
computes the classpath, opens the data root (outside the clone), passes the weights file, the engine
commit and the data root explicitly to every JVM, and tees every command's output to
<data>/logs/<name>.log with a timestamped header. The notebook calls this and nothing else.

    python magat.py build [--clean] [--tests]
    python magat.py test
    python magat.py check contract    --game tribes [--portfolio DIR] [--scenarios N] [--ids 1,2]
    python magat.py check equivalence --game tribes [--portfolio DIR] [--weights FILE] [--arms emcts] ...
    python magat.py arms --game tribes
    python magat.py portfolio build  --game tribes --out DIR [--threads N] [--targets single=200,dual=200]
    python magat.py portfolio freeze --portfolio DIR
    python magat.py portfolio verify --game tribes [--portfolio DIR] [--published DIR] [--subset] [--plan N]
    python magat.py portfolio ledger --portfolio DIR
    python magat.py java <args...>          raw magat.cli.Main invocation
    python magat.py layering
    python magat.py notebook build|selftest
    python magat.py shakedown
    python magat.py version
"""
import datetime
import os
import pathlib
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
CORE = ROOT / "core"                      # the Java core: core/src, core/test, core/lib
# Build output; MAGAT_BUILD moves it, e.g. out of a synced folder whose daemon holds files open.
BUILD = pathlib.Path(os.environ.get("MAGAT_BUILD") or (ROOT / "build")).resolve()
CLASSES = BUILD / "classes"
TEST_CLASSES = BUILD / "test-classes"
GAMES = ROOT / "games"
DEFAULT_HEAP = os.environ.get("MAGAT_HEAP", "4g")
# A portfolio build holds every capture in memory and plays a source game per worker.
BUILD_HEAP = os.environ.get("MAGAT_HEAP", "8g")
GC_FLAGS = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=500"]

# Engine-specific JVM properties: Tribes resolves its data files against tribes.home.
ENGINE_PROPS = {
    "tribes": lambda engine: [f"-Dtribes.home={engine}"],
}

# Published artefacts per game: the defaults the CLI is pointed at when a flag is not given.
PUBLISHED = {
    "tribes": {
        "portfolio": GAMES / "tribes" / "portfolio" / "v1",
        "weights": GAMES / "tribes" / "calibration" / "r7615" / "compute_weights.properties",
    }
}


def data_root() -> pathlib.Path:
    d = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    try:
        d.relative_to(ROOT)
        sys.exit(f"MAGAT_DATA={d} is inside the repository; run output must live outside the clone")
    except ValueError:
        pass
    (d / "logs").mkdir(parents=True, exist_ok=True)
    return d


def engine_dir(game: str) -> pathlib.Path:
    """The engine submodule; MAGAT_<GAME>_ENGINE overrides it (a local tree while the fork is set up)."""
    override = os.environ.get(f"MAGAT_{game.upper()}_ENGINE")
    if override:
        return pathlib.Path(override).resolve()
    return GAMES / game / "engine"


def engine_commit(game: str) -> str:
    d = engine_dir(game)
    try:
        return subprocess.run(["git", "-C", str(d), "rev-parse", "HEAD"], capture_output=True,
                              text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


def games() -> list:
    return sorted(p.name for p in GAMES.iterdir() if p.is_dir()) if GAMES.is_dir() else []


def source_dirs(tests: bool = False) -> list:
    dirs = [CORE / ("test" if tests else "src")]
    for g in games():
        dirs.append(GAMES / g / ("test" if tests else "src"))
        dirs.append(engine_dir(g) / ("test" if tests else "src"))
    return [d for d in dirs if d.is_dir()]


def lib_jars() -> list:
    jars = []
    for d in [CORE / "lib"] + [engine_dir(g) / "lib" for g in games()]:
        if d.is_dir():
            jars += sorted(str(j) for j in d.glob("*.jar"))
    return jars


def classpath(*extra) -> str:
    return os.pathsep.join([str(e) for e in extra] + lib_jars())


def java_files(dirs) -> list:
    return sorted(str(p) for d in dirs for p in d.rglob("*.java"))


def newest_mtime(paths) -> float:
    return max((os.path.getmtime(p) for p in paths), default=0.0)


def run(cmd, log: str = None, check: bool = True, cwd=None) -> int:
    """Run a command, stream it, tee it to <data>/logs/<log>.log (append, timestamped)."""
    logfile = data_root() / "logs" / f"{log or 'magat'}.log"
    started = datetime.datetime.now()
    shown = " ".join(str(c) for c in cmd)
    print(f"$ {shown}\n  -> {logfile}", flush=True)
    with open(logfile, "a", encoding="utf-8") as fh:
        fh.write(f"\n>>>>> {started:%Y-%m-%d %H:%M:%S} $ {shown}\n")
        p = subprocess.Popen([str(c) for c in cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace", bufsize=1, cwd=cwd or str(ROOT))
        for line in p.stdout:
            print(line, end="", flush=True)
            fh.write(line)
        rc = p.wait()
        fh.write(f"===== exit {rc} after {datetime.datetime.now() - started}\n")
    print(f"[exit {rc}] after {datetime.datetime.now() - started}", flush=True)
    if check and rc != 0:
        sys.exit(rc)
    return rc


def clean_build_dir() -> None:
    """Remove the build output. A sync daemon (OneDrive) can hold a directory open for a moment,
    so retry, then move what will not delete aside rather than build on top of stale classes."""
    if not BUILD.exists():
        return
    for attempt in range(4):
        shutil.rmtree(BUILD, ignore_errors=True)
        if not BUILD.exists():
            return
        time.sleep(1.0 + attempt)
    aside = BUILD.with_name(f"{BUILD.name}.stale-{int(time.time())}")
    try:
        BUILD.rename(aside)
        print(f"warning: could not delete {BUILD}; moved it to {aside} (delete it later)")
    except OSError as e:
        sys.exit(f"cannot clean {BUILD}: {e}\nset MAGAT_BUILD to a directory outside any synced folder")


def build(clean: bool = False, tests: bool = False, force: bool = False) -> None:
    if clean:
        clean_build_dir()
    BUILD.mkdir(parents=True, exist_ok=True)
    srcs = java_files(source_dirs())
    if not srcs:
        sys.exit("no Java sources found")
    stamp = CLASSES / ".built"
    if force or not stamp.exists() or newest_mtime(srcs) > stamp.stat().st_mtime:
        CLASSES.mkdir(parents=True, exist_ok=True)
        listing = BUILD / "sources.txt"
        listing.write_text("\n".join(srcs) + "\n", encoding="utf-8")
        run(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn", "-d", CLASSES,
             "-cp", classpath(), f"@{listing}"], log="build")
        stamp.touch()
        print(f"BUILD OK - {len(srcs)} sources")
    if tests:
        tsrcs = java_files(source_dirs(tests=True))
        tstamp = TEST_CLASSES / ".built"
        if force or not tstamp.exists() or newest_mtime(tsrcs + srcs) > tstamp.stat().st_mtime:
            TEST_CLASSES.mkdir(parents=True, exist_ok=True)
            listing = BUILD / "test-sources.txt"
            listing.write_text("\n".join(tsrcs) + "\n", encoding="utf-8")
            run(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn", "-d", TEST_CLASSES,
                 "-cp", classpath(CLASSES), f"@{listing}"], log="build")
            tstamp.touch()
            print(f"TEST BUILD OK - {len(tsrcs)} sources")


def test_classes() -> dict:
    """Test class names grouped by the directory the JVM must run in: an engine's own tests read
    its data files relative to the working directory, so they run from the engine directory."""
    groups = {}
    engines = {engine_dir(g).resolve() for g in games()}
    for d in source_dirs(tests=True):
        cwd = d.parent if d.parent.resolve() in engines else ROOT
        for p in d.rglob("*Test.java"):
            groups.setdefault(cwd, []).append(".".join(p.relative_to(d).with_suffix("").parts))
    return {k: sorted(v) for k, v in groups.items()}


def java(args, heap: str = None, props=(), log: str = None, check: bool = True) -> int:
    build()
    game = None
    if "--game" in args:
        game = args[args.index("--game") + 1]
    sysprops = [f"-Dmagat.data={data_root()}"]
    if game:
        sysprops.append(f"-Dmagat.engine.commit={engine_commit(game)}")
        sysprops += ENGINE_PROPS.get(game, lambda d: [])(engine_dir(game))
    cmd = ["java", f"-Xmx{heap or DEFAULT_HEAP}", *GC_FLAGS, *sysprops, *props,
           "-cp", classpath(CLASSES), "magat.cli.Main", *args]
    return run(cmd, log=log or (args[0] if args else "java"), check=check)


def with_published_defaults(args: list) -> list:
    """Fill --portfolio / --weights from the published artefacts of --game when not given."""
    args = list(args)
    if "--game" in args:
        game = args[args.index("--game") + 1]
        pub = PUBLISHED.get(game, {})
        if "check" in args and "--portfolio" not in args and "portfolio" in pub and pub["portfolio"].is_dir():
            args += ["--portfolio", str(pub["portfolio"])]
        if "equivalence" in args and "--weights" not in args and "weights" in pub and pub["weights"].is_file():
            args += ["--weights", str(pub["weights"])]
        if args[:2] == ["portfolio", "verify"] and "--portfolio" not in args and "portfolio" in pub:
            args += ["--portfolio", str(pub["portfolio"])]
    return args


def main(argv: list) -> None:
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        return
    cmd, rest = argv[0], argv[1:]
    if cmd == "version":
        java(["version"], log="version")
    elif cmd == "build":
        build(clean="--clean" in rest, tests="--tests" in rest, force=True)
    elif cmd == "test":
        build(tests=True)
        for cwd, classes in test_classes().items():
            run(["java", f"-Xmx{DEFAULT_HEAP}", "-cp", classpath(TEST_CLASSES, CLASSES),
                 "org.junit.runner.JUnitCore", *classes], log="test", cwd=str(cwd))
    elif cmd in ("check", "arms", "portfolio"):
        heap = BUILD_HEAP if rest[:1] == ["build"] else None
        java(with_published_defaults([cmd, *rest]), heap=heap,
             log=cmd + ("-" + rest[0] if rest and not rest[0].startswith("--") else ""))
    elif cmd == "java":
        java(rest, log="java")
    elif cmd == "layering":
        run([sys.executable, ROOT / "tools" / "check_layering.py"], log="layering")
    elif cmd == "notebook":
        sub = rest[0] if rest else "selftest"
        script = "make_notebook.py" if sub == "build" else "notebook_selftest.py"
        run([sys.executable, ROOT / "tools" / script, *rest[1:]], log="notebook")
    elif cmd == "shakedown":
        run([sys.executable, ROOT / "tools" / "shakedown.py", *rest], log="shakedown")
    else:
        sys.exit(f"unknown command '{cmd}'\n{__doc__}")


if __name__ == "__main__":
    main(sys.argv[1:])
