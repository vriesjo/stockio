package nl.stockio.reservation.domain.service

import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.api.dto.OrderReservationLine
import nl.stockio.api.dto.OrderReservationStatus
import org.junit.jupiter.api.Test

class OrderReservationServiceTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val today = LocalDate.of(2026, 11, 9)
    private val claims = mockk<AllocationOrderClaimRepository>()
    private val lines = mockk<DemandOrderLineRepository>()
    private val service = OrderReservationService(claims, lines, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `returns null when the order has no claim`() {
        every { claims.findById("missing") } returns Optional.empty()

        service.getOrderReservationStatus("missing") shouldBe null

        verify { lines wasNot Called }
    }

    @Test
    fun `keeps a pending reservation that is not yet due`() {
        val claim = claim(expiresAt = now.plusSeconds(1))
        every { claims.findById("order-1") } returns Optional.of(claim)
        every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf("order-1")) } returns storedLines()

        val response = service.getOrderReservationStatus("order-1")

        response?.status shouldBe OrderReservationStatus.PENDING_CONFIRMATION
        response?.cleanupPending shouldBe false
        response?.lines shouldBe listOf(OrderReservationLine("line-1", "SKU-1", 2, today))
        response?.expiresAt shouldBe claim.reservationExpiresAt
    }

    @Test
    fun `reports a pending reservation as expired once the deadline has passed`() {
        val claim = claim(expiresAt = now)
        every { claims.findById("order-1") } returns Optional.of(claim)
        every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf("order-1")) } returns storedLines()

        val response = service.getOrderReservationStatus("order-1")

        response?.status shouldBe OrderReservationStatus.EXPIRED
        response?.cleanupPending shouldBe true
        response?.expiredAt shouldBe null
    }

    @Test
    fun `keeps an already terminal status when the deadline has passed`() {
        listOf(OrderReservationStatus.CONFIRMED, OrderReservationStatus.EXPIRED).forEach { status ->
            val claim = claim(status = status, expiresAt = now.minusSeconds(1), confirmedAt = now.minusSeconds(30))
            every { claims.findById("order-1") } returns Optional.of(claim)
            every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf("order-1")) } returns storedLines()

            val response = service.getOrderReservationStatus("order-1")

            response?.status shouldBe status
            response?.cleanupPending shouldBe false
        }
    }

    private fun claim(
        status: OrderReservationStatus = OrderReservationStatus.PENDING_CONFIRMATION,
        expiresAt: Instant?,
        confirmedAt: Instant? = null
    ) = AllocationOrderClaimEntity(
        orderId = "order-1",
        runId = UUID.randomUUID(),
        customer = "Northwind",
        priority = 1,
        placedDate = today,
        orderReservationStatus = status,
        reservationCreatedAt = now.minusSeconds(60),
        reservationExpiresAt = expiresAt,
        confirmedAt = confirmedAt
    )

    private fun storedLines() = listOf(
        DemandOrderLineEntity(
            orderLineId = "line-1",
            orderId = "order-1",
            sku = "SKU-1",
            requestedQuantity = 2,
            requiredDeliveryDate = today
        )
    )
}
