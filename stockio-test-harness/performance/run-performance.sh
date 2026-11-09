#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${REPO_ROOT}"

if [[ $# -eq 0 ]]; then
  ./gradlew :stockio-test-harness:performanceRun --no-daemon \
    -Dstockio.perf.host=localhost \
    -Dstockio.perf.port=9090 \
    -Dstockio.perf.async=true \
    -Dstockio.perf.warmupSeconds=10 \
    -Dstockio.perf.rampSeconds=10 \
    -Dstockio.perf.soakRps=10 \
    -Dstockio.perf.soakSeconds=60
else
  ./gradlew :stockio-test-harness:performanceRun --no-daemon "$@"
fi
