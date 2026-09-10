#!/bin/sh

# Diagnostic: is maven.scijava.org actually serving artifacts to this runner?
# ci-build.sh starts with mavenEvaluate(), which runs Maven with -q and captures
# its output via $(...) -- so when SciJava is down, Maven retries silently and the
# job hangs for tens of minutes with an empty log. Probe first so the verdict is
# visible. Informational only: never fails the build.
echo '== SciJava reachability =='
curl -sS -o /dev/null --max-time 30 \
  -w 'maven.scijava.org: HTTP %{http_code}  connect=%{time_connect}s  total=%{time_total}s\n' \
  https://maven.scijava.org/content/groups/public/org/scijava/pom-scijava/maven-metadata.xml \
  || echo "maven.scijava.org: UNREACHABLE (curl exit $?)"

curl -fsLO https://raw.githubusercontent.com/scijava/scijava-scripts/main/ci-build.sh
sh ci-build.sh
