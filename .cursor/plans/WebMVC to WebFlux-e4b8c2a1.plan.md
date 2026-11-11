<!-- e4b8c2a1-6f3d-4a9e-8b77-1c0d5e6f7a8b -->
---
todos:
  - id: "classpath-and-app-type"
    content: "Swap stockio-service to WebFlux and springdoc WebFlux UI, set web-application-type reactive, and move MCP off the servlet starter if it pulls WebMVC"
    status: pending
  - id: "generated-interfaces"
    content: "Set reactive true on the three kotlin-spring OpenAPI tasks and regenerate Mono<ResponseEntity> interfaces"
    status: pending
  - id: "controllers"
    content: "Wrap controller bodies in Mono.fromCallable on a shared boundedElastic scheduler and keep 202, Location, 409, and 503 behavior"
    status: pending
  - id: "body-limit-filter"
    content: "Replace AllocationRequestBodyFilter with a WebFilter that caps bytes and replays the body"
    status: pending
  - id: "openapi-resources"
    content: "Serve /openapi/** with WebFluxConfigurer so springdoc still loads the three YAML specs"
    status: pending
  - id: "tests"
    content: "Move standalone and integration HTTP tests from MockMvc to WebTestClient, including the 413 filter"
    status: pending
  - id: "docs"
    content: "Update architecture.md for the WebFlux edge and the blocking domain boundary when implementation starts"
    status: pending
isProject: false
---
# WebMVC to WebFlux

Branch: `feature/exchange-from-mvc-to-webflux`.

This is an edge change. PostgreSQL, JPA, JDBC, Redis sync calls, Kafka, RabbitMQ, JobRunr, Apache POI, and the Tavily/Ollama `RestClient`s stay blocking. WebFlux replaces the servlet container. Blocking work is kept off the Netty event loop. The public JSON contract stays the same, so the Next.js Axios client, Gatling harness, and OpenAPI documents do not change their paths or status codes.

Do not replace Spring Data JPA with R2DBC, and do not rewrite `AllocationService` or `AllocationBackorderService` as a reactive pipeline in this change. Hikari is capped at 10 connections (`DB_POOL_MAX_SIZE`, default 10). That pool, plus the existing admission control, is the concurrency limit. A Netty server does not raise allocation throughput by itself.

## Current servlet surface

Runtime is explicit in [`stockio-service/src/main/resources/application.yml`](stockio-service/src/main/resources/application.yml): `spring.main.web-application-type: servlet`. Dependencies in [`build.gradle`](build.gradle):

- `spring-boot-starter-web` (Tomcat)
- `springdoc-openapi-starter-webmvc-ui:2.8.13`
- `spring-ai-starter-mcp-server` with `spring.ai.mcp.server.type: sync`
- `spring-boot-starter-data-jpa`, AMQP, Kafka, Redis, validation, actuator, JobRunr

OpenAPI Generator 7.26.0, generator `kotlin-spring`, `interfaceOnly: true`, `useSpringBoot3: true`, `reactive` left at its default `false`. Three tasks: `openApiGenerateAllocation`, `openApiGenerateReservation`, `openApiGenerateProductAllocation`. Generated interfaces return `ResponseEntity`. Handwritten controllers do not implement a generated interface.

| Piece | File | Servlet binding |
| --- | --- | --- |
| Generated APIs | `AllocationController`, `ReservationController`, `ProductAllocationController` | `ResponseEntity` methods |
| Handwritten APIs | `AllocationAdminController`, `StockController` | `@GetMapping` / `@PostMapping` / `@DeleteMapping` return values directly |
| Body limit | [`AllocationRequestBodyFilter`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/filter/AllocationRequestBodyFilter.kt) | `OncePerRequestFilter`. Reads bytes, writes `413` JSON on the servlet response, then wraps `HttpServletRequest` so the controller can read the body again. `setReadListener` throws. |
| Static specs | [`OpenApiResourceConfiguration`](stockio-service/src/main/kotlin/nl/stockio/config/OpenApiResourceConfiguration.kt) | `WebMvcConfigurer` serves `/openapi/**` from `classpath:/openapi/` for springdoc (`/openapi.html`) |
| Errors | `CustomerExceptionHandler` | `@RestControllerAdvice` plus `@ResponseStatus` |
| MCP | `AllocationMcpTools` `@Tool` methods | Sync MCP server on the same process |
| Tests | `AllocationControllerTest`, `ReservationControllerTest`, `ProductAllocationControllerTest`, `AllocationRequestBodyFilterTest`, `AllocationIntegrationTest` | `MockMvc` / `@AutoConfigureMockMvc` |

Blocking calls already sit behind the controllers: `runBlocking` plus `Dispatchers.IO.limitedParallelism` in `AllocationService` and `AllocationBackorderService`, `JdbcTemplate` snapshot and ledger repositories, `StringRedisTemplate` on Lettuce (sync API), file IO for CSV and Excel, JobRunr storage, and `RestClient` for Tavily and Ollama.

Leave `stockio-test-harness` on the servlet stack. Its publisher is a separate Spring Boot process and only shares DTOs.

## Target shape

Annotated `@RestController`s stay. They return `Mono<ResponseEntity<T>>` (or `Mono<T>` for the two handwritten admin strings). Each method that touches JDBC, Redis, files, JobRunr, Kafka publish, or `RestClient` is:

```kotlin
Mono.fromCallable { /* existing blocking call */ }
    .subscribeOn(Schedulers.boundedElastic())
```

Put that wrapper in one place, for example `nl.stockio.config.BlockingHttp`, so controllers do not each pick a scheduler. Size `boundedElastic` to the Hikari max and the admission-control permits. A larger elastic pool only queues on the same 10 connections.

`runBlocking` inside those services is safe only while it runs on the elastic thread the callable already occupies. It must not be called from a Netty event-loop thread, a `WebFilter` operator, or a coroutine that resumes on the Reactor event loop. Leave the SKU parallelism on `Dispatchers.IO`.

`@RestControllerAdvice` still applies to exceptions raised inside `Mono.fromCallable`. Keep the filter's direct `413` response. That path never enters the controller, and it should stay that way.

## Steps

1. **Classpath and application type.** In `stockio-service` only, replace `spring-boot-starter-web` with `spring-boot-starter-webflux` and `springdoc-openapi-starter-webmvc-ui` with `springdoc-openapi-starter-webflux-ui` at the same 2.8.13 line. Set `spring.main.web-application-type` to `reactive`. Boot must not see both web stacks. After the dependency edit, run `./gradlew :stockio-service:dependencies` and remove any transitive `spring-webmvc` or Tomcat. `spring-ai-starter-mcp-server` 1.1.2 has no transport suffix; confirm whether it pulls WebMVC. If it does, switch to the WebFlux MCP starter in the same 1.1.2 BOM and set `spring.ai.mcp.server.type` to `async`. Tool methods stay ordinary blocking Kotlin. The async server is what keeps those calls off the event loop.

2. **Generated interfaces.** Add `reactive: 'true'` to the three `kotlin-spring` `configOptions` blocks in [`build.gradle`](build.gradle). Regenerate with the existing tasks. Overrides become `Mono<ResponseEntity<...>>`. Do not change the YAML paths, status codes, or schemas. `models` stays `false`. The cursor rule in [`.cursor/rules/openapi-endpoint.mdc`](.cursor/rules/openapi-endpoint.mdc) shows a blocking `ResponseEntity` override; update that sample in the same change so the next endpoint follows the reactive signature.

3. **Controllers.** Wrap the existing bodies. Preserve `202`, `Location: /api/allocation/jobs/{jobId}`, and the Redis `409` / `503` exceptions thrown from [`AllocationController.submitAllocationJob`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt). `AllocationAdminController` and `StockController` get the same wrapper even though they are not generated. CSV read, Excel write, and JobRunr flush are blocking file and database work.

4. **Request body limit.** Replace `AllocationRequestBodyFilter` with a `WebFilter` ordered at the same precedence. Match the same two POSTs: `/api/allocation/jobs` and `/api/products/allocate`, and the same `AllocationAdmissionProperties.enabled` guard. Read `DataBuffer`s up to `maxRequestBytes + 1`, call `AllocationRequestLimits.checkBytes`, and on `AllocationRequestTooLargeException` write `413` with `Content-Type: application/json`, `Cache-Control: no-store`, and the same `code` / `error` body. On success, replace the exchange body with a replayable buffer of those bytes. WebFlux consumes a body once; the controller codec must still see it. Do not call `runBlocking` in the filter.

5. **Static OpenAPI files.** Change `OpenApiResourceConfiguration` to `WebFluxConfigurer` and register the same `/openapi/**` to `classpath:/openapi/` handler. springdoc URLs in `application.yml` stay `/openapi/allocation-controller.yaml`, `/openapi/product-allocation-controller.yaml`, and `/openapi/reservation-controller.yaml`.

6. **Tests.** Standalone `MockMvcBuilders` becomes `WebTestClient.bindToController(...).controllerAdvice(CustomerExceptionHandler()).build()`. `AllocationIntegrationTest` swaps `@AutoConfigureMockMvc` for `@AutoConfigureWebTestClient`. Assert the same status, JSON paths, and `Location` header. Rewrite `AllocationRequestBodyFilterTest` against the `WebFilter`: oversized body returns `413` and does not read past the cap; an accepted body is still visible to the controller. `ProductResolutionClientsTest` can keep `RestClient`; those calls are not on the event loop.

7. **Docs, when implementation starts.** Update [`docs/architecture.md`](docs/architecture.md): the service is a WebFlux application, and domain I/O stays blocking and isolated from the event loop. HTTP scenarios stay as they are if status codes and bodies do not change. A durable feature spec and issue document belong to a later `/create issue` pass; this plan does not add them.

## Verification

- `./gradlew :stockio-api:openApiGenerateAllocation :stockio-api:openApiGenerateReservation :stockio-api:openApiGenerateProductAllocation`
- `./gradlew :stockio-service:compileKotlin`
- Controller tests, `AllocationRequestBodyFilterTest`, and `AllocationIntegrationTest`
- Runtime classpath has `spring-webflux` and no `spring-webmvc`
- Boot log shows Netty, and `GET /actuator/health` on port 9090 still answers
- One `POST /api/allocation/jobs` and one oversized POST still return `202` and `413`
- `/openapi.html` still loads the three YAML URLs

## Risks

- A controller that returns a plain object, or a `Mono` that is not subscribed on `boundedElastic`, blocks a Netty thread for the whole JDBC or `runBlocking` call and stalls every other request.
- `runBlocking` on an event-loop thread deadlocks if a child coroutine tries to resume there. Keeping it inside the elastic callable avoids that.
- Both starters on the classpath make Boot refuse to start, or pick the servlet app type again because `web-application-type` was left as `servlet`.
- The body filter must cap bytes before buffering. `DataBufferUtils.join` without a limit can read an oversized payload into memory, which is the case the servlet filter rejects.
- Sync MCP (`type: sync`) does not start on a WebFlux server. The tool transport and the web stack have to move together.
- Expect the same admission `503`s. WebFlux does not add capacity beyond Hikari and the existing permits.
