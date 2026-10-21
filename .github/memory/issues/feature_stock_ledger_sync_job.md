# Feature: separate scheduled stock-ledger synchronization from allocation

## Status and scope

Implemented. Allocation completion and physical ledger synchronization are separate.

The independent JobRunr job uses recurring ID `sync-stock-ledger`, defaults to
`0 0,12 * * *` in UTC, and has three retries. Configuration is available through
`ALLOCATION_LEDGER_SYNC_ENABLED`, `ALLOCATION_LEDGER_SYNC_CRON`,
`ALLOCATION_LEDGER_SYNC_ZONE`, and `ALLOCATION_LEDGER_SYNC_FRESHNESS_TOLERANCE`
(default `1h`). The zone also configures the shared business clock.

`AllocationProcessingService` refreshes projections after allocation or explicit
backorder fulfillment. Completion JSON is version 2 and contains `allocationRun`,
not `stockSync`. Job results use direct `ObjectMapper` serialization, without a
legacy codec, because rollout assumes a recreated database and JobRunr storage.
The new REST routes are `/api/logistics/allocation-jobs` and
`/api/logistics/allocation-jobs/{jobId}`.

Migration V16 adds committed ledger-success monitoring.
`GET /api/logistics/stock-ledger/status` exposes freshness; the Actuator
`stockLedger` health component reports a missed schedule after the configured
tolerance. Sync selects today's date after obtaining the exclusive lock and
continues rebuilding its projection atomically before applying live changes.
Omitted manual sync dates also use the business clock after locking. Projection
invariant failures after allocation are classified as processing failures, not
invalid RabbitMQ input, so they can use the existing JobRunr retry path.

The design and implementation rationale follow. The "Current execution paths"
section describes the pre-feature behavior.

Separate demand allocation from physical stock synchronization. Allocation should
not wait for receipt and delivery processing, and a successful allocation should
not imply that the live stock ledger has been synchronized.

Introduce a recurring JobRunr job for ledger synchronization. Keep a fresh,
transactional projection rebuild immediately before applying ledger changes.
Continue refreshing forecasts after allocation and expiry for visibility.

This feature does not implement order confirmation, real warehouse receipt or
dispatch events, or automatic backorder fulfillment.

## Why separate these operations?

The application currently combines three different responsibilities:

| Operation | What it changes | Meaning |
| --- | --- | --- |
| Allocation | Reserved counters, reservation rows, and open backorders | Supply is held for demand; physical quantity does not leave stock. |
| Projection | Derived daily aggregate and lot snapshots | A forecast of arrivals, confirmed deliveries, shortages, and remaining holds. |
| Ledger synchronization | Live stock quantities, received shipment state, and consumed/converted reservations | Due receipt and confirmed delivery movements are applied to the physical baseline. |

An order can be allocated now for delivery several days later. Creating its
reservation is an immediate operation, but receiving a shipment or processing a
delivery need not happen on the same request.

Currently, every successfully processed allocation in the REST/background and
RabbitMQ execution path can trigger a global projection rebuild and ledger sync.
That introduces additional latency and exclusive-lock contention, and ties
physical stock processing to the arrival of new demand. If no demand arrives,
that path does not independently advance the ledger.

Separating the operations gives each its own trigger, completion semantics,
failure handling, and operational schedule.

## Current execution paths

### REST allocation

`AllocationController` exposes `POST /api/logistics/allocate-project-and-sync`.
It publishes an event and returns `202 Accepted` with a job ID. After commit,
`AllocationEventListener` enqueues `AllocationJob`.

`AllocationJob.execute` calls `AllocationService.allocation`, followed by
`AllocationJobCompletionService.complete`. The completion service:

1. Returns a cached job result if one exists.
2. Calls `StockLedgerProcessor.process` using the allocation request's date.
3. Stores and returns `AllocationAndSyncResult`.

The REST operation is therefore already asynchronous from the HTTP caller's
perspective.

### RabbitMQ allocation

`AllocationRabbitListener` calls `AllocationJob.execute` synchronously inside
message processing. On eligible processing failures, it schedules a JobRunr
fallback. This path currently waits for both allocation and ledger sync.

### Other callers

The MCP `allocation_and_stock_projection` tool and product allocation already
refresh projections without synchronizing physical stock. The backorder tool
currently allocates without rebuilding projections. These paths must be reviewed
so their forecast-refresh behavior is explicit and consistent.

### What "synchronous allocation" means here

The core allocation call waits for its SKU workers and returns an allocation
result. This feature preserves that behavior and removes the ledger dependency.
It does not change the existing REST transport from `202` to a synchronous
`200` response. Such an HTTP contract change would be a separate decision.

## Execution model

```text
New demand / explicit backorder fulfillment
    -> allocate and persist reservations/backorders
    -> refresh projection
    -> record allocation completion / return result

Recurring reservation-expiry job
    -> expire overdue unconfirmed holds
    -> refresh projection in the same transaction

Recurring stock-ledger job, or explicit manual invocation
    -> acquire exclusive projection lock
    -> determine/validate today's date
    -> expire all overdue holds
    -> rebuild today's projection from current state
    -> apply due receipts and confirmed deliveries
    -> commit
```

The allocation path does not call `StockLedgerProcessor`.

### Allocation and projection orchestration

Keep the forecast refresh after allocation for the first implementation. This
ensures users see a forecast that includes the allocation before its processing
is reported complete. Refreshing the forecast asynchronously can be introduced
later if a measurable lag is acceptable.

Use a shared allocation-and-projection orchestration entry point for the
REST/background, RabbitMQ, MCP, and product callers. It should delegate to the
existing allocation services rather than duplicate their rules. Explicit
backorder fulfillment should also refresh projections after changing holds.

Do not run projection rebuilding inside a SKU worker: allocation holds the shared
projection lock, whereas rebuilding requires the exclusive lock. Finish the
per-SKU transactions before acquiring that exclusive lock.

Do not wrap the entire parallel allocation flow in a new outer transaction.
Existing per-SKU commit and resume behavior must remain intact.

If allocation commits but projection refresh fails, report processing failure
and allow retry. Retry must reuse the saved allocation rather than reserve supply
again, then retry projection and completion recording.

## Why retain the projection rebuild inside ledger synchronization?

An after-allocation forecast is useful for visibility, but is not a safe input to
a ledger operation several hours later.

Since that forecast was generated:

- Reservation expiry may have released holds.
- Other allocations may have reserved stock.
- Shipment quantities or supply records may have changed.
- Shipments may already have been received by a manual sync.
- The current date may have advanced, even if no new orders arrived.

For example, suppose a snapshot contains a hold of 5 units. The order expires,
releasing those 5 units. Applying that old snapshot later could restore a reserved
counter for a reservation that no longer exists.

The ledger currently derives physical movements from projections. Therefore,
`StockLedgerProcessor.process` must continue to drain overdue cleanup, rebuild
the projection, and apply its balances under one exclusive lock and transaction.
There must be no unlocked gap between rebuilding and applying those balances.

The two refreshes serve different purposes:

| Refresh | Purpose |
| --- | --- |
| After allocation | Keep the displayed forecast current. |
| Immediately before ledger sync | Ensure applied balances represent the current authoritative state. |

`StockSnapshotRepository` continues using JDBC for snapshot reads and replacement.
Orders, reservations, live stock, and the projection coordination lock remain on
JPA. Both persistence mechanisms participate in the same transaction.

## New recurring JobRunr job

Add `StockLedgerSyncJob` under `allocation/domain/job`.

Responsibilities:

1. Register a recurring schedule at application startup with the stable ID
   `sync-stock-ledger`.
2. At execution time, obtain today's date using the shared injected `Clock`.
3. Call the transactional `StockLedgerProcessor.process`.
4. Record/log the resulting `StockSyncResult`, duration, and failures.

Use a normal JobRunr job with bounded retries, following the existing retry
convention of three retries. Do not catch exceptions and return a success-shaped
result. A failed synchronization must remain visible in JobRunr.

Do not schedule `AllocationJobCompletionService` itself: it represents completion
of a particular allocation, not a recurring operation over the global ledger.

### Dates, time zones, and delayed executions

Never persist an allocation request's `asOfDate` as the recurring job's future
sync date. A delayed invocation or retry after midnight must evaluate today again.
The ledger's existing today-only validation remains.

Use the same configured business zone for scheduling and the injected `Clock`.
The zone affects calendar dates; reservation TTL remains elapsed time measured
using `Instant`.

Scheduled execution selects today inside the locked ledger entry point, so
crossing midnight while waiting for the lock does not reuse an obsolete date.
Manual requests with an explicit date still reject yesterday after midnight;
manual requests without a date select today under the lock.

### Configuration

Add validated properties under `stockio.allocation.ledger-sync`:

```yaml
stockio:
  allocation:
    ledger-sync:
      enabled: true
      cron: "0 0,12 * * *"
      zone: "UTC"
```

The defaults are twice daily, at 00:00 and 12:00 UTC, using a five-field cron
expression. Confirm that this cadence satisfies stock-freshness requirements
before deployment. The zone configures both the scheduler and the shared
business `Clock`, rather than changing only the scheduler's interpretation.

Validate cron and zone configuration at startup. When scheduling is disabled,
remove an existing recurring definition with this ID from shared storage;
simply skipping registration would leave an earlier schedule active. Disabling
the schedule does not cancel an execution already queued or running. Keep
configuration consistent across pods.

### Multi-pod behavior and retries

All pods must use the same JobRunr storage and application database. Startup
registration uses the same recurring ID, not a pod-specific ID.

Neither the display name in `@Job` nor the recurring ID is an exactly-once
execution guarantee. Retried or overlapping invocations must remain safe through
the existing database coordination and idempotent ledger behavior.

`StockProjectionLockRepository.acquire` serializes scheduled sync, manual sync,
projection rebuilding, and expiry across pods. Per-SKU allocation uses the
shared side of that same gate.

Keep the existing first-lock ordering. Do not add a second lock with a conflicting
order. A repeated sync must not receive a shipment or consume a reservation twice.

## Choosing the frequency

Twice daily is suitable only if a delay of roughly twelve hours is acceptable
under healthy operation. Failures, downtime, and contention can extend that lag.
Once daily permits roughly twenty-four hours of ordinary delay.

Between synchronizations:

- Arrived shipments remain persisted inbound supply instead of live inventory.
- New-demand allocation can still consider eligible unreceived supply; shipment
  arrival dates and delivery deadlines continue to constrain it.
- Due confirmed deliveries have not yet reduced live physical quantities.
- Those delivery reservations continue withholding quantity from other orders.
- Unconfirmed holds still expire independently on the short expiry schedule.

The existing ledger also treats expected arrival as sufficient to receive supply,
and a due confirmed reservation as sufficient to deliver it. These are current
date-driven assumptions, not verified warehouse events. Scheduling this process
changes when those assumptions are applied; it does not make them actual receipt
or dispatch confirmations.

If near-real-time warehouse accuracy is required, choose a shorter interval or
implement explicit receipt/dispatch events in a separate feature. Do not shorten
or disable reservation expiry merely because ledger sync is less frequent.

Retain the explicit `process_stock_ledger` operation for authorized manual
synchronization using the same transactional processor.

## Allocation completion and public contracts

Refactor `AllocationJobCompletionService` to persist allocation completion only.
Remove its ledger dependency and `ProcessDailyStockRequest` parameter.

Replace the new-job `AllocationAndSyncResult` contract with an
allocation-completion result containing `allocationRun`, without `stockSync`.
Update both the cached-result read in `AllocationJob` and the status read in
`AllocationJobService`. A completed allocation must never advertise that a
scheduled ledger sync has already completed.

Rename the REST routes to reflect the actual behavior. Proposed routes:

```text
POST /api/logistics/allocation-jobs
GET  /api/logistics/allocation-jobs/{jobId}
```

Preserve the `202 Accepted`, job-ID, and `Location` workflow. Retire the old
`allocate-project-and-sync` name through a coordinated caller update rather than
silently keeping its old promise with different behavior.

Inventory the actual consumers before editing: service integration tests, MCP
descriptions, client/API adapters, UI code if it consumes these routes or result
fields, and documentation.

Newly stored job-result JSON includes version 2. Serialize and deserialize
`AllocationCompletionResult` directly with the shared `ObjectMapper`. The
database, including JobRunr storage, will be recreated for this rollout, so no
legacy combined-result decoder or historical job-result migration is needed.
Do not manufacture a `stockSync` field for new results.

Keep the current REST today-only request-date restriction for this first
feature, but change its error wording to describe the allocation endpoint rather
than claim that synchronization happens with it. Relaxing that input policy is
a separate decision.

## Completion, failure, and operational visibility

Allocation completion and ledger synchronization are independent outcomes. A
ledger failure must not retroactively mark an already completed allocation as
failed or trigger duplicate allocation.

Use JobRunr execution state and structured logging for scheduled sync failures.
Emit success logs only after the transaction commits. Include snapshot date,
SKU count, lot snapshot count, duration, and a correlation/job identifier where
available. The lot snapshot count can include projected future lots; do not label
it as the number of physically received lots.

Make the last successful ledger synchronization timestamp observable, for
example through a persisted status record or an existing metrics integration.
Alert when its age exceeds the configured schedule plus an agreed tolerance.
Do not infer success from registration or from an allocation job's status.

Do not add a once-per-calendar-day guard: multiple runs per day are intentional,
and later supply or reservations may require another synchronization that day.

## Implementation sequence

1. Introduce validated scheduling/zone configuration and `StockLedgerSyncJob`,
   initially disabled while the old allocation path is still active.
2. Extract/reuse allocation-and-projection orchestration for all relevant
   callers, without changing per-SKU transactions or TTL ownership.
3. Remove ledger invocation from allocation completion. Adapt execution,
   cached-result deserialization, job status, and new result versioning together.
4. Update REST routes, response consumers, MCP descriptions, and documentation.
   Recreate job storage rather than retaining historical combined job results.
5. Enable the independent recurring job after coordinated deployment, retaining
   manual synchronization and the existing ledger transaction boundaries.
6. Add freshness monitoring and execute the acceptance scenarios below.

Stop old workers and recreate the database, including JobRunr storage, before
starting the updated application. Old serialized jobs may reference changed
method signatures or expect the combined result contract. Do not run old
allocation-plus-sync workers alongside the new completion model.

## Acceptance scenarios

- New demand creates reservations and refreshes forecasts without calling the
  ledger, receiving shipments, or consuming due confirmed deliveries.
- RabbitMQ processing acknowledges successful allocation/projection without
  waiting for the next ledger schedule; retry does not allocate twice.
- REST retains asynchronous acceptance and reports allocation completion without
  a fabricated `stockSync` result. Cached current-format results remain readable.
- A forecast failure after committed SKU allocation is visible; retry refreshes
  it without duplicating reservations.
- Explicit backorder fulfillment refreshes the forecast without performing sync.
- The recurring job processes due supply and confirmed deliveries even if no
  allocation requests have arrived.
- A retry after midnight selects the new current date, not the old allocation or
  previous execution date. Business-zone boundaries are deterministic with `Clock`.
- Expiry between allocation and sync cannot restore released counters through an
  old snapshot; pre-sync cleanup and rebuilding remain atomic.
- Unconfirmed orders never physically deliver, including overdue deliveries.
- Multiple pods, overlapping scheduled/manual runs, and retries cannot duplicate
  receipt or delivery. Committed effects remain idempotent.
- A projection or ledger failure rolls back snapshot replacement and business
  mutations together and leaves a failed execution visible.
- Repeated rebuilds and syncs in one transaction work without detaching unrelated
  JPA entities; snapshot persistence remains JDBC-based.
- Disabled scheduling removes the stored recurring definition; already queued
  work and manual execution have explicit, documented behavior.
- Success/failure monitoring distinguishes allocation completion from ledger
  freshness and detects a missed synchronization window.
