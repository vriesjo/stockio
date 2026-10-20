package nl.stockio.product.domain.port

import nl.stockio.product.infra.ProductKnowledgeChunk
import nl.stockio.product.infra.ProductKnowledgeMatch
import nl.stockio.product.infra.ProductVectorMatch

interface ProductVectorRepository {
    fun findProductEmbeddingHashes(model: String): Map<String, String>
    fun upsertProductEmbedding(sku: String, model: String, contentHash: String, embedding: List<Double>)
    fun findSimilarProducts(embedding: List<Double>, model: String, limit: Int): List<ProductVectorMatch>
    fun findSimilarKnowledge(embedding: List<Double>, model: String, limit: Int): List<ProductKnowledgeMatch>
    fun upsertKnowledgeChunks(sku: String, model: String, chunks: List<ProductKnowledgeChunk>)
}
