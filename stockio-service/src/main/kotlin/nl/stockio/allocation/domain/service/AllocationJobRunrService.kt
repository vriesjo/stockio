package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.allocation.domain.job.AllocationJobTrackingService
import nl.stockio.allocation.domain.job.AllocationJobTrackingState
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.server.BackgroundJobServer
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.springframework.stereotype.Service

/**
 * Reads allocation job status and completion results across JobRunr and tracking storage.
 *
 * JobRunr remains the primary source for jobs it knows about. The tracking and result
 * stores preserve status when a submitted allocation is not present in JobRunr storage.
 */
@Service
class AllocationJobRunrService(
    private val storageProvider: StorageProvider,
    private val resultRepository: AllocationJobResultRepository,
    private val jobTracking: AllocationJobTrackingService,
    private val objectMapper: ObjectMapper,
    private val backgroundJobServers: List<BackgroundJobServer>
) {

    /**
     * Returns the current status and, when available, the saved result for [jobId].
     *
     * Falls back to durable allocation tracking and completion records when the job
     * is no longer present in JobRunr storage.
     *
     * @throws JobNotFoundException if the job is absent from all applicable stores.
     * @throws IllegalStateException if persisted status and completion data are inconsistent.
     */
    fun getStatusById(jobId: UUID): AllocationJobStatus {
        val tracked = jobTracking.findById(jobId).orElse(null)
        val job = try {
            storageProvider.getJobById(jobId)
        } catch (exception: JobNotFoundException) {
            if (tracked == null) throw exception
            null
        }
        if (job != null) {
            val result = if (job.state == StateName.SUCCEEDED) {
                readCompletion(jobId)
            } else {
                null
            }
            return AllocationJobStatus(jobId = jobId, status = job.state.name, result = result)
        }

        val completion = resultRepository.findById(jobId).orElse(null)
        if (completion != null) {
            return AllocationJobStatus(
                jobId = jobId,
                status = AllocationJobTrackingState.SUCCEEDED.name,
                result = objectMapper.readValue(completion.resultJson, AllocationCompletionResult::class.java)
            )
        }
        val trackedJob = tracked ?: throw IllegalStateException("Job '$jobId' is missing from both status stores.")
        if (trackedJob.status == AllocationJobTrackingState.SUCCEEDED) {
            throw IllegalStateException("Succeeded allocation job '$jobId' has no saved completion result.")
        }
        return AllocationJobStatus(jobId = jobId, status = trackedJob.status.name)
    }

    private fun readCompletion(jobId: UUID): AllocationCompletionResult {
        val saved = resultRepository.findById(jobId).orElseThrow {
            IllegalStateException("Succeeded allocation job '$jobId' has no saved completion result.")
        }
        return objectMapper.readValue(saved.resultJson, AllocationCompletionResult::class.java)
    }

    /**
     * Stops the configured JobRunr background servers, deletes stored jobs in the
     * supported lifecycle states, and restarts the servers.
     *
     * This clears JobRunr job records only; allocation tracking and saved completion
     * results are stored separately and are not cleared here.
     */
    fun flushAllJobs() {

        backgroundJobServers.forEach { server ->
            server.stop()
        }

        Thread.sleep(2000) // Wait for the server to stop and release locks

        val statesToFlush = listOf(
            StateName.PROCESSING,
            StateName.FAILED,
            StateName.ENQUEUED,
            StateName.SCHEDULED,
            StateName.SUCCEEDED,
            StateName.AWAITING,
        )

        for (state in statesToFlush) {
            var deletedCount = 0
            do {
                // Fetch jobs in batches of 500 (storage provider max batch limits apply)
                val jobs = storageProvider.getJobList(state, AmountRequest("updatedAt:ASC", 500))
                for (job in jobs) {
                    try {
                        // Use permanent delete or JobScheduler delete depending on state
                        storageProvider.deletePermanently(job.id)
                    } catch (e: Exception) {
                        // Handle active locks if processing
                    }
                }
                deletedCount = jobs.size
            } while (deletedCount > 0)
        }
        backgroundJobServers.forEach { server ->
            server.start()
        }
    }
}
