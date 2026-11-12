package nl.stockio.product.application.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.ProductAllocationRequest
import nl.stockio.api.dto.ProductAllocationResponse
import nl.stockio.api.dto.ProductAllocationStatus
import nl.stockio.api.dto.ProductCandidate
import nl.stockio.api.dto.ProductSource
import nl.stockio.api.dto.ResolvedProduct
import nl.stockio.product.domain.port.ProductRepository
import nl.stockio.product.domain.service.EmbeddingClientException
import nl.stockio.product.domain.service.OllamaEmbeddingClient
import nl.stockio.product.domain.service.ProductResolutionProperties
import nl.stockio.product.domain.service.TavilyLookupException
import nl.stockio.product.domain.service.TavilySearchClient
import nl.stockio.product.domain.service.TavilySearchResult
import nl.stockio.product.infra.ProductKnowledgeChunk
import nl.stockio.product.infra.ProductVectorJdbcTemplateRepository
import nl.stockio.product.infra.outbound.persistence.ProductEntity
import nl.stockio.util.blockingElastic
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sqrt
import kotlin.text.get

/**
 * Service responsible for allocating products based on a product query.
 *
 * This service handles the allocation of products by first checking for exact matches in the product catalog.
 * If no exact match is found, it uses embeddings to find similar products and may fall back to Tavily search results.
 * The service also manages the persistence of knowledge embeddings for future allocations.
 */
@Service
class ProductAllocationService(
    private val tavilySearchClient: TavilySearchClient,
    private val embeddingClient: OllamaEmbeddingClient,
    private val productRepository: ProductRepository,
    private val productVectorRepository: ProductVectorJdbcTemplateRepository,
    private val processingService: AllocationProcessor,
    private val properties: ProductResolutionProperties,
) {
    private class PreparedProductAllocation(
        val response: ProductAllocationResponse,
        val allocationRequest: AllocationRequest? = null,
    )

    suspend fun allocate(request: ProductAllocationRequest): ProductAllocationResponse {
        val prepared = withContext(blockingElastic) { resolve(request) }
        val allocationRequest = prepared.allocationRequest ?: return prepared.response
        val run = processingService.process(allocationRequest)
        return prepared.response.copy(allocationRun = run)
    }

    private fun resolve(request: ProductAllocationRequest): PreparedProductAllocation {
        validate(request)

        val products = productRepository.findAllByStatus("ACTIVE")
        if (products.isEmpty()) {
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "The product catalog contains no active products; no allocation was made.",
                ),
            )
        }
        val exactProduct =
            products.firstOrNull {
                normalize(it.title) == normalize(request.productQuery)
            }
        if (exactProduct != null) {
            val selected = ProductCandidate(exactProduct.sku, exactProduct.title, 1.0)
            return allocateResolvedProduct(request, selected, false, listOf(selected), emptyList())
        }
        if (!request.allowEquivalent) {
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "No exact catalog product matched '${request.productQuery}'; no allocation was made.",
                ),
            )
        }

        val queryEmbedding: List<Double>
        val localCandidates: List<ProductCandidate>
        try {
            val embeddings = embeddingClient.embed(listOf(request.productQuery))
            if (embeddings.size != 1) {
                throw EmbeddingClientException("Ollama returned an unexpected number of product-query embeddings.")
            }
            queryEmbedding = embeddings.single()
            validateEmbedding(queryEmbedding)
            refreshProductEmbeddings(products)
            val activeProducts = products.associateBy { it.sku }
            localCandidates =
                productSimilarities(queryEmbedding)
                    .mapNotNull { (sku, score) ->
                        activeProducts[sku]?.let { ProductCandidate(sku, it.title, score) }
                    }.sortedByDescending { it.similarity }
        } catch (exception: EmbeddingClientException) {
            logger.error(exception) { "Local product embedding failed for request ${request.requestId}." }
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.EMBEDDING_FAILED,
                    message = exception.message ?: "Local product embedding failed; no allocation was made.",
                ),
            )
        }
        val localBest = localCandidates.firstOrNull()
        val localSecond = localCandidates.getOrNull(1)
        if (localBest != null &&
            localBest.similarity >= properties.minimumLocalSimilarity &&
            (localSecond == null || localBest.similarity - localSecond.similarity >= properties.minimumLocalSimilarityGap)
        ) {
            return allocateResolvedProduct(
                request,
                localBest,
                true,
                localCandidates.take(properties.maxCandidates),
                emptyList(),
            )
        }

        val tavilyResults =
            try {
                tavilySearchClient.search(tavilyQuery(request))
            } catch (exception: TavilyLookupException) {
                logger.error(exception) { "Fallback Tavily product lookup failed for request ${request.requestId}." }
                return PreparedProductAllocation(
                    ProductAllocationResponse(
                        status = ProductAllocationStatus.TAVILY_LOOKUP_FAILED,
                        message = exception.message ?: "Fallback Tavily product lookup failed.",
                        candidates = localCandidates.take(properties.maxCandidates),
                    ),
                )
            }
        val sources = tavilyResults.map { ProductSource(it.title, it.url) }
        if (tavilyResults.isEmpty()) {
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.TAVILY_LOOKUP_FAILED,
                    message = "Tavily returned no usable product-search results; no allocation was made.",
                    candidates = localCandidates.take(properties.maxCandidates),
                ),
            )
        }

        val evidenceEmbedding: List<Double>
        val candidates: List<ProductCandidate>
        try {
            val searchEvidence =
                tavilyResults
                    .joinToString("\n") { "${it.title}\n${it.content}" }
                    .take(MAX_SEARCH_EVIDENCE_CHARACTERS)
            val embeddings = embeddingClient.embed(listOf(searchEvidence))
            if (embeddings.size != 1) {
                throw EmbeddingClientException("Ollama returned an unexpected number of product-search embeddings.")
            }
            evidenceEmbedding = embeddings.single()
            validateEmbedding(evidenceEmbedding)
            if (cosineSimilarity(queryEmbedding, evidenceEmbedding) < properties.minimumTavilyEvidenceSimilarity) {
                return PreparedProductAllocation(
                    ProductAllocationResponse(
                        status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                        message = "Tavily returned no relevant evidence for '${request.productQuery}'; no allocation was made.",
                        sources = sources,
                    ),
                )
            }
            candidates =
                findCandidates(
                    query = request.productQuery,
                    products = products,
                    queryEmbedding = queryEmbedding,
                    evidenceEmbedding = evidenceEmbedding,
                    tavilyResults = tavilyResults,
                )
        } catch (exception: EmbeddingClientException) {
            logger.error(exception) { "Product embedding failed for request ${request.requestId}." }
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.EMBEDDING_FAILED,
                    message = exception.message ?: "Product embedding failed; no allocation was made.",
                    sources = sources,
                ),
            )
        }

        val selected = candidates.firstOrNull()
        val second = candidates.getOrNull(1)
        if (selected == null || selected.similarity < properties.minimumSimilarity) {
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.NO_PRODUCT_MATCH,
                    message = "No catalog product met the minimum similarity for '${request.productQuery}'; no allocation was made.",
                    candidates = candidates.take(properties.maxCandidates),
                    sources = sources,
                ),
            )
        }
        if (second != null && selected.similarity - second.similarity < properties.minimumSimilarityGap) {
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.AMBIGUOUS_PRODUCT_MATCH,
                    message = "More than one catalog product is a plausible match for '${request.productQuery}'; no allocation was made.",
                    candidates = candidates.take(properties.maxCandidates),
                    sources = sources,
                ),
            )
        }

        try {
            persistKnowledge(selected.sku, tavilyResults)
        } catch (exception: EmbeddingClientException) {
            logger.error(exception) { "Could not embed Tavily product knowledge for request ${request.requestId}." }
            return PreparedProductAllocation(
                ProductAllocationResponse(
                    status = ProductAllocationStatus.EMBEDDING_FAILED,
                    message = exception.message ?: "Product knowledge ingestion failed; no allocation was made.",
                    selectedProduct = selected.toResolvedProduct(true),
                    sources = sources,
                ),
            )
        }

        return allocateResolvedProduct(request, selected, true, candidates.take(properties.maxCandidates), sources)
    }

    private fun allocateResolvedProduct(
        request: ProductAllocationRequest,
        selected: ProductCandidate,
        equivalent: Boolean,
        candidates: List<ProductCandidate>,
        sources: List<ProductSource>,
    ): PreparedProductAllocation {
        val allocationRequest =
            AllocationRequest(
                requestId = request.requestId,
                asOfDate = request.asOfDate,
                runType = AllocationRunType.NEW_DEMAND,
                demandOrders =
                    listOf(
                        DemandOrder(
                            orderId = request.orderId,
                            customer = request.customer,
                            priority = request.priority,
                            placedDate = request.placedDate,
                            lines =
                                listOf(
                                    DemandOrderLine(
                                        orderLineId =
                                            UUID
                                                .nameUUIDFromBytes(
                                                    "stockio:product-order-line:${request.orderId}".toByteArray(Charsets.UTF_8),
                                                ).toString(),
                                        sku = selected.sku,
                                        requestedQuantity = request.quantity,
                                        requiredDeliveryDate = request.requiredDeliveryDate,
                                    ),
                                ),
                        ),
                    ),
            )
        return PreparedProductAllocation(
            response =
                ProductAllocationResponse(
                    status = ProductAllocationStatus.ALLOCATION_PROCESSED,
                    message = "Allocation processed. The result reports allocated quantities and any backorder.",
                    selectedProduct = selected.toResolvedProduct(equivalent),
                    candidates = candidates,
                    sources = sources,
                ),
            allocationRequest = allocationRequest,
        )
    }

    private fun refreshProductEmbeddings(products: List<ProductEntity>) {
        val hashes = productVectorRepository.findProductEmbeddingHashes(properties.embeddingModel)
        val staleProducts =
            products.filter { product ->
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
                embedding = embedding,
            )
        }
    }

    private fun findCandidates(
        query: String,
        products: List<ProductEntity>,
        queryEmbedding: List<Double>,
        evidenceEmbedding: List<Double>,
        tavilyResults: List<TavilySearchResult>,
    ): List<ProductCandidate> {
        val activeProducts = products.associateBy { it.sku }
        val queryScores = productSimilarities(queryEmbedding)
        val evidenceScores = productSimilarities(evidenceEmbedding)
        val scores =
            queryScores.mapValuesTo(mutableMapOf()) { (sku, queryScore) ->
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
        return scores
            .mapNotNull { (sku, score) ->
                activeProducts[sku]?.let { ProductCandidate(sku, it.title, score) }
            }.sortedByDescending { it.similarity }
            .take(maxOf(2, properties.maxCandidates))
    }

    private fun productSimilarities(embedding: List<Double>): Map<String, Double> {
        val productScores =
            productVectorRepository
                .findSimilarProducts(
                    embedding = embedding,
                    model = properties.embeddingModel,
                    limit = maxOf(2, properties.maxCandidates),
                ).associate { it.sku to it.similarity.coerceIn(-1.0, 1.0) }
        val knowledgeScores =
            productVectorRepository
                .findSimilarKnowledge(
                    embedding = embedding,
                    model = properties.embeddingModel,
                    limit = properties.maxCandidates * KNOWLEDGE_CANDIDATE_MULTIPLIER,
                ).groupBy { it.sku }
                .mapValues { (_, matches) ->
                    matches.maxOf { it.similarity.coerceIn(-1.0, 1.0) }
                }
        return (productScores.keys + knowledgeScores.keys).associateWith { sku ->
            maxOf(
                productScores[sku] ?: Double.NEGATIVE_INFINITY,
                knowledgeScores[sku] ?: Double.NEGATIVE_INFINITY,
            )
        }
    }

    private fun persistKnowledge(
        sku: String,
        results: List<TavilySearchResult>,
    ) {
        val textChunks =
            results.flatMap { result ->
                splitIntoChunks(result.content).map { chunk ->
                    Triple(result, chunk, sha256("${result.url}\n$chunk"))
                }
            }
        if (textChunks.isEmpty()) return

        val embeddings = embeddingClient.embed(textChunks.map { it.second })
        if (embeddings.size != textChunks.size) {
            throw EmbeddingClientException("Ollama returned an unexpected number of knowledge embeddings.")
        }
        val chunks =
            textChunks.zip(embeddings).map { (entry, embedding) ->
                validateEmbedding(embedding)
                ProductKnowledgeChunk(
                    sourceUrl = entry.first.url,
                    sourceTitle = entry.first.title,
                    content = entry.second,
                    contentHash = entry.third,
                    embedding = embedding,
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
        if (embedding.size != ProductVectorJdbcTemplateRepository.Companion.EMBEDDING_DIMENSIONS || embedding.any { !it.isFinite() }) {
            throw EmbeddingClientException(
                "The configured Ollama embedding model must return ${ProductVectorJdbcTemplateRepository.Companion.EMBEDDING_DIMENSIONS} finite values.",
            )
        }
    }

    private fun cosineSimilarity(
        left: List<Double>,
        right: List<Double>,
    ): Double {
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
        value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

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
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun ProductCandidate.toResolvedProduct(equivalent: Boolean) = ResolvedProduct(sku, title, similarity, equivalent)

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
