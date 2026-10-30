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

## Feature: Add a reusable RabbitMQ allocation producer starter

- **Status:** Draft
- **Priority:** Not set
- **Parent Specs:** [`./architecture.md`](./architecture.md)
- **Bounded Context:** Messaging Integration & Allocation Contracts

### Overview

Provide a reusable Spring Boot producer starter so other applications can
publish Stockio `AllocationRequest` messages without depending on the executable
`stockio-service` module. Extract the wire contract into a shared artifact and
register the typed RabbitMQ publisher through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Keep the current sample-data generation in `stockio-service`; the reusable
starter owns publishing and configuration, not demo scheduling or consumer
behavior.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Reusable allocation contract | Extract the allocation-request wire model into a versioned contracts module and preserve the JSON shape accepted by the existing Stockio listener. |
| FR-02 | Typed RabbitMQ publisher | Provide a publisher API that sends an allocation request through the host application's `RabbitTemplate` using configured exchange and routing-key values. |
| FR-03 | Spring Boot auto-configuration | Register producer auto-configuration through `AutoConfiguration.imports`; bind configuration properties, require explicit enablement, and back off when the host provides equivalent beans. |
| FR-04 | Reuse from Stockio sample publisher | Adapt the existing scheduled sample publisher to use the reusable publisher while keeping product lookup, sample generation, and scheduling in `stockio-service`. |
| FR-05 | External artifact consumption | Package the contract and producer starter as versioned, independently consumable artifacts without depending on the executable service module. |
| FR-06 | Producer-only boundary | Keep the allocation listener, queue and dead-letter topology, JobRunr fallback, and consumer retry behavior in `stockio-service`. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Wire compatibility | Preserve the allocation message field names, enum values, and date serialization expected by the existing listener. |
| NFR-02 | Safe opt-in and customization | Do not activate producer beans unless enabled, and allow host applications to override the publisher or message converter. |
| NFR-03 | Explicit failure behavior | Surface conversion and send exceptions; do not claim broker delivery guarantees beyond the configured `RabbitTemplate` and broker behavior. |
| NFR-04 | Library build isolation | Keep reusable modules as libraries rather than executable Spring Boot applications, with dependencies scoped to producer and contract responsibilities. |

### Plan

1. Extract the allocation request wire types into a versioned `stockio-allocation-contracts` module and update service imports without changing the existing JSON contract.
2. Add a reusable RabbitMQ producer starter with configuration properties, a typed publisher, conditional auto-configuration, and the `AutoConfiguration.imports` registration.
3. Configure the starter as a library module, add artifact publication/versioning support, and adjust root Gradle plugin application so application plugins are not forced onto library modules.
4. Adapt the scheduled sample publisher to call the reusable publisher; retain sample generation and scheduling in the service, and retain listener topology and consumer behavior there.
5. Add focused auto-configuration, serialization, send-routing, override, failure, and compatibility tests, plus a consumer-compatible message test.

### Resources

- [Scenario 11: Publish an Allocation Request from Another Spring Boot Application](./scenarios.md#scenario-11-publish-an-allocation-request-from-another-spring-boot-application)
- [Feature issue: reusable RabbitMQ allocation producer starter](./issues/feature_rabbitmq_allocation_producer_starter.md)
- [Architecture](./architecture.md)

## Feature: Generate OpenAPI interfaces for REST controllers

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [`./architecture.md`](./architecture.md)
- **Bounded Context:** REST API / selected controller

### Overview

For a controller selected by the user, inspect its existing implementation and
tests, create or update an OpenAPI specification that captures its current
behavior, then generate a Kotlin Spring API interface and make that controller
implement it without changing the external contract.

The initial `AllocationController` and user-selected `ReservationController`
integrations, plus the subsequently selected `ProductAllocationController`,
are complete. Each controller has an OpenAPI specification based on its
existing implementation and a generated Kotlin Spring interface. Generation
is owned by `project(":stockio-api")`; `stockio-service` compiles generated
sources directly without depending on the MCP client artifact.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | User-selected controller | Ask which controller to work on and use the exact controller provided by the user. |
| FR-02 | Specification-first workflow | Inspect the chosen controller and tests, then create or update its OpenAPI specification before interface generation. |
| FR-03 | Behavior-accurate specification | Describe existing routes, parameters, DTO schemas, validation, status codes, headers, and observable behavior without inventing or changing the contract. |
| FR-04 | Kotlin Spring interface generation | Generate an interface-only Kotlin Spring API from the selected specification with Boot 3-compatible annotations and appropriate `ResponseEntity` signatures. |
| FR-05 | Controller implementation | Make the selected controller implement the generated interface while preserving behavior and avoiding duplicate Spring mappings. |
| FR-06 | Model ownership | Reuse existing service DTOs where appropriate or generate/map API models explicitly; avoid duplicate or divergent types. |
| FR-07 | Reproducible Gradle generation | Keep the generator version pinned, generate under `build/`, and wire selected generated sources and task dependencies into service compilation. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | API compatibility | The specification and generated interface must preserve the selected controller's existing HTTP paths, payloads, validation, and response behavior. |
| NFR-02 | Clean-build reproducibility | A clean checkout must validate and generate the selected interface and compile without committed generated sources. |
| NFR-03 | Module boundaries | The service must not depend on the MCP client artifact solely to obtain interfaces; compile generated sources directly into the service. |
| NFR-04 | Maintainability | Keep plugin configuration explicit, use unique outputs for additional controllers, and treat each OpenAPI document as the source for its generated routes. |

### Plan

1. Ask which controller to implement an interface for and inspect the user-selected source, tests, DTOs, and error behavior.
2. Create or update its controller-specific OpenAPI specification before generating code; validate the specification against the implementation.
3. Configure a controller-specific Kotlin Spring generation/validation task in `project(":stockio-api")`, using DTO mappings or generated models as appropriate.
4. Add the generated source directory and task dependency to `stockio-service`, without adding a dependency on the MCP client artifact.
5. Implement the generated interface in the selected controller and remove only duplicate route annotations.
6. Add focused endpoint tests and run OpenAPI validation, generation, Kotlin compilation, and relevant controller tests.

### Resources

- [Feature issue: Generate OpenAPI interfaces for REST controllers](./issues/feature_add_interfaces_to_the_controllers.md)
- [Initial AllocationController OpenAPI document](../../stockio-api/src/main/resources/openapi/allocation-controller.yaml)
- [ReservationController OpenAPI document](../../stockio-api/src/main/resources/openapi/reservation-controller.yaml)
- [ProductAllocationController OpenAPI document](../../stockio-api/src/main/resources/openapi/product-allocation-controller.yaml)
- [Scenario 1: Allocate a Product by Name](./scenarios.md#scenario-1-allocate-a-product-by-name)
- [Scenario 5: Inspect an Order Reservation](./scenarios.md#scenario-5-inspect-an-order-reservation)
- [Scenario 2: Allocate Structured New Demand](./scenarios.md#scenario-2-allocate-structured-new-demand)
- [Scenario 3: Monitor an Allocation Job](./scenarios.md#scenario-3-monitor-an-allocation-job)
- [Scenario 7: Monitor Stock-Ledger Freshness](./scenarios.md#scenario-7-monitor-stock-ledger-freshness)
- [Architecture](./architecture.md)

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
