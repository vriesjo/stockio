package nl.stockio.allocation.domain.service

import nl.stockio.allocation.domain.api.bean.InboundShipment
import nl.stockio.allocation.infra.InboundSupplyEntity
import nl.stockio.allocation.infra.InboundSupplyRepository
import org.springframework.stereotype.Service

@Service
class InboundSupplyService(
    private val inboundSupplyRepository: InboundSupplyRepository
) {

    fun registerInboundSupply(shipments: List<InboundShipment>) {
        if (shipments.isEmpty()) return
        val ids = shipments.map { it.shipmentId }
        val supplies = if (ids.isEmpty()) {
            mutableMapOf()
        } else {
            inboundSupplyRepository.lockAllByShipmentIdIn(ids).associateByTo(mutableMapOf()) { it.shipmentId }
        }
        shipments.forEach { shipment ->
            val existing = supplies[shipment.shipmentId]
            if (existing == null) {
                supplies[shipment.shipmentId] = InboundSupplyEntity(
                    shipmentId = shipment.shipmentId,
                    sku = shipment.sku,
                    warehouseCode = shipment.warehouseCode,
                    expectedQuantity = shipment.expectedQuantity,
                    expectedArrivalDate = shipment.expectedArrivalDate
                )
            } else {
                require(
                    existing.sku == shipment.sku &&
                        existing.warehouseCode == shipment.warehouseCode &&
                        existing.expectedArrivalDate == shipment.expectedArrivalDate
                ) {
                    "Inbound shipment ID '${shipment.shipmentId}' has already been used with different SKU, warehouse, or arrival date."
                }
                require(shipment.expectedQuantity >= existing.quantityReserved) {
                    "Inbound shipment '${shipment.shipmentId}' cannot be reduced below its reserved quantity."
                }
                existing.expectedQuantity = shipment.expectedQuantity
            }
        }
        inboundSupplyRepository.saveAll(supplies.values)
    }
}
