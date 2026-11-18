# Code Review Guidelines: Kotlin Event-Driven Architecture (EDA), Kafka & Outbox Pattern

> **Purpose:** This document defines the engineering standards and review checklist for microservices utilizing Event-Driven Architecture (EDA), Apache Kafka, and the Transactional Outbox Pattern in Kotlin. Reviewers (human and AI) must enforce these rules on all Pull Requests touching event producers, consumers, outbox relays, and domain event schemas.

---

## 1. Transactional Outbox Pattern & Atomicity

The primary objective of the Outbox Pattern is to eliminate the **dual-write problem** (where a database state change succeeds, but publishing to Kafka fails, or vice versa).

### Checklist
- [ ] **Single-Transaction Boundary:** Verify that domain entity mutations (e.g., `Order` persistence) and outbox event record insertions occur within the **exact same database transaction scope** (e.g., `@Transactional` in Spring, `newSuspendedTransaction` in Exposed, or reactive transaction operators).
- [ ] **No Dual Writes (Blocking Anti-Pattern):** Reject any PR that performs a database write followed immediately by a direct `kafkaTemplate.send()` or `KafkaProducer.send()` inside business logic without staging the event in an outbox table first.
- [ ] **Concurrent Relay Safety:** If using a polling-based outbox publisher across multiple pod instances, verify that the fetch query utilizes row-level locking to prevent duplicate reads and race conditions (e.g., `SELECT ... FOR UPDATE SKIP LOCKED` in PostgreSQL).
- [ ] **Outbox Retention & Cleanup:** Ensure a mechanism exists (scheduled cleanup job, partitioning, or TTL) to archive or delete processed outbox rows so the table does not grow unboundedly.
- [ ] **CDC Compatibility (If using Debezium/Kafka Connect):** Verify that the outbox table schema adheres to the connector's expected column layout (`id`, `aggregatetype`, `aggregateid`, `type`, `payload`, `timestamp`) and that `payload` serialization is deterministic.

### Code Example: Correct vs. Incorrect
```kotlin
// ❌ REJECT: Dual-write vulnerability
@Transactional
suspend fun placeOrder(command: PlaceOrderCommand) {
    val order = orderRepository.save(command.toEntity())
    kafkaTemplate.send("orders", order.id.toString(), order.toCreatedEvent()).await() // Can fail after DB commit or commit before DB rollback!
}

// ✅ APPROVE: Atomic Outbox write
@Transactional
suspend fun placeOrder(command: PlaceOrderCommand) {
    val order = orderRepository.save(command.toEntity())
    val outboxEvent = OutboxEvent.from(
        aggregateType = "Order",
        aggregateId = order.id.toString(),
        event = order.toCreatedEvent()
    )
    outboxRepository.save(outboxEvent) // Committed atomically with the Order
}
```

---

## 2. Kafka Producer & Consumer Reliability

Client configurations govern delivery guarantees. Reviewers must check both application configuration files (`application.yml`) and programmatic Kafka factory beans.

### Checklist
- [ ] **Producer Idempotence & Durability:** Confirm producers enforce idempotence and full ISR (In-Sync Replicas) acknowledgments:
  - `enable.idempotence = true`
  - `acks = all`
  - `max.in.flight.requests.per.connection <= 5`
- [ ] **Partition Key Strategy:** Verify that messages are published with a consistent domain entity key (e.g., `aggregateId` such as `orderId` or `customerId`) rather than `null`. This guarantees strict causal ordering per entity within a partition.
- [ ] **Consumer Manual Offset Management:** Confirm `enable.auto.commit = false`. Offsets must *only* be acknowledged/committed **after** downstream business logic and local database transactions have successfully committed.
- [ ] **Dead Letter Queue (DLQ) & Retry Strategy:** Verify that consumer error handlers distinguish between transient errors (network timeouts -> retry with exponential backoff) and poison pills (deserialization failures / malformed payloads -> route immediately to a `.DLT` / DLQ topic without blocking partition consumption).

---

## 3. Kotlin Idioms & Coroutine Concurrency Safety

Asynchronous event processing in Kotlin requires strict adherence to structured concurrency and thread-dispatcher boundaries.

### Checklist
- [ ] **Structured Concurrency:** Reject any usage of `GlobalScope.launch` or unmanaged `CoroutineScope` instances inside event listeners or outbox relayers. Coroutines must be bound to managed lifecycle scopes (`coroutineScope`, `supervisorScope`, or framework-managed lifecycles).
- [ ] **Dispatcher Isolation:** Ensure blocking I/O operations (e.g., JDBC calls, blocking HTTP clients) are explicitly isolated on `Dispatchers.IO` (or Loom virtual threads), and never block Netty/Reactor event-loop threads.
- [ ] **Cancellation Propagation:** Verify that `catch (e: Exception)` blocks inside suspending event handlers rethrow `CancellationException` (`if (e is CancellationException) throw e`) so coroutine cancellation signals are never swallowed.
- [ ] **Exhaustive Event Modeling (`sealed interface`):** Domain events should be modeled as Kotlin `sealed interface` or `sealed class` hierarchies. Consumers handling multiple event subtypes from a topic must use exhaustive `when` expressions without `else` branches so new event types cause compile-time errors if unhandled.

### Code Example: Exhaustive Event Handling
```kotlin
sealed interface OrderEvent {
    val eventId: UUID
    val occurredAt: Instant

    data class Created(override val eventId: UUID, override val occurredAt: Instant, val totalAmount: BigDecimal) : OrderEvent
    data class Cancelled(override val eventId: UUID, override val occurredAt: Instant, val reason: String) : OrderEvent
}

// ✅ APPROVE: Compiler enforces handling of all subtypes
suspend fun handle(event: OrderEvent) {
    when (event) {
        is OrderEvent.Created -> processCreation(event)
        is OrderEvent.Cancelled -> processCancellation(event)
    }
}
```

---

## 4. Consumer Idempotency & Deduplication

Because Outbox relays and Kafka consumers provide **at-least-once delivery**, duplicate message delivery during rebalances or pod restarts is guaranteed to occur eventually.

### Checklist
- [ ] **Idempotent Processing:** Every consumer must safely handle receiving the exact same `eventId` multiple times without corrupting state or triggering duplicate side effects (e.g., charging a customer twice).
- [ ] **Atomic Deduplication Guard:** Verify that consumers record the processed `eventId` in a local deduplication table (e.g., `processed_events` with a `PRIMARY KEY` or `UNIQUE` constraint on `event_id`) **within the same local transaction** as the business state mutation.
- [ ] **Graceful Duplicate Handling:** Confirm that catching a unique constraint violation (`DataIntegrityViolationException`) on `processed_events` logs the duplicate at `INFO`/`DEBUG` level and acknowledges the Kafka offset cleanly without triggering retries or DLQ routing.

---

## 5. Schema Evolution & Event Contracts

Events are public APIs between bounded contexts. Breaking changes cause cascading outages.

### Checklist
- [ ] **Standardized Event Envelope:** Verify that events wrap domain data in a consistent envelope containing metadata headers or fields:
  - `eventId` (UUIDv4 or UUIDv7 for idempotency)
  - `eventType` (String / fully qualified event name)
  - `schemaVersion` (Int or SemVer)
  - `occurredAt` (UTC ISO-8601 `Instant`)
  - `correlationId` / `causationId` (for distributed tracing)
- [ ] **Backward Compatibility:** Reject any schema changes that:
  - Remove or rename existing non-optional fields.
  - Change the data type of an existing field.
  - Add new mandatory fields without sensible defaults (in Kotlin data classes, ensure new properties have default values like `val discountCode: String? = null` so older payloads deserialize cleanly).
- [ ] **Schema Registry Enforcement:** If using Avro, Protobuf, or JSON Schema, verify that compatibility mode is set to at least `BACKWARD` (or `BACKWARD_TRANSITIVE`).

---

## 6. Observability & Testing

### Checklist
- [ ] **Trace Context Propagation:** Confirm that W3C Traceparent / OpenTelemetry headers are injected into the outbox record upon creation and extracted into Kafka message headers upon relay, preserving end-to-end distributed traces across async boundaries.
- [ ] **Structured Logging:** Ensure consumer and producer logs include MDC context (`eventId`, `aggregateId`, `topic`, `partition`, `offset`) without logging sensitive PII payloads.
- [ ] **Integration Testing with Testcontainers:** Verify that new event flows include integration tests using real Kafka and Database instances (via Testcontainers) rather than relying solely on mocked `KafkaTemplate` unit tests.