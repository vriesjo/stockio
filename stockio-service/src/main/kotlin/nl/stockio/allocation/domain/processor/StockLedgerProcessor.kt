package nl.stockio.allocation.domain.processor

import java.time.Clock
import java.time.LocalDate
import nl.stockio.allocation.domain.api.data.ProcessDailyStockRequest
import nl.stockio.allocation.domain.api.data.StockSyncResult
import nl.stockio.allocation.domain.api.data.ReservationStatus
import nl.stockio.reservation.domain.service.ReservationCleanupService
import nl.stockio.allocation.infra.AllocationOrderClaimRepository
import nl.stockio.allocation.infra.InboundReservationRepository
import nl.stockio.allocation.infra.InboundSupplyRepository
import nl.stockio.allocation.infra.StockDailyLotSnapshotRepository
import nl.stockio.allocation.infra.StockDailySnapshotRepository
import nl.stockio.allocation.infra.StockEntity
import nl.stockio.allocation.infra.StockProjectionLockRepository
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationEntity
import nl.stockio.allocation.infra.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Applies today's projected stock balances to the live inventory ledger.
 *
 * Unlike allocation, which reserves supply, this processor records physical receipt and
 * confirmed delivery movements. It first acquires the exclusive stock-projection lock, expires
 * all overdue unconfirmed holds, and rebuilds the projection from the resulting live state.
 * Cleanup, projection rebuilding, and ledger updates run in one transaction.
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
class StockLedgerProcessor(
    private val stockProjectionLockRepository: StockProjectionLockRepository,
    private val stockRepository: StockRepository,
    private val stockDailyLotSnapshotRepository: StockDailyLotSnapshotRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val stockDailySnapshotRepository: StockDailySnapshotRepository,
    private val cleanupService: ReservationCleanupService,
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val claimRepository: AllocationOrderClaimRepository,
    private val clock: Clock
) {

    /**
     * Synchronizes live stock for the current date according to the injected [Clock].
     *
     * @param request identifies today's snapshot date; past and future dates are rejected.
     * @return the synchronized date, distinct SKU count, lot snapshot count, and update timestamp.
     * The lot count includes projected future lots, not only physically received lots.
     */
    @Transactional
    fun process(request: ProcessDailyStockRequest): StockSyncResult {
        stockProjectionLockRepository.acquire()
        require(request.snapshotDate == LocalDate.now(clock)) {
            "Only today's stock snapshot can be synchronized to live stock."
        }
        while (cleanupService.expireDueBatch() > 0) {
            // Drain overdue holds before generating the snapshot used by this synchronization.
        }
        stockProjectionProcessor.process(request.snapshotDate)
        val confirmedOrderIds = claimRepository.findAll()
            .filter { it.reservationStatus == ReservationStatus.CONFIRMED }
            .mapTo(mutableSetOf()) { it.orderId }
        val snapshots = stockDailySnapshotRepository.findAllBySnapshotDate(request.snapshotDate)
            .map {
                AggregateSnapshot(
                    sku = it.sku,
                    warehouseCode = it.warehouseCode,
                    closingStock = it.closingStock
                )
            }
        val lotSnapshots = stockDailyLotSnapshotRepository.findAllBySnapshotDate(request.snapshotDate)
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

        val arrivedIds = inboundSupplyRepository.findAll()
            .filter { !it.received && it.expectedArrivalDate <= request.snapshotDate }
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
            if (liveByKey[key] == null && !snapshot.receivedDate.isAfter(request.snapshotDate)) {
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
        val inboundReservations = if (receivedShipmentIds.isEmpty()) {
            emptyList()
        } else {
            inboundReservationRepository.lockAllByShipmentLineIdIn(receivedShipmentIds)
        }
        val synchronizedStockIds = liveByKey.values
            .filter { (it.sku to it.warehouseCode) in aggregateByScope }
            .map { it.id }
            .toSet()
        val dueStockReservations = stockReservationRepository
            .findAllByRequiredDeliveryDateLessThanEqual(request.snapshotDate)
            .filter { it.stockId in synchronizedStockIds && it.orderId in confirmedOrderIds }
        val dueReservationIds = dueStockReservations.map { it.id }.toSet()
        val stockReservations = stockReservationRepository.findAll()
            .filterNot { it.id in dueReservationIds }
            .associateByTo(mutableMapOf()) { it.orderLineId to it.stockId }
        val liveByInboundId = liveByKey.values.associateBy { Triple(it.sku, it.warehouseCode, it.lotId) }

        inboundReservations.filter {
            it.orderId !in confirmedOrderIds || it.requiredDeliveryDate > request.snapshotDate
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

        return StockSyncResult(
            snapshotDate = request.snapshotDate,
            skuCount = snapshots.map { it.sku }.distinct().size,
            lotCount = lotSnapshots.size,
            updatedAt = now
        )
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
