package nl.stockio.stock.domain.processor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.domain.port.StockLedgerRun
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.port.StockReceiptFollowup
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.port.StockSnapshotRepository
import nl.stockio.stock.infra.StockDailyLotSnapshot
import nl.stockio.stock.infra.StockDailySnapshot
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StockLedgerSkuWorkerTest {
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private val today = LocalDate.of(2026, 10, 26)
    private val clock = mockk<Clock>()
    private val stocks = mockk<StockRepository>(relaxed = true)
    private val snapshots = mockk<StockSnapshotRepository>(relaxed = true)
    private val reservations = mockk<StockReservationRepository>(relaxed = true)
    private val supplies = mockk<InboundSupplyRepository>(relaxed = true)
    private val inbound = mockk<InboundReservationRepository>(relaxed = true)
    private val projection = mockk<StockProjectionProcessor>(relaxed = true)
    private val claims = mockk<AllocationOrderClaimRepository>(relaxed = true)
    private val runs = mockk<StockLedgerRunRepository>(relaxed = true)
    private val locks = mockk<AllocationSkuLockRepository>(relaxed = true)
    private val lines = mockk<DemandOrderLineRepository>(relaxed = true)
    private val worker = StockLedgerSkuWorker(
        stocks, snapshots, reservations, supplies, inbound, projection, claims, runs, clock, locks, lines
    )
    private val run = StockLedgerRun(UUID.randomUUID(), today, listOf("SKU"))
    private val owner = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { clock.zone } returns ZoneOffset.UTC
        every { clock.instant() } returns now
        every { runs.lockWork(run.id, "SKU", owner) } returns true
    }

    @Test
    fun `business date is rechecked after waiting for the SKU lock`() {
        every { locks.lockBySku("SKU") } answers {
            every { clock.instant() } returns now.plusSeconds(86400)
            mockk()
        }
        shouldThrow<IllegalArgumentException> { worker.process(run, owner, "SKU") }
        verify(exactly = 0) { projection.processSku(any(), any()) }
        verify(exactly = 0) { runs.completeWork(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `expiry deadline is rechecked after waiting for the SKU lock`() {
        val claim = claim("ORDER", OrderReservationStatus.PENDING_CONFIRMATION)
            .apply { reservationExpiresAt = now.plusSeconds(1) }
        every { claims.findAllById(any()) } returns listOf(claim)
        every { locks.lockBySku("SKU") } answers {
            every { clock.instant() } returns now.plusSeconds(1)
            mockk()
        }
        shouldThrow<LedgerCleanupRequiredException> { worker.process(run, owner, "SKU") }
        verify(exactly = 0) { projection.processSku(any(), any()) }
    }

    @Test
    fun `locks SKU before decision reads and checkpoints only after fresh projection and effects`() {
        worker.process(run, owner, "SKU")
        verifyOrder {
            locks.lockBySku("SKU")
            runs.lockWork(run.id, "SKU", owner)
            claims.findAllById(any())
            projection.processSku(today, "SKU")
            snapshots.findDailyBySkuAndDate("SKU", today)
            stocks.saveAll(any<Iterable<StockEntity>>())
            runs.completeWork(run.id, "SKU", owner, 0, 0, now)
        }
        verify(exactly = 0) { stocks.findAll() }
        verify(exactly = 0) { reservations.findAll() }
        verify(exactly = 0) { supplies.findAll() }
    }

    @Test
    fun `completed work is replayed without physical movement or projection`() {
        every { runs.lockWork(run.id, "SKU", owner) } returns false
        worker.process(run, owner, "SKU")
        verify(exactly = 0) { projection.processSku(any(), any()) }
        verify(exactly = 0) { runs.completeWork(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `overdue whole-order cleanup is requested without expanding the held SKU set`() {
        every { claims.findAllById(any()) } returns listOf(
            claim("ORDER", OrderReservationStatus.PENDING_CONFIRMATION).apply { reservationExpiresAt = now }
        )
        shouldThrow<LedgerCleanupRequiredException> { worker.process(run, owner, "SKU") }
            .orderIds shouldBe listOf("ORDER")
        verify(exactly = 0) { projection.processSku(any(), any()) }
        verify(exactly = 1) { locks.lockBySku("SKU") }
    }

    @Test
    fun `applies due confirmed deliveries but preserves pending future and unrelated holds`() {
        val stock = StockEntity(sku = "SKU", lotId = "LOT", quantityOnHand = 10, quantityReserved = 5)
        val due = reservation(stock, "CONFIRMED", 3, today)
        val pending = reservation(stock, "PENDING", 1, today.minusDays(1))
        val future = reservation(stock, "CONFIRMED", 1, today.plusDays(1))
        every { stocks.lockAllBySkuIn(listOf("SKU")) } returns listOf(stock)
        every { snapshots.findDailyBySkuAndDate("SKU", today) } returns listOf(daily(7))
        every { snapshots.findLotsBySkuAndDate("SKU", today) } returns listOf(lot("LOT", 7, 2))
        every { claims.findAllById(any()) } returns listOf(
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED),
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION)
        )
        every { reservations.findAllBySku("SKU") } returns listOf(due, pending, future)

        worker.process(run, owner, "SKU")

        stock.quantityOnHand shouldBe 7
        stock.quantityReserved shouldBe 2
        verify { reservations.deleteAll(listOf(due)) }
        verify { reservations.saveAll(match<Iterable<StockReservationEntity>> { it.toSet() == setOf(pending, future) }) }
        verify { runs.completeWork(run.id, "SKU", owner, 1, 1, now) }
    }

    @Test
    fun `receipts convert retained holds and persist stable follow-up with the checkpoint`() {
        val supply = InboundSupplyEntity(
            shipmentId = "SHIPMENT", shipmentLineId = "LINE", sku = "SKU", lotId = "RECEIPT",
            expectedQuantity = 10, quantityReserved = 6, expectedArrivalDate = today
        )
        val holds = listOf(
            inboundHold("PENDING", 1, today.minusDays(1)),
            inboundHold("CONFIRMED", 3, today.plusDays(1)),
            inboundHold("CONFIRMED", 2, today)
        )
        every { supplies.findAllBySku("SKU") } returns listOf(supply)
        every { supplies.lockAllByShipmentLineIdIn(listOf("LINE")) } returns listOf(supply)
        every { inbound.lockAllByShipmentLineIdIn(listOf("LINE")) } returns holds
        every { snapshots.findDailyBySkuAndDate("SKU", today) } returns listOf(daily(8))
        every { snapshots.findLotsBySkuAndDate("SKU", today) } returns listOf(lot("RECEIPT", 8, 4))
        every { claims.findAllById(any()) } returns listOf(
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION),
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED)
        )
        val retained = slot<Iterable<StockReservationEntity>>()
        every { reservations.saveAll(capture(retained)) } answers { firstArg<Iterable<StockReservationEntity>>().toList() }

        worker.process(run, owner, "SKU")

        supply.received shouldBe true
        supply.quantityReserved shouldBe 0
        retained.captured.map { it.quantity }.sorted() shouldBe listOf(1, 3)
        verify { inbound.deleteAll(holds) }
        val id = UUID.nameUUIDFromBytes("stock-ledger-backorder-fulfillment:$today:LINE".toByteArray())
        verify { runs.recordReceipt(StockReceiptFollowup(id, "SKU", today), run.id, now) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing aggregate", "mismatched total", "reserved omission"])
    fun `rejects invalid snapshots without a successful checkpoint`(failure: String) {
        if (failure != "missing aggregate") {
            every { snapshots.findDailyBySkuAndDate("SKU", today) } returns listOf(daily(6))
        }
        if (failure != "reserved omission") {
            every { snapshots.findLotsBySkuAndDate("SKU", today) } returns listOf(lot("LOT", 5))
        } else {
            every { stocks.lockAllBySkuIn(listOf("SKU")) } returns listOf(
                StockEntity(sku = "SKU", lotId = "LOT", quantityOnHand = 6, quantityReserved = 2)
            )
            every { snapshots.findDailyBySkuAndDate("SKU", today) } returns listOf(daily(0))
        }
        shouldThrow<IllegalArgumentException> { worker.process(run, owner, "SKU") }
        verify(exactly = 0) { runs.completeWork(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { runs.recordReceipt(any(), any(), any()) }
    }

    private fun claim(id: String, status: OrderReservationStatus) = AllocationOrderClaimEntity(
        orderId = id, orderReservationStatus = status, reservationCreatedAt = now,
        reservationExpiresAt = now.plusSeconds(1800)
    )

    private fun reservation(stock: StockEntity, orderId: String, quantity: Int, date: LocalDate) =
        StockReservationEntity(
            orderId = orderId, orderLineId = "$orderId-$date", stockId = stock.id,
            quantity = quantity, requiredDeliveryDate = date
        )

    private fun inboundHold(orderId: String, quantity: Int, date: LocalDate) = InboundReservationEntity(
        orderId = orderId, orderLineId = "$orderId-$date", shipmentId = "SHIPMENT", shipmentLineId = "LINE",
        quantity = quantity, requiredDeliveryDate = date
    )

    private fun daily(closing: Int) = StockDailySnapshot(
        snapshotDate = today, sku = "SKU", warehouseCode = "MAIN", openingStock = closing,
        inboundQuantity = 0, demandQuantity = 0, allocatedQuantity = 0, closingStock = closing, shortageQuantity = 0
    )

    private fun lot(id: String, closing: Int, reserved: Int = 0) = StockDailyLotSnapshot(
        snapshotDate = today, sku = "SKU", warehouseCode = "MAIN", lotId = id, receivedDate = today,
        openingStock = closing, inboundQuantity = 0, allocatedQuantity = 0, closingStock = closing,
        quantityReserved = reserved
    )
}
