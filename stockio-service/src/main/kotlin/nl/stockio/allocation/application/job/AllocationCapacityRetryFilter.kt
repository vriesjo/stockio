package nl.stockio.allocation.domain.job

import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.filters.RetryFilter
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.jobrunr.scheduling.exceptions.JobNotFoundException

/**
 * A JobRunr retry filter that defers retries for allocation jobs that fail due to capacity or lock contention issues.
 *
 * This filter intercepts job state transitions and applies custom retry logic:
 * - If a job fails due to allocation capacity being exceeded or lock contention, it is deferred for a random interval
 *   between 5 and 10 seconds without consuming a business retry.
 * - For other failures, the job is retried according to an exponential backoff policy, up to a maximum number of retries.
 *
 * This helps mitigate synchronous allocation lock contention and prevents excessive retries for transient capacity issues.
 */
class AllocationCapacityRetryFilter : RetryFilter() {

    // Called when a job transitions to a new state. If the new state is a failed state, this method determines whether
    // to defer the job or apply a business retry based on the type of failure.
    override fun onStateElection(job: Job, newState: JobState) {
        if (newState !is FailedState || newState.mustNotRetry()) return
        if (isCapacityFailure(newState)) {
            // Defer the job for a random interval between 5 and 10 seconds without consuming a business retry.
            job.scheduleAt(
                Instant.now().plusMillis(ThreadLocalRandom.current().nextLong(5_000, 10_001)),
                "Allocation capacity unavailable; deferred without consuming a business retry"
            )
            return
        }
        if (newState.exceptionType == JobNotFoundException::class.java.name) return
        val failures = job.jobStates.filterIsInstance<FailedState>().count { !isCapacityFailure(it) }
        val retries = job.amountOfRetries ?: DEFAULT_NBR_OF_RETRIES
        if (failures <= retries) {
            // Apply exponential backoff for business retries.
            // The delay is the SEED to the power of the number of failures.
            job.scheduleAt(
                Instant.now().plusSeconds(DEFAULT_BACKOFF_POLICY_TIME_SEED.toDouble().pow(failures).toLong()),
                "Business retry $failures of $retries"
            )
        }
    }

    private fun isCapacityFailure(state: FailedState): Boolean =
        state.exceptionType == AllocationCapacityExceededException::class.java.name ||
            state.exceptionCauseType == AllocationCapacityExceededException::class.java.name ||
            state.exceptionType == AllocationLockContentionException::class.java.name ||
            state.exceptionCauseType == AllocationLockContentionException::class.java.name
}
