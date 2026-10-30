package nl.stockio.product.domain.api

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.ProductAllocationRequest
import nl.stockio.api.dto.ProductAllocationResponse
import nl.stockio.api.dto.ProductAllocationStatus
import nl.stockio.product.domain.service.ProductAllocationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

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
        val mockMvc: MockMvc = MockMvcBuilders
            .standaloneSetup(ProductAllocationController(service))
            .build()
        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        val result = mockMvc.perform(
            post("/api/products/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NO_PRODUCT_MATCH"))
            .andReturn()

        assertEquals(
            expectedResponse,
            objectMapper.readValue(result.response.contentAsString, ProductAllocationResponse::class.java)
        )
        verify(service).allocate(request)
    }

    @Test
    fun `post allocate returns bad request when service rejects request`() {
        val request = request()
        val service = mock(ProductAllocationService::class.java)
        `when`(service.allocate(request)).thenThrow(IllegalArgumentException("Invalid request"))
        val mockMvc: MockMvc = MockMvcBuilders
            .standaloneSetup(ProductAllocationController(service))
            .build()
        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        mockMvc.perform(
            post("/api/products/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Invalid request"))
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
}
