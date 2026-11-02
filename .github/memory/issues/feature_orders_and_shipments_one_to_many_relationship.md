# Feature: orders and shipments with multiple lines

## Status and objective

Implemented as a coordinated breaking contract change. Repository callers now use
nested order/shipment lines. Old allocation jobs must be drained and incomplete
legacy runs reconciled before deployment; old and new writers must not overlap.

The implementation preserves migrated data and readable historical results, not
legacy request submission. A migrated run can still be read by ID, but replaying
its old request through the new contract is explicitly rejected. New requests
remain idempotent under the new contract.

Represent an order and an inbound shipment as business documents containing one
or more product lines. Preserve per-SKU allocation while separating document
identity, line identity, reservations, and order-level confirmation/expiry.

Implement this model correction before adding the public order-confirmation
flow. It supersedes the single-SKU assumptions in the existing
[reservation-expiry feature](feature_expire_allocation_reservations.md), but
preserves its delivery-safety and expiry guarantees.

## Problem

`AllocationRequest.kt` currently models:

```text
DemandOrder(orderId, customer, sku, requestedQuantity, ...)
InboundShipment(shipmentId, sku, expectedQuantity, ...)
```

These objects behave like product lines but use document-level IDs as their
identities. Consequently, one real order cannot contain multiple requested
products, and one real shipment cannot contain multiple supplied products.

The restriction exists throughout the implementation, not only in the DTOs:

| Current component | Single-line assumption |
| --- | --- |
| `AllocationRequestValidator` | Rejects repeated order IDs and shipment IDs within a request. |
| `AllocationService` | Groups complete orders/shipments by their single SKU and uses order ID to recover input ordering. |
| `AllocationSkuWorker` | Creates an order claim and its deadline inside one SKU transaction. |
| `allocation_order_claim` | Uses order ID as its primary key and stores one SKU alongside the order lifecycle. |
| `inbound_supply` / `InboundSupplyService` | Uses shipment ID as the supply identity and rejects reuse with another SKU. |
| `stock_reservation` | Identifies demand by order ID, with uniqueness per order and stock lot. |
| `inbound_reservation` | Identifies demand by order ID and supply by shipment ID. |
| `open_backorder` | Uses order ID as its primary key and stores one SKU and remaining quantity. |
| `allocation_record` | Stores one SKU per record and currently permits only one record per run/order pair. |
| Projection and ledger processors | Use shipment ID as the projected inbound lot identity and order ID as the reservation key. |

Simply allowing duplicate IDs or replacing `sku` with a list would be unsafe.
Several reservation maps would merge different lines, lifecycle ownership would
be ambiguous, and multiple lines of the same shipment could collide on receipt.

### Example the model must support

```text
Order ORDER-100
  OL-100-1: MAKI-ZALM,   10 units, delivery 2026-10-21
  OL-100-2: MAKI-TONIJN,  5 units, delivery 2026-10-22
  OL-100-3: MAKI-ZALM,    3 units, delivery 2026-10-23

Shipment SHIP-200
  SL-200-1: MAKI-ZALM,   20 units, arrival 2026-10-21, warehouse MAIN
  SL-200-2: MAKI-TONIJN, 10 units, arrival 2026-10-22, warehouse MAIN
  SL-200-3: MAKI-ZALM,    8 units, arrival 2026-10-23, warehouse SECONDARY
```

Repeated SKUs are intentional. Identity must not be `(orderId, sku)` or
`(shipmentId, sku)`: different lines of the same product can have different dates
or fulfillment requirements.

## Proposed solution

### Header and line contracts

Retain `demandOrders` and `inboundShipments` as request collections, but make their
elements document headers with nonempty line collections:

```text
DemandOrder
  orderId
  customer
  priority
  placedDate
  lines[]
    orderLineId
    sku
    requestedQuantity
    requiredDeliveryDate

InboundShipment
  shipmentId
  lines[]
    shipmentLineId
    sku
    expectedQuantity
    expectedArrivalDate
    warehouseCode
```

Keep delivery dates, arrival dates, and receiving warehouses at line level.
Header-level defaults can be added later if useful; they must resolve to explicit
line values before validation and hashing. Avoid storing competing header and
line values without a defined inheritance rule.

Use stable, globally unique, opaque line IDs. IDs must remain unchanged across
retries and backorder fulfillment. A document can contain the same SKU more than
once, but a line ID cannot belong to two different documents.

### Persistence model

Recommended target:

```text
demand_order
  order_id PK
  run_id FK (original allocation run)
  customer, priority, placed_date
  reservation_status, reservation_created_at, reservation_expires_at
  confirmed_at, expired_at
    |
    +-- demand_order_line
          order_line_id PK
          order_id FK
          sku, requested_quantity, required_delivery_date, sequence_number

inbound_shipment
  shipment_id PK
    |
    +-- inbound_supply (shipment-line storage)
          shipment_line_id PK
          shipment_id FK
          lot_id (stable internal receiving-lot identity)
          sku, expected_quantity, expected_arrival_date, warehouse_code
          quantity_reserved, received
```

Evolve the existing order-claim responsibility into `demand_order`; do not maintain
a second independent order lifecycle. The primary key and original-run reference
continue to prevent duplicate new-demand submission. The header no longer has a
SKU. The current `inbound_supply` data becomes shipment-line state; a staged
migration may retain its old table name temporarily.

Reservations and backorders become line-specific:

| Table | Target identity and constraints |
| --- | --- |
| `stock_reservation` | References `order_line_id` and `stock_id`; unique per line/stock lot. |
| `inbound_reservation` | References `order_line_id` and `shipment_line_id`; unique per demand/supply line pair. |
| `open_backorder` | One active remaining-demand record per `order_line_id`. |
| `allocation_record` | Identifies the order and line; unique per run/order line, not per run/order. |
| `allocation_source` | Inbound source references identify the shipment line rather than only the shipment header. |

Use foreign keys for active relationships and validate SKU compatibility between
demand lines and their reserved supply. Preserve immutable historical quantities
and source details in allocation-run records.

Shipment receipt is line-level state. A shipment header can therefore be partly
received; derive its summary from its lines rather than maintaining a conflicting
header `received` flag. Partial quantity receipt within a single line, multiple
receipt lots per line, and split receipts are separate features.

## Allocation and transaction design

### Register documents before dispatching SKU work

Move order registration out of `AllocationSkuWorker.allocateNewDemand`.
Initialize all headers and their complete line sets in a short transaction before
starting SKU workers:

1. Validate the full document structure and resolve the request/run identity.
2. Acquire the shared projection gate, sorted document identity locks, sorted inbound SKU locks, and then the run row before mutations.
3. Register order headers and lines, and register or validate shipment documents.
4. Assign one reservation creation time and deadline per new order header.
5. Commit initialization, then flatten the lines and dispatch per-SKU allocation.

Use database uniqueness and locked ownership checks to serialize conflicting
initialization. A retry resumes the original run and document set; it must not
overwrite another run's order, change line membership, or reset a deadline.

The implemented TTL begins when the order header is initialized. This intentionally
replaces the current per-SKU start time: queueing and slow SKU processing consume
the same order-level TTL.

The later [concurrent-registration feature](./feature-concurrent-document-registration.md)
replaced the original globally exclusive initialization phase with shared
projection coordination and namespaced identity/SKU locks. Independent
registrations can overlap while ownership checks and inbound amendments remain
protected. SKU allocation still uses shared projection locking and per-SKU
transactions after registration commits.

The run stores `documents_initialized` in that same transaction. A retry of an
initialized run does not reapply shipment quantity updates or recreate documents;
this also prevents a delayed retry from reverting a later permitted supply update.

### Preserve per-SKU allocation and partial fulfillment

Flatten order lines into internal demand items carrying both header and line IDs,
then group those items by SKU. Do the same for inbound lines.

Keep existing priority/FIFO allocation rules. For ties, retain document input
position and line position as deterministic final ordering keys. Reservation maps
and backorder lookups must use line identity rather than order identity.

Per-SKU transactions may still commit independently. This feature does not make
multi-line orders all-or-nothing and does not guarantee that every line receives
stock. Line outcomes remain distinct; any order-level allocation summary must be
derived from them.

### Expiry during an incomplete run

Each worker must check the existing header lifecycle and deadline after acquiring
its normal allocation locks. It must not create a missing header or initialize a
new deadline itself.

If the order expires after one SKU commits but before another starts:

- Cleanup releases the committed lines and removes their backorders.
- Late workers must not reserve the remaining lines or recreate active demand.
- Existing per-SKU results remain historical and can be replayed unchanged.
- Unprocessed lines need an explicit skipped-due-to-expiry result, not a
  success-shaped allocation or a newly registered backorder.
- Record the terminal SKU operation outcome so retries can finish the run rather
  than repeatedly attempting an expired order.

Skipped lines use `processingOutcome: SKIPPED_ORDER_EXPIRED`, a null allocation
`status`, empty sources, and zero allocated/backordered quantities. Processed
lines retain their allocation status and use `processingOutcome: PROCESSED`.
Confirmation state remains separate from allocation quantity status.

## Confirmation and reservation expiry

Keep one lifecycle and deadline on the order header:

```text
PENDING_CONFIRMATION --> CONFIRMED
PENDING_CONFIRMATION --> EXPIRED
```

Expiry applies to all order lines in one transaction. Batching remains by order
count, not line count; do not expire half an order in one batch and the rest later.
A large order can therefore produce a larger cleanup transaction.

Update `ReservationCleanupService` to find all lines of selected headers, release
their current stock and inbound reservations, and delete their backorders.
Determine affected SKUs from those lines, not from a header `sku` field.

Preserve the existing exclusive projection lock, fail-on-inconsistency behavior,
projection rebuild before commit, and after-commit logging. Acquire the global
projection lock first; apply consistent ordering when locking multiple documents
and sources. Never upgrade a worker's shared projection lock to exclusive.

Backorder fulfillment checks the parent lifecycle for every line and never renews
the order deadline. Expired IDs remain non-reusable.

The later confirmation command confirms the order as a whole. Partial line-level
confirmation and cancellation are out of scope. Before implementing confirmation,
define how it handles a header whose initial allocation run has not completed;
the recommended first rule is to reject confirmation until initialization and
initial SKU processing have completed.

The existing order-reservation status endpoint/tool remains order-scoped. Replace
its single `sku` field with line identities/SKUs or a separate line-details
collection, while retaining header lifecycle, timestamps, and `cleanupPending`.

## Shipments, projections, and receipt

Register a shipment header once and track its supplies independently by line ID.
Repeated registration must validate header ownership and line identity, not reject
the header merely because another line contains a different SKU.

Recommended initial amendment policy: freeze line membership, SKU, arrival date,
and warehouse after registration. Preserve supported expected-quantity updates
only for unreceived lines and never below their reserved quantity. Received lines
must not be reopened or received again by replaying registration. Explicit
document amendments are outside this feature.

Change projected inbound lot identity from shipment ID to a stable identity
derived from the shipment line. Do not concatenate arbitrary business IDs into a
stock lot ID: existing stock lot and shipment ID columns have different length
limits. Use a persisted mapping or an internal lot identity that fits the schema.
Retain shipment header and line IDs as provenance.

On receipt, convert reservations using `(orderLineId, stockId)`, preserving
different demand lines even when they reserve the same stock lot. Pending
reservations remain holds, including overdue delivery dates; confirmed
reservations retain existing delivery semantics.

An order line may reserve several supply lines, and one supply line may cover
several order lines. Projections still aggregate by SKU/warehouse/date, but
lot-level snapshots and source records must retain enough identity for receipt,
expiry, and audit. Ledger synchronization must continue to expire due orders and
rebuild projections atomically before applying balances.

## Impact analysis and required work

| Area | Work |
| --- | --- |
| Request DTOs and validation | Add nested line DTOs and nonempty-list validation; validate header IDs, globally unique line IDs, positive quantities, dates, and consistent parent ownership. |
| Domain models and policies | Carry order/shipment line IDs through `AllocationOrder`, supply state, reservation changes, backorder registration, and source selection; retain SKU-based calculation. |
| Orchestration | Add transactional document initialization; flatten lines; adapt deterministic ordering, SKU discovery, incomplete-run handling, and terminal expiry outcomes. |
| Persistence and repositories | Add header/line entities, foreign keys, uniqueness constraints, lookup indexes, and line-aware reservation/backorder queries. Remove the header-SKU assumption. |
| Run persistence and replay | Update record uniqueness and source references; retain per-SKU resumability and immutable historical results. |
| Expiry/status services | Resolve all lines for an order; atomically release their reservations; revise the status DTO without duplicating header lifecycle. |
| Projection/ledger processors | Use supply-line lot identities, line-aware reservation keys, and per-line receipt state; preserve pending/confirmed delivery safety. |
| REST, MCP, RabbitMQ, JobRunr | Update request/response contracts, tool descriptions, event/job payload handling, and persisted-result readers. |
| Product allocation | Map the existing single-product request to one order containing one stable line. Do not broaden that interface into multi-product resolution implicitly. |
| API client and UI | Update duplicated Kotlin/TypeScript contracts, result rendering, examples, and relevant UI/e2e tests under `stockio-api`. |
| CSV and sample publisher | Generate stable document and line identities; do not merge unrelated CSV rows by SKU/customer without an explicit grouping key. |
| Excel reports | Include document and line IDs, preserve separate source rows, and prevent repeated header IDs from merging unrelated line outcomes. |
| Counts and metrics | Define whether counters refer to orders or lines. Keep `openBackorderCount` as distinct open orders for compatibility; expose a separate line count if needed. |
| Documentation and tests | Update architecture, expiry design, API examples, fixtures, migration coverage, and integration acceptance scenarios. |

Do not assume the existing CSV supplies business order/shipment grouping. If it
does not, retain one generated document with one line per imported transaction
until the import contract supplies reliable document identifiers.

## Migration and compatibility

Migration V15 implements the header/line transition after V14. Applied migrations
are not edited.

Legacy order-line IDs equal their former order IDs, and legacy shipment-line IDs
equal their former shipment IDs. Explicit `legacy_order_id` and
`legacy_shipment_id` columns preserve those mappings. Existing inbound lot IDs
are retained where they fit; new supply lines use persisted UUID receiving-lot
IDs independent of business identifier length. Migration rejects missing or
ambiguous order metadata, incompatible reservation SKUs, and inconsistent source
reservation counters.

1. Inventory existing claims, allocation records, reservations, backorders,
   inbound supplies, received lots, cached results, and unfinished jobs/runs.
2. Create order headers from claims and the available original order metadata.
   Preserve lifecycle, all existing timestamps, original-run ownership, and IDs.
   Do not reapply V14's legacy-confirmed backfill to currently pending orders.
3. Create one stable legacy order line per former order and one shipment header
   and supply line per former supply. Persist explicit old-to-new ID mappings.
4. Validate active references, SKU consistency, and quantities before changing
   foreign keys. Fail migration on missing or ambiguous metadata; do not invent
   unrelated line identities to hide inconsistent state.
5. Backfill reservation, backorder, result, and source references. Preserve
   existing received-stock lot identities and snapshots through a durable mapping
   rather than receiving old shipments again.
6. Apply target constraints and indexes after backfill, then retire transitional
   columns/tables once all consumers use the new identities.

Nested lines change the serialized request and therefore its hash. V15 leaves old
hashes unchanged and marks legacy runs as contract version 1; new runs use version
2. Historical run reads remain supported, but legacy request replay is rejected
with an explicit message rather than recomputing its hash under the new shape.

Persisted JobRunr arguments, per-SKU result JSON, and cached allocation-job results
also use the old shape. Drain old allocation jobs before cutover. Cached result
readers default missing line IDs to their legacy order/source IDs and missing
processing outcomes to `PROCESSED`, without rewriting saved result JSON. Reconcile
unfinished legacy runs before upgrading; they cannot be resumed through the new
request contract.

The selected rollout is a coordinated breaking change across repository callers.
There is no parallel legacy endpoint or implicit flat-request adapter. The
single-product interface remains flat externally and creates one deterministic
order line internally.

Old and new application versions must not concurrently allocate, expire, or
receive supply against incompatible schemas/identities. Coordinate the schema
cutover, queued work, application rollout, and rollback plan.

## Recommended implementation sequence

1. Agree on contract compatibility, header/line ownership, TTL start time, result
   shapes, count semantics, and shipment amendment rules.
2. Design and verify migrations and durable legacy identity/lot mappings against
   representative active and historical data.
3. Introduce DTOs/entities and document initialization, then update allocation,
   reservation keys, backorders, and run persistence end-to-end.
4. Adapt expiry and lifecycle reads, followed by projection and receipt handling.
   Do not enable multi-line writes while any single-line consumer remains active.
5. Update all adapters, job serialization/replay, product allocation, imports,
   reports, API client/UI, and documentation.
6. Exercise migration and concurrency scenarios, then deploy the coordinated
   change. Implement the public confirmation flow afterward.

## Acceptance scenarios

- One order contains several SKUs and receives separate line-level allocations,
  reservations, and backorders with one shared lifecycle and deadline.
- Two lines of the same order use the same SKU, including identical dates, without
  merging their identities or quantities.
- Several order lines reserve one stock lot or supply line; releasing one order
  leaves other orders' reservations intact.
- One shipment contains several SKUs, repeated-SKU lines, and different line
  arrival dates/warehouses without identity or projected-lot collisions.
- Receiving one shipment line does not receive, reset, or consume another line.
- Receipt converts pending and confirmed reservations with the correct demand
  line identity, and repeated ledger synchronization is idempotent.
- Expiring an order releases all its lines across SKUs/warehouses atomically,
  including converted inbound reservations and remaining backorders.
- One SKU commits before expiry and another starts afterward: no late worker
  recreates reservations, renews the deadline, or leaves the run retrying forever.
- Concurrent runs cannot claim an existing order or move a line to another
  header. A retry with the same request is idempotent; changed data is rejected.
- Migration preserves confirmed, pending, and expired lifecycles, current
  counters, received stock, and readable historical results.
- Legacy request replay and queued-job execution follow the chosen compatibility
  policy without duplicate reservations, documents, or receipts.
- Reports, status reads, counts, and UI results distinguish order identity from
  line identity and expose partial line outcomes accurately.

Use PostgreSQL integration tests for constraints, migration, locking, multi-SKU
expiry races, and receipt conversion. Use focused domain/contract tests for
flattening, validation, deterministic ordering, hashing compatibility, and
repeated-SKU line handling.
