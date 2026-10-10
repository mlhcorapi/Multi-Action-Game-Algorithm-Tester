#!/usr/bin/env python3
"""Calibrate this machine's own currency in sections, under conditions this script controls where it can
and records where it cannot, with a reliability verdict for every section.

    python magat.py calibrate-controlled --name laptop-i9-14900hx --preflight   the checklist; measures nothing
    python magat.py calibrate-controlled --name laptop-i9-14900hx --plan        the sections; measures nothing
    python magat.py calibrate-controlled --name laptop-i9-14900hx               every section, then the checks
    python magat.py calibrate-controlled --name r7615-v2 --section 2            one section (one notebook cell)
    python magat.py calibrate-controlled --name r7615-v2 --section 2 --redo     section 2 measured again
    python magat.py calibrate-controlled --name r7615-v2 --section checks       the two drift checks

The measurement is `magat calibrate`, unchanged: the same cells, gates, bracket and merge. This script
decides how it is run and keeps the evidence. docs/CALIBRATING_A_MACHINE.md is the guide.

1. Preflight. It reads the machine and changes nothing. A laptop: power, battery saver, the power plan
   and clock policy, network, other programs, synced folders, uptime. A server or container: the CPUs
   it may use, its CPU and memory limits, the L3 domains, other work on the machine. It refuses a
   machine on battery or in battery saver, a second JVM, and files it cannot read.
2. Controls on the measuring JVMs only. On a hybrid CPU they run on the performance cores. On a
   server with several L3 domains they run on the quietest one, so the measured thread never migrates
   across caches. On Windows they also run at high priority with EcoQoS off, and the machine is kept
   awake.
3. Sections. The arms, in a fixed order, in sections of 8. Each section is one `magat calibrate`
   session, which writes all or nothing and merges into the currency by ratio. A session that runs for
   hours stakes every cell on its bracket. Sections are planned once per currency and keep their
   numbers, so a notebook can give each one its own cell and re-run any of them.
4. Before each section, a stability check (`calibrate --estimate`, which writes nothing) until two
   estimates in a row agree within 3%, and optionally a wait for the machine to be quiet.
5. After each section, a verdict:
   - PASS: the calibrator's gates passed, and nothing was seen disturbing it.
   - SUSPECT: the gates passed, but something was seen: sustained other work, CPU throttling of the
     container, or a session that ran at a different speed from the first.
   - FAIL: the gates failed, and nothing was written.
   A failed section is retried once; a suspect one is measured again with --redo, when you choose.
6. Checks, which write nothing: this machine against the published currency, and against its own new
   currency at the end.
7. A monitor throughout: power source, other work on the machine (and which programs), throttling, and
   the busiest measuring core's clock and temperature.

Output lives outside the clone: <data>/calibration/<game>-<name>/ holds the currency, and record/ holds
state.json, a cumulative SUMMARY.md, and one folder per invocation.

Exit codes:
- 0: complete, or the section passed.
- 1: a section failed, or the run is incomplete; run the same command again.
- 2: refused, or a usage error.
- 3: stopped because the machine left mains power.
- 4: the machine stayed busy, so nothing was started.
- 5: a section is suspect, or the self-check failed.
- 130: interrupted.
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
SPEED_TOLERANCE = 0.05
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
# The processor settings that set the clock. A currency is measured under one policy: once a session has
# written it, a different one is refused, because a merge cancels a uniform change of speed but not a
# change in how memory-bound and compute-bound work scale against each other.
POLICY = ("PERFBOOSTMODE", "PROCFREQMAX", "PROCFREQMAX1", "PROCTHROTTLEMAX", "PROCTHROTTLEMAX1", "governor")
RECORDED = ("PERFBOOSTMODE", "PROCFREQMAX", "PROCFREQMAX1", "PROCTHROTTLEMAX", "PROCTHROTTLEMAX1", "PERFEPP", "PERFEPP1")

EST_LEVEL = re.compile(r"\[estimate\] (\S+) @ (\d+): ([\d.]+) s per turn over the level's (\d+) positions")
BANNER_ARMS = re.compile(r"^# reference cell (\S+)\s+levels (\S+)\s+arms \[(.*)\]")
BANNER_REPS = re.compile(r"^# reps (\d+)\s+turns/rep (\S+) over levels (\S+)\s+warmup (\d+)")
BRACKET = re.compile(r"the reference cell moved ([+-]?[\d.]+)% / ([+-]?[\d.]+)% / ([+-]?[\d.]+)%")
SPEED = re.compile(r"reference advance is x([\d.]+) the file's")
DRIFT = re.compile(r"largest drift in a turn's price ([+-]?[\d.]+)% \(([^)]*)\)")
MISSING = re.compile(r"^\s+missing: (\S+)")
ARM_LINE = re.compile(r"^  ([a-z0-9][a-z0-9._-]*)\s")


# ---------------------------------------------------------------------------------------------------
# What the operating system exposes. Every method answers None (or does nothing) where it does not.

class Platform:
    name = sys.platform

    def power(self):
        """{"ac": bool|None, "battery": int|None, "charging": bool|None, "saver": bool|None}"""
        return None

    def cpu_times(self):
        """(busy, total) CPU seconds summed over every logical CPU the OS reports, since boot."""
        return None

    def per_cpu(self):
        """{cpu: (busy, total)} seconds since boot, per logical CPU; None where not exposed."""
        return None

    def cpu_mhz(self, cpu):
        return None

    def temperature(self):
        return None

    def cgroup(self):
        """The container's limits and counters: {"version", "cpus", "memory_gb", "usage_s", "throttled_s", "nr_throttled"}."""
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

    def allowed_cpus(self):
        return list(range(os.cpu_count() or 1))

    def topology(self):
        """[(efficiency class, [logical CPUs])] per physical core, or None."""
        return None

    def l3_domains(self):
        """[[logical CPUs sharing one L3 cache]], or None."""
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
        used = [(name, (t - a[pid][1]) / seconds) for pid, (name, t) in b.items() if pid in a]
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
            no_battery = s.BatteryFlag in (128, 255)
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
            return _k32.OpenProcess(access, False, int(pid)) or None

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
            return bool(_k32.SetProcessAffinityMask(_k32.GetCurrentProcess(), sum(1 << c for c in cpus)))

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
    CG = pathlib.Path("/sys/fs/cgroup")

    def power(self):
        base = pathlib.Path("/sys/class/power_supply")
        ac, battery, charging = None, None, None
        if base.is_dir():
            for d in base.iterdir():
                kind = _read(d / "type")
                if kind == "Mains":
                    ac = _read(d / "online") == "1" or bool(ac)
                elif kind == "Battery":
                    cap = _read(d / "capacity")
                    battery = int(cap) if cap.isdigit() else None
                    charging = _read(d / "status") == "Charging"
        if ac is None and battery is None:
            ac = True                         # a server, a desktop or a VM: no battery to run out
        return {"ac": ac, "battery": battery, "charging": charging, "saver": None}

    def cpu_times(self):
        first = _read("/proc/stat").splitlines()[0].split()
        v = [int(x) for x in first[1:9]]
        idle = v[3] + v[4]
        return (sum(v) - idle) / self.TICK, sum(v) / self.TICK

    def per_cpu(self):
        out = {}
        for line in _read("/proc/stat").splitlines()[1:]:
            if not line.startswith("cpu"):
                break
            f = line.split()
            v = [int(x) for x in f[1:9]]
            idle = v[3] + v[4]
            out[int(f[0][3:])] = ((sum(v) - idle) / self.TICK, sum(v) / self.TICK)
        return out

    def cpu_mhz(self, cpu):
        khz = _read(f"/sys/devices/system/cpu/cpu{cpu}/cpufreq/scaling_cur_freq")
        if khz.isdigit():
            return int(khz) / 1000
        n = -1
        for line in _read("/proc/cpuinfo").splitlines():
            if line.startswith("processor"):
                n = int(line.split(":")[1])
            elif line.startswith("cpu MHz") and n == cpu:
                return float(line.split(":")[1])
        return None

    def temperature(self):
        best = None
        for d in pathlib.Path("/sys/class/hwmon").glob("hwmon*"):
            if _read(d / "name") in ("k10temp", "coretemp", "zenpower"):
                for f in d.glob("temp*_input"):
                    v = _read(f)
                    if v.lstrip("-").isdigit():
                        best = max(best or -1e9, int(v) / 1000)
        return best

    def cgroup(self):
        out = {}
        if (self.CG / "cgroup.controllers").is_file():                 # version 2
            out["version"] = 2
            quota = _read(self.CG / "cpu.max").split()
            if len(quota) == 2 and quota[0] != "max":
                out["cpus"] = int(quota[0]) / int(quota[1])
            mem = _read(self.CG / "memory.max")
            if mem.isdigit():
                out["memory_gb"] = int(mem) / 2**30
            stat = dict(line.split() for line in _read(self.CG / "cpu.stat").splitlines() if len(line.split()) == 2)
            if "usage_usec" in stat:
                out["usage_s"] = int(stat["usage_usec"]) / 1e6
            if "throttled_usec" in stat:
                out["throttled_s"] = int(stat["throttled_usec"]) / 1e6
                out["nr_throttled"] = int(stat.get("nr_throttled", 0))
            return out
        for cpu_dir in (self.CG / "cpu,cpuacct", self.CG / "cpu"):     # version 1
            if cpu_dir.is_dir():
                out["version"] = 1
                q, p = _read(cpu_dir / "cpu.cfs_quota_us"), _read(cpu_dir / "cpu.cfs_period_us")
                if q.lstrip("-").isdigit() and int(q) > 0 and p.isdigit():
                    out["cpus"] = int(q) / int(p)
                stat = dict(line.split() for line in _read(cpu_dir / "cpu.stat").splitlines() if len(line.split()) == 2)
                if "throttled_time" in stat:
                    out["throttled_s"] = int(stat["throttled_time"]) / 1e9
                    out["nr_throttled"] = int(stat.get("nr_throttled", 0))
                usage = _read(self.CG / "cpuacct" / "cpuacct.usage") or _read(cpu_dir / "cpuacct.usage")
                if usage.isdigit():
                    out["usage_s"] = int(usage) / 1e9
                mem = _read(self.CG / "memory" / "memory.limit_in_bytes")
                if mem.isdigit() and int(mem) < 2**60:
                    out["memory_gb"] = int(mem) / 2**30
                break
        return out or None

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

    def allowed_cpus(self):
        try:
            return sorted(os.sched_getaffinity(0))
        except (OSError, AttributeError):
            return super().allowed_cpus()

    def performance_cpus(self):
        return _cpu_list(_read("/sys/devices/cpu_core/cpus")) or None      # Intel hybrid: the performance cores

    def l3_domains(self):
        seen = []
        for d in sorted(pathlib.Path("/sys/devices/system/cpu").glob("cpu[0-9]*"), key=lambda p: int(p.name[3:])):
            for idx in d.glob("cache/index*"):
                if _read(idx / "level") == "3":
                    cpus = _cpu_list(_read(idx / "shared_cpu_list"))
                    if cpus and cpus not in seen:
                        seen.append(cpus)
        return seen or None

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


def _cpu_list(text):
    cpus = []
    for part in (text or "").split(","):
        part = part.strip()
        if not part:
            continue
        a, _, b = part.partition("-")
        if not a.isdigit() or (b and not b.isdigit()):
            return []
        cpus += list(range(int(a), int(b or a) + 1))
    return cpus


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


def sample_other_work(plat, seconds=5.0, ours=()):
    """Logical CPUs of work on the machine over the next few seconds, less our own processes', and the
    per-CPU busy fractions (None where not exposed)."""
    a, pa = plat.cpu_times(), plat.per_cpu()
    oa = {h: plat.process_times(h) for h in ours}
    t0 = time.time()
    time.sleep(seconds)
    dt = time.time() - t0
    b, pb = plat.cpu_times(), plat.per_cpu()
    if a is None or b is None:
        return None, None
    used = sum(max(0.0, (plat.process_times(h) or 0) - (oa[h] or 0)) for h in ours)
    other = max(0.0, (b[0] - a[0] - used) / dt)
    busy = None
    if pa and pb:
        busy = {c: (pb[c][0] - pa[c][0]) / max(1e-9, pb[c][1] - pa[c][1]) for c in pb if c in pa}
    return other, busy


class Monitor(threading.Thread):
    """Samples the machine every few seconds while the calibration runs.

    Windows reports no load average, and inside a container the load average describes the host, so
    the calibrator cannot see other work on the machine. This can: it subtracts the CPU its own
    processes used from the CPU the machine used, and names the programs behind any sustained
    remainder. Inside a container whose /proc is the host's, that remainder is other users' work.
    It reads the container's throttling counters, the busiest measuring CPU's clock and the
    temperature. And it stops a measuring JVM the moment a machine that started on mains power is on
    battery: on battery the development laptop runs at about a third of its speed, so nothing measured
    after that point is comparable with what came before."""

    def __init__(self, plat, record, interval, other_limit, events, cpus):
        super().__init__(daemon=True)
        self.plat, self.interval, self.limit, self.events = plat, interval, other_limit, events
        self.record = record
        self.cpus = set(cpus or [])
        self.csv_path = record / "monitor.csv"
        with open(self.csv_path, "w", encoding="utf-8", newline="") as fh:
            fh.write("time,phase,ac,battery,charging,machine_cpus,calibration_cpus,other_cpus,busiest_cpu,busy_pct,mhz,"
                     "temp_c,throttled_s\n")
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
    def start_counters(self, elsewhere):
        if not WINDOWS or not shutil.which("typeperf"):
            return
        fd, name = tempfile.mkstemp(prefix="magat-counters-", suffix=".csv")
        os.close(fd)
        os.unlink(name)
        self.counters_path = pathlib.Path(name)       # typeperf cannot write past MAX_PATH; moved later
        cmd = ["typeperf", "-si", str(max(1, int(self.interval))), "-f", "CSV", "-o", name, "-y",
               r"\Processor Information(*)\% Processor Time", r"\Processor Information(*)\% Processor Performance",
               r"\Thermal Zone Information(*)\Temperature", r"\Thermal Zone Information(*)\% Passive Limit"]
        try:
            self.counters = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            self.counters = None
            return
        h = self.plat.open(self.counters.pid, write=True)
        if elsewhere:
            self.plat.confine_process(h, elsewhere)    # keep the sampler off the measuring cores
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
        prev_t, prev_cpu, prev_per = time.time(), self.plat.cpu_times(), self.plat.per_cpu()
        prev_cg = self.plat.cgroup() or {}
        own_self = self.plat.open(os.getpid()) if WINDOWS else os.getpid()
        self.track(own_self, new=False)
        while not self.halt.wait(self.interval):
            t, cpu, per = time.time(), self.plat.cpu_times(), self.plat.per_cpu()
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
            busiest = pct = mhz = temp = None
            if per and prev_per:
                cands = [c for c in per if c in prev_per and (not self.cpus or c in self.cpus)]
                if cands:
                    frac = {c: (per[c][0] - prev_per[c][0]) / max(1e-9, per[c][1] - prev_per[c][1]) for c in cands}
                    busiest = max(frac, key=frac.get)
                    pct = 100 * frac[busiest]
                    if pct >= 50:             # the measured thread, not a gap between JVMs
                        mhz = self.plat.cpu_mhz(busiest)
                        temp = self.plat.temperature()
            cg = self.plat.cgroup() or {}
            throttled = None
            if "throttled_s" in cg and "throttled_s" in prev_cg:
                throttled = max(0.0, cg["throttled_s"] - prev_cg["throttled_s"])
            prev_t, prev_cpu, prev_per, prev_cg = t, cpu, per, cg
            p = self.plat.power() or {}
            self.sample(t, p, machine, java / dt, other, busiest, pct, mhz, temp, throttled)

    def sample(self, t, p, machine, java, other, busiest, pct, mhz, temp, throttled):
        phase = self.phase
        with open(self.csv_path, "a", encoding="utf-8", newline="") as fh:
            fh.write(",".join(_fmt(x) for x in (datetime.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S"), phase,
                                                 p.get("ac"), p.get("battery"), p.get("charging"), machine, java, other,
                                                 busiest, pct, mhz, temp, throttled)) + "\n")
        s = self.stats.setdefault(phase, {"n": 0, "other": 0.0, "worst": 0.0, "java": 0.0, "events": 0, "power": False,
                                          "mhz": [], "temp": [], "throttled": 0.0})
        if mhz:
            s["mhz"].append(mhz)
        if temp is not None:
            s["temp"].append(temp)
        if throttled:
            s["throttled"] += throttled
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
                names = ", ".join(f"{n} {c:.1f}" for n, c in top) or "not visible from here (another container or user)"
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

    def clock_stats(self):
        """Per phase, from this monitor's own samples (Linux): the busiest measuring CPU's clock, the temperature, throttling."""
        out = {}
        for phase, s in self.stats.items():
            d = {}
            mhz = sorted(s["mhz"])
            if mhz:
                d["mhz"] = (round(mhz[0]), round(mhz[len(mhz) // 2]), round(mhz[-1]))
            if s["temp"]:
                d["temp_max_c"] = max(s["temp"])
            if s["throttled"]:
                d["throttled_s"] = s["throttled"]
            if d:
                out[phase] = d
        return out


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
    """Windows: the active power plan and the AC values of the processor settings that set the clock.
    Linux: the frequency governor."""
    out = {}
    if LINUX:
        govs = sorted({_read(p) for p in pathlib.Path("/sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_governor")} - {""})
        if govs:
            out["governor"] = ",".join(govs)
        return out
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
        return "not readable here"
    if "governor" in ps and "PERFBOOSTMODE" not in ps:
        return f"frequency governor {ps['governor']}"
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
    return (_read("/sys/class/dmi/id/sys_vendor") + " " + _read("/sys/class/dmi/id/product_name")).strip() or "not readable"


def network_up():
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
        return [d.name for d in pathlib.Path("/sys/class/net").glob("*")
                if d.name != "lo" and (d / "device").exists() and _read(d / "operstate") == "up"]
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


def jupyter_kernels(plat) -> int:
    """Other Jupyter kernels in this container (each one a notebook that may start work), not counting ours."""
    if not LINUX:
        return 0
    ancestors, procs = set(), {pid: ppid for pid, ppid, _ in plat.processes()}
    pid = os.getpid()
    while pid in procs and pid not in ancestors:
        ancestors.add(pid)
        pid = procs[pid]
    n = 0
    for p in procs:
        if p not in ancestors and "ipykernel" in _read(f"/proc/{p}/cmdline").replace("\0", " "):
            n += 1
    return n


def git_state() -> str:
    rev = run_text(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
    dirty = run_text(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"]).strip()
    return (rev or "unknown") + ("-dirty" if dirty else "")


def _ranges(cpus) -> str:
    out, start, prev = [], None, None
    for c in sorted(cpus or []):
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


def choose_cpus(a, plat):
    """Where the measuring JVMs run, and why: (cpus or None, reason)."""
    if a.no_affinity:
        return None, "anywhere (--no-affinity)"
    allowed = set(plat.allowed_cpus())
    if a.cpus:
        cpus = [c for c in _cpu_list(a.cpus) if c in allowed]
        return (cpus, "the CPUs given with --cpus") if cpus else (None, "--cpus names no CPU this process may use")
    pcores = plat.performance_cpus()
    if pcores:
        cpus = [c for c in pcores if c in allowed]
        if cpus:
            return cpus, f"the performance cores of a hybrid CPU ({len(cpus)} logical CPUs)"
    domains = [d for d in (plat.l3_domains() or []) if set(d) <= allowed]
    if len(domains) > 1:
        _, busy = sample_other_work(plat, 3.0)
        if busy:
            load = {i: sum(busy.get(c, 0.0) for c in d) for i, d in enumerate(domains)}
            i = min(load, key=load.get)
            return domains[i], (f"the quietest of {len(domains)} L3 domains ({len(domains[i])} logical CPUs, "
                                f"{load[i]:.1f} CPUs busy when chosen)")
        return domains[0], f"one of {len(domains)} L3 domains"
    return None, "anywhere (one L3 domain, nothing to choose)"


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
    portable = bool(p and p.get("battery") is not None)
    if p is None:
        item("info", "power source: not readable here")
    elif portable:
        if p["ac"] is False:
            item("refuse" if enforce and not a.allow_battery else "warn",
                 f"on battery ({p['battery']}%): plug in. On battery the development laptop measured at about a third of "
                 "its speed, and a battery can run out mid-section")
        else:
            item("ok", f"on mains power, battery {p['battery']}%")
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

    allowed = plat.allowed_cpus()
    facts["allowed_cpus"] = _ranges(allowed)
    facts["logical_cpus"] = os.cpu_count()
    cpus, why = choose_cpus(a, plat)
    facts["measuring_cpus"], facts["measuring_cpus_why"] = _ranges(cpus) if cpus else None, why
    item("ok" if cpus or "nothing to choose" in why else "warn",
         f"measuring JVMs will run on {('CPUs ' + _ranges(cpus) + ': ') if cpus else ''}{why}")
    if plat.performance_cpus() and a.no_affinity:
        item("warn", "hybrid CPU and --no-affinity: the measured thread may land on an efficient core (about half the speed)")

    cg = plat.cgroup()
    facts["cgroup"] = cg
    if cg:
        item("info", f"container (cgroup v{cg.get('version')}): CPUs {facts['allowed_cpus']} of {os.cpu_count()}, "
                     + (f"CPU limit {cg['cpus']:.1f}" if cg.get("cpus") else "no CPU limit")
                     + (f", memory limit {cg['memory_gb']:.0f} GB" if cg.get("memory_gb") else ", no memory limit"))
        if cg.get("cpus") and cg["cpus"] < 2:
            item("warn", f"the container may use {cg['cpus']:.1f} CPU: the JVM's compiler and collector threads will be "
                         "throttled while the measured thread runs. Ask for a server with at least 2 CPUs")
        if cg.get("memory_gb") and cg["memory_gb"] < 6:
            item("refuse" if enforce else "warn", f"memory limit {cg['memory_gb']:.1f} GB: the measuring JVM uses up to 4 GB "
                                                  "of heap and about 1 GB more. Ask for a server with at least 6 GB")
    if LINUX:
        nodes = len(list(pathlib.Path("/sys/devices/system/node").glob("node[0-9]*")))
        facts["numa_nodes"] = nodes
        domains = plat.l3_domains()
        item("info", f"{nodes or 1} NUMA node(s); " + (f"{len(domains)} L3 domain(s) of {len(domains[0])} logical CPUs"
                                                         if domains else "L3 domains not readable"))
        mem = {line.split(":")[0]: line.split(":")[1] for line in _read("/proc/meminfo").splitlines() if ":" in line}
        avail = mem.get("MemAvailable", "").split()
        if avail and avail[0].isdigit():
            gb = int(avail[0]) / 2**20
            item("warn" if gb < 6 else "ok", f"{gb:.0f} GB of memory available")
        k = jupyter_kernels(plat)
        if k:
            item("warn", f"{k} other Jupyter kernel(s) are running in this server: make sure none of them runs anything "
                         "while a section measures (shut down the notebooks you are not using)")

    before = cg.get("usage_s") if cg else None
    other, busy = sample_other_work(plat, 5.0)
    after = (plat.cgroup() or {}).get("usage_s")
    facts["other_cpus_now"] = other
    if other is not None:
        where = ""
        if before is not None and after is not None:
            mine = (after - before) / 5.0
            facts["container_cpus_now"] = mine
            where = (f", of which this container {mine:.1f}: the rest is other users' work, which this machine's /proc shows"
                     if other > mine + 0.3 else
                     f", all of it this container's: either the machine is quiet or /proc shows only this container, in which "
                     "case other users' work is invisible from here")
        item("warn" if other > a.other_cpus else "ok",
             f"work on the machine now: {other:.1f} logical CPUs{where}; a section is suspect above {a.other_cpus:g} of other work")

    up = network_up()
    facts["network_up"] = up
    if up is None:
        item("info", "network: not readable here")
    elif up and portable:
        item("warn" if not a.allow_online else "info",
             f"online ({', '.join(up)}): once the preflight is clean, turn on airplane mode (or unplug the cable)")
    elif portable:
        item("ok", "offline: no physical network adapter is connected")

    procs = plat.processes()
    names = sorted({n for _, _, n in procs})
    jvms = [n for n in names if n in JVMS]
    if jvms:
        item("refuse" if enforce and not a.allow_other_java else "warn",
             "another JVM is running (" + ", ".join(jvms) + "): stop it; two JVMs cannot share this measurement")
    noisy = [f"{n} ({NOTABLE[n]})" for n in names if n in NOTABLE]
    if noisy:
        item("warn", "running and worth closing: " + "; ".join(noisy))
    elif WINDOWS:
        item("ok", "none of the usual background programs is running")

    roots = synced_roots()
    if roots and under(out, roots):
        item("warn", f"the output {out} is inside a synced folder: move MAGAT_DATA outside it (or pass --out)")
    cloud = online_only([portfolio, ROOT / "games" / a.game / "engine", ROOT / "core" / "lib"])
    facts["online_only_files"] = len(cloud)
    if cloud:
        item("refuse" if enforce else "warn",
             f"{len(cloud)} file(s) the calibration reads are online-only in OneDrive (e.g. {cloud[0]}): while still online, "
             "right-click the repository folder > Always keep on this device")
    elif roots and under(ROOT, roots):
        item("ok", "the repository is in OneDrive, but every file the calibration reads is on this device")

    hours = plat.uptime_hours()
    facts["uptime_hours"] = hours
    if hours is not None and portable:
        item("warn" if hours > 24 else "ok", f"up for {hours:.1f} h" + (": restart first, then wait about 15 minutes "
                                                                         "for start-up work to finish" if hours > 24 else ""))
    elif hours is not None:
        item("info", f"the machine has been up for {hours / 24:.1f} days")
    if WINDOWS:
        pause = update_pause()
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


# ---------------------------------------------------------------------------------------------------
# Running the launcher

class Runner:
    def __init__(self, a, plat, monitor, events, record):
        self.a, self.plat, self.monitor, self.events, self.record = a, plat, monitor, events, record
        self.spans = []                       # (phase, start, end) for the counters

    def launch(self, args, phase, log_name, measure, echo=True):
        """One launcher command: output streamed and kept, the measuring JVM found and tuned."""
        if measure and self.monitor is not None and self.monitor.power_lost.is_set():
            raise PowerLost()
        if self.monitor is not None:
            self.monitor.phase = phase
        log = self.record / f"{log_name}.log"
        start = time.time()
        p = subprocess.Popen([PY, str(MAGAT), *map(str, args)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace", bufsize=1, cwd=str(ROOT),
                             env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        launcher = self.plat.open(p.pid) if self.monitor is not None else None
        tuned = {}
        finder = None
        if self.monitor is not None:
            self.monitor.track(launcher)
            if measure:
                finder = threading.Thread(target=self._find_jvm, args=(p, tuned), daemon=True)
                finder.start()
        lines = []
        with open(log, "a", encoding="utf-8") as fh:
            fh.write(f"\n>>>>> {now()} $ magat {' '.join(map(str, args))}\n")
            for line in p.stdout:
                if echo and (not self.a.quiet or not measure):
                    print(line, end="", flush=True)
                fh.write(f"{datetime.datetime.now():%H:%M:%S} {line}")
                lines.append(line.rstrip("\n"))
            rc = p.wait()
            fh.write(f"===== exit {rc} after {time.time() - start:.0f} s\n")
        if finder:
            finder.join(timeout=5)
        if self.monitor is not None:
            self.monitor.release(launcher)
            for h in tuned.get("handles", []):
                self.monitor.release(h)
            self.monitor.phase = "between"
        self.spans.append((phase, start, time.time()))
        if measure and "pid" in tuned:
            self.events(f"[controls] {phase}: JVM pid {tuned['pid']}, CPUs {_ranges(tuned.get('cpus')) or '?'}"
                        + "".join(f", {k} {v}" for k, v in (tuned.get("applied") or {}).items()))
        return rc, lines

    def _find_jvm(self, p, tuned):
        """Find the JVM among the launcher's descendants (PATH's java may be a shim that starts the real one),
        counting every descendant as ours and tuning the JVMs."""
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
                h = self.plat.open(pid, write=jvm)
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
        level = int(level)
        n = est["positions"][str(level)] if str(level) in est["positions"] else est["positions"][level]
        cells = 1 + arms_in_session
        turns = est["warmup"] + cells * (1 + est["reps"] * n)
        if level == ref_budget and cells > 1:
            turns += est["warmup"] + 1 + est["reps"] * n          # the bracket
        total += turns * per_turn
    return total


def counter_stats(path, spans, nominal, pcores, confined=False) -> dict:
    """Per phase, from typeperf (Windows): the busiest logical CPU, its clock, and the thermal zones.

    When the measuring JVM is confined, the busiest of its CPUs is where the measured thread ran;
    otherwise the busiest of all, and how often that was an efficient core. A sample counts only when
    that CPU was busy for most of it (the measured thread, not a gap between JVMs)."""
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
        cols.append((i, obj, inst, body[body.rfind(")\\") + 2:]))
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
                d["approx"] = True            # relative to the registry's nominal frequency: read the spread, not the level
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
        parts.append(f"{'about ' if d.get('approx') else ''}{d['mhz'][0]}-{d['mhz'][2]} MHz (median {d['mhz'][1]})")
    elif "perf_pct" in d:
        parts.append(f"{d['perf_pct'][0]:.0f}-{d['perf_pct'][2]:.0f}% of nominal")
    if d.get("busiest_on_performance_core", 1) < 1:
        parts.append(f"busiest CPU on an efficient core in {100 * (1 - d['busiest_on_performance_core']):.0f}% of samples")
    if "temp_max_c" in d:
        parts.append(f"up to {d['temp_max_c']:.0f} C")
    if d.get("passive_min_pct", 100) < 100:
        parts.append(f"passive cooling limit down to {d['passive_min_pct']:.0f}%")
    if d.get("throttled_s"):
        parts.append(f"container CPU-throttled {d['throttled_s']:.1f} s")
    return ", ".join(parts) or "-"


def reliability(row, a, clock) -> list:
    """Why a session that passed the calibrator's gates is still in doubt (empty: nothing seen)."""
    reasons = []
    if row.get("other_cpus_worst", 0) > a.other_cpus:
        reasons.append(f"other work up to {row['other_cpus_worst']:.1f} logical CPUs, sustained for {3 * a.interval:.0f} s")
    if (clock or {}).get("throttled_s", 0) > 0:
        reasons.append(f"the container was CPU-throttled for {clock['throttled_s']:.1f} s")
    if "speed_vs_file" in row and abs(row["speed_vs_file"] - 1) > a.speed_tolerance:
        reasons.append(f"ran at x{row['speed_vs_file']:.3f} the speed of the currency's first session")
    if (clock or {}).get("busiest_on_performance_core", 1) < 0.95:
        reasons.append("the measured thread ran on an efficient core part of the time")
    return reasons


# ---------------------------------------------------------------------------------------------------

def main(argv) -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass
    ap = argparse.ArgumentParser(prog="magat calibrate-controlled", description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", required=True, help="the currency's name, e.g. laptop-i9-14900hx or r7615-v2")
    ap.add_argument("--game", default="tribes")
    ap.add_argument("--out", help="the calibration directory (default <data>/calibration/<game>-<name>)")
    ap.add_argument("--portfolio", help="default: the game's published portfolio")
    ap.add_argument("--arms", help="arms or @sets to price (default: every priced arm the game registers)")
    ap.add_argument("--batch", type=int, default=BATCH, help="arms per section, when the sections are first planned")
    ap.add_argument("--section", default="all", help="all | next | checks | <number>")
    ap.add_argument("--redo", action="store_true", help="with --section <n>: measure it again (calibrate --replace)")
    ap.add_argument("--plan", action="store_true", help="print the sections and what is priced; measure nothing")
    ap.add_argument("--format", type=int, default=2, choices=(1, 2))
    ap.add_argument("--levels"), ap.add_argument("--turns"), ap.add_argument("--reps"), ap.add_argument("--warmup")
    ap.add_argument("--soak-minutes", type=float, default=SOAK_MINUTES, help="at least this long before the first section")
    ap.add_argument("--soak-max-minutes", type=float, default=SOAK_MAX_MINUTES)
    ap.add_argument("--stable-within", type=float, default=STABLE_WITHIN)
    ap.add_argument("--wait-quiet", type=float, default=0.0,
                    help="minutes to wait for other work to drop below --other-cpus before a section (0: do not wait)")
    ap.add_argument("--other-cpus", type=float, default=OTHER_CPUS, help="other work, in logical CPUs, that makes a section suspect")
    ap.add_argument("--speed-tolerance", type=float, default=SPEED_TOLERANCE,
                    help="a section running this far from the first session's speed is suspect")
    ap.add_argument("--retries", type=int, default=RETRIES)
    ap.add_argument("--check-arms", default=",".join(CHECK_ARMS))
    ap.add_argument("--no-checks", action="store_true", help="--section all: skip the two drift checks")
    ap.add_argument("--redo-checks", action="store_true", help="run the drift checks again even if recorded")
    ap.add_argument("--priority", default="high", choices=("normal", "above", "high"), help="Windows only")
    ap.add_argument("--cpus", help="run the measuring JVMs on these CPUs, e.g. 0-15")
    ap.add_argument("--no-affinity", action="store_true", help="let the OS place the measuring JVMs on any CPU")
    ap.add_argument("--let-display-sleep", action="store_true", help="keep the system awake but let the display turn off")
    ap.add_argument("--interval", type=float, default=INTERVAL)
    ap.add_argument("--quiet", action="store_true", help="do not echo the calibrator's own output (it is still logged)")
    ap.add_argument("--preflight", action="store_true", help="the checklist only; measure nothing")
    ap.add_argument("--allow-battery", action="store_true")
    ap.add_argument("--allow-online", action="store_true")
    ap.add_argument("--allow-other-java", action="store_true")
    ap.add_argument("--allow-policy-change", action="store_true", help="continue a currency under a different clock policy")
    ap.add_argument("--toy", action="store_true", help="toy sizes for the shakedown: gates reported, not enforced")
    a = ap.parse_args(argv)

    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", a.name):
        print("--name: lower-case letters, digits, '.', '_' and '-'", file=sys.stderr)
        return 2
    numbered = re.fullmatch(r"\d+(,\d+)*", a.section) is not None
    if a.section not in ("all", "next", "checks") and not numbered:
        print("--section: all, next, checks, a section number, or a list of them (2,3,4)", file=sys.stderr)
        return 2
    if a.redo and not numbered:
        print("--redo needs --section <number>[,<number>...]", file=sys.stderr)
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
    record_root = out / "record"
    record = record_root / stamp
    record.mkdir(parents=True, exist_ok=True)
    events = Events(record / "events.log")
    state_path = record_root / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.is_file() else {}

    def save_state():
        tmp = state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, indent=2, default=str), encoding="utf-8")
        tmp.replace(state_path)

    print("############################################################")
    print(f"# MAGAT controlled calibration   {a.game}-{a.name}   section {a.section}{' (redo)' if a.redo else ''}"
          + ("   (TOY)" if a.toy else ""))
    print(f"# currency  {out}")
    print(f"# record    {record}")
    print("############################################################")
    quiet_runner = Runner(a, plat, None, events, record)

    # ---- the arms and their sections: planned once per currency, so a section keeps its number
    rc, lines = quiet_runner.launch(["arms", "--game", a.game, *(["--arms", a.arms] if a.arms else [])], "arms", "build", False,
                                    echo=False)
    if rc != 0:
        print(f"cannot list the arms (exit {rc}); read {record / 'build.log'}", file=sys.stderr)
        return 2
    listed = [(m.group(1), line) for line in lines for m in [ARM_LINE.match(line)] if m]
    arms = [x for x, line in listed if "[not priced]" not in line]
    ref_arm = next((x for x, line in listed if "[reference]" in line), None)
    if weights.is_file():
        m = re.search(r"^reference\s*=\s*(\S+)@", weights.read_text(encoding="utf-8", errors="replace"), re.M)
        ref_arm = m.group(1) if m else ref_arm
    if ref_arm is None:
        ref_arm = arms[0] if arms else CHECK_ARMS[0]
    plan = state.get("plan")
    if plan and plan.get("batch") != a.batch and a.batch != BATCH:
        print(f"note: this currency's sections were planned with --batch {plan['batch']}; --batch {a.batch} is ignored")
    if not plan:
        todo = [x for x in arms if x != ref_arm]
        size = a.batch if a.batch > 0 else max(1, len(todo))
        plan = {"batch": a.batch, "reference": ref_arm, "sections": [todo[i:i + size] for i in range(0, len(todo), size)]}
    else:
        known = {x for s in plan["sections"] for x in s}
        new = [x for x in arms if x not in known and x != plan["reference"]]
        size = plan["batch"] if plan["batch"] > 0 else max(1, len(new))
        plan["sections"] += [new[i:i + size] for i in range(0, len(new), size)]
    sections = plan["sections"] or [[]]          # the reference arm alone still makes a currency

    def coverage(runner):
        """The arms the currency prices at every level, and what it lacks."""
        if not weights.is_file():
            return set(), list(arms)
        levels = re.search(r"^levels\s*=\s*(\S+)", weights.read_text(encoding="utf-8", errors="replace"), re.M)
        rc, lines = runner.launch(["weights", "--weights", weights, "--game", a.game, "--arms", ",".join([ref_arm, *arms]),
                                   *(["--levels", levels.group(1)] if levels else [])], "coverage", "coverage", measure=False,
                                  echo=False)
        missing = [m.group(1) for line in lines for m in [MISSING.match(line)] if m]
        gone = {x.split("@")[0] for x in missing}
        return {x for x in [ref_arm, *arms] if x not in gone}, missing

    def latest(section_no):
        rows = [r for r in state.get("sessions", []) if r.get("section") == section_no]
        return rows[-1] if rows else None

    covered, _ = coverage(quiet_runner)

    def print_plan():
        print(f"\n=== PLAN: {len(arms)} arm(s) in {len(sections)} section(s) of up to {plan['batch'] or len(arms)}, the "
              f"reference arm {plan['reference']} measured in every one ===")
        for k, sec in enumerate(sections, 1):
            done = all(x in covered for x in sec) and weights.is_file()
            last = latest(k)
            verdict = (last.get("reliability") or last.get("verdict")) if last else "-"
            pace = state.get("pace")
            mins = f"~{project(pace, len(sec)) / 60:.0f} min" if pace else "duration after the first estimate"
            print(f"  section {k}: {'priced' if done else 'to measure':10}  last verdict {verdict:9}  {mins:>10}   "
                  f"{', '.join(sec) or plan['reference']}")
        ch = state.get("checks", {})
        print("  checks:    " + (", ".join(f"{k} {v.get('verdict')}" for k, v in ch.items()) or "not run"))
        print(f"SECTIONS: {len(sections)}")

    if a.plan:
        print_plan()
        return 0

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
        verdict = f"NOT READY ({len(refusals)} refusal(s))" if refusals else "READY" + (
            " (read the warnings)" if any(lvl == "warn" for lvl, _ in items) else "")
        print(f"\nPREFLIGHT: {verdict}")
        if not refusals:
            print_plan()
        return 2 if refusals else 0

    # ---- what this invocation does
    def pending_sections():
        return [k for k, sec in enumerate(sections, 1) if not (weights.is_file() and all(x in covered for x in sec))]

    checks_all = [] if a.no_checks else (["self"] if a.toy else ["published", "self"])
    if a.section == "all":
        todo_sections, todo_checks = pending_sections(), checks_all
    elif a.section == "next":
        pend = pending_sections()
        todo_sections = pend[:1]
        todo_checks = [] if pend else [c for c in checks_all if c not in state.get("checks", {})][:2]
    elif a.section == "checks":
        todo_sections, todo_checks = [], checks_all or ["published", "self"]
    else:
        asked = list(dict.fromkeys(int(x) for x in a.section.split(",")))
        if any(not 1 <= k <= len(sections) for k in asked):
            print(f"there are {len(sections)} section(s); --plan lists them", file=sys.stderr)
            return 2
        pend = pending_sections()
        todo_sections = [k for k in asked if a.redo or k in pend]
        todo_checks = []
    todo_checks = [c for c in todo_checks if a.redo_checks or c not in state.get("checks", {})]
    if not todo_sections and not todo_checks:
        if numbered:
            worst = 0
            for k in dict.fromkeys(int(x) for x in a.section.split(",")):
                last = latest(k)
                v = (last or {}).get("reliability") or (last or {}).get("verdict") or "PASS"
                print(f"\nsection {k} is already priced (last verdict {v}); --redo measures it again")
                print(f"SECTION {k}: {v} (already priced)")
                worst = worst if v == "PASS" or a.toy else 5
            return worst
        print_plan()
        print("\nCONTROLLED CALIBRATION: " + ("COMPLETE" if not pending_sections() else "nothing to do in this section"))
        return 0

    # ---- one invocation at a time
    lock = record_root / ".lock"
    try:
        fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        try:
            other_pid = int(lock.read_text().strip() or 0)
        except (OSError, ValueError):
            other_pid = 0
        if other_pid and plat.alive(other_pid):
            print(f"another calibrate-controlled (pid {other_pid}) is running on {out}", file=sys.stderr)
            return 2
        lock.unlink()
        fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    os.write(fd, str(os.getpid()).encode())
    os.close(fd)

    # ---- controls
    cpus = _cpu_list(facts.get("measuring_cpus") or "")
    confined = bool(cpus) and plat.confine(cpus)
    elsewhere = [c for c in plat.allowed_cpus() if cpus and c not in cpus]
    awake = plat.keep_awake(True, display=not a.let_display_sleep)
    monitor = Monitor(plat, record, a.interval, a.other_cpus, events, cpus if confined else None)
    monitor.on_ac = (plat.power() or {}).get("ac") is not False
    monitor.start_counters(elsewhere)
    monitor.start()
    runner = Runner(a, plat, monitor, events, record)
    events(f"[start] {a.game}-{a.name}: sections {todo_sections or '-'}, checks {todo_checks or '-'}; "
           + (f"JVMs on CPUs {_ranges(cpus)} ({facts.get('measuring_cpus_why')})" if confined else "JVMs placed by the OS")
           + (", kept awake" if awake else "") + (f", priority {a.priority}" if WINDOWS else ""))
    state["plan"] = plan
    state.setdefault("first", stamp)
    state.setdefault("sessions", [])
    state.setdefault("checks", {})
    state.setdefault("invocations", []).append({"stamp": stamp, "section": a.section, "redo": a.redo,
                                                "facts": {k: facts.get(k) for k in ("power", "power_settings", "power_mode",
                                                                                    "cgroup", "measuring_cpus", "other_cpus_now",
                                                                                    "uptime_hours", "network_up", "commit", "java")}})
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
    clock = {}

    def clocks():
        if WINDOWS:
            return counter_stats(monitor.counters_path, runner.spans, nominal_mhz(), set(cpus), confined)
        return monitor.clock_stats()

    def wait_quiet(label):
        """Wait until other work on the machine drops below the threshold, or stop."""
        if a.wait_quiet <= 0 or a.toy:
            return
        deadline = time.time() + 60 * a.wait_quiet
        while True:
            other, _ = sample_other_work(plat, 30.0)
            if other is None or other <= a.other_cpus:
                if other is not None:
                    events(f"[quiet] {other:.1f} logical CPUs of other work before {label}: starting")
                return
            if time.time() > deadline:
                raise Stop(4, f"the machine stayed busy before {label}: {other:.1f} logical CPUs of other work after "
                              f"waiting {a.wait_quiet:g} min; nothing was started. Run the same command later")
            events(f"[quiet] {other:.1f} logical CPUs of other work before {label}: waiting (until "
                   f"{datetime.datetime.fromtimestamp(deadline):%H:%M})")
            time.sleep(30)

    try:
        rc, _ = runner.launch(["version"], "build", "build", measure=False)
        if rc != 0:
            raise Stop(2, f"the build failed; read {record / 'build.log'}")

        # ---- stability: estimates until two in a row agree
        wait_quiet("the stability check")
        print("\n=== STABILITY: estimates back to back until the machine holds its speed ===")
        t0 = time.time()
        est = None
        while True:
            k = len(soak_rows) + 1
            rc, lines = runner.launch(["calibrate", "--estimate", "--game", a.game, "--format", a.format, "--arms", ref_arm,
                                       *passthrough, *toy], f"soak {k}", "soak", measure=True)
            if monitor.power_lost.is_set():
                raise PowerLost()
            e = parse_estimate(lines)
            if rc != 0 or not e.get("levels") or "reference" not in e:
                raise Stop(1, f"estimate {k} failed (exit {rc}); read {record / 'soak.log'}")
            change = None
            if soak_rows:
                prev = soak_rows[-1]["levels"]
                change = max(abs(e["levels"][lv] / prev[lv] - 1) for lv in e["levels"] if lv in prev)
            soak_rows.append({"levels": e["levels"], "change": change, "minutes": (time.time() - t0) / 60})
            est = e
            events(f"[stability] estimate {k}: " + ", ".join(f"{lv}: {v:.2f} s/turn" for lv, v in sorted(e["levels"].items()))
                   + (f"; change {100 * change:.1f}%" if change is not None else ""))
            elapsed = (time.time() - t0) / 60
            steady = change is not None and change <= a.stable_within
            if a.toy:
                break
            if steady and elapsed >= a.soak_minutes:
                events(f"[stability] steady after {elapsed:.0f} min: two estimates in a row within {100 * a.stable_within:.0f}%")
                break
            if elapsed >= a.soak_max_minutes:
                raise Stop(1, f"the machine did not hold its speed: not steady after {elapsed:.0f} min of estimates (last "
                              f"change {100 * (change or 0):.1f}%), so a section would compare cells measured at different "
                              "speeds; docs/CALIBRATING_A_MACHINE.md says what to change")
        state["pace"] = {"levels": est["levels"], "positions": est["positions"], "reps": est["reps"],
                         "warmup": est["warmup"], "reference": est["reference"]}
        save_state()
        last = clocks().get(f"soak {len(soak_rows)}")
        if last:
            events("[stability] busiest measuring core during the last estimate: " + describe_counters(last))

        hours = sum(project(est, len(sections[k - 1])) for k in todo_sections) / 3600
        check_arms = [x for x in a.check_arms.split(",") if x in arms or x == ref_arm] or [ref_arm]
        check_hours = len(todo_checks) * project(est, len([x for x in check_arms if x != ref_arm])) / 3600
        finish = datetime.datetime.now() + datetime.timedelta(hours=hours + check_hours)
        print(f"\n=== THIS INVOCATION: section(s) {todo_sections or '-'}, check(s) {todo_checks or '-'} ===")
        for k in todo_sections:
            print(f"  section {k}: {', '.join(sections[k - 1]) or ref_arm}   ~{project(est, len(sections[k - 1])) / 60:.0f} min")
        events(f"[plan] about {hours:.1f} h of sections and {check_hours:.1f} h of checks at this pace; expected to finish "
               f"around {finish:%H:%M} (up to 25% later if the arms run slower than the reference)")
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
        if todo_sections and not a.toy:
            events("[plan] leave the machine alone from here: nothing else should run until it finishes")

        # ---- sections
        failed = []
        for attempt in range(1 + max(0, a.retries)):
            queue = todo_sections if attempt == 0 else failed
            failed = []
            for n, k in enumerate(queue):
                sec = sections[k - 1]
                if not a.redo and weights.is_file() and sec:
                    covered, _ = coverage(runner)
                    if all(x in covered for x in sec):
                        continue
                if n or attempt:
                    wait_quiet(f"section {k}")
                label = f"section {k}" + (" redo" if a.redo else "") + (f" attempt {attempt + 1}" if attempt else "")
                print(f"\n=== {label.upper()} of {len(sections)}: {', '.join(sec) or ref_arm} ===")
                events(f"[section] {label} starts: {len(sec) or 1} arm(s)")
                before = time.time()
                args = ["calibrate", "--game", a.game, "--out", out, "--arms", ",".join(sec) or ref_arm, "--format", a.format,
                        *passthrough, *toy]
                if a.redo and sec and weights.is_file():
                    args += ["--replace", ",".join(sec)]
                rc, lines = runner.launch(args, label, "sections", measure=True)
                keep = record / label.replace(" ", "-")
                keep.mkdir(exist_ok=True)
                for f in ("compute_profiles.csv", "compute_operations.csv"):
                    if (out / f).is_file() and (out / f).stat().st_mtime >= before:
                        shutil.copy2(out / f, keep / f)
                text = "\n".join(lines)
                passed = rc == 0 and "CALIBRATION: PASS" in text
                row = {"section": k, "attempt": attempt + 1, "redo": a.redo, "arms": sec or [ref_arm], "label": label,
                       "verdict": "PASS" if passed else ("STOPPED (power)" if monitor.power_lost.is_set() else "FAIL"),
                       "minutes": round((time.time() - before) / 60, 1), "invocation": stamp}
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
                clock = clocks()
                row["clock"] = clock.get(label)
                if passed:
                    row["reasons"] = reliability(row, a, row["clock"])
                    row["reliability"] = "SUSPECT" if row["reasons"] else "PASS"
                    state.setdefault("policy", policy)
                else:
                    row["reliability"] = row["verdict"]
                session_rows.append(row)
                state["sessions"].append(row)
                save_state()
                events(f"[section] {label}: {row['reliability']} in {row['minutes']} min"
                       + (f"; bracket {row['bracket'][0]:+.1f}/{row['bracket'][1]:+.1f}/{row['bracket'][2]:+.1f}%" if "bracket" in row else "")
                       + (f"; speed x{row['speed_vs_file']:.3f} the first session's" if "speed_vs_file" in row else "")
                       + "".join(f"; {r}" for r in row.get("reasons", []) + row["failures"]))
                print(f"SECTION {k}: {row['reliability']}" + (" - " + "; ".join(row["reasons"]) if row.get("reasons") else ""))
                if monitor.power_lost.is_set():
                    raise PowerLost()
                if not passed:
                    failed.append(k)
            if not failed:
                break
        covered, missing = coverage(runner)

        # ---- checks (write nothing)
        if weights.is_file() and not failed:
            for which in todo_checks:
                label = f"check {which}"
                if not all(x in covered for x in check_arms):
                    events(f"[check] {which}: skipped, the currency does not price {', '.join(check_arms)} yet")
                    continue
                wait_quiet(label)
                print(f"\n=== CHECK AGAINST {'THE PUBLISHED CURRENCY' if which == 'published' else 'THE NEW CURRENCY'} "
                      f"(writes nothing) ===")
                args = ["calibrate", "--check", "--game", a.game, "--arms", ",".join(check_arms)]
                # No --levels: a check measures the levels of the file it checks (the published one's may differ).
                for flag in ("reps", "warmup"):
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
                row = {"check": which, "verdict": verdict, "minutes": round((time.time() - before) / 60, 1), "label": label,
                       "invocation": stamp}
                m = DRIFT.search(text)
                if m:
                    row["drift"] = f"{m.group(1)}% ({m.group(2)})"
                m = BRACKET.search(text)
                if m:
                    row["bracket"] = [float(x) for x in m.groups()]
                s = monitor.stats.get(label, {})
                if s.get("n"):
                    row["other_cpus_worst"] = round(s["worst"], 2)
                row["clock"] = clocks().get(label)
                check_rows.append(row)
                if verdict in ("PASS", "FAIL"):
                    state["checks"][which] = row
                    save_state()
                events(f"[check] {which}: {verdict}" + (f", largest turn-price drift {row['drift']}" if "drift" in row else ""))
                print(f"CHECK {which.upper()}: {verdict}")

        if a.section == "all":
            code = 1 if failed or missing else 0
        elif a.section == "checks" or (a.section == "next" and todo_checks):
            code = 1 if any(r["verdict"].startswith("ERROR") for r in check_rows) else 0
        else:
            code = 1 if failed else 0
        if code == 0 and any(r.get("reliability") == "SUSPECT" for r in session_rows) and not a.toy:
            code = 5
        if code == 0 and any(r["check"] == "self" and r["verdict"] == "FAIL" for r in check_rows) and not a.toy:
            code = 5
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
    if WINDOWS:
        counters = counter_stats(counters_file, runner.spans, nominal_mhz(), set(cpus), confined) if counters_file else {}
    else:
        counters = monitor.clock_stats()
    try:
        covered, _ = coverage(runner)
    except Exception:
        pass
    summary = write_summary(a, out, record, facts, plan, sections, state, soak_rows, session_rows, check_rows, counters,
                            monitor.stats, weights, covered, time.time() - t_start, code, confined, cpus, awake)
    print("\n" + summary)
    _unlock(lock)
    if weights.is_file():
        subprocess.run([PY, str(MAGAT), "bundle", "--name", f"calibration-{a.game}-{a.name}", "--paths", str(out)],
                       cwd=str(ROOT), check=False)
    pend = [k for k, sec in enumerate(sections, 1) if not (weights.is_file() and all(x in covered for x in sec))]
    suspect = [r["section"] for r in session_rows if r.get("reliability") == "SUSPECT"]
    verdicts = {0: "COMPLETE" if not pend else f"DONE - {len(pend)} section(s) still to measure: {pend}",
                1: f"INCOMPLETE - {why}" if why else "INCOMPLETE - run the same command again to measure what is missing",
                2: f"NOT STARTED - {why}", 3: "STOPPED - the machine went on battery; plug in and run the same command again",
                4: f"NOT STARTED - {why}",
                5: (f"SUSPECT - the currency is written, but section(s) {suspect} are in doubt (reasons above); once the cause "
                    f"is gone, measure them again with --section {','.join(map(str, suspect))} --redo"
                    if suspect else "SUSPECT - the self-check failed: the machine did not hold its speed across the run"),
                130: "INTERRUPTED - run the same command again to continue"}
    print(f"CONTROLLED CALIBRATION: {verdicts.get(code, code)}")
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


def write_summary(a, out, record, facts, plan, sections, state, soak, sessions, checks, counters, mstats, weights,
                  covered, seconds, code, confined, cpus, awake) -> str:
    ps = facts.get("power_settings", {})
    p = facts.get("power") or {}
    cg = facts.get("cgroup") or {}
    net = facts.get("network_up")
    L = [f"# Controlled calibration: {a.game}-{a.name}" + (" (toy)" if a.toy else ""), "",
         f"- Currency: `{weights}`" + (" (written)" if weights.is_file() else " (not written yet)"),
         f"- This invocation: section `{a.section}`{' (redo)' if a.redo else ''}, record `{record}`, {seconds / 3600:.2f} h, "
         f"finished {now()}, exit {code}",
         f"- Commit {facts.get('commit')}; {facts.get('cpu')}; {facts.get('machine')}; "
         f"{(facts.get('java') or ['java ?'])[0]}", "",
         "## Conditions of this invocation", "", "| | |", "|---|---|"]
    if p.get("battery") is not None:
        L.append(f"| Power | {'mains' if p.get('ac') is not False else 'battery'}, battery {p.get('battery')}%"
                 + (", charging" if p.get("charging") else "") + " |")
    if ps.get("plan"):
        L.append(f"| Power plan | {ps['plan']}; power mode {facts.get('power_mode', {}).get('effective', '-')} |")
    L += [f"| Clock policy | {describe_clock(ps)} |",
          f"| Measuring JVMs | " + (f"CPUs {_ranges(cpus)}: {facts.get('measuring_cpus_why')}" if confined else "placed by the OS")
          + (f"; priority {a.priority}; power throttling off" if WINDOWS else "") + " |"]
    if cg:
        L.append(f"| Container | cgroup v{cg.get('version')}, CPUs {facts.get('allowed_cpus')}, "
                 + (f"CPU limit {cg['cpus']:.1f}" if cg.get("cpus") else "no CPU limit")
                 + (f", memory limit {cg['memory_gb']:.0f} GB" if cg.get("memory_gb") else "") + " |")
    if facts.get("other_cpus_now") is not None:
        L.append(f"| Other work at the start | {facts['other_cpus_now']:.1f} logical CPUs |")
    if WINDOWS:
        L.append(f"| Kept awake | {'yes' if awake else 'no'} |")
    if net is not None and p.get("battery") is not None:
        L.append(f"| Network | {'offline' if net == [] else 'online: ' + ', '.join(net)} |")
    if facts.get("uptime_hours") is not None:
        L.append(f"| Uptime at start | {facts['uptime_hours']:.1f} h |")
    L.append("")
    if soak:
        levels = sorted(soak[0]["levels"])
        L += ["## Stability check", "", "| Estimate | " + " | ".join(f"{lv} s/turn" for lv in levels) + " | Change | Busiest core |",
              "|---|" + "---|" * (len(levels) + 2)]
        for i, r in enumerate(soak, 1):
            L.append(f"| {i} | " + " | ".join(f"{r['levels'][lv]:.2f}" for lv in levels) + " | "
                     + (f"{100 * r['change']:.1f}%" if r["change"] is not None else "-") + " | "
                     + describe_counters(counters.get(f"soak {i}")) + " |")
        L.append("")

    def session_table(rows, title):
        out_ = [title, "", "| Section | Arms | Verdict | Minutes | Bracket (adv/copy/eval) | Speed vs first | Other work: mean / worst | Busiest core | When |",
                "|---|---|---|---|---|---|---|---|---|"]
        for r in rows:
            out_.append(f"| {r['label']} | {', '.join(r['arms'])} | {r.get('reliability', r['verdict'])} | {r['minutes']} | "
                        + ("/".join(f"{x:+.1f}%" for x in r["bracket"]) if "bracket" in r else "-") + " | "
                        + (f"x{r['speed_vs_file']:.3f}" if "speed_vs_file" in r else "first") + " | "
                        + (f"{r['other_cpus_mean']:.1f} / {r['other_cpus_worst']:.1f}" if "other_cpus_mean" in r else "-") + " | "
                        + describe_counters(r.get("clock")) + f" | {r.get('invocation', '')} |")
        for r in rows:
            for f in r.get("reasons", []) + r.get("failures", []):
                out_.append(f"- {r['label']}: {f}")
        return out_ + [""]

    if sessions:
        L += session_table(sessions, "## Sections measured in this invocation")
    if checks:
        L += ["## Checks in this invocation (write nothing)", "", "| Check | Verdict | Largest turn-price drift | Bracket | Busiest core |",
              "|---|---|---|---|---|"]
        for r in checks:
            L.append(f"| {r['check']} | {r['verdict']} | {r.get('drift', '-')} | "
                     + ("/".join(f"{x:+.1f}%" for x in r["bracket"]) if "bracket" in r else "-") + " | "
                     + describe_counters(r.get("clock")) + " |")
        L.append("")
    events = sum(s.get("events", 0) for s in mstats.values())
    L += [f"Other work above {a.other_cpus:g} logical CPU(s), sustained: {events} period(s); each is in `events.log` with "
          "the programs that were using the CPU, and `monitor.csv` has every sample.", ""]

    # the whole currency, every invocation
    C = [f"# Controlled calibration: {a.game}-{a.name}" + (" (toy)" if a.toy else ""), "",
         f"Currency `{weights}`" + (" (written)" if weights.is_file() else " (not written yet)") + f"; updated {now()}.", "",
         "## Sections", "", "| Section | Arms | Priced | Latest verdict | Bracket | Speed vs first | Other work, worst | Busiest core | When |",
         "|---|---|---|---|---|---|---|---|---|"]
    redo = []
    for k, sec in enumerate(sections, 1):
        rows = [r for r in state.get("sessions", []) if r.get("section") == k]
        r = rows[-1] if rows else {}
        priced = weights.is_file() and all(x in covered for x in sec)
        v = r.get("reliability", r.get("verdict", "-"))
        if v == "SUSPECT":
            redo.append(k)
        C.append(f"| {k} | {', '.join(sec) or plan['reference']} | {'yes' if priced else 'no'} | {v} | "
                 + ("/".join(f"{x:+.1f}%" for x in r["bracket"]) if "bracket" in r else "-") + " | "
                 + (f"x{r['speed_vs_file']:.3f}" if "speed_vs_file" in r else ("first" if r else "-")) + " | "
                 + (f"{r['other_cpus_worst']:.1f}" if "other_cpus_worst" in r else "-") + " | "
                 + describe_counters(r.get("clock")) + f" | {r.get('invocation', '-')} |")
    C.append("")
    for k in redo:
        rows = [r for r in state["sessions"] if r.get("section") == k]
        C.append(f"- Section {k} is suspect: {'; '.join(rows[-1].get('reasons', []))}.")
    if redo:
        C.append(f"- Once the cause is gone, measure them again in one session: "
                 f"`python magat.py calibrate-controlled --name {a.name} --section {','.join(map(str, redo))} --redo`")
    ch = state.get("checks", {})
    C += ["", "## Checks", "", "| Check | Verdict | Largest turn-price drift | When |", "|---|---|---|---|"]
    for name in ("published", "self"):
        r = ch.get(name)
        C.append(f"| {name} | {r['verdict'] if r else 'not run'} | {r.get('drift', '-') if r else '-'} | "
                 f"{r.get('invocation', '-') if r else '-'} |")
    C += ["", f"Every invocation's record is in `{record.parent}`; this one's summary is `{record / 'SUMMARY.md'}`.", ""]
    text = "\n".join(L)
    (record / "SUMMARY.md").write_text(text + "\n", encoding="utf-8")
    (record.parent / "SUMMARY.md").write_text("\n".join(C) + "\n", encoding="utf-8")
    return text + "\n" + "\n".join(C)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
