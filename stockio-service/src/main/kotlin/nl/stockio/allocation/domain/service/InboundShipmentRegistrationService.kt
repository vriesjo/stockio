package nl.stockio.allocation.domain.service

import nl.stockio.api.dto.InboundShipment
import nl.stockio.reservation.domain.port.InboundShipmentRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.reservation.infra.InboundShipmentEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Service responsible for registering inbound shipments and their associated supplies.
 *
 * This service ensures that inbound shipments and their lines are correctly registered in the system,
 * validating that shipment lines do not change their registered membership and that they do not conflict
 * with existing shipment lines. It operates within a mandatory transaction context to ensure data integrity.
 */
@Service
class InboundShipmentRegistrationService(
    private val shipmentRepository: InboundShipmentRepository,
    private val supplyRepository: InboundSupplyRepository
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun registerInboundSupply(shipments: List<InboundShipment>) {
        shipments.sortedBy { it.shipmentId }.forEach { shipment ->
            val exists = shipmentRepository.existsById(shipment.shipmentId)
            val existing = supplyRepository.lockAllByShipmentIdIn(listOf(shipment.shipmentId))
                .associateBy { it.shipmentLineId }
            if (exists) {
                require(existing.keys == shipment.lines.map { it.shipmentLineId }.toSet()) {
                    "Shipment '${shipment.shipmentId}' cannot change its registered line membership."
                }
            } else {
                require(supplyRepository.findAllById(shipment.lines.map { it.shipmentLineId }).isEmpty()) {
                    "Shipment line IDs already belong to another shipment."
                }
                shipmentRepository.saveAndFlush(InboundShipmentEntity(shipment.shipmentId))
            }
            val supplies = shipment.lines.map { line ->
                val supply = existing[line.shipmentLineId]
                if (supply == null) {
                    InboundSupplyEntity(
                        shipmentId = shipment.shipmentId,
                        shipmentLineId = line.shipmentLineId,
                        sku = line.sku,
                        warehouseCode = line.warehouseCode,
                        expectedQuantity = line.expectedQuantity,
                        expectedArrivalDate = line.expectedArrivalDate
                    )
                } else {
                    require(supply.sku == line.sku && supply.warehouseCode == line.warehouseCode &&
                        supply.expectedArrivalDate == line.expectedArrivalDate) {
                        "Shipment line '${line.shipmentLineId}' has different SKU, warehouse, or arrival date."
                    }
                    require(line.expectedQuantity >= supply.quantityReserved &&
                        (!supply.received || line.expectedQuantity == supply.expectedQuantity)) {
                        "Shipment line '${line.shipmentLineId}' cannot change received quantity or fall below reservations."
                    }
                    supply.expectedQuantity = line.expectedQuantity
                    supply
                }
            }
            supplyRepository.saveAll(supplies)
        }
    }
}
