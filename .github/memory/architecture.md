# Stockio Software Architecture

## Stack
- **Backend:** Spring Boot (Kotlin)
- **Frontend:** React, NextJs
- **Database:** PostgreSQL (Spring Data JPA, Flyway, pgvector)
- **Testing:** JUnit, Testcontainers, Mockito
- **Build:** Gradle

## Overview

Stockio is a Kotlin/Spring application for allocating demand against live inventory and expected inbound supply.
It persists allocation results and reservations in PostgreSQL, maintains daily aggregate and lot-level stock projections,
synchronizes today's projection to live stock, and generates Excel reports.
The same core services are exposed through Spring AI MCP tools and a local HTTP API.

- PostgreSQL is the system of record.
- Flyway manages the schema;
- JPA repositories handle operational data and projection locking, while `StockSnapshotRepository` uses `JdbcTemplate`
to read and replace snapshots in the same transaction. Snapshots are not JPA-managed entities, so repeated rebuilds do not require clearing the persistence context.
- Daily snapshots are derived projections from live stock and persisted reservations, not an independent source of inventory truth.

### Design Patterns

- **Hexagonal Architecture (Api, Ports, Infra):** Clear separation of concerns between the HTTP layer, business logic, and persistence.
- **Dependency Injection:** Spring's @Service, @Repository, and constructor injection for loose coupling and testability.
- **DTOs (Data Transfer Objects):** Used for request and response payloads to decouple API and domain models.
- **Singleton:** Spring beans are singletons by default, ensuring one instance per application context.
- **Ports and adapters:** Allocation, reservation cleanup, and stock processors depend on repository ports implemented by Spring Data JPA and JDBC adapters. The separation is partial: some services still inject concrete infrastructure classes, JobRunr APIs, and HTTP clients directly.
- **Configuration/Properties:** Externalized configuration using YAML and @ConfigurationProperties for environment flexibility.

## Modules and technology

The Gradle build includes two modules:

| Module | Purpose |
| --- | --- |
| `:stockio-service` (`stockio-service/`) | Spring Boot application, MCP tools, REST API, allocation workflows, persistence, CSV import, and Excel generation. |
| `:stockio-api` (`stockio-api/`) | Sample Spring AI MCP client. It is not required by the service at runtime. |

The build uses Java 21, Kotlin 2.2.20, Spring Boot 3.5.10, and Spring AI 1.1.2. The service uses Spring MVC, Spring Data JPA, PostgreSQL with pgvector, Flyway, RabbitMQ, JobRunr, Apache POI, HikariCP, and Spring Boot Actuator. Natural-language product allocation also uses the Tavily search API and Ollama embeddings. Allocation work is parallelized across SKUs with Kotlin coroutines.

## System context

```mermaid
flowchart LR
    Operator["Operator / REST client"] -->|"HTTP / JSON"| API["Stockio service"]
    Agent["MCP client"] -->|"MCP tools"| API
    API -->|"Application data and JobRunr storage"| DB[("PostgreSQL + pgvector")]
    API -->|"Product lookup"| Tavily["Tavily Search API"]
    API -->|"Embeddings"| Ollama["Ollama"]
    API -->|"Publish allocation requests"| Rabbit["RabbitMQ"]
    Rabbit -->|"Deliver allocation requests"| API
    API -->|"Read"| CSV["Configured allocation CSV"]
    API -->|"Write .xlsx"| Reports["Report directory"]
```

The service runs as a servlet application and exposes MCP tools through Spring AI. The HTTP server binds to `127.0.0.1:8080` by default. No application-level authentication is configured; protect the API and Actuator endpoints if `SERVER_ADDRESS` is changed to expose them beyond the local host.

## C4 - level 3 component diagram

This C4 level-3 view opens the `stockio-service` container into its application
components and separates the hexagonal boundaries:

- **Inbound adapters** translate HTTP, MCP, messaging, and scheduled triggers.
- **Application services** orchestrate use cases and transaction boundaries.
- **Domain services and policies** contain allocation and projection rules.
- **Outbound ports** define persistence and external-service contracts.
- **Outbound adapters** implement those ports using JPA, JDBC, HTTP, RabbitMQ,
  JobRunr, and the filesystem.

The diagram groups logical responsibilities; it is not a claim that every
integration has an explicit port interface. In particular, JobRunr, search
clients, and filesystem operations still have direct application dependencies.
Repository ports also expose infrastructure entity types. JobRunr is embedded
infrastructure; PostgreSQL stores both operational data and JobRunr data.

```mermaid
flowchart LR
    Rest["REST clients"] --> RestAdapters
    Mcp["MCP clients"] --> McpAdapters
    Rabbit["RabbitMQ"] --> MessageAdapter
    Scheduler["Cron / JobRunr"] --> ScheduledAdapter
    Csv["Configured CSV"] --> FileAdapter
    FileAdapter -->|"Writes"| Reports["Report directory"]
    SearchAdapter -->|"Queries"| Tavily["Tavily Search API"]
    SearchAdapter -->|"Embeds"| Ollama["Ollama Embedding API"]
    PersistenceAdapters -->|"Reads and writes"| Database[("PostgreSQL + pgvector<br/>operational data + JobRunr")]

    subgraph Core["stockio-service - Kotlin / Spring Boot"]
        subgraph Inbound["Inbound adapters"]
            RestAdapters["AllocationController / ReservationController / StockController<br/>Spring MVC<br/>Jobs, lifecycle and freshness"]
            ProductAdapter["ProductAllocationController<br/>Spring MVC<br/>Product-by-name allocation"]
            AdminAdapter["AllocationAdminController<br/>Spring MVC<br/>CSV, reports, retry cleanup"]
            McpAdapters["AllocationTools / ProductAllocationTools<br/>Spring AI @Tool<br/>MCP use cases"]
            MessageAdapter["AllocationRabbitListener<br/>Spring AMQP<br/>Queue intake and retry handoff"]
            ScheduledAdapter["ReservationExpiryJob / StockLedgerSyncJob / BackorderAllocationJob<br/>JobRunr + scheduling<br/>Recurring and receipt triggers"]
        end

        subgraph UseCases["Application use cases"]
            AllocationJob["AllocationJobExecutor / AllocationJobRunrService<br/>Submission, execution, status and cleanup"]
            ProductFlow["ProductAllocationService<br/>Resolves product, starts allocation"]
            AllocationFlow["AllocationProcessor<br/>Allocates, then refreshes forecast"]
            BackorderFlow["BackorderAllocationService<br/>Scoped fulfillment of persisted demand"]
            LedgerFlow["StockLedgerProcessor<br/>Applies today's forecast to live stock"]
            ReservationFlow["ReservationExpiryService / CleanupService<br/>Expires overdue holds"]
            QueryFlow["OrderReservationService / StockLedgerService<br/>Read-only lifecycle and freshness"]
            FileFlow["AllocationExcelService / ExcelReportProcessor<br/>CSV import and Excel output"]
        end

        subgraph Domain["Domain services and policies"]
            RunDomain["AllocationService / AllocationRunService<br/>Validation, idempotency, run state"]
            DocumentDomain["AllocationDocumentService / InboundSupplyService<br/>Order, shipment, and supply ownership"]
            SkuDomain["AllocationSkuWorker<br/>Per-SKU transactional allocation"]
            PolicyDomain["AllocationPolicyCalculator<br/>Priority, FIFO, eligibility, backorders"]
            ProjectionDomain["StockProjectionProcessor<br/>Aggregate and lot-level forecasts"]
        end

        subgraph OutPorts["Outbound ports"]
            AllocationPort["Allocation repository ports<br/>Runs, records, sources, locks, claims, backorders"]
            ReservationPort["Reservation repository ports<br/>Shipments, supply, reservations"]
            StockPort["Stock repository ports<br/>Live stock, locks, snapshots, sync status"]
            ProductPort["ProductRepository / ProductVectorRepository<br/>Catalog and similarity search"]
            RuntimePort["AllocationJobResultRepository<br/>Completion persistence"]
        end

        subgraph OutAdapters["Outbound adapters"]
            PersistenceAdapters["Spring Data JPA + JdbcTemplate<br/>PostgreSQL entities, locks, snapshots, pgvector"]
            SearchAdapter["Tavily / Ollama HTTP clients<br/>Timeouts and response validation"]
            JobAdapter["JobRunr integration<br/>Execution, retries, job storage"]
            AmqpAdapter["AllocationRequestPublisher / Rabbit configuration<br/>Demo requests and queue bindings"]
            FileAdapter["CSV reader / Apache POI<br/>Filesystem import and .xlsx reports"]
        end
    end

    RestAdapters -->|"Calls"| AllocationJob
    RestAdapters -->|"Reads"| QueryFlow
    ProductAdapter -->|"Calls"| ProductFlow
    AdminAdapter -->|"Calls"| FileFlow
    AdminAdapter -->|"Calls"| AllocationJob
    McpAdapters -->|"Calls"| ProductFlow
    McpAdapters -->|"Calls"| AllocationFlow
    McpAdapters -->|"Calls"| LedgerFlow
    McpAdapters -->|"Reads"| QueryFlow
    McpAdapters -->|"Calls"| FileFlow
    MessageAdapter -->|"Calls"| AllocationJob
    ScheduledAdapter -->|"Triggers"| ReservationFlow
    ScheduledAdapter -->|"Triggers"| LedgerFlow
    ScheduledAdapter -->|"Backorder job"| AllocationFlow

    AllocationJob -->|"Executes"| AllocationFlow
    AllocationJob -->|"Persists completion"| RuntimePort
    ProductFlow -->|"Starts NEW_DEMAND"| AllocationFlow
    AllocationFlow -->|"Coordinates"| RunDomain
    AllocationFlow -->|"Fulfillment commands"| BackorderFlow
    AllocationFlow -->|"Refreshes"| ProjectionDomain
    LedgerFlow -->|"Uses"| ReservationFlow
    LedgerFlow -->|"Rebuilds and applies"| ProjectionDomain
    ReservationFlow -->|"Refreshes"| ProjectionDomain
    QueryFlow --> ReservationPort
    QueryFlow --> StockPort
    FileFlow --> FileAdapter
    FileFlow --> AllocationPort
    FileFlow --> ProductPort
    LedgerFlow -->|"After receipt commit: enqueue"| JobAdapter

    RunDomain --> DocumentDomain
    RunDomain --> SkuDomain
    BackorderFlow --> DocumentDomain
    BackorderFlow --> SkuDomain
    BackorderFlow --> AllocationPort
    SkuDomain --> PolicyDomain
    SkuDomain --> AllocationPort
    SkuDomain --> ReservationPort
    SkuDomain --> StockPort
    DocumentDomain --> AllocationPort
    DocumentDomain --> ReservationPort
    ProjectionDomain --> StockPort
    ProjectionDomain --> ReservationPort
    ProjectionDomain --> AllocationPort
    ProductFlow --> ProductPort
    ProductFlow --> SearchAdapter

    AllocationPort --> PersistenceAdapters
    ReservationPort --> PersistenceAdapters
    StockPort --> PersistenceAdapters
    ProductPort --> PersistenceAdapters
    RuntimePort --> PersistenceAdapters
    MessageAdapter -->|"Retry fallback"| JobAdapter
    AllocationJob -->|"Direct enqueue and status"| JobAdapter
    AllocationJob -->|"Uses"| RuntimePort
    AmqpAdapter --> Rabbit
    JobAdapter --> Database
    PersistenceAdapters --> Database

    classDef inbound fill:#e3f2fd,stroke:#1565c0
    classDef usecase fill:#e8f5e9,stroke:#2e7d32
    classDef domain fill:#fff3e0,stroke:#ef6c00
    classDef port fill:#f3e5f5,stroke:#7b1fa2
    classDef adapter fill:#fce4ec,stroke:#ad1457
    classDef external fill:#eeeeee,stroke:#616161
    class RestAdapters,ProductAdapter,AdminAdapter,McpAdapters,MessageAdapter,ScheduledAdapter inbound
    class AllocationJob,ProductFlow,AllocationFlow,BackorderFlow,LedgerFlow,ReservationFlow,QueryFlow,FileFlow usecase
    class RunDomain,DocumentDomain,SkuDomain,PolicyDomain,ProjectionDomain domain
    class AllocationPort,ReservationPort,StockPort,ProductPort,RuntimePort port
    class PersistenceAdapters,SearchAdapter,JobAdapter,AmqpAdapter,FileAdapter adapter
    class Rest,Mcp,Rabbit,Scheduler,Csv,Reports,Tavily,Ollama,Database external
```

The following runtime collaboration view keeps the more detailed submission and job
relationships. It is complementary to the hexagonal dependency view above:

```mermaid
flowchart TB
    Clients["REST and MCP clients"]
    Rabbit["RabbitMQ"]
    DB[("PostgreSQL + pgvector")]
    Tavily["Tavily Search API"]
    Ollama["Ollama"]
    Files["CSV input / Excel output"]

    subgraph Service["Stockio service container - Kotlin / Spring Boot"]
        Entry["REST controllers / MCP tools<br/>Inbound adapters"]
        Submission["AllocationJobExecutor.executeAsync<br/>Direct JobRunr submission"]
        Consumer["AllocationRabbitListener<br/>Synchronous attempt and retry handoff"]
        Runtime["JobRunr scheduler / workers<br/>Embedded infrastructure"]
        Job["AllocationJobExecutor / AllocationJobRunrService<br/>Execution, status and result persistence"]
        Processing["AllocationProcessor<br/>Allocate then refresh forecast"]
        Allocation["AllocationService / AllocationRunService<br/>Validation, SKU dispatch and run state"]
        Documents["AllocationDocumentService / InboundSupplyService<br/>Document ownership, TTL and supply registration"]
        Worker["AllocationSkuWorker / policies<br/>Per-SKU reservations and backorders"]
        Product["ProductAllocationService / search clients<br/>Local-first product resolution"]
        Scheduled["ReservationExpiryJob / StockLedgerSyncJob / BackorderAllocationJob<br/>Recurring and receipt-triggered work"]
        Expiry["ReservationExpiryService / CleanupService<br/>Release overdue holds"]
        Ledger["StockLedgerProcessor<br/>Apply due physical movements"]
        Projection["StockProjectionProcessor<br/>Calculate daily forecast"]
        Status["Reservation / ledger status services<br/>Read models and freshness health"]
        FileAdapters["AllocationExcelService / ExcelReportProcessor<br/>Import requests and generate reports"]
        JPA["Operational repositories<br/>Spring Data JPA, including database locks"]
        Snapshots["StockSnapshotRepository / StockLedgerSyncStatusRepository<br/>JDBC snapshots and sync status"]
        Vectors["ProductVectorRepository<br/>JDBC vector search and knowledge storage"]
    end

    Clients --> Entry
    Entry --> Submission
    Submission --> Runtime
    Rabbit --> Consumer
    Consumer --> Job
    Consumer -->|"Direct retry enqueue"| Runtime
    Runtime --> Job
    Runtime --> Scheduled
    Scheduled --> Expiry
    Scheduled --> Ledger
    Ledger -->|"After receipt commit: scoped fulfillment enqueue"| Runtime
    Scheduled -->|"BackorderAllocationJob"| Processing
    Job --> Processing
    Job --> JPA
    Job -->|"Job state and administrative cleanup"| Runtime
    Entry --> Job
    Entry --> Processing
    Entry --> Product
    Entry --> Ledger
    Entry --> Status
    Entry --> FileAdapters
    Product --> Processing
    Product --> JPA
    Product --> Vectors
    Product --> Tavily
    Product --> Ollama
    Processing --> Allocation
    Processing --> Projection
    Allocation --> Documents
    Allocation --> Worker
    Allocation --> JPA
    Documents --> JPA
    Worker --> JPA
    Ledger --> Expiry
    Ledger --> Projection
    Ledger --> JPA
    Ledger --> Snapshots
    Expiry --> JPA
    Expiry --> Projection
    Projection --> JPA
    Projection --> Snapshots
    Status --> JPA
    Status --> Snapshots
    FileAdapters --> JPA
    FileAdapters --> Files
    Runtime -->|"Job storage and worker coordination"| DB
    JPA --> DB
    Snapshots --> DB
    Vectors --> DB
```

The optional demo publisher is another RabbitMQ producer inside the service; its
behavior is described in the [RabbitMQ flow](#rabbitmq-intake-and-retry-flow).
These components run within one Spring Boot application, not as separately
deployed services.

### Component responsibilities (hexagonal mapping)

| Component | Responsibility |
| --- | --- |
| `AllocationTools` | MCP adapter for new-demand allocation and projection, backorder fulfillment, stock-ledger synchronization, and Excel report generation. |
| `ProductAllocationTools` / `ProductAllocationService` | Resolves a product phrase through local catalog/vector matching with Tavily fallback, then delegates to `AllocationProcessor`. |
| Tavily / Ollama clients | Perform external product search and local embedding requests with configured timeouts. |
| `ProductVectorRepository` | Persists catalog embeddings and Tavily knowledge chunks and performs pgvector similarity searches. |
| `AllocationController` | Validates today's business date, submits allocation/projection work directly to JobRunr by default, and exposes job status plus reservation/freshness read aliases. Also supports `async=false` with the limitation described below. |
| `ReservationController` / `StockController` | Expose dedicated reservation-lifecycle and ledger-freshness reads. |
| `AllocationAdminController` | Creates allocation requests from CSV, generates reports, and removes jobs with excessive retries. |
| `AllocationService` | Validates, groups work by SKU, coordinates SKU workers, and completes allocation runs. |
| `AllocationRunService` | Owns allocation-run identity, request-hash validation, completion, and persisted historical results. |
| `AllocationProcessor` | Runs allocation or backorder fulfillment, then refreshes projections after the SKU transactions commit. Never synchronizes the ledger. |
| `AllocationDocumentService` | Registers immutable order/shipment headers and lines before SKU work and assigns one reservation deadline per order. |
| `AllocationSkuWorker` | Performs one SKU's transactional allocation or backorder fulfillment, reserves stock/supply, and persists resumable operation results. |
| `AllocationPolicyCalculator` | Applies order priority, FIFO source selection, delivery-date eligibility, and backorder rules. |
| `StockProjectionProcessor` | Rebuilds aggregate and lot-level daily projections from the live stock baseline and persisted reservations. |
| `StockLedgerProcessor` | Expires overdue holds, rebuilds today's projection, and synchronizes it to live stock. |
| `StockLedgerSyncJob` | Independently schedules ledger synchronization twice daily by default. |
| `ReservationExpiryJob` / expiry services | Release overdue pending orders in transactional batches and rebuild projections. |
| `AllocationJobExecutor` | Directly enqueues HTTP requests, executes allocation/projection, and persists or reuses `{jobId, allocationRun}` completion JSON. |
| `AllocationJobRunrService` | Reads JobRunr status and saved completion, and performs administrative job-record cleanup. |
| `BackorderAllocationJob` / `BackorderAllocationService` | Process receipt-triggered or manual fulfillment commands without creating new demand. |
| `OrderReservationService` / `StockLedgerService` | Read current lifecycle and ledger freshness without changing stock. |
| `StockProjectionLockRepository` | Acquires the shared/exclusive database coordination lock; the caller must already have a transaction. |
| `StockSnapshotRepository` | Reads and replaces derived snapshots through JDBC in the caller's transaction. |
| `AllocationExcelService` | Parses the configured CSV and creates a validated allocation request. |
| `ExcelReportProcessor` | Writes allocation details, sources, pivot tables, and formula summaries to an `.xlsx` workbook. |
| Repositories and `JdbcTemplate` | Persist operational state, enforce locks, and read/write snapshot data. |

The repository interfaces in `allocation/domain/port`, `reservation/domain/port`,
`stock/domain/port`, and `product/domain/port` are the outbound ports shown in the diagram. Their
implementations in `allocation/infra`, `reservation/infra`, `product/infra`, and
`stock/infra` are outbound adapters. Some current port signatures still expose
infra entity types; replacing those with domain persistence models would make the
hexagonal boundary stricter without changing the use-case components.

## Interfaces

### MCP tools

| Tool | Input | Behavior |
| --- | --- | --- |
| `allocation_and_stock_projection` | `AllocationRequest` with `runType: NEW_DEMAND` | Allocates demand, persists reservations/backorders, and rebuilds daily projections. It does not synchronize live stock. |
| `allocate_product_by_name` | `ProductAllocationRequest` | Resolves an active catalog SKU locally or through Tavily fallback, delegates allocation/projection, and returns product evidence plus the allocation result. Unresolved or ambiguous products are not allocated. |
| `allocate_open_backorders` | `AllocationRequest` with `runType: BACKORDER_FULFILLMENT` | Attempts to fulfill persisted backorders using eligible stock and inbound supply, rebuilds projections, and returns an `AllocationRunResult`. Does not synchronize live stock. |
| `process_stock_ledger` | `ProcessDailyStockRequest` | Expires overdue reservations, rebuilds today's projection, and applies it to live stock. Only confirmed orders are delivered; allocation is not recalculated. |
| `get_order_reservation_status` | Order ID | Returns the current lifecycle, deadline, and whether expired reservations still await cleanup. |
| `generate_excel_report` | `ExcelReportRequest` (`runId`, simple `.xlsx` `fileName`) | Generates a report for a saved allocation run and returns the output path. |

### REST endpoints

| Method and path | Behavior |
| --- | --- |
| `POST /api/products/allocate` | Synchronously performs the agentic product lookup and allocation for a `ProductAllocationRequest`; returns the product-resolution status and allocation result. |
| `POST /api/allocation/jobs` | Validates today's business-date `asOfDate`; by default (`async=true`) directly enqueues work and returns `202 Accepted`, `{jobId, requestId}`, and `Location: /api/allocation/jobs/{jobId}` after enqueue succeeds. Does not synchronize stock. |
| `GET /api/allocation/jobs/{jobId}` | Returns JobRunr state and, when succeeded, `result: {jobId, allocationRun}` without a completion version or stock-sync result. An unknown job returns `404`. |
| `GET /api/stock/ledger/status` | Returns the last committed ledger success, snapshot date/counts, next expected execution, stale deadline, and freshness status. |
| `GET /api/reservations/orders/{orderId}/status` | Returns current reservation lifecycle and deadline, or `404` for an unknown order. |
| `GET /api/allocation/stock-ledger/status` | Additional ledger-freshness read mapping on `AllocationController`. |
| `GET /api/allocation/order-reservation/{orderId}/status` | Additional reservation-lifecycle read mapping on `AllocationController`. |
| `GET /api/admin/allocations/generate-request-by-csv` | Parses the configured CSV and returns a generated `AllocationRequest`; it does not execute it. |
| `POST /api/admin/allocations/generate-excel` | Generates an Excel report from a saved run. |
| `DELETE /api/admin/allocations/delete-jobs-with-excessive-retries` | Removes qualifying JobRunr jobs with excessive retry transitions. |
| `GET /actuator/health` | Spring Boot health endpoint. |
| `GET /actuator/metrics/{metricName}` | Reads an exposed Micrometer metric. |

Execution and failure behavior for these interfaces are described in
[Supported flows](#supported-flows).

`POST /api/allocation/jobs?async=false` executes inline but still returns `202`.
Its current response uses the allocation `runId` as `jobId`, although completion
is stored under a separate generated execution ID and no JobRunr job is created.
The returned status URL is therefore not a working JobRunr polling contract for
this branch. This is an implementation limitation, not an alternate durable
submission flow. The old `/api/logistics/...` and `/api/allocation-jobs` mappings
are not declared.

### Allocation request shape

An `AllocationRequest` contains:

- `requestId`: UUID used for idempotency.
- `asOfDate`: date used for the allocation and projection.
- `runType`: `NEW_DEMAND` or `BACKORDER_FULFILLMENT`.
- `demandOrders`: order headers with order ID, customer, priority, placed date, and a nonempty `lines` list. Each line has a globally unique `orderLineId`, SKU, requested quantity, and required delivery date.
- `inboundShipments`: shipment headers with shipment ID and a nonempty `lines` list. Each line has a globally unique `shipmentLineId`, SKU, expected quantity, expected arrival date, and warehouse code.

The server validates required fields, positive quantities and priorities, date order, and unique IDs within a request. A new-demand request cannot reuse order IDs already processed as new demand; unresolved quantities belong to the backorder-fulfillment workflow.

An order or shipment may contain repeated SKUs, including separate lines with identical dates. Reservation/backorder identity is the line ID, not the header ID or SKU. Allocation results remain flat but include both `orderId` and `orderLineId`; inbound sources include shipment header and line IDs. `openBackorderCount` counts distinct open orders, not lines.

## Supported flows

The flow diagrams show runtime collaboration; PostgreSQL arrows abbreviate the
repository calls. Transaction and lock boundaries are called out where they affect
correctness. Allocation holds supply, projection forecasts movements, and ledger
synchronization applies due physical movements: these are separate operations.

| Flow | Trigger | Outcome |
| --- | --- | --- |
| [Allocation](#allocation-flow) | Structured new demand through an adapter or job | Reservations, backorders, historical result, and refreshed forecast. |
| [Backorder fulfillment](#backorder-fulfillment-flow) | Receipt-triggered JobRunr job or explicit MCP request | Additional reservations against existing demand and refreshed forecast. |
| [Product resolution and allocation](#product-resolution-and-allocation-flow) | Product REST endpoint or MCP tool | Resolved SKU and allocation, or an explicit non-allocation outcome. |
| [Reservation expiry](#reservation-expiry-flow) | Recurring JobRunr sweep | Released holds, expired order headers, and refreshed forecast. |
| [REST allocation submission](#rest-allocation-submission-and-completion-flow) | HTTP request and status polling | Asynchronous allocation/projection completion, not ledger sync. |
| [RabbitMQ intake and retry](#rabbitmq-intake-and-retry-flow) | Queue delivery, including demo publications | Synchronous allocation attempt, JobRunr fallback, or dead-letter rejection. |
| [Stock projection](#stock-projection-flow) | Allocation, expiry, or ledger processing | Replacement aggregate and lot-level forecasts. |
| [Ledger synchronization](#ledger-synchronization-flow) | Independent recurring job or manual MCP call | Due receipts/deliveries applied atomically and sync success recorded. |
| [Status and freshness reads](#status-and-freshness-read-flow) | REST, MCP, or Actuator query | Current reservation lifecycle or ledger freshness, without stock mutations. |
| [CSV import](#csv-import-flow) | Admin HTTP request | A generated allocation request; no allocation is performed. |
| [Excel report](#excel-report-flow) | Admin HTTP request or MCP tool | Workbook generated from a saved allocation run. |
| [Job-record cleanup](#job-record-cleanup-flow) | Admin HTTP request | Permanent removal of qualifying JobRunr records, without undoing allocation. |

Order confirmation and explicit warehouse receipt/dispatch confirmation are not
implemented flows. Receipt-triggered backorder fulfillment is implemented, but
its post-commit enqueue is not durably coupled to the receipt transaction.

### Allocation flow

`AllocationProcessor` is the common entry point for allocation jobs, MCP
tools, and product allocation. It runs `NEW_DEMAND` allocation, waits for the SKU
workers to finish, and then refreshes projections. It never invokes the ledger.

```mermaid
sequenceDiagram
    participant Caller as Allocation adapter or job
    participant Processing as AllocationProcessor
    participant Allocation as AllocationService
    participant Documents as AllocationDocumentService
    participant Worker as AllocationSkuWorker
    participant Policy as AllocationPolicyCalculator
    participant Projection as StockProjectionProcessor
    participant DB as PostgreSQL

    Caller->>Processing: process(NEW_DEMAND request)
    Processing->>Allocation: allocation(request)
    Allocation->>DB: Check request ID, payload hash and saved run
    alt Matching completed run
        DB-->>Allocation: Historical allocation result
    else New or resumable run
        Allocation->>DB: Begin or resume run
        Allocation->>Documents: initialize(runId, request)
        Documents->>DB: Exclusive projection lock and locked run validation
        Documents->>DB: Claim order headers, save ordered lines and TTL deadlines
        Documents->>DB: Register shipment lines and mark documents initialized
        Note over Documents,DB: Initialization commits before SKU workers start
        Allocation->>Worker: allocateNewDemand for each selected SKU
        Note over Worker,DB: Bounded parallelism with one transaction per SKU
        Worker->>DB: Shared projection lock, SKU lock and cached-operation check
        Worker->>DB: Load live stock, all unreceived SKU supply and order lifecycle
        Worker->>Policy: Calculate eligible line allocations
        Policy-->>Worker: Reservations, source counters, backorders and results
        Worker->>DB: Persist SKU changes and resumable result
        Worker-->>Allocation: Line allocation records
        Allocation->>DB: Complete run after all SKU results are available
    end
    Allocation-->>Processing: AllocationRunResult
    Processing->>Projection: process(asOfDate)
    Projection-->>Processing: Forecast replacement committed
    Processing-->>Caller: AllocationRunResult
```

Document registration assigns one order-header deadline shared by all its lines,
including time spent waiting for SKU processing. Already initialized documents
are not registered again, so retries do not renew TTL or reapply supply amendments.
An expired header cannot acquire new reservations; late workers persist
`SKIPPED_ORDER_EXPIRED` with null allocation status and no active demand.

The allocation rules are:

1. Orders are processed by ascending numeric priority, then placed date, then original request order.
2. For each SKU, persisted stock lots are considered FIFO by received date, lot ID, and warehouse code.
3. Available live stock is `quantity_on_hand - quantity_reserved`.
4. Remaining demand may use inbound lines ordered by expected arrival date, shipment ID, and shipment line ID, if arrival is no later than the demand line's required delivery date.
5. Available unreceived supply is `expected_quantity - quantity_reserved`. Backorder fulfillment additionally considers only supply that has arrived by `asOfDate`.
6. Unfilled quantity is saved as an open backorder. Only confirmed backorders contribute to projected shortage; shortages do not reduce physical stock.
7. New orders create unconfirmed temporary reservations. Only confirmed reservations leave projected physical stock on their effective delivery date.

Allocations retain source-level detail identifying the stock lot or inbound shipment. A new shipment is classified as inbound supply in the run that allocates it; its unreserved surplus becomes live stock when received and can be allocated as inventory by a later request.

Supply loading includes persisted unreceived shipment lines from earlier runs,
not only the shipments in the current request. Requests can therefore allocate
against previously registered supply.

No outer transaction spans the SKU workers. If one SKU fails, other committed
SKU results remain resumable; the order is not allocated all-or-nothing. If the
forecast fails after allocation commits, processing fails visibly and retry
reuses the allocation run before retrying projection. Projection invariant
failures are distinguished from invalid request input for RabbitMQ retry handling.

### Backorder fulfillment flow

The MCP `allocate_open_backorders` tool maps a `BACKORDER_FULFILLMENT` request to
`BackorderAllocationRequest` with trigger `MANUAL`. After a ledger receipt
commits, `BackorderAllocationJob` passes a command with trigger
`STOCK_LEDGER_RECEIPT` and affected `skuScope` through the same processor and
`BackorderAllocationService`. Neither path creates replacement order headers or
new TTL deadlines.

```mermaid
sequenceDiagram
    actor Caller
    participant Tool as AllocationTools
    participant Processing as AllocationProcessor
    participant Allocation as BackorderAllocationService
    participant Documents as AllocationDocumentService
    participant Worker as AllocationSkuWorker
    participant Projection as StockProjectionProcessor
    participant DB as PostgreSQL

    Caller->>Tool: allocate_open_backorders(request)
    Tool->>Processing: process(BACKORDER_FULFILLMENT request)
    Processing->>Allocation: allocate(BackorderAllocationRequest)
    Allocation->>Documents: Initialize run and register submitted inbound supply
    Documents->>DB: Persist registration without creating new demand
    Allocation->>DB: Select scoped or open-backorder SKUs plus prior-operation SKUs
    Allocation->>Worker: allocateBackorders for each selected SKU
    Worker->>DB: Lock SKU, backorders, stock and unreceived supply
    Worker->>Worker: Exclude expired or overdue unconfirmed orders
    Worker->>Worker: Allocate current stock and eligible arrived supply
    Worker->>DB: Increase reservations and reduce or delete backorder lines
    Worker-->>Allocation: Fulfillment results
    Allocation->>DB: Complete idempotent fulfillment run
    Allocation-->>Processing: AllocationRunResult
    Processing->>Projection: Refresh forecast after SKU commits
    Processing-->>Tool: AllocationRunResult
    Tool-->>Caller: AllocationRunResult
```

Backorders are processed by priority/FIFO, with document-line sequence preserving
separate repeated-SKU lines. Inbound supply must have arrived by `asOfDate` and
no later than the original required delivery date. Fulfillment keeps the
original order deadline, may be partial, and does not synchronize physical stock.
Repeated requests reuse saved run/SKU results rather than reserve twice.
The current fingerprint hashes an internal `AllocationRequest`, excluding the
command's `skuScope` and `trigger`; this is not full command-field validation.

### Product resolution and allocation flow

`POST /api/products/allocate` and `allocate_product_by_name` both call
`ProductAllocationService` synchronously. The MCP caller translates natural
language into a structured `ProductAllocationRequest`; the service does not
invent order identity, quantities, or dates.

```mermaid
sequenceDiagram
    actor Caller
    participant Adapter as Product REST endpoint or MCP tool
    participant Product as ProductAllocationService
    participant Ollama
    participant Tavily as Tavily Search API
    participant DB as PostgreSQL and pgvector
    participant Processing as AllocationProcessor

    Caller->>Adapter: ProductAllocationRequest
    Adapter->>Product: allocate(request)
    Product->>Product: Validate required order fields
    Product->>DB: Load active catalog
    alt Exact normalized title match
        Product->>Product: Select SKU without external calls
    else No exact match and equivalents disabled
        Product->>Product: Record unresolved outcome without external calls
    else Equivalents allowed
        Product->>Ollama: Embed query and missing or stale catalog descriptions
        Product->>DB: Refresh vectors and search catalog and cached knowledge
        alt Strong and separated local candidate
            Product->>Product: Select SKU without Tavily
        else Weak or ambiguous local match
            Product->>Tavily: Search product or nearest equivalent
            Tavily-->>Product: Evidence and source URLs, or lookup failure
            Product->>Ollama: Embed usable evidence, reusing query embedding
            Product->>DB: Search product and knowledge vectors
            Product->>Product: Check relevance, candidate score and score gap
            opt One SKU resolved with usable evidence
                Product->>Ollama: Embed source content chunks
                Product->>DB: Store knowledge with SKU and provenance
            end
        end
    end
    alt Resolution or knowledge ingestion did not succeed
        Product-->>Adapter: Non-allocation status and available candidates or sources
    else One SKU resolved successfully
        Product->>Processing: NEW_DEMAND request with deterministic order-line ID
        Note over Processing: Runs the allocation and projection flow
        Processing-->>Product: Actual AllocationRunResult
        Product-->>Adapter: Selected product, sources and allocation result
    end
    Adapter-->>Caller: ProductAllocationResponse
```

The request includes `productQuery`, `quantity`, `allowEquivalent`, `requestId`,
`orderId`, `customer`, `priority`, `placedDate`, `requiredDeliveryDate`, and
`asOfDate`. For example, "Hold 50 units of Maki Komkommer, or the nearest
equivalent" uses that phrase with `quantity: 50` and `allowEquivalent: true`.

Resolution is local-first. Exact normalized active-title matches skip Ollama and
Tavily. Otherwise, a local candidate scoring at least `0.90`, with a lead of at
least `0.08`, skips Tavily. Weak/ambiguous local results fall back to Tavily;
evidence must meet cosine relevance `0.25`, the candidate must score at least
`0.72`, and its lead must be at least `0.04`. These configurable defaults need
evaluation against representative queries.

Unresolved/ambiguous products and Tavily/Ollama or knowledge-ingestion failures
return explicit non-allocation outcomes. Local-only matches have no external
sources. Once resolved, normal partial-allocation and backorder rules apply:
the response never claims more quantity than the allocation actually reserved.
Invalid input returns HTTP `400`; allocation/projection failures remain processing
errors, not product-resolution success. No ledger synchronization occurs.

### Reservation expiry flow

Expiry is an independent recurring operation, not a consequence of receiving
another allocation request. Its batch boundary is an entire order header and
all that order's lines.

```mermaid
sequenceDiagram
    participant Runtime as JobRunr
    participant Job as ReservationExpiryJob
    participant Service as ReservationExpiryService
    participant Cleanup as ReservationCleanupService
    participant Projection as StockProjectionProcessor
    participant DB as PostgreSQL

    Runtime->>Job: execute()
    loop Process batches until a batch is smaller than the configured size
        Job->>Service: expireBatch()
        Note over Service,DB: One transaction per batch, including forecast replacement
        Service->>Cleanup: expireDueBatch()
        Cleanup->>DB: Acquire exclusive projection lock
        Cleanup->>DB: Lock overdue pending headers, bounded by order count
        Cleanup->>DB: Load all selected orders' lines and current reservation sources
        Cleanup->>DB: Release reserved counters and delete holds and backorders
        Cleanup->>DB: Mark headers EXPIRED and flush lifecycle changes
        Cleanup-->>Service: Number of expired orders
        opt At least one order expired
            Service->>Projection: process(today)
            Projection->>DB: Replace forecast in the same transaction
        end
        Note over Service,DB: Commit releases locks and triggers cleanup success logs
        Service-->>Job: Batch count
    end
```

New order headers are `PENDING_CONFIRMATION`. Their shared UTC deadline defaults
to 30 minutes after document initialization, independent of delivery dates. The
sweep defaults to every minute, with batches of 100 orders. Configure positive
values through `ALLOCATION_RESERVATION_TTL`,
`ALLOCATION_RESERVATION_EXPIRY_INTERVAL`, and
`ALLOCATION_RESERVATION_EXPIRY_BATCH_SIZE`.

An order is logically expired at its deadline, although background cleanup can
lag. Overdue headers cannot receive more allocations while waiting, and retries
cannot renew deadlines or recreate released holds. Expired order IDs cannot be
reused. See the [status flow](#status-and-freshness-read-flow) for the distinction
between logical expiry and completed cleanup.

Cleanup releases counters on the reservation's current source. If receipt
converted an inbound hold to a stock hold, cleanup releases the stock lot rather
than received inbound supply. Neither physical stock quantities nor expected
inbound quantities change.

There is currently no confirmation command, so new holds eventually expire.
Pre-feature claims preserved by V14 are `CONFIRMED` without TTL. See
[the expiry feature design](issues/feature_expire_allocation_reservations.md)
for lifecycle invariants and acceptance scenarios.

Missing reservation sources or insufficient reserved counters fail cleanup and
roll back the batch; cleanup does not silently discard inconsistencies.

### REST allocation submission and completion flow

The asynchronous REST interface acknowledges submission before allocation
finishes. Its job ID identifies background execution; the request's `requestId`
separately identifies the idempotent allocation run.

```mermaid
sequenceDiagram
    actor Client
    participant REST as AllocationController
    participant Runtime as JobRunr
    participant Job as AllocationJobExecutor
    participant Processing as AllocationProcessor
    participant Status as AllocationJobRunrService
    participant DB as PostgreSQL

    Client->>REST: POST /api/allocation/jobs (async=true by default)
    REST->>REST: Validate today's business date
    REST->>Job: executeAsync(request)
    Job->>Runtime: Directly enqueue executeSync(new jobId, request)
    Runtime->>DB: Store job
    Runtime-->>Job: Enqueue accepted
    Job-->>REST: AllocationJobAccepted(jobId, requestId)
    REST-->>Client: 202 Accepted, job ID and Location
    Runtime->>Job: executeSync(jobId, request)
    Job->>DB: Read any saved completion for job ID
    alt Completion already persisted
        Job->>Job: Deserialize AllocationCompletionResult with ObjectMapper
    else No saved completion
        Job->>Processing: Run allocation and projection flow
        Processing-->>Job: AllocationRunResult
        Job->>DB: Persist allocation_job_result JSON with jobId and allocationRun
    end
    Job-->>Runtime: Completion result
    Runtime->>DB: Record SUCCEEDED
    Client->>REST: GET /api/allocation/jobs/{jobId}
    REST->>Status: Read execution status
    Status->>Runtime: Look up JobRunr state
    opt Job succeeded
        Status->>DB: Load and deserialize saved completion
    end
    Status-->>REST: State and result when succeeded
    REST-->>Client: Job state and optional allocation completion
```

JobRunr retries failures up to three times. A saved completion is returned
without allocating/projecting again. If projection failed before completion was
saved, retry resumes the allocation run and refreshes the forecast without
reserving twice, as described in the [allocation flow](#allocation-flow).

A `SUCCEEDED` job without a saved completion is an explicit consistency error,
not an empty successful response. Completed JSON and run results describe what
was allocated at execution time; they do not prove those holds are still active.

HTTP submission has no Spring event or after-commit listener and no surrounding
application transaction. Enqueue errors propagate instead of returning `202`.
If enqueue succeeds but the HTTP response is lost, the caller has an unknown
outcome; resubmitting the same logical request may create another execution job,
while allocation-run idempotency prevents extra reservations. This direct
handoff is distinct from the ledger's post-commit scheduling gap.

### RabbitMQ intake and retry flow

Rabbit intake does not initially queue a JobRunr job. The listener attempts the
same `AllocationJobExecutor.executeSync` synchronously, using `requestId` as the job ID.

```mermaid
sequenceDiagram
    participant Producer as External producer or demo publisher
    participant Rabbit as RabbitMQ
    participant Listener as AllocationRabbitListener
    participant Job as AllocationJobExecutor
    participant Processing as AllocationProcessor
    participant Runtime as JobRunr

    Producer->>Rabbit: AllocationRequest JSON
    Rabbit->>Listener: receive(request)
    Listener->>Job: executeSync(requestId, request)
    Job->>Processing: Allocate and refresh forecast unless completion cached
    alt Successful processing
        Job-->>Listener: Saved completion
        Listener-->>Rabbit: Successful listener return permits acknowledgement
    else Invalid input - IllegalArgumentException
        Job-->>Listener: Invalid request
        Listener-->>Rabbit: Rethrow for rejection without requeue
        Rabbit->>Rabbit: Route rejected delivery to dead-letter queue
    else Other processing failure
        Job-->>Listener: Processing error
        Listener->>Runtime: Does a job with requestId already exist?
        opt No existing JobRunr job
            Listener->>Runtime: Directly enqueue executeSync with same requestId
        end
        Listener-->>Rabbit: Return after successful retry handoff
    end
```

Invalid requests are not retried by JobRunr. Other failures hand off only when a
job with that ID does not already exist; the existence check skips already
recorded jobs but is not itself an atomic business deduplication guarantee.
The request queue has a dead-letter exchange/queue, with rejected messages not
requeued by default. A failure while arranging fallback still propagates rather
than masquerading as completed allocation.

The current fallback lambda still contains logging in addition to the executor
call. Unlike the corrected HTTP enqueue lambda, this has not been verified
against JobRunr's actual lambda parser by the mocked listener tests and can fail
during enqueue. A single executor invocation is the supported scheduling shape;
successful unit tests alone do not prove durable fallback acceptance.

The optional `AllocationRequestPublisher` emits sample requests at a fixed interval when enabled. In the current `application.yml`, it is enabled by default with a one-second interval; disable it in non-demo deployments with `ALLOCATION_RABBITMQ_PUBLISHER_ENABLED=false`.

Its sample shipments arrive one to three days ahead, and generated orders are
due three days ahead with matched quantities. This makes inbound supply eligible
for immediate reservation; it does not postpone the order's 30-minute TTL.

### Stock projection flow

`StockProjectionProcessor` derives snapshots from persisted operational state.
Allocation calls it after SKU transactions commit; expiry and ledger processing
call it within their existing transaction.

```mermaid
sequenceDiagram
    participant Caller as Allocation, expiry or ledger processing
    participant Projection as StockProjectionProcessor
    participant Operational as Repository ports with JPA adapters
    participant Snapshots as StockSnapshotRepository
    participant DB as PostgreSQL

    Caller->>Projection: process(asOfDate)
    Projection->>Operational: Acquire exclusive projection lock
    Operational->>DB: Read live lots, unreceived supply, claims and active demand
    DB-->>Projection: Current operational baseline
    Projection->>Projection: Validate sources, lifecycle, lot identities and balances
    Projection->>Projection: Start at max(asOfDate, business today)
    Projection->>Projection: Simulate arrivals, holds and confirmed demand by day
    Projection->>Snapshots: replaceFrom(startDate, aggregateSnapshots, lotSnapshots)
    Snapshots->>DB: Delete lot and aggregate snapshots from startDate onward
    Snapshots->>DB: Batch insert replacement rows through JDBC
    Note over Projection,DB: Replacement and caller's changes share the transaction
    Projection-->>Caller: Forecast rebuilt without physical stock or lifecycle changes
```

#### Snapshot meaning

The `stock` table is the current physical baseline, by SKU, warehouse, and lot. The daily projection is stored in two tables:

- `stock_daily_snapshots`: aggregate balances by date, SKU, and warehouse.
- `stock_daily_lot_snapshots`: corresponding balances and reservations by date, SKU, warehouse, and lot.

For each day:

```text
closing_stock = opening_stock + inbound_qty - allocated_qty
demand_qty = allocated_qty + shortage_qty
```

The day's closing balance becomes the next day's opening balance. A confirmed backorder contributes to `shortage_qty` on its required delivery date but does not reduce physical stock. Only confirmed stock and inbound reservations reduce physical stock on their effective delivery date; pending reservations remain holds.

The horizon ends at the latest unreceived arrival, confirmed reservation delivery,
or confirmed backorder date, and includes at least the start date. Overdue events
are applied at the start, not reconstructed in the past. Pending delivery dates
do not extend the horizon, and future TTL release is not simulated. Earlier
snapshots are retained.

An unreceived shipment contributes its full expected quantity on arrival,
including reserved units. Pending inbound holds become projected lot reservations
on receipt but are not physical deliveries. Only confirmed backorders generate
shortage, assigned to warehouse `MAIN`.

Snapshot replacement deliberately uses JDBC, not managed snapshot entities:
rebuilding the same keys avoids JPA identity/bulk-delete conflicts without
flushing or clearing the processor's persistence context. Reads also use JDBC,
so ledger processing sees replacements made in the same transaction. The shared
datasource transaction rolls back both snapshot changes and JPA business changes
on failure; an empty replacement clears the current/future range.

#### Live quantity interpretation

For a live stock lot:

```text
quantity_available = quantity_on_hand - quantity_reserved
```

`quantity_on_hand` remains the physical quantity even while some units are reserved for future delivery. Before receipt, inbound supply is separate:

```text
inbound_quantity_available = expected_quantity - quantity_reserved
```

### Ledger synchronization flow

Ledger synchronization runs independently of allocation. Both scheduled and
manual entry points use the same processor and atomic synchronization logic.

```mermaid
sequenceDiagram
    participant Trigger as JobRunr or manual MCP caller
    participant Job as StockLedgerSyncJob
    participant Ledger as StockLedgerProcessor
    participant Cleanup as ReservationCleanupService
    participant Projection as StockProjectionProcessor
    participant Snapshots as StockSnapshotRepository
    participant Status as StockLedgerSyncStatusRepository
    participant Runtime as JobRunr
    participant Backorders as BackorderAllocationJob
    participant DB as PostgreSQL

    alt Scheduled invocation
        Trigger->>Job: Execute recurring sync-stock-ledger job
        Job->>Ledger: processToday()
    else Manual invocation
        Trigger->>Ledger: process(request with optional snapshotDate)
    end
    Ledger->>DB: Acquire exclusive projection lock
    Ledger->>Ledger: Resolve business today under lock and reject another explicit date
    Note over Ledger,DB: One transaction holds the lock through cleanup, projection and sync
    loop Until no overdue order batch remains
        Ledger->>Cleanup: expireDueBatch()
        Cleanup->>DB: Release overdue holds and persist expired lifecycle
    end
    Ledger->>Projection: process(today)
    Projection->>Snapshots: Replace current/future forecast in same transaction
    Ledger->>Snapshots: Read freshly generated daily and lot snapshots
    Snapshots->>DB: Read JDBC snapshot rows
    Ledger->>Ledger: Verify aggregate balances equal lot totals
    Ledger->>DB: Apply physical lot quantities and reserved counters
    Ledger->>DB: Consume due confirmed stock holds
    Ledger->>DB: Convert retained inbound holds to stock holds
    Ledger->>DB: Remove arrived inbound holds and mark supply received
    Ledger->>Status: Record successful sync timestamp, date and counts
    Status->>DB: Update singleton sync status in the ledger transaction
    Note over Ledger,DB: Commit all changes together or roll everything back
    opt Newly received shipment lines
        Ledger->>Runtime: After commit: enqueue stable command/job IDs for received SKUs
        Runtime->>Backorders: execute(BackorderAllocationRequest)
        Backorders->>Backorders: Allocate existing backorders and refresh projection
    end
    Ledger->>Ledger: Emit structured success log after commit
    alt Scheduled invocation
        Ledger-->>Job: Sync result
        Job-->>Trigger: Execution completed
    else Manual invocation
        Ledger-->>Trigger: Sync result
    end
```

`process_stock_ledger` accepts only today's `snapshotDate`; when omitted, it selects today using the business clock after acquiring the lock. Under one exclusive lock and transaction, it drains overdue reservation cleanup, rebuilds today's projection, and validates aggregate balances against lot-level balances. A freshly calculated empty projection is valid. Synchronization updates live lot quantities/reservations, removes due confirmed stock reservations, marks arrived shipments as received, and converts remaining inbound holds to stock reservations. Pending reservations are retained even when their delivery date has passed.

The sync transaction does not recalculate allocations or accept explicit
warehouse movements. After committing newly received supply, it enqueues
affected-SKU backorder fulfillment as separate work. Repeating sync is safe
because delivered reservations and received shipments are no longer projected
as new movements.

The callback derives stable request and job UUIDs from the snapshot date and
sorted received shipment-line IDs. It is not a durable outbox: a crash between
receipt commit and enqueue can lose the trigger, and enqueue failure cannot
roll back the receipt. A later ledger run sees those lines as already received
and need not recreate the trigger. Manual fulfillment remains a recovery option.

`StockLedgerSyncJob` registers the stable JobRunr recurring ID `sync-stock-ledger`. The default five-field cron `0 0,12 * * *` runs at midnight and noon UTC. The configured zone also supplies the shared business `Clock`, including REST date validation, sample publishing, CSV imports, expiry, and projection dates. Each scheduled invocation selects today after acquiring the ledger lock, so delayed retries do not reuse yesterday's allocation date.

Allocation and explicit backorder fulfillment refresh forecasts but never trigger physical synchronization. Ledger sync still rebuilds its own projection under the same transaction and lock immediately before applying it: an earlier forecast may contain expired or changed holds. Until sync runs, arrived supply remains inbound and due confirmed deliveries remain reserved in live stock. Expected arrival and due delivery are still date-driven assumptions, not warehouse event confirmations.

The default schedule normally allows up to roughly twelve hours of physical
ledger lag; it does not slow down allocation or the one-minute expiry sweep.
Each successful sync writes its timestamp, business date, SKU count, and lot
snapshot count in the same transaction. The lot count includes forecast lots;
it is not a count of newly received physical lots. Freshness interpretation is
described in the [status flow](#status-and-freshness-read-flow).

All pods must share JobRunr/application storage and identical schedule
configuration. Disabling scheduling removes the recurring definition, not
already queued/running executions. See
[the ledger scheduling feature](issues/feature_stock_ledger_sync_job.md)
for rollout and operational trade-offs.

#### Allocation, receipt, and delivery example

On day D, live lot `LOT-A` has 5 units and shipment `SHIP-B` brings 4 units
arriving on D. Two confirmed orders are due on D+1: `ORDER-1` requests 3 and
`ORDER-2` requests 4, with equal priority and placed date. This illustrates
confirmed commitments, not the default temporary holds; confirmation itself
remains a future feature.

| Order | Requested | From inventory | From inbound | Backordered |
| --- | ---: | ---: | ---: | ---: |
| `ORDER-1` | 3 | 3 | 0 | 0 |
| `ORDER-2` | 4 | 2 | 2 | 0 |

Before receipt, `LOT-A` has 5 on hand / 5 reserved / 0 available; `SHIP-B`
has 4 expected / 2 reserved / 2 available. The D sync creates `SHIP-B` as a
live lot: total stock becomes 9 on hand, 7 reserved, and 2 available. The
D+1 sync delivers the 7 confirmed units, leaving 2 units available for later
allocation as inventory. With pending orders, receipt still happens but delivery
does not: holds remain until their TTL cleanup instead.

### Status and freshness read flow

Reservation lifecycle and ledger freshness answer different questions. These
reads do not expire orders, rebuild forecasts, or synchronize stock.

```mermaid
sequenceDiagram
    actor Caller as Operator, MCP client or monitoring
    participant Adapter as REST, MCP or Actuator adapter
    participant Orders as OrderReservationService
    participant Ledger as StockLedgerService
    participant DB as PostgreSQL

    alt Reservation lifecycle request
        Caller->>Adapter: GET order reservation or get_order_reservation_status
        Adapter->>Orders: getOrderReservationStatus(orderId)
        Orders->>DB: Load order header and lines
        Orders->>Orders: Compare persisted deadline with current instant
        Orders-->>Adapter: Logical status, deadline, cleanupPending and lines
        Adapter-->>Caller: Current reservation status, or not found
    else Ledger freshness request
        Caller->>Adapter: GET stock-ledger/status or Actuator health
        Adapter->>Ledger: getStatusLastSyncRun()
        Ledger->>DB: Load monitoring baseline and last committed success
        Ledger->>Ledger: Calculate next expected sync and stale deadline
        Ledger-->>Adapter: Timestamp, date, counts and freshness
        Adapter-->>Caller: Status response or stockLedger health
    end
```

For an overdue pending order, status reports logical `EXPIRED` with
`cleanupPending: true` until the cleanup transaction commits. It does not perform
that cleanup, so inventory counters may still contain those holds briefly.
An unknown order returns REST `404`. Saved allocation results/reports are not a
substitute for this live read.

Ledger monitoring uses `lastSuccessAt`, or `monitoringStartedAt` before the first
success, as its baseline. The next expected cron occurrence plus
`freshnessTolerance` (default one hour) determines `staleAfter`. When enabled,
the Actuator `stockLedger` indicator is `UP` while fresh and `DOWN` after that
deadline. With scheduling disabled it is `UNKNOWN`, no freshness alarm is raised,
and manual synchronization remains available.

The singleton `stock_ledger_sync_status` is monitoring state, not a lock or a
once-per-day execution guard. Its success fields update only when the ledger
transaction commits. External monitoring must poll health/status and alert;
persisting this row alone does not deliver an alert.

### CSV import flow

The admin endpoint converts the configured CSV file into an allocation request.
Import does not register documents, reserve supply, or execute allocation.

```mermaid
sequenceDiagram
    actor Operator
    participant Admin as AllocationAdminController
    participant Import as AllocationExcelService
    participant File as Configured CSV file
    participant DB as Product repository and PostgreSQL

    Operator->>Admin: GET /api/admin/allocations/generate-request-by-csv
    Admin->>Import: Generate request from configured input
    Import->>Import: Capture business asOfDate
    Import->>File: Read bytes and calculate content hash, then parse rows
    loop Each input row
        alt Verkoop or Levering
            Import->>DB: Resolve product title to SKU, with per-import cache
            Import->>Import: Validate quantity and date, then create one document and line
            Import->>Import: Derive IDs from byte hash and row position
        else Unsupported transaction type
            Import->>Import: Warn and skip row
        end
    end
    Import->>Import: Validate rows and require at least one demand order
    Import-->>Admin: AllocationRequest with deterministic requestId
    Admin-->>Operator: Generated request without performing allocation
    Note over Operator,Admin: Caller may separately submit the allocation request
```

The expected columns, in order, are:

```text
Artikel,Transactie_type,QTY,Datum
```

`Verkoop` rows become single-line demand orders; `Levering` rows become single-line
shipments. Rows are not grouped by SKU or customer. Unknown transaction types
are skipped with a warning. Unknown products, malformed quantities/rows, invalid
dates, or a file without sales rows fail the import. Dates accept `yyyy-MM-dd`
or day-first `d-M-yyyy`.

Document and line IDs come from the CSV byte hash and row position; the request
ID also includes the captured `asOfDate`. Identical bytes on the same date replay
the same request regardless of path. On another date, document IDs still belong
to their original run and cannot allocate the transactions again. Changed bytes
represent a new import.

The default path is `docs/allocations.csv`; set `ALLOCATION_CSV_PATH` if the
input is elsewhere.

### Excel report flow

Reports describe a completed historical allocation run, not today's remaining
holds or the latest ledger state.

```mermaid
sequenceDiagram
    actor Caller
    participant Adapter as Admin endpoint or AllocationTools
    participant Runs as AllocationRunService
    participant DB as PostgreSQL
    participant Report as ExcelReportProcessor
    participant Files as Report directory

    Caller->>Adapter: ExcelReportRequest(runId, fileName)
    Adapter->>Adapter: Validate simple .xlsx filename and resolve output path
    Adapter->>Runs: Load saved allocation run
    Runs->>DB: Read persisted run, records and sources
    Runs-->>Adapter: Historical AllocationRunResult
    Adapter->>Report: Generate workbook from run
    Report->>Files: Write details, sources, pivots and formula summaries
    Report-->>Adapter: Generated report path
    Adapter-->>Caller: File path
    Note over Runs,Files: No reservations, projections or ledger changes
```

The workbook contains:

- `Allocation Details`: one row per order line, including quantities, status, and
  inventory/inbound sources.
- `Source Allocations`: one row per allocation source.
- `Pivot Summary`: pivots by SKU/status and customer/SKU backorders.
- `Formula Summary`: `SUMIFS` totals grouped by SKU/status.

The output directory defaults to `${user.home}/itzitzo-mcp/reports` and is
configurable with `REPORT_DIR`. The requested name must be a simple `.xlsx`
filename without a directory path.

### Job-record cleanup flow

The admin operation removes qualifying execution records from JobRunr storage;
it is not reservation expiry or an allocation rollback.

```mermaid
sequenceDiagram
    actor Operator
    participant Admin as AllocationAdminController
    participant Jobs as AllocationJobRunrService
    participant Storage as JobRunr StorageProvider

    Operator->>Admin: DELETE /api/admin/allocations/delete-jobs-with-excessive-retries
    Admin->>Jobs: deleteJobsWithRetriesByStatus()
    Jobs->>Storage: Read up to 100 PROCESSING and 100 SCHEDULED jobs
    Storage-->>Jobs: Jobs ordered by updatedAt ascending
    loop Each selected job
        Jobs->>Jobs: Count SCHEDULED, PROCESSING and FAILED state-history entries
        opt Count greater than two
            Jobs->>Storage: deletePermanently(jobId)
        end
    end
    Jobs-->>Admin: Cleanup finished
    Admin-->>Operator: Cleanup response
```

The threshold is based on counted state transitions, not a separate retry-count
field. Removing a job record does not release reservations, delete allocation
history, or guarantee cancellation of code already executing. Use this
administrative endpoint deliberately; normal reservation release follows the
expiry flow.

## Cross-cutting concurrency, idempotency, and rollout

Allocation runs are keyed by `requestId` and store a request hash. A retry with
the same ID/payload resumes or returns the existing run; conflicting reuse is
rejected. Per-SKU operation results and document ownership prevent duplicate
reservations.

`StockProjectionLockRepository` coordinates participating paths through the
singleton `stock_projection_lock` row (`id = 1`), not a lock on stock row 1.
SKU workers hold its shared lock and a per-SKU lock; distinct SKUs can proceed
concurrently. Document initialization, projection, expiry, and ledger sync use
the exclusive lock. Callers must have an active transaction, and a missing lock
row is an error. A worker never upgrades its shared lock to an exclusive one.

SKU concurrency is limited by `stockio.allocation.max-concurrent-skus` (default
`4`), configured through `ALLOCATION_MAX_CONCURRENT_SKUS`. The limit applies to
each service's dispatcher, not independently per request or globally across the
separate new-demand and backorder services or application instances. Each SKU
uses its own transaction; forecast replacement begins after the allocation
transactions finish.

Stable recurring IDs register one shared schedule definition across pods;
database locks and lifecycle checks make overlapping executions/retries safe.
Job names and recurring IDs are not exactly-once guarantees.

The nested line contract is a coordinated breaking change. V15 preserves legacy
run hashes as contract version 1; new runs use version 2. Legacy allocation
runs/results remain readable, but old requests cannot be submitted/resumed
through the nested contract. Drain old allocation jobs and reconcile unfinished
legacy runs before upgrading writers together.

Deploy lifecycle-aware allocation, projection, ledger, and expiry together; old
ledger code must not run concurrently with temporary holds.

The current allocation routes use `/api/allocation/jobs`, and dedicated reads
use `/api/reservations/orders/{orderId}/status` and `/api/stock/ledger/status`.
Old `allocate-project-and-sync`, `allocation-jobs`, and `/api/logistics/...`
routes are not compatibility aliases. The allocation-only job-result rollout assumes a recreated
application database, including JobRunr storage, plus coordinated caller updates.
Completion JSON now contains `jobId` and `allocationRun`, not a top-level
`version` or `stockSync`; the run's stored `contractVersion` is a separate schema
concern. Completion JSON uses the shared `ObjectMapper` directly; no adapter reads
historical combined allocation/sync job results.

## Persistence model

| Table | Responsibility |
| --- | --- |
| `products` | Product catalog and SKU reference data. |
| `product_embeddings` | Cached 768-dimensional Ollama embeddings for active catalog products, refreshed when product text or model changes. |
| `product_knowledge_chunks` | Tavily source text associated with a resolved SKU, with provenance, content hash, retrieval time, and a 768-dimensional embedding. |
| `stock` | Current physical quantity and future reservations by lot. |
| `stock_reservation` | Order reservations against live stock lots. |
| `inbound_shipment` | Shipment header identity and immutable line membership. |
| `inbound_supply` | Shipment lines with expected/received state, reserved quantities, and stable receiving-lot IDs. |
| `inbound_reservation` | Order reservations against unreceived shipments. |
| `allocation_run` | Idempotent run metadata, type, as-of date, completion state, and request hash. |
| `allocation_record` | Per-order-line allocation and backorder results. |
| `allocation_source` | Lot/shipment source detail for each allocation record. |
| `open_backorder` | Current unfulfilled quantity by order line. |
| `allocation_sku_lock` | Per-SKU serialization for allocation workers. |
| `allocation_sku_operation` | Per-run, per-SKU result used to resume work safely. |
| `demand_order` | Order header, original-run ownership, and shared confirmation/expiry lifecycle. |
| `demand_order_line` | Immutable line identity, SKU, requested quantity, delivery date, and document position. |
| `stock_daily_snapshots` | Aggregate daily stock trajectory. |
| `stock_daily_lot_snapshots` | Lot-level daily balances and reservations used by the ledger sync. |
| `stock_projection_lock` | Database row lock coordinating allocation, projection, and synchronization. |
| `stock_ledger_sync_status` | Monitoring baseline and last committed manual/scheduled ledger success. |
| `allocation_job_result` | Serialized `{jobId, allocationRun}` completion for executor calls, including synchronous RabbitMQ execution. |
| `flyway_schema_history` | Flyway migration metadata. |

Flyway migrations V1-V16 create and evolve these tables, seed products, add daily projections and concurrency support, remove the retired stock-ledger tables, enable pgvector product-resolution storage, add order-reservation expiry, migrate single-line documents to header/line relationships, and persist ledger-sync freshness. V15 preserves lifecycle timestamps, legacy line mappings, and received lots, and rejects inconsistent legacy reservation data. Hibernate validates the migrated JPA schema at startup; it does not create or alter tables. The local Compose and PostgreSQL Testcontainers images include pgvector.

## Configuration and operations

| Variable / property | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5437/logistics` | PostgreSQL JDBC URL for the pgvector-enabled local Compose database. |
| `DB_USERNAME` / `DB_PASSWORD` | `logistics` / `logistics` | Database credentials. |
| `DB_POOL_MAX_SIZE` | `10` | Hikari maximum pool size. |
| `DB_POOL_MIN_IDLE` | `2` | Hikari minimum idle connections. |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | `30000` | Time to wait for a pool connection. |
| `DB_POOL_IDLE_TIMEOUT_MS` | `600000` | Maximum idle connection time. |
| `DB_POOL_MAX_LIFETIME_MS` | `1800000` | Maximum connection lifetime. |
| `ALLOCATION_LEDGER_SYNC_ENABLED` | `true` | Register independent ledger synchronization; false deletes its recurring definition. |
| `ALLOCATION_LEDGER_SYNC_CRON` | `0 0,12 * * *` | Five-field cron for physical ledger processing. |
| `ALLOCATION_LEDGER_SYNC_ZONE` | `UTC` | Scheduler zone and shared business-clock zone. |
| `ALLOCATION_LEDGER_SYNC_FRESHNESS_TOLERANCE` | `1h` | Grace after the next expected sync before ledger health becomes stale. |
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8080` | HTTP bind address and port. |
| `ALLOCATION_MAX_CONCURRENT_SKUS` | `4` | Maximum concurrent SKU workers. |
| `ALLOCATION_RESERVATION_TTL` | `30m` | Temporary order-header hold duration, including SKU-processing time. |
| `ALLOCATION_RESERVATION_EXPIRY_INTERVAL` | `1m` | Recurring expiry sweep interval, independent of ledger sync. |
| `ALLOCATION_RESERVATION_EXPIRY_BATCH_SIZE` | `100` | Maximum overdue order headers cleaned up per expiry batch. |
| `ALLOCATION_CSV_PATH` | `docs/allocations.csv` | CSV input path. |
| `REPORT_DIR` | `${user.home}/itzitzo-mcp/reports` | Excel output directory. |
| `TAVILY_API_KEY` | empty (required for fallback searches) | Tavily API credential. |
| `TAVILY_BASE_URL` | `https://api.tavily.com` | Tavily API base URL. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama service URL. |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model; it must return 768 dimensions. |
| `PRODUCT_RESOLUTION_TIMEOUT` | `15s` | Connect/read timeout for Tavily and Ollama calls. |
| `PRODUCT_RESOLUTION_MAX_RESULTS` | `5` | Tavily search result count. |
| `PRODUCT_RESOLUTION_MAX_CANDIDATES` | `5` | Maximum product candidates used/returned. |
| `PRODUCT_RESOLUTION_MINIMUM_SIMILARITY` | `0.72` | Minimum score for non-exact product matches. |
| `PRODUCT_RESOLUTION_MINIMUM_SIMILARITY_GAP` | `0.04` | Required lead over the second candidate to accept an equivalent. |
| `PRODUCT_RESOLUTION_MINIMUM_LOCAL_SIMILARITY` | `0.90` | Minimum local vector score to bypass Tavily. |
| `PRODUCT_RESOLUTION_MINIMUM_LOCAL_SIMILARITY_GAP` | `0.08` | Required local lead over the second candidate to bypass Tavily. |
| `PRODUCT_RESOLUTION_MINIMUM_TAVILY_EVIDENCE_SIMILARITY` | `0.25` | Minimum cosine similarity between the requested phrase and Tavily evidence. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ server address. |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `stockio` / `stockio` | RabbitMQ credentials. |
| `ALLOCATION_RABBITMQ_EXCHANGE` | `stockio.allocation.exchange` | Allocation request exchange. |
| `ALLOCATION_RABBITMQ_ROUTING_KEY` | `allocation.request` | Allocation request routing key. |
| `ALLOCATION_RABBITMQ_REQUEST_QUEUE` | `stockio.allocation.requests` | Allocation request queue. |
| `ALLOCATION_RABBITMQ_DEAD_LETTER_QUEUE` | `stockio.allocation.dead` | Queue for rejected requests. |
| `ALLOCATION_RABBITMQ_PUBLISHER_ENABLED` | `true` | Enables the demo request publisher in the current application config. |
| `ALLOCATION_RABBITMQ_PUBLISHER_INTERVAL` | `1000` | Demo publisher interval in milliseconds. |
| `JOBRUNR_DASHBOARD_PORT` | `8181` | JobRunr dashboard port. |
| `JOBRUNR_DASHBOARD_ENABLED` | `true` for a standalone service | Overrides dashboard startup; the API's STDIO subprocess explicitly sets it to `false` to avoid port conflicts. |

The sample API launches the service as an MCP STDIO subprocess on an ephemeral
HTTP port with the dashboard disabled; background JobRunr execution remains
enabled. An existing standalone dashboard on `8181` does not need to be stopped
for that subprocess to initialize.

Local Docker Compose includes a one-shot `ollama-pull-model` service. It waits
for Ollama's healthcheck, pulls `nomic-embed-text` over the internal Ollama URL,
and exits. Models persist in the `ollama-data` volume. Server health alone does
not establish model readiness; wait for successful model initialization.

Spring Boot Actuator exposes `health` and `metrics` on the application HTTP port. Useful Hikari metrics include:

```text
/actuator/metrics/hikaricp.connections
/actuator/metrics/hikaricp.connections.active
/actuator/metrics/hikaricp.connections.idle
/actuator/metrics/hikaricp.connections.pending
/actuator/metrics/hikaricp.connections.max
```

The metrics endpoint is operational information; keep it on a trusted interface or protect it with the deployment's access controls. Size the connection pool against PostgreSQL's connection limit across all application instances, not only one process.

## Tests

Run the service test suite with:

```sh
./gradlew :stockio-service:test
```

The integration tests use Testcontainers PostgreSQL to verify persistence, migrations, allocation, projections, and synchronization behavior.
