# Active Context

## Feature: Automate backorder fulfillment after physical inbound receipt

- **Specification:** `.github/memory/specifications.md#feature-automate-backorder-fulfillment-after-physical-inbound-receipt`
- **Scenario:** `.github/memory/scenarios.md#scenario-4-fulfill-open-backorders`
- **Issue:** `.github/memory/issues/feature_automate-backorder-fulfillment-after-inbound-receipt.md`

### Steps worked on
- Captured the approved design direction: expected inbound registration does not trigger fulfillment; physical receipt during stock-ledger synchronization does.
- Defined a dedicated `BackorderFulfillmentCommand` and reusable application service instead of reusing `AllocationRequest` or the `NEW_DEMAND` flow.
- Documented post-commit JobRunr scheduling, affected-SKU scoping, priority/FIFO behavior, expiry filtering, projection refresh, idempotency, and concurrent-execution protection.
- Added the feature specification and synchronized Scenario 4 with the feature issue document.
- Implementation has not started.

### Next possible steps (suggestions)
- Decide the exact JobRunr idempotency key and persistence guard for duplicate receipt-triggered jobs.
- Confirm how the ledger transaction exposes newly received shipment-line SKUs to the after-commit scheduler.
- Implement and test the reusable backorder fulfillment application service.

Tell us what is the current active context for the agent. This should include:
- Reference to the feature definition in `.github/memory/requirements-specification.md` in the `## Feature` section.
- Steps worked on, including brief summary of the AGENTS.md `Workflow orchestration` routine in the `### Steps worked on` section.
- If you have any suggestions for next steps, add them in the `### Next possible steps (suggestions)` section.

## Feature: Adopt Virtual Threads via Coroutine Offloading for Blocking Persistence Paths
- Reference: `.github/memory/requirements-specification.md`##Feature: Adopt Virtual Threads via Coroutine Offloading for Blocking Persistence Paths

### Steps worked on
- Reviewed AGENTS guidance, lessons, requirements, architecture, scenarios, existing feature issues, and relevant WebFlux/session/repository source paths.
- Completed repository-wide impact analysis for virtual-thread adoption in the current WebFlux + blocking JDBC mixed model.
- Confirmed feature decisions with user: Priority `P1`, and scope limited to Phase 1 (keep WebFlux/Gateway, coroutine-first virtual-thread offloading, unchanged API contracts).
- Prepared and synchronized feature specification entries in requirements and issue documents:
  - `.github/memory/requirements-specification.md`
  - `.github/memory/issues/feature-virtual-threads-blocking-persistence.md`
- Moved feature status to `In-progress` and started implementation from `.github/memory/issues/feature-virtual-threads-blocking-persistence.md`.
- Added shared `vtDispatcher` bean backed by virtual threads and injected it into request-path services that touch blocking persistence.
- Offloaded blocking user repository calls with coroutine boundaries (`withContext(vtDispatcher)`) in `UserService` and `SessionService.rotateTokens`.
- Converted user controller/service request-path methods to coroutine `suspend` flow while keeping endpoint routes and payload contracts unchanged.
- Removed unmanaged request-path `subscribe()` side-effect in login flow and replaced it with structured suspension (`awaitSingleOrNull`).
- Verified style and behavior with `./gradlew --no-daemon :service:ktlintCheck :service:test` (successful).

### Next possible steps (suggestions)
- Review whether the feature can be moved to `Done` in requirements after stakeholder validation of migration scope.
- Add focused thread-observability assertions (if desired) to prove no event-loop execution for blocking repository calls under load.

## Feature: Switch from symmetric secretKey used for signing and verifying to an asymmetric secretKey pair
- Reference: .github/memory/requirements-specification.md##Feature: Switch from symmetric secretKey used for signing and verifying to an asymmetric secretKey pair

### Steps worked on
- Created issue for Feature: Switch from symmetric secretKey used for signing and verifying to an asymmetric secretKey pair, including FR, NFR, plan, and architectural context.
- Updated status to In-progress in requirements-specification.md.
- Implemented RS256 JWT signing/verification using RSA private/public keys in `JwtTokenProvider`.
- Updated session public key endpoint flow to return PEM-formatted public key only.
- Added/updated focused tests for asymmetric JWT behavior and public key exposure.
- Updated memory docs (`architecture.md`, `scenarios.md`, issue technical implementation) with the new JWT flow.

### Next possible steps (suggestions)
- Run and verify targeted tests for the updated JWT/session components.
- If all validations pass, finalize the feature and update requirements status.

## Feature: Implement Kotlin Gatling tests for the login, verify and signup flows
- Reference: .github/memory/requirements-specification.md##Feature: Implement Kotlin Gatling tests for the login, verify and signup flows

### Steps worked on
- Created issue and technical plan at `.github/memory/issues/feature-kotlin-gatling-auth-tests.md`.
- Scanned `requirements-specification.md`, `scenarios.md`, and `architecture.md` for authentication flows and performance testing needs.
- Added new `performance/` Gradle module with Gatling Kotlin simulations for signup, verify, and login.
- Implemented shared helpers (`BaseSimulation`, `AuthFeeder`, `SessionHelper`) with env/system-driven load profile and SLA thresholds.
- Added Tekton CI integration (`task-gradle-performance.yaml`) and wired pipeline step to run Gatling and persist HTML report artifacts.

### Next possible steps (suggestions)
- Run the performance task against a reachable test endpoint to calibrate default SLA thresholds with real baseline metrics.
- Extend Tekton artifact publication if a dedicated artifact store path/convention is introduced.

## Feature: Switchable User Repository Implementations
- Reference: `.github/memory/requirements-specification.md`##Feature: Switchable User Repository Implementations

### Steps worked on
- Reviewed the requirements, issue plan, active context, architecture, scenarios, lessons, relevant persistence source, tests, configuration, and current worktree changes.
- Recreated the feature issue document after it was no longer present in the worktree.
- Set the feature status to In-progress and updated this active context before code changes, following the root `AGENTS.md` pre-execution steps.
- Refactored the `UserRepository` contract and `UserQuery` types to persistence-neutral models and query types.
- Implemented both `UserJooqRepository` and `UserHibernateRepository` adapters over the same operations (create, delete, verify, lookup, filtering, count, pagination) and wired runtime selection with `user.repository.type` defaulting to `jooq`.
- Added adapter behavior tests (jOOQ and Hibernate slices) and repository-selection property tests including invalid value failure behavior.

### Next possible steps (suggestions)
- Resolve unrelated baseline test failures (`GatewayForwardFilterIntegrationTest`, `RedisSessionRepositoryTest`) before broad green builds.

## Feature: Switchable User Repository Implementations

- Status: Done
- Priority: P1
- Context: The service currently relies on a jOOQ-backed user repository. Supporting a Hibernate/JPA implementation will allow the persistence technology to be selected through runtime configuration, while keeping application services independent of either persistence framework.

## Functional Requirements (FR)

| ID | Title | Description |
|----|-------|-------------|
| FR-01 | Persistence-neutral user repository port | Refactor the shared `UserRepository` contract and its query/model types so they do not expose jOOQ-generated records, jOOQ conditions, or other persistence-specific types. |
| FR-02 | Multiple repository adapters | Provide jOOQ and Hibernate/JPA adapters that implement the same user operations, including create, delete, verification, lookup, filtering, counting, and pagination. |
| FR-03 | Runtime repository selection | Select exactly one adapter using Spring runtime configuration. Keep jOOQ as the default for existing deployments, support an explicit Hibernate/JPA choice, and fail clearly for an unsupported selection value. |
| FR-04 | Consistent repository behavior | Preserve the existing user query semantics and result behavior across both adapters so callers do not need implementation-specific branches. |

### Plan

1. Introduce persistence-neutral user and query types, then refactor `UserRepository`, `UserQuery`, and service callers to use them instead of jOOQ records, conditions, and generated table fields.
2. Update the jOOQ adapter to implement the persistence-neutral contract without changing the current default behavior.
3. Add Hibernate/JPA persistence dependencies and user entity mappings for the existing users table, then implement the same repository operations using Hibernate/JPA.
4. Configure mutually exclusive Spring repository beans using a runtime property (for example, `user.repository.type=jooq|hibernate`), with jOOQ as the default and invalid values rejected clearly.
5. Add focused repository behavior tests for both adapters and configuration tests for default, explicit Hibernate/JPA selection, and invalid selection.

## Non-Functional Requirements (NFR)

Not yet
