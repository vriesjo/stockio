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
**When** the client submits the request to `POST /api/products/allocate`, or invokes the `allocate_product_by_name` MCP tool \
**Then** Stockio resolves the product against the active catalog and allocates the resolved SKU against eligible inventory and inbound supply \
**And** if the product cannot be matched confidently, the response explains why and no allocation is made \
**And** any unfilled quantity is recorded as an open backorder

### High-level technical analysis
- REST endpoint: `POST /api/products/allocate`
- MCP tool: `allocate_product_by_name`
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
**When** the client submits `POST /api/logistics/allocation-jobs`, or invokes `allocation_and_stock_projection` \
**Then** Stockio accepts the request and processes it as `NEW_DEMAND` \
**And** orders are processed by ascending priority and FIFO `placedDate` \
**And** inventory is consumed FIFO by `receivedDate` and `lotId` \
**And** inbound supply is used only when its expected arrival is on or before the line's required delivery date \
**And** any remaining quantity becomes an open backorder

### High-level technical analysis
- REST endpoint: `POST /api/logistics/allocation-jobs`
- MCP tool: `allocation_and_stock_projection`
- Controller: `AllocationController`
- Services: `AllocationEventPublisher`, `AllocationJobService`, `AllocationProcessingService`, and the allocation workers
- The REST request is published for asynchronous processing and returns a `jobId` with a `Location` header
- Allocation reserves persisted inventory and inbound supply and refreshes stock projections, but does not synchronize physical stock
- New orders start as temporary reservations and expire unless confirmed
- Reusing a `requestId` with the same payload is idempotent; using it with a different payload or operation is rejected

### Acceptance Criteria
- A valid request returns `202 Accepted`, a `jobId`, and a location for status polling
- The request must use `runType: NEW_DEMAND` for the structured new-demand flow
- Allocation results distinguish inventory, inbound, allocated, partially backordered, and backordered quantities
- Orders already submitted as new demand cannot be submitted again as new demand
- The same request can be retried safely without creating a duplicate allocation run
- Allocation never changes physical live stock directly

## Scenario 3: Monitor an Allocation Job

### Gherkin description
**Given** a client has received a `jobId` from a new-demand allocation request \
**When** the client requests `GET /api/logistics/allocation-jobs/{jobId}` \
**Then** Stockio returns the current job status \
**And** when processing succeeds, the response includes the allocation run and contract version \
**And** when the job does not exist, Stockio returns `404 Not Found`

### High-level technical analysis
- Endpoint: `GET /api/logistics/allocation-jobs/{jobId}`
- Controller: `AllocationController`
- Service: `AllocationJobService`
- Completed results are persisted as an `AllocationCompletionResult` containing `version` and `allocationRun`
- Job completion is serialized and reused on retries

### Acceptance Criteria
- A known job returns its current status
- A completed job returns `status: SUCCEEDED` and a non-null allocation result
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
- Application service: a reusable backorder fulfillment service accepting a `BackorderFulfillmentCommand`, not an `AllocationRequest`
- Command data: `asOfDate`, `trigger`, and an optional affected-SKU scope
- Domain path: backorder fulfillment service -> existing per-SKU workers -> existing allocation policies and repository ports
- The job runs only after the ledger transaction commits, uses persisted received supply and open backorders, and refreshes projections after successful fulfillment
- The MCP tool remains an explicit manual/recovery adapter and may use the same application service
- Idempotency and concurrency protection prevent duplicate fulfillment for the same received-supply event or SKU scope

### Acceptance Criteria
- Newly received inbound supply causes a backorder-fulfillment job to be enqueued only after the ledger transaction commits
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
**When** the client requests `GET /api/logistics/orders/{orderId}/reservation`, or invokes `get_order_reservation_status` \
**Then** Stockio returns the current reservation lifecycle, line details, and expiry information \
**And** if no reservation exists for the order, the REST API returns `404 Not Found`

### High-level technical analysis
- REST endpoint: `GET /api/logistics/orders/{orderId}/reservation`
- MCP tool: `get_order_reservation_status`
- Controller: `AllocationController`
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
**When** a client requests `GET /api/logistics/stock-ledger/status` or checks the `stockLedger` health indicator \
**Then** Stockio reports the last successful synchronization, expected next run, freshness deadline, and current stale state \
**And** a missed freshness deadline makes the health indicator `DOWN` when scheduling is enabled \
**And** disabled scheduling reports an `UNKNOWN` health state

### High-level technical analysis
- Endpoint: `GET /api/logistics/stock-ledger/status`
- Service: `LedgerSyncStatusService`
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
- Product titles are resolved through the product repository, and generated IDs/customer names make each loaded request distinct

### Acceptance Criteria
- A valid CSV returns a structurally valid `AllocationRequest`
- Sales and delivery rows map to the correct demand and inbound models
- Unsupported transaction types do not create allocation inputs
- CSV loading does not create an allocation run, reservation, or stock movement
- Invalid input is reported as an error rather than returning a success-shaped fallback

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
**Given** failed or repeatedly retried background allocation jobs \
**When** an administrator requests `DELETE /api/admin/allocations/delete-jobs-with-excessive-retries` \
**Then** Stockio removes jobs that have exhausted or exceeded the configured retry state \
**And** the endpoint returns a confirmation message

### High-level technical analysis
- Endpoint: `DELETE /api/admin/allocations/delete-jobs-with-excessive-retries`
- Controller: `AllocationAdminController`
- Service: `AllocationJobService`
- The operation is an administrative cleanup action and does not re-run allocation or modify stock

### Acceptance Criteria
- Jobs in the excessive-retry set are deleted
- The endpoint returns a clear confirmation response
- Cleanup does not create allocation runs, reservations, or stock movements
