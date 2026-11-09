# Feature: Add an allocation performance harness for job endpoints

- **Status:** In-progress
- **Priority:** Not set
- **Bounded Context:** Test Infrastructure & Performance Validation
- **Implementation started:** Yes. Initial Gatling harness implementation is in progress.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-add-an-allocation-performance-harness-for-job-endpoints)
- **Parent Specs:** [Architecture](../../../docs/architecture.md), [Feature: Isolate browser tests and synthetic publishing in a test harness](../../../docs/specifications.md#feature-isolate-browser-tests-and-synthetic-publishing-in-a-test-harness)
- **Scenario:** [Scenario 19](../../../docs/scenarios.md#scenario-19-exercise-allocation-job-rest-endpoints-under-controlled-load)

## Context and scope

`stockio-test-harness/performance/README.md` currently contains a generic prompt
requesting a production-ready Gatling suite. That seed is useful, but it
overreaches in several areas for current repository constraints. This feature
turns the prompt into repository-owned, testable, and honest performance
infrastructure.

In scope:
- a harness-owned Gatling Kotlin suite targeting allocation-job REST endpoints;
- Gatling's built-in async HTTP client via the DSL (no generated runtime webclient);
- request paths/payload fields derived from `stockio-api/src/main/resources/openapi/allocation-controller.yaml`;
- parameterized environment and load-shape configuration;
- reproducible result artifacts and explicit assertions.

Out of scope:
- production-capacity guarantees from local runs;
- mandatory authentication behavior without confirmed service auth;
- introducing a separate generated webclient runtime for load execution.

## Challenge points from the seed README

1. **Client choice should stay transparent for load diagnostics.** Use Gatling's
   async DSL HTTP client directly and treat the OpenAPI file as the contract
   source for request/response structure, not as a generated runtime client.
2. **Static SLA thresholds need environment binding.** Hardcoded percentile
   limits are useful as defaults but should be parameterized and interpreted
   against environment class and run purpose.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Harness-owned suite | Add Gatling Kotlin sources/config under `stockio-test-harness/performance`, with Gradle tasks that keep service/UI runtime dependencies one-way. |
| FR-02 | Endpoint flow fidelity | Execute `POST /api/allocation/jobs` and subsequent `GET /api/allocation/jobs/{id}` polling with request/response correlation. |
| FR-03 | OpenAPI contract source | Derive endpoint paths and payload/response field expectations from `stockio-api/src/main/resources/openapi/allocation-controller.yaml` and keep them synchronized. |
| FR-04 | Gatling async DSL client | Implement traffic with Gatling's built-in async HTTP DSL client rather than a generated runtime webclient. |
| FR-05 | Configurable profile | Support warm-up/ramp/soak defaults from the seed request, overrideable via env/JVM properties. |
| FR-06 | Realistic data variation | Use feeder-driven payload variation and avoid single-static-body artifacts that distort caching/path behavior. |
| FR-07 | Explicit checks and assertions | Validate expected status codes/response shape and expose configurable percentile/success-rate assertions. |
| FR-08 | Reproducible outputs | Persist effective config + reports per run so observed behavior is auditable and comparable. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Honest claims | Documentation and reports must state environment scope; no production-readiness claim without representative infrastructure runs. |
| NFR-02 | Safety | Defaults must target dedicated non-production systems only and avoid accidental overload of shared developer resources. |
| NFR-03 | Debuggability | Scenario structure should remain modular and inspectable (protocol, feeders, actions, scenario, setup), with enough visibility to diagnose bottlenecks. |

## Implementation plan

1. Replace prompt-only README content with repository-specific assumptions,
   endpoints, run modes, and risk notes.
2. Wire Gatling Kotlin execution in the harness build and add modular simulation
   structure for protocol/actions/scenario/setup using Gatling's async HTTP DSL.
3. Implement create-job + poll-job flow with ID extraction and configurable auth
   header behavior, aligned with `allocation-controller.yaml`.
4. Parameterize profile and assertion thresholds; store effective run parameters
   alongside Gatling output artifacts.
5. Add Make/CLI execution guidance for local and CI use; run initial baseline
   measurements and document limitations.
6. Synchronize scenario/specification/active context after first implemented run.

## Functional scenario

### Gherkin description
**Given** a dedicated non-production Stockio environment and configured run profile \
**When** the harness executes staged load against allocation-job create and poll endpoints \
**Then** throughput, latency, and success metrics are captured with explicit assertion outcomes \
**And** environment assumptions and unsupported guarantees are documented with the run

### Acceptance Criteria

- The suite runs from `stockio-test-harness` with documented commands and config overrides.
- Both create and poll endpoints are exercised in one scenario flow with correlated IDs.
- Workload phases and assertion limits are externally configurable and recorded.
- Reports clearly distinguish pass/fail assertions and environment context.
- Documentation avoids unsupported production guarantees and captures known gaps.
