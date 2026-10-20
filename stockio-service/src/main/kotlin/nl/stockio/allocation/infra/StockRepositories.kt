package nl.stockio.allocation.infra

import jakarta.persistence.LockModeType
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Transactional(propagation = Propagation.MANDATORY)
interface StockProjectionLockRepository : JpaRepository<StockProjectionLockEntity, Int> {
    fun acquireAllocationReadLock() {
        checkNotNull(lockForAllocation()) { "The stock projection lock row is missing." }
    }

    fun acquire() {
        checkNotNull(lockForProjection()) { "The stock projection lock row is missing." }
    }

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select l from StockProjectionLockEntity l where l.id = 1")
    fun lockForAllocation(): StockProjectionLockEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockProjectionLockEntity l where l.id = 1")
    fun lockForProjection(): StockProjectionLockEntity?
}

interface StockRepository : JpaRepository<StockEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.sku in :skus order by s.receivedDate, s.lotId")
    fun lockAllBySkuIn(@Param("skus") skus: Collection<String>): List<StockEntity>
}

interface AllocationJobResultRepository : JpaRepository<AllocationJobResultEntity, UUID>

interface StockReservationRepository : JpaRepository<StockReservationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.orderLineId in :lineIds")
    fun lockAllByOrderLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<StockReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.orderId in :orderIds")
    fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<StockReservationEntity>

    fun findAllByOrderIdIn(orderIds: Collection<String>): List<StockReservationEntity>

    fun findAllByRequiredDeliveryDateLessThanEqual(requiredDeliveryDate: LocalDate): List<StockReservationEntity>
}

interface InboundShipmentRepository : JpaRepository<InboundShipmentEntity, String>

interface InboundSupplyRepository : JpaRepository<InboundSupplyEntity, String> {
    fun findAllByShipmentId(shipmentId: String): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.shipmentLineId in :lineIds order by s.shipmentLineId")
    fun lockAllByShipmentLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.shipmentId in :shipmentIds")
    fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.received = false order by s.expectedArrivalDate, s.shipmentId, s.shipmentLineId")
    fun lockAllUnreceived(): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select s from InboundSupplyEntity s where s.sku = :sku and s.received = false " +
            "order by s.expectedArrivalDate, s.shipmentId, s.shipmentLineId"
    )
    fun lockAllUnreceivedBySku(@Param("sku") sku: String): List<InboundSupplyEntity>
}

interface InboundReservationRepository : JpaRepository<InboundReservationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.orderLineId in :lineIds")
    fun lockAllByOrderLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.shipmentLineId in :lineIds")
    fun lockAllByShipmentLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.orderId in :orderIds")
    fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.shipmentId in :shipmentIds")
    fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundReservationEntity>

    fun findAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
}
