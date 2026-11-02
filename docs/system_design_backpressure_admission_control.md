# System design: backpressure and admission control

## Scope

Design discussion for handling allocation traffic such as 1,000 requests per
second, with different orders and SKUs.

**Current** describes inspected Stockio code. **Proposed** describes options,
not implemented guarantees or an approved feature specification. This document
does not change application behavior or select production limits.

The [concurrent-registration feature](../.github/memory/issues/feature-concurrent-document-registration.md)
has now implemented the narrower registration protocol described below.
Admission control and projection redesign remain proposals.

Related material:

- [Stockio architecture](../.github/memory/architecture.md)
- [System design: prevent duplicate allocation](./system_design_interview.md)

## 1. Current execution and bottlenecks

For `POST /api/allocation/jobs?async=false`, the request waits for:

```text
HTTP request
  -> allocation run initialization
  -> document registration under a shared projection gate and scoped locks
  -> per-SKU allocation transactions
  -> allocation run completion
  -> full projection rebuild under the exclusive global projection lock
  -> execution result persistence
  -> HTTP response
```

| Boundary | Current behavior |
| --- | --- |
| SKU dispatcher | `AllocationService` uses `Dispatchers.IO.limitedParallelism(maxConcurrentSkus)`, default 4. The dispatcher belongs to the singleton service: its limit is shared across new-demand requests within one application instance, not allocated separately per request. Additional work waits. |
| Request thread | `runBlocking` keeps the calling HTTP thread occupied while awaiting SKU workers. |
| Global database lock | Registration and SKU workers acquire the shared projection gate. Projection rebuilding, ledger, and expiry acquire its exclusive side. Independent registrations can overlap, but different SKUs still contend with exclusive operations. |
| Registration locks | Sorted namespaced document identities -> sorted inbound SKUs -> run row -> business rows. Existing supply is locked before amendment validation. Document locks protect identities even before their business rows exist. |
| Projection scope | Each processed allocation request invokes a rebuild from the overall operational dataset, rather than only its changed SKU. |
| Database pool | Hikari defaults to 10 connections and a 30,000 ms connection-acquisition timeout. Environment settings can override these defaults. |
| Synchronous response | Processing runs inline but returns `202 Accepted` afterward. It does not create a JobRunr job or gain automatic JobRunr retries. Its returned job-status location is not pollable as a JobRunr job. |
| Asynchronous response | `async=true` enqueues a JobRunr job and returns after enqueue succeeds. This frees the HTTP thread earlier, but does not increase allocation capacity or establish a bounded backlog. |

The four-worker limit is not a global database connection budget: backorder
allocation has its own dispatcher, other workflows also use the pool, and
additional application instances have their own limits.

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
- [StockProjectionProcessor](../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockProjectionProcessor.kt)
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

As another bound, globally exclusive database work must fit within the available
serialized time budget. Increasing SKU concurrency cannot remove that bound.

## 3. Proposed: combine rate limits and admission limits

| Control | Purpose | Proposed overload behavior |
| --- | --- | --- |
| Rate limit | Bound arrival rate, with a small burst allowance. Optionally enforce both caller and service budgets. | Return `429 Too Many Requests`, with retry guidance where meaningful. |
| Synchronous in-flight limit | Cap accepted, still-executing synchronous allocations, even when processing slows. | Reject promptly with `503 Service Unavailable` when capacity is unavailable. Avoid another large waiting queue. |
| Bounded asynchronous backlog | Bound outstanding durable work and its age, not just HTTP connections. | Refuse new submissions when overloaded; return `202` only after successful durable enqueue. |
| Worker concurrency budget | Bound active database-intensive execution, accounting for all entry points and background tasks. | Let only the bounded admitted backlog wait; do not use Hikari's pending borrowers as the work queue. |

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

## 4. Reduce global serialization

The narrower registration protocol is implemented: shared projection gate,
sorted document identity keys, sorted inbound SKUs, run locking, and locked
supply reads. Namespaces distinguish order, order-line, shipment, and
shipment-line identities. Rows in `allocation_document_lock` are retained;
existing business primary/unique/foreign-key constraints remain in force.

Taking SKU locks before the run row avoids holding a run lock while waiting
for a worker that may need run foreign-key checks. Competing document identities
and inbound SKUs serialize, but independent registrations can overlap.

The remaining throughput concern is not simply the number of threads: each
processed request still performs a full, globally exclusive projection rebuild.

Potential target:

```text
Bounded admission
  -> durable queue for asynchronous requests, or bounded inline execution
  -> document/run identity protection
  -> transactional per-SKU allocation
  -> durably recorded projection-refresh requirement
  -> scoped or coalesced projection refresh
```

Implemented registration protections:

- Database identity constraints plus namespaced document/run locks replace
  globally exclusive registration.
- Registration and allocation coordinate inbound changes through SKU locks;
  request identity and per-SKU replay safety remain unchanged.

Remaining projection proposals:

- Rebuilding only affected projection scopes, where the model supports it.
- Coalescing several mutations into fewer projection rebuilds instead of
  rebuilding the overall dataset after every request.

**Do not simply remove the global lock.** It coordinates allocation with
projection, reservation lifecycle, and ledger operations. Define consistent
lock-acquisition ordering, cross-SKU/document behavior, and snapshot consistency
before replacing it. The database lock also coordinates different service
instances; replacing it with only an in-process lock would weaken correctness.

Moving projection work to the background changes freshness guarantees. Define
when forecasts must reflect an allocation and how ledger operations obtain a
consistent baseline. If asynchronous refresh is selected, persist a dirty marker
or outbox record atomically with the relevant mutation, with durable processing
and retry. An after-commit enqueue alone can fail after the mutation commits.

## 5. Threading and persistence options

| Option | Benefit | Limitation and recommendation |
| --- | --- | --- |
| Virtual threads with existing JPA/JDBC | Can reduce the cost of waiting HTTP threads. | Do not create more database capacity or bypass locks. Existing coroutine dispatcher limits remain. Keep bounded admission and evaluate from measurements. |
| Non-blocking repositories/R2DBC | Reduce thread occupancy while waiting for I/O. | Require substantial persistence and transaction changes; do not remove database contention. Not the first change for this bottleneck. A coroutine wrapper does not make JPA/JDBC non-blocking. |
| Larger Hikari pool | May help when the database has unused capacity and useful independent work is connection-limited. | More connections waiting for the same lock can worsen contention. Tune after measuring lock waits and database capacity. |
| More service replicas | Can increase capacity for genuinely independent work. | Each replica adds worker concurrency and connections; the shared global database lock remains. Budget concurrency across replicas. |

## 6. Recommended sequence

1. Establish a baseline: completed requests/second, end-to-end p95/p99 latency,
   transaction and lock-wait time, Hikari active/pending/timeouts, worker waiting
   time, and asynchronous backlog depth/oldest age.
2. Add bounded admission and explicit overload responses. Prefer asynchronous
   submission for bursty workloads, but bound its backlog and execution.
3. Keep the implemented scoped registration protection; address per-request
   full projection rebuilding while preserving allocation and ledger correctness.
4. Tune worker concurrency, connection-pool size, and per-instance budgets from
   measurements. Evaluate virtual threads separately.
5. If sustained accepted load still exceeds completion capacity, reduce accepted
   traffic or add actual processing capacity through further partitioning or
   other measured architectural changes.

## 7. Decisions and verification before implementation

Choose the target completion rate, payload size/SKUs per request, burst duration,
maximum waiting time, synchronous latency objective, backlog limit, and forecast
freshness requirement. No numerical admission limits are selected here.

Plan functional and load checks for:

- Sustained load and bursts, including different SKUs and hot-SKU contention.
- Bounded resource usage and prompt, predictable overload responses.
- Slow databases, lock contention, disconnects, enqueue failures, and retry storms.
- Duplicate requests, partial execution, crash recovery, and multiple instances.
- Queue aging, reservation expiry, and requests crossing the business-date boundary.
- Correct reservation quantities and consistent ledger/projection results under
  the proposed locking and refresh strategy.

These are proposed checks, not existing performance coverage. The
[`stockio-test-harness/performance/`](../stockio-test-harness/performance/README.md)
area currently contains guidance only.
