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
    fun registerSubmission(jobId: UUID, requestId: UUID) {
        repository.save(
            AllocationJobTrackingEntity(
                jobId = jobId,
                requestId = requestId,
                status = AllocationJobTrackingState.PUBLISHING
            )
        )
    }

    fun findById(jobId: UUID): Optional<AllocationJobTrackingEntity> = repository.findById(jobId)

    fun markSubmissionUnknown(jobId: UUID) {
        transition(jobId, setOf(AllocationJobTrackingState.PUBLISHING), AllocationJobTrackingState.SUBMISSION_UNKNOWN)
    }

    fun markEnqueued(jobId: UUID) {
        transition(jobId, setOf(AllocationJobTrackingState.PUBLISHING), AllocationJobTrackingState.ENQUEUED)
    }

    fun markProcessing(jobId: UUID) {
        transition(
            jobId,
            setOf(
                AllocationJobTrackingState.PUBLISHING,
                AllocationJobTrackingState.SUBMISSION_UNKNOWN,
                AllocationJobTrackingState.ENQUEUED,
                AllocationJobTrackingState.PROCESSING
            ),
            AllocationJobTrackingState.PROCESSING
        )
    }

    fun markSucceeded(jobId: UUID) {
        transition(
            jobId,
            setOf(
                AllocationJobTrackingState.PUBLISHING,
                AllocationJobTrackingState.SUBMISSION_UNKNOWN,
                AllocationJobTrackingState.ENQUEUED,
                AllocationJobTrackingState.PROCESSING
            ),
            AllocationJobTrackingState.SUCCEEDED
        )
    }

    fun markFailed(jobId: UUID) {
        transition(
            jobId,
            setOf(
                AllocationJobTrackingState.PUBLISHING,
                AllocationJobTrackingState.SUBMISSION_UNKNOWN,
                AllocationJobTrackingState.ENQUEUED,
                AllocationJobTrackingState.PROCESSING
            ),
            AllocationJobTrackingState.FAILED
        )
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
    PUBLISHING,
    SUBMISSION_UNKNOWN,
    ENQUEUED,
    PROCESSING,
    SUCCEEDED,
    FAILED
}
