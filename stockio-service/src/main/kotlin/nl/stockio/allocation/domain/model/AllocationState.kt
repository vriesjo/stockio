package nl.stockio.allocation.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRecord

data class AllocationOrder(
    val orderId: String,
    val customer: String,
    val sku: String,
    val quantity: Int,
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate
)

data class InventoryLotState(
    val stockId: UUID,
    val sku: String,
    val lotId: String,
    val warehouseCode: String,
    val receivedDate: LocalDate,
    val quantityOnHand: Int,
    var quantityReserved: Int
)

data class InboundSupplyState(
    val shipmentId: String,
    val sku: String,
    val warehouseCode: String,
    val expectedQuantity: Int,
    val expectedArrivalDate: LocalDate,
    var quantityReserved: Int
)

data class StockReservationChange(
    val id: UUID?,
    val orderId: String,
    val stockId: UUID,
    var quantity: Int,
    var requiredDeliveryDate: LocalDate,
    val createdAt: Instant?
)

data class InboundReservationChange(
    val id: UUID?,
    val orderId: String,
    val shipmentId: String,
    var quantity: Int,
    var requiredDeliveryDate: LocalDate,
    val createdAt: Instant?
)

data class BackorderRegistration(
    val orderId: String,
    val customer: String,
    val sku: String,
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate,
    val originalQuantity: Int,
    val quantity: Int
)

data class AllocationCalculationResult(
    val allocations: List<AllocationRecord>,
    val inventoryLots: List<InventoryLotState>,
    val inboundSupplies: List<InboundSupplyState>,
    val stockReservations: List<StockReservationChange>,
    val inboundReservations: List<InboundReservationChange>,
    val backordersToRegister: List<BackorderRegistration>
)
