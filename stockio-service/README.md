# Logistics MCP server

The Kotlin Spring Boot MCP server processes a lot-level stock ledger, allocates structured demand against persisted live stock and inbound shipments, stores allocation runs and open backorders in PostgreSQL, and generates Excel workbooks with native pivot tables and formula-based summaries.

## Run

Requirements: JDK 21 and PostgreSQL (or Docker Compose for a local database). Start the included PostgreSQL service and configure the server connection with:

```shell
export DB_URL=jdbc:postgresql://localhost:5437/logistics
export DB_USERNAME=logistics
export DB_PASSWORD=your-password
export REPORT_DIR="$HOME/itzitzo-mcp/reports"
docker compose up -d --wait postgres
./gradlew :stock-service:bootJar
java -jar stockio-service/build/libs/stock-service-0.0.1-SNAPSHOT.jar
```

The server exposes both MCP STDIO and a local REST API. When using MCP, launch the JAR from an MCP client and keep protocol messages on stdout; application logs are sent to stderr. The REST API listens on `127.0.0.1:8080` by default and can also be used when the JAR is launched directly. Set `SERVER_ADDRESS` and `SERVER_PORT` to override the REST bind address and port.

The `stockio-api` MCP client starts its own service subprocess with a random HTTP
port and the JobRunr dashboard disabled, avoiding a conflict with another
service's dashboard on port `8181`. After changing the client launch
configuration, restart `stockio-api` so it starts a new subprocess.

### Local embedding model

From the repository root, `docker compose -f infra/docker/docker-compose.yml up -d`
starts Ollama and a one-shot `ollama-pull-model` service. The initializer waits
until Ollama is healthy, then pulls `nomic-embed-text` through its internal
HTTP endpoint. The model is stored in the existing `ollama-data` volume, so
container restarts do not require downloading the existing model files again.
The initializer exiting with code `0` is expected; Ollama remains running.

Use `make up-ollama` to start only Ollama and wait for the model pull to finish.
To inspect the model list, run:

```shell
docker compose -f infra/docker/docker-compose.yml exec ollama ollama list
```

Ollama's health check reports server readiness, not completion of the model
download. With a detached full-stack startup, check
`docker compose -f infra/docker/docker-compose.yml logs ollama-pull-model`
for the pull result before using equivalent-product matching.

## Tools

| Tool | Purpose |
| --- | --- |
| `allocation_and_stock_projection` | Allocate new orders by priority/FIFO and refresh forecasts without changing physical stock. Eligible inbound supply must arrive by the delivery deadline; unfilled quantities become backorders. |
| `allocate_open_backorders` | Fulfill persisted backorders by priority/FIFO using eligible inventory and arrived inbound supply, then refresh forecasts without synchronizing stock. |
| `process_stock_ledger` | Manually expire overdue holds, rebuild today's forecast, and apply due receipts/confirmed deliveries to live stock in one transaction. |
| `generate_excel_report` | Write a workbook with allocation details, source allocations, native pivot tables, and a formula-based SKU/status summary under `~/itzitzo-mcp/reports` by default. Set `REPORT_DIR` to override the output directory. |

## REST API

`POST /api/logistics/allocation-jobs` accepts the same JSON payload as
`allocation_and_stock_projection`. The request's `asOfDate` must be today in the
configured business zone. It returns `202 Accepted` with a `jobId` and a `Location`
header pointing to `GET /api/logistics/allocation-jobs/{jobId}`. A successful job
returns `{ "status": "SUCCEEDED", "result": { "version": 2, "allocationRun": ... } }`.
Allocation refreshes forecasts but does not receive supply or deliver orders.
There is no `stockSync` result attached to allocation completion.

Physical synchronization is independent: `StockLedgerSyncJob` runs at midnight
and noon UTC by default. Configure `ALLOCATION_LEDGER_SYNC_CRON`,
`ALLOCATION_LEDGER_SYNC_ZONE`, and `ALLOCATION_LEDGER_SYNC_ENABLED`; disabling it
removes the recurring schedule but does not cancel already queued/running work.
The zone also configures the shared business clock. Manual synchronization remains
available through `process_stock_ledger`.

`GET /api/logistics/stock-ledger/status` exposes the last committed synchronization
and its freshness deadline. Actuator's `stockLedger` health component becomes
`DOWN` after the next expected cron occurrence plus
`ALLOCATION_LEDGER_SYNC_FRESHNESS_TOLERANCE` (default `1h`) is missed. Configure
operational monitoring against health/status to alert on stale stock processing.
With automatic scheduling disabled, that component reports `UNKNOWN`.

Migration V16 adds ledger-success monitoring. Recreate the database, including
JobRunr storage, and update callers together: the old allocate-and-sync routes
and combined result contract are retired. Job completion is serialized directly
with `ObjectMapper`; no historical combined-result compatibility adapter is kept.

The admin endpoint `POST /api/admin/allocations/load-csv` loads the configured CSV and returns the generated `AllocationRequest`. It reads `docs/allocations.csv` by default; set `ALLOCATION_CSV_PATH` to use another server-side file. The endpoint does not run allocation.

```shell
curl -X POST http://127.0.0.1:8080/api/admin/allocations/load-csv
```

`POST /api/admin/reports/excel` invokes the same report-generation method as the MCP tool and returns the generated workbook path:

```shell
curl -X POST http://127.0.0.1:8080/api/admin/reports/excel \
  -H 'Content-Type: application/json' \
  -d '{
    "runId": "11111111-1111-1111-1111-111111111111",
    "fileName": "allocation_report.xlsx"
  }'
```

```shell
curl -X POST http://127.0.0.1:8080/api/logistics/allocation-jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "11111111-1111-1111-1111-111111111111",
    "asOfDate": "YYYY-MM-DD",
    "runType": "NEW_DEMAND",
    "demandOrders": [{
      "orderId": "ORDER-1",
      "customer": "Example Customer",
      "priority": 1,
      "placedDate": "YYYY-MM-DD",
      "lines": [{
        "orderLineId": "ORDER-1-LINE-1",
        "sku": "MAKI-ZALM",
        "requestedQuantity": 10,
        "requiredDeliveryDate": "YYYY-MM-DD"
      }]
    }]
  }'
```

Both allocation tools accept structured JSON payloads. Each request must include a UUID `requestId`; retrying the same ID and payload returns the original run, while reusing that ID with a different payload or operation is rejected. Order IDs already processed as new demand cannot be submitted again; use the backorder tool for remaining quantities.

`AllocationCsvLoader` converts the project CSV into an `AllocationRequest`:

```kotlin
val request = allocationCsvLoader.load(Path.of("docs/allocations.csv"))
```

It maps `Verkoop` rows to demand and `Levering` rows to inbound shipments, resolving product titles through `ProductRepository`; other transaction types are ignored. IDs and customer names are generated anew on each load.

`allocation_and_stock_projection` and `allocate_open_backorders` reserve persisted
supply and rebuild aggregate/lot forecasts. Orders are unconfirmed by default and
their holds expire unless confirmed; only confirmed orders project outgoing
deliveries and shortages. Unreceived persisted shipments remain available to later
allocation runs.

The recurring ledger job, or manual `process_stock_ledger`, independently applies
today's movements. It first cleans up expiry and rebuilds its projection under the
same exclusive lock and transaction rather than trusting a saved forecast. Due
confirmed deliveries are consumed; arrived inbound holds become stock holds when
still pending or needed for future delivery. Receipt/delivery state makes retries
safe. PostgreSQL persists all operational data, forecasts, and sync status; Flyway
applies schema migrations on startup.

### Example `calculate_allocation` prompt + input:

```
Use  itzitzo-mcp-server ’s  calculate_allocation  tool to allocate the structured demand below against persisted live stock and the supplied inbound shipments. Use only the data I provide—do not invent or estimate IDs, quantities, priorities, or dates. If required information is missing or ambiguous, ask me before calling the tool.

Apply these rules:

- Lower priority numbers are higher priority; within a priority, process orders FIFO by  placedDate .
- Consume unreserved persisted inventory FIFO by  receivedDate , then  lotId .
- Use inbound shipments only when  expectedArrivalDate  is on or before that order’s  requiredDeliveryDate .
- Allow partial allocations and leave any unfilled quantity as an open backorder.
- Do not include inventory quantities in this request; the tool reads and reserves live stock from PostgreSQL.

- Call  calculate_allocation  with this structured payload. Generate a new UUID for  requestId :
```

```json
{
  "requestId": "11111111-1111-1111-1111-111111111111",
  "asOfDate": "2026-10-08",
  "demandOrders": [
    {
      "orderId": "ORDER-1",
      "customer": "Example Customer",
      "sku": "MAKI-ZALM",
      "requestedQuantity": 100,
      "priority": 1,
      "placedDate": "2026-10-01",
      "requiredDeliveryDate": "2026-10-20"
    }
  ],
  "inboundShipments": [
    {
      "shipmentId": "SHIP-1",
      "sku": "MAKI-ZALM",
      "expectedQuantity": 25,
      "expectedArrivalDate": "2026-10-15"
    }
  ]
}
```
### Example `fulfill_open_backorders` prompt + input:

```
Use  itzitzo-mcp-server ’s  fulfill_open_backorders  tool to fulfill persisted open backorders using persisted live stock and the inbound-supply data below. Don’t invent or estimate quantities, dates, or IDs. If the supply data or  asOfDate  is missing or unclear, ask me before calling the tool.
Process backorders by priority, then FIFO. Use inbound shipments only if they have arrived by  asOfDate  and their  expectedArrivalDate  is on or before the order’s  requiredDeliveryDate . Partially fulfill orders when possible and leave any remainder open.

After the tool returns, summarize which backorders were fulfilled or partially fulfilled, the sources and quantities used, and any quantities still open. Use the same  requestId  only to retry the identical request; use a new UUID when the available-supply snapshot changes.

Call the tool with this payload, using a new UUID for this fulfillment attempt:
```

```json
{
  "requestId": "[new UUID]",
  "asOfDate": "YYYY-MM-DD",
  "inboundShipments": [
    {
      "shipmentId": "[shipment ID]",
      "sku": "[SKU]",
      "expectedQuantity": 25,
      "expectedArrivalDate": "YYYY-MM-DD"
    }
  ]
}
```

### Example `process_daily_stock` input:

Synchronize the saved lot-level projection for today after allocation has rebuilt the daily snapshots.

```json
{
  "snapshotDate": "YYYY-MM-DD"
}
```

### Example `generate_excel_report` prompt + input:

```
Use  itzitzo-mcp-server ’s  generate_excel_report  tool to create an Excel report from the persisted allocation run below. Use the exact  runId  returned by  calculate_allocation  or  fulfill_open_backorders ; don’t invent or modify it. If I haven’t provided a run ID, ask me for one before calling the tool.
Save the report as  allocation_report.xlsx . The tool writes it under  ~/itzitzo-mcp/reports  by default and creates allocation details, source allocations, native Excel pivot tables, and formula-based summaries. Use only a simple  .xlsx  filename—don’t provide a directory path.

After the tool returns, tell me the exact path it returned.

Call the tool with:
```

```json
{
  "runId": "[persisted run UUID]",
  "fileName": "allocation_report.xlsx"
}
```

## Test

The PostgreSQL integration test uses Testcontainers and requires Docker:

```shell
./gradlew :stock-service:test
```
