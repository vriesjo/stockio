package nl.stockio.product.resolution

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.service.BackorderAllocationService
import nl.stockio.api.dto.ProductAllocationRequest
import nl.stockio.api.dto.ProductAllocationStatus
import nl.stockio.product.domain.service.OllamaEmbeddingClient
import nl.stockio.product.domain.service.EmbeddingClientException
import nl.stockio.product.domain.service.ProductAllocationService
import nl.stockio.product.domain.service.ProductResolutionProperties
import nl.stockio.product.domain.service.TavilyLookupException
import nl.stockio.product.domain.service.TavilySearchClient
import nl.stockio.product.domain.service.TavilySearchResult
import nl.stockio.product.infra.ProductEntity
import nl.stockio.product.infra.ProductKnowledgeMatch
import nl.stockio.product.infra.ProductJpaRepository
import nl.stockio.product.infra.ProductVectorMatch
import nl.stockio.product.infra.ProductVectorJdbcTemplateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ProductAllocationServiceTest {

    @Test
    fun `fallback Tavily lookup failure prevents allocation`() {
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = object : TavilySearchClient {
                override fun search(query: String): List<TavilySearchResult> =
                    throw TavilyLookupException("Tavily is unavailable.")
            },
            productVectorRepository = vectorRepository(
                emptyList(), listOf(ProductVectorMatch("MAKI-KOMKOMMER", "Maki Komkommer", 0.80))
            ),
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "cucumber sushi roll"))

        assertEquals(ProductAllocationStatus.TAVILY_LOOKUP_FAILED, response.status)
        assertNull(response.allocationRun)
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `irrelevant fallback Tavily results prevent allocation`() {
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = TavilySearchClient {
                listOf(TavilySearchResult("Weather report", "https://example.test/weather", "Rain tomorrow"))
            },
            embeddingClient = OllamaEmbeddingClient { texts ->
                texts.map { if (it.contains("Weather report")) unitVector(1) else unitVector(0) }
            },
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "cucumber sushi roll"))

        assertEquals(ProductAllocationStatus.NO_PRODUCT_MATCH, response.status)
        assertNull(response.allocationRun)
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `exact catalog match is allocated through the existing allocation service`() {
        val requested = request(productQuery = "  MAKI-komkommer ").copy(allowEquivalent = false)
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V")
        val allocationService = mock(AllocationService::class.java)
        val vectorRepository = vectorRepository(listOf(product))
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val embeddingClient = mock(OllamaEmbeddingClient::class.java)
        val expectedRun = AllocationRunResult(
            runId = UUID.randomUUID(),
            requestId = request().requestId,
            runType = AllocationRunType.NEW_DEMAND,
            asOfDate = LocalDate.now(),
            createdAt = Instant.now(),
            openBackorderCount = 1,
            allocations = emptyList()
        )
        val expectedRequest = allocationRequest(requested, "MAKI-KOMKOMMER")
        `when`(allocationService.allocation(expectedRequest)).thenReturn(expectedRun)
        val sut = service(
            productRepository = productRepository(listOf(product)),
            productVectorRepository = vectorRepository,
            allocationService = allocationService,
            tavilySearchClient = tavilySearchClient,
            embeddingClient = embeddingClient
        )

        val response = sut.allocate(requested)
        val replay = sut.allocate(requested)

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals("MAKI-KOMKOMMER", response.selectedProduct?.sku)
        assertFalse(response.selectedProduct?.equivalent ?: true)
        assertEquals(expectedRun, response.allocationRun)
        assertEquals(expectedRun, replay.allocationRun)
        verify(allocationService, times(2)).allocation(expectedRequest)
        verifyNoInteractions(tavilySearchClient, embeddingClient)
        assertTrue(response.sources.isEmpty())
    }

    @Test
    fun `weak local match falls back to Tavily and resolves an equivalent`() {
        val requested = request(productQuery = "cucumber sushi roll")
        val products = listOf(
            ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"),
            ProductEntity(sku = "MAKI-AVOCADO", title = "Maki Avocado", category = "V")
        )
        val productVectorRepository = vectorRepository(
            products,
            matches = listOf(
                ProductVectorMatch("MAKI-KOMKOMMER", "Maki Komkommer", 0.86),
                ProductVectorMatch("MAKI-AVOCADO", "Maki Avocado", 0.74)
            )
        )
        val allocationService = mock(AllocationService::class.java)
        `when`(allocationService.allocation(allocationRequest(requested, "MAKI-KOMKOMMER"))).thenReturn(
            AllocationRunResult(
                runId = UUID.randomUUID(),
                requestId = requested.requestId,
                runType = AllocationRunType.NEW_DEMAND,
                asOfDate = requested.asOfDate,
                createdAt = Instant.now(),
                openBackorderCount = 0,
                allocations = emptyList()
            )
        )
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        `when`(tavilySearchClient.search("cucumber sushi roll product or nearest equivalent")).thenReturn(
            listOf(TavilySearchResult("Maki Komkommer", "https://example.test/maki", "Cucumber maki sushi"))
        )
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(products),
            productVectorRepository = productVectorRepository,
            allocationService = allocationService
        )

        val response = sut.allocate(requested)

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals("MAKI-KOMKOMMER", response.selectedProduct?.sku)
        assertTrue(response.selectedProduct?.equivalent ?: false)
        verify(tavilySearchClient).search("cucumber sushi roll product or nearest equivalent")
        assertEquals("https://example.test/maki", response.sources.single().url)
    }

    @Test
    fun `ambiguous equivalent candidates do not trigger allocation`() {
        val products = listOf(
            ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"),
            ProductEntity(sku = "MAKI-AVOCADO", title = "Maki Avocado", category = "V")
        )
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = TavilySearchClient {
                listOf(TavilySearchResult("Sushi products", "https://example.test/sushi", "Various sushi rolls"))
            },
            productRepository = productRepository(products),
            productVectorRepository = vectorRepository(
                products,
                matches = listOf(
                    ProductVectorMatch("MAKI-KOMKOMMER", "Maki Komkommer", 0.84),
                    ProductVectorMatch("MAKI-AVOCADO", "Maki Avocado", 0.82)
                )
            ),
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "sushi"))

        assertEquals(ProductAllocationStatus.AMBIGUOUS_PRODUCT_MATCH, response.status)
        assertEquals(2, response.candidates.size)
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `strong separated local match allocates without Tavily`() {
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer")
        val secondProduct = ProductEntity(sku = "MAKI-AVOCADO", title = "Maki Avocado")
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val allocationService = successfulAllocationService()
        val requested = request(productQuery = "cucumber sushi roll")
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(listOf(product, secondProduct)),
            productVectorRepository = vectorRepository(
                listOf(product, secondProduct),
                listOf(
                    ProductVectorMatch(product.sku, product.title, 0.95),
                    ProductVectorMatch(secondProduct.sku, secondProduct.title, 0.80)
                )
            ),
            allocationService = allocationService
        )

        val response = sut.allocate(requested)

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals(product.sku, response.selectedProduct?.sku)
        assertTrue(response.selectedProduct?.equivalent ?: false)
        assertTrue(response.sources.isEmpty())
        verify(allocationService).allocation(allocationRequest(requested, product.sku))
        verifyNoInteractions(tavilySearchClient)
    }

    @Test
    fun `cached knowledge can resolve a local match without Tavily`() {
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer")
        val vectors = vectorRepository(listOf(product), emptyList())
        val embedding = List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { 0.01 }
        `when`(vectors.findSimilarKnowledge(embedding, "nomic-embed-text", 25)).thenReturn(
            listOf(ProductKnowledgeMatch(product.sku, 0.96))
        )
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(listOf(product)),
            productVectorRepository = vectors
        )

        val response = sut.allocate(request(productQuery = "cucumber sushi roll"))

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals(product.sku, response.selectedProduct?.sku)
        verifyNoInteractions(tavilySearchClient)
    }

    @Test
    fun `ambiguous strong local matches still require Tavily`() {
        val products = listOf(
            ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer"),
            ProductEntity(sku = "MAKI-AVOCADO", title = "Maki Avocado")
        )
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        `when`(tavilySearchClient.search("sushi product or nearest equivalent")).thenReturn(
            listOf(TavilySearchResult("Sushi", "https://example.test/sushi", "Various sushi rolls"))
        )
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(products),
            productVectorRepository = vectorRepository(
                products,
                listOf(
                    ProductVectorMatch(products[0].sku, products[0].title, 0.96),
                    ProductVectorMatch(products[1].sku, products[1].title, 0.94)
                )
            ),
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "sushi"))

        assertEquals(ProductAllocationStatus.AMBIGUOUS_PRODUCT_MATCH, response.status)
        verify(tavilySearchClient).search("sushi product or nearest equivalent")
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `local gap is stricter than fallback gap`() {
        val products = listOf(
            ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer"),
            ProductEntity(sku = "MAKI-AVOCADO", title = "Maki Avocado")
        )
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        `when`(tavilySearchClient.search("sushi product or nearest equivalent")).thenReturn(
            listOf(TavilySearchResult("Sushi", "https://example.test/sushi", "Various sushi rolls"))
        )
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(products),
            productVectorRepository = vectorRepository(
                products,
                listOf(
                    ProductVectorMatch(products[0].sku, products[0].title, 0.96),
                    ProductVectorMatch(products[1].sku, products[1].title, 0.91)
                )
            )
        )

        val response = sut.allocate(request(productQuery = "sushi"))

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        verify(tavilySearchClient).search("sushi product or nearest equivalent")
    }

    @Test
    fun `equivalents disabled rejects non-exact matches without external calls`() {
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val embeddingClient = mock(OllamaEmbeddingClient::class.java)
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            embeddingClient = embeddingClient,
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "cucumber sushi roll").copy(allowEquivalent = false))

        assertEquals(ProductAllocationStatus.NO_PRODUCT_MATCH, response.status)
        verifyNoInteractions(tavilySearchClient, embeddingClient, allocationService)
    }

    @Test
    fun `empty catalog avoids external calls and allocation`() {
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val embeddingClient = mock(OllamaEmbeddingClient::class.java)
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            embeddingClient = embeddingClient,
            productRepository = productRepository(emptyList()),
            allocationService = allocationService
        )

        val response = sut.allocate(request())

        assertEquals(ProductAllocationStatus.NO_PRODUCT_MATCH, response.status)
        verifyNoInteractions(tavilySearchClient, embeddingClient, allocationService)
    }

    @Test
    fun `local embedding failure does not call Tavily or allocate`() {
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            embeddingClient = OllamaEmbeddingClient { throw EmbeddingClientException("Ollama is unavailable.") },
            allocationService = allocationService
        )

        val response = sut.allocate(request(productQuery = "cucumber sushi roll"))

        assertEquals(ProductAllocationStatus.EMBEDDING_FAILED, response.status)
        verifyNoInteractions(tavilySearchClient, allocationService)
    }

    @Test
    fun `missing catalog vectors are refreshed before local matching`() {
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V")
        val vectors = vectorRepository(
            listOf(product), listOf(ProductVectorMatch(product.sku, product.title, 0.96))
        )
        val tavilySearchClient = mock(TavilySearchClient::class.java)
        val sut = service(
            tavilySearchClient = tavilySearchClient,
            productRepository = productRepository(listOf(product)),
            productVectorRepository = vectors
        )
        `when`(vectors.findProductEmbeddingHashes("nomic-embed-text")).thenReturn(emptyMap())

        val response = sut.allocate(request(productQuery = "cucumber sushi roll"))

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        val inOrder = org.mockito.Mockito.inOrder(vectors)
        val embedding = List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { 0.01 }
        inOrder.verify(vectors).upsertProductEmbedding(
            product.sku, "nomic-embed-text", sha256("Maki Komkommer. V"), embedding
        )
        inOrder.verify(vectors).findSimilarProducts(embedding, "nomic-embed-text", 5)
        verifyNoInteractions(tavilySearchClient)
    }

    private fun service(
        tavilySearchClient: TavilySearchClient = TavilySearchClient { query ->
            assertTrue(query.contains("nearest equivalent"))
            listOf(TavilySearchResult("Maki Komkommer", "https://example.test/maki", "Cucumber maki sushi"))
        },
        embeddingClient: OllamaEmbeddingClient = OllamaEmbeddingClient { texts ->
            texts.map { List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { 0.01 } }
        },
        productRepository: ProductJpaRepository = productRepository(
            listOf(ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"))
        ),
        productVectorRepository: ProductVectorJdbcTemplateRepository = vectorRepository(
            listOf(ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"))
        ),
        allocationService: AllocationService = successfulAllocationService()
    ): ProductAllocationService {
        val products = productRepository.findAllByStatus("ACTIVE")
        val productHashes = products.associate { product ->
            product.sku to sha256(listOfNotNull(product.title, product.description, product.category).joinToString(". "))
        }
        `when`(productVectorRepository.findProductEmbeddingHashes("nomic-embed-text")).thenReturn(productHashes)
        return ProductAllocationService(
            tavilySearchClient = tavilySearchClient,
            embeddingClient = embeddingClient,
            productRepository = productRepository,
            productVectorRepository = productVectorRepository,
            processingService = AllocationProcessor(
                allocationService,
                mock(StockProjectionProcessor::class.java),
                mock(BackorderAllocationService::class.java)
            ),
            properties = ProductResolutionProperties()
        )
    }

    private fun successfulAllocationService(): AllocationService =
        mock(AllocationService::class.java) { invocation ->
            if (invocation.method.name == "allocation") {
                val request = invocation.getArgument<AllocationRequest>(0)
                AllocationRunResult(
                    UUID.randomUUID(), request.requestId, request.runType, request.asOfDate,
                    Instant.now(), 0, emptyList()
                )
            } else {
                org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    private fun productRepository(products: List<ProductEntity>): ProductJpaRepository =
        mock(ProductJpaRepository::class.java).also {
            `when`(it.findAllByStatus("ACTIVE")).thenReturn(products)
        }

    private fun vectorRepository(
        products: List<ProductEntity>,
        matches: List<ProductVectorMatch> = products.map { ProductVectorMatch(it.sku, it.title, 0.90) }
    ): ProductVectorJdbcTemplateRepository =
        mock(ProductVectorJdbcTemplateRepository::class.java).also {
            val embedding = List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { 0.01 }
            `when`(it.findSimilarProducts(embedding, "nomic-embed-text", 5))
                .thenReturn(matches)
            `when`(it.findSimilarKnowledge(embedding, "nomic-embed-text", 25))
                .thenReturn(emptyList())
        }

    private fun allocationRequest(request: ProductAllocationRequest, sku: String) = AllocationRequest(
        requestId = request.requestId,
        asOfDate = request.asOfDate,
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = listOf(
            DemandOrder(
                orderId = request.orderId,
                customer = request.customer,
                priority = request.priority,
                placedDate = request.placedDate,
                lines = listOf(
                    DemandOrderLine(
                        orderLineId = UUID.nameUUIDFromBytes(
                            "stockio:product-order-line:${request.orderId}".toByteArray(Charsets.UTF_8)
                        ).toString(),
                        sku = sku,
                        requestedQuantity = request.quantity,
                        requiredDeliveryDate = request.requiredDeliveryDate
                    )
                )
            )
        )
    )

    private fun request(productQuery: String = "Maki Komkommer") = ProductAllocationRequest(
        requestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        orderId = "HOLD-50-MAKI",
        customer = "Test Customer",
        productQuery = productQuery,
        quantity = 50,
        priority = 1,
        placedDate = LocalDate.now(),
        requiredDeliveryDate = LocalDate.now(),
        asOfDate = LocalDate.now()
    )

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun unitVector(index: Int) =
        List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { if (it == index) 1.0 else 0.0 }
}
