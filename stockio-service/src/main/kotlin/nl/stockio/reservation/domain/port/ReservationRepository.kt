package nl.stockio.reservation.domain.port

import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity

interface ReservationRepository {
}

interface InboundReservationRepository {

    fun findAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByOrderIdIn(orderIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByShipmentLineIdIn(lineIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByOrderLineIdIn(lineIds: Collection<String>): List<InboundReservationEntity>
}

interface InboundSupplyRepository {

    fun lockAllByShipmentLineIdIn(lineIds: Collection<String>): List<InboundSupplyEntity>
    fun lockAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundSupplyEntity>
    fun lockAllUnreceived(): List<InboundSupplyEntity>
    fun lockAllUnreceivedBySku(sku: String): List<InboundSupplyEntity>
}

interface InboundShipmentRepository {

}
