<!-- e5f7c3a9-2c44-4f81-a0d6-6b7c8d9e0f1a -->
---
todos:
  - id: "r2dbc-pool"
    content: "Add an R2DBC pool beside Hikari and Flyway, with no shared transaction across the two drivers"
    status: pending
  - id: "sku-transaction"
    content: "Move SkuAllocationProcessor onto TransactionalOperator and run SET LOCAL on that same R2DBC connection"
    status: pending
  - id: "flatmap-skus"
    content: "Replace runBlocking in AllocationService and AllocationBackorderService with Flux.flatMap limited by max-concurrent-skus"
    status: pending
  - id: "remaining-repos"
    content: "Migrate the remaining JPA and JdbcTemplate repositories one at a time, then remove Hikari and JPA"
    status: pending
  - id: "job-boundary"
    content: "Keep a single runBlocking boundary at JobRunr and the Kafka listener until those callers become publishers"
    status: pending
isProject: false
---

# PostgreSQL and runBlocking

Predecessor: [WebMVC to WebFlux](WebMVC%20to%20WebFlux-e4b8c2a1.plan.md). This is its own feature and starts last. Tavily, Redis, and Kafka can already be publishers. JDBC and JPA cannot become non-blocking in place.

Flyway stays on JDBC. Hikari stays until the last JDBC caller is gone. A repository that still uses JDBC and a repository that uses R2DBC do not share a transaction.

## What pins threads today

`AllocationService.runSkuWorkers` and `AllocationBackorderService.runSkuWorkers` call `runBlocking`. Each SKU then runs on `Dispatchers.IO.limitedParallelism`, configured by `stockio.allocation.max-concurrent-skus` (default 4). The worker is occupied for the whole database call.

Each SKU enters through [`SkuAllocationProcessor`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/processor/SkuAllocationProcessor.kt) methods annotated `@Transactional`. [`AllocationLockContentionPolicy.apply`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/performance/AllocationLockContentionControl.kt) requires that thread-local transaction and runs:

```sql
SET LOCAL lock_timeout = '...'
SET LOCAL statement_timeout = '...'
```

`SET LOCAL` applies only to the connection that executes it. The following `lockSku` must use that same connection.

JDBC and JPA callers that stay blocking until their own step:

- JPA repositories in `AllocationJpaRepositories` and `StockJpaRepositories`, plus reservation repositories
- `StockLedgerSyncStatusJdbcTemplateRepository`, `StockSnapshotRepositoryJdbcTemplate`, `StockScopeJdbcRepository`, `StockLedgerRunJdbcRepository`
- `ProductVectorJdbcTemplateRepository`
- `AllocationLockContentionPolicy`

`@Transactional(propagation = MANDATORY)` on ledger and snapshot repositories means the caller already owns the transaction. A reactive replacement has to carry that same connection through `TransactionalOperator`, not open a second one.

JobRunr's `executeSync` and the Kafka listener call this domain on their own threads. They keep one `runBlocking` (or `block`) at that boundary until those hosts become publishers. The HTTP path does not add another one.

## Target shape

SKU allocation is a `Mono` bound to one reactive transaction:

```kotlin
transactionalOperator.transactional(
    Mono.fromCallable { /* pure allocation math */ }
        .subscribeOn(boundedElastic) // only if the math is CPU work
)
```

Database reads and writes inside that transaction use R2DBC (`DatabaseClient` or a Spring Data R2DBC repository) on the connection `TransactionalOperator` put in the Reactor context. `AllocationLockContentionPolicy` runs its two `SET LOCAL` statements on that connection before `lockSku`.

The fan-out becomes:

```kotlin
Flux.fromIterable(skus)
    .flatMap({ sku -> skuAllocation.process(...) }, maxConcurrentSkus)
    .collectList()
```

`maxConcurrentSkus` stays `stockio.allocation.max-concurrent-skus`. `runBlocking` and `Dispatchers.IO.limitedParallelism` leave `AllocationService` and `AllocationBackorderService`. `@Transactional` leaves `SkuAllocationProcessor`, because a thread-local transaction does not cover a `Flux` that resumes across connections.

Sorted SKU lock order, idempotent run completion, and the existing `503` lock-contention mapping stay. SQL states `55P03`, `57014`, and `40P01` still become `AllocationLockContentionException`.

## Steps

1. **Second pool.** Add `spring-boot-starter-data-r2dbc` and the PostgreSQL R2DBC driver to `stockio-service`. Point it at the same database as `DB_URL`. Give it its own max size. Keep Flyway and Hikari. Do not enlist one business operation in both pools.
2. **SKU transaction.** Rewrite `SkuAllocationProcessor.process` and `allocateBackorders` as `Mono` pipelines inside `TransactionalOperator`. Move `SET LOCAL lock_timeout` and `SET LOCAL statement_timeout` onto that R2DBC connection when `stockio.allocation.lock-contention.enabled` is true. Keep the check that the statement runs inside the transaction. Map the same SQL states to `AllocationLockContentionException`.
3. **Remove `runBlocking` from the two services.** Replace both `runSkuWorkers` helpers with `Flux.flatMap` limited by `maxConcurrentSkus`. `AllocationService` and `AllocationBackorderService` return `Mono`. HTTP controllers flatMap that publisher. Do not wrap it in `fromCallable`.
4. **Remaining repositories, one at a time.** Convert a port only when every caller of that port can compose a `Mono`. Start with the repositories the SKU transaction calls (`AllocationSkuLockRepository`, stock, inbound supply, reservations, backorders, run lifecycle). Then ledger JDBC, snapshot JDBC, and `ProductVectorJdbcTemplateRepository`. Preserve `MANDATORY` by requiring the caller to pass an open `TransactionalOperator` transaction. Leave a repository on JDBC until its caller is migrated.
5. **Remove JDBC from the service path.** When no service repository uses `JdbcTemplate` or JPA, remove Hikari and `spring-boot-starter-data-jpa` from `stockio-service`. Flyway keeps a JDBC URL for migrations. JobRunr's storage stays on its own JDBC setup if JobRunr still requires it.
6. **Job and listener boundary.** `AllocationJobExecutor.executeSync` and the Kafka listener may `runBlocking` once around the domain `Mono`. They do not call `runBlocking` inside a SKU. Deleting that outer boundary belongs to a later JobRunr or `reactor-kafka` change.

## Verification

- The same allocation request still persists one run, the same reservation and backorder rows, and the same completion result.
- Two SKUs for one request run with concurrency at most `max-concurrent-skus`, and locks are still taken in sorted SKU order.
- With lock contention enabled, a lock wait beyond `lock_timeout` still returns `503` `ALLOCATION_LOCK_CONTENTION` on the inline path.
- A test that starts a reactive transaction and then a JDBC write sees them commit and roll back independently.
- Flyway still migrates on startup.
- An HTTP allocation does not sit inside `runBlocking` on a Netty thread or on the dedicated blocking scheduler.

## Risks

- `SET LOCAL` on a different connection from `lockSku` leaves the lock wait unbounded and breaks the `503` budget.
- `flatMap` without the concurrency argument, or with a larger value than `max-concurrent-skus`, exceeds the current SKU parallelism and the database lock protocol.
- A JDBC `@Transactional` method that calls a reactive repository, or the reverse, splits one business change across two connections. A failure then commits one side.
- `Propagation.MANDATORY` repositories fail closed today when no transaction exists. Opening a fresh R2DBC transaction inside them hides that caller bug.
- R2DBC does not raise Hikari's ceiling. PostgreSQL locks and the pool size still bound allocation throughput.
- JobRunr and the Kafka listener still hold a thread for the whole domain `Mono` until their own plans remove that outer `runBlocking`.
