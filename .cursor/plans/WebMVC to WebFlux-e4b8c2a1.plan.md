<!-- e4b8c2a1-6f3d-4a9e-8b77-1c0d5e6f7a8b -->
---
todos:
  - id: "classpath-and-app-type"
    content: "Swap stockio-service to WebFlux and springdoc WebFlux UI, set web-application-type reactive, and confirm the STDIO MCP starter does not pull WebMVC"
    status: completed
  - id: "generated-interfaces"
    content: "Set reactive true on the three kotlin-spring OpenAPI tasks and regenerate suspend ResponseEntity interfaces"
    status: completed
  - id: "controllers"
    content: "Run controller bodies through BlockingHttp on one dedicated boundedElastic scheduler and keep 202, Location, 409, and 503 behavior"
    status: completed
  - id: "body-limit-filter"
    content: "Replace AllocationRequestBodyFilter with a WebFilter that caps bytes and replays the body"
    status: completed
  - id: "openapi-resources"
    content: "Serve /openapi/** with WebFluxConfigurer so springdoc still loads the three YAML specs"
    status: completed
  - id: "tests"
    content: "Move standalone and integration HTTP tests from MockMvc to WebTestClient, including the 413 filter"
    status: completed
  - id: "docs"
    content: "Update architecture.md for the WebFlux edge and the blocking domain boundary when implementation starts"
    status: completed
isProject: false
---

# WebMVC to WebFlux

Branch: `feature/exchange_from_webmvc_to_webflux`.

This is an edge change. PostgreSQL, JPA, JDBC, Redis sync calls, Kafka, RabbitMQ, JobRunr, Apache POI, and the Tavily/Ollama `RestClient`s stay blocking. WebFlux replaces the servlet container. Blocking work stays off the Netty event loop. The public JSON contract stays the same, so the Next.js Axios client, Gatling harness, and OpenAPI documents keep their paths and status codes.

PostgreSQL stays on Spring Data JPA and JDBC. `AllocationService` and `AllocationBackorderService` stay blocking `runBlocking` services. Hikari is capped at 10 connections (`DB_POOL_MAX_SIZE`, default 10). That pool, plus the existing admission control, is the concurrency limit. A Netty server does not raise allocation throughput by itself.

`spring.jpa.open-in-view` is already `false`. Leave it there. WebFlux has no Open EntityManager in View filter.

## Current servlet surface

Runtime is explicit in [`stockio-service/src/main/resources/application.yml`](stockio-service/src/main/resources/application.yml): `spring.main.web-application-type: servlet`.

Dependencies in [`build.gradle`](build.gradle):

- `spring-boot-starter-web` (Tomcat)
- `springdoc-openapi-starter-webmvc-ui:2.8.13`
- `spring-ai-starter-mcp-server` (Spring AI BOM `1.1.2`) with `spring.ai.mcp.server.type: sync`
- `spring-boot-starter-data-jpa`, AMQP, Kafka, Redis, validation, actuator, JobRunr

OpenAPI Generator `7.26.0`, generator `kotlin-spring`, `interfaceOnly: true`, `useSpringBoot3: true`, `reactive` left at its default `false`. Three tasks: `openApiGenerateAllocation`, `openApiGenerateReservation`, `openApiGenerateProductAllocation`. Generated interfaces return `ResponseEntity`. `models` stays `false`.

| Piece | File | Servlet binding |
| --- | --- | --- |
| Generated API implementations | `AllocationController`, `ReservationController`, `ProductAllocationController` | Implement `AllocationApi`, `ReservationApi`, `ProductAllocationApi`. Overrides return `ResponseEntity`. |
| Handwritten APIs | `AllocationAdminController`, `StockController` | Own `@GetMapping`, `@PostMapping`, and `@DeleteMapping`. Admin returns `AllocationRequest` or `String`. Stock returns `ResponseEntity`. |
| Body limit | [`AllocationRequestBodyFilter`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/filter/AllocationRequestBodyFilter.kt) | `OncePerRequestFilter` at `Ordered.HIGHEST_PRECEDENCE + 10`. Checks `Content-Length`, reads at most `maxRequestBytes + 1`, writes `413` JSON, then wraps `HttpServletRequest` so the controller can read the body again. `setReadListener` throws. |
| Static specs | [`OpenApiResourceConfiguration`](stockio-service/src/main/kotlin/nl/stockio/config/OpenApiResourceConfiguration.kt) | `WebMvcConfigurer` serves `/openapi/**` from `classpath:/openapi/` for springdoc (`/openapi.html`). |
| Errors | `CustomerExceptionHandler` | `@RestControllerAdvice` plus `@ResponseStatus`. Handler methods return `Map<String, String>`. |
| MCP | `AllocationMcpTools` `@Tool` methods | Sync MCP server in the same process. The dependency is the STDIO starter, not `spring-ai-starter-mcp-server-webmvc`. |
| Tests | `AllocationControllerTest`, `ReservationControllerTest`, `ProductAllocationControllerTest`, `AllocationRequestBodyFilterTest`, `AllocationIntegrationTest` | `MockMvc` / `@AutoConfigureMockMvc`, or servlet mocks for the filter. |

Blocking calls already sit behind the controllers: `runBlocking` plus `Dispatchers.IO.limitedParallelism` in `AllocationService` and `AllocationBackorderService`, `JdbcTemplate` snapshot and ledger repositories, `StringRedisTemplate` on Lettuce (sync API), file IO for CSV and Excel, JobRunr storage, and `RestClient` for Tavily and Ollama.

`stockio-test-harness` stays a separate non-web process. Its publisher sets `spring.main.web-application-type: none` and only shares DTOs.

## Target shape

Annotated `@RestController`s stay.

`kotlin-spring` with `reactive: true` generates `suspend` functions that return `ResponseEntity<T>`. It does not generate `Mono`.

- Generated controllers are `suspend` and call `BlockingHttp.await`.
- `AllocationAdminController` returns `Mono<AllocationRequest>` or `Mono<String>`.
- `StockController` returns `Mono<ResponseEntity<StockLedgerSyncStatus?>>`.

Each method that touches JDBC, Redis, files, JobRunr, Kafka publish, or `RestClient` runs on the dedicated scheduler. Generated methods use `await`. Handwritten methods use:

```kotlin
Mono.fromCallable { /* existing blocking call */ }
    .subscribeOn(blockingHttpScheduler)
```

Put that wrapper in one place, for example `nl.stockio.config.BlockingHttp`, so controllers do not each pick a scheduler. The scheduler is a dedicated `Schedulers.newBoundedElastic(...)` bean. `Schedulers.boundedElastic()` is the process-wide scheduler and is not resized from application code.

`runBlocking` holds that worker for the whole call while child work still runs on `Dispatchers.IO` and takes Hikari connections. Keep the dedicated pool larger than the Hikari max of 10. Hikari and admission control stay the throughput limit. A larger worker pool only queues on those same 10 connections.

`runBlocking` is safe only on the worker the callable already occupies. It must not run on a Netty event-loop thread, inside a `WebFilter` operator, or in a coroutine that resumes on the Reactor event loop. Leave the SKU parallelism on `Dispatchers.IO`.

`@RestControllerAdvice` still applies to exceptions raised inside `Mono.fromCallable`. Handler methods can keep their current `Map<String, String>` signatures. Keep the filter's direct `413` response. That path never enters the controller.

## Steps

1. **Classpath and application type.** In `stockio-service` only, replace `spring-boot-starter-web` with `spring-boot-starter-webflux` and `springdoc-openapi-starter-webmvc-ui` with `springdoc-openapi-starter-webflux-ui` at the same `2.8.13` line. Set `spring.main.web-application-type` to `reactive`. Boot must see one web stack.

   After the dependency edit, run `./gradlew :stockio-service:dependencies` and remove any transitive `spring-webmvc` or Tomcat.

   Keep `spring-ai-starter-mcp-server` `1.1.2` and `spring.ai.mcp.server.type: sync`. That artifact is the STDIO starter and depends on `spring-boot-starter`, not `spring-boot-starter-web`. The `@Tool` methods are ordinary blocking Kotlin. On Spring AI 1.1 a `SYNC` server registers those methods. An `ASYNC` server registers asynchronous tool methods and ignores these. HTTP MCP (`spring-ai-starter-mcp-server-webflux`) is a separate transport decision and is out of this change.

   JobRunr's dashboard stays enabled on `${JOBRUNR_DASHBOARD_PORT:8181}`. Confirm the JobRunr starter does not pull `spring-webmvc` onto the service classpath.

2. **Generated interfaces.** Add `reactive: 'true'` to the three `kotlin-spring` `configOptions` blocks in [`build.gradle`](build.gradle). Regenerate with the existing tasks. `kotlin-spring` emits `suspend` functions that return `ResponseEntity`. Do not change the YAML paths, status codes, or schemas. `models` stays `false`. The cursor rule in [`.cursor/rules/openapi-endpoint.mdc`](.cursor/rules/openapi-endpoint.mdc) shows a blocking `ResponseEntity` override. Update that sample in the same change so the next endpoint follows the reactive signature.

3. **Controllers.** Wrap the existing bodies. Preserve `202`, `Location: /api/allocation/jobs/{jobId}`, and the Redis `409` / `503` exceptions thrown from [`AllocationController.submitAllocationJob`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt). `AllocationAdminController` and `StockController` get the same wrapper. CSV read, Excel write, and JobRunr flush are blocking file and database work. The admin CSV endpoint reads `stockio.allocation.csv-path`. It is not a multipart upload.

4. **Request body limit.** Replace `AllocationRequestBodyFilter` with a `WebFilter` at `Ordered.HIGHEST_PRECEDENCE + 10`. Match the same two POSTs, `/api/allocation/jobs` and `/api/products/allocate`, and the same `AllocationAdmissionProperties.enabled` guard.

   On a known `Content-Length`, call `AllocationRequestLimits.checkBytes` before reading. Then read `DataBuffer`s up to `maxRequestBytes + 1` and call `checkBytes` again. On `AllocationRequestTooLargeException`, write `413` with `Content-Type: application/json`, `Cache-Control: no-store`, and the same `code` / `error` body, and do not call the rest of the chain. On success, replace the exchange body with a replayable buffer of those bytes and release the original buffers. WebFlux consumes a body once. The controller codec must still see it. Do not call `runBlocking` in the filter.

5. **Static OpenAPI files.** Change `OpenApiResourceConfiguration` to `WebFluxConfigurer` and register the same `/openapi/**` handler for `classpath:/openapi/`. springdoc URLs in `application.yml` stay `/openapi/allocation-controller.yaml`, `/openapi/product-allocation-controller.yaml`, and `/openapi/reservation-controller.yaml`.

6. **Tests.** Standalone `MockMvcBuilders` becomes `WebTestClient.bindToController(...).controllerAdvice(CustomerExceptionHandler()).build()`. `AllocationIntegrationTest` swaps `@AutoConfigureMockMvc` for `@AutoConfigureWebTestClient`. Assert the same status, JSON paths, and `Location` header. Rewrite `AllocationRequestBodyFilterTest` against the `WebFilter`: an oversized `Content-Length` or body returns `413` and does not read past the cap. An accepted body is still visible to the controller. `ProductResolutionClientsTest` can keep `RestClient`. Those calls are not on the event loop.

7. **Docs, when implementation starts.** Update [`docs/architecture.md`](docs/architecture.md). The service is a WebFlux application, and domain I/O stays blocking and isolated from the event loop. The sentence that the service runs as a servlet application is the one to replace. HTTP scenarios stay as they are when status codes and bodies do not change. A durable feature spec and issue document belong to a later `/create issue` pass. This plan does not add them.

## Verification

- `./gradlew :stockio-api:openApiGenerateAllocation :stockio-api:openApiGenerateReservation :stockio-api:openApiGenerateProductAllocation`
- `./gradlew :stockio-service:compileKotlin`
- Controller tests, `AllocationRequestBodyFilterTest`, and `AllocationIntegrationTest`
- Runtime classpath has `spring-webflux` and no `spring-webmvc` or Tomcat
- Boot log shows Netty, and `GET /actuator/health` on port 9090 still answers
- JobRunr dashboard on port 8181 still answers
- One `POST /api/allocation/jobs` returns `202` with `Location`
- One oversized POST still returns `413` with the same JSON body
- `/openapi.html` still loads the three YAML URLs
- Existing MCP `@Tool` methods are still registered on the sync server

## Risks

- A controller that returns a plain object, or a `Mono` subscribed on a Netty thread, blocks that thread for the whole JDBC or `runBlocking` call and stalls other requests.
- `runBlocking` on an event-loop thread deadlocks if a child coroutine tries to resume there. Keeping it inside the dedicated-scheduler callable avoids that.
- Capping that scheduler at the Hikari size of 10 can stall requests: each callable holds its worker inside `runBlocking` while `Dispatchers.IO` still needs connections.
- Both web starters on the classpath make Boot refuse to start, or pick the servlet app type again when `web-application-type` is left as `servlet`.
- The body filter must cap bytes before buffering. `DataBufferUtils.join` without a limit reads an oversized payload into memory, which is the case the servlet filter rejects. Buffers have to be released on both the `413` path and the replay path.
- Setting `spring.ai.mcp.server.type` to `async`, or swapping in the WebFlux MCP starter, drops the current blocking `@Tool` methods. Spring AI 1.1 keeps sync and async tool registration separate.
- Expect the same admission `503`s. WebFlux does not add capacity beyond Hikari and the existing permits.
