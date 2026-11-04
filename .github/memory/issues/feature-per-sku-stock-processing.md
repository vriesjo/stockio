# Feature: Coordinate stock processing through a per-SKU protocol

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Allocation, Reservations & Stock Ledger
- **Implementation started:** Yes. Complete, at the user's request.
- **Parent Specs:** [Architecture](../architecture.md), [Concurrent document registration](../specifications.md#feature-allow-concurrent-allocation-document-registration)
- **Specification:** [Feature specification](../specifications.md#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Scenario:** [Scenario 15](../scenarios.md#scenario-15-process-stock-operations-concurrently-by-sku)

## Context and relationship to completed work

The [completed registration feature](./feature-concurrent-document-registration.md)
replaced globally exclusive registration with shared-gate plus document/SKU
protection. It deliberately retained globally exclusive projection, ledger,
and expiry. Its status and acceptance criteria remain unchanged.

This follow-up removes the gate after those other paths coordinate
through a complete per-SKU protocol. It is not a request to merge allocation,
forecasting, and physical stock synchronization into one lifecycle transaction.

The expected benefit is less interference between independent SKUs and less
full-catalogue work per request. A hot SKU still serializes; database capacity,
query cost, worker budgets, and future admission control still limit throughput.
No preset performance target is assumed.

## Approved implementation decisions

- Preserve committed allocation followed by retryable synchronous scoped projection, and atomic expiry plus affected forecast refresh.
- Synchronous ledger results report success only after the frozen manifest completes. Expose durable partial progress through the existing status endpoint; update global freshness only after complete success.
- Freeze the business date and manifest per run. Resume incomplete current-day work; supersede unfinished previous-day runs and reconcile through a fresh current-day manifest. Never apply an old-date forecast to live stock. New scopes enter the next run.
- Use SKU-local forecast horizons and independently committed cross-SKU read versions.
- Process ledger SKUs sequentially per coordinator, with database coordination preventing overlapping active ledger workers across instances. The coordinator has no outer transaction/retained connection while waiting for work; a worker waiting for its SKU still uses its own transaction/connection.
- Record receipt follow-up transactionally and retry dispatch with stable identities.
- Stop all writers, deploy/migrate all instances, then restart together. Mixed-version operation is unsupported; remove the gate only after all replacement paths are validated.
- Priority remains unset.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Common SKU protocol | All participating writers acquire existing database SKU locks before decision reads; acquire any required sorted document locks before sorted SKU locks, then run/lifecycle and business-row locks. Use SKU-wide scope across warehouses initially. |
| FR-02 | Scoped projection | Read and replace only the selected SKU's projection data, including removal of obsolete/empty scopes, under its SKU lock; keep the first version synchronous without asynchronous forecast publication. |
| FR-03 | Atomic SKU ledger | Recompute the selected SKU's projection and apply its receipts, deliveries, and reservation conversions in one protected transaction; do not apply a previously published forecast to live stock. Preserve retry-safe movements and allocation/ledger separation. |
| FR-04 | Whole-order lifecycle | Discover expiry candidates without taking lifecycle locks first; protect document identity, lock all immutable order SKUs in sorted order, recheck lifecycle/deadline, and release holds plus mark expiry atomically. Never acquire additional unordered SKUs from inside a ledger worker. |
| FR-05 | Recoverable coordination | Persist a frozen current-day manifest and per-SKU checkpoints; resume incomplete current-day work, reconcile superseded old-date runs through today's manifest, expose partial progress in existing status reads, and advance global success only after full completion. |
| FR-06 | Durable receipt follow-up | Persist receipt-triggered backorder work in the receipt transaction through an outbox or equivalent durable mechanism, with idempotent dispatch and consumption. |
| FR-07 | Safe cutover | Introduce and validate scoped processing while retaining the global gate; remove reliance on it only after every deployed participating writer follows the SKU protocol. Preserve existing identity constraints and replay safety. |
| FR-08 | Verification and visibility | Cover independent/hot SKUs, multi-SKU lifecycle, snapshot scoping, crashes/retries, date boundaries, and rollout; measure completed throughput, latency, lock/pool waits, and ledger backlog without claiming a preset capacity. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Consistency boundaries | Guarantee per-SKU mutation consistency and atomic whole-order expiry, not a single allocation/project/ledger lifecycle transaction or an atomic whole-catalogue ledger run. |
| NFR-02 | Bounded execution | Bound coordinator fan-out against the combined database budget; keep transactions free of remote calls and broker/job scheduling side effects that belong in durable follow-up. |
| NFR-03 | Explicit compatibility | Preserve FIFO, eligibility, quantity, ownership, receipt, and reservation rules; approve changes to failure semantics, projection horizon, result/status contracts, and freshness before cutover. |
| NFR-04 | Limited scope | Do not add rate limiting, HTTP admission control, virtual threads, R2DBC, SKU/warehouse partitioning, confirmation endpoints, or asynchronous forecast/version publication in the first version. |

## Implementation plan

1. Resolve transaction/failure compatibility, ledger-run API/status, date/cutoff, work discovery, and projection-horizon decisions; baseline the current implementation.
2. Introduce SKU-scoped reads and snapshot replacement plus per-SKU projection tests while retaining global coordination.
3. Implement the common lock hierarchy and whole-order expiry protocol, including cleanup/retry coordination for ledger workers encountering overdue holds.
4. Add per-SKU ledger transactions, durable run/progress metadata, and receipt follow-up; keep existing global protection during migration.
5. Validate every participating writer, replay/crash behavior, mixed-version deployment constraints, and realistic contention; remove the gate only after the coordinated cutover is approved.
6. Measure performance and remaining bottlenecks, then synchronize interfaces, architecture, scenarios, specifications, issue, run guidance, and active context.

## Functional scenario

### Gherkin description
**Given** allocation and stock-maintenance work for independent SKUs \
**When** allocation, scoped projection, ledger synchronization, or reservation expiry execute concurrently \
**Then** unrelated SKUs can progress without the global projection gate \
**And** operations affecting the same SKU observe protected committed state and cannot over-reserve or duplicate movements \
**And** expiry of a multi-SKU order releases all its holds and changes its lifecycle atomically \
**And** a ledger run reports recoverable per-SKU progress rather than claiming success while intended work is incomplete

### High-level technical analysis
- Reuse database SKU locks across warehouses, with sorted document locks when needed before sorted SKU locks, followed by run/lifecycle and business rows
- Scope projection reads and snapshot replacement to one SKU; ledger computes and applies its fresh SKU projection in the same transaction, separately from allocation
- Expiry discovers candidates before locking, acquires all immutable order SKUs, and rechecks status/deadline; ledger defers and retries when whole-order cleanup is needed rather than expanding its lock set
- Persist the current-day manifest, per-SKU checkpoints and receipt follow-up; expose additive progress in the existing status endpoint and record global freshness only after complete success
- All participating writers use the SKU protocol; deploy them together through a coordinated stop/migrate/deploy/restart with no mixed versions
- The first version keeps synchronous projections and does not add admission control, non-blocking persistence, confirmation interfaces, or a full-catalogue atomicity guarantee

### Acceptance Criteria
- An allocation for SKU 1 does not block ledger/projection work for unrelated SKU 2 solely through a global gate
- Competing allocation, amendment, ledger, and expiry work for the same SKU preserves quantities, reservation sums, receipt state, and replay safety
- Scoped snapshot replacement cannot delete another SKU's forecasts and removes obsolete rows when its own output is empty
- A multi-SKU order is never marked expired with unreleased holds elsewhere, and reversed SKU input does not create inverse lock acquisition
- Ledger uses a fresh protected baseline, retries/crashes do not duplicate physical movements, and receipt follow-up survives an enqueue outage
- Partial ledger execution remains visible and resumable without falsely advancing global success/freshness
- Gate removal cannot overlap a deployed writer that still relies on global exclusivity
- Exercise independent and hot SKUs; production capacity requires representative workload measurement, with no 1,000-request-per-second guarantee

## Pre-implementation baseline

| Component | Current boundary | Required analysis/change |
| --- | --- | --- |
| `AllocationDocumentService` / `InboundSupplyService` | Shared projection gate, sorted document identities, sorted inbound SKUs, run lock, locked supply reads. | Retain identity/SKU safety; remove the gate only at cutover. Include any future membership/lifecycle writer in the protocol. |
| `AllocationSkuWorker` | Shared gate plus one SKU lock and a transaction; persists per-SKU replay results. | Retain protected decision reads and resumability without the gate after cutover. |
| `AllocationProcessor` | Allocation commits, then a separate projection refresh runs; projection failure can be retried after allocation is saved. | Dispatch scoped refreshes and explicitly preserve or approve a change to this failure boundary. |
| `StockProjectionProcessor` | Reads the overall operational dataset and globally replaces snapshots from a date under the exclusive gate. | Add SKU-scoped reads/calculation/replacement, including stale-row removal. |
| `StockSnapshotRepository` and JDBC adapter | Global `replaceFrom` deletes/replaces aggregate and lot forecasts. | Add scope-aware replacement; retain lot/aggregate consistency and avoid deleting another SKU's data. |
| `StockLedgerProcessor` | One exclusive transaction drains expiry, rebuilds forecasts, applies physical movements, records success, and enqueues receipt follow-up after commit. | Split coordination from transactional SKU work; persist partial progress and durable follow-up without applying stale forecasts. |
| `ReservationCleanupService` / `ReservationExpiryService` | Exclusive gate, locked order batch, hold release, lifecycle update, and projection refresh. | Separate candidate discovery from locking; preserve atomic whole-order expiry with sorted SKU locking and scoped refresh. |
| `StockLedgerSyncJob`, status service/repository, REST/MCP adapters | Global scheduled/manual execution and a last-success/freshness view. | Define parent/per-SKU completion, recovery, result aggregation, and compatibility before implementation. |

Existing order/line/shipment uniqueness, parent foreign keys, stable receiving
lots, run request hashes, and per-SKU operation results remain essential.
Additive migrations for run/progress/outbox state may be needed; exact schemas
and public DTO changes are not selected here. Reuse existing ports, adapters,
and JobRunr integration instead of introducing a second scheduler or database.

## Implemented transaction and locking design

### SKU consistency boundary

Every participating mutator must lock the SKU before loading stock, supply,
reservations, or lifecycle state used for a decision:

```text
Begin transaction
  -> sorted document identity locks, if needed
  -> sorted SKU locks
  -> run/lifecycle locks, if needed
  -> protected business reads and row locks
  -> validate, mutate, and save replay/progress state
Commit
```

Locks remain database-backed across service instances. New SKU lock rows must
be created safely using the existing insertion/locking pattern. Never hold a
run/lifecycle row while waiting for a worker's SKU, expand a held SKU set out
of order, or acquire document identity locks after SKU locks.

### Allocation and forecast boundaries

Keep allocation separate from physical ledger processing. A request involving
several SKUs continues to have independently committed SKU operations rather
than becoming one cross-SKU allocation transaction.

The least-disruptive first version preserves allocation commit followed by a
retryable, synchronous scoped forecast transaction. Each refresh takes its SKU
lock before reading and replaces only that scope. Combining allocation and
forecast replacement in one SKU transaction is an alternative decision, not an
assumed change: it alters whether a projection failure rolls allocation back.

SKU projection must include stocks, unreceived supply, reservations, relevant
order lifecycle, shortages, and existing forecast scopes needed for deletion.
Preserve FIFO, date, and quantity rules. No output still requires deletion of
obsolete forecast rows for that SKU, not a no-op.

### Ledger transaction

```text
Begin transaction
  -> lock SKU
  -> recheck date and lifecycle prerequisites
  -> compute fresh SKU forecast from operational state
  -> apply due physical movements and reservation conversions
  -> save scoped snapshots and successful work checkpoint
  -> persist receipt-triggered follow-up, if any
Commit
```

The ledger must not trust an older published forecast, even if it is internally
consistent. Receipt flags, stable lot/line identities, consumed reservations,
and checkpoints must make a repeated work item safe after an unknown outcome.
Failed work rolls back its operational changes and checkpoint together.

### Multi-SKU expiry

Discover candidate orders without holding their lifecycle rows. Derive their
immutable SKU membership, acquire document protection and all those SKU locks
in sorted order, then lock/recheck the order and deadline. Release every stock
or inbound hold, delete remaining backorders, and mark the order expired in
one transaction.

Do not mark expiry after cleaning only one line/SKU. Do not retain the current
claim-first `FOR UPDATE` batch acquisition ahead of SKU locking.

Preserve the current expiry/forecast failure boundary initially: refresh all
affected SKU scopes under the already-held locks in that same expiry
transaction. Moving those refreshes after commit would require a separately
approved freshness and recovery change.

A ledger worker holding one SKU must not call cleanup that acquires other
SKUs. Run cleanup independently and recheck prerequisites inside the SKU
transaction. If a deadline passes while work waits, defer/retry that work after
whole-order cleanup rather than widening its lock set.

### Coordinator and recovery

Persist the intended ledger work manifest, business date/cutoff, and per-SKU
outcomes. Bound dispatch against the shared database budget, including
allocation, backorder, expiry, and operational work. Discover scopes with stock,
supply, reservations, backorders, or obsolete forecasts; do not discover work
from stocked products alone.

Separate parent-run coordination from each SKU transaction. Do not hold a
parent-run lock while executing/waiting for SKU work. A crash after a SKU
commit but before aggregate status update must be recoverable from persisted
checkpoints without applying the movement again.

A parent run is complete only when its intended work set is complete.
Partially completed runs stay visible and resumable; one SKU's success must not
silently advance the global last-success/freshness timestamp. Handle new scopes
appearing after discovery through an explicit work-discovery policy.

Receipt follow-up should be recorded with the receipt transaction and
dispatched after commit with stable deduplication identities. A broker/JobRunr
outage must not lose the request; duplicate dispatch must remain harmless.

## Resolved implementation decisions

| Decision | Approved implementation |
| --- | --- |
| Allocation versus forecast transaction | Preserve committed allocation followed by retryable synchronous scoped projection. Expiry plus affected projection remains atomic. |
| Ledger API/MCP results | Keep `StockSyncResult` on complete success. Existing status reads add durable `progress`; processing errors leave partial work visible without updating global last success. |
| Date and cutoff | Freeze business date per manifest. Recheck after SKU acquisition and before checkpoint/completion; supersede unfinished previous-day runs and reconcile today's state. |
| Work discovery | Persist a union of stock, supply, reservations, shortages and forecast SKUs. Resume incomplete current-day checkpoints; new scopes enter the next manifest. |
| Forecast horizon and readers | SKU-local horizons and independently committed cross-SKU versions; no catalogue-wide point-in-time guarantee. |
| Run/progress/outbox persistence | V18 adds `stock_ledger_run`, `stock_ledger_work`, `stock_ledger_coordinator`, and `stock_receipt_followup`. Physical effects, checkpoint and follow-up share one SKU transaction. |
| Execution budget | Sequential ledger work, admitted across instances by a renewable five-minute owner lease. Fence/check the owner after SKU locking and never hold coordinator metadata while waiting for a SKU. Existing allocation/backorder dispatchers remain separate. |
| Cutover | Stop all old writers, migrate/deploy all instances, then restart together. No mixed-version operation and no migration switch. |

Independent per-SKU commits intentionally remove whole-catalogue transaction
atomicity. If callers need one catalogue-wide point-in-time view, that needs a
separate read/reporting contract; it is not provided merely by per-SKU locking.
No business priority/fairness guarantee follows from lock acquisition order.

## Staged rollout and verification

The implementation removes gate acquisition from every participating workflow:
HTTP/MCP/RabbitMQ allocation, receipt-triggered backorders, projection, ledger
and expiry. The legacy row/adapter remain inert for migration history and legacy
tests. Deployment must stop old writers, migrate/deploy every service instance,
and restart together; never overlap old globally exclusive writers with the
new protocol.

Use focused real PostgreSQL integration tests for independent and overlapping
SKUs, inverse input order, absent identities, whole-order expiry versus
allocation/receipt, deadline crossings, empty scope replacement, and replay.
Exercise failure after physical changes but before commit, after commit before
parent progress aggregation, and before/during receipt follow-up dispatch.
Validate migrations and existing quantity, FIFO, ownership, lifecycle, and
ledger invariants.

Compare baseline and scoped processing with realistic catalogue size, lot and
reservation counts, date horizons, cold/distributed SKUs, and hot-SKU skew.
Measure completed operations/second, p95/p99 latency, transaction/lock waits,
pool pending/timeouts, oldest ledger work, retries, and follow-up age. Do not
claim improvement from accepted submissions alone or assume 1,000 completions
per second. The performance harness currently has guidance only; selecting a
runner/workload is a separate decision.

Rate limiting/admission control and asynchronous versioned forecasts remain
separate follow-up features, not implemented solutions here.

## Implementation and verification

- Scoped repository queries and JDBC `replaceSkuFrom` prevent cross-SKU deletion and preserve empty-scope cleanup. V18 indexes order-line, stock-reservation and backorder SKU access paths.
- `StockLedgerProcessor` runs without an enclosing transaction; competing coordinators wait without retaining a pooled connection. `StockLedgerSkuWorker` atomically commits physical movements, its checkpoint and receipt follow-up.
- Current-day failures resume persisted incomplete work. Expired owner leases can be taken over, and stale workers are fenced before business reads/effects. Successful work survives a crash before parent aggregation.
- Whole-order cleanup discovers IDs without claim locks, takes sorted document/all-SKU locks, rechecks lifecycle/deadline, releases all sources and refreshes affected snapshots in one transaction. A ledger worker releases its transaction before requesting cleanup and retry.
- Receipt dispatch is immediate after complete synchronization and retried by a stable minute-based JobRunr schedule. Enqueue failure preserves pending work; duplicate dispatch/consumption reuses stable identities.
- PostgreSQL regressions cover independent SKU progress under an active allocation, hot-SKU waiting, atomic multi-SKU expiry, empty scoped deletion, local horizons, partial recovery, old-date reconciliation, stale-owner fencing and rollback of checkpoints/outbox with physical state.
- Functional concurrency checks are not a representative production capacity benchmark. The harness still provides performance guidance only; workload selection, p95/p99, completed throughput and pool/lock profiling remain operational follow-up. Completion logs include run counts and elapsed time; status exposes manifest backlog and age through counts and creation time.
- Verification completed: the full service suite passes 135 tests without skips, including PostgreSQL concurrency/recovery cases, Flyway V1-V18 startup/legacy upgrades and schema validation. OpenAPI validation and synchronized documentation link/requirement checks pass. No Kotlin formatter/linter task is configured.
