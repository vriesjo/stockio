<!-- d4e8b2f6-7a18-4d55-b3c9-0e1f2a3b4c5d -->
---
todos:
  - id: "producer-block"
    content: "Set the allocation producer max.block.ms to 0 so a full buffer fails without blocking the caller"
    status: pending
  - id: "publish-mono"
    content: "Return Mono from AllocationJobExecutor.executeAsync via Mono.fromFuture on KafkaTemplate.send"
    status: pending
  - id: "controller-chain"
    content: "Chain that Mono on the 202 path and keep Location, request identity, and fast-path rollback"
    status: pending
  - id: "tests"
    content: "Assert ack success, publish failure, and an unchanged listener, offset, and dead-letter path"
    status: pending
isProject: false
---

# Kafka publish Mono

Predecessor: [WebMVC to WebFlux](WebMVC%20to%20WebFlux-e4b8c2a1.plan.md). Start after that edge is in place. [Redis reactive gateway](Redis%20reactive%20gateway-c3d9f1a8.plan.md) should land first so the fast-path hold and the publish failure rollback are both publishers. This plan still works if Redis is blocking: the hold and rollback then stay on the dedicated blocking scheduler, and only the produce wait is a `Mono`.

`@KafkaListener`, offset commit, and the dead-letter topic stay as they are. Consumption moves to `reactor-kafka` in a separate change.

## Current publish

[`AllocationJobExecutor.executeAsync`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt):

- Validates limits, assigns a new `jobId`, and serializes the `AllocationRequest` unchanged.
- Publishes to `stockio.allocation.kafka.topic` (default `stockio.allocation.requests`) with the job id as the key.
- Blocks on `kafkaTemplate.send(...).get()` until the broker ack. `InterruptedException` restores the interrupt. `ExecutionException` rethrows its cause.
- Returns `AllocationJobAccepted(jobId, request.requestId)` only after that ack. The job is not tracked until the listener takes the record.

The producer in [`application.yml`](stockio-service/src/main/resources/application.yml) sets `acks: all` and does not set `max.block.ms`. The Kafka default is 60 seconds. `KafkaTemplate.send` can block the caller for that long when the producer buffer is full, before it returns a future. `Mono.fromFuture` does not cover that wait.

[`AllocationKafkaConfiguration`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaConfiguration.kt) keeps the listener factory, `DefaultErrorHandler`, and `DeadLetterPublishingRecoverer` on `allocationKafkaTemplate`. The dead-letter topic is `${topic}.DLT`, same partition as the source record. `enable-auto-commit` is `false`.

`AllocationController` calls `executeAsync` for `async=true`, including after a Redis hold. A publish failure after a hold calls `allocationRedisGateway.rollback`.

## Target shape

`executeAsync` returns `Mono<AllocationJobAccepted>`.

```kotlin
Mono.fromFuture { kafkaTemplate.send(topic, jobId.toString(), payload).toCompletableFuture() }
    .thenReturn(AllocationJobAccepted(jobId, request.requestId))
```

`spring.kafka.producer.properties.max.block.ms` is `0` for this producer. A full buffer throws from `send` instead of parking the caller. That error is a failed `Mono`. `acks: all` is unchanged, so the future still completes only after the broker ack.

The controller returns `202`, `Location: /api/allocation/jobs/{jobId}`, and `{jobId, requestId}` when the `Mono` completes. A failed publish still rolls back a fast-path hold and then fails the request. `async=false` with Redis enabled still returns `409` `REDIS_SYNC_DISABLED` before publish. `async=false` with Redis disabled still runs `executeSync` on the blocking scheduler.

The listener, worker channel, offset commit, retry backoff, and dead-letter publisher are not edited.

## Steps

1. **Producer.** Set `max.block.ms: 0` under `spring.kafka.producer.properties` in `stockio-service` `application.yml`. Keep `acks: all` and the string serializers. Apply the same property on the `KafkaTemplate` built in `allocationKafkaTemplate` if that factory replaces Boot's producer properties.
2. **Publish method.** Build the payload and job id as today. Call `limits.validate` before the producer call. Adapt `send` with `Mono.fromFuture`. Do not call `Future.get`. A producer exception, including buffer exhaustion, fails the `Mono` with that cause.
3. **Controller.** `submitAllocationJob` returns the publish `Mono` mapped to the existing `202` response. On the fast path, `rollback` runs when publish fails and the original error is the one returned to the client. With the Redis plan in place, `rollback` is already a `Mono`. Before that, rollback is `Mono.fromCallable { allocationRedisGateway.rollback(request) }.subscribeOn(blockingHttpScheduler)`.
4. **Leave consumption.** Do not change `AllocationKafkaListener`, the bounded channel workers, `DeadLetterPublishingRecoverer`, or manual offset commit.
5. **Tests.** `AllocationJobExecutorTest` asserts a completed `Mono` after a successful send future, and an error `Mono` when send fails, including a full buffer. The send invocation uses the job id as the key and the raw request JSON as the value. A controller test still expects `202` and `Location`. An existing listener or dead-letter test is rerun unchanged.

## Verification

- One accepted `POST /api/allocation/jobs` still returns `202` only after the producer future completes.
- A failed produce returns the producer error and, when a hold was taken, leaves Redis rolled back.
- `max.block.ms=0` makes a full buffer fail the `Mono` without waiting 60 seconds on the calling thread.
- Listener offset order and the dead-letter topic name are unchanged.

## Risks

- Calling `kafkaTemplate.send` on a Netty thread while `max.block.ms` is left at 60000 blocks that loop for up to a minute when the buffer is full.
- `Mono.fromFuture` on an already-started future is cold only if the `send` call sits inside the supplier. Calling `send` before `fromFuture` starts the produce during assembly.
- Setting `max.block.ms` to 0 changes a full buffer from a long wait into an immediate error. Clients that currently wait out the default 60 seconds will see that error instead. The ack wait itself remains on the future.
- Sharing this producer with the dead-letter recoverer means the recoverer also fails fast when the buffer is full. `setFailIfSendResultIsError(true)` already fails the recoverer when the dead-letter send fails. Confirm a full buffer still ends in the existing error-handler failure path.
