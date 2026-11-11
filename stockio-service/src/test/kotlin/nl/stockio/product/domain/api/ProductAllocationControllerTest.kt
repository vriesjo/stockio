package nl.stockio.product.domain.api

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.ProductAllocationRequest
import nl.stockio.api.dto.ProductAllocationResponse
import nl.stockio.api.dto.ProductAllocationStatus
import nl.stockio.product.domain.service.ProductAllocationService
import nl.stockio.allocation.domain.api.exception.CustomerExceptionHandler
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class ProductAllocationControllerTest {

    @Test
    fun `post allocate invokes product allocation service and returns its result`() {
        val request = request()
        val expectedResponse = ProductAllocationResponse(
            status = ProductAllocationStatus.NO_PRODUCT_MATCH,
            message = "No catalog product matched."
        )
        val service = mock(ProductAllocationService::class.java)
        `when`(service.allocate(request)).thenReturn(expectedResponse)
        val client = WebTestClient.bindToController(ProductAllocationController(service)).build()
        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        val result = client.post()
            .uri("/api/products/allocate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("NO_PRODUCT_MATCH")
            .returnResult()

        assertEquals(
            expectedResponse,
            objectMapper.readValue(result.responseBody, ProductAllocationResponse::class.java)
        )
        verify(service).allocate(request)
    }

    @Test
    fun `post allocate returns bad request when service rejects request`() {
        val request = request()
        val service = mock(ProductAllocationService::class.java)
        `when`(service.allocate(request)).thenThrow(IllegalArgumentException("Invalid request"))
        val client = WebTestClient.bindToController(ProductAllocationController(service)).build()
        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        client.post()
            .uri("/api/products/allocate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").isEqualTo("Invalid request")
    }

    private fun request() = ProductAllocationRequest(
        requestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        orderId = "ORDER-50-MAKI",
        customer = "MCP caller",
        productQuery = "Maki Komkommer",
        quantity = 50,
        priority = 1,
        placedDate = LocalDate.parse("2026-10-19"),
        requiredDeliveryDate = LocalDate.parse("2026-10-20"),
        asOfDate = LocalDate.parse("2026-10-19")
    )

    @Test
    fun `capacity and byte errors remain distinct from the controllers input error handler`() {
        for ((exception, expected, code) in listOf(
            Triple(AllocationCapacityExceededException(), 503, "ALLOCATION_CAPACITY_EXHAUSTED"),
            Triple(AllocationRequestTooLargeException(), 413, "ALLOCATION_REQUEST_TOO_LARGE")
        )) {
            val service = mock(ProductAllocationService::class.java)
            `when`(service.allocate(request())).thenThrow(exception)
            val client = WebTestClient.bindToController(ProductAllocationController(service))
                .controllerAdvice(CustomerExceptionHandler()).build()
            client.post().uri("/api/products/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jacksonObjectMapper().findAndRegisterModules().writeValueAsString(request()))
                .exchange()
                .expectStatus().isEqualTo(expected)
                .expectBody()
                .jsonPath("$.code").isEqualTo(code)
        }
    }
}
