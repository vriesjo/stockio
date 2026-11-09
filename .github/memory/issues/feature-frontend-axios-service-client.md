# Feature: Replace the frontend MCP bridge with an Axios service client

## Status and scope

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Frontend Integration & Product Allocation
- **Implementation started:** Yes; implementation and scoped validation are complete.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-replace-the-frontend-mcp-bridge-with-an-axios-service-client)
- **Parent Specs:** [Architecture](../../../docs/architecture.md), [Product allocation REST contract](../../../stockio-api/src/main/resources/openapi/product-allocation-controller.yaml)
- **Scenario:** [Scenario 12](../../../docs/scenarios.md#scenario-12-allocate-a-product-from-the-frontend-through-direct-service-http), building on [Scenario 1](../../../docs/scenarios.md#scenario-1-allocate-a-product-by-name).

## Context and desired behavior

The frontend must not require a separate Spring backend that starts another
Stockio JVM merely to call `allocate_product_by_name`. The user confirmed that
Axios should run inside the existing Next.js route, preserving same-origin
browser requests. `stockio-service` will run independently on port `9090`.

Keep the browser's `POST /api/allocate` interface. Its Next.js handler should
use a small Axios HTTP client to call
`POST http://127.0.0.1:9090/api/products/allocate`, with an environment-configurable
base URL. That existing REST endpoint directly invokes
`productAllocationService.allocate(request)` and returns plain
`ProductAllocationResponse` JSON. Axios cannot call an in-process Kotlin
method; the existing REST adapter provides the requested direct use-case path.
Remove `stockio-service/src/main/kotlin/nl/stockio/product/tools/ProductAllocationTools.kt`
and its `allocate_product_by_name` MCP tool; the product allocation REST
controller and application service remain.

### Separate processes; no fat-JAR dependency

Run the Next.js UI and `stockio-service` as two independently started processes.
The UI communicates with the service only over HTTP on port `9090`. For local
development, start the service through the IDE or `:stockio-service:bootRun`,
and start the UI through its own npm scripts.

Do not package the UI into a service fat JAR, build a fat JAR as a prerequisite
for UI startup, launch `java -jar` from the UI, or retain the bridge's
`:stockio-api:bootRun -> :stockio-service:bootJar` dependency. Shared Kotlin
DTOs can remain a normal library JAR; that is a compile-time dependency, not
a combined executable deployment. Changing optional standalone service
distribution packaging is not required by this feature.

### Previous flow (investigation baseline)

```text
Browser fetch POST /api/allocate
  -> Next.js route (fetch)
  -> Spring stockio-api POST :8082/api/allocate
  -> McpSyncClient / stdio child stockio-service JVM (SERVER_PORT=0)
  -> allocate_product_by_name
  -> ProductAllocationService.allocate(request)
  -> MCP CallToolResult envelope returned to the frontend
```

### Implemented flow

```text
Browser fetch POST /api/allocate (same origin)
  -> existing Next.js route
  -> server-side Axios POST :9090/api/products/allocate
  -> ProductAllocationController.allocate(request)
  -> ProductAllocationService.allocate(request)
  -> plain ProductAllocationResponse JSON returned to the frontend
```

## Implementation outcome

- Added the small server-side Axios client in `ui/lib/product-allocation-client.ts`
  and switched the Next.js route to the existing service REST endpoint.
  HTTP status, content type, and body are preserved without caching or redirects;
  network failures/timeouts return `502`/`504` without automatic retries.
- The frontend consumes validated plain JSON, distinguishes all product
  resolution outcomes, displays service errors, and preserves request identity
  on unchanged manual retries.
- Removed the Spring MCP bridge application/controller/stdio configuration,
  bridge request DTO, application YAML, and bridge-only tests/dependencies.
  Removed `ProductAllocationTools.kt` and its registration, while retaining
  unrelated allocation MCP tools.
- Only the service applies the Spring Boot application plugin. `stockio-api`
  builds a normal DTO JAR and still owns OpenAPI generation. Explicitly aligned
  the Boot BOM's Kotlin version with `kotlin.coreLibrariesVersion` because
  removing Boot from the library otherwise downgraded compiler transitives.
- Updated `make run-api` to start Next.js and `make run-service` to start the
  standalone service with `bootRun`. Environment examples, README, scenarios,
  architecture, and active context reflect separate processes.

### Completed validation

The later [test-harness extraction](./feature-isolate-test-harness.md) moved
browser-test ownership to `stockio-test-harness/e2e/` and its npm package.
Use `make test-e2e` or the harness's `npm run test:e2e` for current browser
checks; the relocated configuration also builds the UI for direct IDE runs.
The commands below record the original feature validation.

- UI `npm test` (19 cases), `npm run typecheck`, and `npm run test:e2e`
  (production build plus the browser flow) passed.
- The default mock-service URL is set in `playwright.config.ts`, not only in
  an npm-script environment, so direct IntelliJ/Playwright launches use the
  same isolated mock backend. Direct IDE runs require an existing UI build;
  they do not require `stockio-service`.
- All three controller OpenAPI specifications validate; service compilation
  consumes their generation tasks successfully.
- Focused `ProductAllocationControllerTest`, `StockioApplicationTest`, and
  `ProductAllocationServiceTest` passed, including REST delegation/error
  mapping and retention of unrelated MCP callbacks.
- Inspected the API JAR and service `bootRun` task graph: plain DTO classes and
  OpenAPI resources are present, without `BOOT-INF`, bridge application YAML,
  or a `bootJar` startup prerequisite.
- Started the real service on `9090` using `bootRun` against a disposable
  PostgreSQL/pgvector database and Next.js separately on `3101`. A live browser
  processed/rendered an exact product allocation and a no-match outcome through
  the Axios route, without a fat-JAR build, bridge, or external product lookup.
  Background workers and RabbitMQ intake were disabled for this isolated smoke
  check; temporary processes and the database were stopped afterward.

## Technical analysis of the pre-implementation code and configuration

Paths below are relative to the repository root. This table records the
investigation baseline; removed files are retained here as migration context,
not as current runtime components.

| Component | Evidence and consequence |
| --- | --- |
| `stockio-api/src/main/kotlin/nl/stockio/api/McpProductAllocationController.kt` | Exposes `/api/allocate`, maps a flat request into nested MCP `request` arguments, and returns `McpSchema.CallToolResult`; obsolete in the planned frontend flow. |
| `stockio-api/src/main/kotlin/nl/stockio/api/McpStdioClientConfig.kt` | Locates the service JAR, starts a Java child process with stdio enabled and `SERVER_PORT=0`, initializes the MCP client, and owns its lifecycle; not a connection to the existing service on `9090`. |
| `stockio-api/src/main/kotlin/nl/stockio/StockioMcpApplication.kt` | Starts the separate Spring bridge application; the frontend should no longer need this runtime. |
| `stockio-api/src/main/resources/application.yml` | Defaults the bridge to `8082`, enables child-server startup, and supplies JAR/Java/MCP timeout settings; retire bridge-only configuration. |
| `stockio-api/src/main/kotlin/nl/stockio/ui/app/api/allocate/route.ts` | Currently uses fetch to forward raw JSON to `STOCKIO_API_URL` (default `8082`) plus `/api/allocate`; validates HTTP(S) URLs, rejects redirects, disables caching, uses a 180-second timeout, and maps network/timeout failures to `502`/`504`. |
| `stockio-api/src/main/kotlin/nl/stockio/ui/app/page.tsx` | Browser fetches the same-origin route, generates request IDs, preserves identity on unchanged retries, and resets identity after edits; HTTP errors currently reference Spring/MCP availability rather than displaying service validation details. |
| `stockio-api/src/main/kotlin/nl/stockio/ui/lib/allocation.ts` | Defines an MCP-named request type and validates results only inside MCP `structuredContent` or text blocks. Must instead validate plain REST JSON while retaining field-level result checks. |
| `stockio-api/src/main/kotlin/nl/stockio/ui/package.json` | Axios is not installed. Add it to this UI package and update its adjacent lockfile, not the unrelated `stockio-api/package-lock.json`. |
| `stockio-service/src/main/kotlin/nl/stockio/product/domain/api/ProductAllocationController.kt` | Implements generated `ProductAllocationApi`; `allocate` directly returns `ResponseEntity.ok(productAllocationService.allocate(request))`. Maps `IllegalArgumentException` to HTTP `400` with `{error: ...}`. |
| `stockio-service/src/main/kotlin/nl/stockio/product/tools/ProductAllocationTools.kt` | `allocate_product_by_name` already delegates to the same application service. Remove this adapter and tool as explicitly requested; retain the REST controller and service. |
| `stockio-service/src/main/kotlin/nl/stockio/product/domain/service/ProductAllocationService.kt` | Owns product resolution, optional external lookup, and delegation to shared allocation processing. All business behavior stays here. |
| `stockio-api/src/main/resources/openapi/product-allocation-controller.yaml` | Defines the existing REST path, flat request, HTTP `200` response, domain statuses, and HTTP `400` errors; use as the client contract rather than inventing another endpoint. |
| `stockio-service/src/main/resources/application.yml` | Already defaults the service to `127.0.0.1:9090`; no port change is needed. |
| Root `build.gradle` | Service has `implementation project(":stockio-api")`, using DTOs now located there. API-owned OpenAPI tasks generate interfaces compiled directly by the service. API `bootRun` also depends on service `bootJar`. Preserve the former responsibilities and retire only bridge-specific wiring. |

### Contract and error behavior

- Send the flat fields `requestId`, `orderId`, `customer`, `productQuery`,
  `quantity`, `priority`, `placedDate`, `requiredDeliveryDate`, `asOfDate`, and
  `allowEquivalent`. Keep UUID strings, positive integers, and ISO date strings;
  do not wrap the payload in MCP tool arguments.
- Read the Axios response's `data` as the plain service result and preserve
  upstream status/body through the Next.js route. Axios normally rejects
  non-2xx responses: deliberately handle response-bearing errors or configure
  status handling so HTTP `400` is not converted into `502`.
- HTTP `200` does not mean an allocation occurred. Preserve
  `NO_PRODUCT_MATCH`, `AMBIGUOUS_PRODUCT_MATCH`, `EMBEDDING_FAILED`, and
  `TAVILY_LOOKUP_FAILED` as domain outcomes with the service's message and
  optional evidence. Only `ALLOCATION_PROCESSED` reports an allocation run.
- Preserve `selectedProduct`, `candidates`, `sources`, and `allocationRun`,
  including line/source identities, partial shortages, and
  `SKIPPED_ORDER_EXPIRED` with nullable line status. Reject incomplete results
  rather than manufacturing a success payload.
- Distinguish upstream HTTP errors from no-response connection failures and
  Axios timeout errors. Keep the existing timeout warning: the service may
  still complete after the HTTP client stops waiting.
- Use the existing `STOCKIO_API_URL` as server-only configuration, changing its
  default/example to `http://127.0.0.1:9090`; do not add `NEXT_PUBLIC_` exposure.
  Keep the existing 180-second timeout, no-store response behavior, and redirect
  rejection. Do not add automatic retries.

## Functional Requirements (FR)

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

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Separate processes without fat-JAR coupling | Run Next.js and the service independently; do not bundle the UI into a fat JAR, require `bootJar` for UI startup, launch a service JVM/JAR from the UI, or add browser-to-service CORS access. |
| NFR-02 | Transport safety | Keep service configuration server-side, validate HTTP(S) URLs without embedded credentials, disable upstream redirects and caching, and preserve the existing 180-second upstream timeout. |
| NFR-03 | Honest unknown outcomes | A timeout or connection loss does not prove allocation failed; warn that processing may continue and retain the original request identity for unchanged manual retries. |
| NFR-04 | Compatibility and build isolation | Preserve the service REST contract, shared Kotlin DTO library dependency, OpenAPI generation, allocation semantics, and unrelated MCP tools; removal of `allocate_product_by_name` is intentional. No schema migration or new business abstraction is required. |

## Implementation plan

1. Add Axios and a server-side product allocation client; update the Next.js route to call the existing service endpoint with the configured base URL, unchanged payload, timeout, and explicit status/error handling.
2. Update frontend request naming, plain REST response validation, service error display, and UI wording while retaining form validation, rendering, and manual retry identity.
3. Remove the obsolete Spring MCP bridge runtime, bridge-only configuration/dependencies/tests, and `ProductAllocationTools.kt`; remove fat-JAR startup coupling, preserve shared DTO/OpenAPI build responsibilities and unrelated MCP tools, and verify service compilation.
4. Update environment/run guidance and focused client, route, parser, and browser tests to use a mock service's `/api/products/allocate` endpoint and plain REST JSON.
5. Run UI unit tests, type checking, production build, and the focused browser flow; validate/generate OpenAPI interfaces and compile/test the affected Kotlin service boundary, then smoke-test with only Next.js and `stockio-service:9090`.

## Functional scenario

Source: [Scenario 12](../../../docs/scenarios.md#scenario-12-allocate-a-product-from-the-frontend-through-direct-service-http).

### Gherkin description
**Given** the Next.js allocation frontend and an independently running `stockio-service` on port `9090` \
**When** a user submits a valid product allocation form to the same-origin `POST /api/allocate` route \
**Then** the Next.js route uses Axios to send the flat request to `POST /api/products/allocate` on `stockio-service` \
**And** `ProductAllocationController` directly invokes `productAllocationService.allocate(request)` \
**And** the frontend renders the plain product allocation response without an MCP envelope \
**And** no separate Spring frontend backend or child service JVM is required \
**And** the UI and service start separately without a fat-JAR build or bundled UI \
**And** `allocate_product_by_name` is no longer exposed as an MCP tool \
**And** an unchanged manual retry preserves the request ID, while editing an attempted request creates a new request ID

### High-level technical analysis
- Keep the existing Next.js route as the same-origin adapter; Axios runs server-side, not in the browser
- Use a small TypeScript Axios client with server-side `STOCKIO_API_URL`, defaulting to `http://127.0.0.1:9090`, and upstream path `/api/products/allocate`
- The REST controller directly delegates to `ProductAllocationService`; `ProductAllocationTools.kt` and `allocate_product_by_name` have been removed
- Return plain `ProductAllocationResponse` JSON; frontend parsing, error text, proxy tests, and browser fixtures use the REST contract
- The obsolete Spring MCP bridge runtime is removed; `stockio-api` remains a normal Kotlin DTO library and owns the OpenAPI documents and Gradle generation tasks used by `stockio-service`
- Start Next.js and the service independently; there is no fat-JAR startup dependency or UI bundled into an executable JAR
- No-store behavior, the 180-second upstream timeout, explicit connection/timeout errors, and manual retry identity are preserved; allocation POSTs are not automatically retried
- The service's allocation, persistence, product lookup, and unrelated MCP interfaces remain unchanged; product allocation uses REST-only access and no browser-to-service CORS change is needed

### Acceptance Criteria
- The frontend works with independently started Next.js and `stockio-service:9090`, without `StockioMcpApplication`, a `bootJar` prerequisite, a bundled UI/service fat JAR, or a child service JVM
- The Next.js route uses Axios to send the unchanged flat request fields to the configured service's `/api/products/allocate` endpoint
- Valid service responses preserve HTTP status and JSON content, including non-allocation domain outcomes returned with HTTP `200`
- The UI displays selected products, candidates, evidence, allocation quantities, line/source identities, skipped-expiry outcomes, and backorders from plain REST JSON
- Invalid response payloads are explicit errors; HTTP `400` exposes the service's error message rather than referring to an unavailable MCP bridge
- Unreachable upstream and timeout conditions return non-success responses (`502` and `504` respectively); timeout messaging warns that allocation may still complete
- No automatic retry occurs; unchanged manual retries preserve `requestId`, and editing an attempted request generates a new ID
- The service's shared DTO compilation and OpenAPI interface generation still work after bridge removal; `ProductAllocationTools.kt` and `allocate_product_by_name` are removed while unrelated MCP tools remain available

## Affected components and boundaries

- **Frontend:** UI `package.json`/adjacent lockfile, a small server-side Axios
  client under `lib/`, `app/api/allocate/route.ts`, `lib/allocation.ts`,
  `app/page.tsx`, `.env.example`, and focused tests.
- **Retired runtime:** the three user-identified Kotlin bridge files,
  bridge-specific request DTO if unused, `stockio-api` application YAML,
  `McpApplicationTests`, and bridge-only Gradle dependencies/startup wiring.
  Adapt API-module packaging only as needed to build shared DTOs without an
  application entry point; do not delete the whole module.
- **Removed product MCP adapter:** delete
  `stockio-service/src/main/kotlin/nl/stockio/product/tools/ProductAllocationTools.kt`
  and adapt/remove only its dedicated tests and registration references.
  Scenario 1 and current architecture/tool documentation now describe
  REST-only product allocation.
- **Inbound service adapter:** existing `ProductAllocationController` and
  generated `ProductAllocationApi`; no new route is needed.
- **Application/domain:** existing `ProductAllocationService` and allocation
  processor, unchanged. No new domain port or policy.
- **Persistence/outbound integrations:** PostgreSQL, repository ports, Tavily,
  Ollama, projection refresh, and backorders remain service-owned and unchanged.
  No migration, messaging change, or browser access to those systems.
- **Documentation:** synchronize this handoff, Scenario 12, the feature
  specification, active context, architecture's planned/current distinction,
  and relevant runtime guidance when implemented.

## Validation plan

- In `stockio-api/src/main/kotlin/nl/stockio/ui`, run `npm test`,
  `npm run typecheck`, `npm run build`, and `npm run test:e2e`.
- Adapt `tests/proxy.test.ts` to assert Axios forwarding to
  `/api/products/allocate`, unchanged JSON, custom base URL, upstream status
  and body preservation, no-store behavior, timeout, and connection failures.
  Include HTTP `400` and `500` so Axios rejection defaults cannot mask them.
- Adapt `tests/allocation.test.ts` to plain results: processed allocation,
  non-allocation domain outcomes, invalid shapes, line/source identities, and
  skipped-expiry behavior. MCP envelope parsing is no longer the frontend
  contract.
- Adapt `e2e/allocation.spec.ts` to serve plain REST responses and assert the
  upstream service path. Keep a dedicated mock port rather than contacting
  the real `9090` service; preserve identity-on-retry and identity-after-edit
  checks, and replace MCP error fixtures with REST domain/HTTP failures.
- Validate/generate the allocation, reservation, and product OpenAPI documents
  through the existing `:stockio-api:openApiValidate`,
  `:stockio-api:openApiValidateReservation`,
  `:stockio-api:openApiValidateProductAllocation`,
  `:stockio-api:openApiGenerateAllocation`,
  `:stockio-api:openApiGenerateReservation`, and
  `:stockio-api:openApiGenerateProductAllocation` tasks.
- Run `:stockio-service:compileKotlin` and
  `:stockio-service:test --tests nl.stockio.product.domain.api.ProductAllocationControllerTest`
  after bridge/build cleanup. The focused controller test already verifies
  the direct service call and HTTP `400` mapping.
- Smoke-test the actual Next.js route against independently running
  `stockio-service:9090` started through the IDE or `bootRun`, with safe test
  data and without building a fat JAR or using the Spring bridge/JAR launcher.
  Start Next.js separately and confirm rendering of plain service responses.
- Verify that `allocate_product_by_name` is no longer registered and that
  unrelated service MCP tools remain registered.
- Review documentation links, scenario/FR/NFR/plan synchronization, and
  `git diff --check`.

## Risks, compatibility, and scope exclusions

- Deleting `stockio-api` wholesale would break shared Kotlin DTO imports and
  OpenAPI generation. Its runtime and build-time responsibilities differ;
  audit remaining usage before removing dependencies or changing packaging.
- Existing frontend tests assume MCP envelopes and upstream `/api/allocate`;
  a transport-only change would leave parsing and browser fixtures broken.
- Axios's non-2xx rejection and timeout classification differ from fetch.
  Preserve service errors rather than treating every rejection as unreachable.
- A timeout cannot cancel or roll back service allocation. Preserve request
  IDs and avoid automatic retries; do not promise exactly-once execution.
- The target removes the bridge's `/api/allocate` on port `8082`, not the
  browser-facing Next.js `/api/allocate`. Old direct bridge consumers must
  migrate to the existing service REST endpoint.
- Removing `allocate_product_by_name` intentionally breaks MCP callers of
  that tool; migrate them to `POST /api/products/allocate`. Other service-side
  MCP tools and stdio support are outside removal scope. No
  browser-direct Axios, CORS expansion, generated npm package/publication,
  new REST endpoint, allocation algorithm change, or database change is needed.
- Priority remains unset. The HTTP flow
  decision is resolved: Axios belongs in the existing Next.js route.
