package nl.stockio.logistics.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AllocationRunRepository : JpaRepository<AllocationRunEntity, UUID> {
    fun findByRequestId(requestId: UUID): AllocationRunEntity?
}

interface AllocationRecordRepository : JpaRepository<AllocationRecordEntity, UUID> {
    fun findAllByRunIdOrderBySequenceNumber(runId: UUID): List<AllocationRecordEntity>
    fun findAllByOrderIdIn(orderIds: Collection<String>): List<AllocationRecordEntity>
}

interface AllocationSourceRepository : JpaRepository<AllocationSourceEntity, UUID> {
    fun findAllByAllocationRecordIdIn(recordIds: Collection<UUID>): List<AllocationSourceEntity>
}

interface OpenBackorderRepository : JpaRepository<OpenBackorderEntity, String> {
    fun findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc(): List<OpenBackorderEntity>
}
