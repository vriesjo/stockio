# Introduction

This file documents the key usage scenarios and architectural flows supported by
**Stockio**, the logistics allocation service.

All scenarios use the following structure:
- Gherkin given/when/then functional scenario
- High-level technical analysis
- Acceptance criteria

Stockio allocates demand against persisted live inventory and eligible inbound
shipments, maintains reservations and backorders, refreshes stock projections,
and independently synchronizes the physical stock ledger.

## Scenario 1: Allocate a Product by Name

### Gherkin description
**Given** an order containing a product name or description, quantity, priority, customer, and valid dates \
**When** the client submits the request to `POST /api/products/allocate` \
**Then** Stockio resolves the product against the active catalog and allocates the resolved SKU against eligible inventory and inbound supply \
**And** if the product cannot be matched confidently, the response explains why and no allocation is made \
**And** any unfilled quantity is recorded as an open backorder

### High-level technical analysis
- REST endpoint: `POST /api/products/allocate`
- The Next.js frontend reaches this endpoint through its same-origin `/api/allocate` route using server-side Axios
- Controller: `ProductAllocationController`
- Service: `ProductAllocationService`
- Product resolution first checks exact active catalog titles, then local embeddings, and optionally Tavily evidence when equivalent matching is allowed
- A successful resolution creates a `NEW_DEMAND` allocation request and delegates to the shared allocation processing flow
- The response reports the selected product, candidates and sources when relevant, and the resulting allocation run

### Acceptance Criteria
- A valid exact product match returns `ALLOCATION_PROCESSED` with the selected SKU and allocation run
- Equivalent matching is used only when `allowEquivalent` is enabled and the match meets the configured confidence and ambiguity thresholds
- `NO_PRODUCT_MATCH`, `AMBIGUOUS_PRODUCT_MATCH`, `EMBEDDING_FAILED`, or `TAVILY_LOOKUP_FAILED` results do not create an allocation
- Successful allocation reports inventory allocations, inbound allocations, and any backordered quantity
- Invalid order, quantity, priority, or date data is rejected with a client error

## Scenario 2: Allocate Structured New Demand

### Gherkin description
**Given** a structured allocation request with a unique `requestId`, `asOfDate`, demand orders, and optional inbound shipments \
**When** the client submits `POST /api/allocation/jobs` with the default `async=true`, or invokes `allocation_and_stock_projection` \
**Then** Stockio accepts the request and processes it as `NEW_DEMAND` \
**And** orders are processed by ascending priority and FIFO `placedDate` \
**And** inventory is consumed FIFO by `receivedDate` and `lotId` \
**And** inbound supply is used only when its expected arrival is on or before the line's required delivery date \
**And** any remaining quantity becomes an open backorder

### High-level technical analysis
- REST endpoint: `POST /api/allocation/jobs` (`async=true` by default)
- MCP tool: `allocation_and_stock_projection`
- Controller: `AllocationController`
- Services: `AllocationJobExecutor`, `AllocationJobRunrService`, `AllocationProcessor`, and the allocation workers
- The controller validates `asOfDate` against the business clock, then directly enqueues JobRunr work; no in-process event or after-commit listener is involved
- The default REST flow returns `{jobId, requestId}` with `Location: /api/allocation/jobs/{jobId}` only after enqueue succeeds; enqueue failures propagate
- MCP invokes the processing flow directly rather than returning a JobRunr acceptance response
- `async=false` executes inline but currently returns the run ID as its job ID without creating a JobRunr job; its returned polling URL is not a valid execution-status contract
- Allocation reserves persisted inventory and inbound supply and refreshes stock projections, but does not synchronize physical stock
- New orders start as temporary reservations and expire unless confirmed
- Reusing a `requestId` with the same payload is idempotent; using it with a different payload or operation is rejected

### Acceptance Criteria
- A valid default asynchronous REST request returns `202 Accepted`, `jobId`, `requestId`, and a matching location for status polling
- The REST endpoint rejects an `asOfDate` other than today before enqueueing or creating a run
- The request must use `runType: NEW_DEMAND` for the structured new-demand flow
- Allocation results distinguish inventory, inbound, allocated, partially backordered, and backordered quantities
- Orders already submitted as new demand cannot be submitted again as new demand
- The same request can be retried safely without creating a duplicate allocation run
- Repeated HTTP submissions can create distinct execution jobs for one logical request; a lost acceptance response is an unknown outcome, not proof that enqueue failed
- Allocation never changes physical live stock directly

## Scenario 3: Monitor an Allocation Job

### Gherkin description
**Given** a client has received a `jobId` from a new-demand allocation request \
**When** the client requests `GET /api/allocation/jobs/{jobId}` \
**Then** Stockio returns the current job status \
**And** when processing succeeds, the response includes the execution job ID and saved allocation run \
**And** when the job does not exist, Stockio returns `404 Not Found`

### High-level technical analysis
- Endpoint: `GET /api/allocation/jobs/{jobId}`
- Controller: `AllocationController`
- Service: `AllocationJobRunrService`; execution and result persistence: `AllocationJobExecutor`
- Completed results are persisted as an `AllocationCompletionResult` containing `jobId` and `allocationRun`, without a top-level `version` or stock-sync result
- Job completion is serialized and reused on retries

### Acceptance Criteria
- A known job returns its current status
- A completed job returns `status: SUCCEEDED` and a non-null allocation result
- A `SUCCEEDED` job without saved completion is an explicit consistency error, not an empty successful result
- The successful result contains the persisted `runId`, `requestId`, run type, as-of date, allocations, and open backorder count
- An unknown job returns `404 Not Found`

## Scenario 4: Fulfill Open Backorders

### Gherkin description
**Given** persisted open backorders and inbound supply that becomes physically received by stock-ledger synchronization \
**When** the ledger transaction commits newly received inbound supply \
**Then** Stockio enqueues a dedicated JobRunr backorder-fulfillment job for the affected SKUs \
**And** the job fulfills eligible, non-expired backorders by priority and FIFO \
**And** it uses inbound supply only when it has arrived by `asOfDate` and meets each order's required delivery date \
**And** it partially fulfills orders when supply is insufficient \
**And** any remaining shortage stays open \
**And** an operator can invoke `allocate_open_backorders` as a manual or recovery trigger without creating a new-demand request

### High-level technical analysis
- MCP tool: `allocate_open_backorders`
- Automatic trigger: `StockLedgerProcessor` after a successful transaction marks inbound supply as received
- Background execution: a dedicated JobRunr backorder-fulfillment job
- Application path: `BackorderAllocationJob` -> `AllocationProcessor` -> `BackorderAllocationService`, accepting an internal `BackorderAllocationRequest`
- Command data: `requestId`, `asOfDate`, `trigger`, optional `skuScope`, and inbound shipments for manual registration
- Domain path: backorder fulfillment service -> existing per-SKU workers -> existing allocation policies and repository ports
- The job runs only after the ledger transaction commits, uses persisted received supply and open backorders, and refreshes projections after successful fulfillment
- The MCP tool maps its external `AllocationRequest` to the same internal command with trigger `MANUAL`
- Idempotency and concurrency protection prevent duplicate fulfillment for the same received-supply event or SKU scope
- Receipt request/job IDs are derived from the date and sorted received shipment-line IDs
- The after-commit callback is not durable: a crash before enqueue can lose the trigger, and enqueue failure cannot undo the receipt. There is no transactional outbox or automatic reconstruction of a missing enqueue
- The current request fingerprint excludes internal `skuScope` and `trigger`; it must not be treated as validation of every internal command field

### Acceptance Criteria
- When the post-commit callback runs and enqueue succeeds, newly received supply schedules fulfillment only after the ledger transaction commits
- Registering expected inbound supply alone does not enqueue a fulfillment job
- The job identifies and processes only SKUs affected by newly received supply when a scope is available
- Fulfilled and partially fulfilled quantities are reported with their allocation sources
- Backorders are processed by lower priority number, then FIFO `placedDate`
- Backorders whose reservations or orders are expired are excluded
- Remaining quantities remain represented as open backorders
- Projection refresh occurs after successful fulfillment
- Job retries and concurrent triggers are idempotent and do not reserve the same supply twice
- The existing `allocate_open_backorders` MCP tool remains available for manual or recovery execution
- Automatic backorder fulfillment does not call `AllocationService.allocation(...)` and does not represent fulfillment as new customer demand

## Scenario 5: Inspect an Order Reservation

### Gherkin description
**Given** an order that has been processed by an allocation flow \
**When** the client requests `GET /api/reservations/orders/{orderId}/status`, or invokes `get_order_reservation_status` \
**Then** Stockio returns the current reservation lifecycle, line details, and expiry information \
**And** if no reservation exists for the order, the REST API returns `404 Not Found`

### High-level technical analysis
- REST endpoint: `GET /api/reservations/orders/{orderId}/status`
- MCP tool: `get_order_reservation_status`
- Controller: `ReservationController`; `AllocationController` also exposes `GET /api/allocation/order-reservation/{orderId}/status`
- Service: `OrderReservationService`
- Reservation states are `PENDING_CONFIRMATION`, `CONFIRMED`, and `EXPIRED`
- The reservation status is authoritative for the current hold; a historical allocation run alone does not prove that a reservation remains active

### Acceptance Criteria
- An existing order returns its reservation status, lines, timestamps, and cleanup state
- Pending reservations expose their expiry deadline
- Confirmed and expired reservations expose the applicable lifecycle timestamps
- The REST endpoint returns `404 Not Found` for an unknown order
- The MCP tool reports an explicit error when no reservation exists

## Scenario 6: Synchronize the Physical Stock Ledger

### Gherkin description
**Given** a current-day stock projection containing inventory, reservations, inbound receipts, and confirmed deliveries \
**When** the client invokes `process_stock_ledger`, or the recurring stock-ledger job runs \
**Then** Stockio expires overdue unconfirmed reservations, rebuilds today's projection, and synchronizes it to live stock in one transaction \
**And** arrived inbound supply is received only once \
**And** due deliveries are consumed only for confirmed orders \
**And** an invalid or non-current snapshot date is rejected without applying partial changes

### High-level technical analysis
- MCP tool: `process_stock_ledger`
- Service: `StockLedgerProcessor`
- The processor acquires the stock projection lock, cleans up expired holds, rebuilds lot-level and aggregate snapshots, applies due receipts and confirmed deliveries, and records synchronization status
- The recurring `StockLedgerSyncJob` runs the same synchronization independently on its configured schedule
- This flow is separate from allocation and is the only flow that applies projected movements to physical live stock

### Acceptance Criteria
- Synchronization accepts today's date or an omitted date resolved by the business clock
- Past and future snapshot dates are rejected
- Expired holds are removed before the synchronized projection is calculated
- Arrived shipments are marked received and are not received again on retry
- Only confirmed orders with due deliveries reduce live stock
- The transaction fails atomically when snapshot or reservation invariants are inconsistent

## Scenario 7: Monitor Stock-Ledger Freshness

### Gherkin description
**Given** the stock-ledger synchronization schedule and monitoring configuration \
**When** a client requests `GET /api/stock/ledger/status` or checks the `stockLedger` health indicator \
**Then** Stockio reports the last successful synchronization, expected next run, freshness deadline, and current stale state \
**And** a missed freshness deadline makes the health indicator `DOWN` when scheduling is enabled \
**And** disabled scheduling reports an `UNKNOWN` health state

### High-level technical analysis
- Endpoint: `GET /api/stock/ledger/status`; also `GET /api/allocation/stock-ledger/status`
- Controller: `StockController` (dedicated route) or `AllocationController` (additional mapping)
- Service: `StockLedgerService`
- Health component: `stockLedger` health indicator
- The status is based on the configured cron, business zone, and freshness tolerance
- The status includes synchronization date and SKU/lot counts when a successful run exists

### Acceptance Criteria
- Status exposes scheduling enabled state, last success, next expected time, stale-after time, and stale flag
- A fresh scheduled ledger is reported as healthy
- A missed freshness deadline is reported as stale and unhealthy
- Disabled scheduling is reported as unknown rather than stale

## Scenario 8: Load an Allocation Request from CSV

### Gherkin description
**Given** a server-side CSV containing supported demand and inbound-delivery rows \
**When** an administrator requests `GET /api/admin/allocations/generate-request-by-csv` \
**Then** Stockio parses the CSV and returns an `AllocationRequest` \
**And** supported `Verkoop` rows become demand orders \
**And** supported `Levering` rows become inbound shipments \
**And** unsupported transaction types are ignored \
**And** the request is not allocated automatically

### High-level technical analysis
- Endpoint: `GET /api/admin/allocations/generate-request-by-csv`
- Controller: `AllocationAdminController`
- Service: `AllocationExcelService`
- The configured `stockio.allocation.csv-path` identifies the server-side input file
- Product titles are resolved through `ProductRepository`; document/line IDs derive from the CSV byte hash and row position, and customer names are deterministic for those IDs
- The request ID also includes the business date: identical bytes on the same date replay the same request regardless of file path

### Acceptance Criteria
- A valid CSV returns a structurally valid `AllocationRequest`
- Sales and delivery rows map to the correct demand and inbound models
- Unsupported transaction types do not create allocation inputs
- CSV loading does not create an allocation run, reservation, or stock movement
- Invalid input is reported as an error rather than returning a success-shaped fallback
- Same-date reloads preserve request and document identities; a later date does not authorize reusing existing documents as new demand

## Scenario 9: Generate an Excel Allocation Report

### Gherkin description
**Given** a persisted allocation run ID and a simple `.xlsx` file name \
**When** the client invokes `generate_excel_report`, or submits `POST /api/admin/allocations/generate-excel` \
**Then** Stockio creates an Excel workbook for that allocation run and returns its path \
**And** the workbook contains allocation details, source allocations, pivot tables, and formula-based SKU/status summaries \
**And** an unknown run or invalid file name is rejected

### High-level technical analysis
- MCP tool: `generate_excel_report`
- REST endpoint: `POST /api/admin/allocations/generate-excel`
- Controller: `AllocationAdminController`
- Services: `AllocationRunService`, `ExcelReportProcessor`, and `AllocationTools`
- Reports are written beneath the configured report directory
- File names are restricted to simple `.xlsx` names without directory traversal or path components

### Acceptance Criteria
- A valid persisted `runId` produces a workbook and returns the generated path
- The workbook contains allocation records and their source details
- Pivot tables and formula-based SKU/status summaries are generated
- File names containing directories or non-`.xlsx` extensions are rejected
- A missing allocation run is reported as an error

## Scenario 10: Remove Jobs with Excessive Retries

### Gherkin description
**Given** scheduled or processing jobs with repeated execution-state transitions \
**When** an administrator requests `DELETE /api/admin/allocations/delete-jobs-with-excessive-retries` \
**Then** Stockio permanently removes selected jobs whose counted state-history entries exceed two \
**And** the endpoint returns a confirmation message

### High-level technical analysis
- Endpoint: `DELETE /api/admin/allocations/delete-jobs-with-excessive-retries`
- Controller: `AllocationAdminController`
- Service: `AllocationJobRunrService`
- Reads up to 100 `PROCESSING` and 100 `SCHEDULED` jobs, oldest `updatedAt` first, and counts `SCHEDULED`, `PROCESSING`, and `FAILED` history entries; this is not a configured retry-limit check
- The operation is an administrative cleanup action and does not re-run allocation or modify stock

### Acceptance Criteria
- Selected jobs with more than two counted history entries are deleted
- The endpoint returns a clear confirmation response
- Cleanup does not create allocation runs, reservations, or stock movements
- Removing a job record does not undo allocation or guarantee cancellation of code already executing

## Scenario 11: Publish an Allocation Request from Another Spring Boot Application

### Gherkin description
**Given** another Spring Boot application includes the Stockio allocation RabbitMQ producer starter and has configured its broker connection, exchange, and routing key \
**When** that application publishes an `AllocationRequest` through the starter's publisher \
**Then** the starter sends a JSON message compatible with Stockio's allocation request contract to the configured exchange and routing key \
**And** the existing Stockio RabbitMQ listener can consume and process the message

### High-level technical analysis
- The wire contract is provided by a reusable `stockio-allocation-contracts` artifact rather than requiring a dependency on the executable `stockio-service` module
- A Spring Boot producer starter provides the typed publisher and is registered through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- The starter uses the host application's Spring AMQP `RabbitTemplate` and externalized properties for the exchange and routing key
- Auto-configuration is opt-in and backs off when the host application supplies its own publisher or message converter
- The scheduled sample-request generator remains in `stockio-test-harness/publisher` with JDBC catalogue lookup and opt-in scheduling; listener, queue, dead-letter, and JobRunr behavior remain in the Stockio service

### Acceptance Criteria
- An independent Spring Boot application can import the published producer starter and allocation-contract artifacts without depending on `stockio-service`
- When enabled and configured, auto-configuration provides a typed publisher that sends the allocation request JSON to the configured exchange and routing key
- The emitted JSON retains the field names, enum values, and date representation accepted by the existing Stockio listener
- Auto-configuration is inactive unless explicitly enabled and backs off for host-provided publisher or message-converter beans
- Conversion and send failures are surfaced to the caller rather than reported as successful publication
- The producer starter does not create consumer queues or dead-letter topology, enable scheduling, or include Stockio-specific sample-data generation
- The existing scheduled sample publisher continues to send requests consumable by the Stockio listener after it is adapted to the reusable publisher

## Scenario 12: Allocate a Product from the Frontend through Direct Service HTTP

**Status:** Implemented.

### Gherkin description
**Given** the Next.js allocation frontend and an independently running `stockio-service` on port `8080` \
**When** a user submits a valid product allocation form to the same-origin `POST /api/allocate` route \
**Then** the Next.js route uses Axios to send the flat request to `POST /api/products/allocate` on `stockio-service` \
**And** `ProductAllocationController` directly invokes `productAllocationService.allocate(request)` \
**And** the frontend renders the plain product allocation response without an MCP envelope \
**And** no separate Spring frontend backend or child service JVM is required \
**And** the UI and service start separately without a fat-JAR build or bundled UI \
**And** `allocate_product_by_name` is no longer exposed as an MCP tool \
**And** an unchanged manual retry preserves the request ID, while editing an attempted request creates a new request ID

### High-level technical analysis
- Keep the existing Next.js route as the same-origin adapter; Axios runs server-side, not in the browser
- Use a small TypeScript Axios client with server-side `STOCKIO_API_URL`, defaulting to `http://127.0.0.1:8080`, and upstream path `/api/products/allocate`
- The REST controller directly delegates to `ProductAllocationService`; `ProductAllocationTools.kt` and `allocate_product_by_name` have been removed
- Return plain `ProductAllocationResponse` JSON; frontend parsing, error text, proxy tests, and browser fixtures use the REST contract
- The obsolete Spring MCP bridge runtime is removed; `stockio-api` remains a normal Kotlin DTO library and owns the OpenAPI documents and Gradle generation tasks used by `stockio-service`
- Start Next.js and the service independently; there is no fat-JAR startup dependency or UI bundled into an executable JAR
- No-store behavior, the 180-second upstream timeout, explicit connection/timeout errors, and manual retry identity are preserved; allocation POSTs are not automatically retried
- The service's allocation, persistence, product lookup, and unrelated MCP interfaces remain unchanged; product allocation uses REST-only access and no browser-to-service CORS change is needed

### Acceptance Criteria
- The frontend works with independently started Next.js and `stockio-service:8080`, without `StockioMcpApplication`, a `bootJar` prerequisite, a bundled UI/service fat JAR, or a child service JVM
- The Next.js route uses Axios to send the unchanged flat request fields to the configured service's `/api/products/allocate` endpoint
- Valid service responses preserve HTTP status and JSON content, including non-allocation domain outcomes returned with HTTP `200`
- The UI displays selected products, candidates, evidence, allocation quantities, line/source identities, skipped-expiry outcomes, and backorders from plain REST JSON
- Invalid response payloads are explicit errors; HTTP `400` exposes the service's error message rather than referring to an unavailable MCP bridge
- Unreachable upstream and timeout conditions return non-success responses (`502` and `504` respectively); timeout messaging warns that allocation may still complete
- No automatic retry occurs; unchanged manual retries preserve `requestId`, and editing an attempted request generates a new ID
- The service's shared DTO compilation and OpenAPI interface generation still work after bridge removal; `ProductAllocationTools.kt` and `allocate_product_by_name` are removed while unrelated MCP tools remain available

## Scenario 13: Run Tests and Synthetic Traffic from a Separate Harness

**Status:** Implemented.

### Gherkin description
**Given** `stockio-test-harness` contains `e2e/`, `performance/`, and `publisher/` \
**When** a developer runs its browser tests or explicitly enables its allocation publisher \
**Then** Playwright exercises the Next.js UI against an isolated mock backend \
**And** the standalone publisher reads catalogue SKUs through its own JDBC connection and publishes compatible allocation requests to RabbitMQ \
**And** starting `stockio-service` alone never starts a synthetic publisher \
**And** `performance/` is reserved for future tooling rather than claiming an implemented load test

### High-level technical analysis
- The harness is a separate Gradle module and npm package; publisher sources/resources/tests live under `publisher/`, and Playwright configuration/specs live under `e2e/`
- The publisher depends on shared `stockio-api` DTOs, Spring JDBC, and RabbitMQ, not on service repositories, entities, application services, or migrations
- A read-only harness datasource selects SKUs from the existing `products` table; the service remains responsible for creating and migrating that schema
- Preserve five paired random orders/shipments, unique request/document/line IDs, configured business dates, delivery eligibility, exchange/routing key, and JSON contract
- Publisher scheduling is opt-in in the harness; queue/dead-letter topology, listener behavior, allocation rules, and JobRunr remain in `stockio-service`
- Browser tests retain their localhost mock and request-identity/error/rendering assertions; the UI no longer owns Playwright dependencies or test files
- Performance tooling, workloads, and thresholds are deliberately deferred

### Acceptance Criteria
- The requested three directories are present and the harness has independent build/run instructions
- Existing browser coverage runs from the harness without a real service, using the existing UI and mock allocation endpoint
- Explicit harness publisher startup reads the configured catalogue and emits the same flat shared allocation contract
- Publisher tests cover valid paired supply/demand, empty/failing catalogue reads, JDBC lookup, and JSON/date compatibility without importing service code
- Disabled harness publishing and standalone service startup generate no synthetic allocation traffic
- Service consumption/topology and application tests remain independent of harness dependencies
- No performance runner or load-generation guarantee is introduced
