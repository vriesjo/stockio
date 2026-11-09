# Feature: Mitigate synchronous allocation lock contention with fail-fast admission

- **Status:** In-progress
- **Priority:** Not set
- **Bounded Context:** Allocation Application Orchestration & Concurrency Control
- **Implementation started:** Yes. Initial contention fail-fast implementation is in progress.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-mitigate-synchronous-allocation-lock-contention-with-fail-fast-admission)
- **Parent Specs:** [Architecture](../../../docs/architecture.md), [Fail-fast bulkhead](../../../docs/specifications.md#feature-bound-allocation-execution-with-a-fail-fast-bulkhead), [Per-SKU protocol](../../../docs/specifications.md#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Scenario:** [Scenario 21](../../../docs/scenarios.md#scenario-21-fail-fast-under-allocation-lock-contention)

## Context and scope

Recent controlled load runs on synchronous allocation report long response times
under moderate concurrency escalation. The observed pattern is high latency with
little/no request failure, which indicates queueing and waiting under lock/pool
pressure rather than deliberate fail-fast overload behavior.

This feature defines the next increment to reduce contention impact:
1. **Fail fast under overload** using explicit admission limits aligned to measured
   database capacity.
2. **Reduce lock-holding residence time** by narrowing lock-critical transaction
   sections while preserving existing lock ordering and recovery contracts.

In scope:
- synchronous allocation admission policy for `async=false` execution paths;
- lock-holding critical section review in registration/SKU/projection/completion
  orchestration;
- explicit lock wait/statement timeout and retry policy;
- observability and contention validation updates.

Out of scope:
- replacing the existing lock hierarchy;
- introducing distributed/global permits;
- durable queue redesign, fairness/tenant quotas, or new endpoints;
- claims of fixed production throughput/latency without representative evidence.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Explicit fail-fast operation | Define and enforce an approved fail-fast admission profile for synchronous allocation paths so overload rejects quickly instead of queueing behind lock contention. |
| FR-02 | Capacity-aligned limits | Set in-flight limits from measured database and lock behavior, not static defaults; include per-instance and replica-budget guidance. |
| FR-03 | Preserve lock protocol | Keep existing sorted document/SKU/run-or-lifecycle lock ordering unchanged while implementing contention mitigation. |
| FR-04 | Shorter lock-holding sections | Reduce time spent in lock-holding transactional sections by moving non-essential work outside those sections where correctness permits. |
| FR-05 | Timeout and retry policy | Define explicit lock-wait/statement timeout and retry behavior that distinguishes capacity rejection, validation failure, and partial-progress recovery. |
| FR-06 | Adapter-consistent overload handling | Keep inline HTTP, MCP, and background execution overload behavior explicit and recoverable without fallback storms or false success semantics. |
| FR-07 | Contention observability | Expose and review admission outcomes, rejection ratio, p95/p99 latency, lock waits, pool pressure, and completion throughput for tuning decisions. |
| FR-08 | Controlled rollout | Enable only after representative contention testing, documented operational limits, and synchronized behavior documentation. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Correctness first | Preserve allocation correctness, idempotency, FIFO/priority behavior, reservation semantics, and projection/ledger compatibility. |
| NFR-02 | No hidden queues | Do not introduce implicit permit wait queues or unbounded executor backlogs as a substitute for explicit admission policy. |
| NFR-03 | Honest guarantees | Document per-instance control boundaries clearly; avoid claims of global throughput or guaranteed latency without representative evidence. |
| NFR-04 | Low-cardinality metrics | Keep observability labels bounded; avoid identity-bearing tags. |

## Technical analysis and affected components

| Component | Current behavior | Planned focus |
| --- | --- | --- |
| `AllocationProcessor` / `AllocationAdmission` | Admission exists but is opt-in; contention can still accumulate when limits are disabled/misaligned. | Operationalize fail-fast mode for synchronous contention tests and production-like profiles. |
| `AllocationService` + `AllocationDocumentService` + `AllocationSkuWorker` | Uses sorted document/SKU locking and bounded per-SKU workers, but synchronous orchestration can accumulate waiting. | Preserve lock order while minimizing time spent inside lock-holding sections. |
| `StockProjectionProcessor` + completion persistence | Runs in the synchronous path after allocation workers. | Identify non-lock-critical work that can be shifted to reduce lock residency without breaking consistency contracts. |
| HTTP/MCP/RabbitMQ/JobRunr adapters | Already map capacity differently by entry point. | Keep overload semantics explicit and consistent under tuned fail-fast policy. |
| Metrics and harness reports | Existing metrics and Gatling artifacts show latency symptoms. | Add explicit before/after contention evidence for admission, locks, pool pressure, and throughput. |

## Implementation plan

1. Baseline contention behavior for synchronous allocation using current harness artifacts and service metrics (latency percentiles, lock waits, pool pressure, throughput).
2. Approve an operational admission profile (enabled flag + in-flight limits + rejection semantics) for representative environments.
3. Identify lock-holding sections in registration/SKU/projection/completion flow and move safe non-lock-critical work outside those sections.
4. Add explicit lock-wait and statement-timeout policy plus retry guidance for inline/background callers, preserving partial-progress recovery semantics.
5. Validate with focused contention scenarios (independent/hot SKUs, projection pressure, mixed callers) and compare before/after outcomes.
6. Synchronize scenario/specification/active-context and service guidance before rollout.

## Functional scenario

### Gherkin description
**Given** synchronous allocation (`POST /api/allocation/jobs?async=false`) runs with measured SKU/document lock contention \
**And** bounded admission is enabled with approved in-flight limits \
**When** incoming concurrency exceeds the configured admitted execution budget \
**Then** non-admitted requests are rejected quickly with explicit capacity errors instead of waiting for long lock/connection queues \
**And** admitted requests preserve existing lock ordering and idempotency semantics while minimizing time spent inside lock-holding transactional sections \
**And** lock wait budgets and retry behavior are explicit and recoverable for inline and background callers

### Acceptance Criteria

- Under configured contention tests, excess synchronous requests fail fast with explicit retryable capacity responses instead of long-tail waiting behavior.
- Existing lock hierarchy (sorted document identities, sorted SKUs, then run/lifecycle/business rows) is preserved and verified.
- Admitted execution path reduces lock-holding residence time for non-essential work without changing allocation correctness rules.
- Lock-wait and statement-timeout policies are explicit, tested, and do not silently mask partial-progress recovery conditions.
- Metrics and run artifacts show before/after impact on p95/p99 latency, rejection ratio, lock waits, and completed throughput.
- Documentation clearly separates local/per-instance admission behavior from cluster-wide or physical database guarantees.
