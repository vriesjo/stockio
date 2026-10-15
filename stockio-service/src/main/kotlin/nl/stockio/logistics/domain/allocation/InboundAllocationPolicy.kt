package nl.stockio.logistics.domain.allocation

import nl.stockio.logistics.domain.AllocationSource
import nl.stockio.logistics.domain.AllocationSourceType
import java.time.LocalDate
import kotlin.math.min

class InboundAllocationPolicy {

    fun allocate(
        sku: String,
        latestArrivalDate: LocalDate,
        requested: Int,
        shipmentsBySku: Map<String, List<InboundSupplyState>>,
        remainingByShipment: MutableMap<String, Int>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        deliveryDate: LocalDate,
        reservationsByOrderAndShipment: MutableMap<Pair<String, String>, InboundReservationChange>
    ): Int {
        var remaining = requested
        for (shipment in shipmentsBySku[sku].orEmpty()) {
            if (remaining == 0) break
            if (shipment.expectedArrivalDate > latestArrivalDate) continue
            val available = remainingByShipment.getValue(shipment.shipmentId)
            val allocated = min(remaining, available)
            if (allocated > 0) {
                remainingByShipment[shipment.shipmentId] = available - allocated
                shipment.quantityReserved += allocated
                val key = orderId to shipment.shipmentId
                val reservation = reservationsByOrderAndShipment.getOrPut(key) {
                    InboundReservationChange(
                        id = null,
                        orderId = orderId,
                        shipmentId = shipment.shipmentId,
                        quantity = 0,
                        requiredDeliveryDate = deliveryDate,
                        createdAt = null
                    )
                }
                reservation.quantity += allocated
                reservation.requiredDeliveryDate = deliveryDate
                remaining -= allocated
                sources += AllocationSource(
                    sourceType = AllocationSourceType.INBOUND,
                    sourceId = shipment.shipmentId,
                    quantity = allocated,
                    sourceDate = shipment.expectedArrivalDate,
                    warehouseLocation = shipment.warehouseCode
                )
            }
        }
        return requested - remaining
    }
}
