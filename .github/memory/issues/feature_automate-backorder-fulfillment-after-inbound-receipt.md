# Feature: Automate backorder fulfillment after physical inbound receipt

## Status and scope

The historical handoff below is strengthened by the implemented
[per-SKU stock-processing feature](./feature-per-sku-stock-processing.md):
`StockLedgerSkuWorker` now records receipt follow-up transactionally, and a
retryable dispatcher schedules it outside the receipt transaction. The old
after-commit-only callback design is superseded; fulfillment rules are unchanged.

- **Status:** In-progress
- **Priority:** P1
- **Bounded Context:** Inventory & Allocation
- **Specification:** [`../specifications.md`](../specifications.md#feature-automate-backorder-fulfillment-after-physical-inbound-receipt)
- **Scenario:** [`Scenario 4: Fulfill Open Backorders`](../scenarios.md#scenario-4-fulfill-open-backorders)

Automate fulfillment of persisted open backorders when stock-ledger
synchronization physically receives inbound supply. Registering
`AllocationRequest.inboundShipments` remains an expected-supply operation and
must not trigger fulfillment before the shipment has arrived.

The implementation should enqueue a dedicated JobRunr job after the ledger
transaction commits, identify the affected SKUs, and invoke a reusable
backorder-fulfillment application service using an internal command. The
existing MCP tool remains available as a manual and recovery trigger.

## Scenario

### Gherkin description

**Given** persisted open backorders and inbound supply that becomes physically received by stock-ledger synchronization \
**When** the ledger transaction commits newly received inbound supply \
**Then** Stockio enqueues a dedicated JobRunr backorder-fulfillment job for the affected SKUs \
**And** the job fulfills eligible, non-expired backorders by priority and FIFO \
**And** any remaining shortage stays open \
**And** an operator can invoke `allocate_open_backorders` as a manual or recovery trigger without creating a new-demand request

### High-level technical analysis

- `StockLedgerProcessor` is the physical receipt boundary because it marks
  inbound supply as received inside the ledger transaction.
- Job scheduling must happen from an after-commit hook or equivalent mechanism;
  scheduling before commit could fulfill against a receipt that later rolls
  back.
- The current `AllocationService.allocateOpenBackorders(AllocationRequest)`
  couples recovery fulfillment to an external request DTO and request hashing.
- A `BackorderFulfillmentCommand` should carry `asOfDate`, `trigger`, and an
  optional `skuScope`.
- A reusable application service should retain the existing
  `AllocationSkuWorker.fulfillBackorders`, allocation policies, repository
  locking, and projection refresh behavior.
- Automatic fulfillment must not call `AllocationService.allocation(...)`,
  because that path represents `NEW_DEMAND` and customer-order creation.

### Acceptance Criteria

- Expected inbound supply registration alone does not enqueue fulfillment.
- Newly received inbound supply enqueues fulfillment only after the ledger
  transaction commits.
- The job processes only affected SKUs when a scope is available.
- Eligible backorders are processed by priority and FIFO `placedDate`.
- Expired backorders and reservations are excluded.
- Partial fulfillment and remaining open quantities are preserved.
- The projection is refreshed after successful fulfillment.
- Job retries and duplicate/concurrent triggers do not reserve the same supply
  twice.
- `allocate_open_backorders` remains available as a manual/recovery trigger
  backed by the same application service.
- Automatic fulfillment does not create a new-demand allocation run.

## Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Dedicated fulfillment command | Introduce an internal `BackorderFulfillmentCommand` containing `asOfDate`, `trigger`, and an optional affected-SKU scope. |
| FR-02 | Post-commit receipt trigger | Schedule a JobRunr fulfillment job only after stock-ledger commit confirms newly received inbound supply. |
| FR-03 | Reusable application service | Extract fulfillment into a reusable service such as `BackorderFulfillmentService.fulfill(command)`. |
| FR-04 | Scoped eligible fulfillment | Process eligible, non-expired open backorders for affected SKUs while preserving priority/FIFO ordering. |
| FR-05 | Reuse allocation rules | Reuse the existing per-SKU worker, policy, repository-port, and projection mechanisms. |
| FR-06 | Idempotent concurrency | Protect retries and overlapping jobs from duplicate reservation or fulfillment. |
| FR-07 | Manual recovery | Keep the MCP backorder tool backed by the reusable service. |
| FR-08 | New-demand separation | Do not invoke the `NEW_DEMAND` allocation path for automatic fulfillment. |

## Non-Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Transaction safety | A rolled-back ledger receipt must not leave a queued fulfillment job. |
| NFR-02 | Operational visibility | Job failures, retries, duplicate suppression, and fulfillment outcomes must remain observable. |
| NFR-03 | Projection consistency | Successful fulfillment must refresh the stock projection after allocation changes commit. |

## Technical analysis

### Current implementation

- `InboundSupplyService.registerInboundSupply(...)` persists expected shipment
  and supply records while processing an allocation request.
- `AllocationService.allocation(...)` handles `NEW_DEMAND` and registers
  documents, reservations, and open backorders.
- `AllocationService.allocateOpenBackorders(...)` currently requires a complete
  `AllocationRequest` with `BACKORDER_FULFILLMENT`.
- `AllocationProcessingService.process(...)` dispatches between the two run
  types and refreshes projections afterward.
- `StockLedgerProcessor` identifies arrived, unreceived supply and marks it
  received during the ledger transaction.
- `AllocationJob` provides the existing JobRunr execution and retry pattern.
- `AllocationSkuWorker.fulfillBackorders(...)` contains the reusable per-SKU
  fulfillment behavior.

### Proposed implementation

1. Introduce the command and trigger model in the application layer.
2. Extract backorder fulfillment orchestration from `AllocationService` into a
   dedicated service that accepts the command.
3. Return or record the shipment-line/SKU set newly marked received by the
   ledger processor.
4. Register an after-commit callback that schedules a stable JobRunr job with
   the date, trigger, and SKU scope.
5. Use SKU-level locks and persisted operation state to make retries and
   overlapping jobs safe.
6. Preserve the manual MCP adapter by translating its request into a manual
   command.
7. Refresh projections after successful fulfillment using the existing
   application orchestration boundary.

### Risks and compatibility

- Scheduling from a transaction synchronization callback must not obscure
  scheduling failures; failures need explicit logging and operational recovery.
- A receipt can affect multiple shipment lines and SKUs, so the scope must be
  deduplicated and persisted or deterministically reconstructed for retries.
- A backorder may become eligible because of existing stock rather than the new
  receipt; the manual trigger may retain an unscoped mode, while automatic
  jobs should prefer affected SKUs to avoid unnecessary global runs.
- A fulfilled backorder must not be represented as a new demand order or
  overwrite the historical new-demand run.
- The current request-hash/idempotency model applies to external allocation
  requests; automatic command/job idempotency needs an event or job identity
  appropriate to received supply.

## Implementation plan

1. Add command and trigger types plus focused unit tests.
2. Extract the reusable backorder fulfillment service and adapt the MCP tool.
3. Extend ledger synchronization to report newly received supply and enqueue
   post-commit JobRunr work.
4. Add concurrency and idempotency guards.
5. Add integration tests covering commit/rollback, receipt eligibility,
   priority/FIFO, expiry, retries, duplicate jobs, and projection refresh.
6. Update architecture, README, and operational documentation if the job
   becomes a supported production workflow.
