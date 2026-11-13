#!/bin/bash
# Builds view-intensities.jar (IntensityCorrectionViewer + ClosestViewSource, compiled against a BigStitcher-Spark fat jar) and a
# view-intensities launcher into OUT_DIR. Copy both next to the fat jar on the cluster and run
#   ./view-intensities -x dataset.xml -i coefficients.n5
#
# The fat jar's shade step drops the scijava plugin index (META-INF/json/org.scijava.plugin.Plugin), without which
# BigDataViewer cannot start ("No compatible service: org.scijava.plugin.PluginService"). The add-on jar carries an
# index rebuilt from this project's dependency jars, restricted to classes that exist in the fat jar.
#
# usage: tools/build-view-intensities.sh FAT_JAR OUT_DIR
set -euo pipefail

FAT_JAR=$1
OUT_DIR=$2
REPO=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/classes/META-INF/json" "$OUT_DIR"
(cd "$REPO" && mvn -q -o dependency:build-classpath -Dmdep.outputFile="$TMP/cp.txt" >/dev/null)

javac -proc:none --release 21 -cp "$FAT_JAR" -d "$TMP/classes" \
	"$REPO/src/main/java/net/preibisch/mvrecon/process/fusion/intensity/IntensityCorrectionViewer.java" \
	"$REPO/src/main/java/net/preibisch/mvrecon/process/fusion/intensity/ClosestViewSource.java"

python3 -I - "$FAT_JAR" "$TMP/cp.txt" "$TMP/classes/META-INF/json/org.scijava.plugin.Plugin" <<'EOF'
import json, sys, zipfile
fat, cp, out = sys.argv[1:]
present = set(zipfile.ZipFile(fat).namelist())
entries, seen = [], set()
for jar in open(cp).read().strip().split(':'):
    try:
        text = zipfile.ZipFile(jar).read('META-INF/json/org.scijava.plugin.Plugin').decode()
    except (KeyError, zipfile.BadZipFile, FileNotFoundError, IsADirectoryError):
        continue
    dec, i = json.JSONDecoder(), 0
    while i < len(text):
        start = i
        obj, i = dec.raw_decode(text, i)
        cls = obj['class']
        if cls not in seen and cls.replace('.', '/') + '.class' in present:
            seen.add(cls)
            entries.append(text[start:i])  # verbatim: scijava's parser rejects Python's float formatting (1e+300)
open(out, 'w').write(''.join(entries))
print(f'scijava plugin index: {len(entries)} plugins')
EOF

jar cf "$OUT_DIR/view-intensities.jar" -C "$TMP/classes" .

cat > "$OUT_DIR/view-intensities" <<EOF
#!/bin/bash
# BigDataViewer with on-the-fly intensity correction, press C to toggle. Needs a display (NoMachine or bsub -Is -XF).
# Override the fat jar with BSS_JAR, the heap with JAVA_OPTS.
DIR=\$(dirname "\$(readlink -f "\$0")")
JAVA=\${JAVA_HOME:-/misc/sc/jdks/zulu21.50.19-ca-jdk21.0.11-linux_x64}/bin/java
exec "\$JAVA" \${JAVA_OPTS:--Xmx32g} -cp "\$DIR/view-intensities.jar:\${BSS_JAR:-\$DIR/$(basename "$FAT_JAR")}" \\
	net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionViewer "\$@"
EOF
chmod +x "$OUT_DIR/view-intensities"
echo "built $OUT_DIR/view-intensities.jar and $OUT_DIR/view-intensities"
