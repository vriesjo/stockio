package nl.stockio.allocation.domain.port

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationRecordEntity
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.AllocationSkuLockEntity
import nl.stockio.allocation.infra.AllocationSourceEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.allocation.infra.OpenBackorderEntity

interface AllocationOrderClaimRepository {

    fun lockExpiredBatch(now: Instant, batchSize: Int): List<AllocationOrderClaimEntity>
    fun findOldestOverdueDeadline(now: Instant): Instant?
}

interface AllocationRecordRepository {

    fun findAllByRunIdOrderBySequenceNumber(runId: UUID): List<AllocationRecordEntity>
    fun findAllByOrderIdIn(orderIds: Collection<String>): List<AllocationRecordEntity>
}

interface AllocationRunRepository {

    fun lockById(runId: UUID): AllocationRunEntity?
    fun insertIfMissing(
        id: UUID,
        requestId: UUID,
        requestHash: String,
        runType: String,
        asOfDate: LocalDate,
        createdAt: Instant
    ): Int
}

interface AllocationSkuLockRepository {

    fun insertIfMissing(sku: String): Int
    fun lockBySku(sku: String): AllocationSkuLockEntity?
}

interface AllocationSkuOperationRepository

interface AllocationSourceRepository {

    fun findAllByAllocationRecordIdIn(recordIds: Collection<UUID>): List<AllocationSourceEntity>
}

interface DemandOrderLineRepository {

    fun findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(orderIds: Collection<String>): List<DemandOrderLineEntity>
}

interface OpenBackorderRepository {

    fun findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc(): List<OpenBackorderEntity>
    fun findDistinctSkus(): List<String>
    fun countDistinctOrders(): Long
    fun deleteAllByOrderIdIn(orderIds: Collection<String>)
    fun lockAllBySkuOrderByPriorityFifo(sku: String): List<OpenBackorderEntity>
}
