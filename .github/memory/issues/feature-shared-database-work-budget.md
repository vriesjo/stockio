# Feature: Bound database work while preserving ledger and expiry headroom

- **Status:** Draft
- **Priority:** Not set
- **Bounded Context:** Allocation, Reservations & Stock Ledger / Database Admission
- **Implementation started:** No. Analysis and proposed design only; policies require approval.
- **Parent Specs:** [Architecture](../architecture.md#cross-cutting-concurrency-idempotency-and-rollout), [Allocation admission](../specifications.md#feature-bound-allocation-execution-with-a-fail-fast-bulkhead), [Per-SKU stock processing](../specifications.md#feature-coordinate-stock-processing-through-a-per-sku-protocol)
- **Specification:** [Feature specification](../specifications.md#feature-bound-database-work-while-preserving-ledger-and-expiry-headroom)
- **Scenario:** [Scenario 17](../scenarios.md#scenario-17-preserve-maintenance-admission-under-database-work-pressure)

## Context and scope

The existing opt-in semaphore bounds allocation orchestrations, not their active
database transactions. New-demand and backorder services have separate
four-worker dispatchers; registration, synchronous projection, run metadata,
completion persistence, ledger and expiry also borrow database connections.
Transactions waiting for document/SKU locks can retain those connections.
Hikari bounds connections but does not prioritize maintenance.

This feature proposes a second, explicit per-instance admission layer around
database-intensive work units. Protect ledger and expiry admission from customer
work without changing the existing SKU consistency protocol. This is overload
and availability protection, not a prerequisite for quantity correctness or a
promise of higher throughput.

In scope: allocation run initialization/completion, document registration,
new-demand/backorder SKU transactions, standalone projection, ledger SKU work
and coordinator metadata, whole-order expiry batches, execution-result writes,
and classification of discovery/outbox database access. Receipt-triggered
backorder allocation uses the allocation share, not privileged maintenance
capacity merely because it runs in the background.

Out of scope: distributed permits, hard physical connection reservations,
larger pools, new thread pools, Resilience4j migration, rate limiting, tenant
fairness, durable-backlog caps, asynchronous forecasts, SKU-aware scheduling,
new public endpoints, and reservation/date policy changes. Dedicated pools or
broader borrower admission require separate approval if hard isolation is needed.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Transaction coverage | Apply a shared per-instance database-work budget to allocation run/document writes, new-demand/backorder SKU workers, standalone projection, ledger work/metadata, whole-order expiry and execution-result writes; explicitly classify discovery and outbox database access. |
| FR-02 | Maintenance shares | Enforce an overall covered-work ceiling and explicit workload shares so allocation cannot consume ledger/expiry admission; define protection between ledger and expiry rather than treating one undifferentiated semaphore as reserved maintenance capacity. |
| FR-03 | Transaction lifetime | Acquire admission before the owning transaction begins and release exactly once after commit/rollback and actual work termination; one existing expiry batch transaction consumes one work unit regardless of its order/SKU count. |
| FR-04 | Safe ownership | Reuse admission for nested projection joining a covered ledger/expiry transaction; independent child transactions acquire independently, and coordinators must not hold database permits while awaiting children, retries, cleanup or external scheduling. |
| FR-05 | Recoverable saturation | Distinguish database-phase saturation from pre-write request rejection and business failure; preserve earlier commits and unchanged logical identities, with approved HTTP/MCP errors and recoverable RabbitMQ/JobRunr/maintenance deferral that avoids fallback storms and false success. |
| FR-06 | Preserve stock semantics | Preserve sorted document/SKU/run-or-lifecycle locking, per-SKU replay, atomic expiry, ledger fencing/checkpoints, synchronous forecast recovery, original business dates and hold deadlines; do not advance ledger freshness for deferred/incomplete work. |
| FR-07 | Budget visibility | Expose enabled/configured/active capacity, admission outcomes and work duration by bounded workload/phase labels; correlate with Hikari pending/acquisition/timeout data, database lock waits, ledger freshness and overdue-expiry lag. |
| FR-08 | Controlled rollout | Default enforcement to disabled, validate explicit enabled limits, inventory ungoverned borrowers and replica totals, and enable only after saturation/recovery policies and representative contention checks are approved. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Bounded admission | No unbounded permit-wait queue, extra executor backlog, or waiting/backoff for admission while retaining a transaction or pooled connection. |
| NFR-02 | Existing atomicity | Do not merge allocation phases into one transaction, split whole-order expiry, change lock order, or release a permit on cancellation before transaction completion. |
| NFR-03 | Honest guarantees | Bound covered work per instance, not all physical connections or cluster-wide concurrency; spare permits cannot bypass hot-SKU locks, unavailable scheduler threads, or ungoverned borrowers. |
| NFR-04 | Bounded observability | Use fixed workload, phase and outcome labels; exclude request/job/order IDs, SKUs, customer data, payloads and exception messages from metric tags. |
| NFR-05 | Explicit implementation | Prefer small explicit permit ownership around existing transaction boundaries and current Micrometer/scheduling infrastructure; do not add a limiter framework, schema or scheduling subsystem without a demonstrated approved need. |

## Implementation plan

1. Baseline enabled allocation admission, enumerate transaction owners and bypass borrowers, and approve workload shares, saturation/retry policies, maintenance objectives and per-instance/replica sizing.
2. Introduce opt-in validated database-work configuration, explicit workload admission/ownership and bounded-label metrics; prove class/total invariants and failure-safe acquisition/release.
3. Wrap transaction entry points outside Spring transaction creation, including run/result writes and metadata; reuse ownership for joined projection and preserve expiry batches and nontransactional orchestration.
4. Integrate distinct database-capacity errors and recoverable adapter/maintenance deferral, including partial allocation, failed projection/completion, ledger ownership and cleanup transitions.
5. Add focused unit, PostgreSQL and adapter tests for ceilings, maintenance shares, proxy/lifetime boundaries, nested reuse, cancellation, contention and idempotent partial recovery; measure representative independent/hot SKUs.
6. Document measured sizing, uncovered borrowers, replica budgets, retry/date caveats and rollout; synchronize scenario, specification, issue, architecture, service/API guidance and active context before opt-in enablement.

## Functional scenario

### Gherkin description
**Given** database-work admission is enabled with approved allocation, ledger and expiry shares \
**And** allocation work occupies its entire permitted database-work share \
**When** more allocation work and due ledger or expiry work are triggered \
**Then** allocation cannot consume the protected maintenance admission shares \
**And** maintenance can be admitted within its own limits independently of allocation-share saturation \
**And** an unadmitted work unit opens no transaction and borrows no connection for that unit \
**And** nested projection reuses its owning ledger or expiry transaction's admission \
**And** deferred work preserves prior commits and retry identity without reporting false completion

### High-level technical analysis
- Keep the existing request semaphore and add a separate per-instance transaction-work budget with explicit workload shares
- Place admission outside transactional proxies/templates; retain ownership until commit/rollback, including commit failures
- Count one expiry batch transaction once; joined projection reuses its owner, while independent SKU transactions acquire separately
- Classify receipt-triggered backorders as allocation; release ledger work before whole-order cleanup and reacquire for a subsequent ledger attempt
- Handle database-phase saturation separately from pre-write request rejection because registration or other SKU transactions may already have committed
- Account explicitly for metadata, reads, outbox access, JobRunr storage and all replicas; maintenance admission is not a physical connection or completion-time guarantee

### Acceptance Criteria
- Covered active transactions never exceed configured class/total ceilings, even across concurrent new-demand, backorder, projection and background callers
- Allocation-share saturation alone cannot deny available ledger/expiry admission; verify the approved protection between the two maintenance classes as well
- Rejected units do not enter their transaction or borrow its connection, and acquired permits remain occupied through commit/rollback and actual cancellation completion
- Ledger/expiry projection consumes no second permit; expiry remains atomic across every selected order's complete SKU set and all affected forecasts
- Coordinators and deferred workers retain no database-work permit/connection while waiting for children, retry backoff, cleanup or external scheduling
- Saturation after partial SKU commits, allocation before projection, or projection before result persistence remains resumable without duplicate reservations or physical movements
- Background saturation preserves recoverable work without fallback storms, unintended dead-lettering, false success, or falsely advancing ledger freshness; dates and hold deadlines remain unchanged
- Disabled enforcement preserves existing behavior, invalid enabled configuration fails startup, and metrics/bypass documentation distinguish local admission from physical or distributed guarantees

## Technical analysis and affected components

The following is the inspected baseline, not implemented budget coverage.

| Component | Current boundary | Proposed responsibility |
| --- | --- | --- |
| [AllocationAdmission](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationAdmission.kt) / `AllocationProcessor` | One request permit through allocation, projection and optional completion; disabled by default. | Keep this independent guard; do not reinterpret a request permit as a transaction permit. |
| [AllocationRunService](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationRunService.kt) | Separate transactional run creation/completion plus read paths. | Cover standalone run writes; joined reads inside a worker reuse its transaction rather than double-acquiring. |
| [AllocationDocumentService](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationDocumentService.kt) | Transactional registration precedes worker fan-out; deadlines start here. | Admit before transaction creation; retain identity/SKU/run lock order and original deadlines on retry. |
| [AllocationService](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationService.kt) / [BackorderAllocationService](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/BackorderAllocationService.kt) | Separate limited dispatchers; no enclosing orchestration transaction. | Keep coordinators permit-free; classify standalone discovery reads and each worker's database unit. |
| [AllocationSkuWorker](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/tools/AllocationSkuWorker.kt) | Proxied `@Transactional` SKU methods; connection can be occupied while waiting for locks. | Surround the proxied transaction call with allocation-class admission, not an acquisition inside the method body. |
| [StockProjectionProcessor](../../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockProjectionProcessor.kt) | `TransactionTemplate` per standalone SKU; `processSku` joins ledger/expiry through `@Transactional`. | Admit outside standalone templates/proxies; reuse verified transaction ownership on joined calls and audit all direct callers. |
| [StockLedgerProcessor](../../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockLedgerProcessor.kt) / [StockLedgerSkuWorker](../../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockLedgerSkuWorker.kt) | Nontransactional coordinator, fenced SKU transactions; overdue holds trigger rollback then external whole-order cleanup. | Classify ledger metadata and workers; keep coordinator wait/dispatch outside permits and hand cleanup to expiry admission after rollback/release. |
| [StockLedgerRunJdbcRepository](../../../stockio-service/src/main/kotlin/nl/stockio/stock/infra/StockLedgerRunJdbcRepository.kt) | Transactional begin/complete/fail/dispatch metadata; mandatory joined worker/checkpoint/outbox writes; standalone reads. | Budget owning transactions, reuse joined ownership, and preserve fencing/lease recovery when metadata itself cannot be admitted. |
| [ReservationCleanupService](../../../stockio-service/src/main/kotlin/nl/stockio/reservation/domain/service/ReservationCleanupService.kt) | Candidate discovery and expiry batch share a transaction; all selected orders and projections commit together. | One expiry permit around the existing transaction, not one per order/SKU; protect direct and ledger-triggered cleanup entry points. |
| [AllocationJobExecutor](../../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt) / adapters | Cached result reads bypass request admission; completion uses repository writes; existing overload recovery recognizes allocation capacity. | Cover result writes, account for read bypasses, and extend recovery classification for a distinct database-phase signal. |
| [StockReceiptFollowupDispatcher](../../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/job/StockReceiptFollowupDispatcher.kt) / JobRunr | Pending read, scheduler enqueue, then durable dispatch/failure metadata; separate JobRunr storage access. | Classify database portions only; do not retain database permits across scheduler calls or silently exempt writes needed for recovery. |

Persistence ports and the existing JPA/JDBC adapters retain their stock and
identity responsibilities. No new business table, migration, transport or
external integration is proposed. Audit transaction propagation and production
callers before choosing wrappers; a public/direct entry point must not bypass
coverage merely because the usual orchestrator is guarded.

### Why a second layer can help

Illustration only: with four request permits and a ten-connection pool, one
new-demand request can occupy four worker connections, a backorder request four
more, and two other requests one each for projection and registration. All ten
connections can be occupied by four admitted orchestrations. Ledger/expiry are
not covered by the request semaphore and may wait for connections.

This can delay release of expired holds and physical-ledger freshness. It does
not mean the current lock protocol is incorrect, or that a budget necessarily
improves throughput. First measure whether request admission alone suffices.

### Proposed ownership and maintenance policy

Use explicit in-process admission, not another executor. Recommended starting
design: non-waiting acquisition, a covered-work ceiling and non-borrowable
allocation/ledger/expiry shares. Exact counters/semaphore composition is an
implementation choice; acquisition must preserve class/total limits atomically
or roll back partial reservations immediately without waiting.

Illustration only: allocation 6, ledger 1 and expiry 1 permit gives at most 8
covered transactions, leaving a planning allowance of 2 in a ten-connection
pool. These are not selected defaults. Keeping ledger and expiry distinct avoids
assuming that a shared maintenance semaphore prevents either from starving the
other. Work-conserving borrowing and fairness scheduling are deferred.

```text
request admission, where applicable
  -> orchestration without a database-work permit
  -> acquire workload admission outside any transaction
       -> begin owning transaction
       -> existing sorted locks, reads/writes and joined projection
       -> commit/rollback
     release workload admission
  -> next independent phase acquires its own admission
```

Calling `tryAcquire` inside an `@Transactional` method body is too late: Spring's
proxy has already started the transaction. Prefer an explicit wrapper calling
the transactional collaborator, or a guard outside the existing transaction
template. Release after the proxy/template returns, including commit failure.
Do not add an outer transaction across worker fan-out.

Ownership reuse applies only to the same already-admitted transaction, not an
arbitrary active transaction or coroutine thread. Separate worker transactions
need separate permits. Do not rely on a thread-local token following coroutine
execution; audit propagation and reject unsupported entry/ownership combinations.
Avoid introducing `REQUIRES_NEW` nesting that retains an outer permit/connection
while acquiring another. Ledger cleanup must occur after the failed SKU
transaction releases its ledger permit; retry the SKU with fresh admission.

### Saturation, partial progress and recovery

Recommended policy, pending approval: reject a database unit immediately when
its class is full, return a distinct retryable HTTP 503/MCP capacity error for
inline execution, and defer recoverable background work through existing
mechanisms without consuming ordinary business retries. Exact code and retry
timings remain open. No automatic UI retry or implicit asynchronous fallback.

Do not reuse `AllocationCapacityExceededException` unchanged: its current
message says the execution attempt was not admitted. Database-phase rejection
can follow run creation, registration, committed SKUs, or completed allocation
before projection/result persistence. The refused unit performs no work, but
the whole request may have made durable progress. Retry with the unchanged
logical request and payload; neither report a rollback of earlier phases nor
invent durable automatic recovery for an inline caller.

RabbitMQ's capacity branch and the JobRunr capacity state filter must recognize
the new signal without generic fallback, tight loops or false success.
Maintenance jobs need their own approved recoverable policy too: current
allocation-specific filtering does not automatically cover ledger/expiry.
Keep ledger checkpoints/ownership safe when begin/fail/complete metadata cannot
be admitted; never hold a worker permit merely to persist retry bookkeeping.
Do not advance freshness until actual completion. Existing lease/date checks
still apply, and a delayed retry is not guaranteed to remain business-valid.

### Headroom and deployment limits

Inventory Hikari borrowers: status/cached reads, catalogue/product-cache access,
scope discovery, outbox access, health checks, JobRunr storage and other writers.
Explicitly classify them as covered units or measured/accounted bypasses.
Planning constraint:

```text
covered database work + other active database use + headroom <= pool size
```

This assumes one connection per covered unit; audit propagation before relying
on it. An in-process limiter cannot physically reserve connections consumed by
uncontrolled borrowers. A spare permit also cannot defeat a same-SKU lock or
an occupied JobRunr worker pool. Hard maintenance latency/connection guarantees
would require broader admission/isolation and bounded execution assumptions.

Document combined budgets and connections across the maximum replica count,
deployment overlap and any external database clients. Local limits are not
distributed permits. Keep enforcement off until all participating writers and
recovery adapters are deployed; mixed covered/uncovered instances weaken the
resource bound even though the SKU consistency protocol remains unchanged.

## Open decisions before implementation

| Decision | Proposed direction / unresolved choice |
| --- | --- |
| Need and priority | Priority unset. Confirm pool/lock pressure or maintenance objectives justify another layer after tuning request admission. |
| Shares and sizing | Non-borrowable allocation/ledger/expiry shares under a total ceiling; approve exact classifications, positive limits, pool headroom and replica budget from measurements. |
| Saturation policy | Recommend immediate admission attempt; approve it versus bounded pre-transaction waiting, and approve distinct HTTP/MCP code plus background backoff/jitter. |
| Maintenance recovery | Approve ledger/expiry scheduling, retry accounting, metadata-admission failure handling and lease recovery without changing business dates or freshness semantics. |
| Bypass coverage | Decide which standalone discovery, outbox, product/cache and operational reads need admission versus explicit measured headroom; JobRunr storage is not automatically governed. |
| Operational objectives | Set acceptable ledger freshness, overdue-expiry lag, allocation latency and saturation thresholds; no numerical production SLO is assumed. |
| Enablement | Proposed disabled-by-default enforcement with explicit positive configuration; agree coordinated writer/adapter rollout and legacy in-flight reconciliation. |

## Verification and implementation acceptance

- Unit tests: class/total ceilings, failed composite acquisition rollback,
  maintenance shares under allocation saturation, disabled behavior, configuration,
  bounded metric tags and exactly-once release on failure/cancellation.
- Real PostgreSQL tests: prove admission precedes transaction/connection entry,
  retain permits through commit/rollback, preserve independent/hot-SKU and
  multi-SKU expiry behavior, and avoid nested admission at a one-slot boundary.
- Extend existing [allocation integration tests](../../../stockio-service/src/test/kotlin/nl/stockio/allocation/domain/service/AllocationIntegrationTest.kt)
  and projection/ledger/adapter tests with saturation after partial SKU commits,
  projection failure and completion-persistence refusal; retry without duplicate
  reservations, receipts or deliveries.
- Exercise ledger rollback -> whole-order cleanup -> ledger retry, deferred
  metadata writes, stale ownership/date boundaries, recoverable broker/job
  deferral and recurring maintenance execution without false completion.
- Use the existing [performance harness guidance](../../../stockio-test-harness/performance/README.md)
  for representative allocation/backorder/maintenance contention, connection
  waits and tail latency; demonstrate uncovered borrowers and multiple replicas
  remain visible rather than claiming hard physical isolation.
- Run the smallest relevant service compilation/tests and available formatting
  checks during implementation; update OpenAPI/UI forwarding only if the
  approved error contract requires it.
- Before enabling, verify metrics collection, pool/replica sizing, all caller
  coverage and an operational rollback to disabled enforcement. This document
  itself changes no runtime behavior or production configuration.

## Resources

- [Backpressure design discussion](../../../docs/system_design_backpressure_admission_control.md#second-increment-protect-the-shared-database-budget)
- [Implemented allocation admission](./feature-fail-fast-allocation-admission.md)
- [Implemented per-SKU protocol](./feature-per-sku-stock-processing.md)
- [Active context](../active-context.md#feature-bound-database-work-while-preserving-ledger-and-expiry-headroom)
