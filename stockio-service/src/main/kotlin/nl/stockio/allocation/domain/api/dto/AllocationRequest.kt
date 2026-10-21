package nl.stockio.allocation.domain.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AllocationRequest(
    val requestId: UUID,
    val asOfDate: LocalDate,
    val runType: AllocationRunType,
    @field:Valid
    val demandOrders: List<DemandOrder> = emptyList(),
    @field:Valid
    val inboundShipments: List<InboundShipment> = emptyList()
)

data class DemandOrder(
    @field:NotBlank
    @field:Size(max = 100)
    val orderId: String,
    @field:NotBlank
    val customer: String,
    @field:Positive
    val priority: Int,
    val placedDate: LocalDate,
    @field:Valid
    @field:NotEmpty
    val lines: List<DemandOrderLine>
)

data class DemandOrderLine(
    @field:NotBlank
    @field:Size(max = 100)
    val orderLineId: String,
    @field:NotBlank
    @field:Size(max = 64)
    val sku: String,
    @field:Positive
    val requestedQuantity: Int,
    val requiredDeliveryDate: LocalDate
)

data class ProcessDailyStockRequest(
    val snapshotDate: LocalDate? = null
)

data class AllocationRequestEvent(
    val jobId: UUID,
    val request: AllocationRequest
)

data class InboundShipment(
    @field:NotBlank
    @field:Size(max = 100)
    val shipmentId: String,
    @field:Valid
    @field:NotEmpty
    val lines: List<InboundShipmentLine>
)

data class InboundShipmentLine(
    @field:NotBlank
    @field:Size(max = 100)
    val shipmentLineId: String,
    @field:NotBlank
    @field:Size(max = 64)
    val sku: String,
    @field:Positive
    val expectedQuantity: Int,
    val expectedArrivalDate: LocalDate,
    @field:NotBlank
    @field:Size(max = 50)
    val warehouseCode: String = "MAIN"
)

enum class AllocationRunType {
    NEW_DEMAND,
    BACKORDER_FULFILLMENT
}

data class ExcelReportRequest(
    val runId: UUID,
    @field:NotBlank
    val fileName: String
)
