#!/usr/bin/env bash
# Stage 0 (mutation-signal) pipeline driver. Works in git-bash on Windows and on Linux.
#
#   ./run_stage0.sh build              compile the project into out/
#   ./run_stage0.sh verify             instrumented-vs-plain equivalence check (~1-2 min)
#   ./run_stage0.sh smoke              tiny end-to-end run -> test_results/stage0_smoke (~1 min)
#   ./run_stage0.sh run [args...]      the data collection -> test_results/stage0
#                                      args: [threads] [cuCsv] [gamesPerBudget] [opponent] [turnLimit] [outDir]
#                                      defaults: 4  10000,40000,160000,640000  4  emcts  50  test_results/stage0
#   ./run_stage0.sh analyze [dir]      offline analysis -> analysis/stage0_results
#   ./run_stage0.sh all                build + verify + run + analyze
#
# The run is resumable: re-issue the same command and finished (budget, game) cells are skipped.
# Ctrl-C is a cooperative stop (running games finish and write).
set -e
cd "$(dirname "$0")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';';;
  *) SEP=':';;
esac
CP="out${SEP}lib/*"
JAVA_OPTS="${JAVA_OPTS:--Xmx12g}"

build() {
  find src -name '*.java' > _srcs.txt
  javac --release 11 -nowarn -cp "lib/*" -d out @_srcs.txt
  echo "BUILD OK ($(wc -l < _srcs.txt) sources)"
}

case "${1:-}" in
  build)   build ;;
  verify)  java $JAVA_OPTS -cp "$CP" core.data.exp.Stage0SignalRunner verify "${2:-8000}" "${3:-10}" ;;
  smoke)   java $JAVA_OPTS -cp "$CP" core.data.exp.Stage0SignalRunner smoke
           python analysis/analyze_stage0.py test_results/stage0_smoke --outdir analysis/stage0_smoke_results ;;
  run)     shift; java $JAVA_OPTS -cp "$CP" core.data.exp.Stage0SignalRunner "$@" ;;
  analyze) python analysis/analyze_stage0.py "${2:-test_results/stage0}" --outdir analysis/stage0_results ;;
  all)     build
           java $JAVA_OPTS -cp "$CP" core.data.exp.Stage0SignalRunner verify
           java $JAVA_OPTS -cp "$CP" core.data.exp.Stage0SignalRunner
           python analysis/analyze_stage0.py test_results/stage0 --outdir analysis/stage0_results ;;
  *) echo "usage: $0 build|verify|smoke|run|analyze|all"; exit 1 ;;
esac
