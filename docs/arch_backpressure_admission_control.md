# Cross-cutting Backpressure and admission control

This is how allocation load is limited today. Two guards can reject or defer work before it sits on a database lock, and both are off unless configured. The limits that always run are the per-SKU database locks, the in-process SKU dispatcher, and the Kafka consumer's worker channel. [Blocking HTTP](#blocking-http) moves blocking calls off the Netty event loop. It does not reject or defer them.

A shared database-work budget, a caller rate limit, and a cap on the durable backlog are not built. They are listed under [Not implemented](#not-implemented).

## Implemented concurrency, idempotency, and rollout

### Request path

`POST /api/allocation/jobs` accepts work in two modes.

`async=true` validates the payload, publishes it to Kafka topic `stockio.allocation.requests`, and returns `202` after the broker acknowledges. Each call gets a new job id. Repeating the same `requestId` publishes another record. The consumer is on unless `ALLOCATION_KAFKA_ENABLED=false`. The job is not tracked until the listener takes the record.

`async=false` runs the allocation on the request thread and returns `202` only after that work finishes. The `Location` uses the allocation run id. No JobRunr job is created, so that location is not a JobRunr status. With the Redis fast path enabled, this mode is rejected with `409` `REDIS_SYNC_DISABLED`; only `async=true` is accepted, and the Redis hold is taken before the Kafka publish.

The Kafka listener (`listener-concurrency` 2, `worker-count` 2, `channel-capacity` 4) runs the same synchronous executor. The RabbitMQ listener also starts and runs that executor. RabbitMQ is not the HTTP intake path.

Business identity is `requestId` plus a stored request hash. The same payload resumes the existing run. A different payload for that id is rejected. A completed job id is returned from `allocation_job_result` and is not executed again.

### What always limits concurrency

Writers coordinate per SKU in PostgreSQL, across warehouses and service instances. One transaction holds a given SKU. Independent SKUs proceed together. The old global `stock_projection_lock` is not taken by any production path.

Lock order:

1. Registration locks sorted document identities, then sorted inbound SKU rows, then the run row, then business rows. Order-only documents do not take SKU locks at registration, so two orders for the same SKU can register at the same time.
2. A SKU worker locks its SKU before it reads availability. Each SKU is its own transaction.
3. Projection, ledger SKU work, and amendments take that same SKU lock.
4. Whole-order expiry locks every SKU on the order, in sorted order, then refreshes the affected forecasts in that transaction.
5. Run completion takes no SKU locks.

Document lock rows are inserted with `ON CONFLICT DO NOTHING` and then locked. Namespaces keep order, order-line, shipment, and shipment-line ids distinct.

`AllocationService` runs SKU workers on `Dispatchers.IO.limitedParallelism`, default 4 (`ALLOCATION_MAX_CONCURRENT_SKUS`). That cap is per process and shared by new-demand requests in that process. It is not a queue, and it is not shared with the backorder service, other instances, registration, or projection. Projection of the SKUs touched by a request runs after those allocation transactions commit, and it does not use the four-worker dispatcher.

Hikari defaults to 10 connections and a 30 second acquisition timeout. A transaction that already holds a connection can wait on a SKU lock for as long as PostgreSQL allows, unless lock-contention control is enabled. Pool exhaustion is often that lock wait, not a pool that is simply too small. `max-concurrent-skus` does not cap registration, projection, ledger, expiry, status reads, or JobRunr.

### Opt-in admission

`stockio.allocation.admission.enabled` defaults to false (`ALLOCATION_ADMISSION_ENABLED`). Enabling it requires every limit below to be an explicit positive number. At the defaults the process refuses to start with admission turned on.

`AllocationAdmission` is one `Semaphore` per instance. `tryAcquire()` either admits the call or throws `AllocationCapacityExceededException`. Nothing waits for a permit. The permit covers allocation, the synchronous projection of affected SKUs, and writing the job result. HTTP, MCP, RabbitMQ, Kafka, JobRunr, and receipt-triggered backorders share it. Product resolution, cached result reads, ledger, expiry, and status reads do not take it.

When admission is on, these checks run before writes and before an async publish:

| Check | Over limit |
| --- | --- |
| In-flight orchestrations (`max-in-flight`) | HTTP `503` `ALLOCATION_CAPACITY_EXHAUSTED` |
| Orders, shipments, demand lines, inbound lines, distinct SKUs | HTTP `400` |
| Request bytes (`max-request-bytes`) | HTTP `413` `ALLOCATION_REQUEST_TOO_LARGE` |

HTTP and RabbitMQ compare the raw body before JSON parsing. MCP and in-process DTOs are measured by canonical serialization after they already exist as objects. The Next.js process is separate and needs the same flag and byte limit if it should reject there. Forecast horizon and the set of backorders discovered from the database are not capped by these counts.

Kafka and RabbitMQ do not answer HTTP for this case. On capacity or lock contention they keep the same delivery and pause 5–10 seconds, then try again. That pause does not hold a permit or a database connection. Other failures are enqueued to JobRunr. Capacity and lock contention are not. Invalid payloads and oversized RabbitMQ or Kafka messages are dead-lettered.

While admission is enabled, the RabbitMQ listener uses one consumer, prefetch 1, and auto-ack. The unacknowledged message stays with that consumer during the pause. Shutdown or interruption requeues it. With admission off, those container settings are left at the Spring defaults, and the capacity exception is never thrown.

JobRunr jobs use `AllocationCapacityRetryFilter`. Capacity and lock-contention failures are scheduled again after 5–10 seconds and do not consume the three business retries. Other failures use exponential backoff and those three retries.

Meters, when the registry is scraped: `stockio.allocation.admission.active`, `.limit`, `.enabled`, admission attempt counters, request-rejected counters, and execution and phase timers. They do not record queue age or time spent waiting on a SKU lock.

### Opt-in lock-contention control

`stockio.allocation.lock-contention.enabled` defaults to false. When enabled, each SKU allocation, registration, and projection transaction runs:

```text
SET LOCAL lock_timeout = '<ALLOCATION_LOCK_TIMEOUT, default 2s>'
SET LOCAL statement_timeout = '<ALLOCATION_STATEMENT_TIMEOUT, default 20s>'
```

Lock wait (`55P03`), statement timeout (`57014`), and deadlock (`40P01`) become `AllocationLockContentionException`. HTTP maps that to `503` `ALLOCATION_LOCK_CONTENTION`. Kafka, RabbitMQ, and JobRunr defer it the same way they defer a full semaphore. Allocation results are unchanged. With the flag off, a lock wait holds the connection until PostgreSQL or the pool gives up.

### Ledger coordination

Ledger sync is a separate single-writer lease, not an allocation permit and not the retired projection gate. `stock_ledger_coordinator` allows one owner at a time, renewed on a five-minute lease. Other callers poll outside a transaction. A ledger worker locks its SKU first, then checks the owner and the work checkpoint. Allocation, projection, and expiry do not lock that coordinator row.

### Example: orders A and C share SKU 1, order B uses SKU 2

Distinct request, order, and line ids. No inbound amendment. A acquires SKU 1 first.

| Phase | A: order 1, SKU 1 | B: order 2, SKU 2 | C: order 3, SKU 1 |
| --- | --- | --- | --- |
| Registration | Proceeds. No SKU lock. | Proceeds. | Proceeds, including while A later holds SKU 1. |
| SKU allocation | Holds SKU 1 and allocates. | Holds SKU 2 and allocates. | Waits for SKU 1. |
| After A commits | Releases the SKU 1 lock. | Can commit on its own. | Locks SKU 1, sees A's reservations, then allocates. |

Arrival order does not decide the winner. If A's reservation uses the supply, C is partial or backordered. Later projection or ledger work for SKU 1 takes the same lock. SKU 2 does not wait for it. Expiry of a multi-SKU order holds every SKU on that order together.

### Rollout

Stop writers, migrate, deploy, and restart them together, including the Kafka consumer and JobRunr. A process that still takes the global projection lock cannot run beside this protocol.

Current routes are `POST /api/allocation/jobs` and `GET /api/allocation/jobs/{jobId}`. Reservation reads are `GET /api/reservations/orders/{orderId}/status` and `GET /api/reservations/orders/{key}/redis`, both on `ReservationController`. Ledger status is `/api/stock/ledger/status`. The old `allocate-project-and-sync`, `allocation-jobs`, and `/api/logistics/...` paths are not aliases.

Completion JSON is `jobId` plus `allocationRun`. It has no top-level `version` or `stockSync`. Stored `contractVersion` is separate: V15 keeps legacy run hashes as version 1, and new runs use version 2. Legacy rows stay readable. Old request bodies are not accepted or resumed.

## Blocking HTTP

The service is WebFlux on Netty. JDBC, the Redis sync client, Kafka publish, JobRunr, CSV and Excel, and the Tavily and Ollama `RestClient`s still block until they return. `BlockingHttp` (`nl.stockio.config.BlockingHttp`) runs that work on one Reactor scheduler named `blocking-http`, so a blocked call does not occupy an event-loop thread.

The scheduler is `Schedulers.newBoundedElastic`. Its thread cap is `availableProcessors * 10`, and at least 16. The queue cap is `Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE`, 100_000 unless `reactor.schedulers.defaultBoundedElasticQueueSize` overrides it. Past that cap, Reactor rejects the submission. That rejection is a scheduler error. It is not `503` `ALLOCATION_CAPACITY_EXHAUSTED`, and it does not take or release the admission semaphore.

The pool is larger than Hikari's default of 10 because a synchronous allocation holds one of these workers inside `runBlocking` for the whole request. The SKU workers themselves run on `Dispatchers.IO.limitedParallelism` and take the database connections. The parked HTTP worker is waiting, not querying. Hikari, and admission when it is enabled, remain the throughput limit. The `blocking-http` threads are not.

Two methods share the scheduler:

- `call` returns a `Mono` and runs the action when WebFlux subscribes. `StockController` and `AllocationAdminController` use it.
- `await` is the suspend form and moves the coroutine onto the same scheduler. `AllocationController`, `ReservationController`, and `ProductAllocationController` use it.

Those three generated controllers default the parameter to `BlockingHttp.immediate()`, which runs on the calling thread. Spring injects the pooled bean when it creates them. A unit test that constructs the controller itself keeps the immediate scheduler. `StockController` and `AllocationAdminController` require the bean.

Kafka and RabbitMQ listeners do not use this scheduler. They run on their consumer threads. MCP tool methods are not wrapped by `BlockingHttp`. A product-allocation call still occupies one `blocking-http` worker for the whole `await`, including the blocking Tavily or Ollama `RestClient` round trip inside it.

The scheduler bean uses `destroyMethod = "dispose"`, so shutdown disposes the pool with the application context.

## Not implemented

These are specified elsewhere. Nothing in the running service does them.

- A [shared database-work budget](../.github/memory/issues/feature-shared-database-work-budget.md) that reserves pool capacity for ledger and expiry (Scenario 17). The admission semaphore does not do this. It counts allocation orchestrations on one instance.
- A caller or service rate limit, and any `429` response for allocation intake.
- A bound on outstanding Kafka, RabbitMQ, or JobRunr work, and deduplication of queued jobs by `requestId`. Business idempotency applies when a job runs, not when it is published.
- Coalesced or asynchronous forecast refresh. Projection still runs synchronously for the SKUs of the request that just committed.
- Virtual threads, R2DBC, a larger pool, or more replicas as a capacity design. Extra replicas add their own dispatchers and connections. Hot SKUs still serialize in PostgreSQL.

Configuration for the two opt-in guards is in the [service README](../stockio-service/README.md#opt-in-allocation-admission). The admission feature record is [fail-fast allocation admission](../.github/memory/issues/feature-fail-fast-allocation-admission.md). The lock protocol record is [per-SKU stock processing](../.github/memory/issues/feature-per-sku-stock-processing.md).
