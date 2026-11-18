# Spring Boot Code Review Guideline

> **Purpose:** Rapid-scan checklist for pull requests that touch the Stockio Spring Boot service. Use it to find production-readiness issues in dependency injection, transactions, configuration, REST, persistence, errors, logging, and tests. Then apply [security-code-review-guideline.md](./security-code-review-guideline.md) for the full security pass and [kotlin+eda+outbox_code_review_guidelines.md](./kotlin+eda+outbox_code_review_guidelines.md) when the diff includes Kafka, RabbitMQ, or the outbox.

This guideline follows the review areas of the [Spring Boot Code Review Checklist](https://guidelines.ryanharrison.co.uk/frameworks/spring-boot/spring-boot-code-review/) and adapts them to this repository: Kotlin, Spring Boot 3.5, WebFlux on Netty, hexagonal ports and adapters, JPA plus a separate R2DBC pool, Flyway, and Testcontainers. Examples below are the shapes reviewers should expect in this codebase.

## How to use the severities

| Severity | Meaning |
| --- | --- |
| **Block** | Security hole, data corruption, or a leaked secret. Do not approve. |
| **Fix** | Correctness or performance bug that will show up under real traffic or concurrency. Fix before merge. |
| **Discuss** | Design or consistency improvement. Approve only when the author records why the current shape stays. |

A green build does not clear a **Block** or **Fix** item. Style comments that tooling can enforce stay out of this pass.

## 1. Security red flags

These are Spring-specific expressions of the security baseline. The security guideline owns exposure, authentication, messaging, and files. Finding any item here is a **Block**.

- SQL built with string concatenation, `String.format`, or Kotlin interpolation. JDBC, JPA, and R2DBC use bind parameters. Dynamic `ORDER BY` or table names come from a fixed allowlist in code.
- Secrets, tokens, connection strings, or full allocation bodies written to logs, Actuator, or exception responses.
- A live credential committed in Kotlin, YAML, tests, or docs. Localhost placeholders for Postgres and RabbitMQ are the only defaults that belong in `application.yml`.
- A new request body, path variable, header, or message field with no Bean Validation or equivalent explicit check before domain code uses it.
- A caller-controlled string passed to `Path.of`, a file writer, or a report directory without the single-segment `.xlsx` check already used by report generation.
- A stack trace, SQL exception, or internal path returned from `@RestControllerAdvice`.
- A JPA entity, persistence adapter, or repository type used as a REST or MCP response. Responses use API DTOs from `:stockio-api`.
- A monetary amount typed as `Double` or `Float`. Stock quantities in this service are whole numbers (`Int`). A new price, cost, or tax field uses `BigDecimal` with an explicit scale.
- A controller that accepts an entity, or a DTO that binds every persistent column, so a client can set `id`, status, or audit fields they do not own.
- User-controlled text rendered into HTML, a log format string, or an Excel formula cell without encoding or neutralization.

## 2. Dependency injection and layering

Stockio uses constructor injection. Kotlin primary constructors make `@Autowired` unnecessary.

**Fix**

- Field injection, or `lateinit var` used to receive a Spring bean.
- A missing `val` on an injected dependency, which leaves the bean mutable.
- A circular dependency between services. Split the cycle on a port or move the shared rule into the domain type that owns it.
- A `@Service` or `@Component` that injects more than about five collaborators. That is a cohesion problem, not a reason to introduce a service locator.
- Business decisions in a controller, listener, or repository. Controllers adapt HTTP. Application services coordinate. Domain types hold rules. Repositories persist.

**Discuss**

- A new abstraction that duplicates a port the module already has.
- Feature packages (`allocation`, `reservation`, `stock`, `product`) are the layout. A new layer-wide package (`controllers`, `services`) needs a reason.

```kotlin
// Shape already used by ReservationController
@RestController
class ReservationController(
    private val orderReservationService: OrderReservationService,
    private val blockingHttp: BlockingHttp,
) : ReservationApi {

    override suspend fun getOrderReservationStatusByOrderId(
        orderId: String,
    ): ResponseEntity<OrderReservationResponse> = blockingHttp.await {
        orderReservationService.getOrderReservationStatus(orderId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }
}
```

`@PostConstruct` and `@PreDestroy` stay short and non-blocking. Starting a broker client, migrating schema, or calling Tavily during bean creation is a **Fix**.

## 3. Transaction management

JPA `@Transactional` uses the primary JDBC transaction manager. SKU allocation uses `TransactionalOperator` on the R2DBC manager and does not enlist the Hikari connection. Reviewers check which manager a change actually joins.

**Block**

- A write that must be atomic with another write, split across two commits (database row, then Kafka send, then another row). The EDA guideline covers the outbox. This pass rejects the dual-write when it appears inside a Spring service.

**Fix**

- A service method that modifies persistent state and has no transaction boundary.
- `@Transactional` on a private method, or a local call to a `@Transactional` method on the same class. Spring's proxy never sees those calls. Move the boundary to a separate Spring bean.
- `@Transactional` on a repository adapter when the use case also updates other ports. The application service owns the boundary. Repository-level `Propagation.MANDATORY` is appropriate only when the method must join a transaction the caller already opened, which is an existing pattern in the stock and reservation adapters.
- A transaction held open across HTTP, Kafka, Redis, or filesystem work. Remote calls happen before the transaction opens or after it commits.
- `readOnly = true` missing on a query method that only reads. `OrderReservationService` is the reference shape.
- `Propagation.REQUIRES_NEW` or `NESTED` without a comment that names the failure that must commit independently of the caller.
- A transaction that covers several unrelated use cases "so it all rolls back". Keep one use case per transaction.
- Blocking JDBC inside a reactive chain on the Netty event loop. Domain I/O goes through `BlockingHttp` or the R2DBC pool. SKU allocation stays on `TransactionalOperator`.

```kotlin
// Read-only boundary on OrderReservationService. Mapping omitted.
@Service
class OrderReservationService(
    private val claimRepository: AllocationOrderClaimRepository,
    private val lineRepository: DemandOrderLineRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun getOrderReservationStatus(orderId: String): OrderReservationResponse? {
        val claim = claimRepository.findById(orderId).orElse(null) ?: return null
        return mapStatus(claim, lineRepository, clock)
    }
}
```

`Propagation.NEVER` on the stock ledger processor is intentional: that method must not run inside a caller transaction. Do not delete it as unused.

## 4. Configuration

**Block**

- A secret or environment-specific host hardcoded in a `@Configuration`, `@Bean`, or `@Value` default that is a real credential.

**Fix**

- A new group of related settings added as scattered `@Value` parameters. Group them in `@ConfigurationProperties` and validate with Bean Validation (`@Min`, `@NotBlank`, duration fields). Existing prefixes live under `stockio.*` (`stockio.allocation.kafka`, `stockio.product-resolution`, `stockio.allocation.admission`).
- A configuration class with no validation, so an invalid duration or a negative pool size boots and fails on the first request.
- Profile-specific behavior hidden in `if (env == "prod")` inside a service. Use configuration properties or a `@Profile` bean.
- `spring.jpa.hibernate.ddl-auto` changed away from `validate`. Schema changes are Flyway migrations.
- `spring.jpa.open-in-view` set to `true`. It stays `false`.
- Actuator `exposure.include` widened past `health,metrics`. Details are in the security guideline.

`@EnableConfigurationProperties` or constructor binding must actually register the new type. A properties class that nothing injects is dead configuration.

## 5. REST and MCP adapters

Generated controllers implement OpenAPI interfaces. The generator owns `@RequestMapping`. Handwritten controllers (`AllocationAdminController`, `StockController`) are the exception and must be listed in `docs/architecture.md` when their routes change.

**Block**

- An entity instance returned from a controller or MCP tool.
- A request type that reuses the entity class.

**Fix**

- A `@RequestBody` without `@Valid`, or path and query parameters with constraints but no `@Validated` on the controller.
- A new endpoint with no `@RestControllerAdvice` mapping for the exceptions it throws. `CustomerExceptionHandler` is the allocation handler. New exceptions get an explicit handler and a stable `code` plus `error` body. Do not add a second error format in the same change.
- The wrong status for the contract: `201` when a resource is created, `202` for accepted async allocation work, `204` for a delete with no body, `400` for invalid input, `404` for an unknown id, `413` for an oversized allocation body, `409` for a conflict, `503` for exhausted capacity. Matching the OpenAPI document beats a generic "always 200".
- `@Controller` plus manual `@ResponseBody` on a JSON API.
- Validation failures that fall through to a framework HTML error or a stack trace.
- Blocking work called directly from a WebFlux handler. Suspend the call through `BlockingHttp` so JDBC stays off the Netty event loop.
- A list endpoint that returns an unbounded collection from a table that grows with orders, reservations, or ledger rows. Accept `Pageable` (or an explicit limit) and default the page size.

**Discuss**

- OpenAPI annotations on a handwritten controller when the generated spec is the contract. Update the YAML spec instead of decorating the Kotlin method.
- API versioning. Current routes are unversioned under `/api`. A breaking response change needs a version decision recorded in the spec. Do not invent `/api/v2` for an additive field.
- Cache headers and content negotiation. Add them when a client actually caches or requests another media type.

```kotlin
@RestController
@Validated
class AllocationAdminController(
    private val allocationTools: AllocationMcpTools,
    private val blockingHttp: BlockingHttp,
) {
    @PostMapping("/allocations/generate-excel")
    fun generateExcelReport(
        @Valid @RequestBody request: ExcelReportRequest,
    ): Mono<String> = blockingHttp.call { allocationTools.generateExcelReport(request) }
}
```

CORS, if introduced, lists origins. A wildcard combined with credentials is a **Block**. Rate limiting for allocation already has admission control. A new public entry that can fan out work reuses that control or documents an equivalent cap. Do not add a second unbounded executor.

## 6. Data access, JPA, and R2DBC

**Block**

- Unbounded `findAll()` or a native query without `LIMIT` on a table that holds orders, stock rows, reservations, or job history, exposed through an API or a job that runs on a timer.

**Fix**

- A lazy `@OneToMany` or `@ManyToMany` walked in a loop. Load the association with `JOIN FETCH` or `@EntityGraph`, or map a projection that selects the columns the caller needs.
- `FetchType.EAGER` on a collection. Collections stay lazy and are fetched explicitly at the use-case boundary.
- A lazy association touched after the transaction closes (`LazyInitializationException` hidden by open-in-view). Keep `open-in-view: false` and load inside the service transaction.
- A query that selects full entities when the caller needs two columns. Prefer a projection or a DTO query.
- Integration tests that swap PostgreSQL for H2. JSON, locking, `SKIP LOCKED`, and Flyway SQL in this service are PostgreSQL-specific. Use Testcontainers.
- A repository method whose name or `@Query` does not match the columns it filters. Add the index in a Flyway migration when the new predicate runs on a growing table.
- Entity attribute names that silently depend on a naming strategy. Set `@Column(name = "...")` when the column is snake_case, matching `StockEntity`.
- JPA entities without the protected no-arg constructor Kotlin requires for Hibernate. Use a primary constructor with defaults, as the existing entities do.
- R2DBC code that takes a JDBC `EntityManager` connection, or a `@Transactional` JDBC method that expects to see R2DBC writes. The pools are separate. Say which one the change uses.
- Native SQL where JPQL or a Spring Data method name is enough. Native SQL stays for PostgreSQL features (locks, `ON CONFLICT`) and lives next to the migration that created the objects it touches.

```kotlin
// Required shape when a new query walks a collection
interface AllocationRepository : JpaRepository<AllocationEntity, UUID> {
    @EntityGraph(attributePaths = ["lines"])
    fun findByRunId(runId: UUID, pageable: Pageable): Page<AllocationEntity>
}
```

Pagination defaults belong on the controller (`@PageableDefault`) so a missing query string cannot request an enormous page.

## 7. Entity design

**Block**

- `Double` or `Float` for money. Quantities stay `Int`. New money uses `BigDecimal`.

**Fix**

- `@Enumerated(EnumType.ORDINAL)`. Persist enums with `EnumType.STRING`, as `AllocationJobTrackingEntity.status` does. Renaming an enum constant is a migration plus a code change.
- A missing `@Version` on an entity that two allocations, ledger runs, or HTTP requests can update at the same time. Handle `OptimisticLockingFailureException` with a retry or a conflict response, not a generic 500 that drops the caller's data.
- Bidirectional associations with no method that updates both sides, or `orphanRemoval` omitted when deleting the parent must delete the children.
- Timestamps set by the caller with `Instant.now()` scattered through adapters when the use case already has a clock. Prefer the injected clock so tests stay deterministic. `@PrePersist` and `@PreUpdate` are acceptable when every write path must stamp the same audit columns.
- Validation that exists only in the application and not in the database (unique order line plus lot, non-null SKU). Duplicate the rule with a constraint in Flyway when a bad row would corrupt stock.

## 8. Exception handling and resilience

**Fix**

- `catch (e: Exception)` that swallows the failure, including `CancellationException` on a coroutine. Log with context and rethrow, or map to a known domain exception.
- Exceptions used as ordinary branching for invalid input that Bean Validation could reject.
- A new failure mode that returns `exception.message` straight to the client. Log the detail. Return the stable code and a caller-safe sentence.
- An outbound call (Tavily, Ollama, Schema Registry, brokers, Redis) with no connect and read timeout. `stockio.product-resolution.request-timeout` is the existing pattern.
- A retry or circuit breaker with no cap, no backoff, and no distinction between a poison payload and a timeout. Messaging retries follow the EDA guideline.

**Discuss**

- Adopting RFC 7807 `ProblemDetail` across the API. Do that as one change. Until then, keep the `{ "code", "error" }` map from `CustomerExceptionHandler`.

## 9. Logging and metrics

**Block**

- Logging a password, API key, authorization header, or full request body.

**Fix**

- String concatenation or Kotlin templates inside a log call. Use placeholders so disabled levels do not build the message: `log.info("allocation.job.accepted jobId={}", jobId)`.
- `ERROR` for an expected rejection (validation, unknown id, capacity) and `DEBUG` for a failure the operator must see.
- A log line with no identifier. Include `jobId`, `orderId`, `runId`, or the listener topic and offset when those exist.
- A metric tag taken from SKU, order id, or free text. Tags stay low-cardinality (status, run type, exception class).
- Logging the entire entity or response "for debugging" and leaving it in the merged code.

Correlation ids belong in MDC when a request or message already carries one. Do not invent a second tracing stack inside a single pull request.

## 10. Kotlin and Spring Boot idioms

**Discuss**, unless the item hides a bug, in which case it is a **Fix**.

- API DTOs are Kotlin data classes in `:stockio-api`, not Java records and not JPA entities.
- Nullable return types express absence. Do not introduce `Optional` for fields, parameters, or collections. `Optional` is acceptable only at a Java interop boundary that already returns it.
- Sealed interfaces model a closed set of results or events. An `else` branch on that set hides a new subtype.
- Mapping stays explicit and local. MapStruct is not a project standard. Add it only when a PR is already drowning in repetitive mapping and the author shows the generated code is reviewed.
- The service is WebFlux because MCP and the HTTP stack are reactive, while domain I/O is blocking and isolated. Do not rewrite a blocking use case to WebFlux, and do not add a raw `@Async` executor, `GlobalScope`, or a new unbounded pool beside `BlockingHttp` and the R2DBC pool.
- Virtual threads are available on Java 21. Adopting them is a runtime change, not a drive-by in a feature PR. Blocking the Netty loop remains a **Fix** either way.

## 11. Testing

**Fix**

- A service change with no test for the failure path (validation, not found, conflict, capacity).
- A repository or SQL change tested only with mocks. Persistence tests use Testcontainers and PostgreSQL.
- Tests that depend on the wall clock, a fixed local port, or a shared database leftover from another test.
- A `@SpringBootTest` that boots the entire application to check one pure function. Use a unit test. Boot the context for HTTP, JPA, Flyway, and listener slices.

**Discuss**

- Mutation testing. The build does not run a mutation suite. Do not block a PR for lacking one.
- Contract tests against Tavily or Schema Registry. Mock the HTTP edge in unit tests and keep one Testcontainers or stub test for the adapter when the protocol changes.

Kotest, JUnit Jupiter, and Mockk are already in the build. Match the style of the package you are editing.

## 12. Database migrations

**Fix**

- A schema change without a Flyway migration, or a migration without the entity and query update that uses it.
- A migration that cannot be re-run safely (`INSERT` without a conflict target, `ADD COLUMN NOT NULL` without a default on a populated table) when the deploy may retry.
- A query filtering a new column with no index, on a table that grows with demand lines or stock movements.
- A join missing its condition, producing a cartesian product.
- A migration that drops or renames a column the running application version still reads. Expand, deploy, then contract.

## 13. Observability and operations

**Fix**

- A new external dependency with no timeout and no health signal. Actuator health stays free of secret-bearing detail (`show-details: never`).
- A business-critical path (allocation accept, allocation fail, ledger run, reservation expiry) that emits neither a log with an id nor a metric.
- Structured logging abandoned for multi-line string dumps.

**Discuss**

- JSON log encoding and alert rules. Those are deployment concerns. A PR that changes log content should still be machine-parseable: one event per line, stable keys.
- Container user and image hardening when the PR changes the image build. The runtime user should not be root.

## 14. Code smells that predict review churn

**Discuss**, and promote to **Fix** when the smell hides a behavior bug.

- A method that needs more than about 40 lines or several levels of nesting. Extract the rule or use a guard clause.
- A class past roughly 300 to 400 lines that mixes coordination and policy.
- The same validation copied into a controller, a listener, and a job. Put it on the application service or the DTO.
- Names that fight the domain (`Manager`, `Helper`, `processData`) when a domain word already exists (`allocate`, `expire`, `project`).
- A public API method with a non-obvious contract and no KDoc. Skip KDoc that repeats the function name.
- `TODO` or `FIXME` with no issue link.

## 15. Quick scan

| Category | What to check | Severity |
| --- | --- | --- |
| Security | Parameterized SQL, no caller-built queries | Block |
| Security | No secrets or full payloads in logs or responses | Block |
| Security | No live credentials in source or YAML | Block |
| Security | Bean Validation or an explicit check on new input | Block |
| Security | DTOs cross the REST and MCP boundary, not entities | Block |
| Security | Error bodies stay caller-safe | Block |
| Security | Money is `BigDecimal`; quantities stay `Int` | Block |
| Transactions | Writes have a real transaction boundary on the correct manager | Fix |
| Transactions | Reads use `readOnly = true` | Fix |
| Transactions | No remote I/O inside the transaction | Fix |
| Transactions | `@Transactional` is not on a private or self-invoked method | Fix |
| Performance | Collections fetched with `JOIN FETCH` or `@EntityGraph` | Fix |
| Performance | List APIs and growing queries are paged or limited | Fix |
| Performance | JDBC and HTTP do not run on the Netty event loop | Fix |
| API | `@Valid` on request bodies | Fix |
| API | Status codes match the OpenAPI contract | Fix |
| API | One error shape, via `@RestControllerAdvice` | Fix |
| Config | `@ConfigurationProperties` plus validation | Discuss |
| DI | Constructor injection of `val` dependencies | Discuss |
| Entities | `EnumType.STRING` | Fix |
| Entities | `@Version` where concurrent updates are expected | Fix |
| Data | Flyway migration matches the code change | Fix |
| Tests | Failure paths tested; PostgreSQL via Testcontainers for SQL | Fix |
| Ops | Timeouts on outbound clients; Actuator exposure unchanged | Fix |

## 16. Block checklist

Approve only when every applicable line is true.

- [ ] Queries are parameterized. No caller-controlled SQL fragments.
- [ ] Logs, metrics, and error responses omit secrets, tokens, and stack traces.
- [ ] The diff introduces no live credential.
- [ ] New input is validated before domain code runs.
- [ ] File names and paths cannot escape their configured directory.
- [ ] Controllers and MCP tools return DTOs.
- [ ] New money fields use `BigDecimal`. Quantities stay integers.
- [ ] State changes run in a transaction on the JDBC manager or the R2DBC operator, and the author knows which.
- [ ] Read paths that only query are `readOnly = true`.
- [ ] List and scan queries that can grow are bounded.
- [ ] Outbound calls have a finite timeout.
- [ ] Persistence changes are covered by a PostgreSQL Testcontainers test, or the PR explains why the existing suite already covers them.

## References

- Spring Boot code review checklist: https://guidelines.ryanharrison.co.uk/frameworks/spring-boot/spring-boot-code-review/
- Stockio architecture: [`docs/architecture.md`](../architecture.md)
- Security pass: [security-code-review-guideline.md](./security-code-review-guideline.md)
- EDA and outbox pass: [kotlin+eda+outbox_code_review_guidelines.md](./kotlin+eda+outbox_code_review_guidelines.md)
