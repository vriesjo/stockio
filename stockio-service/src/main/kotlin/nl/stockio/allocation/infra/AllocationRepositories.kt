package nl.stockio.allocation.infra

import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AllocationRunRepository : JpaRepository<AllocationRunEntity, UUID> {
    fun findByRequestId(requestId: UUID): AllocationRunEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AllocationRunEntity r where r.id = :runId")
    fun lockById(@Param("runId") runId: UUID): AllocationRunEntity?

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
    fun insertIfMissing(
        @Param("id") id: UUID,
        @Param("requestId") requestId: UUID,
        @Param("requestHash") requestHash: String,
        @Param("runType") runType: String,
        @Param("asOfDate") asOfDate: LocalDate,
        @Param("createdAt") createdAt: Instant
    ): Int
}

interface AllocationRecordRepository : JpaRepository<AllocationRecordEntity, UUID> {
    fun findAllByRunIdOrderBySequenceNumber(runId: UUID): List<AllocationRecordEntity>
    fun findAllByOrderIdIn(orderIds: Collection<String>): List<AllocationRecordEntity>
}

interface AllocationSourceRepository : JpaRepository<AllocationSourceEntity, UUID> {
    fun findAllByAllocationRecordIdIn(recordIds: Collection<UUID>): List<AllocationSourceEntity>
}

interface AllocationSkuLockRepository : JpaRepository<AllocationSkuLockEntity, String> {
    @Modifying
    @Query(
        value = "INSERT INTO allocation_sku_lock (sku) VALUES (:sku) ON CONFLICT (sku) DO NOTHING",
        nativeQuery = true
    )
    fun insertIfMissing(@Param("sku") sku: String): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AllocationSkuLockEntity l where l.sku = :sku")
    fun lockBySku(@Param("sku") sku: String): AllocationSkuLockEntity?
}

interface AllocationSkuOperationRepository : JpaRepository<AllocationSkuOperationEntity, UUID> {
    fun findByRunIdAndSku(runId: UUID, sku: String): AllocationSkuOperationEntity?
    fun findAllByRunId(runId: UUID): List<AllocationSkuOperationEntity>
    fun deleteAllByRunId(runId: UUID)
}

interface AllocationOrderClaimRepository : JpaRepository<AllocationOrderClaimEntity, String> {
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
    fun lockExpiredBatch(
        @Param("now") now: Instant,
        @Param("batchSize") batchSize: Int
    ): List<AllocationOrderClaimEntity>

    @Query(
        "select min(c.reservationExpiresAt) from AllocationOrderClaimEntity c " +
            "where c.reservationStatus = nl.stockio.allocation.domain.api.data.ReservationStatus.PENDING_CONFIRMATION " +
            "and c.reservationExpiresAt <= :now"
    )
    fun findOldestOverdueDeadline(@Param("now") now: Instant): Instant?
}

interface DemandOrderLineRepository : JpaRepository<DemandOrderLineEntity, String> {
    fun findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(orderIds: Collection<String>): List<DemandOrderLineEntity>
}

interface OpenBackorderRepository : JpaRepository<OpenBackorderEntity, String> {
    fun findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc(): List<OpenBackorderEntity>

    @Query("select distinct b.sku from OpenBackorderEntity b")
    fun findDistinctSkus(): List<String>

    @Query("select count(distinct b.orderId) from OpenBackorderEntity b")
    fun countDistinctOrders(): Long

    fun deleteAllByOrderIdIn(orderIds: Collection<String>)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select b from OpenBackorderEntity b join DemandOrderLineEntity l on l.orderLineId = b.orderLineId " +
            "where b.sku = :sku order by b.priority, b.placedDate, b.createdAt, b.orderId, l.sequenceNumber"
    )
    fun lockAllBySkuOrderByPriorityFifo(@Param("sku") sku: String): List<OpenBackorderEntity>
}
