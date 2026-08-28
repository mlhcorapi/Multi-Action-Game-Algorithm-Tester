#!/usr/bin/env bash
#
# AB-EMCTS shakedown — ~6 minutes, run BEFORE committing the machine to Stage A.
#
# Stage A is a ~5.6 h block that is mostly single-threaded and requires an idle machine, so an
# environment problem discovered inside it costs an evening. This runs every moving part at toy size:
# build, machine detection, portfolio, split freeze, both equivalence gates, the selection-structure
# assertions, a calibration cell, and a real (tiny) split run end to end.
#
# It measures nothing. Every number it prints is too small to mean anything; the only question it
# answers is "does this all work on this machine".
#
# Usage:  ./run_ab_shakedown.sh [outDir]        (default test_results/_shakedown)

set -uo pipefail

OUT="${1:-test_results/_shakedown}"
# Classpath separator: ':' everywhere except a Windows JVM driven from Git Bash / MSYS.
SEP=":"
case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
CP="out${SEP}lib/*"
PASS=0
FAIL=0

step() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }
ok()   { printf '  [ok]   %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  [FAIL] %s\n' "$*"; FAIL=$((FAIL+1)); }

run_check() {   # run_check "<name>" "<expected substring>" <command...>
  local name="$1" want="$2"; shift 2
  local log; log="$(mktemp)"
  if "$@" > "$log" 2>&1 && grep -q -- "$want" "$log"; then
    ok "$name"
  else
    bad "$name"
    echo "         command: $*"
    echo "         --- last 12 lines ---"
    tail -12 "$log" | sed 's/^/         /'
  fi
  rm -f "$log"
}

printf '############################################################\n'
printf '# AB-EMCTS SHAKEDOWN   out=%s\n' "$OUT"
printf '# Toy sizes throughout. This checks that things RUN, not what they measure.\n'
printf '############################################################\n'

# ---------------------------------------------------------------------------- 0. environment
step "0. Environment"
java -version 2>&1 | head -1 | sed 's/^/  java: /'
printf '  cwd:  %s\n' "$PWD"
if [ ! -d lib ]; then bad "no lib/ directory — run this from Tribes-master/"; exit 1; fi

# test_results must not live on a synced or network filesystem: the calibration is a timing
# measurement, and the recorded failure mode is a sync daemon waking during it.
FSTYPE="$(df -PT . 2>/dev/null | awk 'NR==2{print $2}')"
printf '  filesystem for %s: %s\n' "$PWD" "${FSTYPE:-unknown}"
case "$FSTYPE" in
  nfs*|cifs*|smb*|fuse*) bad "working tree is on $FSTYPE — move it to local disk before calibrating" ;;
  *) ok "working tree is on a local filesystem" ;;
esac

# ---------------------------------------------------------------------------- 1. build
step "1. Build"
find src -name '*.java' > sources.txt
rm -rf out && mkdir -p out
if javac --release 11 -nowarn -d out -cp "lib/*" @sources.txt 2>&1 | grep -v '^Note:' | grep -q .; then
  bad "compile produced errors"
  javac --release 11 -nowarn -d out -cp "lib/*" @sources.txt 2>&1 | grep -v '^Note:' | head -10 | sed 's/^/         /'
  exit 1
fi
ok "$(wc -l < sources.txt) source files compiled"

# ---------------------------------------------------------------------------- 2. machine
step "2. Machine policy (no work run)"
java -cp "$CP" core.data.exp.ServerProfile | sed 's/^/  /'
echo
echo "  ^ CHECK BY EYE: physical cores DETECTED (not ASSUMED), and the worker ceiling."
echo "    If it says ASSUMED, pass -Dv6.maxThreads=N to every later command."

# ---------------------------------------------------------------------------- 3. portfolio + split
step "3. Portfolio and the frozen split"
rm -rf "$OUT"
run_check "build a 6+6 toy portfolio" "" \
  java -Xmx4g -cp "$CP" core.data.exp.BuildV6Portfolio "$OUT/_portfolio" 4 6 6
run_check "freeze the development/holdout split" "DEV:" \
  java -cp "$CP" core.data.exp.PortfolioSplit "$OUT/_portfolio"
if [ -f "$OUT/_portfolio/_split_dev.txt" ] && [ -f "$OUT/_portfolio/_split_holdout.txt" ]; then
  ok "both split files written"
  grep -h '^# ' "$OUT/_portfolio/_split_dev.txt" | sed 's/^/         /'
else
  bad "split files missing"
fi

# ---------------------------------------------------------------------------- 4. the gates
step "4. Equivalence gates (the backbone of every cross-stage comparison)"
run_check "Stage 1: Baseline is bit-identical to plain EMCTS" "PASS" \
  java -Xmx4g -Dab.allowUnprofiled=true -cp "$CP" core.data.exp.Stage1Runner verify
run_check "Stage 0: instrumentation is behaviourally inert" "PASS" \
  java -Xmx4g -cp "$CP" core.data.exp.Stage0SignalRunner verify 20000 10
run_check "selection fix: the tree still branches" "PASS" \
  java -Xmx4g -cp "$CP" core.data.exp.SelectionFixCheck

# ---------------------------------------------------------------------------- 5. selection arms
step "5. Selection policies are what the design says they are"
run_check "LEGACY==COMMA, LEGACY_k1==DRIFT_k1, branching structure" "VERDICT: PASS" \
  java -Xmx4g -cp "$CP" core.data.exp.SelectionStructureCheck 6000 6

# ---------------------------------------------------------------------------- 6. calibration path
step "6. Calibration path (toy: 1 level, 2 reps — the VERDICT here means nothing)"
# The VERDICT here is EXPECTED to read FAIL: at these toy sizes the cells fall under MIN_OPS and the
# calibrator correctly refuses to write weights it does not trust. What is checked is that the path
# runs and records its measurements, which it does either way.
run_check "a3 suite enumerates and measures its cells" "AB_Z0_Fine@40000" \
  java -Xmx6g -cp "$CP" core.data.exp.ComputeCalibrateV6 \
       "$OUT/_portfolio" "$OUT/_calibration" 2 2 1 40000 --suite a3
run_check "loaded calibration harness runs and reports gate A3'" "GATE A3" \
  java -Xmx6g -cp "$CP" core.data.exp.ComputeCalibrateV6 \
       "$OUT/_portfolio" "$OUT/_calibration" 2 1 1 40000 --suite a3 --underLoad 4
if [ -f "$OUT/_calibration/compute_profiles.csv" ] \
   && [ -f "$OUT/_calibration/compute_profiles_load4.csv" ]; then
  ok "idle and loaded measurements written side by side (loaded did not clobber idle)"
else
  bad "expected both compute_profiles.csv and compute_profiles_load4.csv"
fi

# ---------------------------------------------------------------------------- 7. end to end
step "7. A real split run, end to end (stage S: 3 arms, 1 visit, 8k units)"
run_check "dev split runs and checkpoints" "DONE" \
  java -Xmx6g -Dab.allowUnprofiled=true -cp "$CP" \
       core.data.exp.Stage1Runner 4 "$OUT" dev S
if [ -f "$OUT/dev/_split.txt" ] && [ -s "$OUT/dev/_completed.txt" ]; then
  ok "split marker and checkpoint written: $(tr '\n' ' ' < "$OUT/dev/_completed.txt")"
else
  bad "expected $OUT/dev/_split.txt and a non-empty _completed.txt"
fi
run_check "holdout runs disjoint scenarios into its own tree" "DONE" \
  java -Xmx6g -Dab.allowUnprofiled=true -cp "$CP" \
       core.data.exp.Stage1Runner 4 "$OUT" holdout S
run_check "re-running dev skips the finished leaf" "SKIP (done)" \
  java -Xmx6g -Dab.allowUnprofiled=true -cp "$CP" \
       core.data.exp.Stage1Runner 4 "$OUT" dev S

# ---------------------------------------------------------------------------- 8. analysis path
step "8. Analysis path"
# Whichever interpreter actually has the dependencies. On some machines that is `python`, not
# `python3`, and a runbook that names the wrong one sends you looking for a problem you do not have.
PY_BIN=""
for cand in python3 python py; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import pandas, numpy' 2>/dev/null; then
    PY_BIN="$cand"; break
  fi
done
if [ -n "$PY_BIN" ]; then
  ok "'$PY_BIN' has pandas and numpy — use it for the analyses"
  if "$PY_BIN" analysis/analyze_stage1.py "$OUT/dev" --outdir "$OUT/results" > /dev/null 2>&1; then
    ok "analyze_stage1.py runs (tables in $OUT/results/tables)"
  else
    bad "analyze_stage1.py failed under '$PY_BIN' — run it by hand to see why"
  fi
else
  bad "no python with pandas+numpy on PATH (tried python3, python, py) — the analyses will not run"
fi

# ---------------------------------------------------------------------------- summary
printf '\n############################################################\n'
if [ "$FAIL" -eq 0 ]; then
  printf '# SHAKEDOWN PASSED (%d checks). The machine is ready for Stage A.\n' "$PASS"
  printf '# Next: analysis/AB_EMCTS_RUNBOOK.md, step A1.\n'
else
  printf '# SHAKEDOWN FAILED: %d of %d checks. Do NOT start Stage A.\n' "$FAIL" "$((PASS+FAIL))"
fi
printf '# Scratch output is in %s and can be deleted.\n' "$OUT"
printf '############################################################\n'
exit "$FAIL"
