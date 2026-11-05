# Allocation performance harness

This harness uses **Gatling's built-in async HTTP client via the DSL** to test
allocation job endpoints. It does **not** generate or run a separate webclient.

Contract source of truth:

- `stockio-api/src/main/resources/openapi/allocation-controller.yaml`
- `POST /api/allocation/jobs`
- `GET /api/allocation/jobs/{jobId}`

## Scope and safety

- Run only against **dedicated non-production** environments.
- Local results are environment-specific and are **not** production guarantees.
- Authentication is optional in this baseline harness and controlled by config.

## Run

From repository root:

```bash
./gradlew :stockio-test-harness:performanceRun --no-daemon
```

Gatling HTML reports are written to:

`stockio-test-harness/build/reports/gatling`

## Configuration

Set as JVM system properties (`-D...`) or environment variables.

| Purpose | System property | Environment variable | Default |
| --- | --- | --- | --- |
| Protocol | `stockio.perf.protocol` | `STOCKIO_PERF_PROTOCOL` | `http` |
| Host | `stockio.perf.host` | `STOCKIO_PERF_HOST` | `127.0.0.1` |
| Port | `stockio.perf.port` | `STOCKIO_PERF_PORT` | `9090` |
| `async` query for submit | `stockio.perf.async` | `STOCKIO_PERF_ASYNC` | `true` |
| Optional bearer token | `stockio.perf.authToken` | `STOCKIO_PERF_AUTH_TOKEN` | unset |
| Warm-up users/sec | `stockio.perf.warmupRps` | `STOCKIO_PERF_WARMUP_RPS` | `5` |
| Warm-up seconds | `stockio.perf.warmupSeconds` | `STOCKIO_PERF_WARMUP_SECONDS` | `30` |
| Ramp start users/sec | `stockio.perf.rampStartRps` | `STOCKIO_PERF_RAMP_START_RPS` | `10` |
| Ramp end users/sec | `stockio.perf.rampEndRps` | `STOCKIO_PERF_RAMP_END_RPS` | `100` |
| Ramp seconds | `stockio.perf.rampSeconds` | `STOCKIO_PERF_RAMP_SECONDS` | `120` |
| Soak users/sec | `stockio.perf.soakRps` | `STOCKIO_PERF_SOAK_RPS` | `100` |
| Soak seconds | `stockio.perf.soakSeconds` | `STOCKIO_PERF_SOAK_SECONDS` | `300` |
| P95 threshold (ms) | `stockio.perf.sla.p95Millis` | `STOCKIO_PERF_SLA_P95_MS` | `300` |
| P99 threshold (ms) | `stockio.perf.sla.p99Millis` | `STOCKIO_PERF_SLA_P99_MS` | `800` |
| Success rate threshold (%) | `stockio.perf.sla.successPct` | `STOCKIO_PERF_SLA_SUCCESS_PCT` | `99` |

Example:

```bash
./gradlew :stockio-test-harness:performanceRun --no-daemon \
  -Dstockio.perf.host=127.0.0.1 \
  -Dstockio.perf.port=9090 \
  -Dstockio.perf.soakRps=40 \
  -Dstockio.perf.soakSeconds=120
  -Dstockio.perf.async=false
```

## What the simulation does

1. Feeds varied request data from `performance/src/main/resources/feeders/allocation-demand.csv`.
2. Submits `POST /api/allocation/jobs` with OpenAPI-aligned payload fields.
3. Extracts `jobId` and immediately calls `GET /api/allocation/jobs/{jobId}`.
4. Applies global assertions for P95, P99, and success percentage.
