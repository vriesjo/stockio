# Specifications

For each feature, we will upfront define the specifications the agent is working on.
Here we connect Features with Specifications. It's about top-down tracing of Specs -> Features.
After writing a feature file and adding a scenario, the agent should link both the scenario and the feature
to the specification that is being implemented.

A feature has a

- **Status:**  [Draft / Approved / In-Progress/ Done]
- **Parent Specs:** The specification that should be used as a baseline for implementing the feature.
- **Bounded Context:** e.g. within the domain of Allocation

Once the issue is created, the agent should update it with the functional requirements (FR)
and non-functional requirements (NFR) that the agent must meet.

## Feature: Separate scheduled stock-ledger synchronization from allocation

- **Status:** In-progress
- **Parent Specs:** [`./architecture.md`]
- **Bounded Context:** Inventory & Allocation

### Overview
This feature implements a separate scheduled stock-ledger synchronization process that is decoupled from the allocation process.
The goal is to ensure that stock-ledger updates are performed independently,allowing for better scalability and maintainability of the system.

### Resources
- [Feature: separate scheduled stock-ledger synchronization from allocation](./issues/feature_stock_ledger_sync_job.md)
- [Scenario 6: Synchronize the Physical Stock Ledger](./scenarios.md#scenario-6-synchronize-the-physical-stock-ledger)

## Feature: Automate backorder fulfillment after physical inbound receipt

- **Status:** In-progress
- **Priority:** P1
- **Parent Specs:** [`./architecture.md`](./architecture.md)
- **Bounded Context:** Inventory & Allocation

### Overview

Automate fulfillment of persisted open backorders when the regular stock-ledger
synchronization physically receives inbound supply. Expected inbound supply is
not sufficient to trigger fulfillment: registering an `AllocationRequest` only
records a future supply expectation and may not result in an arrived shipment.

After a successful ledger transaction marks inbound supply as received, Stockio
should enqueue a dedicated JobRunr job. The job should invoke a reusable
backorder fulfillment application service with an internal command rather than
constructing an `AllocationRequest` or invoking the `NEW_DEMAND` flow. The
existing MCP backorder tool remains available as a manual and recovery trigger.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Dedicated fulfillment command | Introduce an internal `BackorderFulfillmentCommand` containing `asOfDate`, `trigger`, and an optional affected-SKU scope. |
| FR-02 | Post-commit receipt trigger | After the stock-ledger transaction commits newly received inbound supply, enqueue a JobRunr backorder-fulfillment job. Registering expected supply alone must not trigger the job. |
| FR-03 | Reusable application service | Refactor backorder fulfillment behind a reusable application service such as `BackorderFulfillmentService.fulfill(command)` rather than requiring an `AllocationRequest`. |
| FR-04 | Scoped eligible fulfillment | Fulfill only eligible, non-expired open backorders for affected SKUs, preserving lower-priority-number-first and FIFO `placedDate` ordering. |
| FR-05 | Existing worker and policy reuse | Reuse the existing per-SKU workers, allocation policies, reservation ports, and projection refresh behavior instead of duplicating allocation rules. |
| FR-06 | Idempotent concurrent execution | Make retries and concurrent triggers safe so received supply and backorders are not reserved twice or processed by duplicate jobs. |
| FR-07 | Manual recovery compatibility | Keep `allocate_open_backorders` as a manual and recovery adapter backed by the same fulfillment application service. |
| FR-08 | New-demand separation | Do not call `AllocationService.allocation(...)` and do not model automatic fulfillment as `NEW_DEMAND` or create a new customer order. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Transaction boundary | The JobRunr job must be scheduled after the successful ledger commit; a rolled-back receipt must not trigger fulfillment. |
| NFR-02 | Operational reliability | JobRunr retries, failures, and duplicate-trigger suppression must remain observable and must not return success-shaped fallback results. |
| NFR-03 | Forecast consistency | Successful fulfillment must refresh the stock projection after per-SKU work commits. |

### Plan

1. Model `BackorderFulfillmentCommand` and the trigger type in the allocation/reservation application layer.
2. Extract the current backorder logic from `AllocationService.allocateOpenBackorders(AllocationRequest)` into a reusable fulfillment service while preserving worker and policy behavior.
3. Add a post-commit ledger synchronization hook that identifies newly received shipment-line SKUs and schedules a stable, retryable JobRunr fulfillment job.
4. Add idempotency and concurrency coordination for repeated receipt events, overlapping SKU scopes, and JobRunr retries.
5. Adapt the `allocate_open_backorders` MCP tool to create a manual command and use the same application service.
6. Add focused tests for expected-but-not-received supply, post-commit scheduling, SKU scoping, priority/FIFO ordering, expiry filtering, retries, duplicate triggers, and projection refresh.

### Resources

- [Scenario 4: Fulfill Open Backorders](./scenarios.md#scenario-4-fulfill-open-backorders)
- [Feature issue: automate backorder fulfillment after physical inbound receipt](./issues/feature_automate-backorder-fulfillment-after-inbound-receipt.md)
- [Architecture](./architecture.md)
