package nl.stockio.stock.domain.port

import java.time.LocalDate
import nl.stockio.allocation.domain.model.StockSyncResult
import nl.stockio.stock.infra.StockDailyLotSnapshot
import nl.stockio.stock.infra.StockDailySnapshot
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockLedgerSyncState
import nl.stockio.stock.infra.StockReservationEntity

interface StockRepository {

    fun lockAllBySkuIn(skus: Collection<String>): List<StockEntity>
}

interface StockProjectionLockRepository {
    fun acquireAllocationReadLock()
    fun acquire()
}

interface StockLedgerSyncStatusRepository {

    fun getSyncStatus(): StockLedgerSyncState
    fun recordSuccess(result: StockSyncResult)
}

interface StockReservationRepository {
    fun lockAllByOrderLineIdIn(lineIds: Collection<String>): List<StockReservationEntity>
    fun lockAllByOrderIdIn(orderIds: Collection<String>): List<StockReservationEntity>
    fun findAllByOrderIdIn(orderIds: Collection<String>): List<StockReservationEntity>
    fun findAllByRequiredDeliveryDateLessThanEqual(requiredDeliveryDate: LocalDate): List<StockReservationEntity>
}

interface StockSnapshotRepository {

    fun replaceFrom(
        startDate: LocalDate,
        dailySnapshots: List<StockDailySnapshot>,
        lotSnapshots: List<StockDailyLotSnapshot>
    )
    fun findDailyByDate(snapshotDate: LocalDate): List<StockDailySnapshot>
    fun findLotsByDate(snapshotDate: LocalDate): List<StockDailyLotSnapshot>
    fun findDailyBySkuAndWarehouse(sku: String, warehouseCode: String): List<StockDailySnapshot>
}

interface AllocationJobResultRepository {

}
