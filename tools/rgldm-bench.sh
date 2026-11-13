#!/usr/bin/env bash
# RGLDM matching benchmark: flat KD-tree vs blocked brute force over point count x search radius.
# Not part of the library; runs tools/RGLDMBench.java in java source-file mode against the fat jar.
#
#   tools/rgldm-bench.sh --build                     # once, needs maven; produces target/multiview-reconstruction-*.jar
#   tools/rgldm-bench.sh --xml /path/dataset.xml --label beads --setups 124,125 --out bench-$(hostname).tsv
#
# Options (defaults): --sizes 1000,2000,5000,10000,20000,all  --radii 0,50,100,250,500,1000,2000,5000
#   --neighbors 3 (a list sweeps neighbors, e.g. --sizes all --neighbors 3,4,5,6) --redundancy 1 --ratio 3 --repeats 3 --maxSeconds 120 --overlapOnly true --legacy false
# Copy the jar and this directory to the cluster; only a JDK 21 is needed there (JAVA_HOME=/path/to/jdk-21 if the default java is older).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--build" ]]; then
  mvn -P fatjar clean package -Denforcer.skip=true -DskipTests
  ls -la target/multiview-reconstruction-*.jar
  exit 0
fi

JAR="${RGLDM_JAR:-$(ls -t target/multiview-reconstruction-*.jar 2>/dev/null | grep -v sources | grep -v tests | head -1 || true)}"
[[ -n "$JAR" && -f "$JAR" ]] || { echo "no fat jar found; run $0 --build or set RGLDM_JAR" >&2; exit 1; }

# needs a JDK >= 21 (the jar is compiled for it); set JAVA_HOME if the default java is older
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
VER="$( { "$JAVA" -version 2>&1 || true; } | head -1 | sed -nE 's/.*"([0-9]+)[."].*/\1/p')"
if [[ "${VER:-0}" -lt 21 ]]; then
  echo "found java $VER at $JAVA; need 21 or newer (class file version 65). Point JAVA_HOME at a JDK 21, e.g." >&2
  echo "  JAVA_HOME=/path/to/jdk-21 $0 $*" >&2
  exit 1
fi

# cores: the matcher itself is single-threaded; this pins the JVM (GC, loading pool, the "cores" column) to the
# scheduler allocation instead of the whole node. RGLDM_CORES overrides; LSF and Slurm variables are picked up automatically.
CORES="${RGLDM_CORES:-${LSB_DJOB_NUMPROC:-${SLURM_CPUS_PER_TASK:-${SLURM_CPUS_ON_NODE:-}}}}"
CORE_OPT=""
[[ -n "$CORES" ]] && CORE_OPT="-XX:ActiveProcessorCount=$CORES" && echo "pinning JVM to $CORES cores"

# shellcheck disable=SC2086  # CORE_OPT is a single option or empty
exec "$JAVA" -Xmx"${RGLDM_XMX:-16g}" $CORE_OPT -Djava.awt.headless=true -cp "$JAR" tools/RGLDMBench.java "$@"
