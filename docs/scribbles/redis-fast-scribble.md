---
name: redis-fast-path
description: >-
  Guides investigation and design of Stockio's Redis Fast Path (UI provisional
  actions) alongside the PostgreSQL Slow Path Heavy Ledger (ACID source of
  truth) with async WAL → Redis sync. Use when discussing Redis, fast path,
  slow path, provisional holds, Lua scripts, WAL sync, or dual-layer stock.
disable-model-invocation: true
---

# Redis Fast Path

Living skill for the Fast Path / Slow Path split. Keep this file thin; update
it as investigation decisions land. Do not invent architecture that is not yet
agreed.

## Decisions (agreed so far)

### Fast Path — UI & provisional actions

- UI/API provisional actions hit **Redis directly**.
- Execute an **atomic Lua script** (or equivalent atomic decrement) with a
  stock check in the same script.
- Target latency: **&lt; 5 ms**.
- Success creates a **provisional hold with TTL**; response returns instantly.
- **Does not touch PostgreSQL.**

### Fast Path entry points (first)

- **`AllocationController.submitAllocationJob`** (`POST /api/allocation/jobs`)
  must be on the Fast Path — provisional accept via Redis atomic check/hold;
  no Postgres on the request path.
- Other entry points (`/api/products/allocate`, MCP, listeners): **out of
  scope for now** (may revisit later; not part of this skill’s first cut).

### Redis key model (provisional holds)

Hold identity uses **`AllocationRequest` natural IDs** — no separate synthetic
`reservation_id`:

| ID | Source | Role |
| --- | --- | --- |
| `requestId` | `AllocationRequest.requestId` (`UUID`) | Request-scoped correlation / idempotency |
| `orderId` | `DemandOrder.orderId` | Order within the request |
| `orderLineId` | `DemandOrderLine.orderLineId` | Line hold unit (ties to `sku` + qty) |

Three key types for fast check, hold, and TTL cleanup:

| Key | Shape | Purpose |
| --- | --- | --- |
| `stock:sku:{sku}:{warehouseCode}` | Integer | Available qty for atomic Lua check/decrement (warehouse-scoped) |
| `reservation:{requestId}:{orderId}:{orderLineId}` | Hash | Hold metadata: sku, warehouse (`MAIN` default), qty, status (`tentative` → …), timestamps |
| `expiry:res:{requestId}:{orderId}:{orderLineId}` | String + **TTL** (e.g. 10–15 min) | Abandon/expiry trigger for that line hold |

Hold/decrement for a request uses **one multi-key Lua script** covering all
demand lines in that `AllocationRequest` (all-or-nothing). Per-line scripts
with app-level rollback are **rejected** (non-atomic; race window; crash mid-
rollback drifts Redis stock).

### Provisional → durable handoff

Aligned to Stockio's existing **allocate → `PENDING_CONFIRMATION` → confirm/expire** lifecycle; Fast Path only inserts a Redis-first provisional step:

1. **PROVISIONAL (Redis only)** — TTL-backed hold; &lt;5 ms accept on Fast Path entry.
2. **ALLOCATE (Postgres Slow Path, async)** — validation/eligibility; on failure
   delete/rollback Redis hold; on success write durable pending reservation
   (existing ACID path / projections) as pending order.
3. **CONFIRM (Postgres Slow Path)** — confirm allocation (existing confirm
   semantics); durable reservation leaves `PENDING_CONFIRMATION`.
4. **SETTLE (Postgres → Redis)** — WAL/CDC updates Redis stock projection;
   remove transient Redis hold when durable state supersedes it.

### Slow Path — ACID PostgreSQL Heavy Ledger

- PostgreSQL remains the **ultimate source of truth** with existing ACID
  transaction boundaries.
- **Keep existing Slow Path functionality as-is** (allocation, ledger,
  projections, jobs, expiry, etc.).
- Introduce an **async WAL stream** that syncs committed Postgres changes
  **back to Redis**.

### WAL → Redis tooling

- **Preferred direction**: Debezium → Kafka topics (partition by SKU) →
  consumer or Kafka Connect sink → Redis.
- Rationale: offset management, schema evolution, DLQ, horizontal scale.
- Stockio already uses Kafka for allocation listeners — reuse that ops plane
  where practical (KRaft; do not assume ZooKeeper).
- Must design for **at-least-once** delivery and **idempotent** Redis applies.

### Hold cleanup ownership

| State / Artifact | Owner | Mechanism | Purpose |
| --- | --- | --- | --- |
| Redis provisional hold | Redis TTL & keyspace/expiration listener | `SETEX` (e.g. 10 min TTL) | Instantly frees hot-path stock if the client abandons |
| PostgreSQL pending order | Background sweeper (cron worker; maps to existing reservation expiry job) | Periodic SQL where `expires_at < NOW()` | Cleans abandoned durable pending rows; prevents unbounded growth |
| Final confirmation / explicit cancel | Request path (API handler) | Inline ACID transaction (status → confirmed/canceled) | Handles active user intent immediately |
| CDC projection sync | WAL consumer | Asynchronous stream processing | Reflects committed Slow Path truth only — **never** owns cleanup decisions |

### Redis rebuild / cold-start

- **Proactive bulk pre-warm** before accepting Fast Path traffic (preferred over
  lazy cache-miss fill for this path).
- Hydrate from PostgreSQL `stock`, then open HTTP only when Redis is hot.
- Stockio `stock` is **lot-scoped** (`sku`, `warehouse_code`, `lot_id`) with
  `quantity_on_hand`, `quantity_reserved`, and generated `quantity_available`.
- Fast Path stock key is **`stock:sku:{sku}:{warehouseCode}`** — SUM available
  qty by `(sku, warehouse_code)` across lots (not one Redis key per lot).
- Provisional warehouse when demand lines omit it: **default `MAIN`** for now;
  extend `DemandOrderLine` later only if the request model gains warehouse.
- Hot-SKU-only pre-warm is an optional later optimization if full scan is too
  slow; not required for the first design.

### Redis divergence repair

- On divergence (not only cold start): **full re-warm** from Postgres into Redis
  (replace Fast Path stock keys from durable truth).
- Pause or fail Fast Path accepts during re-warm so provisional holds do not
  race the rebuild.

### Deferred (explicitly not now)

- Deep redesign of allocation **admission**, **SKU locks**, and **ledger sync**
  interaction with Fast Path — keep existing Slow Path behavior; rely on async
  WAL → Redis for convergence after durable commits.
- Over-sell / conflict **client UX** when Fast Path succeeds but Slow Path later
  rejects — **out of scope for this skill**; API-only failure/rollback path for
  now; UX later in a separate skill.

## Working vocabulary

| Term | Meaning |
| --- | --- |
| Fast Path | Redis operational layer for UI/API provisional actions |
| Slow Path | PostgreSQL heavy ledger — durable ACID source of truth |
| Provisional hold | Fast Path reservation with TTL; not yet durable truth |
| Atomic Lua check | Redis script that checks availability and holds/decrements atomically |
| WAL → Redis sync | Async Debezium/Kafka stream of Postgres changes into Redis |

## Checked against Stockio (review notes)

Keep these corrections when refining the design; do not silently reintroduce them:

- Prefer Stockio terms (`requestId`, `orderId`, `orderLineId`,
  `PENDING_CONFIRMATION`, confirm / expiry jobs) over generic cart/`user_id`
  or synthetic `reservation_id` language unless a real cart API appears.
- **Confirm must not “commit the stock ledger”** in Stockio today: ledger sync
  (Scenario 6) is a separate physical apply path; confirm only advances
  reservation lifecycle. Physical deduction stays on the existing ledger job
  unless a later decision explicitly changes that.
- Steps 2 and 3 are **allocate (pending)** then **confirm**, not two identical
  “handoff & deduction” pipelines.
- Cleanup ownership is split (TTL / Postgres sweeper / request-path confirm);
  CDC must only reflect committed truth, never decide cleanup.
- Multi-line accept must be **one atomic multi-key Lua** per request — never
  per-line decrement + compensatory increment.
- Pre-warm aggregates lot rows → `stock:sku:{sku}:{warehouseCode}`; do not use
  1:1 lot keys or SKU-only keys without warehouse.
- `DemandOrderLine` has **no warehouse** today; provisional hold uses default
  **`MAIN`** (same default as inbound shipment lines) until the request model
  is extended.
- Deferring admission/SKU-lock redesign is fine for now; WAL sync does **not**
  replace Slow Path locking during the async ALLOCATE step.
- Full re-warm on divergence must not run concurrent with Fast Path accepts.

## How to use this skill

1. Read `../architecture.md`, `active-context.md`, and relevant
   scenarios/specs before proposing design.
2. Prefer questions and options over premature implementation.
3. When a decision is made, update this skill immediately. Keep memory docs in
   sync only when behavior is approved.
4. Do not implement Redis wiring until the user asks.

## Open questions

None for this investigation pass. Reopen when implementing or when scoping the
next entry point / warehouse-on-demand-line change.

## Memory docs to update later (when approved)

- `architecture.md` — dual-path C4 / persistence boundaries
- `scenarios.md` / `specifications.md` — provisional accept + pending/confirm
- `issues/` — feature issue once scope is ready

## Update checklist

After each investigation turn that produces a decision:

- [ ] Record under **Decisions**
- [ ] Remove or rewrite the matching open question
- [ ] Note affected memory files above
