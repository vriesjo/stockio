package nl.stockio.reservation.domain.api

import java.time.Instant
import java.time.LocalDate
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.OrderReservationLine
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.api.dto.RedisKeyResponse
import nl.stockio.allocation.domain.api.exception.CustomerExceptionHandler
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.reservation.domain.service.OrderReservationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ReservationControllerTest {

    @Test
    fun `generated interface maps reservation status route and preserves not found behavior`() {
        val service = mock(OrderReservationService::class.java)
        val reservation = OrderReservationResponse(
            orderId = "ORDER-1",
            lines = listOf(
                OrderReservationLine(
                    orderLineId = "LINE-1",
                    sku = "SKU-1",
                    requestedQuantity = 2,
                    requiredDeliveryDate = LocalDate.parse("2026-10-29")
                )
            ),
            status = OrderReservationStatus.PENDING_CONFIRMATION,
            createdAt = Instant.parse("2026-10-29T12:00:00Z"),
            expiresAt = Instant.parse("2026-10-29T12:30:00Z"),
            confirmedAt = null,
            expiredAt = null,
            cleanupPending = false
        )
        `when`(service.getOrderReservationStatus("ORDER-1")).thenReturn(reservation)
        `when`(service.getOrderReservationStatus("MISSING")).thenReturn(null)
        val mockMvc = MockMvcBuilders.standaloneSetup(ReservationController(service)).build()

        mockMvc.perform(get("/api/reservations/orders/ORDER-1/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
            .andExpect(jsonPath("$.lines[0].sku").value("SKU-1"))
            .andExpect(jsonPath("$.cleanupPending").value(false))
        mockMvc.perform(get("/api/reservations/orders/MISSING/status"))
            .andExpect(status().isNotFound)

        verify(service).getOrderReservationStatus("ORDER-1")
        verify(service).getOrderReservationStatus("MISSING")
    }

    @Test
    fun `generated interface maps redis key lookup`() {
        val gateway = object : AllocationGateway {
            override val enabled = true
            override fun accept(request: AllocationRequest) = Unit
            override fun rollback(request: AllocationRequest) = Unit
            override fun settle(request: AllocationRequest) = Unit
            override fun getByKey(key: String) =
                RedisKeyResponse(key, value = "5").takeIf { key == "stock:sku:SKU-1:MAIN" }
        }
        val mockMvc = MockMvcBuilders.standaloneSetup(
            ReservationController(mock(OrderReservationService::class.java), gateway)
        ).setControllerAdvice(CustomerExceptionHandler()).build()

        mockMvc.perform(get("/api/reservations/orders/stock:sku:SKU-1:MAIN/redis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value").value("5"))
        mockMvc.perform(get("/api/reservations/orders/other/redis"))
            .andExpect(status().isBadRequest)
    }
}
