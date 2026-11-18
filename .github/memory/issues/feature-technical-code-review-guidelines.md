# Feature: Establish a technical code review guideline baseline for high-throughput event-driven systems

- **Status:** In-progress
- **Priority:** Not set
- **Bounded Context:** Engineering quality governance
- **Implementation started:** Yes. Initial guideline baseline is drafted in repository docs.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-establish-a-technical-code-review-guideline-baseline-for-high-throughput-event-driven-systems)
- **Parent Specs:** [Architecture](../../../docs/architecture.md), [Backpressure and admission control](../../../docs/arch_backpressure_admission_control.md)
- **Scenario:** [Scenario 23](../../../docs/scenarios.md#scenario-23-review-high-throughput-event-driven-changes-with-a-reusable-guideline-baseline)

## Context and scope

Stockio has strong technical constraints across ingestion reliability,
idempotency, lock ordering, admission control, and operational observability.
Those constraints exist today across architecture/scenario/specification files
and implementation issue handoffs, but there is no single review baseline for
future PRs.

This feature defines a reusable, repository-owned guideline baseline for code
reviews in similar architectures: Spring Boot services, Kafka/RabbitMQ event
ingress, PostgreSQL persistence/locking, and high-throughput contention
management.

In scope:
- topic taxonomy for technical code reviews;
- per-topic actionable checks and red flags;
- required review evidence for correctness/reliability/performance claims;
- separation of Stockio-specific invariants from reusable cross-project rules.

Out of scope:
- runtime behavior or API changes;
- automatic lint/check tooling for review rules (can be a later feature);
- organization-wide process policy beyond repository-owned engineering guidance.

## Technical analysis of current repository baseline

1. **Architecture boundaries are explicit but distributed.** Module boundaries
   and hexagonal responsibilities are documented, yet review checks are not
   centralized.
2. **Reliability semantics are nuanced.** Kafka/RabbitMQ + JobRunr paths,
   durable status tracking, and ambiguous publish outcomes require explicit
   wording discipline during reviews.
3. **Concurrency correctness depends on ordering invariants.** Sorted document
   and SKU lock ordering, plus run/lifecycle locking behavior, are critical and
   must be preserved in change review.
4. **Throughput controls are mostly opt-in and context-sensitive.** Admission
   limits and lock-contention controls require evidence-driven tuning and honest
   guarantees.
5. **Claims need evidence standards.** Existing docs already emphasize no
   unsupported performance/exactly-once guarantees; review guidance should make
   this a mandatory check.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Guideline topic taxonomy | Define stable review topics covering architecture boundaries, event-ingress reliability, idempotency/replay safety, PostgreSQL locking/transaction semantics, backpressure/admission, contention handling, and operability. |
| FR-02 | Actionable checks per topic | Provide concrete review checks and red-flag conditions for each topic, not only principles. |
| FR-03 | Evidence expectations | Define minimum required evidence for high-risk claims (tests, metrics artifacts, failure-mode validation, rollout notes). |
| FR-04 | Hybrid portability | Keep guidance reusable for similar systems while preserving Stockio-specific invariants and semantics. |
| FR-05 | Claim hygiene | Require rejection of unsupported guarantees and success-shaped language for ambiguous processing/delivery outcomes. |
| FR-06 | Audience coverage | Keep the baseline usable by both PR reviewers and PR authors (self-check). |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Technical correctness | Guidance must align with current documented architecture and implemented behavior. |
| NFR-02 | Brevity with depth | Keep the baseline concise enough for PR use while preserving rationale for high-risk decisions. |
| NFR-03 | Maintainability | Use a structure that can evolve with future architecture/features without full rewrites. |

## Implementation plan

1. Consolidate repository constraints and reliability semantics from docs and active context.
2. Distill recurring review-risk hotspots into a stable taxonomy.
3. Draft per-topic checks, anti-patterns, and required evidence expectations.
4. Separate repository-specific invariants from portable guidance for similar stacks.
5. Publish synchronized scenario/specification/issue links and capture next implementation steps.

## Delivered baseline (increment 1)

- Published first guideline baseline:
  [Technical code review guidelines](../../../docs/technical-code-review-guidelines.md)
- Includes minimum evidence expectations, must-review technical areas, red flags,
  Stockio-specific invariants, and a reviewer verdict template.
- Designed for both PR reviewers and PR authors as a practical checklist.

## Functional scenario

### Gherkin description
**Given** a pull request changes Stockio in high-risk technical areas \
**When** a reviewer applies the guideline baseline \
**Then** the review validates architecture boundaries, reliability semantics, and contention behavior with explicit checks \
**And** required evidence is requested before approving correctness/performance claims \
**And** unsupported guarantees are rejected

### Acceptance Criteria

- The guideline baseline explicitly covers architecture boundaries, Kafka reliability/replay safety, PostgreSQL locking/transaction correctness, and high-throughput backpressure/contention behavior.
- Each topic includes concrete checks and anti-patterns.
- The baseline distinguishes Stockio-specific invariants from reusable recommendations.
- Required review evidence is explicit for performance/reliability claims.
- Wording guidance rejects unsupported guarantees and success-shaped ambiguous outcomes.

### Current increment status

- Initial checklist baseline delivered.
- Next increment can add examples from recent PRs and optional automation/templates.
