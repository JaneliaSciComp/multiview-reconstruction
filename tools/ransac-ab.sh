#!/usr/bin/env bash
# Multi-consensus RANSAC, fixed iterations vs adaptive stop, on identical candidates of every matched pair of one label.
# Runs tools/RansacAB.java in java source-file mode against the fat jar (needs a JDK 21; set JAVA_HOME if the default is older).
#
#   tools/ransac-ab.sh <dataset.xml> <label> <threads> [neighbors redundancy ratio radius]     defaults: 3 0 3 100
#   e.g. tools/ransac-ab.sh ../intensity_correct_ipstore_zarr3/dataset.xml beads 16 | tee tools/ransac-ab-$(hostname).log
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 3 ] || { sed -n '2,6p' "$0"; exit 1; }

JAR="${RGLDM_JAR:-$(ls -t target/multiview-reconstruction-*.jar 2>/dev/null | grep -v sources | grep -v tests | head -1 || true)}"
[[ -n "$JAR" && -f "$JAR" ]] || { echo "no fat jar found under target/; set RGLDM_JAR" >&2; exit 1; }

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
VER="$( { "$JAVA" -version 2>&1 || true; } | head -1 | sed -nE 's/.*"([0-9]+)[."].*/\1/p')"
[[ "${VER:-0}" -ge 21 ]] || { echo "found java $VER at $JAVA; need 21 or newer, e.g. JAVA_HOME=/path/to/jdk-21 $0 $*" >&2; exit 1; }

CORES="${RGLDM_CORES:-${LSB_DJOB_NUMPROC:-${SLURM_CPUS_PER_TASK:-}}}"
CORE_OPT=""; [[ -n "$CORES" ]] && CORE_OPT="-XX:ActiveProcessorCount=$CORES"

# shellcheck disable=SC2086
exec "$JAVA" -Xmx"${RGLDM_XMX:-32g}" $CORE_OPT -Djava.awt.headless=true -cp "$JAR" tools/RansacAB.java "$@"
