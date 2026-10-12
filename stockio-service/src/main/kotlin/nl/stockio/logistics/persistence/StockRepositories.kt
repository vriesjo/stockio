package nl.stockio.logistics.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StockRepository : JpaRepository<StockEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.sku in :skus order by s.receivedDate, s.lotId")
    fun lockAllBySkuIn(@Param("skus") skus: Collection<String>): List<StockEntity>
}

interface StockDailySnapshotRepository : JpaRepository<StockDailySnapshotEntity, UUID> {
    fun findAllBySnapshotDate(snapshotDate: java.time.LocalDate): List<StockDailySnapshotEntity>

    fun findAllBySkuAndWarehouseCodeOrderBySnapshotDate(
        sku: String,
        warehouseCode: String
    ): List<StockDailySnapshotEntity>

    fun findBySnapshotDateAndSkuAndWarehouseCode(
        snapshotDate: java.time.LocalDate,
        sku: String,
        warehouseCode: String
    ): StockDailySnapshotEntity?

    @Modifying(flushAutomatically = true)
    @Query("delete from StockDailySnapshotEntity s where s.snapshotDate >= :snapshotDate")
    fun deleteAllBySnapshotDateGreaterThanEqual(
        @Param("snapshotDate") snapshotDate: java.time.LocalDate
    ): Int
}

interface StockDailyLotSnapshotRepository :
    JpaRepository<StockDailyLotSnapshotEntity, StockDailyLotSnapshotId> {
    fun findAllBySnapshotDate(snapshotDate: java.time.LocalDate): List<StockDailyLotSnapshotEntity>

    @Modifying(flushAutomatically = true)
    @Query("delete from StockDailyLotSnapshotEntity s where s.snapshotDate >= :snapshotDate")
    fun deleteAllBySnapshotDateGreaterThanEqual(
        @Param("snapshotDate") snapshotDate: java.time.LocalDate
    ): Int
}

interface StockReservationRepository : JpaRepository<StockReservationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.orderId in :orderIds")
    fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<StockReservationEntity>

    fun findAllByOrderIdIn(orderIds: Collection<String>): List<StockReservationEntity>

    fun findAllByRequiredDeliveryDateLessThanEqual(requiredDeliveryDate: java.time.LocalDate): List<StockReservationEntity>
}

interface InboundSupplyRepository : JpaRepository<InboundSupplyEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.shipmentId in :shipmentIds")
    fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundSupplyEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InboundSupplyEntity s where s.received = false order by s.expectedArrivalDate, s.shipmentId")
    fun lockAllUnreceived(): List<InboundSupplyEntity>
}

interface InboundReservationRepository : JpaRepository<InboundReservationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.orderId in :orderIds")
    fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<InboundReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InboundReservationEntity r where r.shipmentId in :shipmentIds")
    fun lockAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<String>): List<InboundReservationEntity>

    fun findAllByShipmentIdIn(shipmentIds: Collection<String>): List<InboundReservationEntity>
}
