# Active Context

## Current change: PostgreSQL R2DBC for SKU allocation

- `AllocationJobExecutor.execute`, `AllocationProcessor.process`, `AllocationService.allocation`, `AllocationService.allocateOpenBackorders`, and `AllocationBackorderService.allocate` are `suspend` functions. Prepare, projection, and completion hop to Reactor's bounded elastic dispatcher. SKU work stays a bounded `Flux` and is awaited with `awaitSingle`. `executeSync` and `processSync` are `runBlocking` for Kafka, RabbitMQ, JobRunr, and MCP. The R2DBC port still returns `Mono`.
- SKU allocation transactions use a second PostgreSQL pool (`spring.r2dbc`, `R2DBC_POOL_*`). Flyway, Hikari, and the JPA repositories stay.
- `SkuAllocationProcessor` runs inside `TransactionalOperator`. Lock-contention `SET LOCAL` uses that connection before the SKU lock.
- New demand and backorder fulfillment fan out with `Flux.flatMap` at `ALLOCATION_MAX_CONCURRENT_SKUS`. The suspending allocation call awaits that publisher. Kafka, RabbitMQ, JobRunr, and MCP block once in `executeSync` or `processSync`.
- JDBC and R2DBC are not enlisted in the same business transaction.
- Spring Boot skips JDBC `DataSource` auto-configuration when an R2DBC `ConnectionFactory` exists, so `JdbcDataSourceConfiguration` builds the Hikari pool from `spring.datasource`. The JPA transaction manager is marked primary so `@Transactional` does not see two `TransactionManager` beans. Spring Data R2DBC repositories stay off.
- `AllocationIntegrationTest` passes, including independent JDBC and R2DBC commit and rollback.
- Spring Boot skips JDBC `DataSource` auto-configuration when an R2DBC `ConnectionFactory` exists, so `JdbcDataSourceConfiguration` builds the Hikari pool from `spring.datasource`. The JPA transaction manager is marked primary so `@Transactional` does not see two `TransactionManager` beans. Spring Data R2DBC repositories stay off.
- `AllocationIntegrationTest` passes, including independent JDBC and R2DBC commit and rollback.

## Feature: Redis Fast Path provisional allocation accept

- **Specification:** [Feature specification](../../docs/specifications.md#feature-redis-fast-path-provisional-allocation-accept)
- **Scenario:** [Scenario 22](../../docs/scenarios.md#scenario-22-accept-allocation-jobs-on-the-redis-fast-path)
- **Issue:** [Feature issue](./issues/feature-redis-fast-path.md)
- **Status:** In-progress
- **Priority:** Not set
- **Implementation started:** Yes. Fast-path accept, pre-warm, hold cleanup, and CDC consumer are in progress.

### Steps worked on
- Turned the Redis fast-path investigation into scenario 22, a specification, and an issue.
- Fast path stays limited to async `POST /api/allocation/jobs`, with PostgreSQL as the durable ledger.

### Next possible steps (suggestions)
- Run the service against the compose Redis and Debezium connector when CDC is enabled.
- Decide whether a later change should add warehouse to `DemandOrderLine`.

## Feature: Mitigate synchronous allocation lock contention with fail-fast admission

- **Specification:** [Feature specification](../../docs/specifications.md#feature-mitigate-synchronous-allocation-lock-contention-with-fail-fast-admission)
- **Scenario:** [Scenario 21](../../docs/scenarios.md#scenario-21-fail-fast-under-allocation-lock-contention)
- **Issue:** [Feature issue](./issues/feature-fail-fast-lock-contention-mitigation.md)
- **Status:** In-progress
- **Priority:** Not set
- **Implementation started:** Yes. Initial contention fail-fast implementation is in progress.

### Steps worked on
- Reviewed the contention symptom from the latest allocation harness report: high latency under higher concurrency with low failure, indicating queueing/waiting behavior.
- Connected the requested approach to existing repository design: fail-fast admission as the first control, then lock-holding duration reduction while preserving sorted lock protocol and idempotency semantics.
- Added and linked a new scenario/specification/issue set for this increment, including explicit acceptance criteria for latency, rejection behavior, lock ordering, and observability.
- Started implementation with transaction-local lock/statement timeout controls on lock-heavy allocation/projection transactions and retryable lock-contention error mapping.
- Extended HTTP and background handling to treat lock contention as explicit recoverable overload (`503` for inline, deferred retry/backoff for RabbitMQ/Kafka/JobRunr paths).

### Next possible steps (suggestions)
- Validate contention behavior under representative load and tune `ALLOCATION_ADMISSION_*` plus lock-timeout budgets from measured lock/pool pressure.
- Continue narrowing lock-residency sections where correctness allows (especially around non-lock-critical synchronous completion/projection work).
- Finalize docs and rollout guidance after before/after latency/rejection evidence is captured.

## Feature: Add a direct structured allocation UI alongside the agentic page

- **Specification:** [Feature specification](../../docs/specifications.md#feature-add-a-direct-structured-allocation-ui-alongside-the-agentic-page)
- **Scenario:** [Scenario 20](../../docs/scenarios.md#scenario-20-submit-structured-allocation-requests-from-a-direct-ui-workflow)
- **Issue:** [Feature issue](./issues/feature-direct-structured-allocation-ui.md)
- **Status:** In-progress
- **Priority:** Not set
- **Implementation started:** Yes. Direct-page implementation is in progress.

### Steps worked on
- Confirmed direct-page contract source of truth as `POST /api/allocation/jobs` with `AllocationRequest`.
- Added a dedicated direct page route with SKU-first form fields, request identity behavior, JSON preview, and submit/result/error rendering.
- Added a new server-side Axios proxy route/client for allocation jobs with admission-size guard and transport error mapping aligned with existing patterns.
- Added focused tests for direct request mapping/parsing and allocation-jobs proxy forwarding/error behavior.

### Next possible steps (suggestions)
- Run the UI typecheck/tests and resolve any integration regressions.
- Validate the new direct page against a running `stockio-service` instance and finalize docs/status when complete.

## Feature: Add an allocation performance harness for job endpoints

- **Specification:** [Feature specification](../../docs/specifications.md#feature-add-an-allocation-performance-harness-for-job-endpoints)
- **Scenario:** [Scenario 19](../../docs/scenarios.md#scenario-19-exercise-allocation-job-rest-endpoints-under-controlled-load)
- **Issue:** [Feature issue](./issues/feature-allocation-performance-harness.md)
- **Status:** In-progress
- **Priority:** Not set
- **Implementation started:** Yes. Initial Gatling harness scaffolding is being implemented.

### Steps worked on
- Reviewed `stockio-test-harness/performance/README.md` and challenged it against current repository constraints, existing harness boundaries, and the "honest performance claims" rule already used elsewhere.
- Captured key challenge points: production-readiness overclaim risk, unverified mandatory auth assumption, generated-client tradeoff, and static SLA thresholds without environment binding.
- Added linked scenario/specification/issue entries to convert the prompt into a repository-owned implementation handoff with explicit scope, requirements, and plan.
- Started implementation by wiring a harness-owned Gatling run task, adding a Kotlin simulation with the async Gatling DSL, and replacing the prompt README with executable run/configuration guidance.

### Next possible steps (suggestions)
- Decide whether to use direct Gatling DSL only or retain optional OpenAPI-generated client usage with observability safeguards.
- Approve environment classes and default assertion thresholds for local versus representative infrastructure runs before implementation.

## Feature: Add a Kafka allocation consumer alongside RabbitMQ

- **Specification:** [Feature specification](../../docs/specifications.md#feature-add-a-kafka-allocation-consumer-alongside-rabbitmq)
- **Scenario:** [Scenario 18](../../docs/scenarios.md#scenario-18-consume-an-allocation-request-from-kafka)
- **Issue:** [Feature issue](./issues/feature-concurrent-per-event-allocation.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Kafka consumption and asynchronous REST publishing with durable job status tracking are complete.

### Steps worked on
- Followed the AGENTS.md feature workflow by checking the repository guidance, current memory, allocation listener/executor, dependencies, and worktree before editing.
- Reframed the initial generic Fastned billing/Adyen/coroutines proposal as Stockio stock allocation through a Kafka adapter alongside the RabbitMQ consumer.
- During analysis, documented shared `executeSync` delegation, stable identity, a bounded Kotlin `Channel` with fixed workers as a local burst buffer, the existing semaphore bulkhead as authoritative, offset safety, cross-broker duplicates, and the initial broker/retry decisions before implementation.
- Approved the existing `AllocationRequest` JSON/request identity, RabbitMQ-equivalent capacity and JobRunr fallback behavior, dead-lettering for invalid records, and the user-provided Kafka Compose baseline.
- Selected synchronous listener completion after verifying async-future listeners can process later records in a partition before earlier futures finish; bounded channel workers retain local buffering while consumer concurrency overlaps partitions.
- Added Spring Kafka dependencies, disabled-by-default listener/container and DLT/topic configuration, bounded channel workers with capacity backoff and JobRunr fallback, local Kafka Compose services, and focused listener/queue tests.
- Compiled the service, validated Compose configuration, and passed focused Kafka/RabbitMQ listener and queue tests plus embedded-broker offset-commit, invalid-record DLT-routing, and retry-exhaustion tests.
- Added a PostgreSQL-backed concurrent same-request execution test that confirms one allocation and one persisted JobRunr completion; direct cross-broker single-flight behavior is not guaranteed.
- Updated the feature scope to publish raw `AllocationRequest` JSON from asynchronous REST submissions, keyed by the accepted execution ID; the existing consumer remains opt-in and continues to support external request-ID keyed messages.
- Added PostgreSQL job tracking so accepted Kafka jobs remain pollable without a JobRunr record; acknowledgement failures retain an explicit `SUBMISSION_UNKNOWN` state.
- Verified focused executor, Kafka listener/offset/DLT, job-status, and PostgreSQL tracking tests; validated and regenerated the allocation OpenAPI interface.

### Next possible steps (suggestions)
- Provision the Kafka source topic and configure the broker before using default asynchronous REST submission; enable the listener explicitly when this service should consume its own topic.
- Tune worker and listener concurrency from measured production capacity; preserve the current RabbitMQ path and keep Kafka consumption opt-in.
- Exercise broker outage, shutdown, and consumer-rebalance behavior on dedicated infrastructure before production rollout.

## Feature: Bound database work while preserving ledger and expiry headroom

- **Specification:** [Feature specification](../../docs/specifications.md#feature-bound-database-work-while-preserving-ledger-and-expiry-headroom)
- **Scenario:** [Scenario 17](../../docs/scenarios.md#scenario-17-preserve-maintenance-admission-under-database-work-pressure)
- **Issue:** [Feature issue](./issues/feature-shared-database-work-budget.md)
- **Status:** Draft
- **Priority:** Not set
- **Implementation started:** No. Analysis and proposed design only.

### Steps worked on
- Inspected current request admission, transaction/proxy boundaries, separate SKU dispatchers, projection, ledger metadata/cleanup handoff, expiry batches and result/outbox persistence.
- Drafted linked requirements, scenario, component analysis and validation plan for a separate database-work budget with protected ledger/expiry shares.
- Documented pre-transaction acquisition, joined-projection reuse, partial-commit saturation recovery, bypass borrowers and per-instance/replica limitations; runtime behavior remains unchanged.

### Next possible steps (suggestions)
- Baseline/tune the existing request bulkhead and confirm pool pressure or maintenance objectives justify the new layer.
- Approve workload classification/shares, measured limits, rejection versus bounded waiting, adapter/maintenance retry policies and borrower/replica headroom before implementation.
- Keep enforcement opt-in and avoid claiming physical connection reservations or bounded maintenance latency from local permits alone.

## Feature: Bound allocation execution with a fail-fast bulkhead

- **Specification:** [Feature specification](../../docs/specifications.md#feature-bound-allocation-execution-with-a-fail-fast-bulkhead)
- **Scenario:** [Scenario 16](../../docs/scenarios.md#scenario-16-reject-allocation-work-when-execution-capacity-is-exhausted)
- **Issue:** [Feature issue](./issues/feature-fail-fast-allocation-admission.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete, at the user's request.

### Steps worked on
- Inspected shared and alternate allocation processors, result persistence, product resolution, HTTP/MCP/RabbitMQ/JobRunr callers, existing validation, Axios forwarding and Micrometer infrastructure.
- Approved opt-in enforcement with explicit positive concurrency/count/byte limits, distinct 503/400/413 errors, recoverable background deferral, unchanged request dates/holds and pre-enable queue reconciliation. Priority remains unset.
- Implemented one non-waiting semaphore guard through scoped projection and optional completion persistence; the alternate backorder processor delegates without nested permits or an outer transaction.
- Added raw HTTP/RabbitMQ byte bounds, canonical DTO and aggregate shape checks, pre-enqueue validation, MCP coverage, bounded-label Micrometer measurements and separately configured streamed UI byte enforcement.
- Added enabled RabbitMQ one-consumer/prefetch-1/AUTO-ack backoff retaining the original message and JobRunr same-job scheduled capacity retries that do not consume the three business retries.
- Verified the full service suite and final affected semaphore/executor/MCP/consumer/PostgreSQL checks, OpenAPI validation/generation, 22 UI tests and UI typechecking. No production capacity or live broker-outage/delivery-timeout benchmark is claimed.
- Synchronized the implemented scenario/specification/issue, architecture, service configuration guidance and backpressure design. Existing staged changes remain preserved.

### Next possible steps (suggestions)
- Select measured limits, drain/reconcile incompatible queued work, deploy updated adapters and enable explicitly; configure the same flag/byte limit in the separate UI.
- Exercise broker delivery-timeout/outage behavior on dedicated infrastructure and monitor admission rejection, queue age, history growth, database waits and maintenance freshness.
- Database-wide budgets, bounded durable intake, traffic fairness and end-to-end product-resolution admission remain separate features.

## Feature: Coordinate stock processing through a per-SKU protocol

- **Specification:** [Feature specification](../../docs/specifications.md#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Scenario:** [Scenario 15](../../docs/scenarios.md#scenario-15-process-stock-operations-concurrently-by-sku)
- **Issue:** [Feature issue](./issues/feature-per-sku-stock-processing.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete, at the user's request.

### Steps worked on
- Approved compatible allocation/forecast and expiry failure boundaries, SKU-local horizons, current-day manifests, partial progress and coordinated deployment.
- Implemented scoped query/replacement paths and removed production acquisition of the global projection gate; all writers follow sorted document/SKU/run-or-lifecycle ordering.
- Added V18 run/work/coordinator/outbox state and scoped-read indexes. Sequential ledger work commits fresh projections, physical changes, checkpoints and receipt follow-up atomically per SKU.
- Added fenced renewable ownership, incomplete-work recovery, stale-date reconciliation, and additive progress through the existing ledger-status endpoint. Coordinators never hold metadata while waiting for SKUs.
- Whole-order expiry locks all immutable SKUs before lifecycle revalidation and atomically releases holds plus refreshes affected forecasts; ledger retries cleanup outside its worker transaction.
- Receipt scheduling is durable and retryable outside business transactions. Allocation remains separate from physical ledger synchronization.
- Verified the full service suite: 135 passing tests, including PostgreSQL concurrency, partial/crash/date recovery, owner fencing, legacy migrations and schema validation; OpenAPI and documentation checks pass. Updated architecture, README and linked scenarios/specifications/issues.

### Next possible steps (suggestions)
- Deploy only with a coordinated stop/migrate/deploy/restart of all service instances, consumers and JobRunr writers; mixed versions are unsafe.
- Run representative throughput/tail-latency and lock/pool profiling before tuning budgets or selecting separate admission control. No capacity benchmark or 1,000-request-per-second guarantee is claimed.

## Feature: Allow concurrent allocation document registration

- **Specification:** [Feature specification](../../docs/specifications.md#feature-allow-concurrent-allocation-document-registration)
- **Scenario:** [Scenario 14](../../docs/scenarios.md#scenario-14-register-independent-allocation-documents-concurrently)
- **Issue:** [Feature issue](./issues/feature-concurrent-document-registration.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete, at the user's request.

### Steps worked on
- Traced registration, inbound amendments, SKU workers, run locks, schema constraints, and exclusive projection/ledger/expiry paths.
- Selected a shared projection gate plus sorted namespaced identity-lock rows, existing sorted inbound SKU locks, run locking, and locked supply reads.
- Identified that the run lock must follow inbound SKU acquisition to avoid blocking worker foreign-key checks while waiting for that worker.
- Linked scope, scenario, requirements, issue, and deterministic PostgreSQL validation before code changes.
- Added V17's namespaced document-lock table and the matching entity/port/JPA repository, following existing SKU-lock patterns.
- Switched registration to the shared gate, sorted document/inbound SKU locks, then run protection; existing supply reads now use row locks before amendment checks.
- Added eight real PostgreSQL concurrency cases covering independent overlap, exclusive exclusion, all four identity conflict types, supply amendments, same-run retries, and reversed SKU input.
- Verified 90 affected tests, including all 51 allocation integration cases, Flyway/schema validation, and reservation/ledger/projection behavior. Updated architecture, README, scenarios, linked issue/specification, and system-design notes.
- Preserved the existing controller edit. Admission control, full projection rebuilding, worker/pool sizes, and API contracts remain outside this change.

### Next possible steps (suggestions)
- No remaining implementation steps for registration. Measure remaining projection/lock contention before selecting separate admission-control or projection changes.

## Feature: Isolate browser tests and synthetic publishing in a test harness

- **Specification:** [Feature specification](../../docs/specifications.md#feature-isolate-browser-tests-and-synthetic-publishing-in-a-test-harness)
- **Scenario:** [Scenario 13](../../docs/scenarios.md#scenario-13-run-tests-and-synthetic-traffic-from-a-separate-harness)
- **Issue:** [Feature issue](./issues/feature-isolate-test-harness.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete: independent publisher, relocated browser suite, and performance scaffolding.

### Steps worked on
- Confirmed `stockio-test-harness/{e2e,performance,publisher}` as the requested layout.
- Confirmed a harness-only JDBC catalogue connection and performance scaffolding only.
- Traced publisher, service topology/configuration, shared DTO ownership, Playwright setup, and the existing publisher/browser tests.
- Linked the specification, scenario, issue, affected boundaries, and validation plan before implementation.
- Registered the Gradle harness with publisher-specific source sets and shared DTO dependencies only; service and UI runtimes do not depend on it.
- Moved the fake publisher to a non-web application with opt-in scheduling, its own read-only JDBC datasource and business clock, and compatible RabbitMQ JSON. Removed its service code/configuration and obsolete integration-test override.
- Moved Playwright spec/configuration and npm ownership to the harness. UI build/start and mock defaults work for npm and direct IntelliJ launches; added Node 22 configuration and Make targets.
- Added performance guidance only, updated architecture/README, and rebased the still-draft producer-starter handoff on the new harness boundary.
- Verified five publisher/JDBC tests, four affected service tests, 19 UI unit cases, UI/harness typechecking, the relocated browser flow, default-disabled non-web startup, and one-way runtime dependency graphs.

### Next possible steps (suggestions)
- No remaining implementation steps. Performance tooling and workloads remain explicitly deferred.

## Feature: Replace the frontend MCP bridge with an Axios service client

- **Specification:** [Feature specification](../../docs/specifications.md#feature-replace-the-frontend-mcp-bridge-with-an-axios-service-client)
- **Scenario:** [Scenario 12](../../docs/scenarios.md#scenario-12-allocate-a-product-from-the-frontend-through-direct-service-http), building on [Scenario 1](../../docs/scenarios.md#scenario-1-allocate-a-product-by-name).
- **Issue:** [Feature issue](./issues/feature-frontend-axios-service-client.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete: Axios forwarding, plain REST results, removal of the bridge/product MCP tool, and shared DTO/OpenAPI compilation.

### Steps worked on
- Traced browser fetch -> Next.js route -> Spring MCP bridge on `8082` -> stdio child JVM -> `allocate_product_by_name` -> `ProductAllocationService`.
- Confirmed `POST /api/products/allocate` already calls the same service directly and returns plain `ProductAllocationResponse`.
- Confirmed the user's choice: keep the same-origin Next.js route and place Axios there, calling the independently running service on `9090`; do not introduce browser CORS access.
- Identified MCP-specific frontend parsing/fixtures and the shared Kotlin DTO/OpenAPI build responsibilities that must survive bridge removal.
- Added the linked draft specification, planned scenario, issue analysis, and explicitly planned architecture boundary.
- Clarified the requested removal of `ProductAllocationTools.kt` and `allocate_product_by_name`; unrelated MCP tools remain outside removal scope.
- Made separate UI/service startup explicit: no bundled UI/service fat JAR, `bootJar` prerequisite for the UI, or UI-managed service JVM.
- Implemented server-side Axios forwarding to `/api/products/allocate`, plain REST validation/rendering, explicit HTTP/network errors, and unchanged manual retry identity.
- Removed the bridge runtime/configuration/tests and product MCP tool/registration. Kept shared DTOs as a normal Kotlin library and retained unrelated MCP tools.
- Restricted the Boot application plugin to the service and aligned BOM-managed Kotlin dependencies with the Kotlin plugin to preserve library compilation.
- Updated README/environment guidance, Makefile launch targets, scenarios, architecture, and the original product-resolution issue's interface references.
- Verified UI unit/type/build/browser checks, OpenAPI validation/generation, focused controller/service/tool-registration tests, and live browser allocation against separately started Next.js/service processes with an isolated database. Stopped the temporary validation services.
- Moved the browser test's default mock-service URL into `playwright.config.ts` so IntelliJ/direct Playwright runs do not depend on npm-script environment setup; the tests do not need the real service.

### Next possible steps (suggestions)
- No remaining implementation steps for this feature.

## Feature: Automate backorder fulfillment after physical inbound receipt

- **Specification:** `docs/specifications.md#feature-automate-backorder-fulfillment-after-physical-inbound-receipt`
- **Scenario:** `docs/scenarios.md#scenario-4-fulfill-open-backorders`
- **Issue:** `.github/memory/issues/feature_automate-backorder-fulfillment-after-inbound-receipt.md`

### Steps worked on
- Captured the approved design direction: expected inbound registration does not trigger fulfillment; physical receipt during stock-ledger synchronization does.
- Defined a dedicated `BackorderFulfillmentCommand` and reusable application service instead of reusing `AllocationRequest` or the `NEW_DEMAND` flow.
- Documented post-commit JobRunr scheduling, affected-SKU scoping, priority/FIFO behavior, expiry filtering, projection refresh, idempotency, and concurrent-execution protection.
- Added the feature specification and synchronized Scenario 4 with the feature issue document.
- Moved the feature to In-progress and implemented the dedicated backorder fulfillment command, reusable service, JobRunr job, and manual MCP adapter path.
- Extended stock-ledger synchronization to enqueue scoped fulfillment after a successful transaction commit, with stable event/job identities and existing SKU-level locking/idempotency.
- Added shared projection refresh for manual and automatic fulfillment while preserving separation from the `NEW_DEMAND` allocation path.

### Next possible steps (suggestions)
- Add focused integration coverage for post-commit enqueueing, rollback suppression, scoped receipt fulfillment, and duplicate receipt events.
- Review operational logging and JobRunr dashboard behavior for enqueue failures and retry outcomes.

Tell us what is the current active context for the agent. This should include:
- Reference to the feature definition in `.github/memory/requirements-specification.md` in the `## Feature` section.
- Steps worked on, including brief summary of the AGENTS.md `Workflow orchestration` routine in the `### Steps worked on` section.
- If you have any suggestions for next steps, add them in the `### Next possible steps (suggestions)` section.

## Planned feature: Add a reusable RabbitMQ allocation producer starter

- **Specification:** `docs/specifications.md#feature-add-a-reusable-rabbitmq-allocation-producer-starter`
- **Scenario:** `docs/scenarios.md#scenario-11-publish-an-allocation-request-from-another-spring-boot-application`
- **Issue:** `.github/memory/issues/feature_rabbitmq_allocation_producer_starter.md`
- **Implementation started:** No. This is a draft implementation handoff; application code has not been changed.

### Steps worked on
- Confirmed the feature targets RabbitMQ producer reuse, not Kafka.
- Defined separate allocation-contract and producer-starter library modules, with Spring Boot auto-configuration registered through `AutoConfiguration.imports`.
- Updated the boundary to keep JDBC demo request generation and opt-in scheduling in `stockio-test-harness/publisher`, and keep listeners, queue/dead-letter topology, and JobRunr fallback consumer-side.
- Added Scenario 11, linked the feature specification and issue document, and documented the implementation plan and compatibility constraints.

### Next possible steps (suggestions)
- Confirm the artifact repository, artifact coordinates/versioning policy, and whether publisher confirms are in scope before implementation.
- Implement the contract and producer-starter modules, then adapt the existing sample publisher and verify the current consumer contract.

## Feature: Generate OpenAPI interfaces for REST controllers

- **Specification:** `docs/specifications.md#feature-generate-openapi-interfaces-for-rest-controllers`
- **Issue:** `.github/memory/issues/feature_add_interfaces_to_the_controllers.md`
- **Affected scenarios:** [1](../../docs/scenarios.md#scenario-1-allocate-a-product-by-name), plus initial coverage for [2](../../docs/scenarios.md#scenario-2-allocate-structured-new-demand), [3](../../docs/scenarios.md#scenario-3-monitor-an-allocation-job), [5](../../docs/scenarios.md#scenario-5-inspect-an-order-reservation), and [7](../../docs/scenarios.md#scenario-7-monitor-stock-ledger-freshness).
- **Status:** Done for the initial `AllocationController`, `ReservationController`, and user-selected `ProductAllocationController` integrations.
- **Implementation:** Each controller now implements its generated Kotlin Spring API from a controller-specific OpenAPI specification; `stockio-service` compiles generated sources directly from `stockio-api/build`.

### Steps worked on
- Completed the initial `AllocationController` interface generation and implementation using the service-local specification and Gradle integration.
- The user selected `ReservationController` for the next controller.
- Inspected `ReservationController`, its service, DTOs, and existing endpoint tests. Created `reservation-controller.yaml` from the implementation before generating the interface.
- Added dedicated `:stockio-api:openApiValidateReservation` and `:stockio-api:openApiGenerateReservation` tasks and registered their generated source with service compilation.
- Made `ReservationController` implement `ReservationApi` and added standalone MockMvc coverage for the successful response and `404`.
- Verified reservation spec validation, generation, service compilation, and focused tests.
- The user selected `stockio-service/src/main/kotlin/nl/stockio/product/domain/api/ProductAllocationController.kt` as the next controller. Its endpoint is already covered by Scenario 1 and has a focused controller test.
- Created `product-allocation-controller.yaml` from the existing endpoint DTOs and behavior, including the shared allocation-run schema reference.
- Added product-specific OpenAPI validation/generation tasks and the generated source path to service compilation; `ProductAllocationController` now implements `ProductAllocationApi`.
- Verified OpenAPI validation/generation, service compilation, and focused controller tests for the successful response and `400` error behavior.

### Next possible steps (suggestions)
- Apply the same specification-first workflow to another controller only after the user selects it.
