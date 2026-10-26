package nl.stockio.reservation.infra

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundShipmentRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@Transactional(propagation = Propagation.MANDATORY)
@ConditionalOnProperty(prefix = "stockio.reservation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface InboundShipmentJpaRepository : InboundShipmentRepository, JpaRepository<InboundShipmentEntity, String> {
    override fun existsById(shipmentId: String): Boolean
    override fun <S : InboundShipmentEntity> saveAndFlush(entity: S): S
}

@ConditionalOnProperty(prefix = "stockio.reservation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface InboundSupplyJpaRepository : InboundSupplyRepository, JpaRepository<InboundSupplyEntity, String> {
    override fun findAll(): List<InboundSupplyEntity>
    override fun findAllByShipmentId(shipmentId: String): List<InboundSupplyEntity>
    override fun findAllById(ids: Iterable<String>): List<InboundSupplyEntity>
    override fun <S : InboundSupplyEntity> saveAll(entities: Iterable<S>): List<S>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.shipmentLineId in :lineIds order by s.shipmentLineId")
    override fun lockAllByShipmentLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.shipmentId in :shipmentIds")
    override fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.received = false order by s.expectedArrivalDate, s.shipmentId, s.shipmentLineId")
    override fun lockAllUnreceived(): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select s from InboundSupplyEntity s where s.sku = :sku and s.received = false " +
            "order by s.expectedArrivalDate, s.shipmentId, s.shipmentLineId"
    )
    override fun lockAllUnreceivedBySku(@Param("sku") sku: String): List<InboundSupplyEntity>
}

@ConditionalOnProperty(prefix = "stockio.reservation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface InboundReservationJpaRepository : InboundReservationRepository, JpaRepository<InboundReservationEntity, UUID> {
    override fun findAll(): List<InboundReservationEntity>
    override fun deleteAll(entities: Iterable<InboundReservationEntity>)
    override fun <S : InboundReservationEntity> saveAll(entities: Iterable<S>): List<S>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.orderLineId in :lineIds")
    override fun lockAllByOrderLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.shipmentLineId in :lineIds")
    override fun lockAllByShipmentLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.orderId in :orderIds")
    override fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.shipmentId in :shipmentIds")
    override fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundReservationEntity>

    override fun findAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
}
