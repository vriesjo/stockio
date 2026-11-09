# Feature: Isolate browser tests and synthetic publishing in a test harness

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Test Infrastructure & Allocation Messaging
- **Implementation started:** Yes. Complete, at the user's request.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-isolate-browser-tests-and-synthetic-publishing-in-a-test-harness)
- **Parent Specs:** [Architecture](../../../docs/architecture.md)
- **Scenario:** [Scenario 13](../../../docs/scenarios.md#scenario-13-run-tests-and-synthetic-traffic-from-a-separate-harness)

## Context and scope

The user approved `stockio-test-harness/` with `e2e/`, `performance/`, and
`publisher/`, a harness-only JDBC connection for catalogue lookup, and
performance-directory scaffolding only. Move the existing Playwright test and
fake publisher rather than introducing a new testing framework, service
endpoint, reusable producer starter, or dependency on the service artifact.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Separate harness layout | Register `:stockio-test-harness` and create `e2e/`, `performance/`, and `publisher/`, with independent npm and Gradle commands. |
| FR-02 | Relocate browser coverage | Move the existing Playwright configuration, allocation spec, and dependency ownership into the harness; retain isolated mock traffic, UI startup, and all existing observable assertions. |
| FR-03 | Standalone JDBC publisher | Move the synthetic publisher and focused tests into `publisher/`; select catalogue SKUs using a harness-only read-only JDBC datasource and shared DTOs, without service classes or migrations. |
| FR-04 | Opt-in compatible traffic | Keep generated request/date/quantity/supply rules and JSON/routing compatible; require explicit publisher enablement and remove synthetic publisher startup/configuration from the service. |
| FR-05 | Performance scaffolding and guidance | Reserve `performance/` with guidance only, add harness launch/test instructions and Make targets, and synchronize affected repository documentation. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | One-way dependencies | The harness may depend on `stockio-api` contracts and the running UI/service infrastructure; neither service nor UI runtime may depend on the harness. |
| NFR-02 | Safe operational boundaries | Publisher scheduling is disabled by default; document test-only side effects, use read-only catalogue connections, and leave consumer topology and schema ownership in the service. |
| NFR-03 | Preserved contracts | Preserve allocation wire fields, dates, paired demand/supply eligibility, browser behavior, and independent UI/service startup; do not introduce a performance framework or unrelated producer starter. |

## Implementation plan

1. Register the harness module and its publisher source sets, bootstrap a non-web publisher application, and retain shared DTO dependencies without importing service code.
2. Move the publisher to harness-owned JDBC catalogue lookup, JSON conversion, clock, and opt-in scheduling; remove service-owned publisher code/configuration and relocate focused tests.
3. Move Playwright configuration/specs and npm dependencies into the harness, wire UI startup and mock defaults, and create the performance guidance and Make targets.
4. Verify publisher/JDBC/serialization tests, service compilation and affected tests, UI checks and relocated browser coverage; synchronize architecture, scenarios, specifications, issues, README, and active context.

## Functional scenario

### Gherkin description
**Given** `stockio-test-harness` contains `e2e/`, `performance/`, and `publisher/` \
**When** a developer runs its browser tests or explicitly enables its allocation publisher \
**Then** Playwright exercises the Next.js UI against an isolated mock backend \
**And** the standalone publisher reads catalogue SKUs through its own JDBC connection and publishes compatible allocation requests to RabbitMQ \
**And** starting `stockio-service` alone never starts a synthetic publisher \
**And** `performance/` is reserved for future tooling rather than claiming an implemented load test

### High-level technical analysis
- The harness is a separate Gradle module and npm package; publisher sources/resources/tests live under `publisher/`, and Playwright configuration/specs live under `e2e/`
- The publisher depends on shared `stockio-api` DTOs, Spring JDBC, and RabbitMQ, not on service repositories, entities, application services, or migrations
- A read-only harness datasource selects SKUs from the existing `products` table; the service remains responsible for creating and migrating that schema
- Preserve five paired random orders/shipments, unique request/document/line IDs, configured business dates, delivery eligibility, exchange/routing key, and JSON contract
- Publisher scheduling is opt-in in the harness; queue/dead-letter topology, listener behavior, allocation rules, and JobRunr remain in `stockio-service`
- Browser tests retain their localhost mock and request-identity/error/rendering assertions; the UI no longer owns Playwright dependencies or test files
- Performance tooling, workloads, and thresholds are deliberately deferred

### Acceptance Criteria
- The requested three directories are present and the harness has independent build/run instructions
- Existing browser coverage runs from the harness without a real service, using the existing UI and mock allocation endpoint
- Explicit harness publisher startup reads the configured catalogue and emits the same flat shared allocation contract
- Publisher tests cover valid paired supply/demand, empty/failing catalogue reads, JDBC lookup, and JSON/date compatibility without importing service code
- Disabled harness publishing and standalone service startup generate no synthetic allocation traffic
- Service consumption/topology and application tests remain independent of harness dependencies
- No performance runner or load-generation guarantee is introduced

## Pre-extraction baseline and affected boundaries

- `AllocationRequestPublisher` previously lived in the service, was enabled by
  service YAML by default, queried `ProductRepository.findAll()`, and created
  five order/shipment pairs per scheduled invocation.
- Replace only that catalogue dependency with `JdbcTemplate` selecting `sku`
  from `products`. Preserve all catalogue statuses as before; do not introduce
  a new product-selection policy during the move.
- The existing publisher test also invokes service allocation policies.
  Move its request/routing/eligibility assertions to the harness without
  importing those policies; production allocation-policy coverage stays in
  the service's own tests.
- Keep the service's `AllocationRabbitConfiguration`, listener, converter,
  queue/DLX bindings, JobRunr retry handling, and business clock unchanged.
  Remove only publisher-specific YAML and obsolete test disablement.
- The UI previously owned `e2e/allocation.spec.ts`, `playwright.config.ts`, and
  `@playwright/test`. Move all three responsibilities to the harness.
  The moved spec can still import the frontend's request type as a type-only
  dependency; the UI must not import harness code.
- The harness owns its Node manifest/lockfile, TypeScript checking, mock URL
  default, and UI build/start command. Browser tests never start a real
  service or publish broker traffic.

## Validation and risks

- Run harness publisher tests with a fixed business clock, mocked send
  failures/catalogue failures, JSON round-trip checks, and an isolated JDBC
  test database; verify default-disabled startup and absence of listener,
  JPA/Flyway, web server, and service dependencies.
- Run relocated Playwright coverage and harness TypeScript checking, plus UI
  unit/type/build checks and focused service Rabbit listener tests.
- Review Gradle module dependencies, source paths, npm ownership/lockfiles,
  README/Make targets, documentation links and synchronized requirements, and
  `git diff --check`.
- Harness traffic creates real allocations/reservations/backorders when a
  service consumes it. Use dedicated non-production infrastructure.
- The harness performs no migrations: the service schema and consumer
  topology must exist first. A read-only DB user is recommended in addition
  to the read-only pool setting.
- The existing draft reusable-producer feature must now identify the harness,
  not the service, as the owner of sample generation/scheduling.

## Completed implementation and verification

- Added the Gradle module, custom publisher source sets, non-web Boot entry
  point, read-only catalogue datasource, clock, JSON converter, and opt-in
  scheduling. The publisher imports only shared API DTOs, not service classes.
- Moved the browser spec/configuration and Playwright dependency ownership to
  a separate harness npm package, with Node 22 configuration and typechecking.
  The configuration builds/starts the UI for both npm and direct IDE runs.
- Added `make test-e2e`, `make run-publisher`, root harness guidance, and
  performance scaffolding only. Removed synthetic service publishing.
- Five harness tests passed, including a real isolated PostgreSQL catalogue
  read with a SELECT-only user, paired/date/identity invariants, JSON
  round-trip/type headers, explicit enablement, and catalogue/send failures.
- Four focused service tests passed (`AllocationRabbitListenerTest` and
  `StockioApplicationTest`); service and harness main/test compilation passed.
- All 19 UI unit cases, UI/harness TypeScript checks, and the moved browser
  flow passed with `STOCKIO_API_URL` unset, exercising UI production build and
  startup with the mock default.
- Disabled publisher startup completed as a non-web application even with
  unreachable DB/broker addresses. Runtime dependency reports show harness
  and service each depend on the API library, not each other; the harness
  has no web-server, JPA, Flyway, or JobRunr runtime dependencies.
- No performance framework was installed, no producer-starter feature was
  implemented, and no existing shared infrastructure was changed.
