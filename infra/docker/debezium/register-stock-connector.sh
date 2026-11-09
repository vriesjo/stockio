#!/bin/sh
set -eu
dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
curl --fail-with-body -sS -X POST http://localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  --data @"$dir/stock-connector.json"
echo
