#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PORT="${PORT:-8099}"

echo "Serving repository root at http://127.0.0.1:${PORT}"
echo "Open Swagger UI at:"
echo "  http://127.0.0.1:${PORT}/stockio-test-harness/performance/swagger/allocation-swagger.html"

cd "${REPO_ROOT}"
if command -v python3 >/dev/null 2>&1; then
  exec python3 -m http.server "${PORT}" --bind 127.0.0.1
fi
exec python -m http.server "${PORT}" --bind 127.0.0.1
