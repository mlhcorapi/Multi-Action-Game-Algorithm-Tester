#!/usr/bin/env bash
# Stage 1 (biased mutation) pipeline driver. Works in git-bash on Windows and on Linux.
#
#   ./run_stage1.sh build              compile the project into out/
#   ./run_stage1.sh verify             Baseline == plain EMCTS equivalence gate (builds a tiny
#                                      smoke portfolio on first use; ~3-5 min first time)
#   ./run_stage1.sh smoke              tiny end-to-end run + analysis (~5-10 min first time)
#   ./run_stage1.sh run [threads]      THE 12-HOUR RUN (default 4 threads). Resumable; Ctrl-C safe.
#                                      First run builds the 60-scenario portfolio (~0.5-1 h).
#   ./run_stage1.sh analyze [dir]      offline analysis -> analysis/stage1_results
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
  verify)  java $JAVA_OPTS -cp "$CP" core.data.exp.Stage1Runner verify ;;
  smoke)   java $JAVA_OPTS -cp "$CP" core.data.exp.Stage1Runner smoke
           python analysis/analyze_stage1.py test_results/stage1_smoke --outdir analysis/stage1_smoke_results ;;
  run)     java $JAVA_OPTS -cp "$CP" core.data.exp.Stage1Runner "${2:-4}" ;;
  analyze) python analysis/analyze_stage1.py "${2:-test_results/stage1}" --outdir analysis/stage1_results ;;
  *) echo "usage: $0 build|verify|smoke|run|analyze"; exit 1 ;;
esac
