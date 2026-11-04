# Feature: Bound allocation execution with a fail-fast bulkhead

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Allocation Application Orchestration & Ingress Adapters
- **Implementation started:** Yes. Complete, at the user's request.
- **Parent Specs:** [Architecture](../architecture.md#cross-cutting-concurrency-idempotency-and-rollout), [Per-SKU stock processing](../specifications.md#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Specification:** [Feature specification](../specifications.md#feature-bound-allocation-execution-with-a-fail-fast-bulkhead)
- **Scenario:** [Scenario 16](../scenarios.md#scenario-16-reject-allocation-work-when-execution-capacity-is-exhausted)

## Context and scope

The completed per-SKU protocol allows independent stock work to overlap without
the former global projection gate. It does not bound admitted requests.
Registration and scoped projection run outside the allocation dispatcher, and
multiple callers can create pending SKU tasks while holding HTTP/job threads.
Hikari is currently the eventual connection-pressure boundary, not a deliberate
admission policy.

This increment rejects excess allocation execution before allocation business
writes, retains capacity until synchronous processing actually ends, bounds
incoming request shape, and exposes measurements for tuning. This is a
per-instance semaphore bulkhead around allocation orchestration, not a
database-transaction limiter, thread-pool bulkhead, rate limiter, or bounded
durable queue.

## Approved implementation policies

- Opt in explicitly; require positive concurrency, count and byte limits when enabled rather than inventing production values.
- HTTP capacity rejection is 503, count-limit rejection is 400 and byte-limit rejection is 413. Do not add automatic retries or `Retry-After`.
- Defer saturated RabbitMQ/JobRunr execution without generic fallback storms or consuming business retries; preserve recoverable work and request identity.
- Preserve request dates, hold deadlines and existing per-SKU transaction boundaries. Drain/reconcile pre-existing oversized work before enabling.
- Product-resolution admission, database-wide execution budgets and bounded durable intake remain outside this feature. Priority remains unset.

In scope: structured HTTP allocation, the allocation phase of product HTTP,
allocation MCP tools, inline RabbitMQ processing, JobRunr allocation execution
and receipt-triggered backorders. Adapter overload handling is part of this
feature, even though a general durable-backlog cap is not.

Out of scope: distributed permits, caller quotas, a shared database-work budget,
hard maintenance reservations, asynchronous forecast refresh, virtual threads,
R2DBC, larger pools, new endpoints, and correcting the existing synchronous
allocation `202`/polling mismatch. Product resolution and its external calls
remain outside the allocation permit; an end-to-end product front-door budget
is a separate proposal. No capacity or latency target is claimed without a
representative benchmark.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Shared fail-fast admission | Use one configurable per-instance allocation execution limit across participating entry points; attempt admission without a waiting queue and before allocation run/document/reservation writes. |
| FR-02 | Complete permit lifetime | Retain one permit through allocation, synchronous affected-SKU projection and execution-result persistence where applicable; release exactly once only after the actual work stops, on success or failure. |
| FR-03 | Explicit overload responses | Return HTTP 503 with a stable capacity-error code for rejected inline execution; provide distinct retryable MCP errors and safe durable-worker deferral without silently converting rejected HTTP work to asynchronous work. |
| FR-04 | Bounded request shape | Enforce approved order/shipment counts, total demand/inbound line counts and distinct-SKU limits before allocation writes or task fan-out, including before HTTP asynchronous enqueue; define byte-size enforcement separately for each ingress. |
| FR-05 | Safe background overload | Distinguish capacity rejection from invalid input and business failure; prevent RabbitMQ fallback enqueue storms, tight requeue loops, accidental dead-lettering and false JobRunr success on deferred work. |
| FR-06 | Preserve recovery contracts | Preserve request identity, payload checks, per-SKU locks, partial-run recovery, synchronous projection and physical-ledger separation; do not extend reservation deadlines or silently retime delayed work. |
| FR-07 | Admission visibility | Expose configured capacity, active permits, admitted/rejected attempts, shape-limit rejections, execution outcomes and duration, plus allocation/projection/completion phase durations, through existing Micrometer infrastructure. |
| FR-08 | Controlled configuration and cutover | Validate enabled limits at startup, document per-instance scope and all participating callers, and enable enforcement only after adapter overload behavior and queued-work compatibility are approved and verified. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | No hidden waiting | Admission must not sleep or wait for a permit, hold a database connection while waiting, or create another unbounded application queue. |
| NFR-02 | Existing consistency | Do not change transaction/lock ordering, FIFO allocation, forecast freshness, reservation quantities, or ledger/expiry semantics; a projection failure may still follow committed allocation. |
| NFR-03 | Honest resource boundary | Bound allocation orchestrations per instance, not all HTTP work, database transactions, durable backlog or persisted-state projection cost; size limits from measurements rather than Hikari defaults. |
| NFR-04 | Bounded observability | Use fixed low-cardinality metric labels; never use request/job/order IDs, SKUs, customer data, payloads or exception messages as metric tags. |
| NFR-05 | Limited implementation | Reuse existing processors, validation, adapters, Micrometer and scheduling infrastructure; avoid a new limiter framework, database schema or scheduler unless the approved retry policy requires one. |

## Implementation plan

1. Approve entry-point coverage, enablement, numerical execution/request limits, byte-limit ownership, retry/defer policies and treatment of already queued work; capture the baseline.
2. Introduce validated admission/request-limit configuration, bounded shape validation, a typed capacity exception and a non-waiting application admission guard with Micrometer measurements.
3. Wrap shared allocation orchestration through projection and optional completion persistence; route the alternate backorder processor through the same boundary without nested permits or an outer transaction.
4. Integrate HTTP/MCP errors and pre-enqueue shape checks; implement and verify RabbitMQ/JobRunr overload handling before enabling the common guard, including receipt-triggered backorders.
5. Add focused permit-lifetime, rejection, payload, adapter and concurrency/recovery checks; measure independent/hot SKUs, slow projection and background contention on dedicated test infrastructure.
6. Document configured limits, error/retry contracts, metrics, queue-date caveats and rollout; synchronize architecture, scenarios, specification, issue, API documentation, service guidance and active context with the implemented behavior.

## Functional scenario

### Gherkin description
**Given** allocation admission is enabled with approved execution and request-size limits \
**And** every allocation execution permit on this service instance is occupied \
**When** a valid client attempts another inline allocation \
**Then** the service rejects that attempt promptly with HTTP 503 and a capacity-error code before allocation business writes \
**And** accepted executions retain their permits until allocation, synchronous projection and applicable completion persistence finish or fail \
**And** oversized requests are rejected distinctly before allocation or asynchronous enqueue \
**And** background capacity rejection preserves recoverable work without a fallback enqueue storm or immediate retry loop

### High-level technical analysis
- Apply one non-waiting per-instance guard to shared allocation orchestration, not only controllers or individual SKU workers
- Include optional execution-result persistence inside the guarded scope; reuse the same boundary for direct processors and background execution without nested permits
- Preserve existing SKU transactions and synchronous forecast recovery; do not hold an outer database transaction across parallel workers
- Validate bounded request shape before writes/fan-out and asynchronous enqueue; enforce approved ingress byte limits before deserialization where supported
- Map typed overload separately from validation/business errors in HTTP, MCP, RabbitMQ and JobRunr; approve durable deferral before enabling enforcement
- Expose low-cardinality admission/outcome/phase metrics through existing Micrometer infrastructure; durable backlog and global database budgets remain separate

### Acceptance Criteria
- At most the configured number of allocation orchestrations are admitted per instance across participating entry points; no permit-wait queue is introduced
- Saturated inline HTTP attempts return 503 with a stable capacity code and perform no allocation run, document, reservation, backorder or completion writes for that attempt
- A permit remains occupied during blocked projection and completion persistence and is released exactly once after success, exception or actual cancellation completion
- An unchanged retry after committed allocation and failed projection preserves idempotency and refreshes forecasts without duplicate reservations
- Exact request limits are accepted, over-limit requests are rejected before writes/enqueue, and limit bypass through internal or non-HTTP entry points is covered
- RabbitMQ capacity rejection does not trigger generic JobRunr fallback or unintended dead-lettering; JobRunr deferral does not claim successful allocation or exhaust business retries unnoticed
- Metrics identify admission pressure and processing outcomes with bounded labels, and status/health/ledger/expiry paths are not subjected to the allocation request guard
- Multi-instance checks show independent limits rather than a claimed distributed cap; production limits require representative workload evidence

## Pre-implementation analysis

The following table records the baseline and changes identified before
implementation; the sections below describe the implemented behavior.

| Component | Current behavior | Proposed change |
| --- | --- | --- |
| `AllocationController` | Checks today's `asOfDate`; inline execution delegates to `executeSync`; asynchronous execution enqueues immediately. | Preserve success behavior; map overload for inline execution and validate shape before async enqueue. Async acceptance does not reserve an execution permit. |
| `AllocationJobExecutor` | Checks cached completion, processes allocation plus forecasts, then persists completion. Each async submission has a fresh job ID. | Include processing and `complete` inside one admission scope; preserve a read-only completed-job fast path. Do not claim queued-job deduplication. |
| `AllocationProcessor` | Shared overloads for new demand and backorders; no enclosing transaction; scoped projection follows committed allocation. | Own the shared admission scope and optionally execute completion persistence before releasing it. |
| `BackorderAllocationProcessor` | Duplicates backorder allocation/projection orchestration outside the principal processor. | Delegate to the guarded shared path rather than maintain another independently guarded implementation. |
| `AllocationService` / `BackorderAllocationService` | Separate four-worker dispatchers; registration precedes fan-out; one coroutine per SKU; `runBlocking` waits for results. | Keep SKU worker limits and ordering; audit direct callers and ensure no production orchestration bypass. Bound incoming fan-out without implying all persisted backorder scopes are bounded. |
| `ProductAllocationService` | Resolves catalogue/embedding/search results before calling the shared processor; may persist product knowledge before allocation. | Apply allocation admission at processing, not around remote resolution. A 503 guarantees no allocation writes by the refused attempt, not no product-cache writes or no previous committed attempt. |
| `AllocationTools` | Both allocation tools call processing directly, bypassing the job executor. | Reuse the guarded processing overloads and expose a retryable capacity error rather than invalid input or empty success. |
| `AllocationRabbitListener` | Inline processing; generic exceptions enqueue JobRunr fallback. Invalid input is rejected with default requeue disabled. | Handle typed overload before both catch branches; use an approved recoverable pause/defer policy. Simply throwing overload would not provide safe retry. |
| JobRunr / `BackorderAllocationJob` | Allocation/backorder jobs have three configured retries; recurring jobs share infrastructure. | Capacity-aware deferral must preserve work and identities without consuming ordinary business retries indefinitely or blocking all workers. Verify the supported JobRunr lifecycle before choosing a hook. |
| `StockReceiptFollowupDispatcher` | Enqueues durable receipt work with stable IDs, up to 100 pending records per dispatch. | Guard job execution, not receipt commits. Preserve durable follow-up; this feature does not impose a new outbox/JobRunr backlog cap. |
| Request DTOs / `AllocationRequestValidator` | Existing field/relationship checks but no aggregate collection-size or SKU-count limits. | Add a shared configuration-driven shape check; retain existing validators and avoid building large flattened collections just to count them. |
| HTTP exception advice / OpenAPI | Input errors map to 400; no allocation-capacity contract. | Add a distinct 503 error, document approved size errors, and keep overload outside `IllegalArgumentException` handling. |
| Next.js Axios route | Forwards upstream status/body, but only selected response headers; no automatic retry. | Verify 503 propagation end to end. Forward `Retry-After` only if an approved server policy supplies it; do not assume it is already forwarded. |
| Actuator / build | Actuator and Micrometer infrastructure already available; no custom allocation admission measurements. | Use the injected registry without adding a limiter dependency or claiming a new metrics backend is required. |

## Implemented admission ownership

`AllocationAdmission` uses `Semaphore.tryAcquire()` without waiting.
One permit represents one allocation orchestration, not one
SKU, HTTP connection or database transaction. A request involving many SKUs
still consumes one orchestration permit, which is why payload limits matter.
All participating new-demand and backorder executions share the same pool.
There is no arrival-order, tenant-fairness or maintenance-capacity guarantee.

The processor is the common boundary. An internal completion callback keeps
the executor's result persistence inside the same permit lifetime:

```text
Direct processor/MCP/product/backorder call:
  shape validation
  -> try admission
  -> existing allocation -> synchronous projection
  -> return result
  -> release in finally after work stops

Job executor / inline structured HTTP / RabbitMQ:
  read-only completed-job lookup (existing fast path)
  -> shared processor with completion callback:
       shape validation -> try admission
       -> allocation -> synchronous projection -> persist completion
       -> release in finally after work stops
```

The ordinary processing overload uses an identity callback, while the
executor supplies its existing `complete(jobId, run)` operation. No admission
token needs to cross a public DTO, no thread-local ownership must follow
coroutines, and workers do not reacquire permits. The alternate backorder
processor delegates to this shared boundary.

Do not annotate this guarded orchestration with `@Transactional`. Preserve
registration, per-SKU commits, run completion and projection transaction
boundaries. Hold the permit while downstream workers stop; an HTTP timeout,
client disconnect or job cancellation signal alone is not proof they stopped.
Failed projection/completion can follow committed allocation. Release capacity
after failure and retain existing retry/recovery semantics, not an imaginary
all-or-nothing request rollback.

## Configuration and validation

Configuration namespace: `stockio.allocation.admission`. Enforcement defaults
to disabled. Every numeric setting must be explicitly positive when enabled;
the configured `0` fallbacks mean unset, not production limits.

| Setting | Meaning |
| --- | --- |
| `enabled` | Defaults to false; explicit opt-in after all writers/adapters are deployed. |
| `max-in-flight` | Positive per-instance execution count, required when enabled; select experimentally, not equal to Hikari's maximum. |
| `max-orders` / `max-shipments` | Aggregate incoming document counts. |
| `max-demand-lines` / `max-inbound-lines` | Total lines across all documents, not only lines per order. |
| `max-distinct-skus` | Union of incoming demand, supply and explicitly supplied internal SKU scope; cap derived incoming fan-out before launching tasks. |
| `max-request-bytes` | Raw service HTTP bodies and RabbitMQ payloads before JSON conversion, plus bounded canonical DTO serialization for internal/MCP requests. Must be below `Int.MAX_VALUE`. Set the same flag/byte limit separately in Next.js. |

Check shape by bounded iteration with early rejection rather than allocating
unlimited intermediate lists/sets. For new async HTTP submissions, check before
enqueue; repeat at actual execution for non-HTTP and legacy/direct callers.
Reuse existing bean/domain validation rather than invent a new request hash.

HTTP distinctions: body too large is 413, semantic collection/count
limits use existing 400 validation conventions, and valid-but-saturated
execution is 503.
For transport byte bounds, cover chunked bodies and deserialization, not only
`Content-Length`. The servlet filter reads at most limit + 1 bytes before
deserialization; Next.js counts streamed UTF-8 bytes before Axios. RabbitMQ checks
the already-received message body before JSON conversion. MCP/internal calls
also bound canonical DTO bytes without allocating an output buffer; this is
not a hard byte limit on MCP transport reception/deserialization. Configure
external transport/proxy limits separately where needed.
Do not silently drop inbound documents, truncate line lists, or split an
allocation into requests with altered identity.

Incoming payload bounds do not bound database history, unscoped backorder
discovery or forecast work from already persisted events. Do not truncate a
SKU's forecast horizon to satisfy a request-size limit. A maximum forecast
date span or persisted-work execution budget needs separate compatibility
analysis/approval; existing projections must remain complete.

## Error, retry and queue behavior

Capacity errors use a direct `RuntimeException` signal and the stable
HTTP code `ALLOCATION_CAPACITY_EXHAUSTED`, not a validation exception.
The response describes refusal of this execution attempt, not the absence of
earlier commits under the same request ID. Reuse unchanged logical identity
after timeout/unknown outcomes. No automatic UI retry, implicit async fallback
or `Retry-After` is added. MCP retains the typed error and coded unchanged-retry
guidance through the existing tool execution path.

When enabled, the allocation RabbitMQ container uses one consumer, prefetch 1
and AUTO acknowledgments. Capacity rejection keeps the original broker message
unacknowledged, pauses that existing consumer thread for a jittered 5-10 seconds,
then retries the same request. The delay uses no permit or database connection.
It is consumer backoff, not a semaphore wait queue or a new delay queue.
Shutdown/interruption requests requeue explicitly despite the normal
`default-requeue-rejected=false`; a closed channel stops further local retries.
Broker-held work remains subject to broker durability/delivery-timeout rules.
The generic JobRunr fallback remains for genuine processing failures, not
capacity rejection. Oversized messages are invalid input and follow rejection/
dead-letter handling, which is why existing work must be reconciled first.

Both allocation JobRunr methods declare `AllocationCapacityRetryFilter` through
their existing `@Job` annotations. At state election, a capacity failure changes
the same job to SCHEDULED, with jittered 5-10-second delay; actual pickup also
depends on JobRunr polling. No worker sleeps and no duplicate job is enqueued.
Persisted failure type/cause identifies capacity history; it is excluded from
the three business retries. Ordinary failures retain three retries with the
existing default exponential seed 3; terminal failures remain visible.
Capacity deferral can continue while overloaded: queue age and per-job state
history can grow. This feature does not bound backlog or promise eventual
admission/fairness.

Async HTTP may continue accepting jobs while inline execution is full; this
feature intentionally does not promise a durable backlog bound. Its shape
validation still applies before enqueue. Old queued requests exceeding new
limits need an explicit drain/grandfather/operator-recovery policy.
Queued `asOfDate` and reservation TTL remain unchanged: controller validation
does not repeat automatically at execution, and waiting after registration
consumes hold lifetime. Never silently change dates or extend holds.

## Metrics and measurement

The existing `MeterRegistry` exposes:

| Meter | Purpose |
| --- | --- |
| `stockio.allocation.admission.limit` / `.active` | Configured limit and currently held permits, including projection/completion. |
| `stockio.allocation.admission.enabled` | Whether enforcement is active. When disabled, the active gauge still counts executions but is not capped. |
| `stockio.allocation.admission.attempts` | Counter with admitted/saturated outcome. |
| `stockio.allocation.request.rejected` | Counter with bounded shape-limit reason. |
| `stockio.allocation.execution.duration` | Full admitted lifetime, with bounded success/failure outcome. |
| `stockio.allocation.phase.duration` | Allocation, projection and completion durations. |

Labels are the fixed run type and success/failure or admitted/saturated outcome,
phase or shape-rejection reason where applicable. Ingress labels are not added.
Log correlation IDs rather than
tagging them. Reconcile active permits and counters in tests; do not count a
committed allocation as end-to-end success when projection/completion fails.

Measure HTTP latency with existing HTTP observations alongside the admitted
timer. Use configured histogram/SLO support and an appropriate collector when
evaluating p95/p99; the Actuator metrics endpoint alone does not establish
aggregated cross-instance percentiles. Correlate Hikari active/pending/timeouts,
database lock waits, accepted/completed rates, and background age/freshness.
Keep metric names and buckets stable once approved.

Use dedicated harness infrastructure for independent SKUs, a hot SKU,
multi-SKU requests, slow projection, enqueue failures and maintenance overlap.
The performance area currently has guidance, not an implemented benchmark.
For a fixed limit N, demonstrate N blocked admitted executions and prompt
rejection of the next, without asserting an arbitrary production throughput.
Request rejection limits load; it does not make hot-SKU transactions faster.

## Affected components and validation

- Application: `AllocationProcessor`, `BackorderAllocationProcessor`, `AllocationJobExecutor`, request validation and admission configuration/metrics.
- Adapters: allocation/product HTTP controllers and advice, `AllocationTools`, `AllocationRabbitListener`, JobRunr allocation/backorder execution and the Next.js status/header forwarding boundary.
- Contracts: allocation/product OpenAPI error responses and approved size constraints. No new public endpoint, persistence port, business schema or replacement lock protocol is proposed.
- Tests: existing processor, executor/job, controller, product-service, RabbitMQ listener and UI-route suites; add focused guard/configuration tests. Use real PostgreSQL integration checks where rejection-before-writes and committed-allocation recovery require them.
- Infrastructure: possible RabbitMQ retry topology or JobRunr filter/configuration only after the adapter policy decision; keep this distinct from a general durable-admission schema.

Verify N/N+1 concurrency, shared ingress budget, exact and over-limit sizes,
zero allocation writes on refused attempts, blocked projection/completion,
failure/cancellation release, callback fast paths, no nested acquisition,
duplicate request recovery, and background no-loss/no-storm behavior. Verify
health/status, ledger and whole-order expiry do not acquire allocation permits.
They still contend for the same database, so this is not maintenance isolation.

Verification passed: the full service suite, final affected semaphore/executor/
MCP/RabbitMQ and PostgreSQL checks, allocation/product OpenAPI validation and
generation/compilation, 22 UI tests and UI typechecking. Coverage includes
blocked projection/completion, cancellation signals, configured limits, UTF-8/
unknown-length bodies, unchanged recovery after allocation commit, same-job
capacity deferral and ordinary retry exhaustion.

No live broker-outage/delivery-timeout exercise or capacity benchmark was run.
Validate those deployment-specific conditions on dedicated infrastructure.
No Kotlin lint/format task is configured. Documentation checks cover links,
anchors, synchronized requirements/plan/scenario and whitespace.

## Operational decisions before enablement

1. Select all execution/count/byte limits from representative workload measurements. No production numbers or 1,000-request-per-second guarantee are supplied.
2. Deploy updated adapters together, drain/reconcile incompatible queued work, and apply the same byte flag/limit to the independently running UI. Keep old-date request identity and hold semantics unchanged.
3. Monitor rejected/admitted/completed rates, active permits, Hikari/lock waits, maintenance freshness, queue age and JobRunr history growth. Broker delivery timeout must accommodate intended backoff/processing.
4. Configure timer histogram/SLO buckets and a collector for tail-latency analysis when needed. The test harness still has no production-capacity benchmark.
5. Consider traffic-class fairness, database-work budgets, durable-backlog bounds and product-resolution admission separately. This first semaphore pool supplies none of those guarantees.

## Resources

- [Backpressure and admission-control design](../../../docs/system_design_backpressure_admission_control.md)
- [Current allocation processor](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/processor/AllocationProcessor.kt)
- [Current job executor](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt)
- [Current RabbitMQ listener](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt)
- [Current request validator](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/validator/AllocationRequestValidator.kt)
- [Current Axios route](../../../stockio-api/src/main/kotlin/nl/stockio/ui/app/api/allocate/route.ts)
- [Performance harness guidance](../../../stockio-test-harness/performance/README.md)
