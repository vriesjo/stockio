package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationAndSyncResult
import nl.stockio.allocation.domain.api.data.AllocationJobStatus
import nl.stockio.allocation.infra.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.springframework.stereotype.Service

@Service
class AllocationJobService(
    private val storageProvider: StorageProvider,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    fun getStatus(jobId: UUID): AllocationJobStatus {
        val job = storageProvider.getJobById(jobId)
        val result = if (job.state == StateName.SUCCEEDED) {
            resultRepository.findById(jobId)
                .map { objectMapper.readValue(it.resultJson, AllocationAndSyncResult::class.java) }
                .orElse(null)
        } else {
            null
        }
        return AllocationJobStatus(
            jobId = jobId,
            status = job.state.name,
            result = result
        )
    }

    fun deleteJobsWithRetries() {
        val failedJobs = storageProvider.getJobList(StateName.PROCESSING, AmountRequest("updatedAt:ASC", 100)) +
            storageProvider.getJobList(StateName.SCHEDULED, AmountRequest("updatedAt:ASC", 100))
        for (job in failedJobs) {
            // JobRunr records a state history; multiple Failed/Processing transitions indicate retries.
            val retryCount = job.jobStates.count { it.name == StateName.SCHEDULED || it.name == StateName.PROCESSING || it.name == StateName.FAILED }

            if (retryCount > 2) {
                storageProvider.deletePermanently(job.id)
            }
        }
    }
}
