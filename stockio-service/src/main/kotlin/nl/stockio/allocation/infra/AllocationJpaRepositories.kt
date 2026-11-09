package nl.stockio.allocation.infra

import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import nl.stockio.allocation.domain.port.AllocationDocumentLockRepository
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationRecordRepository
import nl.stockio.allocation.domain.port.AllocationRunRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.AllocationSkuOperationRepository
import nl.stockio.allocation.domain.port.AllocationSourceRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.stock.infra.StockEntity
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationRunJpaRepository : AllocationRunRepository, JpaRepository<AllocationRunEntity, UUID> {
    override fun findByRequestId(requestId: UUID): AllocationRunEntity?
    override fun findById(runId: UUID): Optional<AllocationRunEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AllocationRunEntity r where r.id = :runId")
    override fun lockById(@Param("runId") runId: UUID): AllocationRunEntity?

    @Modifying
    @Query(
        value = """
            INSERT INTO allocation_run
                (id, request_id, request_hash, run_type, as_of_date, open_backorder_count, completed, created_at)
            VALUES (:id, :requestId, :requestHash, :runType, :asOfDate, 0, false, :createdAt)
            ON CONFLICT (request_id) DO NOTHING
        """,
        nativeQuery = true
    )
    override fun insertIfMissing(
        @Param("id") id: UUID,
        @Param("requestId") requestId: UUID,
        @Param("requestHash") requestHash: String,
        @Param("runType") runType: String,
        @Param("asOfDate") asOfDate: LocalDate,
        @Param("createdAt") createdAt: Instant
    ): Int
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationRecordJpaRepository : AllocationRecordRepository, JpaRepository<AllocationRecordEntity, UUID> {
    override fun findAllByRunIdOrderBySequenceNumber(runId: UUID): List<AllocationRecordEntity>
    override fun findAllByOrderIdIn(orderIds: Collection<String>): List<AllocationRecordEntity>
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationSourceJpaRepository : AllocationSourceRepository, JpaRepository<AllocationSourceEntity, UUID> {
    override fun findAllByAllocationRecordIdIn(recordIds: Collection<UUID>): List<AllocationSourceEntity>
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationDocumentLockJpaRepository :
    AllocationDocumentLockRepository, JpaRepository<AllocationDocumentLockEntity, String> {
    @Modifying
    @Query(
        value = "INSERT INTO allocation_document_lock (lock_key) VALUES (:lockKey) ON CONFLICT (lock_key) DO NOTHING",
        nativeQuery = true
    )
    override fun insertIfMissing(@Param("lockKey") lockKey: String): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AllocationDocumentLockEntity l where l.lockKey = :lockKey")
    override fun lockByKey(@Param("lockKey") lockKey: String): AllocationDocumentLockEntity?
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationSkuLockJpaRepository : AllocationSkuLockRepository, JpaRepository<AllocationSkuLockEntity, String> {
    @Modifying
    @Query(
        value = "INSERT INTO allocation_sku_lock (sku) VALUES (:sku) ON CONFLICT (sku) DO NOTHING",
        nativeQuery = true
    )
    override fun insertIfMissing(@Param("sku") sku: String): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AllocationSkuLockEntity l where l.sku = :sku")
    override fun lockBySku(@Param("sku") sku: String): AllocationSkuLockEntity?
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationSkuOperationJpaRepository : AllocationSkuOperationRepository, JpaRepository<AllocationSkuOperationEntity, UUID> {
    override fun findByRunIdAndSku(runId: UUID, sku: String): AllocationSkuOperationEntity?
    override fun findAllByRunId(runId: UUID): List<AllocationSkuOperationEntity>
    override fun <S : AllocationSkuOperationEntity> save(entity: S): S
    override fun deleteAllByRunId(runId: UUID)
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface AllocationOrderClaimJpaRepository : AllocationOrderClaimRepository, JpaRepository<AllocationOrderClaimEntity, String> {
    override fun findAll(): List<AllocationOrderClaimEntity>
    @Query(
        value = "SELECT order_id FROM demand_order WHERE reservation_status = 'PENDING_CONFIRMATION' " +
            "AND reservation_expires_at <= :now ORDER BY reservation_expires_at, order_id LIMIT :batchSize",
        nativeQuery = true
    )
    override fun findExpiredOrderIds(@Param("now") now: Instant, @Param("batchSize") batchSize: Int): List<String>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AllocationOrderClaimEntity c where c.orderId = :orderId")
    override fun lockByOrderId(@Param("orderId") orderId: String): AllocationOrderClaimEntity?

    @Query(
        value = """
            SELECT * FROM demand_order
            WHERE reservation_status = 'PENDING_CONFIRMATION' AND reservation_expires_at <= :now
            ORDER BY reservation_expires_at, order_id
            LIMIT :batchSize
            FOR UPDATE
        """,
        nativeQuery = true
    )
    override fun lockExpiredBatch(
        @Param("now") now: Instant,
        @Param("batchSize") batchSize: Int
    ): List<AllocationOrderClaimEntity>

    @Query(
        "select min(c.reservationExpiresAt) from AllocationOrderClaimEntity c " +
            "where c.orderReservationStatus = nl.stockio.api.dto.OrderReservationStatus.PENDING_CONFIRMATION " +
            "and c.reservationExpiresAt <= :now"
    )
    override fun findOldestOverdueDeadline(@Param("now") now: Instant): Instant?
    @Query("select c from AllocationOrderClaimEntity c where c.orderId = :id")
    override fun findById(@Param("id") id: String): Optional<AllocationOrderClaimEntity>
    override fun findAllById(ids: Iterable<String>): List<AllocationOrderClaimEntity>
    override fun <S : AllocationOrderClaimEntity> saveAndFlush(entity: S): S
    override fun flush()
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface DemandOrderLineJpaRepository : DemandOrderLineRepository, JpaRepository<DemandOrderLineEntity, String> {
    @Query("select distinct l.orderId from DemandOrderLineEntity l where l.sku = :sku")
    override fun findDistinctOrderIdsBySku(@Param("sku") sku: String): List<String>
    override fun findAllById(ids: Iterable<String>): List<DemandOrderLineEntity>
    override fun findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(orderIds: Collection<String>): List<DemandOrderLineEntity>
}

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface OpenBackorderJpaRepository : OpenBackorderRepository, JpaRepository<OpenBackorderEntity, String> {
    override fun findAll(): List<OpenBackorderEntity>
    override fun findAllBySku(sku: String): List<OpenBackorderEntity>
    override fun <S : OpenBackorderEntity> save(entity: S): S
    override fun <S : OpenBackorderEntity> saveAll(entities: Iterable<S>): List<S>
    override fun delete(entity: OpenBackorderEntity)
    override fun findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc(): List<OpenBackorderEntity>

    @Query("select distinct b.sku from OpenBackorderEntity b")
    override fun findDistinctSkus(): List<String>

    @Query("select count(distinct b.orderId) from OpenBackorderEntity b")
    override fun countDistinctOrders(): Long

    override fun deleteAllByOrderIdIn(orderIds: Collection<String>)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select b from OpenBackorderEntity b join DemandOrderLineEntity l on l.orderLineId = b.orderLineId " +
            "where b.sku = :sku order by b.priority, b.placedDate, b.createdAt, b.orderId, l.sequenceNumber"
    )
    override fun lockAllBySkuOrderByPriorityFifo(@Param("sku") sku: String): List<OpenBackorderEntity>
}

data class SkuWarehouseAvailability(
    val sku: String,
    val warehouseCode: String,
    val quantityAvailable: Long
)

@ConditionalOnProperty(prefix = "stockio.allocation.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface StockAvailabilityJpaRepository : JpaRepository<StockEntity, UUID> {
    @Query(
        """
        select new nl.stockio.allocation.infra.SkuWarehouseAvailability(
            s.sku,
            s.warehouseCode,
            sum(s.quantityOnHand - s.quantityReserved)
        )
        from StockEntity s
        group by s.sku, s.warehouseCode
        order by s.sku, s.warehouseCode
        """
    )
    fun sumBySkuAndWarehouse(): List<SkuWarehouseAvailability>

    @Query(
        """
        select sum(s.quantityOnHand - s.quantityReserved)
        from StockEntity s
        where s.sku = :sku and s.warehouseCode = :warehouseCode
        """
    )
    fun sumAvailable(@Param("sku") sku: String, @Param("warehouseCode") warehouseCode: String): Long?
}
