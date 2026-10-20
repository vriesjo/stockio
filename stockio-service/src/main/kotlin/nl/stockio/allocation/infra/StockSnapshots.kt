package nl.stockio.allocation.infra

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class StockDailySnapshot(
    val snapshotDate: LocalDate,
    val sku: String,
    val warehouseCode: String,
    val openingStock: Int,
    val inboundQuantity: Int,
    val demandQuantity: Int,
    val allocatedQuantity: Int,
    val closingStock: Int,
    val shortageQuantity: Int,
    val id: UUID = UUID.randomUUID(),
    val createdAt: Instant = Instant.now()
)

data class StockDailyLotSnapshot(
    val snapshotDate: LocalDate,
    val sku: String,
    val warehouseCode: String,
    val lotId: String,
    val receivedDate: LocalDate,
    val openingStock: Int,
    val inboundQuantity: Int,
    val allocatedQuantity: Int,
    val closingStock: Int,
    val quantityReserved: Int,
    val createdAt: Instant = Instant.now()
)
