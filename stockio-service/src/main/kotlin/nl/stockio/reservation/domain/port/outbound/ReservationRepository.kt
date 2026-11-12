package nl.stockio.reservation.domain.port

import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundShipmentEntity
import nl.stockio.reservation.infra.InboundSupplyEntity

interface ReservationRepository {
}

interface InboundReservationRepository {

    fun findAll(): List<InboundReservationEntity>
    fun findAllBySku(sku: String): List<InboundReservationEntity>
    fun deleteAll(entities: Iterable<InboundReservationEntity>)
    fun <S : InboundReservationEntity> saveAll(entities: Iterable<S>): List<S>
    fun findAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByOrderIdIn(orderIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByShipmentLineIdIn(lineIds: Collection<String>): List<InboundReservationEntity>
    fun lockAllByOrderLineIdIn(lineIds: Collection<String>): List<InboundReservationEntity>
}

interface InboundSupplyRepository {

    fun findAll(): List<InboundSupplyEntity>
    fun findAllBySku(sku: String): List<InboundSupplyEntity>
    fun findAllByShipmentId(shipmentId: String): List<InboundSupplyEntity>
    fun findAllById(ids: Iterable<String>): List<InboundSupplyEntity>
    fun <S : InboundSupplyEntity> saveAll(entities: Iterable<S>): List<S>
    fun lockAllByShipmentLineIdIn(lineIds: Collection<String>): List<InboundSupplyEntity>
    fun lockAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundSupplyEntity>
    fun lockAllUnreceived(): List<InboundSupplyEntity>
    fun lockAllUnreceivedBySku(sku: String): List<InboundSupplyEntity>
}

interface InboundShipmentRepository {
    fun existsById(shipmentId: String): Boolean
    fun <S : InboundShipmentEntity> saveAndFlush(entity: S): S
}
