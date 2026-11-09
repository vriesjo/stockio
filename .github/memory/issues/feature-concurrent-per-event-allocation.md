# Feature: Add a Kafka allocation consumer alongside RabbitMQ

## Status and scope

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Allocation Messaging & Ingress
- **Specification:** [`../../../docs/specifications.md`](../../../docs/specifications.md#feature-add-a-kafka-allocation-consumer-alongside-rabbitmq)
- **Scenario:** [`Scenario 18: Consume an Allocation Request from Kafka`](../../../docs/scenarios.md#scenario-18-consume-an-allocation-request-from-kafka)

Publish default asynchronous REST allocation submissions to Kafka, and consume
Kafka allocation requests alongside the existing RabbitMQ consumer. The Kafka
adapter invokes the same allocation application path as
`AllocationRabbitListener`, including
`allocationJobExecutor.executeSync(jobId, request)`. Stockio allocates stock;
this is not payment processing or external billing.

The scope includes the service's allocation submission publisher and Kafka
consumer, not a reusable producer starter, Kafka Streams topology, or a
replacement for RabbitMQ. Keep RabbitMQ available and behaviorally unchanged.
A bounded Kotlin `Channel` with a fixed set of allocation workers is the proposed
local buffer for smoothing short bursts; the existing `AllocationAdmission`
semaphore remains the authoritative execution bulkhead.

## Approved implementation decisions

- Consume the existing `AllocationRequest` JSON contract; use
  the UUID Kafka record key as the `jobId` for
  `allocationJobExecutor.executeSync`, falling back to `request.requestId` when
  an external producer sends no key.
- Publish raw `AllocationRequest` JSON from asynchronous REST submission and
  use a unique execution job ID as its Kafka key. Return the existing acceptance
  response only after Kafka acknowledges the send.
- Persist Kafka job state separately from JobRunr so the existing status endpoint
  can report queued and processing jobs, completed results, and ambiguous
  submission outcomes.
- Keep Kafka consumption enabled by default. Set `ALLOCATION_KAFKA_ENABLED=false`
  to stop the listener. Asynchronous REST submission still requires a reachable
  Kafka topic. Configure topic, group, worker count, channel capacity, retry
  delay, and dead-letter topic through service properties.
- Mirror the current RabbitMQ failure policy: retain and back off capacity
  rejections, dead-letter invalid/oversized requests, and enqueue a durable
  JobRunr fallback for other synchronous failures. Kafka offset progress follows
  successful completion or successful fallback enqueue.
- Use a bounded channel and fixed worker set. A channel send waits for capacity;
  it does not acknowledge the Kafka record. The synchronous listener waits for
  its worker before returning, preserving per-partition order; configured
  consumer concurrency and fixed workers bound processing and buffered work.
- Merge the user-provided `infra/docker-compose.yml` Kafka stack into
  `infra/docker/docker-compose.yml`; retain the supplied baseline file as-is.

## Scenario

### Gherkin description

**Given** Kafka allocation consumption is enabled and a valid allocation request
is available on its configured topic \
**When** Stockio consumes the record \
**Then** it invokes the shared allocation execution path using the request's
stable identity \
**And** it commits the Kafka offset only after allocation completes or a
durable recovery handoff succeeds \
**And** the existing RabbitMQ allocation consumer remains available

### High-level technical analysis

- `AllocationRabbitListener` is the current **RabbitMQ** adapter, not a Kafka
  consumer; it receives an `AllocationRequest` and uses
  `request.requestId` as the `jobId` passed to
  `allocationJobExecutor.executeSync(jobId, request)`.
- `AllocationJobExecutor.executeSync` returns a persisted completion when one
  exists for that `jobId`; otherwise it invokes the allocation processor and
  persists completion. This supports replay after completion, but the initial
  lookup alone does not establish single-flight behavior for simultaneous
  duplicate deliveries.
- Allocation already uses Kotlin coroutines internally to dispatch per-SKU
  workers, then joins them through `runBlocking`; that is separate from Kafka
  record consumption and does not provide an intake buffer.
- Kafka consumption must preserve Stockio's synchronous, database-backed
  allocation semantics. A bounded `Channel<AllocationRequest>` can absorb
  short bursts before a fixed worker set invokes the existing synchronous
  execution path. It is an in-memory buffer, not durable admission or added
  processing capacity; do not launch an unbounded coroutine per record.
- `AllocationAdmission.execute` already owns a non-waiting semaphore bulkhead:
  it uses `tryAcquire()` and throws `AllocationCapacityExceededException`
  when full. Kafka workers must complement that shared limit, not add a
  competing capacity policy or silently drop a rejected record.
- Kafka offset handling, retry/backoff, invalid-message handling, and any
  JobRunr recovery handoff need explicit Kafka-specific policy. Do not copy
  RabbitMQ acknowledgment behavior mechanically.
- Consumer concurrency must respect allocation admission and existing
  per-SKU/database coordination. Partition count or consumer count is not a
  capacity guarantee.

## Proposed ingestion and admission flow

```mermaid
flowchart LR
    K[Kafka topic] --> L[Kafka listener / poll]
    L --> Q["Bounded Kotlin Channel&lt;AllocationRequest&gt;"]
    Q --> W[Fixed allocation workers]
    W --> E["allocationJobExecutor.executeSync(requestId, request)"]
    E --> A["Existing AllocationAdmission semaphore"]
    A --> D[Stock allocation and persisted completion]
    D -->|success or durable handoff| O[Commit eligible Kafka offset]
    Q -.->|full: pause or backpressure; do not commit| L
    A -.->|capacity exhausted: bounded defer and retry| W
```

The channel can smooth brief intake bursts, but it is volatile and must remain
bounded. Enqueueing into it is not successful processing and must not commit an
offset. Workers must retain/retry capacity-rejected records without a hot retry
loop, and offset progress must not pass unfinished earlier records in the same
partition. The synchronous listener waits for the bounded-channel worker before
returning; validate offset advancement and recovery with a broker-backed test
before rollout.

### Acceptance Criteria

- A valid asynchronous REST submission publishes the unchanged request JSON to
  the configured Kafka topic, keyed by its returned `jobId`; publish failures
  propagate without returning a successful acceptance response.
- Acknowledged Kafka submissions remain queryable by `jobId` before and during
  processing, and successful completion returns the persisted allocation result
  without requiring a JobRunr job record.
- A broker acknowledgement error is represented as an unknown submission
  outcome rather than a false accepted or rejected status.
- The service consumer uses a valid UUID record key as its execution job ID
  and preserves `requestId` fallback for compatible external publishers.
- Kafka consumption can be enabled/configured without removing or silently
  changing the existing RabbitMQ consumer.
- A Kafka record is mapped to the agreed allocation request contract and
  delegates to the shared allocation execution path with a stable job identity.
- A record is not acknowledged by committing its offset before success or a
  confirmed durable recovery handoff.
- Concurrent same-request execution through the shared executor is tested; the
  stable request identity is retained across Kafka/RabbitMQ delivery, and no
  single-flight guarantee is claimed for simultaneous cross-broker delivery.
- Capacity exhaustion applies bounded backpressure without unbounded in-memory
  work; permanent invalid records and recoverable failures follow distinct,
  explicit policies.
- The bounded channel and fixed worker set smooth short bursts without
  replacing or bypassing `AllocationAdmission`; channel saturation and
  semaphore rejection preserve records and do not advance offsets.
- Tests cover message conversion, execution delegation, offset/retry behavior,
  duplicate delivery, channel saturation, capacity pressure, and coexistence
  with RabbitMQ.

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Kafka allocation adapter | Add a Kafka listener for the agreed allocation message contract and delegate to the existing allocation application path; keep broker-specific code outside allocation business logic. |
| FR-02 | Stable execution identity | Derive the execution/job identity deterministically from the logical request so redelivery does not create a new logical allocation; define and test simultaneous duplicate behavior rather than relying only on the completion cache. |
| FR-03 | Safe offset progress | Commit offsets only after successful allocation completion or after a durable fallback has accepted responsibility for the same request. |
| FR-04 | Explicit failure policy | Distinguish capacity pressure, transient processing failures, malformed/permanent requests, and successful completion; use bounded-delay capacity backoff and finite retries/DLT for other failures. |
| FR-05 | Bounded consumption | Use a bounded local Kotlin channel and fixed worker concurrency to smooth short bursts; keep the existing `AllocationAdmission` semaphore authoritative, preserve records on channel or semaphore saturation, and do not launch unbounded per-record coroutines or buffers. |
| FR-06 | RabbitMQ coexistence | Keep the RabbitMQ listener and its contract operational; document behavior when both brokers deliver the same logical request. |
| FR-07 | Operational visibility | Log and measure bounded-cardinality consumption outcomes, retries, capacity deferrals, and processing duration without using request or SKU identifiers as metric labels. |
| FR-08 | Asynchronous REST publisher | Serialize the unchanged `AllocationRequest` and publish to the configured Kafka topic using a unique execution job ID as the record key; return acceptance only after the broker acknowledges the publish. |
| FR-09 | Durable Kafka job status | Persist submission, enqueue, processing, and terminal states so accepted Kafka jobs can be polled by their returned execution ID without requiring a JobRunr record. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | At-least-once safety | Assume records can be redelivered; do not claim exactly-once stock allocation from Kafka offset transactions alone. |
| NFR-02 | Bounded resource use | Bound the local channel and active workers; preserve the existing allocation admission and stock-coordination guarantees, and never treat in-memory channel enqueueing as durable acceptance. |
| NFR-03 | Default consumer | The Kafka listener starts unless `ALLOCATION_KAFKA_ENABLED` is false. RabbitMQ consumption remains available beside it. |
| NFR-04 | Honest performance claims | Do not claim non-blocking processing, throughput improvement, or latency bounds without representative measurements. |
| NFR-05 | Compatible message contract | Keep the Kafka value as raw `AllocationRequest` JSON and preserve request-ID fallback for producers that do not send an execution key. |
| NFR-06 | Honest status and delivery outcomes | Preserve the REST status-polling contract and represent ambiguous broker acknowledgements explicitly rather than claiming the message was accepted or rejected without evidence. |

## Technical analysis and affected components

### Current implementation

- `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt`
  declares `@RabbitListener`, RabbitMQ queue/exchange/dead-letter configuration,
  capacity backoff, and a JobRunr fallback for synchronous processing failures.
- `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt`
  owns `executeSync`; the RabbitMQ listener passes `request.requestId` as its
  `jobId`.
- `AllocationJobExecutor.executeAsync` publishes to the configured Kafka topic;
  the service Kafka listener consumes those records by default and stays off
  only when `ALLOCATION_KAFKA_ENABLED=false`.
- Kafka-submitted job status does not depend on JobRunr storage: accepted
  messages are tracked through execution and completion in PostgreSQL.
- `AllocationAdmission` uses a non-waiting `Semaphore.tryAcquire()` for
  allocation work and reports saturation with
  `AllocationCapacityExceededException`; the RabbitMQ listener currently
  backs off and retries that request.
- `executeSync` is a synchronous call into Spring/JPA-backed work; per-SKU
  workers already use Kotlin coroutines internally. Its database and per-SKU
  coordination, plus the allocation admission limit, constrain safe Kafka
  consumer concurrency. A new Kafka channel must not multiply that existing
  concurrency without measurement.

### Proposed boundary

- Add a Kafka-specific adapter and configuration in `stockio-service`.
- Reuse the same allocation execution service; do not duplicate allocation
  business rules in the listener.
- Use a bounded Kotlin `Channel<AllocationRequest>` as a local burst buffer
  feeding a fixed worker set. Size/configure workers with the existing
  `AllocationAdmission` limit in mind; keep that semaphore as the shared
  execution guard across Kafka, RabbitMQ, and other callers.
- If the channel is full or the bulkhead rejects work, apply bounded backpressure
  or retry without dropping the record, committing its offset, or spinning in
  a hot loop. The synchronous listener waits for its bounded-channel worker;
  only successful allocation or durable fallback lets it return and advance
  the offset.
- Limit each poll to one record per partition so worker completion cannot
  reorder work within a partition; size worker concurrency to the provisioned
  topic partitions and documented service limits.
- Keep Kafka offset control and retry/dead-letter handling in the Kafka
  integration boundary. Extract shared consumer orchestration only if that is
  needed to make the chosen recovery behavior consistent and testable.
- Use a separate bounded worker/channel layer only for broker intake; do not
  confuse it with the existing per-SKU coroutine workers or claim that it
  makes blocking Spring/JPA calls non-blocking.
- Do not add Adyen integration, payment models, Kafka Streams processing, or a
  producer starter as part of this feature.

## Operational configuration and rollout

- Production bootstrap servers, credentials, topic/group names, partition
  count, worker count, channel capacity, and retry delay remain deployment
  configuration. Local topic/group defaults are for development only.
- Size worker concurrency and partitions from observed allocation/database
  capacity; do not claim throughput or latency bounds from local buffering.
- The database's unique request identity and allocation-run resume behavior
  protect logical allocation replay. Preserve the keyed execution ID for
  service-published records and use `requestId` fallback only for unkeyed
  external records; test same-request overlap across Kafka and RabbitMQ.
- Provision the source topic for default asynchronous REST submissions and
  deploy the Kafka consumer disabled unless this service is intended to consume
  those records. Provision the DLT before enabling consumption deliberately;
  the service does not auto-create the source topic while its consumer is off.
  RabbitMQ remains available; no reusable producer library is part of this
  feature.

## Implementation plan

1. Add opt-in Spring Kafka dependencies, typed service configuration, source
   and DLT topic declarations, and a raw-string consumer compatible with the
   existing `AllocationRequest` JSON contract.
2. Publish asynchronous REST submissions as raw request JSON keyed by their
   accepted execution job ID, and make the Kafka consumer use that key while
   retaining compatibility with request-ID-keyed or unkeyed external messages.
3. Add durable job tracking for publish, enqueue, execution, ambiguous send,
   successful completion, and dead-letter failure states; use it alongside
   JobRunr state in the status service.
4. Implement synchronous listener completion backed by a bounded Kotlin
   channel and fixed workers; preserve per-partition order and commit offsets
   only after execution or durable JobRunr handoff.
5. Mirror RabbitMQ's capacity backoff and JobRunr fallback behavior, dead-letter
   malformed/oversized requests, and preserve records on channel/bulkhead
   saturation.
6. Merge the supplied local Kafka stack into the existing Docker Compose file
   and document disabled-by-default configuration and startup.
7. Add focused publisher, tracking/status, listener, queue, retry/DLT, duplicate-delivery, context
   lifecycle, and broker-backed tests; measure before setting production
   concurrency or claiming throughput improvements.

## Resources

- [Scenario 18: Consume an Allocation Request from Kafka](../../../docs/scenarios.md#scenario-18-consume-an-allocation-request-from-kafka)
- [Feature specification](../../../docs/specifications.md#feature-add-a-kafka-allocation-consumer-alongside-rabbitmq)
- [Architecture](../../../docs/architecture.md)
- Current RabbitMQ adapter: `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt`
- Shared execution path: `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt`
