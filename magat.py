#!/usr/bin/env python3
"""MAGAT launcher: the one thing a user types.

Locates the repository from any working directory, builds when sources are newer than classes,
computes the classpath, opens the data root (outside the clone), passes the weights file, the engine
commit and the data root explicitly to every JVM, and tees every command's output to
<data>/logs/<name>.log with a timestamped header. The notebook calls this and nothing else.

    python magat.py build [--clean] [--tests]
    python magat.py test [--java | --python]          JUnit, then the analysis package's unit tests: one line and any
                                                      failures; everything they print in <data>/logs/test.log
    python magat.py check contract    --game tribes [--portfolio DIR] [--scenarios N] [--ids 1,2]
    python magat.py check equivalence --game tribes [--portfolio DIR] [--weights FILE] [--arms emcts] ...
    python magat.py arms --game tribes
    python magat.py portfolio build  --game tribes --out DIR [--threads N] [--targets single=200,dual=200]
    python magat.py portfolio freeze --portfolio DIR
    python magat.py portfolio verify --game tribes [--portfolio DIR] [--published DIR] [--subset] [--plan N]
    python magat.py machine
    python magat.py threads   --game tribes --out DIR [--portfolio DIR] [--levels ...] [--ladder 2,4,8] [--segment-seconds 25]
    python magat.py calibrate --game tribes --out DIR [--portfolio DIR] [--levels ...] [--arms a,b|@set] [--replace a] [--toy]
                              [--audit-warn 0.10]
    python magat.py calibrate --estimate --game tribes [the same arguments]     what the session would take; writes nothing
    python magat.py calibrate --check --game tribes --arms a,b [--weights FILE] [--portfolio DIR]   gates each cell's turn price
    python magat.py calibrate-controlled --name NAME [--preflight | --checks | --no-checks] ...
                              this machine's own currency in one session, with reliability checks (docs/CALIBRATING_A_MACHINE.md)
    python magat.py calibrate-time --game tribes --remeasure [--times 500,1000,5000] [--positions 64] [--tolerance 0.05] [--round 500]
                              the levels that are those milliseconds of thinking time here (a maintainer's tool, run on the
                              reference machine; refused without --remeasure once games/<game>/levels.json is published)
    python magat.py weights   [--game tribes] [--weights FILE] [--arms a,b|@set] [--levels ...] [--progress]
    python magat.py run --experiment SPEC [--split dev|holdout|all] [--out DIR] [--portfolio DIR] [--weights FILE]
                        [--threads N] [--threads-at 400000=31] [--retire-leaf arm@budget] [--toy]
    python magat.py experiment validate --experiment SPEC [--weights FILE | --no-weights] [--split ...]
    python magat.py bundle --name NAME --paths P [P ...] [--exclude seqlog_,mutlog_]
    python magat.py check replay    --game tribes --leaf DIR [--portfolio DIR] [--weights FILE] [--turns 20]
    python magat.py check time      --game tribes (--weights FILE | --fixed-weights) [--levels ...] [--arms a,b] [--targets ms,...]
                                    single-threaded whole turns over spread positions: each level's thinking time here
    python magat.py check audit     --game tribes [--arms a,b|@set] [--budget 40000] [--warn 0.10] [--fail 0.25]
    python magat.py analyze --run DIR [--experiment SPEC] [--baseline ARM] [--reprice WEIGHTS] --out DIR
                            [--compare DIR [--compare-weights WEIGHTS]]
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
ANALYSIS = ROOT / "analysis"              # the Python analysis package, magat_analysis
DEFAULT_HEAP = os.environ.get("MAGAT_HEAP", "4g")


def memory_available() -> tuple:
    """(bytes, where from) this process may use: a container's cgroup limit, else what Linux reports
    available (a shared server's memory is not all ours), else the machine's physical memory."""
    for f in ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"):
        try:
            v = pathlib.Path(f).read_text().strip()
        except OSError:
            continue
        if v.isdigit() and int(v) < (1 << 50):
            return int(v), "the container's memory limit"
    try:
        for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024, "the memory available now"
    except OSError:
        pass
    if os.name == "nt":
        import ctypes

        class Status(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong)] + [
                (n, ctypes.c_ulonglong) for n in ("ullTotalPhys", "ullAvailPhys", "ullTotalPageFile", "ullAvailPageFile",
                                                  "ullTotalVirtual", "ullAvailVirtual", "ullAvailExtendedVirtual")]
        s = Status()
        s.dwLength = ctypes.sizeof(Status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(s)):
            return s.ullTotalPhys, "the machine's physical memory"
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"), "the machine's physical memory"
    except (ValueError, OSError, AttributeError):
        return 0, "unknown"


HEAP_BASE_MB, HEAP_PER_WORKER_MB, HEAP_MIN_MB = 1024, 256, 2048


def big_heap() -> tuple:
    """The heap for work that runs a search per worker: a portfolio build, a thread sweep, a run. Sized to
    the workers, not to the machine. A Tribes worker holds 5-35 MB; the
    rest of its heap is the engine's short-lived garbage, about 2-3 GB a second, which G1 lets fill whatever
    heap it is given before it collects. 256 MB of room per worker keeps collecting near 4% of the time at
    every thread count. Workers: MAGAT_MAX_THREADS, else half the logical CPUs (one per physical core).
    Never more than half the memory available, never less than 2 GB."""
    if "MAGAT_HEAP" in os.environ:
        return os.environ["MAGAT_HEAP"], "MAGAT_HEAP"
    try:
        cpus = len(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        cpus = os.cpu_count() or 2
    capped = os.environ.get("MAGAT_MAX_THREADS", "").strip()
    workers = int(capped) if capped.isdigit() and int(capped) > 0 else max(1, cpus // 2)
    mb = HEAP_BASE_MB + HEAP_PER_WORKER_MB * workers
    why = f"1 GB + 256 MB for each of {workers} worker(s)"
    avail, where = memory_available()
    if avail > 0 and mb > (avail >> 21):
        mb = avail >> 21
        why += f", capped at half of {where} ({avail / (1 << 30):.0f} GB)"
    if mb < HEAP_MIN_MB:
        mb = HEAP_MIN_MB
        why += ", raised to the 2 GB floor"
    return f"{mb}m", why


GC_FLAGS = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=500"]

# Engine-specific JVM properties: Tribes resolves its data files against tribes.home.
ENGINE_PROPS = {
    "tribes": lambda engine: [f"-Dtribes.home={engine}"],
}

# Published artefacts per game: the defaults the CLI is pointed at when a flag is not given. A game's
# published currency ("weights") is the reference machine's, once it is published.
PUBLISHED = {
    "tribes": {
        "portfolio": GAMES / "tribes" / "portfolio" / "v1",
        "levels": GAMES / "tribes" / "levels.json",
    }
}


def published_levels(game: str) -> list:
    """The game's budget levels in units, from its published levels.json (written by calibrate-time on the
    reference machine); empty until it exists."""
    f = PUBLISHED.get(game, {}).get("levels")
    if not f or not f.is_file():
        return []
    import json
    return [int(lv["units"]) for lv in json.loads(f.read_text(encoding="utf-8"))["levels"]]


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


def git(*args, cwd=None) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(cwd or ROOT), *args], capture_output=True, text=True)


def ensure_engines() -> None:
    """Every game's engine, before anything is compiled. A clone made without --recurse-submodules has an
    empty engine directory, which javac reports as a hundred missing packages: fetch the submodule the
    repository pins, or say plainly what is missing. An engine at another commit than the pinned one (as
    after a `git pull`, which moves the pin but not the engine) is checked out at the pinned commit when it
    has no local changes, and reported when it has, since a run's provenance records the engine's commit."""
    modules = ROOT / ".gitmodules"
    declared = modules.read_text(encoding="utf-8") if modules.is_file() else ""
    for g in games():
        d = engine_dir(g)
        override = os.environ.get(f"MAGAT_{g.upper()}_ENGINE")
        if not (d / "src").is_dir():
            if override:
                sys.exit(f"MAGAT_{g.upper()}_ENGINE={override} has no src/ directory: point it at an engine tree")
            rel = d.relative_to(ROOT).as_posix()
            if f"path = {rel}" not in declared:
                continue    # a game whose engine is not a submodule (or that has none)
            if (ROOT / ".git").exists() and shutil.which("git"):
                print(f"# the {g} engine ({rel}) is not checked out: fetching the submodule this repository pins",
                      flush=True)
                run(["git", "submodule", "update", "--init", "--recursive", rel], log="build", check=False)
            if not (d / "src").is_dir():
                block = declared.split(f"path = {rel}", 1)[1].split("[submodule", 1)[0]
                field = lambda k: next((l.split("=", 1)[1].strip() for l in block.splitlines()
                                        if l.strip().startswith(k + " ")), "?")
                sys.exit(f"the {g} engine is missing: {d} has no src/ directory.\n"
                         f"In a git clone of MAGAT run:  git submodule update --init --recursive {rel}\n"
                         f"(or clone with --recurse-submodules). If this copy is not a git clone (a downloaded\n"
                         f"archive), put the engine tree there:\n"
                         f"  git clone --branch {field('branch')} {field('url')} {rel}")
            continue
        if override or not (ROOT / ".git").exists() or not shutil.which("git"):
            continue
        rel = d.relative_to(ROOT).as_posix()
        # What `git submodule update` checks out: the commit in the index ("160000 <sha> 0\t<path>").
        staged = git("ls-files", "--stage", "--", rel).stdout.split()
        pinned = staged[1] if len(staged) >= 2 and staged[0] == "160000" else ""
        head = git("rev-parse", "HEAD", cwd=d).stdout.strip()
        if pinned and head and head != pinned:
            # `git pull` moves the pin but not the engine. With no local changes in the engine, follow the pin.
            if not git("status", "--porcelain", cwd=d).stdout.strip():
                print(f"# the {g} engine is at {head[:10]}, but this commit of MAGAT pins {pinned[:10]}: "
                      f"checking out the pinned engine", flush=True)
                run(["git", "submodule", "update", "--init", "--recursive", rel], log="build", check=False)
                head = git("rev-parse", "HEAD", cwd=d).stdout.strip()
            if head != pinned:
                print(f"warning: the {g} engine is at {head[:10]} (with local changes, or the pinned commit could not be "
                      f"fetched), but this commit of MAGAT pins {pinned[:10]}.\n"
                      f"  Runs record the engine's commit; to use the pinned one: git submodule update --recursive {rel}",
                      flush=True)


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


def say(text: str = "", end: str = "\n") -> None:
    """Print, unless whoever reads this output has gone (`magat ... | grep -q` stops at its match): from then
    on the screen gets nothing, and the command still runs to its end, logged, with its own exit code."""
    try:
        print(text, end=end, flush=True)
    except BrokenPipeError:
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())


def run(cmd, log: str = None, check: bool = True, cwd=None, echo: bool = True, lines: list = None) -> int:
    """Run a command, stream it (unless echo is off), tee it to <data>/logs/<log>.log (append, timestamped);
    `lines` collects its output."""
    logfile = data_root() / "logs" / f"{log or 'magat'}.log"
    started = datetime.datetime.now()
    shown = " ".join(str(c) for c in cmd)
    if echo:
        say(f"$ {shown}\n  -> {logfile}")
    with open(logfile, "a", encoding="utf-8") as fh:
        fh.write(f"\n>>>>> {started:%Y-%m-%d %H:%M:%S} $ {shown}\n")
        p = subprocess.Popen([str(c) for c in cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace", bufsize=1, cwd=cwd or str(ROOT))
        for line in p.stdout:
            if echo:
                say(line, end="")
            fh.write(line)
            if lines is not None:
                lines.append(line.rstrip("\n"))
        rc = p.wait()
        fh.write(f"===== exit {rc} after {datetime.datetime.now() - started}\n")
    if echo:
        say(f"[exit {rc}] after {datetime.datetime.now() - started}")
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


def compile_java(cmd, echo: bool) -> None:
    """javac; quiet unless it fails (echo off), when its output is shown and the launcher stops."""
    out = []
    rc = run(cmd, log="build", check=False, echo=echo, lines=out)
    if rc != 0:
        if not echo:
            print("\n".join(out))
        sys.exit(rc)


def build(clean: bool = False, tests: bool = False, force: bool = False, echo: bool = True) -> None:
    if clean:
        clean_build_dir()
    BUILD.mkdir(parents=True, exist_ok=True)
    ensure_engines()
    srcs = java_files(source_dirs())
    if not srcs:
        sys.exit("no Java sources found")
    stamp = CLASSES / ".built"
    if force or not stamp.exists() or newest_mtime(srcs) > stamp.stat().st_mtime:
        CLASSES.mkdir(parents=True, exist_ok=True)
        listing = BUILD / "sources.txt"
        listing.write_text("\n".join(srcs) + "\n", encoding="utf-8")
        compile_java(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn", "-d", CLASSES,
                      "-cp", classpath(), f"@{listing}"], echo)
        stamp.touch()
        if echo:
            print(f"BUILD OK - {len(srcs)} sources")
    if tests:
        tsrcs = java_files(source_dirs(tests=True))
        tstamp = TEST_CLASSES / ".built"
        if force or not tstamp.exists() or newest_mtime(tsrcs + srcs) > tstamp.stat().st_mtime:
            TEST_CLASSES.mkdir(parents=True, exist_ok=True)
            listing = BUILD / "test-sources.txt"
            listing.write_text("\n".join(tsrcs) + "\n", encoding="utf-8")
            compile_java(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn", "-d", TEST_CLASSES,
                          "-cp", classpath(CLASSES), f"@{listing}"], echo)
            tstamp.touch()
            if echo:
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


# Stack frames of the test runners themselves, left out of a failure as shown (the log keeps them).
RUNNER_FRAMES = ("at org.junit.", "at junit.", "at java.base/jdk.internal.", "at java.base/java.lang.reflect.",
                 "at jdk.internal.", "at sun.reflect.", "at java.lang.reflect.")


def junit_result(lines: list, rc: int) -> tuple:
    """(tests, failed, the failures as shown) from JUnitCore's output."""
    for line in lines:
        if line.startswith("OK (") and rc == 0:
            return int(line.split("(")[1].split()[0]), 0, []
    total = next((line for line in lines if line.startswith("Tests run:")), None)
    if total is None:                                   # the JVM itself failed: show the end of what it said
        return 0, 1, lines[-30:]
    tests, failed = (int(part.split(":")[1]) for part in total.split(",")[:2])
    start = next((i for i, line in enumerate(lines) if line.startswith("There w") and "failure" in line), 0)
    end = next((i for i, line in enumerate(lines) if line.startswith("FAILURES!!!")), len(lines))
    shown = [line for line in lines[start:end] if not line.strip().startswith(RUNNER_FRAMES)]
    return tests, failed, shown


def unittest_result(lines: list, rc: int) -> tuple:
    """(tests, failed, the failures as shown) from `python -m unittest`'s output."""
    ran = next((line for line in lines if line.startswith("Ran ") and " test" in line), None)
    if ran is None:
        return 0, 1, lines[-30:]
    tests = int(ran.split()[1])
    if rc == 0:
        return tests, 0, []
    verdict = next((line for line in reversed(lines) if line.startswith("FAILED (")), "FAILED (failures=1)")
    failed = sum(int(part.split("=")[1]) for part in verdict[len("FAILED ("):-1].split(", ")
                 if part.split("=")[0] in ("failures", "errors"))
    start = next((i for i, line in enumerate(lines) if line.startswith("=" * 70)), len(lines))
    end = max((i for i, line in enumerate(lines) if line.startswith("-" * 70)), default=len(lines))
    return tests, failed, lines[start:end]


def test(rest: list) -> int:
    """MAGAT's JUnit tests, each engine's own, then the analysis package's. The screen gets one line, and the
    real failures when there are any; <data>/logs/test.log gets everything the tests printed."""
    started = time.time()
    groups, shown = [], []
    if "--python" not in rest:
        build(tests=True, echo=False)
        engines = {engine_dir(g).resolve(): g for g in games()}
        for cwd, classes in test_classes().items():
            name = f"{engines[cwd.resolve()]} engine" if cwd.resolve() in engines else "MAGAT"
            out = []
            rc = run(["java", f"-Xmx{DEFAULT_HEAP}", "-cp", classpath(TEST_CLASSES, CLASSES), "org.junit.runner.JUnitCore",
                      *classes], log="test", cwd=str(cwd), check=False, echo=False, lines=out)
            tests, failed, failures = junit_result(out, rc)
            groups.append((name, tests, failed))
            shown += failures
    if "--java" not in rest:
        os.environ["PYTHONPATH"] = os.pathsep.join(p for p in (str(ANALYSIS), os.environ.get("PYTHONPATH")) if p)
        out = []
        rc = run([sys.executable, "-m", "unittest", "discover", "-s", str(ANALYSIS / "tests"), "-t", str(ANALYSIS), "-v"],
                 log="test", check=False, echo=False, lines=out)
        tests, failed, failures = unittest_result(out, rc)
        groups.append(("analysis", tests, failed))
        shown += failures
    tests, failed = sum(g[1] for g in groups), sum(g[2] for g in groups)
    for line in shown:
        print(line)
    verdict = f"FAIL - {failed} of {tests} failed" if failed else f"PASS - {tests} tests"
    parts = ", ".join(f"{name} {bad} of {n}" if bad else f"{name} {n}" for name, n, bad in groups)
    print(f"TESTS: {verdict} ({parts}) in {time.time() - started:.0f} s; "
          f"the full output is in {data_root() / 'logs' / 'test.log'}")
    return 1 if failed else 0


def java(args, heap: str = None, props=(), log: str = None, check: bool = True) -> int:
    build()
    game = None
    if "--game" in args:
        game = args[args.index("--game") + 1]
    commit, notebook = git_state()
    sysprops = [f"-Dmagat.data={data_root()}", f"-Dmagat.git.commit={commit}"]
    if notebook:
        sysprops.append("-Dmagat.git.notebook=modified")
    if game:
        sysprops.append(f"-Dmagat.engine.commit={engine_commit(game)}")
        sysprops += ENGINE_PROPS.get(game, lambda d: [])(engine_dir(game))
    if heap == "big":
        heap, why = big_heap()
        print(f"# heap {heap}: {why}", flush=True)
    cmd = ["java", f"-Xmx{heap or DEFAULT_HEAP}", *GC_FLAGS, *sysprops, *props,
           "-cp", classpath(CLASSES), "magat.cli.Main", *args]
    return run(cmd, log=log or (args[0] if args else "java"), check=check)


def git_state() -> tuple:
    """(this checkout's commit, whether a notebook differs from it), for provenance. The commit carries -dirty when
    a tracked file other than a notebook has uncommitted changes. A notebook run in Jupyter saves its outputs
    into the file, which says nothing about the code a result came from, so it is reported apart."""
    try:
        rev = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, timeout=10).stdout.strip()
        changed = subprocess.run(["git", "diff", "--name-only", "-z", "HEAD"], cwd=ROOT, capture_output=True,
                                 text=True, timeout=10).stdout.split("\0")
    except Exception:
        return "unknown", False
    changed = [f for f in changed if f]
    notebooks = [f for f in changed if f.endswith(".ipynb")]
    return (rev or "unknown") + ("-dirty" if len(notebooks) < len(changed) else ""), bool(notebooks)


def with_spec_game(args: list) -> list:
    """`run` and `experiment` name their game in the spec; pass it on so the JVM gets the engine settings."""
    args = list(args)
    if "--experiment" in args and "--game" not in args:
        import json
        spec = pathlib.Path(args[args.index("--experiment") + 1])
        try:
            game = json.loads(spec.read_text(encoding="utf-8")).get("game")
        except (OSError, ValueError) as e:
            sys.exit(f"cannot read the experiment spec {spec}: {e}")
        if game:
            args += ["--game", game]
    return args


def bundle(argv: list) -> None:
    """Zip pre-specified files of a stage into <data>/bundles/<name>.zip (the notebook's bundle(), as a command)."""
    import glob
    import zipfile
    if "--name" not in argv or "--paths" not in argv:
        sys.exit("usage: magat bundle --name NAME --paths P [P ...] [--exclude seqlog_,mutlog_]")
    name = argv[argv.index("--name") + 1]
    exclude = argv[argv.index("--exclude") + 1].split(",") if "--exclude" in argv else ["seqlog_", "mutlog_", "_running.lock"]
    paths = []
    for a in argv[argv.index("--paths") + 1:]:
        if a.startswith("--"):
            break
        paths.append(a)
    data = data_root()
    files = {}                                       # file -> the folder its name in the zip is relative to
    for item in paths:
        pth = pathlib.Path(item)
        if not pth.is_absolute():
            pth = data / pth
        if pth.is_dir():
            files.update((q, pth.resolve().parent) for q in pth.rglob("*") if q.is_file())
        elif pth.exists():
            files[pth] = pth.resolve().parent
        else:
            files.update((pathlib.Path(h), pathlib.Path(h).resolve().parent) for h in glob.glob(str(pth))
                         if pathlib.Path(h).is_file())
    files = {f: base for f, base in sorted(files.items()) if not any(x in f.name for x in exclude)}
    out = data / "bundles" / f"{name}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f, base in files.items():
            # Inside the data root, the path under it; outside, the path under the folder that was named.
            try:
                arc = f.resolve().relative_to(data)
            except ValueError:
                arc = f.resolve().relative_to(base)
            z.write(f, str(arc))
    print(f"BUNDLE: {len(files)} files -> {out}")


def python_analysis(args: list, log: str) -> int:
    """Run the analysis package (numpy and pandas; matplotlib optional) in a child Python, teed like
    every other command."""
    os.environ["PYTHONPATH"] = os.pathsep.join(p for p in (str(ANALYSIS), os.environ.get("PYTHONPATH")) if p)
    return run([sys.executable, "-m", "magat_analysis", *args], log=log)


def with_published_defaults(args: list) -> list:
    """Fill --portfolio / --weights from the published artefacts of --game when not given. --no-weights: none
    (`experiment validate` then checks a spec's structure, not its pricing)."""
    args = list(args)
    no_weights = "--no-weights" in args
    if no_weights:
        args.remove("--no-weights")
    if "--game" in args:
        game = args[args.index("--game") + 1]
        pub = PUBLISHED.get(game, {})
        wants_portfolio = args[0] in ("check", "calibrate", "threads", "run")
        if wants_portfolio and "--portfolio" not in args and "portfolio" in pub and pub["portfolio"].is_dir():
            args += ["--portfolio", str(pub["portfolio"])]
        wants_weights = ("equivalence" in args or "replay" in args or args[0] in ("weights", "run")
                         or (args[0] == "calibrate" and "--check" in args) or args[:2] == ["experiment", "validate"])
        if wants_weights and not no_weights and "--weights" not in args and "weights" in pub and pub["weights"].is_file():
            args += ["--weights", str(pub["weights"])]
        if args[:2] == ["portfolio", "verify"] and "--portfolio" not in args and "portfolio" in pub:
            args += ["--portfolio", str(pub["portfolio"])]
        # The published levels, where no level is named: a fallback the Java side ranks below a file's own levels.
        wants_levels = args[0] in ("calibrate", "threads") or args[:2] == ["check", "time"]
        levels = published_levels(game) if wants_levels and "--levels" not in args else []
        if levels:
            args += ["--default-levels", ",".join(map(str, levels))]
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
        sys.exit(test(rest))
    elif cmd == "analyze":
        python_analysis([cmd, *rest], log=cmd)
    elif cmd in ("run", "experiment"):
        args = with_published_defaults(with_spec_game([cmd, *rest]))
        log = "run" if cmd == "run" else "experiment-validate"
        if cmd == "run":
            split = rest[rest.index("--split") + 1] if "--split" in rest else None
            log = "run" + (f"-{split}" if split else "")
        java(args, heap="big" if cmd == "run" else None, log=log)
    elif cmd == "bundle":
        bundle(rest)
    elif cmd in ("check", "arms", "portfolio", "calibrate", "threads", "machine", "weights"):
        big = (cmd == "portfolio" and rest[:1] == ["build"]) or cmd == "threads"
        log = cmd + ("-" + rest[0] if rest and not rest[0].startswith("--") else "")
        if cmd == "calibrate" and "--check" in rest:
            log = "calibrate-check"
        java(with_published_defaults([cmd, *rest]), heap="big" if big else None, log=log)
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
    elif cmd == "calibrate-controlled":
        run([sys.executable, ROOT / "tools" / "calibrate_controlled.py", *rest], log="calibrate-controlled")
    elif cmd == "calibrate-time":
        run([sys.executable, ROOT / "tools" / "calibrate_time.py", *rest], log="calibrate-time")
    else:
        sys.exit(f"unknown command '{cmd}'\n{__doc__}")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
        sys.stdout.flush()
    except BrokenPipeError:
        # Output the launcher prints itself, after its reader has gone: Python's own recipe for that.
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        sys.exit(1)
