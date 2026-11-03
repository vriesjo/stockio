package nl.stockio.stock.domain.processor

import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import nl.stockio.stock.domain.port.StockLedgerRun
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.port.StockReceiptFollowup
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.port.StockSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Applies today's projected stock balances to the live inventory ledger.
 *
 * Records physical movements for one protected SKU, rebuilding its fresh forecast and
 * checkpointing the effects in the same transaction. Whole-order expiry is requested
 * outside this transaction rather than expanding the held SKU set.
 *
 * Lot-level closing balances and reserved quantities are copied to live stock after checking
 * that they agree with aggregate SKU/warehouse snapshots. Arrived shipment lines become stock
 * lots and are marked received so later synchronizations do not receive them again.
 *
 * Due reservations for confirmed orders are consumed. Reservations on arrived inbound supply
 * are converted to stock reservations when delivery is still in the future or the order remains
 * unconfirmed. Pending confirmation therefore preserves a hold, even after its delivery date,
 * rather than causing a physical delivery; its original order-level expiry still applies.
 *
 * Invalid snapshot dates or inconsistent snapshot and reservation data fail the transaction
 * instead of applying partial ledger changes.
 */
@Service
class StockLedgerSkuWorker(
    private val stockRepository: StockRepository,
    private val snapshotRepository: StockSnapshotRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val claimRepository: AllocationOrderClaimRepository,
    private val runRepository: StockLedgerRunRepository,
    private val clock: Clock,
    private val skuLockRepository: AllocationSkuLockRepository,
    private val lineRepository: DemandOrderLineRepository
) {

    @Transactional
    fun process(run: StockLedgerRun, owner: UUID, sku: String) {
        skuLockRepository.insertIfMissing(sku)
        checkNotNull(skuLockRepository.lockBySku(sku)) { "Could not lock SKU '$sku'." }
        if (!runRepository.lockWork(run.id, sku, owner)) return
        val today = LocalDate.now(clock)
        require(run.snapshotDate == today) {
            "Only today's stock snapshot can be synchronized to live stock."
        }
        val claims = claimRepository.findAllById(lineRepository.findDistinctOrderIdsBySku(sku))
        val overdue = claims.filter {
            it.orderReservationStatus == OrderReservationStatus.PENDING_CONFIRMATION && !it.canAllocate(clock.instant())
        }
        if (overdue.isNotEmpty()) throw LedgerCleanupRequiredException(overdue.map { it.orderId })
        val snapshotDate = run.snapshotDate
        stockProjectionProcessor.processSku(snapshotDate, sku)
        val confirmedOrderIds = claims
            .filter { it.orderReservationStatus == OrderReservationStatus.CONFIRMED }
            .mapTo(mutableSetOf()) { it.orderId }
        val snapshots = snapshotRepository.findDailyBySkuAndDate(sku, snapshotDate)
            .map {
                AggregateSnapshot(
                    sku = it.sku,
                    warehouseCode = it.warehouseCode,
                    closingStock = it.closingStock
                )
            }
        val lotSnapshots = snapshotRepository.findLotsBySkuAndDate(sku, snapshotDate)
            .map {
                LotSnapshot(
                    sku = it.sku,
                    warehouseCode = it.warehouseCode,
                    lotId = it.lotId,
                    receivedDate = it.receivedDate,
                    closingStock = it.closingStock,
                    quantityReserved = it.quantityReserved
                )
            }
        val aggregateByScope = snapshots.associateBy { it.sku to it.warehouseCode }
        require(lotSnapshots.all { (it.sku to it.warehouseCode) in aggregateByScope }) {
            "Lot-level snapshots contain a SKU/warehouse scope with no aggregate daily snapshot."
        }
        val lotTotals = lotSnapshots.groupBy { it.sku to it.warehouseCode }
            .mapValues { (_, lots) -> lots.sumOf { it.closingStock } }
        aggregateByScope.forEach { (scope, snapshot) ->
            require(snapshot.closingStock == (lotTotals[scope] ?: 0)) {
                "Lot-level closing stock does not match the daily snapshot for ${scope.first}/${scope.second}."
            }
        }

        val arrivedIds = inboundSupplyRepository.findAllBySku(sku)
            .filter { !it.received && it.expectedArrivalDate <= snapshotDate }
            .map { it.shipmentLineId }
        val arrivedSupplies = if (arrivedIds.isEmpty()) {
            emptyList()
        } else {
            inboundSupplyRepository.lockAllByShipmentLineIdIn(arrivedIds)
        }

        val skuCodes = snapshots.map { it.sku }.distinct()
        val liveStock = if (skuCodes.isEmpty()) emptyList() else stockRepository.lockAllBySkuIn(skuCodes)
        val liveByKey = liveStock.associateBy { StockKey(it.sku, it.warehouseCode, it.lotId) }.toMutableMap()
        val lotsByKey = lotSnapshots.associateBy { StockKey(it.sku, it.warehouseCode, it.lotId) }
        val now = clock.instant()

        liveStock.filter { (it.sku to it.warehouseCode) in aggregateByScope }.forEach { stock ->
            val snapshot = lotsByKey[StockKey(stock.sku, stock.warehouseCode, stock.lotId)]
            if (snapshot == null) {
                require(stock.quantityReserved == 0) {
                    "The daily snapshot omits reserved lot '${stock.lotId}'."
                }
                stock.quantityOnHand = 0
                stock.updatedAt = now
            } else {
                stock.quantityOnHand = snapshot.closingStock
                stock.quantityReserved = snapshot.quantityReserved
                stock.updatedAt = now
            }
        }

        lotSnapshots.forEach { snapshot ->
            val key = StockKey(snapshot.sku, snapshot.warehouseCode, snapshot.lotId)
            if (liveByKey[key] == null && !snapshot.receivedDate.isAfter(snapshotDate)) {
                liveByKey[key] = StockEntity(
                    sku = snapshot.sku,
                    warehouseCode = snapshot.warehouseCode,
                    lotId = snapshot.lotId,
                    receivedDate = snapshot.receivedDate,
                    quantityOnHand = snapshot.closingStock,
                    quantityReserved = snapshot.quantityReserved,
                    updatedAt = now
                )
            }
        }

        val receivedShipmentIds = arrivedSupplies.map { it.shipmentLineId }
        val receivedSkus = arrivedSupplies.map { it.sku }.toSet()
        val inboundReservations = if (receivedShipmentIds.isEmpty()) {
            emptyList()
        } else {
            inboundReservationRepository.lockAllByShipmentLineIdIn(receivedShipmentIds)
        }
        val synchronizedStockIds = liveByKey.values
            .filter { (it.sku to it.warehouseCode) in aggregateByScope }
            .map { it.id }
            .toSet()
        val allStockReservations = stockReservationRepository.findAllBySku(sku)
        val dueStockReservations = allStockReservations
            .filter { it.requiredDeliveryDate <= snapshotDate }
            .filter { it.stockId in synchronizedStockIds && it.orderId in confirmedOrderIds }
        val dueReservationIds = dueStockReservations.map { it.id }.toSet()
        val stockReservations = allStockReservations
            .filterNot { it.id in dueReservationIds }
            .associateByTo(mutableMapOf()) { it.orderLineId to it.stockId }
        val liveByInboundId = liveByKey.values.associateBy { Triple(it.sku, it.warehouseCode, it.lotId) }

        inboundReservations.filter {
            it.orderId !in confirmedOrderIds || it.requiredDeliveryDate > snapshotDate
        }.forEach { reservation ->
            val supply = arrivedSupplies.first { it.shipmentLineId == reservation.shipmentLineId }
            val stock = liveByInboundId[Triple(supply.sku, supply.warehouseCode, supply.lotId)]
                ?: throw IllegalStateException("Received shipment '${supply.shipmentId}' is missing from its stock snapshot.")
            val key = reservation.orderLineId to stock.id
            val stockReservation = stockReservations.getOrPut(key) {
                StockReservationEntity(
                    orderId = reservation.orderId,
                    orderLineId = reservation.orderLineId,
                    stockId = stock.id,
                    requiredDeliveryDate = reservation.requiredDeliveryDate
                )
            }
            stockReservation.quantity = Math.addExact(stockReservation.quantity, reservation.quantity)
        }

        stockReservationRepository.deleteAll(dueStockReservations)
        if (inboundReservations.isNotEmpty()) {
            inboundReservationRepository.deleteAll(inboundReservations)
        }
        arrivedSupplies.forEach { supply ->
            supply.received = true
            supply.quantityReserved = 0
        }

        stockRepository.saveAll(liveByKey.values)
        stockReservationRepository.saveAll(stockReservations.values)
        if (arrivedSupplies.isNotEmpty()) inboundSupplyRepository.saveAll(arrivedSupplies)

        require(LocalDate.now(clock) == snapshotDate) { "Ledger work crossed the business-date boundary; retry today's run." }
        runRepository.completeWork(
            run.id, sku, owner, snapshots.map { it.sku }.distinct().size, lotSnapshots.size, clock.instant()
        )
        if (receivedSkus.isNotEmpty()) {
            val id = UUID.nameUUIDFromBytes(
                "stock-ledger-backorder-fulfillment:$snapshotDate:${receivedShipmentIds.sorted().joinToString(",")}"
                    .toByteArray()
            )
            runRepository.recordReceipt(StockReceiptFollowup(id, sku, snapshotDate), run.id, clock.instant())
        }
    }

    private data class StockKey(val sku: String, val warehouseCode: String, val lotId: String)
    private data class AggregateSnapshot(val sku: String, val warehouseCode: String, val closingStock: Int)
    private data class LotSnapshot(
        val sku: String,
        val warehouseCode: String,
        val lotId: String,
        val receivedDate: LocalDate,
        val closingStock: Int,
        val quantityReserved: Int
    )

}

class LedgerCleanupRequiredException(val orderIds: List<String>) :
    IllegalStateException("Overdue orders require whole-order cleanup before ledger processing.")
