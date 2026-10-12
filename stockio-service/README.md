# Logistics MCP server

The Kotlin Spring Boot MCP server processes a lot-level stock ledger, allocates structured demand against persisted live stock and inbound shipments, stores allocation runs and open backorders in PostgreSQL, and generates Excel workbooks with native pivot tables and formula-based summaries.

## Run

Requirements: JDK 21 and PostgreSQL (or Docker Compose for a local database). Start the included PostgreSQL service and configure the server connection with:

```shell
export DB_URL=jdbc:postgresql://localhost:5436/logistics
export DB_USERNAME=logistics
export DB_PASSWORD=your-password
export REPORT_DIR="$HOME/itzitzo-mcp/reports"
docker compose up -d --wait postgres
./gradlew :stock-service:bootJar
java -jar stockio-service/build/libs/stock-service-0.0.1-SNAPSHOT.jar
```

The server exposes both MCP STDIO and a local REST API. When using MCP, launch the JAR from an MCP client and keep protocol messages on stdout; application logs are sent to stderr. The REST API listens on `127.0.0.1:8080` by default and can also be used when the JAR is launched directly. Set `SERVER_ADDRESS` and `SERVER_PORT` to override the REST bind address and port.

## Tools

| Tool | Purpose |
| --- | --- |
| `calculate_allocation` | Allocate new orders by priority and FIFO. Eligible inbound supply can be used only if it arrives by the order's required delivery date. Unfilled quantities are saved as open backorders. |
| `fulfill_open_backorders` | Fulfill persisted backorders by priority/FIFO using current inventory and inbound shipments arrived by both `asOfDate` and each order's required delivery date. |
| `process_daily_stock` | Synchronize today's allocation-generated lot snapshot to live stock and turn arrived inbound reservations into stock reservations. |
| `generate_excel_report` | Write a workbook with allocation details, source allocations, native pivot tables, and a formula-based SKU/status summary under `~/itzitzo-mcp/reports` by default. Set `REPORT_DIR` to override the output directory. |

## REST API

`POST /api/logistics/allocate-and-sync` accepts the same JSON payload as `calculate_allocation`, then synchronizes today's saved snapshot. The request's `asOfDate` must be today's date. The response contains both `allocationRun` and `stockSync`; the operation runs in one transaction, so a failed sync also rolls back the allocation.

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
curl -X POST http://127.0.0.1:8080/api/logistics/allocate-and-sync \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "11111111-1111-1111-1111-111111111111",
    "asOfDate": "YYYY-MM-DD",
    "demandOrders": [{
      "orderId": "ORDER-1",
      "customer": "Example Customer",
      "sku": "MAKI-ZALM",
      "requestedQuantity": 10,
      "priority": 1,
      "placedDate": "YYYY-MM-DD",
      "requiredDeliveryDate": "YYYY-MM-DD"
    }]
  }'
```

Both allocation tools accept structured JSON payloads. Each request must include a UUID `requestId`; retrying the same ID and payload returns the original run, while reusing that ID with a different payload or operation is rejected. Order IDs already processed as new demand cannot be submitted again; use the backorder tool for remaining quantities.

`AllocationCsvLoader` converts the project CSV into an `AllocationRequest`:

```kotlin
val request = allocationCsvLoader.load(Path.of("docs/allocations.csv"))
```

It maps `Verkoop` rows to demand and `Levering` rows to inbound shipments, resolving product titles through `ProductRepository`; other transaction types are ignored. IDs and customer names are generated anew on each load.

`calculate_allocation` and `fulfill_open_backorders` read persisted lot-level stock, reserve allocated stock, and lock stock rows while allocating so concurrent requests cannot reserve the same quantity. Each allocation run rebuilds daily aggregate and lot-level snapshots from today forward. Allocated units reduce physical closing stock on their required delivery date; backordered quantities contribute to shortage but do not reduce physical stock. Previously registered, unreceived shipments remain available to later allocation runs.

Call `process_daily_stock` after calculating today's projections to synchronize today's saved lot balances to live stock. It accepts only `snapshotDate` (which must be today), does not recalculate movements, removes reservations that leave stock today, and converts arrived inbound reservations into live stock reservations. The operation is safe to retry. Allocation runs, shipment supply, reservations, daily snapshots, and open backorders are persisted in PostgreSQL; Flyway applies schema migrations on startup.

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
