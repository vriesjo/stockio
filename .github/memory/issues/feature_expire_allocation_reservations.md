# Feature: expire unconfirmed allocation reservations

## Status and scope

The initial implementation introduces temporary order reservations and automatic
expiry, including confirmation-aware projections and ledger synchronization. A
public order-confirmation flow will be implemented separately.

The subsequent [multi-line order/shipment feature](feature_orders_and_shipments_one_to_many_relationship.md)
moves lifecycle storage to the `demand_order` header. All lines share one deadline,
assigned during document initialization before SKU allocation. Expiry releases
the entire order across its lines; the former header-level `sku` no longer exists.
References below to the original order-claim implementation describe its initial
single-line form.

New orders are unconfirmed by default. When their reservation deadline passes,
release their reservations and remove their remaining open demand. Keep allocation
history and idempotency records for audit and replay protection.

Implemented defaults:

- Reservation TTL: 30 minutes.
- Expiry sweep interval: 1 minute.
- No automatic TTL renewal, including on retries or backorder allocation.
- Expire the whole order, including any unallocated backorder remainder.

Until confirmation is implemented, all newly submitted orders remain temporary
and eventually expire. They must not be treated as physical deliveries.

## Starting point before this feature

`AllocationSkuWorker.allocateNewDemand` creates an `allocation_order_claim`,
reserves stock and/or inbound supply, and registers remaining demand as an
`open_backorder`. Reserved quantities are stored both in reservation rows and in
the source's `quantity_reserved` counter.

`StockProjectionProcessor.process` previously scheduled every reservation as an
outgoing movement on its required delivery date. `StockLedgerProcessor.process`
then applies today's closing balances to live stock and deletes due reservation
rows. It also converts inbound reservations into stock reservations when shipments
arrive.

Consequently, deleting old reservation rows alone is insufficient:

- The source counters would still make the quantity unavailable.
- Open backorders could recreate reservations for an expired order.
- Existing snapshots could reintroduce obsolete quantities during ledger sync.
- A reservation already consumed by ledger sync could no longer be released
  safely, because the physical stock would already have been reduced.

## Order-level reservation lifecycle

Extend `allocation_order_claim` rather than adding a deadline independently to
every reservation. The claim already identifies an order and its SKU, and exists
even when the entire order is backordered.

An order may reserve multiple lots and shipments. All those reservations share
one lifecycle and deadline, including reservations converted on inbound receipt.

Proposed additional fields:

| Field | Meaning |
| --- | --- |
| `reservation_status` | `PENDING_CONFIRMATION`, `CONFIRMED`, or `EXPIRED`. |
| `reservation_created_at` | UTC instant when the initial allocation transaction creates the claim. |
| `reservation_expires_at` | Creation instant plus the configured TTL; required for pending orders. |
| `confirmed_at` | Nullable UTC instant, reserved for the later confirmation feature. |
| `expired_at` | Nullable UTC instant when cleanup is committed. |

Use PostgreSQL `timestamptz` and Kotlin `Instant` for timestamps. Inject a shared
`Clock` so deadlines and boundary cases are deterministic in tests. TTL is elapsed
time, independent of `asOfDate`, arrival dates, and required delivery dates.

Create lifecycle fields in the same per-SKU transaction as the initial claim and
reservations. A rollback creates neither. Each order's deadline starts at its
initial allocation, not when the entire multi-SKU run eventually completes.

Allowed transitions:

```text
PENDING_CONFIRMATION -- confirmation before deadline --> CONFIRMED
PENDING_CONFIRMATION -- deadline reached -------------> EXPIRED
```

Expiry applies when `reservation_expires_at <= now`. At exactly the deadline,
confirmation must fail. `EXPIRED` is terminal; a new allocation requires a new order
ID and request ID. Do not reuse `AllocationStatus` for this lifecycle: allocation
quantity outcomes and confirmation state describe different things.

Keep the persisted deadline for audit after either transition. Confirmed orders
are not subject to this TTL. Cancellation of confirmed orders is out of scope.

## Expiry processing

Add a transactional `ReservationExpiryService` and a recurring JobRunr job using
the existing background-job infrastructure. Use a stable recurring-job identifier
and bounded batches ordered by deadline and order ID.

For each batch:

1. Acquire the existing exclusive `stock_projection_lock` before any order or
   supply locks. Capture `now` after obtaining the lock.
2. Select and lock pending claims whose deadline is at or before `now`. Recheck
   their status and deadline while locked.
3. Load their current stock and inbound reservations. For each source, subtract
   exactly the quantity being released from `quantity_reserved`, then delete the
   corresponding reservation rows.
4. Delete the orders' open backorders, including orders with no reserved quantity.
   Do not create replacement demand or automatically allocate other orders.
5. Mark the claims `EXPIRED` and set `expired_at`.
6. Flush the mutations and rebuild projections from today using the resulting
   persisted state, in the same transaction.

Do not change `stock.quantity_on_hand`, `inbound_supply.expected_quantity`, or
shipment receipt state during expiry. Releasing a reservation makes existing
quantity available; it is not an arrival, shipment, or stock adjustment.

Fail and roll back if a source is missing or its reserved counter would become
negative. Report the inconsistency; do not clamp counters or silently skip rows.
An already expired order is a no-op, making retries and multiple application
instances safe. The global lock serializes expiry with allocation and ledger sync.
Always acquire it first; never upgrade an allocation's shared lock to exclusive.

Physical cleanup is asynchronous. Under healthy operation, a hold normally
releases on the next sweep; lock contention, downtime, or job failures may delay
that release. Do not promise a hard one-minute eviction guarantee. Persisted
deadlines allow the next execution after restart to catch up.

Logical expiry is immediate: an overdue order cannot be confirmed, delivered, or
receive additional backorder reservations, even if its cleanup job has not run.
Until cleanup commits, its source counters may conservatively reduce availability.

## Allocation and backorder integration

`AllocationSkuWorker.allocateNewDemand` creates pending claims using the
configured TTL. All entry points must use this default; callers cannot implicitly
confirm orders through an allocation request.

`fulfillBackorders` must exclude expired claims and overdue pending claims. It may
allocate more supply for a still-valid pending order, but must retain that order's
original deadline. Confirmed orders remain eligible without a TTL.

An order expiring while a worker is running is cleaned up after that transaction
releases its shared projection lock. No worker may renew or resurrect it.

Keep allocation runs, records, sources, per-SKU operation results, order claims,
and cached job results unchanged as historical records. Replaying an old request
returns its original result and does not recreate reservations or extend the TTL.
Those results describe the allocation at execution time, not current active holds.

Expose the current lifecycle and deadline through
`GET /api/logistics/orders/{orderId}/reservation` and the
`get_order_reservation_status` MCP tool, separately from immutable allocation-run results.
The returned status includes logical expiry at the deadline; `cleanupPending`
distinguishes an overdue hold awaiting cleanup from committed expiry.
Reports based on saved runs remain historical; live availability must use current
stock and reservation state.

## Projection and delivery synchronization

This is a required part of the feature, not optional follow-up work.

### Pending orders

Pending reservations reduce available quantity, but do not authorize physical
delivery. In daily projections, exclude them from outgoing `allocated_qty` and
delivery `demand_qty`. Pending backorders likewise must not appear as committed
delivery shortages. Keep their active reserved quantities represented in lot
balances until cleanup removes them.

The initial design does not simulate future TTL release inside daily snapshots.
Expiry rebuilds the forecast when it actually releases holds. Snapshots are
therefore a view of current holds, not a timestamp-accurate future availability
promise.

`StockLedgerProcessor` must not delete pending reservations merely because their
required delivery date is today or earlier. It must not subtract those quantities
from physical stock.

When inbound supply arrives, receive the physical quantity normally and convert
all still-active pending inbound reservations to stock reservations, even if their
delivery date has passed. Preserve the order association; the lifecycle and TTL
remain on the claim. Expiry subsequently releases the stock reservation rather
than subtracting from an already received inbound supply counter.

### Confirmed orders

Only confirmed reservations become projected outgoing deliveries and are consumed
by ledger synchronization. Once the later confirmation flow exists, it must
rebuild projections atomically with the lifecycle transition. Existing confirmed
delivery behavior otherwise remains unchanged.

### Avoid stale snapshots

Expiry and future confirmation must rebuild snapshots before committing changes.
Before ledger synchronization, process due expiry and rebuild today's projection
under the same exclusive lock and transaction as applying it. This prevents a
previously saved snapshot from resurrecting released holds or consuming pending
orders. Do not perform cleanup, rebuilding, and synchronization as separately
committed operations.

Extract shared locked cleanup logic so the recurring job and ledger path use the
same rules without recursive processor calls. Ordinary projection rebuilds must
also exclude overdue pending orders from delivery movements. Ensure the ledger
path supports a valid empty projection when no stock, supply, or active demand
remains, without treating arbitrary missing snapshots as success.

Wire the rules into direct MCP tools, product allocation orchestration, background
job completion, and any other callers of the processors, not just the recurring
job.

## Configuration and migration

Proposed configuration:

```yaml
stockio:
  allocation:
    reservation:
      ttl: ${ALLOCATION_RESERVATION_TTL:30m}
      expiry-interval: ${ALLOCATION_RESERVATION_EXPIRY_INTERVAL:1m}
      expiry-batch-size: ${ALLOCATION_RESERVATION_EXPIRY_BATCH_SIZE:100}
```

Validate positive durations and batch size at startup. Changing the configured TTL
affects newly allocated orders only; never recompute existing deadlines.

Add a new Flyway migration; do not edit applied migrations. Add lifecycle
constraints and a partial index on `(reservation_expires_at, order_id)` for
`PENDING_CONFIRMATION` claims.

Implemented compatibility policy: backfill pre-feature claims as `CONFIRMED`, with no
expiry deadline, to avoid unexpectedly releasing existing commitments. This is a
legacy migration exception, not the default for new orders. Validate that existing active reservations and backorders have claims;
report inconsistencies rather than inventing lifecycle records silently.

Deploy lifecycle-aware allocation, projection, ledger, and expiry processing
together. Do not run old and new application versions concurrently against active
temporary holds: old ledger code would still consume them as deliveries.

## Implementation sequence

1. Add lifecycle persistence, migration, validated configuration, and shared clock.
2. Initialize pending claims and gate backorder allocation by lifecycle/deadline.
3. Make projections and ledger synchronization confirmation-aware, including
   inbound-to-stock conversion for overdue pending delivery dates.
4. Implement transactional expiry, projection rebuilding, and recurring execution.
5. Expose current lifecycle/deadline, document historical result semantics, and
   add structured expiry counts, released quantities, oldest overdue deadline,
   and failure reporting.
6. Implement the public confirmation command in a later feature. It must use the
   same lock ordering and atomically reject overdue or expired orders.

Do not enable temporary holds until the delivery-safety changes are deployed.

## Acceptance scenarios

- A new allocation reserves inventory/inbound supply and is pending with one
  persisted deadline. A fully backordered order receives the same lifecycle.
- Before the deadline, the sweep releases nothing. At the exact deadline, it
  releases all reservations, removes remaining backorders, and marks the order
  expired. Stock on hand and expected inbound quantities do not change.
- Other orders sharing the same lot/shipment keep their reserved quantities.
- A same-day unconfirmed delivery is not consumed by projection or ledger sync.
- A pending inbound reservation survives receipt, retains its original deadline,
  and later expires against the new stock reservation, including overdue orders.
- Backorder fulfillment before expiry does not renew TTL; at or after the
  deadline it creates no additional reservation.
- Repeating expiry or replaying an allocation/job result neither double-releases
  quantity nor recreates holds, including partially completed multi-SKU runs.
- Concurrent allocation, expiry, receipt, and ledger execution preserve source
  counters and cannot reapply stale reservation snapshots.
- A missing source, invalid counter, or projection rebuild failure rolls back the
  whole expiry transaction and surfaces an error.
- After restart, overdue orders are processed without depending on in-memory
  timers. Multiple job instances produce the same final state.
- Confirmed legacy orders do not expire. Seed confirmed lifecycle state in tests
  to verify delivery behavior before the public confirmation flow exists.
- The future confirmation race has one outcome: confirmation committed before
  the deadline wins; otherwise confirmation is rejected and cleanup releases the
  pending hold.

Use focused service tests with a fixed clock and PostgreSQL integration tests for
transactions, locks, receipt conversion, and snapshot/ledger consistency.
