package nl.stockio.logistics.domain.allocation

import nl.stockio.logistics.domain.AllocationSource
import nl.stockio.logistics.domain.AllocationSourceType
import java.time.LocalDate
import java.util.UUID
import kotlin.math.min

class InventoryAllocationPolicy {

    fun allocate(
        sku: String,
        requested: Int,
        lotsBySku: Map<String, List<InventoryLotState>>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        reservationsByOrderAndStock: MutableMap<Pair<String, UUID>, StockReservationChange>,
        deliveryDate: LocalDate
    ): Int {
        var remaining = requested
        for (lot in lotsBySku[sku].orEmpty()) {
            if (remaining == 0) break
            val available = lot.quantityOnHand - lot.quantityReserved
            val allocated = min(remaining, available)
            if (allocated > 0) {
                lot.quantityReserved += allocated
                val key = orderId to lot.stockId
                val reservation = reservationsByOrderAndStock.getOrPut(key) {
                    StockReservationChange(
                        id = null,
                        orderId = orderId,
                        stockId = lot.stockId,
                        quantity = 0,
                        requiredDeliveryDate = deliveryDate,
                        createdAt = null
                    )
                }
                reservation.quantity += allocated
                reservation.requiredDeliveryDate = deliveryDate
                remaining -= allocated
                sources += AllocationSource(
                    sourceType = AllocationSourceType.INVENTORY,
                    sourceId = lot.lotId,
                    quantity = allocated,
                    sourceDate = lot.receivedDate,
                    warehouseLocation = lot.warehouseCode
                )
            }
        }
        return requested - remaining
    }
}
