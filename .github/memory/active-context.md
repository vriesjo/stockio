# Active Context

## Feature: Replace the frontend MCP bridge with an Axios service client

- **Specification:** [Feature specification](./specifications.md#feature-replace-the-frontend-mcp-bridge-with-an-axios-service-client)
- **Scenario:** [Scenario 12](./scenarios.md#scenario-12-allocate-a-product-from-the-frontend-through-direct-service-http), building on [Scenario 1](./scenarios.md#scenario-1-allocate-a-product-by-name).
- **Issue:** [Feature issue](./issues/feature-frontend-axios-service-client.md)
- **Status:** Done
- **Priority:** Not set
- **Implementation started:** Yes. Complete: Axios forwarding, plain REST results, removal of the bridge/product MCP tool, and shared DTO/OpenAPI compilation.

### Steps worked on
- Traced browser fetch -> Next.js route -> Spring MCP bridge on `8082` -> stdio child JVM -> `allocate_product_by_name` -> `ProductAllocationService`.
- Confirmed `POST /api/products/allocate` already calls the same service directly and returns plain `ProductAllocationResponse`.
- Confirmed the user's choice: keep the same-origin Next.js route and place Axios there, calling the independently running service on `8080`; do not introduce browser CORS access.
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

- **Specification:** `.github/memory/specifications.md#feature-automate-backorder-fulfillment-after-physical-inbound-receipt`
- **Scenario:** `.github/memory/scenarios.md#scenario-4-fulfill-open-backorders`
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

- **Specification:** `.github/memory/specifications.md#feature-add-a-reusable-rabbitmq-allocation-producer-starter`
- **Scenario:** `.github/memory/scenarios.md#scenario-11-publish-an-allocation-request-from-another-spring-boot-application`
- **Issue:** `.github/memory/issues/feature_rabbitmq_allocation_producer_starter.md`
- **Implementation started:** No. This is a draft implementation handoff; application code has not been changed.

### Steps worked on
- Confirmed the feature targets RabbitMQ producer reuse, not Kafka.
- Defined separate allocation-contract and producer-starter library modules, with Spring Boot auto-configuration registered through `AutoConfiguration.imports`.
- Recorded the boundary that keeps demo request generation and scheduling in `stockio-service`, and keeps listeners, queue/dead-letter topology, and JobRunr fallback consumer-side.
- Added Scenario 11, linked the feature specification and issue document, and documented the implementation plan and compatibility constraints.

### Next possible steps (suggestions)
- Confirm the artifact repository, artifact coordinates/versioning policy, and whether publisher confirms are in scope before implementation.
- Implement the contract and producer-starter modules, then adapt the existing sample publisher and verify the current consumer contract.

## Feature: Generate OpenAPI interfaces for REST controllers

- **Specification:** `.github/memory/specifications.md#feature-generate-openapi-interfaces-for-rest-controllers`
- **Issue:** `.github/memory/issues/feature_add_interfaces_to_the_controllers.md`
- **Affected scenarios:** [1](scenarios.md#scenario-1-allocate-a-product-by-name), plus initial coverage for [2](scenarios.md#scenario-2-allocate-structured-new-demand), [3](scenarios.md#scenario-3-monitor-an-allocation-job), [5](scenarios.md#scenario-5-inspect-an-order-reservation), and [7](scenarios.md#scenario-7-monitor-stock-ledger-freshness).
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
