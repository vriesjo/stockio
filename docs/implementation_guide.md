# Production Implementation Guide: Bridging RAG Knowledge & Deterministic MCP Actions

This document outlines the architectural blueprint and code patterns for combining a **Local RAG Knowledge Base (Postgres + pgvector + Ollama)** with an **Agentic Tavily Fallback Loop** and a **Deterministic Stock Reservation MCP Tool**.

---

## 1. System Architecture Overview

```
                          [User Query / Request]
                                     │
                                     ▼
                      [Local Postgres `pgvector` Search]
                                     │
                     ┌───────────────┴───────────────┐
                     ▼                               ▼
            (Sufficient Match)             (Knowledge Gap / Miss)
                     │                               │
                     │                               ▼
                     │                     [Tavily API Web Search]
                     │                               │
                     │                     [Ollama Chunk & Embed]
                     │                               │
                     │                     [Upsert to Postgres]
                     └───────────────┬───────────────┘
                                     │
                                     ▼
                     [Deterministic MCP Tool Execution]
                           (`reserve_stock` in DB)
```

---

## 2. Database Schema (`Postgres + pgvector`)

Ensure your Postgres instance has the `vector` extension enabled. This schema stores text chunks, their metadata, vector embeddings, and ensures idempotency via unique constraints.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku VARCHAR(100) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(768), -- Matches nomic-embed-text / local embedding dimensions
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT unique_sku_chunk UNIQUE (sku, chunk_index)
);

-- HNSW index for lightning-fast approximate nearest neighbor (ANN) search
CREATE INDEX ON product_knowledge_chunks USING hnsw (embedding vector_cosine_ops);
```

---

## 3. Kotlin Implementation: The Unified Agentic Service

Below is a production-grade Kotlin implementation using a service pattern that checks local storage first, triggers a Tavily web search fallback if a knowledge gap is found, updates the database (write-through cache), and then executes the deterministic stock reservation.

```kotlin
@Service
class AgenticStockService(
    private val vectorRepository: ProductVectorRepository,
    private val tavilyClient: TavilyClient,
    private val ollamaClient: OllamaClient,
    private val stockMcpClient: StockReservationMcpClient
) {

    /**
     * Main entry point for user request combining knowledge retrieval and stock reservation.
     */
    fun processSmartReservation(query: String, sku: String, quantity: Int, warehouseId: String): ReservationResult {
        
        // Step 1: Query Local Postgres pgvector Database
        val queryEmbedding = ollamaClient.generateEmbedding(query)
        val localMatches = vectorRepository.similaritySearch(queryEmbedding, skuFilter = sku, threshold = 0.75)

        // Step 2: Handle Knowledge Gap via Tavily Fallback Loop
        if (localMatches.isEmpty()) {
            println("Knowledge gap detected for SKU: $sku. Triggering Tavily fallback...")
            
            // Call Tavily Search API for clean markdown specs
            val tavilyResponse = tavilyClient.search("Technical specifications and details for SKU $sku")
            
            if (tavilyResponse.results.isNotEmpty()) {
                val rawMarkdown = tavilyResponse.results.joinToString("\n") { it.content }
                
                // Chunk, embed, and persist back into Postgres (Self-Healing Cache)
                ingestAndPersistKnowledge(sku, rawMarkdown)
            } else {
                return ReservationResult.Failed("Could not find product specifications locally or via web search.")
            }
        } else {
            println("Local RAG hit successful for SKU: $sku")
        }

        // Step 3: Execute Deterministic MCP Tool Call
        // The LLM/Agent is now satisfied with context; execute strict ACID transaction
        val mcpResponse = stockMcpClient.reserveStock(
            sku = sku,
            quantity = quantity,
            warehouseId = warehouseId,
            ttl = "24h"
        )

        return if (mcpResponse.success) {
            ReservationResult.Success(mcpResponse.reservationId)
        } else {
            ReservationResult.Failed(mcpResponse.errorMessage)
        }
    }

    private fun ingestAndPersistKnowledge(sku: String, rawText: String) {
        // Chunk text into ~500 token blocks
        val chunks = textChunker.chunk(rawText, maxTokens = 500)
        
        chunks.forEachIndexed { index, chunkText ->
            val embedding = ollamaClient.generateEmbedding(chunkText)
            
            // Upsert with deterministic uniqueness to ensure idempotency
            vectorRepository.upsertChunk(
                sku = sku,
                chunkIndex = index,
                content = chunkText,
                metadata = mapOf("source" to "tavily_fallback_web_search"),
                embedding = embedding
            )
        }
    }
}
```

---

## 4. Key Engineering Best Practices Implemented

1. **Write-Through Self-Healing Cache:** When Tavily fills a knowledge gap, the data is immediately embedded and written to Postgres. Subsequent requests for the same SKU bypass external web calls entirely.
2. **Idempotent Vector Upsertion:** Using `CONSTRAINT unique_sku_chunk UNIQUE (sku, chunk_index)` alongside content-hashing ensures that re-processed ingestion streams never duplicate vector records in `pgvector`.
3. **Strict Separation of Concerns:** Unstructured probabilistic reasoning (Tavily/LLM/Embeddings) is strictly decoupled from the atomic, deterministic execution layer (`StockReservationMcpClient`).