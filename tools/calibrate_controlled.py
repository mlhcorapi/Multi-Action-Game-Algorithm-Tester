#!/usr/bin/env python3
"""Calibrate this machine's own currency unattended and offline, under conditions this script controls
and records.

    python magat.py calibrate-offline --name laptop-i9-14900hx --preflight    the checklist; measures nothing
    python magat.py calibrate-offline --name laptop-i9-14900hx                the calibration; resumable

The measurement is `magat calibrate`, unchanged: the same cells, gates, bracket and merge. This script
decides how it is run and keeps the evidence (read docs/CALIBRATING_A_MACHINE.md first):

1. Preflight: power, power plan and clock policy, cores, network, other programs, synced folders,
   uptime. A machine on battery, in battery saver or running another JVM is refused.
2. Controls on every measuring JVM: the machine is kept awake; on a hybrid CPU the work is confined
   to the performance cores; the JVM runs at high priority with Windows' power throttling (EcoQoS)
   off. The machine's own settings are read, never changed.
3. Soak: `calibrate --estimate` (it writes nothing) runs back to back until the machine has warmed up
   and two estimates in a row agree within 3%. Then the projected duration.
4. Sessions: the arms in batches of 8. Each batch is one `magat calibrate` session, which writes all
   or nothing and merges into the currency by ratio. A session that fails is retried once, and running
   the same command again measures only what is missing. A session that runs for hours exposes every
   cell to the bracket: one noisy cell, or a machine that slows by 10% late in the session, would cost
   all of it.
5. Checks, which write nothing: this machine against the published currency, and at the end against
   its own new currency (it should still agree with itself hours later).
6. A monitor throughout: power source and battery, the CPU used by anything other than the
   calibration (and which programs used it), the busiest core's clock and the thermal zones.
7. SUMMARY.md and a bundle.

Output lives outside the clone: <data>/calibration/<game>-<name>/ holds the currency, and
offline/<stamp>/ holds one record per invocation.

Exit codes: 0 complete, 1 incomplete (re-run the same command), 2 refused or a usage error, 3 stopped
because the power source changed, 130 interrupted.
"""
import argparse
import csv
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
MAGAT = ROOT / "magat.py"
WINDOWS = os.name == "nt"
LINUX = sys.platform.startswith("linux")

# Every default is a flag.
BATCH = 8
SOAK_MINUTES = 10.0
SOAK_MAX_MINUTES = 45.0
STABLE_WITHIN = 0.03
RETRIES = 1
INTERVAL = 10.0
OTHER_CPUS = 1.0
CHECK_ARMS = ("emcts", "emcts-comma")

# Programs worth closing before a timing measurement, and why.
NOTABLE = {
    "onedrive.exe": "syncs files", "dropbox.exe": "syncs files", "googledrivefs.exe": "syncs files",
    "chrome.exe": "a browser", "msedge.exe": "a browser", "firefox.exe": "a browser", "brave.exe": "a browser",
    "opera.exe": "a browser", "teams.exe": "chat and meetings", "ms-teams.exe": "chat and meetings",
    "slack.exe": "chat", "discord.exe": "chat", "zoom.exe": "meetings", "claude.exe": "Claude desktop",
    "code.exe": "an editor and its indexers", "idea64.exe": "an IDE", "pycharm64.exe": "an IDE", "devenv.exe": "an IDE",
    "steam.exe": "a game launcher", "epicgameslauncher.exe": "a game launcher", "spotify.exe": "media",
    "obs64.exe": "recording", "vmmem": "a virtual machine (WSL or Docker)", "vmmemwsl": "WSL",
    "com.docker.backend.exe": "Docker",
}
JVMS = ("java.exe", "javaw.exe", "java")

BOOST_MODES = {0: "disabled", 1: "enabled", 2: "aggressive", 3: "efficient enabled", 4: "efficient aggressive",
               5: "aggressive at guaranteed", 6: "efficient aggressive at guaranteed"}
OVERLAYS = {"00000000-0000-0000-0000-000000000000": "balanced",
            "961cc777-2547-4f9d-8174-7d86181b8a7a": "best power efficiency",
            "3af9b8d9-7c97-431d-ad78-34a8bfea439f": "better performance",
            "ded574b5-45a0-4f42-8737-46345c09c238": "best performance"}
# The processor settings that set the clock. A currency is measured under one policy: on resume, a
# different one is refused, because a merge cancels a uniform change of speed but not a change in how
# memory-bound and compute-bound work scale against each other.
POLICY = ("PERFBOOSTMODE", "PROCFREQMAX", "PROCFREQMAX1", "PROCTHROTTLEMAX", "PROCTHROTTLEMAX1")
RECORDED = POLICY + ("PERFEPP", "PERFEPP1")

EST_LEVEL = re.compile(r"\[estimate\] (\S+) @ (\d+): ([\d.]+) s per turn over the level's (\d+) positions")
BANNER_ARMS = re.compile(r"^# reference cell (\S+)\s+levels (\S+)\s+arms \[(.*)\]")
BANNER_REPS = re.compile(r"^# reps (\d+)\s+turns/rep (\S+) over levels (\S+)\s+warmup (\d+)")
BRACKET = re.compile(r"the reference cell moved ([+-]?[\d.]+)% / ([+-]?[\d.]+)% / ([+-]?[\d.]+)%")
MEASURED = re.compile(r"measured in ([\d.]+) min")
SPEED = re.compile(r"reference advance is x([\d.]+) the file's")
DRIFT = re.compile(r"largest drift in a turn's price ([+-]?[\d.]+)% \(([^)]*)\)")
MISSING = re.compile(r"^\s+missing: (\S+)")


# ---------------------------------------------------------------------------------------------------
# What the operating system exposes. Every method answers None (or does nothing) where it does not.

class Platform:
    name = sys.platform

    def power(self):
        """{"ac": bool|None, "battery": int|None, "charging": bool|None, "saver": bool|None}"""
        return None

    def cpu_times(self):
        """(busy, total) CPU seconds summed over every logical CPU since boot."""
        return None

    def process_times(self, h):
        return None

    def processes(self):
        """[(pid, ppid, lower-case name)]"""
        return []

    def open(self, pid, write=False):
        return None

    def close(self, h):
        pass

    def pid_of(self, h):
        return h

    def tune(self, h, priority):
        return {}

    def terminate(self, h):
        pass

    def affinity_of(self, h):
        return None

    def topology(self):
        """[(efficiency class, [logical CPUs])] per physical core, or None."""
        return None

    def confine(self, cpus):
        return False

    def confine_process(self, h, cpus):
        return False

    def keep_awake(self, on, display):
        return False

    def uptime_hours(self):
        return None

    def alive(self, pid):
        return True

    def performance_cpus(self):
        """The performance cores' logical CPUs on a hybrid CPU, else None."""
        topo = self.topology()
        if not topo:
            return None
        classes = sorted({c for c, _ in topo})
        if len(classes) < 2:
            return None
        return sorted(cpu for c, cpus in topo if c == classes[-1] for cpu in cpus)

    def top_processes(self, exclude, seconds=2.0, n=5):
        """The programs that used the most CPU over the next `seconds`: [(name, logical CPUs)]."""
        def snap():
            out = {}
            for pid, _, name in self.processes():
                if pid in exclude or pid == 0:
                    continue
                h = self.open(pid)
                if h is None:
                    continue
                t = self.process_times(h)
                self.close(h)
                if t is not None:
                    out[pid] = (name, t)
            return out
        a = snap()
        time.sleep(seconds)
        b = snap()
        used = []
        for pid, (name, t) in b.items():
            if pid in a:
                used.append((name, (t - a[pid][1]) / seconds))
        used.sort(key=lambda x: -x[1])
        return [u for u in used[:n] if u[1] > 0.02]


if WINDOWS:
    import ctypes
    import ctypes.wintypes as wt
    import winreg

    class _FILETIME(ctypes.Structure):
        _fields_ = [("lo", wt.DWORD), ("hi", wt.DWORD)]

    class _POWER(ctypes.Structure):
        _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte), ("BatteryLifePercent", ctypes.c_ubyte),
                    ("SystemStatusFlag", ctypes.c_ubyte), ("BatteryLifeTime", wt.DWORD), ("BatteryFullLifeTime", wt.DWORD)]

    class _ENTRY(ctypes.Structure):
        _fields_ = [("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
                    ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wt.DWORD), ("cntThreads", wt.DWORD),
                    ("th32ParentProcessID", wt.DWORD), ("pcPriClassBase", ctypes.c_long), ("dwFlags", wt.DWORD),
                    ("szExeFile", ctypes.c_wchar * 260)]

    class _THROTTLE(ctypes.Structure):
        _fields_ = [("Version", wt.ULONG), ("ControlMask", wt.ULONG), ("StateMask", wt.ULONG)]

    class _GUID(ctypes.Structure):
        _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD), ("Data4", ctypes.c_ubyte * 8)]

        def __str__(self):
            d = bytes(self.Data4)
            return f"{self.Data1:08x}-{self.Data2:04x}-{self.Data3:04x}-{d[:2].hex()}-{d[2:].hex()}"

    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _PFT = ctypes.POINTER(_FILETIME)
    for _name, _res, _args in [
        ("OpenProcess", wt.HANDLE, [wt.DWORD, wt.BOOL, wt.DWORD]),
        ("CloseHandle", wt.BOOL, [wt.HANDLE]),
        ("GetCurrentProcess", wt.HANDLE, []),
        ("GetProcessId", wt.DWORD, [wt.HANDLE]),
        ("GetProcessTimes", wt.BOOL, [wt.HANDLE, _PFT, _PFT, _PFT, _PFT]),
        ("GetSystemTimes", wt.BOOL, [_PFT, _PFT, _PFT]),
        ("GetExitCodeProcess", wt.BOOL, [wt.HANDLE, ctypes.POINTER(wt.DWORD)]),
        ("SetPriorityClass", wt.BOOL, [wt.HANDLE, wt.DWORD]),
        ("SetProcessInformation", wt.BOOL, [wt.HANDLE, ctypes.c_int, ctypes.c_void_p, wt.DWORD]),
        ("SetProcessAffinityMask", wt.BOOL, [wt.HANDLE, ctypes.c_size_t]),
        ("GetProcessAffinityMask", wt.BOOL, [wt.HANDLE, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]),
        ("TerminateProcess", wt.BOOL, [wt.HANDLE, wt.UINT]),
        ("CreateToolhelp32Snapshot", wt.HANDLE, [wt.DWORD, wt.DWORD]),
        ("Process32FirstW", wt.BOOL, [wt.HANDLE, ctypes.POINTER(_ENTRY)]),
        ("Process32NextW", wt.BOOL, [wt.HANDLE, ctypes.POINTER(_ENTRY)]),
        ("SetThreadExecutionState", wt.DWORD, [wt.DWORD]),
        ("GetTickCount64", ctypes.c_ulonglong, []),
        ("GetSystemPowerStatus", wt.BOOL, [ctypes.POINTER(_POWER)]),
        ("GetLogicalProcessorInformationEx", wt.BOOL, [ctypes.c_int, ctypes.c_void_p, ctypes.POINTER(wt.DWORD)]),
    ]:
        _f = getattr(_k32, _name)
        _f.restype, _f.argtypes = _res, _args

    _INVALID = ctypes.c_void_p(-1).value
    _QUERY, _SET, _TERMINATE = 0x1000, 0x0200, 0x0001
    _PRIORITY = {"normal": 0x20, "above": 0x8000, "high": 0x80}

    def _seconds(ft):
        return ((ft.hi << 32) | ft.lo) / 1e7

    class WindowsPlatform(Platform):
        name = "windows"

        def power(self):
            s = _POWER()
            if not _k32.GetSystemPowerStatus(ctypes.byref(s)):
                return None
            no_battery = s.BatteryFlag == 128 or s.BatteryFlag == 255
            return {"ac": None if s.ACLineStatus == 255 else s.ACLineStatus == 1,
                    "battery": None if no_battery or s.BatteryLifePercent == 255 else int(s.BatteryLifePercent),
                    "charging": None if no_battery else bool(s.BatteryFlag & 8),
                    "saver": bool(s.SystemStatusFlag & 1)}

        def cpu_times(self):
            idle, kernel, user = _FILETIME(), _FILETIME(), _FILETIME()
            if not _k32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
                return None
            i, k, u = _seconds(idle), _seconds(kernel), _seconds(user)
            return k - i + u, k + u          # kernel time includes idle time

        def process_times(self, h):
            a, b, k, u = _FILETIME(), _FILETIME(), _FILETIME(), _FILETIME()
            if not h or not _k32.GetProcessTimes(h, ctypes.byref(a), ctypes.byref(b), ctypes.byref(k), ctypes.byref(u)):
                return None
            return _seconds(k) + _seconds(u)

        def processes(self):
            snap = _k32.CreateToolhelp32Snapshot(0x2, 0)
            if not snap or snap == _INVALID:
                return []
            out = []
            try:
                e = _ENTRY()
                e.dwSize = ctypes.sizeof(_ENTRY)
                ok = _k32.Process32FirstW(snap, ctypes.byref(e))
                while ok:
                    out.append((int(e.th32ProcessID), int(e.th32ParentProcessID), e.szExeFile.lower()))
                    ok = _k32.Process32NextW(snap, ctypes.byref(e))
            finally:
                _k32.CloseHandle(snap)
            return out

        def open(self, pid, write=False):
            access = _QUERY | (_SET | _TERMINATE if write else 0)
            h = _k32.OpenProcess(access, False, int(pid))
            return h or None

        def close(self, h):
            if h:
                _k32.CloseHandle(h)

        def pid_of(self, h):
            return int(_k32.GetProcessId(h)) if h else None

        def tune(self, h, priority):
            done = {}
            if priority in _PRIORITY and priority != "normal":
                done["priority"] = priority if _k32.SetPriorityClass(h, _PRIORITY[priority]) else f"refused ({ctypes.get_last_error()})"
            # ProcessPowerThrottling (4), EXECUTION_SPEED controlled and off: never run this JVM as
            # background work on efficient cores or efficient clocks (EcoQoS).
            st = _THROTTLE(1, 0x1, 0x0)
            ok = _k32.SetProcessInformation(h, 4, ctypes.byref(st), ctypes.sizeof(st))
            done["power throttling"] = "off" if ok else f"not changed ({ctypes.get_last_error()})"
            return done

        def terminate(self, h):
            if h:
                _k32.TerminateProcess(h, 3)

        def affinity_of(self, h):
            proc, system = ctypes.c_size_t(), ctypes.c_size_t()
            if not h or not _k32.GetProcessAffinityMask(h, ctypes.byref(proc), ctypes.byref(system)):
                return None
            return [i for i in range(64) if proc.value >> i & 1]

        def topology(self):
            n = wt.DWORD(0)
            _k32.GetLogicalProcessorInformationEx(0, None, ctypes.byref(n))
            if not n.value:
                return None
            buf = ctypes.create_string_buffer(n.value)
            if not _k32.GetLogicalProcessorInformationEx(0, buf, ctypes.byref(n)):
                return None
            raw, off, cores = buf.raw, 0, []
            while off < n.value:
                size = int.from_bytes(raw[off + 4:off + 8], "little")
                eff = raw[off + 9]
                group = int.from_bytes(raw[off + 40:off + 42], "little")
                mask = int.from_bytes(raw[off + 32:off + 40], "little")
                if group == 0:                # one processor group: up to 64 logical CPUs
                    cores.append((eff, [i for i in range(64) if mask >> i & 1]))
                off += size
            return cores

        def confine(self, cpus):
            mask = sum(1 << c for c in cpus)
            return bool(_k32.SetProcessAffinityMask(_k32.GetCurrentProcess(), mask))

        def confine_process(self, h, cpus):
            return bool(h and cpus and _k32.SetProcessAffinityMask(h, sum(1 << c for c in cpus)))

        def keep_awake(self, on, display):
            flags = 0x80000000 | (0x1 | (0x2 if display else 0) if on else 0)
            return bool(_k32.SetThreadExecutionState(flags))

        def uptime_hours(self):
            return _k32.GetTickCount64() / 3.6e6

        def alive(self, pid):
            h = self.open(pid)
            if not h:
                return False
            code = wt.DWORD()
            ok = _k32.GetExitCodeProcess(h, ctypes.byref(code))
            self.close(h)
            return bool(ok) and code.value == 259          # STILL_ACTIVE

        def overlay(self):
            out = {}
            try:
                pp = ctypes.WinDLL("powrprof")
                for key, fn in (("effective", "PowerGetEffectiveOverlayScheme"), ("chosen", "PowerGetActualOverlayScheme")):
                    g = _GUID()
                    if getattr(pp, fn)(ctypes.byref(g)) == 0:
                        out[key] = OVERLAYS.get(str(g), str(g))
            except (OSError, AttributeError):
                pass
            return out


class LinuxPlatform(Platform):
    name = "linux"
    TICK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100

    def power(self):
        base = pathlib.Path("/sys/class/power_supply")
        if not base.is_dir():
            return None
        ac, battery, charging = None, None, None
        for d in base.iterdir():
            kind = _read(d / "type")
            if kind == "Mains":
                ac = _read(d / "online") == "1" or bool(ac)
            elif kind == "Battery":
                cap = _read(d / "capacity")
                battery = int(cap) if cap and cap.isdigit() else None
                charging = _read(d / "status") == "Charging"
        if ac is None and battery is None:
            ac = True                         # a desktop or a VM: no battery to run out
        return {"ac": ac, "battery": battery, "charging": charging, "saver": None}

    def cpu_times(self):
        first = _read("/proc/stat").splitlines()[0].split()
        v = [int(x) for x in first[1:9]]
        idle = v[3] + v[4]
        return (sum(v) - idle) / self.TICK, sum(v) / self.TICK

    def process_times(self, h):
        s = _read(f"/proc/{h}/stat")
        if not s:
            return None
        rest = s[s.rfind(")") + 2:].split()
        return (int(rest[11]) + int(rest[12])) / self.TICK

    def processes(self):
        out = []
        for d in pathlib.Path("/proc").iterdir():
            if not d.name.isdigit():
                continue
            s = _read(d / "stat")
            if not s:
                continue
            name = s[s.find("(") + 1:s.rfind(")")].lower()
            rest = s[s.rfind(")") + 2:].split()
            out.append((int(d.name), int(rest[1]), name))
        return out

    def open(self, pid, write=False):
        return pid if pathlib.Path(f"/proc/{pid}").is_dir() else None

    def terminate(self, h):
        try:
            os.kill(h, 15)
        except OSError:
            pass

    def affinity_of(self, h):
        try:
            return sorted(os.sched_getaffinity(h))
        except (OSError, AttributeError):
            return None

    def performance_cpus(self):
        text = _read("/sys/devices/cpu_core/cpus")      # Intel hybrid: the performance cores
        if not text:
            return None
        cpus = []
        for part in text.split(","):
            a, _, b = part.partition("-")
            cpus += list(range(int(a), int(b or a) + 1))
        return cpus

    def confine(self, cpus):
        try:
            os.sched_setaffinity(0, cpus)
            return True
        except (OSError, AttributeError):
            return False

    def uptime_hours(self):
        text = _read("/proc/uptime")
        return float(text.split()[0]) / 3600 if text else None

    def alive(self, pid):
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False


def _read(path) -> str:
    try:
        return pathlib.Path(path).read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return ""


def platform_api() -> Platform:
    if WINDOWS:
        return WindowsPlatform()
    if LINUX:
        return LinuxPlatform()
    return Platform()


# ---------------------------------------------------------------------------------------------------
# Recording

def now() -> str:
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class Events:
    """The human-readable timeline: printed, and appended to events.log."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def __call__(self, text):
        line = f"[{now()}] {text}"
        with self.lock:
            print(line, flush=True)
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(line + "\n")


class Monitor(threading.Thread):
    """Samples the machine every few seconds while the calibration runs.

    Windows reports no load average, so the calibrator cannot see other work on the machine. This
    can: it subtracts the CPU used by the calibration's own processes from the CPU the whole machine
    used, and names the programs behind any sustained remainder. It also watches the power source,
    and stops a measuring JVM the moment a machine that started on mains power is on battery: on
    battery this laptop runs at about a third of its speed, so nothing measured after that point is
    comparable with what came before."""

    def __init__(self, plat, record, interval, other_limit, events):
        super().__init__(daemon=True)
        self.plat, self.interval, self.limit, self.events = plat, interval, other_limit, events
        self.record = record
        self.csv_path = record / "monitor.csv"
        with open(self.csv_path, "w", encoding="utf-8", newline="") as fh:
            fh.write("time,phase,ac,battery,charging,machine_cpus,calibration_cpus,other_cpus\n")
        self.phase = "start"
        self.tracked = []                     # {"h", "last", "closing", "java"}
        self.lock = threading.Lock()
        self.halt = threading.Event()
        self.power_lost = threading.Event()
        self.on_ac = None
        self.stats = {}
        self.window = []
        self.last_event = 0.0
        self.counters = None
        self.counters_path = None
        self.own_pids = {os.getpid()}

    # -- the processes that are ours
    def track(self, h, java=False, new=True):
        """Count a process of ours. A new one is counted from its start: the CPU a JVM burns starting up
        (class loading, the compiler threads) is the calibration's, not other work."""
        if h is None:
            return
        with self.lock:
            self.tracked.append({"h": h, "last": 0.0 if new else self.plat.process_times(h), "closing": False, "java": java})
            pid = self.plat.pid_of(h)
            if pid:
                self.own_pids.add(pid)

    def release(self, h):
        with self.lock:
            for t in self.tracked:
                if t["h"] == h:
                    t["closing"] = True       # read once more, then close

    def java_handles(self):
        with self.lock:
            return [t["h"] for t in self.tracked if t["java"] and not t["closing"]]

    # -- counters (Windows): the busiest core's clock and the thermal zones, from typeperf
    def start_counters(self, non_performance):
        if not WINDOWS or not shutil.which("typeperf"):
            return
        fd, name = tempfile.mkstemp(prefix="magat-counters-", suffix=".csv")
        os.close(fd)
        os.unlink(name)
        self.counters_path = pathlib.Path(name)
        cmd = ["typeperf", "-si", str(max(1, int(self.interval))), "-f", "CSV", "-o", name, "-y",
               r"\Processor Information(*)\% Processor Time", r"\Processor Information(*)\% Processor Performance",
               r"\Thermal Zone Information(*)\Temperature", r"\Thermal Zone Information(*)\% Passive Limit"]
        try:
            self.counters = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            self.counters = None
            return
        h = self.plat.open(self.counters.pid, write=True)
        if non_performance:
            self.plat.confine_process(h, non_performance)    # keep the sampler off the measuring cores
        self.track(h)

    def stop_counters(self):
        """Stop typeperf and move its file into the record; the path of the counters, or None."""
        if self.counters is None:
            return None
        self.counters.terminate()
        try:
            self.counters.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        self.counters = None
        target = self.record / "counters.csv"
        if self.counters_path is not None and self.counters_path.is_file():
            shutil.move(str(self.counters_path), str(target))
            self.counters_path = target
            return target
        self.events("[counters] typeperf recorded nothing: no clock or thermal record for this run")
        return None

    def run(self):
        prev_t, prev_cpu = time.time(), self.plat.cpu_times()
        own_self = self.plat.open(os.getpid()) if WINDOWS else os.getpid()
        self.track(own_self, new=False)
        while not self.halt.wait(self.interval):
            t, cpu = time.time(), self.plat.cpu_times()
            dt = max(1e-6, t - prev_t)
            ours = java = 0.0
            with self.lock:
                keep = []
                for tr in self.tracked:
                    cur = self.plat.process_times(tr["h"])
                    if cur is not None and tr["last"] is not None:
                        used = max(0.0, cur - tr["last"])
                        ours += used
                        if tr["java"]:
                            java += used
                    tr["last"] = cur
                    if tr["closing"]:
                        if WINDOWS:
                            self.plat.close(tr["h"])
                    else:
                        keep.append(tr)
                self.tracked = keep
            machine = (cpu[0] - prev_cpu[0]) / dt if cpu and prev_cpu else None
            other = max(0.0, machine - ours / dt) if machine is not None else None
            prev_t, prev_cpu = t, cpu
            p = self.plat.power() or {}
            self.sample(t, p, machine, java / dt, other)

    def sample(self, t, p, machine, java, other):
        phase = self.phase
        with open(self.csv_path, "a", encoding="utf-8", newline="") as fh:
            fh.write(",".join(_fmt(x) for x in (datetime.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S"), phase,
                                                 p.get("ac"), p.get("battery"), p.get("charging"), machine, java, other)) + "\n")
        s = self.stats.setdefault(phase, {"n": 0, "other": 0.0, "worst": 0.0, "java": 0.0, "events": 0, "power": False})
        if other is not None:
            s["n"] += 1
            s["other"] += other
            s["java"] += java
            self.window = (self.window + [other])[-3:]
            sustained = min(self.window) if len(self.window) == 3 else 0.0
            s["worst"] = max(s["worst"], sustained)
            if sustained > self.limit and time.time() - self.last_event > 300:
                self.last_event = time.time()
                s["events"] += 1
                top = self.plat.top_processes(self.own_pids)
                names = ", ".join(f"{n} {c:.1f}" for n, c in top) or "not identifiable"
                self.events(f"[load] at least {sustained:.1f} logical CPUs of other work for {3 * self.interval:.0f} s during "
                            f"{phase}; using CPU now: {names}")
        ac = p.get("ac")
        if self.on_ac and ac is False and not self.power_lost.is_set():
            self.power_lost.set()
            s["power"] = True
            self.events(f"[power] the machine is on battery ({p.get('battery')}%) during {phase}: stopping the measurement")
            for h in self.java_handles():
                self.plat.terminate(h)
        elif self.power_lost.is_set() and ac:
            self.events("[power] mains power is back")


def _fmt(x):
    if x is None:
        return ""
    if isinstance(x, float):
        return f"{x:.3f}"
    return str(x)


# ---------------------------------------------------------------------------------------------------
# The machine's settings, read for the record and the preflight

def run_text(cmd, timeout=30) -> str:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace")
        return (p.stdout or "") + (p.stderr or "")
    except (OSError, subprocess.SubprocessError):
        return ""


def power_settings() -> dict:
    """The active power plan and the AC values of the processor settings that decide the clock."""
    out = {}
    if not WINDOWS:
        return out
    m = re.search(r"GUID:\s*(\S+)\s*\((.*)\)", run_text(["powercfg", "/getactivescheme"]))
    if m:
        out["plan"] = f"{m.group(2)} ({m.group(1)})"
    for alias in RECORDED:
        text = run_text(["powercfg", "/qh", "SCHEME_CURRENT", "SUB_PROCESSOR", alias])
        m = re.search(r"Current AC Power Setting Index:\s*0x([0-9a-fA-F]+)", text)
        if m:
            out[alias] = int(m.group(1), 16)
    return out


def describe_clock(ps: dict) -> str:
    if not ps:
        return "not readable on this OS"
    boost = BOOST_MODES.get(ps.get("PERFBOOSTMODE"), ps.get("PERFBOOSTMODE"))
    cap_p, cap = ps.get("PROCFREQMAX1"), ps.get("PROCFREQMAX")
    parts = [f"boost {boost}"]
    parts.append(f"performance cores capped at {cap_p} MHz" if cap_p else "no frequency cap on the performance cores")
    if cap:
        parts.append(f"efficient cores capped at {cap} MHz")
    for alias, label in (("PROCTHROTTLEMAX1", "performance cores' maximum state"), ("PROCTHROTTLEMAX", "maximum processor state")):
        if ps.get(alias, 100) != 100:
            parts.append(f"{label} {ps[alias]}%")
    return ", ".join(parts)


def nominal_mhz():
    if not WINDOWS:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
            return int(winreg.QueryValueEx(key, "~MHz")[0])
    except OSError:
        return None


def cpu_name() -> str:
    if WINDOWS:
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                return winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
        except OSError:
            pass
    for line in _read("/proc/cpuinfo").splitlines():
        if line.startswith("model name"):
            return line.split(":", 1)[1].strip()
    return os.environ.get("PROCESSOR_IDENTIFIER", "unknown")


def machine_model() -> str:
    if WINDOWS:
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\BIOS") as key:
                return " ".join(str(winreg.QueryValueEx(key, v)[0]).strip() for v in ("SystemManufacturer", "SystemProductName"))
        except OSError:
            pass
    return (_read("/sys/class/dmi/id/sys_vendor") + " " + _read("/sys/class/dmi/id/product_name")).strip() or "unknown"


def network_up() -> list:
    """The physical network adapters that are connected (None: not readable)."""
    if WINDOWS:
        try:
            p = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                                "Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | ForEach-Object { $_.Name }"],
                               capture_output=True, text=True, timeout=60, errors="replace")
        except (OSError, subprocess.SubprocessError):
            return None
        if p.returncode != 0:
            return None
        return [line.strip() for line in p.stdout.splitlines() if line.strip()]
    if LINUX:
        up = []
        for d in pathlib.Path("/sys/class/net").glob("*"):
            if d.name != "lo" and (d / "device").exists() and _read(d / "operstate") == "up":
                up.append(d.name)
        return up
    return None


def update_pause():
    if not WINDOWS:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\WindowsUpdate\UX\Settings") as key:
            return winreg.QueryValueEx(key, "PauseUpdatesExpiryTime")[0]
    except OSError:
        return None


def maintenance_time():
    if not WINDOWS:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Schedule\Maintenance") as key:
            return str(winreg.QueryValueEx(key, "Activation Boundary")[0])
    except OSError:
        return None


def synced_roots() -> list:
    roots = []
    for var in ("OneDrive", "OneDriveConsumer", "OneDriveCommercial"):
        v = os.environ.get(var)
        if v:
            roots.append(pathlib.Path(v).resolve())
    return sorted(set(roots))


def under(path, roots) -> bool:
    p = pathlib.Path(path).resolve()
    return any(p == r or r in p.parents for r in roots)


def online_only(dirs) -> list:
    """Files a sync client keeps in the cloud only: unreadable once the network is off."""
    if not WINDOWS:
        return []
    recall = 0x400000 | 0x40000 | 0x1000    # recall on data access, recall on open, offline
    out = []
    for d in dirs:
        if not d.is_dir():
            continue
        for p in d.rglob("*"):
            try:
                if p.is_file() and os.stat(p, follow_symlinks=False).st_file_attributes & recall:
                    out.append(p)
            except OSError:
                pass
    return out


def git_state() -> str:
    rev = run_text(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
    dirty = run_text(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"]).strip()
    return (rev or "unknown") + ("-dirty" if dirty else "")


# ---------------------------------------------------------------------------------------------------

def preflight(a, plat, out, data, portfolio) -> tuple:
    """(facts, [(level, text)]) with level ok | info | warn | refuse. Reads; changes nothing."""
    facts, items = {"time": now(), "argv": sys.argv[1:], "platform": plat.name, "cpu": cpu_name(),
                    "machine": machine_model(), "python": sys.version.split()[0], "commit": git_state()}, []

    def item(level, text):
        items.append((level, text))

    enforce = not a.toy
    p = plat.power()
    facts["power"] = p
    if p is None:
        item("info", "power source: not readable on this OS")
    else:
        if p["ac"] is False:
            item("refuse" if enforce and not a.allow_battery else "warn",
                 f"on battery ({p['battery']}%): plug in. On battery this laptop measured at about a third of its speed, "
                 "and a battery can run out mid-session")
        else:
            item("ok", "on mains power" + (f", battery {p['battery']}%" if p.get("battery") is not None else ""))
        if p.get("saver"):
            item("refuse" if enforce else "warn", "battery saver is on: it lowers the clock; turn it off")
        if p.get("charging"):
            item("warn", "the battery is charging: charging adds heat and can share the adapter's power with the CPU; "
                         "start when it is full (or at its charge limit)")

    ps = power_settings()
    facts["power_settings"] = ps
    if WINDOWS:
        ov = plat.overlay()
        facts["power_mode"] = ov
        item("info", f"power plan: {ps.get('plan', 'unknown')}; power mode {ov.get('chosen', '?')} (in effect: {ov.get('effective', '?')})")
        if ov.get("effective") == "best power efficiency":
            item("warn", "the power mode in effect is 'best power efficiency': it lowers the clock. Settings > System > "
                         "Power & battery > Power mode, plugged in: Best performance")
        item("info", "clock policy: " + describe_clock(ps))

    pcores = plat.performance_cpus()
    topo = plat.topology()
    facts["performance_cpus"] = pcores
    facts["logical_cpus"] = os.cpu_count()
    if pcores and not a.no_affinity:
        item("ok", f"hybrid CPU: measuring JVMs will run on the {len(pcores)} logical CPUs of the performance cores "
                   f"({_ranges(pcores)}), never on an efficient core")
    elif pcores:
        item("warn", "hybrid CPU and --no-affinity: Windows may move the measured thread to an efficient core (about half the speed)")
    else:
        item("info", f"{os.cpu_count()} logical CPUs" + (", one kind of core" if topo else ""))

    up = network_up()
    facts["network_up"] = up
    if up is None:
        item("info", "network: not readable on this OS")
    elif up:
        item("warn" if not a.allow_online else "info",
             f"online ({', '.join(up)}): once the preflight is clean, turn on airplane mode (or unplug the cable)")
    else:
        item("ok", "offline: no physical network adapter is connected")

    procs = plat.processes()
    names = sorted({n for _, _, n in procs})
    facts["processes"] = len(procs)
    jvms = [n for n in names if n in JVMS]
    if jvms:
        item("refuse" if enforce and not a.allow_other_java else "warn",
             "another JVM is running (" + ", ".join(jvms) + "): close it; two JVMs cannot share this measurement")
    noisy = [f"{n} ({NOTABLE[n]})" for n in names if n in NOTABLE]
    if noisy:
        item("warn", "running and worth closing: " + "; ".join(noisy))
    else:
        item("ok", "none of the usual background programs is running")

    roots = synced_roots()
    if roots and under(out, roots):
        item("warn", f"the output {out} is inside a synced folder: move MAGAT_DATA outside it (or pass --out)")
    needed = [portfolio, ROOT / "games" / a.game / "engine", ROOT / "core" / "lib"]
    cloud = online_only(needed)
    facts["online_only_files"] = len(cloud)
    if cloud:
        item("refuse" if enforce else "warn",
             f"{len(cloud)} file(s) the calibration reads are online-only in OneDrive (e.g. {cloud[0]}): while still online, "
             "right-click the repository folder > Always keep on this device")
    elif roots and under(ROOT, roots):
        item("ok", "the repository is in OneDrive, but every file the calibration reads is on this device")

    hours = plat.uptime_hours()
    facts["uptime_hours"] = hours
    if hours is not None:
        item("warn" if hours > 24 else "ok", f"up for {hours:.1f} h" + (": restart first, then wait about 15 minutes "
                                                                         "for start-up work to finish" if hours > 24 else ""))
    pause = update_pause()
    if WINDOWS:
        facts["updates_paused_until"] = pause
        expired = True
        if pause:
            try:
                expired = datetime.datetime.fromisoformat(pause.replace("Z", "+00:00")) < datetime.datetime.now(datetime.timezone.utc)
            except ValueError:
                pass
        item("warn" if expired else "ok", "Windows Update " + (f"paused until {pause}" if not expired else
                                                               "is not paused: pause it for a week so nothing installs mid-run"))
        mt = maintenance_time()
        facts["maintenance"] = mt
        item("info", "automatic maintenance runs " + (f"daily at {mt[11:16]}" if mt and len(mt) >= 16 else
                                                      "at Windows' default time (02:00)") + " when the machine looks idle; "
             "keep the run away from it or move it (Control Panel > Security and Maintenance > Maintenance)")

    free = shutil.disk_usage(data).free / 2**30
    item("warn" if free < 2 else "ok", f"{free:.0f} GB free under the data root")
    java = run_text(["java", "-version"]).strip().splitlines()
    facts["java"] = java[:3]
    item("ok" if java else "refuse", "java: " + (java[0] if java else "not found on PATH"))
    facts["portfolio"] = str(portfolio)
    ident = _read(portfolio / "identity.txt").split()
    facts["portfolio_identity"] = ident[0] if ident else None
    if not ident:
        item("refuse", f"no portfolio at {portfolio}")
    if facts["commit"].endswith("-dirty"):
        item("warn", "the checkout has uncommitted changes: the currency records the commit as -dirty")
    return facts, items


def _ranges(cpus) -> str:
    out, start, prev = [], None, None
    for c in cpus:
        if start is None:
            start = prev = c
        elif c == prev + 1:
            prev = c
        else:
            out.append(f"{start}-{prev}" if prev > start else str(start))
            start = prev = c
    if start is not None:
        out.append(f"{start}-{prev}" if prev > start else str(start))
    return ",".join(out)


# ---------------------------------------------------------------------------------------------------
# Running the launcher

class Runner:
    def __init__(self, a, plat, monitor, events, record):
        self.a, self.plat, self.monitor, self.events, self.record = a, plat, monitor, events, record
        self.spans = []                       # (phase, start, end) for the counters

    def launch(self, args, phase, log_name, measure):
        """One launcher command: output streamed and kept, the measuring JVM found and tuned."""
        if measure and self.monitor.power_lost.is_set():
            raise PowerLost()
        self.monitor.phase = phase
        log = self.record / f"{log_name}.log"
        start = time.time()
        p = subprocess.Popen([PY, str(MAGAT), *map(str, args)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace", bufsize=1, cwd=str(ROOT),
                             env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        launcher = self.plat.open(p.pid)
        self.monitor.track(launcher)
        tuned = {}
        finder = None
        if measure:
            finder = threading.Thread(target=self._find_jvm, args=(p, tuned), daemon=True)
            finder.start()
        lines = []
        with open(log, "a", encoding="utf-8") as fh:
            fh.write(f"\n>>>>> {now()} $ magat {' '.join(map(str, args))}\n")
            for line in p.stdout:
                print(line, end="", flush=True)
                fh.write(f"{datetime.datetime.now():%H:%M:%S} {line}")
                lines.append(line.rstrip("\n"))
            rc = p.wait()
            fh.write(f"===== exit {rc} after {time.time() - start:.0f} s\n")
        if finder:
            finder.join(timeout=5)
        self.monitor.release(launcher)
        for h in tuned.get("handles", []):
            self.monitor.release(h)
        self.spans.append((phase, start, time.time()))
        if measure and tuned.get("applied"):
            self.events(f"[controls] {phase}: JVM pid {tuned['pid']}, CPUs {_ranges(tuned.get('cpus') or []) or '?'}, "
                        + ", ".join(f"{k} {v}" for k, v in tuned["applied"].items()))
        self.monitor.phase = "between"
        return rc, lines

    def _find_jvm(self, p, tuned):
        """Find the JVM among the launcher's descendants (PATH's java may be a shim that starts the real one)."""
        seen = set()
        found_at = None
        while p.poll() is None and (found_at is None or time.time() - found_at < 5):
            procs = self.plat.processes()
            family = {p.pid}
            grew = True
            while grew:
                grew = False
                for pid, ppid, name in procs:
                    if ppid in family and pid not in family:
                        family.add(pid)
                        grew = True
            for pid, ppid, name in procs:
                if pid not in family or pid == p.pid or pid in seen:
                    continue
                seen.add(pid)
                jvm = name in JVMS
                h = self.plat.open(pid, write=jvm) if WINDOWS else self.plat.open(pid)
                if h is None:
                    continue
                tuned.setdefault("handles", []).append(h)
                self.monitor.track(h, java=jvm)
                if jvm:
                    tuned["pid"] = pid
                    tuned["applied"] = self.plat.tune(h, self.a.priority)
                    tuned["cpus"] = self.plat.affinity_of(h)
                    found_at = found_at or time.time()
            time.sleep(0.25)


def parse_estimate(lines) -> dict:
    est = {"levels": {}, "positions": {}}
    for line in lines:
        m = EST_LEVEL.search(line)
        if m:
            est["levels"][int(m.group(2))] = float(m.group(3))
            est["positions"][int(m.group(2))] = int(m.group(4))
        m = BANNER_ARMS.match(line)
        if m:
            est["reference"] = m.group(1)
            est["levels_spec"] = m.group(2)
            est["arms"] = [x.strip() for x in m.group(3).split(",") if x.strip()]
        m = BANNER_REPS.match(line)
        if m:
            est["reps"], est["turns"], est["warmup"] = int(m.group(1)), m.group(2), int(m.group(4))
        if line.startswith("ESTIMATE: "):
            est["hours"] = float(line.split()[1])
    return est


def project(est, arms_in_session) -> float:
    """Seconds one session would take at the estimate's pace (the calibrator's own --estimate arithmetic)."""
    ref_budget = int(est["reference"].split("@")[1])
    total = 0.0
    for level, per_turn in est["levels"].items():
        n = est["positions"][level]
        cells = 1 + arms_in_session
        turns = est["warmup"] + cells * (1 + est["reps"] * n)
        if level == ref_budget and cells > 1:
            turns += est["warmup"] + 1 + est["reps"] * n          # the bracket
        total += turns * per_turn
    return total


def counter_stats(path, spans, nominal, pcores, confined=False) -> dict:
    """Per phase, from typeperf: the busiest logical CPU, its clock, and the thermal zones.

    When the measuring JVM is confined to the performance cores, the busiest of those is where the
    measured thread ran; otherwise the busiest of all, and how often that was an efficient core. A
    sample counts only when that CPU was busy for most of it (the measured thread, not a gap between
    JVMs)."""
    if path is None or not path.is_file():
        return {}
    try:
        with open(path, encoding="utf-8", errors="replace", newline="") as fh:
            rows = list(csv.reader(fh))
    except OSError:
        return {}
    if len(rows) < 2:
        return {}
    cols = []
    for i, h in enumerate(rows[0][1:], 1):
        body = h.lstrip("\\")
        body = body[body.find("\\") + 1:]
        obj, inst = body[:body.find("(")], body[body.find("(") + 1:body.rfind(")\\")]
        counter = body[body.rfind(")\\") + 2:]
        cols.append((i, obj, inst, counter))
    windows = [(ph, datetime.datetime.fromtimestamp(s), datetime.datetime.fromtimestamp(e)) for ph, s, e in spans]
    stats = {}
    for row in rows[1:]:
        t = None
        for f in ("%m/%d/%Y %H:%M:%S.%f", "%d/%m/%Y %H:%M:%S.%f"):
            try:
                t = datetime.datetime.strptime(row[0], f)
                break
            except (ValueError, IndexError):
                pass
        if t is None:
            continue
        phase = next((ph for ph, s, e in windows if s <= t <= e), None)
        if phase is None:
            continue
        busy, perf, temps, passive = {}, {}, [], []
        for i, obj, inst, counter in cols:
            try:
                v = float(row[i])
            except (ValueError, IndexError):
                continue
            if obj == "Processor Information" and "," in inst and "_Total" not in inst:
                cpu = int(inst.split(",")[1])
                if counter == "% Processor Time":
                    busy[cpu] = v
                elif counter == "% Processor Performance":
                    perf[cpu] = v
            elif obj == "Thermal Zone Information":
                if counter == "Temperature":
                    temps.append(v - 273.15)
                elif counter == "% Passive Limit":
                    passive.append(v)
        candidates = {c: v for c, v in busy.items() if not confined or c in pcores}
        if not candidates:
            continue
        cpu = max(candidates, key=candidates.get)
        if candidates[cpu] < 50:
            continue
        s = stats.setdefault(phase, {"n": 0, "on_p": 0, "perf": [], "temp": [], "passive": []})
        s["n"] += 1
        s["on_p"] += 1 if (not pcores or cpu in pcores) else 0
        if cpu in perf:
            s["perf"].append(perf[cpu])
        s["temp"] += temps
        s["passive"] += passive
    out = {}
    for phase, s in stats.items():
        perf = sorted(s["perf"])
        d = {"samples": s["n"], "busiest_on_performance_core": s["on_p"] / s["n"]}
        if perf:
            d["perf_pct"] = (perf[0], perf[len(perf) // 2], perf[-1])
            if nominal:
                d["mhz"] = tuple(round(x * nominal / 100) for x in d["perf_pct"])
        if s["temp"]:
            d["temp_max_c"] = max(s["temp"])
        if s["passive"]:
            d["passive_min_pct"] = min(s["passive"])
        out[phase] = d
    return out


def describe_counters(d) -> str:
    if not d:
        return "-"
    parts = []
    if "mhz" in d:
        parts.append(f"{d['mhz'][0]}-{d['mhz'][2]} MHz (median {d['mhz'][1]})")
    elif "perf_pct" in d:
        parts.append(f"{d['perf_pct'][0]:.0f}-{d['perf_pct'][2]:.0f}% of nominal")
    if d.get("busiest_on_performance_core", 1) < 1:
        parts.append(f"busiest CPU on an efficient core in {100 * (1 - d['busiest_on_performance_core']):.0f}% of samples")
    if "temp_max_c" in d:
        parts.append(f"thermal zones up to {d['temp_max_c']:.0f} C")
    if d.get("passive_min_pct", 100) < 100:
        parts.append(f"passive cooling limit down to {d['passive_min_pct']:.0f}%")
    return ", ".join(parts) or "-"


# ---------------------------------------------------------------------------------------------------

def main(argv) -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass
    ap = argparse.ArgumentParser(prog="magat calibrate-offline", description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", required=True, help="the currency's name, e.g. laptop-i9-14900hx")
    ap.add_argument("--game", default="tribes")
    ap.add_argument("--out", help="the calibration directory (default <data>/calibration/<game>-<name>)")
    ap.add_argument("--portfolio", help="default: the game's published portfolio")
    ap.add_argument("--arms", help="arms or @sets to price (default: every priced arm the game registers)")
    ap.add_argument("--batch", type=int, default=BATCH, help="arms per session (0: one session for everything)")
    ap.add_argument("--format", type=int, default=2, choices=(1, 2))
    ap.add_argument("--levels"), ap.add_argument("--turns"), ap.add_argument("--reps"), ap.add_argument("--warmup")
    ap.add_argument("--soak-minutes", type=float, default=SOAK_MINUTES)
    ap.add_argument("--soak-max-minutes", type=float, default=SOAK_MAX_MINUTES)
    ap.add_argument("--stable-within", type=float, default=STABLE_WITHIN)
    ap.add_argument("--retries", type=int, default=RETRIES)
    ap.add_argument("--check-arms", default=",".join(CHECK_ARMS))
    ap.add_argument("--no-checks", action="store_true", help="skip the two drift checks")
    ap.add_argument("--redo-checks", action="store_true", help="run the drift checks again even if recorded")
    ap.add_argument("--priority", default="high", choices=("normal", "above", "high"))
    ap.add_argument("--no-affinity", action="store_true", help="let the OS place the JVM on any core")
    ap.add_argument("--let-display-sleep", action="store_true", help="keep the system awake but let the display turn off")
    ap.add_argument("--interval", type=float, default=INTERVAL)
    ap.add_argument("--other-cpus", type=float, default=OTHER_CPUS, help="other work, in logical CPUs, worth an event")
    ap.add_argument("--preflight", action="store_true", help="the checklist only; measure nothing")
    ap.add_argument("--allow-battery", action="store_true")
    ap.add_argument("--allow-online", action="store_true")
    ap.add_argument("--allow-other-java", action="store_true")
    ap.add_argument("--allow-policy-change", action="store_true", help="resume under a different clock policy")
    ap.add_argument("--toy", action="store_true", help="toy sizes for the shakedown: gates reported, not enforced")
    a = ap.parse_args(argv)

    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", a.name):
        print("--name: lower-case letters, digits, '.', '_' and '-'", file=sys.stderr)
        return 2
    plat = platform_api()
    data = pathlib.Path(os.environ.get("MAGAT_DATA") or (pathlib.Path.home() / "magat_runs")).resolve()
    if data == ROOT or ROOT in data.parents:
        print(f"MAGAT_DATA={data} is inside the repository; run output must live outside the clone", file=sys.stderr)
        return 2
    out = pathlib.Path(a.out).resolve() if a.out else data / "calibration" / f"{a.game}-{a.name}"
    portfolio = pathlib.Path(a.portfolio).resolve() if a.portfolio else ROOT / "games" / a.game / "portfolio" / "v1"
    weights = out / "compute_weights.properties"
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    offline = out / "offline"
    record = offline / stamp
    record.mkdir(parents=True, exist_ok=True)
    events = Events(record / "events.log")
    state_path = offline / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.is_file() else {}

    def save_state():
        tmp = state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, indent=2, default=str), encoding="utf-8")
        tmp.replace(state_path)

    print("############################################################")
    print(f"# MAGAT offline calibration   {a.game}-{a.name}" + ("   (TOY)" if a.toy else ""))
    print(f"# currency  {out}")
    print(f"# record    {record}")
    print("############################################################")

    # ---- preflight
    facts, items = preflight(a, plat, out, data, portfolio)
    previous = state.get("policy") if weights.is_file() else None
    policy = {k: facts.get("power_settings", {}).get(k) for k in POLICY}
    if previous and previous != policy:
        changed = ", ".join(f"{k} {previous.get(k)} -> {policy.get(k)}" for k in POLICY if previous.get(k) != policy.get(k))
        items.append(("refuse" if not (a.allow_policy_change or a.toy) else "warn",
                      f"the clock policy differs from this currency's first session ({changed}). A currency measured under "
                      "two clock policies is two currencies: restore the policy, or calibrate under another --name"))
    (record / "preflight.json").write_text(json.dumps({"facts": facts, "items": items}, indent=2, default=str), encoding="utf-8")
    print("\n=== PREFLIGHT ===")
    for level, text in items:
        print(f"  [{level:6}] {text}")
    refusals = [t for lvl, t in items if lvl == "refuse"]
    if refusals or a.preflight:
        verdict = "NOT READY (" + str(len(refusals)) + " refusal(s))" if refusals else "READY" + (
            "" if not [1 for lvl, _ in items if lvl == "warn"] else " (read the warnings)")
        print(f"\nPREFLIGHT: {verdict}")
        if not refusals:
            print("Next: fix what you can of the warnings, go offline, and run the same command without --preflight.")
        return 2 if refusals else 0

    # ---- one invocation at a time
    lock = offline / ".lock"
    try:
        fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        try:
            other = int(lock.read_text().strip() or 0)
        except (OSError, ValueError):
            other = 0
        if other and plat.alive(other):
            print(f"another calibrate-offline (pid {other}) is running on {out}", file=sys.stderr)
            return 2
        lock.unlink()
        fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    os.write(fd, str(os.getpid()).encode())
    os.close(fd)

    # ---- controls
    pcores = facts.get("performance_cpus")
    confined = False
    if pcores and not a.no_affinity:
        confined = plat.confine(pcores)
    others = [c for c in range(os.cpu_count() or 1) if pcores and c not in pcores]
    awake = plat.keep_awake(True, display=not a.let_display_sleep)
    monitor = Monitor(plat, record, a.interval, a.other_cpus, events)
    p0 = plat.power() or {}
    monitor.on_ac = p0.get("ac") is not False
    monitor.start_counters(others)
    monitor.start()
    runner = Runner(a, plat, monitor, events, record)
    events(f"[start] {a.game}-{a.name}: kept awake {'yes' if awake else 'no (not supported here)'}"
           + (f", confined to {_ranges(pcores)}" if confined else "") + f", priority {a.priority}")
    state.setdefault("first", stamp)
    state.setdefault("sessions", [])
    state.setdefault("checks", {})
    invocation = {"stamp": stamp, "facts": {k: facts.get(k) for k in ("power", "power_settings", "power_mode", "uptime_hours",
                                                                      "network_up", "commit", "java")}}
    state.setdefault("invocations", []).append(invocation)
    save_state()

    passthrough = []
    for flag in ("levels", "turns", "reps", "warmup"):
        if getattr(a, flag):
            passthrough += [f"--{flag}", getattr(a, flag)]
    if a.portfolio:
        passthrough += ["--portfolio", portfolio]
    toy = ["--toy"] if a.toy else []
    t_start = time.time()
    code, why = 0, None
    soak_rows, session_rows, check_rows = [], [], []
    est = None
    try:
        # ---- build first, so no measurement waits on the compiler
        rc, _ = runner.launch(["version"], "build", "build", measure=False)
        if rc != 0:
            raise Stop(2, f"the build failed; read {record / 'build.log'}")

        # ---- soak and estimate
        print("\n=== SOAK: estimates back to back until the machine holds its speed ===")
        arms_flag = ["--arms", a.arms] if a.arms else []
        t0 = time.time()
        while True:
            k = len(soak_rows) + 1
            rc, lines = runner.launch(["calibrate", "--estimate", "--game", a.game, "--format", a.format, *arms_flag,
                                       *passthrough, *toy], f"soak {k}", "soak", measure=True)
            if monitor.power_lost.is_set():
                raise PowerLost()
            e = parse_estimate(lines)
            if rc != 0 or not e.get("levels") or "arms" not in e:
                raise Stop(1, f"estimate {k} failed (exit {rc}); read {record / 'soak.log'}")
            change = None
            if soak_rows:
                prev = soak_rows[-1]["levels"]
                change = max(abs(e["levels"][lv] / prev[lv] - 1) for lv in e["levels"] if lv in prev)
            soak_rows.append({"levels": e["levels"], "change": change, "hours": e.get("hours"), "minutes": (time.time() - t0) / 60})
            est = e
            events(f"[soak] estimate {k}: " + ", ".join(f"{lv}: {v:.2f} s/turn" for lv, v in sorted(e["levels"].items()))
                   + (f"; change {100 * change:.1f}%" if change is not None else ""))
            elapsed = (time.time() - t0) / 60
            steady = change is not None and change <= a.stable_within
            if a.toy:
                break
            if steady and elapsed >= a.soak_minutes:
                events(f"[soak] steady after {elapsed:.0f} min: two estimates in a row within {100 * a.stable_within:.0f}%")
                break
            if elapsed >= a.soak_max_minutes:
                raise Stop(1, f"the machine did not hold its speed: not steady after {elapsed:.0f} min of estimates (last "
                              f"change {100 * (change or 0):.1f}%), so a session would compare cells measured at different "
                              "speeds; see docs/CALIBRATING_A_MACHINE.md, 'Clock policy'")
        soak_counters = counter_stats(monitor.counters_path or record / "counters.csv", runner.spans, nominal_mhz(),
                                      set(pcores or []), confined)
        last = soak_counters.get(f"soak {len(soak_rows)}")
        if last:
            events("[soak] busiest core during the last estimate: " + describe_counters(last))

        # ---- what is missing
        arms, reference = est["arms"], est["reference"]
        ref_arm, levels = reference.split("@")[0], est["levels_spec"]

        def coverage():
            if not weights.is_file():
                return set(arms) - set(arms), arms
            rc, lines = runner.launch(["weights", "--weights", weights, "--game", a.game, "--arms", ",".join(arms),
                                       "--levels", levels], "coverage", "coverage", measure=False)
            missing = [m.group(1) for line in lines for m in [MISSING.match(line)] if m]
            gone = {x.split("@")[0] for x in missing}
            return {x for x in arms if x not in gone}, missing

        covered, missing = coverage()
        todo = [x for x in arms if x not in covered and x != ref_arm]
        size = a.batch if a.batch > 0 else max(1, len(todo))
        batches = [todo[i:i + size] for i in range(0, len(todo), size)]
        if not weights.is_file() and not batches:
            batches = [[]]                    # the reference arm alone still makes a currency
        checks = [] if a.no_checks else (["self"] if a.toy else ["published", "self"])
        check_arms = [x for x in a.check_arms.split(",") if x in arms] or [ref_arm]
        pending_checks = [c for c in checks if a.redo_checks or c not in state["checks"]]
        hours = sum(project(est, len(b)) for b in batches) / 3600
        check_hours = len(pending_checks) * project(est, len([x for x in check_arms if x != ref_arm])) / 3600
        finish = datetime.datetime.now() + datetime.timedelta(hours=hours + check_hours)
        print(f"\n=== PLAN: {len(arms)} arm(s), {len(covered)} already priced; {len(batches)} session(s) of up to {size} arm(s), "
              f"then {len(pending_checks)} check(s) ===")
        for i, b in enumerate(batches, 1):
            print(f"  session {i}: {', '.join(b) if b else ref_arm + ' (the reference arm)'}   ~{project(est, len(b)) / 60:.0f} min")
        events(f"[plan] about {hours:.1f} h of sessions and {check_hours:.1f} h of checks at the soak's pace; "
               f"expected to finish around {finish:%H:%M} (up to 25% later if the arms run slower than the reference)")
        if WINDOWS and not a.toy:
            mt = facts.get("maintenance")
            hh, mm = (int(mt[11:13]), int(mt[14:16])) if mt and len(mt) >= 16 and mt[11:13].isdigit() else (2, 0)
            start = datetime.datetime.now()
            due = start.replace(hour=hh, minute=mm, second=0, microsecond=0)
            if due < start:
                due += datetime.timedelta(days=1)
            if due <= finish + datetime.timedelta(hours=0.25 * (hours + check_hours)):
                events(f"[plan] WARNING: the run will still be going at {hh:02d}:{mm:02d}, when Windows' automatic maintenance "
                       "starts on an idle machine (scans, updates). Move the maintenance time or start earlier; "
                       "docs/CALIBRATING_A_MACHINE.md, 'Before the run', step 7")
        if batches and not a.toy:
            events("[plan] leave the machine alone from here: no keyboard, mouse or lid until it finishes")

        # ---- sessions
        failed = []
        for attempt in range(1 + max(0, a.retries)):
            queue = batches if attempt == 0 else failed
            failed = []
            for i, b in enumerate(queue, 1):
                label = f"session {batches.index(b) + 1}/{len(batches)}" + (f" (attempt {attempt + 1})" if attempt else "")
                if b and weights.is_file():
                    have, _ = coverage()
                    if all(x in have for x in b):
                        continue
                print(f"\n=== {label.upper()}: {', '.join(b) if b else ref_arm} ===")
                events(f"[session] {label} starts: {len(b) or 1} arm(s)")
                before = time.time()
                rc, lines = runner.launch(["calibrate", "--game", a.game, "--out", out, "--arms", ",".join(b) or ref_arm,
                                           "--format", a.format, *passthrough, *toy], label, "sessions", measure=True)
                keep = record / f"session-{batches.index(b) + 1}-attempt-{attempt + 1}"
                keep.mkdir(exist_ok=True)
                for f in ("compute_profiles.csv", "compute_operations.csv"):
                    if (out / f).is_file() and (out / f).stat().st_mtime >= before:
                        shutil.copy2(out / f, keep / f)
                text = "\n".join(lines)
                passed = rc == 0 and "CALIBRATION: PASS" in text
                row = {"session": batches.index(b) + 1, "attempt": attempt + 1, "arms": b or [ref_arm],
                       "verdict": "PASS" if passed else ("STOPPED (power)" if monitor.power_lost.is_set() else "FAIL"),
                       "minutes": round((time.time() - before) / 60, 1), "label": label}
                m = BRACKET.search(text)
                if m:
                    row["bracket"] = [float(x) for x in m.groups()]
                m = SPEED.search(text)
                if m:
                    row["speed_vs_file"] = float(m.group(1))
                row["failures"] = [line.strip()[7:] for line in lines if line.strip().startswith("[FAIL]")]
                s = monitor.stats.get(label, {})
                if s.get("n"):
                    row["other_cpus_mean"] = round(s["other"] / s["n"], 2)
                    row["other_cpus_worst"] = round(s["worst"], 2)
                    row["load_events"] = s["events"]
                session_rows.append(row)
                state["sessions"].append({**row, "invocation": stamp})
                if passed:
                    state.setdefault("policy", policy)
                save_state()
                events(f"[session] {label}: {row['verdict']} in {row['minutes']} min"
                       + (f"; bracket {row['bracket'][0]:+.1f}/{row['bracket'][1]:+.1f}/{row['bracket'][2]:+.1f}%" if "bracket" in row else "")
                       + (f"; " + "; ".join(row["failures"]) if row["failures"] else ""))
                if monitor.power_lost.is_set():
                    raise PowerLost()
                if not passed:
                    failed.append(b)
            if not failed:
                break
        covered, missing = coverage()

        # ---- checks (write nothing)
        if weights.is_file() and not failed:
            for which in pending_checks:
                label = f"check {which}"
                print(f"\n=== CHECK AGAINST {'THE PUBLISHED CURRENCY' if which == 'published' else 'THE NEW CURRENCY'} "
                      f"(writes nothing) ===")
                args = ["calibrate", "--check", "--game", a.game, "--arms", ",".join(check_arms)]
                for flag in ("levels", "reps", "warmup"):
                    if getattr(a, flag):
                        args += [f"--{flag}", getattr(a, flag)]
                if a.portfolio:
                    args += ["--portfolio", portfolio]
                if which == "self":
                    args += ["--weights", weights, *toy]
                before = time.time()
                rc, lines = runner.launch(args, label, "checks", measure=True)
                if monitor.power_lost.is_set():
                    raise PowerLost()
                text = "\n".join(lines)
                verdict = "PASS" if "CHECK: PASS" in text else "FAIL" if "CHECK: FAIL" in text else f"ERROR (exit {rc})"
                row = {"check": which, "verdict": verdict, "minutes": round((time.time() - before) / 60, 1), "label": label}
                m = DRIFT.search(text)
                if m:
                    row["drift"] = f"{m.group(1)}% ({m.group(2)})"
                m = BRACKET.search(text)
                if m:
                    row["bracket"] = [float(x) for x in m.groups()]
                check_rows.append(row)
                if verdict in ("PASS", "FAIL"):
                    state["checks"][which] = {**row, "invocation": stamp}
                    save_state()
                events(f"[check] {which}: {verdict}" + (f", largest turn-price drift {row['drift']}" if "drift" in row else ""))

        complete = weights.is_file() and not missing
        code = 0 if complete and not failed else 1
    except PowerLost:
        code = 3
    except Stop as e:
        events(f"[stop] {e.why}")
        code, why = e.code, e.why
    except KeyboardInterrupt:
        events("[stop] interrupted; nothing a session had not finished was written")
        code = 130
    finally:
        monitor.halt.set()
        monitor.join(timeout=2 * a.interval + 5)
        counters_file = monitor.stop_counters()
        plat.keep_awake(False, display=False)

    # ---- summary and bundle
    counters = counter_stats(counters_file, runner.spans, nominal_mhz(), set(pcores or []), confined) if counters_file else {}
    summary = write_summary(a, out, record, facts, soak_rows, session_rows, check_rows, counters, monitor.stats, weights,
                            time.time() - t_start, code, confined, pcores, awake)
    print("\n" + summary)
    _unlock(lock)
    if weights.is_file():
        subprocess.run([PY, str(MAGAT), "bundle", "--name", f"calibration-{a.game}-{a.name}", "--paths", str(out)],
                       cwd=str(ROOT), check=False)
    verdicts = {0: "COMPLETE", 1: f"INCOMPLETE - {why}" if why else "INCOMPLETE - re-run the same command to measure what is missing",
                3: "STOPPED - the machine went on battery; plug in and re-run the same command",
                130: "INTERRUPTED - re-run the same command to continue"}
    print(f"OFFLINE CALIBRATION: {verdicts.get(code, code)}")
    return code


class PowerLost(Exception):
    pass


class Stop(Exception):
    def __init__(self, code, why):
        super().__init__(why)
        self.code, self.why = code, why


def _unlock(lock):
    try:
        lock.unlink()
    except OSError:
        pass


def write_summary(a, out, record, facts, soak, sessions, checks, counters, mstats, weights, seconds, code,
                  confined, pcores, awake) -> str:
    ps = facts.get("power_settings", {})
    p = facts.get("power") or {}
    L = [f"# Offline calibration: {a.game}-{a.name}" + (" (toy)" if a.toy else ""), "",
         f"- Record: `{record}`",
         f"- Currency: `{weights}`" + (" (written)" if weights.is_file() else " (not written yet)"),
         f"- Ran {seconds / 3600:.2f} h, finished {now()}; exit {code}",
         f"- Commit {facts.get('commit')}; {facts.get('cpu')}; {facts.get('machine')}", "",
         "## Conditions", "",
         "| | |", "|---|---|",
         f"| Power | {'mains' if p.get('ac') is not False else 'battery'}"
         + (f", battery {p.get('battery')}%" if p.get("battery") is not None else "")
         + (", charging" if p.get("charging") else "") + " |",
         f"| Power plan | {ps.get('plan', 'not readable')}; power mode {facts.get('power_mode', {}).get('effective', '-')} |",
         f"| Clock policy | {describe_clock(ps)} |",
         f"| Measuring JVMs | " + (f"confined to the performance cores' logical CPUs {_ranges(pcores)}" if confined
                                  else "placed by the OS") + f"; priority {a.priority}; power throttling off where supported |",
         f"| Kept awake | {'yes' if awake else 'no'} |",
         f"| Network | {'offline' if facts.get('network_up') == [] else 'online: ' + ', '.join(facts['network_up']) if facts.get('network_up') else 'not readable'} |",
         f"| Uptime at start | {facts.get('uptime_hours') or 0:.1f} h |", ""]
    if soak:
        L += ["## Soak", "", "| Estimate | " + " | ".join(f"{lv} s/turn" for lv in sorted(soak[0]["levels"])) + " | Change | Busiest core |",
              "|---|" + "---|" * (len(soak[0]["levels"]) + 2)]
        for i, r in enumerate(soak, 1):
            L.append(f"| {i} | " + " | ".join(f"{r['levels'][lv]:.2f}" for lv in sorted(r["levels"])) + " | "
                     + (f"{100 * r['change']:.1f}%" if r["change"] is not None else "-") + " | "
                     + describe_counters(counters.get(f"soak {i}")) + " |")
        L.append("")
    if sessions:
        L += ["## Sessions", "", "| Session | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs file | Other work, logical CPUs: mean / worst sustained | Busiest core |",
              "|---|---|---|---|---|---|---|---|"]
        for r in sessions:
            L.append(f"| {r['label']} | {', '.join(r['arms'])} | {r['verdict']} | {r['minutes']} | "
                     + ("/".join(f"{x:+.1f}%" for x in r["bracket"]) if "bracket" in r else "-") + " | "
                     + (f"x{r['speed_vs_file']:.4f}" if "speed_vs_file" in r else "first") + " | "
                     + (f"{r['other_cpus_mean']:.1f} / {r['other_cpus_worst']:.1f}" if "other_cpus_mean" in r else "-") + " | "
                     + describe_counters(counters.get(r["label"])) + " |")
        for r in sessions:
            for f in r.get("failures", []):
                L.append(f"- {r['label']}: {f}")
        L.append("")
    if checks:
        L += ["## Checks (write nothing)", "", "| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |", "|---|---|---|---|---|"]
        for r in checks:
            L.append(f"| {r['check']} | {r['verdict']} | {r.get('drift', '-')} | "
                     + ("/".join(f"{x:+.1f}%" for x in r["bracket"]) if "bracket" in r else "-") + " | "
                     + describe_counters(counters.get(r["label"])) + " |")
        L.append("")
    events = sum(s.get("events", 0) for s in mstats.values())
    L += ["## Other work on the machine", "",
          f"{events} period(s) of sustained other work above {a.other_cpus} logical CPU(s); each is in `events.log` with the "
          "programs that were using the CPU. `monitor.csv` has every sample.", ""]
    disturbed = [r for r in sessions if r.get("load_events")]
    if disturbed:
        arms = sorted({x for r in disturbed for x in r["arms"]})
        L += ["Sessions measured while something else was working passed their own gates, so their cells were not visibly "
              "affected. To measure them again anyway, one session per batch:", "",
              f"    python magat.py calibrate --game {a.game} --out \"{out}\" --arms {','.join(arms)} --replace {','.join(arms)}", ""]
    text = "\n".join(L)
    (record / "SUMMARY.md").write_text(text + "\n", encoding="utf-8")
    shutil.copy2(record / "SUMMARY.md", out / "offline" / "SUMMARY.md")
    return text


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
