# Feature: Allow concurrent allocation document registration

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Allocation & Reservation Concurrency
- **Implementation started:** Yes. Complete, at the user's request.
- **Parent Specs:** [Architecture](../../../docs/architecture.md)
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-allow-concurrent-allocation-document-registration)
- **Scenario:** [Scenario 14](../../../docs/scenarios.md#scenario-14-register-independent-allocation-documents-concurrently)

## Context

This is the completed registration baseline. Its shared/exclusive gate has been
superseded by the [per-SKU protocol](./feature-per-sku-stock-processing.md).
The historical requirements below remain unchanged; identity, SKU and run-row
protections are retained by the follow-up.

Globally exclusive registration serializes unrelated requests. Simply changing
that lock to shared is unsafe: check-then-save document creation can race,
assigned-ID JPA merges can reparent lines, and inbound amendments can race with
reservation/receipt state. The user requested the narrower locking alternative
before other backpressure changes.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Shared registration gate | Allow independent document registrations to overlap under the existing shared projection lock while excluding exclusive projection, ledger, and expiry transactions. |
| FR-02 | Document identity protection | Serialize competing order headers/lines and shipment headers/lines, including absent identities, using sorted namespaced database lock rows and existing identity constraints. |
| FR-03 | Safe inbound amendments | Acquire the existing inbound SKU locks in sorted order and lock supply rows before validating/amending quantities, preserving reservations, receipt state, immutable membership, and SKU metadata. |
| FR-04 | Consistent transaction ordering | Acquire shared gate -> sorted document keys -> sorted inbound SKUs -> run row -> business rows; preserve worker/exclusive-operation ordering and atomic rollback without lock upgrades. |
| FR-05 | Retry and compatibility | Preserve initialized/completed-run skipping, order deadlines, request idempotency, allocation rules, wire contracts, and independent per-SKU commits; add concurrency regression coverage and synchronized documentation. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Database-wide coordination | Coordinate across service instances using transaction-scoped database row locks, not in-process synchronization; introduce only an additive lock-table migration. |
| NFR-02 | Bounded change | Do not implement rate limiting, admission control, background/scoped projection rebuilding, pool tuning, virtual threads, or a new persistence stack in this feature. |

## Implementation plan

1. Add an additive document-lock migration and matching entity/repository port/JPA adapter, following the existing SKU lock pattern.
2. Switch registration to shared-gate, sorted document/SKU, run-row, and supply-row locking while preserving document validation and retry behavior.
3. Add deterministic PostgreSQL concurrency tests for independent overlap, exclusive exclusion, identity conflicts, supply amendments versus allocation, and reversed SKU input order.
4. Validate migration startup and affected allocation/reservation/ledger/projection tests, then synchronize architecture, scenarios, design notes, README, issue, and active context.

## Functional scenario

### Gherkin description
**Given** concurrent allocation requests with independent order and shipment identities \
**When** their documents are registered before SKU allocation \
**Then** independent registrations can overlap under the shared projection gate \
**And** competing order, line, and shipment identities cannot be reassigned or merged \
**And** inbound amendments serialize with allocation for the affected SKUs without losing reservations \
**And** exclusive projection, ledger, and expiry operations remain excluded until registration commits

### High-level technical analysis
- `AllocationDocumentService.initialize` takes the existing shared database projection gate instead of the exclusive gate
- Namespaced document-identity lock rows protect order headers/lines and shipment headers/lines, including identities that have not yet been inserted; existing primary, unique, and foreign-key constraints remain authoritative
- Registration acquires the shared gate, all document keys in sorted order, all inbound SKU locks in sorted order, the run row, and then business rows; locking the run after the SKU locks avoids waiting for a worker's SKU while blocking its run foreign-key checks
- Existing SKU workers retain shared-gate -> SKU -> business-row ordering; projection, ledger, and expiry retain exclusive-gate-first ordering, with no shared-to-exclusive upgrade
- Inbound registration locks existing shipment supply rows before validation and amendment; initialized/completed runs still skip re-registration and deadline renewal
- This changes concurrency only, not request contracts, allocation rules, full projection rebuilding, admission control, worker/pool sizes, or cross-SKU transaction boundaries

### Acceptance Criteria
- Two registrations with disjoint document identities and inbound SKUs can both finish their registration bodies before either transaction commits
- An exclusive projection-gate request cannot pass an active registration transaction
- Concurrent duplicate headers and cross-parent order/shipment line IDs are rejected without reparenting, partial registration, or duplicate holds
- An amendment waiting behind allocation revalidates the committed reserved quantity and cannot reduce expected supply below it or overwrite it
- Opposite input orders for overlapping inbound SKUs do not cause opposite lock acquisition
- Existing retry/idempotency, immutable shipment membership, received-supply restrictions, expiry, ledger, and projection behavior remain valid

## Technical analysis and affected components

- `AllocationDocumentService` is the only production caller of
  `InboundSupplyService`. It already owns the registration transaction and
  run-row lock; it will own the new identity and existing SKU locks as well.
- Add `allocation_document_lock(lock_key PRIMARY KEY)` with namespace prefixes
  for order, order-line, shipment, and shipment-line identities. Lazily create
  and lock rows using `INSERT ... ON CONFLICT DO NOTHING`, then pessimistic
  locking, like `allocation_sku_lock`. Retain lock rows; deleting them during
  normal processing could undermine coordination.
- The new table is needed because locking existing business rows does not
  protect an identity that has not been inserted. It also avoids hash/advisory
  lock collisions and does not change established business keys or constraints.
- Acquire the run row after inbound SKU locks. A retry waiting for a worker's
  SKU must not hold an exclusive run lock that could block that worker's
  foreign-key checks. Workers never acquire document identity locks.
- `InboundSupplyService` will read existing supply through a pessimistically
  locked query after registration has obtained SKU protection. Wrong SKU,
  membership, arrival, warehouse, received quantity, and below-reservation
  amendments remain rejected.
- Existing exclusive-gate workflows (`StockProjectionProcessor`,
  `StockLedgerProcessor`, `ReservationCleanupService`) remain unchanged.
  Their gate excludes registration before either side takes business locks.
- No HTTP/MCP/RabbitMQ contracts, DTOs, frontend behavior, or test-harness
  dependencies change.

## Validation and risks

- Extend `AllocationIntegrationTest`, using real PostgreSQL transactions,
  bounded waits, backend blocking information, and parallel connections
  rather than timing-only sleeps.
- Verify unrelated registrations overlap and the exclusive gate remains
  blocked; duplicate headers and cross-parent line conflicts reject cleanly.
- Hold a SKU allocation transaction while starting a supply amendment; verify
  amendment waits, rereads committed counters, and rejects an invalid reduction.
- Submit overlapping inbound SKU sets in reverse order while one SKU is held
  externally; verify bounded completion after release.
- Run existing initialized-document retry, reservation/expiry, partial-run,
  ledger/projection, migration, and ownership tests. Flyway startup must apply
  the additive migration and Hibernate must validate the schema.
- This removes one source of global serialization, not the full projection
  bottleneck or database capacity limits. Lock waits still consume connections.
- Identity-lock rows add storage proportional to distinct document identities.
  No automatic pruning or admission-control guarantee is introduced.

## Completed implementation and verification

- Added V17 and the document-lock entity/port/JPA repository using the existing
  SKU-lock insertion/locking pattern; existing business constraints are unchanged.
- Registration now holds the shared gate, sorted identity locks, sorted inbound
  SKU locks, and then its run lock. Existing supply is read under pessimistic
  locking before amendment checks and writes.
- Added eight PostgreSQL regression cases: independent overlap/exclusive
  exclusion; four header/line identity-conflict cases; amendment versus worker;
  same-run retry versus worker; and reversed inbound SKU acquisition.
- The affected validation run passed 90 tests: all 51 allocation integration
  cases plus reservation configuration/controller and stock ledger/projection
  unit cases. Flyway startup applied all 17 migrations and Hibernate schema
  validation succeeded; existing legacy migration/retry/lifecycle cases passed.
- Tests use bounded lock timeouts and actual PostgreSQL blocking chains, not
  sleeps to infer contention. Blocker inspection follows queue predecessors
  because PostgreSQL may report another waiter as the immediate blocker.
- Projection rebuilding, ledger/expiry exclusivity, worker/pool sizes,
  request contracts, admission control, and the user's controller edit remain
  unchanged.

## Separate follow-up

[Per-SKU stock processing](./feature-per-sku-stock-processing.md) is a new
completed feature, not a reopening of this registration baseline. It implements scoped
projection/ledger work, whole-order expiry coordination, durable partial-run
recovery, and removal of gate acquisition from all participating writers.
Deploy the replacement protocol across all writers together.
