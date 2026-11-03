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
follow-up. Admission control remains a proposal.

Related material:

- [Stockio architecture](../.github/memory/architecture.md)
- [System design: prevent duplicate allocation](./system_design_interview.md)
- [Implemented feature: per-SKU stock processing](../.github/memory/issues/feature-per-sku-stock-processing.md)

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
| SKU dispatcher | `AllocationService` uses `Dispatchers.IO.limitedParallelism(maxConcurrentSkus)`, default 4. Its limit is shared across new-demand requests in one instance, not per request. Each request still creates an `async` task for every SKU; limited execution is not a bounded waiting queue. |
| Request thread | `runBlocking` keeps the calling HTTP thread occupied while awaiting SKU workers. |
| SKU database locks | Allocation, amendments, projection, ledger and whole-order expiry use the same database SKU locks. No production path takes the retired global projection gate. Hot SKUs and overlapping multi-SKU expiry still serialize. |
| Registration locks | Sorted namespaced document identities -> sorted inbound SKUs -> run row -> business rows. Existing supply is locked before amendment validation. Document locks protect identities even before their business rows exist. |
| Projection scope | Allocation refreshes affected SKUs sequentially per request, after allocation commits. Different requests can refresh concurrently; this phase does not use the four-worker allocation dispatcher. Registration also runs outside that dispatcher. |
| Ledger budget | Sequential ledger coordination across instances uses a renewable fenced owner lease. Busy callers poll every 100 ms without an outer transaction, but still occupy calling threads; this is serialization, not fail-fast admission or a bounded caller queue. Workers take their SKU before coordinator metadata. |
| Database pool | Hikari defaults to 10 connections and a 30,000 ms connection-acquisition timeout. Environment settings can override these defaults. |
| Synchronous response | Processing runs inline but returns `202 Accepted` afterward. It does not create a JobRunr job or gain automatic JobRunr retries. Its returned job-status location is not pollable as a JobRunr job. |
| Asynchronous response | `async=true` enqueues and returns after enqueue succeeds. Each submission receives a fresh job ID, even for the same logical `requestId`; business idempotency does not deduplicate queued jobs. No outstanding-work bound is enforced. |
| RabbitMQ | The listener executes inline. A non-input processing exception normally enqueues JobRunr fallback; simply throwing a future overload exception would move backlog rather than apply backpressure. Prefetch/concurrency and broker queue-size policies are not explicitly bounded by this repository's listener configuration. |
| Payload/work size | DTOs bound some string lengths and require nonempty line lists, but do not cap order/line counts, distinct SKUs, or projection horizon. One admitted request can therefore create substantial work. |

The four-worker limit is not a global database connection budget: backorder
allocation has its own dispatcher, other workflows also use the pool, and
additional application instances have their own limits.

In particular, `maxConcurrentSkus=4` does not mean "at most four database
transactions": registration, projection, backorder workers, ledger, expiry,
status reads and JobRunr persistence also compete for connections. No shared
execution bulkhead, request rate limiter, or database lock/statement timeout
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

## 3. Proposed: combine rate limits and admission limits

| Control | Purpose | Proposed overload behavior |
| --- | --- | --- |
| Rate limit | Bound arrival rate, with a small burst allowance. Optionally enforce both caller and service budgets. | Return `429 Too Many Requests`, with retry guidance where meaningful. |
| Synchronous in-flight limit | Cap accepted, still-executing synchronous allocations, even when processing slows. | Reject promptly with `503 Service Unavailable` when capacity is unavailable. Avoid another large waiting queue. |
| Bounded asynchronous backlog | Bound outstanding durable work and its age, not just HTTP connections. | Refuse new submissions when overloaded; return `202` only after successful durable enqueue. |
| Worker concurrency budget | Bound active database-intensive execution, accounting for all entry points and background tasks. | Let only the bounded admitted backlog wait; do not use Hikari's pending borrowers as the work queue. |

Rate limiting and concurrency limiting solve different problems. A token bucket
can allow database occupancy to grow when execution slows; a concurrency
bulkhead can still be flooded with rapid rejected attempts. Start by bounding
admitted work, then add caller fairness/rate policies once identities and
measured capacity are known. `429` describes a caller's rate policy; `503`
describes unavailable execution capacity. Neither is implemented yet.

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
replacement of the global gate. Rate limiting, admission control, and
asynchronous forecast publication remain separate.

## 5. Threading and persistence options

| Option | Benefit | Limitation and recommendation |
| --- | --- | --- |
| Virtual threads with existing JPA/JDBC | Can reduce the cost of waiting HTTP threads. | Do not create more database capacity or bypass locks. Existing coroutine dispatcher limits remain. Keep bounded admission and evaluate from measurements. |
| Non-blocking repositories/R2DBC | Reduce thread occupancy while waiting for I/O. | Require substantial persistence and transaction changes; do not remove database contention. Not the first change for this bottleneck. A coroutine wrapper does not make JPA/JDBC non-blocking. |
| Larger Hikari pool | May help when the database has unused capacity and useful independent work is connection-limited. | More connections waiting for the same lock can worsen contention. Tune after measuring lock waits and database capacity. |
| More service replicas | Can increase capacity for genuinely independent work. | Each replica adds worker concurrency and connections; database SKU locks still serialize hot SKUs. Budget concurrency across replicas. |

## 6. Proposed next implementation: bounded allocation execution

**Recommendation:** add a small, configurable, fail-fast allocation bulkhead
and bounded request shape before changing threading or increasing the pool.
Instrument it and establish a workload baseline in the same increment. Keep
the current SKU protocol and synchronous forecast semantics.

### First increment: protect inline execution

- Admit allocation orchestration before registration opens a business
  transaction. Use a shared application-level boundary around allocation plus
  its synchronous projection, not only an HTTP controller or the SKU dispatcher.
  `AllocationProcessor` is the main shared path; audit the alternate
  `BackorderAllocationProcessor` and any direct service callers so they cannot
  bypass the policy or acquire it twice.
- Use non-blocking `tryAcquire`-style admission, without an unbounded semaphore
  wait queue. Hold an admitted-request permit through forecast completion and,
  where applicable, execution-result persistence. Release it on success or
  failure, even when allocation already committed.
  A disconnect alone must not release a permit while work is still executing.
- Cover REST allocation, resolved-product allocation, MCP, RabbitMQ, JobRunr
  and receipt-triggered backorders. Translate a typed capacity rejection at
  each adapter: synchronous HTTP `503`; an explicit retryable tool error for
  MCP; controlled retry/pause/defer for durable workers. Keep input errors
  distinct from overload. Ship RabbitMQ overload handling with this increment,
  not after enabling a bulkhead that its current generic catch would bypass.
- Bound orders, total demand/inbound lines, distinct SKUs, and allowed forecast
  date span before creating per-SKU tasks. Also choose a request-body byte limit
  at the HTTP/broker ingress. These are new compatibility policies requiring
  approved limits, not existing DTO validation guarantees.
- Product resolution can call Ollama/Tavily before allocation starts. Give that
  front-door phase its own bounded external-call/request budget if protecting
  the UI endpoint end to end; never hold a database-work permit across remote
  calls.
- Add admitted/active/rejected counts, permit hold time, completed request rate,
  end-to-end p95/p99, SKU task waiting, projection duration, and Hikari
  active/pending/timeouts. Existing metrics exposure and ledger completion logs
  are useful starting points, not proof all these measurements already exist.

This bounds outstanding orchestrations per instance, not all database activity.
It is the first protective step, not a hard multi-instance database-capacity
guarantee or a bound on queued JobRunr jobs.

### Second increment: protect the shared database budget

Apply a shared execution budget to database-intensive transaction entry points:
registration, new-demand/backorder SKU workers, projection, ledger SKU work and
whole-order expiry. Acquire before opening the transaction and release after
commit/rollback, so waiting for an application permit cannot retain a pooled
connection. Treat a whole-order expiry transaction as one database work unit,
not one permit per locked SKU.

Avoid nested permit acquisition: ledger and expiry already invoke projection
inside their own transaction. Their inner refresh must use the existing
admission context, not wait for another permit from the same exhausted budget.
Do not hold a database-work permit while an outer coordinator waits for child
work to acquire permits.

Reserve maintenance/control-plane headroom rather than assigning the whole
pool to customer allocation. A sizing constraint is:

```text
admitted database work + other active database use + headroom <= pool size
```

An application limiter cannot reserve physical Hikari connections for paths it
does not govern. Include JobRunr persistence and reads in the per-instance
budget, and ensure the sum across replicas fits database capacity. Use controlled
workload shares or another explicit isolation mechanism if maintenance
availability must be guaranteed. Do not copy the default pool size of 10 into a
semaphore limit of 10.

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

RabbitMQ needs an explicit overload path before enabling the common bulkhead:
the current generic catch would otherwise enqueue a JobRunr fallback. Configure
consumer concurrency/prefetch and a deliberate broker backlog/overflow policy,
and choose pause or delayed retry without a tight requeue loop. The current
`default-requeue-rejected=false` also means simply throwing a new overload error
is not a safe "retry later" policy. Do not rely on that setting for backpressure
or discard valid work as if it were invalid input.

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

## 7. Decisions and verification before implementation

Choose the target completion rate, request-shape/date limits, burst duration,
maximum waiting time, synchronous latency objective, backlog size/age, retry
policy, multi-instance budget, maintenance shares and forecast freshness
requirement. Decide the RabbitMQ overload/acknowledgment policy and asynchronous
idempotency/status contract before implementing those adapters. No numerical
admission limits or new API contracts are selected here.

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

Existing PostgreSQL concurrency/recovery tests cover the per-SKU protocol, not
these proposed admission guarantees or production performance targets. The
[`stockio-test-harness/performance/`](../stockio-test-harness/performance/README.md)
area currently contains guidance only.
