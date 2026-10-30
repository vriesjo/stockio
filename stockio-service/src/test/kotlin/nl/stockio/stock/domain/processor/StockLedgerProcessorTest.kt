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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.api.dto.ProcessDailyStockRequest
import nl.stockio.allocation.domain.job.BackorderAllocationJob
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.api.dto.StockSyncResult
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.reservation.domain.service.ReservationCleanupService
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.domain.port.StockLedgerSyncStatusRepository
import nl.stockio.stock.domain.port.StockProjectionLockRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.port.StockSnapshotRepository
import nl.stockio.stock.infra.StockDailyLotSnapshot
import nl.stockio.stock.infra.StockDailySnapshot
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils

class StockLedgerProcessorTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private val clock = mockk<Clock>()
    private val lockRepository = mockk<StockProjectionLockRepository>()
    private val stockRepository = mockk<StockRepository>()
    private val snapshotRepository = mockk<StockSnapshotRepository>()
    private val stockReservationRepository = mockk<StockReservationRepository>()
    private val supplyRepository = mockk<InboundSupplyRepository>()
    private val inboundReservationRepository = mockk<InboundReservationRepository>()
    private val cleanupService = mockk<ReservationCleanupService>()
    private val projectionProcessor = mockk<StockProjectionProcessor>()
    private val claimRepository = mockk<AllocationOrderClaimRepository>()
    private val syncStatusRepository = mockk<StockLedgerSyncStatusRepository>()
    private val scheduler = mockk<JobScheduler>()
    private val backorderJob = mockk<BackorderAllocationJob>()
    private val savedStocks = slot<Iterable<StockEntity>>()
    private val savedReservations = slot<Iterable<StockReservationEntity>>()
    private val processor = StockLedgerProcessor(
        lockRepository,
        stockRepository,
        snapshotRepository,
        stockReservationRepository,
        supplyRepository,
        inboundReservationRepository,
        cleanupService,
        projectionProcessor,
        claimRepository,
        syncStatusRepository,
        clock,
        scheduler,
        backorderJob
    )

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
        every { clock.zone } returns ZoneOffset.UTC
        every { clock.instant() } returns now
        every { lockRepository.acquire() } just Runs
        every { cleanupService.expireDueBatch() } returns 0
        every { projectionProcessor.process(any()) } just Runs
        every { claimRepository.findAll() } returns emptyList()
        every { snapshotRepository.findDailyByDate(any()) } returns emptyList()
        every { snapshotRepository.findLotsByDate(any()) } returns emptyList()
        every { supplyRepository.findAll() } returns emptyList()
        every { stockRepository.lockAllBySkuIn(any()) } returns emptyList()
        every { stockReservationRepository.findAllByRequiredDeliveryDateLessThanEqual(any()) } returns emptyList()
        every { stockReservationRepository.findAll() } returns emptyList()
        every { stockReservationRepository.deleteAll(any()) } just Runs
        every { stockRepository.saveAll(capture(savedStocks)) } answers {
            firstArg<Iterable<StockEntity>>().toList()
        }
        every { stockReservationRepository.saveAll(capture(savedReservations)) } answers {
            firstArg<Iterable<StockReservationEntity>>().toList()
        }
        every { supplyRepository.saveAll(any<Iterable<InboundSupplyEntity>>()) } answers {
            firstArg<Iterable<InboundSupplyEntity>>().toList()
        }
        every { syncStatusRepository.recordSuccess(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clearSynchronization()
    }

    @ParameterizedTest
    @ValueSource(strings = ["scheduled", "omitted", "explicit"])
    fun `drains expiry before projection and records a valid empty synchronization`(entryPoint: String) {
        every { cleanupService.expireDueBatch() } returnsMany listOf(2, 1, 0)

        val result = when (entryPoint) {
            "scheduled" -> processor.processToday()
            "omitted" -> processor.process(ProcessDailyStockRequest())
            else -> processor.process(ProcessDailyStockRequest(today))
        }

        result shouldBe StockSyncResult(today, 0, 0, now)
        savedStocks.captured.toList() shouldBe emptyList()
        savedReservations.captured.toList() shouldBe emptyList()
        verifyOrder {
            lockRepository.acquire()
            cleanupService.expireDueBatch()
            cleanupService.expireDueBatch()
            cleanupService.expireDueBatch()
            projectionProcessor.process(today)
            snapshotRepository.findDailyByDate(today)
            snapshotRepository.findLotsByDate(today)
            syncStatusRepository.recordSuccess(result)
        }
        verify(exactly = 3) { cleanupService.expireDueBatch() }
        TransactionSynchronizationUtils.triggerAfterCommit()
        verify(exactly = 0) { scheduler.enqueue(any<UUID>(), any<JobLambda>()) }
        verify(exactly = 0) { supplyRepository.lockAllByShipmentLineIdIn(any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `resolves today's date after acquiring the projection lock`(scheduled: Boolean) {
        val afterMidnight = Instant.parse("2026-10-27T00:00:01Z")
        every { clock.instant() } returns Instant.parse("2026-10-26T23:59:59Z")
        every { lockRepository.acquire() } answers {
            every { clock.instant() } returns afterMidnight
        }

        val result = if (scheduled) processor.processToday() else processor.process(ProcessDailyStockRequest())

        result.snapshotDate shouldBe today.plusDays(1)
        result.updatedAt shouldBe afterMidnight
        verify(exactly = 1) { projectionProcessor.process(today.plusDays(1)) }
        verify(exactly = 1) { snapshotRepository.findLotsByDate(today.plusDays(1)) }
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 1])
    fun `rejects non-current dates before cleanup or ledger writes`(offset: Long) {
        shouldThrow<IllegalArgumentException> {
            processor.process(ProcessDailyStockRequest(today.plusDays(offset)))
        }.message shouldBe "Only today's stock snapshot can be synchronized to live stock."

        verify(exactly = 1) { lockRepository.acquire() }
        verify(exactly = 0) { cleanupService.expireDueBatch() }
        verify(exactly = 0) { projectionProcessor.process(any()) }
        verifyNoLedgerWrites()
    }

    @Test
    fun `updates synchronized lots without delivering pending future or unrelated reservations`() {
        val main = stock("MAIN-LOT", 10, 5)
        val east = stock("EAST-LOT", 4).apply { warehouseCode = "EAST" }
        val omitted = stock("OMITTED-LOT", 3)
        val outsideScope = stock("WEST-LOT", 7, 1).apply { warehouseCode = "WEST" }
        val due = reservation(main, "CONFIRMED", 3, today)
        val pending = reservation(main, "PENDING", 1, today.minusDays(1))
        val future = reservation(main, "CONFIRMED", 1, today.plusDays(1))
        val unrelated = reservation(outsideScope, "CONFIRMED", 1, today)
        every { stockRepository.lockAllBySkuIn(any()) } returns listOf(main, east, omitted, outsideScope)
        every { snapshotRepository.findDailyByDate(today) } returns listOf(
            daily(7), daily(4, warehouse = "EAST"), daily(0, sku = "FUTURE")
        )
        every { snapshotRepository.findLotsByDate(today) } returns listOf(
            lot(main.lotId, 7, 2),
            lot(east.lotId, 4, warehouse = "EAST"),
            lot("FUTURE-LOT", 0, sku = "FUTURE", receivedDate = today.plusDays(1))
        )
        every { claimRepository.findAll() } returns listOf(
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED),
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION)
        )
        every { stockReservationRepository.findAllByRequiredDeliveryDateLessThanEqual(today) } returns
            listOf(due, pending, unrelated)
        every { stockReservationRepository.findAll() } returns listOf(due, pending, future, unrelated)

        val result = processor.processToday()

        main.quantityOnHand shouldBe 7
        main.quantityReserved shouldBe 2
        main.updatedAt shouldBe now
        omitted.quantityOnHand shouldBe 0
        omitted.updatedAt shouldBe now
        outsideScope.quantityOnHand shouldBe 7
        outsideScope.quantityReserved shouldBe 1
        savedStocks.captured.toSet() shouldBe setOf(main, east, omitted, outsideScope)
        savedReservations.captured.toSet() shouldBe setOf(pending, future, unrelated)
        verify(exactly = 1) { stockReservationRepository.deleteAll(listOf(due)) }
        result.skuCount shouldBe 2
        result.lotCount shouldBe 3
        verify(exactly = 1) { syncStatusRepository.recordSuccess(result) }
    }

    @Test
    fun `receives arrived supply converts retained holds and schedules scoped fulfillment only after commit`() {
        val first = supply("LINE-B", "SKU", 10, 6)
        val second = supply("LINE-A", "OTHER", 4)
        val futureSupply = supply("LINE-FUTURE", "FUTURE", 20).apply {
            expectedArrivalDate = today.plusDays(1)
        }
        val receivedSupply = supply("LINE-RECEIVED", "OLD", 30).apply { received = true }
        prepareReceipts(listOf(first, second), listOf(
            lot(first.lotId, 8, 4),
            lot(second.lotId, 4, sku = "OTHER")
        ))
        every { supplyRepository.findAll() } returns listOf(first, second, futureSupply, receivedSupply)
        val pending = inboundReservation(first, "PENDING", 1, today.minusDays(1))
        val future = inboundReservation(first, "CONFIRMED", 3, today.plusDays(1))
        val due = inboundReservation(first, "CONFIRMED", 2, today)
        val inboundHolds = listOf(pending, future, due)
        every { inboundReservationRepository.lockAllByShipmentLineIdIn(listOf("LINE-B", "LINE-A")) } returns inboundHolds
        every { inboundReservationRepository.deleteAll(inboundHolds) } just Runs
        every { claimRepository.findAll() } returns listOf(
            claim("PENDING", OrderReservationStatus.PENDING_CONFIRMATION),
            claim("CONFIRMED", OrderReservationStatus.CONFIRMED)
        )
        val jobId = slot<UUID>()
        val job = slot<JobLambda>()
        val command = slot<BackorderAllocationRequest>()
        every { scheduler.enqueue(capture(jobId), capture(job)) } returns mockk()
        every { backorderJob.execute(capture(command)) } just Runs

        processor.processToday()

        first.received shouldBe true
        first.quantityReserved shouldBe 0
        second.received shouldBe true
        futureSupply.received shouldBe false
        val stocks = savedStocks.captured.associateBy { it.lotId }
        stocks.keys shouldBe setOf(first.lotId, second.lotId)
        stocks.getValue(first.lotId).quantityOnHand shouldBe 8
        stocks.getValue(first.lotId).quantityReserved shouldBe 4
        stocks.getValue(first.lotId).receivedDate shouldBe first.expectedArrivalDate
        stocks.getValue(second.lotId).quantityOnHand shouldBe 4
        val retained = savedReservations.captured.associateBy { it.orderLineId }
        retained.keys shouldBe setOf(pending.orderLineId, future.orderLineId)
        retained.getValue(pending.orderLineId).quantity shouldBe 1
        retained.getValue(pending.orderLineId).requiredDeliveryDate shouldBe pending.requiredDeliveryDate
        retained.getValue(future.orderLineId).quantity shouldBe 3
        retained.values.map { it.stockId }.toSet() shouldBe setOf(stocks.getValue(first.lotId).id)
        verify(exactly = 1) { inboundReservationRepository.deleteAll(inboundHolds) }
        verify(exactly = 1) { supplyRepository.saveAll(listOf(first, second)) }
        verify(exactly = 0) { scheduler.enqueue(any<UUID>(), any<JobLambda>()) }

        TransactionSynchronizationUtils.triggerAfterCommit()
        verify(exactly = 1) { scheduler.enqueue(any<UUID>(), any<JobLambda>()) }
        verify(exactly = 0) { backorderJob.execute(any()) }
        job.captured.run()

        command.captured.asOfDate shouldBe today
        command.captured.trigger shouldBe BackorderAllocationTrigger.STOCK_LEDGER_RECEIPT
        command.captured.skuScope shouldBe setOf("SKU", "OTHER")
        command.captured.inboundShipments shouldBe emptyList()
        command.captured.requestId shouldBe UUID.nameUUIDFromBytes(
            "stock-ledger-backorder-fulfillment:$today:LINE-A,LINE-B".toByteArray()
        )
        jobId.captured shouldBe UUID.nameUUIDFromBytes(
            "stock-ledger-backorder-job:${command.captured.requestId}".toByteArray()
        )
    }

    @Test
    fun `does not enqueue receipt fulfillment when transaction completion reports rollback`() {
        val supply = supply("LINE", "SKU", 5)
        prepareReceipts(listOf(supply), listOf(lot(supply.lotId, 5)))

        processor.processToday()
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)

        verify(exactly = 0) { scheduler.enqueue(any<UUID>(), any<JobLambda>()) }
        verify(exactly = 0) { backorderJob.execute(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing aggregate", "mismatched total"])
    fun `rejects inconsistent lot and aggregate snapshots before writing ledger state`(inconsistency: String) {
        every { snapshotRepository.findLotsByDate(today) } returns listOf(lot("LOT", 5))
        if (inconsistency == "mismatched total") {
            every { snapshotRepository.findDailyByDate(today) } returns listOf(daily(6))
        }

        val exception = shouldThrow<IllegalArgumentException> { processor.processToday() }

        exception.message shouldBe if (inconsistency == "missing aggregate") {
            "Lot-level snapshots contain a SKU/warehouse scope with no aggregate daily snapshot."
        } else {
            "Lot-level closing stock does not match the daily snapshot for SKU/MAIN."
        }
        verifyNoLedgerWrites()
    }

    @Test
    fun `rejects a forecast that omits a reserved live lot`() {
        every { stockRepository.lockAllBySkuIn(listOf("SKU")) } returns listOf(stock("MISSING", 5, 2))
        every { snapshotRepository.findDailyByDate(today) } returns listOf(daily(0))

        shouldThrow<IllegalArgumentException> { processor.processToday() }.message shouldBe
            "The daily snapshot omits reserved lot 'MISSING'."
        verifyNoLedgerWrites()
    }

    @Test
    fun `rejects retained inbound holds without a receiving stock snapshot`() {
        val supply = supply("LINE", "SKU", 5, 2)
        val hold = inboundReservation(supply, "PENDING", 2, today)
        prepareReceipts(listOf(supply), emptyList())
        every { inboundReservationRepository.lockAllByShipmentLineIdIn(listOf("LINE")) } returns listOf(hold)

        shouldThrow<IllegalStateException> { processor.processToday() }.message shouldBe
            "Received shipment 'SHIPMENT-LINE' is missing from its stock snapshot."
        supply.received shouldBe false
        supply.quantityReserved shouldBe 2
        verifyNoLedgerWrites()
    }

    @Test
    fun `propagates projection failures without recording success or scheduling work`() {
        every { projectionProcessor.process(today) } throws IllegalStateException("Projection failed")

        shouldThrow<IllegalStateException> { processor.processToday() }.message shouldBe "Projection failed"

        verify(exactly = 0) { snapshotRepository.findDailyByDate(any()) }
        verifyNoLedgerWrites()
    }

    @Test
    fun `surfaces enqueue failure after receipt processing has already recorded success`() {
        val supply = supply("LINE", "SKU", 5)
        prepareReceipts(listOf(supply), listOf(lot(supply.lotId, 5)))
        every { scheduler.enqueue(any<UUID>(), any<JobLambda>()) } throws IllegalStateException("Scheduler unavailable")
        val result = processor.processToday()

        shouldThrow<IllegalStateException> {
            TransactionSynchronizationUtils.triggerAfterCommit()
        }.message shouldBe "Scheduler unavailable"

        verifyOrder {
            syncStatusRepository.recordSuccess(result)
            scheduler.enqueue(any<UUID>(), any<JobLambda>())
        }
        verify(exactly = 0) { backorderJob.execute(any()) }
    }

    private fun verifyNoLedgerWrites() {
        verify(exactly = 0) { stockRepository.saveAll(any<Iterable<StockEntity>>()) }
        verify(exactly = 0) { stockReservationRepository.saveAll(any<Iterable<StockReservationEntity>>()) }
        verify(exactly = 0) { stockReservationRepository.deleteAll(any()) }
        verify(exactly = 0) { supplyRepository.saveAll(any<Iterable<InboundSupplyEntity>>()) }
        verify(exactly = 0) { inboundReservationRepository.deleteAll(any()) }
        verify(exactly = 0) { syncStatusRepository.recordSuccess(any()) }
        verify(exactly = 0) { scheduler.enqueue(any<UUID>(), any<JobLambda>()) }
        TransactionSynchronizationManager.getSynchronizations() shouldBe emptyList()
    }

    private fun prepareReceipts(supplies: List<InboundSupplyEntity>, lots: List<StockDailyLotSnapshot>) {
        every { supplyRepository.findAll() } returns supplies
        every { supplyRepository.lockAllByShipmentLineIdIn(supplies.map { it.shipmentLineId }) } returns supplies
        every { inboundReservationRepository.lockAllByShipmentLineIdIn(any()) } returns emptyList()
        every { snapshotRepository.findLotsByDate(today) } returns lots
        every { snapshotRepository.findDailyByDate(today) } returns lots
            .groupBy { it.sku to it.warehouseCode }
            .map { (scope, values) -> daily(values.sumOf { it.closingStock }, scope.first, scope.second) }
    }

    private fun daily(closing: Int, sku: String = "SKU", warehouse: String = "MAIN") = StockDailySnapshot(
        snapshotDate = today, sku = sku, warehouseCode = warehouse, openingStock = closing,
        inboundQuantity = 0, demandQuantity = 0, allocatedQuantity = 0, closingStock = closing, shortageQuantity = 0
    )

    private fun lot(
        id: String,
        closing: Int,
        reserved: Int = 0,
        sku: String = "SKU",
        warehouse: String = "MAIN",
        receivedDate: LocalDate = today
    ) = StockDailyLotSnapshot(
        snapshotDate = today, sku = sku, warehouseCode = warehouse, lotId = id, receivedDate = receivedDate,
        openingStock = closing, inboundQuantity = 0, allocatedQuantity = 0,
        closingStock = closing, quantityReserved = reserved
    )

    private fun stock(lotId: String, onHand: Int, reserved: Int = 0) = StockEntity(
        sku = "SKU", lotId = lotId, receivedDate = today.minusDays(1),
        quantityOnHand = onHand, quantityReserved = reserved, updatedAt = now.minusSeconds(60)
    )

    private fun supply(lineId: String, sku: String, quantity: Int, reserved: Int = 0) = InboundSupplyEntity(
        shipmentId = "SHIPMENT-$lineId", shipmentLineId = lineId, sku = sku, lotId = "LOT-$lineId",
        expectedQuantity = quantity, quantityReserved = reserved, expectedArrivalDate = today
    )

    private fun claim(orderId: String, status: OrderReservationStatus) =
        AllocationOrderClaimEntity(orderId = orderId, orderReservationStatus = status)

    private fun reservation(stock: StockEntity, orderId: String, quantity: Int, date: LocalDate) = StockReservationEntity(
        orderId = orderId, orderLineId = "$orderId-$date", stockId = stock.id,
        quantity = quantity, requiredDeliveryDate = date
    )

    private fun inboundReservation(supply: InboundSupplyEntity, orderId: String, quantity: Int, date: LocalDate) =
        InboundReservationEntity(
            orderId = orderId, orderLineId = "$orderId-$date",
            shipmentId = supply.shipmentId, shipmentLineId = supply.shipmentLineId,
            quantity = quantity, requiredDeliveryDate = date
        )
}
