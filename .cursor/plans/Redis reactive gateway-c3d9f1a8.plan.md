<!-- c3d9f1a8-4b62-4c15-9e70-8a1b2c3d4e5f -->
---
todos:
  - id: "reactive-template"
    content: "Replace StringRedisTemplate in AllocationRedisGateway with ReactiveStringRedisTemplate on the existing Lettuce factory"
    status: pending
  - id: "mono-operations"
    content: "Return Mono from Lua hold, rollback, and getByKey using the same scripts, keys, and 409 exceptions"
    status: pending
  - id: "http-chain"
    content: "Compose those Monos in AllocationController and ReservationController without Mono.block"
    status: pending
  - id: "background-block"
    content: "Keep rebuild, expiry, and JobRunr settle or rollback blocking only on their own threads"
    status: pending
  - id: "tests"
    content: "Cover hold, duplicate hold, insufficient stock, rollback, and key read with the same Redis results"
    status: pending
isProject: false
---

# Redis reactive gateway

Predecessor: [WebMVC to WebFlux](WebMVC%20to%20WebFlux-e4b8c2a1.plan.md). Start after that edge is in place. This plan does not change PostgreSQL, the Lua scripts, or the Redis key format.

Lettuce is already the driver. `StringRedisTemplate` blocks the caller until Lettuce's future completes. `ReactiveStringRedisTemplate` waits on that same connection without holding a thread. The HTTP fast path is the part that must stop blocking. Rebuild, key-expiry, and JobRunr still run on their own threads.

## Current gateway

[`AllocationRedisGateway`](stockio-service/src/main/kotlin/nl/stockio/allocation/infra/AllocationRedisGateway.kt) is active when `stockio.allocation.redis.enabled=true`. It uses `LettuceConnectionFactory` and `StringRedisTemplate`.

| Operation | Behavior |
| --- | --- |
| `accept` | Runs `redis/fast-path-hold.lua`. `0` throws `AllocationRedisInsufficientStockException`. `1` and `2` succeed. Anything else throws `AllocationRedisHoldConflictException`. A Redis runtime failure also becomes `AllocationRedisHoldConflictException`. |
| `rollback` | Runs `redis/fast-path-rollback.lua`. |
| `getByKey` | Reads a string or hash and its TTL. Used by `ReservationController.getOrderReservationStatusByRedisKey` for `stock:sku`, `reservation`, and `expiry:res` keys. |
| `settle`, `rebuild`, `onReservationExpired` | Blocking deletes, PostgreSQL pre-warm, and the expiry listener. |

Keys stay in [`RedisKeys`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/fastpath/RedisKeys.kt): `stock:sku:{sku}:{warehouse}`, `reservation:{requestId}:{orderId}:{orderLineId}`, `expiry:res:{requestId}:{orderId}:{orderLineId}`.

[`AllocationController.submitAllocationJob`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/AllocationController.kt) calls `accept` before Kafka publish and `rollback` when publish fails. `async=false` while the gateway is enabled still throws `AllocationRedisSyncDisabledException`. [`CustomerExceptionHandler`](stockio-service/src/main/kotlin/nl/stockio/allocation/domain/api/exception/CustomerExceptionHandler.kt) maps insufficient stock, hold conflict, and sync-disabled to `409`.

## Target shape

`AllocationGateway` returns publishers for the three request-path operations:

```kotlin
fun accept(request: AllocationRequest): Mono<Void>
fun rollback(request: AllocationRequest): Mono<Void>
fun getByKey(key: String): Mono<RedisKeyResponse>
```

`DisabledAllocationRedisGateway` returns `Mono.empty()` from `accept` and `rollback`, and `Mono.empty()` from `getByKey`. `enabled` stays `false`.

The enabled gateway takes `ReactiveStringRedisTemplate`. Hold and rollback use the existing `DefaultRedisScript<Long>` and classpath Lua files. `accept` still requires `accepting == true` and still throws `AllocationRedisNotReadyException` before Redis. Result `0`, `1`, and `2` keep the same meaning. `getByKey` still returns a string value or hash fields plus TTL, and an unknown type stays empty so the controller can answer `404`.

`AllocationController` flatMaps `accept`, then publish, and on publish failure flatMaps `rollback` before surfacing the error. `ReservationController` maps `getByKey` to `200` or `404`. Neither controller calls `Mono.block`.

`rebuild`, `settle`, and `onReservationExpired` may call the reactive template and then `block` on the startup runner, the JobRunr worker, or the `RedisMessageListenerContainer` thread. They must not run on a Netty event-loop thread. `settle` stays a blocking gateway method until JobRunr moves.

## Steps

1. **Template.** In `AllocationRedisGatewayEnabledConfiguration`, build `ReactiveStringRedisTemplate` from the existing `LettuceConnectionFactory`. Remove `StringRedisTemplate` once the gateway no longer needs it. Leave the expiry `RedisMessageListenerContainer` in place.
2. **Hold and rollback.** Execute the same scripts with the same key list and arguments: hold TTL seconds, `Instant.now()`, and the JSON line payload. Rollback still passes `"rollback"`. An empty line list completes without a Redis call.
3. **Stock and key read.** `getByKey` reads `DataType.STRING` and `DataType.HASH` through the reactive operations. Preserve `RedisKeyResponse` fields. `ReservationController` still rejects a key outside `RedisKeys.isManaged`.
4. **HTTP composition.** Chain `accept` and `rollback` in `submitAllocationJob`. Keep the `409` exceptions and the `REDIS_SYNC_DISABLED` rejection for `async=false`. Publish remains the current `executeAsync` until the Kafka plan. If that plan has landed, chain its `Mono` directly. If it has not, publish stays `Mono.fromCallable { executeAsync(request) }.subscribeOn(blockingHttpScheduler)`.
5. **Background callers.** `AllocationJobExecutor.executeSync` blocks on `rollback` and `settle` on the JobRunr thread. `rebuild` still pauses `accepting`, loads `StockAvailabilityJpaRepository.sumBySkuAndWarehouse()`, and writes the same stock keys. `onReservationExpired` still restores quantity and deletes the reservation hash.
6. **Tests.** Update `RedisProvisionalHoldTest` for the `Mono` port. Assert a second identical hold returns result `2`, a short hold signals `AllocationRedisInsufficientStockException`, rollback restores the stock key, and `getByKey` returns the stock string. A controller test still expects `409` with `REDIS_INSUFFICIENT_STOCK` and `REDIS_HOLD_CONFLICT`.

## Verification

- The Lua files and key strings are unchanged.
- `POST /api/allocation/jobs` with the fast path on still returns `202` after a successful hold, and `409` for insufficient stock, hold conflict, and `async=false`.
- `GET` by Redis key still returns `200` for a managed string or hash and `404` for a missing key.
- The Netty event loop is not inside Lettuce's sync future during hold, rollback, or `getByKey`.

## Risks

- `block` on the controller thread, or `fromCallable` around `accept`, holds a worker for the whole Redis round trip.
- Opening a second Lettuce factory for the reactive template splits connections and can observe a different database. Use the factory that already serves this gateway.
- Reactive script execution returns a `Flux`. The hold result must be the single script number. Dropping or duplicating that number changes the `409` decision.
- `SET` during rebuild and the expiry increment stay blocking on their current threads. Moving those off their threads is a later change.
