# Feature: Generate OpenAPI specifications and interfaces for REST controllers

## Status and scope

- **Status:** Done for `AllocationController`, `ReservationController`, and the user-selected `ProductAllocationController`.
- **Priority:** Not set
- **Bounded Context:** REST API / selected controller
- **Specification:** [`../../../docs/specifications.md`](../../../docs/specifications.md#feature-generate-openapi-interfaces-for-rest-controllers)
- **Implementation scenarios:** [Scenario 1](../../../docs/scenarios.md#scenario-1-allocate-a-product-by-name), [Scenario 2](../../../docs/scenarios.md#scenario-2-allocate-structured-new-demand), [Scenario 3](../../../docs/scenarios.md#scenario-3-monitor-an-allocation-job), [Scenario 5](../../../docs/scenarios.md#scenario-5-inspect-an-order-reservation), and [Scenario 7](../../../docs/scenarios.md#scenario-7-monitor-stock-ledger-freshness). Determine applicable scenarios after the user selects another controller.

At the start of each new controller run, ask: **"Which controller should I use?
Please provide its source path or class name."** Do not assume
`AllocationController` or select a controller on the user's behalf. Inspect
the chosen controller and its tests, then create an OpenAPI specification from
its existing routes, parameters,
request and response DTOs, validation, status codes, and observable behavior.
Place a new specification in
`stockio-api/src/main/resources/openapi/` using a controller-specific filename,
or update the corresponding existing specification when one already exists.

Only after the specification reflects the existing implementation should the
Kotlin Spring API interface be generated with the OpenAPI Generator Gradle
plugin. The selected controller should implement that interface, and its
external HTTP behavior must remain unchanged. The generated API surface should
be compiled with the server implementation rather than maintaining independent
Spring mappings and signatures.

This is a contract-preserving build and implementation change, not a new
user-facing flow. Do not update scenario text unless the selected controller's
supported behavior is intentionally changing; if it is, update the affected
scenario and linked feature documentation as well.

## Current state

- The initial reference implementation is
  `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt`
  and its specification is
  `stockio-api/src/main/resources/openapi/allocation-controller.yaml`.
- Most recently selected and now completed target:
  `stockio-service/src/main/kotlin/nl/stockio/product/domain/api/ProductAllocationController.kt`.
- Its existing route is `POST /api/products/allocate`; its specification is
  `stockio-api/src/main/resources/openapi/product-allocation-controller.yaml`.
  The generated `ProductAllocationApi` is consumed from
  `stockio-api/build/generated/openapi/product-allocation`.
- The initial allocation specification describes four routes: submit
  allocation, read job status, read ledger status, and read order-reservation
  status.
- The initial controller returns service DTOs and `ResponseEntity` values. Its
  `async=false` inline path returns `202` but uses an allocation run ID as
  `jobId` without creating a pollable JobRunr job.
- For a newly selected controller, identify its DTO ownership and validation
  before deciding whether models should be generated or mapped to existing
  types; blindly generating models can create duplicate API types.
- The root Gradle build uses Gradle 8.14.3, Kotlin 2.2.20, and Spring Boot
  3.5.10. OpenAPI Generator 7.26.0 is configured in `project(":stockio-api")`.
- `stockio-api` now builds a normal shared DTO library after the
  [Axios frontend integration](./feature-frontend-axios-service-client.md)
  removed its Spring MCP client runtime. The service depends on that library
  for DTOs, but still compiles generated REST interfaces directly from build
  output rather than obtaining them through the library artifact.
- Kotlin Spring interface generation writes only the API interface under
  `stockio-api/build/generated/openapi`; model generation is disabled and the
  interface references existing service DTOs.
- The service's Kotlin source set includes the generated source directory and
  `compileKotlin` depends on `:stockio-api:openApiGenerate`; the generated
  interface is not compiled into or published by the MCP client module.
- `AllocationController` implements the generated interface, whose annotations
  provide the route mappings. The controller no longer declares duplicate
  Spring route annotations.
- Focused standalone MockMvc tests cover the initial allocation controller's
  submission and status routes, including the default async behavior,
  `Location` header, and missing-reservation response.
- `ReservationController` implements generated `ReservationApi` from
  `stockio-api/build/generated/openapi/reservation`; the service compiles this
  source directory through the dedicated generation task dependency.
- A focused standalone MockMvc test verifies the reservation route, response
  data, and existing `404` behavior.
- `ProductAllocationController` implements generated `ProductAllocationApi`;
  the specification reuses the allocation-run schema and maps existing product
  DTOs rather than generating duplicate models.

## Implementation outcome

- The generation task is owned by `project(":stockio-api")` and outputs under
  `stockio-api/build/generated/openapi`; the service compiles the generated
  source directly without depending on the MCP client artifact.
- `openApiValidateReservation` and `openApiGenerateReservation` validate and
  generate the reservation contract into its separate output directory.
- `openApiValidate`, `openApiGenerate`, forced Kotlin compilation, and focused
  controller mapping tests pass.
- `openApiValidateReservation`, `openApiGenerateReservation`, service
  compilation, and `ReservationControllerTest` pass.
- `openApiValidateProductAllocation` and `openApiGenerateProductAllocation`
  validate and generate the product interface under
  `stockio-api/build/generated/openapi/product-allocation`.
- The service compiles the product generated source, and
  `ProductAllocationControllerTest` verifies the route, response, and existing
  `400` behavior.
- The existing Testcontainers integration-test class could not initialize in
  this environment because Docker is unavailable. Focused MockMvc tests exercise
  generated interface mappings without Docker.

## Recommended design

### Generator and source ownership

- Use the `kotlin-spring` generator, not `spring`: the server is Kotlin and
  Spring MVC. Configure interface-only generation, Spring Boot 3 / Jakarta
  annotations, Bean Validation, and `ResponseEntity` signatures.
- First inspect the user-selected controller and create or update its OpenAPI
  specification from the implementation. The spec is the source for code
  generation, not a substitute for examining actual controller behavior.
- Keep each OpenAPI specification under
  `stockio-api/src/main/resources/openapi/`. Generate code under a Gradle
  `build/` directory, do not commit generated sources, and wire generation into
  service compilation so a clean build works.
- Configure generation in `project(":stockio-api")` using the selected
  controller's specification, with output under
  `stockio-api/build/generated/openapi/<controller>/` when multiple controller
  interfaces are generated.
- The generated interface refers to DTOs owned by `stockio-service`, so do not
  compile that generated file as part of the MCP client module. Instead,
  register the generated source directory in the service's Kotlin source set
  and make `:stockio-service:compileKotlin` depend on
  `:stockio-api:openApiGenerate`. This keeps the service off the MCP client
  artifact while satisfying the requested task ownership and output location.

### Gradle configuration sketch

Configure the generator and validator in `project(":stockio-api")` for the
controller selected by the user, and consume its generated source from the
service build. Replace the controller-specific values below after selection.
Keep output and package names unique if multiple interfaces are generated:

```groovy
project(":stockio-api") {
    def controllerOpenApiSpec = rootProject.file(
        "stockio-api/src/main/resources/openapi/<controller-spec>.yaml"
    )
    def generatedControllerApi = layout.buildDirectory.dir(
        "generated/openapi/<controller>"
    )

    openApiGenerate {
        generatorName.set("kotlin-spring")
        inputSpec.set(controllerOpenApiSpec)
        outputDir.set(generatedControllerApi.get())
        apiPackage.set("nl.stockio.<bounded-context>.domain.api.generated")
        modelPackage.set("nl.stockio.<bounded-context>.domain.api.generated.model")
        // Configure mappings and Kotlin Spring options for the selected controller.
    }

    openApiValidate {
        inputSpec.set(controllerOpenApiSpec)
    }
}

project(":stockio-service") {
    sourceSets.main.kotlin.srcDir(
        project(":stockio-api").layout.buildDirectory.dir(
            "generated/openapi/<controller>/src/main/kotlin"
        )
    )
    tasks.named("compileKotlin") {
        dependsOn(project(":stockio-api").tasks.named("openApiGenerate"))
    }
}
```

The root plugin version remains centrally pinned. The exact Gradle task type,
task names, generator options, source folders, imports, and mappings must be
verified against the selected controller and generated output. Avoid duplicate
request mappings and do not compile duplicate generated DTOs.

### Generated models and controller implementation

The generator normally produces model classes as well as API interfaces. After
the user selects a controller and its OpenAPI document is created, choose the
model strategy based on that controller's existing DTO ownership:

1. Generate API models and map them at the controller boundary to the existing
   request/response DTOs; or
2. configure supported import mappings so generated interface signatures use
   the existing DTOs and generated duplicate models are suppressed.

Do not maintain generated and hand-written classes with the same contract but
different validation or nullability. Verify generated signatures against the
existing `AllocationRequest`, `AllocationJobAccepted`, `AllocationJobStatus`,
`OrderReservationResponse`, and `StockLedgerSyncStatus` types.

The allocation OpenAPI operation IDs (`submitAllocationJob`,
`getAllocationJobStatus`, and `getAllocationStockLedgerStatus`) match
`AllocationController`. `ReservationController` implements
`getOrderReservationStatusByOrderId` and `getOrderReservationStatusByRedisKey`. When
adding another interface, align operation IDs
with the method names or rename the implementation when adding `override`.
Configure mapping annotations on one layer only to avoid duplicate or ambiguous
Spring routes.

## Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | User-selected controller | Ask the user which existing controller to use. Work only on the controller they identify; do not assume `AllocationController` or choose another controller without confirmation. |
| FR-02 | Specification-first workflow | Inspect the selected controller implementation and relevant tests, then create or update its OpenAPI specification before generating an interface. |
| FR-03 | Behavior-accurate specification | Describe the selected controller's actual paths, parameters, request and response schemas, validation, status codes, headers, and observable behavior. Do not invent behavior or silently change the contract. |
| FR-04 | Kotlin Spring interface generation | Generate an interface-only Kotlin Spring API from the selected specification with Boot 3-compatible annotations and signatures appropriate to the controller's responses. |
| FR-05 | Controller implementation | Make the selected controller implement its generated interface while preserving existing external behavior and removing duplicate mappings only where the generated interface supplies them. |
| FR-06 | Model ownership | Select generation or import mappings based on the chosen controller's existing DTOs; avoid duplicate or divergent API models. |
| FR-07 | Reproducible Gradle generation | Keep the plugin version pinned, generate under `build/`, use controller-specific task/output/package names where needed, and wire generation into service compilation. |

## Non-Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | API compatibility | The generated interface and specification must preserve the selected controller's current HTTP paths, payload contract, validation, and observable response behavior. |
| NFR-02 | Clean-build reproducibility | A clean checkout must generate the selected interface and compile without committed generated source files. |
| NFR-03 | Module boundaries | The service must not depend on the MCP client artifact solely to obtain server interfaces; compile generated sources directly into the service. |
| NFR-04 | Maintainability | Keep the generator version and options explicit, avoid task/output collisions, and use the controller-specific OpenAPI specification as the source for generated routes. |

## Implementation plan

1. Ask the user, "Which controller should I use? Please provide its source
   path or class name," and use that controller rather than choosing one.
2. Inspect the selected controller, DTOs, tests, exception handling, and related
   configuration to establish its existing observable API behavior.
3. Create or update a controller-specific OpenAPI specification under
   `stockio-api/src/main/resources/openapi/`; review it against the implementation
   before code generation.
4. Configure a controller-specific `openApiValidate` and `openApiGenerate` task
   in `project(":stockio-api")`, mapping existing DTOs or generating models as
   appropriate.
5. Add the generated source directory and task dependency to the service build,
   then make the user-selected controller implement the generated interface
   without changing its external behavior.
6. Add focused tests for the selected controller's generated route mappings and
   behavior; run specification validation, generation, Kotlin compilation, and
   relevant controller tests.

## Acceptance criteria

- The user selects the controller before implementation begins.
- The selected controller's current implementation and tests are inspected
  before a matching OpenAPI specification is created or updated.
- The specification is validated before its generated Kotlin Spring interface
  is used to implement the selected controller.
- Generated sources live under a `build/` directory, are not committed, and are
  compiled automatically from a clean checkout.
- The selected controller implements the generated interface without duplicate
  or ambiguous Spring request mappings.
- The selected controller's existing paths, parameters, payload schemas,
  status codes, headers, validation, and observable behavior remain unchanged.
- The generated interface is not compiled into or published by the MCP client
  module; the service compiles the generated source directly without depending
  on the MCP client artifact.

## Risks and remaining considerations

- **Generated model ownership — resolved:** model generation is disabled and
  import mappings reuse the existing DTOs; `ErrorResponse` maps to the Kotlin
  `Map` type because the generated interface does not use it in a method
  signature.
- **Generated interface location — resolved:** generation is owned by
  `:stockio-api`, but the generated source is compiled only in
  `:stockio-service`.
- **Reservation controller integration — resolved:** the user-selected
  controller implements `ReservationApi`, generated from its implementation-
  derived OpenAPI spec; existing route and not-found behavior are preserved.
- **Spring annotation placement — resolved:** generated interface mappings are
  the only route mappings for `AllocationController`.
- **Validation parity:** Generated Bean Validation annotations must agree with
  runtime validation, including date and uniqueness rules enforced by the
  application after enqueue.
- **Initial example:** `AllocationController` has a documented `async=false`
  limitation: its returned `Location` is not pollable. This behavior is
  preserved in the initial integration; a different user-selected controller
  requires its own behavior analysis.
- **Generator version:** The draft's sample version `7.8.0` should not be copied
  without compatibility review. Version `7.26.0` is pinned centrally and
  validated with the repository's Gradle wrapper, Kotlin, and Spring Boot
  versions.

## Resources

- [Initial OpenAPI example](../../../stockio-api/src/main/resources/openapi/allocation-controller.yaml)
- [Architecture](../../../docs/architecture.md)
- [Scenario 2: Allocate Structured New Demand](../../../docs/scenarios.md#scenario-2-allocate-structured-new-demand)
- [Scenario 3: Monitor an Allocation Job](../../../docs/scenarios.md#scenario-3-monitor-an-allocation-job)
- [Scenario 5: Inspect an Order Reservation](../../../docs/scenarios.md#scenario-5-inspect-an-order-reservation)
- [Scenario 7: Monitor Stock-Ledger Freshness](../../../docs/scenarios.md#scenario-7-monitor-stock-ledger-freshness)
- [OpenAPI Generator Gradle plugin documentation](https://openapi-generator.tech/docs/plugins/#gradle)
- [Kotlin Spring generator options](https://openapi-generator.tech/docs/generators/kotlin-spring/)
