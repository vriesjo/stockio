# Feature: Redis Fast Path provisional allocation accept

- **Status:** In-progress
- **Priority:** Not set
- **Bounded Context:** Allocation intake and stock availability
- **Implementation started:** Yes
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-redis-fast-path-provisional-allocation-accept)
- **Parent Specs:** [Architecture](../../../docs/architecture.md)
- **Scenario:** [Scenario 22](../../../docs/scenarios.md#scenario-22-accept-allocation-jobs-on-the-redis-fast-path)

## Context and scope

`POST /api/allocation/jobs` currently validates the request and either publishes
it to Kafka or runs allocation inline against PostgreSQL. This feature inserts
a Redis provisional hold in front of the async path only.

In scope:

- opt-in Redis fast path for `AllocationController.submitAllocationJob`;
- multi-key Lua hold, TTL expiry, slow-path settle and rollback;
- startup pre-warm and full rebuild from `stock.quantity_available`;
- Debezium connector definition and a Kafka consumer that rewrites Redis availability.

Out of scope:

- `/api/products/allocate`, MCP, and listener-originated allocation;
- client oversell UX;
- warehouse on `DemandOrderLine` (holds use `MAIN`);
- redesign of admission, SKU locks, or ledger sync;
- treating confirm as a stock-ledger commit.

## Functional scenario

### Gherkin description

**Given** Redis fast path is enabled and stock availability has been pre-warmed from PostgreSQL \
**When** a client submits `POST /api/allocation/jobs` with `async=true` \
**Then** Stockio places one all-or-nothing provisional hold in Redis and returns `202 Accepted` without writing stock or allocation rows \
**And** the existing asynchronous worker later allocates in PostgreSQL \
**And** a failed slow-path allocation releases the Redis hold \
**And** a successful slow-path allocation settles the provisional keys \
**And** `async=false` is rejected while fast path is enabled

### Acceptance Criteria

- With fast path disabled, `POST /api/allocation/jobs` keeps its current sync and async behavior.
- With fast path enabled, a successful async submit decrements Redis and does not insert stock or allocation rows on the request path.
- One multi-key script covers every demand line; a short line does not leave earlier lines decremented.
- Slow-path failure restores Redis availability; slow-path success removes provisional keys without treating confirm as a ledger commit.
- Startup pre-warm and divergence repair load `SUM(quantity_available)` grouped by SKU and warehouse, and refuse new holds while a rebuild is running.
- CDC applies absolute availability for the affected SKU and warehouse idempotently.
- `GET /api/reservations/orders/{key}/redis` reads one `RedisKeys` stock, reservation, or expiry key when fast path is enabled, and rejects any other key.

## Technical analysis and affected components

| Component | Current behavior | Planned change |
| --- | --- | --- |
| `AllocationController.submitAllocationJob` | Async publish or inline `executeSync` | When enabled: Redis hold, then Kafka publish, no stock write |
| `ReservationController.getOrderReservationStatusByRedisKey` | Not present | `GET /api/reservations/orders/{key}/redis` reads one managed Redis key |
| `AllocationJobExecutor` | Publishes or runs allocation | `executeAsync` publishes to Kafka only; the listener starts tracking; `executeSync` settles or rolls back holds |
| Redis | Not used | `stock:sku:{sku}:{warehouse}`, `reservation:{requestId}:{orderId}:{orderLineId}`, `expiry:res:...` |
| `stock` | Lot rows with generated `quantity_available` | Pre-warm and CDC sum by SKU and warehouse |
| Debezium / Kafka | Allocation requests only | `public.stock` changes on `stockio.cdc.public.stock` |

Hold cleanup:

| State | Owner | Mechanism |
| --- | --- | --- |
| Redis provisional hold | Redis TTL listener | 10 minute expiry restores availability |
| PostgreSQL pending order | Existing reservation expiry job | Unchanged |
| Confirm or cancel | Existing slow-path APIs | Unchanged; confirm does not post the ledger |
| CDC | Kafka consumer | Sets Redis availability from PostgreSQL; never deletes holds |

While fast path is enabled, the accept path publishes to Kafka and does not write stock, allocation, or job-tracking rows. The listener inserts `PROCESSING` when it takes the record. Job status before that read returns not found.

## Implementation plan

1. Add the Redis client, Lua scripts, pre-warm gate, and rebuild.
2. Branch `submitAllocationJob` when `stockio.allocation.redis.enabled=true`.
3. Settle or roll back from `executeSync`, except on capacity and lock-contention retries.
4. Restore one hold when its expiry key expires.
5. Run Debezium from compose and apply stock events idempotently.
6. Keep the feature flag off by default.

## Risks

- Redis availability can diverge until CDC or rebuild runs. Accepts pause during rebuild.
- Two demand lines for one SKU must be checked against the combined quantity inside the Lua script.
- Expiry notifications require `notify-keyspace-events Ex`.
- Job status polling is empty until slow-path completion because the accept path does not write tracking rows.
