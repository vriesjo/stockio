package nl.stockio.allocation.domain.allocator

import java.time.LocalDate
import java.util.UUID
import kotlin.math.min
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.allocation.domain.model.InventoryLotState
import nl.stockio.allocation.domain.model.StockReservationChange

/**
 * Allocates requested quantities from available inventory lots for each SKU.
 *
 * The allocator updates the in-memory lot and reservation state and records the
 * inventory lots used as allocation sources.
 */
class InventoryLotAllocator {

    /**
     * Reserves as much of [requested] as possible from [sku]'s inventory lots.
     *
     * Updates each consumed lot's reserved quantity, accumulates reservation
     * changes by order line and stock item, and appends the consumed lots to
     * [sources]. Returns the quantity allocated; this may be less than [requested]
     * when inventory is insufficient.
     */
    fun allocate(
        sku: String,
        requested: Int,
        lotsBySku: Map<String, List<InventoryLotState>>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        orderLineId: String,
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
                val key = orderLineId to lot.stockId
                val reservation = reservationsByOrderAndStock.getOrPut(key) {
                    StockReservationChange(
                        id = null,
                        orderId = orderId,
                        orderLineId = orderLineId,
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
