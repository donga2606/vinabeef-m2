#!/usr/bin/env bash
# Dev runner for a Spring Boot app inside the Maven container.
# Runs `spring-boot:run` (devtools active) and recompiles when files under src/main change,
# so devtools restarts the app without rebuilding any image.
# Usage: run-with-reload.sh <module path relative to backend/, e.g. apps/herd-health>
set -euo pipefail
MODULE="$1"
cd /workspace/backend

# Build the module together with its in-repo dependencies (e.g. libs/access-policy) and put them in
# the shared m2 cache. With -pl alone Maven looks for sibling SNAPSHOT jars in ~/.m2, which is empty
# on a fresh m2-cache volume. Tests are run by `dev mvn verify` / CI, not by this dev runner.
mvn -B -q -pl "$MODULE" -am install -DskipTests

snapshot() { find "$MODULE/src/main" -type f -exec stat -c '%n %Y %s' {} + 2>/dev/null | sort | md5sum; }

(
  last="$(snapshot)"
  while sleep 2; do
    now="$(snapshot)"
    if [[ "$now" != "$last" ]]; then
      last="$now"
      echo "[reload] change detected in $MODULE/src/main - recompiling"
      mvn -B -q -o -pl "$MODULE" -am compile || echo "[reload] compile failed - app keeps running old classes"
    fi
  done
) &

exec mvn -B -pl "$MODULE" spring-boot:run
