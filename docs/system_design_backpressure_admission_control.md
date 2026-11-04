# System design: backpressure and admission control

## Scope

Design discussion for handling allocation traffic such as 1,000 requests per
second, with different orders and SKUs.

**Current** describes inspected Stockio code. **Proposed** describes options,
not implemented guarantees or an approved feature specification. This document
does not change application behavior or select production limits.

"Implemented" refers to the current worktree, not confirmation that every
running instance has been upgraded.

The [concurrent-registration feature](../.github/memory/issues/feature-concurrent-document-registration.md)
implemented narrower registration. The per-SKU follow-up now also implements
scoped projection, atomic SKU ledger work, whole-order expiry and durable receipt
follow-up. The first allocation semaphore bulkhead is also implemented as an
explicit opt-in; database-work budgets, rate limiting and bounded durable intake
remain proposals.

Related material:

- [Stockio architecture](../.github/memory/architecture.md)
- [System design: prevent duplicate allocation](./system_design_interview.md)
- [Implemented feature: per-SKU stock processing](../.github/memory/issues/feature-per-sku-stock-processing.md)
- [Feature: fail-fast allocation admission](../.github/memory/issues/feature-fail-fast-allocation-admission.md)
- [Draft feature: shared database-work budget](../.github/memory/issues/feature-shared-database-work-budget.md)
- [Admission configuration](../stockio-service/README.md#opt-in-allocation-admission)

## 1. Current execution and bottlenecks

For `POST /api/allocation/jobs?async=false`, the request waits for:

```text
HTTP request
  -> allocation run initialization
  -> document registration under sorted document/SKU/run locks
  -> per-SKU allocation transactions
  -> allocation run completion
  -> synchronous affected-SKU projection transactions
  -> execution result persistence
  -> HTTP response
```

| Boundary | Current behavior |
| --- | --- |
| Allocation admission | Disabled by default. When enabled with explicit positive execution/count/byte limits, a per-instance semaphore rejects without waiting before allocation writes and spans allocation, projection and applicable completion persistence. It is not a database-transaction semaphore or a new thread pool. |
| SKU dispatcher | `AllocationService` uses `Dispatchers.IO.limitedParallelism(maxConcurrentSkus)`, default 4. Its limit is shared across new-demand requests in one instance, not per request. Each request still creates an `async` task for every SKU; limited execution is not a bounded waiting queue. |
| Request thread | `runBlocking` keeps the calling HTTP thread occupied while awaiting SKU workers. |
| SKU database locks | Allocation, amendments, projection, ledger and whole-order expiry use the same database SKU locks. No production path takes the retired global projection gate. Hot SKUs and overlapping multi-SKU expiry still serialize. |
| Registration locks | Sorted namespaced document identities -> sorted inbound SKUs -> run row -> business rows. Existing supply is locked before amendment validation. Document locks protect identities even before their business rows exist. |
| Projection scope | Allocation refreshes affected SKUs sequentially per request, after allocation commits. Different requests can refresh concurrently; this phase does not use the four-worker allocation dispatcher. Registration also runs outside that dispatcher. |
| Ledger budget | Sequential ledger coordination across instances uses a renewable fenced owner lease. Busy callers poll every 100 ms without an outer transaction, but still occupy calling threads; this is serialization, not fail-fast admission or a bounded caller queue. Workers take their SKU before coordinator metadata. |
| Database pool | Hikari defaults to 10 connections and a 30,000 ms connection-acquisition timeout. Environment settings can override these defaults. |
| Synchronous response | Processing runs inline but returns `202 Accepted` afterward. It does not create a JobRunr job or gain automatic JobRunr retries. Its returned job-status location is not pollable as a JobRunr job. |
| Asynchronous response | `async=true` enqueues and returns after enqueue succeeds. Each submission receives a fresh job ID, even for the same logical `requestId`; business idempotency does not deduplicate queued jobs. No outstanding-work bound is enforced. |
| RabbitMQ | When admission is enabled, one consumer/prefetch 1/AUTO ack keeps capacity-rejected work unacknowledged during jittered 5-10-second consumer backoff. Genuine processing failures retain JobRunr fallback; capacity does not. No total broker backlog cap is enforced here. |
| Payload/work size | Opt-in aggregate order/shipment/line/SKU and byte limits apply before writes/fan-out; shape checks also precede async enqueue. Raw HTTP/RabbitMQ bytes are checked before JSON, internal/MCP canonical DTO bytes after deserialization. Persisted-work cost and projection horizon remain unbounded by request shape. |
| Background allocation jobs | Annotation-elected JobRunr filtering durably schedules the same saturated job after 5-10 seconds plus polling, excluding capacity failures from the three business retries. History/queue age can still grow. |

The four-worker limit is not a global database connection budget: backorder
allocation has its own dispatcher, other workflows also use the pool, and
additional application instances have their own limits.

In particular, `maxConcurrentSkus=4` does not mean "at most four database
transactions": registration, projection, backorder workers, ledger, expiry,
status reads and JobRunr persistence also compete for connections. No shared
database-work budget, request rate limiter, or database lock/statement timeout
policy is configured here. Hikari's connection-acquisition timeout does not
bound a lock wait after a connection has been acquired.

A request waiting only for a dispatcher slot does not inherently retain a
database connection; there is no outer allocation transaction spanning that
wait. However, transactions waiting on database locks can occupy connections.
Pool exhaustion can therefore be a symptom of lock contention, not merely a
pool that is too small.

Source references:

- [AllocationController](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt)
- [AllocationService](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationService.kt)
- [AllocationDocumentService](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationDocumentService.kt)
- [AllocationSkuWorker](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/worker/AllocationSkuWorker.kt)
- [AllocationProcessor](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/processor/AllocationProcessor.kt)
- [BackorderAllocationService](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/BackorderAllocationService.kt)
- [AllocationJobExecutor](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt)
- [RabbitMQ listener and queue declarations](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt)
- [Allocation request DTOs](../stockio-api/src/main/kotlin/nl/stockio/api/dto/AllocationRequest.kt)
- [Semaphore and configuration](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationAdmission.kt)
- [Request limits](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationRequestLimits.kt)
- [Capacity retry filter](../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationCapacityRetryFilter.kt)
- [StockProjectionProcessor](../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockProjectionProcessor.kt)
- [Ledger coordinator](../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockLedgerProcessor.kt)
- [Receipt follow-up dispatcher](../stockio-service/src/main/kotlin/nl/stockio/stock/domain/job/StockReceiptFollowupDispatcher.kt)
- [Database lock implementation](../stockio-service/src/main/kotlin/nl/stockio/stock/infra/StockJpaRepositories.kt)
- [Service configuration](../stockio-service/src/main/resources/application.yml)

## 2. Capacity: accepting work is not completing work

Let arrival rate be `lambda` and sustainable completion rate be `mu`.
If `lambda > mu` over time, outstanding work grows until a resource limit or
deadline is reached. Queues absorb bursts; they do not solve sustained overload.

For illustration only, if each request has one SKU and each SKU transaction
takes 50 ms, four workers have a theoretical ceiling of:

```text
4 / 0.050 seconds = 80 requests/second
```

This excludes registration, projection rebuilding, connection waits, and other
contention. At 1,000 incoming requests/second and 80 completions/second, the
backlog would grow by approximately 920 requests/second while that rate persists.
This is not a measured Stockio throughput result.

That simplified worker ceiling applies only to its allocation stage. It is not
a system-wide ceiling or a basis for choosing HTTP admission limits: payload
fan-out, registration, independent projection execution and background work
must be measured together.

Hot-SKU work must still fit within its serialized time budget. Increasing worker
concurrency cannot make operations on the same SKU run in parallel.

## 3. Admission layers: implemented and proposed

| Control | Purpose | Proposed overload behavior |
| --- | --- | --- |
| Rate limit | Bound arrival rate, with a small burst allowance. Optionally enforce both caller and service budgets. | Return `429 Too Many Requests`, with retry guidance where meaningful. |
| Allocation in-flight limit (implemented, opt-in) | Cap executing allocation orchestrations across inline and background callers, through forecast/completion. | Inline HTTP returns `503 Service Unavailable`; background adapters defer. No permit wait queue. |
| Bounded asynchronous backlog | Bound outstanding durable work and its age, not just HTTP connections. | Refuse new submissions when overloaded; return `202` only after successful durable enqueue. |
| Worker concurrency budget | Bound active database-intensive execution, accounting for all entry points and background tasks. | Let only the bounded admitted backlog wait; do not use Hikari's pending borrowers as the work queue. |

Rate limiting and concurrency limiting solve different problems. A token bucket
can allow database occupancy to grow when execution slows; a concurrency
bulkhead can still be flooded with rapid rejected attempts. Start by bounding
admitted work, then add caller fairness/rate policies once identities and
measured capacity are known. `429` describes a caller's rate policy; `503`
describes unavailable execution capacity. The 503 semaphore policy is implemented
when enabled; a 429 rate policy is not.

Apply admission control before opening allocation transactions. Release permits
on all completion/failure paths, but do not release an execution permit merely
because a client disconnects if the underlying work continues.

Leave capacity for expiry, ledger processing, projection work, and operational
requests. A synchronous HTTP limit alone is insufficient if JobRunr, RabbitMQ,
or other callers can independently saturate the same database.

Across multiple instances, distinguish per-instance limits from the total
database budget. A backlog limit must be enforced safely under concurrent
submissions; a separate "count queued jobs, then enqueue" check can overshoot.
Choose an atomic admission/reservation mechanism rather than assuming JobRunr
already supplies this policy.

For clients, use bounded retries with backoff and jitter. After a timeout or
uncertain outcome, reuse the same logical `requestId` and unchanged payload.
Do not turn every retry into a new allocation request.

Do not silently switch a rejected synchronous call to asynchronous execution.
If sending `Retry-After`, derive a defensible estimate or use an explicit
configured retry policy; it is not a completion-time guarantee.

## 4. Reduce global serialization

The narrower registration protocol is implemented:
sorted document identity keys, sorted inbound SKUs, run locking, and locked
supply reads. Namespaces distinguish order, order-line, shipment, and
shipment-line identities. Rows in `allocation_document_lock` are retained;
existing business primary/unique/foreign-key constraints remain in force.

Taking SKU locks before the run row avoids holding a run lock while waiting
for a worker that may need run foreign-key checks. Competing document identities
and inbound SKUs serialize, but independent registrations can overlap.

Projection now reads/replaces one SKU at a time, including deleting stale output
for an empty scope. Ledger computes and applies its fresh SKU forecast in the
same transaction, with a durable checkpoint and receipt follow-up. Whole-order
expiry acquires all immutable order SKUs in sorted order and refreshes affected
forecasts atomically. Partial ledger work does not advance global freshness.

Potential target:

```text
Bounded admission
  -> durable queue for asynchronous requests, or bounded inline execution
  -> document/run identity protection
  -> transactional per-SKU allocation
  -> synchronous scoped projection refresh (current behavior)
```

Durable dirty markers and coalescing are optional later changes, not prerequisites
for this admission-control increment. Today, projection failure is recovered by
retrying processing with the original logical request identity; no separate
durable forecast-refresh queue is provided.

Implemented registration protections:

- Database identity constraints plus namespaced document/run locks replace
  globally exclusive registration.
- Registration and allocation coordinate inbound changes through SKU locks;
  request identity and per-SKU replay safety remain unchanged.

Remaining projection proposals:

- Coalescing several mutations into fewer projection rebuilds instead of
  refreshing each affected SKU after every request.

The global gate was replaced only after all participating workflows adopted
database-backed SKU coordination. Deployment must stop all old writers before
migrating/restarting all instances with the new protocol. Mixed-version operation
is unsafe; in-process locks would not provide equivalent multi-instance safety.

Moving projection work to the background changes freshness guarantees. Define
when forecasts must reflect an allocation and how ledger operations obtain a
consistent baseline. If asynchronous refresh is selected, persist a dirty marker
or outbox record atomically with the relevant mutation, with durable processing
and retry. An after-commit enqueue alone can fail after the mutation commits.

The separate per-SKU feature implements synchronous scoped projection
and fresh per-SKU ledger transactions, not asynchronous forecasts. It also
covers whole-order expiry, partial ledger-run recovery, and a coordinated
replacement of the global gate. Allocation admission is implemented separately
as described above; rate limiting and asynchronous forecast publication remain
separate proposals.

## 5. Threading and persistence options

| Option | Benefit | Limitation and recommendation |
| --- | --- | --- |
| Virtual threads with existing JPA/JDBC | Can reduce the cost of waiting HTTP threads. | Do not create more database capacity or bypass locks. Existing coroutine dispatcher limits remain. Keep bounded admission and evaluate from measurements. |
| Non-blocking repositories/R2DBC | Reduce thread occupancy while waiting for I/O. | Require substantial persistence and transaction changes; do not remove database contention. Not the first change for this bottleneck. A coroutine wrapper does not make JPA/JDBC non-blocking. |
| Larger Hikari pool | May help when the database has unused capacity and useful independent work is connection-limited. | More connections waiting for the same lock can worsen contention. Tune after measuring lock waits and database capacity. |
| More service replicas | Can increase capacity for genuinely independent work. | Each replica adds worker concurrency and connections; database SKU locks still serialize hot SKUs. Budget concurrency across replicas. |

## 6. Implemented first increment and proposed next steps

The first increment implements configurable fail-fast allocation admission,
request shape/byte limits and Micrometer measurements while preserving the SKU
protocol and synchronous forecast semantics. Enforcement defaults to disabled;
choose explicit positive limits from representative measurements before enabling.
**Next recommendation:** baseline/tune that opt-in guard. If pool contention or
maintenance freshness objectives justify another layer, approve the shared
database-work budget below before changing threading or increasing pools.

The [separate feature](../.github/memory/issues/feature-fail-fast-allocation-admission.md)
details admission ownership through completion persistence, adapter policies,
request limits and metrics. It does not select production limits or imply
that a running instance has enabled enforcement.

### First increment: implemented allocation execution guard

- `AllocationProcessor` uses one shared per-instance `Semaphore.tryAcquire()`;
  the alternate backorder processor delegates to it. An optional completion
  callback retains the permit through result persistence without nested permits
  or an outer transaction.
- HTTP/MCP/RabbitMQ/JobRunr and receipt backorders share the execution pool.
  HTTP capacity errors are 503, count limits 400 and byte limits 413; unchanged
  retries preserve request identity. No `Retry-After` or automatic UI retry is added.
- Raw HTTP/RabbitMQ and canonical DTO byte bounds accompany incoming document,
  line and distinct-SKU limits. Next.js needs the same flag/byte limit separately.
  Forecast horizons and persisted backorder discovery are not truncated.
- RabbitMQ backs off its bounded consumer while retaining the original
  unacknowledged message. JobRunr schedules the same job durably without spending
  business retries. Neither adapter turns capacity rejection into generic fallback.
- Active/enabled/limit gauges, admission/shape counters and execution/phase timers
  use bounded labels. Combine these with HTTP/Hikari metrics and database profiling;
  configure histograms/collection separately for p95/p99. SKU waiting and queue-age
  instrumentation are not implied by those meters.
- Product resolution and its Ollama/Tavily/cache work remain outside the
  allocation permit, as do cached completion reads, ledger, expiry and status.
  Drain/reconcile oversized queued work before enabling. Broker delivery timeout
  and growing JobRunr history remain operational considerations.

This bounds outstanding orchestrations per instance, not all database activity.
It is the first protective step, not a hard multi-instance database-capacity
guarantee or a bound on queued JobRunr jobs.

### Second increment: protect the shared database budget

The [draft feature](../.github/memory/issues/feature-shared-database-work-budget.md)
captures Scenario 17, transaction owners, maintenance shares, partial-progress
recovery, verification and open policy decisions. Implementation has not started.

Apply a shared execution budget to database-intensive transaction entry points:
registration, new-demand/backorder SKU workers, projection, ledger SKU work and
whole-order expiry, including owning run/result and ledger-metadata writes.
Acquire outside Spring's transactional proxy/template and release after
commit/rollback, so waiting for an application permit cannot retain a pooled
connection. Acquiring inside an `@Transactional` method body is too late.
Treat the existing whole-order expiry batch transaction as one database work
unit, not one permit per order or locked SKU.

Avoid nested permit acquisition: ledger and expiry already invoke projection
inside their own transaction. Their inner refresh must use the existing
admission context, not wait for another permit from the same exhausted budget.
Do not hold a database-work permit while an outer coordinator waits for child
work to acquire permits.

Reserve maintenance/control-plane headroom rather than assigning the whole
pool to customer allocation. Protect ledger and expiry from allocation and
define their protection from each other; one shared semaphore alone does
neither. Receipt-triggered backorder allocation uses the allocation share.
A sizing constraint is:

```text
admitted database work + other active database use + headroom <= pool size
```

An application limiter cannot reserve physical Hikari connections for paths it
does not govern. Include JobRunr persistence and reads in the per-instance
budget, and ensure the sum across replicas fits database capacity. Use controlled
workload shares or another explicit isolation mechanism if maintenance
availability must be guaranteed. Do not copy the default pool size of 10 into a
semaphore limit of 10.

Database-phase refusal may follow committed registration, other SKUs or
allocation before projection/completion. Distinguish it from the existing
pre-write request rejection and preserve unchanged-identity recovery without
claiming a whole-request rollback. Extend adapter and maintenance deferral
deliberately; allocation-specific retry filtering does not automatically cover
ledger/expiry jobs.

Hot-SKU lock waits can still occupy execution slots and connections. Measure
their effect; if they crowd out independent SKUs, consider bounded SKU-aware
scheduling before transactions. Local scheduling is an optimization, not a
replacement for cross-instance database locks.

### Third increment: bound durable intake and retry amplification

For HTTP asynchronous submission, atomically reserve outstanding-work capacity
and associate a logical `requestId` with its validated payload fingerprint.
Concurrent duplicates should reuse the accepted logical submission rather
than allocate fresh jobs/capacity; conflicting reuse must remain an error.
Bound both backlog size and age, and count execution/retry work until a defined
terminal outcome. Do not release capacity on a client disconnect.

The capacity reservation and enqueue lifecycle must recover from crashes. Use
a transactionally durable admission/dispatch record or another proven
reservation-and-reconciliation mechanism; a count-then-enqueue check or an
in-memory counter is insufficient. If acceptance uses an outbox before JobRunr
enqueue, status polling must represent that admitted-but-not-yet-enqueued state.
Do not silently change the current `202`/job-status contract.

Current opt-in RabbitMQ consumer backoff already prevents capacity-triggered
JobRunr fallback and overrides no-requeue on shutdown/interruption. A bounded
durable-intake feature must additionally choose a deliberate broker backlog/
overflow and age policy; do not mistake one unacknowledged message per consumer
for a bounded broker queue or discard valid overloaded work as invalid input.

Receipt follow-up is durable, but dispatching up to 100 pending records per
invocation is not a bound on JobRunr backlog. Include that producer and its
retries in downstream capacity planning; preserve pending durable work when
execution/dispatch is saturated.

Finally, define age/date handling for queued allocation. REST validates
`asOfDate` on submission; JobRunr execution does not repeat that controller
check. Expiry deadlines start at document initialization, so post-registration
waiting consumes hold lifetime. Do not assume queued work is automatically
rejected at midnight, silently retime it under the same request ID, or assume
overload can always be retried after arbitrary delay.

### Then tune from measurements

Choose numerical limits from representative independent-SKU and hot-SKU
workloads, including background maintenance and realistic request sizes.
Adjust concurrency and pool size only while completed throughput, tail latency
and maintenance freshness remain healthy. Add rate/caller-fairness policies,
virtual threads, or projection coalescing as separate measured follow-ups.

## 7. Decisions and verification for follow-ups

Choose the target completion rate, request-shape/date limits, burst duration,
maximum waiting time, synchronous latency objective, backlog size/age, retry
policy, multi-instance budget, maintenance shares and forecast freshness
requirement. For bounded durable intake, decide broker overflow/age policies and
the asynchronous idempotency/status contract; existing execution backoff does
not supply that policy. No numerical production limits are selected here.

Plan functional and load checks for:

- Sustained load and bursts, including different SKUs and hot-SKU contention.
- Bounded resource usage and prompt, predictable overload responses.
- Slow databases, lock contention, disconnects, enqueue failures, and retry storms.
- Duplicate requests, partial execution, crash recovery, and multiple instances.
- Rejection before business writes, permit release after exceptions and
  committed-allocation/failed-projection outcomes, and no nested-budget deadlock.
- Queue-capacity reservations surviving crash/enqueue gaps, duplicate submissions
  consuming one logical capacity slot, and status polling before dispatch.
- RabbitMQ overload not becoming a JobRunr enqueue storm, a tight requeue loop,
  or unintended dead-lettering of valid work.
- Queue aging, reservation expiry, and requests crossing the business-date boundary.
- Correct reservation quantities and consistent ledger/projection results under
  the proposed locking and refresh strategy.

Functional semaphore, byte/count, adapter and PostgreSQL rejection/recovery tests
cover the implemented first increment. Atomic durable-intake reservations,
database-work budgets, broker-outage scenarios and production performance targets
remain separate verification work. The
[`stockio-test-harness/performance/`](../stockio-test-harness/performance/README.md)
area currently contains guidance only.
