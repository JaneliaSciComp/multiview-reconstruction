#!/bin/bash
#
# A/B of the real `match` stage with different JVM options (matcher strategy, RANSAC variant), same jar, same zarr3
# interest point store, same Spark resources, run one after the other through bigstitcher-spark_pipeline.sh. Each mode
# works on its own copy of dataset.xml + interestpoints.zarr, so production data is never touched. Afterwards the
# correspondences of the first two modes are compared per view.
#
#   tools/match_benchmark.sh          # submits itself as a 1-core LSF controller job (survives ssh disconnect)
#   tools/match_benchmark.sh --local  # run the controller in this shell
#   tail -f <BENCH>/logs/match_benchmark-*.log
#
set -euo pipefail

# ============================== CONFIG ==============================
BENCH=/nrs/tavakoli/MirrorScope/20260831_ExpID99_BIS_0.03/rgldm-bench/match_ab            # one subdirectory per mode is created here
SRC=/nrs/tavakoli/MirrorScope/20260831_ExpID99_BIS_0.03/intensity_correct_ipstore_zarr3       # dataset with the zarr3 interest point store
STORE=interestpoints.zarr
JAR=/groups/scicompsoft/home/schweinfurthl/BigStitcher-Spark-0.1.0-SNAPSHOT-rgldm.jar          # BigStitcher-Spark built against the new mvrecon
MVR_JAR=/nrs/tavakoli/MirrorScope/20260831_ExpID99_BIS_0.03/rgldm-bench/target/multiview-reconstruction-9.0.14-SNAPSHOT.jar
PIPELINE=/groups/scicompsoft/home/schweinfurthl/Tavakoli/ExpID99/bigstitcher-spark_pipeline.sh                # the production pipeline script; its CONFIG and match args are reused
LABEL=beads
# modes: NAME:<one -D option passed to driver and executors> (unquoted through flintstone/spark-janelia, so no spaces).
# Directory match_ab/NAME per mode.
#   matcher A/B:  no JVM option anymore; append "--descriptorSearch LEGACY" / "AUTO" to ARGS_match in the pipeline copy instead
#   RANSAC A/B:   "FIXED:-Dransac.adaptive=false" "ADAPTIVE:-Dransac.adaptive=true"
MODES=("FIXED:-Dransac.adaptive=false" "ADAPTIVE:-Dransac.adaptive=true")
BILL_TO=tavakoli
RUNTIME=8:00
export JAVA_HOME=/misc/sc/jdks/zulu21.50.19-ca-jdk21.0.11-linux_x64
# ============================ END CONFIG ============================

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
LOG_DIR=$BENCH/logs; mkdir -p "$LOG_DIR"
log() { echo "[$(date '+%F %T')] match_benchmark: $*"; }

if [ "${1:-}" != "--local" ] && [ -z "${LSB_JOBID:-}" ]; then
  ctl_log=$LOG_DIR/match_benchmark-$(date +%Y%m%d_%H%M%S).log
  bsub -n1 -W "$RUNTIME" -P "$BILL_TO" -J match_benchmark -o "$ctl_log" "$SCRIPT_DIR/$(basename "$0") --local"
  echo "controller submitted; follow with: tail -f $ctl_log"
  exit 0
fi

[ -f "$PIPELINE" ] || { log "pipeline script not found: $PIPELINE"; exit 1; }
[ -f "$JAR" ] || { log "jar not found: $JAR"; exit 1; }
mkdir -p "$BENCH"; : > "$BENCH/times.txt"

for entry in "${MODES[@]}"; do
  mode=${entry%%:*}; opts=${entry#*:}
  dir=$BENCH/$mode
  log "preparing $dir from $SRC ($STORE) with $JAR"
  rm -rf "$dir"; mkdir -p "$dir"
  cp "$SRC/dataset.xml" "$dir/"
  cp -r "$SRC/$STORE" "$dir/"
  ln -s "$SRC/dataset.ome.zarr" "$dir/dataset.ome.zarr"     # match never reads pixels, but the XML names the container

  # a copy of the pipeline script pointing at this directory and jar; the search mode goes to driver and executors
  sed -e "s|^BASE=.*|BASE=$dir|" -e "s|^JAR=.*|JAR=$JAR|" \
      -e "s|^# ============================ END CONFIG.*|export SUBMIT_ARGS=\"--conf spark.executor.extraJavaOptions=$opts --conf spark.driver.extraJavaOptions=$opts\"\n&|" \
      "$PIPELINE" > "$dir/pipeline.sh"
  chmod +x "$dir/pipeline.sh"

  log "running match: $mode ($opts)"
  t0=$(date +%s)
  "$dir/pipeline.sh" --local --start match --stop match
  s=$(( $(date +%s) - t0 ))
  log "$mode finished in ${s}s (includes cluster queueing; see $dir/logs for the stage log and the driver log path)"
  echo "$mode $s" >> "$BENCH/times.txt"
done

echo; echo "==== wall time per mode (seconds, incl. queueing) ===="; cat "$BENCH/times.txt"
echo; echo "==== correspondences ===="
M0=${MODES[0]%%:*}; M1=${MODES[1]%%:*}
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp "$MVR_JAR" "$SCRIPT_DIR/CompareCorrespondences.java" "$BENCH/$M0/dataset.xml" "$BENCH/$M1/dataset.xml" "$LABEL"
