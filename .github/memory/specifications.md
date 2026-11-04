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
Keep sample-data generation in `stockio-test-harness/publisher`; the reusable
starter owns publishing and configuration, not demo scheduling or consumer
behavior.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Reusable allocation contract | Extract the allocation-request wire model into a versioned contracts module and preserve the JSON shape accepted by the existing Stockio listener. |
| FR-02 | Typed RabbitMQ publisher | Provide a publisher API that sends an allocation request through the host application's `RabbitTemplate` using configured exchange and routing-key values. |
| FR-03 | Spring Boot auto-configuration | Register producer auto-configuration through `AutoConfiguration.imports`; bind configuration properties, require explicit enablement, and back off when the host provides equivalent beans. |
| FR-04 | Reuse from Stockio sample publisher | Adapt the existing scheduled sample publisher to use the reusable publisher while keeping JDBC catalogue lookup, sample generation, and opt-in scheduling in `stockio-test-harness/publisher`. |
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
4. Adapt the scheduled sample publisher in `stockio-test-harness/publisher` to call the reusable publisher; retain JDBC catalogue lookup and opt-in scheduling in the harness, and retain listener topology and consumer behavior in the service.
5. Add focused auto-configuration, serialization, send-routing, override, failure, and compatibility tests, plus a consumer-compatible message test.

### Resources

- [Scenario 11: Publish an Allocation Request from Another Spring Boot Application](./scenarios.md#scenario-11-publish-an-allocation-request-from-another-spring-boot-application)
- [Feature issue: reusable RabbitMQ allocation producer starter](./issues/feature_rabbitmq_allocation_producer_starter.md)
- [Architecture](./architecture.md)

## Feature: Add a Kafka allocation consumer alongside RabbitMQ

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md), [Allocation admission](#feature-bound-allocation-execution-with-a-fail-fast-bulkhead), [Per-SKU stock processing](#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Bounded Context:** Allocation Messaging & Ingress

### Overview

Add an opt-in Kafka consumer for Stockio allocation requests while retaining
the current RabbitMQ consumer. The Kafka adapter should invoke the same
synchronous allocation execution path and preserve stable request identity,
bounded admission, and explicit offset/recovery semantics. This is a stock allocation workflow, not payment processing. Consume the existing
`AllocationRequest` JSON with `requestId` as the stable job ID. Mirror
RabbitMQ's capacity backoff, invalid-message dead-lettering, and durable JobRunr
fallback for other failures. Keep Kafka disabled by default and merge the
user-supplied local broker baseline into the existing Compose stack.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Kafka allocation adapter | Add a Kafka listener for the agreed allocation message contract and delegate to the existing allocation application path; keep broker-specific code outside allocation business logic. |
| FR-02 | Stable execution identity | Derive the execution/job identity deterministically from the logical request so redelivery does not create a new logical allocation; define and test simultaneous duplicate behavior rather than relying only on the completion cache. |
| FR-03 | Safe offset progress | Commit offsets only after successful allocation completion or after a durable fallback has accepted responsibility for the same request. |
| FR-04 | Explicit failure policy | Distinguish capacity pressure, transient processing failures, malformed/permanent requests, and successful completion; use bounded-delay capacity backoff and finite retries/DLT for other failures. |
| FR-05 | Bounded consumption | Use a bounded local Kotlin channel and fixed worker concurrency to smooth short bursts; keep the existing `AllocationAdmission` semaphore authoritative, preserve records on channel or semaphore saturation, and do not launch unbounded per-record coroutines or buffers. |
| FR-06 | RabbitMQ coexistence | Keep the RabbitMQ listener and its contract operational; document behavior when both brokers deliver the same logical request. |
| FR-07 | Operational visibility | Log and measure bounded-cardinality consumption outcomes, retries, capacity deferrals, and processing duration without using request or SKU identifiers as metric labels. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | At-least-once safety | Assume records can be redelivered; do not claim exactly-once stock allocation from Kafka offset transactions alone. |
| NFR-02 | Bounded resource use | Bound the local channel and active workers; preserve the existing allocation admission and stock-coordination guarantees, and never treat in-memory channel enqueueing as durable acceptance. |
| NFR-03 | Opt-in rollout | Kafka listener activation and connection settings must be explicit so introducing Kafka does not unexpectedly alter existing RabbitMQ operation. |
| NFR-04 | Honest performance claims | Do not claim non-blocking processing, throughput improvement, or latency bounds without representative measurements. |

### Plan

1. Add opt-in Spring Kafka dependencies, typed service configuration, source and DLT topic declarations, and a raw-string consumer compatible with the existing `AllocationRequest` JSON contract.
2. Implement synchronous listener completion backed by a bounded Kotlin channel and fixed workers; preserve per-partition order and commit offsets only after execution or durable JobRunr handoff.
3. Mirror RabbitMQ's capacity backoff and JobRunr fallback behavior, dead-letter malformed/oversized requests, and preserve records on channel/bulkhead saturation.
4. Merge the supplied local Kafka stack into the existing Docker Compose file and document disabled-by-default configuration and startup.
5. Add focused listener, queue, retry/DLT, duplicate-delivery, context lifecycle, and broker-backed tests; measure before setting production concurrency or claiming throughput improvements.

### Resources

- [Scenario 18: Consume an Allocation Request from Kafka](./scenarios.md#scenario-18-consume-an-allocation-request-from-kafka)
- [Feature issue: Kafka allocation consumer](./issues/feature-concurrent-per-event-allocation.md)
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

## Feature: Replace the frontend MCP bridge with an Axios service client

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md), [Product allocation REST contract](../../stockio-api/src/main/resources/openapi/product-allocation-controller.yaml)
- **Bounded Context:** Frontend Integration & Product Allocation

### Overview

The frontend no longer requires a separate Spring MCP client application.
The same-origin Next.js `POST /api/allocate` route uses
a server-side Axios HTTP client to call the independently running
`stockio-service` at `http://127.0.0.1:9090/api/products/allocate`. The existing
REST controller calls `productAllocationService.allocate(request)` and the
frontend consumes its plain JSON result, not an MCP envelope.
`ProductAllocationTools.kt` and `allocate_product_by_name` are removed.
The UI and service run as separate processes without a bundled UI/service fat
JAR or fat-JAR startup dependency. Shared Kotlin DTOs remain a normal library
dependency, and OpenAPI generation remains API-module-owned. Implementation
and scoped validation are complete; priority remains unset.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Same-origin frontend route | Keep browser submissions to Next.js `POST /api/allocate`; perform the service call inside that route, without a separate Spring frontend backend. |
| FR-02 | Axios service client | Add Axios to the UI package and a small server-side TypeScript client using `STOCKIO_API_URL`, default `http://127.0.0.1:9090`, to POST flat `ProductAllocationRequest` JSON to `/api/products/allocate`. |
| FR-03 | Existing application use case | Use `ProductAllocationController.allocate` and its direct `productAllocationService.allocate(request)` delegation; do not invoke MCP or duplicate allocation logic in TypeScript. |
| FR-04 | Plain REST result | Update frontend types and result parsing to consume plain `ProductAllocationResponse`, preserving domain outcomes, evidence, allocation quantities, line/source identities, skipped-expiry outcomes, and backorders. |
| FR-05 | Explicit HTTP failures | Preserve upstream HTTP status and JSON bodies; show service validation errors, reject malformed successful payloads, and return explicit `502` connection and `504` timeout responses. |
| FR-06 | Request identity | Preserve unchanged request IDs on manual retries and generate a new ID when an attempted request is edited; do not automatically retry allocation POSTs. |
| FR-07 | Retire obsolete MCP adapters safely | Remove the obsolete MCP bridge runtime/configuration/build/test wiring and `ProductAllocationTools.kt` (`allocate_product_by_name`); preserve shared DTOs, OpenAPI assets, generation tasks, and unrelated service MCP tools. |
| FR-08 | Runtime guidance and coverage | Update frontend environment examples, UI wording, and relevant run documentation; cover the new Axios forwarding contract, plain results, failures, and retry identity in focused tests. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Separate processes without fat-JAR coupling | Run Next.js and the service independently; do not bundle the UI into a fat JAR, require `bootJar` for UI startup, launch a service JVM/JAR from the UI, or add browser-to-service CORS access. |
| NFR-02 | Transport safety | Keep service configuration server-side, validate HTTP(S) URLs without embedded credentials, disable upstream redirects and caching, and preserve the existing 180-second upstream timeout. |
| NFR-03 | Honest unknown outcomes | A timeout or connection loss does not prove allocation failed; warn that processing may continue and retain the original request identity for unchanged manual retries. |
| NFR-04 | Compatibility and build isolation | Preserve the service REST contract, shared Kotlin DTO library dependency, OpenAPI generation, allocation semantics, and unrelated MCP tools; removal of `allocate_product_by_name` is intentional. No schema migration or new business abstraction is required. |

### Plan

1. Add Axios and a server-side product allocation client; update the Next.js route to call the existing service endpoint with the configured base URL, unchanged payload, timeout, and explicit status/error handling.
2. Update frontend request naming, plain REST response validation, service error display, and UI wording while retaining form validation, rendering, and manual retry identity.
3. Remove the obsolete Spring MCP bridge runtime, bridge-only configuration/dependencies/tests, and `ProductAllocationTools.kt`; remove fat-JAR startup coupling, preserve shared DTO/OpenAPI build responsibilities and unrelated MCP tools, and verify service compilation.
4. Update environment/run guidance and focused client, route, parser, and browser tests to use a mock service's `/api/products/allocate` endpoint and plain REST JSON.
5. Run UI unit tests, type checking, production build, and the focused browser flow; validate/generate OpenAPI interfaces and compile/test the affected Kotlin service boundary, then smoke-test with only Next.js and `stockio-service:9090`.

### Resources

- [Scenario 12: Allocate a Product from the Frontend through Direct Service HTTP](./scenarios.md#scenario-12-allocate-a-product-from-the-frontend-through-direct-service-http)
- [Scenario 1: Allocate a Product by Name](./scenarios.md#scenario-1-allocate-a-product-by-name)
- [Feature issue: Replace the frontend MCP bridge with an Axios service client](./issues/feature-frontend-axios-service-client.md)
- [Frontend architecture boundary](./architecture.md#frontend-http-integration)

## Feature: Isolate browser tests and synthetic publishing in a test harness

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md)
- **Bounded Context:** Test Infrastructure & Allocation Messaging

### Overview

Create `stockio-test-harness/` with `e2e/`, `performance/`, and `publisher/`.
Move the existing mock-backed Playwright flow and synthetic RabbitMQ publisher
out of the UI/service. The user selected a harness-only JDBC catalogue
connection and performance scaffolding only. The harness shares DTOs with the
service but never depends on the executable service module.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Separate harness layout | Register `:stockio-test-harness` and create `e2e/`, `performance/`, and `publisher/`, with independent npm and Gradle commands. |
| FR-02 | Relocate browser coverage | Move the existing Playwright configuration, allocation spec, and dependency ownership into the harness; retain isolated mock traffic, UI startup, and all existing observable assertions. |
| FR-03 | Standalone JDBC publisher | Move the synthetic publisher and focused tests into `publisher/`; select catalogue SKUs using a harness-only read-only JDBC datasource and shared DTOs, without service classes or migrations. |
| FR-04 | Opt-in compatible traffic | Keep generated request/date/quantity/supply rules and JSON/routing compatible; require explicit publisher enablement and remove synthetic publisher startup/configuration from the service. |
| FR-05 | Performance scaffolding and guidance | Reserve `performance/` with guidance only, add harness launch/test instructions and Make targets, and synchronize affected repository documentation. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | One-way dependencies | The harness may depend on `stockio-api` contracts and the running UI/service infrastructure; neither service nor UI runtime may depend on the harness. |
| NFR-02 | Safe operational boundaries | Publisher scheduling is disabled by default; document test-only side effects, use read-only catalogue connections, and leave consumer topology and schema ownership in the service. |
| NFR-03 | Preserved contracts | Preserve allocation wire fields, dates, paired demand/supply eligibility, browser behavior, and independent UI/service startup; do not introduce a performance framework or unrelated producer starter. |

### Plan

1. Register the harness module and its publisher source sets, bootstrap a non-web publisher application, and retain shared DTO dependencies without importing service code.
2. Move the publisher to harness-owned JDBC catalogue lookup, JSON conversion, clock, and opt-in scheduling; remove service-owned publisher code/configuration and relocate focused tests.
3. Move Playwright configuration/specs and npm dependencies into the harness, wire UI startup and mock defaults, and create the performance guidance and Make targets.
4. Verify publisher/JDBC/serialization tests, service compilation and affected tests, UI checks and relocated browser coverage; synchronize architecture, scenarios, specifications, issues, README, and active context.

### Resources

- [Scenario 13: Run Tests and Synthetic Traffic from a Separate Harness](./scenarios.md#scenario-13-run-tests-and-synthetic-traffic-from-a-separate-harness)
- [Feature issue: isolate test harness](./issues/feature-isolate-test-harness.md)

## Feature: Allow concurrent allocation document registration

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md)
- **Bounded Context:** Allocation & Reservation Concurrency

### Overview

Replace globally exclusive document registration with shared projection
coordination and narrower identity/SKU/supply protection. Keep exclusive
projection, ledger, and expiry behavior and all existing allocation contracts.

This completed feature records the registration baseline. Its shared/exclusive
gate is superseded by the [per-SKU protocol](#feature-coordinate-stock-processing-through-a-per-sku-protocol);
document identity, sorted inbound SKU and run-row protections remain in force.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Shared registration gate | Allow independent document registrations to overlap under the existing shared projection lock while excluding exclusive projection, ledger, and expiry transactions. |
| FR-02 | Document identity protection | Serialize competing order headers/lines and shipment headers/lines, including absent identities, using sorted namespaced database lock rows and existing identity constraints. |
| FR-03 | Safe inbound amendments | Acquire the existing inbound SKU locks in sorted order and lock supply rows before validating/amending quantities, preserving reservations, receipt state, immutable membership, and SKU metadata. |
| FR-04 | Consistent transaction ordering | Acquire shared gate -> sorted document keys -> sorted inbound SKUs -> run row -> business rows; preserve worker/exclusive-operation ordering and atomic rollback without lock upgrades. |
| FR-05 | Retry and compatibility | Preserve initialized/completed-run skipping, order deadlines, request idempotency, allocation rules, wire contracts, and independent per-SKU commits; add concurrency regression coverage and synchronized documentation. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Database-wide coordination | Coordinate across service instances using transaction-scoped database row locks, not in-process synchronization; introduce only an additive lock-table migration. |
| NFR-02 | Bounded change | Do not implement rate limiting, admission control, background/scoped projection rebuilding, pool tuning, virtual threads, or a new persistence stack in this feature. |

### Plan

1. Add an additive document-lock migration and matching entity/repository port/JPA adapter, following the existing SKU lock pattern.
2. Switch registration to shared-gate, sorted document/SKU, run-row, and supply-row locking while preserving document validation and retry behavior.
3. Add deterministic PostgreSQL concurrency tests for independent overlap, exclusive exclusion, identity conflicts, supply amendments versus allocation, and reversed SKU input order.
4. Validate migration startup and affected allocation/reservation/ledger/projection tests, then synchronize architecture, scenarios, design notes, README, issue, and active context.

### Resources

- [Scenario 14: Register Independent Allocation Documents Concurrently](./scenarios.md#scenario-14-register-independent-allocation-documents-concurrently)
- [Feature issue: concurrent document registration](./issues/feature-concurrent-document-registration.md)
- [Backpressure design discussion](../../docs/system_design_backpressure_admission_control.md)

## Feature: Bound database work while preserving ledger and expiry headroom

- **Status:** Draft
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md#cross-cutting-concurrency-idempotency-and-rollout), [Allocation admission](#feature-bound-allocation-execution-with-a-fail-fast-bulkhead), [Per-SKU stock processing](#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Bounded Context:** Allocation, Reservations & Stock Ledger / Database Admission

### Overview

Propose an explicit per-instance transaction-work budget alongside the existing
allocation request semaphore. Bound covered database-intensive phases and
protect ledger and expiry admission with workload shares. Acquire before
transaction creation, retain ownership through commit/rollback, and reuse it
for joined projection. Preserve partial-run recovery and distinguish later
database-phase refusal from pre-write request rejection. Implementation has
not started; limits, saturation policies and operational objectives require
approval. This is not a hard physical connection reservation or a throughput
guarantee, and ungoverned borrowers/replicas must be accounted for explicitly.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Transaction coverage | Apply a shared per-instance database-work budget to allocation run/document writes, new-demand/backorder SKU workers, standalone projection, ledger work/metadata, whole-order expiry and execution-result writes; explicitly classify discovery and outbox database access. |
| FR-02 | Maintenance shares | Enforce an overall covered-work ceiling and explicit workload shares so allocation cannot consume ledger/expiry admission; define protection between ledger and expiry rather than treating one undifferentiated semaphore as reserved maintenance capacity. |
| FR-03 | Transaction lifetime | Acquire admission before the owning transaction begins and release exactly once after commit/rollback and actual work termination; one existing expiry batch transaction consumes one work unit regardless of its order/SKU count. |
| FR-04 | Safe ownership | Reuse admission for nested projection joining a covered ledger/expiry transaction; independent child transactions acquire independently, and coordinators must not hold database permits while awaiting children, retries, cleanup or external scheduling. |
| FR-05 | Recoverable saturation | Distinguish database-phase saturation from pre-write request rejection and business failure; preserve earlier commits and unchanged logical identities, with approved HTTP/MCP errors and recoverable RabbitMQ/JobRunr/maintenance deferral that avoids fallback storms and false success. |
| FR-06 | Preserve stock semantics | Preserve sorted document/SKU/run-or-lifecycle locking, per-SKU replay, atomic expiry, ledger fencing/checkpoints, synchronous forecast recovery, original business dates and hold deadlines; do not advance ledger freshness for deferred/incomplete work. |
| FR-07 | Budget visibility | Expose enabled/configured/active capacity, admission outcomes and work duration by bounded workload/phase labels; correlate with Hikari pending/acquisition/timeout data, database lock waits, ledger freshness and overdue-expiry lag. |
| FR-08 | Controlled rollout | Default enforcement to disabled, validate explicit enabled limits, inventory ungoverned borrowers and replica totals, and enable only after saturation/recovery policies and representative contention checks are approved. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Bounded admission | No unbounded permit-wait queue, extra executor backlog, or waiting/backoff for admission while retaining a transaction or pooled connection. |
| NFR-02 | Existing atomicity | Do not merge allocation phases into one transaction, split whole-order expiry, change lock order, or release a permit on cancellation before transaction completion. |
| NFR-03 | Honest guarantees | Bound covered work per instance, not all physical connections or cluster-wide concurrency; spare permits cannot bypass hot-SKU locks, unavailable scheduler threads, or ungoverned borrowers. |
| NFR-04 | Bounded observability | Use fixed workload, phase and outcome labels; exclude request/job/order IDs, SKUs, customer data, payloads and exception messages from metric tags. |
| NFR-05 | Explicit implementation | Prefer small explicit permit ownership around existing transaction boundaries and current Micrometer/scheduling infrastructure; do not add a limiter framework, schema or scheduling subsystem without a demonstrated approved need. |

### Plan

1. Baseline enabled allocation admission, enumerate transaction owners and bypass borrowers, and approve workload shares, saturation/retry policies, maintenance objectives and per-instance/replica sizing.
2. Introduce opt-in validated database-work configuration, explicit workload admission/ownership and bounded-label metrics; prove class/total invariants and failure-safe acquisition/release.
3. Wrap transaction entry points outside Spring transaction creation, including run/result writes and metadata; reuse ownership for joined projection and preserve expiry batches and nontransactional orchestration.
4. Integrate distinct database-capacity errors and recoverable adapter/maintenance deferral, including partial allocation, failed projection/completion, ledger ownership and cleanup transitions.
5. Add focused unit, PostgreSQL and adapter tests for ceilings, maintenance shares, proxy/lifetime boundaries, nested reuse, cancellation, contention and idempotent partial recovery; measure representative independent/hot SKUs.
6. Document measured sizing, uncovered borrowers, replica budgets, retry/date caveats and rollout; synchronize scenario, specification, issue, architecture, service/API guidance and active context before opt-in enablement.

### Resources

- [Scenario 17: Preserve Maintenance Admission Under Database-Work Pressure](./scenarios.md#scenario-17-preserve-maintenance-admission-under-database-work-pressure)
- [Feature issue: shared database-work budget](./issues/feature-shared-database-work-budget.md)
- [Backpressure design discussion](../../docs/system_design_backpressure_admission_control.md#second-increment-protect-the-shared-database-budget)

## Feature: Bound allocation execution with a fail-fast bulkhead

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md#cross-cutting-concurrency-idempotency-and-rollout), [Per-SKU stock processing](#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Bounded Context:** Allocation Application Orchestration & Ingress Adapters

### Overview

Bound admitted allocation orchestrations per service instance before allocation
business writes, retaining capacity through synchronous projection and applicable
execution-result persistence. Reject saturated inline HTTP with 503, bound
incoming request shape, and expose tuning metrics. Include explicit background
overload handling so RabbitMQ/JobRunr cannot bypass admission or amplify retries.
Implementation is approved with opt-in enforcement and explicit operator-supplied
limits, distinct 503/400/413 errors, recoverable background deferral, unchanged
dates/holds and pre-enable reconciliation of existing oversized work.

### Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Shared fail-fast admission | Use one configurable per-instance allocation execution limit across participating entry points; attempt admission without a waiting queue and before allocation run/document/reservation writes. |
| FR-02 | Complete permit lifetime | Retain one permit through allocation, synchronous affected-SKU projection and execution-result persistence where applicable; release exactly once only after the actual work stops, on success or failure. |
| FR-03 | Explicit overload responses | Return HTTP 503 with a stable capacity-error code for rejected inline execution; provide distinct retryable MCP errors and safe durable-worker deferral without silently converting rejected HTTP work to asynchronous work. |
| FR-04 | Bounded request shape | Enforce approved order/shipment counts, total demand/inbound line counts and distinct-SKU limits before allocation writes or task fan-out, including before HTTP asynchronous enqueue; define byte-size enforcement separately for each ingress. |
| FR-05 | Safe background overload | Distinguish capacity rejection from invalid input and business failure; prevent RabbitMQ fallback enqueue storms, tight requeue loops, accidental dead-lettering and false JobRunr success on deferred work. |
| FR-06 | Preserve recovery contracts | Preserve request identity, payload checks, per-SKU locks, partial-run recovery, synchronous projection and physical-ledger separation; do not extend reservation deadlines or silently retime delayed work. |
| FR-07 | Admission visibility | Expose configured capacity, active permits, admitted/rejected attempts, shape-limit rejections, execution outcomes and duration, plus allocation/projection/completion phase durations, through existing Micrometer infrastructure. |
| FR-08 | Controlled configuration and cutover | Validate enabled limits at startup, document per-instance scope and all participating callers, and enable enforcement only after adapter overload behavior and queued-work compatibility are approved and verified. |

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | No hidden waiting | Admission must not sleep or wait for a permit, hold a database connection while waiting, or create another unbounded application queue. |
| NFR-02 | Existing consistency | Do not change transaction/lock ordering, FIFO allocation, forecast freshness, reservation quantities, or ledger/expiry semantics; a projection failure may still follow committed allocation. |
| NFR-03 | Honest resource boundary | Bound allocation orchestrations per instance, not all HTTP work, database transactions, durable backlog or persisted-state projection cost; size limits from measurements rather than Hikari defaults. |
| NFR-04 | Bounded observability | Use fixed low-cardinality metric labels; never use request/job/order IDs, SKUs, customer data, payloads or exception messages as metric tags. |
| NFR-05 | Limited implementation | Reuse existing processors, validation, adapters, Micrometer and scheduling infrastructure; avoid a new limiter framework, database schema or scheduler unless the approved retry policy requires one. |

### Plan

1. Approve entry-point coverage, enablement, numerical execution/request limits, byte-limit ownership, retry/defer policies and treatment of already queued work; capture the baseline.
2. Introduce validated admission/request-limit configuration, bounded shape validation, a typed capacity exception and a non-waiting application admission guard with Micrometer measurements.
3. Wrap shared allocation orchestration through projection and optional completion persistence; route the alternate backorder processor through the same boundary without nested permits or an outer transaction.
4. Integrate HTTP/MCP errors and pre-enqueue shape checks; implement and verify RabbitMQ/JobRunr overload handling before enabling the common guard, including receipt-triggered backorders.
5. Add focused permit-lifetime, rejection, payload, adapter and concurrency/recovery checks; measure independent/hot SKUs, slow projection and background contention on dedicated test infrastructure.
6. Document configured limits, error/retry contracts, metrics, queue-date caveats and rollout; synchronize architecture, scenarios, specification, issue, API documentation, service guidance and active context with the implemented behavior.

### Resources

- [Scenario 16: Reject Allocation Work When Execution Capacity Is Exhausted](./scenarios.md#scenario-16-reject-allocation-work-when-execution-capacity-is-exhausted)
- [Feature issue: fail-fast allocation admission](./issues/feature-fail-fast-allocation-admission.md)
- [Backpressure design discussion](../../docs/system_design_backpressure_admission_control.md)

## Feature: Coordinate stock processing through a per-SKU protocol

- **Status:** Done
- **Priority:** Not set
- **Parent Specs:** [Architecture](./architecture.md), [Concurrent document registration](#feature-allow-concurrent-allocation-document-registration)
- **Bounded Context:** Allocation, Reservations & Stock Ledger

### Overview

Replace the global shared/exclusive projection gate only after allocation,
registration, scoped projection, ledger, and expiry follow an equivalent
database-backed SKU protocol. Permit unrelated SKUs to progress concurrently,
preserve atomic whole-order expiry, and expose recoverable partial ledger-run
progress. Allocation remains separate from physical ledger synchronization.
The implementation preserves allocation/forecast and expiry failure boundaries,
uses SKU-local horizons, persists partial ledger progress, reconciles stale-date
runs through today's manifest, and requires coordinated all-writer deployment.

### Functional Requirements (FR)

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

### Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Consistency boundaries | Guarantee per-SKU mutation consistency and atomic whole-order expiry, not a single allocation/project/ledger lifecycle transaction or an atomic whole-catalogue ledger run. |
| NFR-02 | Bounded execution | Bound coordinator fan-out against the combined database budget; keep transactions free of remote calls and broker/job scheduling side effects that belong in durable follow-up. |
| NFR-03 | Explicit compatibility | Preserve FIFO, eligibility, quantity, ownership, receipt, and reservation rules; approve changes to failure semantics, projection horizon, result/status contracts, and freshness before cutover. |
| NFR-04 | Limited scope | Do not add rate limiting, HTTP admission control, virtual threads, R2DBC, SKU/warehouse partitioning, confirmation endpoints, or asynchronous forecast/version publication in the first version. |

### Plan

1. Resolve transaction/failure compatibility, ledger-run API/status, date/cutoff, work discovery, and projection-horizon decisions; baseline the current implementation.
2. Introduce SKU-scoped reads and snapshot replacement plus per-SKU projection tests while retaining global coordination.
3. Implement the common lock hierarchy and whole-order expiry protocol, including cleanup/retry coordination for ledger workers encountering overdue holds.
4. Add per-SKU ledger transactions, durable run/progress metadata, and receipt follow-up; keep existing global protection during migration.
5. Validate every participating writer, replay/crash behavior, mixed-version deployment constraints, and realistic contention; remove the gate only after the coordinated cutover is approved.
6. Measure performance and remaining bottlenecks, then synchronize interfaces, architecture, scenarios, specifications, issue, run guidance, and active context.

### Resources

- [Scenario 15: Process Stock Operations Concurrently by SKU](./scenarios.md#scenario-15-process-stock-operations-concurrently-by-sku)
- [Feature issue: per-SKU stock processing](./issues/feature-per-sku-stock-processing.md)
- [Completed registration baseline](./issues/feature-concurrent-document-registration.md)
- [Backpressure design discussion](../../docs/system_design_backpressure_admission_control.md)
