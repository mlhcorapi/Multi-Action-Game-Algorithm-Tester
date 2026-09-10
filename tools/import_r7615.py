#!/usr/bin/env python3
"""Import the R7615 artefacts (the thesis line's published calibration and portfolio) into MAGAT.

    python tools/import_r7615.py <dir-with-_portfolio-_calibration-logs-stage1>

Writes games/tribes/portfolio/v1/ (manifest, splits, bins, identity) and
games/tribes/calibration/r7615/ (the weights in the version-1 format, every original beside it, and
ORIGIN.md saying what came from where). Run once; the outputs are committed.
"""
import hashlib
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT_OUT = ROOT / "games" / "tribes" / "portfolio" / "v1"
CAL_OUT = ROOT / "games" / "tribes" / "calibration" / "r7615"

# Archived profile name -> MAGAT arm id (DESIGN.md Appendix A). The engine's own EMCTSAgent cell,
# the anchor of the currency, is kept as emcts-engine; MAGAT does not run it.
ARM_MAP = {
    "EMCTS": "emcts-engine",
    "AB_Baseline": "emcts",
    "AB_Bias_e75": "emcts-bias-e75", "AB_Bias_e50": "emcts-bias-e50",
    "AB_Bias_e25": "emcts-bias-e25", "AB_Bias_e10": "emcts-bias-e10",
    "AB_Coarse_e25": "emcts-bias-coarse-e25", "AB_NoRemoval_e25": "emcts-bias-norem-e25",
    "AB_PerBurn_e25": "emcts-bias-perburn-e25", "AB_Shortlist15": "emcts-short15",
    "AB_Short15_e25": "emcts-short15-bias-e25", "AB_Short5_e25": "emcts-short5-bias-e25",
    "AB_Z0_Coarse": "emcts-z0-coarse", "AB_Z0_Mid": "emcts-z0-mid", "AB_Z0_Fine": "emcts-z0-fine",
    "AB_Z0_Fine_InsFine": "emcts-z0-fine-insfine",
    "AB_Comma_Off": "emcts-comma", "AB_Comma_On": "comma-bias-e25",
    "AB_Comma_e75": "comma-bias-e75", "AB_Comma_e50": "comma-bias-e50", "AB_Comma_e10": "comma-bias-e10",
    "AB_Comma_Coarse_e25": "comma-bias-coarse-e25", "AB_Comma_Fine_e25": "comma-bias-fine-e25",
    "AB_Comma_NoRem_e25": "comma-bias-norem-e25", "AB_Comma_PerBurn_e25": "comma-bias-perburn-e25",
    "AB_Comma_Short15": "comma-short15", "AB_Comma_Short15_Coarse": "comma-short15-coarse",
    "AB_Comma_Short15_e25": "comma-short15-bias-e25", "AB_Comma_Short5_e25": "comma-short5-bias-e25",
    "AB_Comma_Rand15": "comma-rand15",
    "AB_Comma_Z0_Coarse": "comma-z0-coarse", "AB_Comma_Z0_Mid": "comma-z0-mid",
    "AB_Comma_Z0_Fine": "comma-z0-fine", "AB_Comma_Z0_Fine_InsFine": "comma-z0-fine-insfine",
    "AB_Comma_Z0_Random": "comma-z0-random",
}


def read_props(path: pathlib.Path) -> dict:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        k, _, v = t.partition("=")
        out[k.strip()] = v.strip()
    return out


def identity_of(manifest: pathlib.Path) -> str:
    lines = manifest.read_text(encoding="utf-8").splitlines()
    header = lines[0].split(",")
    i_id, i_src, i_tick = header.index("ScenarioId"), header.index("Source"), header.index("CapturedTick")
    h = hashlib.sha256()
    for line in lines[1:]:
        f = line.split(",")
        if len(f) < 11:
            continue
        h.update(f"{f[i_id].strip()},{f[i_src].strip()},{f[i_tick].strip()}\n".encode("utf-8"))
    return h.hexdigest()


def import_portfolio(src: pathlib.Path) -> str:
    PORT_OUT.mkdir(parents=True, exist_ok=True)
    for name in ("portfolio.csv", "_split_dev.txt", "_split_holdout.txt", "config.txt", "_portfolio_complete.txt"):
        shutil.copyfile(src / name, PORT_OUT / name)
    (PORT_OUT / "scenarios").mkdir(exist_ok=True)
    bins = sorted((src / "scenarios").glob("scenario_*.bin"))
    for b in bins:
        shutil.copyfile(b, PORT_OUT / "scenarios" / b.name)
    identity = identity_of(PORT_OUT / "portfolio.csv")
    frozen = re.search(r"# identity-sha256: ([0-9a-f]{64})", (PORT_OUT / "_split_dev.txt").read_text(encoding="utf-8"))
    assert frozen and frozen.group(1) == identity, f"identity mismatch: split says {frozen and frozen.group(1)}, manifest hashes to {identity}"
    (PORT_OUT / "identity.txt").write_text(identity + "\n", encoding="utf-8")
    print(f"portfolio: {len(bins)} bins, identity {identity}")
    return identity


def import_calibration(final_weights: pathlib.Path, stage_a: pathlib.Path, identity: str, extra_files: list, logs: list):
    CAL_OUT.mkdir(parents=True, exist_ok=True)
    (CAL_OUT / "logs").mkdir(exist_ok=True)
    props = read_props(final_weights)
    stage_a_props = read_props(stage_a / "compute_weights.properties")
    # Per-op nanoseconds from the stage A cells CSV (reference + the 17 plus-era arms), by profile.
    nanos = {}
    csv = stage_a / "compute_profiles.csv"
    if csv.is_file():
        rows = csv.read_text(encoding="utf-8").splitlines()
        hdr = rows[0].split(",")
        for r in rows[1:]:
            f = r.split(",")
            if len(f) < len(hdr):
                continue
            d = dict(zip(hdr, f))
            nanos[d["Profile"]] = (d["AdvanceNanos"], d["CopyNanos"], d["EvalNanos"])

    profiles = {}
    for k, v in props.items():
        m = re.match(r"compute\.profile\.(.+)@(\d+)\.(wAdvance|wCopy|wEval|advanceNanos)$", k)
        if not m:
            continue
        arm, budget, op = m.groups()
        if arm not in ARM_MAP:
            sys.exit(f"unmapped archived profile: {arm}")
        profiles.setdefault((ARM_MAP[arm], int(budget), arm), {})[op] = v

    header_lines = [l for l in final_weights.read_text(encoding="utf-8").splitlines() if l.startswith("# ")]
    machine = next((l[len("# machine="):] for l in header_lines if l.startswith("# machine=")), "unknown")
    java = next((l[len("# java="):] for l in header_lines if l.startswith("# java=")), "unknown")
    measured = next((l[len("# measured="):] for l in header_lines if l.startswith("# measured=")), "unknown")

    out = []
    out.append("# MAGAT weights, version 1: the Tribes currency as measured on the reference machine.")
    out.append("# Converted from the thesis line's compute_weights.properties (kept verbatim beside this")
    out.append("# file as compute_weights.r7615-original.properties); see ORIGIN.md for the mapping.")
    out.append("magat.weights.version = 1")
    out.append("game = tribes")
    out.append(f"machine = {machine} | Dell PowerEdge R7615 | 64 physical / 128 logical")
    out.append(f"java = {java}")
    out.append(f"measured = {measured}")
    out.append("levels = 40000,80000,400000")
    out.append("reference = emcts@40000")
    out.append(f"referenceAdvanceNanos = {props['compute.referenceAdvanceNanos']}")
    out.append("turnsPerRep = 8,4,2")
    out.append(f"portfolio.identity = {identity}")
    out.append(f"global.wAdvance = {props['compute.wAdvance']}")
    out.append(f"global.wCopy = {props['compute.wCopy']}")
    out.append(f"global.wEval = {props['compute.wEval']}")
    out.append("")
    out.append("# profile.<arm>@<budget>.{wAdvance,wCopy,wEval} are ratios to the reference advance;")
    out.append("# *Nanos are the measured per-operation costs where the archive carries them.")
    for (arm, budget, orig) in sorted(profiles, key=lambda t: (t[0], t[1])):
        p = profiles[(arm, budget, orig)]
        key = f"profile.{arm}@{budget}"
        out.append(f"{key}.wAdvance = {p['wAdvance']}")
        out.append(f"{key}.wCopy = {p['wCopy']}")
        out.append(f"{key}.wEval = {p['wEval']}")
        if "advanceNanos" in p:
            out.append(f"{key}.advanceNanos = {p['advanceNanos']}")
        n = nanos.get(f"{orig}@{budget}")
        if n:
            out.append(f"{key}.copyNanos = {n[1]}")
            out.append(f"{key}.evalNanos = {n[2]}")
    (CAL_OUT / "compute_weights.properties").write_text("\n".join(out) + "\n", encoding="utf-8")

    shutil.copyfile(final_weights, CAL_OUT / "compute_weights.r7615-original.properties")
    shutil.copyfile(stage_a / "compute_weights.properties", CAL_OUT / "compute_weights.r7615-stageA.properties")
    shutil.copyfile(stage_a / "compute_profiles.csv", CAL_OUT / "compute_profiles.stageA.csv")
    for f in extra_files:
        shutil.copyfile(f, CAL_OUT / f.name)
    for f in logs:
        shutil.copyfile(f, CAL_OUT / "logs" / f.name)

    mapping = "\n".join(f"| `{k}@<b>` | `{v}@<b>` |" for k, v in ARM_MAP.items())
    (CAL_OUT / "ORIGIN.md").write_text(f"""# Origin of the published Tribes calibration

Measured on the Dell PowerEdge R7615 (AMD EPYC 9534, 64 physical / 128 logical cores, JDK {java})
by the thesis line's `ComputeCalibrateV6`, 2026-08-28 (stage A2, 54 cells) and merged into by the
D-prep, E-prep, E3 and later sessions (see the `# merged` lines in the original file). One unit is
one advance of the reference cell `EMCTS@40000` = {props['compute.referenceAdvanceNanos']} ns on that machine.

| File | What it is |
|---|---|
| `compute_weights.properties` | the currency in MAGAT's version-1 format, arms renamed per the table below |
| `compute_weights.r7615-original.properties` | the final archived file, verbatim (all merges applied) |
| `compute_weights.r7615-stageA.properties` | the stage A2 file, verbatim, before any merge |
| `compute_profiles.stageA.csv` | every stage A2 cell: counts, per-op nanoseconds, CVs, weights, status |
| `compute_profiles.last.csv` | the last calibration session's cells (the file is overwritten per session) |
| `compute_profiles_load48.csv` | the A3' loaded measurement (48 workers), archived, not the currency |
| `thread_calibration.csv` | the A0b thread sweep per budget level |
| `action_bias_floor*.csv` | the support-probe results for the biased arms |
| `logs/` | the A-stage console logs, including the verify gate's signature for scenario 1 |

**Mapping of archived profile names to MAGAT arm ids.** `EMCTS@<b>` is the engine's own
`EMCTSAgent`, the anchor of the currency, kept as `emcts-engine` and not run by MAGAT;
`AB_Baseline@<b>` is the arm every action-bias contrast was paired against and is MAGAT's `emcts`.

| archived | MAGAT |
|---|---|
{mapping}

**What the archive does not carry.** Per-operation nanoseconds for copies and evaluations exist only
for the stage A2 cells (the per-session CSV was overwritten by later sessions); merged profiles carry
`advanceNanos` only. `turnsPerRep` was not recorded in the file when it was written; `8,4,2` is what
the A2 log states. The E3 re-pricing log was not bundled.
""", encoding="utf-8")
    print(f"calibration: {len(profiles)} profiles written to {CAL_OUT / 'compute_weights.properties'}")


def main(argv):
    if len(argv) != 1:
        sys.exit(__doc__)
    src = pathlib.Path(argv[0]).resolve()
    identity = import_portfolio(src / "_portfolio")
    cal = src / "_calibration"
    stage_a = src / "stage1" / "_calibration"
    extra = [cal / "compute_profiles_load48.csv", cal / "thread_calibration.csv",
             cal / "action_bias_floor.csv", cal / "action_bias_floor_Comma_On.csv"]
    last = cal / "compute_profiles.csv"
    shutil.copyfile(last, cal / "compute_profiles.last.csv")
    extra.append(cal / "compute_profiles.last.csv")
    logs = sorted((src / "logs").glob("A*.log"))
    import_calibration(cal / "compute_weights.properties", stage_a, identity, extra, logs)


if __name__ == "__main__":
    main(sys.argv[1:])
