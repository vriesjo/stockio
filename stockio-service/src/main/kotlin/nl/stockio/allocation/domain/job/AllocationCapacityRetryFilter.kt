package nl.stockio.allocation.domain.job

import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import nl.stockio.allocation.domain.service.AllocationCapacityExceededException
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.filters.RetryFilter
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.jobrunr.scheduling.exceptions.JobNotFoundException

class AllocationCapacityRetryFilter : RetryFilter() {
    override fun onStateElection(job: Job, newState: JobState) {
        if (newState !is FailedState || newState.mustNotRetry()) return
        if (isCapacityFailure(newState)) {
            job.scheduleAt(Instant.now().plusMillis(ThreadLocalRandom.current().nextLong(5_000, 10_001)),
                "Allocation capacity unavailable; deferred without consuming a business retry")
            return
        }
        if (newState.exceptionType == JobNotFoundException::class.java.name) return
        val failures = job.jobStates.filterIsInstance<FailedState>().count { !isCapacityFailure(it) }
        val retries = job.amountOfRetries ?: DEFAULT_NBR_OF_RETRIES
        if (failures <= retries) {
            job.scheduleAt(Instant.now().plusSeconds(DEFAULT_BACKOFF_POLICY_TIME_SEED.toDouble().pow(failures).toLong()),
                "Business retry $failures of $retries")
        }
    }

    private fun isCapacityFailure(state: FailedState): Boolean =
        state.exceptionType == AllocationCapacityExceededException::class.java.name ||
            state.exceptionCauseType == AllocationCapacityExceededException::class.java.name
}
