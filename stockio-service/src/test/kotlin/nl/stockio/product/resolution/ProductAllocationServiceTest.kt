package nl.stockio.product.resolution

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.bean.AllocationRequest
import nl.stockio.allocation.domain.api.bean.AllocationRunResult
import nl.stockio.allocation.domain.api.bean.AllocationRunType
import nl.stockio.allocation.domain.api.bean.DemandOrder
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.product.domain.model.ProductAllocationRequest
import nl.stockio.product.domain.model.ProductAllocationStatus
import nl.stockio.product.domain.service.OllamaEmbeddingClient
import nl.stockio.product.domain.service.ProductAllocationService
import nl.stockio.product.domain.service.ProductResolutionProperties
import nl.stockio.product.domain.service.TavilyLookupException
import nl.stockio.product.domain.service.TavilySearchClient
import nl.stockio.product.domain.service.TavilySearchResult
import nl.stockio.product.infra.ProductEntity
import nl.stockio.product.infra.ProductRepository
import nl.stockio.product.infra.ProductVectorMatch
import nl.stockio.product.infra.ProductVectorRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ProductAllocationServiceTest {

    @Test
    fun `required Tavily lookup failure prevents allocation`() {
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = object : TavilySearchClient {
                override fun search(query: String): List<TavilySearchResult> =
                    throw TavilyLookupException("Tavily is unavailable.")
            },
            allocationService = allocationService
        )

        val response = sut.allocate(request())

        assertEquals(ProductAllocationStatus.TAVILY_LOOKUP_FAILED, response.status)
        assertNull(response.allocationRun)
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `irrelevant Tavily results prevent allocation even when the catalog has an exact title`() {
        val allocationService = mock(AllocationService::class.java)
        val sut = service(
            tavilySearchClient = TavilySearchClient {
                listOf(TavilySearchResult("Weather report", "https://example.test/weather", "Rain tomorrow"))
            },
            embeddingClient = OllamaEmbeddingClient { texts ->
                if (texts.size == 2) {
                    listOf(unitVector(0), unitVector(1))
                } else {
                    texts.map { unitVector(0) }
                }
            },
            allocationService = allocationService
        )

        val response = sut.allocate(request())

        assertEquals(ProductAllocationStatus.NO_PRODUCT_MATCH, response.status)
        assertNull(response.allocationRun)
        verifyNoInteractions(allocationService)
    }

    @Test
    fun `exact catalog match is allocated through the existing allocation service`() {
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V")
        val allocationService = mock(AllocationService::class.java)
        val vectorRepository = vectorRepository(listOf(product))
        val expectedRun = AllocationRunResult(
            runId = UUID.randomUUID(),
            requestId = request().requestId,
            runType = AllocationRunType.NEW_DEMAND,
            asOfDate = LocalDate.now(),
            createdAt = Instant.now(),
            openBackorderCount = 1,
            allocations = emptyList()
        )
        val expectedRequest = allocationRequest(request(), "MAKI-KOMKOMMER")
        `when`(allocationService.allocation(expectedRequest)).thenReturn(expectedRun)
        val sut = service(
            productRepository = productRepository(listOf(product)),
            productVectorRepository = vectorRepository,
            allocationService = allocationService
        )

        val response = sut.allocate(request())

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals("MAKI-KOMKOMMER", response.selectedProduct?.sku)
        assertFalse(response.selectedProduct?.equivalent ?: true)
        assertEquals(expectedRun, response.allocationRun)
        verify(allocationService).allocation(expectedRequest)
    }

    @Test
    fun `nearest equivalent is selected only when its score is sufficiently separated`() {
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
        val sut = service(
            productRepository = productRepository(products),
            productVectorRepository = productVectorRepository,
            allocationService = allocationService
        )

        val response = sut.allocate(requested)

        assertEquals(ProductAllocationStatus.ALLOCATION_PROCESSED, response.status)
        assertEquals("MAKI-KOMKOMMER", response.selectedProduct?.sku)
        assertTrue(response.selectedProduct?.equivalent ?: false)
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

    private fun service(
        tavilySearchClient: TavilySearchClient = TavilySearchClient { query ->
            assertTrue(query.contains("nearest equivalent"))
            listOf(TavilySearchResult("Maki Komkommer", "https://example.test/maki", "Cucumber maki sushi"))
        },
        embeddingClient: OllamaEmbeddingClient = OllamaEmbeddingClient { texts ->
            texts.map { List(ProductVectorRepository.EMBEDDING_DIMENSIONS) { 0.01 } }
        },
        productRepository: ProductRepository = productRepository(
            listOf(ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"))
        ),
        productVectorRepository: ProductVectorRepository = vectorRepository(
            listOf(ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer", category = "V"))
        ),
        allocationService: AllocationService = mock(AllocationService::class.java)
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
            allocationService = allocationService,
            stockProjectionProcessor = mock(StockProjectionProcessor::class.java),
            properties = ProductResolutionProperties()
        )
    }

    private fun productRepository(products: List<ProductEntity>): ProductRepository =
        mock(ProductRepository::class.java).also {
            `when`(it.findAllByStatus("ACTIVE")).thenReturn(products)
        }

    private fun vectorRepository(
        products: List<ProductEntity>,
        matches: List<ProductVectorMatch> = products.map { ProductVectorMatch(it.sku, it.title, 0.90) }
    ): ProductVectorRepository =
        mock(ProductVectorRepository::class.java).also {
            val embedding = List(ProductVectorRepository.EMBEDDING_DIMENSIONS) { 0.01 }
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
                sku = sku,
                requestedQuantity = request.quantity,
                priority = request.priority,
                placedDate = request.placedDate,
                requiredDeliveryDate = request.requiredDeliveryDate
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
        List(ProductVectorRepository.EMBEDDING_DIMENSIONS) { if (it == index) 1.0 else 0.0 }
}
