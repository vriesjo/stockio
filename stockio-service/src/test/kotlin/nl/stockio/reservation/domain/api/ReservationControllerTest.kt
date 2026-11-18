package nl.stockio.reservation.domain.api

import java.time.Instant
import java.time.LocalDate
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.OrderReservationLine
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.api.dto.RedisKeyResponse
import nl.stockio.allocation.infra.inbound.exception.CustomerExceptionHandler
import nl.stockio.allocation.domain.port.outbound.AllocationGateway
import nl.stockio.reservation.domain.service.OrderReservationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.reactive.server.WebTestClient

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
        val client = WebTestClient.bindToController(ReservationController(service)).build()

        client.get().uri("/api/reservations/orders/ORDER-1/status")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION")
            .jsonPath("$.lines[0].sku").isEqualTo("SKU-1")
            .jsonPath("$.cleanupPending").isEqualTo(false)
        client.get().uri("/api/reservations/orders/MISSING/status")
            .exchange()
            .expectStatus().isNotFound

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
        val client = WebTestClient.bindToController(
            ReservationController(mock(OrderReservationService::class.java), gateway)
        ).controllerAdvice(CustomerExceptionHandler()).build()

        client.get().uri("/api/reservations/orders/stock:sku:SKU-1:MAIN/redis")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.value").isEqualTo("5")
        client.get().uri("/api/reservations/orders/other/redis")
            .exchange()
            .expectStatus().isBadRequest
    }
}
