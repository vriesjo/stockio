package nl.stockio.product.infra

import nl.stockio.product.domain.port.ProductVectorRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class ProductVectorMatch(
    val sku: String,
    val title: String,
    val similarity: Double
)

data class ProductKnowledgeMatch(
    val sku: String,
    val similarity: Double
)

data class ProductKnowledgeChunk(
    val sourceUrl: String,
    val sourceTitle: String,
    val content: String,
    val contentHash: String,
    val embedding: List<Double>
)

@ConditionalOnProperty(prefix = "stockio.produc.vector.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
@Repository
class ProductVectorJdbcTemplateRepository(
    private val jdbcTemplate: JdbcTemplate
) : ProductVectorRepository {

    override fun findProductEmbeddingHashes(model: String): Map<String, String> =
        jdbcTemplate.query(
            "SELECT sku, content_hash FROM product_embeddings WHERE model = ?",
            { resultSet, _ -> resultSet.getString("sku") to resultSet.getString("content_hash") },
            model
        ).toMap()

    @Transactional
    override fun upsertProductEmbedding(sku: String, model: String, contentHash: String, embedding: List<Double>) {
        jdbcTemplate.update(
            """
                INSERT INTO product_embeddings (sku, model, content_hash, embedding)
                VALUES (?, ?, ?, CAST(? AS vector))
                ON CONFLICT (sku) DO UPDATE
                SET model = EXCLUDED.model,
                    content_hash = EXCLUDED.content_hash,
                    embedding = EXCLUDED.embedding,
                    updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
            sku,
            model,
            contentHash,
            vectorLiteral(embedding)
        )
    }

    override fun findSimilarProducts(
        embedding: List<Double>,
        model: String,
        limit: Int
    ): List<ProductVectorMatch> =
        jdbcTemplate.query(
            """
                SELECT p.sku, p.title, 1 - (e.embedding <=> CAST(? AS vector)) AS similarity
                FROM product_embeddings e
                JOIN products p ON p.sku = e.sku
                WHERE e.model = ? AND p.status = 'ACTIVE'
                ORDER BY e.embedding <=> CAST(? AS vector)
                LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                ProductVectorMatch(
                    sku = resultSet.getString("sku"),
                    title = resultSet.getString("title"),
                    similarity = resultSet.getDouble("similarity")
                )
            },
            vectorLiteral(embedding),
            model,
            vectorLiteral(embedding),
            limit
        )

    override fun findSimilarKnowledge(
        embedding: List<Double>,
        model: String,
        limit: Int
    ): List<ProductKnowledgeMatch> =
        jdbcTemplate.query(
            """
                SELECT k.sku, 1 - (k.embedding <=> CAST(? AS vector)) AS similarity
                FROM product_knowledge_chunks k
                JOIN products p ON p.sku = k.sku
                WHERE k.model = ? AND p.status = 'ACTIVE'
                ORDER BY k.embedding <=> CAST(? AS vector)
                LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                ProductKnowledgeMatch(
                    sku = resultSet.getString("sku"),
                    similarity = resultSet.getDouble("similarity")
                )
            },
            vectorLiteral(embedding),
            model,
            vectorLiteral(embedding),
            limit
        )

    @Transactional
    override fun upsertKnowledgeChunks(sku: String, model: String, chunks: List<ProductKnowledgeChunk>) {
        chunks.forEach { chunk ->
            jdbcTemplate.update(
                """
                    INSERT INTO product_knowledge_chunks (
                        sku, model, source_url, source_title, content, content_hash, embedding
                    )
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector))
                    ON CONFLICT (sku, source_url, content_hash) DO UPDATE
                    SET model = EXCLUDED.model,
                        source_title = EXCLUDED.source_title,
                        content = EXCLUDED.content,
                        embedding = EXCLUDED.embedding,
                        retrieved_at = CURRENT_TIMESTAMP
                """.trimIndent(),
                sku,
                model,
                chunk.sourceUrl,
                chunk.sourceTitle,
                chunk.content,
                chunk.contentHash,
                vectorLiteral(chunk.embedding)
            )
        }
    }

    private fun vectorLiteral(values: List<Double>): String {
        require(values.size == EMBEDDING_DIMENSIONS) {
            "Expected $EMBEDDING_DIMENSIONS embedding dimensions but received ${values.size}."
        }
        require(values.all(Double::isFinite)) { "Embedding values must be finite." }
        return values.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    companion object {
        const val EMBEDDING_DIMENSIONS = 768
    }
}
