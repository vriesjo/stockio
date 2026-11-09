# System design interview: prevent Stockio from allocating the same stock twice

## Purpose and source

Use this guide to rehearse a Java/Spring system design interview using Stockio
as your concrete example. The repository is implemented in Kotlin on the JVM;
the transaction, concurrency, persistence, and messaging principles translate
directly to Java.

Inspired by Arvind Kumar's article,
[System Design Interview: How Would You Prevent a Payment from Being Processed Twice?](https://medium.com/javarevisited/system-design-interview-how-would-you-prevent-a-payment-from-being-processed-twice-724aded39642).
The initial version used the public introduction because the full-text link
returned HTTP 403. This revision was checked against the supplied
full interview PDF supplied in the conversation (`systen_interview.pdf`, not
currently present in `docs/`), particularly pages 2-10, which
contain the interview and its summary. The page references in Section 14 refer
to PDF pages, not article section numbers. This remains an original
Stockio-focused adaptation rather than a reproduction of the article.

The central adaptation is:

> How would you prevent an order from reserving inventory twice, while also
> preventing two different orders from reserving the same available units?

**Scope:** interview preparation, not an approved feature specification or an
implementation plan. Sections labeled **Current** describe inspected code.
Sections labeled **Proposed** describe design options to discuss, not guarantees
Stockio already provides.

## 1. The interview prompt

**Interviewer**

You operate Stockio, an inventory allocation service. Customers submit orders
containing multiple SKU lines. Supply comes from physical stock lots and expected
inbound shipments. Stockio creates reservations, records shortages as backorders,
and maintains forecasts. A separate ledger process applies receipts and confirmed
deliveries to physical stock.

Requests can arrive through HTTP, MCP tools, Kafka, or RabbitMQ. The HTTP
asynchronous path publishes to Kafka, and that consumer is on by default.
Clients retry after timeouts, Kafka and RabbitMQ can redeliver, JobRunr can
retry a fallback job, and several application instances may execute concurrently.

Design the system so that:

- Repeating one logical allocation does not create additional reservations.
- Different orders cannot over-reserve the same stock.
- A crash halfway through a multiline order can be recovered safely.
- Repeating ledger synchronization does not receive or deliver stock twice.
- Failures remain visible and recoverable.

Explain the APIs, identities, database constraints, transaction boundaries,
concurrency strategy, retry behavior, and operational tradeoffs. Then explain
how you would implement the critical boundaries in Java and Spring.

### Suggested 65-minute practice session

| Time | Discussion |
| --- | --- |
| 0-5 minutes | Clarify the business meaning of "twice" and the required consistency. |
| 5-15 minutes | Draw the architecture, API, and data model. |
| 15-30 minutes | Explain idempotency, contention, and transaction boundaries. |
| 30-45 minutes | Walk through crashes, redelivery, and post-commit scheduling. |
| 45-55 minutes | Answer Java/Spring questions and discuss testing. |
| 55-65 minutes | Discuss scaling, limitations, and a concise closing answer. |

Practice answering each question aloud before reading its answer.

### Start with uncertainty, not duplicate clicks

The article's opening distinction is important: a timeout means the caller does
not know the outcome. It does not mean the operation failed.

In Stockio, a caller can lose the response after reservations commit, or a worker
can lose its acknowledgment after processing a message. Disabling a button or
debouncing HTTP requests does not address either case. The design must make
retries safe and let the caller discover the authoritative outcome without
inventing a new operation.

## 2. Clarify the requirements before drawing boxes

**Interviewer: What do you ask first?**

**Strong candidate answer**

"I would distinguish duplicate delivery from a second legitimate business
operation. Then I would establish the inventory and recovery invariants."

| Clarification | Stockio-specific answer or decision |
| --- | --- |
| What is a duplicate request? | The same `requestId` representing the same allocation payload and operation. |
| What if the caller chooses another request ID? | An existing `orderId` must not become new demand again. Transport identity and business identity are separate protections. |
| Can an order have repeated SKU lines? | Yes. `orderLineId`, not SKU alone, identifies demand within an order. |
| Must a whole request commit atomically? | **Current:** no. Documents initialize transactionally; SKU operations commit independently and can be resumed. |
| Is a reservation a physical shipment? | No. Allocation reserves supply; ledger synchronization handles physical movements. |
| Can an allocation be partial? | Yes. Remaining demand becomes a backorder. |
| Is expected inbound equivalent to received stock? | No. Expected supply can be reserved subject to delivery eligibility; ledger processing changes receipt state. |
| Do holds last forever? | No. Pending orders have an order-level deadline and may expire. |
| Is global priority ordering required across all arriving requests? | Do not assume it. Sorting a selected batch by priority/FIFO is not a global scheduler for future or concurrent requests. |
| What traffic and latency should we target? | Ask the interviewer. Do not present invented traffic estimates as repository measurements. |

### Invariants to write on the whiteboard

1. One `requestId` identifies one payload and operation; conflicting reuse fails.
2. One new-demand order and its lines have one owning run.
3. Reserved physical quantity cannot exceed the corresponding on-hand quantity;
   reserved expected quantity cannot exceed available expected supply.
4. A committed SKU mutation and its replay checkpoint must agree.
5. Replaying an allocation does not renew its reservation deadline.
6. A received shipment line cannot be received again, and an applied delivery
   cannot be consumed again.
7. A completed historical allocation is not proof that its reservation is still
   active now.

The first two invariants address duplicate intent. The third addresses competing
intent. Neither substitutes for the other.

## 3. Ground the discussion in the actual project

**Current:** Stockio is a modular Spring Boot service, not a collection of
independently deployed inventory microservices. PostgreSQL is the system of
record. JPA manages operational entities; JDBC manages derived stock snapshots.
Kafka is the default asynchronous intake. RabbitMQ remains a second consumer.
JobRunr runs fallback allocation after a consumer processing failure, plus
ledger sync and receipt follow-up dispatch.

```text
HTTP allocation request, async=true
  -> AllocationController
  -> AllocationJobExecutor.executeAsync
  -> Kafka publish, then 202 after the broker acknowledges
  -> AllocationKafkaListener
       -> AllocationJobExecutor.executeSync
       -> same record retried on capacity or lock contention
       -> JobRunr fallback on other execution failure
                         |
RabbitMQ -> AllocationRabbitListener
              -> AllocationJobExecutor.executeSync directly
              -> same pause-and-retry on capacity or lock contention
              -> JobRunr fallback on other execution failure
                         |
MCP tools --------------> shared allocation processing
                         |
                  AllocationProcessor
                         |
                  AllocationService
                         |
            initialize documents and order ownership
                         |
            transactional workers, one per SKU
                         |
              persist completed allocation run
                         |
                refresh derived projections
                         |
               persist job completion result

Independent ledger trigger
  -> StockLedgerProcessor, one SKU transaction at a time
  -> expire overdue holds outside that transaction when required
  -> rebuild projection + apply movements + write receipt follow-up
  -> after the run completes: dispatch pending follow-up to JobRunr
  -> a minute job dispatches any follow-up still pending

Operational state, checkpoints, projections, and JobRunr storage -> PostgreSQL
```

**Important:** asynchronous HTTP publishes to Kafka and returns `202` after the
broker acknowledges. It does not enqueue JobRunr on the success path, and it
does not use RabbitMQ. There is no application transaction around that publish.
The Redis fast path is off unless `STOCKIO_REDIS_PATH_ENABLED=true`. When it is
on, only `async=true` is accepted: a Redis hold is taken before the Kafka
publish, and PostgreSQL remains the ledger. The hold is an integer per SKU and
warehouse, the sum of available quantity across lots.

Receipt follow-up is a row in `stock_receipt_followup`, inserted in the same
SKU transaction as the receipt. Dispatch happens after the ledger run completes
and again from the recurring dispatcher. It is not an in-memory after-commit
callback.

### API accuracy matters

The current controllers declare these paths:

| Operation | Current controller mapping |
| --- | --- |
| Submit asynchronous allocation (default `async=true`) | `POST /api/allocation/jobs` |
| Poll a job | `GET /api/allocation/jobs/{jobId}` |
| Read reservation lifecycle | `GET /api/reservations/orders/{orderId}/status` |
| Read ledger freshness | `GET /api/stock/ledger/status` |
| Additional ledger read mapping | `GET /api/allocation/stock-ledger/status` |

The asynchronous response is `202 Accepted` with `{jobId, requestId}` and
`Location: /api/allocation/jobs/{jobId}`. Successful status polling returns
`{jobId, status, result: {jobId, allocationRun}}`; completion no longer has a
top-level `version` or `stockSync`. Stored allocation-run `contractVersion`
remains a separate concern. Old `/api/logistics/...` and `/api/allocation-jobs`
references are not additional supported mappings.

**Current limitation:** `async=false` executes inline but still responds `202`,
using the allocation `runId` as `jobId`. No JobRunr job is created, and completion
is stored under a different generated execution ID. Do not present its returned
status URL as a working polling contract. An interview improvement would return
the synchronous completion directly, or explicitly create a durable execution
record with a consistent identity; neither refinement is implemented here.

An allocation request uses this shape; dates are illustrative, and the HTTP
controller requires `asOfDate` to equal today according to the business clock
before either publishing or executing inline:

```json
{
  "requestId": "11111111-1111-4111-8111-111111111111",
  "asOfDate": "2026-10-24",
  "runType": "NEW_DEMAND",
  "demandOrders": [
    {
      "orderId": "ORDER-100",
      "customer": "Example Customer",
      "priority": 1,
      "placedDate": "2026-10-24",
      "lines": [
        {
          "orderLineId": "ORDER-100-L1",
          "sku": "MAKI-ZALM",
          "requestedQuantity": 80,
          "requiredDeliveryDate": "2026-10-25"
        }
      ]
    }
  ],
  "inboundShipments": []
}
```

## 4. Round one: "Use an idempotency key" is only the beginning

### Q1. Who generates the key, and when?

**Answer**

"The caller creates a stable `requestId` for one logical operation before
submitting it. It persists and reuses that ID and payload across timeouts,
network retries, and redelivery. A fresh UUID on every retry defeats request
deduplication."

**Current:** `AllocationRequest` carries a UUID `requestId`. The HTTP executor
generates a separate `jobId` for each submission. RabbitMQ execution uses
`requestId` as its execution `jobId`.

Distinguish the identities:

| Identity | Responsibility |
| --- | --- |
| `requestId` | Stable logical allocation identity. |
| `jobId` | Execution/status identity; not necessarily the logical request ID. |
| `runId` | Persisted allocation result identity. |
| `orderId` / `orderLineId` | Business demand ownership and line identity. |
| `shipmentId` / `shipmentLineId` | Shipment membership and individual supply identity. |

Multiple HTTP submissions can therefore create multiple execution jobs while
converging on one allocation run. Idempotent business effects do not imply one
job, one log entry, or one HTTP response.

**Follow-up: could we derive the key from request attributes instead?**

Yes, but deterministic does not automatically mean correct. For example, hashing
`orderId`, the ordered line data, and the operation can produce a stable value.
First define which repeated actions represent the same business operation.

| Approach | Benefit | Interview trap |
| --- | --- | --- |
| Generate a UUID once per logical command | Separates retry identity from business data. | Regenerating it on timeout turns a retry into another command. |
| Derive a key from canonical business attributes | Independent callers can reproduce it if they share the same identity contract. | It can collapse legitimate later operations, or create a new key when a changed payload should have been rejected as conflicting reuse. |

In Java, `UUID.randomUUID()` is a suitable starting point for an opaque command
key; persist that value before sending and reuse it. For a derived key, hash an
unambiguous, versioned encoding, not naive string concatenation: field boundaries
and ordering must be explicit. Hashing improves representation; it does not
decide whether two operations are semantically the same.

**Current Stockio distinction:** `requestId` identifies an allocation command;
`requestHash` validates its payload. They are not interchangeable. A new
backorder-fulfillment command can legitimately operate on an existing order
without resubmitting it as `NEW_DEMAND`.

Retrying an incomplete command preserves its ID. Intentionally triggering a
later fulfillment is a new command with its own ID and the same business order
identities. Neither replaying a completed command nor changing its ID should be
used to renew an expired hold. A deliberate order amendment or replacement
needs an explicit business policy; it is not an automatic retry mechanism.

### Q2. What if the same key is reused with a different quantity?

**Answer**

"I store a fingerprint of the request with its key and compare it on replay.
Changing quantity, operation, dates, or other meaningful data must not silently
return an unrelated success."

**Current:** `requestHash` computes SHA-256 over Jackson-serialized typed data.
Run initialization/replay validates the hash and operation; initialization also
checks the contract version and as-of date. Conflicts are rejected.

Do not call this universal semantic canonicalization. List order and serialization
behavior matter, and the multiline contract explicitly preserves line ordering.
An API evolution needs an explicit compatibility policy rather than hoping a
new serializer produces the old fingerprint.

**Proposed:** define an explicit canonical, versioned command representation if
cross-language clients or contract changes require equivalence beyond the
current typed serialization. Keep any sequence that affects allocation policy.

Also inspect fingerprint coverage: `BackorderAllocationService` currently hashes
an internal `AllocationRequest`, which does not include the command's `skuScope`
or `trigger`. Do not claim that hash proves equality of every backorder command
field. Stable generated receipt IDs help, but a stricter design would define
whether scope belongs in the immutable command identity.

### Q3. Two identical requests arrive at exactly the same time. What wins?

**Answer**

"Not a Java `if` statement. PostgreSQL arbitrates ownership."

**Current:** `allocation_run.request_id` has a unique constraint. The repository
uses an atomic insert-if-absent:

```sql
INSERT INTO allocation_run
    (id, request_id, request_hash, run_type, as_of_date,
     open_backorder_count, completed, created_at)
VALUES
    (:id, :requestId, :requestHash, :runType, :asOfDate,
     0, false, :createdAt)
ON CONFLICT (request_id) DO NOTHING;
```

Both callers then read the persisted run and validate its identity. Matching
requests can resume that run; a conflicting request fails. Later SKU locks and
checkpoints prevent both callers from applying the same reservation mutations.

A check-then-insert without a unique constraint races. `synchronized`, a static
map, or a Spring singleton protects at most one JVM, not multiple instances.

**Follow-up: what does the loser return if the winner has not finished?**

An existing key does not imply a completed response exists. Do not return an
empty success or start an uncoordinated second allocation.

**Current:** an incomplete run can be resumed through coordinated workers.
Concurrent work may wait for the SKU lock and then reuse a checkpoint or the
completed run. HTTP submissions return an execution `jobId`. Status prefers a
JobRunr job when one exists and returns its result only in `SUCCEEDED`. A Kafka
success does not create a JobRunr job: the listener records
`allocation_job_tracking`, and a saved `allocation_job_result` is reported as
`SUCCEEDED`. Unknown ids are `404`. This is not a lookup by `requestId`.

**Proposed API contract:** distinguish a completed replay, matching in-progress
work, and a conflicting payload. Return the saved logical result for completed
work; for in-progress work, return `202 Accepted` with a durable status reference
and optional retry guidance. If clients must recover after losing the initial
acceptance response, persist a request-to-status mapping or expose lookup by
request identity. A bounded wait is another option, not an excuse to hold a
database transaction open indefinitely.

Replay the original allocation result, not a fresh calculation using today's
stock. Its current reservation lifecycle remains a separate query. A historical
success can coexist with a subsequently expired hold.

### Q4. What if the customer sends a different key for the same order?

**Answer**

"Request deduplication is insufficient. I also enforce business ownership."

**Current:** `AllocationDocumentService` claims the order for a run, persists its
lines, and rejects another run claiming the same order. `demand_order.order_id`
and `demand_order_line.order_line_id` are primary keys. Replays must preserve the
stored header, line contents, ordering, and parent membership.

Two legitimate lines can share a SKU, so `UNIQUE(orderId, sku)` would be the wrong
substitute for line identity.

**Follow-up:** if the caller changes both request ID and order ID for the same
real-world purchase, Stockio cannot infer that they represent one purchase.
Upstream business identifiers or an agreed external-order uniqueness rule are
needed. Payload similarity is not a safe general-purpose deduplication rule.

## 5. Round two: prevent overselling, not just duplicate requests

### Q5. There are 100 units. Two different orders each request 80. What happens?

**Answer**

"Both are valid, distinct operations. Idempotency does not stop overselling.
I must serialize or atomically validate access to the shared supply."

Assuming one eligible physical lot, no inbound supply, and active holds, one
order can reserve 80. The next sees only 20 available, reserves 20, and
backorders 60. Total reserved is 100, not 160.

**Current worker transaction:**

1. When lock-contention control is enabled, set a transaction-local lock timeout and statement timeout. It is off by default.
2. Create the SKU lock row if missing and acquire its pessimistic write lock.
3. Check the `(runId, sku)` replay checkpoint and completed run.
4. Lock eligible supply and stock and read relevant reservations.
5. Calculate allocation and persist counters, reservations, and backorders.
6. Persist that SKU's serialized result checkpoint in the same transaction.
7. Commit, releasing the locks.

The SKU lock also coordinates the absence of supply rows: locking existing stock
rows alone would not serialize a SKU for which no stock exists yet.

The losing transaction observes the updated state after acquiring the lock.
Which request obtains it first is not a guarantee of global business priority.

### Q6. Why both a projection lock and a SKU lock?

**Answer**

"The current service does not take both. The SKU lock is the coordination row.
The global projection lock is leftover schema."

**Current:** `stock_projection_lock` remains in the database, and tests still
know the row. No production allocation, projection, ledger, or expiry path
acquires it. Writers share `allocation_sku_lock`. Different SKUs proceed
together. One transaction at a time holds a given SKU, including that SKU's
allocation, projection rebuild, ledger application, and inbound amendment.
Whole-order expiry locks every SKU on the order, in sorted order.

Document registration locks sorted document identities, then sorted inbound SKU
rows, then the run row. Order-only documents do not take a SKU lock at
registration, so two orders for the same SKU can register together. Existing
supply rows are locked before amendment validation. The run lock is taken after
the SKU locks so a registration does not block a worker's foreign-key checks
while that worker waits for its SKU.

The SKU lock is stronger than a single stock-counter update: the ledger worker
rebuilds that SKU's forecast and applies receipts, hold conversions, and
deliveries in the same transaction.

**Tradeoff:** a hot SKU still serializes, and a lock wait can hold a pooled
connection. There is no global exclusive phase to remove. Adding instances does
not make two writers update the same SKU at once. Opt-in lock-contention
control can fail that wait as `503` `ALLOCATION_LOCK_CONTENTION` instead of
holding the connection. It is off by default.

**Alternative discussion:** optimistic versioning or a conditional update such as
`UPDATE ... WHERE available >= requested` can work for a simpler counter.
Stockio also needs FIFO lots, inbound eligibility, multiline reservations, and
backorders; replacing its locks is not a one-line redesign.

### Q7. Is a multiline allocation one big transaction?

**Answer**

"No. I would describe it as a resumable workflow with atomic per-SKU work, not
an all-or-nothing order transaction."

**Current:** `AllocationService` dispatches bounded parallel SKU work using Kotlin
coroutines. Each worker method is a separate transactional Spring bean entry
point. A successful SKU can commit even if another SKU fails.

`allocation_sku_operation` has a unique `(run_id, sku)` constraint and stores the
request hash and serialized result. Finalization locks the run, verifies that
the expected SKU operations exist, writes ordered allocation records/sources,
marks the run complete, and removes intermediate checkpoints transactionally.

If SKU A commits and SKU B fails, retry reuses A's checkpoint and retries B.
After finalization, the saved completed run replaces the checkpoints as the
replay source.

**Follow-up: what if the business requires all lines or none?**

That is a different requirement. Discuss a single transaction with stable
multi-SKU lock ordering, or a durable reservation/compensation workflow. Both
change contention, failure semantics, and the externally visible lifecycle.
Do not claim the current parallel workflow already provides atomic order-wide
allocation.

## 6. Round three: crash recovery and messaging

### Q8. The database commits a reservation, then the process crashes. Now what?

Walk through the exact boundary rather than saying "retry it":

| Failure point | Current recovery behavior or limitation |
| --- | --- |
| Before the Kafka publish is acknowledged | No `202` is returned; the publish error propagates. With the Redis fast path on, a failed publish rolls the hold back. |
| After Kafka acknowledges, before `202` reaches the caller | The record may already be consumed. Retry the same logical request; HTTP still publishes a new job id. |
| Before a SKU transaction commits | Its mutations and checkpoint roll back together. |
| After a SKU commits, before all SKUs finish | Retry reuses committed SKU checkpoints and processes unfinished work. |
| After run completion, before projection succeeds | Retry reuses the completed allocation and retries the projection; it does not reserve again. |
| After projection, before job-result persistence | Retry can replay the run and rebuild projections before saving completion. |
| After job-result persistence, before acknowledgment/response | Execution can return the saved completion result. Kafka or RabbitMQ may still redeliver that execution. |
| After a receipt commits, before JobRunr accepts the follow-up | The receipt and a `stock_receipt_followup` row commit together. The minute dispatcher, and the dispatch after a completed ledger run, enqueue from that row. |

**Current:** `AllocationProcessor` deliberately avoids one outer transaction
spanning parallel workers and projection refresh. It wraps a post-allocation
projection `IllegalArgumentException` as a retryable processing failure rather
than letting RabbitMQ classify it as invalid demand.

The recovery objective is the same allocation effect, not necessarily the same
number of projection rebuilds.

### Q9. Kafka or RabbitMQ delivers the same request twice. Do you need exactly-once delivery?

**Answer**

"I design for at-least-once delivery and idempotent database effects.
Acknowledgment is not atomically tied to every database commit."

**Current:** the Kafka listener is the default consumer. It calls
`AllocationJobExecutor.executeSync` with the record key as the job id. HTTP
publishes that key as a new UUID. A missing key falls back to `requestId`; a
key that is not a UUID is rejected. The RabbitMQ listener uses
`request.requestId` as the job id. Both run the work directly. Capacity and
lock contention pause 5–10 seconds and retry the same delivery; they do not
enqueue JobRunr. Other execution failures enqueue a JobRunr fallback when that
job id is not already present. Invalid input and an oversized body are
dead-lettered.

The durable allocation run, SKU checkpoints, order ownership, and reservation
constraints are the deeper protection. A job existence check alone is not the
business correctness boundary.

**Implementation caveat:** both fallback lambdas log and then call
`executeSync`. JobRunr parses the lambda rather than running arbitrary closure
code. A logging call inside that lambda has previously failed enqueue with
`Unknown INVOKEDYNAMIC`. Listener tests that mock the scheduler do not prove
the lambda serializes. Treat fallback scheduling as an integration risk.

Distinguish failure classes: malformed/conflicting commands should not consume
infinite retries; transient infrastructure failures can be retried with limits
and backoff. A dead-letter route must be paired with monitoring and an explicit
repair/replay procedure.

**Caution:** the cached job-result path returns by job ID before revalidating
the submitted command. Do not assume every adapter rejects conflicting key
reuse at the same point. A hardened design would validate immutable command
identity before serving a completion cache too.

**Follow-up: is making the allocation consumer idempotent enough?**

No. The article extends the problem to downstream consumers. An allocation can
be correct while a reporting ledger, customer notification, or warehouse-command
consumer duplicates its own side effect.

**Proposed extension, not current downstream architecture:** publish an
`AllocationCompleted` event through an outbox and give each consumer its own
durable inbox:

```sql
CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);
```

Within one consumer database transaction:

1. Attempt to insert `(consumerName, eventId)` using
   `ON CONFLICT DO NOTHING` and inspect the affected-row count.
2. If inserted, apply the local business mutation and commit it with the inbox
   record. If the mutation fails, roll back both.
3. If an already committed inbox entry exists, skip that consumer's repeated
   effect. Acknowledge the broker only after the transaction completes.

The consumer name matters: the reporting and warehouse consumers should each
process an event once, not prevent each other from processing it. The unique
index coordinates concurrent redeliveries; a separate existence check does not.
Never commit an inbox marker first and perform the business mutation later.

An inbox deduplicates one event identity. If a producer emits two different
event IDs for the same business action, the consumer may also need a domain
uniqueness rule, such as one dispatch record per immutable fulfillment ID.
An email or remote warehouse call is not part of the local database transaction:
record an outgoing intent transactionally and use provider idempotency or
reconciliation at that additional boundary.

**Exactly-once qualification:** name the scope of the guarantee. Broker features
can provide transactional guarantees within their own supported boundaries;
they do not automatically encompass arbitrary databases and remote APIs.
Idempotency protects against duplicate effects, but does not by itself guarantee
that work eventually happens. Durable handoff, retries, and recovery provide
the liveness side of the design.

### Q10. Does committing a receipt guarantee the backorder job runs?

**Answer**

"The receipt and the intention to schedule commit together. JobRunr acceptance
is a later step, and that step can be repeated safely."

**Current:** `StockLedgerSkuWorker` applies the receipt and inserts
`stock_receipt_followup` in the same transaction. The follow-up id is derived
from the business date and the sorted received shipment-line ids. The job id is
derived from that follow-up id. `StockReceiptFollowupDispatcher` reads pending
rows after the ledger run completes, and a recurring job reads them every
minute. It enqueues `BackorderAllocationJob`, then sets `dispatched_at`.

A crash after the receipt commit leaves the row pending. The next dispatch
picks it up. A crash after enqueue and before `dispatched_at` can enqueue
again; the stable job id is what makes that repeat harmless, provided JobRunr
rejects the duplicate id. A failed enqueue records `last_error` and leaves the
row pending. Marking the row dispatched before enqueue is not what the code does.

JobRunr enqueue does not join the receipt transaction. Sharing PostgreSQL with
JobRunr does not make those two writes atomic.

**Contrast with HTTP:** asynchronous submit publishes to Kafka and returns
`202` after the broker acknowledges. There is no business row committed in that
request. Losing the response can still leave a published record whose job id
the caller never saw. Retrying with the same `requestId` protects the
allocation run and can still publish another job. There is no lookup from
`requestId` to job id.

### Q11. How do you avoid receiving or delivering stock twice?

**Answer**

"The physical ledger uses persisted movement state, a shared consistency lock,
and a single database transaction. I would not blindly apply an old forecast
again."

**Current:** `StockLedgerProcessor` admits one ledger owner through
`stock_ledger_coordinator` and processes today's manifest one SKU at a time.
Each worker locks that SKU before it reads. Overdue pending orders are expired
in a separate whole-order transaction, then the SKU is retried. The SKU
transaction rebuilds today's projection, checks that lot closing quantities sum
to the daily snapshot, applies receipts and due confirmed deliveries, marks
arrived shipment lines received, writes the receipt follow-up, and checkpoints
the SKU. The parent run is completed only after every SKU checkpoint exists.

Subsequent runs rebuild from the new live state: received lines are no longer
new receipts, and consumed reservations are gone. Rollback preserves the prior
receipt state, stock, snapshots, and success timestamp.

Only due confirmed deliveries reduce physical stock. A pending hold is not a
dispatch instruction. Arrived inbound holds may become physical-stock holds
without becoming deliveries.

**Boundary:** this is Stockio's database ledger, not proof that an external
warehouse physically shipped exactly once. Adding a warehouse API introduces a
new failure boundary.

### Q12. What if a future warehouse API succeeds but Stockio times out?

**Proposed extension, not current behavior**

"The outcome is unknown, not necessarily failed. A local transaction cannot roll
back a remote dispatch."

Use a stable external operation ID, a durable dispatch state machine, and the
warehouse's idempotency support if available. Query status/reconcile before
issuing another logically new dispatch. Deliver callbacks through an inbox with
unique event IDs and apply state transitions transactionally. Send commands
through an outbox.

**Follow-up: who creates the external reference, and what exactly is unique?**

Stockio would allocate and durably store an immutable `fulfillmentId` before the
first remote call, then send that same reference on every retry. It can be a
persisted UUID or a deterministically derived identity based on an agreed,
immutable fulfillment command. The warehouse must enforce that reference as an
idempotency key, not merely record it as descriptive metadata.

Do not blindly use `orderId` as the external uniqueness key: one Stockio order
can legitimately have multiple lines and partial fulfillment operations.
Request identity, order identity, and physical-dispatch identity need different
scopes. A split dispatch gets its own durable fulfillment ID; a retry of that
dispatch does not.

After a process crash, recover the stored reference and ask the provider about
that operation or retry with its supported key. If local state is lost entirely
and the reference cannot be reconstructed or discovered remotely, adding an
extra ID alone cannot recover the truth. Include retention, backup, provider
lookup, and the provider's idempotency horizon in the contract.

**Proposed external-operation states**

```text
CREATED -> SUBMITTED -> SUCCEEDED
                    -> FAILED_FINAL
                    -> UNKNOWN -> reconcile -> SUCCEEDED / FAILED_FINAL
```

Persist intent before submission. A timeout or a crash after submission can leave
the outcome `UNKNOWN`; recovery must also inspect stale `SUBMITTED` records,
because the crash may have prevented saving `UNKNOWN`. Only an authoritative
negative result permits the failure transition. If the business permits another
dispatch attempt, define that explicitly rather than changing keys to escape
uncertainty. Retrying an unknown operation reuses its identity.

**Do not confuse state machines:** Stockio's existing run completion marker,
per-SKU checkpoints, JobRunr execution states, and reservation lifecycle are
different kinds of state. A failed job may have committed SKU reservations; an
allocation run marked complete may still need projection/job completion.
There is no current warehouse dispatch state machine in this repository.

If the external system supports neither idempotency nor authoritative status
lookup, acknowledge that the strongest guarantee is unavailable; reconciliation
or manual intervention may be necessary. Do not promise end-to-end exactly-once
effects from a local unique index.

## 7. Java and Spring deep dive

### Q13. How would you express the worker boundary in Java?

The essential shape is a proxied Spring service method, not a JVM-wide lock.
This is an illustrative Java translation of the boundary, not drop-in project
code. The `AllocationStore` port below represents database operations that must
join the same transaction and implement the stated locking/identity contracts.

```java
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuReservationWorker {
    private final AllocationStore store;

    public SkuReservationWorker(AllocationStore store) {
        this.store = store;
    }

    @Transactional
    public String allocate(UUID runId, String sku, String expectedHash) {
        store.createAndLockSku(sku);
        store.validateRunIdentity(runId, expectedHash);

        String checkpoint = store.findCheckpoint(runId, sku, expectedHash);
        if (checkpoint != null) {
            return checkpoint;
        }

        String completed = store.findCompletedSkuResult(runId, sku);
        if (completed != null) {
            return completed;
        }

        String result = store.calculateAndPersistReservations(runId, sku);
        store.saveCheckpoint(runId, sku, expectedHash, result);
        return result;
    }

    public interface AllocationStore {
        void createAndLockSku(String sku);
        void validateRunIdentity(UUID runId, String expectedHash);
        String findCheckpoint(UUID runId, String sku, String expectedHash);
        String findCompletedSkuResult(UUID runId, String sku);
        String calculateAndPersistReservations(UUID runId, String sku);
        void saveCheckpoint(UUID runId, String sku, String hash, String result);
    }
}
```

The checkpoint lookup must reject a stored hash mismatch, not interpret it as a
cache miss. The store must lock/read current supply before calculating. A
reservation mutation must not commit separately from `saveCheckpoint`.

**Interviewer follow-ups**

| Question | Strong answer |
| --- | --- |
| Why a separate bean? | Ordinary Spring proxy interception applies when calling the worker through the proxy; self-invocation does not create a new transactional boundary. |
| What happens with `CompletableFuture`? | Do not assume the caller's thread-bound JPA transaction crosses executor threads. Each task calls a proxied transactional worker; never share a managed `EntityManager` across threads. |
| Would virtual threads solve contention? | They can change thread costs, not database lock or connection capacity. Bound concurrent database work regardless. |
| Is `@Transactional` alone sufficient? | No. Define constraints, locks, isolation assumptions, and replay state inside that boundary. |
| What about checked exceptions in Java? | Spring's usual default rollback rules cover runtime exceptions/errors, not every checked exception. Configure `rollbackFor` when the failure contract requires it. |
| Can I catch an exception and return success? | Not safely. A persistence failure may leave the transaction rollback-only, and a success-shaped result hides unfinished work. |
| Should we always use `REQUIRES_NEW`? | No. A nontransactional orchestrator calling a `REQUIRED` worker already gets a worker transaction. Nested transaction policies need a reason. |
| Why not Redis `SETNX`? | An opt-in Redis hold decrements available quantity per SKU and warehouse before publish. It is off by default and is not the lock. PostgreSQL SKU locks and reservation rows remain the correctness boundary. A Redis lock would still need leases and fencing. |
| What about deadlocks? | Use a consistent lock order, short transactions, no remote calls while holding locks, and bounded whole-transaction retries after deadlock/serialization failure. |

### Q14. Which constraints would you defend in a schema discussion?

**Current schema protections**

| Constraint | Why it matters |
| --- | --- |
| `UNIQUE allocation_run(request_id)` | One durable run identity per logical request. |
| `UNIQUE allocation_sku_operation(run_id, sku)` | One intermediate replay checkpoint per run/SKU. |
| `PRIMARY KEY demand_order(order_id)` | One owner for new-demand order identity. |
| `PRIMARY KEY demand_order_line(order_line_id)` | Stable line identity, including repeated-SKU lines. |
| Reservation uniqueness on `(order_line_id, stock_id)` or `(order_line_id, shipment_line_id)` | Prevents duplicate source rows for one line/source relationship. |
| Composite parent foreign keys | Prevent lines/reservations from silently changing order or shipment membership. |
| `UNIQUE inbound_supply(sku, warehouse_code, lot_id)` | Prevents conflicting supply-lot identities. |

Explain what each constraint does **not** prove. A unique reservation row can
still hold an incorrectly incremented quantity. Counter correctness requires
the transactional allocation algorithm and locks, not uniqueness alone.

**Proposed API refinement:** a conflicting idempotency key can be modeled as
`409 Conflict`. Do not describe that as current behavior: the inspected
global `CustomerExceptionHandler` maps `IllegalArgumentException` to `400 Bad Request`,
while asynchronous failures may surface through job status instead.

## 8. Reliability drills

**Interviewer: How would you demonstrate the design works?**

Use PostgreSQL/Testcontainers for constraints, locks, transactions, and
concurrency. Mock-only tests cannot prove those properties. Use barriers/latches
to create overlap, inject failures at specific boundaries, and assert persisted
state after completion or rollback.

| Drill | Required observable result |
| --- | --- |
| Submit one request twice sequentially | Same allocation run and reservation quantities; no extra backorder demand. |
| Execute the same request concurrently | One logical run and one set of SKU effects, even if multiple jobs execute. |
| Retry while the first execution is blocked before completion | No fabricated completed response or extra reservation; status/resume behavior follows the chosen in-progress contract. |
| Reuse a key with changed quantity/operation | Explicit conflict; original state unchanged. Exercise completion-cache paths too. |
| Change request ID but keep order identity | Second run cannot claim the order. |
| Trigger legitimate later backorder fulfillment | A new command can fulfill remaining demand without creating another new-demand order. |
| Compete for 100 units with two 80-unit orders | At most 100 units reserved; 60 units remain backordered in total under the stated assumptions. |
| Fail SKU B after SKU A commits | Retry reuses A's result and does not increase its reservations again. |
| Fail projection after allocation commit | Allocation remains committed; retry refreshes projections without repeating allocation. |
| Retry an initialized order near expiry | Original deadline is unchanged; expired lines are not re-reserved. |
| Run manual and scheduled ledger sync concurrently | A shipment line is received once; physical balances remain consistent. |
| Roll back receipt synchronization | No receipt, snapshot, follow-up row, or success state commits, so nothing is dispatched for that attempt. |
| Kill the process after the receipt commits, before JobRunr accepts the follow-up | The follow-up row remains pending and a later dispatch enqueues it. |
| Lose acknowledgment after successful processing | Redelivery has no additional business effect. |
| Reuse a scoped backorder command ID with different scope | Establish and enforce the intended identity contract; do not assume the current hash covers it. |
| Redeliver one event to two proposed downstream consumers | Each consumer applies its own effect once; neither consumer suppresses the other. |
| Roll back a proposed inbox consumer after insertion | Neither inbox marker nor local business effect commits; retry can perform the effect. |
| Time out after a proposed remote dispatch succeeds | Reconciliation/retry uses the original fulfillment ID; no new physical dispatch is created. |

**Existing examples to study:** `AllocationIntegrationTest` covers independent
SKU/idempotent aggregation, projection-failure resume, overlapping ledger runs,
ledger rollback, document ownership races, expiry, and unchanged deadlines on
resume. `AllocationKafkaListenerTest` and `AllocationRabbitListenerTest` exercise
listener failure and fallback behavior. This table is an interview test plan, not a claim that every drill
already has an automated test.

## 9. Scaling and operations follow-ups

### "How would this scale?"

Start with measurements, not a broker replacement. Examine transaction duration,
SKU lock wait, connection-pool saturation, Kafka consumer lag, JobRunr queue
age, and projection rebuild cost.

**Current:** SKU concurrency is configurable, with a default of four per
allocation service dispatcher. That is not a global concurrency limit across
instances, jobs, or the separate new-demand and backorder services. An opt-in
per-instance semaphore can reject allocation when `max-in-flight` permits are
taken. It is off unless every admission limit is set to a positive number.
Opt-in lock-contention control can bound a lock wait. Neither one is a shared
database-work budget. That budget is not implemented.

**Proposed:** measure before enabling those guards, then tune batch sizes and
query cost. Partition coordination only when the domain permits independent
stock scopes. A hot SKU remains a contention point even if more application
instances are added.

For sizing practice, let the interviewer supply assumptions. If requests arrive
at rate `R` and each touches `S` distinct SKUs on average, the starting workload
is approximately `R * S` SKU operations per second, before retries and recurring
work. Use measured transaction times and contention to estimate capacity rather
than treating thread count as throughput.

### "What should operators see?"

**Current:** ledger processing persists its last successful synchronization, and
status/health logic can report freshness. Jobs have execution states and saved
completion results; listener paths log failures.

**Proposed operational signals:** correlate `requestId`, `jobId`, `runId`,
`orderId`, and SKU; monitor conflicts, replay counts, stuck incomplete runs,
dead-letter volume, retries, lock waits, expired-hold cleanup lag, and receipt
commit-to-fulfillment lag. An outbox would add pending-event count and oldest
undispatched age.

Do not count a projection failure as a second allocation, or a failed enqueue
after commit as a rolled-back receipt. Log the boundary that actually failed.

### "Can we expire idempotency records after 24 hours?"

Not without a contract. Client retries, delayed broker messages, retained jobs,
and operational replays can outlive a short TTL. Order ownership and relational
references may also need to outlive cached execution results.

**Proposed:** define a replay horizon, archival strategy, and durable tombstones
or business-identity retention before deleting deduplication state. A cache
eviction policy must not accidentally authorize old demand again.

## 10. Common weak answers

| Weak answer | Better explanation |
| --- | --- |
| "Use a UUID." | Stable caller identity plus fingerprint, atomic uniqueness, and replay state. |
| "A hash of the order is always a better key." | Define retry versus legitimate new-command semantics first; a payload fingerprint serves a different purpose. |
| "The duplicate can always return the previous response." | The first execution may still be running; define an in-progress/status contract. |
| "Use `synchronized`." | Coordinate all writers through the authoritative database, across JVMs. |
| "Use Kafka exactly once." | Kafka is the default intake, and RabbitMQ and JobRunr remain. A broker guarantee does not cover PostgreSQL reservations or a remote warehouse call. |
| "Our consumer deduplicates, so the whole event chain is safe." | Every downstream side-effect boundary needs its own duplicate protection. |
| "Wrap everything in one transaction." | Explain independent SKU commits and resumability; a database transaction does not include arbitrary remote calls. |
| "The HTTP timeout means failure." | The operation may have committed; retry/query with the same identity. |
| "The order was allocated, so stock shipped." | A reservation, confirmation state, projection, and physical movement are different concepts. |
| "After commit means the job cannot be lost." | The follow-up row commits with the receipt. JobRunr acceptance is the next step, and the dispatcher retries while that row is pending. |
| "One unique reservation row prevents overselling." | Quantity changes and competing demand still need transactional supply coordination. |
| "Priority sorting guarantees global fairness." | Current batches have ordering rules; cross-request fairness needs an explicit admission/scheduling policy. |

## 11. A two-minute closing answer

> I would solve duplicate demand and competing demand separately. The caller
> supplies a stable request ID, and PostgreSQL owns the unique allocation run
> plus a request fingerprint. Order and line identities stop another request ID
> from recreating the same new demand.
>
> Within allocation, each SKU has a transactional worker. A pessimistic SKU
> lock serializes allocation, projection, and ledger work for that SKU.
> Different SKUs proceed together. The worker commits its stock and reservation
> changes together with a replay checkpoint. A failed request resumes unfinished
> SKUs instead of reserving successful lines again. Finalization persists the
> completed run; projection and job completion happen afterward and can be
> retried. The old global projection lock is not part of this protocol.
>
> I assume at-least-once message delivery, not magical exactly-once execution.
> Replays converge on durable business state. Asynchronous HTTP publishes to
> Kafka and returns after the broker acknowledges. Physical ledger
> synchronization is separate: one owner processes today's SKUs, and each SKU
> transaction rebuilds that SKU's projection and commits receipts, due confirmed
> deliveries, the receipt follow-up, and its checkpoint together.
>
> Stockio already has this database-side foundation. The receipt follow-up row
> closes the old in-memory callback gap. I would still qualify the window after
> JobRunr accepts the job and before `dispatched_at` is set, and I would verify
> command fingerprint coverage, completion-cache validation, and the synchronous
> status-identity mismatch rather than claim guarantees the code does not
> provide.
>
> For downstream integrations, each consumer needs its own transactional inbox.
> A remote dispatch also needs a stable fulfillment reference and reconciliation
> of unknown outcomes; a local transaction alone cannot make it exactly once.

## 12. Self-assessment

Score each area from 0 to 2: **0** = missed, **1** = named the concept,
**2** = explained the Stockio mechanism, its boundary, and a failure case.

| Area | Maximum |
| --- | --- |
| Clarified invariants and partial versus atomic allocation | 2 |
| Distinguished request, execution, run, and business identities, including legitimate new commands | 2 |
| Explained concurrent duplicates, in-progress responses, and conflicting payloads | 2 |
| Explained overselling prevention and lock scope | 2 |
| Explained SKU checkpoints and projection-failure recovery | 2 |
| Identified the receipt follow-up window, a duplicate dispatch, and the per-consumer inbox boundary | 2 |
| Explained Java/Spring proxies and executor transaction boundaries | 2 |
| Separated reservation, lifecycle, forecast, and physical stock | 2 |
| Proposed adversarial database-backed tests | 2 |
| Discussed remote unknown outcomes and tradeoffs without inventing existing guarantees | 2 |

For practice, aim for 16/20 or better and a closing answer delivered without
reading. This is a rehearsal target, not an employer's scoring rubric.

## 13. Repository reading map

Read in this order, focusing on transaction annotations, lock acquisition,
identity validation, and the state persisted before each return:

| Topic | Source |
| --- | --- |
| Architecture, flows, and data model | [Architecture](../architecture.md), [supported flows](../arch_supported_flows.md), [data model](../arch_data_model.md), [backpressure](../arch_backpressure_admission_control.md), [scenarios](../scenarios.md), [specifications](../specifications.md), [active context](../../.github/memory/active-context.md) |
| HTTP contracts | [AllocationController](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt), [ReservationController](../../stockio-service/src/main/kotlin/nl/stockio/reservation/domain/api/ReservationController.kt), [StockController](../../stockio-service/src/main/kotlin/nl/stockio/stock/api/StockController.kt), [request DTO](../../stockio-api/src/main/kotlin/nl/stockio/api/dto/AllocationRequest.kt) |
| Kafka publish, completion, and status | [AllocationJobExecutor](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt), [AllocationJobRunrService](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationJobRunrService.kt), [completion DTOs](../../stockio-api/src/main/kotlin/nl/stockio/api/dto/AllocationRunResult.kt) |
| Run identity and finalization | [AllocationService](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationService.kt), [AllocationRunLifecycleService](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationRunLifecycleService.kt), [Hasher](../../stockio-service/src/main/kotlin/nl/stockio/util/Hasher.kt) |
| Order ownership and unchanged deadlines | [AllocationDocumentService](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationDocumentService.kt) |
| Transactional worker, ports, and checkpoints | [SkuAllocationProcessor](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/processor/SkuAllocationProcessor.kt), [allocation ports](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/port/AllocationRepositories.kt), [stock ports](../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/port/StockRepository.kt), [allocation adapters](../../stockio-service/src/main/kotlin/nl/stockio/allocation/infra/AllocationJpaRepositories.kt), [stock adapters](../../stockio-service/src/main/kotlin/nl/stockio/stock/infra/StockJpaRepositories.kt) |
| Schema evolution and constraints | [V3: request idempotency](../../stockio-service/src/main/resources/db/migration/V3__add_request_idempotency.sql), [V11: parallel SKU allocation](../../stockio-service/src/main/resources/db/migration/V11__support_parallel_sku_allocations.sql), [V15: multiline identities](../../stockio-service/src/main/resources/db/migration/V15__order_and_shipment_lines.sql), [V18: ledger follow-up](../../stockio-service/src/main/resources/db/migration/V18__per_sku_stock_processing.sql) |
| Projection and execution replay | [AllocationProcessor](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/processor/AllocationProcessor.kt), [StockProjectionProcessor](../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockProjectionProcessor.kt), [AllocationJobExecutor](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt) |
| Message redelivery and fallback | [AllocationKafkaListener](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaListener.kt), [AllocationRabbitListener](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt) |
| Physical receipt and backorder trigger | [StockLedgerProcessor](../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockLedgerProcessor.kt), [StockLedgerSkuWorker](../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/processor/StockLedgerSkuWorker.kt), [receipt follow-up](../../stockio-service/src/main/kotlin/nl/stockio/stock/domain/job/StockReceiptFollowupDispatcher.kt), [AllocationBackorderService](../../stockio-service/src/main/kotlin/nl/stockio/allocation/domain/service/AllocationBackorderService.kt) |
| Database-backed examples | [AllocationIntegrationTest](../../stockio-service/src/test/kotlin/nl/stockio/allocation/domain/service/AllocationIntegrationTest.kt) |
| Listener failure examples | [AllocationKafkaListenerTest](../../stockio-service/src/test/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaListenerTest.kt), [AllocationRabbitListenerTest](../../stockio-service/src/test/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListenerTest.kt) |

When repository prose and executable mappings differ, inspect the implementation
and its tests before using the prose as an interview claim.

## 14. Coverage check against the full interview

The first guide already covered most database and crash-recovery mechanisms.
The full PDF made the key-derivation tradeoff and downstream-consumer protection
more explicit. This revision expands those areas and tightens the boundary
between safe replay, new business commands, and unknown remote outcomes.

| PDF topic | PDF pages | Stockio adaptation |
| --- | --- | --- |
| A timeout creates uncertainty, not proof of failure | 2 | Section 1: uncertainty-first framing; Q8: committed effects after a crash. |
| Generate one client key and reuse it | 2-3 | Q1: caller ownership, UUIDs, and distinct execution IDs. |
| Derive a key from attributes, but decide business semantics first | 3-4 | Q1 follow-up: canonical encoding, fingerprints, and retry versus later fulfillment. |
| Separate business identity and retry identity | 4, 7-8 | Q1/Q4: request, order, and line identity; Q12: physical fulfillment identity. |
| Persist and replay results; use atomic uniqueness rather than check-then-act | 5-6 | Q2/Q3: hashes, unique run insertion, checkpoints, and explicit in-progress behavior. |
| External success followed by local crash | 7 | Q12: persisted provider reference, provider-side deduplication, unknown state, and reconciliation. |
| A different key must not duplicate the same business action | 8 | Q4: order ownership, without incorrectly forbidding legitimate line/partial fulfillment. |
| Every downstream consumer needs duplicate protection | 8-9 | Q9 follow-up: consumer-scoped inbox, atomic local effects, and separate remote-effect protection. |
| Repeat delivery versus one intended effect | 9-10 | Q9: scoped exactly-once claims, idempotent effects, and durable recovery; closing answer. |

The in-progress request contract, the downstream consumer inbox, and the
external-operation state machine are design elaborations for interview practice.
Stockio does not have them. The receipt follow-up row is implemented: it is the
durable handoff from a committed receipt to backorder allocation.
Pages after the interview contain site material and responses; they are not
treated as additional requirements.
