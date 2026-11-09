package nl.stockio.reservation.domain.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.port.AllocationDocumentLockRepository
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.infra.AllocationDocumentLockEntity
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationSkuLockEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import org.junit.jupiter.api.Test

class ReservationCleanupServiceTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val today = LocalDate.of(2026, 11, 9)
    private val claims = mockk<AllocationOrderClaimRepository>()
    private val lines = mockk<DemandOrderLineRepository>()
    private val stock = mockk<StockRepository>()
    private val stockReservations = mockk<StockReservationRepository>()
    private val inboundSupply = mockk<InboundSupplyRepository>()
    private val inboundReservations = mockk<InboundReservationRepository>()
    private val backorders = mockk<OpenBackorderRepository>()
    private val documentLocks = mockk<AllocationDocumentLockRepository>()
    private val skuLocks = mockk<AllocationSkuLockRepository>()
    private val projection = mockk<StockProjectionProcessor>()
    private val service = ReservationCleanupService(
        claims, lines, stock, stockReservations, inboundSupply, inboundReservations, backorders,
        ReservationProperties(ttl = Duration.ofMinutes(30), expiryBatchSize = 25),
        Clock.fixed(now, ZoneOffset.UTC), documentLocks, skuLocks, projection
    )

    @Test
    fun `returns zero without locking when no orders are due`() {
        every { claims.findExpiredOrderIds(now, 25) } returns emptyList()

        service.expireDueBatch() shouldBe 0
        service.expireOrders(emptyList()) shouldBe 0

        verify(exactly = 1) { claims.findExpiredOrderIds(now, 25) }
        verify { lines wasNot Called }
        verify { documentLocks wasNot Called }
        verify { projection wasNot Called }
    }

    @Test
    fun `does not select an expired claim again`() {
        val claim = claim(OrderReservationStatus.EXPIRED, expiredAt = now.minusSeconds(30))
        allowLocks()
        every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf("order-1")) } returns listOf(line())
        every { claims.lockByOrderId("order-1") } returns claim

        service.expireOrders(listOf("order-1")) shouldBe 0

        claim.orderReservationStatus shouldBe OrderReservationStatus.EXPIRED
        claim.expiredAt shouldBe now.minusSeconds(30)
        verify { stock wasNot Called }
        verify { stockReservations wasNot Called }
        verify { projection wasNot Called }
    }

    @Test
    fun `fails when a reservation exceeds the reserved stock quantity`() {
        val stockId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        allowLocks()
        every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(any()) } returns listOf(line())
        every { claims.lockByOrderId("order-1") } returns claim(OrderReservationStatus.PENDING_CONFIRMATION)
        every { stockReservations.lockAllByOrderIdIn(listOf("order-1")) } returns listOf(
            StockReservationEntity(
                id = reservationId, orderId = "order-1", orderLineId = "line-1", stockId = stockId,
                quantity = 5, requiredDeliveryDate = today
            )
        )
        every { inboundReservations.lockAllByOrderIdIn(listOf("order-1")) } returns emptyList()
        every { stock.lockAllBySkuIn(listOf("SKU")) } returns listOf(
            StockEntity(id = stockId, sku = "SKU", quantityReserved = 1, receivedDate = today)
        )

        shouldThrow<IllegalStateException> { service.expireOrders(listOf("order-1")) }
            .message shouldBe "Reservation '$reservationId' exceeds the reserved quantity on stock '$stockId'."

        verify { projection wasNot Called }
        verify(exactly = 0) { stockReservations.deleteAll(any()) }
    }

    private fun allowLocks() {
        every { documentLocks.insertIfMissing(any()) } returns 1
        every { documentLocks.lockByKey(any()) } returns AllocationDocumentLockEntity("order:order-1")
        every { skuLocks.insertIfMissing(any()) } returns 1
        every { skuLocks.lockBySku(any()) } returns AllocationSkuLockEntity("SKU")
    }

    private fun claim(status: OrderReservationStatus, expiredAt: Instant? = null) = AllocationOrderClaimEntity(
        orderId = "order-1",
        orderReservationStatus = status,
        reservationCreatedAt = now.minusSeconds(120),
        reservationExpiresAt = now.minusSeconds(1),
        expiredAt = expiredAt
    )

    private fun line() = DemandOrderLineEntity(
        orderLineId = "line-1", orderId = "order-1", sku = "SKU", requestedQuantity = 5,
        requiredDeliveryDate = today
    )
}
