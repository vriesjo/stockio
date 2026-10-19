package nl.stockio.product.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import kotlin.math.sqrt
import nl.stockio.allocation.domain.api.bean.AllocationRequest
import nl.stockio.allocation.domain.api.bean.AllocationRunType
import nl.stockio.allocation.domain.api.bean.DemandOrder
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.product.domain.model.ProductAllocationRequest
import nl.stockio.product.domain.model.ProductAllocationResponse
import nl.stockio.product.domain.model.ProductAllocationStatus
import nl.stockio.product.domain.model.ProductCandidate
import nl.stockio.product.domain.model.ProductSource
import nl.stockio.product.domain.model.ResolvedProduct
import nl.stockio.product.infra.ProductEntity
import nl.stockio.product.infra.ProductKnowledgeChunk
import nl.stockio.product.infra.ProductRepository
import nl.stockio.product.infra.ProductVectorRepository
import org.springframework.stereotype.Service

@Service
class ProductAllocationService(
    private val tavilySearchClient: TavilySearchClient,
    private val embeddingClient: OllamaEmbeddingClient,
    private val productRepository: ProductRepository,
    private val productVectorRepository: ProductVectorRepository,
    private val allocationService: AllocationService,
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val properties: ProductResolutionProperties
) {

    fun allocate(request: ProductAllocationRequest): ProductAllocationResponse {
        validate(request)

        val tavilyResults = try {
            tavilySearchClient.search(tavilyQuery(request))
        } catch (exception: TavilyLookupException) {
            logger.error(exception) { "Required Tavily product lookup failed for request ${request.requestId}." }
            return ProductAllocationResponse(
                status = ProductAllocationStatus.TAVILY_LOOKUP_FAILED,
                message = exception.message ?: "Required Tavily product lookup failed."
            )
        }
        val sources = tavilyResults.map { ProductSource(it.title, it.url) }
        if (tavilyResults.isEmpty()) {
            return ProductAllocationResponse(
                status = ProductAllocationStatus.TAVILY_LOOKUP_FAILED,
                message = "Tavily returned no usable product-search results; no allocation was made."
            )
        }

        val products = productRepository.findAllByStatus("ACTIVE")
        if (products.isEmpty()) {
            return ProductAllocationResponse(
                status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                message = "The product catalog contains no active products; no allocation was made.",
                sources = sources
            )
        }

        val queryEmbedding: List<Double>
        val evidenceEmbedding: List<Double>
        val candidates: List<ProductCandidate>
        try {
            val searchEvidence = tavilyResults.joinToString("\n") { "${it.title}\n${it.content}" }
                .take(MAX_SEARCH_EVIDENCE_CHARACTERS)
            val embeddings = embeddingClient.embed(listOf(request.productQuery, searchEvidence))
            if (embeddings.size != 2) {
                throw EmbeddingClientException("Ollama returned an unexpected number of product-search embeddings.")
            }
            queryEmbedding = embeddings[0]
            evidenceEmbedding = embeddings[1]
            validateEmbedding(queryEmbedding)
            validateEmbedding(evidenceEmbedding)
            if (cosineSimilarity(queryEmbedding, evidenceEmbedding) < properties.minimumTavilyEvidenceSimilarity) {
                return ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "Tavily returned no relevant evidence for '${request.productQuery}'; no allocation was made.",
                    sources = sources
                )
            }
            refreshProductEmbeddings(products)
            candidates = findCandidates(
                query = request.productQuery,
                products = products,
                queryEmbedding = queryEmbedding,
                evidenceEmbedding = evidenceEmbedding,
                tavilyResults = tavilyResults
            )
        } catch (exception: EmbeddingClientException) {
            logger.error(exception) { "Product embedding failed for request ${request.requestId}." }
            return ProductAllocationResponse(
                status = ProductAllocationStatus.EMBEDDING_FAILED,
                message = exception.message ?: "Product embedding failed; no allocation was made.",
                sources = sources
            )
        }

        val exactProduct = products.firstOrNull {
            normalize(it.title) == normalize(request.productQuery)
        }
        val selected = if (exactProduct != null) {
            ProductCandidate(exactProduct.sku, exactProduct.title, 1.0)
        } else {
            if (!request.allowEquivalent) {
                return ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "No exact catalog product matched '${request.productQuery}'; no allocation was made.",
                    candidates = candidates,
                    sources = sources
                )
            }
            val best = candidates.firstOrNull()
            val second = candidates.getOrNull(1)
            if (best == null || best.similarity < properties.minimumSimilarity) {
                return ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "No catalog product met the minimum similarity for '${request.productQuery}'; no allocation was made.",
                    candidates = candidates,
                    sources = sources
                )
            }
            if (second != null && best.similarity - second.similarity < properties.minimumSimilarityGap) {
                return ProductAllocationResponse(
                    status = ProductAllocationStatus.AMBIGUOUS_PRODUCT_MATCH,
                    message = "More than one catalog product is a plausible match for '${request.productQuery}'; no allocation was made.",
                    candidates = candidates,
                    sources = sources
                )
            }
            best
        }

        try {
            persistKnowledge(selected.sku, tavilyResults)
        } catch (exception: EmbeddingClientException) {
            logger.error(exception) { "Could not embed Tavily product knowledge for request ${request.requestId}." }
            return ProductAllocationResponse(
                status = ProductAllocationStatus.EMBEDDING_FAILED,
                message = exception.message ?: "Product knowledge ingestion failed; no allocation was made.",
                selectedProduct = selected.toResolvedProduct(exactProduct == null),
                sources = sources
            )
        }

        val allocationRequest = AllocationRequest(
            requestId = request.requestId,
            asOfDate = request.asOfDate,
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(
                DemandOrder(
                    orderId = request.orderId,
                    customer = request.customer,
                    sku = selected.sku,
                    requestedQuantity = request.quantity,
                    priority = request.priority,
                    placedDate = request.placedDate,
                    requiredDeliveryDate = request.requiredDeliveryDate
                )
            )
        )
        val allocationRun = allocationService.allocation(allocationRequest)
        stockProjectionProcessor.process(request.asOfDate)

        return ProductAllocationResponse(
            status = ProductAllocationStatus.ALLOCATION_PROCESSED,
            message = "Allocation processed. The result reports allocated quantities and any backorder.",
            selectedProduct = selected.toResolvedProduct(exactProduct == null),
            candidates = candidates,
            sources = sources,
            allocationRun = allocationRun
        )
    }

    private fun refreshProductEmbeddings(products: List<ProductEntity>) {
        val hashes = productVectorRepository.findProductEmbeddingHashes(properties.embeddingModel)
        val staleProducts = products.filter { product ->
            hashes[product.sku] != sha256(productText(product))
        }
        if (staleProducts.isEmpty()) return

        val embeddings = embeddingClient.embed(staleProducts.map(::productText))
        if (embeddings.size != staleProducts.size) {
            throw EmbeddingClientException("Ollama returned an unexpected number of product embeddings.")
        }
        staleProducts.zip(embeddings).forEach { (product, embedding) ->
            validateEmbedding(embedding)
            productVectorRepository.upsertProductEmbedding(
                sku = product.sku,
                model = properties.embeddingModel,
                contentHash = sha256(productText(product)),
                embedding = embedding
            )
        }
    }

    private fun findCandidates(
        query: String,
        products: List<ProductEntity>,
        queryEmbedding: List<Double>,
        evidenceEmbedding: List<Double>,
        tavilyResults: List<TavilySearchResult>
    ): List<ProductCandidate> {
        val activeProducts = products.associateBy { it.sku }
        val queryScores = productSimilarities(queryEmbedding)
        val evidenceScores = productSimilarities(evidenceEmbedding)
        val scores = queryScores.mapValuesTo(mutableMapOf()) { (sku, queryScore) ->
            evidenceScores[sku]?.let { queryScore * QUERY_SCORE_WEIGHT + it * EVIDENCE_SCORE_WEIGHT }
                ?: queryScore
        }
        evidenceScores.forEach { (sku, evidenceScore) ->
            if (sku !in scores) scores[sku] = evidenceScore
        }

        val normalizedQuery = normalize(query)
        val normalizedEvidence = normalize(tavilyResults.joinToString(" ") { "${it.title} ${it.content}" })
        products.forEach { product ->
            val normalizedTitle = normalize(product.title)
            if (normalizedTitle == normalizedQuery) {
                scores[product.sku] = 1.0
            } else if (normalizedTitle.isNotEmpty() && normalizedQuery.contains(normalizedTitle)) {
                scores[product.sku] = maxOf(scores[product.sku] ?: 0.0, 0.9)
            }
            if (normalizedTitle.isNotEmpty() && normalizedEvidence.contains(normalizedTitle)) {
                scores[product.sku] = maxOf(scores[product.sku] ?: 0.0, TAVILY_TITLE_MATCH_SCORE)
            }
        }
        return scores.mapNotNull { (sku, score) ->
            activeProducts[sku]?.let { ProductCandidate(sku, it.title, score) }
        }.sortedByDescending { it.similarity }
            .take(properties.maxCandidates)
    }

    private fun productSimilarities(embedding: List<Double>): Map<String, Double> {
        val productScores = productVectorRepository.findSimilarProducts(
            embedding = embedding,
            model = properties.embeddingModel,
            limit = properties.maxCandidates
        ).associate { it.sku to it.similarity.coerceIn(-1.0, 1.0) }
        val knowledgeScores = productVectorRepository.findSimilarKnowledge(
            embedding = embedding,
            model = properties.embeddingModel,
            limit = properties.maxCandidates * KNOWLEDGE_CANDIDATE_MULTIPLIER
        ).groupBy { it.sku }.mapValues { (_, matches) ->
            matches.maxOf { it.similarity.coerceIn(-1.0, 1.0) }
        }
        return (productScores.keys + knowledgeScores.keys).associateWith { sku ->
            maxOf(
                productScores[sku] ?: Double.NEGATIVE_INFINITY,
                knowledgeScores[sku] ?: Double.NEGATIVE_INFINITY
            )
        }
    }

    private fun persistKnowledge(sku: String, results: List<TavilySearchResult>) {
        val textChunks = results.flatMap { result ->
            splitIntoChunks(result.content).map { chunk ->
                Triple(result, chunk, sha256("${result.url}\n$chunk"))
            }
        }
        if (textChunks.isEmpty()) return

        val embeddings = embeddingClient.embed(textChunks.map { it.second })
        if (embeddings.size != textChunks.size) {
            throw EmbeddingClientException("Ollama returned an unexpected number of knowledge embeddings.")
        }
        val chunks = textChunks.zip(embeddings).map { (entry, embedding) ->
            validateEmbedding(embedding)
            ProductKnowledgeChunk(
                sourceUrl = entry.first.url,
                sourceTitle = entry.first.title,
                content = entry.second,
                contentHash = entry.third,
                embedding = embedding
            )
        }
        productVectorRepository.upsertKnowledgeChunks(sku, properties.embeddingModel, chunks)
    }

    private fun validate(request: ProductAllocationRequest) {
        require(request.orderId.isNotBlank()) { "orderId must not be blank." }
        require(request.customer.isNotBlank()) { "customer must not be blank." }
        require(request.productQuery.isNotBlank()) { "productQuery must not be blank." }
        require(request.quantity > 0) { "quantity must be positive." }
        require(request.priority > 0) { "priority must be positive." }
        require(request.placedDate <= request.requiredDeliveryDate) {
            "placedDate must be on or before requiredDeliveryDate."
        }
    }

    private fun validateEmbedding(embedding: List<Double>) {
        if (embedding.size != ProductVectorRepository.Companion.EMBEDDING_DIMENSIONS || embedding.any { !it.isFinite() }) {
            throw EmbeddingClientException(
                "The configured Ollama embedding model must return ${ProductVectorRepository.Companion.EMBEDDING_DIMENSIONS} finite values."
            )
        }
    }

    private fun cosineSimilarity(left: List<Double>, right: List<Double>): Double {
        val dotProduct = left.zip(right).sumOf { (leftValue, rightValue) -> leftValue * rightValue }
        val leftNorm = sqrt(left.sumOf { it * it })
        val rightNorm = sqrt(right.sumOf { it * it })
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return dotProduct / (leftNorm * rightNorm)
    }

    private fun tavilyQuery(request: ProductAllocationRequest): String =
        if (request.allowEquivalent) {
            "${request.productQuery} product or nearest equivalent"
        } else {
            request.productQuery
        }

    private fun productText(product: ProductEntity): String =
        listOfNotNull(product.title, product.description, product.category)
            .filter(String::isNotBlank)
            .joinToString(". ")

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun splitIntoChunks(content: String): List<String> {
        val words = content.split(Regex("\\s+")).filter(String::isNotBlank)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        words.forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > MAX_CHUNK_CHARACTERS) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun ProductCandidate.toResolvedProduct(equivalent: Boolean) =
        ResolvedProduct(sku, title, similarity, equivalent)

    companion object {
        private const val MAX_CHUNK_CHARACTERS = 2000
        private const val MAX_SEARCH_EVIDENCE_CHARACTERS = 6000
        private const val QUERY_SCORE_WEIGHT = 0.7
        private const val EVIDENCE_SCORE_WEIGHT = 0.3
        private const val TAVILY_TITLE_MATCH_SCORE = 0.98
        private const val KNOWLEDGE_CANDIDATE_MULTIPLIER = 5
        private val logger = KotlinLogging.logger {}
    }
}
