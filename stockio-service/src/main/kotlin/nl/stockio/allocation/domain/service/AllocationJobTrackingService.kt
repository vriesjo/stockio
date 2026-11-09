package nl.stockio.allocation.domain.job

import java.time.Instant
import java.util.Optional
import java.util.UUID
import nl.stockio.stock.domain.port.AllocationJobTrackingRepository
import nl.stockio.stock.infra.AllocationJobTrackingEntity
import org.springframework.stereotype.Service

/**
 * Service for tracking the state of allocation jobs.
 *
 * This service provides methods to register new allocation jobs, update their state,
 * and retrieve job information. It interacts with the underlying repository to persist
 * and query job tracking data.
 */
@Service
class AllocationJobTrackingService(
    private val repository: AllocationJobTrackingRepository
) {
    /**
     * Records that a worker has taken [jobId]. Inserts `PROCESSING` when no row exists.
     * Leaves a terminal row unchanged so a redelivery cannot reopen it.
     */
    fun beginProcessing(jobId: UUID, requestId: UUID) {
        val existing = repository.findById(jobId).orElse(null)
        if (existing == null) {
            repository.save(
                AllocationJobTrackingEntity(
                    jobId = jobId,
                    requestId = requestId,
                    status = AllocationJobTrackingState.PROCESSING
                )
            )
            return
        }
        if (existing.status == AllocationJobTrackingState.SUCCEEDED ||
            existing.status == AllocationJobTrackingState.FAILED
        ) {
            return
        }
        transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.PROCESSING)
    }

    fun findById(jobId: UUID): Optional<AllocationJobTrackingEntity> = repository.findById(jobId)

    fun markProcessing(jobId: UUID) {
        transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.PROCESSING)
    }

    fun markSucceeded(jobId: UUID) {
        transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.SUCCEEDED)
    }

    fun markFailed(jobId: UUID) {
        transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.FAILED)
    }

    private fun transition(
        jobId: UUID,
        from: Set<AllocationJobTrackingState>,
        to: AllocationJobTrackingState
    ) {
        repository.transition(jobId, from, to, Instant.now())
    }
}

enum class AllocationJobTrackingState {
    PROCESSING,
    SUCCEEDED,
    FAILED
}
