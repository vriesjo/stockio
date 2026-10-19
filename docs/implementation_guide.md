# Implementation Plan: Natural-Language Product Resolution and Stock Allocation

## Goal

Support requests such as:

> Hold 50 units of Maki Komkommer, or the nearest equivalent.

The service must identify what product the user means before it can allocate inventory. A Tavily product lookup is a required prerequisite for every allocation request; local cached knowledge may help interpret and compare results, but it must not bypass that lookup. Once a request is resolved to a product in Stockio's catalog, Stockio's existing allocation workflow remains the authority for reserving stock and inbound supply.

This document is an implementation plan for the existing Stockio service, not a standalone reservation implementation. In particular, the proposed `reserve_stock` MCP tool and `StockReservationMcpClient` are not part of the documented service architecture and should not be added as a parallel inventory path.

## Existing Stockio behavior to preserve

Stockio is a Kotlin/Spring service with PostgreSQL as its system of record. Flyway manages schema changes, and allocation services process requests by SKU, persist stock or inbound reservations, and record unfilled quantities as backorders.

The allocation request includes identifiers and dates in addition to SKU and quantity. New-demand allocation and backorder fulfillment are separate workflows. The existing MCP tools and REST endpoints should be reused or extended; inventory must not be reserved by directly updating tables from the product-resolution feature.

See [architecture.md](architecture.md) for the current modules, request shape, allocation rules, persistence model, and interfaces.

## Request flow

```text
Natural-language request
        |
        v
Parse intent and requested product phrase
        |
        v
Required Tavily search for the product and possible equivalents
        |
        v
Compare search evidence with Stockio's product catalog
        |
        +---- No credible catalog match or unresolved ambiguity --> Stop and report candidates
        |
        v
Resolve to a Stockio SKU and expose the selected product to the caller
        |
        v
Build a validated AllocationRequest
        |
        v
Existing Stockio allocation workflow
        |
        v
Return allocation, reservation, and any backorder details
```

### 1. Parse the request

The MCP caller parses the user's natural-language request into the structured `ProductAllocationRequest`; the service does not attempt to parse arbitrary prose. For the example above, the product phrase and quantity are `Maki Komkommer` and `50`.

The caller must also provide `requestId`, `orderId`, `customer`, `priority`, `placedDate`, `requiredDeliveryDate`, and `asOfDate`. These are required by Stockio's allocation model and must not be invented by the service. `allowEquivalent` defaults to `true`.

### 2. Perform the required Tavily lookup

Run a Tavily search for the requested product and reasonable equivalent names or descriptions before each allocation. Tavily result titles and content participate in matching against Stockio's catalog. A local vector-search hit may add candidate context, but it is not a substitute for completing Tavily lookup.

If Tavily is unavailable, returns no useful evidence, or the result cannot be mapped to a Stockio catalog product, fail closed: report that no allocation was made. Do not allocate using an unverified SKU or silently fall back to a stale result.

### 3. Resolve a result to a catalog SKU

Use Tavily results as evidence for product identity, not as inventory records. Compare the evidence to Stockio's active product catalog and resolve to a SKU that Stockio already knows. The implementation embeds the query and search evidence with Ollama, compares them with catalog and cached-knowledge vectors in pgvector, and boosts candidates whose catalog title appears in Tavily evidence. Exact normalized title matches are preferred.

For non-exact matches, the default minimum similarity is `0.72`; if the top two candidate scores differ by less than `0.04`, the match is ambiguous. Both values are configurable. When the requested product is absent but one equivalent catalog product is a sufficiently strong match, return the selected catalog product and Tavily sources. If no candidate qualifies or the match is ambiguous, stop and return candidates rather than allocating against a guess.

### 4. Allocate through Stockio

After product resolution, construct a normal `AllocationRequest` using the resolved SKU and the validated request details. Invoke the existing allocation application service through a suitable orchestration layer and expose that orchestration through the relevant MCP or HTTP interface.

Keep the responsibilities separate:

- Product resolution determines which catalog SKU the request refers to.
- The existing allocation policy determines eligible stock and inbound supply, reservations, delivery-date constraints, and backorders.
- PostgreSQL allocation records and reservations remain the inventory source of truth.

Do not invoke an MCP server from inside the service as a substitute for calling its application service, and do not implement a second reservation mechanism.

## Knowledge storage and retrieval

Use PostgreSQL with `pgvector`, installed by Flyway migration V13. The local Compose service and integration-test container use the `pgvector/pgvector:pg16` image.

The migration creates:

- `product_embeddings`: cached active-catalog embeddings keyed by SKU, model, and product-content hash.
- `product_knowledge_chunks`: Tavily content chunks associated with the resolved SKU, including source URL/title, retrieval time, content hash, and embedding.

Both vector columns use 768 dimensions. The configured Ollama embedding model must return 768 values; the default is `nomic-embed-text`. Content-hash-based upserts make repeated ingestion idempotent without relying on a chunk index that can change when content changes.

Treat Tavily content as external, potentially stale or incorrect input. Validate and retain provenance, limit what is ingested, and never convert a Tavily result into a Stockio SKU without matching it to the catalog.

## Implementation phases

1. **Provide request metadata.** The MCP caller supplies the order/customer identity and dates required by Stockio, along with a stable `requestId` for retries.
2. **Run required external lookup.** Tavily is called for every request, even when local product embeddings or knowledge are cached. External calls use a bounded timeout.
3. **Resolve and cache product evidence.** Ollama embeds the query, active catalog descriptions, and Tavily results. pgvector ranks catalog products and prior Tavily knowledge; successful source chunks are upserted for the resolved SKU.
4. **Delegate allocation.** The `allocate_product_by_name` MCP tool builds a normal `NEW_DEMAND` `AllocationRequest`, invokes `AllocationService`, and rebuilds the projection. Existing behavior is preserved: available inventory/inbound quantity is allocated, and any remainder is saved as an open backorder.
5. **Return an auditable result.** Return the selected product/SKU, Tavily sources, allocation run, and actual allocated/backordered quantities. If resolution fails, return candidate products and do not allocate.

## Failure and safety behavior

- No allocation begins until the required Tavily lookup has completed and the requested product has been resolved to a catalog SKU.
- Tavily, embedding, or database errors are surfaced as failures; they must not be converted into success-shaped results.
- No credible match or unresolved ambiguity means no allocation. Return useful candidate information when available.
- The selected SKU and product name are included in the result so an equivalent substitution is visible to the caller.
- Reservation and backorder outcomes come from Stockio's allocation result. A request for 50 may result in a partial allocation and an open backorder for the remainder; do not report all 50 as held unless Stockio confirms it.
- Retries must respect the existing `requestId` idempotency and request-hash behavior. Search and ingestion retries must not cause duplicate product-knowledge records or duplicate allocations.

## Acceptance criteria

- A request such as “Hold 50 units of Maki Komkommer, or the nearest equivalent” performs Tavily lookup before allocation and resolves to a real catalog SKU.
- A locally cached product match does not bypass the required Tavily lookup.
- The caller supplies all required order metadata, and shortages follow Stockio's partial-allocation/backorder behavior.
- Tavily failure, no credible catalog match, and unresolved ambiguity each result in no allocation and a clear failure or candidate response.
- A resolved request delegates allocation to Stockio's existing workflow and reports its actual stock, inbound, and backorder results.
- Replaying the same allocation request follows existing idempotency behavior; repeating knowledge ingestion does not create duplicate chunks.
- Automated tests cover successful resolution and allocation, equivalent-product resolution, the failure cases above, and the selected shortage behavior.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `TAVILY_API_KEY` | empty (required at request time) | Tavily API credential. |
| `TAVILY_BASE_URL` | `https://api.tavily.com` | Tavily API base URL. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama service URL. |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model; it must return 768 dimensions. |
| `PRODUCT_RESOLUTION_TIMEOUT` | `15s` | Connect/read timeout for Tavily and Ollama. |
| `PRODUCT_RESOLUTION_MAX_RESULTS` | `5` | Tavily result count. |
| `PRODUCT_RESOLUTION_MAX_CANDIDATES` | `5` | Maximum catalog candidates evaluated/returned. |
| `PRODUCT_RESOLUTION_MINIMUM_SIMILARITY` | `0.72` | Minimum score for a non-exact product match. |
| `PRODUCT_RESOLUTION_MINIMUM_SIMILARITY_GAP` | `0.04` | Minimum lead over the second candidate to accept an equivalent. |
| `PRODUCT_RESOLUTION_MINIMUM_TAVILY_EVIDENCE_SIMILARITY` | `0.25` | Minimum cosine similarity between the requested phrase and Tavily evidence. |

Set `TAVILY_API_KEY` before using the MCP tool, start Ollama at the configured URL, and pull the configured model (for the default model, run `ollama pull nomic-embed-text`). PostgreSQL must support the `vector` extension; the Compose and integration-test configurations use pgvector-enabled images.
