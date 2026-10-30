package nl.stockio.allocation.domain.worker

import java.time.LocalDate
import kotlin.math.min
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.allocation.domain.model.InboundReservationChange
import nl.stockio.allocation.domain.model.InboundSupplyState

class InboundAllocationPolicy {

    fun allocate(
        sku: String,
        latestArrivalDate: LocalDate,
        requested: Int,
        shipmentsBySku: Map<String, List<InboundSupplyState>>,
        remainingByShipment: MutableMap<String, Int>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        orderLineId: String,
        deliveryDate: LocalDate,
        reservationsByOrderAndShipment: MutableMap<Pair<String, String>, InboundReservationChange>
    ): Int {
        var remaining = requested
        for (shipment in shipmentsBySku[sku].orEmpty()) {
            if (remaining == 0) break
            if (shipment.expectedArrivalDate > latestArrivalDate) continue
            val available = remainingByShipment.getValue(shipment.shipmentLineId)
            val allocated = min(remaining, available)
            if (allocated > 0) {
                remainingByShipment[shipment.shipmentLineId] = available - allocated
                shipment.quantityReserved += allocated
                val key = orderLineId to shipment.shipmentLineId
                val reservation = reservationsByOrderAndShipment.getOrPut(key) {
                    InboundReservationChange(
                        id = null,
                        orderId = orderId,
                        orderLineId = orderLineId,
                        shipmentId = shipment.shipmentId,
                        shipmentLineId = shipment.shipmentLineId,
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
                    sourceId = shipment.shipmentLineId,
                    shipmentId = shipment.shipmentId,
                    shipmentLineId = shipment.shipmentLineId,
                    quantity = allocated,
                    sourceDate = shipment.expectedArrivalDate,
                    warehouseLocation = shipment.warehouseCode
                )
            }
        }
        return requested - remaining
    }
}
