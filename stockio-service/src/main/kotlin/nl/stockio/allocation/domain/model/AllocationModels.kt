package nl.stockio.allocation.domain.model

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRunType

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

data class StockSyncResult(
    val snapshotDate: LocalDate,
    val skuCount: Int,
    val lotCount: Int,
    val updatedAt: Instant
)

data class AllocationCompletionResult(
    val jobId: UUID,
    val allocationRun: AllocationRunResult,
)

data class AllocationJobAccepted(
    val jobId: UUID,
    val requestId: UUID,
)

data class AllocationJobStatus(
    val jobId: UUID,
    val status: String,
    val result: AllocationCompletionResult? = null
)

data class AllocationSource(
    val sourceType: AllocationSourceType,
    val sourceId: String,
    val quantity: Int,
    val sourceDate: LocalDate,
    val warehouseLocation: String? = null,
    val shipmentId: String? = if (sourceType == AllocationSourceType.INBOUND) sourceId else null,
    val shipmentLineId: String? = if (sourceType == AllocationSourceType.INBOUND) sourceId else null
)

enum class AllocationProcessingOutcome {
    PROCESSED,
    SKIPPED_ORDER_EXPIRED
}

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
    val status: AllocationStatus?,
    val sources: List<AllocationSource>,
    val orderLineId: String = orderId,
    val processingOutcome: AllocationProcessingOutcome = AllocationProcessingOutcome.PROCESSED
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
