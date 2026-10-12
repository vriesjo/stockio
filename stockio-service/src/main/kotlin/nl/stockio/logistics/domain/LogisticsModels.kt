package nl.stockio.logistics.domain

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AllocationRequest(
    val requestId: UUID,
    val asOfDate: LocalDate,
    @field:NotEmpty
    @field:Valid
    val demandOrders: List<DemandOrder>,
    @field:Valid
    val inboundShipments: List<InboundShipment> = emptyList()
)

data class DemandOrder(
    @field:NotBlank
    val orderId: String,
    @field:NotBlank
    val customer: String,
    @field:NotBlank
    val sku: String,
    @field:Positive
    val requestedQuantity: Int,
    @field:Positive
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate
)

data class OpeningStockLot(
    @field:NotBlank
    val lotId: String,
    @field:NotBlank
    val sku: String,
    @field:PositiveOrZero
    val quantityOnHand: Int,
    val receivedDate: LocalDate,
    @field:NotBlank
    val warehouseCode: String = "MAIN"
)

enum class StockTransactionType {
    INBOUND,
    DEMAND
}

data class StockTransaction(
    @field:NotBlank
    val transactionId: String,
    @field:NotBlank
    val sku: String,
    val type: StockTransactionType,
    @field:Positive
    val quantity: Int,
    val date: LocalDate,
    @field:NotBlank
    val warehouseCode: String = "MAIN",
    val lotId: String? = null,
    val orderId: String? = null
)

data class ProcessDailyStockRequest(
    val snapshotDate: LocalDate = LocalDate.now()
)

data class StockSyncResult(
    val snapshotDate: LocalDate,
    val skuCount: Int,
    val lotCount: Int,
    val updatedAt: Instant
)

data class AllocationAndSyncResult(
    val allocationRun: AllocationRunResult,
    val stockSync: StockSyncResult
)

data class InboundShipment(
    @field:NotBlank
    val shipmentId: String,
    @field:NotBlank
    val sku: String,
    @field:Positive
    val expectedQuantity: Int,
    val expectedArrivalDate: LocalDate,
    @field:NotBlank
    val warehouseCode: String = "MAIN"
)

enum class AllocationRunType {
    NEW_DEMAND,
    BACKORDER_FULFILLMENT
}

enum class AllocationStatus {
    ALLOCATED,
    PARTIALLY_BACKORDERED,
    BACKORDERED,
    PARTIALLY_FULFILLED,
    FULFILLED
}

enum class AllocationSourceType {
    INVENTORY,
    INBOUND
}

data class AllocationSource(
    val sourceType: AllocationSourceType,
    val sourceId: String,
    val quantity: Int,
    val sourceDate: LocalDate,
    val warehouseLocation: String? = null
)

data class AllocationRecord(
    val orderId: String,
    val customer: String,
    val sku: String,
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate,
    val requestedQuantity: Int,
    val allocatedFromInventory: Int,
    val allocatedFromInbound: Int,
    val backorderedQuantity: Int,
    val status: AllocationStatus,
    val sources: List<AllocationSource>
)

data class AllocationRunResult(
    val runId: UUID,
    val requestId: UUID,
    val runType: AllocationRunType,
    val asOfDate: LocalDate,
    val createdAt: Instant,
    val openBackorderCount: Int,
    val allocations: List<AllocationRecord>
)

data class BackorderFulfillmentRequest(
    val requestId: UUID,
    val asOfDate: LocalDate,
    @field:Valid
    val inboundShipments: List<InboundShipment> = emptyList()
)

data class ExcelReportRequest(
    val runId: UUID,
    @field:NotBlank
    val fileName: String
)
