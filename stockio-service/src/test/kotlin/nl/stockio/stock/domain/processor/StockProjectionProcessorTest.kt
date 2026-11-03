package nl.stockio.stock.domain.processor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.OpenBackorderEntity
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.port.StockSnapshotRepository
import nl.stockio.stock.domain.port.StockScopeRepository
import org.springframework.transaction.PlatformTransactionManager
import nl.stockio.stock.infra.StockDailyLotSnapshot
import nl.stockio.stock.infra.StockDailySnapshot
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class StockProjectionProcessorTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val lockRepository = mockk<AllocationSkuLockRepository>(relaxed = true)
    private val stockRepository = mockk<StockRepository>()
    private val stockReservationRepository = mockk<StockReservationRepository>()
    private val supplyRepository = mockk<InboundSupplyRepository>()
    private val inboundReservationRepository = mockk<InboundReservationRepository>()
    private val backorderRepository = mockk<OpenBackorderRepository>()
    private val snapshotRepository = mockk<StockSnapshotRepository>()
    private val claimRepository = mockk<AllocationOrderClaimRepository>()
    private val startDate = slot<LocalDate>()
    private val dailySnapshots = slot<List<StockDailySnapshot>>()
    private val lotSnapshots = slot<List<StockDailyLotSnapshot>>()
    private val processor = StockProjectionProcessor(
        stockRepository,
        stockReservationRepository,
        supplyRepository,
        inboundReservationRepository,
        backorderRepository,
        snapshotRepository,
        claimRepository,
        clock,
        lockRepository,
        mockk<StockScopeRepository>(relaxed = true),
        mockk<PlatformTransactionManager>(relaxed = true)
    )

    @BeforeEach
    fun setUp() {
        every { claimRepository.findAllById(any()) } returns emptyList()
        every { stockRepository.findAllBySku("SKU") } returns emptyList()
        every { stockReservationRepository.findAllBySku("SKU") } returns emptyList()
        every { supplyRepository.findAllBySku("SKU") } returns emptyList()
        every { inboundReservationRepository.findAllBySku("SKU") } returns emptyList()
        every { backorderRepository.findAllBySku("SKU") } returns emptyList()
        every {
            snapshotRepository.replaceSkuFrom("SKU", capture(startDate), capture(dailySnapshots), capture(lotSnapshots))
        } just Runs
    }

    @ParameterizedTest
    @ValueSource(longs = [-3, 0, 2])
    fun `clears empty forecasts from the requested date clamped to business today`(offset: Long) {
        processor.processSku(today.plusDays(offset), "SKU")

        startDate.captured shouldBe today.plusDays(maxOf(offset, 0))
        dailySnapshots.captured shouldBe emptyList()
        lotSnapshots.captured shouldBe emptyList()
        verifyOrder {
            lockRepository.lockBySku("SKU")
            claimRepository.findAllById(any())
            stockRepository.findAllBySku("SKU")
            snapshotRepository.replaceSkuFrom("SKU", any(), any(), any())
        }
        verify(exactly = 1) { snapshotRepository.replaceSkuFrom("SKU", any(), any(), any()) }
    }

    @Test
    fun `projects full arrivals and confirmed deliveries while retaining pending holds`() {
        val stock = stock(onHand = 10, reserved = 6)
        val supply = supply(quantity = 5, reserved = 3, arrivalDate = today.plusDays(1))
        val deliveryDate = today.plusDays(2)
        val pendingDate = today.plusDays(20)
        val confirmedStock = stockReservation(stock, "CONFIRMED", 4, deliveryDate)
        val pendingStock = stockReservation(stock, "PENDING", 2, pendingDate)
        val confirmedInbound = inboundReservation(supply, "CONFIRMED", 2, deliveryDate)
        val pendingInbound = inboundReservation(supply, "PENDING", 1, pendingDate)
        every { stockRepository.findAllBySku("SKU") } returns listOf(stock)
        every { supplyRepository.findAllBySku("SKU") } returns listOf(supply)
        every { stockReservationRepository.findAllBySku("SKU") } returns listOf(confirmedStock, pendingStock)
        every { inboundReservationRepository.findAllBySku("SKU") } returns listOf(confirmedInbound, pendingInbound)
        every { claimRepository.findAllById(any()) } returns listOf(
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED),
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION)
        )

        processor.processSku(today, "SKU")

        val daily = dailySnapshots.captured
        daily.map { it.snapshotDate } shouldBe listOf(today, today.plusDays(1), deliveryDate)
        daily.map { it.openingStock } shouldBe listOf(10, 10, 15)
        daily.map { it.inboundQuantity } shouldBe listOf(0, 5, 0)
        daily.map { it.allocatedQuantity } shouldBe listOf(0, 0, 6)
        daily.map { it.demandQuantity } shouldBe listOf(0, 0, 6)
        daily.map { it.closingStock } shouldBe listOf(10, 15, 9)
        daily.map { it.shortageQuantity } shouldBe listOf(0, 0, 0)

        val stockLots = lotSnapshots.captured.filter { it.lotId == stock.lotId }
        stockLots.map { it.openingStock } shouldBe listOf(10, 10, 10)
        stockLots.map { it.closingStock } shouldBe listOf(10, 10, 6)
        stockLots.map { it.allocatedQuantity } shouldBe listOf(0, 0, 4)
        stockLots.map { it.quantityReserved } shouldBe listOf(6, 6, 2)
        val inboundLots = lotSnapshots.captured.filter { it.lotId == supply.lotId }
        inboundLots.map { it.receivedDate }.distinct() shouldBe listOf(supply.expectedArrivalDate)
        inboundLots.map { it.openingStock } shouldBe listOf(0, 0, 5)
        inboundLots.map { it.inboundQuantity } shouldBe listOf(0, 5, 0)
        inboundLots.map { it.closingStock } shouldBe listOf(0, 5, 3)
        inboundLots.map { it.allocatedQuantity } shouldBe listOf(0, 0, 2)
        inboundLots.map { it.quantityReserved } shouldBe listOf(0, 3, 1)

        stock.quantityOnHand shouldBe 10
        stock.quantityReserved shouldBe 6
        supply.quantityReserved shouldBe 3
        supply.received shouldBe false
    }

    @Test
    fun `aggregates lots by SKU and warehouse and adds only confirmed shortages in MAIN`() {
        every { stockRepository.findAllBySku("SKU") } returns listOf(
            stock(5).apply { warehouseCode = "EAST"; lotId = "EAST-1" },
            stock(7).apply { warehouseCode = "EAST"; lotId = "EAST-2" },
            stock(4)
        )
        every { backorderRepository.findAllBySku("SKU") } returns listOf(
            backorder("CONFIRMED", 2, today.plusDays(1)),
            backorder("PENDING", 100, today.plusDays(20))
        )
        every { claimRepository.findAllById(any()) } returns listOf(
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED),
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION)
        )

        processor.processSku(today, "SKU")

        val daily = dailySnapshots.captured
        daily.size shouldBe 4
        daily.map { Triple(it.sku, it.warehouseCode, it.snapshotDate) }.toSet() shouldBe
            setOf("SKU" to "MAIN", "SKU" to "EAST")
                .flatMap { (sku, warehouse) ->
                    listOf(today, today.plusDays(1)).map { Triple(sku, warehouse, it) }
                }.toSet()
        daily.filter { it.sku == "SKU" && it.warehouseCode == "EAST" }
            .map { it.closingStock } shouldBe listOf(12, 12)
        val main = daily.filter { it.sku == "SKU" && it.warehouseCode == "MAIN" }
        main.map { it.closingStock } shouldBe listOf(4, 4)
        main.map { it.demandQuantity } shouldBe listOf(0, 2)
        main.map { it.shortageQuantity } shouldBe listOf(0, 2)
        daily.map { it.allocatedQuantity }.distinct() shouldBe listOf(0)
        daily.filterNot { it.sku == "SKU" && it.warehouseCode == "MAIN" }
            .map { it.shortageQuantity }.distinct() shouldBe listOf(0)
    }

    @Test
    fun `moves overdue arrivals deliveries and shortages to the projection start date`() {
        val supply = supply(quantity = 8, reserved = 3, arrivalDate = today.minusDays(2))
        every { supplyRepository.findAllBySku("SKU") } returns listOf(supply)
        every { inboundReservationRepository.findAllBySku("SKU") } returns listOf(
            inboundReservation(supply, "CONFIRMED", 3, today.minusDays(1))
        )
        every { backorderRepository.findAllBySku("SKU") } returns listOf(
            backorder("CONFIRMED", 2, today.minusDays(1))
        )
        every { claimRepository.findAllById(any()) } returns listOf(claim("CONFIRMED", OrderReservationStatus.CONFIRMED))

        processor.processSku(today.minusDays(5), "SKU")

        startDate.captured shouldBe today
        val daily = dailySnapshots.captured.single()
        daily.snapshotDate shouldBe today
        daily.openingStock shouldBe 0
        daily.inboundQuantity shouldBe 8
        daily.allocatedQuantity shouldBe 3
        daily.shortageQuantity shouldBe 2
        daily.demandQuantity shouldBe 5
        daily.closingStock shouldBe 5
        val lot = lotSnapshots.captured.single()
        lot.quantityReserved shouldBe 0
        lot.closingStock shouldBe 5
        lot.receivedDate shouldBe supply.expectedArrivalDate
    }

    @Test
    fun `ignores received supply instead of counting its quantity or arrival again`() {
        val stock = stock(4)
        val received = supply(quantity = 100, arrivalDate = today.plusDays(20)).apply {
            lotId = stock.lotId
            received = true
        }
        every { stockRepository.findAllBySku("SKU") } returns listOf(stock)
        every { supplyRepository.findAllBySku("SKU") } returns listOf(received)

        processor.processSku(today, "SKU")

        val daily = dailySnapshots.captured.single()
        daily.snapshotDate shouldBe today
        daily.closingStock shouldBe 4
        daily.inboundQuantity shouldBe 0
        lotSnapshots.captured.single().closingStock shouldBe 4
    }

    @ParameterizedTest
    @CsvSource("stock, missing", "stock, expired", "inbound, missing", "inbound, expired", "backorder, missing", "backorder, expired")
    fun `rejects active demand without a valid order claim`(source: String, lifecycle: String) {
        val stock = stock(5, 2)
        val supply = supply(5, 2)
        every { stockRepository.findAllBySku("SKU") } returns listOf(stock)
        every { supplyRepository.findAllBySku("SKU") } returns listOf(supply)
        if (lifecycle == "expired") {
            every { claimRepository.findAllById(any()) } returns listOf(claim("ORDER", OrderReservationStatus.EXPIRED))
        }
        when (source) {
            "stock" -> every { stockReservationRepository.findAllBySku("SKU") } returns
                listOf(stockReservation(stock, "ORDER", 2, today))
            "inbound" -> every { inboundReservationRepository.findAllBySku("SKU") } returns
                listOf(inboundReservation(supply, "ORDER", 2, today))
            "backorder" -> every { backorderRepository.findAllBySku("SKU") } returns listOf(backorder("ORDER", 2, today))
        }

        val exception = shouldThrow<IllegalStateException> { processor.processSku(today, "SKU") }

        exception.message shouldBe if (lifecycle == "expired") {
            "Expired order 'ORDER' still has active reservations or backorders."
        } else {
            "Active reservation or backorder 'ORDER' has no allocation order claim."
        }
        verify(exactly = 0) { snapshotRepository.replaceSkuFrom("SKU", any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["stock", "inbound"])
    fun `rejects reservations whose supply source is missing`(source: String) {
        val stockReservation = stockReservation(stock(5), "ORDER", 2, today)
        val inboundReservation = inboundReservation(supply(5), "ORDER", 2, today)
        if (source == "stock") {
            every { stockReservationRepository.findAllBySku("SKU") } returns listOf(stockReservation)
        } else {
            every { inboundReservationRepository.findAllBySku("SKU") } returns listOf(inboundReservation)
        }

        val exception = shouldThrow<IllegalStateException> { processor.processSku(today, "SKU") }

        exception.message shouldBe if (source == "stock") {
            "Stock reservation '${stockReservation.id}' references missing stock."
        } else {
            "Inbound reservation '${inboundReservation.id}' references missing supply."
        }
        verify(exactly = 0) { snapshotRepository.replaceSkuFrom("SKU", any(), any(), any()) }
    }

    @Test
    fun `rejects unreceived supply that conflicts with an existing stock lot`() {
        val stock = stock(5)
        every { stockRepository.findAllBySku("SKU") } returns listOf(stock)
        every { supplyRepository.findAllBySku("SKU") } returns listOf(supply(3).apply { lotId = stock.lotId })

        shouldThrow<IllegalArgumentException> { processor.processSku(today, "SKU") }.message shouldBe
            "Inbound shipment 'SHIPMENT' conflicts with an existing stock lot."
        verify(exactly = 0) { snapshotRepository.replaceSkuFrom("SKU", any(), any(), any()) }
    }

    @ParameterizedTest
    @CsvSource("5, 2, 3", "2, 3, 3")
    fun `rejects confirmed deliveries exceeding reserved or on-hand stock`(onHand: Int, reserved: Int, quantity: Int) {
        val stock = stock(onHand, reserved)
        every { stockRepository.findAllBySku("SKU") } returns listOf(stock)
        every { stockReservationRepository.findAllBySku("SKU") } returns listOf(
            stockReservation(stock, "CONFIRMED", quantity, today)
        )
        every { claimRepository.findAllById(any()) } returns listOf(claim("CONFIRMED", OrderReservationStatus.CONFIRMED))

        shouldThrow<IllegalArgumentException> { processor.processSku(today, "SKU") }.message shouldBe
            "Projected allocation exceeds stock for lot '${stock.lotId}' on $today."
        verify(exactly = 0) { snapshotRepository.replaceSkuFrom("SKU", any(), any(), any()) }
    }

    private fun stock(onHand: Int, reserved: Int = 0) = StockEntity(
        sku = "SKU", lotId = "STOCK-LOT", receivedDate = today.minusDays(1),
        quantityOnHand = onHand, quantityReserved = reserved
    )

    private fun supply(quantity: Int, reserved: Int = 0, arrivalDate: LocalDate = today) = InboundSupplyEntity(
        shipmentId = "SHIPMENT", shipmentLineId = "SHIPMENT-LINE", sku = "SKU", lotId = "INBOUND-LOT",
        expectedQuantity = quantity, quantityReserved = reserved, expectedArrivalDate = arrivalDate
    )

    private fun claim(orderId: String, status: OrderReservationStatus) = AllocationOrderClaimEntity(
        orderId = orderId, orderReservationStatus = status,
        reservationCreatedAt = clock.instant(), reservationExpiresAt = clock.instant().plusSeconds(1800)
    )

    private fun stockReservation(stock: StockEntity, orderId: String, quantity: Int, date: LocalDate) =
        StockReservationEntity(
            orderId = orderId, orderLineId = "$orderId-STOCK", stockId = stock.id,
            quantity = quantity, requiredDeliveryDate = date
        )

    private fun inboundReservation(supply: InboundSupplyEntity, orderId: String, quantity: Int, date: LocalDate) =
        InboundReservationEntity(
            orderId = orderId, orderLineId = "$orderId-INBOUND",
            shipmentId = supply.shipmentId, shipmentLineId = supply.shipmentLineId,
            quantity = quantity, requiredDeliveryDate = date
        )

    private fun backorder(orderId: String, quantity: Int, date: LocalDate) = OpenBackorderEntity(
        orderId = orderId, orderLineId = "$orderId-BACKORDER", sku = "SKU",
        originalQuantity = quantity, remainingQuantity = quantity, requiredDeliveryDate = date
    )
}
